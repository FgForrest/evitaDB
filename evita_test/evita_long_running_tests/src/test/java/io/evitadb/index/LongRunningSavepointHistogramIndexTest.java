/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.index;

import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static io.evitadb.test.TestTags.HISTOGRAM;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static io.evitadb.test.TestTags.SLOW;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertSavepointCommitKeeps;
import static io.evitadb.utils.AssertionUtils.assertSavepointRollbackRestores;

/**
 * Generational randomized backfill proof that {@link SimpleHistogramIndex} snapshots and restores correctly under a
 * per-entity savepoint (Ref: #1252). Because the index is a
 * {@link io.evitadb.core.transaction.memory.TransactionalLayerProducer} whose transactional changes (the inner filter
 * and cardinality indexes) are snapshotable, the proof drives the index directly and asserts its logical filter
 * contents (value → sorted owner ids, read via {@link LongRunningHistogramIndexTest#snapshot(HistogramIndex)}).
 *
 * Each generation seeds a fresh random non-empty index outside any transaction, then within one real transaction
 * applies a random baseline batch of insert/remove mutations (standing for *prior* entities in the same transaction —
 * these must SURVIVE the savepoint rollback), opens a savepoint, applies a random in-savepoint batch preceded by a
 * guaranteed-visible marker mutation (standing for the *failing* entity — these must be REVERTED on rollback / KEPT on
 * commit), and asserts the histogram contents against the oracle captured at savepoint open. The transaction then
 * commits so the commit-time layer-sweep verification proves the restore left no dangling layer. The run is
 * time-bounded; the random seed is echoed on failure for deterministic reproduction.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("SimpleHistogramIndex savepoint rollback/commit backfill (generational fuzz)")
@Tag(INDEXING)
@Tag(MANAGEMENT)
@Tag(HISTOGRAM)
@Tag(TRANSACTION)
class LongRunningSavepointHistogramIndexTest implements TimeBoundedTestSupport {
	private static final String HISTOGRAM_NAME = "priceHistogram";
	private static final String REFERENCE_NAME = "BRAND";
	private static final int MAX_OPS = 10;

	@ParameterizedTest(name = "Savepoint rollback restores the exact pre-savepoint histogram contents")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	@DisplayName("Savepoint rollback restores the exact pre-savepoint histogram contents")
	void shouldRollBackHistogramIndex(@Nonnull GenerationalTestInput input) {
		runFor(input, 1000, 0L, (random, iteration) -> {
			final HistogramState state = new HistogramState(random);
			assertSavepointRollbackRestores(
				state.index,
				tested -> state.applyRandomMutations(random, 1 + random.nextInt(MAX_OPS)),
				LongRunningHistogramIndexTest::snapshot,
				tested -> {
					// a guaranteed-visible mutation makes the in-savepoint batch non-vacuous
					state.forceMutation();
					state.applyRandomMutations(random, random.nextInt(MAX_OPS));
				}
			);
			return iteration + 1;
		});
	}

	@ParameterizedTest(name = "Savepoint commit keeps the in-savepoint histogram contents")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	@DisplayName("Savepoint commit keeps the in-savepoint histogram contents")
	void shouldCommitHistogramIndex(@Nonnull GenerationalTestInput input) {
		runFor(input, 1000, 0L, (random, iteration) -> {
			final HistogramState state = new HistogramState(random);
			assertSavepointCommitKeeps(
				state.index,
				tested -> state.applyRandomMutations(random, 1 + random.nextInt(MAX_OPS)),
				LongRunningHistogramIndexTest::snapshot,
				tested -> {
					state.forceMutation();
					state.applyRandomMutations(random, random.nextInt(MAX_OPS));
				}
			);
			return iteration + 1;
		});
	}

	/**
	 * A {@link SimpleHistogramIndex} paired with an in-test model of its contents (value → ownerPK → cardinality count)
	 * so randomized insert/remove mutations can be generated that keep the model and index in lockstep — a remove only
	 * ever targets a present (value, owner) pair, so the cardinality index never underflows. The initial non-empty index
	 * is seeded outside any transaction; mutations are applied to the index (and mirrored in the model) within the
	 * framework's transaction.
	 */
	private static final class HistogramState {
		private static final int MAX_VALUE = 50;
		private static final int MAX_OWNER = 30;
		private static final int FORCED_VALUE = -1;

		private final SimpleHistogramIndex index =
			new SimpleHistogramIndex(HISTOGRAM_NAME, REFERENCE_NAME, Integer.class, 0);
		private final Map<Integer, Map<Integer, Integer>> model = new HashMap<>();
		// reserved owner-id sequence for guaranteed-new forced mutations, kept clear of the 1..MAX_OWNER random range
		private int forcedOwnerSeq = 100_000;

		HistogramState(@Nonnull Random random) {
			final int seedOperations = 20 + random.nextInt(20);
			for (int i = 0; i < seedOperations; i++) {
				addRandomValue(random);
			}
		}

		/**
		 * Applies `count` random insert/remove mutations, mirrored into the model.
		 */
		void applyRandomMutations(@Nonnull Random random, int count) {
			for (int i = 0; i < count; i++) {
				if (this.model.isEmpty() || random.nextBoolean()) {
					addRandomValue(random);
				} else {
					removeRandomValue(random);
				}
			}
		}

		/**
		 * Applies one guaranteed-visible change: inserts a brand-new owner id (drawn from a reserved sequence random ops
		 * never touch) for a reserved value, crossing the 0→1 cardinality boundary so the filter index gains a visible
		 * entry and the in-savepoint batch is never a no-op.
		 */
		void forceMutation() {
			final int ownerPK = ++this.forcedOwnerSeq;
			this.index.insertValue(null, FORCED_VALUE, ownerPK);
			this.model.computeIfAbsent(FORCED_VALUE, k -> new HashMap<>()).merge(ownerPK, 1, Integer::sum);
		}

		/**
		 * Inserts a random (value, ownerPK) pair, mirrored into the model (incrementing its cardinality).
		 */
		private void addRandomValue(@Nonnull Random random) {
			final int value = random.nextInt(MAX_VALUE);
			final int ownerPK = random.nextInt(MAX_OWNER) + 1;
			this.index.insertValue(null, value, ownerPK);
			this.model.computeIfAbsent(value, k -> new HashMap<>()).merge(ownerPK, 1, Integer::sum);
		}

		/**
		 * Removes a random present (value, ownerPK) pair, mirrored into the model; a no-op when the model is empty.
		 */
		private void removeRandomValue(@Nonnull Random random) {
			final int[] entry = pickRandomEntry(random);
			if (entry != null) {
				this.index.removeValue(null, entry[0], entry[1]);
				decrement(entry[0], entry[1]);
			}
		}

		/**
		 * Picks a random present (value, ownerPK) pair with cardinality > 0, returns `{value, ownerPK}` or null.
		 */
		@Nullable
		private int[] pickRandomEntry(@Nonnull Random random) {
			final List<int[]> entries = new ArrayList<>();
			for (final Map.Entry<Integer, Map<Integer, Integer>> valueEntry : this.model.entrySet()) {
				for (final Map.Entry<Integer, Integer> ownerEntry : valueEntry.getValue().entrySet()) {
					if (ownerEntry.getValue() > 0) {
						entries.add(new int[]{valueEntry.getKey(), ownerEntry.getKey()});
					}
				}
			}
			if (entries.isEmpty()) {
				return null;
			}
			return entries.get(random.nextInt(entries.size()));
		}

		/**
		 * Decrements cardinality for (value, ownerPK) and cleans up zero-cardinality entries.
		 */
		private void decrement(int value, int ownerPK) {
			final Map<Integer, Integer> ownerMap = this.model.get(value);
			if (ownerMap != null) {
				final int newCardinality = ownerMap.merge(ownerPK, -1, Integer::sum);
				if (newCardinality <= 0) {
					ownerMap.remove(ownerPK);
					if (ownerMap.isEmpty()) {
						this.model.remove(value);
					}
				}
			}
		}
	}

}
