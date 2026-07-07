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

import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.ForcedSortResolution;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.PositionResolution;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.SortResolutionStrategy;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.SortedRecordsProvider;
import io.evitadb.dataType.Predecessor;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.Random;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.INDEXING;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that the tree-backed {@link SortedRecordsProvider} handed out by a {@link ChainIndex} (since it resolves
 * positions straight from its live element tree) matches an independent oracle — the chain order fixed by
 * construction — across the sparse tree probe (`K <= N / 64`), the cold dense tree walk (`K > N / 64`) and, for the
 * rare inconsistent (multi-run) case, the legacy array merge-walk, in ascending and descending order.
 *
 * The consistent-chain fixtures fix the ascending order by construction (element `order[i]` is inserted as the
 * successor of `order[i - 1]`, `order[0]` a fresh head), so the whole {@link ChainIndex#elements} tree is a single
 * head-first run whose order equals `order` — an oracle wholly independent of the tree internals. The inconsistent
 * fixture wires two disjoint chains (two heads, no cross link), so the index never collapses to one chain and the
 * supplier must fall back to the array merge-walk over the semi-consistent flattening.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Tag(INDEXING)
@Tag(ATTRIBUTE)
@DisplayName("ChainIndex tree-backed provider equivalence (tree path == array path == oracle)")
class ChainIndexTreeProviderEquivalenceTest {

	private static final int RECORD_COUNT = 2_000;

	@Test
	@DisplayName("consistent chain: ascending provider matches the construction oracle for sparse and dense selections")
	void shouldMatchAscendingWhenConsistent() {
		final ChainFixture fixture = buildConsistentFixture(20250706L, RECORD_COUNT);
		assertTrue(fixture.chainIndex().isConsistent(), "the fixture chain must be a single consistent chain");
		verifyProvider(
			fixture.chainIndex().getAscendingOrderRecordsSupplier(),
			fixture.order(),
			fixture.presentRecordIds(),
			mixedSelectionSizes(RECORD_COUNT),
			42L
		);
	}

	@Test
	@DisplayName("consistent chain: descending provider matches the reversed oracle for sparse and dense selections")
	void shouldMatchDescendingWhenConsistent() {
		final ChainFixture fixture = buildConsistentFixture(987654321L, RECORD_COUNT);
		assertTrue(fixture.chainIndex().isConsistent(), "the fixture chain must be a single consistent chain");
		final int[] descendingOrder = fixture.order().clone();
		reverse(descendingOrder);
		verifyProvider(
			fixture.chainIndex().getDescendingOrderRecordsSupplier(),
			descendingOrder,
			fixture.presentRecordIds(),
			mixedSelectionSizes(RECORD_COUNT),
			99L
		);
	}

	@Test
	@DisplayName("consistent chain: a sparse selection reports the tree sparse probe, a dense one the cold tree walk")
	void shouldUseTreeStrategiesWhenConsistent() {
		final ChainFixture fixture = buildConsistentFixture(20250706L, RECORD_COUNT);

		// sparse selection: K * 64 <= N -> the tree sparse probe (per-record O(log N) look-ups, no materialization)
		final int[] sparseSelection = pickDistinct(fixture.presentRecordIds(), 10, new Random(1L));
		assertEquals(
			SortResolutionStrategy.TREE_SPARSE_PROBE,
			resolveFreshAscending(fixture, sparseSelection).strategy(),
			"a sparse selection on a consistent chain resolves via the tree sparse probe"
		);

		// dense selection: K * 64 > N on a fresh (cold) supplier -> the O(N) dense tree walk (never materializes arrays)
		final int[] denseSelection = pickDistinct(fixture.presentRecordIds(), RECORD_COUNT / 2, new Random(2L));
		assertEquals(
			SortResolutionStrategy.TREE_DENSE_WALK,
			resolveFreshAscending(fixture, denseSelection).strategy(),
			"a dense selection on a fresh consistent chain resolves via the cold dense tree walk"
		);
	}

	@Test
	@DisplayName("inconsistent chain: the array merge-walk still resolves the semi-consistent order correctly")
	void shouldFallBackToArrayWhenInconsistent() {
		// two disjoint chains (two heads, no cross-link) never collapse to one -> the index stays inconsistent
		final ChainFixture fixture = buildInconsistentFixture(300, 200);
		assertFalse(fixture.chainIndex().isConsistent(), "the fixture chain must be inconsistent (multiple runs)");

		// the semi-consistent order IS the array-backed provider's reference (runs ordered by descending length)
		final int[] order = fixture.chainIndex().getUnorderedLookup().getArray();
		final SortedRecordsProvider provider = fixture.chainIndex().getAscendingOrderRecordsSupplier();
		verifyProvider(provider, order, fixture.presentRecordIds(), new int[]{1, 5, order.length}, 7L);

		// with no live tree order able to express the reordered runs, resolution stays on the array merge-walk
		final int[] selection = pickDistinct(fixture.presentRecordIds(), 5, new Random(3L));
		assertEquals(
			SortResolutionStrategy.ARRAY_MERGE_WALK,
			resolve(fixture.chainIndex().getAscendingOrderRecordsSupplier(), selection).strategy(),
			"an inconsistent chain resolves via the array merge-walk"
		);
	}

	@Test
	@DisplayName("inconsistent chain: the descending array merge-walk resolves the reversed semi-consistent order")
	void shouldFallBackToArrayWhenInconsistentDescending() {
		// two disjoint chains (two heads, no cross-link) never collapse to one -> the index stays inconsistent
		final ChainFixture fixture = buildInconsistentFixture(300, 200);
		assertFalse(fixture.chainIndex().isConsistent(), "the fixture chain must be inconsistent (multiple runs)");

		// the descending order is the semi-consistent (ascending) flattening reversed - the reference for the
		// array-backed descending supplier, which reverses the flattened runs and inverts their positions
		final int[] descendingOrder = fixture.chainIndex().getUnorderedLookup().getArray().clone();
		reverse(descendingOrder);
		final SortedRecordsProvider provider = fixture.chainIndex().getDescendingOrderRecordsSupplier();
		verifyProvider(
			provider, descendingOrder, fixture.presentRecordIds(), new int[]{1, 5, descendingOrder.length}, 13L
		);

		// with no live tree order able to express the reordered runs, resolution stays on the array merge-walk
		final int[] selection = pickDistinct(fixture.presentRecordIds(), 5, new Random(23L));
		assertEquals(
			SortResolutionStrategy.ARRAY_MERGE_WALK,
			resolve(fixture.chainIndex().getDescendingOrderRecordsSupplier(), selection).strategy(),
			"an inconsistent chain resolves via the array merge-walk in the descending direction too"
		);
	}

	@Test
	@DisplayName("inconsistent chain: forcing the tree path degrades to the array merge-walk (no tree to walk)")
	void shouldDegradeForcedTreeToArrayWhenInconsistent() {
		// an array-backed (inconsistent) supplier has no live element tree, so a forced TREE override must degrade to
		// the array merge-walk and resolve identically to the cost-based path
		final ChainFixture fixture = buildInconsistentFixture(300, 200);
		assertFalse(fixture.chainIndex().isConsistent(), "the fixture chain must be inconsistent (multiple runs)");

		final int recordCount = fixture.presentRecordIds().length;
		final int[] selectedPresent = pickDistinct(fixture.presentRecordIds(), 5, new Random(29L));
		// two record ids guaranteed absent from the index (present ids are 1..recordCount) so the hand-off is exercised
		final int[] absent = {recordCount + 7, recordCount + 42};
		final int[] selectedAll = concatSorted(selectedPresent, absent);
		final PersistentRoaringBitmap selected =
			RoaringBitmapBackedBitmap.getRoaringBitmap(new BaseBitmap(selectedAll));

		final SortedRecordsProvider provider = fixture.chainIndex().getAscendingOrderRecordsSupplier();
		final PositionResolution costBased =
			provider.resolvePositions(selected, selectedAll.length, new int[512], new int[512]);
		final PositionResolution forcedTree = provider.resolvePositions(
			selected, selectedAll.length, new int[512], new int[512], ForcedSortResolution.TREE
		);

		// the forced TREE override is ignored by an array-backed supplier: it still reports the array merge-walk
		assertEquals(
			SortResolutionStrategy.ARRAY_MERGE_WALK, forcedTree.strategy(),
			"forced TREE on an array-backed supplier degrades to the array merge-walk"
		);
		// and it resolves to exactly the same mask and not-found hand-off as the cost-based call
		assertArrayEquals(
			new BaseBitmap(costBased.mask()).getArray(), new BaseBitmap(forcedTree.mask()).getArray(),
			"forced TREE mask matches the cost-based mask"
		);
		assertArrayEquals(
			new BaseBitmap(costBased.notFoundRecords()).getArray(),
			new BaseBitmap(forcedTree.notFoundRecords()).getArray(),
			"forced TREE not-found hand-off matches the cost-based hand-off"
		);
		assertEquals(
			costBased.notFoundRecordsCount(), forcedTree.notFoundRecordsCount(),
			"forced TREE not-found count matches the cost-based count"
		);
	}

	/**
	 * Cross-checks a provider against the expected sorted order and its own array getters, then resolves the supplied
	 * selection sizes (each mixed with two absent record ids) and asserts the mask, the emitted order and the not-found
	 * hand-off all match the expected order. Identical in spirit to the SortIndex tree-provider equivalence check.
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

		// 1) the materialized order matches the construction oracle
		assertArrayEquals(expectedOrder, provider.getSortedRecordIds(), "sorted record ids vs oracle");

		// 2) recordAt (tree-direct) matches the expected order for every position
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

		// 4) resolvePositions across the requested selection sizes, each mixed with a handful of absent record ids so the
		//    not-found hand-off is exercised too
		final int[] bufferA = new int[512];
		final int[] bufferB = new int[512];
		final Random random = new Random(selectionSeed);
		for (final int selectionSize : selectionSizes) {
			final int[] selectedPresent = pickDistinct(presentRecordIds, selectionSize, random);
			// two record ids guaranteed absent from the index (present ids are 1..recordCount)
			final int[] absent = {recordCount + 7, recordCount + 42};
			final int[] selectedAll = concatSorted(selectedPresent, absent);

			final PersistentRoaringBitmap selected =
				RoaringBitmapBackedBitmap.getRoaringBitmap(new BaseBitmap(selectedAll));
			final PositionResolution resolution =
				provider.resolvePositions(selected, selectedAll.length, bufferA, bufferB);

			// expected mask = the sorted set of positions of the present selected records
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
	 * Resolves `selection` through a FRESH (cold) ascending provider so a previously-warmed provider's arrays never
	 * interfere with the strategy under test.
	 */
	@Nonnull
	private static PositionResolution resolveFreshAscending(@Nonnull ChainFixture fixture, @Nonnull int[] selection) {
		return resolve(fixture.chainIndex().getAscendingOrderRecordsSupplier(), selection);
	}

	/**
	 * Resolves `selection` (with no absent padding) through the given provider.
	 */
	@Nonnull
	private static PositionResolution resolve(@Nonnull SortedRecordsProvider provider, @Nonnull int[] selection) {
		final PersistentRoaringBitmap selected =
			RoaringBitmapBackedBitmap.getRoaringBitmap(new BaseBitmap(selection));
		return provider.resolvePositions(selected, selection.length, new int[512], new int[512]);
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
	 * Builds a single consistent chain of `recordCount` records whose ascending order is a fixed random permutation of
	 * `1..recordCount`: `order[0]` is a fresh head and each `order[i]` is inserted as the successor of `order[i - 1]`,
	 * so the index stays a single collapsed chain throughout.
	 */
	@Nonnull
	private static ChainFixture buildConsistentFixture(long seed, int recordCount) {
		final int[] order = permutation(seed, recordCount);
		final ChainIndex chainIndex = new ChainIndex(new AttributeIndexKey(null, "a", null));
		chainIndex.upsertPredecessor(new Predecessor(), order[0]);
		for (int i = 1; i < recordCount; i++) {
			chainIndex.upsertPredecessor(new Predecessor(order[i - 1]), order[i]);
		}
		return new ChainFixture(chainIndex, order, ascendingIds(recordCount));
	}

	/**
	 * Builds two disjoint consistent chains — `1..firstChainLength` and `firstChainLength + 1..totalCount`, each a
	 * fresh head with its own successors and no cross-link — so the index stays inconsistent (two runs that never
	 * collapse) and the suppliers must take the array merge-walk.
	 */
	@Nonnull
	private static ChainFixture buildInconsistentFixture(int totalCount, int firstChainLength) {
		final ChainIndex chainIndex = new ChainIndex(new AttributeIndexKey(null, "a", null));
		chainIndex.upsertPredecessor(new Predecessor(), 1);
		for (int recordId = 2; recordId <= firstChainLength; recordId++) {
			chainIndex.upsertPredecessor(new Predecessor(recordId - 1), recordId);
		}
		chainIndex.upsertPredecessor(new Predecessor(), firstChainLength + 1);
		for (int recordId = firstChainLength + 2; recordId <= totalCount; recordId++) {
			chainIndex.upsertPredecessor(new Predecessor(recordId - 1), recordId);
		}
		return new ChainFixture(chainIndex, null, ascendingIds(totalCount));
	}

	/**
	 * Mixed selection sizes: four genuine tree-path sizes (`(K + 2) * 64 <= recordCount`, capped at `K = 29` for
	 * `N = 2000`) plus four dense sizes, so both the sparse probe and the dense tree walk are exercised.
	 */
	@Nonnull
	private static int[] mixedSelectionSizes(int recordCount) {
		return new int[]{1, 5, 15, 29, recordCount / 32, recordCount / 8, recordCount / 2, recordCount};
	}

	/**
	 * Returns a fixed random permutation of `1..recordCount`.
	 */
	@Nonnull
	private static int[] permutation(long seed, int recordCount) {
		final int[] result = ascendingIds(recordCount);
		final Random random = new Random(seed);
		for (int i = recordCount - 1; i > 0; i--) {
			final int j = random.nextInt(i + 1);
			final int tmp = result[i];
			result[i] = result[j];
			result[j] = tmp;
		}
		return result;
	}

	/**
	 * Returns the ascending record ids `1..count`.
	 */
	@Nonnull
	private static int[] ascendingIds(int count) {
		final int[] result = new int[count];
		for (int i = 0; i < count; i++) {
			result[i] = i + 1;
		}
		return result;
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
	 * Immutable test fixture bundling the chain index with its ascending construction order (or {@code null} for the
	 * inconsistent fixture, whose order is read from {@link ChainIndex#getUnorderedLookup()}) and the present ids.
	 */
	private record ChainFixture(
		@Nonnull ChainIndex chainIndex,
		int[] order,
		@Nonnull int[] presentRecordIds
	) {
	}

}
