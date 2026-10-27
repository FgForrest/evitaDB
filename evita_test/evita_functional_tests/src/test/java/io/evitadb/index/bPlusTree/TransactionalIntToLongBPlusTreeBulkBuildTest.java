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
import io.evitadb.core.transaction.memory.WarmUpSavepoint;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer.Savepoint;
import io.evitadb.dataType.ConsistencySensitiveDataStructure.ConsistencyReport;
import io.evitadb.dataType.ConsistencySensitiveDataStructure.ConsistencyState;
import io.evitadb.exception.GenericEvitaInternalError;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Differential, structural and transactional checks for the primitive bulk builder.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Int-to-long B+ tree bulk build")
@Tag(INDEXING)
@Tag(TRANSACTION)
class TransactionalIntToLongBPlusTreeBulkBuildTest {

	/**
	 * Clears a savepoint if a failed assertion interrupted its rollback.
	 */
	@AfterEach
	void closeLeakedSavepoint() {
		final WarmUpSavepoint savepoint = WarmUpSavepoint.getIfOpen();
		if (savepoint != null) {
			savepoint.commit();
		}
	}

	/**
	 * Compares random-order inserts with a bulk build, including signed extremes and absent point probes.
	 * Leaf occupancy follows the stable-capacity rule: only the last two leaves may be short when the remainder
	 * needs a top-up.
	 */
	@ParameterizedTest
	@ValueSource(ints = {0, 1, 2, 62, 63, 64, 65, 127, 128, 129, 1_000, 100_000})
	@DisplayName("bulk construction matches random-order inserts and sizes leaves exactly")
	void shouldEqualRandomInsertBuiltTree(int count) {
		final int[] randomKeys = randomKeys(count);
		final TransactionalIntToLongBPlusTree inserted = new TransactionalIntToLongBPlusTree();
		for (final int key : randomKeys) {
			inserted.insert(key, value(key));
		}
		final int[] keys = randomKeys.clone();
		Arrays.sort(keys);
		final long[] values = new long[count];
		for (int i = 0; i < count; i++) {
			values[i] = value(keys[i]);
		}
		final TransactionalIntToLongBPlusTree bulk = new TransactionalIntToLongBPlusTree();
		bulk.bulkBuild(keys, values);
		assertMatches(inserted, bulk);
		final List<BPlusTreeNode<?>> leaves = bulk.enumerateLeaves();
		assertEquals(Math.max(1, (count + 62) / 63), leaves.size());
		final int remainder = count % 63;
		for (int i = 0; i < leaves.size(); i++) {
			final TransactionalIntToLongBPlusTree.BPlusLeafTreeNode leaf =
				(TransactionalIntToLongBPlusTree.BPlusLeafTreeNode) leaves.get(i);
			assertEquals(leaf.size(), leaf.getKeys().length);
			assertEquals(leaf.size(), leaf.getValues().length);
			assertTrue(leaf.size() <= 63);
			if (leaves.size() > 1) {
				assertTrue(leaf.size() >= 31);
			}
			if (i < leaves.size() - 1) {
				final boolean toppedUpLast = remainder > 0 && remainder < 31 && i == leaves.size() - 2;
				assertEquals(toppedUpLast ? 63 - (31 - remainder) : 63, leaf.size());
			} else {
				assertEquals(count <= 63 ? count : remainder == 0 ? 63 : Math.max(31, remainder), leaf.size());
			}
		}
		for (final int key : keys) {
			assertEquals(inserted.search(key), bulk.search(key));
			assertEquals(inserted.searchOrDefault(key, -1L), bulk.searchOrDefault(key, -1L));
		}
		final Random probes = new Random(42);
		for (int i = 0; i < 1000; i++) {
			final int key = probes.nextInt();
			assertEquals(inserted.search(key), bulk.search(key));
			assertEquals(inserted.searchOrDefault(key, -1L), bulk.searchOrDefault(key, -1L));
			if (Arrays.binarySearch(keys, key) < 0) {
				assertTrue(bulk.search(key).isEmpty());
				assertEquals(-1L, bulk.searchOrDefault(key, -1L));
			}
		}
		for (final int probe : new int[]{Integer.MIN_VALUE, 0, Integer.MAX_VALUE}) {
			assertEntriesEqual(inserted.greaterOrEqualEntryIterator(probe), bulk.greaterOrEqualEntryIterator(probe));
			assertEntriesEqual(inserted.lesserOrEqualEntryIterator(probe), bulk.lesserOrEqualEntryIterator(probe));
		}
		// The builder must copy both input columns, including the single-leaf case.
		Arrays.fill(keys, 0);
		Arrays.fill(values, 0L);
		assertMatches(inserted, bulk);
	}

