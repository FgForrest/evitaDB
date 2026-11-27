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

import io.evitadb.core.transaction.Transaction;
import io.evitadb.core.transaction.memory.TransactionalLayerProducer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.ObjIntConsumer;
import java.util.function.ToIntFunction;
import java.util.stream.IntStream;

import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that a first-write shrink clears the vacated slots while preserving the original node.
 */
@DisplayName("Transactional B+ tree node shrink")
@Tag(INDEXING)
@Tag(TRANSACTION)
class TransactionalNodeShrinkTest {
	private static final int BLOCK_SIZE = 4;

	/**
	 * Checks the int-to-long routing node's separators and children.
	 */
	@Test
	@DisplayName("Int-to-long internal node clears its tail")
	void shouldClearIntToLongInternalTailOnFirstWrite() {
		final TransactionalIntToLongBPlusTree tree = new TransactionalIntToLongBPlusTree(BLOCK_SIZE, 1, 3, 1);
		insertEntries(BLOCK_SIZE + 1, key -> tree.insert(key, key * 10L));
		assertIntInternalShrink((TransactionalIntToLongBPlusTree.BPlusInternalTreeNode) tree.getRoot());
	}

	/**
	 * Checks the element tree's use of the shared int-keyed routing node.
	 */
	@Test
	@DisplayName("Element internal node clears its tail")
	void shouldClearElementInternalTailOnFirstWrite() {
		final TransactionalElementBPlusTree<Integer> tree = elementTree();
		insertEntries(BLOCK_SIZE + 1, tree::insert);
		assertIntInternalShrink((TransactionalElementBPlusTree.BPlusInternalTreeNode) tree.getRoot());
	}

	/**
	 * Checks the long routing node's separators and children.
	 */
	@Test
	@DisplayName("Long internal node clears its tail")
	void shouldClearLongInternalTailOnFirstWrite() {
		final TransactionalLongBPlusTree<String> tree = new TransactionalLongBPlusTree<>(BLOCK_SIZE, String.class);
		insertEntries(BLOCK_SIZE + 1, key -> tree.insert(key, "value" + key));
		assertShrink(
			(TransactionalLongBPlusTree.BPlusInternalTreeNode) tree.getRoot(),
			TransactionalLongBPlusTree.BPlusInternalTreeNode::getPeek,
			TransactionalLongBPlusTree.BPlusInternalTreeNode::setPeek,
			node -> List.of(
				new Slots(Arrays.stream(node.getKeys()).boxed().toList(), 0, 0L),
				new Slots(Arrays.asList(node.getChildren()), 1, null)
			)
		);
	}

	/**
	 * Checks the object routing node's separators and children.
	 */
	@Test
	@DisplayName("Object internal node clears its tail")
	void shouldClearObjectInternalTailOnFirstWrite() {
		final TransactionalObjectBPlusTree<Integer, String> tree = objectTree();
		insertEntries(BLOCK_SIZE + 1, key -> tree.insert(key, "value" + key));
		assertShrink(
			(TransactionalObjectBPlusTree.BPlusInternalTreeNode<?>) tree.getRoot(),
			TransactionalObjectBPlusTree.BPlusInternalTreeNode::getPeek,
			TransactionalObjectBPlusTree.BPlusInternalTreeNode::setPeek,
			node -> List.of(
				new Slots(Arrays.asList(node.getKeys()), 0, null),
				new Slots(Arrays.asList(node.getChildren()), 1, null)
			)
		);
	}

	/**
	 * Checks the bucket routing node's separators and children.
	 */
	@Test
	@DisplayName("Bucket internal node clears its tail")
	void shouldClearBucketInternalTailOnFirstWrite() {
		final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(
			BLOCK_SIZE, Integer.class, null
		);
		insertEntries(BLOCK_SIZE + 1, key -> tree.addRecord(key, key * 10));
		assertShrink(
			(TransactionalBucketBPlusTree.BPlusInternalTreeNode<Integer>) tree.getRoot(),
			TransactionalBucketBPlusTree.BPlusInternalTreeNode::getPeek,
			TransactionalBucketBPlusTree.BPlusInternalTreeNode::setPeek,
			node -> List.of(
				new Slots(Arrays.asList(node.getKeys()), 0, null),
				new Slots(Arrays.asList(node.getChildren()), 1, null)
			)
		);
	}

