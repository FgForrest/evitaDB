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

import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.sort.NestedContextSorter;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.ReducedGroupEntityIndex;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.attribute.OwnerFilterIndex;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Real reduced indexes, with representative rank splits and independently populated candidate and owner indexes. */
final class ReferenceAttributeBoundaryTestSupport {
	/** Prevents construction of the support utility. */
	private ReferenceAttributeBoundaryTestSupport() {
	}

	/** Creates an independently owned filter with the same normalization as a production attribute filter. */
	@Nonnull
	static OwnerFilterIndex filter(@Nonnull Class<?> type) {
		return new OwnerFilterIndex(new AttributeIndexKey(null, "share", null), type, 2);
	}

	/** Creates one real representative-attribute partition of a group. */
	@Nonnull
	static ReducedGroupEntityIndex index(int rank) {
		return new ReducedGroupEntityIndex(
			rank + 1, "owner", new EntityIndexKey(
				EntityIndexType.REFERENCED_GROUP_ENTITY, Scope.LIVE,
				new RepresentativeReferenceKey(new ReferenceKey("brand", 1), new Serializable[]{rank})
			)
		);
	}

	/**
	 * Creates the deterministic scalar fixture: one reduced index and one filter index per position, every candidate
	 * at the value 10, and an owner filter in which only owner 100 holds that value - owner 100 counts, owner 200 does
	 * not.
	 *
	 * @param candidates the referenced PKs of each position: the filter index there holds each at the value 10, and
	 *                   the reduced index there tracks each for the uncounted owner 200
	 * @param passing    the referenced PKs the reduced index of each position tracks for the counted owner 100; they
	 *                   need not be candidates of that position
	 * @param scope      the referenced PKs in scope
	 * @return the fixture, without a baseline
	 */
	@Nonnull
	static Fixture fixture(@Nonnull int[][] candidates, @Nonnull int[][] passing, @Nonnull int... scope) {
		final ReducedGroupEntityIndex[] indexes = new ReducedGroupEntityIndex[candidates.length];
		final FilterIndex[] filters = new FilterIndex[candidates.length];
		for (int i = 0; i < candidates.length; i++) {
			indexes[i] = index(i);
			filters[i] = filter(Integer.class);
			for (final int pk : candidates[i]) {
				indexes[i].insertPrimaryKeyIfMissing(200, pk);
				filters[i].addRecord(pk, 10);
			}
			for (final int pk : passing[i]) {
				indexes[i].insertPrimaryKeyIfMissing(100, pk);
			}
		}
		final OwnerFilterIndex owners = filter(Integer.class);
		owners.addRecord(100, 10);
		return new Fixture(filters, indexes, owners, null, new BaseBitmap(scope));
	}

	/** Asserts the real result against both an explicit witness and the independent exhaustive oracle. */
	static void assertPick(@Nonnull Fixture fixture, @Nullable Integer pk, @Nullable Integer alternative) {
		assertEquals(new ReferenceAttributeBoundaryOracle.Pair(pk, alternative), fixture.actual(10, null));
		fixture.compare(10, null, null);
	}

	/**
	 * All inputs to one boundary; filters and indexes remain positionally aligned.
	 *
	 * @param filters  the reference attribute's filter index of each index, `null` where the index has none
	 * @param indexes  the indexes of the boundary context
	 * @param owners   the owner filter telling the owners the histogram counts at a value, `null` to keep every
	 *                 candidate
	 * @param baseline the baseline the counted owners are confined to, `null` for none
	 * @param scope    the referenced PKs in scope
	 */
	record Fixture(
		@Nonnull FilterIndex[] filters,
		@Nonnull EntityIndex[] indexes,
		@Nullable FilterIndex owners,
		@Nullable Formula baseline,
		@Nonnull Bitmap scope
	) {
		/** Calls the actual package-local production picker and exposes its pair. */
		@Nonnull
		ReferenceAttributeBoundaryOracle.Pair actual(
			@Nonnull Serializable value, @Nullable NestedContextSorter sorter
		) {
			final ReferenceHistogramAccumulator.BoundaryPick result =
				ReferenceHistogramAccumulator.pickBoundaryPkFromReferenceAttribute(
					this.filters, this.indexes, value, this.owners == null ? List.of() : List.of(this.owners),
					this.baseline, this.scope, sorter
				);
			return new ReferenceAttributeBoundaryOracle.Pair(result.pk(), result.alternativePk());
		}

		/** Compares fresh equivalent sorter states; callers must never reuse state between production and oracle. */
		void compare(
			@Nonnull Serializable value, @Nullable NestedContextSorter actualSorter,
			@Nullable NestedContextSorter oracleSorter
		) {
			compare(value, actualSorter, oracleSorter, () -> "value=" + value);
		}

		/**
		 * Compares like {@link #compare(Serializable, NestedContextSorter, NestedContextSorter)}, failing with the
		 * given description of the compared inputs, so a generated case can be reproduced from its failure.
		 */
		void compare(
			@Nonnull Serializable value, @Nullable NestedContextSorter actualSorter,
			@Nullable NestedContextSorter oracleSorter, @Nonnull Supplier<String> failureMessage
		) {
			assertEquals(
				ReferenceAttributeBoundaryOracle.pick(
					this.filters, this.indexes, value, this.owners, this.baseline, this.scope, oracleSorter
				), actual(value, actualSorter), failureMessage
			);
		}
	}
}
