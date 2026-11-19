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

import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.core.cache.NoCacheSupervisor;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.query.QueryPlanningContext;
import io.evitadb.core.query.extraResult.translator.reference.producer.ReferenceAttributeBoundaryTestSupport.Fixture;
import io.evitadb.core.query.sort.NestedContextSorter;
import io.evitadb.core.query.sort.NoSorter;
import io.evitadb.core.query.sort.Sorter;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.SortedRecordsProvider;
import io.evitadb.core.query.sort.attribute.sorter.MergedComparableSortedRecordsSupplierSorter;
import io.evitadb.core.query.sort.primaryKey.sorter.ReversedPrimaryKeySorter;
import io.evitadb.core.query.sort.random.sorter.RandomSorter;
import io.evitadb.core.query.sort.segment.sorter.SegmentSorter;
import io.evitadb.index.attribute.SortedRecordsSupplier;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.core.query.extraResult.translator.reference.producer.ReferenceAttributeBoundaryTestSupport.*;
import static io.evitadb.test.TestTags.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Exercises the real attribute, primary-key, segmented and seeded-random sorters on exhaustive eligible subsets. */
@DisplayName("Reference attribute boundary pick with a sorter")
@Tag(ENGINE)
@Tag(QUERY)
@Tag(HISTOGRAM)
@Tag(REFERENCE)
class ReferenceAttributeBoundarySorterTest {

	/** Attribute sorters, alone and as a limited segment. */
	@Nested
	@DisplayName("Attribute sorters")
	class AttributeSorters {

		/** Ties use the attribute direction's PK order; missing values pass to bitmap completion. */
		@Test
		@DisplayName("should keep both attribute orders with ties and with records missing the value")
		void shouldPreserveAttributeOrdersWithTiesAndMixedMissingValues() {
			final Fixture fixture = fixture(new int[][]{{1, 2, 3, 4, 5}}, new int[][]{{2, 3, 4}}, 1, 2, 3, 4, 5);
			check(fixture, () -> List.of(attribute(OrderDirection.ASC, 1, 5, 2, 5, 5, 2)), 2, 5);
			check(fixture, () -> List.of(attribute(OrderDirection.DESC, 1, 5, 2, 5, 5, 2)), 2, null);
			check(fixture, () -> List.of(attribute(OrderDirection.ASC)), 2, 1);
			check(fixture, () -> List.of(attribute(OrderDirection.DESC)), 2, 1);
		}

		/** Removing an unowned tied record changes which record enters the limited first segment. */
		@Test
		@DisplayName("should sort the owned subset itself for a limited attribute segment")
		void shouldSortTheEligibleSubsetForLimitedAttributeSegment() {
			final Fixture fixture = fixture(new int[][]{{1, 2, 3, 4}}, new int[][]{{2, 3, 4}}, 1, 2, 3, 4);
			check(fixture, () -> List.of(
				new SegmentSorter(List.of(attribute(OrderDirection.ASC, 1, 5, 2, 5)), null, 1),
				ReversedPrimaryKeySorter.INSTANCE
			), 2, 1);
		}
	}

	/** Primary key sorters, alone and as a limited segment. */
	@Nested
	@DisplayName("Primary key sorters")
	class PrimaryKeySorters {

		/** Non-null PK sorters also retain exhaustive selection and alternative handling. */
		@Test
		@DisplayName("should keep both primary key orders")
		void shouldPreserveBothPrimaryKeyOrders() {
			final Fixture fixture = fixture(new int[][]{{1, 2, 3, 4}}, new int[][]{{2, 3}}, 1, 2, 3, 4);
			check(fixture, () -> List.of(NoSorter.INSTANCE), 2, 1);
			check(fixture, () -> List.of(ReversedPrimaryKeySorter.INSTANCE), 3, 4);
		}

		/** A limited ascending PK segment followed by descending completion is also sensitive to subset removal. */
		@Test
		@DisplayName("should sort the owned subset itself for a limited primary key segment")
		void shouldSortTheEligibleSubsetForLimitedPrimaryKeySegment() {
			final Fixture fixture = fixture(new int[][]{{1, 2, 3}}, new int[][]{{2, 3}}, 1, 2, 3);
			check(fixture, () -> List.of(
				new SegmentSorter(List.of(NoSorter.INSTANCE), null, 1), ReversedPrimaryKeySorter.INSTANCE
			), 2, 1);
		}
	}