	/**
	 * Exercises child splits, sibling consolidation and commit after bulk construction at several spine heights.
	 */
	@ParameterizedTest
	@ValueSource(ints = {0, 1, 63, 64, 129, 1_000, 100_000})
	@DisplayName("bulk-built trees support further inserts, deletes and commits")
	void shouldCommitMutationsAfterBulkBuild(int count) {
		final int[] keys = new int[count];
		final long[] values = new long[count];
		final TransactionalIntToLongBPlusTree reference = new TransactionalIntToLongBPlusTree();
		for (int i = 0; i < count; i++) {
			keys[i] = i * 3;
			values[i] = value(keys[i]);
			reference.insert(keys[i], values[i]);
		}
		final TransactionalIntToLongBPlusTree bulk = new TransactionalIntToLongBPlusTree();
		bulk.bulkBuild(keys, values);
		for (int i = 0; i < count; i += 2) {
			reference.delete(keys[i]);
		}
		for (int i = 0; i < count + 200; i++) {
			reference.insert(i * 3 + 1, value(i * 3 + 1));
		}
		assertStateAfterCommit(
			bulk,
			tree -> {
				for (int i = 0; i < count; i += 2) {
					tree.delete(keys[i]);
				}
				for (int i = 0; i < count + 200; i++) {
					tree.insert(i * 3 + 1, value(i * 3 + 1));
				}
				assertMatches(reference, tree);
			},
			(original, committed) -> {
				assertEquals(count, original.size());
				assertConsistent(original);
				assertMatches(reference, committed);
			}
		);
	}

	/**
	 * Rejects malformed input before changing the empty tree and forbids replacing a populated tree.
	 */
	@Test
	@DisplayName("malformed input and a populated tree are rejected with the violated premise")
	void shouldValidateInput() {
		final TransactionalIntToLongBPlusTree tree = new TransactionalIntToLongBPlusTree();
		assertRejected("parallel key and value arrays", () -> tree.bulkBuild(new int[]{1}, new long[0]));
		assertRejected("strictly ascending keys", () -> tree.bulkBuild(new int[]{2, 1}, new long[]{1, 2}));
		assertRejected("strictly ascending keys", () -> tree.bulkBuild(new int[]{1, 1}, new long[]{1, 2}));
		assertEquals(0, tree.size());
		tree.insert(1, 1L);
		assertRejected("an empty tree", () -> tree.bulkBuild(new int[]{2}, new long[]{2}));
		assertEquals(1L, tree.searchOrDefault(1, -1L));
	}

	/**
	 * Guards the shared rule that nodes split before a savepoint must participate in its rollback.
	 */
	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	@DisplayName("rollback restores split leaves created before the savepoint and commit preserves the restoration")
	void shouldRestoreSplitLeavesCreatedBeforeSavepoint(boolean bulk) {
		final TransactionalIntToLongBPlusTree tree = spacedTree(127, bulk);
		assertEquals(
			bulk ? List.of(63, 33, 31) : List.of(32, 32, 63),
			tree.enumerateLeaves().stream().map(BPlusTreeNode::size).toList()
		);
		final int splitKey = bulk ? 4 : 384;
		final int deletedKey = bulk ? 6 : 381;
		final int insertedKey = bulk ? 5 : 383;
		final TransactionalIntToLongBPlusTree expected = spacedTree(127, false);
		expected.insert(splitKey, value(splitKey));
		assertStateAfterCommit(
			tree,
			tested -> {
				tested.insert(splitKey, value(splitKey));
				assertEquals(4, tested.enumerateLeaves().size());
				final TransactionalLayerMaintainer maintainer = Transaction.getTransactionalLayerMaintainer();
				final Savepoint savepoint = maintainer.openSavepoint();
				tested.delete(deletedKey);
				tested.insert(insertedKey, value(insertedKey));
				assertTrue(tested.search(deletedKey).isEmpty());
				assertEquals(value(insertedKey), tested.searchOrDefault(insertedKey, -1L));
				maintainer.rollbackSavepoint(savepoint);
				assertMatches(expected, tested);
			},
			(original, committed) -> {
				assertMatches(spacedTree(127, false), original);
				assertMatches(expected, committed);
			}
		);
	}

