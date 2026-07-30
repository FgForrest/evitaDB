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

package io.evitadb.index.attribute;

import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.TreeSet;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.INDEXING;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * Verifies that the bucket-anchored insertion produces exactly the sort order a brute-force reference model
 * prescribes: records ordered by value, and — within one value — by record id ascending.
 *
 * This suite deliberately covers the two shapes neither the scaling probe nor the generational suites exercise:
 *
 * - **Multi-record buckets.** Values are drawn from a small alphabet so buckets hold many records, which is the only
 *   way the overflow-bitmap branch of the in-bucket predecessor search is reached at all.
 * - **Negative record ids.** The bucket bitmaps are Roaring-backed and therefore ordered by UNSIGNED comparison,
 *   whereas the sort index orders record ids by SIGNED comparison. The two agree for positive ids only, so a
 *   corpus of positive ids can never distinguish them.
 */
@Tag(INDEXING)
@Tag(ATTRIBUTE)
@DisplayName("Bucket-anchored sort index insertion matches the reference order")
class SortIndexBucketAnchorParityTest {

	/**
	 * Builds the expected record order from the reference model: values ascending, record ids ascending within a
	 * value (both in natural/signed order, which is what {@link TreeMap} and {@link TreeSet} provide).
	 *
	 * @param model the reference model of value → record ids
	 * @return the expected sorted record ids
	 */
	private static int[] expectedOrder(@Nonnull TreeMap<Integer, TreeSet<Integer>> model) {
		final List<Integer> expected = new ArrayList<>();
		for (final Map.Entry<Integer, TreeSet<Integer>> entry : model.entrySet()) {
			expected.addAll(entry.getValue());
		}
		final int[] result = new int[expected.size()];
		for (int i = 0; i < result.length; i++) {
			result[i] = expected.get(i);
		}
		return result;
	}

	/**
	 * Drives a randomized insert/remove walk against both the index and the reference model, comparing the full
	 * sorted order after every mutation so a divergence is reported at the operation that caused it.
	 *
	 * @param seed           the random seed
	 * @param operationCount the number of mutations to perform
	 * @param distinctValues the size of the value alphabet (small ⇒ multi-record buckets)
	 * @param idOrigin       the lowest record id generated (negative to cover signed/unsigned divergence)
	 * @param idBound        the exclusive upper bound of generated record ids
	 */
	private static void randomWalk(long seed, int operationCount, int distinctValues, int idOrigin, int idBound) {
		final SortIndex sortIndex = new OwnerSortIndex(Integer.class, new AttributeIndexKey(null, "a", null));
		final TreeMap<Integer, TreeSet<Integer>> model = new TreeMap<>();
		final Map<Integer, Integer> valueOfRecord = new TreeMap<>();
		final Random rnd = new Random(seed);

		for (int i = 0; i < operationCount; i++) {
			final boolean remove = !valueOfRecord.isEmpty() && rnd.nextInt(100) < 30;
			if (remove) {
				final List<Integer> present = new ArrayList<>(valueOfRecord.keySet());
				final int recordId = present.get(rnd.nextInt(present.size()));
				final int value = valueOfRecord.remove(recordId);
				sortIndex.removeRecord(value, recordId);
				final TreeSet<Integer> bucket = model.get(value);
				bucket.remove(recordId);
				if (bucket.isEmpty()) {
					model.remove(value);
				}
			} else {
				final int recordId = rnd.nextInt(idBound - idOrigin) + idOrigin;
				if (recordId == EvitaDataTypes.RESERVED_PRIMARY_KEY || valueOfRecord.containsKey(recordId)) {
					// skip the reserved primary key (evitaDB never assigns it, and the index rejects it) and any
					// duplicate id - the sort index holds one value per record
					continue;
				}
				final int value = rnd.nextInt(distinctValues);
				sortIndex.addRecord(value, recordId);
				valueOfRecord.put(recordId, value);
				model.computeIfAbsent(value, it -> new TreeSet<>()).add(recordId);
			}
			assertArrayEquals(
				expectedOrder(model),
				sortIndex.getAscendingOrderRecordsSupplier().getSortedRecordIds(),
				"Sort order diverged after operation #" + i + " (seed " + seed + ")"
			);
		}
	}

	@Test
	@DisplayName("dense multi-record buckets keep records ordered by value then id")
	void shouldKeepOrderWithDenseBuckets() {
		for (long seed = 1; seed <= 5; seed++) {
			randomWalk(seed, 400, 8, 1, 200);
		}
	}

	@Test
	@DisplayName("single-record buckets keep records ordered by value then id")
	void shouldKeepOrderWithSparseBuckets() {
		for (long seed = 1; seed <= 5; seed++) {
			randomWalk(seed, 400, 500, 1, 500);
		}
	}

	@Test
	@DisplayName("negative record ids are ordered by signed comparison, not unsigned")
	void shouldOrderNegativeRecordIdsSigned() {
		final SortIndex sortIndex = new OwnerSortIndex(Integer.class, new AttributeIndexKey(null, "a", null));
		// one value ⇒ a single multi-record bucket: the order is decided purely by record-id comparison
		sortIndex.addRecord(1, 5);
		sortIndex.addRecord(1, -3);
		sortIndex.addRecord(1, 10);
		sortIndex.addRecord(1, -20);
		assertArrayEquals(
			new int[]{-20, -3, 5, 10},
			sortIndex.getAscendingOrderRecordsSupplier().getSortedRecordIds()
		);
	}

	@Test
	@DisplayName("randomized walk with negative record ids keeps signed order")
	void shouldKeepSignedOrderWithNegativeRecordIds() {
		for (long seed = 1; seed <= 5; seed++) {
			randomWalk(seed, 300, 6, -100, 100);
		}
	}
}
