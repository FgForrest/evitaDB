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
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.core.Evita;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.ReducedGroupEntityIndex;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.EvitaTestSupport.TestPaths;
import io.evitadb.utils.Functions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.function.Consumer;
import org.junit.jupiter.api.Tag;

import static io.evitadb.api.query.QueryConstraints.entityFetchAllContent;
import static org.junit.jupiter.api.Assertions.*;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.INDEXING;

/**
 * End-to-end integration tests for the conditional bucketed histogram indexing infrastructure.
 * Verifies data access paths, trigger mechanisms, state transitions, fan-out,
 * and correct histogram FilterIndex maintenance when `bucketedPartially` expressions are used.
 *
 * Mirrors the structure of {@link ConditionalFacetIndexingTest} but for histogram indexing.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Conditional bucket indexing operations")
@Tag(CONTRACT)
@Tag(INDEXING)
class ConditionalBucketIndexingTest implements EvitaTestSupport, IndexingTestSupport {

	private static final String ENTITY_PRODUCT = "product";
	private static final String ENTITY_PARAMETER_VALUE = "parameterValue";
	private static final String ENTITY_PARAMETER = "parameter";
	private static final String ENTITY_TAG = "tag";
	private static final String ENTITY_BUNDLE = "bundle";

	private static final String REF_PARAM_BY_GROUP_ATTR = "paramByGroupAttr";
	private static final String REF_PARAM_BY_GROUP_ATTR_WITH_DEFAULT = "paramByGroupAttrWithDefault";
	private static final String REF_PARAM_BY_REF_ATTR = "paramByRefAttr";
	private static final String REF_PARAM_BY_ENTITY_ATTR = "paramByEntityAttr";
	private static final String REF_PARAM_BY_REF_ENTITY_ATTR = "paramByRefEntityAttr";
	private static final String REF_PARAM_UNCONDITIONAL = "paramUnconditional";
	private static final String REF_PARAM_MULTI_HISTOGRAM = "paramMultiHistogram";
	private static final String REF_PARAM_BY_MIXED_AND = "paramByMixedAnd";
	private static final String REF_PARAM_BY_MULTI_SOURCE_OR = "paramByMultiSourceOr";
	private static final String REF_PARAM_DUAL_FACET_HISTOGRAM = "paramDualFacetHistogram";
	private static final String REF_PARAM_BY_LOCALIZED_ATTR = "paramByLocalizedAttr";

	private static final String ATTR_INPUT_WIDGET_TYPE = "inputWidgetType";
	private static final String ATTR_BASIC_UNIT_VALUE = "basicUnitValue";
	private static final String ATTR_WEIGHT = "weight";
	private static final String ATTR_LOCALIZED_WEIGHT = "localizedWeight";
	private static final String ATTR_STATUS = "status";
	private static final String ATTR_PRIORITY = "priority";
	private static final String ATTR_SOME_VALUE = "someValue";
	private static final String ATTR_IS_ACTIVE = "isActive";
	private static final String ATTR_CODE = "code";

	private static final String HISTOGRAM_VALUE = "valueHistogram";
	private static final String HISTOGRAM_REF_ATTR = "refAttrHistogram";
	private static final String HISTOGRAM_ENTITY = "entityHistogram";
	private static final String HISTOGRAM_STATUS = "statusHistogram";
	private static final String HISTOGRAM_UNCONDITIONAL = "unconditionalHistogram";
	private static final String HISTOGRAM_HIST1 = "hist1";
	private static final String HISTOGRAM_HIST2 = "hist2";
	private static final String HISTOGRAM_MIXED = "mixedHistogram";
	private static final String HISTOGRAM_OR = "orHistogram";
	private static final String HISTOGRAM_DUAL = "dualHistogram";
	private static final String HISTOGRAM_LOCALIZED = "localizedHistogram";


	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("ConditionalBucketIndexingTest");
		this.evita = new Evita(
			getEvitaConfiguration()
		);
		this.evita.defineCatalog(TEST_CATALOG);
	}

	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	/**
	 * Builds the standard Evita configuration pointing to the test directories.
	 *
	 * @return the Evita configuration for the test
	 */
	@Nonnull
	private EvitaConfiguration getEvitaConfiguration() {
		return newTestEvitaConfigurationBuilder(this.paths)
			.server(
				ServerOptions.builder()
					.closeSessionsAfterSecondsOfInactivity(-1)
					.build()
			)
			.build();
	}

	/**
	 * Defines all entity types and reference schemas needed for conditional bucket indexing tests.
	 *
	 * @param session the active evitaDB session
	 */
	private static void defineConditionalBucketSchema(@Nonnull EvitaSessionContract session) {
		// 1. Define Tag (simple entity for nested reference tests)
		session.defineEntitySchema(ENTITY_TAG).updateVia(session);

		// 2. Define Parameter (group entity)
		session.defineEntitySchema(ENTITY_PARAMETER)
			.withAttribute(ATTR_INPUT_WIDGET_TYPE, String.class, whichIs -> whichIs.filterable().nullable())
			.withReferenceToEntity(
				ENTITY_TAG, ENTITY_TAG, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFiltering()
					.withAttribute(ATTR_WEIGHT, Integer.class, whichAttr -> whichAttr.filterable().nullable())
			)
			.updateVia(session);

		// 3. Define ParameterValue (referenced entity)
		session.defineEntitySchema(ENTITY_PARAMETER_VALUE)
			.withLocale(Locale.ENGLISH, new Locale("cs"))
			.withAttribute(ATTR_BASIC_UNIT_VALUE, BigDecimal.class, whichIs -> whichIs.filterable().nullable())
			.withAttribute(ATTR_WEIGHT, Integer.class, whichIs -> whichIs.filterable().nullable())
			.withAttribute(ATTR_STATUS, String.class, whichIs -> whichIs.filterable().nullable())
			.withAttribute(
				ATTR_LOCALIZED_WEIGHT, BigDecimal.class,
				whichIs -> whichIs.localized().filterable().nullable()
			)
			.updateVia(session);

		// 4. Define Product (owner entity with all reference types)
		session.defineEntitySchema(ENTITY_PRODUCT)
			.withHierarchy()
			.withAttribute(ATTR_IS_ACTIVE, Boolean.class, whichIs -> whichIs.filterable().nullable())
			.withAttribute(ATTR_CODE, String.class, whichIs -> whichIs.filterable().nullable())

			// --- Grouped: condition on group entity attribute, value from referenced entity ---
			.withReferenceToEntity(
				REF_PARAM_BY_GROUP_ATTR, ENTITY_PARAMETER_VALUE, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFilteringAndPartitioning()
					.indexedWithComponents(ReferenceIndexedComponents.values())
					.withGroupTypeRelatedToEntity(ENTITY_PARAMETER)
					.bucketed(
						HISTOGRAM_VALUE,
						ExpressionFactory.parse(
							"$reference.referencedEntity?.attributes['basicUnitValue']"
						)
					)
					.bucketedPartially(
						ExpressionFactory.parse(
							"($reference.groupEntity?.attributes['inputWidgetType'] ?? '') == 'INTERVAL'"
						)
					)
			)

			// --- Grouped: same as above but with ?? default on value expression ---
			.withReferenceToEntity(
				REF_PARAM_BY_GROUP_ATTR_WITH_DEFAULT, ENTITY_PARAMETER_VALUE, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFilteringAndPartitioning()
					.indexedWithComponents(ReferenceIndexedComponents.values())
					.withGroupTypeRelatedToEntity(ENTITY_PARAMETER)
					.bucketed(
						HISTOGRAM_VALUE,
						ExpressionFactory.parse(
							"$reference.referencedEntity?.attributes['basicUnitValue'] ?? 0"
						)
					)
					.bucketedPartially(
						ExpressionFactory.parse(
							"($reference.groupEntity?.attributes['inputWidgetType'] ?? '') == 'INTERVAL'"
						)
					)
			)

			// --- Ungrouped: condition on reference attribute, value from reference attribute ---
			.withReferenceToEntity(
				REF_PARAM_BY_REF_ATTR, ENTITY_PARAMETER_VALUE, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFilteringAndPartitioning()
					.withAttribute(ATTR_PRIORITY, Integer.class, whichAttr -> whichAttr.filterable().nullable())
					.withAttribute(ATTR_SOME_VALUE, BigDecimal.class, whichAttr -> whichAttr.filterable().nullable())
					.bucketed(
						HISTOGRAM_REF_ATTR,
						ExpressionFactory.parse("$reference.attributes['someValue']")
					)
					.bucketedPartially(
						ExpressionFactory.parse("$reference.attributes['priority'] > 0")
					)
			)

			// --- Ungrouped: condition on entity attribute, value from referenced entity ---
			.withReferenceToEntity(
				REF_PARAM_BY_ENTITY_ATTR, ENTITY_PARAMETER_VALUE, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFilteringAndPartitioning()
					.bucketed(
						HISTOGRAM_ENTITY,
						ExpressionFactory.parse(
							"$reference.referencedEntity?.attributes['basicUnitValue']"
						)
					)
					.bucketedPartially(
						ExpressionFactory.parse(
							"($entity.attributes['isActive'] ?? false) == true"
						)
					)
			)

			// --- Ungrouped: condition on referenced entity attribute, value from referenced entity ---
			.withReferenceToEntity(
				REF_PARAM_BY_REF_ENTITY_ATTR, ENTITY_PARAMETER_VALUE, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFilteringAndPartitioning()
					.bucketed(
						HISTOGRAM_STATUS,
						ExpressionFactory.parse(
							"$reference.referencedEntity?.attributes['basicUnitValue']"
						)
					)
					.bucketedPartially(
						ExpressionFactory.parse(
							"($reference.referencedEntity.attributes['status'] ?? '') == 'ACTIVE'"
						)
					)
			)

			// --- Grouped: unconditional (no bucketedPartially expression) ---
			.withReferenceToEntity(
				REF_PARAM_UNCONDITIONAL, ENTITY_PARAMETER_VALUE, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFilteringAndPartitioning()
					.indexedWithComponents(ReferenceIndexedComponents.values())
					.withGroupTypeRelatedToEntity(ENTITY_PARAMETER)
					.bucketed(
						HISTOGRAM_UNCONDITIONAL,
						ExpressionFactory.parse(
							"$reference.referencedEntity?.attributes['basicUnitValue']"
						)
					)
			)

			// --- Grouped: multiple histogram definitions on same reference ---
			.withReferenceToEntity(
				REF_PARAM_MULTI_HISTOGRAM, ENTITY_PARAMETER_VALUE, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFilteringAndPartitioning()
					.indexedWithComponents(ReferenceIndexedComponents.values())
					.withGroupTypeRelatedToEntity(ENTITY_PARAMETER)
					.bucketed(
						HISTOGRAM_HIST1,
						ExpressionFactory.parse(
							"$reference.referencedEntity?.attributes['basicUnitValue']"
						)
					)
					.bucketed(
						HISTOGRAM_HIST2,
						ExpressionFactory.parse(
							"$reference.referencedEntity?.attributes['weight']"
						)
					)
					.bucketedPartially(
						ExpressionFactory.parse(
							"($reference.groupEntity?.attributes['inputWidgetType'] ?? '') == 'INTERVAL'"
						)
					)
			)

			// --- Grouped: compound AND condition (group + entity attribute) ---
			.withReferenceToEntity(
				REF_PARAM_BY_MIXED_AND, ENTITY_PARAMETER_VALUE, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFilteringAndPartitioning()
					.indexedWithComponents(ReferenceIndexedComponents.values())
					.withGroupTypeRelatedToEntity(ENTITY_PARAMETER)
					.bucketed(
						HISTOGRAM_MIXED,
						ExpressionFactory.parse(
							"$reference.referencedEntity?.attributes['basicUnitValue']"
						)
					)
					.bucketedPartially(
						ExpressionFactory.parse(
							"($reference.groupEntity?.attributes['inputWidgetType'] ?? '') == 'INTERVAL'"
								+ " && ($entity.attributes['isActive'] ?? false) == true"
						)
					)
			)

			// --- Grouped: compound OR condition (group + referenced entity) ---
			.withReferenceToEntity(
				REF_PARAM_BY_MULTI_SOURCE_OR, ENTITY_PARAMETER_VALUE, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFilteringAndPartitioning()
					.indexedWithComponents(ReferenceIndexedComponents.values())
					.withGroupTypeRelatedToEntity(ENTITY_PARAMETER)
					.bucketed(
						HISTOGRAM_OR,
						ExpressionFactory.parse(
							"$reference.referencedEntity?.attributes['basicUnitValue']"
						)
					)
					.bucketedPartially(
						ExpressionFactory.parse(
							"($reference.groupEntity?.attributes['inputWidgetType'] ?? '') == 'INTERVAL'"
								+ " || ($reference.referencedEntity.attributes['status'] ?? '') == 'ACTIVE'"
						)
					)
			)

			// --- Grouped: dual facet + histogram on same reference (mutually exclusive conditions) ---
			.withReferenceToEntity(
				REF_PARAM_DUAL_FACET_HISTOGRAM, ENTITY_PARAMETER_VALUE, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFilteringAndPartitioning()
					.indexedWithComponents(ReferenceIndexedComponents.values())
					.faceted()
					.withGroupTypeRelatedToEntity(ENTITY_PARAMETER)
					.facetedPartially(
						ExpressionFactory.parse(
							"($reference.groupEntity?.attributes['inputWidgetType'] ?? '') == 'CHECKBOX'"
						)
					)
					.bucketed(
						HISTOGRAM_DUAL,
						ExpressionFactory.parse(
							"$reference.referencedEntity?.attributes['basicUnitValue']"
						)
					)
					.bucketedPartially(
						ExpressionFactory.parse(
							"($reference.groupEntity?.attributes['inputWidgetType'] ?? '') == 'INTERVAL'"
						)
					)
			)

			// --- Ungrouped: localized histogram from referenced entity localized attribute ---
			.withReferenceToEntity(
				REF_PARAM_BY_LOCALIZED_ATTR, ENTITY_PARAMETER_VALUE, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFilteringAndPartitioning()
					.bucketed(
						HISTOGRAM_LOCALIZED,
						ExpressionFactory.parse(
							"$reference.referencedEntity?.localizedAttributes['localizedWeight']"
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
	 * Returns the Product entity collection from the current catalog.
	 *
	 * @return the Product collection
	 */
	@Nonnull
	private EntityCollectionContract getProductCollection() {
		return getCollection(ENTITY_PRODUCT);
	}

	/**
	 * Returns the live collection of the given entity type from the test catalog.
	 *
	 * @param entityType the entity type whose collection is requested
	 * @return the entity collection
	 */
	@Nonnull
	private EntityCollectionContract getCollection(@Nonnull String entityType) {
		final CatalogContract catalog = this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
		return catalog.getCollectionForEntity(entityType).orElseThrow();
	}

	/**
	 * Asserts that the owner entity is present in the histogram FilterIndex bucket for the given value
	 * in the specified group's ReducedGroupEntityIndex.
	 *
	 * @param collection    the entity collection to inspect
	 * @param referenceName the reference schema name
	 * @param groupPK       the group entity primary key (group index discriminator)
	 * @param histogramName the histogram definition name
	 * @param value         the expected histogram bucket value
	 * @param ownerPK       the primary key of the owner entity (Product PK)
	 */
	private static void assertHistogramBucketContains(
		@Nonnull EntityCollectionContract collection,
		@Nonnull String referenceName,
		int groupPK,
		@Nonnull String histogramName,
		@Nonnull Serializable value,
		int ownerPK
	) {
		final EntityIndex entityIndex = IndexingTestSupport.getReferencedGroupEntityIndex(
			collection, Scope.LIVE, referenceName, groupPK
		);
		assertNotNull(
			entityIndex,
			"ReducedGroupEntityIndex for ref '" + referenceName
				+ "' groupPK " + groupPK + " must exist"
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
				+ "' in groupPK " + groupPK + " must exist"
		);
		assertTrue(
			filterIndex.getRecordsEqualTo(EvitaDataTypes.toSupportedType(value)).contains(ownerPK),
			"Owner PK " + ownerPK + " should be in histogram '" + histogramName
				+ "' bucket " + value + " groupPK " + groupPK
		);
	}

	/**
	 * Asserts that the owner entity is NOT present in the histogram FilterIndex bucket for the given value
	 * in the specified group's ReducedGroupEntityIndex.
	 *
	 * @param collection    the entity collection to inspect
	 * @param referenceName the reference schema name
	 * @param groupPK       the group entity primary key (group index discriminator)
	 * @param histogramName the histogram definition name
	 * @param value         the histogram bucket value that should NOT contain the owner
	 * @param ownerPK       the primary key of the owner entity (Product PK)
	 */
	private static void assertHistogramBucketNotContains(
		@Nonnull EntityCollectionContract collection,
		@Nonnull String referenceName,
		int groupPK,
		@Nonnull String histogramName,
		@Nonnull Serializable value,
		int ownerPK
	) {
		final EntityIndex entityIndex = IndexingTestSupport.getReferencedGroupEntityIndex(
			collection, Scope.LIVE, referenceName, groupPK
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
			filterIndex.getRecordsEqualTo(EvitaDataTypes.toSupportedType(value)).contains(ownerPK),
			"Owner PK " + ownerPK + " should NOT be in histogram '" + histogramName
				+ "' bucket " + value + " groupPK " + groupPK
		);
	}

	/**
	 * Asserts that the owner entity is NOT present in any histogram FilterIndex bucket
	 * in the specified group's ReducedGroupEntityIndex.
	 *
	 * @param collection    the entity collection to inspect
	 * @param referenceName the reference schema name
	 * @param groupPK       the group entity primary key (group index discriminator)
	 * @param histogramName the histogram definition name
	 * @param ownerPK       the primary key of the owner entity (Product PK)
	 */
	private static void assertHistogramNotIndexed(
		@Nonnull EntityCollectionContract collection,
		@Nonnull String referenceName,
		int groupPK,
		@Nonnull String histogramName,
		int ownerPK
	) {
		final EntityIndex entityIndex = IndexingTestSupport.getReferencedGroupEntityIndex(
			collection, Scope.LIVE, referenceName, groupPK
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
				+ "' groupPK " + groupPK
		);
	}

	/**
	 * Asserts that the owner entity is present in the histogram FilterIndex bucket for ungrouped references
	 * in the ReferencedTypeEntityIndex.
	 *
	 * @param collection    the entity collection to inspect
	 * @param referenceName the reference schema name
	 * @param histogramName the histogram definition name
	 * @param value         the expected histogram bucket value
	 * @param ownerPK       the primary key of the owner entity (Product PK)
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
	 * Asserts that the owner entity is NOT present in the specified histogram bucket value
	 * for ungrouped references in the ReferencedTypeEntityIndex.
	 *
	 * @param collection    the entity collection to inspect
	 * @param referenceName the reference schema name
	 * @param histogramName the histogram definition name
	 * @param value         the histogram bucket value to check
	 * @param ownerPK       the primary key of the owner entity (Product PK)
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
			return; // no type index = not indexed
		}
		assertInstanceOf(
			ReferencedTypeEntityIndex.class, entityIndex,
			"Expected ReferencedTypeEntityIndex but got " + entityIndex.getClass().getSimpleName()
		);
		final ReferencedTypeEntityIndex typeIndex = (ReferencedTypeEntityIndex) entityIndex;
		final FilterIndex filterIndex = typeIndex.getHistogramFilterIndex(histogramName, null);
		if (filterIndex == null) {
			return; // no histogram FilterIndex = not indexed at all
		}
		assertFalse(
			filterIndex.getRecordsEqualTo(EvitaDataTypes.toSupportedType(value)).contains(ownerPK),
			"Owner PK " + ownerPK + " should NOT be in ungrouped histogram '" + histogramName
				+ "' bucket " + value
		);
	}

	/**
	 * Asserts that the owner entity is NOT present in any ungrouped histogram FilterIndex bucket.
	 *
	 * @param collection    the entity collection to inspect
	 * @param referenceName the reference schema name
	 * @param histogramName the histogram definition name
	 * @param ownerPK       the primary key of the owner entity (Product PK)
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
			"Owner PK " + ownerPK + " should NOT be in ungrouped histogram '" + histogramName + "'"
		);
	}

	/**
	 * Asserts that the owner entity is present in the locale-specific histogram FilterIndex bucket
	 * for ungrouped references in the ReferencedTypeEntityIndex.
	 *
	 * @param collection    the entity collection to inspect
	 * @param referenceName the reference schema name
	 * @param histogramName the histogram definition name
	 * @param locale        the locale for the localized histogram FilterIndex
	 * @param value         the expected histogram bucket value
	 * @param ownerPK       the primary key of the owner entity (Product PK)
	 */
	private static void assertUngroupedLocalizedHistogramBucketContains(
		@Nonnull EntityCollectionContract collection,
		@Nonnull String referenceName,
		@Nonnull String histogramName,
		@Nonnull Locale locale,
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
		final FilterIndex filterIndex = typeIndex.getHistogramFilterIndex(histogramName, locale);
		assertNotNull(
			filterIndex,
			"Histogram FilterIndex '" + histogramName + "' locale " + locale
				+ " in ungrouped ref '" + referenceName + "' must exist"
		);
		assertTrue(
			filterIndex.getRecordsEqualTo(EvitaDataTypes.toSupportedType(value)).contains(ownerPK),
			"Owner PK " + ownerPK + " should be in ungrouped histogram '" + histogramName
				+ "' locale " + locale + " bucket " + value
		);
	}

	/**
	 * Asserts that the owner entity is NOT present in any locale-specific ungrouped histogram
	 * FilterIndex bucket.
	 *
	 * @param collection    the entity collection to inspect
	 * @param referenceName the reference schema name
	 * @param histogramName the histogram definition name
	 * @param locale        the locale for the localized histogram FilterIndex
	 * @param ownerPK       the primary key of the owner entity (Product PK)
	 */
	private static void assertUngroupedLocalizedHistogramNotIndexed(
		@Nonnull EntityCollectionContract collection,
		@Nonnull String referenceName,
		@Nonnull String histogramName,
		@Nonnull Locale locale,
		int ownerPK
	) {
		final EntityIndex entityIndex = IndexingTestSupport.getReferencedEntityTypeIndex(
			collection, Scope.LIVE, referenceName
		);
		if (entityIndex == null) {
			return; // no type index = not indexed
		}
		assertInstanceOf(
			ReferencedTypeEntityIndex.class, entityIndex,
			"Expected ReferencedTypeEntityIndex but got " + entityIndex.getClass().getSimpleName()
		);
		final ReferencedTypeEntityIndex typeIndex = (ReferencedTypeEntityIndex) entityIndex;
		final FilterIndex filterIndex = typeIndex.getHistogramFilterIndex(histogramName, locale);
		if (filterIndex == null) {
			return; // no histogram FilterIndex for this locale = not indexed
		}
		assertFalse(
			filterIndex.getAllRecords().contains(ownerPK),
			"Owner PK " + ownerPK + " should NOT be in ungrouped histogram '" + histogramName
				+ "' locale " + locale
		);
	}

	/**
	 * Asserts that no histogram FilterIndex exists for the given locale in the ungrouped
	 * ReferencedTypeEntityIndex.
	 *
	 * @param collection    the entity collection to inspect
	 * @param referenceName the reference schema name
	 * @param histogramName the histogram definition name
	 * @param locale        the locale for the localized histogram FilterIndex
	 */
	private static void assertUngroupedLocalizedHistogramAbsent(
		@Nonnull EntityCollectionContract collection,
		@Nonnull String referenceName,
		@Nonnull String histogramName,
		@Nonnull Locale locale
	) {
		final EntityIndex entityIndex = IndexingTestSupport.getReferencedEntityTypeIndex(
			collection, Scope.LIVE, referenceName
		);
		if (entityIndex == null) {
			return; // no type index = no histogram
		}
		assertInstanceOf(
			ReferencedTypeEntityIndex.class, entityIndex,
			"Expected ReferencedTypeEntityIndex but got " + entityIndex.getClass().getSimpleName()
		);
		final ReferencedTypeEntityIndex typeIndex = (ReferencedTypeEntityIndex) entityIndex;
		final FilterIndex filterIndex = typeIndex.getHistogramFilterIndex(histogramName, locale);
		assertNull(
			filterIndex,
			"Histogram FilterIndex '" + histogramName + "' locale " + locale
				+ " should not exist in ungrouped ref '" + referenceName + "'"
		);
	}

	/**
	 * Asserts that the specified owner entity is still present in the reduced entity index
	 * for the given reference, confirming that reference-based filtering still works even
	 * when the histogram is conditionally excluded.
	 *
	 * @param collection    the entity collection to inspect
	 * @param referenceName the reference schema name
	 * @param refPK         the primary key of the referenced entity
	 * @param ownerPK       the primary key of the owner entity (Product PK)
	 */
	private static void assertReferenceStillIndexed(
		@Nonnull EntityCollectionContract collection,
		@Nonnull String referenceName,
		int refPK,
		int ownerPK
	) {
		final EntityIndex reducedIndex = IndexingTestSupport.getReferencedEntityIndex(
			collection, referenceName, refPK
		);
		assertNotNull(
			reducedIndex,
			"Reduced entity index for reference '" + referenceName + "' PK " + refPK + " must exist"
		);
		assertTrue(
			reducedIndex.getAllPrimaryKeys().contains(ownerPK),
			"Owner entity PK " + ownerPK + " should be in reduced index for reference '"
				+ referenceName + "', referenced PK " + refPK
		);
	}

	/**
	 * Asserts that a facet IS indexed for the given owner in the global index.
	 *
	 * @param collection    the entity collection to inspect
	 * @param referenceName the reference schema name
	 * @param facetPK       the primary key of the faceted entity
	 * @param groupPK       the group entity PK (null if ungrouped)
	 * @param ownerPK       the primary key of the owner entity (Product PK)
	 */
	private static void assertFacetIndexed(
		@Nonnull EntityCollectionContract collection,
		@Nonnull String referenceName,
		int facetPK,
		@Nullable Integer groupPK,
		int ownerPK
	) {
		final EntityIndex globalIndex = IndexingTestSupport.getGlobalIndex(collection);
		assertNotNull(globalIndex, "Global index must exist");
		final var facetRefIndex = globalIndex.getFacetingEntities().get(referenceName);
		assertNotNull(facetRefIndex, "FacetReferenceIndex for '" + referenceName + "' must exist");
		final var facetGroupIndex = facetRefIndex.getFacetsInGroup(groupPK);
		assertNotNull(facetGroupIndex, "FacetGroupIndex for group " + groupPK + " must exist");
		final var facetIdIndex = facetGroupIndex.getFacetIdIndex(facetPK);
		assertNotNull(facetIdIndex, "FacetIdIndex for facet PK " + facetPK + " must exist");
		assertTrue(
			facetIdIndex.getRecords().contains(ownerPK),
			"Owner PK " + ownerPK + " should be in facet index for '" + referenceName
				+ "', facet PK " + facetPK
		);
	}

	/**
	 * Asserts that a facet is NOT indexed for the given owner in the global index.
	 *
	 * @param collection    the entity collection to inspect
	 * @param referenceName the reference schema name
	 * @param facetPK       the primary key of the faceted entity
	 * @param groupPK       the group entity PK (null if ungrouped)
	 * @param ownerPK       the primary key of the owner entity (Product PK)
	 */
	private static void assertFacetNotIndexed(
		@Nonnull EntityCollectionContract collection,
		@Nonnull String referenceName,
		int facetPK,
		@Nullable Integer groupPK,
		int ownerPK
	) {
		final EntityIndex globalIndex = IndexingTestSupport.getGlobalIndex(collection);
		if (globalIndex == null) {
			return;
		}
		final var facetRefIndex = globalIndex.getFacetingEntities().get(referenceName);
		if (facetRefIndex == null) {
			return;
		}
		final var facetGroupIndex = facetRefIndex.getFacetsInGroup(groupPK);
		if (facetGroupIndex == null) {
			return;
		}
		final var facetIdIndex = facetGroupIndex.getFacetIdIndex(facetPK);
		if (facetIdIndex == null) {
			return;
		}
		assertFalse(
			facetIdIndex.getRecords().contains(ownerPK),
			"Owner PK " + ownerPK + " should NOT be in facet index for '" + referenceName
				+ "', facet PK " + facetPK
		);
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
	 * Tests verifying correct initial histogram index state for each supported data access path.
	 */
	@Nested
	@DisplayName("Initial indexing — one test per data access path")
	class InitialIndexingTest {

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should index histogram conditionally based on group entity attribute")
		void shouldIndexHistogramConditionallyBasedOnGroupEntityAttribute(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineConditionalBucketSchema(session);

					session.createNewEntity(ENTITY_PARAMETER, 10)
						.setAttribute(ATTR_INPUT_WIDGET_TYPE, "INTERVAL")
						.upsertVia(session);
					session.createNewEntity(ENTITY_PARAMETER, 20)
						.setAttribute(ATTR_INPUT_WIDGET_TYPE, "CHECKBOX")
						.upsertVia(session);

					session.createNewEntity(ENTITY_PARAMETER_VALUE, 1)
						.setAttribute(ATTR_BASIC_UNIT_VALUE, new BigDecimal("50"))
						.upsertVia(session);

					// Product#1 refs PV#1 via matching group → bucketed
					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setReference(
							REF_PARAM_BY_GROUP_ATTR, 1,
							whichIs -> whichIs.setGroup(ENTITY_PARAMETER, 10)
						)
						.upsertVia(session);

					// Product#2 refs PV#1 via non-matching group → NOT bucketed
					session.createNewEntity(ENTITY_PRODUCT, 2)
						.setReference(
							REF_PARAM_BY_GROUP_ATTR, 1,
							whichIs -> whichIs.setGroup(ENTITY_PARAMETER, 20)
						)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();
					assertHistogramBucketContains(
						productCollection, REF_PARAM_BY_GROUP_ATTR, 10,
						HISTOGRAM_VALUE, new BigDecimal("50"), 1
					);
					assertHistogramNotIndexed(
						productCollection, REF_PARAM_BY_GROUP_ATTR, 10,
						HISTOGRAM_VALUE, 2
					);
					assertReferenceStillIndexed(
						productCollection, REF_PARAM_BY_GROUP_ATTR, 1, 2
					);
				}
			);
		}

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should index histogram conditionally based on referenced entity attribute")
		void shouldIndexHistogramConditionallyBasedOnReferencedEntityAttribute(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineConditionalBucketSchema(session);

					session.createNewEntity(ENTITY_PARAMETER_VALUE, 1)
						.setAttribute(ATTR_STATUS, "ACTIVE")
						.setAttribute(ATTR_BASIC_UNIT_VALUE, new BigDecimal("50"))
						.upsertVia(session);
					session.createNewEntity(ENTITY_PARAMETER_VALUE, 2)
						.setAttribute(ATTR_STATUS, "INACTIVE")
						.setAttribute(ATTR_BASIC_UNIT_VALUE, new BigDecimal("75"))
						.upsertVia(session);

					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setReference(REF_PARAM_BY_REF_ENTITY_ATTR, 1)
						.upsertVia(session);
					session.createNewEntity(ENTITY_PRODUCT, 2)
						.setReference(REF_PARAM_BY_REF_ENTITY_ATTR, 2)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();
					assertUngroupedHistogramBucketContains(
						productCollection, REF_PARAM_BY_REF_ENTITY_ATTR,
						HISTOGRAM_STATUS, new BigDecimal("50"), 1
					);
					assertUngroupedHistogramNotIndexed(
						productCollection, REF_PARAM_BY_REF_ENTITY_ATTR,
						HISTOGRAM_STATUS, 2
					);
				}
			);
		}

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should index histogram conditionally based on reference attribute")
		void shouldIndexHistogramConditionallyBasedOnReferenceAttribute(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineConditionalBucketSchema(session);

					session.createNewEntity(ENTITY_PARAMETER_VALUE, 1).upsertVia(session);

					// Product#1: priority=5 (> 0 → TRUE), someValue=100
					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setReference(
							REF_PARAM_BY_REF_ATTR, 1,
							whichIs -> whichIs
								.setAttribute(ATTR_PRIORITY, 5)
								.setAttribute(ATTR_SOME_VALUE, new BigDecimal("100"))
						)
						.upsertVia(session);

					// Product#2: priority=-1 (≤ 0 → FALSE), someValue=200
					session.createNewEntity(ENTITY_PRODUCT, 2)
						.setReference(
							REF_PARAM_BY_REF_ATTR, 1,
							whichIs -> whichIs
								.setAttribute(ATTR_PRIORITY, -1)
								.setAttribute(ATTR_SOME_VALUE, new BigDecimal("200"))
						)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();
					assertUngroupedHistogramBucketContains(
						productCollection, REF_PARAM_BY_REF_ATTR,
						HISTOGRAM_REF_ATTR, new BigDecimal("100"), 1
					);
					assertUngroupedHistogramNotIndexed(
						productCollection, REF_PARAM_BY_REF_ATTR,
						HISTOGRAM_REF_ATTR, 2
					);
					assertReferenceStillIndexed(
						productCollection, REF_PARAM_BY_REF_ATTR, 1, 2
					);
				}
			);
		}

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should index histogram conditionally based on entity attribute")
		void shouldIndexHistogramConditionallyBasedOnEntityAttribute(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineConditionalBucketSchema(session);

					session.createNewEntity(ENTITY_PARAMETER_VALUE, 1)
						.setAttribute(ATTR_BASIC_UNIT_VALUE, new BigDecimal("50"))
						.upsertVia(session);

					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setAttribute(ATTR_IS_ACTIVE, true)
						.setReference(REF_PARAM_BY_ENTITY_ATTR, 1)
						.upsertVia(session);

					session.createNewEntity(ENTITY_PRODUCT, 2)
						.setAttribute(ATTR_IS_ACTIVE, false)
						.setReference(REF_PARAM_BY_ENTITY_ATTR, 1)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();
					assertUngroupedHistogramBucketContains(
						productCollection, REF_PARAM_BY_ENTITY_ATTR,
						HISTOGRAM_ENTITY, new BigDecimal("50"), 1
					);
					assertUngroupedHistogramNotIndexed(
						productCollection, REF_PARAM_BY_ENTITY_ATTR,
						HISTOGRAM_ENTITY, 2
					);
					assertReferenceStillIndexed(
						productCollection, REF_PARAM_BY_ENTITY_ATTR, 1, 2
					);
				}
			);
		}

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should index histogram unconditionally when no bucketedPartially expression")
		void shouldIndexHistogramUnconditionallyWhenNoBucketedPartiallyExpression(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineConditionalBucketSchema(session);

					session.createNewEntity(ENTITY_PARAMETER, 10)
						.setAttribute(ATTR_INPUT_WIDGET_TYPE, "ANYTHING")
						.upsertVia(session);

					session.createNewEntity(ENTITY_PARAMETER_VALUE, 1)
						.setAttribute(ATTR_BASIC_UNIT_VALUE, new BigDecimal("50"))
						.upsertVia(session);

					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setReference(
							REF_PARAM_UNCONDITIONAL, 1,
							whichIs -> whichIs.setGroup(ENTITY_PARAMETER, 10)
						)
						.upsertVia(session);
					session.createNewEntity(ENTITY_PRODUCT, 2)
						.setReference(
							REF_PARAM_UNCONDITIONAL, 1,
							whichIs -> whichIs.setGroup(ENTITY_PARAMETER, 10)
						)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();
					assertHistogramBucketContains(
						productCollection, REF_PARAM_UNCONDITIONAL, 10,
						HISTOGRAM_UNCONDITIONAL, new BigDecimal("50"), 1
					);
					assertHistogramBucketContains(
						productCollection, REF_PARAM_UNCONDITIONAL, 10,
						HISTOGRAM_UNCONDITIONAL, new BigDecimal("50"), 2
					);
				}
			);
		}

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should index multiple histogram definitions on same reference")
		void shouldIndexMultipleHistogramDefinitionsOnSameReference(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineConditionalBucketSchema(session);

					session.createNewEntity(ENTITY_PARAMETER, 10)
						.setAttribute(ATTR_INPUT_WIDGET_TYPE, "INTERVAL")
						.upsertVia(session);

					session.createNewEntity(ENTITY_PARAMETER_VALUE, 1)
						.setAttribute(ATTR_BASIC_UNIT_VALUE, new BigDecimal("50"))
						.setAttribute(ATTR_WEIGHT, 10)
						.upsertVia(session);

					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setReference(
							REF_PARAM_MULTI_HISTOGRAM, 1,
							whichIs -> whichIs.setGroup(ENTITY_PARAMETER, 10)
						)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();
					assertHistogramBucketContains(
						productCollection, REF_PARAM_MULTI_HISTOGRAM, 10,
						HISTOGRAM_HIST1, new BigDecimal("50"), 1
					);
					assertHistogramBucketContains(
						productCollection, REF_PARAM_MULTI_HISTOGRAM, 10,
						HISTOGRAM_HIST2, 10, 1
					);
				}
			);
		}

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should handle null value with default expression")
		void shouldHandleNullValueWithDefaultExpression(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineConditionalBucketSchema(session);

					session.createNewEntity(ENTITY_PARAMETER, 10)
						.setAttribute(ATTR_INPUT_WIDGET_TYPE, "INTERVAL")
						.upsertVia(session);

					// PV with null basicUnitValue
					session.createNewEntity(ENTITY_PARAMETER_VALUE, 1)
						.upsertVia(session);

					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setReference(
							REF_PARAM_BY_GROUP_ATTR_WITH_DEFAULT, 1,
							whichIs -> whichIs.setGroup(ENTITY_PARAMETER, 10)
						)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();
					// value expression has ?? 0, so null → default 0
					assertHistogramBucketContains(
						productCollection, REF_PARAM_BY_GROUP_ATTR_WITH_DEFAULT, 10,
						HISTOGRAM_VALUE, BigDecimal.ZERO, 1
					);
				}
			);
		}

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should skip null value without default expression")
		void shouldSkipNullValueWithoutDefaultExpression(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineConditionalBucketSchema(session);

					session.createNewEntity(ENTITY_PARAMETER, 10)
						.setAttribute(ATTR_INPUT_WIDGET_TYPE, "INTERVAL")
						.upsertVia(session);

					// PV with null basicUnitValue
					session.createNewEntity(ENTITY_PARAMETER_VALUE, 1)
						.upsertVia(session);

					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setReference(
							REF_PARAM_BY_GROUP_ATTR, 1,
							whichIs -> whichIs.setGroup(ENTITY_PARAMETER, 10)
						)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();
					// no default → no histogram entry
					assertHistogramNotIndexed(
						productCollection, REF_PARAM_BY_GROUP_ATTR, 10,
						HISTOGRAM_VALUE, 1
					);
				}
			);
		}

	}

	/**
	 * Tests verifying that histogram index state transitions correctly when the owner entity
	 * is mutated locally.
	 */
	@Nested
	@DisplayName("Local trigger — state transitions on owner entity mutations")
	class LocalTriggerTest {

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should toggle histogram on entity attribute change")
		void shouldToggleHistogramOnEntityAttributeChange(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineConditionalBucketSchema(session);

					session.createNewEntity(ENTITY_PARAMETER_VALUE, 1)
						.setAttribute(ATTR_BASIC_UNIT_VALUE, new BigDecimal("50"))
						.upsertVia(session);

					// start with isActive=false → not indexed
					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setAttribute(ATTR_IS_ACTIVE, false)
						.setReference(REF_PARAM_BY_ENTITY_ATTR, 1)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();
					assertUngroupedHistogramNotIndexed(
						productCollection, REF_PARAM_BY_ENTITY_ATTR,
						HISTOGRAM_ENTITY, 1
					);

					// mutate to true → should become indexed
					session.getEntity(ENTITY_PRODUCT, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setAttribute(ATTR_IS_ACTIVE, true)
						.upsertVia(session);

					assertUngroupedHistogramBucketContains(
						productCollection, REF_PARAM_BY_ENTITY_ATTR,
						HISTOGRAM_ENTITY, new BigDecimal("50"), 1
					);

					// mutate back to false → should be removed
					session.getEntity(ENTITY_PRODUCT, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setAttribute(ATTR_IS_ACTIVE, false)
						.upsertVia(session);

					assertUngroupedHistogramNotIndexed(
						productCollection, REF_PARAM_BY_ENTITY_ATTR,
						HISTOGRAM_ENTITY, 1
					);
				}
			);
		}

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should toggle histogram on reference attribute change (condition)")
		void shouldToggleHistogramOnReferenceAttributeChange(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineConditionalBucketSchema(session);

					session.createNewEntity(ENTITY_PARAMETER_VALUE, 1).upsertVia(session);

					// start with priority=-1 → not indexed
					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setReference(
							REF_PARAM_BY_REF_ATTR, 1,
							whichIs -> whichIs
								.setAttribute(ATTR_PRIORITY, -1)
								.setAttribute(ATTR_SOME_VALUE, new BigDecimal("100"))
						)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();
					assertUngroupedHistogramNotIndexed(
						productCollection, REF_PARAM_BY_REF_ATTR,
						HISTOGRAM_REF_ATTR, 1
					);

					// mutate priority to 5 → should become indexed
					session.getEntity(ENTITY_PRODUCT, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setOrUpdateReference(
							REF_PARAM_BY_REF_ATTR, 1,
							Functions.alwaysTrue(),
							whichIs -> whichIs.setAttribute(ATTR_PRIORITY, 5)
						)
						.upsertVia(session);

					assertUngroupedHistogramBucketContains(
						productCollection, REF_PARAM_BY_REF_ATTR,
						HISTOGRAM_REF_ATTR, new BigDecimal("100"), 1
					);

					// mutate back to -1 → should be removed
					session.getEntity(ENTITY_PRODUCT, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setOrUpdateReference(
							REF_PARAM_BY_REF_ATTR, 1,
							Functions.alwaysTrue(),
							whichIs -> whichIs.setAttribute(ATTR_PRIORITY, -1)
						)
						.upsertVia(session);

					assertUngroupedHistogramNotIndexed(
						productCollection, REF_PARAM_BY_REF_ATTR,
						HISTOGRAM_REF_ATTR, 1
					);
				}
			);
		}

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should update histogram bucket when reference attribute value changes")
		void shouldUpdateHistogramBucketWhenReferenceAttributeValueChanges(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineConditionalBucketSchema(session);

					session.createNewEntity(ENTITY_PARAMETER_VALUE, 1).upsertVia(session);

					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setReference(
							REF_PARAM_BY_REF_ATTR, 1,
							whichIs -> whichIs
								.setAttribute(ATTR_PRIORITY, 5)
								.setAttribute(ATTR_SOME_VALUE, new BigDecimal("100"))
						)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();
					assertUngroupedHistogramBucketContains(
						productCollection, REF_PARAM_BY_REF_ATTR,
						HISTOGRAM_REF_ATTR, new BigDecimal("100"), 1
					);

					// change value attribute from 100 → 200
					session.getEntity(ENTITY_PRODUCT, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setOrUpdateReference(
							REF_PARAM_BY_REF_ATTR, 1,
							Functions.alwaysTrue(),
							whichIs -> whichIs.setAttribute(ATTR_SOME_VALUE, new BigDecimal("200"))
						)
						.upsertVia(session);

					assertUngroupedHistogramBucketContains(
						productCollection, REF_PARAM_BY_REF_ATTR,
						HISTOGRAM_REF_ATTR, new BigDecimal("200"), 1
					);
					// old bucket should not contain the product anymore
					final EntityIndex typeIndex = IndexingTestSupport.getReferencedEntityTypeIndex(
						productCollection, Scope.LIVE, REF_PARAM_BY_REF_ATTR
					);
					assertNotNull(typeIndex);
					final FilterIndex filterIndex =
						((ReferencedTypeEntityIndex) typeIndex).getHistogramFilterIndex(HISTOGRAM_REF_ATTR, null);
					assertNotNull(filterIndex);
					assertFalse(
						filterIndex.getRecordsEqualTo(new BigDecimal("100")).contains(1),
						"Product should no longer be in old bucket 100"
					);
				}
			);
		}

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should toggle histogram on group assignment change")
		void shouldToggleHistogramOnGroupAssignmentChange(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineConditionalBucketSchema(session);

					session.createNewEntity(ENTITY_PARAMETER, 10)
						.setAttribute(ATTR_INPUT_WIDGET_TYPE, "INTERVAL")
						.upsertVia(session);

					session.createNewEntity(ENTITY_PARAMETER_VALUE, 1)
						.setAttribute(ATTR_BASIC_UNIT_VALUE, new BigDecimal("50"))
						.upsertVia(session);

					// product with no group → expression evaluates to false
					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setReference(REF_PARAM_BY_GROUP_ATTR, 1)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();
					// no group → should not be in any histogram
					assertHistogramNotIndexed(
						productCollection, REF_PARAM_BY_GROUP_ATTR, 10,
						HISTOGRAM_VALUE, 1
					);

					// assign group → should become indexed
					session.getEntity(ENTITY_PRODUCT, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setReference(
							REF_PARAM_BY_GROUP_ATTR, 1,
							whichIs -> whichIs.setGroup(ENTITY_PARAMETER, 10)
						)
						.upsertVia(session);

					assertHistogramBucketContains(
						productCollection, REF_PARAM_BY_GROUP_ATTR, 10,
						HISTOGRAM_VALUE, new BigDecimal("50"), 1
					);

					// remove group → should be removed
					session.getEntity(ENTITY_PRODUCT, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setReference(REF_PARAM_BY_GROUP_ATTR, 1)
						.upsertVia(session);

					assertHistogramNotIndexed(
						productCollection, REF_PARAM_BY_GROUP_ATTR, 10,
						HISTOGRAM_VALUE, 1
					);
				}
			);
		}

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should remove histogram on reference removal")
		void shouldRemoveHistogramOnReferenceRemoval(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineConditionalBucketSchema(session);

					session.createNewEntity(ENTITY_PARAMETER_VALUE, 1)
						.setAttribute(ATTR_BASIC_UNIT_VALUE, new BigDecimal("50"))
						.upsertVia(session);

					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setAttribute(ATTR_IS_ACTIVE, true)
						.setReference(REF_PARAM_BY_ENTITY_ATTR, 1)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();
					assertUngroupedHistogramBucketContains(
						productCollection, REF_PARAM_BY_ENTITY_ATTR,
						HISTOGRAM_ENTITY, new BigDecimal("50"), 1
					);

					// remove reference → histogram should be cleaned up
					session.getEntity(ENTITY_PRODUCT, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.removeReference(REF_PARAM_BY_ENTITY_ATTR, 1)
						.upsertVia(session);

					assertUngroupedHistogramNotIndexed(
						productCollection, REF_PARAM_BY_ENTITY_ATTR,
						HISTOGRAM_ENTITY, 1
					);
				}
			);
		}

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should not reevaluate when irrelevant attribute changes")
		void shouldNotReevaluateWhenIrrelevantAttributeChanges(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineConditionalBucketSchema(session);

					session.createNewEntity(ENTITY_PARAMETER_VALUE, 1)
						.setAttribute(ATTR_BASIC_UNIT_VALUE, new BigDecimal("50"))
						.upsertVia(session);

					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setAttribute(ATTR_IS_ACTIVE, true)
						.setAttribute(ATTR_CODE, "ABC")
						.setReference(REF_PARAM_BY_ENTITY_ATTR, 1)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();
					assertUngroupedHistogramBucketContains(
						productCollection, REF_PARAM_BY_ENTITY_ATTR,
						HISTOGRAM_ENTITY, new BigDecimal("50"), 1
					);

					// change irrelevant attribute → should still be indexed
					session.getEntity(ENTITY_PRODUCT, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setAttribute(ATTR_CODE, "XYZ")
						.upsertVia(session);

					assertUngroupedHistogramBucketContains(
						productCollection, REF_PARAM_BY_ENTITY_ATTR,
						HISTOGRAM_ENTITY, new BigDecimal("50"), 1
					);
				}
			);
		}

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should handle cardinality tracking for duplicate values across references")
		void shouldHandleCardinalityTrackingForDuplicateValuesAcrossReferences(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineConditionalBucketSchema(session);

					// both PVs have same basicUnitValue
					session.createNewEntity(ENTITY_PARAMETER_VALUE, 1)
						.setAttribute(ATTR_BASIC_UNIT_VALUE, new BigDecimal("50"))
						.upsertVia(session);
					session.createNewEntity(ENTITY_PARAMETER_VALUE, 2)
						.setAttribute(ATTR_BASIC_UNIT_VALUE, new BigDecimal("50"))
						.upsertVia(session);

					// Product refs both PVs via ungrouped REF_PARAM_BY_ENTITY_ATTR
					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setAttribute(ATTR_IS_ACTIVE, true)
						.setReference(REF_PARAM_BY_ENTITY_ATTR, 1)
						.setReference(REF_PARAM_BY_ENTITY_ATTR, 2)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();
					// both references contribute value 50 → product in bucket 50
					assertUngroupedHistogramBucketContains(
						productCollection, REF_PARAM_BY_ENTITY_ATTR,
						HISTOGRAM_ENTITY, new BigDecimal("50"), 1
					);

					// remove one reference → one contributing reference remains → still in bucket
					session.getEntity(ENTITY_PRODUCT, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.removeReference(REF_PARAM_BY_ENTITY_ATTR, 1)
						.upsertVia(session);

					assertUngroupedHistogramBucketContains(
						productCollection, REF_PARAM_BY_ENTITY_ATTR,
						HISTOGRAM_ENTITY, new BigDecimal("50"), 1
					);

					// remove second reference → no contributing references → removed from bucket
					session.getEntity(ENTITY_PRODUCT, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.removeReference(REF_PARAM_BY_ENTITY_ATTR, 2)
						.upsertVia(session);

					assertUngroupedHistogramNotIndexed(
						productCollection, REF_PARAM_BY_ENTITY_ATTR,
						HISTOGRAM_ENTITY, 1
					);
				}
			);
		}

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should keep qualifying contribution when non-qualifying sibling reference is removed")
		void shouldKeepQualifyingContributionWhenNonQualifyingSiblingReferenceIsRemoved(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineConditionalBucketSchema(session);

					// both parameter values carry the SAME histogram value, but only PV#1 qualifies -
					// PV#2 never contributes anything to the histogram
					session.createNewEntity(ENTITY_PARAMETER_VALUE, 1)
						.setAttribute(ATTR_STATUS, "ACTIVE")
						.setAttribute(ATTR_BASIC_UNIT_VALUE, new BigDecimal("50"))
						.upsertVia(session);
					session.createNewEntity(ENTITY_PARAMETER_VALUE, 2)
						.setAttribute(ATTR_STATUS, "INACTIVE")
						.setAttribute(ATTR_BASIC_UNIT_VALUE, new BigDecimal("50"))
						.upsertVia(session);

					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setReference(REF_PARAM_BY_REF_ENTITY_ATTR, 1)
						.setReference(REF_PARAM_BY_REF_ENTITY_ATTR, 2)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();
					assertUngroupedHistogramBucketContains(
						productCollection, REF_PARAM_BY_REF_ENTITY_ATTR,
						HISTOGRAM_STATUS, new BigDecimal("50"), 1
					);

					// dropping the reference that never contributed must not evict PV#1's contribution
					session.getEntity(ENTITY_PRODUCT, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.removeReference(REF_PARAM_BY_REF_ENTITY_ATTR, 2)
						.upsertVia(session);

					assertUngroupedHistogramBucketContains(
						productCollection, REF_PARAM_BY_REF_ENTITY_ATTR,
						HISTOGRAM_STATUS, new BigDecimal("50"), 1
					);
				}
			);
		}
	}

	/**
	 * Tests verifying cross-entity trigger propagation when the referenced entity is mutated.
	 */
	@Nested
	@DisplayName("Cross-entity triggers — referenced entity mutations")
	class CrossEntityReferencedEntityTriggerTest {

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should drop contributions in every owner collection sharing a reference name")
		void shouldDropContributionsInEveryOwnerCollectionSharingReferenceName(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineConditionalBucketSchema(session);
					// a second owner type whose trigger shares the reference name, dependency type and scope
					// with product's — the pair a ReevaluateExpressionMutation's identity cannot tell apart,
					// so the pre-mutation condition state has to be keyed by target collection as well
					session.defineEntitySchema(ENTITY_BUNDLE)
						.withReferenceToEntity(
							REF_PARAM_BY_REF_ENTITY_ATTR, ENTITY_PARAMETER_VALUE, Cardinality.ZERO_OR_MORE,
							whichIs -> whichIs
								.indexedForFilteringAndPartitioning()
								.bucketed(
									HISTOGRAM_STATUS,
									ExpressionFactory.parse(
										"$reference.referencedEntity?.attributes['basicUnitValue']"
									)
								)
								.bucketedPartially(
									ExpressionFactory.parse(
										"($reference.referencedEntity.attributes['status'] ?? '') == 'ACTIVE'"
									)
								)
						)
						.updateVia(session);

					session.createNewEntity(ENTITY_PARAMETER_VALUE, 1)
						.setAttribute(ATTR_STATUS, "ACTIVE")
						.setAttribute(ATTR_BASIC_UNIT_VALUE, new BigDecimal("50"))
						.upsertVia(session);

					// deliberately disjoint owner PKs: a set captured against one collection must not be able
					// to cover the other collection's owner by coincidence
					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setReference(REF_PARAM_BY_REF_ENTITY_ATTR, 1)
						.upsertVia(session);
					session.createNewEntity(ENTITY_BUNDLE, 7)
						.setReference(REF_PARAM_BY_REF_ENTITY_ATTR, 1)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();
					final EntityCollectionContract bundleCollection = getCollection(ENTITY_BUNDLE);
					assertUngroupedHistogramBucketContains(
						productCollection, REF_PARAM_BY_REF_ENTITY_ATTR,
						HISTOGRAM_STATUS, new BigDecimal("50"), 1
					);
					assertUngroupedHistogramBucketContains(
						bundleCollection, REF_PARAM_BY_REF_ENTITY_ATTR,
						HISTOGRAM_STATUS, new BigDecimal("50"), 7
					);

					// a single mutation of the referenced entity fans out to both owner collections
					session.getEntity(ENTITY_PARAMETER_VALUE, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setAttribute(ATTR_STATUS, "INACTIVE")
						.upsertVia(session);

					// neither collection may keep a stale contribution
					assertUngroupedHistogramNotIndexed(
						productCollection, REF_PARAM_BY_REF_ENTITY_ATTR, HISTOGRAM_STATUS, 1
					);
					assertUngroupedHistogramNotIndexed(
						bundleCollection, REF_PARAM_BY_REF_ENTITY_ATTR, HISTOGRAM_STATUS, 7
					);
				}
			);
		}

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should toggle histogram when referenced entity attribute changes (condition)")
		void shouldToggleHistogramWhenReferencedEntityAttributeChangesCondition(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineConditionalBucketSchema(session);

					session.createNewEntity(ENTITY_PARAMETER_VALUE, 1)
						.setAttribute(ATTR_STATUS, "ACTIVE")
						.setAttribute(ATTR_BASIC_UNIT_VALUE, new BigDecimal("50"))
						.upsertVia(session);

					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setReference(REF_PARAM_BY_REF_ENTITY_ATTR, 1)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();
					assertUngroupedHistogramBucketContains(
						productCollection, REF_PARAM_BY_REF_ENTITY_ATTR,
						HISTOGRAM_STATUS, new BigDecimal("50"), 1
					);

					// change status to INACTIVE → condition false
					session.getEntity(ENTITY_PARAMETER_VALUE, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setAttribute(ATTR_STATUS, "INACTIVE")
						.upsertVia(session);

					assertUngroupedHistogramNotIndexed(
						productCollection, REF_PARAM_BY_REF_ENTITY_ATTR,
						HISTOGRAM_STATUS, 1
					);

					// restore to ACTIVE
					session.getEntity(ENTITY_PARAMETER_VALUE, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setAttribute(ATTR_STATUS, "ACTIVE")
						.upsertVia(session);

					assertUngroupedHistogramBucketContains(
						productCollection, REF_PARAM_BY_REF_ENTITY_ATTR,
						HISTOGRAM_STATUS, new BigDecimal("50"), 1
					);
				}
			);
		}

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should keep sibling contribution when a second reference starts qualifying for the same bucket")
		void shouldKeepSiblingContributionWhenSecondReferenceStartsQualifyingForSameBucket(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineConditionalBucketSchema(session);

					// both parameter values carry the SAME histogram value, so both land in one bucket;
					// only the first one qualifies initially
					session.createNewEntity(ENTITY_PARAMETER_VALUE, 1)
						.setAttribute(ATTR_STATUS, "ACTIVE")
						.setAttribute(ATTR_BASIC_UNIT_VALUE, new BigDecimal("50"))
						.upsertVia(session);
					session.createNewEntity(ENTITY_PARAMETER_VALUE, 2)
						.setAttribute(ATTR_STATUS, "INACTIVE")
						.setAttribute(ATTR_BASIC_UNIT_VALUE, new BigDecimal("50"))
						.upsertVia(session);

					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setReference(REF_PARAM_BY_REF_ENTITY_ATTR, 1)
						.setReference(REF_PARAM_BY_REF_ENTITY_ATTR, 2)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();
					// only PV#1 qualifies - bucket 50 holds a single contribution
					assertUngroupedHistogramBucketContains(
						productCollection, REF_PARAM_BY_REF_ENTITY_ATTR,
						HISTOGRAM_STATUS, new BigDecimal("50"), 1
					);

					// PV#2 starts qualifying for the very same bucket - the bucket must now hold two
					// contributions even though the visible membership bitmap cannot show that
					session.getEntity(ENTITY_PARAMETER_VALUE, 2, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setAttribute(ATTR_STATUS, "ACTIVE")
						.upsertVia(session);

					assertUngroupedHistogramBucketContains(
						productCollection, REF_PARAM_BY_REF_ENTITY_ATTR,
						HISTOGRAM_STATUS, new BigDecimal("50"), 1
					);

					// PV#1 stops qualifying - PV#2 still does, so the product must stay in bucket 50
					session.getEntity(ENTITY_PARAMETER_VALUE, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setAttribute(ATTR_STATUS, "INACTIVE")
						.upsertVia(session);

					assertUngroupedHistogramBucketContains(
						productCollection, REF_PARAM_BY_REF_ENTITY_ATTR,
						HISTOGRAM_STATUS, new BigDecimal("50"), 1
					);
				}
			);
		}

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should keep sibling contribution when a non-qualifying reference changes its value")
		void shouldKeepSiblingContributionWhenNonQualifyingReferenceChangesItsValue(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineConditionalBucketSchema(session);

					// PV#1 qualifies for bucket 50; PV#2 shares that value but never qualifies
					session.createNewEntity(ENTITY_PARAMETER_VALUE, 1)
						.setAttribute(ATTR_STATUS, "ACTIVE")
						.setAttribute(ATTR_BASIC_UNIT_VALUE, new BigDecimal("50"))
						.upsertVia(session);
					session.createNewEntity(ENTITY_PARAMETER_VALUE, 2)
						.setAttribute(ATTR_STATUS, "INACTIVE")
						.setAttribute(ATTR_BASIC_UNIT_VALUE, new BigDecimal("50"))
						.upsertVia(session);

					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setReference(REF_PARAM_BY_REF_ENTITY_ATTR, 1)
						.setReference(REF_PARAM_BY_REF_ENTITY_ATTR, 2)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();
					assertUngroupedHistogramBucketContains(
						productCollection, REF_PARAM_BY_REF_ENTITY_ATTR,
						HISTOGRAM_STATUS, new BigDecimal("50"), 1
					);

					// PV#2's value moves off the shared bucket; since PV#2 never contributed there,
					// PV#1's contribution to bucket 50 must survive untouched
					session.getEntity(ENTITY_PARAMETER_VALUE, 2, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setAttribute(ATTR_BASIC_UNIT_VALUE, new BigDecimal("60"))
						.upsertVia(session);

					assertUngroupedHistogramBucketContains(
						productCollection, REF_PARAM_BY_REF_ENTITY_ATTR,
						HISTOGRAM_STATUS, new BigDecimal("50"), 1
					);
				}
			);
		}

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should update histogram when referenced entity attribute changes (value)")
		void shouldUpdateHistogramWhenReferencedEntityAttributeChangesValue(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineConditionalBucketSchema(session);

					session.createNewEntity(ENTITY_PARAMETER, 10)
						.setAttribute(ATTR_INPUT_WIDGET_TYPE, "INTERVAL")
						.upsertVia(session);

					session.createNewEntity(ENTITY_PARAMETER_VALUE, 1)
						.setAttribute(ATTR_BASIC_UNIT_VALUE, new BigDecimal("50"))
						.upsertVia(session);

					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setReference(
							REF_PARAM_BY_GROUP_ATTR, 1,
							whichIs -> whichIs.setGroup(ENTITY_PARAMETER, 10)
						)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();
					assertHistogramBucketContains(
						productCollection, REF_PARAM_BY_GROUP_ATTR, 10,
						HISTOGRAM_VALUE, new BigDecimal("50"), 1
					);

					// change value: 50 → 75
					session.getEntity(ENTITY_PARAMETER_VALUE, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setAttribute(ATTR_BASIC_UNIT_VALUE, new BigDecimal("75"))
						.upsertVia(session);

					assertHistogramBucketContains(
						productCollection, REF_PARAM_BY_GROUP_ATTR, 10,
						HISTOGRAM_VALUE, new BigDecimal("75"), 1
					);
					assertHistogramBucketNotContains(
						productCollection, REF_PARAM_BY_GROUP_ATTR, 10,
						HISTOGRAM_VALUE, new BigDecimal("50"), 1
					);
				}
			);
		}

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should remove histogram when referenced entity is removed")
		void shouldRemoveHistogramWhenReferencedEntityIsRemoved(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineConditionalBucketSchema(session);

					session.createNewEntity(ENTITY_PARAMETER_VALUE, 1)
						.setAttribute(ATTR_STATUS, "ACTIVE")
						.setAttribute(ATTR_BASIC_UNIT_VALUE, new BigDecimal("50"))
						.upsertVia(session);

					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setReference(REF_PARAM_BY_REF_ENTITY_ATTR, 1)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();
					assertUngroupedHistogramBucketContains(
						productCollection, REF_PARAM_BY_REF_ENTITY_ATTR,
						HISTOGRAM_STATUS, new BigDecimal("50"), 1
					);

					// delete the referenced entity
					session.deleteEntity(ENTITY_PARAMETER_VALUE, 1);

					assertUngroupedHistogramNotIndexed(
						productCollection, REF_PARAM_BY_REF_ENTITY_ATTR,
						HISTOGRAM_STATUS, 1
					);
				}
			);
		}

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should index histogram when referenced entity inserted later")
		void shouldIndexHistogramWhenReferencedEntityInsertedLater(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineConditionalBucketSchema(session);

					// product referencing non-existent PV#1
					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setReference(REF_PARAM_BY_REF_ENTITY_ATTR, 1)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();
					// PV absent → condition false (null-safe coalesce)
					assertUngroupedHistogramNotIndexed(
						productCollection, REF_PARAM_BY_REF_ENTITY_ATTR,
						HISTOGRAM_STATUS, 1
					);

					// late arrival: create PV#1 with matching attributes
					session.createNewEntity(ENTITY_PARAMETER_VALUE, 1)
						.setAttribute(ATTR_STATUS, "ACTIVE")
						.setAttribute(ATTR_BASIC_UNIT_VALUE, new BigDecimal("50"))
						.upsertVia(session);

					// cross-entity trigger fires → histogram populated
					assertUngroupedHistogramBucketContains(
						productCollection, REF_PARAM_BY_REF_ENTITY_ATTR,
						HISTOGRAM_STATUS, new BigDecimal("50"), 1
					);
				}
			);
		}

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should update multiple histograms when referenced entity value changes")
		void shouldUpdateMultipleHistogramsWhenReferencedEntityValueChanges(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineConditionalBucketSchema(session);

					session.createNewEntity(ENTITY_PARAMETER, 10)
						.setAttribute(ATTR_INPUT_WIDGET_TYPE, "INTERVAL")
						.upsertVia(session);

					session.createNewEntity(ENTITY_PARAMETER_VALUE, 1)
						.setAttribute(ATTR_BASIC_UNIT_VALUE, new BigDecimal("50"))
						.setAttribute(ATTR_WEIGHT, 10)
						.upsertVia(session);

					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setReference(
							REF_PARAM_MULTI_HISTOGRAM, 1,
							whichIs -> whichIs.setGroup(ENTITY_PARAMETER, 10)
						)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();
					assertHistogramBucketContains(
						productCollection, REF_PARAM_MULTI_HISTOGRAM, 10,
						HISTOGRAM_HIST1, new BigDecimal("50"), 1
					);
					assertHistogramBucketContains(
						productCollection, REF_PARAM_MULTI_HISTOGRAM, 10,
						HISTOGRAM_HIST2, 10, 1
					);

					// change basicUnitValue (affects hist1 only)
					session.getEntity(ENTITY_PARAMETER_VALUE, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setAttribute(ATTR_BASIC_UNIT_VALUE, new BigDecimal("75"))
						.upsertVia(session);

					assertHistogramBucketContains(
						productCollection, REF_PARAM_MULTI_HISTOGRAM, 10,
						HISTOGRAM_HIST1, new BigDecimal("75"), 1
					);
					// hist2 should remain unchanged
					assertHistogramBucketContains(
						productCollection, REF_PARAM_MULTI_HISTOGRAM, 10,
						HISTOGRAM_HIST2, 10, 1
					);
				}
			);
		}
	}

	/**
	 * Tests verifying cross-entity trigger propagation when the group entity is mutated.
	 */
	@Nested
	@DisplayName("Cross-entity triggers — group entity mutations")
	class CrossEntityGroupEntityTriggerTest {

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should cascade to all owner entities when group entity attribute changes")
		void shouldCascadeToAllOwnerEntitiesWhenGroupEntityAttributeChanges(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineConditionalBucketSchema(session);

					session.createNewEntity(ENTITY_PARAMETER, 10)
						.setAttribute(ATTR_INPUT_WIDGET_TYPE, "INTERVAL")
						.upsertVia(session);

					session.createNewEntity(ENTITY_PARAMETER_VALUE, 1)
						.setAttribute(ATTR_BASIC_UNIT_VALUE, new BigDecimal("50"))
						.upsertVia(session);

					// three products referencing same PV via same group
					for (int i = 1; i <= 3; i++) {
						session.createNewEntity(ENTITY_PRODUCT, i)
							.setReference(
								REF_PARAM_BY_GROUP_ATTR, 1,
								whichIs -> whichIs.setGroup(ENTITY_PARAMETER, 10)
							)
							.upsertVia(session);
					}
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();
					for (int i = 1; i <= 3; i++) {
						assertHistogramBucketContains(
							productCollection, REF_PARAM_BY_GROUP_ATTR, 10,
							HISTOGRAM_VALUE, new BigDecimal("50"), i
						);
					}

					// change group attribute to non-matching
					session.getEntity(ENTITY_PARAMETER, 10, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setAttribute(ATTR_INPUT_WIDGET_TYPE, "CHECKBOX")
						.upsertVia(session);

					for (int i = 1; i <= 3; i++) {
						assertHistogramNotIndexed(
							productCollection, REF_PARAM_BY_GROUP_ATTR, 10,
							HISTOGRAM_VALUE, i
						);
					}

					// restore to INTERVAL
					session.getEntity(ENTITY_PARAMETER, 10, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setAttribute(ATTR_INPUT_WIDGET_TYPE, "INTERVAL")
						.upsertVia(session);

					for (int i = 1; i <= 3; i++) {
						assertHistogramBucketContains(
							productCollection, REF_PARAM_BY_GROUP_ATTR, 10,
							HISTOGRAM_VALUE, new BigDecimal("50"), i
						);
					}
				}
			);
		}

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should remove histogram when group entity is removed")
		void shouldRemoveHistogramWhenGroupEntityIsRemoved(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineConditionalBucketSchema(session);

					session.createNewEntity(ENTITY_PARAMETER, 10)
						.setAttribute(ATTR_INPUT_WIDGET_TYPE, "INTERVAL")
						.upsertVia(session);

					session.createNewEntity(ENTITY_PARAMETER_VALUE, 1)
						.setAttribute(ATTR_BASIC_UNIT_VALUE, new BigDecimal("50"))
						.upsertVia(session);

					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setReference(
							REF_PARAM_BY_GROUP_ATTR, 1,
							whichIs -> whichIs.setGroup(ENTITY_PARAMETER, 10)
						)
						.upsertVia(session);
					session.createNewEntity(ENTITY_PRODUCT, 2)
						.setReference(
							REF_PARAM_BY_GROUP_ATTR, 1,
							whichIs -> whichIs.setGroup(ENTITY_PARAMETER, 10)
						)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();
					assertHistogramBucketContains(
						productCollection, REF_PARAM_BY_GROUP_ATTR, 10,
						HISTOGRAM_VALUE, new BigDecimal("50"), 1
					);
					assertHistogramBucketContains(
						productCollection, REF_PARAM_BY_GROUP_ATTR, 10,
						HISTOGRAM_VALUE, new BigDecimal("50"), 2
					);

					// delete the group entity
					session.deleteEntity(ENTITY_PARAMETER, 10);

					assertHistogramNotIndexed(
						productCollection, REF_PARAM_BY_GROUP_ATTR, 10,
						HISTOGRAM_VALUE, 1
					);
					assertHistogramNotIndexed(
						productCollection, REF_PARAM_BY_GROUP_ATTR, 10,
						HISTOGRAM_VALUE, 2
					);
				}
			);
		}

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should index histogram when group entity is inserted after referencing entity")
		void shouldIndexHistogramWhenGroupEntityIsInsertedAfterReferencingEntity(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineConditionalBucketSchema(session);

					session.createNewEntity(ENTITY_PARAMETER_VALUE, 1)
						.setAttribute(ATTR_BASIC_UNIT_VALUE, new BigDecimal("50"))
						.upsertVia(session);

					// product references PV#1 via non-existent group
					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setReference(
							REF_PARAM_BY_GROUP_ATTR, 1,
							whichIs -> whichIs.setGroup(ENTITY_PARAMETER, 10)
						)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();
					assertHistogramNotIndexed(
						productCollection, REF_PARAM_BY_GROUP_ATTR, 10,
						HISTOGRAM_VALUE, 1
					);

					// late arrival: create group entity with matching attribute
					session.createNewEntity(ENTITY_PARAMETER, 10)
						.setAttribute(ATTR_INPUT_WIDGET_TYPE, "INTERVAL")
						.upsertVia(session);

					assertHistogramBucketContains(
						productCollection, REF_PARAM_BY_GROUP_ATTR, 10,
						HISTOGRAM_VALUE, new BigDecimal("50"), 1
					);
				}
			);
		}
	}

	/**
	 * Tests verifying dual facet + histogram on the same reference with mutually exclusive conditions.
	 */
	@Nested
	@DisplayName("Dual facet and histogram — mutually exclusive conditions")
	class DualFacetAndHistogramTest {

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should index facet and histogram in mutually exclusive groups")
		void shouldIndexFacetAndHistogramInMutuallyExclusiveGroups(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineConditionalBucketSchema(session);

					session.createNewEntity(ENTITY_PARAMETER, 10)
						.setAttribute(ATTR_INPUT_WIDGET_TYPE, "CHECKBOX")
						.upsertVia(session);
					session.createNewEntity(ENTITY_PARAMETER, 20)
						.setAttribute(ATTR_INPUT_WIDGET_TYPE, "INTERVAL")
						.upsertVia(session);

					session.createNewEntity(ENTITY_PARAMETER_VALUE, 1)
						.setAttribute(ATTR_BASIC_UNIT_VALUE, new BigDecimal("50"))
						.upsertVia(session);

					// Product#1: CHECKBOX group → faceted, NOT bucketed
					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setReference(
							REF_PARAM_DUAL_FACET_HISTOGRAM, 1,
							whichIs -> whichIs.setGroup(ENTITY_PARAMETER, 10)
						)
						.upsertVia(session);

					// Product#2: INTERVAL group → NOT faceted, bucketed
					session.createNewEntity(ENTITY_PRODUCT, 2)
						.setReference(
							REF_PARAM_DUAL_FACET_HISTOGRAM, 1,
							whichIs -> whichIs.setGroup(ENTITY_PARAMETER, 20)
						)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();

					// Product#1: facet indexed
					assertFacetIndexed(
						productCollection, REF_PARAM_DUAL_FACET_HISTOGRAM, 1, 10, 1
					);
					// Product#1: NOT in histogram
					assertHistogramNotIndexed(
						productCollection, REF_PARAM_DUAL_FACET_HISTOGRAM, 10,
						HISTOGRAM_DUAL, 1
					);

					// Product#2: NOT faceted
					assertFacetNotIndexed(
						productCollection, REF_PARAM_DUAL_FACET_HISTOGRAM, 1, 20, 2
					);
					// Product#2: in histogram bucket 50
					assertHistogramBucketContains(
						productCollection, REF_PARAM_DUAL_FACET_HISTOGRAM, 20,
						HISTOGRAM_DUAL, new BigDecimal("50"), 2
					);
				}
			);
		}

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should swap facet and histogram when group attribute changes")
		void shouldSwapFacetAndHistogramWhenGroupAttributeChanges(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineConditionalBucketSchema(session);

					session.createNewEntity(ENTITY_PARAMETER, 10)
						.setAttribute(ATTR_INPUT_WIDGET_TYPE, "CHECKBOX")
						.upsertVia(session);

					session.createNewEntity(ENTITY_PARAMETER_VALUE, 1)
						.setAttribute(ATTR_BASIC_UNIT_VALUE, new BigDecimal("50"))
						.upsertVia(session);

					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setReference(
							REF_PARAM_DUAL_FACET_HISTOGRAM, 1,
							whichIs -> whichIs.setGroup(ENTITY_PARAMETER, 10)
						)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();

					// initially CHECKBOX → faceted, not bucketed
					assertFacetIndexed(
						productCollection, REF_PARAM_DUAL_FACET_HISTOGRAM, 1, 10, 1
					);
					assertHistogramNotIndexed(
						productCollection, REF_PARAM_DUAL_FACET_HISTOGRAM, 10,
						HISTOGRAM_DUAL, 1
					);

					// switch to INTERVAL → facet removed, histogram added
					session.getEntity(ENTITY_PARAMETER, 10, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setAttribute(ATTR_INPUT_WIDGET_TYPE, "INTERVAL")
						.upsertVia(session);

					assertFacetNotIndexed(
						productCollection, REF_PARAM_DUAL_FACET_HISTOGRAM, 1, 10, 1
					);
					assertHistogramBucketContains(
						productCollection, REF_PARAM_DUAL_FACET_HISTOGRAM, 10,
						HISTOGRAM_DUAL, new BigDecimal("50"), 1
					);

					// switch back to CHECKBOX → facet restored, histogram removed
					session.getEntity(ENTITY_PARAMETER, 10, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setAttribute(ATTR_INPUT_WIDGET_TYPE, "CHECKBOX")
						.upsertVia(session);

					assertFacetIndexed(
						productCollection, REF_PARAM_DUAL_FACET_HISTOGRAM, 1, 10, 1
					);
					assertHistogramNotIndexed(
						productCollection, REF_PARAM_DUAL_FACET_HISTOGRAM, 10,
						HISTOGRAM_DUAL, 1
					);
				}
			);
		}
	}

	/**
	 * Tests verifying compound expressions, null-safe access, and no-op suppression.
	 */
	@Nested
	@DisplayName("Mixed and cross-cutting expression tests")
	class MixedAndCrossCuttingTest {

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should evaluate mixed expression combining group and entity attributes")
		void shouldEvaluateMixedExpressionCombiningGroupAndEntityAttributes(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineConditionalBucketSchema(session);

					session.createNewEntity(ENTITY_PARAMETER, 10)
						.setAttribute(ATTR_INPUT_WIDGET_TYPE, "INTERVAL")
						.upsertVia(session);

					session.createNewEntity(ENTITY_PARAMETER_VALUE, 1)
						.setAttribute(ATTR_BASIC_UNIT_VALUE, new BigDecimal("50"))
						.upsertVia(session);

					// both conditions hold → TRUE
					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setAttribute(ATTR_IS_ACTIVE, true)
						.setReference(
							REF_PARAM_BY_MIXED_AND, 1,
							whichIs -> whichIs.setGroup(ENTITY_PARAMETER, 10)
						)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();
					assertHistogramBucketContains(
						productCollection, REF_PARAM_BY_MIXED_AND, 10,
						HISTOGRAM_MIXED, new BigDecimal("50"), 1
					);

					// toggle entity attribute to false → local part breaks
					session.getEntity(ENTITY_PRODUCT, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setAttribute(ATTR_IS_ACTIVE, false)
						.upsertVia(session);

					assertHistogramNotIndexed(
						productCollection, REF_PARAM_BY_MIXED_AND, 10,
						HISTOGRAM_MIXED, 1
					);

					// restore entity attribute, break group attribute
					session.getEntity(ENTITY_PRODUCT, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setAttribute(ATTR_IS_ACTIVE, true)
						.upsertVia(session);

					session.getEntity(ENTITY_PARAMETER, 10, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setAttribute(ATTR_INPUT_WIDGET_TYPE, "CHECKBOX")
						.upsertVia(session);

					assertHistogramNotIndexed(
						productCollection, REF_PARAM_BY_MIXED_AND, 10,
						HISTOGRAM_MIXED, 1
					);

					// restore group attribute → both hold again
					session.getEntity(ENTITY_PARAMETER, 10, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setAttribute(ATTR_INPUT_WIDGET_TYPE, "INTERVAL")
						.upsertVia(session);

					assertHistogramBucketContains(
						productCollection, REF_PARAM_BY_MIXED_AND, 10,
						HISTOGRAM_MIXED, new BigDecimal("50"), 1
					);
				}
			);
		}

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should evaluate OR expression across multiple cross-entity sources")
		void shouldEvaluateOrExpressionAcrossMultipleCrossEntitySources(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineConditionalBucketSchema(session);

					session.createNewEntity(ENTITY_PARAMETER, 10)
						.setAttribute(ATTR_INPUT_WIDGET_TYPE, "INTERVAL")
						.upsertVia(session);
					session.createNewEntity(ENTITY_PARAMETER_VALUE, 1)
						.setAttribute(ATTR_STATUS, "ACTIVE")
						.setAttribute(ATTR_BASIC_UNIT_VALUE, new BigDecimal("50"))
						.upsertVia(session);

					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setReference(
							REF_PARAM_BY_MULTI_SOURCE_OR, 1,
							whichIs -> whichIs.setGroup(ENTITY_PARAMETER, 10)
						)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();
					// both branches true
					assertHistogramBucketContains(
						productCollection, REF_PARAM_BY_MULTI_SOURCE_OR, 10,
						HISTOGRAM_OR, new BigDecimal("50"), 1
					);

					// break group branch only → still true (OR)
					session.getEntity(ENTITY_PARAMETER, 10, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setAttribute(ATTR_INPUT_WIDGET_TYPE, "CHECKBOX")
						.upsertVia(session);

					assertHistogramBucketContains(
						productCollection, REF_PARAM_BY_MULTI_SOURCE_OR, 10,
						HISTOGRAM_OR, new BigDecimal("50"), 1
					);

					// also break referenced entity branch → both false
					session.getEntity(ENTITY_PARAMETER_VALUE, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setAttribute(ATTR_STATUS, "INACTIVE")
						.upsertVia(session);

					assertHistogramNotIndexed(
						productCollection, REF_PARAM_BY_MULTI_SOURCE_OR, 10,
						HISTOGRAM_OR, 1
					);

					// restore one branch → true again
					session.getEntity(ENTITY_PARAMETER_VALUE, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setAttribute(ATTR_STATUS, "ACTIVE")
						.upsertVia(session);

					assertHistogramBucketContains(
						productCollection, REF_PARAM_BY_MULTI_SOURCE_OR, 10,
						HISTOGRAM_OR, new BigDecimal("50"), 1
					);
				}
			);
		}

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should handle null-safe group entity access")
		void shouldHandleNullSafeGroupEntityAccess(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineConditionalBucketSchema(session);

					session.createNewEntity(ENTITY_PARAMETER_VALUE, 1)
						.setAttribute(ATTR_BASIC_UNIT_VALUE, new BigDecimal("50"))
						.upsertVia(session);

					// reference with non-existent group → null-safe returns null → false
					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setReference(
							REF_PARAM_BY_GROUP_ATTR, 1,
							whichIs -> whichIs.setGroup(ENTITY_PARAMETER, 10)
						)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();
					assertHistogramNotIndexed(
						productCollection, REF_PARAM_BY_GROUP_ATTR, 10,
						HISTOGRAM_VALUE, 1
					);

					// create the group entity → should now be true
					session.createNewEntity(ENTITY_PARAMETER, 10)
						.setAttribute(ATTR_INPUT_WIDGET_TYPE, "INTERVAL")
						.upsertVia(session);

					assertHistogramBucketContains(
						productCollection, REF_PARAM_BY_GROUP_ATTR, 10,
						HISTOGRAM_VALUE, new BigDecimal("50"), 1
					);

					// delete the group entity → should be false again
					session.deleteEntity(ENTITY_PARAMETER, 10);

					assertHistogramNotIndexed(
						productCollection, REF_PARAM_BY_GROUP_ATTR, 10,
						HISTOGRAM_VALUE, 1
					);
				}
			);
		}

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should not trigger reevaluation when attribute value does not actually change")
		void shouldNotTriggerReevaluationWhenAttributeValueDoesNotActuallyChange(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineConditionalBucketSchema(session);

					session.createNewEntity(ENTITY_PARAMETER, 10)
						.setAttribute(ATTR_INPUT_WIDGET_TYPE, "INTERVAL")
						.upsertVia(session);

					session.createNewEntity(ENTITY_PARAMETER_VALUE, 1)
						.setAttribute(ATTR_BASIC_UNIT_VALUE, new BigDecimal("50"))
						.upsertVia(session);

					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setReference(
							REF_PARAM_BY_GROUP_ATTR, 1,
							whichIs -> whichIs.setGroup(ENTITY_PARAMETER, 10)
						)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();
					assertHistogramBucketContains(
						productCollection, REF_PARAM_BY_GROUP_ATTR, 10,
						HISTOGRAM_VALUE, new BigDecimal("50"), 1
					);

					// no-change mutation: set the same value
					session.getEntity(ENTITY_PARAMETER, 10, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setAttribute(ATTR_INPUT_WIDGET_TYPE, "INTERVAL")
						.upsertVia(session);

					// should still be indexed — no spurious state change
					assertHistogramBucketContains(
						productCollection, REF_PARAM_BY_GROUP_ATTR, 10,
						HISTOGRAM_VALUE, new BigDecimal("50"), 1
					);
				}
			);
		}
	}

	/**
	 * Tests verifying locale-aware histogram indexing where the value expression references
	 * a localized attribute on the referenced entity. Each locale should produce its own
	 * independent histogram FilterIndex.
	 */
	@Nested
	@DisplayName("Localized histogram — per-locale FilterIndex maintenance")
	class LocalizedHistogramTest {

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should index localized histogram per locale")
		void shouldIndexLocalizedHistogramPerLocale(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineConditionalBucketSchema(session);

					// PV#1: localized weight in both en and cs, condition-satisfying status
					session.createNewEntity(ENTITY_PARAMETER_VALUE, 1)
						.setAttribute(ATTR_STATUS, "ACTIVE")
						.setAttribute(ATTR_LOCALIZED_WEIGHT, Locale.ENGLISH, new BigDecimal("17.64"))
						.setAttribute(ATTR_LOCALIZED_WEIGHT, new Locale("cs"), new BigDecimal("500"))
						.upsertVia(session);

					// Product#1 references PV#1
					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setReference(REF_PARAM_BY_LOCALIZED_ATTR, 1)
						.upsertVia(session);

					// PV#2: different values per locale
					session.createNewEntity(ENTITY_PARAMETER_VALUE, 2)
						.setAttribute(ATTR_STATUS, "ACTIVE")
						.setAttribute(ATTR_LOCALIZED_WEIGHT, Locale.ENGLISH, new BigDecimal("35.27"))
						.setAttribute(ATTR_LOCALIZED_WEIGHT, new Locale("cs"), new BigDecimal("1000"))
						.upsertVia(session);

					// Product#2 references PV#2
					session.createNewEntity(ENTITY_PRODUCT, 2)
						.setReference(REF_PARAM_BY_LOCALIZED_ATTR, 2)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();

					// English histogram should contain ounce values
					assertUngroupedLocalizedHistogramBucketContains(
						productCollection, REF_PARAM_BY_LOCALIZED_ATTR,
						HISTOGRAM_LOCALIZED, Locale.ENGLISH, new BigDecimal("17.64"), 1
					);
					assertUngroupedLocalizedHistogramBucketContains(
						productCollection, REF_PARAM_BY_LOCALIZED_ATTR,
						HISTOGRAM_LOCALIZED, Locale.ENGLISH, new BigDecimal("35.27"), 2
					);

					// Czech histogram should contain gram values
					assertUngroupedLocalizedHistogramBucketContains(
						productCollection, REF_PARAM_BY_LOCALIZED_ATTR,
						HISTOGRAM_LOCALIZED, new Locale("cs"), new BigDecimal("500"), 1
					);
					assertUngroupedLocalizedHistogramBucketContains(
						productCollection, REF_PARAM_BY_LOCALIZED_ATTR,
						HISTOGRAM_LOCALIZED, new Locale("cs"), new BigDecimal("1000"), 2
					);

					// non-localized histogram (locale=null) should NOT exist
					assertUngroupedHistogramNotIndexed(
						productCollection, REF_PARAM_BY_LOCALIZED_ATTR,
						HISTOGRAM_LOCALIZED, 1
					);
					assertUngroupedHistogramNotIndexed(
						productCollection, REF_PARAM_BY_LOCALIZED_ATTR,
						HISTOGRAM_LOCALIZED, 2
					);
				}
			);
		}

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should update localized histogram on attribute change")
		void shouldUpdateLocalizedHistogramOnAttributeChange(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineConditionalBucketSchema(session);

					session.createNewEntity(ENTITY_PARAMETER_VALUE, 1)
						.setAttribute(ATTR_STATUS, "ACTIVE")
						.setAttribute(ATTR_LOCALIZED_WEIGHT, Locale.ENGLISH, new BigDecimal("17.64"))
						.setAttribute(ATTR_LOCALIZED_WEIGHT, new Locale("cs"), new BigDecimal("500"))
						.upsertVia(session);

					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setReference(REF_PARAM_BY_LOCALIZED_ATTR, 1)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();

					// initial state verified
					assertUngroupedLocalizedHistogramBucketContains(
						productCollection, REF_PARAM_BY_LOCALIZED_ATTR,
						HISTOGRAM_LOCALIZED, Locale.ENGLISH, new BigDecimal("17.64"), 1
					);
					assertUngroupedLocalizedHistogramBucketContains(
						productCollection, REF_PARAM_BY_LOCALIZED_ATTR,
						HISTOGRAM_LOCALIZED, new Locale("cs"), new BigDecimal("500"), 1
					);

					// change only the English localized weight on the referenced entity
					session.getEntity(ENTITY_PARAMETER_VALUE, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setAttribute(ATTR_LOCALIZED_WEIGHT, Locale.ENGLISH, new BigDecimal("21.16"))
						.upsertVia(session);

					// English histogram should reflect the new value
					assertUngroupedLocalizedHistogramBucketContains(
						productCollection, REF_PARAM_BY_LOCALIZED_ATTR,
						HISTOGRAM_LOCALIZED, Locale.ENGLISH, new BigDecimal("21.16"), 1
					);
					// Czech histogram should remain unchanged
					assertUngroupedLocalizedHistogramBucketContains(
						productCollection, REF_PARAM_BY_LOCALIZED_ATTR,
						HISTOGRAM_LOCALIZED, new Locale("cs"), new BigDecimal("500"), 1
					);
				}
			);
		}

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should remove localized histogram on reference removal")
		void shouldRemoveLocalizedHistogramOnReferenceRemoval(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineConditionalBucketSchema(session);

					session.createNewEntity(ENTITY_PARAMETER_VALUE, 1)
						.setAttribute(ATTR_STATUS, "ACTIVE")
						.setAttribute(ATTR_LOCALIZED_WEIGHT, Locale.ENGLISH, new BigDecimal("17.64"))
						.setAttribute(ATTR_LOCALIZED_WEIGHT, new Locale("cs"), new BigDecimal("500"))
						.upsertVia(session);

					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setReference(REF_PARAM_BY_LOCALIZED_ATTR, 1)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();

					// verify initial indexing
					assertUngroupedLocalizedHistogramBucketContains(
						productCollection, REF_PARAM_BY_LOCALIZED_ATTR,
						HISTOGRAM_LOCALIZED, Locale.ENGLISH, new BigDecimal("17.64"), 1
					);
					assertUngroupedLocalizedHistogramBucketContains(
						productCollection, REF_PARAM_BY_LOCALIZED_ATTR,
						HISTOGRAM_LOCALIZED, new Locale("cs"), new BigDecimal("500"), 1
					);

					// remove the reference → histograms for ALL locales should be cleaned up
					session.getEntity(ENTITY_PRODUCT, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.removeReference(REF_PARAM_BY_LOCALIZED_ATTR, 1)
						.upsertVia(session);

					assertUngroupedLocalizedHistogramNotIndexed(
						productCollection, REF_PARAM_BY_LOCALIZED_ATTR,
						HISTOGRAM_LOCALIZED, Locale.ENGLISH, 1
					);
					assertUngroupedLocalizedHistogramNotIndexed(
						productCollection, REF_PARAM_BY_LOCALIZED_ATTR,
						HISTOGRAM_LOCALIZED, new Locale("cs"), 1
					);
				}
			);
		}

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should handle missing locale gracefully")
		void shouldHandleMissingLocaleGracefully(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineConditionalBucketSchema(session);

					// PV#1: has localizedWeight only in English, not in Czech
					session.createNewEntity(ENTITY_PARAMETER_VALUE, 1)
						.setAttribute(ATTR_STATUS, "ACTIVE")
						.setAttribute(ATTR_LOCALIZED_WEIGHT, Locale.ENGLISH, new BigDecimal("17.64"))
						.upsertVia(session);

					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setReference(REF_PARAM_BY_LOCALIZED_ATTR, 1)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();

					// English histogram should contain the value
					assertUngroupedLocalizedHistogramBucketContains(
						productCollection, REF_PARAM_BY_LOCALIZED_ATTR,
						HISTOGRAM_LOCALIZED, Locale.ENGLISH, new BigDecimal("17.64"), 1
					);

					// Czech histogram should NOT have any entry for this product
					assertUngroupedLocalizedHistogramAbsent(
						productCollection, REF_PARAM_BY_LOCALIZED_ATTR,
						HISTOGRAM_LOCALIZED, new Locale("cs")
					);
				}
			);
		}
	}

	/**
	 * Tests verifying cross-entity trigger propagation for localized histogram attributes
	 * when the referenced entity's localized attribute is mutated.
	 */
	@Nested
	@DisplayName("Cross-entity triggers — localized referenced entity mutations")
	class CrossEntityLocalizedTriggerTest {

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should update locale-specific histogram when referenced entity localized attribute changes")
		void shouldUpdateLocaleSpecificHistogramWhenReferencedEntityLocalizedAttributeChanges(
			CatalogState state
		) {
			withCatalogInState(
				state,
				session -> {
					defineConditionalBucketSchema(session);

					session.createNewEntity(ENTITY_PARAMETER_VALUE, 1)
						.setAttribute(ATTR_STATUS, "ACTIVE")
						.setAttribute(ATTR_LOCALIZED_WEIGHT, Locale.ENGLISH, new BigDecimal("17.64"))
						.setAttribute(ATTR_LOCALIZED_WEIGHT, new Locale("cs"), new BigDecimal("500"))
						.upsertVia(session);

					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setReference(REF_PARAM_BY_LOCALIZED_ATTR, 1)
						.upsertVia(session);
					session.createNewEntity(ENTITY_PRODUCT, 2)
						.setReference(REF_PARAM_BY_LOCALIZED_ATTR, 1)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();

					// verify initial state for both products
					assertUngroupedLocalizedHistogramBucketContains(
						productCollection, REF_PARAM_BY_LOCALIZED_ATTR,
						HISTOGRAM_LOCALIZED, Locale.ENGLISH, new BigDecimal("17.64"), 1
					);
					assertUngroupedLocalizedHistogramBucketContains(
						productCollection, REF_PARAM_BY_LOCALIZED_ATTR,
						HISTOGRAM_LOCALIZED, Locale.ENGLISH, new BigDecimal("17.64"), 2
					);
					assertUngroupedLocalizedHistogramBucketContains(
						productCollection, REF_PARAM_BY_LOCALIZED_ATTR,
						HISTOGRAM_LOCALIZED, new Locale("cs"), new BigDecimal("500"), 1
					);
					assertUngroupedLocalizedHistogramBucketContains(
						productCollection, REF_PARAM_BY_LOCALIZED_ATTR,
						HISTOGRAM_LOCALIZED, new Locale("cs"), new BigDecimal("500"), 2
					);

					// mutate only the Czech localized weight on referenced entity
					session.getEntity(ENTITY_PARAMETER_VALUE, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setAttribute(ATTR_LOCALIZED_WEIGHT, new Locale("cs"), new BigDecimal("750"))
						.upsertVia(session);

					// Czech histogram should reflect the new value for both products
					assertUngroupedLocalizedHistogramBucketContains(
						productCollection, REF_PARAM_BY_LOCALIZED_ATTR,
						HISTOGRAM_LOCALIZED, new Locale("cs"), new BigDecimal("750"), 1
					);
					assertUngroupedLocalizedHistogramBucketContains(
						productCollection, REF_PARAM_BY_LOCALIZED_ATTR,
						HISTOGRAM_LOCALIZED, new Locale("cs"), new BigDecimal("750"), 2
					);

					// English histogram should remain unchanged for both products
					assertUngroupedLocalizedHistogramBucketContains(
						productCollection, REF_PARAM_BY_LOCALIZED_ATTR,
						HISTOGRAM_LOCALIZED, Locale.ENGLISH, new BigDecimal("17.64"), 1
					);
					assertUngroupedLocalizedHistogramBucketContains(
						productCollection, REF_PARAM_BY_LOCALIZED_ATTR,
						HISTOGRAM_LOCALIZED, Locale.ENGLISH, new BigDecimal("17.64"), 2
					);
				}
			);
		}

		/**
		 * Verifies that toggling the cross-entity condition to false removes histogram entries
		 * for ALL locales, and restoring the condition brings them back.
		 */
		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should remove all locale histogram entries when condition becomes false")
		void shouldRemoveAllLocaleHistogramEntriesWhenConditionBecomesFalse(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineConditionalBucketSchema(session);

					session.createNewEntity(ENTITY_PARAMETER_VALUE, 1)
						.setAttribute(ATTR_STATUS, "ACTIVE")
						.setAttribute(ATTR_LOCALIZED_WEIGHT, Locale.ENGLISH, new BigDecimal("17.64"))
						.setAttribute(ATTR_LOCALIZED_WEIGHT, new Locale("cs"), new BigDecimal("500"))
						.upsertVia(session);

					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setReference(REF_PARAM_BY_LOCALIZED_ATTR, 1)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();

					// verify both locales are indexed initially
					assertUngroupedLocalizedHistogramBucketContains(
						productCollection, REF_PARAM_BY_LOCALIZED_ATTR,
						HISTOGRAM_LOCALIZED, Locale.ENGLISH, new BigDecimal("17.64"), 1
					);
					assertUngroupedLocalizedHistogramBucketContains(
						productCollection, REF_PARAM_BY_LOCALIZED_ATTR,
						HISTOGRAM_LOCALIZED, new Locale("cs"), new BigDecimal("500"), 1
					);

					// toggle condition to false via cross-entity trigger
					session.getEntity(ENTITY_PARAMETER_VALUE, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setAttribute(ATTR_STATUS, "INACTIVE")
						.upsertVia(session);

					// ALL locale histogram entries should be removed
					assertUngroupedLocalizedHistogramNotIndexed(
						productCollection, REF_PARAM_BY_LOCALIZED_ATTR,
						HISTOGRAM_LOCALIZED, Locale.ENGLISH, 1
					);
					assertUngroupedLocalizedHistogramNotIndexed(
						productCollection, REF_PARAM_BY_LOCALIZED_ATTR,
						HISTOGRAM_LOCALIZED, new Locale("cs"), 1
					);

					// restore condition - entries should come back
					session.getEntity(ENTITY_PARAMETER_VALUE, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setAttribute(ATTR_STATUS, "ACTIVE")
						.upsertVia(session);

					assertUngroupedLocalizedHistogramBucketContains(
						productCollection, REF_PARAM_BY_LOCALIZED_ATTR,
						HISTOGRAM_LOCALIZED, Locale.ENGLISH, new BigDecimal("17.64"), 1
					);
					assertUngroupedLocalizedHistogramBucketContains(
						productCollection, REF_PARAM_BY_LOCALIZED_ATTR,
						HISTOGRAM_LOCALIZED, new Locale("cs"), new BigDecimal("500"), 1
					);
				}
			);
		}
	}

	/**
	 * Tests verifying correct histogram behavior for numeric type edge cases
	 * including BigDecimal normalization, Integer type value tracking, and default value conversion.
	 */
	@Nested
	@DisplayName("Numeric type edge cases")
	class NumericTypeEdgeCaseTest {

		/**
		 * Verifies that BigDecimal values differing only in trailing zeros (e.g. "50.00" vs "50")
		 * are normalized to the same histogram bucket via stripTrailingZeros().
		 */
		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should normalize BigDecimal via stripTrailingZeros")
		void shouldNormalizeBigDecimalViaStripTrailingZeros(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineConditionalBucketSchema(session);

					// PV#1 with trailing zeros in basicUnitValue
					session.createNewEntity(ENTITY_PARAMETER_VALUE, 1)
						.setAttribute(ATTR_STATUS, "ACTIVE")
						.setAttribute(ATTR_BASIC_UNIT_VALUE, new BigDecimal("50.00"))
						.upsertVia(session);

					// PV#2 with standard form
					session.createNewEntity(ENTITY_PARAMETER_VALUE, 2)
						.setAttribute(ATTR_STATUS, "ACTIVE")
						.setAttribute(ATTR_BASIC_UNIT_VALUE, new BigDecimal("50"))
						.upsertVia(session);

					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setReference(REF_PARAM_BY_REF_ENTITY_ATTR, 1)
						.upsertVia(session);
					session.createNewEntity(ENTITY_PRODUCT, 2)
						.setReference(REF_PARAM_BY_REF_ENTITY_ATTR, 2)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();

					// both should be in the same normalized bucket regardless of input form
					assertUngroupedHistogramBucketContains(
						productCollection, REF_PARAM_BY_REF_ENTITY_ATTR,
						HISTOGRAM_STATUS, new BigDecimal("50"), 1
					);
					assertUngroupedHistogramBucketContains(
						productCollection, REF_PARAM_BY_REF_ENTITY_ATTR,
						HISTOGRAM_STATUS, new BigDecimal("50"), 2
					);
				}
			);
		}

		/**
		 * Verifies that Integer-typed histogram values (HISTOGRAM_HIST2 using weight attribute)
		 * are correctly tracked through cross-entity value changes.
		 */
		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should track Integer histogram value changes via cross-entity trigger")
		void shouldTrackIntegerHistogramValueChangesViaCrossEntityTrigger(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineConditionalBucketSchema(session);

					session.createNewEntity(ENTITY_PARAMETER, 10)
						.setAttribute(ATTR_INPUT_WIDGET_TYPE, "INTERVAL")
						.upsertVia(session);

					session.createNewEntity(ENTITY_PARAMETER_VALUE, 1)
						.setAttribute(ATTR_BASIC_UNIT_VALUE, new BigDecimal("50"))
						.setAttribute(ATTR_WEIGHT, 10)
						.upsertVia(session);

					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setReference(
							REF_PARAM_MULTI_HISTOGRAM, 1,
							whichIs -> whichIs.setGroup(ENTITY_PARAMETER, 10)
						)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();

					assertHistogramBucketContains(
						productCollection, REF_PARAM_MULTI_HISTOGRAM, 10,
						HISTOGRAM_HIST2, 10, 1
					);

					// change weight from 10 to 25 via cross-entity trigger
					session.getEntity(ENTITY_PARAMETER_VALUE, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setAttribute(ATTR_WEIGHT, 25)
						.upsertVia(session);

					assertHistogramBucketContains(
						productCollection, REF_PARAM_MULTI_HISTOGRAM, 10,
						HISTOGRAM_HIST2, 25, 1
					);
					assertHistogramBucketNotContains(
						productCollection, REF_PARAM_MULTI_HISTOGRAM, 10,
						HISTOGRAM_HIST2, 10, 1
					);
				}
			);
		}

		/**
		 * Verifies that the default value literal (integer 0 from `?? 0`) is properly converted
		 * to the source attribute type (BigDecimal) so that a null attribute and an explicit zero
		 * resolve to the same histogram bucket.
		 */
		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should convert default value to match source attribute type")
		void shouldConvertDefaultValueToMatchSourceAttributeType(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineConditionalBucketSchema(session);

					session.createNewEntity(ENTITY_PARAMETER, 10)
						.setAttribute(ATTR_INPUT_WIDGET_TYPE, "INTERVAL")
						.upsertVia(session);

					// PV#1 with null basicUnitValue - should use default
					session.createNewEntity(ENTITY_PARAMETER_VALUE, 1)
						.upsertVia(session);

					// PV#2 with explicit basicUnitValue = 0 - should match the default bucket
					session.createNewEntity(ENTITY_PARAMETER_VALUE, 2)
						.setAttribute(ATTR_BASIC_UNIT_VALUE, BigDecimal.ZERO)
						.upsertVia(session);

					// both use the "with default" reference (value expr: ?? 0)
					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setReference(
							REF_PARAM_BY_GROUP_ATTR_WITH_DEFAULT, 1,
							whichIs -> whichIs.setGroup(ENTITY_PARAMETER, 10)
						)
						.upsertVia(session);
					session.createNewEntity(ENTITY_PRODUCT, 2)
						.setReference(
							REF_PARAM_BY_GROUP_ATTR_WITH_DEFAULT, 2,
							whichIs -> whichIs.setGroup(ENTITY_PARAMETER, 10)
						)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();

					// Product#1 (null PV -> default 0) and Product#2 (explicit 0) in same bucket
					assertHistogramBucketContains(
						productCollection, REF_PARAM_BY_GROUP_ATTR_WITH_DEFAULT, 10,
						HISTOGRAM_VALUE, BigDecimal.ZERO, 1
					);
					assertHistogramBucketContains(
						productCollection, REF_PARAM_BY_GROUP_ATTR_WITH_DEFAULT, 10,
						HISTOGRAM_VALUE, BigDecimal.ZERO, 2
					);
				}
			);
		}
	}

	/**
	 * Tests verifying histogram cleanup when the owner entity is deleted,
	 * and correct selective indexing when an entity is created with multiple references
	 * having mixed condition outcomes.
	 */
	@Nested
	@DisplayName("Entity lifecycle — owner deletion and mixed-condition creation")
	class EntityLifecycleTest {

		/**
		 * Verifies that deleting the owner entity (Product) completely removes its histogram
		 * entries from all affected indexes.
		 */
		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should clean up histogram when owner entity is deleted")
		void shouldCleanUpHistogramWhenOwnerEntityDeleted(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineConditionalBucketSchema(session);

					session.createNewEntity(ENTITY_PARAMETER, 10)
						.setAttribute(ATTR_INPUT_WIDGET_TYPE, "INTERVAL")
						.upsertVia(session);

					session.createNewEntity(ENTITY_PARAMETER_VALUE, 1)
						.setAttribute(ATTR_BASIC_UNIT_VALUE, new BigDecimal("50"))
						.setAttribute(ATTR_STATUS, "ACTIVE")
						.upsertVia(session);

					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setAttribute(ATTR_IS_ACTIVE, true)
						.setReference(
							REF_PARAM_BY_GROUP_ATTR, 1,
							whichIs -> whichIs.setGroup(ENTITY_PARAMETER, 10)
						)
						.setReference(REF_PARAM_BY_ENTITY_ATTR, 1)
						.setReference(REF_PARAM_BY_REF_ENTITY_ATTR, 1)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();

					// verify initial indexing across multiple reference types
					assertHistogramBucketContains(
						productCollection, REF_PARAM_BY_GROUP_ATTR, 10,
						HISTOGRAM_VALUE, new BigDecimal("50"), 1
					);
					assertUngroupedHistogramBucketContains(
						productCollection, REF_PARAM_BY_ENTITY_ATTR,
						HISTOGRAM_ENTITY, new BigDecimal("50"), 1
					);
					assertUngroupedHistogramBucketContains(
						productCollection, REF_PARAM_BY_REF_ENTITY_ATTR,
						HISTOGRAM_STATUS, new BigDecimal("50"), 1
					);

					// delete the owner entity
					session.deleteEntity(ENTITY_PRODUCT, 1);

					// all histogram entries should be cleaned up
					assertHistogramNotIndexed(
						productCollection, REF_PARAM_BY_GROUP_ATTR, 10,
						HISTOGRAM_VALUE, 1
					);
					assertUngroupedHistogramNotIndexed(
						productCollection, REF_PARAM_BY_ENTITY_ATTR,
						HISTOGRAM_ENTITY, 1
					);
					assertUngroupedHistogramNotIndexed(
						productCollection, REF_PARAM_BY_REF_ENTITY_ATTR,
						HISTOGRAM_STATUS, 1
					);
				}
			);
		}

		/**
		 * Verifies that creating a single entity with multiple references (some matching, some not)
		 * correctly indexes only the matching references in the histogram.
		 */
		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should index only matching references when entity has mixed conditions")
		void shouldIndexOnlyMatchingReferencesWhenEntityHasMixedConditions(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineConditionalBucketSchema(session);

					session.createNewEntity(ENTITY_PARAMETER, 10)
						.setAttribute(ATTR_INPUT_WIDGET_TYPE, "INTERVAL")
						.upsertVia(session);
					session.createNewEntity(ENTITY_PARAMETER, 20)
						.setAttribute(ATTR_INPUT_WIDGET_TYPE, "CHECKBOX")
						.upsertVia(session);

					session.createNewEntity(ENTITY_PARAMETER_VALUE, 1)
						.setAttribute(ATTR_BASIC_UNIT_VALUE, new BigDecimal("50"))
						.upsertVia(session);
					session.createNewEntity(ENTITY_PARAMETER_VALUE, 2)
						.setAttribute(ATTR_BASIC_UNIT_VALUE, new BigDecimal("75"))
						.upsertVia(session);

					// single product with multiple references: some matching, some not
					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setAttribute(ATTR_IS_ACTIVE, true)
						// grouped ref to PV#1 via INTERVAL group - bucketed
						.setReference(
							REF_PARAM_BY_GROUP_ATTR, 1,
							whichIs -> whichIs.setGroup(ENTITY_PARAMETER, 10)
						)
						// grouped ref to PV#2 via CHECKBOX group - NOT bucketed
						.setReference(
							REF_PARAM_BY_GROUP_ATTR, 2,
							whichIs -> whichIs.setGroup(ENTITY_PARAMETER, 20)
						)
						// ungrouped ref with matching entity condition - bucketed
						.setReference(REF_PARAM_BY_ENTITY_ATTR, 1)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();

					// grouped PV#1 via INTERVAL group - in histogram
					assertHistogramBucketContains(
						productCollection, REF_PARAM_BY_GROUP_ATTR, 10,
						HISTOGRAM_VALUE, new BigDecimal("50"), 1
					);
					// grouped PV#2 via CHECKBOX group - NOT in histogram
					assertHistogramNotIndexed(
						productCollection, REF_PARAM_BY_GROUP_ATTR, 20,
						HISTOGRAM_VALUE, 1
					);
					// ungrouped ref with isActive=true - in histogram
					assertUngroupedHistogramBucketContains(
						productCollection, REF_PARAM_BY_ENTITY_ATTR,
						HISTOGRAM_ENTITY, new BigDecimal("50"), 1
					);
				}
			);
		}
	}

	/**
	 * Tests verifying histogram behavior during group reassignment between matching groups
	 * and cross-entity updates propagating to multiple groups referencing the same entity.
	 */
	@Nested
	@DisplayName("Group edge cases — reassignment and multi-group propagation")
	class GroupEdgeCaseTest {

		/**
		 * Verifies that moving a reference from one matching group (INTERVAL) to another matching
		 * group (INTERVAL) correctly maintains the histogram entry.
		 */
		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should reassign histogram between matching groups")
		void shouldReassignHistogramBetweenMatchingGroups(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineConditionalBucketSchema(session);

					// two groups, both INTERVAL
					session.createNewEntity(ENTITY_PARAMETER, 10)
						.setAttribute(ATTR_INPUT_WIDGET_TYPE, "INTERVAL")
						.upsertVia(session);
					session.createNewEntity(ENTITY_PARAMETER, 20)
						.setAttribute(ATTR_INPUT_WIDGET_TYPE, "INTERVAL")
						.upsertVia(session);

					session.createNewEntity(ENTITY_PARAMETER_VALUE, 1)
						.setAttribute(ATTR_BASIC_UNIT_VALUE, new BigDecimal("50"))
						.upsertVia(session);

					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setReference(
							REF_PARAM_BY_GROUP_ATTR, 1,
							whichIs -> whichIs.setGroup(ENTITY_PARAMETER, 10)
						)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();

					// initially in histogram via group#10
					assertHistogramBucketContains(
						productCollection, REF_PARAM_BY_GROUP_ATTR, 10,
						HISTOGRAM_VALUE, new BigDecimal("50"), 1
					);

					// reassign from group#10 to group#20 (both INTERVAL)
					session.getEntity(ENTITY_PRODUCT, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setReference(
							REF_PARAM_BY_GROUP_ATTR, 1,
							whichIs -> whichIs.setGroup(ENTITY_PARAMETER, 20)
						)
						.upsertVia(session);

					// should still be in histogram (new group is also INTERVAL)
					assertHistogramBucketContains(
						productCollection, REF_PARAM_BY_GROUP_ATTR, 20,
						HISTOGRAM_VALUE, new BigDecimal("50"), 1
					);
				}
			);
		}

		/**
		 * Verifies that changing a referenced entity's value attribute propagates correctly
		 * to products in different matching groups (both INTERVAL) via cross-entity trigger.
		 */
		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should propagate cross-entity update to products in different matching groups")
		void shouldPropagateCrossEntityUpdateToProductsInDifferentMatchingGroups(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineConditionalBucketSchema(session);

					session.createNewEntity(ENTITY_PARAMETER, 10)
						.setAttribute(ATTR_INPUT_WIDGET_TYPE, "INTERVAL")
						.upsertVia(session);
					session.createNewEntity(ENTITY_PARAMETER, 20)
						.setAttribute(ATTR_INPUT_WIDGET_TYPE, "INTERVAL")
						.upsertVia(session);

					session.createNewEntity(ENTITY_PARAMETER_VALUE, 1)
						.setAttribute(ATTR_BASIC_UNIT_VALUE, new BigDecimal("50"))
						.upsertVia(session);

					// Product#1 refs PV#1 in group#10
					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setReference(
							REF_PARAM_BY_GROUP_ATTR, 1,
							whichIs -> whichIs.setGroup(ENTITY_PARAMETER, 10)
						)
						.upsertVia(session);
					// Product#2 refs PV#1 in group#20
					session.createNewEntity(ENTITY_PRODUCT, 2)
						.setReference(
							REF_PARAM_BY_GROUP_ATTR, 1,
							whichIs -> whichIs.setGroup(ENTITY_PARAMETER, 20)
						)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();

					assertHistogramBucketContains(
						productCollection, REF_PARAM_BY_GROUP_ATTR, 10,
						HISTOGRAM_VALUE, new BigDecimal("50"), 1
					);
					assertHistogramBucketContains(
						productCollection, REF_PARAM_BY_GROUP_ATTR, 20,
						HISTOGRAM_VALUE, new BigDecimal("50"), 2
					);

					// change PV#1's value - cross-entity trigger should update both
					session.getEntity(ENTITY_PARAMETER_VALUE, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setAttribute(ATTR_BASIC_UNIT_VALUE, new BigDecimal("75"))
						.upsertVia(session);

					assertHistogramBucketContains(
						productCollection, REF_PARAM_BY_GROUP_ATTR, 10,
						HISTOGRAM_VALUE, new BigDecimal("75"), 1
					);
					assertHistogramBucketContains(
						productCollection, REF_PARAM_BY_GROUP_ATTR, 20,
						HISTOGRAM_VALUE, new BigDecimal("75"), 2
					);
					assertHistogramBucketNotContains(
						productCollection, REF_PARAM_BY_GROUP_ATTR, 10,
						HISTOGRAM_VALUE, new BigDecimal("50"), 1
					);
					assertHistogramBucketNotContains(
						productCollection, REF_PARAM_BY_GROUP_ATTR, 10,
						HISTOGRAM_VALUE, new BigDecimal("50"), 2
					);
				}
			);
		}
	}

	/**
	 * Tests verifying histogram data survives catalog persistence (close/reopen) cycle,
	 * confirming correct Kryo serialization/deserialization of histogram FilterIndex data.
	 */
	@Nested
	@DisplayName("Persistence — histogram data survives catalog close and reopen")
	class PersistenceRoundTripTest {

		/**
		 * Creates histogram data across grouped and ungrouped references, closes evita,
		 * reopens it, and verifies all histogram FilterIndex entries survived the round-trip.
		 */
		@Test
		@DisplayName("Should survive catalog close and reopen with histogram data intact")
		void shouldSurviveCatalogCloseAndReopenWithHistogramDataIntact() {
			// create fixture and go ALIVE
			ConditionalBucketIndexingTest.this.evita.updateCatalog(TEST_CATALOG, session -> {
				defineConditionalBucketSchema(session);

				session.createNewEntity(ENTITY_PARAMETER, 10)
					.setAttribute(ATTR_INPUT_WIDGET_TYPE, "INTERVAL")
					.upsertVia(session);

				session.createNewEntity(ENTITY_PARAMETER_VALUE, 1)
					.setAttribute(ATTR_BASIC_UNIT_VALUE, new BigDecimal("50"))
					.setAttribute(ATTR_STATUS, "ACTIVE")
					.upsertVia(session);

				session.createNewEntity(ENTITY_PRODUCT, 1)
					.setAttribute(ATTR_IS_ACTIVE, true)
					.setReference(
						REF_PARAM_BY_GROUP_ATTR, 1,
						whichIs -> whichIs.setGroup(ENTITY_PARAMETER, 10)
					)
					.setReference(REF_PARAM_BY_ENTITY_ATTR, 1)
					.setReference(REF_PARAM_BY_REF_ENTITY_ATTR, 1)
					.upsertVia(session);

				session.goLiveAndClose();
			});

			// close and reopen Evita
			ConditionalBucketIndexingTest.this.evita.close();
			ConditionalBucketIndexingTest.this.evita = new Evita(getEvitaConfiguration());
			ConditionalBucketIndexingTest.this.evita.waitUntilFullyInitialized();

			// verify histogram data survived the round-trip
			final EntityCollectionContract productCollection = getProductCollection();

			// grouped histogram via group entity attribute
			assertHistogramBucketContains(
				productCollection, REF_PARAM_BY_GROUP_ATTR, 10,
				HISTOGRAM_VALUE, new BigDecimal("50"), 1
			);
			// ungrouped histogram via entity attribute condition
			assertUngroupedHistogramBucketContains(
				productCollection, REF_PARAM_BY_ENTITY_ATTR,
				HISTOGRAM_ENTITY, new BigDecimal("50"), 1
			);
			// ungrouped histogram via referenced entity attribute condition
			assertUngroupedHistogramBucketContains(
				productCollection, REF_PARAM_BY_REF_ENTITY_ATTR,
				HISTOGRAM_STATUS, new BigDecimal("50"), 1
			);
		}
	}

	/**
	 * Tests revealing that cross-entity histogram re-evaluation incorrectly removes histogram entries
	 * contributed by unrelated references when one referenced entity's attribute changes.
	 *
	 * The bug is in `ReevaluateExpressionExecutor.processHistogramTriggers()`: when computing the
	 * `histogramShouldNotBeIndexed` bitmap via FilterBy evaluation, it removes ALL histogram entries
	 * for the affected owner PKs — including entries contributed by UNRELATED references that were
	 * never part of the mutation. The subsequent re-add only restores entries for the mutated entity,
	 * permanently losing the contributions from other referenced entities.
	 */
	@Nested
	@DisplayName("Cross-entity re-evaluation fan-out — multi-reference regression")
	class CrossEntityMultiReferenceRegressionTest {

		/**
		 * Product#1 has two references to the same conditional reference type, each pointing to a
		 * different PV. Toggling the condition on one PV should not destroy the histogram entry
		 * contributed by the other.
		 */
		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should preserve histogram entries when cross-entity condition toggles on one reference")
		void shouldPreserveHistogramEntriesWhenCrossEntityConditionTogglesOnOneReference(
			@Nonnull CatalogState state
		) {
			withCatalogInState(
				state,
				session -> {
					defineConditionalBucketSchema(session);

					session.createNewEntity(ENTITY_PARAMETER_VALUE, 1)
						.setAttribute(ATTR_STATUS, "ACTIVE")
						.setAttribute(ATTR_BASIC_UNIT_VALUE, new BigDecimal("50"))
						.upsertVia(session);

					session.createNewEntity(ENTITY_PARAMETER_VALUE, 2)
						.setAttribute(ATTR_STATUS, "ACTIVE")
						.setAttribute(ATTR_BASIC_UNIT_VALUE, new BigDecimal("75"))
						.upsertVia(session);

					// Product#1 references BOTH PV#1 and PV#2 via the conditional reference type
					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setAttribute(ATTR_IS_ACTIVE, true)
						.setReference(REF_PARAM_BY_REF_ENTITY_ATTR, 1)
						.setReference(REF_PARAM_BY_REF_ENTITY_ATTR, 2)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();

					// initially: Product#1 in bucket 50 (PV#1) and bucket 75 (PV#2)
					assertUngroupedHistogramBucketContains(
						productCollection, REF_PARAM_BY_REF_ENTITY_ATTR,
						HISTOGRAM_STATUS, new BigDecimal("50"), 1
					);
					assertUngroupedHistogramBucketContains(
						productCollection, REF_PARAM_BY_REF_ENTITY_ATTR,
						HISTOGRAM_STATUS, new BigDecimal("75"), 1
					);

					// set PV#1 status to INACTIVE → condition false for PV#1 reference
					session.getEntity(ENTITY_PARAMETER_VALUE, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setAttribute(ATTR_STATUS, "INACTIVE")
						.upsertVia(session);

					// BUG: Product#1 should STILL be in bucket 75 (from PV#2, whose condition
					// is still ACTIVE), but the cross-entity re-evaluation removes ALL entries
					// for Product#1 and only re-adds based on PV#1 (which now fails condition)
					// — PV#2's entry is lost
					assertUngroupedHistogramBucketContains(
						productCollection, REF_PARAM_BY_REF_ENTITY_ATTR,
						HISTOGRAM_STATUS, new BigDecimal("75"), 1
					);
				}
			);
		}

		/**
		 * Product#1 has two references to the same conditional reference type, each pointing to a
		 * different PV with different values. Changing the value attribute on PV#1 (cross-entity
		 * value change) should not destroy the histogram entry contributed by PV#2.
		 */
		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should preserve histogram entries when cross-entity value changes on one reference")
		void shouldPreserveHistogramEntriesWhenCrossEntityValueChangesOnOneReference(
			@Nonnull CatalogState state
		) {
			withCatalogInState(
				state,
				session -> {
					defineConditionalBucketSchema(session);

					session.createNewEntity(ENTITY_PARAMETER_VALUE, 1)
						.setAttribute(ATTR_STATUS, "ACTIVE")
						.setAttribute(ATTR_BASIC_UNIT_VALUE, new BigDecimal("50"))
						.upsertVia(session);

					session.createNewEntity(ENTITY_PARAMETER_VALUE, 2)
						.setAttribute(ATTR_STATUS, "ACTIVE")
						.setAttribute(ATTR_BASIC_UNIT_VALUE, new BigDecimal("75"))
						.upsertVia(session);

					// Product#1 references BOTH PV#1 and PV#2 via the conditional reference type
					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setAttribute(ATTR_IS_ACTIVE, true)
						.setReference(REF_PARAM_BY_REF_ENTITY_ATTR, 1)
						.setReference(REF_PARAM_BY_REF_ENTITY_ATTR, 2)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();

					// initially: Product#1 in bucket 50 (PV#1) and bucket 75 (PV#2)
					assertUngroupedHistogramBucketContains(
						productCollection, REF_PARAM_BY_REF_ENTITY_ATTR,
						HISTOGRAM_STATUS, new BigDecimal("50"), 1
					);
					assertUngroupedHistogramBucketContains(
						productCollection, REF_PARAM_BY_REF_ENTITY_ATTR,
						HISTOGRAM_STATUS, new BigDecimal("75"), 1
					);

					// change PV#1's basicUnitValue from 50 to 60 (value change, NOT condition change)
					session.getEntity(ENTITY_PARAMETER_VALUE, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setAttribute(ATTR_BASIC_UNIT_VALUE, new BigDecimal("60"))
						.upsertVia(session);

					// Product#1 should be in bucket 60 (PV#1 new value) AND bucket 75 (PV#2 preserved)
					assertUngroupedHistogramBucketContains(
						productCollection, REF_PARAM_BY_REF_ENTITY_ATTR,
						HISTOGRAM_STATUS, new BigDecimal("60"), 1
					);
					assertUngroupedHistogramBucketContains(
						productCollection, REF_PARAM_BY_REF_ENTITY_ATTR,
						HISTOGRAM_STATUS, new BigDecimal("75"), 1
					);
					// should NOT be in old bucket 50
					assertUngroupedHistogramBucketNotContains(
						productCollection, REF_PARAM_BY_REF_ENTITY_ATTR,
						HISTOGRAM_STATUS, new BigDecimal("50"), 1
					);
				}
			);
		}

		/**
		 * Product#1 has two references to the same conditional reference type, both pointing to PVs
		 * with the SAME value (50). Setting PV#1 to INACTIVE should decrement cardinality from 2 to 1,
		 * NOT remove Product#1 from the bucket entirely.
		 */
		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should preserve histogram entry when same value contributed by multiple references")
		void shouldPreserveHistogramEntryWhenSameValueContributedByMultipleReferences(
			@Nonnull CatalogState state
		) {
			withCatalogInState(
				state,
				session -> {
					defineConditionalBucketSchema(session);

					session.createNewEntity(ENTITY_PARAMETER_VALUE, 1)
						.setAttribute(ATTR_STATUS, "ACTIVE")
						.setAttribute(ATTR_BASIC_UNIT_VALUE, new BigDecimal("50"))
						.upsertVia(session);

					session.createNewEntity(ENTITY_PARAMETER_VALUE, 2)
						.setAttribute(ATTR_STATUS, "ACTIVE")
						.setAttribute(ATTR_BASIC_UNIT_VALUE, new BigDecimal("50"))
						.upsertVia(session);

					// Product#1 references BOTH PV#1 and PV#2
					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setAttribute(ATTR_IS_ACTIVE, true)
						.setReference(REF_PARAM_BY_REF_ENTITY_ATTR, 1)
						.setReference(REF_PARAM_BY_REF_ENTITY_ATTR, 2)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();

					// initially: Product#1 in bucket 50 (contributed by both PV#1 and PV#2)
					assertUngroupedHistogramBucketContains(
						productCollection, REF_PARAM_BY_REF_ENTITY_ATTR,
						HISTOGRAM_STATUS, new BigDecimal("50"), 1
					);

					// set PV#1 status to INACTIVE -> condition false for PV#1 reference
					session.getEntity(ENTITY_PARAMETER_VALUE, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setAttribute(ATTR_STATUS, "INACTIVE")
						.upsertVia(session);

					// Product#1 should STILL be in bucket 50 (PV#2 still contributes)
					assertUngroupedHistogramBucketContains(
						productCollection, REF_PARAM_BY_REF_ENTITY_ATTR,
						HISTOGRAM_STATUS, new BigDecimal("50"), 1
					);
				}
			);
		}

		/**
		 * Product#1 has two references to the same reference type, each pointing to a different PV.
		 * Removing the reference to PV#1 (local path -- single reference removal) should preserve the
		 * histogram entry contributed by PV#2.
		 */
		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should preserve histogram entries when one reference is removed")
		void shouldPreserveHistogramEntriesWhenOneReferenceRemoved(
			@Nonnull CatalogState state
		) {
			withCatalogInState(
				state,
				session -> {
					defineConditionalBucketSchema(session);

					session.createNewEntity(ENTITY_PARAMETER_VALUE, 1)
						.setAttribute(ATTR_STATUS, "ACTIVE")
						.setAttribute(ATTR_BASIC_UNIT_VALUE, new BigDecimal("50"))
						.upsertVia(session);

					session.createNewEntity(ENTITY_PARAMETER_VALUE, 2)
						.setAttribute(ATTR_STATUS, "ACTIVE")
						.setAttribute(ATTR_BASIC_UNIT_VALUE, new BigDecimal("75"))
						.upsertVia(session);

					// Product#1 references BOTH PV#1 and PV#2
					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setAttribute(ATTR_IS_ACTIVE, true)
						.setReference(REF_PARAM_BY_REF_ENTITY_ATTR, 1)
						.setReference(REF_PARAM_BY_REF_ENTITY_ATTR, 2)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();

					// initially: Product#1 in bucket 50 (PV#1) and bucket 75 (PV#2)
					assertUngroupedHistogramBucketContains(
						productCollection, REF_PARAM_BY_REF_ENTITY_ATTR,
						HISTOGRAM_STATUS, new BigDecimal("50"), 1
					);
					assertUngroupedHistogramBucketContains(
						productCollection, REF_PARAM_BY_REF_ENTITY_ATTR,
						HISTOGRAM_STATUS, new BigDecimal("75"), 1
					);

					// remove Product#1's reference to PV#1
					session.getEntity(ENTITY_PRODUCT, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.removeReference(REF_PARAM_BY_REF_ENTITY_ATTR, 1)
						.upsertVia(session);

					// Product#1 should be in bucket 75 (PV#2 preserved), NOT in bucket 50
					assertUngroupedHistogramBucketContains(
						productCollection, REF_PARAM_BY_REF_ENTITY_ATTR,
						HISTOGRAM_STATUS, new BigDecimal("75"), 1
					);
					assertUngroupedHistogramBucketNotContains(
						productCollection, REF_PARAM_BY_REF_ENTITY_ATTR,
						HISTOGRAM_STATUS, new BigDecimal("50"), 1
					);
				}
			);
		}


	}
}
