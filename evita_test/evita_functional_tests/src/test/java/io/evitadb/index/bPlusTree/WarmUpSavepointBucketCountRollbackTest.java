/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

import io.evitadb.core.transaction.memory.WarmUpSavepoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.TRANSACTION;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies that a warm-up savepoint rewinds the bucket tree's **bucket count**, which is the one piece of this tree's
 * state that lives outside the node graph and therefore outside every inverse the nodes push for themselves.
 *
 * The count is a plain `int` on the tree, carried across a transaction by a `BucketCountChanges` layer that only
 * exists inside one. On the delegate branch — which is the warm-up branch, and the only branch a savepoint ever sees —
 * nothing else would put it back, so the tree journals it itself. A count left one too high outlives the rollback as a
 * tree that reports buckets it does not hold, which `recordCount()` and every emptiness check then read.
 *
 * The sibling {@code WarmUpRollbackConformanceTest} enforces that this tree DECLARES rollback support; this one
 * enforces that it delivers it.
 *
 * @author Johnny (Jan Novotný), FG Forrest a.s. (c) 2026
 */
@Tag(ENGINE)
@Tag(TRANSACTION)
@DisplayName("Warm-up rollback restores the bucket tree's bucket count")
class WarmUpSavepointBucketCountRollbackTest {

	@AfterEach
	void closeLeakedSavepoint() {
		final WarmUpSavepoint leaked = WarmUpSavepoint.getIfOpen();
		if (leaked != null) {
			leaked.commit();
		}
	}

	@Test
	@DisplayName("Rollback un-counts buckets born inside the savepoint")
	void shouldRestoreCountAfterBucketBirths() {
		final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(3, Integer.class);
		tree.addRecord(10, 1);
		tree.addRecord(20, 2);
		assertEquals(2, tree.size(), "self-check: the fixture must hold two buckets before the savepoint opens");

		final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
		tree.addRecord(30, 3);
		tree.addRecord(40, 4);
		// a record joining an EXISTING bucket must not move the count in either direction
		tree.addRecord(10, 5);
		assertEquals(4, tree.size(), "self-check: the writes must have taken effect inside the savepoint");
		savepoint.rollback();

		assertEquals(2, tree.size(), "Rollback must un-count every bucket born inside the savepoint.");
		assertEquals(tree.bucketCount(), tree.size(), "The scalar count must agree with the buckets actually held.");
	}

	@Test
	@DisplayName("Rollback re-counts buckets that died inside the savepoint")
	void shouldRestoreCountAfterBucketDeaths() {
		final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(3, Integer.class);
		tree.addRecord(10, 1);
		tree.addRecord(20, 2);
		tree.addRecord(30, 3);
		assertEquals(3, tree.size(), "self-check: the fixture must hold three buckets before the savepoint opens");

		final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
		tree.removeRecord(20, 2);
		tree.removeRecord(30, 3);
		assertEquals(1, tree.size(), "self-check: the removals must have taken effect inside the savepoint");
		savepoint.rollback();

		assertEquals(3, tree.size(), "Rollback must re-count every bucket that died inside the savepoint.");
		assertEquals(tree.bucketCount(), tree.size(), "The scalar count must agree with the buckets actually held.");
	}

	@Test
	@DisplayName("Rollback unwinds a mixed run of births and deaths through the counts it passed")
	void shouldRestoreCountAfterMixedRun() {
		final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(3, Integer.class);
		tree.addRecord(10, 1);
		tree.addRecord(20, 2);

		final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
		tree.addRecord(30, 3);
		tree.removeRecord(10, 1);
		tree.addRecord(40, 4);
		tree.addRecord(50, 5);
		tree.removeRecord(40, 4);
		assertEquals(3, tree.size(), "self-check: the run must have taken effect inside the savepoint");
		savepoint.rollback();

		assertEquals(2, tree.size(), "Reverse replay must walk back through every count the run passed through.");
		assertEquals(tree.bucketCount(), tree.size(), "The scalar count must agree with the buckets actually held.");
	}

	@Test
	@DisplayName("Commit keeps the counts the savepoint made")
	void shouldKeepCountAfterCommit() {
		final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(3, Integer.class);
		tree.addRecord(10, 1);

		final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
		tree.addRecord(20, 2);
		tree.addRecord(30, 3);
		savepoint.commit();

		assertEquals(3, tree.size(), "A committed savepoint must leave the counts its writes made.");
		assertEquals(tree.bucketCount(), tree.size(), "The scalar count must agree with the buckets actually held.");
	}
}
