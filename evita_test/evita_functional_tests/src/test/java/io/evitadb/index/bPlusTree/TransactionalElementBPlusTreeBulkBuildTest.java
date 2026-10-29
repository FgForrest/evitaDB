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
import io.evitadb.index.price.model.priceRecord.PriceRecord;
import io.evitadb.index.price.model.priceRecord.PriceRecordContract;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.TreeMap;
import java.util.function.ToIntFunction;

import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Differential, structural and transactional checks for bulk-built element price trees.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Element B+ tree bulk build")
@Tag(INDEXING)
@Tag(TRANSACTION)
class TransactionalElementBPlusTreeBulkBuildTest {
	private static final ToIntFunction<PriceRecordContract> KEY = PriceRecordContract::internalPriceId;

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
	 * Compares iteration, searches, cursor-backed range walks, occupancy and exact column sizing with an insert-built
	 * tree.
	 */
	@ParameterizedTest
	@ValueSource(ints = {0, 1, 2, 62, 63, 64, 65, 127, 128, 129, 1_000, 100_000})
	@DisplayName("bulk construction preserves reads and sizes leaves exactly")
	void shouldEqualInsertBuiltTree(int count) {
		final PriceRecordContract[] input = records(count);
		final TransactionalElementBPlusTree<PriceRecordContract> bulk = bulkBuilt(input);
		final TransactionalElementBPlusTree<PriceRecordContract> inserted = insertBuilt(input);
		assertArrayEquals(inserted.toArray(), bulk.toArray());
		assertMatches(bulk, mapOf(input));
		assertEquals(Math.max(1, (count + 62) / 63), bulk.enumerateLeaves().size());
		for (final BPlusTreeNode<?> node : bulk.enumerateLeaves()) {
			final TransactionalElementBPlusTree.BPlusLeafTreeNode<?> leaf =
				(TransactionalElementBPlusTree.BPlusLeafTreeNode<?>) node;
			assertEquals(leaf.size(), leaf.getValues().length, "Leaf arrays must match their content.");
			assertTrue(leaf.size() <= 63);
			if (bulk.isRootInternal()) {
				assertTrue(leaf.size() >= 31, "Every non-root leaf must meet the minimum occupancy.");
			}
			assertEquals(count > 0, leaf.isDirty(), "Dirty flags must match insert-built leaves.");
			assertEquals(AbstractTransactionalBPlusTree.UNASSIGNED_PAGE_SEQUENCE, leaf.getPageSequence());
		}
		for (final int probe : new int[]{0, count * 3 / 2 + 1, Integer.MAX_VALUE}) {
			assertIteratorsEqual(inserted.greaterOrEqualValueIterator(probe), bulk.greaterOrEqualValueIterator(probe));
			assertIteratorsEqual(inserted.lesserOrEqualValueIterator(probe), bulk.lesserOrEqualValueIterator(probe));
			assertNull(bulk.search(probe % 3 == 0 ? probe + 1 : probe));
		}
		Arrays.fill(input, null);
		assertArrayEquals(inserted.toArray(), bulk.toArray(), "The builder must copy the input array.");
	}

	/**
	 * Pins the fixed-fill remainder rule independently of the tree's consistency verifier.
	 */
	@Test
	@DisplayName("short remainders borrow exactly enough from their predecessor")
	void shouldComputeLeafSizes() {
		final TransactionalElementBPlusTree<PriceRecordContract> tree = bulkBuilt(records(0));
		assertArrayEquals(new int[]{33, 31}, tree.bulkLeafSizes(64, 31));
		assertArrayEquals(new int[]{63, 33, 31}, tree.bulkLeafSizes(127, 31));
		for (int count = 1; count < 20 * 64; count++) {
			final int[] sizes = tree.bulkLeafSizes(count, 31);
			assertEquals((count + 62) / 63, sizes.length);
			int sum = 0;
			for (final int size : sizes) {
				sum += size;
				assertTrue(size <= 63);
				assertTrue(sizes.length == 1 || size >= 31);
			}
			assertEquals(count, sum);
		}
	}

