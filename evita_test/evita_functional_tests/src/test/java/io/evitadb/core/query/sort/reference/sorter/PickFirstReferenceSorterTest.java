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
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.QueryPlanningContext;
import io.evitadb.core.query.response.ServerEntityDecorator;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.SortedRecordsProvider;
import io.evitadb.core.query.sort.Sorter.SortingContext;
import io.evitadb.dataType.Scope;
import io.evitadb.index.ReducedEntityIndex;
import io.evitadb.index.ReferencePartition;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.VirtualReferencePartition;
import io.evitadb.index.VirtualReferencePartitionIndex;
import io.evitadb.index.attribute.SortIndex.ComparatorSource;
import io.evitadb.index.attribute.SortedRecordsSupplier;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.IntPredicate;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.ORDER;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * The virtual cases (step 4f of #1615) read single-owner partitions out of the registry of the type index: a generated
 * mix of materialized and virtual partitions checked against a brute-force oracle in every page window and both
 * directions, with the claimed values both dense and sparse; the cursor gate - one selected owner holding the largest
 * of `D` values reads one value id and walks nothing, whatever `D`; and an unselected owner of a virtual partition the
 * gather hands over is never claimed.
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
	 * The sort key the registry keeps the values of the virtual partitions under.
	 */
	private static final AttributeIndexKey VALUE_KEY =
		new AttributeIndexKey(PickFirstReducedIndexFixture.REFERENCE_NAME, "value", null);
	/**
	 * The values of the virtual partitions, read out of the registry under {@link #VALUE_KEY}.
	 */
	private static final PickFirstVirtualSortValues VALUE_SORT_VALUES = PickFirstVirtualSortValues.ofSortKey(VALUE_KEY);
	/**
	 * The value source of the cases without any virtual partition.
	 */
	private static final PickFirstVirtualSortValues NO_VIRTUAL_VALUES = VALUE_SORT_VALUES;

	/**
	 * Creates a provider holding the passed owners with their values in the ordering direction: ascending by value
	 * and then by owner, or the exact mirror of that for a descending ordering - the construction of
	 * `SortIndex#getAscendingOrderRecordsSupplier` and `SortIndex#getDescendingOrderRecordsSupplier`. Owners marked
	 * with {@link #NO_VALUE} are held by the index but not by the provider, exactly like a row lacking the attribute.
	 *
	 * @param index       the index the provider belongs to
	 * @param direction   the ordering direction the provider serves
	 * @param ownerValues pairs of owner primary key and value
	 * @return the provider, or `null` when no owner has a value
	 */
	@Nullable
	private static SortedRecordsSupplier createProvider(
		@Nonnull ReducedEntityIndex index,
		@Nonnull OrderDirection direction,
		@Nonnull int... ownerValues
	) {
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
		if (direction == OrderDirection.DESC) {
			Collections.reverse(pairs);
		}
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
	 * Wraps a provider into the lazy source the sorter asks for, keeping `null` for an index without values.
	 */
	@Nullable
	private static Supplier<SortedRecordsProvider> lazy(@Nullable SortedRecordsProvider provider) {
		return provider == null ? null : () -> provider;
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
			new SortingContext(fixture.createExecutionContext(), fixture.allOwners(), 2, 5, 0, 0),
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
			new SortingContext(fixture.createExecutionContext(), fixture.allOwners(), 0, 13, 3, 0),
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
			new SortingContext(fixture.createExecutionContext(), fixture.allOwners(), 0, 3, 0, 0),
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
			new SortingContext(fixture.createExecutionContext(), fixture.allOwners(), 9, 10, 0, 0),
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
			new PrefetchedExecutionContext(fixture.indexFixture.queryContext), fixture.allOwners(), 0, 10, 0, 0
		);
		final SortingContext empty = new SortingContext(
			fixture.createExecutionContext(), EmptyBitmap.INSTANCE, 0, 10, 0, 0
		);
		// owner 4 is held by index 105, which has no value for it, and owner 5 by no index at all
		final SortingContext noValue = new SortingContext(
			fixture.createExecutionContext(), new BaseBitmap(UNCLAIMED), 0, 10, 0, 0
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
			index -> index.getPrimaryKey() == 101 ? null : lazy(fixture.provider(OrderDirection.ASC, index)),
			NO_VIRTUAL_VALUES, Comparator.naturalOrder(),
			OrderDirection.ASC
		);

		final SortResult sorted = sort(
			sorter,
			new SortingContext(fixture.createExecutionContext(), fixture.allOwners(), 0, 10, 0, 0),
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
			new SortingContext(fixture.createExecutionContext(), fixture.allOwners(), 0, 10, 0, 0),
			10
		);
		final SortResult descending = sort(
			fixture.sorter(OrderDirection.DESC),
			new SortingContext(fixture.createExecutionContext(), fixture.allOwners(), 0, 10, 0, 0),
			10
		);

		assertAll(
			() -> assertArrayEquals(CLAIMED_ASCENDING, ascending.written()),
			() -> assertArrayEquals(new int[]{10, 8, 3, 1, 9, 6, 2, 7}, descending.written())
		);
	}

	@Test
	@DisplayName("should return every page window as the matching slice of the full order in both directions")
	void shouldReturnEveryPageWindowAsSliceOfFullOrder() {
		final WitnessFixture fixture = new WitnessFixture();
		final int[] descending = {10, 8, 3, 1, 9, 6, 2, 7};
		for (OrderDirection direction : OrderDirection.values()) {
			final int[] fullOrder = direction == OrderDirection.ASC ? CLAIMED_ASCENDING : descending;
			for (int start = 0; start <= fullOrder.length; start++) {
				for (int end = start + 1; end <= fullOrder.length + 1; end++) {
					final SortResult sorted = sort(
						fixture.sorter(direction),
						new SortingContext(fixture.createExecutionContext(), fixture.allOwners(), start, end, 0, 0),
						10
					);
					final int[] expected = Arrays.copyOfRange(fullOrder, start, Math.min(end, fullOrder.length));
					final int[] expectedSkipped = Arrays.copyOfRange(fullOrder, 0, start);
					final String window = direction + " [" + start + ", " + end + ")";
					assertArrayEquals(expected, sorted.written(), window);
					assertArrayEquals(expectedSkipped, sorted.skipped(), window + " skipped");
				}
			}
		}
	}

	@Test
	@DisplayName("should claim nothing from resolved indexes that hold no selected owner")
	void shouldClaimNothingFromIndexesHoldingNoSelectedOwner() {
		final WitnessFixture fixture = new WitnessFixture();

		// the resolver hands over every index of the fixture, yet only 102 (no value for it) and 104 hold owner 1, and
		// only 101 and 104 hold owner 7 - 103 and 106 must be rejected by the intersection with the unclaimed owners
		// before their providers are ever built, 105 has no provider at all
		final Set<Integer> builtProviders = new TreeSet<>();
		final PickFirstReferenceSorter sorter = new PickFirstReferenceSorter(
			fixture.resolver,
			index -> {
				final SortedRecordsProvider provider = fixture.provider(OrderDirection.ASC, index);
				return provider == null ? null : () -> {
					builtProviders.add(index.getPrimaryKey());
					return provider;
				};
			},
			NO_VIRTUAL_VALUES, Comparator.naturalOrder(),
			OrderDirection.ASC
		);
		final SortResult sorted = sort(
			sorter,
			new SortingContext(fixture.createExecutionContext(), new BaseBitmap(1, 7), 0, 10, 0, 0),
			10
		);

		assertAll(
			() -> assertArrayEquals(new int[]{7, 1}, sorted.written()),
			() -> assertArrayEquals(new int[0], content(sorted.output().nonSortedKeys())),
			() -> assertEquals(Set.of(101, 102, 104), builtProviders)
		);
	}

	@Test
	@DisplayName("should resolve a small unclaimed rest directly against a much larger index")
	void shouldResolveSmallUnclaimedRestDirectlyAgainstLargeIndex() {
		// index 201 of target 1 holds 60 owners, at least 16 times the 3 selected ones, so the rest is resolved
		// against it directly; owner 75 is held by index 202 of target 2 only and must still be claimed afterwards
		final PickFirstReducedIndexFixture indexFixture = new PickFirstReducedIndexFixture();
		final int[] largeIndexOwnerValues = new int[120];
		for (int i = 0; i < 60; i++) {
			largeIndexOwnerValues[i * 2] = i + 1;
			largeIndexOwnerValues[i * 2 + 1] = 200 - i;
		}
		final ReducedEntityIndex largeIndex = indexFixture.addIndex(
			Scope.LIVE, 201, 1, IntStream.rangeClosed(1, 60).toArray()
		);
		final ReducedEntityIndex smallIndex = indexFixture.addIndex(Scope.LIVE, 202, 2, 75);
		final SortedRecordsSupplier largeProvider = createProvider(largeIndex, OrderDirection.ASC, largeIndexOwnerValues);
		final SortedRecordsSupplier smallProvider = createProvider(smallIndex, OrderDirection.ASC, 75, 80);
		final PickFirstReferenceSorter sorter = new PickFirstReferenceSorter(
			indexFixture.resolver(false, Scope.LIVE),
			index -> lazy(index.getPrimaryKey() == 201 ? largeProvider : smallProvider),
			NO_VIRTUAL_VALUES, Comparator.naturalOrder(),
			OrderDirection.ASC
		);

		// owner 3 has value 198, owner 7 value 194, owner 75 value 80
		final SortResult sorted = sort(
			sorter,
			new SortingContext(indexFixture.createExecutionContext(), new BaseBitmap(3, 7, 75), 0, 10, 0, 0),
			10
		);

		assertAll(
			() -> assertArrayEquals(new int[]{75, 7, 3}, sorted.written()),
			() -> assertArrayEquals(new int[0], content(sorted.output().nonSortedKeys()))
		);
	}

	@Test
	@DisplayName("should not build the provider of a much larger index holding no unclaimed owner")
	void shouldNotBuildProviderOfLargeIndexHoldingNoUnclaimedOwner() {
		// index 201 of target 1 holds 60 owners, at least 16 times the one selected owner, so it would be asked directly
		// - but owner 75 is held by index 202 of target 2 only, so 201 must be passed over before its provider is built
		final PickFirstReducedIndexFixture indexFixture = new PickFirstReducedIndexFixture();
		final int[] largeIndexOwnerValues = new int[120];
		for (int i = 0; i < 60; i++) {
			largeIndexOwnerValues[i * 2] = i + 1;
			largeIndexOwnerValues[i * 2 + 1] = 200 - i;
		}
		final ReducedEntityIndex largeIndex = indexFixture.addIndex(
			Scope.LIVE, 201, 1, IntStream.rangeClosed(1, 60).toArray()
		);
		final ReducedEntityIndex smallIndex = indexFixture.addIndex(Scope.LIVE, 202, 2, 75);
		final SortedRecordsSupplier largeProvider = createProvider(largeIndex, OrderDirection.ASC, largeIndexOwnerValues);
		final SortedRecordsSupplier smallProvider = createProvider(smallIndex, OrderDirection.ASC, 75, 80);
		final Set<Integer> builtProviders = new TreeSet<>();
		final PickFirstReferenceSorter sorter = new PickFirstReferenceSorter(
			indexFixture.resolver(false, Scope.LIVE),
			index -> () -> {
				builtProviders.add(index.getPrimaryKey());
				return index.getPrimaryKey() == 201 ? largeProvider : smallProvider;
			},
			NO_VIRTUAL_VALUES, Comparator.naturalOrder(),
			OrderDirection.ASC
		);

		final SortResult sorted = sort(
			sorter,
			new SortingContext(indexFixture.createExecutionContext(), new BaseBitmap(75), 0, 10, 0, 0),
			10
		);

		assertAll(
			() -> assertArrayEquals(new int[]{75}, sorted.written()),
			() -> assertArrayEquals(new int[0], content(sorted.output().nonSortedKeys())),
			() -> assertEquals(Set.of(202), builtProviders)
		);
	}

	@Test
	@DisplayName("should leave the selection it sorts untouched")
	void shouldNotMutateSelection() {
		final WitnessFixture fixture = new WitnessFixture();
		final Bitmap selection = fixture.allOwners();

		sort(
			fixture.sorter(OrderDirection.ASC),
			new SortingContext(fixture.createExecutionContext(), selection, 0, 10, 0, 0),
			10
		);

		assertArrayEquals(IntStream.rangeClosed(1, 10).toArray(), content(selection));
	}

	@Test
	@DisplayName("should claim more owners than the initial capacity of its buffers")
	void shouldClaimMoreOwnersThanInitialCapacity() {
		// the scratch buffers the claim borrows hold 512 records, so 1500 owners are resolved over several batches
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
		final SortedRecordsSupplier provider = createProvider(index, OrderDirection.ASC, ownerValues);
		final PickFirstReferenceSorter sorter = new PickFirstReferenceSorter(
			indexFixture.resolver(false, Scope.LIVE), theIndex -> lazy(provider), NO_VIRTUAL_VALUES,
			Comparator.naturalOrder(), OrderDirection.ASC
		);

		final SortResult sorted = sort(
			sorter,
			new SortingContext(indexFixture.createExecutionContext(), new BaseBitmap(owners), 0, ownerCount, 0, 0),
			ownerCount
		);

		assertArrayEquals(
			IntStream.rangeClosed(1, ownerCount).map(it -> ownerCount + 1 - it).toArray(),
			sorted.written()
		);
	}

	@ParameterizedTest(name = "{0}, values 1..{1}")
	@MethodSource("virtualRunCases")
	@DisplayName("should merge the virtual run with the materialized runs in every page window")
	void shouldMergeVirtualRunInEveryPageWindow(@Nonnull OrderDirection direction, int valueRange) {
		final VirtualRowsFixture fixture = new VirtualRowsFixture(valueRange);
		final int[] expected = fixture.expectedOrder(direction);
		final int[] expectedUnclaimed = fixture.expectedUnclaimed();
		assertTrue(expected.length > 10, "The fixture must claim a sizeable part of the selection.");

		for (int start = 0; start <= expected.length; start++) {
			for (int end = start + 1; end <= expected.length + 1; end++) {
				final SortResult sorted = sort(
					fixture.sorter(direction, new CountingVirtualValues(VALUE_SORT_VALUES)),
					new SortingContext(fixture.indexFixture.createExecutionContext(), fixture.selection(), start, end, 0, 0),
					end - start
				);
				final int[] expectedPage = Arrays.copyOfRange(expected, start, Math.min(end, expected.length));
				final int theStart = start;
				final int theEnd = end;
				assertArrayEquals(expectedPage, sorted.written(), () -> "Window " + theStart + ".." + theEnd + " differs.");
				assertArrayEquals(Arrays.copyOfRange(expected, 0, start), sorted.skipped());
				assertArrayEquals(expectedUnclaimed, content(sorted.output().nonSortedKeys()));
			}
		}
		assertTrue(fixture.indexFixture.virtualRowsRead() > 0, "The virtual partitions must have been read.");
	}

	@ParameterizedTest(name = "{0}, values 1..{1}")
	@MethodSource("virtualRunCases")
	@DisplayName("should order the virtual run by the walk exactly when sorting the claimed values costs more")
	void shouldChooseThePreparationByTheClaimedValueCount(@Nonnull OrderDirection direction, int valueRange) {
		final VirtualRowsFixture fixture = new VirtualRowsFixture(valueRange);
		final CountingVirtualValues values = new CountingVirtualValues(VALUE_SORT_VALUES);

		sort(
			fixture.sorter(direction, values),
			new SortingContext(fixture.indexFixture.createExecutionContext(), fixture.selection(), 0, 1, 0, 0),
			1
		);

		final int groupCount = fixture.claimedVirtualValueCount(direction);
		final boolean dense = PickFirstReferenceSorter.isDense(
			groupCount, fixture.indexFixture.registry(Scope.LIVE).getDistinctSortValueCount(VALUE_KEY)
		);
		// the small range makes the claimed values dense, the large one sparse
		assertEquals(valueRange < 100, dense);
		if (dense) {
			assertTrue(values.walkSteps > 0, "The dense preparation walks the values of the registry.");
		} else {
			assertEquals(0, values.walkSteps, "The sparse preparation must not walk the values of the registry.");
		}
	}

	@ParameterizedTest(name = "D = {0}")
	@ValueSource(ints = {1, 1_000, 100_000})
	@DisplayName("should read one value and walk nothing for one claimed owner holding the largest of D values")
	void shouldReadOneValueForOneClaimedOwner(int distinctValueCount) {
		final PickFirstReducedIndexFixture indexFixture = new PickFirstReducedIndexFixture();
		final VirtualReferencePartitionIndex registry = indexFixture.registry(Scope.LIVE);
		declareValueKey(registry);
		// the selected owner 1 holds the largest value on the first target and a smaller one on the last target
		indexFixture.addVirtualPartition(Scope.LIVE, 1, 1, 1);
		registry.putSortValue(VALUE_KEY, 1, distinctValueCount);
		// the other values are held by unselected owners, one virtual partition each
		for (int i = 1; i < distinctValueCount; i++) {
			indexFixture.addVirtualPartition(Scope.LIVE, i + 1, i + 1, i + 1);
			registry.putSortValue(VALUE_KEY, i + 1, i);
		}
		indexFixture.addVirtualPartition(Scope.LIVE, distinctValueCount + 1, distinctValueCount + 1, 1);
		registry.putSortValue(VALUE_KEY, distinctValueCount + 1, 0);
		assertEquals(distinctValueCount + 1, registry.getDistinctSortValueCount(VALUE_KEY));
		indexFixture.readVirtually();

		for (OrderDirection direction : OrderDirection.values()) {
			final CountingVirtualValues values = new CountingVirtualValues(VALUE_SORT_VALUES);
			final PickFirstReferenceSorter sorter = new PickFirstReferenceSorter(
				indexFixture.resolver(false, Scope.LIVE),
				index -> {
					throw new AssertionError("No materialized partition exists.");
				},
				values,
				direction == OrderDirection.ASC ? Comparator.naturalOrder() : Comparator.reverseOrder(),
				direction
			);

			final SortResult sorted = sort(
				sorter, new SortingContext(indexFixture.createExecutionContext(), new BaseBitmap(1), 0, 20, 0, 0), 20
			);

			assertAll(
				() -> assertArrayEquals(new int[]{1}, sorted.written()),
				() -> assertEquals(1, values.valueIdReads, "Only the claimed row may have its value id read."),
				() -> assertEquals(1, values.valueReads, "Only the claimed value may be read."),
				() -> assertEquals(0, values.walkSteps, "No value of the registry may be walked.")
			);
		}
	}

	@Test
	@DisplayName("should never claim an unselected owner of a virtual partition the gather handed over")
	void shouldRejectUnselectedOwnerOfVirtualPartition() {
		final PickFirstReducedIndexFixture indexFixture = new PickFirstReducedIndexFixture();
		final VirtualReferencePartitionIndex registry = indexFixture.registry(Scope.LIVE);
		declareValueKey(registry);
		// selected owners 1 and 2 share the materialized partition of target 20
		final ReducedEntityIndex shared = indexFixture.addIndex(Scope.LIVE, 120, 20, 1, 2);
		// unselected owner 3 holds the lowest value of all in the virtual partition of target 10
		indexFixture.addVirtualPartition(Scope.LIVE, 110, 10, 3);
		registry.putSortValue(VALUE_KEY, 110, -100);
		indexFixture.readVirtually();
		final SortedRecordsSupplier provider = createProvider(shared, OrderDirection.ASC, 1, 20, 2, 10);
		final CountingVirtualValues values = new CountingVirtualValues(VALUE_SORT_VALUES);
		final PickFirstReferenceSorter sorter = new PickFirstReferenceSorter(
			indexFixture.resolver(false, Scope.LIVE), index -> lazy(provider), values, Comparator.naturalOrder(),
			OrderDirection.ASC
		);

		final SortResult sorted = sort(
			sorter, new SortingContext(indexFixture.createExecutionContext(), new BaseBitmap(1, 2), 0, 2, 0, 0), 2
		);

		assertAll(
			() -> assertArrayEquals(new int[]{2, 1}, sorted.written()),
			() -> assertEquals(0, values.valueIdReads, "The unselected owner's row must not be read."),
			() -> assertTrue(indexFixture.virtualRowsRead() > 0, "The virtual partition must have been handed over.")
		);
	}

	/**
	 * Returns the directions and value ranges of the virtual run cases: a range of four values makes the claimed values
	 * dense, a range of a thousand sparse.
	 *
	 * @return the arguments
	 */
	@Nonnull
	static Stream<Arguments> virtualRunCases() {
		return Stream.of(
			Arguments.of(OrderDirection.ASC, 4),
			Arguments.of(OrderDirection.DESC, 4),
			Arguments.of(OrderDirection.ASC, 1_000),
			Arguments.of(OrderDirection.DESC, 1_000)
		);
	}

	/**
	 * Declares the sort key {@link #VALUE_KEY} in the registry.
	 *
	 * @param registry the registry
	 */
	private static void declareValueKey(@Nonnull VirtualReferencePartitionIndex registry) {
		registry.declareSortKey(
			VALUE_KEY,
			new ComparatorSource[]{new ComparatorSource(Integer.class, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)},
			0
		);
	}

	/**
	 * A generated mix of materialized and virtual partitions of one reference, with a brute-force oracle of the
	 * pick-first order: per selected owner the value of its first row in target order carrying one, then the owners by
	 * value and primary key in the ordering direction.
	 *
	 * Targets are shared by one to three owners; a target held by one owner is a virtual partition three times out of
	 * four. One row in five carries no value, and every seventh owner is left out of the selection although its rows -
	 * virtual ones included - are handed over by the gather, which lists the whole family. Virtual partition primary
	 * keys fall as the owner rises, so an emission in partition order would reverse every tie.
	 */
	private static final class VirtualRowsFixture {
		/**
		 * The owners of the fixture, 1 to this number.
		 */
		private static final int OWNER_COUNT = 40;
		/**
		 * The targets of the fixture, 1 to this number.
		 */
		private static final int TARGET_COUNT = 30;
		/**
		 * The value range from which the fixture adds a large family of virtual rows of unselected owners.
		 */
		private static final int SPARSE_VALUE_RANGE = 100;
		/**
		 * The number of virtual rows of unselected owners added for a sparse value range.
		 */
		private static final int UNSELECTED_FAMILY_SIZE = 2_000;
		/**
		 * The first owner of the virtual rows nobody selects.
		 */
		private static final int UNSELECTED_OWNER_BASE = 10_000;
		/**
		 * The indexes and the registry.
		 */
		@Nonnull final PickFirstReducedIndexFixture indexFixture = new PickFirstReducedIndexFixture();
		/**
		 * The rows of every owner: `{target, value}` with {@link #NO_VALUE} for a row without one.
		 */
		@Nonnull private final Map<Integer, List<int[]>> rowsByOwner = new HashMap<>(64);
		/**
		 * The providers of the materialized indexes per ordering direction, keyed by index primary key.
		 */
		@Nonnull private final Map<OrderDirection, Map<Integer, SortedRecordsProvider>> providers =
			new EnumMap<>(OrderDirection.class);

		VirtualRowsFixture(int valueRange) {
			final Random random = new Random(42L);
			final VirtualReferencePartitionIndex registry = this.indexFixture.registry(Scope.LIVE);
			declareValueKey(registry);
			for (int target = 1; target <= TARGET_COUNT; target++) {
				final int ownerCount = new int[]{1, 1, 1, 2, 3}[random.nextInt(5)];
				final TreeSet<Integer> owners = new TreeSet<>();
				while (owners.size() < ownerCount) {
					owners.add(1 + random.nextInt(OWNER_COUNT));
				}
				final int[] ownerValues = new int[ownerCount * 2];
				int i = 0;
				for (int owner : owners) {
					final int value = random.nextInt(5) == 0 ? NO_VALUE : 1 + random.nextInt(valueRange);
					ownerValues[i++] = owner;
					ownerValues[i++] = value;
					this.rowsByOwner.computeIfAbsent(owner, it -> new ArrayList<>(4)).add(new int[]{target, value});
				}
				if (ownerCount == 1 && random.nextInt(4) != 0) {
					final int owner = owners.first();
					final int primaryKey = 5_000 - (owner * (TARGET_COUNT + 1) + target);
					this.indexFixture.addVirtualPartition(Scope.LIVE, primaryKey, target, owner);
					if (ownerValues[1] != NO_VALUE) {
						registry.putSortValue(VALUE_KEY, primaryKey, ownerValues[1]);
					}
				} else {
					final ReducedEntityIndex index = this.indexFixture.addIndex(
						Scope.LIVE, 100 + target, target,
						owners.stream().mapToInt(Integer::intValue).toArray()
					);
					for (OrderDirection direction : OrderDirection.values()) {
						final SortedRecordsSupplier provider = createProvider(index, direction, ownerValues);
						if (provider != null) {
							this.providers.computeIfAbsent(direction, it -> new HashMap<>(32))
								.put(index.getPrimaryKey(), provider);
						}
					}
				}
			}
			if (valueRange >= SPARSE_VALUE_RANGE) {
				// a large family of values nobody selected keeps the claimed values sparse
				for (int i = 0; i < UNSELECTED_FAMILY_SIZE; i++) {
					final int primaryKey = 6_000 + i;
					this.indexFixture.addVirtualPartition(
						Scope.LIVE, primaryKey, TARGET_COUNT + 1 + i, UNSELECTED_OWNER_BASE + i
					);
					registry.putSortValue(VALUE_KEY, primaryKey, 1 + random.nextInt(valueRange));
				}
			}
			this.indexFixture.readVirtually();
		}

		/**
		 * Returns a fresh bitmap of the selected owners - every owner but each seventh.
		 */
		@Nonnull
		Bitmap selection() {
			return new BaseBitmap(IntStream.rangeClosed(1, OWNER_COUNT).filter(it -> it % 7 != 0).toArray());
		}

		/**
		 * Returns the selected owners in the pick-first order of the direction.
		 */
		@Nonnull
		int[] expectedOrder(@Nonnull OrderDirection direction) {
			final List<int[]> claimed = new ArrayList<>(OWNER_COUNT);
			for (int owner : selection().getArray()) {
				final int value = firstValue(owner);
				if (value != NO_VALUE) {
					claimed.add(new int[]{owner, value});
				}
			}
			Comparator<int[]> order = Comparator.<int[]>comparingInt(it -> it[1]).thenComparingInt(it -> it[0]);
			if (direction == OrderDirection.DESC) {
				order = order.reversed();
			}
			claimed.sort(order);
			return claimed.stream().mapToInt(it -> it[0]).toArray();
		}

		/**
		 * Returns the selected owners without any valued row.
		 */
		@Nonnull
		int[] expectedUnclaimed() {
			return Arrays.stream(selection().getArray()).filter(it -> firstValue(it) == NO_VALUE).toArray();
		}

		/**
		 * Returns how many distinct values the virtual partitions claim.
		 */
		int claimedVirtualValueCount(@Nonnull OrderDirection direction) {
			final Set<Integer> values = new TreeSet<>();
			for (int owner : selection().getArray()) {
				final int[] first = firstValuedRowOf(owner);
				if (first != null && this.providers.getOrDefault(direction, Map.of()).get(100 + first[0]) == null) {
					values.add(first[1]);
				}
			}
			return values.size();
		}

		/**
		 * Returns the value of the first valued row of the owner in target order, or {@link #NO_VALUE}.
		 */
		private int firstValue(int owner) {
			final int[] first = firstValuedRowOf(owner);
			return first == null ? NO_VALUE : first[1];
		}

		/**
		 * Returns the first valued row `{target, value}` of the owner in target order, or `null`.
		 */
		@Nullable
		private int[] firstValuedRowOf(int owner) {
			return this.rowsByOwner.getOrDefault(owner, List.of()).stream()
				.sorted(Comparator.comparingInt(it -> it[0]))
				.filter(it -> it[1] != NO_VALUE)
				.findFirst()
				.orElse(null);
		}

		/**
		 * Creates the sorter in the ordering direction.
		 */
		@Nonnull
		PickFirstReferenceSorter sorter(@Nonnull OrderDirection direction, @Nonnull PickFirstVirtualSortValues values) {
			return new PickFirstReferenceSorter(
				this.indexFixture.resolver(false, Scope.LIVE),
				index -> lazy(this.providers.getOrDefault(direction, Map.of()).get(index.getPrimaryKey())),
				values,
				direction == OrderDirection.ASC ? Comparator.naturalOrder() : Comparator.reverseOrder(),
				direction
			);
		}
	}

	/**
	 * Counts what the sorter asks of the registry values: value id reads, value reads and steps of the ordered walk.
	 */
	private static final class CountingVirtualValues implements PickFirstVirtualSortValues {
		/**
		 * The real source the counts are taken over.
		 */
		@Nonnull private final PickFirstVirtualSortValues delegate;
		/**
		 * Number of value id reads.
		 */
		int valueIdReads;
		/**
		 * Number of value reads.
		 */
		int valueReads;
		/**
		 * Number of value ids the ordered walks passed.
		 */
		int walkSteps;

		CountingVirtualValues(@Nonnull PickFirstVirtualSortValues delegate) {
			this.delegate = delegate;
		}

		@Override
		public int getValueId(@Nonnull VirtualReferencePartition partition) {
			this.valueIdReads++;
			return this.delegate.getValueId(partition);
		}

		@Nonnull
		@Override
		public Serializable getValue(@Nonnull ReferencedTypeEntityIndex typeIndex, int valueId) {
			this.valueReads++;
			return this.delegate.getValue(typeIndex, valueId);
		}

		@Override
		public int getDistinctValueCount(@Nonnull ReferencedTypeEntityIndex typeIndex) {
			return this.delegate.getDistinctValueCount(typeIndex);
		}

		@Override
		public void forEachValueId(
			@Nonnull ReferencedTypeEntityIndex typeIndex,
			boolean descending,
			@Nonnull IntPredicate consumer
		) {
			this.delegate.forEachValueId(
				typeIndex, descending,
				valueId -> {
					this.walkSteps++;
					return consumer.test(valueId);
				}
			);
		}
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
		 * The indexes of the fixture.
		 */
		@Nonnull final PickFirstReducedIndexFixture indexFixture = new PickFirstReducedIndexFixture();
		/**
		 * The resolver over the fixture's indexes, targets in ascending primary key order.
		 */
		@Nonnull final PickFirstReducedIndexResolver resolver;
		/**
		 * The providers of the fixture's indexes per ordering direction, keyed by index primary key.
		 */
		@Nonnull final Map<OrderDirection, Map<Integer, SortedRecordsProvider>> providers =
			new EnumMap<>(OrderDirection.class);

		WitnessFixture() {
			addIndex(101, 1, 7, 1, 8, 60);
			addIndex(102, 2, 1, NO_VALUE, 3, 50);
			addIndex(103, 3, 2, 20, 3, 10, 9, 20, 10, 70);
			addIndex(104, 4, 1, 30, 7, 40);
			addIndex(105, 5, 4, NO_VALUE);
			addIndex(106, 6, 6, 20);
			this.resolver = this.indexFixture.resolver(false, Scope.LIVE);
		}

		/**
		 * Adds one index holding the owners of the pairs, and its providers when any owner has a value.
		 */
		private void addIndex(int primaryKey, int target, @Nonnull int... ownerValues) {
			final int[] owners = IntStream.range(0, ownerValues.length / 2).map(i -> ownerValues[i * 2]).toArray();
			final ReducedEntityIndex index = this.indexFixture.addIndex(Scope.LIVE, primaryKey, target, owners);
			for (OrderDirection direction : OrderDirection.values()) {
				final SortedRecordsSupplier provider = createProvider(index, direction, ownerValues);
				if (provider != null) {
					this.providers.computeIfAbsent(direction, it -> new HashMap<>(8)).put(primaryKey, provider);
				}
			}
		}

		/**
		 * Creates a fresh execution context over the fixture's indexes, with no entities prefetched.
		 */
		@Nonnull
		QueryExecutionContext createExecutionContext() {
			return this.indexFixture.createExecutionContext();
		}

		/**
		 * Returns a fresh bitmap of all owners of the fixture, 1 to 10.
		 */
		@Nonnull
		Bitmap allOwners() {
			return new BaseBitmap(IntStream.rangeClosed(1, 10).toArray());
		}

		/**
		 * Returns the provider of an index of the fixture for the ordering direction, or `null` when it has none.
		 *
		 * @param direction the ordering direction
		 * @param index     the partition to return the provider of
		 * @return the provider, or `null` when the fixture has none for the partition in that direction
		 */
		@Nullable
		SortedRecordsProvider provider(@Nonnull OrderDirection direction, @Nonnull ReferencePartition index) {
			return this.providers.getOrDefault(direction, Map.of()).get(index.getPrimaryKey());
		}

		/**
		 * Creates the sorter in the ordering direction.
		 */
		@Nonnull
		PickFirstReferenceSorter sorter(@Nonnull OrderDirection direction) {
			return new PickFirstReferenceSorter(
				this.resolver,
				index -> lazy(provider(direction, index)),
				NO_VIRTUAL_VALUES,
				direction == OrderDirection.ASC ? Comparator.naturalOrder() : Comparator.reverseOrder(),
				direction
			);
		}
	}

	/**
	 * An execution context whose entity bodies were prefetched - the one state of a real context that cannot be
	 * reached without an entity collection to prefetch from.
	 */
	private static final class PrefetchedExecutionContext extends QueryExecutionContext {

		PrefetchedExecutionContext(@Nonnull QueryPlanningContext queryContext) {
			super(
				queryContext, true, null,
				(type, entity) -> {
					throw new UnsupportedOperationException("No entity is converted in this test.");
				}
			);
		}

		@Nonnull
		@Override
		public List<ServerEntityDecorator> getPrefetchedEntities() {
			return List.of();
		}
	}

}
