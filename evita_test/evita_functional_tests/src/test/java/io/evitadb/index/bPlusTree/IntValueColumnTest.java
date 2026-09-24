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

import io.evitadb.utils.ArrayUtils.InsertionPosition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Comparator;
import java.util.Random;
import java.util.TreeMap;
import java.util.TreeSet;

import static io.evitadb.index.bPlusTree.ValueColumnTestSupport.assertTreeMatchesOracle;
import static io.evitadb.index.bPlusTree.ValueColumnTestSupport.verifyConsistent;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the primitive `int[]` value column: the {@link IntValueColumn} array operations and {@link Integer} key
 * round-trip (proven equivalent to the boxed {@link BoxedObjectColumn}), the {@link ValueColumnFactory} selection rules
 * (notably `BigDecimal` routing to the 4-byte column), an end-to-end randomized workload on an int-keyed
 * {@link TransactionalBucketBPlusTree} matched against a {@link TreeMap} oracle, and the MVCC commit / rollback of an
 * int-keyed tree (so the primitive column's deep-copy duplicate / range moves run across a real transaction layer).
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Primitive int value column")
@Tag(INDEXING)
@Tag(ATTRIBUTE)
class IntValueColumnTest {

	private static final int BLOCK_SIZE = 8;

	/**
	 * Verifies that {@link IntValueColumn} performs the same key moves / searches as the boxed reference column.
	 */
	@Nested
	@DisplayName("IntValueColumn vs. boxed column parity")
	class ColumnParityTest {

		@Test
		@DisplayName("round-trips Integer.MIN / MAX and negative keys preserving natural order")
		void shouldRoundTripExtremeIntegerKeys() {
			final ValueColumn<Integer> primitive = new IntValueColumn<>(BLOCK_SIZE);
			// ascending so the int[] backing stays monotone and each probe lands in its insertion slot
			final int[] ascending = {Integer.MIN_VALUE, -1, 0, 1, 42, Integer.MAX_VALUE};
			for (int i = 0; i < ascending.length; i++) {
				primitive.insertKeyAt(i, ascending[i]);
			}

			// every key boxes back to exactly what was stored (the unbox/box identity is lossless)
			for (int i = 0; i < ascending.length; i++) {
				assertEquals(Integer.valueOf(ascending[i]), primitive.keyAt(i), "Round-trip mismatch at slot " + i);
			}
			// natural Integer order is preserved by the raw int order — every probe is found at its monotone position
			for (int i = 0; i < ascending.length; i++) {
				final InsertionPosition position = primitive.findKeyPosition(ascending[i], 0, ascending.length, null);
				assertTrue(position.alreadyPresent(), "Key " + ascending[i] + " should be present");
				assertEquals(i, position.position(), "Order mismatch at slot " + i);
			}
		}

		@Test
		@DisplayName("insert / remove / findKeyPosition / duplicate match the boxed column")
		void shouldBehaveLikeBoxedColumn() {
			final ValueColumn<Integer> primitive = new IntValueColumn<>(BLOCK_SIZE);
			final ValueColumn<Integer> boxed = new BoxedObjectColumn<>(Integer.class, BLOCK_SIZE);

			// build the same ordered prefix [10, 20, 30, 40] in both columns
			final int[] inserted = {10, 20, 30, 40};
			for (int i = 0; i < inserted.length; i++) {
				primitive.insertKeyAt(i, inserted[i]);
				boxed.insertKeyAt(i, inserted[i]);
			}
			assertColumnsEqual(primitive, boxed, inserted.length);

			// insert 25 in the middle (position 2)
			final InsertionPosition pp = primitive.findKeyPosition(25, 0, inserted.length, null);
			final InsertionPosition bp = boxed.findKeyPosition(25, 0, inserted.length, null);
			assertEquals(bp.position(), pp.position());
			assertEquals(bp.alreadyPresent(), pp.alreadyPresent());
			assertFalse(pp.alreadyPresent());
			primitive.insertKeyAt(pp.position(), 25);
			boxed.insertKeyAt(bp.position(), 25);
			assertColumnsEqual(primitive, boxed, inserted.length + 1);

			// existing key lookup reports alreadyPresent at the same slot
			final InsertionPosition pHit = primitive.findKeyPosition(30, 0, inserted.length + 1, null);
			final InsertionPosition bHit = boxed.findKeyPosition(30, 0, inserted.length + 1, null);
			assertTrue(pHit.alreadyPresent());
			assertEquals(bHit.position(), pHit.position());

			// duplicate is an independent deep copy
			final ValueColumn<Integer> dup = primitive.duplicate();
			assertColumnsEqual(dup, boxed, inserted.length + 1);
			dup.insertKeyAt(0, -1);
			assertEquals(Integer.valueOf(25), primitive.keyAt(2), "Duplicate must not alias the source");

			// remove the middle key (position 2 == value 25) from both
			primitive.removeKeyAt(2);
			primitive.clearAt(inserted.length);
			boxed.removeKeyAt(2);
			boxed.clearAt(inserted.length);
			assertColumnsEqual(primitive, boxed, inserted.length);

			assertInstanceOf(IntValueColumn.class, primitive);
		}

		@Test
		@DisplayName("copyRangeTo moves a key block like the boxed column")
		void shouldCopyRangeLikeBoxedColumn() {
			final ValueColumn<Integer> srcPrimitive = new IntValueColumn<>(BLOCK_SIZE);
			final ValueColumn<Integer> srcBoxed = new BoxedObjectColumn<>(Integer.class, BLOCK_SIZE);
			for (int i = 0; i < 4; i++) {
				srcPrimitive.insertKeyAt(i, (i + 1) * 7);
				srcBoxed.insertKeyAt(i, (i + 1) * 7);
			}

			final ValueColumn<Integer> dstPrimitive = srcPrimitive.allocate(BLOCK_SIZE);
			final ValueColumn<Integer> dstBoxed = srcBoxed.allocate(BLOCK_SIZE);
			srcPrimitive.copyRangeTo(1, dstPrimitive, 0, 3);
			srcBoxed.copyRangeTo(1, dstBoxed, 0, 3);
			assertColumnsEqual(dstPrimitive, dstBoxed, 3);

			// asBoxedArray (cold path) decodes to the same prefix the boxed column already holds
			final Integer[] primitiveBoxed = dstPrimitive.asBoxedArray();
			final Integer[] boxedArray = dstBoxed.asBoxedArray();
			assertEquals(boxedArray[0], primitiveBoxed[0]);
			assertEquals(boxedArray[1], primitiveBoxed[1]);
			assertEquals(boxedArray[2], primitiveBoxed[2]);
		}

		@Test
		@DisplayName("fillEmpty clears slots and appendKey renders the decoded key like the boxed column")
		void shouldClearSlotsAndRenderKeyLikeBoxedColumn() {
			final ValueColumn<Integer> primitive = new IntValueColumn<>(BLOCK_SIZE);
			final ValueColumn<Integer> boxed = new BoxedObjectColumn<>(Integer.class, BLOCK_SIZE);
			final int[] inserted = {3, 6, 9, 12};
			for (int i = 0; i < inserted.length; i++) {
				primitive.insertKeyAt(i, inserted[i]);
				boxed.insertKeyAt(i, inserted[i]);
			}

			// appendKey renders each decoded key identically to the boxed column
			for (int i = 0; i < inserted.length; i++) {
				final StringBuilder primitiveKey = new StringBuilder(16);
				final StringBuilder boxedKey = new StringBuilder(16);
				primitive.appendKey(primitiveKey, i);
				boxed.appendKey(boxedKey, i);
				assertEquals(boxedKey.toString(), primitiveKey.toString(), "appendKey mismatch at slot " + i);
				assertEquals(String.valueOf(inserted[i]), primitiveKey.toString());
			}

			// fillEmpty clears the tail slots in both columns; the surviving prefix is unchanged and the cleared slots
			// round-trip to the column's zero key (re-insert proves the slot is genuinely empty, no reflection needed)
			primitive.fillEmpty(2, inserted.length);
			boxed.fillEmpty(2, inserted.length);
			assertColumnsEqual(primitive, boxed, 2);
			primitive.insertKeyAt(2, 100);
			boxed.insertKeyAt(2, 100);
			assertEquals(Integer.valueOf(100), primitive.keyAt(2));
			assertColumnsEqual(primitive, boxed, 3);
		}

		/**
		 * Asserts the two columns hold the same decoded keys in `[0, size)`.
		 *
		 * @param actual   the column under test
		 * @param expected the boxed reference column
		 * @param size     the number of populated slots
		 */
		private static void assertColumnsEqual(
			@Nonnull ValueColumn<Integer> actual, @Nonnull ValueColumn<Integer> expected, int size
		) {
			assertEquals(expected.capacity(), actual.capacity());
			for (int i = 0; i < size; i++) {
				assertEquals(expected.keyAt(i), actual.keyAt(i), "Key mismatch at slot " + i);
			}
		}
	}

	/**
	 * Verifies the {@link ValueColumnFactory} selection rules and the end-to-end int-keyed tree workload.
	 */
	@Nested
	@DisplayName("ValueColumnFactory selection and tree workload")
	class FactoryAndTreeTest {

		@Test
		@DisplayName("BigDecimal natural-order keys select the 4-byte primitive column")
		void shouldSelectIntColumnForBigDecimalNaturalOrder() {
			assertInstanceOf(
				IntValueColumn.class,
				ValueColumnFactory.forKey(java.math.BigDecimal.class, Comparator.naturalOrder())
					.create(BLOCK_SIZE)
			);
			assertInstanceOf(
				IntValueColumn.class,
				ValueColumnFactory.forKey(java.math.BigDecimal.class, null).create(BLOCK_SIZE)
			);
		}

		@Test
		@DisplayName("non-natural orders fall back to the boxed column even for BigDecimal")
		void shouldFallBackToBoxedColumn() {
			// BigDecimal with a non-natural-order comparator ⇒ boxed (the int order would not match)
			assertInstanceOf(
				BoxedObjectColumn.class,
				ValueColumnFactory.forKey(
					java.math.BigDecimal.class, Comparator.<java.math.BigDecimal>reverseOrder()
				).create(BLOCK_SIZE)
			);
		}

		@Test
		@DisplayName("randomized add/remove workload on an int-keyed tree matches a TreeMap oracle")
		@SuppressWarnings({"unchecked", "rawtypes"})
		void shouldMatchOracleOnIntKeyedTree() {
			// build the tree via the BigDecimal factory so its leaves use the primitive IntValueColumn — proven below;
			// the tree itself is exercised with Integer keys (the type the column stores after upstream normalization)
			final ValueColumnFactory factory =
				ValueColumnFactory.forKey(java.math.BigDecimal.class, Comparator.naturalOrder());
			assertInstanceOf(IntValueColumn.class, factory.create(BLOCK_SIZE));
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(
				BLOCK_SIZE, 3, 7, 3, Integer.class, null, factory
			);

			final TreeMap<Integer, TreeSet<Integer>> oracle = new TreeMap<>();
			final Random random = new Random(424242L);
			final int keyDomain = 60;

			for (int op = 0; op < 6_000; op++) {
				final int key = random.nextInt(keyDomain) - keyDomain / 2; // include negative keys (codec coverage)
				// record ids stay non-negative so the bitmap's unsigned order matches the oracle TreeSet's signed order
				final int recordId = random.nextInt(1_000);
				if (random.nextInt(100) < 65) {
					tree.addRecord(key, recordId);
					oracle.computeIfAbsent(key, k -> new TreeSet<>()).add(recordId);
				} else {
					final TreeSet<Integer> set = oracle.get(key);
					if (set != null && set.contains(recordId)) {
						tree.removeRecord(key, recordId);
						set.remove(recordId);
						if (set.isEmpty()) {
							oracle.remove(key);
						}
					}
				}
				if (op % 250 == 0) {
					assertTreeMatchesOracle(tree, oracle);
				}
			}
			assertTreeMatchesOracle(tree, oracle);
		}
	}

	/**
	 * Drives the int-keyed tree's primitive {@link IntValueColumn} across a real MVCC transaction layer: the
	 * non-transactional oracle tests never run {@link IntValueColumn#duplicate()} / {@link IntValueColumn#copyRangeTo}
	 * through a commit / rollback, so a layer-decoupling defect in the primitive copy path would be invisible without
	 * these tests.
	 */
	@Nested
	@DisplayName("Int-keyed tree across an MVCC transaction")
	class TransactionalTest {

		@Test
		@DisplayName("preserves an int-keyed tree across a commit that splits and merges leaves")
		@Tag(TRANSACTION)
		@SuppressWarnings({"unchecked", "rawtypes"})
		void shouldPreserveIntKeyedTreeAcrossCommit() {
			final ValueColumnFactory factory =
				ValueColumnFactory.forKey(java.math.BigDecimal.class, Comparator.naturalOrder());
			assertInstanceOf(IntValueColumn.class, factory.create(BLOCK_SIZE),
				"Factory must back the tree with the primitive int column");
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(
				BLOCK_SIZE, 3, 7, 3, Integer.class, null, factory
			);

			// commit a base layout dense enough to span several leaves
			final TreeMap<Integer, TreeSet<Integer>> baseOracle = new TreeMap<>();
			for (int key = -20; key <= 20; key++) {
				tree.addRecord(key, key + 1_000);
				baseOracle.computeIfAbsent(key, k -> new TreeSet<>()).add(key + 1_000);
			}

			assertStateAfterCommit(
				tree,
				tested -> {
					// add and remove enough records inside the txn to force leaf splits, steals and merges so the
					// primitive column's duplicate() + copyRangeTo run across the transaction layer
					for (int key = 21; key <= 60; key++) {
						tested.addRecord(key, key + 1_000);
						tested.addRecord(key, key + 2_000);
					}
					for (int key = -20; key <= 5; key++) {
						tested.removeRecord(key, key + 1_000);
					}
				},
				(original, committed) -> {
					// the committed tree matches the post-mutation oracle
					final TreeMap<Integer, TreeSet<Integer>> oracle = new TreeMap<>(baseOracle);
					for (int key = 21; key <= 60; key++) {
						final TreeSet<Integer> set = new TreeSet<>();
						set.add(key + 1_000);
						set.add(key + 2_000);
						oracle.put(key, set);
					}
					for (int key = -20; key <= 5; key++) {
						oracle.remove(key);
					}
					assertTreeMatchesOracle(committed, oracle);
					verifyConsistent(committed);

					// the pre-commit base tree is unchanged — the transaction layer was decoupled
					assertTreeMatchesOracle(original, baseOracle);
				}
			);
		}

		@Test
		@DisplayName("discards int-keyed mutations on rollback, leaving the base tree untouched")
		@Tag(TRANSACTION)
		@SuppressWarnings({"unchecked", "rawtypes"})
		void shouldDiscardIntKeyedMutationsOnRollback() {
			final ValueColumnFactory factory =
				ValueColumnFactory.forKey(java.math.BigDecimal.class, Comparator.naturalOrder());
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(
				BLOCK_SIZE, 3, 7, 3, Integer.class, null, factory
			);

			final TreeMap<Integer, TreeSet<Integer>> baseOracle = new TreeMap<>();
			for (int key = 0; key <= 15; key++) {
				tree.addRecord(key, key + 1_000);
				baseOracle.computeIfAbsent(key, k -> new TreeSet<>()).add(key + 1_000);
			}

			assertStateAfterRollback(
				tree,
				tested -> {
					for (int key = 16; key <= 40; key++) {
						tested.addRecord(key, key + 1_000);
					}
					for (int key = 0; key <= 10; key++) {
						tested.removeRecord(key, key + 1_000);
					}
				},
				(original, discarded) -> {
					// the rolled-back changes leave the base tree exactly as it was
					assertTreeMatchesOracle(original, baseOracle);
					verifyConsistent(original);
				}
			);
		}
	}
}
