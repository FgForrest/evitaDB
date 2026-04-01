/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.api.functional.indexing;

import io.evitadb.api.CatalogContract;
import io.evitadb.api.CatalogState;
import io.evitadb.api.EntityCollectionContract;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.core.Evita;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.dataType.Scope;
import io.evitadb.export.file.configuration.FileSystemExportOptions;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.ReducedGroupEntityIndex;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.function.Consumer;

import static io.evitadb.api.query.QueryConstraints.entityFetchAllContent;
import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration tests for histogram de/indexing when entities change scope (LIVE/ARCHIVED).
 * Verifies that histogram FilterIndex entries correctly move between scopes on archive/restore,
 * and that cross-entity triggers respect scope boundaries.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Histogram scope change operations")
class HistogramScopeChangeTest implements EvitaTestSupport, IndexingTestSupport {

	private static final String DIR_HISTOGRAM_SCOPE_TEST = "histogramScopeChangeTest";
	private static final String DIR_HISTOGRAM_SCOPE_TEST_EXPORT = "histogramScopeChangeTest_export";

	private static final String ENTITY_PRODUCT = "product";
	private static final String ENTITY_PARAMETER_VALUE = "parameterValue";
	private static final String ENTITY_PARAMETER = "parameter";

	private static final String REF_PARAM_UNGROUPED = "paramUngrouped";
	private static final String REF_PARAM_CONDITIONAL = "paramConditional";
	private static final String REF_PARAM_GROUPED = "paramGrouped";

	private static final String ATTR_BASIC_UNIT_VALUE = "basicUnitValue";
	private static final String ATTR_STATUS = "status";
	private static final String ATTR_INPUT_WIDGET_TYPE = "inputWidgetType";
	private static final String ATTR_IS_ACTIVE = "isActive";

	private static final String HISTOGRAM_VALUE = "valueHistogram";
	private static final String HISTOGRAM_CONDITIONAL = "conditionalHistogram";
	private static final String HISTOGRAM_GROUPED = "groupedHistogram";

	private Evita evita;