	/**
	 * Ports the experiment's tail and middle mutation differential, both outside and inside a committed transaction.
	 */
	@ParameterizedTest
	@ValueSource(ints = {0, 1, 63, 64, 129, 1_000, 100_000})
	@DisplayName("bulk-built trees support further inserts, deletes and commits")
	void shouldMutateAndCommit(int count) {
		final PriceRecordContract[] input = records(count);
		final TransactionalElementBPlusTree<PriceRecordContract> direct = bulkBuilt(input);
		final TreeMap<Integer, PriceRecordContract> after = mapOf(input);
		mutate(direct, after, count);
		assertMatches(direct, after);
		assertStateAfterCommit(
			bulkBuilt(input),
			tree -> mutate(tree, mapOf(input), count),
			(original, committed) -> {
				assertMatches(original, mapOf(input));
				assertMatches(committed, after);
			}
		);
	}

	/**
	 * A split by an earlier entity must leave both offspring reachable by the next entity's savepoint rollback.
	 */
	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	@DisplayName("rollback restores split leaves created before the savepoint and commit preserves the restoration")
	void shouldRestoreSplitLeavesCreatedBeforeSavepoint(boolean bulk) {
		final PriceRecordContract[] input = records(127);
		final TransactionalElementBPlusTree<PriceRecordContract> tree = bulk ? bulkBuilt(input) : insertBuilt(input);
		assertEquals(
			bulk ? List.of(63, 33, 31) : List.of(32, 32, 63),
			tree.enumerateLeaves().stream().map(BPlusTreeNode::size).toList()
		);
		final int splitKey = bulk ? 4 : 384;
		final int deletedKey = bulk ? 6 : 381;
		final int insertedKey = bulk ? 5 : 383;
		final TreeMap<Integer, PriceRecordContract> expected = mapOf(input);
		expected.put(splitKey, rec(splitKey));
		assertStateAfterCommit(
			tree,
			tested -> {
				tested.insert(rec(splitKey));
				assertEquals(4, tested.enumerateLeaves().size());
				final TransactionalLayerMaintainer maintainer = Transaction.getTransactionalLayerMaintainer();
				final Savepoint savepoint = maintainer.openSavepoint();
				tested.delete(deletedKey);
				tested.insert(rec(insertedKey));
				assertNull(tested.search(deletedKey));
				assertEquals(rec(insertedKey), tested.search(insertedKey));
				maintainer.rollbackSavepoint(savepoint);
				assertAll(
					() -> assertEquals(rec(deletedKey), tested.search(deletedKey), "Deleted record must return"),
					() -> assertNull(tested.search(insertedKey), "Savepoint insert must disappear"),
					() -> assertMatches(tested, expected)
				);
			},
			(original, committed) -> {
				assertMatches(original, mapOf(input));
				assertMatches(committed, expected);
			}
		);
	}

	/**
	 * Observes the committed base while its writer has split a full leaf and forced a minimum-size sibling to borrow.
	 */
	@Test
	@DisplayName("a split and borrow remain isolated and disappear on abort")
	void shouldIsolateAndAbortSplitAndBorrow() {
		final PriceRecordContract[] input = records(127);
		final TransactionalElementBPlusTree<PriceRecordContract> tree = bulkBuilt(input);
		final TreeMap<Integer, PriceRecordContract> before = mapOf(input);
		assertStateAfterRollback(
			tree,
			tested -> {
				splitAndBorrow(tested);
				final Transaction transaction = Transaction.getTransaction().orElseThrow();
				transaction.unbindTransactionFromThread();
				try {
					assertMatches(tested, before);
				} finally {
					transaction.bindTransactionToThread();
				}
			},
			(original, discarded) -> assertMatches(original, before)
		);
	}

	/**
	 * Verifies abort directly, independently of the mid-transaction isolation assertion.
	 */
	@Test
	@DisplayName("abort restores all original values after a split and borrow")
	void shouldAbortSplitAndBorrow() {
		final PriceRecordContract[] input = records(127);
		assertStateAfterRollback(
			bulkBuilt(input),
			TransactionalElementBPlusTreeBulkBuildTest::splitAndBorrow,
			(original, discarded) -> assertMatches(original, mapOf(input))
		);
	}

