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

package io.evitadb.index.fulltext;

import io.evitadb.exception.GenericEvitaInternalError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import static io.evitadb.test.TestTags.FULLTEXT;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertSavepointCommitKeeps;
import static io.evitadb.utils.AssertionUtils.assertSavepointRollbackRestores;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static io.evitadb.utils.AssertionUtils.assertWarmUpSavepointRollbackRestores;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@link FieldLengthTable}: the length quantization, the per-entity operations, the per-block choice
 * between the sparse and the dense layout, including its hysteresis, and the transactional contract.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(INDEXING)
@Tag(FULLTEXT)
@DisplayName("Fulltext field length table")
class FieldLengthTableTest {

	/**
	 * Bases of the blocks the randomized tests spread their keys over, including the top of the unsigned range a
	 * negative key falls into.
	 */
	private static final int[] BLOCK_BASES = {0, 1 << 16, 5 << 16, 6 << 16, 40 << 16, Integer.MIN_VALUE, -(1 << 16)};

	/**
	 * How far above a block base the randomized keys reach - past the promotion size, so a block crosses into the
	 * dense layout and back.
	 */
	private static final int KEY_SPREAD = FieldLengthTable.DENSE_PROMOTION_SIZE + 12_000;

	/**
	 * Draws a key from the randomized key space.
	 *
	 * @param random the source of randomness
	 * @param dense  whether to draw from the first block only, so it fills past the promotion size
	 * @return the primary key
	 */
	private static int randomKey(@Nonnull Random random, boolean dense) {
		return dense
			? random.nextInt(KEY_SPREAD)
			: BLOCK_BASES[random.nextInt(BLOCK_BASES.length)] + random.nextInt(3_000);
	}

	/**
	 * Applies random puts and removals to the table and to the oracle alike, one removal in four.
	 *
	 * @param random  the source of randomness
	 * @param table   the table under test
	 * @param oracle  primary key to encoded length
	 * @param opCount how many operations to apply
	 * @param dense   whether to aim the operations at one block, driving it across the dense/sparse thresholds
	 */
	private static void applyRandomOperations(
		@Nonnull Random random,
		@Nonnull FieldLengthTable table,
		@Nonnull Map<Integer, Integer> oracle,
		int opCount,
		boolean dense
	) {
		applyRandomOperations(random, table, oracle, opCount, dense, 4);
	}

	/**
	 * Applies random puts and removals to the table and to the oracle alike.
	 *
	 * @param random       the source of randomness
	 * @param table        the table under test
	 * @param oracle       primary key to encoded length
	 * @param opCount      how many operations to apply
	 * @param dense        whether to aim the operations at one block, driving it across the dense/sparse thresholds
	 * @param removalOneIn one operation in this many is a removal - `1` removes only
	 */
	private static void applyRandomOperations(
		@Nonnull Random random,
		@Nonnull FieldLengthTable table,
		@Nonnull Map<Integer, Integer> oracle,
		int opCount,
		boolean dense,
		int removalOneIn
	) {
		for (int i = 0; i < opCount; i++) {
			final int primaryKey = randomKey(random, dense);
			if (random.nextInt(removalOneIn) == 0) {
				table.remove(primaryKey);
				oracle.remove(primaryKey);
			} else {
				final int length = random.nextInt(400);
				table.put(primaryKey, length);
				if (length == 0) {
					oracle.remove(primaryKey);
				} else {
					oracle.put(primaryKey, FieldLengthTable.encode(length));
				}
			}
		}
	}

	/**
	 * Asserts the table, as the caller's transaction sees it, holds exactly the oracle's lengths.
	 *
	 * @param table  the table to check
	 * @param oracle primary key to encoded length
	 */
	private static void assertMatchesOracle(@Nonnull FieldLengthTable table, @Nonnull Map<Integer, Integer> oracle) {
		assertEquals(oracle.size(), table.size(), "entity count");
		for (Map.Entry<Integer, Integer> entry : oracle.entrySet()) {
			assertEquals(entry.getValue(), table.getEncoded(entry.getKey()), "pk " + entry.getKey());
		}
	}

