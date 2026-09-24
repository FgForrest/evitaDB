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

package io.evitadb.core.query.sort.reference.sorter;

import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.QueryPlanningContext;
import io.evitadb.core.query.SharedBufferPool;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.SortedRecordsProvider;
import io.evitadb.core.query.sort.Sorter.SortingContext;
import io.evitadb.dataType.Scope;
import io.evitadb.index.ReducedEntityIndex;
import io.evitadb.index.attribute.SortedRecordsSupplier;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.IntStream;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.ORDER;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies the index route of a `pickFirst` reference ordering, {@link PickFirstReferenceSorter}, around the page and
 * the sorter chain: skipping, writing after a preceding sorter, stopping inside the claimed block, and handing the
 * unclaimed owners on - over real reduced indexes and real sorted-records providers.
 *
 * The fixture mirrors the hand-built dataset of the functional pick-first test (targets ordered by primary key
 * ascending, index primary key = 100 + target):
 *
 * | index | target | owner: value           |
 * |-------|--------|------------------------|
 * | 101   | 1      | 7: 1, 8: 60            |
 * | 102   | 2      | 1: -, 3: 50            |
 * | 103   | 3      | 2: 20, 3: 10, 9: 20, 10: 70 |
 * | 104   | 4      | 1: 30, 7: 40           |
 * | 105   | 5      | 4: -                   |
 * | 106   | 6      | 6: 20                  |
 *
 * Owner 5 has no row at all. Ascending, the owners are claimed as 7 (1), 2, 6, 9 (20), 1 (30), 3 (50), 8 (60) and
 * 10 (70), and owners 4 and 5 stay unclaimed.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Pick-first reference sorter")
@Tag(ENGINE)
@Tag(ORDER)
class PickFirstReferenceSorterTest {
	/**
	 * The claimed owners in ascending order of their values.
	 */
	private static final int[] CLAIMED_ASCENDING = {7, 2, 6, 9, 1, 3, 8, 10};
	/**
	 * The owners no index claims.
	 */
	private static final int[] UNCLAIMED = {4, 5};
	/**
	 * Value marking an owner held by an index that has no value for it.
	 */
	private static final int NO_VALUE = Integer.MIN_VALUE;

	/**
	 * Creates the execution context the sorter runs in, `prefetched` deciding whether entity bodies were prefetched.
	 */
	@Nonnull
	private static QueryExecutionContext createExecutionContext(boolean prefetched) {
		final QueryExecutionContext executionContext = mock(QueryExecutionContext.class);
		// the sorter reads the planning context for the debug sort override / telemetry; the unstubbed mock returns
		// false for isDebugModeEnabled and null for getCurrentStep -> cost-based selection with telemetry off
		when(executionContext.getQueryContext()).thenReturn(mock(QueryPlanningContext.class));
		when(executionContext.getPrefetchedEntities()).thenReturn(prefetched ? List.of() : null);
		doAnswer(invocation -> SharedBufferPool.INSTANCE.obtain()).when(executionContext).borrowBuffer();
		doNothing().when(executionContext).returnBuffer(any());
		return executionContext;
	}

	/**
	 * Creates a provider holding the passed owners with their values, in ascending order of the values - the
	 * construction `EntityPrimaryKeyNaturalTranslator` uses for its own per-index providers. Owners marked with
	 * {@link #NO_VALUE} are held by the index but not by the provider, exactly like a row lacking the attribute.
	 *
	 * @param index        the index the provider belongs to
	 * @param ownerValues  pairs of owner primary key and value
	 * @return the provider, or `null` when no owner has a value
	 */
	@Nullable
	private static SortedRecordsSupplier createProvider(@Nonnull ReducedEntityIndex index, @Nonnull int... ownerValues) {
		final List<int[]> pairs = new ArrayList<>(ownerValues.length / 2);
		for (int i = 0; i < ownerValues.length; i += 2) {
			if (ownerValues[i + 1] != NO_VALUE) {
				pairs.add(new int[]{ownerValues[i], ownerValues[i + 1]});
			}
		}
		if (pairs.isEmpty()) {
			return null;
		}
		pairs.sort(Comparator.<int[]>comparingInt(it -> it[1]).thenComparingInt(it -> it[0]));
		final int[] sortedOwners = pairs.stream().mapToInt(it -> it[0]).toArray();
		final Integer[] values = pairs.stream().map(it -> it[1]).toArray(Integer[]::new);
		final int[] ownersAscending = Arrays.stream(sortedOwners).sorted().toArray();
		final int[] positions = new int[ownersAscending.length];
		for (int i = 0; i < ownersAscending.length; i++) {
			for (int j = 0; j < sortedOwners.length; j++) {
				if (sortedOwners[j] == ownersAscending[i]) {
					positions[i] = j;
				}
			}
		}
		return new SortedRecordsSupplier(
			index.getPrimaryKey(), sortedOwners, positions, new BaseBitmap(ownersAscending),
			position -> values[position]
		);
	}

