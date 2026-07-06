/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.index.attribute;

import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.PositionResolution;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.SortedRecordsProvider;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.SLOW;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that the tree-backed {@link SortedRecordsProvider} (the default provider handed out by {@link SortIndex}
 * since the positional-SPI change) resolves positions identically to the legacy array path and to an independent
 * brute-force oracle, across selections that exercise BOTH the tree path (`K <= N / 64`) and the array fallback
 * (`K > N / 64`), in ascending and descending order.
 *
 * The three cross-checked sources are:
 *
 * - the **oracle** — the sorted order computed directly from the inserted `(value, recordId)` pairs (value ascending,
 *   ties broken by record id ascending), fully independent of {@link SortIndex} internals;
 * - the **array getters** — {@link SortedRecordsProvider#getSortedRecordIds()} / {@link SortedRecordsProvider#getRecordPositions()}
 *   / {@link SortedRecordsProvider#getAllRecords()} (the legacy materialized artifacts);
 * - the **tree-direct SPI** — {@link SortedRecordsProvider#recordAt(int)} / {@link SortedRecordsProvider#positionOf(int)}
 *   / {@link SortedRecordsProvider#resolvePositions}.
 *
 * The tree path is taken while the selected count `K` (padded by the two absent ids the selections always append)
 * satisfies `(K + 2) * 64 <= N`; the small-`N` methods keep several selection sizes below that threshold and several
 * above it so both branches run, while the large-`N` method drives tree-path selections whose resolved positions span
 * multiple B+tree leaves AND multiple 16-bit RoaringBitmap containers (positions beyond 65536).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Tag(INDEXING)
@Tag(ATTRIBUTE)
@DisplayName("SortIndex tree-backed provider equivalence (tree path == array path == oracle)")
class SortIndexTreeProviderEquivalenceTest {

	private static final int RECORD_COUNT = 2_000;

	/**
	 * Record count for the multi-leaf / multi-container coverage: large enough that a tree-path selection resolves to
	 * mask positions beyond the first 16-bit RoaringBitmap container (65536) and spanning many B+tree leaves.
	 */
	private static final int LARGE_RECORD_COUNT = 70_000;

	/**
	 * First 16-bit RoaringBitmap container boundary: positions `>= 65536` fall into the second container.
	 */
	private static final int CONTAINER_BOUNDARY = 1 << 16;

	@Test
	@DisplayName("ascending provider agrees with the array path and the oracle for tree- and array-path selections")
	void shouldMatchAscending() {
		final Fixture fixture = buildFixture(20250706L, RECORD_COUNT);
		verifyProvider(
			fixture.sortIndex().getAscendingOrderRecordsSupplier(),
			fixture.ascendingOrder(),
			fixture.presentRecordIds(),
			mixedSelectionSizes(RECORD_COUNT),
			42L
		);
	}

	@Test
	@DisplayName("descending provider agrees with the array path and the oracle for tree- and array-path selections")
	void shouldMatchDescending() {
		final Fixture fixture = buildFixture(987654321L, RECORD_COUNT);
		final int[] descendingOrder = fixture.ascendingOrder().clone();
		reverse(descendingOrder);
		verifyProvider(
			fixture.sortIndex().getDescendingOrderRecordsSupplier(),
			descendingOrder,
			fixture.presentRecordIds(),
			mixedSelectionSizes(RECORD_COUNT),
			99L
		);
	}

	@Test
	@Tag(SLOW)
	@DisplayName("tree-path resolution spanning many leaves and multiple bitmap containers matches array and oracle")
	void shouldMatchAcrossMultipleLeavesAndContainers() {
		// a large index whose sorted order exceeds one 16-bit RoaringBitmap container (positions run up to ~70000)
		final Fixture fixture = buildFixture(20260706L, LARGE_RECORD_COUNT);
		// tree path requires (K + 2) * 64 <= LARGE_RECORD_COUNT (K up to ~1091); these selections spread present ids
		// across the whole order so the resolved masks span many B+tree leaves AND both bitmap containers
		final int[] treePathSizes = {200, 500, 800, 1000};

		final SortedRecordsProvider ascending = fixture.sortIndex().getAscendingOrderRecordsSupplier();
		verifyProvider(ascending, fixture.ascendingOrder(), fixture.presentRecordIds(), treePathSizes, 42L);
		assertMaskSpansBothContainers(ascending, fixture.ascendingOrder().length);

		final int[] descendingOrder = fixture.ascendingOrder().clone();
		reverse(descendingOrder);
		final SortedRecordsProvider descending = fixture.sortIndex().getDescendingOrderRecordsSupplier();
		verifyProvider(descending, descendingOrder, fixture.presentRecordIds(), treePathSizes, 99L);
		assertMaskSpansBothContainers(descending, descendingOrder.length);
	}

	/**
	 * Cross-checks a provider against the sorted-order oracle and its own array getters, then resolves the supplied
	 * selection sizes (each mixed with a slice of absent records) and asserts the mask, the emitted order and the
	 * not-found hand-off all match the oracle.
	 */
	private static void verifyProvider(
		@Nonnull SortedRecordsProvider provider,
		@Nonnull int[] expectedOrder,
		@Nonnull int[] presentRecordIds,
		@Nonnull int[] selectionSizes,
		long selectionSeed
	) {
		final int recordCount = expectedOrder.length;
		assertEquals(recordCount, provider.getRecordCount(), "record count");

		// 1) the materialized order matches the independent oracle
		assertArrayEquals(expectedOrder, provider.getSortedRecordIds(), "sorted record ids vs oracle");

		// 2) recordAt (tree-direct) matches the array order for every position
		for (int position = 0; position < recordCount; position++) {
			assertEquals(expectedOrder[position], provider.recordAt(position), "recordAt at " + position);
		}

		// 3) positionOf (tree-direct) matches the array-derived position for present ids, and the absent sentinel else
		for (int position = 0; position < recordCount; position++) {
			final int recordId = expectedOrder[position];
			assertEquals(position, provider.positionOf(recordId), "positionOf for record " + recordId);
			assertEquals(position, arrayPositionOf(provider, recordId), "array positionOf for record " + recordId);
		}
		assertEquals(SortedRecordsProvider.POSITION_NOT_FOUND, provider.positionOf(Integer.MAX_VALUE), "absent positionOf");

		// 4) resolvePositions across selection sizes that force the tree path (K <= N/64) and the array path (K > N/64),
		//    each mixed with a handful of absent record ids so the not-found hand-off is exercised too
		final int[] bufferA = new int[512];
		final int[] bufferB = new int[512];
		final Random random = new Random(selectionSeed);
		for (final int selectionSize : selectionSizes) {
			final int[] selectedPresent = pickDistinct(presentRecordIds, selectionSize, random);
			// two record ids guaranteed absent from the index (record ids are 1..N)
			final int[] absent = {recordCount + 7, recordCount + 42};
			final int[] selectedAll = concatSorted(selectedPresent, absent);

			final PersistentRoaringBitmap selected =
				RoaringBitmapBackedBitmap.getRoaringBitmap(new BaseBitmap(selectedAll));
			final PositionResolution resolution =
				provider.resolvePositions(selected, selectedAll.length, bufferA, bufferB);

			// expected mask = the sorted set of oracle positions of the present selected records
			final int[] expectedPositions = new int[selectedPresent.length];
			for (int i = 0; i < selectedPresent.length; i++) {
				expectedPositions[i] = arrayPositionOf(provider, selectedPresent[i]);
			}
			Arrays.sort(expectedPositions);
			assertArrayEquals(
				expectedPositions, new BaseBitmap(resolution.mask()).getArray(),
				"resolvePositions mask (K=" + selectionSize + ")"
			);

			// emitting the mask positions in ascending order reproduces the present selection in sorted order
			final int[] maskPositions = new BaseBitmap(resolution.mask()).getArray();
			final int[] emitted = new int[maskPositions.length];
			for (int i = 0; i < maskPositions.length; i++) {
				emitted[i] = provider.recordAt(maskPositions[i]);
			}
			assertArrayEquals(
				sortedByOrder(selectedPresent, expectedOrder), emitted,
				"emitted order (K=" + selectionSize + ")"
			);

			// the not-found hand-off is exactly the absent record ids
			assertArrayEquals(absent, new BaseBitmap(resolution.notFoundRecords()).getArray(), "not-found ids");
			assertEquals(absent.length, resolution.notFoundRecordsCount(), "not-found count");
		}
	}

	/**
	 * Proves a tree-path resolution actually builds a mask spanning both 16-bit RoaringBitmap containers: it selects the
	 * records at the lowest (0) and the highest (`recordCount - 1`) sorted positions and asserts the resolved mask holds
	 * exactly those two positions, the higher of which lies beyond the first container boundary (65536).
	 */
	private static void assertMaskSpansBothContainers(@Nonnull SortedRecordsProvider provider, int recordCount) {
		final int lastPosition = recordCount - 1;
		assertTrue(lastPosition >= CONTAINER_BOUNDARY, "the large index must place positions beyond the first container");
		// resolve by the two records seated at positions 0 and lastPosition (selectedRecordCount 2 -> tree path)
		final int lowRecord = provider.recordAt(0);
		final int highRecord = provider.recordAt(lastPosition);
		final int[] selectedAll = lowRecord < highRecord
			? new int[]{lowRecord, highRecord}
			: new int[]{highRecord, lowRecord};
		final PersistentRoaringBitmap selected =
			RoaringBitmapBackedBitmap.getRoaringBitmap(new BaseBitmap(selectedAll));
		final PositionResolution resolution =
			provider.resolvePositions(selected, selectedAll.length, new int[512], new int[512]);
		assertArrayEquals(
			new int[]{0, lastPosition}, new BaseBitmap(resolution.mask()).getArray(),
			"the mask must span the first and second RoaringBitmap containers"
		);
	}

	/**
	 * Mixed selection sizes for a small index: four genuine tree-path sizes (`(K + 2) * 64 <= recordCount`, capped at
	 * `K = 29` for `N = 2000`) plus four large array-path sizes, so both resolution branches are exercised.
	 */
	@Nonnull
	private static int[] mixedSelectionSizes(int recordCount) {
		return new int[]{1, 5, 15, 29, recordCount / 32, recordCount / 8, recordCount / 2, recordCount};
	}

	/**
	 * Array-path position lookup used as the reference: index the record within {@link SortedRecordsProvider#getAllRecords()}
	 * (ascending id order) and read {@link SortedRecordsProvider#getRecordPositions()}.
	 */
	private static int arrayPositionOf(@Nonnull SortedRecordsProvider provider, int recordId) {
		final int indexInAllRecords = provider.getAllRecords().indexOf(recordId);
		return indexInAllRecords < 0 ? SortedRecordsProvider.POSITION_NOT_FOUND : provider.getRecordPositions()[indexInAllRecords];
	}

	/**
	 * Builds a {@link SortIndex} of `recordCount` records with deliberately tie-heavy integer values, and returns it
	 * alongside the independently-computed ascending order and the present record ids.
	 */
	@Nonnull
	private static Fixture buildFixture(long seed, int recordCount) {
		final Random random = new Random(seed);
		final SortIndex sortIndex = new OwnerSortIndex(Integer.class, new AttributeIndexKey(null, "a", null));
		final List<int[]> pairs = new ArrayList<>(recordCount);
		final int[] presentRecordIds = new int[recordCount];
		for (int recordId = 1; recordId <= recordCount; recordId++) {
			// values in a narrow range => ~3 records per value => many ties to stress the tie-break-by-id order
			final int value = random.nextInt(recordCount / 3);
			sortIndex.addRecord(value, recordId);
			pairs.add(new int[]{value, recordId});
			presentRecordIds[recordId - 1] = recordId;
		}
		// oracle: sort by value ascending, ties broken by record id ascending
		pairs.sort((left, right) -> left[0] != right[0] ? Integer.compare(left[0], right[0]) : Integer.compare(left[1], right[1]));
		final int[] ascendingOrder = new int[recordCount];
		for (int i = 0; i < recordCount; i++) {
			ascendingOrder[i] = pairs.get(i)[1];
		}
		return new Fixture(sortIndex, ascendingOrder, presentRecordIds);
	}

	/**
	 * Returns the subset of `recordIds` in the order they appear in `order`.
	 */
	@Nonnull
	private static int[] sortedByOrder(@Nonnull int[] recordIds, @Nonnull int[] order) {
		// record ids are 1..order.length, so the presence flag array is sized to the order length + 1
		final boolean[] selected = new boolean[order.length + 1];
		for (final int recordId : recordIds) {
			selected[recordId] = true;
		}
		final int[] result = new int[recordIds.length];
		int peak = 0;
		for (final int recordId : order) {
			if (selected[recordId]) {
				result[peak++] = recordId;
			}
		}
		return result;
	}

	/**
	 * Picks `count` distinct record ids from `pool` using the supplied random source; returns them ascending.
	 */
	@Nonnull
	private static int[] pickDistinct(@Nonnull int[] pool, int count, @Nonnull Random random) {
		final int[] copy = pool.clone();
		for (int i = 0; i < count; i++) {
			final int j = i + random.nextInt(copy.length - i);
			final int tmp = copy[i];
			copy[i] = copy[j];
			copy[j] = tmp;
		}
		final int[] picked = Arrays.copyOf(copy, count);
		Arrays.sort(picked);
		return picked;
	}

	/**
	 * Concatenates two sorted arrays into one ascending array (inputs are disjoint here).
	 */
	@Nonnull
	private static int[] concatSorted(@Nonnull int[] left, @Nonnull int[] right) {
		final int[] merged = new int[left.length + right.length];
		System.arraycopy(left, 0, merged, 0, left.length);
		System.arraycopy(right, 0, merged, left.length, right.length);
		Arrays.sort(merged);
		return merged;
	}

	/**
	 * Reverses the given array in place.
	 */
	private static void reverse(@Nonnull int[] array) {
		for (int i = 0, j = array.length - 1; i < j; i++, j--) {
			final int tmp = array[i];
			array[i] = array[j];
			array[j] = tmp;
		}
	}

	/**
	 * Immutable test fixture bundling the index under test with the independent oracle order and present record ids.
	 */
	private record Fixture(
		@Nonnull SortIndex sortIndex,
		@Nonnull int[] ascendingOrder,
		@Nonnull int[] presentRecordIds
	) {
	}

}