	/**
	 * Reads the table over the whole randomized key space into an `equals`-comparable form.
	 *
	 * @param table the table to read
	 * @return the entity count, and every key of the key space with a length mapped to its encoded length
	 */
	@Nonnull
	private static Map<Integer, Integer> contentOf(@Nonnull FieldLengthTable table) {
		final Map<Integer, Integer> content = new TreeMap<>();
		// the count, under a key outside every probed range
		content.put(Integer.MIN_VALUE + 3_001, table.size());
		for (int primaryKey = 0; primaryKey < KEY_SPREAD; primaryKey++) {
			final int encoded = table.getEncoded(primaryKey);
			if (encoded != 0) {
				content.put(primaryKey, encoded);
			}
		}
		for (int base : BLOCK_BASES) {
			for (int offset = 0; offset < 3_000; offset++) {
				final int encoded = table.getEncoded(base + offset);
				if (encoded != 0) {
					content.put(base + offset, encoded);
				}
			}
		}
		return content;
	}

	@Nested
	@DisplayName("Length quantization")
	class Quantization {

		@Test
		@DisplayName("Short lengths are exact and zero encodes to zero")
		void shouldEncodeShortLengthsExactly() {
			assertEquals(0, FieldLengthTable.encode(0));
			for (int length = 0; length <= 20; length++) {
				assertEquals(length, FieldLengthTable.decode(FieldLengthTable.encode(length)), "length " + length);
			}
		}

		@Test
		@DisplayName("Encoding is monotone and decodes to a lower bound")
		void shouldEncodeMonotonicallyAndDecodeToLowerBound() {
			int previous = 0;
			for (int length = 1; length < 2_000_000; length += 1 + length / 50) {
				final int encoded = FieldLengthTable.encode(length);
				assertTrue(encoded >= previous, "not monotone at " + length);
				assertTrue(encoded > 0 && encoded <= 255, "out of range at " + length);
				assertTrue(FieldLengthTable.decode(encoded) <= length, "decodes above the length at " + length);
				previous = encoded;
			}
		}

		@Test
		@DisplayName("A negative length is refused")
		void shouldRefuseNegativeLength() {
			assertThrows(GenericEvitaInternalError.class, () -> FieldLengthTable.encode(-1));
			assertThrows(GenericEvitaInternalError.class, () -> new FieldLengthTable().put(1, -1));
		}

	}

	@Nested
	@DisplayName("Per-entity operations")
	class Operations {

		@Test
		@DisplayName("A length is stored, overwritten and removed")
		void shouldPutOverwriteAndRemove() {
			final FieldLengthTable table = new FieldLengthTable();
			assertEquals(0, table.getLength(42));
			table.put(42, 7);
			assertEquals(7, table.getLength(42));
			assertEquals(FieldLengthTable.encode(7), table.getEncoded(42));
			assertEquals(1, table.size());
			table.put(42, 9);
			assertEquals(9, table.getLength(42));
			assertEquals(1, table.size());
			table.remove(42);
			assertEquals(0, table.getLength(42));
			assertEquals(0, table.size());
			// removing what is not there changes nothing
			table.remove(42);
			table.remove(1 << 20);
			assertEquals(0, table.size());
		}

		@Test
		@DisplayName("A zero length removes the entity")
		void shouldRemoveEntityOnZeroLength() {
			final FieldLengthTable table = new FieldLengthTable();
			table.put(3, 5);
			table.put(3, 0);
			assertEquals(0, table.getEncoded(3));
			assertEquals(0, table.size());
			table.put(4, 0);
			assertEquals(0, table.size());
		}

