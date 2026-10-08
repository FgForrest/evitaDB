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

package io.evitadb.index.virtual;

import io.evitadb.api.CatalogState;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.catalog.VirtualReferencePartitionMaintenance;
import io.evitadb.core.catalog.VirtualReferencePartitionMaintenance.TransitionStage;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.VirtualReferencePartitionIndex;
import io.evitadb.index.VirtualReferencePartitionSlot;
import io.evitadb.index.virtual.TransitionHistory.Transition;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import static io.evitadb.api.query.QueryConstraints.entityFetchAllContent;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// TODO JNO - remove at the end of #1615
/**
 * Counts the work the write path of a catalog writing its single-owner partitions virtually does, rather than the
 * calls it makes: an owner of `k` single-owner partitions changing a locale, moving to another scope or being removed
 * must cost a number of registry operations proportional to `k` - each of its partitions written once - must bring no
 * index object into existence and must read the references of no other entity. A path that walked the partitions of
 * other owners, or populated a partition from another owner, would show in these counts long before it showed in a
 * latency.
 *
 * The registry operations are counted as the transitions the mutation passes: every owner row entered or vacated and
 * every value written into a row or removed from it reports one.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Operation counts of virtually written partitions")
@Tag(INDEXING)
@Tag(REFERENCE)
class VirtualPartitionOpCountGateTest implements EvitaTestSupport {
	private static final String CATALOG = "virtualPartitionOpCountGate";
	private static final String ENTITY_PRODUCT = "product";
	private static final String ENTITY_CATEGORY = "category";
	private static final String REFERENCE_CATEGORIES = "categories";
	private static final String REFERENCE_PRODUCTS = "products";
	private static final String ATTRIBUTE_NAME = "name";
	private static final String ATTRIBUTE_ORDER = "orderInCategory";
	private static final String ATTRIBUTE_LABEL = "label";
	private static final String COMPOUND_LABEL_AND_ORDER = "labelAndOrder";
	/**
	 * The product owning every partition of the gate.
	 */
	private static final int OWNER = 1;

	@Nullable private TestPaths paths;
	@Nullable private Evita evita;
	@Nonnull private final TransitionHistory history = new TransitionHistory();

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("VirtualPartitionOpCountGateTest");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		this.evita.createCatalogWithVirtualReferencePartitions(CATALOG, true);
		this.evita.defineCatalog(CATALOG).updateViaNewSession(this.evita);
		final VirtualReferencePartitionMaintenance maintenance = maintenance();
		maintenance.setProbe(this.history);
		maintenance.setWrittenVirtually(true);
	}

	@AfterEach
	void tearDown() {
		if (this.evita != null && this.evita.isActive()) {
			this.evita.close();
		}
		if (this.paths != null) {
			cleanupTestPaths(this.paths);
		}
	}

	/**
	 * Returns the holder of the virtual reference partitions of the catalog.
	 *
	 * @return the holder
	 */
	@Nonnull
	private VirtualReferencePartitionMaintenance maintenance() {
		return ((Catalog) evita().getCatalogInstance(CATALOG).orElseThrow()).getVirtualReferencePartitionMaintenance();
	}

	/**
	 * Returns the engine of the test.
	 *
	 * @return the engine
	 */
	@Nonnull
	private Evita evita() {
		return Objects.requireNonNull(this.evita);
	}

	/**
	 * Runs the work in a session and returns the transitions the root mutations of the session passed while they ran -
	 * not those a live catalog's commit passes once more when it replays the session's mutations.
	 *
	 * @param work the work
	 * @return the transitions per stage
	 */
	@Nonnull
	private Map<TransitionStage, Integer> countTransitions(@Nonnull Consumer<EvitaSessionContract> work) {
		final Map<TransitionStage, Integer> counts = new EnumMap<>(TransitionStage.class);
		evita().updateCatalog(
			CATALOG,
			session -> {
				work.accept(session);
				for (final Transition transition : this.history.getSession()) {
					counts.merge(transition.stage(), 1, Integer::sum);
				}
			}
		);
		this.history.sessionClosed(false, false);
		return counts;
	}

	/**
	 * Defines the schema and writes the categories and the owner referencing `partitions` of them, each category
	 * referenced by the owner alone, and brings the catalog to the given state.
	 *
	 * @param state      the catalog state to end in
	 * @param partitions how many single-owner partitions the owner has
	 * @param reflected  TRUE to reflect the reference on the categories
	 */
	private void prepare(@Nonnull CatalogState state, int partitions, boolean reflected) {
		evita().updateCatalog(
			CATALOG,
			session -> {
				session.defineEntitySchema(ENTITY_CATEGORY).updateVia(session);
				session.defineEntitySchema(ENTITY_PRODUCT)
					.withLocale(Locale.ENGLISH, Locale.GERMAN)
					.withAttribute(ATTRIBUTE_NAME, String.class, whichIs -> whichIs.localized().nullable())
					.withReferenceToEntity(
						REFERENCE_CATEGORIES, ENTITY_CATEGORY, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs
							.indexedForFilteringInScope(Scope.LIVE, Scope.ARCHIVED)
							.withAttribute(
								ATTRIBUTE_ORDER, Integer.class,
								thatIs -> thatIs.sortableInScope(Scope.LIVE, Scope.ARCHIVED).nullable()
							)
							.withAttribute(
								ATTRIBUTE_LABEL, String.class,
								thatIs -> thatIs.localized().sortableInScope(Scope.LIVE, Scope.ARCHIVED).nullable()
							)
							.withSortableAttributeCompound(
								COMPOUND_LABEL_AND_ORDER,
								new AttributeElement[]{
									new AttributeElement(
										ATTRIBUTE_LABEL, OrderDirection.ASC, OrderBehaviour.NULLS_LAST
									),
									new AttributeElement(
										ATTRIBUTE_ORDER, OrderDirection.ASC, OrderBehaviour.NULLS_LAST
									)
								},
								thatIs -> thatIs.indexedInScope(Scope.LIVE, Scope.ARCHIVED)
							)
					)
					.updateVia(session);
				if (reflected) {
					session.defineEntitySchema(ENTITY_CATEGORY)
						.withReflectedReferenceToEntity(
							REFERENCE_PRODUCTS, ENTITY_PRODUCT, REFERENCE_CATEGORIES,
							whichIs -> whichIs
								.withCardinality(Cardinality.ZERO_OR_MORE)
								.withoutAttributesInherited()
								.indexedForFilteringInScope(Scope.LIVE, Scope.ARCHIVED)
						)
						.updateVia(session);
				}
				for (int pk = 1; pk <= partitions; pk++) {
					session.upsertEntity(session.createNewEntity(ENTITY_CATEGORY, pk));
				}
				final EntityBuilder owner = session.createNewEntity(ENTITY_PRODUCT, OWNER)
					.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "Owner");
				for (int pk = 1; pk <= partitions; pk++) {
					final int category = pk;
					owner.setReference(
						REFERENCE_CATEGORIES, category,
						whichIs -> whichIs.setAttribute(ATTRIBUTE_ORDER, category)
							.setAttribute(ATTRIBUTE_LABEL, Locale.ENGLISH, "Label " + category)
					);
				}
				owner.upsertVia(session);
				if (state == CatalogState.ALIVE) {
					session.goLiveAndClose();
				}
			}
		);
		this.history.sessionClosed(state == CatalogState.WARMING_UP, false);
	}

	/**
	 * Returns the collection of the entity type in the committed catalog.
	 *
	 * @param entityType the entity type
	 * @return the collection
	 */
	@Nonnull
	private EntityCollection collectionOf(@Nonnull String entityType) {
		return (EntityCollection) evita().getCatalogInstance(CATALOG)
			.orElseThrow()
			.getCollectionForEntity(entityType)
			.orElseThrow();
	}

	/**
	 * Counts the rows of the registry of the category reference in the scope of the committed catalog, and asserts
	 * that no partition the reference advertises there has an index object.
	 *
	 * @param scope the scope
	 * @return the number of owner rows
	 */
	private int rowsWithoutObjects(@Nonnull Scope scope) {
		final EntityCollection products = collectionOf(ENTITY_PRODUCT);
		final EntityIndex typeIndex = products.getIndexByKeyIfExists(
			new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, scope, REFERENCE_CATEGORIES)
		);
		if (!(typeIndex instanceof final ReferencedTypeEntityIndex referencedTypeIndex)) {
			return 0;
		}
		final int[] objects = {0};
		referencedTypeIndex.forEachReferenceIndexPrimaryKey(
			partitionPrimaryKey -> {
				if (products.getIndexByPrimaryKeyIfExists(partitionPrimaryKey) != null) {
					objects[0]++;
				}
			}
		);
		assertEquals(
			0, objects[0], "no partition of `" + REFERENCE_CATEGORIES + "` may have an index object in " + scope
		);
		final VirtualReferencePartitionIndex registry = referencedTypeIndex.getVirtualReferencePartitionIndex();
		final int[] rows = {0};
		registry.forEachEntry(
			VirtualReferencePartitionSlot.PARTITION_OWNER, (partition, payload) -> rows[0]++
		);
		return rows[0];
	}

	/**
	 * The singleton half of the gate: an owner of `k` single-owner partitions.
	 */
	@Nested
	@DisplayName("an owner of single-owner partitions")
	class SingletonPartitions {
		/**
		 * How many single-owner partitions the owner has.
		 */
		private static final int K = 300;

		@ParameterizedTest(name = "{0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("writes each row once on a locale change, a scope move and its removal")
		void shouldWriteEachRowOnceOnALocaleChangeAScopeMoveAndARemoval(CatalogState state) {
			prepare(state, K, false);
			assertEquals(K, rowsWithoutObjects(Scope.LIVE), "every partition of the owner is a row");
			final long fetchesBefore = maintenance().getForeignReferencesFetchCount();

			// a locale gained writes the localized compound of the new locale into every row, once
			final Map<TransitionStage, Integer> localeChange = countTransitions(
				session -> session.getEntity(ENTITY_PRODUCT, OWNER, entityFetchAllContent())
					.orElseThrow()
					.openForWrite()
					.setAttribute(ATTRIBUTE_NAME, Locale.GERMAN, "Besitzer")
					.upsertVia(session)
			);
			assertEquals(Map.of(TransitionStage.MIRRORED, K), localeChange, "the locale change");
			assertEquals(K, rowsWithoutObjects(Scope.LIVE));

			// the scope move vacates every live row, enters every archived one and writes its values into it once
			final Map<TransitionStage, Integer> scopeMove = countTransitions(
				session -> session.archiveEntity(ENTITY_PRODUCT, OWNER)
			);
			assertEquals(K, scopeMove.get(TransitionStage.VACATED), "the rows vacated by the scope move");
			assertEquals(K, scopeMove.get(TransitionStage.ENTERED), "the rows entered by the scope move");
			// the order, the English label and the compound of both locales - four values per row
			assertEquals(4 * K, scopeMove.get(TransitionStage.MIRRORED), "the values the scope move wrote");
			assertEquals(3, scopeMove.size(), "the scope move passed other transitions: " + scopeMove);
			assertEquals(0, rowsWithoutObjects(Scope.LIVE));
			assertEquals(K, rowsWithoutObjects(Scope.ARCHIVED));

			// the removal drops every row with its values at once
			final Map<TransitionStage, Integer> removal = countTransitions(
				session -> session.deleteEntity(ENTITY_PRODUCT, OWNER)
			);
			assertEquals(Map.of(TransitionStage.VACATED, K), removal, "the removal");
			assertEquals(0, rowsWithoutObjects(Scope.ARCHIVED));

			assertEquals(
				fetchesBefore, maintenance().getForeignReferencesFetchCount(),
				"no partition of the owner may have been written from the references of another entity"
			);
		}
	}

	@Test
	@DisplayName("the fetch of the references of another entity is counted")
	void shouldCountTheFetchOfTheReferencesOfAnotherEntity() {
		prepare(CatalogState.WARMING_UP, 1, true);
		final long fetchesBefore = maintenance().getForeignReferencesFetchCount();

		// a category created after a product referenced it reflects the reference, reading the product's references
		evita().updateCatalog(
			CATALOG,
			session -> {
				session.upsertEntity(
					session.createNewEntity(ENTITY_PRODUCT, OWNER + 1).setReference(REFERENCE_CATEGORIES, 2)
				);
				session.upsertEntity(session.createNewEntity(ENTITY_CATEGORY, 2));
			}
		);

		assertTrue(
			maintenance().getForeignReferencesFetchCount() > fetchesBefore,
			"the reflection must have read the references of the product"
		);
	}
}
