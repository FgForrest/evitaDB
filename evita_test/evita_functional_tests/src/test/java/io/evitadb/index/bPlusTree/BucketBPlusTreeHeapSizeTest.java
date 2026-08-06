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
import java.util.Comparator;

import static io.evitadb.test.TestTags.INDEXING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link TransactionalBucketBPlusTree#getHeapSizeInBytes()} — the recursive walk over internal nodes,
 * leaves, both leaf columns and the overflow bitmaps — against JOL rather than against restated arithmetic.
 *
 * # Why this test is the load-bearing one
 *
 * The column figures are checked in `ColumnHeapSizeTest`; what cannot be checked there is whether the *composition*
 * is right — whether the walk reaches every node exactly once, charges the arrays it owns, and stops at the objects
 * it merely borrows. A tree is where an ownership mistake compounds: one over-counted shared object becomes one per
 * node, and at the block sizes production uses that is thousands of copies of the same error.
 *
 * @author Claude (B+ tree heap-size verification), FG Forrest a.s. (c) 2026
 */
@Tag(INDEXING)
@DisplayName("Bucket B+ tree heap-size reporting")
class BucketBPlusTreeHeapSizeTest {
	/**
	 * Leaf block size — small enough that a few hundred keys build a genuinely multi-level tree.
	 */
	private static final int BLOCK_SIZE = 16;

	/**
	 * Builds a bucket tree keyed by {@link Integer} in natural order, so the leaves use the primitive
	 * {@link LongValueColumn} — the representative production shape for `InvertedIndex`.
	 *
	 * @param distinctValues  the number of distinct keys (buckets)
	 * @param recordsPerValue records per bucket; more than one forces the overflow bitmap
	 * @return the populated tree
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	@Nonnull
	private static TransactionalBucketBPlusTree buildTree(int distinctValues, int recordsPerValue) {
		final int minBlock = BLOCK_SIZE / 2 - 1;
		final int minInternal = (int) (Math.ceil((float) minBlock / 2.0) - 1);
		final ValueColumnFactory factory = ValueColumnFactory.forKey(
			Integer.class, (Comparator) Comparator.naturalOrder()
		);
		final TransactionalBucketBPlusTree tree = new TransactionalBucketBPlusTree<>(
			BLOCK_SIZE, minBlock, minBlock, minInternal,
			Comparable.class, (Comparator) Comparator.naturalOrder(), factory
		);
		for (int i = 0; i < distinctValues; i++) {
			final Integer key = 2 * i;
			if (recordsPerValue == 1) {
				tree.addRecord((Comparable) key, i);
			} else {
				final int[] recordIds = new int[recordsPerValue];
				for (int r = 0; r < recordsPerValue; r++) {
					recordIds[r] = i * recordsPerValue + r;
				}
				tree.addRecord((Comparable) key, recordIds);
			}
		}
		return tree;
	}

	/**
	 * The objects a tree reaches but does not own: the key type, the comparator handed to every node, and the codec
	 * enum constant every primitive column shares.
	 *
	 * @return the roots to subtract from a JOL walk
	 */
	@Nonnull
	private static Object[] sharedRoots() {
		return new Object[]{
			Comparable.class,
			Integer.class,
			Comparator.naturalOrder(),
			LongKeyCodec.forType(Integer.class)
		};
	}

	/**
	 * Measures the tree's whole node graph — every internal node, leaf, column and overflow bitmap — against the
	 * walk. This is asserted from the **root node** rather than from the tree, deliberately.
	 *
	 * The tree object holds two `ValueColumnFactory` / `RecordColumnFactory` lambdas. Lambdas are *hidden classes*,
	 * and JOL cannot read a hidden class's field offsets — a walk that reaches one dies with "Cannot get the field
	 * offset", and no `--add-opens` changes that because the restriction is not a package-access one. Passing them
	 * as shared roots does not help either, since JOL must parse a root to subtract it. So the tree object itself is
	 * unmeasurable by JOL, while everything below it — which is all of the recursion, and all of the risk — is not.
	 *
	 * @param tree the tree whose node graph to check
	 */
	private static void assertNodeGraphMatchesMeasuredHeap(@Nonnull TransactionalBucketBPlusTree<?> tree) {
		// the sizer prices the internal nodes' boxed separator keys, which JOL also walks into. These trees key on
		// Integer in natural order, so their LEAVES use the primitive LongValueColumn and hold no boxed key at all
		// - the separators are the internal nodes' own objects, and a boxed Integer is charged in full whether or
		// not the JVM happened to hand back a cached instance
		assertEquals(
			JolHeapSize.ownedSize(tree.getRoot(), sharedRoots()),
			tree.getNodeGraphHeapSizeInBytes(JolHeapSize::shallowSize)
		);
	}

	@Nested
	@DisplayName("matches the measured heap")
	class MeasuredExactness {

		@Test
		void shouldMatchMeasuredHeapForSingleLeafTree() {
			assertNodeGraphMatchesMeasuredHeap(buildTree(5, 1));
		}

		@Test
		void shouldMatchMeasuredHeapForMultiLevelTree() {
			// well past one block, so internal nodes and many leaves are in play
			assertNodeGraphMatchesMeasuredHeap(buildTree(500, 1));
		}

		@Test
		void shouldMatchMeasuredHeapWithOverflowBitmaps() {
			// every bucket holds several records, so each leaf allocates its lazy overflow array of bitmaps
			assertNodeGraphMatchesMeasuredHeap(buildTree(300, 8));
		}

		@Test
		void shouldAddOnlyTheTreesOwnObjectOnTopOfItsNodeGraph() {
			final TransactionalBucketBPlusTree<?> tree = buildTree(500, 1);

			// the tree's own contribution is a handful of scalars, two TransactionalReference holders and their
			// AtomicReferences - a small constant that must not scale with the data
			final long own = tree.getHeapSizeInBytes() - tree.getNodeGraphHeapSizeInBytes(element -> 0L);
			assertTrue(own > 0 && own < 256, "the tree's own object should be a small constant, was " + own);

			final TransactionalBucketBPlusTree<?> larger = buildTree(5_000, 1);
			final long largerOwn = larger.getHeapSizeInBytes()
				- larger.getNodeGraphHeapSizeInBytes(element -> 0L);
			assertEquals(own, largerOwn, "the tree's own object must not grow with the tree");
		}
	}

	@Nested
	@DisplayName("composes the layers beneath it")
	class Composition {

		@Test
		void shouldGrowWithTheOverflowBitmapsItPrices() {
			final TransactionalBucketBPlusTree<?> single = buildTree(300, 1);
			final TransactionalBucketBPlusTree<?> multi = buildTree(300, 8);

			// the same bucket count, but the multi-record tree carries a TransactionalBitmap per bucket - which the
			// benchmark shows is what dominates the walk's cost, so it had better dominate the figure too
			assertTrue(
				multi.getHeapSizeInBytes() > 2 * single.getHeapSizeInBytes(),
				"the overflow bitmaps must be reflected in the reported footprint"
			);
		}

		@Test
		void shouldScaleWithTheNumberOfBuckets() {
			final TransactionalBucketBPlusTree<?> small = buildTree(100, 1);
			final TransactionalBucketBPlusTree<?> large = buildTree(1_000, 1);

			assertTrue(large.getHeapSizeInBytes() > small.getHeapSizeInBytes());
			assertNodeGraphMatchesMeasuredHeap(large);
		}
	}
}