	/** The seeded random sorter. */
	@Nested
	@DisplayName("Random sorter")
	class RandomSorters {

		/** Seeded shuffles depend on input length, so fresh equivalent contexts must sort both subsets separately. */
		@Test
		@DisplayName("should pick the pair of a seeded shuffle of each set")
		void shouldPreserveSeededRandomPair() {
			final Fixture fixture = fixture(new int[][]{{1, 2, 3}}, new int[][]{{1, 2}}, 1, 2, 3);
			check(fixture, () -> List.of(new RandomSorter(0L)), 2, 1);
		}
	}

	/** Which sets reach the sorter, in which order, and which sorts are skipped. */
	@Nested
	@DisplayName("Sorter calls and bypasses")
	class SorterCallsAndBypasses {

		/**
		 * Equal raw cardinality of the owned and of every candidate skips the alternative sort. The seeded shuffle
		 * decides the pick, so only the oracle states it.
		 */
		@Test
		@DisplayName("should skip the alternative sort when every candidate is owned")
		void shouldSkipTheAlternativeSortWhenEveryCandidateIsOwned() {
			checkAgainstOracle(fixture(new int[][]{{1, 2}, {3}}, new int[][]{{1, 2}, {3}}, 1, 2, 3),
				() -> List.of(new RandomSorter(11L)));
		}

		/** A single owned candidate in scope is picked without a sort, while every candidate is still sorted. */
		@Test
		@DisplayName("should skip the sort of a single owned candidate and sort every candidate")
		void shouldSkipTheSortOfASingleOwnedCandidate() {
			check(fixture(new int[][]{{1, 2, 3}}, new int[][]{{2}}, 1, 2, 3),
				() -> List.of(NoSorter.INSTANCE), 2, 1);
		}

		/** A scope leaving a single candidate skips every sort. */
		@Test
		@DisplayName("should skip every sort when the scope leaves a single candidate")
		void shouldSkipEverySortWhenTheScopeLeavesASingleCandidate() {
			check(fixture(new int[][]{{1, 2, 3}}, new int[][]{{2}}, 2),
				() -> List.of(new RandomSorter(11L)), 2, null);
		}

		/** An empty scope leaves nothing to sort and no pick. */
		@Test
		@DisplayName("should return no pick when no candidate is in scope")
		void shouldReturnNoPickWhenNoCandidateIsInScope() {
			check(fixture(new int[][]{{1, 2, 3}}, new int[][]{{2}}),
				() -> List.of(new RandomSorter(11L)), null, null);
		}

		/**
		 * Without an owned candidate the bound sorts every candidate. The seeded shuffle decides the pick, so only the
		 * oracle states it.
		 */
		@Test
		@DisplayName("should sort every candidate when no owner check passes")
		void shouldSortEveryCandidateWhenNoOwnerCheckPasses() {
			checkAgainstOracle(fixture(new int[][]{{1, 2, 3}}, new int[][]{{}}, 1, 2, 3),
				() -> List.of(new RandomSorter(11L)));
		}

		/** The raw-cardinality shortcut compares the sets before the scope narrows them. */
		@Test
		@DisplayName("should sort the alternative when an out-of-scope candidate is not owned")
		void shouldSortTheAlternativeWhenAnOutOfScopeCandidateIsNotOwned() {
			// candidate 3 is out of scope and not owned, yet it makes the raw owned and unrestricted sets differ in
			// size, so the alternative is sorted although both scoped inputs are equal
			final Fixture fixture = fixture(new int[][]{{1, 2, 3}}, new int[][]{{1, 2}}, 1, 2);
			final RecordingSorter sorter = recording(List.of(NoSorter.INSTANCE));

			fixture.actual(10, sorter);

			assertEquals(List.of("[1, 2]", "[1, 2]"), sorter.inputs);
		}
	}

