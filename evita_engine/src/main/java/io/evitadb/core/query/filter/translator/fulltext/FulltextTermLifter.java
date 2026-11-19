/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.core.query.filter.translator.fulltext;

import com.carrotsearch.hppc.IntIntHashMap;
import com.carrotsearch.hppc.IntLongHashMap;
import com.carrotsearch.hppc.IntObjectHashMap;
import io.evitadb.core.query.algebra.fulltext.FulltextFormula.ExpandedTerm;
import io.evitadb.core.query.filter.translator.fulltext.FulltextPostingsBudget.PreparedTerm;
import io.evitadb.index.bPlusTree.ImpactView;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import net.openhft.hashing.LongHashFunction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.PrimitiveIterator.OfInt;
import java.util.function.IntFunction;

/**
 * Query-local one-level projection from target postings to owners. One instance belongs to a reference and owner
 * scope and is shared across every target scope and analyzer group. Its primitive target memo does one partition
 * lookup per distinct target, including empty and virtual partitions. Every visited owner edge is priced before
 * unioning it; impact maxima are computed only when a scorer opens the deferred view.
 */
final class FulltextTermLifter {
	/** Owner postings of every target already visited, including targets without owners. */
	private final IntObjectHashMap<Bitmap> ownersByTarget = new IntObjectHashMap<>(16);
	/** Content hashes of the same owner postings, computed once per distinct target. */
	private final IntLongHashMap ownerHashesByTarget = new IntLongHashMap(16);
	/** Partition lookup in the owner's scope. */
	@Nonnull private final IntFunction<Bitmap> ownerLookup;
	/** Successful lifts reused when the completion gate reprices a query. */
	private final Map<ExpandedTerm, PreparedTerm> prepared = new IdentityHashMap<>();

	/**
	 * Creates a lifter for one reference in one owner scope.
	 * @param ownerLookup resolves all materialized or virtual owner partitions of a target
	 */
	FulltextTermLifter(@Nonnull IntFunction<Bitmap> ownerLookup) {
		this.ownerLookup = ownerLookup;
	}

	/**
	 * Lifts a term, stopping before an inexact expansion exceeds its remaining work allowance.
	 * @param term target-space expansion with the composed weight already attached
	 * @param placeId query-local identity shared across target scopes for this reference and target place
	 * @param remaining inclusive allowance; exact terms are uncapped
	 * @return lifted expansion and target-plus-owner work, or null on overflow
	 */
	@Nullable
	PreparedTerm prepare(@Nonnull ExpandedTerm term, int placeId, long remaining) {
		final PreparedTerm memoized = this.prepared.get(term);
		if (memoized != null) {
			return memoized.cost() <= remaining ? memoized : null;
		}
		long cost = term.postings().size();
		if (cost > remaining) {
			return null;
		}
		final BaseBitmap postings = new BaseBitmap();
		long provenance = term.postings().getContentHash(LongHashFunction.xx3()) ^
			Integer.toUnsignedLong(System.identityHashCode(term.impacts()));
		final OfInt targets = term.postings().iterator();
		while (targets.hasNext()) {
			final int target = targets.nextInt();
			final Bitmap owners = ownersOf(target);
			cost += owners.size();
			if (cost > remaining) {
				return null;
			}
			postings.addAll(owners);
			provenance = 31 * provenance + target;
			provenance = 31 * provenance + this.ownerHashesByTarget.get(target);
		}
		final ImpactView impacts = ImpactView.deferred(postings.size(), () -> impacts(term, postings));
		final PreparedTerm result = new PreparedTerm(new ExpandedTerm(
			placeId, term.term(), postings, impacts, term.distance(), term.exactness(), placeId, term.weight(),
			provenance == 0L ? 1L : provenance), cost);
		this.prepared.put(term, result);
		return result;
	}

	/**
	 * Resolves a target once, retaining empty answers as well. HPPC supports every signed int key, including zero.
	 * @param target target primary key
	 * @return owners in this lifter's owner scope
	 */
	@Nonnull
	private Bitmap ownersOf(int target) {
		final Bitmap cached = this.ownersByTarget.get(target);
		if (cached != null) {
			return cached;
		}
		final Bitmap owners = this.ownerLookup.apply(target);
		this.ownersByTarget.put(target, owners);
		this.ownerHashesByTarget.put(target, owners.getContentHash(LongHashFunction.xx3()));
		return owners;
	}

	/**
	 * Aggregates the maximum unsigned raw impact per owner, in the lifted posting order.
	 * @param term original target expansion
	 * @param postings lifted owner postings
	 * @return aligned impact maxima, computed only for a scoring query
	 */
	@Nonnull
	private byte[] impacts(@Nonnull ExpandedTerm term, @Nonnull Bitmap postings) {
		final IntIntHashMap maxima = new IntIntHashMap(postings.size());
		final ImpactView.Reader reader = term.impacts().reader();
		final OfInt targets = term.postings().iterator();
		int offset = 0;
		while (targets.hasNext()) {
			final Bitmap owners = this.ownersByTarget.get(targets.nextInt());
			final int impact = Byte.toUnsignedInt(reader.impactAt(offset++));
			final OfInt iterator = owners.iterator();
			while (iterator.hasNext()) {
				final int owner = iterator.nextInt();
				if (impact > maxima.get(owner)) {
					maxima.put(owner, impact);
				}
			}
		}
		final byte[] result = new byte[postings.size()];
		final OfInt owners = postings.iterator();
		int index = 0;
		while (owners.hasNext()) {
			result[index++] = (byte) maxima.get(owners.nextInt());
		}
		return result;
	}

}