	/**
	 * Verifies all values and the pre-split shape after rolling back a warm-up savepoint, then writes again.
	 */
	@Test
	@DisplayName("warm-up rollback restores values and shape after a split and borrow")
	void shouldRestoreSplitAndBorrowAtWarmUpSavepoint() {
		final PriceRecordContract[] input = records(127);
		final TransactionalElementBPlusTree<PriceRecordContract> tree = bulkBuilt(input);
		final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
		splitAndBorrow(tree);
		savepoint.rollback();
		assertEquals(3, tree.enumerateLeaves().size(), "Rollback must restore the bulk-built leaf shape.");
		assertMatches(tree, mapOf(input));
		// Restored nodes must still isolate the next ordinary transaction from the committed base.
		assertStateAfterRollback(
			tree,
			tested -> {
				tested.insert(rec(4));
				assertEquals(rec(4), tested.search(4));
			},
			(original, discarded) -> assertMatches(original, mapOf(input))
		);
	}

	/**
	 * Makes a 32-child packing error fail before any delete can create room in the spine.
	 */
	@Test
	@DisplayName("the first middle insert into 2016 spaced keys can split its leaf")
	void shouldSplitFullLeafBeforeAnyDelete() {
		final TransactionalElementBPlusTree<PriceRecordContract> tree = bulkBuilt(records(2_016));
		assertEquals(32, tree.enumerateLeaves().size());
		assertDoesNotThrow(() -> tree.insert(rec(4)), "The assembled spine must allow its first child split.");
		assertEquals(33, tree.enumerateLeaves().size());
		assertEquals(rec(4), tree.search(4));
		assertConsistent(tree);
	}

	/**
	 * Rejects non-ascending low-level input; the price-index factory supplies the insert fallback.
	 */
	@Test
	@DisplayName("the low-level builder requires strictly ascending elements")
	void shouldRejectNonAscendingInput() {
		final GenericEvitaInternalError descending = assertThrows(
			GenericEvitaInternalError.class, () -> bulkBuilt(new PriceRecordContract[]{rec(5), rec(3)})
		);
		assertTrue(descending.getMessage().contains("strictly ascending"), descending.getMessage());
		final GenericEvitaInternalError duplicate = assertThrows(
			GenericEvitaInternalError.class, () -> bulkBuilt(new PriceRecordContract[]{rec(3), rec(3)})
		);
		assertTrue(duplicate.getMessage().contains("strictly ascending"), duplicate.getMessage());
	}

	/**
	 * The low-level builder requires detached construction; the price-index factory falls back to inserts instead.
	 */
	@Test
	@DisplayName("the low-level builder rejects a bound transaction")
	void shouldRejectBoundTransaction() {
		assertStateAfterRollback(
			new TransactionalElementBPlusTree<>(PriceRecordContract.class, KEY),
			tested -> {
				final GenericEvitaInternalError error = assertThrows(
					GenericEvitaInternalError.class, () -> bulkBuilt(records(129))
				);
				assertTrue(error.getMessage().contains("no bound transaction"), error.getMessage());
			},
			(original, discarded) -> assertEquals(0, original.size())
		);
	}

	/**
	 * Returns a deterministic price record for the given key.
	 */
	@Nonnull
	private static PriceRecordContract rec(int key) {
		return new PriceRecord(key, key + 1, key / 3, key * 100 + 21, key * 100);
	}

	/**
	 * Spaces keys by three to leave absent middle-insert probes.
	 */
	@Nonnull
	private static PriceRecordContract[] records(int count) {
		final PriceRecordContract[] records = new PriceRecordContract[count];
		for (int i = 0; i < count; i++) {
			records[i] = rec(i * 3 + 3);
		}
		return records;
	}