	/**
	 * Checks both primitive arrays in an int-to-long leaf.
	 */
	@Test
	@DisplayName("Int-to-long leaf clears its tail")
	void shouldClearIntToLongLeafTailOnFirstWrite() {
		final TransactionalIntToLongBPlusTree tree = new TransactionalIntToLongBPlusTree(BLOCK_SIZE, 1, 3, 1);
		insertEntries(3, key -> tree.insert(key, key * 10L));
		assertShrink(
			(TransactionalIntToLongBPlusTree.BPlusLeafTreeNode) tree.getRoot(),
			TransactionalIntToLongBPlusTree.BPlusLeafTreeNode::getPeek,
			TransactionalIntToLongBPlusTree.BPlusLeafTreeNode::setPeek,
			node -> List.of(
				new Slots(Arrays.stream(node.getKeys()).boxed().toList(), 1, 0),
				new Slots(Arrays.stream(node.getValues()).boxed().toList(), 1, 0L)
			)
		);
	}

	/**
	 * Checks the element leaf's single array.
	 */
	@Test
	@DisplayName("Element leaf clears its tail")
	void shouldClearElementLeafTailOnFirstWrite() {
		final TransactionalElementBPlusTree<Integer> tree = elementTree();
		insertEntries(3, tree::insert);
		assertShrink(
			(TransactionalElementBPlusTree.BPlusLeafTreeNode<?>) tree.getRoot(),
			TransactionalElementBPlusTree.BPlusLeafTreeNode::getPeek,
			TransactionalElementBPlusTree.BPlusLeafTreeNode::setPeek,
			node -> List.of(new Slots(Arrays.asList(node.getValues()), 1, null))
		);
	}

	/**
	 * Checks a long leaf's primitive keys and object values.
	 */
	@Test
	@DisplayName("Long leaf clears its tail")
	void shouldClearLongLeafTailOnFirstWrite() {
		final TransactionalLongBPlusTree<String> tree = new TransactionalLongBPlusTree<>(BLOCK_SIZE, String.class);
		insertEntries(3, key -> tree.insert(key, "value" + key));
		assertShrink(
			(TransactionalLongBPlusTree.BPlusLeafTreeNode<?>) tree.getRoot(),
			TransactionalLongBPlusTree.BPlusLeafTreeNode::getPeek,
			TransactionalLongBPlusTree.BPlusLeafTreeNode::setPeek,
			node -> List.of(
				new Slots(Arrays.stream(node.getKeys()).boxed().toList(), 1, 0L),
				new Slots(Arrays.asList(node.getValues()), 1, null)
			)
		);
	}

	/**
	 * Checks both object arrays in an object leaf.
	 */
	@Test
	@DisplayName("Object leaf clears its tail")
	void shouldClearObjectLeafTailOnFirstWrite() {
		final TransactionalObjectBPlusTree<Integer, String> tree = objectTree();
		insertEntries(3, key -> tree.insert(key, "value" + key));
		assertShrink(
			(TransactionalObjectBPlusTree.BPlusLeafTreeNode<?, ?>) tree.getRoot(),
			TransactionalObjectBPlusTree.BPlusLeafTreeNode::getPeek,
			TransactionalObjectBPlusTree.BPlusLeafTreeNode::setPeek,
			node -> List.of(
				new Slots(Arrays.asList(node.getKeys()), 1, null),
				new Slots(Arrays.asList(node.getValues()), 1, null)
			)
		);
	}

	/**
	 * Checks the bucket leaf as the reference implementation for first-write truncation.
	 */
	@Test
	@DisplayName("Bucket leaf clears its tail")
	void shouldClearBucketLeafTailOnFirstWrite() {
		final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(
			BLOCK_SIZE, Integer.class, null
		);
		insertEntries(3, key -> tree.addRecord(key, key * 10));
		assertShrink(
			(TransactionalBucketBPlusTree.BPlusLeafTreeNode<Integer>) tree.getRoot(),
			TransactionalBucketBPlusTree.BPlusLeafTreeNode::getPeek,
			TransactionalBucketBPlusTree.BPlusLeafTreeNode::setPeek,
			node -> List.of(
				new Slots(Arrays.asList(node.getKeys()), 1, null),
				new Slots(IntStream.range(0, node.getRecords().capacity()).map(node.getRecords()::intAt)
					.boxed().toList(), 1, 0)
			)
		);
	}

	/**
	 * Creates a small element tree.
	 *
	 * @return the empty tree
	 */
	@Nonnull
	private static TransactionalElementBPlusTree<Integer> elementTree() {
		return new TransactionalElementBPlusTree<>(BLOCK_SIZE, 1, 3, 1, Integer.class, Integer::intValue);
	}