	/**
	 * Forces a full-leaf split and a minimum leaf to borrow, then observes the base with the writer detached and aborts.
	 */
	@Test
	@DisplayName("a split and borrow remain isolated and disappear on abort")
	void shouldIsolateAndAbortSplitAndBorrow() {
		final TransactionalIntToLongBPlusTree tree = spacedTree(127, true);
		final TransactionalIntToLongBPlusTree reference = spacedTree(127, false);
		assertStateAfterRollback(
			tree,
			tested -> {
				splitAndBorrow(tested);
				final Transaction transaction = Transaction.getTransaction().orElseThrow();
				transaction.unbindTransactionFromThread();
				try {
					assertMatches(reference, tested);
				} finally {
					transaction.bindTransactionToThread();
				}
			},
			(original, discarded) -> assertMatches(reference, original)
		);
	}

	/**
	 * Verifies abort directly, independently of the mid-transaction isolation assertion.
	 */
	@Test
	@DisplayName("abort restores all original values after a split and borrow")
	void shouldAbortSplitAndBorrow() {
		final TransactionalIntToLongBPlusTree reference = spacedTree(127, false);
		assertStateAfterRollback(
			spacedTree(127, true),
			TransactionalIntToLongBPlusTreeBulkBuildTest::splitAndBorrow,
			(original, discarded) -> assertMatches(reference, original)
		);
	}

	/**
	 * Reads changed values and verifies the original leaf shape and every value after a warm-up rollback.
	 */
	@Test
	@DisplayName("warm-up rollback restores values and shape after a split and borrow")
	void shouldRestoreSplitAndBorrowAtWarmUpSavepoint() {
		final TransactionalIntToLongBPlusTree tree = spacedTree(127, true);
		final TransactionalIntToLongBPlusTree reference = spacedTree(127, false);
		final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
		splitAndBorrow(tree);
		savepoint.rollback();
		assertEquals(3, tree.enumerateLeaves().size(), "Rollback must restore the bulk-built leaf shape.");
		assertMatches(reference, tree);
		// Restored nodes must still isolate the next ordinary transaction from the committed base.
		assertStateAfterRollback(
			tree,
			tested -> {
				tested.insert(4, value(4));
				assertEquals(value(4), tested.searchOrDefault(4, -1L));
			},
			(original, discarded) -> assertMatches(reference, original)
		);
	}

	/**
	 * A spine packed to 32 children is full before its first child split and violates adaptToLeafSplit's premise.
	 */
	@Test
	@DisplayName("the first middle insert into 2016 bulk-built spaced keys can split its leaf")
	void shouldSplitFullLeafBeforeAnyDelete() {
		final TransactionalIntToLongBPlusTree tree = spacedTree(2_016, true);
		assertEquals(32, tree.enumerateLeaves().size());
		assertDoesNotThrow(() -> tree.insert(4, value(4)), "The assembled spine must allow its first child split.");
		assertEquals(33, tree.enumerateLeaves().size());
		assertEquals(value(4), tree.searchOrDefault(4, -1L));
		assertConsistent(tree);
	}

	/**
	 * The low-level primitive builder requires detached construction; array constructors detach their callers.
	 */
	@Test
	@DisplayName("the primitive bulk builder rejects a bound transaction before replacing its root")
	void shouldRejectBoundTransaction() {
		final TransactionalIntToLongBPlusTree tree = new TransactionalIntToLongBPlusTree();
		assertStateAfterRollback(
			tree,
			tested -> {
				final GenericEvitaInternalError error = assertThrows(
					GenericEvitaInternalError.class, () -> tested.bulkBuild(new int[]{3}, new long[]{7})
				);
				assertTrue(error.getMessage().contains("no bound transaction"));
				assertEquals(0, tested.size());
			},
			(original, discarded) -> assertEquals(0, original.size())
		);
	}

