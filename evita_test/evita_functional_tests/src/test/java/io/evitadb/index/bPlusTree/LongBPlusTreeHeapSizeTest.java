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

import io.evitadb.utils.JolHeapSize;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;

import static io.evitadb.test.TestTags.INDEXING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the heap-size walk over {@link TransactionalLongBPlusTree} — the substrate of
 * {@link io.evitadb.index.range.RangeIndex}.
 *
 * This tree differs from the bucket tree in the two places that matter to the accounting, so it gets its own
 * assertions rather than riding on that one's:
 *
 * - its separator keys are `long` **values**, not boxed objects, so an internal node has nothing for the element
 *   sizer to price and no risk of counting a key twice;
 * - its leaves hold a genuine `V[]` payload, so a leaf really can own the objects it points at — which is exactly
 *   the case the sizer exists to let the caller decide.
 *
 * As in `BucketBPlusTreeHeapSizeTest`, the assertion runs against the node graph rather than the tree object: the
 * tree holds a `Function` lambda, and a lambda is a hidden class whose field offsets JOL refuses to read.
 *
 * @author Claude (B+ tree heap-size verification), FG Forrest a.s. (c) 2026
 */
@Tag(INDEXING)
@DisplayName("Long-keyed B+ tree heap-size reporting")
class LongBPlusTreeHeapSizeTest {
	/**
	 * Leaf block size — small enough that a few hundred entries build a genuinely multi-level tree.
	 */
	private static final int BLOCK_SIZE = 16;

	/**
	 * Builds a long-keyed tree holding {@link String} values.
	 *
	 * @param entries how many key-value pairs to insert
	 * @return the populated tree
	 */
	@Nonnull
	private static TransactionalLongBPlusTree<String> buildTree(int entries) {
		// the constructor enforces three constraints at once: each minimum strictly below half its block size, an
		// ODD internal block size, and the internal block size not exceeding the value block size
		final TransactionalLongBPlusTree<String> tree = new TransactionalLongBPlusTree<>(
			BLOCK_SIZE, BLOCK_SIZE / 2 - 1, BLOCK_SIZE - 1, BLOCK_SIZE / 2 - 1, String.class
		);
		for (int i = 0; i < entries; i++) {
			tree.insert(i, "value-" + i);
		}
		return tree;
	}

	/**
	 * Asserts the walk over every node matches a JOL measurement of the same graph, with the stored values priced
	 * by the sizer so both sides account for the leaf payload identically.
	 *
	 * @param tree the tree to check
	 */
	private static void assertNodeGraphMatchesMeasuredHeap(@Nonnull TransactionalLongBPlusTree<String> tree) {
		assertEquals(
			JolHeapSize.ownedSize(tree.getRoot()),
			tree.getNodeGraphHeapSizeInBytes(JolHeapSize::ownedSize)
		);
	}

	@Nested
	@DisplayName("matches the measured heap")
	class MeasuredExactness {

		@Test
		void shouldMatchMeasuredHeapForSingleLeafTree() {
			assertNodeGraphMatchesMeasuredHeap(buildTree(5));
		}

		@Test
		void shouldMatchMeasuredHeapForMultiLevelTree() {
			assertNodeGraphMatchesMeasuredHeap(buildTree(500));
		}
	}

	@Nested
	@DisplayName("leaves the payload policy to the caller")
	class PayloadOwnership {

		@Test
		void shouldExcludeStoredValuesByDefault() {
			final TransactionalLongBPlusTree<String> tree = buildTree(500);

			// the default walk charges the reference slots holding the values, never the values themselves - the
			// leaf cannot know whether the tree owns them or borrows them from somewhere else
			final long withoutValues = tree.getHeapSizeInBytes();
			final long withValues = tree.getHeapSizeInBytes(JolHeapSize::ownedSize);
			assertTrue(
				withValues > withoutValues,
				"pricing the stored values must add to the figure, otherwise the sizer does nothing"
			);
		}

		@Test
		void shouldAddOnlyTheTreesOwnObjectOnTopOfItsNodeGraph() {
			final TransactionalLongBPlusTree<String> tree = buildTree(500);
			final long own = tree.getHeapSizeInBytes() - tree.getNodeGraphHeapSizeInBytes(element -> 0L);
			assertTrue(own > 0 && own < 256, "the tree's own object should be a small constant, was " + own);

			final TransactionalLongBPlusTree<String> larger = buildTree(5_000);
			final long largerOwn = larger.getHeapSizeInBytes()
				- larger.getNodeGraphHeapSizeInBytes(element -> 0L);
			assertEquals(own, largerOwn, "the tree's own object must not grow with the tree");
		}
	}
}