	@BeforeEach
	void setUp() {
		cleanTestSubDirectoryWithRethrow(DIR_HISTOGRAM_SCOPE_TEST);
		cleanTestSubDirectoryWithRethrow(DIR_HISTOGRAM_SCOPE_TEST_EXPORT);
		this.evita = new Evita(
			getEvitaConfiguration()
		);
		this.evita.defineCatalog(TEST_CATALOG);
	}

	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanTestSubDirectoryWithRethrow(DIR_HISTOGRAM_SCOPE_TEST);
		cleanTestSubDirectoryWithRethrow(DIR_HISTOGRAM_SCOPE_TEST_EXPORT);
	}

	/**
	 * Builds the standard Evita configuration pointing to the test directories.
	 *
	 * @return the Evita configuration for the test
	 */
	@Nonnull
	private EvitaConfiguration getEvitaConfiguration() {
		return EvitaConfiguration.builder()
			.server(
				ServerOptions.builder()
					.closeSessionsAfterSecondsOfInactivity(-1)
					.build()
			)
			.storage(
				StorageOptions.builder()
					.storageDirectory(getTestDirectory().resolve(DIR_HISTOGRAM_SCOPE_TEST))
					.build()
			)
			.export(
				FileSystemExportOptions.builder()
					.directory(getTestDirectory().resolve(DIR_HISTOGRAM_SCOPE_TEST_EXPORT))
					.build()
			)
			.build();
	}

	/**
	 * Defines all entity types and reference schemas needed for histogram scope change tests.
	 *
	 * @param session the active evitaDB session
	 */
	private static void defineHistogramScopeSchema(@Nonnull EvitaSessionContract session) {
		// 1. parameter entity - group entity
		session.defineEntitySchema(ENTITY_PARAMETER)
			.withAttribute(
				ATTR_INPUT_WIDGET_TYPE, String.class,
				whichIs -> whichIs.filterableInScope(Scope.LIVE, Scope.ARCHIVED).nullable()
			)
			.updateVia(session);

		// 2. parameterValue entity - referenced entity
		session.defineEntitySchema(ENTITY_PARAMETER_VALUE)
			.withAttribute(
				ATTR_BASIC_UNIT_VALUE, BigDecimal.class,
				whichIs -> whichIs.filterableInScope(Scope.LIVE, Scope.ARCHIVED).nullable()
			)
			.withAttribute(
				ATTR_STATUS, String.class,
				whichIs -> whichIs.filterableInScope(Scope.LIVE, Scope.ARCHIVED).nullable()
			)
			.updateVia(session);

		// 3. product entity
		session.defineEntitySchema(ENTITY_PRODUCT)
			.withAttribute(
				ATTR_IS_ACTIVE, Boolean.class,
				whichIs -> whichIs.filterableInScope(Scope.LIVE, Scope.ARCHIVED).nullable()
			)
			// ref 1: ungrouped unconditional
			.withReferenceToEntity(
				REF_PARAM_UNGROUPED, ENTITY_PARAMETER_VALUE, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFilteringAndPartitioningInScope(Scope.LIVE, Scope.ARCHIVED)
					.bucketedInScope(
						Scope.LIVE, HISTOGRAM_VALUE,
						ExpressionFactory.parse("$reference.referencedEntity?.attributes['basicUnitValue']")
					)
					.bucketedInScope(
						Scope.ARCHIVED, HISTOGRAM_VALUE,
						ExpressionFactory.parse("$reference.referencedEntity?.attributes['basicUnitValue']")
					)
			)
			// ref 2: ungrouped conditional (cross-entity condition on referenced entity status)
			.withReferenceToEntity(
				REF_PARAM_CONDITIONAL, ENTITY_PARAMETER_VALUE, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFilteringAndPartitioningInScope(Scope.LIVE, Scope.ARCHIVED)
					.bucketedInScope(
						Scope.LIVE, HISTOGRAM_CONDITIONAL,
						ExpressionFactory.parse("$reference.referencedEntity?.attributes['basicUnitValue']")
					)
					.bucketedInScope(
						Scope.ARCHIVED, HISTOGRAM_CONDITIONAL,
						ExpressionFactory.parse("$reference.referencedEntity?.attributes['basicUnitValue']")
					)
					.bucketedPartiallyInScope(
						Scope.LIVE,
						ExpressionFactory.parse("($reference.referencedEntity.attributes['status'] ?? '') == 'ACTIVE'")
					)
					.bucketedPartiallyInScope(
						Scope.ARCHIVED,
						ExpressionFactory.parse("($reference.referencedEntity.attributes['status'] ?? '') == 'ACTIVE'")
					)
			)
			// ref 3: grouped conditional
			.withReferenceToEntity(
				REF_PARAM_GROUPED, ENTITY_PARAMETER_VALUE, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFilteringAndPartitioningInScope(Scope.LIVE, Scope.ARCHIVED)
					.indexedWithComponentsInScope(Scope.LIVE, ReferenceIndexedComponents.values())
					.indexedWithComponentsInScope(Scope.ARCHIVED, ReferenceIndexedComponents.values())
					.withGroupTypeRelatedToEntity(ENTITY_PARAMETER)
					.bucketedInScope(
						Scope.LIVE, HISTOGRAM_GROUPED,
						ExpressionFactory.parse("$reference.referencedEntity?.attributes['basicUnitValue']")
					)
					.bucketedInScope(
						Scope.ARCHIVED, HISTOGRAM_GROUPED,
						ExpressionFactory.parse("$reference.referencedEntity?.attributes['basicUnitValue']")
					)
					.bucketedPartiallyInScope(
						Scope.LIVE,
						ExpressionFactory.parse("($reference.groupEntity?.attributes['inputWidgetType'] ?? '') == 'INTERVAL'")
					)
					.bucketedPartiallyInScope(
						Scope.ARCHIVED,
						ExpressionFactory.parse("($reference.groupEntity?.attributes['inputWidgetType'] ?? '') == 'INTERVAL'")
					)
			)
			.updateVia(session);
	}

	/**
	 * Returns the Product entity collection from the current catalog.
	 *
	 * @return the Product collection
	 */
	@Nonnull
	private EntityCollectionContract getProductCollection() {
		final CatalogContract catalog = this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
		return catalog.getCollectionForEntity(ENTITY_PRODUCT).orElseThrow();
	}

	/**
	 * Executes a test in the specified catalog state. The fixture setup always runs in WARMING_UP
	 * (bulk mode), then the catalog optionally transitions to ALIVE before the test logic executes.
	 *
	 * @param targetState  the catalog state in which the test logic should execute
	 * @param fixtureSetup schema definition and initial entity creation (always runs in WARMING_UP)
	 * @param testLogic    assertions and mutations that exercise the scenario under test
	 */
	private void withCatalogInState(
		@Nonnull CatalogState targetState,
		@Nonnull Consumer<EvitaSessionContract> fixtureSetup,
		@Nonnull Consumer<EvitaSessionContract> testLogic
	) {
		if (targetState == CatalogState.WARMING_UP) {
			this.evita.updateCatalog(TEST_CATALOG, session -> {
				fixtureSetup.accept(session);
				testLogic.accept(session);
			});
		} else {
			this.evita.updateCatalog(TEST_CATALOG, session -> {
				fixtureSetup.accept(session);
				session.goLiveAndClose();
			});
			this.evita.updateCatalog(TEST_CATALOG, testLogic);
		}
	}

	/**
	 * Asserts that the owner entity is present in the ungrouped histogram FilterIndex bucket
	 * for the given value in the specified scope.
	 *
	 * @param collection    the entity collection to inspect
	 * @param scope         the scope to check
	 * @param referenceName the reference schema name
	 * @param histogramName the histogram definition name
	 * @param value         the expected histogram bucket value
	 * @param ownerPK       the primary key of the owner entity (Product PK)
	 */
	private static void assertUngroupedHistogramBucketContainsInScope(
		@Nonnull EntityCollectionContract collection,
		@Nonnull Scope scope,
		@Nonnull String referenceName,
		@Nonnull String histogramName,
		@Nonnull Serializable value,
		int ownerPK
	) {
		final EntityIndex entityIndex = IndexingTestSupport.getReferencedEntityTypeIndex(
			collection, scope, referenceName
		);
		assertNotNull(
			entityIndex,
			"ReferencedTypeEntityIndex for ref '" + referenceName + "' in scope " + scope + " must exist"
		);
		assertInstanceOf(
			ReferencedTypeEntityIndex.class, entityIndex,
			"Expected ReferencedTypeEntityIndex but got " + entityIndex.getClass().getSimpleName()
		);
		final ReferencedTypeEntityIndex typeIndex = (ReferencedTypeEntityIndex) entityIndex;
		final FilterIndex filterIndex = typeIndex.getHistogramFilterIndex(histogramName, null);
		assertNotNull(
			filterIndex,
			"Histogram FilterIndex '" + histogramName + "' in scope " + scope + " must exist"
		);
		assertTrue(
			filterIndex.getRecordsEqualTo(EvitaDataTypes.toSupportedType(value)).contains(ownerPK),
			"Owner PK " + ownerPK + " should be in histogram '" + histogramName
				+ "' bucket " + value + " in scope " + scope
		);
	}

	/**
	 * Asserts that the owner entity is NOT present in any ungrouped histogram FilterIndex bucket
	 * in the specified scope.
	 *
	 * @param collection    the entity collection to inspect
	 * @param scope         the scope to check
	 * @param referenceName the reference schema name
	 * @param histogramName the histogram definition name
	 * @param ownerPK       the primary key of the owner entity (Product PK)
	 */
	private static void assertUngroupedHistogramNotIndexedInScope(
		@Nonnull EntityCollectionContract collection,
		@Nonnull Scope scope,
		@Nonnull String referenceName,
		@Nonnull String histogramName,
		int ownerPK
	) {
		final EntityIndex entityIndex = IndexingTestSupport.getReferencedEntityTypeIndex(
			collection, scope, referenceName
		);
		if (entityIndex == null) {
			return; // no type index = not indexed
		}
		assertInstanceOf(
			ReferencedTypeEntityIndex.class, entityIndex,
			"Expected ReferencedTypeEntityIndex but got " + entityIndex.getClass().getSimpleName()
		);
		final ReferencedTypeEntityIndex typeIndex = (ReferencedTypeEntityIndex) entityIndex;
		final FilterIndex filterIndex = typeIndex.getHistogramFilterIndex(histogramName, null);
		if (filterIndex == null) {
			return; // no histogram FilterIndex = not indexed
		}
		assertFalse(
			filterIndex.getAllRecords().contains(ownerPK),
			"Owner PK " + ownerPK + " should NOT be in histogram '" + histogramName
				+ "' in scope " + scope
		);
	}

	/**
	 * Asserts that the owner entity is present in the grouped histogram FilterIndex bucket
	 * for the given value in the specified scope.
	 *
	 * @param collection         the entity collection to inspect
	 * @param scope              the scope to check
	 * @param referenceName      the reference schema name
	 * @param referencedEntityPK the referenced entity primary key (group index discriminator)
	 * @param histogramName      the histogram definition name
	 * @param value              the expected histogram bucket value
	 * @param ownerPK            the primary key of the owner entity (Product PK)
	 */
	private static void assertHistogramBucketContainsInScope(
		@Nonnull EntityCollectionContract collection,
		@Nonnull Scope scope,
		@Nonnull String referenceName,
		int referencedEntityPK,
		@Nonnull String histogramName,
		@Nonnull Serializable value,
		int ownerPK
	) {
		final EntityIndex entityIndex = IndexingTestSupport.getReferencedGroupEntityIndex(
			collection, scope, referenceName, referencedEntityPK
		);
		assertNotNull(
			entityIndex,
			"ReducedGroupEntityIndex for ref '" + referenceName
				+ "' referencedEntityPK " + referencedEntityPK + " in scope " + scope + " must exist"
		);
		assertInstanceOf(
			ReducedGroupEntityIndex.class, entityIndex,
			"Expected ReducedGroupEntityIndex but got " + entityIndex.getClass().getSimpleName()
		);
		final ReducedGroupEntityIndex groupIndex = (ReducedGroupEntityIndex) entityIndex;
		final FilterIndex filterIndex = groupIndex.getHistogramFilterIndex(histogramName, null);
		assertNotNull(
			filterIndex,
			"Histogram FilterIndex '" + histogramName
				+ "' in referencedEntityPK " + referencedEntityPK + " scope " + scope + " must exist"
		);
		assertTrue(
			filterIndex.getRecordsEqualTo(EvitaDataTypes.toSupportedType(value)).contains(ownerPK),
			"Owner PK " + ownerPK + " should be in histogram '" + histogramName
				+ "' bucket " + value + " referencedEntityPK " + referencedEntityPK + " scope " + scope
		);
	}

	/**
	 * Asserts that the owner entity is NOT present in any grouped histogram FilterIndex bucket
	 * in the specified scope.
	 *
	 * @param collection         the entity collection to inspect
	 * @param scope              the scope to check
	 * @param referenceName      the reference schema name
	 * @param referencedEntityPK the referenced entity primary key (group index discriminator)
	 * @param histogramName      the histogram definition name
	 * @param ownerPK            the primary key of the owner entity (Product PK)
	 */
	private static void assertHistogramNotIndexedInScope(
		@Nonnull EntityCollectionContract collection,
		@Nonnull Scope scope,
		@Nonnull String referenceName,
		int referencedEntityPK,
		@Nonnull String histogramName,
		int ownerPK
	) {
		final EntityIndex entityIndex = IndexingTestSupport.getReferencedGroupEntityIndex(
			collection, scope, referenceName, referencedEntityPK
		);
		if (entityIndex == null) {
			return; // no group index = not indexed
		}
		assertInstanceOf(
			ReducedGroupEntityIndex.class, entityIndex,
			"Expected ReducedGroupEntityIndex but got " + entityIndex.getClass().getSimpleName()
		);
		final ReducedGroupEntityIndex groupIndex = (ReducedGroupEntityIndex) entityIndex;
		final FilterIndex filterIndex = groupIndex.getHistogramFilterIndex(histogramName, null);
		if (filterIndex == null) {
			return; // no histogram FilterIndex = not indexed
		}
		assertFalse(
			filterIndex.getAllRecords().contains(ownerPK),
			"Owner PK " + ownerPK + " should NOT be in histogram '" + histogramName
				+ "' referencedEntityPK " + referencedEntityPK + " scope " + scope
		);
	}
	/**
	 * Tests verifying that ungrouped unconditional histogram data correctly moves between
	 * LIVE and ARCHIVED scopes when the entire entity chain is archived and restored.
	 */
	@Nested
	@DisplayName("Ungrouped archive and restore")
	class UngroupedArchiveRestoreTest {

		/**
		 * Verifies that archiving both the referenced entity and the product moves the histogram
		 * from LIVE to ARCHIVED, and restoring both brings it back.
		 */
		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should move ungrouped histogram between scopes when full chain is archived")
		void shouldMoveUngroupedHistogramBetweenScopesOnArchiveAndRestore(@Nonnull CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineHistogramScopeSchema(session);

					session.createNewEntity(ENTITY_PARAMETER_VALUE, 1)
						.setAttribute(ATTR_BASIC_UNIT_VALUE, new BigDecimal("50"))
						.upsertVia(session);

					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setReference(REF_PARAM_UNGROUPED, 1)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();

					// initially: histogram in LIVE scope
					assertUngroupedHistogramBucketContainsInScope(
						productCollection, Scope.LIVE, REF_PARAM_UNGROUPED,
						HISTOGRAM_VALUE, new BigDecimal("50"), 1
					);

					// archive PV#1 — cross-entity trigger removes Product#1's LIVE histogram
					session.archiveEntity(ENTITY_PARAMETER_VALUE, 1);
					assertUngroupedHistogramNotIndexedInScope(
						productCollection, Scope.LIVE, REF_PARAM_UNGROUPED,
						HISTOGRAM_VALUE, 1
					);

					// archive Product#1 — both in ARCHIVED, histogram appears in ARCHIVED
					session.archiveEntity(ENTITY_PRODUCT, 1);
					assertUngroupedHistogramBucketContainsInScope(
						productCollection, Scope.ARCHIVED, REF_PARAM_UNGROUPED,
						HISTOGRAM_VALUE, new BigDecimal("50"), 1
					);

					// restore Product#1 — Product in LIVE, PV still ARCHIVED — no LIVE histogram
					session.restoreEntity(ENTITY_PRODUCT, 1);
					assertUngroupedHistogramNotIndexedInScope(
						productCollection, Scope.LIVE, REF_PARAM_UNGROUPED,
						HISTOGRAM_VALUE, 1
					);

					// restore PV#1 — full chain in LIVE again
					session.restoreEntity(ENTITY_PARAMETER_VALUE, 1);
					assertUngroupedHistogramBucketContainsInScope(
						productCollection, Scope.LIVE, REF_PARAM_UNGROUPED,
						HISTOGRAM_VALUE, new BigDecimal("50"), 1
					);
				}
			);
		}
	}

	/**
	 * Tests verifying that grouped conditional histogram data correctly moves between
	 * LIVE and ARCHIVED scopes when the entire entity chain is archived and restored.
	 */
	@Nested
	@DisplayName("Grouped archive and restore")
	class GroupedArchiveRestoreTest {

		/**
		 * Verifies that archiving the group entity, referenced entity, and the product moves
		 * the grouped conditional histogram from LIVE to ARCHIVED, and restoring brings it back.
		 */
		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should move grouped conditional histogram between scopes when full chain is archived")
		void shouldMoveGroupedConditionalHistogramBetweenScopesOnArchiveAndRestore(@Nonnull CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineHistogramScopeSchema(session);

					session.createNewEntity(ENTITY_PARAMETER, 10)
						.setAttribute(ATTR_INPUT_WIDGET_TYPE, "INTERVAL")
						.upsertVia(session);

					session.createNewEntity(ENTITY_PARAMETER_VALUE, 1)
						.setAttribute(ATTR_BASIC_UNIT_VALUE, new BigDecimal("50"))
						.upsertVia(session);

					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setReference(
							REF_PARAM_GROUPED, 1,
							whichIs -> whichIs.setGroup(ENTITY_PARAMETER, 10)
						)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();

					// initially: histogram in LIVE scope
					assertHistogramBucketContainsInScope(
						productCollection, Scope.LIVE, REF_PARAM_GROUPED,
						1, HISTOGRAM_GROUPED, new BigDecimal("50"), 1
					);

					// archive PV#1 — cross-entity trigger removes histogram from LIVE
					session.archiveEntity(ENTITY_PARAMETER_VALUE, 1);
					assertHistogramNotIndexedInScope(
						productCollection, Scope.LIVE, REF_PARAM_GROUPED,
						1, HISTOGRAM_GROUPED, 1
					);

					// archive Parameter#10 and Product#1 — full chain in ARCHIVED
					session.archiveEntity(ENTITY_PARAMETER, 10);
					session.archiveEntity(ENTITY_PRODUCT, 1);
					assertHistogramBucketContainsInScope(
						productCollection, Scope.ARCHIVED, REF_PARAM_GROUPED,
						1, HISTOGRAM_GROUPED, new BigDecimal("50"), 1
					);

					// restore all — histogram back in LIVE
					session.restoreEntity(ENTITY_PRODUCT, 1);
					session.restoreEntity(ENTITY_PARAMETER, 10);
					session.restoreEntity(ENTITY_PARAMETER_VALUE, 1);
					assertHistogramBucketContainsInScope(
						productCollection, Scope.LIVE, REF_PARAM_GROUPED,
						1, HISTOGRAM_GROUPED, new BigDecimal("50"), 1
					);
				}
			);
		}
	}

	/**
	 * Tests verifying that histogram is NOT indexed in ARCHIVED scope when the
	 * referenced entity remains in LIVE scope (cross-entity scope mismatch).
	 */
	@Nested
	@DisplayName("Cross-entity scope mismatch")
	class CrossEntityScopeMismatchTest {

		/**
		 * Verifies that archiving a product does not index its histogram in the ARCHIVED
		 * scope when the referenced entity (parameterValue) stays in LIVE scope.
		 */
		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should not index histogram in ARCHIVED scope when referenced entity is in LIVE scope")
		void shouldNotIndexHistogramInArchivedScopeWhenReferencedEntityIsInLiveScope(@Nonnull CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineHistogramScopeSchema(session);

					session.createNewEntity(ENTITY_PARAMETER_VALUE, 1)
						.setAttribute(ATTR_STATUS, "ACTIVE")
						.setAttribute(ATTR_BASIC_UNIT_VALUE, new BigDecimal("50"))
						.upsertVia(session);

					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setReference(REF_PARAM_CONDITIONAL, 1)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();

					// initially: conditional histogram in LIVE scope
					assertUngroupedHistogramBucketContainsInScope(
						productCollection, Scope.LIVE, REF_PARAM_CONDITIONAL,
						HISTOGRAM_CONDITIONAL, new BigDecimal("50"), 1
					);

					// archive Product#1 — PV#1 stays LIVE, so ARCHIVED chain is broken
					session.archiveEntity(ENTITY_PRODUCT, 1);

					// NOT in ARCHIVED — PV#1 stays LIVE so chain is broken
					assertUngroupedHistogramNotIndexedInScope(
						productCollection, Scope.ARCHIVED, REF_PARAM_CONDITIONAL,
						HISTOGRAM_CONDITIONAL, 1
					);
					// NOT in LIVE — product no longer in LIVE
					assertUngroupedHistogramNotIndexedInScope(
						productCollection, Scope.LIVE, REF_PARAM_CONDITIONAL,
						HISTOGRAM_CONDITIONAL, 1
					);
				}
			);
		}
	}

	/**
	 * Tests verifying that conditional histogram IS indexed in ARCHIVED scope when the entire
	 * entity chain (product + referenced entity) is archived.
	 */
	@Nested
	@DisplayName("Cross-entity scope match")
	class CrossEntityScopeMatchTest {

		/**
		 * Verifies that conditional histogram appears in ARCHIVED scope only when both the product
		 * and the referenced entity are in the same scope.
		 */
		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should index histogram in ARCHIVED scope when entire chain is archived")
		void shouldIndexHistogramInArchivedScopeWhenEntireChainIsArchived(@Nonnull CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineHistogramScopeSchema(session);

					session.createNewEntity(ENTITY_PARAMETER_VALUE, 1)
						.setAttribute(ATTR_STATUS, "ACTIVE")
						.setAttribute(ATTR_BASIC_UNIT_VALUE, new BigDecimal("50"))
						.upsertVia(session);

					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setReference(REF_PARAM_CONDITIONAL, 1)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();

					// initially: histogram in LIVE
					assertUngroupedHistogramBucketContainsInScope(
						productCollection, Scope.LIVE, REF_PARAM_CONDITIONAL,
						HISTOGRAM_CONDITIONAL, new BigDecimal("50"), 1
					);

					// archive PV#1 — product's LIVE histogram should be removed
					session.archiveEntity(ENTITY_PARAMETER_VALUE, 1);
					assertUngroupedHistogramNotIndexedInScope(
						productCollection, Scope.LIVE, REF_PARAM_CONDITIONAL,
						HISTOGRAM_CONDITIONAL, 1
					);

					// archive Product#1 — both in ARCHIVED now
					session.archiveEntity(ENTITY_PRODUCT, 1);
					assertUngroupedHistogramBucketContainsInScope(
						productCollection, Scope.ARCHIVED, REF_PARAM_CONDITIONAL,
						HISTOGRAM_CONDITIONAL, new BigDecimal("50"), 1
					);

					// restore Product#1 — back to LIVE, but PV still ARCHIVED
					session.restoreEntity(ENTITY_PRODUCT, 1);
					assertUngroupedHistogramNotIndexedInScope(
						productCollection, Scope.LIVE, REF_PARAM_CONDITIONAL,
						HISTOGRAM_CONDITIONAL, 1
					);

					// restore PV#1 — full chain in LIVE again
					session.restoreEntity(ENTITY_PARAMETER_VALUE, 1);
					assertUngroupedHistogramBucketContainsInScope(
						productCollection, Scope.LIVE, REF_PARAM_CONDITIONAL,
						HISTOGRAM_CONDITIONAL, new BigDecimal("50"), 1
					);
				}
			);
		}
	}

	/**
	 * Tests verifying that changes to a LIVE-scope referenced entity do NOT affect
	 * histogram data of a product that has been archived with a different entity.
	 */
	@Nested
	@DisplayName("Cross-entity trigger scope isolation")
	class CrossEntityTriggerScopeIsolationTest {

		/**
		 * Verifies that updating a LIVE-scope referenced entity does not change the ARCHIVED
		 * histogram of a product that was archived with a different referenced entity.
		 * Uses separate products for each PV reference to avoid the cross-entity re-evaluation
		 * fan-out removing unrelated histogram entries.
		 */
		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should not update ARCHIVED histogram when referenced entity changes in LIVE scope")
		void shouldNotUpdateArchivedHistogramWhenReferencedEntityChangesInLiveScope(@Nonnull CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineHistogramScopeSchema(session);

					session.createNewEntity(ENTITY_PARAMETER_VALUE, 1)
						.setAttribute(ATTR_STATUS, "ACTIVE")
						.setAttribute(ATTR_BASIC_UNIT_VALUE, new BigDecimal("50"))
						.upsertVia(session);

					session.createNewEntity(ENTITY_PARAMETER_VALUE, 2)
						.setAttribute(ATTR_STATUS, "ACTIVE")
						.setAttribute(ATTR_BASIC_UNIT_VALUE, new BigDecimal("50"))
						.upsertVia(session);

					// Product#1 references PV#1 only
					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setReference(REF_PARAM_CONDITIONAL, 1)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();

					// archive PV#1 and Product#1 — product has ARCHIVED histogram from PV#1
					session.archiveEntity(ENTITY_PARAMETER_VALUE, 1);
					session.archiveEntity(ENTITY_PRODUCT, 1);

					assertUngroupedHistogramBucketContainsInScope(
						productCollection, Scope.ARCHIVED, REF_PARAM_CONDITIONAL,
						HISTOGRAM_CONDITIONAL, new BigDecimal("50"), 1
					);

					// update PV#2 (in LIVE scope) — change value from 50 to 75
					session.getEntity(ENTITY_PARAMETER_VALUE, 2, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setAttribute(ATTR_BASIC_UNIT_VALUE, new BigDecimal("75"))
						.upsertVia(session);

					// Product#1 ARCHIVED histogram should be UNCHANGED (still bucket 50)
					assertUngroupedHistogramBucketContainsInScope(
						productCollection, Scope.ARCHIVED, REF_PARAM_CONDITIONAL,
						HISTOGRAM_CONDITIONAL, new BigDecimal("50"), 1
					);
				}
			);
		}
	}

	/**
	 * Tests verifying that archiving one product does not affect the histogram data
	 * of other products referencing the same entity.
	 */
	@Nested
	@DisplayName("Fan-out during archive")
	class FanOutDuringArchiveTest {

		/**
		 * Verifies that archiving the referenced entity and one product clears the LIVE
		 * histograms for all products (scope chain broken), while only the archived product
		 * gets an ARCHIVED histogram.
		 */
		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should archive only the target product histogram while clearing LIVE for all")
		void shouldArchiveOnlyTheTargetProductHistogram(@Nonnull CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineHistogramScopeSchema(session);

					session.createNewEntity(ENTITY_PARAMETER_VALUE, 1)
						.setAttribute(ATTR_BASIC_UNIT_VALUE, new BigDecimal("50"))
						.upsertVia(session);

					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setReference(REF_PARAM_UNGROUPED, 1)
						.upsertVia(session);

					session.createNewEntity(ENTITY_PRODUCT, 2)
						.setReference(REF_PARAM_UNGROUPED, 1)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();

					// both products in LIVE histogram
					assertUngroupedHistogramBucketContainsInScope(
						productCollection, Scope.LIVE, REF_PARAM_UNGROUPED,
						HISTOGRAM_VALUE, new BigDecimal("50"), 1
					);
					assertUngroupedHistogramBucketContainsInScope(
						productCollection, Scope.LIVE, REF_PARAM_UNGROUPED,
						HISTOGRAM_VALUE, new BigDecimal("50"), 2
					);

					// archive PV#1 — LIVE histogram cleared for BOTH products (scope chain broken)
					session.archiveEntity(ENTITY_PARAMETER_VALUE, 1);
					assertUngroupedHistogramNotIndexedInScope(
						productCollection, Scope.LIVE, REF_PARAM_UNGROUPED,
						HISTOGRAM_VALUE, 1
					);
					assertUngroupedHistogramNotIndexedInScope(
						productCollection, Scope.LIVE, REF_PARAM_UNGROUPED,
						HISTOGRAM_VALUE, 2
					);

					// archive Product#1 only — ARCHIVED histogram for Product#1
					session.archiveEntity(ENTITY_PRODUCT, 1);
					assertUngroupedHistogramBucketContainsInScope(
						productCollection, Scope.ARCHIVED, REF_PARAM_UNGROUPED,
						HISTOGRAM_VALUE, new BigDecimal("50"), 1
					);

					// Product#2: still in LIVE but no histogram (PV in ARCHIVED)
					assertUngroupedHistogramNotIndexedInScope(
						productCollection, Scope.LIVE, REF_PARAM_UNGROUPED,
						HISTOGRAM_VALUE, 2
					);
				}
			);
		}
	}
}
