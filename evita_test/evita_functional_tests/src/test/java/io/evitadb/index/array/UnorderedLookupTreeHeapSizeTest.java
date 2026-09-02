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

package io.evitadb.index.array;

import io.evitadb.utils.JolHeapSize;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;

import static io.evitadb.test.TestTags.DATA_TYPE;
import static io.evitadb.test.TestTags.INDEXING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the heap-size walk over {@link UnorderedLookupTree} — the order-statistic tree beneath
 * {@link TransactionalUnorderedIntArray}, and through it beneath `SortIndex` and `ChainIndex`.
 *
 * # Why this one is measurable end to end
 *
 * Unlike the B+ tree family, neither this tree nor its nodes hold a lambda anywhere: every field is a primitive, a
 * primitive array or a child reference. So JOL can walk the whole object — the tree, its two reference wrappers and
 * the entire node graph — and the assertion can be made against the public figure directly rather than against a
 * carved-out subgraph.
 *
 * # What the assertions have to catch
 *
 * The two arrays that only exist on a head-aware tree (`headMask` per leaf, `headCounts` per internal node) are the
 * error-prone part: charging them unconditionally would inflate every `SortIndex`, which is never head-aware, and
 * skipping them would under-report every `ChainIndex`, which always is. Both variants are therefore measured.
 *
 * @author Claude (heap-size verification), FG Forrest a.s. (c) 2026
 */
@Tag(INDEXING)
@Tag(DATA_TYPE)
@DisplayName("Unordered lookup tree heap-size reporting")
class UnorderedLookupTreeHeapSizeTest {
	/**
	 * Small fan-out so a few hundred records already build a genuinely multi-level tree.
	 */
	private static final int BLOCK_SIZE = 16;

	/**
	 * Order-key spacing — the production default is irrelevant here, any gap wide enough to avoid re-spacing works.
	 */
	private static final long ORDER_KEY_GAP = 1_000L;

	/**
	 * Discards the reported order-keys. A named class rather than a lambda purely out of habit from the B+ tree
	 * suites; this consumer is never retained by the tree, so it could not have poisoned a JOL walk anyway.
	 */
	private static final class DiscardingConsumer implements OrderKeyConsumer {

		@Override
		public void accept(int recordId, long orderKey) {
			// the heap assertions do not depend on the order-key assignment, only on the resulting node graph
		}
	}

	/**
	 * Builds ascending record ids to load into a tree.
	 *
	 * @param count how many record ids to produce
	 * @return the record ids in logical order
	 */
	@Nonnull
	private static int[] recordIds(int count) {
		final int[] recordIds = new int[count];
		for (int i = 0; i < count; i++) {
			recordIds[i] = i + 1;
		}
		return recordIds;
	}

	/**
	 * Builds a non-head-aware tree — the `SortIndex` shape, where no head mask or head-count array is ever allocated.
	 *
	 * @param count how many records to load
	 * @return the populated tree
	 */
	@Nonnull
	private static UnorderedLookupTree plainTree(int count) {
		final UnorderedLookupTree tree = new UnorderedLookupTree(BLOCK_SIZE, ORDER_KEY_GAP, false);
		tree.bulkLoad(recordIds(count), new DiscardingConsumer());
		return tree;
	}

	/**
	 * Builds a head-aware tree — the `ChainIndex` shape, where every leaf carries a head mask and every internal node
	 * a per-child head count.
	 *
	 * @param count how many records to load
	 * @param heads logical positions that are chain heads
	 * @return the populated tree
	 */
	@Nonnull
	private static UnorderedLookupTree headAwareTree(int count, @Nonnull int[] heads) {
		final UnorderedLookupTree tree = new UnorderedLookupTree(BLOCK_SIZE, ORDER_KEY_GAP, true);
		tree.bulkLoadWithHeads(recordIds(count), heads, new DiscardingConsumer());
		return tree;
	}

	@Nested
	@DisplayName("matches the measured heap")
	class MeasuredExactness {

		@Test
		void shouldMatchMeasuredHeapForEmptyTree() {
			final UnorderedLookupTree tree = new UnorderedLookupTree(BLOCK_SIZE, ORDER_KEY_GAP, false);

			// an empty tree has a null root and a null boxed size, so this pins the tree object's own constant -
			// the one part of the figure that must not depend on the data
			assertEquals(JolHeapSize.ownedSize(tree), tree.getHeapSizeInBytes());
		}

		@Test
		void shouldMatchMeasuredHeapForSingleLeafTree() {
			final UnorderedLookupTree tree = plainTree(5);

			assertEquals(JolHeapSize.ownedSize(tree), tree.getHeapSizeInBytes());
		}

		@Test
		void shouldMatchMeasuredHeapForMultiLevelTree() {
			final UnorderedLookupTree tree = plainTree(500);

			assertEquals(JolHeapSize.ownedSize(tree), tree.getHeapSizeInBytes());
		}

		@Test
		void shouldMatchMeasuredHeapForHeadAwareTree() {
			// head-awareness allocates a mask array per leaf and a head-count array per internal node - the figure
			// must grow to match, and must still be exact rather than approximately right
			final UnorderedLookupTree tree = headAwareTree(500, new int[]{0, 100, 250, 499});

			assertEquals(JolHeapSize.ownedSize(tree), tree.getHeapSizeInBytes());
		}
	}

	@Nested
	@DisplayName("accounts for the optional and cached parts")
	class OptionalStructures {

		@Test
		void shouldChargeHeadAwareTreeMoreThanPlainTreeOfSameSize() {
			final long plain = plainTree(500).getHeapSizeInBytes();
			final long headAware = headAwareTree(500, new int[]{0, 100, 250, 499}).getHeapSizeInBytes();

			// if the head arrays were charged unconditionally every SortIndex would be inflated; if they were never
			// charged every ChainIndex would be under-reported. The gap proves they are charged exactly when present
			assertTrue(
				headAware > plain,
				"a head-aware tree must cost more than a plain one, was " + headAware + " vs " + plain
			);
		}

		@Test
		void shouldChargeTheMemoizedArrayOnceItIsPopulated() {
			final UnorderedLookupTree tree = plainTree(500);
			final long beforeMemoization = tree.getHeapSizeInBytes();

			// getArray() populates the flattened read-cache, which then genuinely occupies heap for as long as it
			// lives - a memoized cache is charged to whoever holds it
			final int[] flattened = tree.getArray();
			final long afterMemoization = tree.getHeapSizeInBytes();

			assertEquals(500, flattened.length);
			assertTrue(
				afterMemoization > beforeMemoization,
				"the memoized array must be charged once populated, was " + afterMemoization
					+ " vs " + beforeMemoization
			);
			// and the figure must still agree with a measurement of the whole object
			assertEquals(JolHeapSize.ownedSize(tree), afterMemoization);
		}
	}

	@Nested
	@DisplayName("separates the tree object from its node graph")
	class NodeGraphSplit {

		@Test
		void shouldAddOnlyTheTreesOwnObjectOnTopOfItsNodeGraph() {
			final UnorderedLookupTree tree = plainTree(500);
			final long own = tree.getHeapSizeInBytes() - tree.getNodeGraphHeapSizeInBytes();
			assertTrue(own > 0 && own < 256, "the tree's own object should be a small constant, was " + own);

			final UnorderedLookupTree larger = plainTree(5_000);
			final long largerOwn = larger.getHeapSizeInBytes() - larger.getNodeGraphHeapSizeInBytes();
			assertEquals(own, largerOwn, "the tree's own object must not grow with the tree");
		}
	}
}
