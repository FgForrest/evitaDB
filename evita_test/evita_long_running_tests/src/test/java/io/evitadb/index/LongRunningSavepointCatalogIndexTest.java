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
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.collection.EntityCollection;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static io.evitadb.test.TestTags.SLOW;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertSavepointCommitKeeps;
import static io.evitadb.utils.AssertionUtils.assertSavepointRollbackRestores;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Generational randomized backfill proof that {@link CatalogIndex} — together with the
 * {@link io.evitadb.index.attribute.GlobalUniqueIndex} sub-indexes it holds — snapshots and restores correctly under a
 * per-entity savepoint (Ref: #1252). Because the index is a
 * {@link io.evitadb.core.transaction.memory.TransactionalLayerProducer} whose transactional changes
 * ({@code CatalogIndex.CatalogIndexChanges}) are `Snapshotable`, the proof drives the {@link CatalogIndex} directly and
 * asserts its logical unique-attribute contents via the value-comparable oracle
 * {@link LongRunningCatalogIndexTest#snapshot}.
 *
 * Each generation seeds a fresh random non-empty index outside any transaction, then within one real transaction applies
 * a random baseline batch of insert/remove unique-attribute mutations (standing for *prior* entities in the same
 * transaction — these must SURVIVE the savepoint rollback), opens a savepoint, applies a random in-savepoint batch
 * preceded by a guaranteed-visible marker mutation (standing for the *failing* entity — these must be REVERTED on
 * rollback / KEPT on commit), and asserts the contents against the oracle captured at savepoint open. The transaction
 * then commits so the commit-time layer-sweep verification proves the restore left no dangling layer. The run is
 * time-bounded; the random seed is echoed on failure for deterministic reproduction.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@SuppressWarnings("SameParameterValue")
@DisplayName("CatalogIndex savepoint rollback/commit backfill (generational fuzz)")
@Tag(INDEXING)
@Tag(MANAGEMENT)
@Tag(TRANSACTION)
class LongRunningSavepointCatalogIndexTest implements TimeBoundedTestSupport {
	private static final int MAX_OPS = 10;
	// entity type and attribute-name set shared with the oracle reader so both agree on which attributes exist
	private static final String ENTITY_TYPE = LongRunningCatalogIndexTest.ENTITY_TYPE;
	private static final String[] ATTR_NAMES = LongRunningCatalogIndexTest.ATTR_NAMES;
	private static final int ENTITY_TYPE_PK = 1;

	@ParameterizedTest(name = "Savepoint rollback restores the exact pre-savepoint unique-attribute contents")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	@DisplayName("Savepoint rollback restores the exact pre-savepoint unique-attribute contents")
	void shouldRollBackCatalogIndex(@Nonnull GenerationalTestInput input) {
		runFor(input, 1000, 0L, (random, iteration) -> {
			final CatalogState state = new CatalogState(random);
			assertSavepointRollbackRestores(
				state.index,
				tested -> state.applyRandomMutations(random, 1 + random.nextInt(MAX_OPS)),
				LongRunningCatalogIndexTest::snapshot,
				tested -> {
					// a guaranteed-visible mutation makes the in-savepoint batch non-vacuous
					state.forceMutation();
					state.applyRandomMutations(random, random.nextInt(MAX_OPS));
				}
			);
			return iteration + 1;
		});
	}

	@ParameterizedTest(name = "Savepoint commit keeps the in-savepoint unique-attribute contents")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	@DisplayName("Savepoint commit keeps the in-savepoint unique-attribute contents")
	void shouldCommitCatalogIndex(@Nonnull GenerationalTestInput input) {
		runFor(input, 1000, 0L, (random, iteration) -> {
			final CatalogState state = new CatalogState(random);
			assertSavepointCommitKeeps(
				state.index,
				tested -> state.applyRandomMutations(random, 1 + random.nextInt(MAX_OPS)),
				LongRunningCatalogIndexTest::snapshot,
				tested -> {
					state.forceMutation();
					state.applyRandomMutations(random, random.nextInt(MAX_OPS));
				}
			);
			return iteration + 1;
		});
	}

	/**
	 * Creates a mock {@link Catalog} that resolves {@link #ENTITY_TYPE} to {@link #ENTITY_TYPE_PK} in both directions.
	 *
	 * @return a configured Catalog mock
	 */
	@Nonnull
	private static Catalog createMockCatalog() {
		final EntityCollection entityCollection = mock(EntityCollection.class);
		when(entityCollection.getEntityTypePrimaryKey()).thenReturn(ENTITY_TYPE_PK);
		when(entityCollection.getEntityType()).thenReturn(ENTITY_TYPE);

		final Catalog catalog = mock(Catalog.class);
		when(catalog.getCollectionForEntityOrThrowException(ENTITY_TYPE)).thenReturn(entityCollection);
		when(catalog.getCollectionForEntityPrimaryKeyOrThrowException(ENTITY_TYPE_PK)).thenReturn(entityCollection);
		return catalog;
	}

	/**
	 * Creates a non-localized {@link GlobalAttributeSchemaContract} mock for the given attribute name (String type, not
	 * unique globally within locale) — the same shape the oracle reader probes with.
	 *
	 * @param attributeName the name of the attribute
	 * @return a configured mock
	 */
	@Nonnull
	private static GlobalAttributeSchemaContract createNonLocalizedAttributeSchema(@Nonnull String attributeName) {
		final GlobalAttributeSchemaContract schema = mock(GlobalAttributeSchemaContract.class);
		when(schema.getName()).thenReturn(attributeName);
		doReturn(String.class).when(schema).getType();
		when(schema.isLocalized()).thenReturn(false);
		when(schema.isUniqueGloballyWithinLocaleInScope(Scope.LIVE)).thenReturn(false);
		return schema;
	}

	/**
	 * Creates a simple {@link EntitySchemaContract} mock that returns {@link #ENTITY_TYPE}.
	 *
	 * @return a configured mock
	 */
	@Nonnull
	private static EntitySchemaContract createEntitySchema() {
		final EntitySchemaContract schema = mock(EntitySchemaContract.class);
		when(schema.getName()).thenReturn(ENTITY_TYPE);
		return schema;
	}

	/**
	 * A {@link CatalogIndex} paired with an in-test model of its unique-attribute contents (attributeName → value →
	 * recordId) so randomized insert/remove mutations can be generated that keep the model and index in lockstep. The
	 * initial non-empty index is seeded outside any transaction; mutations are applied to the index (and mirrored in the
	 * model) within the framework's transaction. Unique values are minted as `name-recordId` from a monotonic record
	 * sequence, so every value within an attribute is distinct.
	 */
	private static final class CatalogState {
		private final CatalogIndex index;
		private final EntitySchemaContract entitySchema = createEntitySchema();
		private final Map<String, Map<Object, Integer>> reference = new HashMap<>();
		private int nextRecordId = 1;
		// reserved record-id sequence for guaranteed-new forced mutations, kept clear of the growing nextRecordId range
		private int forcedRecordSeq = 1_000_000;

		CatalogState(@Nonnull Random random) {
			this.index = new CatalogIndex(Scope.LIVE);
			this.index.attachToCatalog(null, createMockCatalog());
			final int seedOperations = 20 + random.nextInt(20);
			for (int i = 0; i < seedOperations; i++) {
				insertRandomAttribute(random);
			}
		}

		/**
		 * Applies `count` random unique-attribute insert/remove mutations, mirrored into the model.
		 */
		void applyRandomMutations(@Nonnull Random random, int count) {
			for (int i = 0; i < count; i++) {
				if (this.reference.isEmpty() || random.nextBoolean()) {
					insertRandomAttribute(random);
				} else {
					removeRandomAttribute(random);
				}
			}
		}

		/**
		 * Applies one guaranteed-visible change: inserts a unique value for a brand-new record id drawn from a reserved
		 * sequence that random ops never touch, so the in-savepoint batch is never a no-op.
		 */
		void forceMutation() {
			final String attributeName = ATTR_NAMES[0];
			final int recordId = ++this.forcedRecordSeq;
			final String value = attributeName + "-forced-" + recordId;
			this.index.insertUniqueAttribute(
				this.entitySchema, createNonLocalizedAttributeSchema(attributeName),
				Collections.emptySet(), null, value, recordId
			);
			this.reference.computeIfAbsent(attributeName, k -> new HashMap<>()).put(value, recordId);
		}

		/**
		 * Inserts a fresh unique value on a random attribute, mirrored into the model.
		 */
		private void insertRandomAttribute(@Nonnull Random random) {
			final String attributeName = ATTR_NAMES[random.nextInt(ATTR_NAMES.length)];
			final int recordId = this.nextRecordId++;
			final String value = attributeName + "-" + recordId;
			this.index.insertUniqueAttribute(
				this.entitySchema, createNonLocalizedAttributeSchema(attributeName),
				Collections.emptySet(), null, value, recordId
			);
			this.reference.computeIfAbsent(attributeName, k -> new HashMap<>()).put(value, recordId);
		}

		/**
		 * Removes a random present unique value, mirrored into the model; drops the attribute entry once its last value
		 * is removed.
		 */
		private void removeRandomAttribute(@Nonnull Random random) {
			final List<String> names = new ArrayList<>(this.reference.keySet());
			final String attributeName = names.get(random.nextInt(names.size()));
			final Map<Object, Integer> values = this.reference.get(attributeName);
			final List<Object> valueList = new ArrayList<>(values.keySet());
			final Object value = valueList.get(random.nextInt(valueList.size()));
			final int recordId = values.get(value);
			this.index.removeUniqueAttribute(
				this.entitySchema, createNonLocalizedAttributeSchema(attributeName),
				Collections.emptySet(), null, value, recordId
			);
			values.remove(value);
			if (values.isEmpty()) {
				this.reference.remove(attributeName);
			}
		}
	}

}
