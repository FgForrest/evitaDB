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
import io.evitadb.core.Evita;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.dataType.Scope;
import io.evitadb.export.file.configuration.FileSystemExportOptions;
import io.evitadb.index.EntityIndex;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end integration tests for histogram indexing of array-typed numeric attributes.
 * Verifies that each element of a BigDecimal[] attribute produces a separate histogram bucket entry,
 * and that updates, removals, and cross-entity triggers correctly maintain bucket state.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Array attribute histogram indexing operations")
class ArrayAttributeHistogramTest implements EvitaTestSupport, IndexingTestSupport {

	private static final String DIR_ARRAY_HISTOGRAM_TEST = "arrayAttributeHistogramTest";
	private static final String DIR_ARRAY_HISTOGRAM_TEST_EXPORT = "arrayAttributeHistogramTest_export";

	private static final String ENTITY_PRODUCT = "product";
	private static final String ENTITY_PARAMETER_VALUE = "parameterValue";

	private static final String REF_BY_REF_ARRAY_ATTR = "paramByRefArrayAttr";
	private static final String REF_BY_ENTITY_ARRAY_ATTR = "paramByEntityArrayAttr";

	private static final String ATTR_REF_PRICES = "refPrices";
	private static final String ATTR_PRICES = "prices";
	private static final String ATTR_STATUS = "status";

	private static final String HISTOGRAM_REF_ARRAY = "refArrayHistogram";
	private static final String HISTOGRAM_ENTITY_ARRAY = "entityArrayHistogram";

	private Evita evita;

	/**
	 * Defines entity schemas for array histogram tests: a referenced entity with an array attribute,
	 * and a product entity with references configured for reference-attribute and entity-attribute histograms.
	 *
	 * @param session the active evitaDB session
	 */
	private static void defineArrayHistogramSchema(@Nonnull EvitaSessionContract session) {
		// parameterValue -- referenced entity with array attribute
		session.defineEntitySchema(ENTITY_PARAMETER_VALUE)
			.withAttribute(ATTR_PRICES, BigDecimal[].class, whichIs -> whichIs.filterable().nullable())
			.withAttribute(ATTR_STATUS, String.class, whichIs -> whichIs.filterable().nullable())
			.updateVia(session);

		// product -- owner entity
		session.defineEntitySchema(ENTITY_PRODUCT)
			// ref 1: reference attribute array (unconditional)
			.withReferenceToEntity(
				REF_BY_REF_ARRAY_ATTR, ENTITY_PARAMETER_VALUE, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFilteringAndPartitioning()
					.withAttribute(
						ATTR_REF_PRICES, BigDecimal[].class,
						whichAttr -> whichAttr.filterable().nullable()
					)
					.bucketed(
						HISTOGRAM_REF_ARRAY,
						ExpressionFactory.parse("$reference.attributes['refPrices']")
					)
			)
			// ref 2: referenced entity attribute array (conditional via cross-entity trigger)
			.withReferenceToEntity(
				REF_BY_ENTITY_ARRAY_ATTR, ENTITY_PARAMETER_VALUE, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFilteringAndPartitioning()
					.bucketed(
						HISTOGRAM_ENTITY_ARRAY,
						ExpressionFactory.parse(
							"$reference.referencedEntity?.attributes['prices']"
						)
					)
					.bucketedPartially(
						ExpressionFactory.parse(
							"($reference.referencedEntity.attributes['status'] ?? '') == 'ACTIVE'"
						)
					)
			)
			.updateVia(session);
	}

