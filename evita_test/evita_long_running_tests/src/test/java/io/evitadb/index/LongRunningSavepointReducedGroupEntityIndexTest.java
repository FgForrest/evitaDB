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

import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static io.evitadb.test.TestTags.SLOW;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertSavepointCommitKeeps;
import static io.evitadb.utils.AssertionUtils.assertSavepointRollbackRestores;
import static org.mockito.Mockito.mock;

/**
 * Generational randomized backfill proof that {@link ReducedGroupEntityIndex} — including its PK-cardinality and
 * filter-attribute-cardinality bookkeeping — snapshots and restores correctly under a per-entity savepoint
 * (Ref: #1252). Because the index is a
 * {@link io.evitadb.core.transaction.memory.TransactionalLayerProducer} whose transactional changes are
 * `Snapshotable`, the proof drives the index directly and asserts its logical content (all primary keys, the
 * referenced-entity → owner-PK mapping and every filter index's records, read via
 * {@link ReducedGroupEntityIndex#getAllPrimaryKeys()},
 * {@link ReducedGroupEntityIndex#getReferencedEntityPrimaryKeys()} /
 * {@link ReducedGroupEntityIndex#getOwnerPKsForReferencedEntity(int)} and
 * {@link ReducedGroupEntityIndex#getFilterIndex}).
 *
 * Each generation seeds a fresh random non-empty index outside any transaction, then within one real transaction
 * applies a random baseline batch of PK-pair / filter-attribute mutations (standing for *prior* entities in the same
 * transaction — these must SURVIVE the savepoint rollback), opens a savepoint, applies a random in-savepoint batch
 * preceded by a guaranteed-visible marker mutation (standing for the *failing* entity — these must be REVERTED on
 * rollback / KEPT on commit), and asserts the index content against the oracle captured at savepoint open. The
 * transaction then commits so the commit-time layer-sweep verification proves the restore left no dangling layer. The
 * run is time-bounded; the random seed is echoed on failure for deterministic reproduction.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("ReducedGroupEntityIndex savepoint rollback/commit backfill (generational fuzz)")
@Tag(INDEXING)
@Tag(MANAGEMENT)
@Tag(TRANSACTION)
class LongRunningSavepointReducedGroupEntityIndexTest implements TimeBoundedTestSupport {
	private static final int MAX_OPS = 10;

	@ParameterizedTest(name = "Savepoint rollback restores the exact pre-savepoint index contents")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	@DisplayName("Savepoint rollback restores the exact pre-savepoint index contents")
	void shouldRollBackReducedGroupEntityIndex(@Nonnull GenerationalTestInput input) {
		runFor(input, 1000, 0L, (random, iteration) -> {
			final GroupState state = new GroupState(random);
			assertSavepointRollbackRestores(
				state.index,
				tested -> state.applyRandomMutations(random, 1 + random.nextInt(MAX_OPS)),
				LongRunningReducedGroupEntityIndexTest::snapshot,
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
	void shouldCommitReducedGroupEntityIndex(@Nonnull GenerationalTestInput input) {
		runFor(input, 1000, 0L, (random, iteration) -> {
			final GroupState state = new GroupState(random);
			assertSavepointCommitKeeps(
				state.index,
				tested -> state.applyRandomMutations(random, 1 + random.nextInt(MAX_OPS)),
				LongRunningReducedGroupEntityIndexTest::snapshot,
				tested -> {
					state.forceMutation();
					state.applyRandomMutations(random, random.nextInt(MAX_OPS));
				}
			);
			return iteration + 1;
		});
	}

	/**
	 * A {@link ReducedGroupEntityIndex} paired with an in-test model of its logical content: `(entityPk →
	 * referencedPks)` pairs mirroring the PK-cardinality bookkeeping, and a `value → (recordId → cardinality)`
	 * multiset mirroring the filter-attribute cardinality bookkeeping. Randomized mutations are generated so the model
	 * and index stay in lockstep — in particular removals only target present pairs / present cardinality entries, so
	 * the index's premise assertions never trip. The initial non-empty index is seeded outside any transaction;
	 * mutations are applied to the index (and mirrored in the model) within the framework's transaction.
	 */
	private static final class GroupState {
		private static final int MAX_ENTITY_ID = 30;
		private static final int MAX_REFERENCED_ID = 20;
		private static final int MAX_ATTR_VALUE = 10;

		private static final ReferenceSchemaContract REF_SCHEMA = mock(ReferenceSchemaContract.class);
		private static final AttributeSchemaContract ATTR_SCHEMA =
			LongRunningReducedGroupEntityIndexTest.createFilterableAttributeSchema("code", String.class);
		private static final Set<Locale> NO_LOCALES = Collections.emptySet();

		private final ReducedGroupEntityIndex index = LongRunningReducedGroupEntityIndexTest.createIndex(100);
		// entityPk -> set of referencedPks (each unique pair contributes one unit of PK cardinality)
		private final Map<Integer, Set<Integer>> pkPairs = new HashMap<>();
		// attribute value -> (recordId -> cardinality), mirroring the filter-attribute cardinality index
		private final Map<String, Map<Integer, Integer>> attrCardinality = new HashMap<>();
		// reserved entity-id sequence for guaranteed-new forced mutations, kept clear of the 1..MAX_ENTITY_ID range
		private int forcedEntitySeq = 1000;

		GroupState(@Nonnull Random random) {
			final int seedOperations = 20 + random.nextInt(20);
			for (int i = 0; i < seedOperations; i++) {
				addRandom(random);
			}
		}

		/**
		 * Applies `count` random PK-pair / filter-attribute add/remove mutations, mirrored into the model.
		 */
		void applyRandomMutations(@Nonnull Random random, int count) {
			for (int i = 0; i < count; i++) {
				if (isEmpty() || random.nextBoolean()) {
					addRandom(random);
				} else {
					removeRandom(random);
				}
			}
		}

		/**
		 * Applies one guaranteed-visible change: inserts a brand-new `(entityPk, referencedPk)` pair whose entity id is
		 * drawn from a reserved sequence that random ops never touch, so the resulting PK boundary crossing always adds
		 * a fresh primary key and the in-savepoint batch is never a no-op.
		 */
		void forceMutation() {
			final int entityPk = ++this.forcedEntitySeq;
			this.index.insertPrimaryKeyIfMissing(entityPk, 1);
			this.pkPairs.computeIfAbsent(entityPk, k -> new HashSet<>()).add(1);
		}

		private boolean isEmpty() {
			return this.pkPairs.isEmpty() && this.attrCardinality.isEmpty();
		}

		/**
		 * Adds either a random `(entityPk, referencedPk)` pair (only when the pair is not yet present, to keep the
		 * model's cardinality exact) or a random filter attribute (always, incrementing its cardinality), mirrored into
		 * the model.
		 */
		private void addRandom(@Nonnull Random random) {
			if (random.nextBoolean()) {
				final int entityPk = random.nextInt(MAX_ENTITY_ID) + 1;
				final int referencedPk = random.nextInt(MAX_REFERENCED_ID) + 1;
				final Set<Integer> refs = this.pkPairs.computeIfAbsent(entityPk, k -> new HashSet<>());
				if (refs.add(referencedPk)) {
					this.index.insertPrimaryKeyIfMissing(entityPk, referencedPk);
				}
			} else {
				final String value = "VAL_" + (random.nextInt(MAX_ATTR_VALUE) + 1);
				final int recordId = random.nextInt(MAX_ENTITY_ID) + 1;
				this.index.insertFilterAttribute(REF_SCHEMA, ATTR_SCHEMA, NO_LOCALES, null, value, recordId, false);
				this.attrCardinality
					.computeIfAbsent(value, k -> new HashMap<>())
					.merge(recordId, 1, Integer::sum);
			}
		}

		/**
		 * Removes a random present `(entityPk, referencedPk)` pair or a random present filter-attribute occurrence
		 * (decrementing its cardinality), mirrored into the model; a no-op only when both models are empty.
		 */
		private void removeRandom(@Nonnull Random random) {
			final boolean canRemovePair = !this.pkPairs.isEmpty();
			final boolean canRemoveAttr = !this.attrCardinality.isEmpty();
			if (canRemovePair && (!canRemoveAttr || random.nextBoolean())) {
				final List<Integer> entityPks = new ArrayList<>(this.pkPairs.keySet());
				final int entityPk = entityPks.get(random.nextInt(entityPks.size()));
				final Set<Integer> refs = this.pkPairs.get(entityPk);
				final List<Integer> refList = new ArrayList<>(refs);
				final int referencedPk = refList.get(random.nextInt(refList.size()));
				this.index.removePrimaryKey(entityPk, referencedPk);
				refs.remove(referencedPk);
				if (refs.isEmpty()) {
					this.pkPairs.remove(entityPk);
				}
			} else if (canRemoveAttr) {
				final List<String> values = new ArrayList<>(this.attrCardinality.keySet());
				final String value = values.get(random.nextInt(values.size()));
				final Map<Integer, Integer> byRecord = this.attrCardinality.get(value);
				final List<Integer> recordIds = new ArrayList<>(byRecord.keySet());
				final int recordId = recordIds.get(random.nextInt(recordIds.size()));
				this.index.removeFilterAttribute(REF_SCHEMA, ATTR_SCHEMA, NO_LOCALES, null, value, recordId);
				final int newCount = byRecord.get(recordId) - 1;
				if (newCount == 0) {
					byRecord.remove(recordId);
					if (byRecord.isEmpty()) {
						this.attrCardinality.remove(value);
					}
				} else {
					byRecord.put(recordId, newCount);
				}
			}
		}
	}

}
