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

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.function.Function;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Shared fixture for the `UnpublishedChildSlot` tests of the fixed-array B+ trees - {@link TransactionalLongBPlusTree},
 * {@link TransactionalElementBPlusTree} and {@link TransactionalIntToLongBPlusTree}. The bucket tree has its own copy
 * in `TransactionalBucketBPlusTreeTest`, because its internal nodes grow their arrays and so need a slack check these
 * trees do not.
 *
 * An internal node of these trees grows in place by storing the new child pointers (and the separator) and only then
 * raising `peek`, as plain stores, and shrinks by nulling the vacated slots around lowering `peek`. A reader that
 * shares no happens-before edge with that writer can therefore hold a `peek` whose last slot reads `null`: either it
 * sees the raised `peek` before the child store that precedes it, or it loaded `peek` before a concurrent removal
 * nulled the slot. {@link #tear} builds that state deterministically: {@link BPlusTreeNode#setPeek} raised outside a
 * transaction moves `peek` up without touching either array, which is exactly what such a reader observes.
 *
 * The internal-node arrays of these trees are allocated at `blockSize + 1` once and never resized, so a raised `peek`
 * never runs off the array - the slot it admits is inside the array and reads `null`. That is the only half of the
 * bucket tree's hazard these trees can have.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
final class UnpublishedChildSlotSupport {

	private UnpublishedChildSlotSupport() {
		throw new UnsupportedOperationException("This class cannot be instantiated!");
	}

	/**
	 * Picks the root when it sits directly above the leaves and has room for one more child - the shape a split of
	 * the root's last leaf grows in place.
	 *
	 * @param root the root of the tree
	 * @return the root, or `null` when it does not have that shape
	 */
	@Nullable
	static InternalBPlusTreeNode<?> rootAboveLeaves(@Nonnull BPlusTreeNode<?> root) {
		if (root instanceof InternalBPlusTreeNode<?> internal
			&& !(internal.getChildren()[0] instanceof InternalBPlusTreeNode<?>)
			&& hasSlack(internal)) {
			return internal;
		}
		return null;
	}

	/**
	 * Picks an internal node **below** the root that sits directly above the leaves and has room for one more child,
	 * so the heap walk meets the unpublished slot one recursion level down rather than in the node it started from.
	 *
	 * @param root the root of the tree
	 * @return such a node, or `null` when the tree has none
	 */
	@Nullable
	static InternalBPlusTreeNode<?> innerNodeAboveLeaves(@Nonnull BPlusTreeNode<?> root) {
		if (root instanceof InternalBPlusTreeNode<?> rootNode) {
			final BPlusTreeNode<?>[] children = rootNode.getChildren();
			for (int i = 0; i <= rootNode.getPeek(); i++) {
				if (children[i] instanceof InternalBPlusTreeNode<?> inner
					&& !(inner.getChildren()[0] instanceof InternalBPlusTreeNode<?>)
					&& hasSlack(inner)) {
					return inner;
				}
			}
		}
		return null;
	}

	/**
	 * Builds trees of a growing number of keys until `selector` finds the node shape a test needs. The trees are
	 * built outside a transaction, so every node is mutated in place, exactly as a warm-up load builds them.
	 *
	 * @param treeOfKeys builds a tree holding the given number of keys
	 * @param maxKeys    the largest tree to try before giving up
	 * @param selector   picks the node to tear, or answers `null` when this tree has none
	 * @param <T>        the tree type
	 * @return the tree and the node picked in it
	 */
	@Nonnull
	static <T extends AbstractTransactionalBPlusTree> TornSpine<T> fixture(
		@Nonnull IntFunction<T> treeOfKeys,
		int maxKeys,
		@Nonnull Function<BPlusTreeNode<?>, InternalBPlusTreeNode<?>> selector
	) {
		for (int keyCount = 1; keyCount <= maxKeys; keyCount++) {
			final T tree = treeOfKeys.apply(keyCount);
			final InternalBPlusTreeNode<?> selected = selector.apply(tree.getRoot());
			if (selected != null) {
				return new TornSpine<>(tree, selected);
			}
		}
		throw new IllegalStateException("No tree of up to " + maxKeys + " keys has the node shape this fixture needs.");
	}

	/**
	 * Raises the node's `peek` by one without publishing the child - the state a session-free reader observes when
	 * the writer's `peek` store overtakes its child store, or when it loaded `peek` before a concurrent removal nulled
	 * the last slot.
	 *
	 * @param node the node to tear
	 */
	static void tear(@Nonnull InternalBPlusTreeNode<?> node) {
		final int peek = node.getPeek();
		node.setPeek(peek + 1);
		assertEquals(peek + 1, node.getPeek(), "the fixture must raise peek");
		assertNull(
			node.getChildren()[peek + 1], "the admitted slot must read null - that unpublished slot IS the defect"
		);
	}

	/**
	 * Asserts the structural precondition under which a heap walk that steps over a `null` child is an identity:
	 * every slot in `[0, peek]` of every internal node holds a child. Read outside a transaction, so it inspects the
	 * very fields the heap walk reads.
	 *
	 * @param node the subtree root to inspect
	 */
	static void assertEveryLiveChildSlotPopulated(@Nonnull BPlusTreeNode<?> node) {
		if (node instanceof InternalBPlusTreeNode<?> internal) {
			final BPlusTreeNode<?>[] children = internal.getChildren();
			for (int i = 0; i <= internal.getPeek(); i++) {
				final BPlusTreeNode<?> child = children[i];
				final int slot = i;
				assertNotNull(
					child, () -> "slot " + slot + " of a consistent internal node must be populated, peek " +
						internal.getPeek()
				);
				assertEveryLiveChildSlotPopulated(child);
			}
		}
	}

	/**
	 * Whether the node has room for one more child without growing - the state a raised `peek` must be observed in for
	 * the admitted slot to read `null`. These trees never grow the array, so a full node simply cannot be torn.
	 *
	 * @param node the node to test
	 * @return true when the child array carries a slot past the live run
	 */
	private static boolean hasSlack(@Nonnull InternalBPlusTreeNode<?> node) {
		return node.getPeek() + 1 < node.getChildren().length;
	}

	/**
	 * A tree and the internal node in it that is about to be torn.
	 *
	 * @param tree the tree
	 * @param node the internal node whose `peek` will run ahead of its published children
	 * @param <T>  the tree type
	 */
	record TornSpine<T extends AbstractTransactionalBPlusTree>(
		@Nonnull T tree,
		@Nonnull InternalBPlusTreeNode<?> node
	) {
	}

}
