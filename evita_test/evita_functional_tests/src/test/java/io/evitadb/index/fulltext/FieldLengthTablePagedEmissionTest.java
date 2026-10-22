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

import io.evitadb.core.transaction.memory.WarmUpSavepoint;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.fulltext.FieldLengthTable.LengthBlock;
import io.evitadb.index.fulltext.FieldLengthTable.LengthBlockEmission;
import io.evitadb.index.fulltext.analysis.FulltextAnalyzerRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static io.evitadb.index.fulltext.FulltextFieldKey.attribute;
import static io.evitadb.test.TestTags.FULLTEXT;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that a {@link FieldLengthTable} is written in blocks: every flush is applied to a simulated disk, which must
 * then hold exactly the table's blocks and reload into a table with the same lengths - and a flush must write only the
 * blocks that changed since the previous one, on the warm-up path and inside a transaction alike.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Field length table paged emission")
@Tag(INDEXING)
@Tag(FULLTEXT)
class FieldLengthTablePagedEmissionTest {

	/**
	 * The highest block key a positive primary key reaches.
	 */
	private static final int LAST_BLOCK = 0x7FFF;

	/**
	 * Registry providing the real Czech index-slot analyzer for the fulltext index group.
	 */
	private static FulltextAnalyzerRegistry registry;

	@BeforeAll
	static void setUpRegistry() {
		registry = new FulltextAnalyzerRegistry();
	}

	@AfterAll
	static void closeRegistry() {
		registry.close();
	}

	/**
	 * Returns a primary key in the passed block.
	 *
	 * @param blockKey the block
	 * @param low      the low 16 bits
	 * @return the primary key
	 */
	private static int pk(int blockKey, int low) {
		return blockKey << 16 | low;
	}

	/**
	 * Returns the keys of the changed blocks of an emission.
	 *
	 * @param emission the emission
	 * @return the keys, ascending
	 */
	@Nonnull
	private static int[] changedKeys(@Nonnull LengthBlockEmission emission) {
		final int[] keys = new int[emission.changedBlocks().size()];
		for (int i = 0; i < keys.length; i++) {
			keys[i] = emission.changedBlocks().get(i).blockKey();
		}
		return keys;
	}

	/**
	 * Fills the passed number of entities of one block, so a count above a third of the block makes it dense.
	 *
	 * @param table    the table
	 * @param blockKey the block
	 * @param count    how many entities, from low bits `0`
	 * @param touched  the primary keys ever written, to compare by
	 */
	private static void fill(@Nonnull FieldLengthTable table, int blockKey, int count, @Nonnull Set<Integer> touched) {
		for (int low = 0; low < count; low++) {
			table.put(pk(blockKey, low), 1 + low % 200);
			touched.add(pk(blockKey, low));
		}
	}

	/**
	 * The disk the flushes are applied to.
	 */
	private static final class BlockDisk {

		/**
		 * The blocks on disk by key.
		 */
		private final Map<Integer, LengthBlock> blocks = new TreeMap<>();
		/**
		 * The block list of the last flush.
		 */
		private int[] listed = new int[0];

		/**
		 * Applies one flush and checks the disk holds exactly the listed blocks.
		 *
		 * @param emission the flush's emission
		 * @return the emission, for further assertions
		 */
		@Nonnull
		LengthBlockEmission apply(@Nonnull LengthBlockEmission emission) {
			for (final LengthBlock block : emission.changedBlocks()) {
				this.blocks.put(block.blockKey(), block);
			}
			for (final int removed : emission.removedBlockKeys()) {
				assertNotNull(this.blocks.remove(removed), "A removed block must have been on disk: " + removed);
			}
			this.listed = emission.blockKeys();
			final Set<Integer> listedSet = new TreeSet<>();
			for (final int blockKey : this.listed) {
				listedSet.add(blockKey);
			}
			assertEquals(listedSet, this.blocks.keySet(), "The disk must hold exactly the listed blocks.");
			return emission;
		}

		/**
		 * Loads a table back from this disk.
		 *
		 * @return the reloaded table
		 */
		@Nonnull
		FieldLengthTable reload() {
			final LengthBlock[] listedBlocks = new LengthBlock[this.listed.length];
			for (int i = 0; i < listedBlocks.length; i++) {
				listedBlocks[i] = this.blocks.get(this.listed[i]);
			}
			return FieldLengthTable.fromPersistedBlocks(listedBlocks);
		}