	/**
	 * Asserts that the owner entity is present in the ungrouped histogram FilterIndex bucket for the given value.
	 *
	 * @param collection    the entity collection to inspect
	 * @param referenceName the reference schema name
	 * @param histogramName the histogram definition name
	 * @param value         the expected histogram bucket value
	 * @param ownerPK       the primary key of the owner entity
	 */
	private static void assertUngroupedHistogramBucketContains(
		@Nonnull EntityCollectionContract collection,
		@Nonnull String referenceName,
		@Nonnull String histogramName,
		@Nonnull Serializable value,
		int ownerPK
	) {
		final EntityIndex entityIndex = IndexingTestSupport.getReferencedEntityTypeIndex(
			collection, Scope.LIVE, referenceName
		);
		assertNotNull(
			entityIndex,
			"ReferencedTypeEntityIndex for ref '" + referenceName + "' must exist"
		);
		assertInstanceOf(
			ReferencedTypeEntityIndex.class, entityIndex,
			"Expected ReferencedTypeEntityIndex but got " + entityIndex.getClass().getSimpleName()
		);
		final ReferencedTypeEntityIndex typeIndex = (ReferencedTypeEntityIndex) entityIndex;
		final FilterIndex filterIndex = typeIndex.getHistogramFilterIndex(histogramName, null);
		assertNotNull(
			filterIndex,
			"Histogram FilterIndex '" + histogramName + "' in ungrouped ref '" + referenceName + "' must exist"
		);
		assertTrue(
			filterIndex.getRecordsEqualTo(EvitaDataTypes.toSupportedType(value)).contains(ownerPK),
			"Owner PK " + ownerPK + " should be in ungrouped histogram '" + histogramName
				+ "' bucket " + value
		);
	}

	/**
	 * Asserts that the owner entity is NOT in the ungrouped histogram FilterIndex bucket for the given value.
	 *
	 * @param collection    the entity collection to inspect
	 * @param referenceName the reference schema name
	 * @param histogramName the histogram definition name
	 * @param value         the histogram bucket value that should NOT contain the owner
	 * @param ownerPK       the primary key of the owner entity
	 */
	private static void assertUngroupedHistogramBucketNotContains(
		@Nonnull EntityCollectionContract collection,
		@Nonnull String referenceName,
		@Nonnull String histogramName,
		@Nonnull Serializable value,
		int ownerPK
	) {
		final EntityIndex entityIndex = IndexingTestSupport.getReferencedEntityTypeIndex(
			collection, Scope.LIVE, referenceName
		);
		if (entityIndex == null) {
			return;
		}
		assertInstanceOf(
			ReferencedTypeEntityIndex.class, entityIndex,
			"Expected ReferencedTypeEntityIndex but got " + entityIndex.getClass().getSimpleName()
		);
		final ReferencedTypeEntityIndex typeIndex = (ReferencedTypeEntityIndex) entityIndex;
		final FilterIndex filterIndex = typeIndex.getHistogramFilterIndex(histogramName, null);
		if (filterIndex == null) {
			return;
		}
		assertFalse(
			filterIndex.getRecordsEqualTo(EvitaDataTypes.toSupportedType(value)).contains(ownerPK),
			"Owner PK " + ownerPK + " should NOT be in histogram '" + histogramName + "' bucket " + value
		);
	}

	/**
	 * Asserts that the owner entity is NOT present in any ungrouped histogram FilterIndex bucket.
	 *
	 * @param collection    the entity collection to inspect
	 * @param referenceName the reference schema name
	 * @param histogramName the histogram definition name
	 * @param ownerPK       the primary key of the owner entity
	 */
	private static void assertUngroupedHistogramNotIndexed(
		@Nonnull EntityCollectionContract collection,
		@Nonnull String referenceName,
		@Nonnull String histogramName,
		int ownerPK
	) {
		final EntityIndex entityIndex = IndexingTestSupport.getReferencedEntityTypeIndex(
			collection, Scope.LIVE, referenceName
		);
		if (entityIndex == null) {
			return;
		}
		assertInstanceOf(
			ReferencedTypeEntityIndex.class, entityIndex,
			"Expected ReferencedTypeEntityIndex but got " + entityIndex.getClass().getSimpleName()
		);
		final ReferencedTypeEntityIndex typeIndex = (ReferencedTypeEntityIndex) entityIndex;
		final FilterIndex filterIndex = typeIndex.getHistogramFilterIndex(histogramName, null);
		if (filterIndex == null) {
			return;
		}
		assertFalse(
			filterIndex.getAllRecords().contains(ownerPK),
			"Owner PK " + ownerPK + " should NOT be in ungrouped histogram '" + histogramName + "'"
		);
	}