	/**
	 * Creates a small object tree.
	 *
	 * @return the empty tree
	 */
	@Nonnull
	private static TransactionalObjectBPlusTree<Integer, String> objectTree() {
		return new TransactionalObjectBPlusTree<>(BLOCK_SIZE, Integer.class, String.class, null);
	}

	/**
	 * Populates a committed tree with nonzero keys.
	 *
	 * @param count the number of entries
	 * @param insert the insertion operation
	 */
	private static void insertEntries(int count, @Nonnull IntConsumer insert) {
		for (int key = 1; key <= count; key++) {
			insert.accept(key);
		}
	}

	/**
	 * Checks the internal node layout shared by both int-keyed trees.
	 *
	 * @param node the committed root
	 * @param <N> the concrete internal node type
	 */
	private static <N extends AbstractIntKeyedInternalNode<N>> void assertIntInternalShrink(@Nonnull N node) {
		assertShrink(
			node, AbstractIntKeyedInternalNode::getPeek, AbstractIntKeyedInternalNode::setPeek,
			current -> List.of(
				new Slots(Arrays.stream(current.getKeys()).boxed().toList(), 0, 0),
				new Slots(Arrays.asList(current.getChildren()), 1, null)
			)
		);
	}

	/**
	 * Commits the root node directly so a first-write shrink can be observed before the rebalancer can discard the
	 * layer. Checks both snapshots even if an assertion about one of them fails.
	 *
	 * @param node the committed root of the populated tree
	 * @param peek reads the node's last occupied slot
	 * @param shrink changes the node's last occupied slot
	 * @param readSlots reads the backing slots, including the tail, in a stable order
	 * @param <N> the concrete node type
	 */
	private static <N extends TransactionalLayerProducer<N, N>> void assertShrink(
		@Nonnull N node,
		@Nonnull ToIntFunction<N> peek,
		@Nonnull ObjIntConsumer<N> shrink,
		@Nonnull Function<N, List<Slots>> readSlots
	) {
		final int originalPeek = peek.applyAsInt(node);
		assertTrue(originalPeek > 0);
		final List<Slots> originalSlots = readSlots.apply(node);
		final List<Slots> layerSlots = new ArrayList<>();
		assertStateAfterCommit(
			node,
			original -> {
				assertNull(Transaction.getTransactionalMemoryLayerIfExists(original));
				shrink.accept(original, 0);
				final N layer = Transaction.getTransactionalMemoryLayerIfExists(original);
				assertNotNull(layer);
				assertEquals(0, peek.applyAsInt(layer));
				layerSlots.addAll(readSlots.apply(layer));
			},
			(original, committed) -> {
				assertEquals(originalPeek, peek.applyAsInt(original));
				assertEquals(originalSlots, readSlots.apply(original));
				assertEquals(0, peek.applyAsInt(committed));
				final List<Slots> committedSlots = readSlots.apply(committed);
				assertAll(
					"node snapshots",
					() -> assertSlots(originalSlots, layerSlots, "transaction"),
					() -> assertSlots(originalSlots, committedSlots, "commit")
				);
			}
		);
	}

	/**
	 * Verifies the retained prefix and every slot past it in each backing array or column.
	 *
	 * @param original the slots before shrinking
	 * @param shrunk the slots after shrinking
	 * @param stage the snapshot under test
	 */
	private static void assertSlots(
		@Nonnull List<Slots> original, @Nonnull List<Slots> shrunk, @Nonnull String stage
	) {
		assertAll(stage, IntStream.range(0, shrunk.size()).mapToObj(index -> () -> {
			final Slots column = shrunk.get(index);
			final int liveSize = column.liveSize();
			final List<?> slots = column.values();
			assertEquals(original.get(index).values().subList(0, liveSize), slots.subList(0, liveSize),
				"live prefix of array " + index);
			assertEquals(
				Collections.nCopies(slots.size() - liveSize, column.empty()),
				slots.subList(liveSize, slots.size()), "vacated tail of array " + index
			);
		}));
	}

	/**
	 * The physical contents of an array or column and its expected layout after shrinking.
	 *
	 * @param values the backing slots
	 * @param liveSize the expected live prefix length
	 * @param empty the empty slot marker
	 */
	private record Slots(@Nonnull List<?> values, int liveSize, @Nullable Object empty) {
		/**
		 * Captures the backing slots at the time of the read.
		 *
		 * @param values the backing slots
		 * @param liveSize the expected live prefix length
		 * @param empty the empty slot marker
		 */
		private Slots {
			values = new ArrayList<>(values);
		}
	}
}
