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

package io.evitadb.index.trigram;

import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.evitadb.utils.VMLayout;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;

import static io.evitadb.index.bitmap.RoaringBitmapBackedBitmap.ROARING_HEAP_LAYOUT;

/**
 * The posting of one trigram - the set of {@link io.evitadb.index.invertedIndex.ValueIdAllocator value ids} whose
 * value contains that trigram - in the hybrid representation the corpus measurements picked, together with every
 * operation {@link TrigramIndex} performs on it.
 *
 * # The two representations, and why there are two
 *
 * A posting is either a **sorted `int[]`** of at most {@link #SMALL_POSTING_THRESHOLD} entries, or a
 * {@link PersistentRoaringBitmap} above that. The overwhelming majority of trigram keys post against a handful of
 * values, where a bare `int[]` costs `4n + 16` bytes against a Roaring bitmap's fixed header plus one container -
 * measured at −51% of the whole posting heap on the smaller corpus and −6.4% at the knee on the larger one.
 *
 * The threshold is a flat constant rather than a function of the attribute's size. The crossover is genuinely
 * linear in the number of Roaring containers a posting spans, but the heap curve around it is so flat that one
 * constant lands within 1.7% of every measured per-attribute optimum, and the whole stake is ~4% of the index's
 * heap - not worth any scaling machinery.
 *
 * # A posting is not a Java type
 *
 * Postings are held as bare `Object`, discriminated by `instanceof`, rather than behind a sealed interface with
 * two implementations. A wrapper would add one object header per trigram key - tens of thousands per attribute -
 * and an indirection on the intersection path, to buy type safety inside a package-private structure whose only
 * caller is {@link TrigramIndex}. The same trade is made by the shared value tree's buckets, which discriminate a
 * single-record primitive from a multi-record bitmap the same way.
 *
 * # Mutation contract: nothing here is ever mutated in place
 *
 * Every mutator returns the posting to store and NEVER writes into the one it was given. The array arm gets that
 * for free (an `int[]` cannot grow in place); the bitmap arm buys it with a copy-on-write clone, which costs one
 * pass over the container index and shares every container until one is actually written.
 *
 * That is not a stylistic preference - it is what makes the postings safe to hold in the transactional B+ tree
 * {@link TrigramPostingStore} keeps them in. The tree versions the `trigram -> posting` MAPPING and restores
 * REFERENCES on a savepoint rollback; it has no idea what a posting's content is. A posting mutated in place would
 * therefore be seen changed by every older index version that still shares it, and a savepoint rollback would put
 * the reference back while leaving the content changed. Returning a new object instead makes both impossible.
 *
 * A mutation that changes nothing - re-adding a member, in either representation - returns the very instance it
 * was given and allocates nothing, so the write path's common no-op stays free.
 *
 * ## Why cloning a published bitmap is safe, despite the warning on it
 *
 * {@link PersistentRoaringBitmap} warns that `clone()` must not run concurrently with other access to its source,
 * and a query thread genuinely can be reading version N's posting while this class clones it for N+1. What `clone()`
 * writes on the source is only its copy-on-write ownership metadata - the `shared[]` flags and the frozen mark - and
 * those are read ONLY by mutators (`copyIfShared` before an in-place write, `RoaringArray.defrost()` before a
 * structural one). A pure reader consults neither, so the writes are invisible to it, and by the contract above this
 * code never mutates a published posting in the first place, so the flags protect a mutation that cannot happen.
 * `markAllShared`'s own javadoc anticipates this case by name and picks an idempotent full fill so a flag lost to a
 * race is re-established by the next caller. The same pattern is load-bearing on a hotter path: `BitmapChanges` runs
 * the static `or` over the published bitmap of every transactional bitmap in the database.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class TrigramPostings {

	/**
	 * The largest posting still held as a sorted `int[]`; anything above becomes a {@link PersistentRoaringBitmap}.
	 */
	public static final int SMALL_POSTING_THRESHOLD = 128;

	/**
	 * The cardinality at which a bitmap posting falls back to a sorted `int[]`.
	 *
	 * Deliberately HALF of {@link #SMALL_POSTING_THRESHOLD} rather than equal to it: demoting at the same
	 * cardinality that promotes would make a value id repeatedly added and removed at the boundary rebuild the
	 * representation on every single write. The gap is the hysteresis that makes the representation change at most
	 * once per 64 net memberships.
	 */
	public static final int SMALL_POSTING_DEMOTION_THRESHOLD = SMALL_POSTING_THRESHOLD / 2;

	/**
	 * The posting of a trigram that has lost every value id it ever had - the signal {@link #remove} returns to say
	 * so.
	 *
	 * A real, shared, empty `int[]` rather than `null`, so that a caller reading a posting back never has to
	 * distinguish "absent" from "emptied" before handing it on. {@link TrigramPostingStore} turns it into a real
	 * deletion: the trigram leaves the tree rather than sitting in it under an empty posting.
	 */
	static final int[] EMPTY_POSTING = ArrayUtils.EMPTY_INT_ARRAY;

	private TrigramPostings() {
		throw new UnsupportedOperationException("TrigramPostings is a static utility and must not be instantiated!");
	}

	/**
	 * Adds one value id to a posting.
	 *
	 * @param posting the posting to add to, or `null` when the trigram has none yet
	 * @param valueId the value id to add
	 * @return the posting to store back - possibly a different instance than the one passed in
	 */
	@Nonnull
	public static Object add(@Nullable Object posting, int valueId) {
		if (posting == null) {
			return new int[]{valueId};
		}
		if (posting instanceof final int[] small) {
			final int position = Arrays.binarySearch(small, valueId);
			if (position >= 0) {
				// already a member - re-adding the same value id is a no-op, which is what makes the write path
				// tolerant of a value whose trigram appears more than once
				return small;
			}
			if (small.length + 1 > SMALL_POSTING_THRESHOLD) {
				final PersistentRoaringBitmap promoted = PersistentRoaringBitmap.bitmapOf(small);
				promoted.add(valueId);
				return promoted;
			}
			// the insertion point is what `binarySearch` encodes in its negative return
			return ArrayUtils.insertIntIntoArrayOnIndex(valueId, small, -position - 1);
		}
		final PersistentRoaringBitmap bitmap = (PersistentRoaringBitmap) posting;
		if (bitmap.contains(valueId)) {
			// no-op, and the membership test costs far less than the clone it saves
			return bitmap;
		}
		// copy-on-write: the published posting is shared by reference with every older index version, and the tree
		// holding it restores references rather than content
		final PersistentRoaringBitmap owned = bitmap.clone();
		owned.add(valueId);
		return owned;
	}

	/**
	 * Removes one value id from a posting.
	 *
	 * The value id MUST be a member: a value that is dying contributed this trigram when it was born, so its
	 * absence means the index and the shared value tree have diverged - which would silently under-report on every
	 * later query, and is exactly the failure a defensive premise exists to surface at the moment it happens rather
	 * than at the moment it is noticed.
	 *
	 * @param posting the posting to remove from
	 * @param valueId the value id to remove
	 * @param trigram the packed trigram, named in the refusal so a divergence can be traced
	 * @return the posting to store back - possibly a different instance, and {@link #EMPTY_POSTING} when the
	 * trigram lost its last value id
	 * @throws io.evitadb.exception.GenericEvitaInternalError when the value id is not a member of the posting
	 */
	@Nonnull
	public static Object remove(@Nonnull Object posting, int valueId, long trigram) {
		if (posting instanceof final int[] small) {
			final int position = Arrays.binarySearch(small, valueId);
			Assert.isPremiseValid(
				position >= 0,
				() -> "Value id " + valueId + " is absent from the posting of trigram `" +
					TrigramCodec.toDisplayString(trigram) + "` it contributed to - the trigram index and the " +
					"shared value tree have diverged."
			);
			if (small.length == 1) {
				return EMPTY_POSTING;
			}
			return ArrayUtils.removeIntFromArrayOnIndex(small, position);
		}
		final PersistentRoaringBitmap bitmap = (PersistentRoaringBitmap) posting;
		Assert.isPremiseValid(
			bitmap.contains(valueId),
			() -> "Value id " + valueId + " is absent from the posting of trigram `" +
				TrigramCodec.toDisplayString(trigram) + "` it contributed to - the trigram index and the shared " +
				"value tree have diverged."
		);
		// copy-on-write, for the reason stated on this class: the posting being removed from is published
		final PersistentRoaringBitmap owned = bitmap.clone();
		owned.remove(valueId);
		final int cardinality = owned.getCardinality();
		if (cardinality == 0) {
			return EMPTY_POSTING;
		}
		if (cardinality <= SMALL_POSTING_DEMOTION_THRESHOLD) {
			// back to the compact form, at half the promotion threshold - see SMALL_POSTING_DEMOTION_THRESHOLD
			return owned.toArray();
		}
		return owned;
	}

	/**
	 * @param posting the posting to measure, or `null` when the trigram has none
	 * @return how many value ids the posting holds
	 */
	public static int cardinality(@Nullable Object posting) {
		if (posting == null) {
			return 0;
		}
		return posting instanceof final int[] small
			? small.length : ((PersistentRoaringBitmap) posting).getCardinality();
	}

	/**
	 * Exposes a posting as a {@link Bitmap} for callers outside this package.
	 *
	 * The returned bitmap is a **copy** in the small case and shares the published bitmap in the large one, so it
	 * must be treated as read-only by the caller either way - the index hands out its own postings, and mutating
	 * one from outside would corrupt every index version that shares it.
	 *
	 * @param posting the posting to expose, or `null` when the trigram has none
	 * @return the value ids as a bitmap, {@link EmptyBitmap#INSTANCE} when there are none
	 */
	@Nonnull
	public static Bitmap asBitmap(@Nullable Object posting) {
		if (posting == null) {
			return EmptyBitmap.INSTANCE;
		}
		if (posting instanceof final int[] small) {
			return small.length == 0 ? EmptyBitmap.INSTANCE : new BaseBitmap(small);
		}
		return new BaseBitmap((PersistentRoaringBitmap) posting);
	}

	/**
	 * Returns the heap this posting occupies, in bytes - excluding the reference slot in the store that holds it,
	 * which the store charges itself.
	 *
	 * @param posting the posting to price, or `null` when the trigram has none
	 * @return the owned heap footprint in bytes, including alignment padding
	 */
	public static long heapSizeInBytes(@Nullable Object posting) {
		if (posting == null) {
			return 0L;
		}
		if (posting instanceof final int[] small) {
			// the shared empty array is one instance for the whole JVM that no index owns
			return small.length == 0 ? 0L : VMLayout.current().sizeOfArray(small.length, Integer.BYTES);
		}
		return ((PersistentRoaringBitmap) posting).getHeapSizeInBytes(ROARING_HEAP_LAYOUT);
	}

}
