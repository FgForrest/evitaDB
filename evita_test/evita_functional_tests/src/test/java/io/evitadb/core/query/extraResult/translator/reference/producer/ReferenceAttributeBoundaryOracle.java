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

package io.evitadb.core.query.extraResult.translator.reference.producer;

import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.sort.NestedContextSorter;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.ReducedGroupEntityIndex;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.PrimitiveIterator;

/**
 * Exhaustive boundary oracle the production pick is compared with. It builds the complete eligible and unrestricted
 * unions before making either pick, so it never stops early, and it states the lookup of the bound in the filter
 * index on its own (see {@link #lookup}), so a divergence there shows up as a different pick - the range threshold
 * test compares every threshold for that reason.
 *
 * It is deliberately not independent of the production sorter handling: it mirrors the production pre-scope
 * cardinality shortcut, the eligible-then-unrestricted sorter call sequence and the empty and singleton sorter
 * bypasses, so that the sorter calls of both can be compared one by one.
 */
final class ReferenceAttributeBoundaryOracle {
	/** Prevents construction of this test utility. */
	private ReferenceAttributeBoundaryOracle() {
	}

	/** Computes both picks using exhaustive, same-producing-index owner witnesses. */
	@Nonnull
	static Pair pick(
		@Nonnull FilterIndex[] filters,
		@Nonnull EntityIndex[] indexes,
		@Nonnull Serializable value,
		@Nullable FilterIndex ownerFilter,
		@Nullable Formula baseline,
		@Nonnull Bitmap scope,
		@Nullable NestedContextSorter sorter
	) {
		PersistentRoaringBitmap owners = null;
		final BaseBitmap unrestricted = new BaseBitmap();
		final BaseBitmap eligible = new BaseBitmap();
		for (int i = 0; i < indexes.length; i++) {
			if (filters[i] == null) {
				continue;
			}
			final Bitmap records = lookup(filters[i], value);
			if (records.isEmpty()) {
				continue;
			}
			final Bitmap mapped;
			if (indexes[i] instanceof ReducedGroupEntityIndex group) {
				mapped = group.getReferencedPrimaryKeysForIndexPks(records);
			} else if (indexes[i] instanceof ReferencedTypeEntityIndex type) {
				mapped = type.getReferencedPrimaryKeysForIndexPks(records);
			} else {
				throw new IllegalArgumentException("Unsupported fixture index");
			}
			if (ownerFilter != null && owners == null) {
				owners = RoaringBitmapBackedBitmap.getRoaringBitmap(lookup(ownerFilter, value));
				if (baseline != null && !owners.isEmpty()) {
					owners = PersistentRoaringBitmap.and(
						owners, RoaringBitmapBackedBitmap.getRoaringBitmap(baseline.compute())
					);
				}
			}
			final PrimitiveIterator.OfInt iterator = mapped.iterator();
			while (iterator.hasNext()) {
				final int pk = iterator.nextInt();
				unrestricted.add(pk);
				if (owners != null) {
					final Bitmap witnesses = ((ReducedGroupEntityIndex) indexes[i]).getOwnerPKsForReferencedEntity(pk);
					if (witnesses != null && PersistentRoaringBitmap.intersects(
						RoaringBitmapBackedBitmap.getRoaringBitmap(witnesses), owners
					)) {
						eligible.add(pk);
					}
				}
			}
		}
		if (owners != null) {
			final Integer primary = first(eligible, scope, sorter);
			if (primary != null) {
				return pair(primary, eligible.size() == unrestricted.size() ?
					null : first(unrestricted, scope, sorter));
			}
		}
		return pair(first(unrestricted, scope, sorter), null);
	}

	/** Resolves scalar equality or closed range membership directly on the filter index. */
	@Nonnull
	static Bitmap lookup(@Nonnull FilterIndex filter, @Nonnull Serializable value) {
		return filter.getRangeIndex() == null ? filter.getRecordsEqualTo(value) :
			filter.getRecordsValidIn(FilterIndex.fromBucketKey(value));
	}

	/** Picks after scope intersection, mirroring the production empty and singleton sorter bypasses. */
	@Nullable
	private static Integer first(
		@Nonnull Bitmap candidates, @Nonnull Bitmap scope, @Nullable NestedContextSorter sorter
	) {
		final PersistentRoaringBitmap intersection = PersistentRoaringBitmap.and(
			RoaringBitmapBackedBitmap.getRoaringBitmap(candidates), RoaringBitmapBackedBitmap.getRoaringBitmap(scope)
		);
		if (intersection.isEmpty()) {
			return null;
		}
		if (intersection.getLongCardinality() > 1 && sorter != null) {
			final int[] sorted = sorter.sortAndSlice(new BaseBitmap(intersection));
			if (sorted.length > 0) {
				return sorted[0];
			}
		}
		return intersection.first();
	}

	/** Normalizes the alternative independently of the production result factory. */
	@Nonnull
	private static Pair pair(@Nullable Integer pk, @Nullable Integer alternative) {
		return new Pair(pk, pk == null || pk.equals(alternative) ? null : alternative);
	}

	/** The observable pair before any entity is fetched. */
	record Pair(@Nullable Integer pk, @Nullable Integer alternativePk) {
	}
}