		@Test
		@DisplayName("Random operations across many blocks agree with a map oracle")
		void shouldAgreeWithOracleAcrossBlocks() {
			final FieldLengthTable table = new FieldLengthTable();
			final Map<Integer, Integer> oracle = new HashMap<>();
			final Random random = new Random(11);
			// keys spread over a dozen blocks, including the top of the unsigned range a negative key falls into
			final int[] blockBases = {0, 1 << 16, 5 << 16, 6 << 16, 40 << 16, Integer.MIN_VALUE, -(1 << 16)};
			for (int i = 0; i < 60_000; i++) {
				final int primaryKey = blockBases[random.nextInt(blockBases.length)] + random.nextInt(3_000);
				if (random.nextInt(4) == 0) {
					table.remove(primaryKey);
					oracle.remove(primaryKey);
				} else {
					final int length = random.nextInt(400);
					table.put(primaryKey, length);
					if (length == 0) {
						oracle.remove(primaryKey);
					} else {
						oracle.put(primaryKey, FieldLengthTable.encode(length));
					}
				}
			}
			assertEquals(oracle.size(), table.size());
			for (Map.Entry<Integer, Integer> entry : oracle.entrySet()) {
				assertEquals(entry.getValue(), table.getEncoded(entry.getKey()), "pk " + entry.getKey());
			}
			for (int i = 0; i < 10_000; i++) {
				final int primaryKey = blockBases[random.nextInt(blockBases.length)] + random.nextInt(3_000);
				assertEquals(oracle.getOrDefault(primaryKey, 0), table.getEncoded(primaryKey), "pk " + primaryKey);
			}
		}

	}

	@Nested
	@DisplayName("Per-block layout")
	class Layout {

		@Test
		@DisplayName("A block turns dense past the promotion size and keeps every length")
		void shouldPromoteBlockToDense() {
			final FieldLengthTable table = new FieldLengthTable();
			final int base = 3 << 16;
			for (int i = 0; i < FieldLengthTable.DENSE_PROMOTION_SIZE; i++) {
				table.put(base + 2 * i, 1 + i % 30);
			}
			assertFalse(table.isDenseBlock(base));
			table.put(base + 1, 5);
			assertTrue(table.isDenseBlock(base));
			// a neighbouring block is unaffected
			table.put(base + (1 << 16), 4);
			assertFalse(table.isDenseBlock(base + (1 << 16)));
			for (int i = 0; i < FieldLengthTable.DENSE_PROMOTION_SIZE; i++) {
				assertEquals(1 + i % 30, table.getLength(base + 2 * i));
			}
			assertEquals(5, table.getLength(base + 1));
			assertEquals(FieldLengthTable.DENSE_PROMOTION_SIZE + 2, table.size());
		}

		@Test
		@DisplayName("A dense block returns to sparse only below the demotion size, and vanishes when emptied")
		void shouldDemoteDenseBlockWithHysteresis() {
			final FieldLengthTable table = new FieldLengthTable();
			final int count = FieldLengthTable.DENSE_PROMOTION_SIZE + 1;
			for (int i = 0; i < count; i++) {
				table.put(i, 10);
			}
			assertTrue(table.isDenseBlock(0));
			// down to the promotion size and below it: still dense, the hysteresis holds
			int live = count;
			while (live > FieldLengthTable.SPARSE_DEMOTION_SIZE) {
				table.remove(--live);
				assertTrue(table.isDenseBlock(0), "demoted too early at " + live);
			}
			table.remove(--live);
			assertFalse(table.isDenseBlock(0));
			for (int i = 0; i < live; i++) {
				assertEquals(10, table.getLength(i));
			}
			while (live > 0) {
				table.remove(--live);
			}
			assertEquals(0, table.size());
			assertFalse(table.isDenseBlock(0));
			assertEquals(0, table.getLength(0));
		}

	}

	@Nested
	@DisplayName("Transactions")
	@Tag(TRANSACTION)
	class Transactions {

		@Test
		@DisplayName("Writes are visible inside the transaction and published only by the commit")
		void shouldPublishWritesAtCommit() {
			final FieldLengthTable table = new FieldLengthTable();
			table.put(1, 4);
			table.put(2, 6);
			assertStateAfterCommit(
				table,
				t -> {
					t.put(3, 8);
					t.put(1, 5);
					t.remove(2);
					assertEquals(5, t.getLength(1));
					assertEquals(0, t.getLength(2));
					assertEquals(8, t.getLength(3));
					assertEquals(2, t.size());
				},
				(original, committed) -> {
					assertNotSame(original, committed);
					assertEquals(5, committed.getLength(1));
					assertEquals(0, committed.getLength(2));
					assertEquals(8, committed.getLength(3));
					assertEquals(2, committed.size());
					assertEquals(4, original.getLength(1));
					assertEquals(6, original.getLength(2));
					assertEquals(0, original.getLength(3));
					assertEquals(2, original.size());
				}
			);
		}

