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

import io.evitadb.api.index.EntityIndexType;
import io.evitadb.dataType.Scope;
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.SLOW;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertSavepointCommitKeeps;
import static io.evitadb.utils.AssertionUtils.assertSavepointRollbackRestores;

/**
 * Generational randomized backfill proof that {@link ReferencedTypeEntityIndex} snapshots and restores correctly under
 * a per-entity savepoint (Ref: #1252). Because the index is a
 * {@link io.evitadb.core.transaction.memory.TransactionalLayerProducer} whose transactional changes are `Snapshotable`,
 * the proof drives the parent {@link ReferencedTypeEntityIndex} directly and asserts its logical content (index primary
 * keys plus the referenced-entity → index-PK mapping, read via the sibling
 * {@link LongRunningReferencedTypeEntityIndexTest#snapshot(ReferencedTypeEntityIndex)}).
 *
 * Each generation seeds a fresh random non-empty index outside any transaction, then within one real transaction
 * applies a random baseline batch of (indexPk, referencedEntityPk) mutations (standing for *prior* entities in the same
 * transaction — these must SURVIVE the savepoint rollback), opens a savepoint, applies a random in-savepoint batch
 * preceded by a guaranteed-visible marker mutation (standing for the *failing* entity — these must be REVERTED on
 * rollback / KEPT on commit), and asserts the content against the oracle captured at savepoint open. The transaction
 * then commits so the commit-time layer-sweep verification proves the restore left no dangling layer. The run is
 * time-bounded; the random seed is echoed on failure for deterministic reproduction.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("ReferencedTypeEntityIndex savepoint rollback/commit backfill (generational fuzz)")
@Tag(INDEXING)
@Tag(MANAGEMENT)
@Tag(REFERENCE)
@Tag(TRANSACTION)
class LongRunningSavepointReferencedTypeEntityIndexTest implements TimeBoundedTestSupport {
	private static final String ENTITY_TYPE = "Product";
	private static final int INDEX_PK = 1;
	private static final String REFERENCE_NAME = "BRAND";
	private static final int MAX_OPS = 10;

	@ParameterizedTest(name = "Savepoint rollback restores the exact pre-savepoint index contents")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	@DisplayName("Savepoint rollback restores the exact pre-savepoint index contents")
	void shouldRollBackReferencedTypeEntityIndex(@Nonnull GenerationalTestInput input) {
		runFor(input, 1000, 0L, (random, iteration) -> {
			final ReferencedTypeState state = new ReferencedTypeState(random);
			assertSavepointRollbackRestores(
				state.index,
				tested -> state.applyRandomMutations(random, 1 + random.nextInt(MAX_OPS)),
				LongRunningReferencedTypeEntityIndexTest::snapshot,
				tested -> {
					// a guaranteed-visible mutation makes the in-savepoint batch non-vacuous
					state.forceMutation();
					state.applyRandomMutations(random, random.nextInt(MAX_OPS));
				}
			);
			return iteration + 1;
		});
	}

	@ParameterizedTest(name = "Savepoint commit keeps the in-savepoint index contents")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	@DisplayName("Savepoint commit keeps the in-savepoint index contents")
	void shouldCommitReferencedTypeEntityIndex(@Nonnull GenerationalTestInput input) {
		runFor(input, 1000, 0L, (random, iteration) -> {
			final ReferencedTypeState state = new ReferencedTypeState(random);
			assertSavepointCommitKeeps(
				state.index,
				tested -> state.applyRandomMutations(random, 1 + random.nextInt(MAX_OPS)),
				LongRunningReferencedTypeEntityIndexTest::snapshot,
				tested -> {
					state.forceMutation();
					state.applyRandomMutations(random, random.nextInt(MAX_OPS));
				}
			);
			return iteration + 1;
		});
	}

	/**
	 * A {@link ReferencedTypeEntityIndex} paired with an in-test model of its logical content (index PK → referenced
	 * entity PKs) so randomized mutations can be generated that keep the model and index in lockstep. The initial
	 * non-empty index is seeded outside any transaction; mutations are applied to the index (and mirrored in the model)
	 * within the framework's transaction. Duplicate inserts are skipped so the `Set`-based model matches the index's
	 * multiset cardinality tracking.
	 */
	private static final class ReferencedTypeState {
		private static final int MAX_INDEX_PK = 30;
		private static final int MAX_REF_PK = 20;

		private final ReferencedTypeEntityIndex index = new ReferencedTypeEntityIndex(
			INDEX_PK,
			ENTITY_TYPE,
			new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, REFERENCE_NAME)
		);
		private final Map<Integer, Set<Integer>> model = new HashMap<>();
		// reserved index-PK sequence for guaranteed-new forced mutations, kept clear of the 1..MAX_INDEX_PK range
		private int forcedIndexPkSeq = 1000;

		ReferencedTypeState(@Nonnull Random random) {
			final int seedOperations = 20 + random.nextInt(20);
			for (int i = 0; i < seedOperations; i++) {
				addRandomPair(random);
			}
		}

		/**
		 * Applies `count` random insert/remove mutations, mirrored into the model.
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
		 * Applies one guaranteed-visible change: inserts a brand-new (indexPk, referencedEntityPk) pair whose index PK is
		 * drawn from a reserved sequence that random ops never touch, so the in-savepoint batch is never a no-op.
		 */
		void forceMutation() {
			final int indexPk = ++this.forcedIndexPkSeq;
			this.index.insertPrimaryKeyIfMissing(indexPk, 1);
			this.model.computeIfAbsent(indexPk, k -> new HashSet<>()).add(1);
		}

		/**
		 * Adds a random not-yet-present (indexPk, referencedEntityPk) pair; bounded retries avoid an infinite spin when a
		 * random pick collides, giving up silently as a harmless no-op.
		 */
		private void addRandomPair(@Nonnull Random random) {
			for (int attempt = 0; attempt < 10; attempt++) {
				final int indexPk = random.nextInt(MAX_INDEX_PK) + 1;
				final int refPk = random.nextInt(MAX_REF_PK) + 1;
				final Set<Integer> refs = this.model.computeIfAbsent(indexPk, k -> new HashSet<>());
				if (refs.add(refPk)) {
					this.index.insertPrimaryKeyIfMissing(indexPk, refPk);
					return;
				}
			}
		}

		/**
		 * Removes a random present (indexPk, referencedEntityPk) pair, mirrored into the model; a no-op when the model is
		 * empty.
		 */
		private void removeRandomPair(@Nonnull Random random) {
			if (this.model.isEmpty()) {
				return;
			}
			final List<Integer> indexPks = new ArrayList<>(this.model.keySet());
			final int indexPk = indexPks.get(random.nextInt(indexPks.size()));
			final Set<Integer> refs = this.model.get(indexPk);
			final List<Integer> refPks = new ArrayList<>(refs);
			final int refPk = refPks.get(random.nextInt(refPks.size()));
			this.index.removePrimaryKey(indexPk, refPk);
			refs.remove(refPk);
			if (refs.isEmpty()) {
				this.model.remove(indexPk);
			}
		}
	}

}
