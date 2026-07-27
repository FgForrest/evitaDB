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

import io.evitadb.dataType.ConsistencySensitiveDataStructure.ConsistencyState;
import io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree.BucketCursor;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shared assertions for the {@link ValueColumn} test suite. The three concrete-column tests
 * ({@code LongValueColumnTest}, {@code IntValueColumnTest}, {@code InstantValueColumnTest}) each
 * drive a {@link TransactionalBucketBPlusTree} whose leaves use the column under test and verify it
 * against a {@link TreeMap} oracle; the cursor-vs-oracle walk and the structural-consistency check
 * are identical across all of them and live here so the column tests do not each repeat them.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
final class ValueColumnTestSupport {

	private ValueColumnTestSupport() {
		throw new UnsupportedOperationException("Test support class, not instantiable");
	}

	/**
	 * Asserts the tree's forward cursor enumerates exactly the oracle's `(value → record set)` pairs
	 * in ascending value order.
	 *
	 * @param tree   the bucket tree under test
	 * @param oracle the reference `TreeMap`
	 * @param <K>    the (boxed) bucket-value key type
	 */
	static <K extends Comparable<K>> void assertTreeMatchesOracle(
		@Nonnull TransactionalBucketBPlusTree<K> tree,
		@Nonnull TreeMap<K, TreeSet<Integer>> oracle
	) {
		final BucketCursor<K> cursor = tree.cursor();
		for (final Map.Entry<K, TreeSet<Integer>> entry : oracle.entrySet()) {
			assertTrue(cursor.next(), "Tree ran out of buckets before the oracle did");
			assertEquals(entry.getKey(), cursor.value(), "Bucket value mismatch");
			final int[] expected = entry.getValue().stream().mapToInt(Integer::intValue).toArray();
			final int[] actual = cursor.isSingle()
				? new int[]{cursor.singleRecordId()}
				: cursor.records().getArray();
			assertArrayEquals(expected, actual, "Record set mismatch for value " + entry.getKey());
		}
		assertFalse(cursor.next(), "Tree has more buckets than the oracle");
	}

	/**
	 * Asserts the tree's structural consistency oracle reports a healthy tree.
	 *
	 * @param tree the tree to verify
	 * @param <K>  the (boxed) bucket-value key type
	 */
	static <K extends Comparable<K>> void verifyConsistent(
		@Nonnull TransactionalBucketBPlusTree<K> tree
	) {
		assertEquals(
			ConsistencyState.CONSISTENT,
			tree.getConsistencyReport().state(),
			tree.getConsistencyReport().report()
		);
	}
}