		/**
		 * Asserts the disk reproduces the table.
		 *
		 * @param table   the table
		 * @param touched every primary key ever written
		 */
		void assertHolds(@Nonnull FieldLengthTable table, @Nonnull Set<Integer> touched) {
			final FieldLengthTable reloaded = reload();
			assertEquals(table.size(), reloaded.size(), "The disk must hold every entity.");
			for (final int primaryKey : touched) {
				assertEquals(
					table.getEncoded(primaryKey), reloaded.getEncoded(primaryKey), "The length of " + primaryKey
				);
			}
		}

	}

	@Nested
	@DisplayName("Warm-up flushes")
	class WarmUpFlushes {

		@Test
		@DisplayName("Writes exactly the blocks written since the previous flush")
		void shouldWriteOnlyTheBlocksWrittenSinceThePreviousFlush() {
			final FieldLengthTable table = new FieldLengthTable();
			final Set<Integer> touched = new TreeSet<>();
			final BlockDisk disk = new BlockDisk();
			for (final int blockKey : new int[]{0, 1, 5, LAST_BLOCK}) {
				fill(table, blockKey, 3, touched);
			}
			assertArrayEquals(new int[]{0, 1, 5, LAST_BLOCK}, changedKeys(disk.apply(table.collectChangedBlocks())));
			disk.assertHolds(table, touched);

			table.put(pk(1, 700), 9);
			touched.add(pk(1, 700));
			final LengthBlockEmission second = disk.apply(table.collectChangedBlocks());
			assertArrayEquals(new int[]{1}, changedKeys(second), "Only the written block is rewritten.");
			assertEquals(0, second.removedBlockKeys().length);
			disk.assertHolds(table, touched);

			final LengthBlockEmission idle = disk.apply(table.collectChangedBlocks());
			assertTrue(idle.changedBlocks().isEmpty(), "A flush without writes writes nothing.");
			assertArrayEquals(new int[]{0, 1, 5, LAST_BLOCK}, idle.blockKeys());
		}

		@Test
		@DisplayName("Removes a block that emptied, and rewrites one that emptied and filled again")
		void shouldRemoveAnEmptiedBlockAndRewriteARefilledOne() {
			final FieldLengthTable table = new FieldLengthTable();
			final Set<Integer> touched = new TreeSet<>();
			final BlockDisk disk = new BlockDisk();
			fill(table, 2, 4, touched);
			fill(table, 3, 4, touched);
			disk.apply(table.collectChangedBlocks());

			for (int low = 0; low < 4; low++) {
				table.remove(pk(2, low));
				table.remove(pk(3, low));
			}
			table.put(pk(3, 100), 7);
			touched.add(pk(3, 100));
			final LengthBlockEmission emission = disk.apply(table.collectChangedBlocks());
			assertArrayEquals(new int[]{2}, emission.removedBlockKeys());
			assertArrayEquals(new int[]{3}, changedKeys(emission));
			assertArrayEquals(new int[]{3}, emission.blockKeys());
			disk.assertHolds(table, touched);
		}

		@Test
		@DisplayName("A block that appears and empties between two flushes leaves nothing to remove")
		void shouldNotRemoveABlockThatNeverReachedTheDisk() {
			final FieldLengthTable table = new FieldLengthTable();
			final BlockDisk disk = new BlockDisk();
			table.put(pk(4, 1), 3);
			table.remove(pk(4, 1));
			final LengthBlockEmission emission = disk.apply(table.collectChangedBlocks());
			assertTrue(emission.changedBlocks().isEmpty());
			assertEquals(0, emission.removedBlockKeys().length);
			assertEquals(0, emission.blockKeys().length);
		}

		@Test
		@DisplayName("Random writes over sparse and dense blocks keep the disk equal to the table")
		void shouldKeepTheDiskEqualToTheTableOverRandomWrites() {
			final Random random = new Random(42);
			final FieldLengthTable table = new FieldLengthTable();
			final Set<Integer> touched = new TreeSet<>();
			final BlockDisk disk = new BlockDisk();
			// one dense block, promoted past a third of its slots
			fill(table, 6, 30_000, touched);
			for (int round = 0; round < 12; round++) {
				for (int i = 0; i < 2_000; i++) {
					final int primaryKey = pk(random.nextInt(9), random.nextInt(1 << 16));
					if (random.nextInt(3) == 0) {
						table.remove(primaryKey);
					} else {
						table.put(primaryKey, 1 + random.nextInt(5_000));
					}
					touched.add(primaryKey);
				}
				// now and then a block is emptied completely
				if (round % 4 == 3) {
					final int emptied = random.nextInt(9);
					for (final int primaryKey : touched) {
						if (primaryKey >>> 16 == emptied) {
							table.remove(primaryKey);
						}
					}
				}
				disk.apply(table.collectChangedBlocks());
				disk.assertHolds(table, touched);
			}
			assertTrue(table.isDenseBlock(pk(6, 0)), "The fixture must keep a dense block.");
		}

	}

