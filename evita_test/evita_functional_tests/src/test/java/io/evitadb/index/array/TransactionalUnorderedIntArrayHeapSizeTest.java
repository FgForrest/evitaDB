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

import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.utils.ArrayUtils;
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
 * Verifies the heap-size walk over {@link TransactionalUnorderedIntArray} — the composite that `SortIndex` holds as
 * `sortedRecords` and `ChainIndex` as `elements`.
 *
 * The composite is the first structure in this family whose figure spans two independent trees: the order-statistic
 * {@link UnorderedLookupTree} and the `recordId → orderKey` B+ tree beside it. Both are built in this class's own
 * constructors and never handed out, so the whole graph is owned outright — no shared roots to subtract, and no
 * element-sizer decision to delegate.
 *
 * # Where an identity walk and the reported figure deliberately disagree
 *
 * Each of the two trees keeps its element count in a `TransactionalReference<Integer>`. When both counts land in the
 * JVM's autobox cache (`-128..127`) the two references address the **same** `Integer` instance, so a JOL walk — which
 * dedupes by identity — counts it once, while the reported figure charges it to each tree that holds it. That is the
 * standing ruling, not an oversight: the cache boundary is JVM-configurable (`-XX:AutoBoxCacheMax`), so an estimator
 * keyed on it would answer differently on different VMs and would flip ownership at 127. {@link SharedBoxedCount}
 * pins the divergence at exactly one `Integer` so it stays deliberate.
 *
 * @author Claude (heap-size verification), FG Forrest a.s. (c) 2026
 */
@Tag(INDEXING)
@Tag(DATA_TYPE)
@DisplayName("Transactional unordered int array heap-size reporting")
class TransactionalUnorderedIntArrayHeapSizeTest {
	/**
	 * Record count comfortably above the autobox cache ceiling, so the two trees' sizes are distinct `Integer`
	 * instances and an identity walk agrees with the reported figure exactly.
	 */
	private static final int ABOVE_BOX_CACHE = 500;

	/**
	 * Builds ascending record ids.
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
	 * Returns the footprint of a single boxed `Integer`, measured rather than assumed. Deliberately built from a
	 * value outside the autobox cache so a real instance is allocated.
	 *
	 * @return the heap one `Integer` occupies, in bytes
	 */
	private static long oneBoxedInteger() {
		return JolHeapSize.ownedSize(Integer.valueOf(ABOVE_BOX_CACHE));
	}

	@Nested
	@DisplayName("matches the measured heap")
	class MeasuredExactness {

		@Test
		void shouldMatchMeasuredHeapForBulkLoadedArray() {
			final TransactionalUnorderedIntArray array =
				new TransactionalUnorderedIntArray(recordIds(ABOVE_BOX_CACHE));

			assertEquals(JolHeapSize.ownedSize(array), array.getHeapSizeInBytes());
		}

		@Test
		void shouldMatchMeasuredHeapForMultiLevelArray() {
			final TransactionalUnorderedIntArray array =
				new TransactionalUnorderedIntArray(recordIds(5_000));

			assertEquals(JolHeapSize.ownedSize(array), array.getHeapSizeInBytes());
		}

		@Test
		void shouldMatchMeasuredHeapAfterIncrementalInserts() {
			// the bulk-load path packs containers fully; the incremental path leaves them partly filled, which is a
			// different node graph with different array slack - both must be reported exactly
			final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray();
			int previous = EvitaDataTypes.RESERVED_PRIMARY_KEY;
			for (int i = 1; i <= ABOVE_BOX_CACHE; i++) {
				array.add(previous, i);
				previous = i;
			}

			assertEquals(JolHeapSize.ownedSize(array), array.getHeapSizeInBytes());
		}
	}

	@Nested
	@DisplayName("charges a shared boxed count to each holder")
	class SharedBoxedCount {

		@Test
		void shouldChargeTheCachedIntegerTwiceWhenBothTreesShareIt() {
			// both counts are 0, so both TransactionalReferences address Integer.valueOf(0) - one instance, two
			// holders. The reported figure charges it to each; an identity walk sees it once
			final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray();

			assertEquals(
				// the value index's eagerly built root leaf is empty, so both of its backing arrays are the JVM-wide
				// shared empty arrays. The accounting subtracts them by identity (nobody owns a shared empty array),
				// so the measurement has to be told they are borrowed or it charges 16 B for each
				JolHeapSize.ownedSize(array, ArrayUtils.EMPTY_INT_ARRAY, ArrayUtils.EMPTY_LONG_ARRAY)
					+ oneBoxedInteger(),
				array.getHeapSizeInBytes(),
				"an empty composite must charge the shared cached Integer to both trees that hold it"
			);
		}

		@Test
		void shouldNotDivergeOnceTheCountsLeaveTheCache() {
			// the same structure with counts above the cache allocates two distinct Integers, so the deliberate
			// divergence disappears entirely - proving it is the cache doing it, not an arithmetic error
			final TransactionalUnorderedIntArray array =
				new TransactionalUnorderedIntArray(recordIds(ABOVE_BOX_CACHE));

			assertEquals(JolHeapSize.ownedSize(array), array.getHeapSizeInBytes());
		}
	}

	@Nested
	@DisplayName("spans both halves of the composite")
	class CompositeCoverage {

		@Test
		void shouldGrowWithTheStoredRecordCount() {
			final long small = new TransactionalUnorderedIntArray(recordIds(200)).getHeapSizeInBytes();
			final long large = new TransactionalUnorderedIntArray(recordIds(20_000)).getHeapSizeInBytes();

			assertTrue(large > small, "a hundredfold larger array must cost more, was " + large + " vs " + small);
		}

		@Test
		void shouldCountTheValueIndexNotJustThePositionTree() {
			final TransactionalUnorderedIntArray array =
				new TransactionalUnorderedIntArray(recordIds(1_000));

			// the value index is the half most easily forgotten - it holds no user-visible data, only the
			// recordId -> orderKey mapping - so pin that dropping it would be visible
			final long total = array.getHeapSizeInBytes();
			assertTrue(
				total > JolHeapSize.ownedSize(new TransactionalUnorderedIntArray()),
				"a populated composite must exceed an empty one, was " + total
			);
			assertEquals(JolHeapSize.ownedSize(array), total);
		}
	}
}
