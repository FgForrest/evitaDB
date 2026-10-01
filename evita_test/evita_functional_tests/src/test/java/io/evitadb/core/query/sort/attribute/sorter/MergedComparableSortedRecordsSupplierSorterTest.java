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

package io.evitadb.core.query.sort.attribute.sorter;

import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.core.cache.NoCacheSupervisor;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.QueryPlanningContext;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.SortedRecordsProvider;
import io.evitadb.core.query.sort.Sorter.SortingContext;
import io.evitadb.index.attribute.SortedRecordsSupplier;
import io.evitadb.index.bitmap.BaseBitmap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.ORDER;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Verifies how {@link MergedComparableSortedRecordsSupplierSorter} sorts a selection over several providers when at
 * most one of them holds selected records: the records of the only contributing provider are emitted straight from
 * its position mask, sliced to the requested page, without reading a single value, and the records no provider holds
 * are handed to the next sorter. Two contributing providers are merged by value as before.
 *
 * Fixture - rows `(record, value)`, the providers in this array order unless a test says otherwise:
 *
 * - provider A: (1, 10) (2, 20)
 * - provider B: (3, 5) (4, 15)
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Merged comparable sorter with at most one contributing provider")
@Tag(ENGINE)
@Tag(ORDER)
class MergedComparableSortedRecordsSupplierSorterTest {
	/**
	 * The planning context of a query over the records, built without a session the way an index-only evaluation is.
	 * Its catalog is the only mock: {@link Catalog} cannot be built outside a running engine, and the sorter never
	 * reads it.
	 */
	private QueryPlanningContext planningContext;
	/**
	 * How many times any provider's value seeker was asked for a value - the work a merge by value pays per record.
	 */
	private int valueReads;

	@BeforeEach
	void setUp() {
		this.planningContext = new QueryPlanningContext(
			mock(Catalog.class),
			null,
			null,
			new EvitaRequest(query(collection("owner")), OffsetDateTime.now(), EntityReference.class, null),
			Map.of(),
			Map.of(),
			NoCacheSupervisor.INSTANCE
		);
		this.valueReads = 0;
	}

	@Test
	@DisplayName("should emit the records of the only provider holding the selection, though not the first one, without reading a value")
	void shouldEmitOnlyContributingLaterProviderWithoutReadingValues() {
		final SortOutcome outcome = sort(createProviders(OrderDirection.ASC), OrderDirection.ASC, 0, 10, 3, 4);

		assertAll(
			() -> assertArrayEquals(new int[]{3, 4}, outcome.written()),
			() -> assertArrayEquals(new int[0], outcome.remaining()),
			() -> assertEquals(0, this.valueReads, "no value may be read when a single provider holds the selection")
		);
	}

	@Test
	@DisplayName("should pass on every record when no provider holds any of them")
	void shouldPassOnEverythingWhenNoProviderHoldsSelection() {
		final SortOutcome outcome = sort(createProviders(OrderDirection.ASC), OrderDirection.ASC, 1, 10, 7, 8);

		assertAll(
			() -> assertArrayEquals(new int[0], outcome.written()),
			() -> assertArrayEquals(new int[0], outcome.skipped()),
			() -> assertArrayEquals(new int[]{7, 8}, outcome.remaining()),
			() -> assertEquals(0, outcome.skippedCount()),
			() -> assertEquals(0, this.valueReads, "no value may be read when no provider holds the selection")
		);
	}

	@Test
	@DisplayName("should slice the only contributing provider, report the skipped records and hand on the records no provider holds")
	void shouldSliceSingleContributorAndHandOnRecordsNoProviderHolds() {
		// provider B holds 3(5) 4(15), record 9 is held by nobody; the page starts at the second sorted record
		final SortOutcome outcome = sort(createProviders(OrderDirection.ASC), OrderDirection.ASC, 1, 3, 3, 4, 9);

		assertAll(
			() -> assertArrayEquals(new int[]{3}, outcome.skipped()),
			() -> assertEquals(1, outcome.skippedCount()),
			() -> assertArrayEquals(new int[]{4}, outcome.written()),
			() -> assertArrayEquals(new int[]{9}, outcome.remaining()),
			() -> assertEquals(0, this.valueReads, "no value may be read when a single provider holds the selection")
		);
	}

	@Test
	@DisplayName("should emit the first provider alone when a later provider resolves an empty mask")
	void shouldEmitFirstProviderAloneWhenLaterOneResolvesEmpty() {
		// record 9 is still unsorted after provider A, so provider B is asked too and resolves nothing
		final SortOutcome outcome = sort(createProviders(OrderDirection.ASC), OrderDirection.ASC, 0, 10, 1, 9);

		assertAll(
			() -> assertArrayEquals(new int[]{1}, outcome.written()),
			() -> assertArrayEquals(new int[]{9}, outcome.remaining()),
			() -> assertEquals(0, this.valueReads, "no value may be read when a single provider holds the selection")
		);
	}

	@Test
	@DisplayName("should merge by value, reading the values, when two providers hold selected records")
	void shouldMergeByValueWhenTwoProvidersContribute() {
		final SortOutcome ascending = sort(createProviders(OrderDirection.ASC), OrderDirection.ASC, 0, 10, 1, 3);
		final int ascendingReads = this.valueReads;
		final SortOutcome descending = sort(createProviders(OrderDirection.DESC), OrderDirection.DESC, 0, 10, 1, 3);

		assertAll(
			() -> assertArrayEquals(new int[]{3, 1}, ascending.written(), "ascending"),
			() -> assertTrue(ascendingReads > 0, "the merge of two contributing providers reads their values"),
			() -> assertArrayEquals(new int[]{1, 3}, descending.written(), "descending")
		);
	}