	@Nested
	@DisplayName("Warm-up savepoint rollback")
	class WarmUpSavepointRollback {

		@Test
		@DisplayName("A savepoint rolled back between two flushes leaves the disk equal to the restored table")
		void shouldLeaveTheDiskEqualToTheRestoredTable() {
			final FieldLengthTable table = new FieldLengthTable();
			final Set<Integer> touched = new TreeSet<>();
			final BlockDisk disk = new BlockDisk();
			fill(table, 0, 5, touched);
			fill(table, 1, 5, touched);
			disk.apply(table.collectChangedBlocks());

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			try {
				// a new block and an emptied one, both rolled back
				fill(table, 9, 5, touched);
				for (int low = 0; low < 5; low++) {
					table.remove(pk(1, low));
				}
			} finally {
				savepoint.rollback();
			}

			final LengthBlockEmission emission = disk.apply(table.collectChangedBlocks());
			assertEquals(0, emission.removedBlockKeys().length, "Nothing on disk left the table.");
			assertArrayEquals(new int[]{0, 1}, emission.blockKeys());
			disk.assertHolds(table, touched);
		}

	}

	@Nested
	@DisplayName("Transactions")
	@Tag(TRANSACTION)
	class Transactions {

		@Test
		@DisplayName("A flush inside a transaction writes the blocks it overrode, and the committed copy starts clean")
		void shouldWriteTheOverriddenBlocksAndStartTheCommittedCopyClean() {
			final FieldLengthTable table = new FieldLengthTable();
			final Set<Integer> touched = new TreeSet<>();
			final BlockDisk disk = new BlockDisk();
			fill(table, 0, 5, touched);
			fill(table, 1, 5, touched);
			fill(table, 2, 5, touched);
			disk.apply(table.collectChangedBlocks());

			assertStateAfterCommit(
				table,
				t -> {
					t.put(pk(1, 300), 11);
					for (int low = 0; low < 5; low++) {
						t.remove(pk(2, low));
					}
					t.put(pk(8, 1), 2);
					touched.add(pk(1, 300));
					touched.add(pk(8, 1));
					final LengthBlockEmission emission = disk.apply(t.collectChangedBlocks());
					assertArrayEquals(new int[]{1, 8}, changedKeys(emission));
					assertArrayEquals(new int[]{2}, emission.removedBlockKeys());
					assertArrayEquals(new int[]{0, 1, 8}, emission.blockKeys());
					disk.assertHolds(t, touched);
				},
				(original, committed) -> {
					assertNotSame(original, committed);
					disk.assertHolds(committed, touched);
					final LengthBlockEmission next = committed.collectChangedBlocks();
					assertTrue(next.changedBlocks().isEmpty(), "Nothing changed since the flush.");
					assertEquals(0, next.removedBlockKeys().length);
					assertArrayEquals(new int[]{0, 1, 8}, next.blockKeys());
				}
			);
		}

		@Test
		@DisplayName("Blocks written in place before a transaction are written by its flush, and removals follow")
		void shouldCarryTheInPlaceMarksIntoTheTransaction() {
			final FieldLengthTable table = new FieldLengthTable();
			final Set<Integer> touched = new TreeSet<>();
			final BlockDisk disk = new BlockDisk();
			fill(table, 0, 5, touched);
			fill(table, 3, 5, touched);
			disk.apply(table.collectChangedBlocks());
			// written in place, never flushed
			table.put(pk(3, 999), 4);
			touched.add(pk(3, 999));

			assertStateAfterCommit(
				table,
				t -> {
					for (int low = 0; low < 5; low++) {
						t.remove(pk(0, low));
					}
					final LengthBlockEmission emission = disk.apply(t.collectChangedBlocks());
					assertArrayEquals(new int[]{3}, changedKeys(emission));
					assertArrayEquals(new int[]{0}, emission.removedBlockKeys());
					disk.assertHolds(t, touched);
				},
				(original, committed) -> {
					disk.assertHolds(committed, touched);
					assertTrue(committed.collectChangedBlocks().changedBlocks().isEmpty());
				}
			);
		}