	/**
	 * Runs the sorter and returns what it wrote into the result array.
	 */
	@Nonnull
	private static SortResult sort(
		@Nonnull PickFirstReferenceSorter sorter,
		@Nonnull SortingContext input,
		int resultSize
	) {
		final int[] result = new int[resultSize];
		Arrays.fill(result, -1);
		final List<Integer> skipped = new ArrayList<>();
		final SortingContext output = sorter.sortAndSlice(input, result, skipped::add);
		return new SortResult(
			output,
			Arrays.copyOfRange(result, input.peak(), output.peak()),
			result,
			skipped.stream().mapToInt(Integer::intValue).toArray()
		);
	}

	/**
	 * Returns the content of the bitmap.
	 */
	@Nonnull
	private static int[] content(@Nonnull Bitmap bitmap) {
		return bitmap.getArray();
	}

	@Test
	@DisplayName("should skip the leading claimed owners and report them to the skipped records consumer")
	void shouldSkipLeadingClaimedOwnersAndReportThem() {
		final WitnessFixture fixture = new WitnessFixture();

		final SortResult sorted = sort(
			fixture.sorter(OrderDirection.ASC),
			new SortingContext(createExecutionContext(false), fixture.allOwners(), 2, 5, 0, 0),
			5
		);

		assertAll(
			() -> assertArrayEquals(new int[]{7, 2}, sorted.skipped()),
			() -> assertArrayEquals(new int[]{6, 9, 1}, sorted.written()),
			() -> assertEquals(2, sorted.output().skipped()),
			() -> assertEquals(3, sorted.output().peak()),
			() -> assertArrayEquals(UNCLAIMED, content(sorted.output().nonSortedKeys()))
		);
	}

	@Test
	@DisplayName("should write after the records a preceding sorter already wrote")
	void shouldWriteAfterExistingPeak() {
		final WitnessFixture fixture = new WitnessFixture();

		// a preceding sorter wrote three other records and left these ten
		final SortResult sorted = sort(
			fixture.sorter(OrderDirection.ASC),
			new SortingContext(createExecutionContext(false), fixture.allOwners(), 0, 13, 3, 0),
			13
		);

		assertAll(
			() -> assertArrayEquals(new int[]{-1, -1, -1}, Arrays.copyOfRange(sorted.result(), 0, 3)),
			() -> assertArrayEquals(CLAIMED_ASCENDING, sorted.written()),
			() -> assertEquals(3 + CLAIMED_ASCENDING.length, sorted.output().peak()),
			() -> assertArrayEquals(UNCLAIMED, content(sorted.output().nonSortedKeys()))
		);
	}

	@Test
	@DisplayName("should stop at the end of the page inside the claimed block and hand on only unclaimed owners")
	void shouldStopAtPageEndInsideClaimedBlock() {
		final WitnessFixture fixture = new WitnessFixture();

		final SortResult sorted = sort(
			fixture.sorter(OrderDirection.ASC),
			new SortingContext(createExecutionContext(false), fixture.allOwners(), 0, 3, 0, 0),
			3
		);

		assertAll(
			() -> assertArrayEquals(new int[]{7, 2, 6}, sorted.written()),
			() -> assertArrayEquals(new int[0], sorted.skipped()),
			() -> assertArrayEquals(UNCLAIMED, content(sorted.output().nonSortedKeys()))
		);
	}

