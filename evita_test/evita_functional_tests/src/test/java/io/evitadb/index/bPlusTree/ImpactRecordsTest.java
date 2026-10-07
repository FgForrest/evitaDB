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

package io.evitadb.index.bPlusTree;

import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.bPlusTree.ImpactRecords.PendingImpacts;
import io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree.BPlusLeafTreeNode;
import io.evitadb.utils.VMLayout;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.function.ToLongFunction;

import static io.evitadb.test.TestTags.DATA_TYPE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the memory accounting of the impact column of a {@link TransactionalBucketBPlusTree}: what
 * {@link ImpactRecords#heapSizeInBytes(Object)} charges for every slot shape, and that a leaf's own heap size
 * includes its impact column - a figure the engine's memory reporting is built from, and which no correctness test
 * would notice going missing.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(INDEXING)
@Tag(DATA_TYPE)
@Tag(TRANSACTION)
@DisplayName("Impact records memory accounting")
class ImpactRecordsTest {
	/**
	 * Prices a boxed key - the same for both trees a comparison builds, so any constant does.
	 */
	private static final ToLongFunction<Object> KEY_SIZER = key -> 16L;

	/**
	 * Fills a tree with three buckets, one per committed impact tier: key 1 a single record, key 2 a small sorted
	 * array, key 3 a bitmap spanning two roaring containers.
	 *
	 * @param tree      the tree to fill
	 * @param carrying  whether the tree carries impacts, which decides the insert API
	 */
	private static void fillWithEveryTier(@Nonnull TransactionalBucketBPlusTree<Integer> tree, boolean carrying) {
		add(tree, carrying, 1, 10, 1);
		for (int i = 0; i < 3; i++) {
			add(tree, carrying, 2, 20 + i, 2 + i);
		}
		for (int i = 0; i <= OverflowRecords.SMALL_BUCKET_THRESHOLD; i++) {
			add(tree, carrying, 3, i < 64 ? i : 65_536 + i, i);
		}
	}

	/**
	 * Adds a record through the insert API the tree accepts.
	 *
	 * @param tree     the tree
	 * @param carrying whether the tree carries impacts
	 * @param key      the bucket key
	 * @param pk       the record id
	 * @param impact   the record's impact, ignored by a plain tree
	 */
	private static void add(
		@Nonnull TransactionalBucketBPlusTree<Integer> tree, boolean carrying, int key, int pk, int impact
	) {
		if (carrying) {
			tree.addRecord(key, pk, (byte) impact);
		} else {
			tree.addRecord(key, pk);
		}
	}

	/**
	 * Reads the raw impact slot of `key`'s bucket.
	 *
	 * @param tree the tree to look into
	 * @param key  the bucket key
	 * @return the slot content
	 */
	@Nullable
	private static Object impactSlot(@Nonnull TransactionalBucketBPlusTree<Integer> tree, int key) {
		final BPlusLeafTreeNode<Integer> leaf = tree.findLeafNode(key);
		final OverflowColumn impacts = leaf.getImpacts();
		assertNotNull(impacts);
		return impacts.recordsAt(leaf.getValueIndex(key));
	}

	@Nested
	@DisplayName("Slot pricing")
	class SlotPricing {

		@Test
		@DisplayName("a cached Byte and an empty array cost nothing, a flat array costs its own allocation")
		void shouldPriceSingleAndArraySlots() {
			final VMLayout layout = VMLayout.current();
			assertEquals(0L, ImpactRecords.heapSizeInBytes(Byte.valueOf((byte) 7)));
			assertEquals(0L, ImpactRecords.heapSizeInBytes(new byte[0]));
			assertEquals(layout.sizeOfArray(5, Byte.BYTES), ImpactRecords.heapSizeInBytes(new byte[5]));
		}

		@Test
		@DisplayName("chunks cost their reference array plus every chunk")
		void shouldPriceChunkedSlots() {
			final VMLayout layout = VMLayout.current();
			final byte[][] chunks = {new byte[3], new byte[70]};
			assertEquals(
				layout.sizeOfArray(2, layout.referenceSize())
					+ layout.sizeOfArray(3, Byte.BYTES) + layout.sizeOfArray(70, Byte.BYTES),
				ImpactRecords.heapSizeInBytes(chunks)
			);
		}

		@Test
		@DisplayName("a pending delta costs more than the chunks it wraps, and more again while it keeps an undo log")
		void shouldPricePendingDeltaAndItsUndoLog() {
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(16, Integer.class);
			tree.enableImpacts();
			fillWithEveryTier(tree, true);
			final Object committedChunks = impactSlot(tree, 3);
			assertInstanceOf(byte[][].class, committedChunks);
			final long chunksSize = ImpactRecords.heapSizeInBytes(committedChunks);
			assertStateAfterRollback(
				tree,
				t -> {
					t.addRecord(3, 1_000, (byte) 9);
					final PendingImpacts pending = assertInstanceOf(PendingImpacts.class, impactSlot(t, 3));
					final long withoutLog = ImpactRecords.heapSizeInBytes(pending);
					assertTrue(withoutLog > chunksSize, "the delta wraps the chunks and adds its own state");
					pending.mark();
					pending.put(1_000, (byte) 10);
					assertTrue(
						ImpactRecords.heapSizeInBytes(pending) > withoutLog, "an open undo log must be priced"
					);
					pending.release();
					assertEquals(withoutLog, ImpactRecords.heapSizeInBytes(pending), "a released log costs nothing");
				},
				(original, committed) -> assertInstanceOf(byte[][].class, impactSlot(original, 3))
			);
		}

		@Test
		@DisplayName("a slot no tier produces is refused")
		void shouldRefuseForeignSlot() {
			assertThrows(GenericEvitaInternalError.class, () -> ImpactRecords.heapSizeInBytes("not a slot"));
		}
	}

	@Nested
	@DisplayName("Leaf pricing")
	class LeafPricing {

		@Test
		@DisplayName("a leaf's heap size includes its impact column and every slot in it")
		void shouldPriceImpactColumnIntoLeaf() {
			final TransactionalBucketBPlusTree<Integer> impactTree =
				new TransactionalBucketBPlusTree<>(16, Integer.class);
			impactTree.enableImpacts();
			fillWithEveryTier(impactTree, true);
			final TransactionalBucketBPlusTree<Integer> plainTree =
				new TransactionalBucketBPlusTree<>(16, Integer.class);
			fillWithEveryTier(plainTree, false);

			final List<BPlusLeafTreeNode<Integer>> impactLeaves = impactTree.enumerateLeaves();
			final List<BPlusLeafTreeNode<Integer>> plainLeaves = plainTree.enumerateLeaves();
			assertEquals(1, impactLeaves.size(), "three buckets fit one leaf");
			assertEquals(1, plainLeaves.size(), "three buckets fit one leaf");
			final BPlusLeafTreeNode<Integer> leaf = impactLeaves.get(0);
			final OverflowColumn impacts = leaf.getImpacts();
			assertNotNull(impacts);
			long expectedImpactCost = impacts.getHeapSizeInBytes();
			for (int slot = 0; slot <= leaf.getPeek(); slot++) {
				expectedImpactCost += ImpactRecords.heapSizeInBytes(impacts.recordsAt(slot));
			}
			assertTrue(expectedImpactCost > impacts.getHeapSizeInBytes(), "the array and chunk slots cost heap");
			assertEquals(
				expectedImpactCost,
				leaf.getHeapSizeInBytes(KEY_SIZER) - plainLeaves.get(0).getHeapSizeInBytes(KEY_SIZER),
				"the impact-carrying leaf must cost exactly its impact column more than the same leaf without one"
			);
		}
	}
}