	@BeforeEach
	void setUp() {
		cleanTestSubDirectoryWithRethrow(DIR_ARRAY_HISTOGRAM_TEST);
		cleanTestSubDirectoryWithRethrow(DIR_ARRAY_HISTOGRAM_TEST_EXPORT);
		this.evita = new Evita(
			getEvitaConfiguration()
		);
		this.evita.defineCatalog(TEST_CATALOG);
	}

	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanTestSubDirectoryWithRethrow(DIR_ARRAY_HISTOGRAM_TEST);
		cleanTestSubDirectoryWithRethrow(DIR_ARRAY_HISTOGRAM_TEST_EXPORT);
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
					.storageDirectory(getTestDirectory().resolve(DIR_ARRAY_HISTOGRAM_TEST))
					.build()
			)
			.export(
				FileSystemExportOptions.builder()
					.directory(getTestDirectory().resolve(DIR_ARRAY_HISTOGRAM_TEST_EXPORT))
					.build()
			)
			.build();
	}

	/**
	 * Retrieves the product entity collection from the current catalog.
	 *
	 * @return the product entity collection
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
			this.evita.updateCatalog(
				TEST_CATALOG, session -> {
					fixtureSetup.accept(session);
					testLogic.accept(session);
				}
			);
		} else {
			this.evita.updateCatalog(
				TEST_CATALOG, session -> {
					fixtureSetup.accept(session);
					session.goLiveAndClose();
				}
			);
			this.evita.updateCatalog(TEST_CATALOG, testLogic);
		}
	}

	/**
	 * Tests for histogram indexing of BigDecimal[] reference attributes.
	 */
	@Nested
	@DisplayName("Reference attribute array histogram")
	class ReferenceAttributeArrayTest {

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should create separate histogram entries for each reference array element")
		void shouldCreateSeparateHistogramEntriesForEachReferenceArrayElement(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineArrayHistogramSchema(session);

					session.createNewEntity(ENTITY_PARAMETER_VALUE, 1).upsertVia(session);

					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setReference(
							REF_BY_REF_ARRAY_ATTR, 1,
							whichIs -> whichIs.setAttribute(
								ATTR_REF_PRICES,
								new BigDecimal[]{new BigDecimal("10"), new BigDecimal("20"), new BigDecimal("30")}
							)
						)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();

					assertUngroupedHistogramBucketContains(
						productCollection, REF_BY_REF_ARRAY_ATTR,
						HISTOGRAM_REF_ARRAY, new BigDecimal("10"), 1
					);
					assertUngroupedHistogramBucketContains(
						productCollection, REF_BY_REF_ARRAY_ATTR,
						HISTOGRAM_REF_ARRAY, new BigDecimal("20"), 1
					);
					assertUngroupedHistogramBucketContains(
						productCollection, REF_BY_REF_ARRAY_ATTR,
						HISTOGRAM_REF_ARRAY, new BigDecimal("30"), 1
					);
				}
			);
		}

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should update histogram when reference array attribute changes")
		void shouldUpdateHistogramWhenReferenceArrayAttributeChanges(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineArrayHistogramSchema(session);

					session.createNewEntity(ENTITY_PARAMETER_VALUE, 1).upsertVia(session);

					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setReference(
							REF_BY_REF_ARRAY_ATTR, 1,
							whichIs -> whichIs.setAttribute(
								ATTR_REF_PRICES,
								new BigDecimal[]{new BigDecimal("10"), new BigDecimal("20")}
							)
						)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();

					assertUngroupedHistogramBucketContains(
						productCollection, REF_BY_REF_ARRAY_ATTR,
						HISTOGRAM_REF_ARRAY, new BigDecimal("10"), 1
					);
					assertUngroupedHistogramBucketContains(
						productCollection, REF_BY_REF_ARRAY_ATTR,
						HISTOGRAM_REF_ARRAY, new BigDecimal("20"), 1
					);

					// update reference attribute to new array values
					session.getEntity(ENTITY_PRODUCT, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setOrUpdateReference(
							REF_BY_REF_ARRAY_ATTR, 1,
							ref -> true,
							whichIs -> whichIs.setAttribute(
								ATTR_REF_PRICES,
								new BigDecimal[]{new BigDecimal("30"), new BigDecimal("40")}
							)
						)
						.upsertVia(session);

					assertUngroupedHistogramBucketContains(
						productCollection, REF_BY_REF_ARRAY_ATTR,
						HISTOGRAM_REF_ARRAY, new BigDecimal("30"), 1
					);
					assertUngroupedHistogramBucketContains(
						productCollection, REF_BY_REF_ARRAY_ATTR,
						HISTOGRAM_REF_ARRAY, new BigDecimal("40"), 1
					);
					assertUngroupedHistogramBucketNotContains(
						productCollection, REF_BY_REF_ARRAY_ATTR,
						HISTOGRAM_REF_ARRAY, new BigDecimal("10"), 1
					);
					assertUngroupedHistogramBucketNotContains(
						productCollection, REF_BY_REF_ARRAY_ATTR,
						HISTOGRAM_REF_ARRAY, new BigDecimal("20"), 1
					);
				}
			);
		}

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should track cardinality correctly for array attributes across multiple references")
		void shouldTrackCardinalityCorrectlyForArrayAttributes(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineArrayHistogramSchema(session);

					session.createNewEntity(ENTITY_PARAMETER_VALUE, 1).upsertVia(session);
					session.createNewEntity(ENTITY_PARAMETER_VALUE, 2).upsertVia(session);

					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setReference(
							REF_BY_REF_ARRAY_ATTR, 1,
							whichIs -> whichIs.setAttribute(
								ATTR_REF_PRICES,
								new BigDecimal[]{new BigDecimal("10"), new BigDecimal("20")}
							)
						)
						.setReference(
							REF_BY_REF_ARRAY_ATTR, 2,
							whichIs -> whichIs.setAttribute(
								ATTR_REF_PRICES,
								new BigDecimal[]{new BigDecimal("20"), new BigDecimal("30")}
							)
						)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();

					// all three distinct values should be indexed
					assertUngroupedHistogramBucketContains(
						productCollection, REF_BY_REF_ARRAY_ATTR,
						HISTOGRAM_REF_ARRAY, new BigDecimal("10"), 1
					);
					assertUngroupedHistogramBucketContains(
						productCollection, REF_BY_REF_ARRAY_ATTR,
						HISTOGRAM_REF_ARRAY, new BigDecimal("20"), 1
					);
					assertUngroupedHistogramBucketContains(
						productCollection, REF_BY_REF_ARRAY_ATTR,
						HISTOGRAM_REF_ARRAY, new BigDecimal("30"), 1
					);

					// remove reference to PV#1 (contributed 10, 20)
					session.getEntity(ENTITY_PRODUCT, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.removeReference(REF_BY_REF_ARRAY_ATTR, 1)
						.upsertVia(session);

					// bucket 20 and 30 still present via PV#2
					assertUngroupedHistogramBucketContains(
						productCollection, REF_BY_REF_ARRAY_ATTR,
						HISTOGRAM_REF_ARRAY, new BigDecimal("20"), 1
					);
					assertUngroupedHistogramBucketContains(
						productCollection, REF_BY_REF_ARRAY_ATTR,
						HISTOGRAM_REF_ARRAY, new BigDecimal("30"), 1
					);
					// bucket 10 no longer present (only PV#1 contributed)
					assertUngroupedHistogramBucketNotContains(
						productCollection, REF_BY_REF_ARRAY_ATTR,
						HISTOGRAM_REF_ARRAY, new BigDecimal("10"), 1
					);

					// remove reference to PV#2 (contributed 20, 30)
					session.getEntity(ENTITY_PRODUCT, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.removeReference(REF_BY_REF_ARRAY_ATTR, 2)
						.upsertVia(session);

					// Product#1 should not be in any bucket
					assertUngroupedHistogramNotIndexed(
						productCollection, REF_BY_REF_ARRAY_ATTR,
						HISTOGRAM_REF_ARRAY, 1
					);
				}
			);
		}
	}

	/**
	 * Tests for histogram indexing of BigDecimal[] attributes on referenced entities
	 * accessed via cross-entity trigger expressions.
	 */
	@Nested
	@DisplayName("Referenced entity attribute array histogram")
	class ReferencedEntityAttributeArrayTest {

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should create separate histogram entries for each referenced entity array element")
		void shouldCreateSeparateHistogramEntriesForEachReferencedEntityArrayElement(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineArrayHistogramSchema(session);

					session.createNewEntity(ENTITY_PARAMETER_VALUE, 1)
						.setAttribute(ATTR_STATUS, "ACTIVE")
						.setAttribute(
							ATTR_PRICES,
							new BigDecimal[]{new BigDecimal("10"), new BigDecimal("20"), new BigDecimal("30")}
						)
						.upsertVia(session);

					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setReference(REF_BY_ENTITY_ARRAY_ATTR, 1)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();

					assertUngroupedHistogramBucketContains(
						productCollection, REF_BY_ENTITY_ARRAY_ATTR,
						HISTOGRAM_ENTITY_ARRAY, new BigDecimal("10"), 1
					);
					assertUngroupedHistogramBucketContains(
						productCollection, REF_BY_ENTITY_ARRAY_ATTR,
						HISTOGRAM_ENTITY_ARRAY, new BigDecimal("20"), 1
					);
					assertUngroupedHistogramBucketContains(
						productCollection, REF_BY_ENTITY_ARRAY_ATTR,
						HISTOGRAM_ENTITY_ARRAY, new BigDecimal("30"), 1
					);
				}
			);
		}

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should update histogram when referenced entity array attribute changes")
		void shouldUpdateHistogramWhenReferencedEntityArrayAttributeChanges(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineArrayHistogramSchema(session);

					session.createNewEntity(ENTITY_PARAMETER_VALUE, 1)
						.setAttribute(ATTR_STATUS, "ACTIVE")
						.setAttribute(
							ATTR_PRICES,
							new BigDecimal[]{new BigDecimal("10"), new BigDecimal("20")}
						)
						.upsertVia(session);

					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setReference(REF_BY_ENTITY_ARRAY_ATTR, 1)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();

					assertUngroupedHistogramBucketContains(
						productCollection, REF_BY_ENTITY_ARRAY_ATTR,
						HISTOGRAM_ENTITY_ARRAY, new BigDecimal("10"), 1
					);
					assertUngroupedHistogramBucketContains(
						productCollection, REF_BY_ENTITY_ARRAY_ATTR,
						HISTOGRAM_ENTITY_ARRAY, new BigDecimal("20"), 1
					);

					// change referenced entity prices via cross-entity trigger
					session.getEntity(ENTITY_PARAMETER_VALUE, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setAttribute(
							ATTR_PRICES,
							new BigDecimal[]{new BigDecimal("30"), new BigDecimal("40")}
						)
						.upsertVia(session);

					assertUngroupedHistogramBucketContains(
						productCollection, REF_BY_ENTITY_ARRAY_ATTR,
						HISTOGRAM_ENTITY_ARRAY, new BigDecimal("30"), 1
					);
					assertUngroupedHistogramBucketContains(
						productCollection, REF_BY_ENTITY_ARRAY_ATTR,
						HISTOGRAM_ENTITY_ARRAY, new BigDecimal("40"), 1
					);
					assertUngroupedHistogramBucketNotContains(
						productCollection, REF_BY_ENTITY_ARRAY_ATTR,
						HISTOGRAM_ENTITY_ARRAY, new BigDecimal("10"), 1
					);
					assertUngroupedHistogramBucketNotContains(
						productCollection, REF_BY_ENTITY_ARRAY_ATTR,
						HISTOGRAM_ENTITY_ARRAY, new BigDecimal("20"), 1
					);
				}
			);
		}

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should remove all array histogram entries when condition becomes false")
		void shouldRemoveAllArrayHistogramEntriesWhenConditionBecomesFalse(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineArrayHistogramSchema(session);

					session.createNewEntity(ENTITY_PARAMETER_VALUE, 1)
						.setAttribute(ATTR_STATUS, "ACTIVE")
						.setAttribute(
							ATTR_PRICES,
							new BigDecimal[]{new BigDecimal("10"), new BigDecimal("20"), new BigDecimal("30")}
						)
						.upsertVia(session);

					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setReference(REF_BY_ENTITY_ARRAY_ATTR, 1)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();

					// verify initial state -- all three buckets present
					assertUngroupedHistogramBucketContains(
						productCollection, REF_BY_ENTITY_ARRAY_ATTR,
						HISTOGRAM_ENTITY_ARRAY, new BigDecimal("10"), 1
					);
					assertUngroupedHistogramBucketContains(
						productCollection, REF_BY_ENTITY_ARRAY_ATTR,
						HISTOGRAM_ENTITY_ARRAY, new BigDecimal("20"), 1
					);
					assertUngroupedHistogramBucketContains(
						productCollection, REF_BY_ENTITY_ARRAY_ATTR,
						HISTOGRAM_ENTITY_ARRAY, new BigDecimal("30"), 1
					);

					// change status to INACTIVE -- condition becomes false
					session.getEntity(ENTITY_PARAMETER_VALUE, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setAttribute(ATTR_STATUS, "INACTIVE")
						.upsertVia(session);

					// all buckets should be removed
					assertUngroupedHistogramNotIndexed(
						productCollection, REF_BY_ENTITY_ARRAY_ATTR,
						HISTOGRAM_ENTITY_ARRAY, 1
					);

					// restore status to ACTIVE -- all buckets should reappear
					session.getEntity(ENTITY_PARAMETER_VALUE, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setAttribute(ATTR_STATUS, "ACTIVE")
						.upsertVia(session);

					assertUngroupedHistogramBucketContains(
						productCollection, REF_BY_ENTITY_ARRAY_ATTR,
						HISTOGRAM_ENTITY_ARRAY, new BigDecimal("10"), 1
					);
					assertUngroupedHistogramBucketContains(
						productCollection, REF_BY_ENTITY_ARRAY_ATTR,
						HISTOGRAM_ENTITY_ARRAY, new BigDecimal("20"), 1
					);
					assertUngroupedHistogramBucketContains(
						productCollection, REF_BY_ENTITY_ARRAY_ATTR,
						HISTOGRAM_ENTITY_ARRAY, new BigDecimal("30"), 1
					);
				}
			);
		}
	}

	/**
	 * Edge case tests for array-typed histogram indexing with null and empty arrays.
	 */
	@Nested
	@DisplayName("Edge cases")
	class EdgeCaseTest {

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should handle empty and null array gracefully")
		void shouldHandleEmptyAndNullArrayGracefully(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineArrayHistogramSchema(session);

					session.createNewEntity(ENTITY_PARAMETER_VALUE, 1).upsertVia(session);
					session.createNewEntity(ENTITY_PARAMETER_VALUE, 2).upsertVia(session);

					// Product#1 with ref to PV#1, refPrices NOT set (null)
					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setReference(REF_BY_REF_ARRAY_ATTR, 1)
						.upsertVia(session);

					// Product#2 with ref to PV#2, refPrices = empty array
					session.createNewEntity(ENTITY_PRODUCT, 2)
						.setReference(
							REF_BY_REF_ARRAY_ATTR, 2,
							whichIs -> whichIs.setAttribute(ATTR_REF_PRICES, new BigDecimal[]{})
						)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();

					// neither product should be in any histogram bucket
					assertUngroupedHistogramNotIndexed(
						productCollection, REF_BY_REF_ARRAY_ATTR,
						HISTOGRAM_REF_ARRAY, 1
					);
					assertUngroupedHistogramNotIndexed(
						productCollection, REF_BY_REF_ARRAY_ATTR,
						HISTOGRAM_REF_ARRAY, 2
					);
				}
			);
		}
	}
}
