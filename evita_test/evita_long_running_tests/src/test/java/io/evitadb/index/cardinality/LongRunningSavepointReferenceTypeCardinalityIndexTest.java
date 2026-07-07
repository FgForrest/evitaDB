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

package io.evitadb.index.cardinality;

import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import io.evitadb.utils.NumberUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.SLOW;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertSavepointCommitKeeps;
import static io.evitadb.utils.AssertionUtils.assertSavepointRollbackRestores;

/**
 * Generational randomized backfill proof that {@link ReferenceTypeCardinalityIndex} — a
 * {@link io.evitadb.core.transaction.memory.TransactionalLayerProducer} whose transactional composed-key cardinality
 * tree and companion referenced-primary-keys map are `Snapshotable` — snapshots and restores correctly under a
 * per-entity savepoint (Ref: #1252). The proof drives the index directly and asserts its logical content (the internal
 * cardinality tree read via {@link ReferenceTypeCardinalityIndex#getCardinalities()} plus the companion map read via
 * {@link ReferenceTypeCardinalityIndex#getReferencedPrimaryKeysIndex()}).
 *
 * Each generation seeds a fresh random non-empty index outside any transaction, then within one real transaction
 * applies a random baseline batch of cardinality mutations (standing for *prior* entities in the same transaction —
 * these must SURVIVE the savepoint rollback), opens a savepoint, applies a random in-savepoint batch preceded by a
 * guaranteed-visible marker mutation (standing for the *failing* entity — these must be REVERTED on rollback / KEPT on
 * commit), and asserts the content against the oracle captured at savepoint open. The transaction then commits so the
 * commit-time layer-sweep verification proves the restore left no dangling layer. The run is time-bounded; the random
 * seed is echoed on failure for deterministic reproduction.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("ReferenceTypeCardinalityIndex savepoint rollback/commit backfill (generational fuzz)")
@Tag(INDEXING)
@Tag(ATTRIBUTE)
@Tag(REFERENCE)
@Tag(TRANSACTION)
class LongRunningSavepointReferenceTypeCardinalityIndexTest implements TimeBoundedTestSupport {
	private static final int MAX_OPS = 10;

	@ParameterizedTest(name = "Savepoint rollback restores the exact pre-savepoint cardinality contents")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	@DisplayName("Savepoint rollback restores the exact pre-savepoint cardinality contents")
	void shouldRollBackReferenceTypeCardinalityIndex(@Nonnull GenerationalTestInput input) {
		runFor(input, 1000, 0L, (random, iteration) -> {
			final ReferenceCardinalityState state = new ReferenceCardinalityState(random);
			assertSavepointRollbackRestores(
				state.index,
				tested -> state.applyRandomMutations(random, 1 + random.nextInt(MAX_OPS)),
				LongRunningReferenceTypeCardinalityIndexTest::snapshot,
				tested -> {
					// a guaranteed-visible mutation makes the in-savepoint batch non-vacuous
					state.forceMutation();
					state.applyRandomMutations(random, random.nextInt(MAX_OPS));
				}
			);
			return iteration + 1;
		});
	}

	@ParameterizedTest(name = "Savepoint commit keeps the in-savepoint cardinality contents")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	@DisplayName("Savepoint commit keeps the in-savepoint cardinality contents")
	void shouldCommitReferenceTypeCardinalityIndex(@Nonnull GenerationalTestInput input) {
		runFor(input, 1000, 0L, (random, iteration) -> {
			final ReferenceCardinalityState state = new ReferenceCardinalityState(random);
			assertSavepointCommitKeeps(
				state.index,
				tested -> state.applyRandomMutations(random, 1 + random.nextInt(MAX_OPS)),
				LongRunningReferenceTypeCardinalityIndexTest::snapshot,
				tested -> {
					state.forceMutation();
					state.applyRandomMutations(random, random.nextInt(MAX_OPS));
				}
			);
			return iteration + 1;
		});
	}

	/**
	 * A {@link ReferenceTypeCardinalityIndex} paired with an in-test model of its `(indexPk, refPk) → pair count`
	 * contents (keyed by the composed `long` from {@link NumberUtils#pack(int, int)}) so randomized mutations can be
	 * generated that keep the model and index in lockstep. The initial non-empty index is seeded outside any
	 * transaction; mutations are applied to the index (and mirrored in the model) within the framework's transaction.
	 */
	private static final class ReferenceCardinalityState {
		private static final int MAX_INDEX_PK = 20;
		private static final int MAX_REF_PK = 10;

		private final ReferenceTypeCardinalityIndex index = new ReferenceTypeCardinalityIndex();
		private final Map<Long, Integer> model = new HashMap<>();
		// reserved index-PK sequence for guaranteed-new forced mutations, kept clear of the 1..MAX_INDEX_PK random range
		private int forcedIndexPkSeq = 1000;

		ReferenceCardinalityState(@Nonnull Random random) {
			final int seedOperations = 20 + random.nextInt(20);
			for (int i = 0; i < seedOperations; i++) {
				addRandomPair(random);
			}
		}

		/**
		 * Applies `count` random pair add/remove mutations, mirrored into the model.
		 */
		void applyRandomMutations(@Nonnull Random random, int count) {
			for (int i = 0; i < count; i++) {
				if (this.model.isEmpty() || random.nextBoolean()) {
					addRandomPair(random);
				} else {
					removeRandomPair(random);
				}
			}
		}

		/**
		 * Applies one guaranteed-visible change: adds a pair for a brand-new index PK drawn from a reserved sequence that
		 * random ops never touch, so the in-savepoint batch is never a no-op (a fresh index PK always crosses the
		 * cardinality boundary).
		 */
		void forceMutation() {
			final int indexPk = ++this.forcedIndexPkSeq;
			final int refPk = 1;
			this.index.addRecord(indexPk, refPk);
			this.model.merge(NumberUtils.pack(indexPk, refPk), 1, Integer::sum);
		}

		/**
		 * Adds one (indexPk, refPk) occurrence — a brand-new pair or an increment of an existing one — mirrored into the
		 * model. Always a visible change (cardinality is per-pair additive), so no retry loop is needed.
		 */
		private void addRandomPair(@Nonnull Random random) {
			final int indexPk = random.nextInt(MAX_INDEX_PK) + 1;
			final int refPk = random.nextInt(MAX_REF_PK) + 1;
			this.index.addRecord(indexPk, refPk);
			this.model.merge(NumberUtils.pack(indexPk, refPk), 1, Integer::sum);
		}

		/**
		 * Removes one occurrence of a randomly chosen present pair (decrement, or full removal when its count reaches
		 * zero), mirrored into the model; a no-op when the model is empty.
		 */
		private void removeRandomPair(@Nonnull Random random) {
			if (this.model.isEmpty()) {
				return;
			}
			final List<Long> keys = new ArrayList<>(this.model.keySet());
			final long composed = keys.get(random.nextInt(keys.size()));
			final int[] parts = NumberUtils.unpack(composed);
			this.index.removeRecord(parts[0], parts[1]);
			final int newCount = this.model.get(composed) - 1;
			if (newCount == 0) {
				this.model.remove(composed);
			} else {
				this.model.put(composed, newCount);
			}
		}
	}

}
