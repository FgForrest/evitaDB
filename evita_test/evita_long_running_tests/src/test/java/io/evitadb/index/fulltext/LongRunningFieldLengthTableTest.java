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

import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.test.TestTags.DATA_TYPE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.SLOW;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Generational randomized proof for {@link FieldLengthTable}, run against a `primary key -> encoded length` map.
 *
 * ## What it drives
 *
 * The table decides its layout per 65,536-key block: a block turns dense past
 * {@link FieldLengthTable#DENSE_PROMOTION_SIZE} entities and returns to sparse only below
 * {@link FieldLengthTable#SPARSE_DEMOTION_SIZE}. The functional tests cross each threshold a few times. This test
 * steers one block back and forth across both of them for the whole run - growing it past the promotion size,
 * shrinking it below the demotion size, and again - while chaining the committed table of each generation into the
 * next. Every crossing happens inside a commit merge, which clones each touched block once and writes the
 * transaction's overrides into the clone in primary-key order, so a promotion or demotion can happen in the middle
 * of a merge. Light traffic on six more blocks, two of them in the negative half of the key space, keeps a sparse
 * block, an emptied block and a re-created block in the same table.
 *
 * ## What it checks after every generation
 *
 * - the published table holds exactly the model's lengths, and its entity count;
 * - the version that was written to still holds its pre-transaction lengths, because it shares every untouched
 *   block with the published one and a block written in place would show up there;
 * - the layout follows from the entity count where the hysteresis leaves no choice: dense above the promotion size,
 *   sparse below the demotion size. In between either layout is legal, since it depends on the block's history.
 *
 * A run long enough to matter must have seen the steered block in both layouts, and the test asserts it did.
 *
 * ```
 * mvn -pl evita_test/evita_functional_tests,evita_test/evita_long_running_tests test -P longRunning \
 *     -Dtest=LongRunningFieldLengthTableTest
 * ```
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Fulltext field length table (generational randomized proof)")
@Tag(INDEXING)
@Tag(DATA_TYPE)
class LongRunningFieldLengthTableTest implements TimeBoundedTestSupport {
	/**
	 * Bases of the lightly used blocks, including the top of the unsigned range a negative key falls into.
	 */
	private static final int[] LIGHT_BLOCK_BASES = {1 << 16, 5 << 16, 6 << 16, 40 << 16, Integer.MIN_VALUE, -(1 << 16)};
	/**
	 * How far above its base a key of a lightly used block reaches.
	 */
	private static final int LIGHT_BLOCK_SPREAD = 200;
	/**
	 * The entity count of the steered block at which the growing phase turns to shrinking.
	 */
	private static final int GROW_UNTIL = FieldLengthTable.DENSE_PROMOTION_SIZE + 2_000;
	/**
	 * The entity count of the steered block at which the shrinking phase turns to growing.
	 */
	private static final int SHRINK_UNTIL = FieldLengthTable.SPARSE_DEMOTION_SIZE - 2_000;
	/**
	 * The most writes one transaction makes.
	 */
	private static final int MAX_OPERATIONS_PER_TRANSACTION = 3_000;
	/**
	 * A run of at least this many generations must have seen the steered block in both layouts.
	 */
	private static final int GENERATIONS_TO_SEE_BOTH_LAYOUTS = 300;

	@DisplayName("survives a block steered across both layout thresholds by chained commits")
	@ParameterizedTest(name = "FieldLengthTable should survive generational randomized churn against a map oracle")
	@Tag(SLOW)
	@Tag(TRANSACTION)
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalProofTest(@Nonnull GenerationalTestInput input) {
		final TestState last = runFor(
			input,
			100,
			new TestState(new TreeMap<>(), new FieldLengthTable(), true, 0, false, false),
			(random, state) -> {
				final TreeMap<Integer, Integer> before = state.model();
				final TreeMap<Integer, Integer> model = new TreeMap<>(before);
				final boolean growing = nextPhase(state.growing(), steeredCount(before));
				final AtomicReference<FieldLengthTable> published = new AtomicReference<>();

				assertStateAfterCommit(
					state.table(),
					original -> {
						applyRandomOperations(random, original, model, growing);
						assertMatchesModel(original, model, "The transaction's view");
					},
					(original, committed) -> {
						final FieldLengthTable result = committed == null ? original : committed;
						if (result == original) {
							// carried forward as the same instance, legal only when no write changed a length
							assertEquals(before, model, "No new version was published, yet the model changed");
						}
						assertMatchesModel(result, model, "The published version");
						assertLayoutFollowsCount(result, steeredCount(model));
						// the previous version shares every untouched block with the published one
						assertMatchesModel(original, before, "The version that was written to");
						published.set(result);
					}
				);

				final boolean dense = published.get().isDenseBlock(0);
				return new TestState(
					model, published.get(), growing, state.generations() + 1,
					state.denseSeen() || dense, state.sparseSeen() || !dense
				);
			}
		);
		assertBothLayoutsSeen(last);
	}

	@DisplayName("survives a block steered across both layout thresholds outside a transaction")
	@ParameterizedTest(name = "FieldLengthTable should survive non-transactional (warm-up) churn against a map oracle")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalWarmUpProofTest(@Nonnull GenerationalTestInput input) {
		final TestState last = runFor(
			input,
			100,
			new TestState(new TreeMap<>(), new FieldLengthTable(), true, 0, false, false),
			(random, state) -> {
				final TreeMap<Integer, Integer> model = state.model();
				final FieldLengthTable table = state.table();
				final boolean growing = nextPhase(state.growing(), steeredCount(model));
				applyRandomOperations(random, table, model, growing);
				assertMatchesModel(table, model, "The table");
				assertLayoutFollowsCount(table, steeredCount(model));
				final boolean dense = table.isDenseBlock(0);
				return new TestState(
					model, table, growing, state.generations() + 1,
					state.denseSeen() || dense, state.sparseSeen() || !dense
				);
			}
		);
		assertBothLayoutsSeen(last);
	}

	/**
	 * Decides the phase of the next generation from the current one and the steered block's entity count.
	 *
	 * @param growing      whether the current phase grows the steered block
	 * @param steeredCount the steered block's entity count
	 * @return whether the next generation grows the steered block
	 */
	private static boolean nextPhase(boolean growing, int steeredCount) {
		if (growing && steeredCount > GROW_UNTIL) {
			return false;
		} else if (!growing && steeredCount < SHRINK_UNTIL) {
			return true;
		}
		return growing;
	}

	/**
	 * Applies a random batch of writes to the table and the model alike. Four writes in five go to the steered block
	 * and the rest to the light blocks. In the steered block a growing phase mostly puts and a shrinking phase mostly
	 * removes; a put of a zero length is a removal, and every write may hit an entity that already has a length.
	 *
	 * @param random  the source of randomness
	 * @param table   the table under test
	 * @param model   primary key to encoded length; updated alongside the table
	 * @param growing whether the steered block grows in this batch
	 */
	private static void applyRandomOperations(
		@Nonnull Random random,
		@Nonnull FieldLengthTable table,
		@Nonnull TreeMap<Integer, Integer> model,
		boolean growing
	) {
		final int operations = 1 + random.nextInt(MAX_OPERATIONS_PER_TRANSACTION);
		for (int i = 0; i < operations; i++) {
			final boolean steered = random.nextInt(5) != 0;
			final int primaryKey = steered
				? random.nextInt(1 << 16)
				: LIGHT_BLOCK_BASES[random.nextInt(LIGHT_BLOCK_BASES.length)] + random.nextInt(LIGHT_BLOCK_SPREAD);
			final boolean remove;
			if (steered) {
				final int removalPercent = growing ? 15 : 85;
				remove = random.nextInt(100) < removalPercent;
			} else {
				remove = random.nextInt(3) == 0;
			}
			if (remove) {
				// a removal aims at an existing entity when the block has one, or it would mostly miss
				final Integer existing = steered ? existingSteeredKey(model, primaryKey) : null;
				final int victim = existing == null ? primaryKey : existing;
				table.remove(victim);
				model.remove(victim);
			} else {
				final int length = randomLength(random);
				table.put(primaryKey, length);
				if (length == 0) {
					model.remove(primaryKey);
				} else {
					model.put(primaryKey, FieldLengthTable.encode(length));
				}
			}
		}
	}

	/**
	 * Draws a length: mostly short ones, which the encoding keeps exact, sometimes long ones, which it rounds, and
	 * now and then zero, which removes the entity.
	 *
	 * @param random the source of randomness
	 * @return the length
	 */
	private static int randomLength(@Nonnull Random random) {
		final int dice = random.nextInt(20);
		if (dice == 0) {
			return 0;
		} else if (dice < 4) {
			return random.nextInt(1_000_000);
		}
		return 1 + random.nextInt(60);
	}

	/**
	 * Finds an entity of the steered block that has a length, near the drawn key.
	 *
	 * @param model      primary key to encoded length
	 * @param primaryKey the drawn key
	 * @return an entity of the steered block with a length, or null when the block holds none
	 */
	private static Integer existingSteeredKey(@Nonnull TreeMap<Integer, Integer> model, int primaryKey) {
		final Integer above = model.ceilingKey(primaryKey);
		if (above != null && above < 1 << 16) {
			return above;
		}
		final Integer below = model.floorKey(primaryKey);
		return below != null && below >= 0 ? below : null;
	}

	/**
	 * Counts the entities of the steered block.
	 *
	 * @param model primary key to encoded length
	 * @return how many entities of the first 65,536-key block have a length
	 */
	private static int steeredCount(@Nonnull TreeMap<Integer, Integer> model) {
		return model.subMap(0, true, (1 << 16) - 1, true).size();
	}

	/**
	 * Asserts the table, as the caller's transaction sees it, holds exactly the model's lengths. The entity count is
	 * what rules out an extra length: with every modelled length present and the counts equal, nothing else is there.
	 *
	 * @param table the table to check
	 * @param model primary key to encoded length
	 * @param label which version is being checked, named in the failure
	 */
	private static void assertMatchesModel(
		@Nonnull FieldLengthTable table,
		@Nonnull TreeMap<Integer, Integer> model,
		@Nonnull String label
	) {
		assertEquals(model.size(), table.size(), () -> label + ": entity count");
		for (final Map.Entry<Integer, Integer> entry : model.entrySet()) {
			final int primaryKey = entry.getKey();
			assertEquals(entry.getValue(), table.getEncoded(primaryKey), () -> label + ": length of " + primaryKey);
		}
	}

	/**
	 * Asserts the steered block's layout where its entity count decides it.
	 *
	 * @param table        the committed table
	 * @param steeredCount the steered block's entity count
	 */
	private static void assertLayoutFollowsCount(@Nonnull FieldLengthTable table, int steeredCount) {
		if (steeredCount > FieldLengthTable.DENSE_PROMOTION_SIZE) {
			assertTrue(table.isDenseBlock(0), () -> "A block of " + steeredCount + " entities must be dense");
		} else if (steeredCount < FieldLengthTable.SPARSE_DEMOTION_SIZE) {
			assertFalse(table.isDenseBlock(0), () -> "A block of " + steeredCount + " entities must be sparse");
		}
	}

	/**
	 * Asserts a run long enough to matter saw the steered block in both layouts, so it crossed the thresholds rather
	 * than circling on one side of them.
	 *
	 * @param last the state the run ended with
	 */
	private static void assertBothLayoutsSeen(@Nonnull TestState last) {
		if (last.generations() >= GENERATIONS_TO_SEE_BOTH_LAYOUTS) {
			assertTrue(
				last.denseSeen() && last.sparseSeen(),
				() -> "After " + last.generations() + " generations the steered block was " +
					(last.denseSeen() ? "never sparse" : "never dense")
			);
		}
	}

	/**
	 * The state carried from one generation to the next.
	 *
	 * @param model       primary key to encoded length, as last published
	 * @param table       the last published table
	 * @param growing     whether the steered block is in its growing phase
	 * @param generations how many generations ran
	 * @param denseSeen   whether a generation ended with the steered block dense
	 * @param sparseSeen  whether a generation ended with the steered block sparse or absent
	 */
	private record TestState(
		@Nonnull TreeMap<Integer, Integer> model,
		@Nonnull FieldLengthTable table,
		boolean growing,
		int generations,
		boolean denseSeen,
		boolean sparseSeen
	) {}

}