	/**
	 * Compares the pair and the full sorter invocation trace with the oracle, using independently constructed sorter
	 * contexts, and asserts the expected pair explicitly - `null` and `null` meaning no pick.
	 */
	private static void check(
		@Nonnull Fixture fixture, @Nonnull Supplier<List<Sorter>> factory,
		@Nullable Integer pk, @Nullable Integer alternative
	) {
		checkAgainstOracle(fixture, factory);
		assertEquals(new ReferenceAttributeBoundaryOracle.Pair(pk, alternative), fixture.actual(10,
			recording(factory.get())));
	}

	/**
	 * Compares the pair and the full sorter invocation trace with the oracle, using independently constructed sorter
	 * contexts, for pairs only the oracle states.
	 */
	private static void checkAgainstOracle(@Nonnull Fixture fixture, @Nonnull Supplier<List<Sorter>> factory) {
		final RecordingSorter actual = recording(factory.get());
		final RecordingSorter oracle = recording(factory.get());
		fixture.compare(10, actual, oracle);
		assertEquals(oracle.inputs, actual.inputs);
	}

	/**
	 * Creates an execution context with genuine buffers and index sorter machinery. The catalog is the only mock: a
	 * real one cannot be built without a running engine, and the planning context only stores it - the sorters order
	 * the records they are handed without reading it.
	 */
	@Nonnull
	private static RecordingSorter recording(@Nonnull List<Sorter> sorters) {
		final QueryPlanningContext planning = new QueryPlanningContext(
			mock(Catalog.class), null, null,
			new EvitaRequest(query(collection("owner")), OffsetDateTime.now(), EntityReference.class, null),
			Map.of(), Map.of(), NoCacheSupervisor.INSTANCE
		);
		return new RecordingSorter(planning, sorters);
	}

	/** Builds an attribute provider ordered by value then PK, consumed by the actual merged attribute sorter. */
	@Nonnull
	private static Sorter attribute(@Nonnull OrderDirection direction, @Nonnull int... recordValues) {
		final List<int[]> pairs = new ArrayList<>(recordValues.length / 2);
		for (int i = 0; i < recordValues.length; i += 2) {
			pairs.add(new int[]{recordValues[i], recordValues[i + 1]});
		}
		final Comparator<int[]> ascending = Comparator.<int[]>comparingInt(pair -> pair[1])
			.thenComparingInt(pair -> pair[0]);
		pairs.sort(direction == OrderDirection.ASC ? ascending : ascending.reversed());
		final int[] records = new int[pairs.size()];
		final Integer[] values = new Integer[pairs.size()];
		for (int i = 0; i < pairs.size(); i++) {
			records[i] = pairs.get(i)[0];
			values[i] = pairs.get(i)[1];
		}
		final int[] ascendingRecords = records.clone();
		Arrays.sort(ascendingRecords);
		final int[] positions = new int[records.length];
		for (int i = 0; i < ascendingRecords.length; i++) {
			for (int j = 0; j < records.length; j++) {
				if (ascendingRecords[i] == records[j]) {
					positions[i] = j;
				}
			}
		}
		final SortedRecordsProvider provider = new SortedRecordsSupplier(
			1L, records, positions, new BaseBitmap(ascendingRecords), position -> values[position]
		);
		return new MergedComparableSortedRecordsSupplierSorter(
			direction == OrderDirection.ASC ? Comparator.<Integer>naturalOrder() : Comparator.<Integer>reverseOrder(),
			direction, new SortedRecordsProvider[]{provider}
		);
	}

	/** Records sorter inputs while delegating all ordering to real production sorters. */
	private static final class RecordingSorter extends NestedContextSorter {
		/** The records of every call in call order, each rendered as its sorted primary key array. */
		private final List<String> inputs = new ArrayList<>();

		/** Creates a fresh context for one side of the differential. */
		private RecordingSorter(@Nonnull QueryPlanningContext planning, @Nonnull List<Sorter> sorters) {
			super(planning.createExecutionContext(), () -> "boundary ordering", sorters);
		}

		/** Captures the exact input and call order, including calls whose first result normalizes away. */
		@Nonnull
		@Override
		public int[] sortAndSlice(@Nonnull Bitmap records) {
			this.inputs.add(Arrays.toString(records.getArray()));
			return super.sortAndSlice(records);
		}
	}
}