	@Test
	@DisplayName("should skip past every claimed owner when the page starts after them")
	void shouldSkipPastAllClaimedOwners() {
		final WitnessFixture fixture = new WitnessFixture();

		final SortResult sorted = sort(
			fixture.sorter(OrderDirection.ASC),
			new SortingContext(createExecutionContext(false), fixture.allOwners(), 9, 10, 0, 0),
			1
		);

		assertAll(
			() -> assertArrayEquals(new int[0], sorted.written()),
			() -> assertArrayEquals(CLAIMED_ASCENDING, sorted.skipped()),
			() -> assertEquals(CLAIMED_ASCENDING.length, sorted.output().skipped()),
			() -> assertArrayEquals(UNCLAIMED, content(sorted.output().nonSortedKeys()))
		);
	}

	@Test
	@DisplayName("should pass the context through unchanged when it has nothing to sort")
	void shouldPassThroughUnchanged() {
		final WitnessFixture fixture = new WitnessFixture();
		final PickFirstReferenceSorter sorter = fixture.sorter(OrderDirection.ASC);
		final SortingContext prefetched = new SortingContext(
			createExecutionContext(true), fixture.allOwners(), 0, 10, 0, 0
		);
		final SortingContext empty = new SortingContext(
			createExecutionContext(false), EmptyBitmap.INSTANCE, 0, 10, 0, 0
		);
		// owner 4 is held by index 105, which has no value for it, and owner 5 by no index at all
		final SortingContext noValue = new SortingContext(
			createExecutionContext(false), new BaseBitmap(UNCLAIMED), 0, 10, 0, 0
		);

		assertAll(
			() -> assertSame(prefetched, sorter.sortAndSlice(prefetched, new int[10], null)),
			() -> assertSame(empty, sorter.sortAndSlice(empty, new int[10], null)),
			() -> assertSame(noValue, sorter.sortAndSlice(noValue, new int[10], null))
		);
	}

	@Test
	@DisplayName("should let the next index claim an owner when an index has no provider")
	void shouldLetNextIndexClaimWhenProviderIsMissing() {
		final WitnessFixture fixture = new WitnessFixture();
		final PickFirstReferenceSorter sorter = new PickFirstReferenceSorter(
			fixture.resolver,
			index -> index.getPrimaryKey() == 101 ? null : fixture.providerFactory.apply(index),
			Comparator.naturalOrder(),
			OrderDirection.ASC
		);

		final SortResult sorted = sort(
			sorter,
			new SortingContext(createExecutionContext(false), fixture.allOwners(), 0, 10, 0, 0),
			10
		);

		// owner 7 falls to its row of target 4 (40), owner 8 has no other row
		assertAll(
			() -> assertArrayEquals(new int[]{2, 6, 9, 1, 7, 3, 10}, sorted.written()),
			() -> assertArrayEquals(new int[]{4, 5, 8}, content(sorted.output().nonSortedKeys()))
		);
	}

	@Test
	@DisplayName("should break value ties by the owner primary key in the direction of the ordering")
	void shouldBreakValueTiesByOwnerPrimaryKeyInOrderingDirection() {
		final WitnessFixture fixture = new WitnessFixture();

		// owners 2 and 9 tie inside index 103, owner 6 ties with both from index 106
		final SortResult ascending = sort(
			fixture.sorter(OrderDirection.ASC),
			new SortingContext(createExecutionContext(false), fixture.allOwners(), 0, 10, 0, 0),
			10
		);
		final SortResult descending = sort(
			fixture.sorter(OrderDirection.DESC),
			new SortingContext(createExecutionContext(false), fixture.allOwners(), 0, 10, 0, 0),
			10
		);

		assertAll(
			() -> assertArrayEquals(CLAIMED_ASCENDING, ascending.written()),
			() -> assertArrayEquals(new int[]{10, 8, 3, 1, 9, 6, 2, 7}, descending.written())
		);
	}

	@Test
	@DisplayName("should leave the selection it sorts untouched")
	void shouldNotMutateSelection() {
		final WitnessFixture fixture = new WitnessFixture();
		final Bitmap selection = fixture.allOwners();

		sort(
			fixture.sorter(OrderDirection.ASC),
			new SortingContext(createExecutionContext(false), selection, 0, 10, 0, 0),
			10
		);

		assertArrayEquals(IntStream.rangeClosed(1, 10).toArray(), content(selection));
	}

