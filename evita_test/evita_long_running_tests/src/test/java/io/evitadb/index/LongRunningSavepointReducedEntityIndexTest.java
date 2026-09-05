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

import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.core.transaction.memory.AbstractSavepointFuzzTest;
import io.evitadb.core.transaction.memory.TransactionalStateProducer;
import io.evitadb.index.LongRunningReducedEntityIndexTest.ReducedIndexSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static io.evitadb.test.TestTags.TRANSACTION;

/**
 * Generational randomized backfill proof that {@link ReducedEntityIndex} snapshots and restores correctly under a
 * per-entity savepoint (Ref: #1252). Because the index is a
 * {@link io.evitadb.core.transaction.memory.TransactionalLayerProducer} whose transactional changes are
 * `Snapshotable`, the proof drives the index directly and asserts its logical content (all primary keys plus the
 * per-language primary keys, read via {@link ReducedEntityIndex#getAllPrimaryKeys()} /
 * {@link ReducedEntityIndex#getRecordsWithLanguageFormula}).
 *
 * Each generation seeds a fresh random non-empty index outside any transaction, then within one real transaction
 * applies a random baseline batch of PK / locale mutations (standing for *prior* entities in the same transaction —
 * these must SURVIVE the savepoint rollback), opens a savepoint, applies a random in-savepoint batch preceded by a
 * guaranteed-visible marker mutation (standing for the *failing* entity — these must be REVERTED on rollback / KEPT on
 * commit), and asserts the index content against the oracle captured at savepoint open. The transaction then commits so
 * the commit-time layer-sweep verification proves the restore left no dangling layer. The run is time-bounded; the
 * random seed is echoed on failure for deterministic reproduction.
 *
 * The scenario is declared once and run by {@link AbstractSavepointFuzzTest} in BOTH phases: the transactional
 * savepoint described above, and the WARM_UP savepoint where the same writes land straight on the delegate
 * structures and are rewound from the inverses they journal themselves. See that class for the shape of one
 * generation, for the mid-savepoint read every case is asserted through, and for why the warm-up half runs
 * exclusively.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("ReducedEntityIndex savepoint rollback/commit backfill (generational fuzz)")
@Tag(INDEXING)
@Tag(MANAGEMENT)
@Tag(TRANSACTION)
class LongRunningSavepointReducedEntityIndexTest extends AbstractSavepointFuzzTest<ReducedIndexSnapshot> {
	private static final int MAX_OPS = 10;

	@Nonnull
	@Override
	protected FuzzGeneration<ReducedIndexSnapshot> newGeneration(@Nonnull Random random) {
		return new ReducedIndexState(random);
	}

	/**
	 * A {@link ReducedEntityIndex} paired with an in-test model of its logical content (primary keys and a
	 * locale → entity ids map) so randomized mutations can be generated that keep the model and index in lockstep.
	 * The initial non-empty index is seeded outside any transaction; mutations are applied to the index (and mirrored
	 * in the model) within the framework's transaction.
	 */
	private static final class ReducedIndexState implements FuzzGeneration<ReducedIndexSnapshot> {
		private static final int MAX_ENTITY_ID = 50;
		private static final Locale[] TEST_LOCALES = {
			Locale.ENGLISH, Locale.GERMAN, Locale.FRENCH, new Locale("cs")
		};

		private final EntitySchemaContract schema = LongRunningReducedEntityIndexTest.createEvolvingSchema();
		private final ReducedEntityIndex index = LongRunningReducedEntityIndexTest.createInstance();
		private final Set<Integer> pks = new HashSet<>();
		private final Map<Locale, Set<Integer>> locales = new HashMap<>();
		// reserved entity-id sequence for guaranteed-new forced mutations, kept clear of the 1..MAX_ENTITY_ID range
		private int forcedEntitySeq = 1000;

		ReducedIndexState(@Nonnull Random random) {
			final int seedOperations = 20 + random.nextInt(20);
			for (int i = 0; i < seedOperations; i++) {
				addRandom(random);
			}
		}

		@Nonnull
		@Override
		public TransactionalStateProducer<?> subject() {
			return this.index;
		}

		@Nonnull
		@Override
		public ReducedIndexSnapshot contents() {
			return LongRunningReducedEntityIndexTest.snapshot(this.index);
		}

		@Override
		public void applyBaselineOperations(@Nonnull Random random) {
			applyRandomMutations(random, 1 + random.nextInt(MAX_OPS));
		}

		@Override
		public void applySavepointOperations(@Nonnull Random random) {
			applyRandomMutations(random, random.nextInt(MAX_OPS));
			// applied LAST: a marker applied first enters the model and a later random operation can undo it
			forceMutation();
		}

		/**
		 * Applies `count` random PK / locale add/remove mutations, mirrored into the model.
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
		 * Applies one guaranteed-visible change: inserts a brand-new primary key drawn from a reserved sequence that
		 * random ops never touch, so the in-savepoint batch is never a no-op.
		 */
		void forceMutation() {
			final int pk = ++this.forcedEntitySeq;
			this.index.insertPrimaryKeyIfMissing(pk);
			this.pks.add(pk);
		}

		private boolean isEmpty() {
			return this.pks.isEmpty() && this.locales.isEmpty();
		}

		/**
		 * Adds either a random primary key or a random locale-to-PK entry, mirrored into the model; both index calls
		 * are idempotent, so the model guard just avoids redundant work.
		 */
		private void addRandom(@Nonnull Random random) {
			if (random.nextBoolean()) {
				final int pk = random.nextInt(MAX_ENTITY_ID) + 1;
				if (this.pks.add(pk)) {
					this.index.insertPrimaryKeyIfMissing(pk);
				}
			} else {
				final Locale locale = TEST_LOCALES[random.nextInt(TEST_LOCALES.length)];
				final int pk = random.nextInt(MAX_ENTITY_ID) + 1;
				if (this.locales.computeIfAbsent(locale, l -> new HashSet<>()).add(pk)) {
					this.index.upsertLanguage(locale, pk, this.schema);
				}
			}
		}

		/**
		 * Removes a random present primary key or locale-to-PK entry, mirrored into the model; a no-op when both the
		 * PK set and the locale map are empty.
		 */
		private void removeRandom(@Nonnull Random random) {
			if (!this.pks.isEmpty() && (this.locales.isEmpty() || random.nextBoolean())) {
				final List<Integer> pkList = new ArrayList<>(this.pks);
				final int pk = pkList.get(random.nextInt(pkList.size()));
				this.index.removePrimaryKey(pk);
				this.pks.remove(pk);
			} else if (!this.locales.isEmpty()) {
				final List<Locale> localeList = new ArrayList<>(this.locales.keySet());
				final Locale locale = localeList.get(random.nextInt(localeList.size()));
				final Set<Integer> entities = this.locales.get(locale);
				final List<Integer> entityIds = new ArrayList<>(entities);
				final int pk = entityIds.get(random.nextInt(entityIds.size()));
				this.index.removeLanguage(locale, pk);
				entities.remove(pk);
				if (entities.isEmpty()) {
					this.locales.remove(locale);
				}
			}
		}
	}

}