	/**
	 * Generates unique, widely spread signed ids by permuting a sequence with an odd multiplier, then shuffling.
	 */
	@Nonnull
	private static int[] randomKeys(int count) {
		final int[] keys = new int[count];
		for (int i = 0; i < count; i++) {
			keys[i] = Integer.MIN_VALUE + i * 1_000_003;
		}
		if (count > 1) {
			keys[1] = Integer.MAX_VALUE;
		}
		final Random random = new Random(42);
		for (int i = count - 1; i > 0; i--) {
			final int other = random.nextInt(i + 1);
			final int swap = keys[i];
			keys[i] = keys[other];
			keys[other] = swap;
		}
		return keys;
	}

	/**
	 * Returns a deterministic value spanning both words of a long.
	 */
	private static long value(int key) {
		return ((long) key << 32) ^ (key * 31L + 17);
	}

	/**
	 * Checks the tree's structural, occupancy and iterator invariants.
	 */
	private static void assertConsistent(@Nonnull TransactionalIntToLongBPlusTree tree) {
		final ConsistencyReport report = tree.getConsistencyReport();
		assertEquals(ConsistencyState.CONSISTENT, report.state(), report.report());
	}

	/**
	 * Compares complete entries in iteration order against the insert-built reference.
	 */
	private static void assertMatches(
		@Nonnull TransactionalIntToLongBPlusTree reference, @Nonnull TransactionalIntToLongBPlusTree actual
	) {
		assertConsistent(actual);
		assertConsistent(reference);
		assertEquals(reference.size(), actual.size());
		final Iterator<TransactionalIntToLongBPlusTree.Entry> expected = reference.entryIterator();
		final Iterator<TransactionalIntToLongBPlusTree.Entry> entries = actual.entryIterator();
		while (expected.hasNext()) {
			assertTrue(entries.hasNext());
			assertEquals(expected.next(), entries.next());
		}
		assertFalse(entries.hasNext());
	}

	/**
	 * Expects the builder to refuse the input and to name the premise it violates, so a reordered validation cannot
	 * pass by failing on a different premise.
	 */
	private static void assertRejected(@Nonnull String premise, @Nonnull Executable bulkBuild) {
		final GenericEvitaInternalError error = assertThrows(GenericEvitaInternalError.class, bulkBuild);
		assertTrue(error.getMessage().contains(premise), error.getMessage());
	}

	/**
	 * Builds spaced positive keys either directly or through the insert loop.
	 */
	@Nonnull
	private static TransactionalIntToLongBPlusTree spacedTree(int count, boolean bulk) {
		final int[] keys = new int[count];
		final long[] values = new long[count];
		final TransactionalIntToLongBPlusTree tree = new TransactionalIntToLongBPlusTree();
		for (int i = 0; i < count; i++) {
			keys[i] = i * 3 + 3;
			values[i] = value(keys[i]);
			if (!bulk) {
				tree.insert(keys[i], values[i]);
			}
		}
		if (bulk) {
			tree.bulkBuild(keys, values);
		}
		return tree;
	}

	/**
	 * Splits the first 63-entry leaf before deleting from the 31-entry last leaf, which must borrow from its sibling.
	 */
	private static void splitAndBorrow(@Nonnull TransactionalIntToLongBPlusTree tree) {
		assertEquals(31, tree.enumerateLeaves().getLast().size());
		tree.insert(4, value(4));
		assertEquals(4, tree.enumerateLeaves().size(), "The first full leaf must split.");
		tree.delete(381);
		assertEquals(31, tree.enumerateLeaves().getLast().size(), "The last leaf must borrow to avoid underflow.");
		assertEquals(value(4), tree.searchOrDefault(4, -1L));
		assertTrue(tree.search(381).isEmpty());
		assertConsistent(tree);
	}

	/**
	 * Compares complete cursor-backed range walks against the insert-built reference.
	 */
	private static void assertEntriesEqual(
		@Nonnull Iterator<TransactionalIntToLongBPlusTree.Entry> expected,
		@Nonnull Iterator<TransactionalIntToLongBPlusTree.Entry> actual
	) {
		while (expected.hasNext()) {
			assertTrue(actual.hasNext());
			assertEquals(expected.next(), actual.next());
		}
		assertFalse(actual.hasNext());
	}

}