	/**
	 * Replays the insert loop as a differential reference.
	 */
	@Nonnull
	private static TransactionalElementBPlusTree<PriceRecordContract> insertBuilt(
		@Nonnull PriceRecordContract[] records
	) {
		final TransactionalElementBPlusTree<PriceRecordContract> tree =
			new TransactionalElementBPlusTree<>(PriceRecordContract.class, KEY);
		for (final PriceRecordContract record : records) {
			tree.insert(record);
		}
		return tree;
	}

	/**
	 * Calls the production bulk builder.
	 */
	@Nonnull
	private static TransactionalElementBPlusTree<PriceRecordContract> bulkBuilt(@Nonnull PriceRecordContract[] records) {
		return TransactionalElementBPlusTree.bulkBuild(PriceRecordContract.class, KEY, records);
	}

	/**
	 * Builds a content reference independent of tree structure.
	 */
	@Nonnull
	private static TreeMap<Integer, PriceRecordContract> mapOf(@Nonnull PriceRecordContract[] records) {
		final TreeMap<Integer, PriceRecordContract> result = new TreeMap<>();
		for (final PriceRecordContract record : records) {
			result.put(record.internalPriceId(), record);
		}
		return result;
	}

	/**
	 * Compares complete contents and every keyed lookup as well as the tree's consistency report.
	 */
	private static void assertMatches(
		@Nonnull TransactionalElementBPlusTree<PriceRecordContract> tree,
		@Nonnull TreeMap<Integer, PriceRecordContract> expected
	) {
		assertConsistent(tree);
		assertEquals(expected.size(), tree.size());
		assertArrayEquals(expected.values().toArray(PriceRecordContract[]::new), tree.toArray());
		for (final PriceRecordContract record : expected.values()) {
			assertEquals(record, tree.search(record.internalPriceId()));
		}
	}

	/**
	 * Checks the separator and minimum-occupancy invariants that iteration alone cannot observe.
	 */
	private static void assertConsistent(@Nonnull TransactionalElementBPlusTree<PriceRecordContract> tree) {
		final ConsistencyReport report = tree.getConsistencyReport();
		assertEquals(ConsistencyState.CONSISTENT, report.state(), report.report());
	}

	/**
	 * Compares a complete cursor-backed range walk with the insert-built reference.
	 */
	private static void assertIteratorsEqual(@Nonnull Iterator<?> expected, @Nonnull Iterator<?> actual) {
		while (expected.hasNext()) {
			assertTrue(actual.hasNext());
			assertEquals(expected.next(), actual.next());
		}
		assertFalse(actual.hasNext());
	}

	/**
	 * Adds tail and middle keys and deletes random original records as in the experiment's differential test.
	 */
	private static void mutate(
		@Nonnull TransactionalElementBPlusTree<PriceRecordContract> tree,
		@Nonnull TreeMap<Integer, PriceRecordContract> expected, int count
	) {
		for (int i = 0; i < 200; i++) {
			final PriceRecordContract record = rec(count * 3 + 3 + i * 3);
			tree.insert(record);
			expected.put(record.internalPriceId(), record);
		}
		final Random random = new Random(count * 31L);
		for (int i = 0; i < count / 2; i++) {
			final int key = (random.nextInt(Math.max(1, count)) + 1) * 3;
			tree.delete(key);
			expected.remove(key);
		}
		for (int i = 0; i < 50; i++) {
			final PriceRecordContract record = rec(random.nextInt(count * 3 + 3) * 3 + 1);
			tree.insert(record);
			expected.put(record.internalPriceId(), record);
		}
	}

	/**
	 * Splits the first full leaf before making the minimum-size last leaf borrow from its predecessor.
	 */
	private static void splitAndBorrow(@Nonnull TransactionalElementBPlusTree<PriceRecordContract> tree) {
		assertEquals(31, tree.enumerateLeaves().getLast().size());
		tree.insert(rec(4));
		assertEquals(4, tree.enumerateLeaves().size(), "The first full leaf must split.");
		tree.delete(381);
		assertEquals(31, tree.enumerateLeaves().getLast().size(), "The last leaf must borrow to avoid underflow.");
		assertEquals(rec(4), tree.search(4));
		assertNull(tree.search(381));
		assertConsistent(tree);
	}
}
