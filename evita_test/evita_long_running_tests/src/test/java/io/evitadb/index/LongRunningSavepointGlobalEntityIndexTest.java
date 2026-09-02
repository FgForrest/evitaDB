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
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EvolutionMode;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import javax.annotation.Nonnull;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
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
import static org.mockito.Mockito.when;

/**
 * Generational randomized backfill proof that {@link GlobalEntityIndex} snapshots and restores correctly under a
 * per-entity savepoint (Ref: #1252). Because the index is a
 * {@link io.evitadb.core.transaction.memory.TransactionalLayerProducer} whose transactional changes are `Snapshotable`,
 * the proof drives the parent {@link GlobalEntityIndex} directly and asserts its logical content (primary keys and
 * per-locale record ids, read via the sibling {@link LongRunningGlobalEntityIndexTest#snapshot(GlobalEntityIndex)}).
 *
 * Each generation seeds a fresh random non-empty index outside any transaction, then within one real transaction
 * applies a random baseline batch of mutations (standing for *prior* entities in the same transaction — these must
 * SURVIVE the savepoint rollback), opens a savepoint, applies a random in-savepoint batch preceded by a
 * guaranteed-visible marker mutation (standing for the *failing* entity — these must be REVERTED on rollback / KEPT on
 * commit), and asserts the content against the oracle captured at savepoint open. The transaction then commits so the
 * commit-time layer-sweep verification proves the restore left no dangling layer. The run is time-bounded; the random
 * seed is echoed on failure for deterministic reproduction.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("GlobalEntityIndex savepoint rollback/commit backfill (generational fuzz)")
@Tag(INDEXING)
@Tag(MANAGEMENT)
@Tag(TRANSACTION)
class LongRunningSavepointGlobalEntityIndexTest implements TimeBoundedTestSupport {
	private static final String ENTITY_TYPE = "Product";
	private static final int INDEX_PK = 1;
	private static final int MAX_OPS = 10;

	@ParameterizedTest(name = "Savepoint rollback restores the exact pre-savepoint index contents")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	@DisplayName("Savepoint rollback restores the exact pre-savepoint index contents")
	void shouldRollBackGlobalEntityIndex(@Nonnull GenerationalTestInput input) {
		runFor(input, 1000, 0L, (random, iteration) -> {
			final GlobalState state = new GlobalState(random);
			assertSavepointRollbackRestores(
				state.index,
				tested -> state.applyRandomMutations(random, 1 + random.nextInt(MAX_OPS)),
				LongRunningGlobalEntityIndexTest::snapshot,
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
	void shouldCommitGlobalEntityIndex(@Nonnull GenerationalTestInput input) {
		runFor(input, 1000, 0L, (random, iteration) -> {
			final GlobalState state = new GlobalState(random);
			assertSavepointCommitKeeps(
				state.index,
				tested -> state.applyRandomMutations(random, 1 + random.nextInt(MAX_OPS)),
				LongRunningGlobalEntityIndexTest::snapshot,
				tested -> {
					state.forceMutation();
					state.applyRandomMutations(random, random.nextInt(MAX_OPS));
				}
			);
			return iteration + 1;
		});
	}

	/**
	 * Builds a mocked entity schema that permits locale evolution, so `upsertLanguage` accepts any test locale.
	 *
	 * @return a schema contract with the `ADDING_LOCALES` evolution mode enabled
	 */
	@Nonnull
	private static EntitySchemaContract createEvolvingSchema() {
		final EntitySchemaContract schema = mock(EntitySchemaContract.class);
		when(schema.getLocales()).thenReturn(Set.of());
		when(schema.getEvolutionMode()).thenReturn(EnumSet.of(EvolutionMode.ADDING_LOCALES));
		return schema;
	}

	/**
	 * A {@link GlobalEntityIndex} paired with an in-test model of its logical content (primary keys plus a locale → PK
	 * mapping) so randomized mutations can be generated that keep the model and index in lockstep. The initial non-empty
	 * index is seeded outside any transaction; mutations are applied to the index (and mirrored in the model) within the
	 * framework's transaction.
	 */
	private static final class GlobalState {
		private static final int MAX_PK = 50;
		private static final Locale[] TEST_LOCALES = {
			Locale.ENGLISH, Locale.GERMAN, Locale.FRENCH, new Locale("cs")
		};

		private final EntitySchemaContract schema = createEvolvingSchema();
		private final GlobalEntityIndex index = new GlobalEntityIndex(
			INDEX_PK,
			ENTITY_TYPE,
			new EntityIndexKey(EntityIndexType.GLOBAL, Scope.LIVE)
		);
		private final Set<Integer> pks = new HashSet<>();
		private final Map<Locale, Set<Integer>> locales = new HashMap<>();
		// reserved PK sequence for guaranteed-new forced mutations, kept clear of the 1..MAX_PK random range
		private int forcedPkSeq = 1000;

		GlobalState(@Nonnull Random random) {
			final int seedOperations = 20 + random.nextInt(20);
			for (int i = 0; i < seedOperations; i++) {
				applyRandomMutation(random);
			}
		}

		/**
		 * Applies `count` random insert/remove mutations, mirrored into the model.
		 */
		void applyRandomMutations(@Nonnull Random random, int count) {
			for (int i = 0; i < count; i++) {
				applyRandomMutation(random);
			}
		}

		/**
		 * Applies one guaranteed-visible change: inserts a brand-new primary key drawn from a reserved sequence that
		 * random ops never touch, so the in-savepoint batch is never a no-op.
		 */
		void forceMutation() {
			final int pk = ++this.forcedPkSeq;
			this.index.insertPrimaryKeyIfMissing(pk);
			this.pks.add(pk);
		}

		/**
		 * Applies a single random operation (PK insert/remove, locale upsert/remove), mirrored into the model.
		 */
		private void applyRandomMutation(@Nonnull Random random) {
			final int operation = random.nextInt(4);
			final int pk = random.nextInt(MAX_PK) + 1;
			switch (operation) {
				case 0 -> {
					// insert PK
					this.index.insertPrimaryKeyIfMissing(pk);
					this.pks.add(pk);
				}
				case 1 -> {
					// remove PK (only if present in the model)
					if (!this.pks.isEmpty()) {
						final int targetPk = this.pks.iterator().next();
						this.index.removePrimaryKey(targetPk);
						this.pks.remove(targetPk);
					}
				}
				case 2 -> {
					// upsert language
					final Locale locale = TEST_LOCALES[random.nextInt(TEST_LOCALES.length)];
					this.index.upsertLanguage(locale, pk, this.schema);
					this.locales.computeIfAbsent(locale, l -> new HashSet<>()).add(pk);
				}
				case 3 -> {
					// remove language (only if present)
					if (!this.locales.isEmpty()) {
						final Locale locale = this.locales.keySet().iterator().next();
						final Set<Integer> pksForLocale = this.locales.get(locale);
						if (!pksForLocale.isEmpty()) {
							final int targetPk = pksForLocale.iterator().next();
							this.index.removeLanguage(locale, targetPk);
							pksForLocale.remove(targetPk);
							if (pksForLocale.isEmpty()) {
								this.locales.remove(locale);
							}
						}
					}
				}
				default -> throw new GenericEvitaInternalError(
					"Unexpected random operation: " + operation
				);
			}
		}
	}

}
