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

package io.evitadb.index.attribute;

import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.core.transaction.memory.AbstractSavepointFuzzTest;
import io.evitadb.core.transaction.memory.TransactionalStateProducer;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityTypeClassifierResolver;
import io.evitadb.index.attribute.LongRunningGlobalUniqueIndexTest.GlobalUniqueSnapshot;
import io.evitadb.test.Entities;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.TRANSACTION;

/**
 * Generational randomized backfill proof that {@link GlobalUniqueIndex} — its value tree and per-entity-type record-id
 * bitmaps — snapshots and restores correctly under a per-entity savepoint (Ref: #1252). Because the index and all its
 * children are {@link io.evitadb.core.transaction.memory.TransactionalLayerProducer}s whose transactional changes are
 * `Snapshotable`, the proof drives the {@link GlobalUniqueIndex} directly and asserts its logical content via the
 * value-comparable {@link LongRunningGlobalUniqueIndexTest#snapshot(GlobalUniqueIndex)} oracle (value → packed entity
 * tuple, plus the per-type record ids).
 *
 * Each generation seeds a fresh random non-empty index outside any transaction, then within one real transaction
 * applies a random baseline batch of register/unregister mutations (standing for *prior* entities in the same
 * transaction — these must SURVIVE the savepoint rollback), opens a savepoint, applies a random in-savepoint batch
 * preceded by a guaranteed-visible marker mutation (standing for the *failing* entity — these must be REVERTED on
 * rollback / KEPT on commit), and asserts the content against the oracle captured at savepoint open. The transaction
 * then commits so the commit-time layer-sweep verification proves the restore left no dangling layer. The run is
 * time-bounded; the random seed is echoed on failure for deterministic reproduction.
 *
 * The scenario is declared once and run by {@link AbstractSavepointFuzzTest} in BOTH phases: the transactional
 * savepoint described above, and the WARM_UP savepoint where the same writes land straight on the delegate
 * structures and are rewound from the inverses they journal themselves. See that class for the shape of one
 * generation, for the mid-savepoint read every case is asserted through, and for why the warm-up half runs
 * exclusively.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("GlobalUniqueIndex savepoint rollback/commit backfill (generational fuzz)")
@Tag(INDEXING)
@Tag(ATTRIBUTE)
@Tag(TRANSACTION)
class LongRunningSavepointGlobalUniqueIndexTest extends AbstractSavepointFuzzTest<GlobalUniqueSnapshot> {
	private static final int MAX_OPS = 10;
	private final Catalog catalog = Mockito.mock(Catalog.class);

	@BeforeEach
	void setUp() {
		final EntityCollection productCollection = Mockito.mock(EntityCollection.class);
		Mockito.when(productCollection.getEntityTypePrimaryKey()).thenReturn(1);
		Mockito.when(productCollection.getEntityType()).thenReturn(Entities.PRODUCT);
		Mockito.when(this.catalog.getCollectionForEntityPrimaryKeyOrThrowException(1)).thenReturn(productCollection);
		Mockito.when(this.catalog.getCollectionForEntityOrThrowException(Entities.PRODUCT)).thenReturn(productCollection);
	}

	@Nonnull
	@Override
	protected FuzzGeneration<GlobalUniqueSnapshot> newGeneration(@Nonnull Random random) {
		return new GlobalUniqueState(random, this.catalog);
	}

	/**
	 * A {@link GlobalUniqueIndex} (attached to a mocked catalog) paired with an in-test model of its value → owning
	 * record id mapping so randomized mutations can be generated that keep the model and index in lockstep. All keys use
	 * {@link Entities#PRODUCT} and no locale; values and record ids are drawn from a strictly monotonic sequence so every
	 * registered key is unique — no unique-value violation can ever arise. The initial non-empty index is seeded outside
	 * any transaction; mutations are applied to the index (and mirrored in the model) within the framework's transaction.
	 */
	private static final class GlobalUniqueState implements FuzzGeneration<GlobalUniqueSnapshot> {
		private final GlobalUniqueIndex index;
		private final EntityTypeClassifierResolver classifierResolver;
		// value → owning record id
		private final Map<String, Integer> valueToRecord = new HashMap<>();
		// monotonic sequence for random unique values / record ids
		private int seq = 0;
		// reserved monotonic sequence for guaranteed-new forced mutations, kept clear of the `seq` range
		private int forcedSeq = 1_000_000;

		GlobalUniqueState(@Nonnull Random random, @Nonnull Catalog catalog) {
			this.index = new GlobalUniqueIndex(Scope.LIVE, new AttributeKey("code"), String.class);
			this.classifierResolver = new EntityTypeClassifierResolver() {
				@Override
				public int toEntityTypePrimaryKey(@Nonnull String entityType) {
					return catalog.getCollectionForEntityOrThrowException(entityType).getEntityTypePrimaryKey();
				}

				@Nonnull
				@Override
				public String toEntityTypeName(int entityTypePrimaryKey) {
					return catalog.getCollectionForEntityPrimaryKeyOrThrowException(entityTypePrimaryKey).getEntityType();
				}
			};
			final int seedOperations = 20 + random.nextInt(20);
			for (int i = 0; i < seedOperations; i++) {
				addRandomUniqueKey();
			}
		}

		@Nonnull
		@Override
		public TransactionalStateProducer<?> subject() {
			return this.index;
		}

		@Nonnull
		@Override
		public GlobalUniqueSnapshot contents() {
			return LongRunningGlobalUniqueIndexTest.snapshot(this.index);
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
		 * Applies `count` random register/unregister mutations, mirrored into the model.
		 */
		void applyRandomMutations(@Nonnull Random random, int count) {
			for (int i = 0; i < count; i++) {
				if (this.valueToRecord.isEmpty() || random.nextBoolean()) {
					addRandomUniqueKey();
				} else {
					removeRandomUniqueKey(random);
				}
			}
		}

		/**
		 * Applies one guaranteed-visible change: registers a brand-new value / record id drawn from a reserved sequence
		 * that random ops never touch, so the in-savepoint batch is never a no-op.
		 */
		void forceMutation() {
			final int id = ++this.forcedSeq;
			final String value = "F_" + id;
			this.index.registerUniqueKey(value, Entities.PRODUCT, null, id, this.classifierResolver);
			this.valueToRecord.put(value, id);
		}

		/**
		 * Registers the next strictly-fresh value / record id from the monotonic sequence, mirrored into the model.
		 */
		private void addRandomUniqueKey() {
			final int id = ++this.seq;
			final String value = "V_" + id;
			this.index.registerUniqueKey(value, Entities.PRODUCT, null, id, this.classifierResolver);
			this.valueToRecord.put(value, id);
		}

		/**
		 * Unregisters a random present value, mirrored into the model; a no-op when the model is empty.
		 */
		private void removeRandomUniqueKey(@Nonnull Random random) {
			if (this.valueToRecord.isEmpty()) {
				return;
			}
			final List<String> values = new ArrayList<>(this.valueToRecord.keySet());
			final String value = values.get(random.nextInt(values.size()));
			final int id = this.valueToRecord.remove(value);
			this.index.unregisterUniqueKey(value, Entities.PRODUCT, null, id, this.classifierResolver);
		}
	}

}