	@Test
	@DisplayName("should claim more owners than the initial capacity of its buffers")
	void shouldClaimMoreOwnersThanInitialCapacity() {
		// the claim buffers start at the selection size capped at 1024
		final int ownerCount = 1500;
		final int[] owners = IntStream.rangeClosed(1, ownerCount).toArray();
		final PickFirstReducedIndexFixture indexFixture = new PickFirstReducedIndexFixture();
		final ReducedEntityIndex index = indexFixture.addIndex(Scope.LIVE, 101, 1, owners);
		final int[] ownerValues = new int[ownerCount * 2];
		for (int i = 0; i < ownerCount; i++) {
			ownerValues[i * 2] = owners[i];
			// the higher the owner, the lower its value
			ownerValues[i * 2 + 1] = ownerCount - owners[i];
		}
		final SortedRecordsSupplier provider = createProvider(index, ownerValues);
		final PickFirstReferenceSorter sorter = new PickFirstReferenceSorter(
			indexFixture.resolver(false, Scope.LIVE), theIndex -> provider, Comparator.naturalOrder(), OrderDirection.ASC
		);

		final SortResult sorted = sort(
			sorter,
			new SortingContext(createExecutionContext(false), new BaseBitmap(owners), 0, ownerCount, 0, 0),
			ownerCount
		);

		assertArrayEquals(
			IntStream.rangeClosed(1, ownerCount).map(it -> ownerCount + 1 - it).toArray(),
			sorted.written()
		);
	}

	/**
	 * What one run of the sorter produced.
	 *
	 * @param output  the returned context
	 * @param written the records the sorter wrote, in order
	 * @param result  the whole result array
	 * @param skipped the records reported as skipped, in order
	 */
	private record SortResult(
		@Nonnull SortingContext output,
		@Nonnull int[] written,
		@Nonnull int[] result,
		@Nonnull int[] skipped
	) {
	}

	/**
	 * The indexes and providers of the fixture described in the class documentation.
	 */
	private static final class WitnessFixture {
		/**
		 * The resolver over the fixture's indexes, targets in ascending primary key order.
		 */
		@Nonnull final PickFirstReducedIndexResolver resolver;
		/**
		 * Returns the provider of an index of the fixture.
		 */
		@Nonnull final Function<ReducedEntityIndex, SortedRecordsProvider> providerFactory;

		WitnessFixture() {
			final PickFirstReducedIndexFixture indexFixture = new PickFirstReducedIndexFixture();
			final Map<Integer, SortedRecordsProvider> providers = new HashMap<>(8);
			addIndex(indexFixture, providers, 101, 1, 7, 1, 8, 60);
			addIndex(indexFixture, providers, 102, 2, 1, NO_VALUE, 3, 50);
			addIndex(indexFixture, providers, 103, 3, 2, 20, 3, 10, 9, 20, 10, 70);
			addIndex(indexFixture, providers, 104, 4, 1, 30, 7, 40);
			addIndex(indexFixture, providers, 105, 5, 4, NO_VALUE);
			addIndex(indexFixture, providers, 106, 6, 6, 20);
			this.resolver = indexFixture.resolver(false, Scope.LIVE);
			this.providerFactory = index -> providers.get(index.getPrimaryKey());
		}

		/**
		 * Adds one index holding the owners of the pairs, and its provider when any owner has a value.
		 */
		private static void addIndex(
			@Nonnull PickFirstReducedIndexFixture indexFixture,
			@Nonnull Map<Integer, SortedRecordsProvider> providers,
			int primaryKey,
			int target,
			@Nonnull int... ownerValues
		) {
			final int[] owners = IntStream.range(0, ownerValues.length / 2).map(i -> ownerValues[i * 2]).toArray();
			final ReducedEntityIndex index = indexFixture.addIndex(Scope.LIVE, primaryKey, target, owners);
			final SortedRecordsSupplier provider = createProvider(index, ownerValues);
			if (provider != null) {
				providers.put(primaryKey, provider);
			}
		}

		/**
		 * Returns a fresh bitmap of all owners of the fixture, 1 to 10.
		 */
		@Nonnull
		Bitmap allOwners() {
			return new BaseBitmap(IntStream.rangeClosed(1, 10).toArray());
		}

		/**
		 * Creates the sorter in the ordering direction.
		 */
		@Nonnull
		PickFirstReferenceSorter sorter(@Nonnull OrderDirection direction) {
			return new PickFirstReferenceSorter(
				this.resolver,
				this.providerFactory,
				direction == OrderDirection.ASC ? Comparator.naturalOrder() : Comparator.reverseOrder(),
				direction
			);
		}
	}

}