		@Test
		@DisplayName("The committed copy knows the blocks on disk: its first flush removes a block that emptied")
		void shouldRemoveABlockInTheFirstFlushAfterTheCommit() {
			final FieldLengthTable table = new FieldLengthTable();
			final Set<Integer> touched = new TreeSet<>();
			final BlockDisk disk = new BlockDisk();
			fill(table, 0, 5, touched);
			fill(table, 1, 5, touched);
			disk.apply(table.collectChangedBlocks());

			final FieldLengthTable[] committedCopy = new FieldLengthTable[1];
			assertStateAfterCommit(
				table,
				t -> {
					t.put(pk(0, 50), 3);
					touched.add(pk(0, 50));
					disk.apply(t.collectChangedBlocks());
				},
				(original, committed) -> committedCopy[0] = committed
			);
			assertStateAfterCommit(
				committedCopy[0],
				t -> {
					for (int low = 0; low < 5; low++) {
						t.remove(pk(1, low));
					}
					final LengthBlockEmission emission = disk.apply(t.collectChangedBlocks());
					assertArrayEquals(new int[]{1}, emission.removedBlockKeys());
					assertArrayEquals(new int[]{0}, emission.blockKeys());
				},
				(original, committed) -> disk.assertHolds(committed, touched)
			);
		}

		@Test
		@DisplayName("Warm-up flushes followed by flushed commits keep the disk equal to the table")
		void shouldKeepTheDiskEqualAcrossWarmUpAndCommits() {
			final Random random = new Random(11);
			final Set<Integer> touched = new TreeSet<>();
			final BlockDisk disk = new BlockDisk();
			FieldLengthTable table = new FieldLengthTable();
			fill(table, 4, 25_000, touched);
			disk.apply(table.collectChangedBlocks());
			for (int round = 0; round < 8; round++) {
				final FieldLengthTable[] next = new FieldLengthTable[1];
				assertStateAfterCommit(
					table,
					t -> {
						for (int i = 0; i < 1_500; i++) {
							final int primaryKey = pk(random.nextInt(6), random.nextInt(1 << 16));
							if (random.nextInt(3) == 0) {
								t.remove(primaryKey);
							} else {
								t.put(primaryKey, 1 + random.nextInt(900));
							}
							touched.add(primaryKey);
						}
						disk.apply(t.collectChangedBlocks());
					},
					(original, committed) -> next[0] = committed
				);
				table = next[0];
				disk.assertHolds(table, touched);
			}
		}

	}

	@Nested
	@DisplayName("Reload")
	class Reload {

		@Test
		@DisplayName("A reloaded table writes nothing until written, then only what was written - removals included")
		void shouldReloadCleanAndKeepTheBlocksOnDisk() {
			final FieldLengthTable table = new FieldLengthTable();
			final Set<Integer> touched = new TreeSet<>();
			final BlockDisk disk = new BlockDisk();
			fill(table, 0, 30_000, touched);
			fill(table, 2, 10, touched);
			fill(table, 5, 10, touched);
			disk.apply(table.collectChangedBlocks());

			final FieldLengthTable reloaded = disk.reload();
			assertTrue(reloaded.isDenseBlock(pk(0, 0)), "A block past a third of its slots loads dense.");
			assertTrue(reloaded.collectChangedBlocks().changedBlocks().isEmpty(), "A reloaded table is clean.");

			reloaded.put(pk(5, 77), 3);
			touched.add(pk(5, 77));
			for (int low = 0; low < 10; low++) {
				reloaded.remove(pk(2, low));
			}
			final LengthBlockEmission emission = disk.apply(reloaded.collectChangedBlocks());
			assertArrayEquals(new int[]{5}, changedKeys(emission));
			assertArrayEquals(new int[]{2}, emission.removedBlockKeys(), "The reload restores the blocks on disk.");
			disk.assertHolds(reloaded, touched);
		}

		@Test
		@DisplayName("A block loads dense exactly when the runtime table would have promoted it")
		void shouldLoadABlockDenseOnlyPastThePromotionSize() {
			final FieldLengthTable table = new FieldLengthTable();
			final Set<Integer> touched = new TreeSet<>();
			final BlockDisk disk = new BlockDisk();
			fill(table, 3, FieldLengthTable.DENSE_PROMOTION_SIZE, touched);
			fill(table, 4, FieldLengthTable.DENSE_PROMOTION_SIZE + 1, touched);
			assertFalse(table.isDenseBlock(pk(3, 0)), "The runtime table keeps a block at the size sparse.");
			assertTrue(table.isDenseBlock(pk(4, 0)), "The runtime table promotes a block past the size.");
			disk.apply(table.collectChangedBlocks());

			final FieldLengthTable reloaded = disk.reload();
			assertFalse(reloaded.isDenseBlock(pk(3, 0)), "A block at the promotion size loads sparse.");
			assertTrue(reloaded.isDenseBlock(pk(4, 0)), "A block past the promotion size loads dense.");
			disk.assertHolds(reloaded, touched);
		}