	@Test
	@DisplayName("should emit a descending provider's records in its own order when it is the only one holding the selection")
	void shouldEmitDescendingSingleContributorStraightFromMask() {
		// provider B holds a tie of 4 and 5 on value 15, which a descending order breaks by the higher primary key
		final SortedRecordsProvider[] providers = {
			createProvider(1, OrderDirection.DESC, 1, 10, 2, 20),
			createProvider(2, OrderDirection.DESC, 3, 5, 4, 15, 5, 15)
		};
		final SortOutcome outcome = sort(providers, OrderDirection.DESC, 0, 10, 3, 4, 5);

		assertAll(
			() -> assertArrayEquals(new int[]{5, 4, 3}, outcome.written()),
			() -> assertEquals(0, this.valueReads, "no value may be read when a single provider holds the selection")
		);
	}

	/**
	 * Runs the sorter over the providers with the comparator of the direction and returns what it wrote, skipped and
	 * left unsorted.
	 *
	 * @param providers  the providers in the order in which they claim records
	 * @param direction  the ordering direction
	 * @param startIndex the first requested position
	 * @param endIndex   the position after the last requested one
	 * @param selection  the records to sort
	 * @return the outcome of the sort
	 */
	@Nonnull
	private SortOutcome sort(
		@Nonnull SortedRecordsProvider[] providers,
		@Nonnull OrderDirection direction,
		int startIndex,
		int endIndex,
		@Nonnull int... selection
	) {
		final MergedComparableSortedRecordsSupplierSorter sorter = new MergedComparableSortedRecordsSupplierSorter(
			direction == OrderDirection.ASC ? Comparator.<Integer>naturalOrder() : Comparator.<Integer>reverseOrder(),
			direction,
			providers
		);
		final QueryExecutionContext executionContext = this.planningContext.createExecutionContext();
		final int[] result = new int[endIndex - startIndex];
		final List<Integer> skipped = new ArrayList<>(4);
		final SortingContext output = sorter.sortAndSlice(
			new SortingContext(executionContext, new BaseBitmap(selection), startIndex, endIndex, 0, 0),
			result,
			skipped::add
		);
		return new SortOutcome(
			Arrays.copyOf(result, output.peak()),
			skipped.stream().mapToInt(Integer::intValue).toArray(),
			output.nonSortedKeys().getArray(),
			output.skipped()
		);
	}

	/**
	 * Creates providers A and B of the fixture laid out in the direction.
	 *
	 * @param direction the ordering direction
	 * @return the providers in the order A, B
	 */
	@Nonnull
	private SortedRecordsProvider[] createProviders(@Nonnull OrderDirection direction) {
		return new SortedRecordsProvider[]{
			createProvider(1, direction, 1, 10, 2, 20),
			createProvider(2, direction, 3, 5, 4, 15)
		};
	}

	/**
	 * Creates a provider holding records with explicit values, laid out as a real sort index lays them out: in the
	 * order of the values in the ordering direction, and records with equal values by their primary key in the same
	 * direction. Its value seeker counts its reads in {@link #valueReads}.
	 *
	 * @param id               identity of the provider
	 * @param direction        the ordering direction
	 * @param recordValuePairs pairs of record primary key and value
	 * @return the provider
	 */
	@Nonnull
	private SortedRecordsProvider createProvider(
		long id,
		@Nonnull OrderDirection direction,
		@Nonnull int... recordValuePairs
	) {
		final List<int[]> pairs = new ArrayList<>(recordValuePairs.length / 2);
		for (int i = 0; i < recordValuePairs.length; i += 2) {
			pairs.add(new int[]{recordValuePairs[i], recordValuePairs[i + 1]});
		}
		final Comparator<int[]> ascending = Comparator.<int[]>comparingInt(it -> it[1]).thenComparingInt(it -> it[0]);
		pairs.sort(direction == OrderDirection.ASC ? ascending : ascending.reversed());
		final int[] sortedRecords = pairs.stream().mapToInt(it -> it[0]).toArray();
		final Integer[] values = pairs.stream().map(it -> it[1]).toArray(Integer[]::new);
		final int[] recordsAscending = Arrays.stream(sortedRecords).sorted().toArray();
		// the position of each record, in ascending record order, within the sorted array
		final int[] positions = new int[recordsAscending.length];
		for (int i = 0; i < recordsAscending.length; i++) {
			for (int j = 0; j < sortedRecords.length; j++) {
				if (sortedRecords[j] == recordsAscending[i]) {
					positions[i] = j;
				}
			}
		}
		return new SortedRecordsSupplier(
			id, sortedRecords, positions, new BaseBitmap(recordsAscending),
			position -> {
				this.valueReads++;
				return values[position];
			}
		);
	}

	/**
	 * What one run of the sorter produced.
	 *
	 * @param written      the records the sorter wrote, in order
	 * @param skipped      the records it reported as skipped before the requested page, in order
	 * @param remaining    the records it handed to the next sorter
	 * @param skippedCount the number of skipped records the output context accounts for
	 */
	private record SortOutcome(
		@Nonnull int[] written,
		@Nonnull int[] skipped,
		@Nonnull int[] remaining,
		int skippedCount
	) {
	}

}