		@Test
		@DisplayName("A rolled-back transaction leaves the table untouched")
		void shouldDiscardWritesOnRollback() {
			final FieldLengthTable table = new FieldLengthTable();
			table.put(1, 4);
			assertStateAfterRollback(
				table,
				t -> {
					t.put(1, 9);
					t.put(70_000, 2);
				},
				(original, committed) -> {
					assertNull(committed);
					assertEquals(4, original.getLength(1));
					assertEquals(0, original.getLength(70_000));
					assertEquals(1, original.size());
				}
			);
		}

		@Test
		@DisplayName("A table the transaction did not write is carried forward as the same instance")
		void shouldCarryUntouchedTableForward() {
			final FieldLengthTable table = new FieldLengthTable();
			table.put(1, 4);
			assertStateAfterCommit(
				table,
				t -> assertEquals(4, t.getLength(1)),
				(original, committed) -> assertSame(original, committed)
			);
		}

		@Test
		@DisplayName("A block crosses into the dense layout and back in transactions, and every version reads right")
		void shouldMatchOracleAcrossCommits() {
			final Random random = new Random(29);
			final Map<Integer, Integer> oracle = new HashMap<>();
			FieldLengthTable table = new FieldLengthTable();
			applyRandomOperations(random, table, oracle, 2_000, false);
			// fill the first block past the promotion size, scatter, drain it below the demotion size, scatter, fill
			final int[] removalOneIn = {8, 4, 1, 4, 8, 4};
			final boolean[] dense = {true, false, true, false, true, false};
			final Boolean[] denseAfter = {true, null, false, null, true, null};
			for (int round = 0; round < removalOneIn.length; round++) {
				final Map<Integer, Integer> before = new HashMap<>(oracle);
				final FieldLengthTable[] next = new FieldLengthTable[1];
				final int theRound = round;
				assertStateAfterCommit(
					table,
					t -> {
						applyRandomOperations(
							random, t, oracle, dense[theRound] ? 60_000 : 2_000, dense[theRound], removalOneIn[theRound]
						);
						assertMatchesOracle(t, oracle);
					},
					(original, committed) -> {
						assertMatchesOracle(committed, oracle);
						assertMatchesOracle(original, before);
						next[0] = committed;
					}
				);
				table = next[0];
				if (denseAfter[round] != null) {
					assertEquals(
						denseAfter[round], table.isDenseBlock(0), "layout of the first block after round " + round
					);
				}
			}
		}

		@Test
		@DisplayName("A savepoint rollback restores the lengths written before it, and a savepoint commit keeps them")
		void shouldRestoreOnSavepointRollback() {
			final Random random = new Random(31);
			final FieldLengthTable table = new FieldLengthTable();
			final Map<Integer, Integer> oracle = new HashMap<>();
			applyRandomOperations(random, table, oracle, 2_000, false);
			assertSavepointRollbackRestores(
				table,
				t -> applyRandomOperations(random, t, new HashMap<>(oracle), 500, false),
				FieldLengthTableTest::contentOf,
				t -> applyRandomOperations(random, t, new HashMap<>(oracle), 500, false)
			);
			assertSavepointCommitKeeps(
				table,
				t -> applyRandomOperations(random, t, new HashMap<>(oracle), 500, false),
				FieldLengthTableTest::contentOf,
				t -> applyRandomOperations(random, t, new HashMap<>(oracle), 500, false)
			);
		}

		@Test
		@DisplayName("A warm-up savepoint rollback restores every length, across a promotion to the dense layout")
		void shouldRestoreOnWarmUpSavepointRollback() {
			final Random random = new Random(37);
			final FieldLengthTable table = new FieldLengthTable();
			final Map<Integer, Integer> oracle = new HashMap<>();
			assertWarmUpSavepointRollbackRestores(
				table,
				t -> applyRandomOperations(random, t, oracle, 3_000, false),
				FieldLengthTableTest::contentOf,
				t -> applyRandomOperations(random, t, new HashMap<>(), 40_000, true)
			);
		}

	}

}