		@Test
		@DisplayName("The first flush after a reload removes a block that emptied")
		void shouldRemoveABlockInTheFirstFlushAfterTheReload() {
			final FieldLengthTable table = new FieldLengthTable();
			final Set<Integer> touched = new TreeSet<>();
			final BlockDisk disk = new BlockDisk();
			fill(table, 1, 10, touched);
			fill(table, 7, 10, touched);
			disk.apply(table.collectChangedBlocks());

			final FieldLengthTable reloaded = disk.reload();
			for (int low = 0; low < 10; low++) {
				reloaded.remove(pk(7, low));
			}
			final LengthBlockEmission emission = disk.apply(reloaded.collectChangedBlocks());
			assertArrayEquals(new int[]{7}, emission.removedBlockKeys());
			assertArrayEquals(new int[]{1}, emission.blockKeys());
			disk.assertHolds(reloaded, touched);
		}

		@Test
		@DisplayName("Refuses blocks out of order and malformed blocks")
		void shouldRefuseMalformedBlocks() {
			final LengthBlock first = new LengthBlock(1, new char[]{1}, new byte[]{1});
			final LengthBlock second = new LengthBlock(2, new char[]{1}, new byte[]{1});
			assertThrows(
				GenericEvitaInternalError.class,
				() -> FieldLengthTable.fromPersistedBlocks(new LengthBlock[]{second, first})
			);
			assertThrows(
				GenericEvitaInternalError.class,
				() -> FieldLengthTable.fromPersistedBlocks(new LengthBlock[]{first, first})
			);
			assertThrows(GenericEvitaInternalError.class, () -> new LengthBlock(1, new char[0], new byte[0]));
			assertThrows(GenericEvitaInternalError.class, () -> new LengthBlock(1, new char[]{2, 1}, new byte[]{1, 1}));
			assertThrows(GenericEvitaInternalError.class, () -> new LengthBlock(1, new char[]{1}, new byte[]{0}));
			assertThrows(GenericEvitaInternalError.class, () -> new LengthBlock(1, new char[]{1}, new byte[]{1, 2}));
			assertThrows(GenericEvitaInternalError.class, () -> new LengthBlock(0x10000, new char[]{1}, new byte[]{1}));
		}

	}

	@Nested
	@DisplayName("Fulltext index")
	@Tag(TRANSACTION)
	class OfTheFulltextIndex {

		@Test
		@DisplayName("Collects the lengths of every field, a field registered in the transaction included")
		void shouldCollectEveryFieldIncludingOneRegisteredInTheTransaction() {
			final FulltextIndex index = new FulltextIndex(
				registry.getIndexAnalyzer("product", Locale.forLanguageTag("cs"))
			);
			index.addValue(attribute("title"), 5, "Rychlá hnědá liška");
			index.addValue(attribute("title"), pk(3, 1), "Líná kočka");
			final LengthBlockEmission[] warmUp = index.collectChangedLengthBlocks();
			assertEquals(1, warmUp.length);
			assertArrayEquals(new int[]{0, 3}, changedKeys(warmUp[0]));

			assertStateAfterCommit(
				index,
				t -> {
					t.addValue(attribute("body"), 9, "Skáče přes plot");
					final LengthBlockEmission[] emissions = t.collectChangedLengthBlocks();
					assertEquals(2, emissions.length, "The field the transaction registered is collected too.");
					assertTrue(emissions[0].changedBlocks().isEmpty(), "The title was not written.");
					assertArrayEquals(new int[]{0}, changedKeys(emissions[1]));
					assertArrayEquals(
						new int[]{0, 3}, emissions[0].blockKeys(), "An unwritten field still lists its blocks."
					);
				},
				(original, committed) -> {
					final LengthBlockEmission[] next = committed.collectChangedLengthBlocks();
					assertEquals(2, next.length);
					assertTrue(next[0].changedBlocks().isEmpty());
					assertTrue(next[1].changedBlocks().isEmpty(), "The committed copy shares the flushed state.");
				}
			);
		}

	}

}
