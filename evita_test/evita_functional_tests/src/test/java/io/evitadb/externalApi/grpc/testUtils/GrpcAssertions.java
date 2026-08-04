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

package io.evitadb.externalApi.grpc.testUtils;

import io.evitadb.api.exception.ContextMissingException;
import io.evitadb.api.requestResponse.data.AssociatedDataContract;
import io.evitadb.api.requestResponse.data.AttributesContract;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.BinaryEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.extraResult.AttributeHistogram;
import io.evitadb.api.requestResponse.extraResult.FacetSummary;
import io.evitadb.api.requestResponse.extraResult.FacetSummary.FacetGroupStatistics;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary.FacetStatistics;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary.ReferenceGroupStatistics;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary.RequestImpact;
import io.evitadb.api.requestResponse.extraResult.Hierarchy;
import io.evitadb.api.requestResponse.extraResult.Hierarchy.LevelInfo;
import io.evitadb.api.requestResponse.extraResult.HistogramContract;
import io.evitadb.api.requestResponse.extraResult.PriceHistogram;
import io.evitadb.api.requestResponse.extraResult.FormulaPlan;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry.QueryPhase;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry.StepMetric;
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntityAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EvolutionMode;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.HistogramIndexDefinition;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.Scope;
import io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter;
import io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter.AssociatedDataForm;
import io.evitadb.externalApi.grpc.generated.*;
import io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;

import static io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter.GRPC_MAX_INSTANT;
import static io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter.GRPC_MIN_INSTANT;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Class used in tests to assert Evita data types and gRPC messages against each other.
 *
 * @author Tomáš Pozler, 2022
 */
public class GrpcAssertions {
	public static void assertEntitySchema(@Nonnull EntitySchemaContract expectedEntitySchema, @Nonnull GrpcEntitySchema actualEntitySchema) {
		assertEquals(expectedEntitySchema.getName(), actualEntitySchema.getName());
		if (expectedEntitySchema.getDescription() == null) {
			assertEquals(actualEntitySchema.getDescription().getDefaultInstanceForType(), actualEntitySchema.getDescription());
		} else {
			assertEquals(expectedEntitySchema.getDescription(), actualEntitySchema.getDescription().getValue());
		}
		if (expectedEntitySchema.getDeprecationNotice() == null) {
			assertEquals(actualEntitySchema.getDeprecationNotice().getDefaultInstanceForType(), actualEntitySchema.getDeprecationNotice());
		} else {
			assertEquals(expectedEntitySchema.getDeprecationNotice(), actualEntitySchema.getDeprecationNotice().getValue());
		}
		assertEquals(expectedEntitySchema.isWithGeneratedPrimaryKey(), actualEntitySchema.getWithGeneratedPrimaryKey());
		assertEquals(expectedEntitySchema.isWithHierarchy(), actualEntitySchema.getWithHierarchy());
		assertEquals(expectedEntitySchema.isWithPrice(), actualEntitySchema.getWithPrice());
		assertLocales(expectedEntitySchema.getLocales(), actualEntitySchema.getLocalesList());
		assertCurrencies(expectedEntitySchema.getCurrencies(), actualEntitySchema.getCurrenciesList());
		assertAttributes(expectedEntitySchema.getAttributes(), actualEntitySchema.getAttributesMap());
		assertAssociatedData(expectedEntitySchema.getAssociatedData(), actualEntitySchema.getAssociatedDataMap());
		assertReferences(expectedEntitySchema.getReferences(), actualEntitySchema.getReferencesMap());
		assertEvolutionMode(expectedEntitySchema.getEvolutionMode(), actualEntitySchema.getEvolutionModeList());
	}

	public static void assertCatalogSchema(@Nonnull CatalogSchemaContract expectedCatalogSchema, @Nonnull GrpcCatalogSchema actualCatalogSchema) {
		assertEquals(expectedCatalogSchema.getName(), actualCatalogSchema.getName());
		if (expectedCatalogSchema.getDescription() == null) {
			assertEquals(actualCatalogSchema.getDescription().getDefaultInstanceForType(), actualCatalogSchema.getDescription());
		} else {
			assertEquals(expectedCatalogSchema.getDescription(), actualCatalogSchema.getDescription().getValue());
		}
		assertGlobalAttributes(expectedCatalogSchema.getAttributes(), actualCatalogSchema.getAttributesMap());
	}

	public static void assertGlobalAttributes(@Nonnull Map<String, GlobalAttributeSchemaContract> expectedAttributesMap, @Nonnull Map<String, GrpcGlobalAttributeSchema> actualAttributesMap) {
		assertEquals(expectedAttributesMap.size(), actualAttributesMap.size());
		for (Map.Entry<String, GlobalAttributeSchemaContract> expectedAttributeEntry : expectedAttributesMap.entrySet()) {
			final GlobalAttributeSchemaContract expectedAttributeSchema = expectedAttributeEntry.getValue();
			final GrpcGlobalAttributeSchema actualAttributeSchema = actualAttributesMap.get(expectedAttributeEntry.getKey());
			assertEquals(expectedAttributeSchema.getName(), actualAttributeSchema.getName());
			if (expectedAttributeSchema.getDescription() == null) {
				assertEquals(actualAttributeSchema.getDescription().getDefaultInstanceForType(), actualAttributeSchema.getDescription());
			} else {
				assertEquals(expectedAttributeSchema.getDescription(), actualAttributeSchema.getDescription().getValue());
			}
			if (expectedAttributeSchema.getDeprecationNotice() == null) {
				assertEquals(actualAttributeSchema.getDeprecationNotice().getDefaultInstanceForType(), actualAttributeSchema.getDeprecationNotice());
			} else {
				assertEquals(expectedAttributeSchema.getDeprecationNotice(), actualAttributeSchema.getDeprecationNotice().getValue());
			}
			assertEquals(expectedAttributeSchema.isUnique(), actualAttributeSchema.getUnique());
			assertEquals(expectedAttributeSchema.isFilterable(), actualAttributeSchema.getFilterable());
			assertEquals(expectedAttributeSchema.isSortable(), actualAttributeSchema.getSortable());
			assertEquals(expectedAttributeSchema.isLocalized(), actualAttributeSchema.getLocalized());
			assertEquals(expectedAttributeSchema.isNullable(), actualAttributeSchema.getNullable());
			assertEquals(EvitaDataTypesConverter.toGrpcEvitaDataType(expectedAttributeSchema.getType()), actualAttributeSchema.getType());
			assertEquals(expectedAttributeSchema.getIndexedDecimalPlaces(), actualAttributeSchema.getIndexedDecimalPlaces());
			assertEquals(expectedAttributeSchema.isRepresentative(), actualAttributeSchema.getRepresentative());
			assertEquals(expectedAttributeSchema.isUniqueGlobally(), actualAttributeSchema.getUniqueGlobally());
		}
	}

	public static void assertAttributes(@Nonnull Map<String, ? extends AttributeSchemaContract> expectedAttributesMap, @Nonnull Map<String, GrpcAttributeSchema> actualAttributesMap) {
		assertEquals(expectedAttributesMap.size(), actualAttributesMap.size());
		for (Map.Entry<String, ? extends AttributeSchemaContract> expectedAttributeEntry : expectedAttributesMap.entrySet()) {
			final AttributeSchemaContract expectedAttributeSchema = expectedAttributeEntry.getValue();
			final GrpcAttributeSchema actualAttributeSchema = actualAttributesMap.get(expectedAttributeEntry.getKey());

			if (expectedAttributeSchema instanceof GlobalAttributeSchemaContract globalAttributeSchema) {
				assertEquals(GrpcAttributeSchemaType.GLOBAL_SCHEMA, actualAttributeSchema.getSchemaType());
				assertEquals(globalAttributeSchema.isRepresentative(), actualAttributeSchema.getRepresentative());
				assertEquals(globalAttributeSchema.isUniqueGlobally(), actualAttributeSchema.getUniqueGlobally());
			} else if (expectedAttributeSchema instanceof EntityAttributeSchemaContract entityAttributeSchema) {
				assertEquals(GrpcAttributeSchemaType.ENTITY_SCHEMA, actualAttributeSchema.getSchemaType());
				assertEquals(entityAttributeSchema.isRepresentative(), actualAttributeSchema.getRepresentative());
			} else {
				assertEquals(GrpcAttributeSchemaType.REFERENCE_SCHEMA, actualAttributeSchema.getSchemaType());
			}

			assertEquals(expectedAttributeSchema.getName(), actualAttributeSchema.getName());
			if (expectedAttributeSchema.getDescription() == null) {
				assertEquals(actualAttributeSchema.getDescription().getDefaultInstanceForType(), actualAttributeSchema.getDescription());
			} else {
				assertEquals(expectedAttributeSchema.getDescription(), actualAttributeSchema.getDescription().getValue());
			}
			if (expectedAttributeSchema.getDeprecationNotice() == null) {
				assertEquals(actualAttributeSchema.getDeprecationNotice().getDefaultInstanceForType(), actualAttributeSchema.getDeprecationNotice());
			} else {
				assertEquals(expectedAttributeSchema.getDeprecationNotice(), actualAttributeSchema.getDeprecationNotice().getValue());
			}
			assertEquals(expectedAttributeSchema.isUnique(), actualAttributeSchema.getUnique());
			assertEquals(expectedAttributeSchema.isFilterable(), actualAttributeSchema.getFilterable());
			assertEquals(expectedAttributeSchema.isSortable(), actualAttributeSchema.getSortable());
			assertEquals(expectedAttributeSchema.isLocalized(), actualAttributeSchema.getLocalized());
			assertEquals(expectedAttributeSchema.isNullable(), actualAttributeSchema.getNullable());
			assertEquals(EvitaDataTypesConverter.toGrpcEvitaDataType(expectedAttributeSchema.getType()), actualAttributeSchema.getType());
			assertEquals(expectedAttributeSchema.getIndexedDecimalPlaces(), actualAttributeSchema.getIndexedDecimalPlaces());
		}
	}

	public static void assertAssociatedData(@Nonnull Map<String, AssociatedDataSchemaContract> expectedAssociatedDataMap, @Nonnull Map<String, GrpcAssociatedDataSchema> actualAssociatedDataMap) {
		assertEquals(expectedAssociatedDataMap.size(), actualAssociatedDataMap.size());
		for (Map.Entry<String, AssociatedDataSchemaContract> expectedAssociatedDataEntry : expectedAssociatedDataMap.entrySet()) {
			final AssociatedDataSchemaContract expectedAssociatedDataSchema = expectedAssociatedDataEntry.getValue();
			final GrpcAssociatedDataSchema actualAssociatedDataSchema = actualAssociatedDataMap.get(expectedAssociatedDataEntry.getKey());
			assertEquals(expectedAssociatedDataSchema.getName(), actualAssociatedDataSchema.getName());
			if (expectedAssociatedDataSchema.getDescription() == null) {
				assertEquals(actualAssociatedDataSchema.getDescription().getDefaultInstanceForType(), actualAssociatedDataSchema.getDescription());
			} else {
				assertEquals(expectedAssociatedDataSchema.getDescription(), actualAssociatedDataSchema.getDescription().getValue());
			}
			if (expectedAssociatedDataSchema.getDeprecationNotice() == null) {
				assertEquals(actualAssociatedDataSchema.getDeprecationNotice().getDefaultInstanceForType(), actualAssociatedDataSchema.getDeprecationNotice());
			} else {
				assertEquals(expectedAssociatedDataSchema.getDeprecationNotice(), actualAssociatedDataSchema.getDeprecationNotice().getValue());
			}
			assertEquals(EvitaDataTypesConverter.toGrpcEvitaAssociatedDataDataType(expectedAssociatedDataSchema.getType()), actualAssociatedDataSchema.getType());
			assertEquals(expectedAssociatedDataSchema.isLocalized(), actualAssociatedDataSchema.getLocalized());
			assertEquals(expectedAssociatedDataSchema.isNullable(), actualAssociatedDataSchema.getNullable());
		}
	}

	public static void assertReferences(@Nonnull Map<String, ReferenceSchemaContract> expectedReferencesSchemaMap, @Nonnull Map<String, GrpcReferenceSchema> actualReferenceSchemaMap) {
		assertEquals(expectedReferencesSchemaMap.size(), actualReferenceSchemaMap.size());
		for (Map.Entry<String, ReferenceSchemaContract> expectedReferenceEntry : expectedReferencesSchemaMap.entrySet()) {
			final ReferenceSchemaContract expectedReferenceSchema = expectedReferenceEntry.getValue();
			final GrpcReferenceSchema actualReferenceSchema = actualReferenceSchemaMap.get(expectedReferenceEntry.getKey());
			assertEquals(expectedReferenceSchema.getName(), actualReferenceSchema.getName());
			if (expectedReferenceSchema.getDescription() == null) {
				assertEquals(actualReferenceSchema.getDescription().getDefaultInstanceForType(), actualReferenceSchema.getDescription());
			} else {
				assertEquals(expectedReferenceSchema.getDescription(), actualReferenceSchema.getDescription().getValue());
			}
			if (expectedReferenceSchema.getDeprecationNotice() == null) {
				assertEquals(actualReferenceSchema.getDeprecationNotice().getDefaultInstanceForType(), actualReferenceSchema.getDeprecationNotice());
			} else {
				assertEquals(expectedReferenceSchema.getDeprecationNotice(), actualReferenceSchema.getDeprecationNotice().getValue());
			}
			assertEquals(expectedReferenceSchema.getCardinality(), Cardinality.valueOf(actualReferenceSchema.getCardinality().name()));
			assertEquals(expectedReferenceSchema.getReferencedEntityType(), actualReferenceSchema.getEntityType());
			assertEquals(expectedReferenceSchema.isReferencedEntityTypeManaged(), actualReferenceSchema.getReferencedEntityTypeManaged());
			if (expectedReferenceSchema.getReferencedGroupType() == null) {
				assertEquals(actualReferenceSchema.getGroupType().getDefaultInstanceForType(), actualReferenceSchema.getGroupType());
			} else {
				assertEquals(expectedReferenceSchema.getReferencedGroupType(), actualReferenceSchema.getGroupType().getValue());
			}
			assertEquals(expectedReferenceSchema.isReferencedGroupTypeManaged(), actualReferenceSchema.getReferencedGroupTypeManaged());
			assertEquals(expectedReferenceSchema.isIndexed(), actualReferenceSchema.getIndexed());
			assertEquals(expectedReferenceSchema.isFaceted(), actualReferenceSchema.getFaceted());
			assertEquals(
				expectedReferenceSchema.getIndexedComponentsInScopes().size(),
				actualReferenceSchema.getScopedIndexedComponentsList().size(),
				"Indexed components in scopes count mismatch for reference `" + expectedReferenceSchema.getName() + "`"
			);

			// assert bucketed histogram definitions
			final long expectedBucketedCount = expectedReferenceSchema.getAllHistogramIndexDefinitions()
				.values().stream().mapToLong(m -> m.size()).sum();
			assertEquals(
				expectedBucketedCount,
				actualReferenceSchema.getBucketedList().size(),
				"Bucketed histogram definitions count mismatch for reference `" + expectedReferenceSchema.getName() + "`"
			);
			for (GrpcScopedHistogramIndexDefinition grpcBucketed : actualReferenceSchema.getBucketedList()) {
				final Scope scope = EvitaEnumConverter.toScope(grpcBucketed.getScope());
				final HistogramIndexDefinition expectedDef =
					expectedReferenceSchema.getHistogramIndexDefinition(scope, grpcBucketed.getNameOfTheIndex());
				assertNotNull(
					expectedDef,
					"Expected bucketed histogram definition for scope " + scope +
						" in reference `" + expectedReferenceSchema.getName() + "`"
				);
				assertEquals(expectedDef.nameOfTheIndex(), grpcBucketed.getNameOfTheIndex());
			}

			// assert bucketedPartially expressions
			assertEquals(
				expectedReferenceSchema.getBucketedPartiallyInScopes().size(),
				actualReferenceSchema.getBucketedPartiallyList().size(),
				"Bucketed partially count mismatch for reference `" + expectedReferenceSchema.getName() + "`"
			);

			assertAttributes(expectedReferenceSchema.getAttributes(), actualReferenceSchema.getAttributesMap());
		}
	}

	public static void assertLocales(@Nonnull Set<Locale> expectedLocales, @Nonnull List<GrpcLocale> actualLocales) {
		assertEquals(expectedLocales.size(), actualLocales.size());
		for (Locale locale : expectedLocales) {
			assertTrue(actualLocales.stream().anyMatch(actualLocale ->
				actualLocale.getLanguageTag().equals(locale.toLanguageTag())));
		}
	}

	public static void assertCurrencies(@Nonnull Set<Currency> expectedCurrencies, @Nonnull List<GrpcCurrency> actualCurrencies) {
		assertEquals(expectedCurrencies.size(), actualCurrencies.size());
		for (Currency currency : expectedCurrencies) {
			assertTrue(actualCurrencies.stream().anyMatch(actualLocale ->
				actualLocale.getCode().equals(currency.getCurrencyCode()))
			);
		}
	}

	public static void assertEvolutionMode(@Nonnull Set<EvolutionMode> expectedEvolutionModes, @Nonnull List<GrpcEvolutionMode> actualEvolutionModes) {
		assertEquals(expectedEvolutionModes.size(), actualEvolutionModes.size());
		for (EvolutionMode evolutionMode : expectedEvolutionModes) {
			assertTrue(
				actualEvolutionModes.stream().anyMatch(
					actualEvolutionMode -> evolutionMode.equals(
						EvolutionMode.valueOf(actualEvolutionMode.name())
					)
				)
			);
		}
	}

	public static void assertBinaryEntity(@Nonnull BinaryEntity expectedEntity, @Nonnull GrpcBinaryEntity actualEntity) {
		assertEquals(expectedEntity.getType(), actualEntity.getEntityType());
		final byte[] entityStoragePart = expectedEntity.getEntityStoragePart();
		if (entityStoragePart == null) {
			assertTrue(actualEntity.getEntityStoragePart().isEmpty());
		} else {
			assertArrayEquals(entityStoragePart, actualEntity.getEntityStoragePart().toByteArray());
		}
		final byte[] priceStoragePart = expectedEntity.getPriceStoragePart();
		if (priceStoragePart == null) {
			assertTrue(actualEntity.getPriceStoragePart().isEmpty());
		} else {
			assertArrayEquals(priceStoragePart, actualEntity.getPriceStoragePart().toByteArray());
		}
		final byte[] referenceStoragePart = expectedEntity.getReferenceStoragePart();
		if (referenceStoragePart == null) {
			assertTrue(actualEntity.getReferenceStoragePart().isEmpty());
		} else {
			assertArrayEquals(referenceStoragePart, actualEntity.getReferenceStoragePart().toByteArray());
		}
		final byte[][] expectedAttributeStorageParts = expectedEntity.getAttributeStorageParts();
		for (int i = 0; i < expectedAttributeStorageParts.length; i++) {
			assertArrayEquals(expectedAttributeStorageParts[i], actualEntity.getAttributeStoragePartsList().get(i).toByteArray());
		}
		final byte[][] expectedAssociatedDataStorageParts = expectedEntity.getAssociatedDataStorageParts();
		for (int i = 0; i < expectedAssociatedDataStorageParts.length; i++) {
			assertArrayEquals(expectedAssociatedDataStorageParts[i], actualEntity.getAssociatedDataStoragePartsList().get(i).toByteArray());
		}
	}

	public static void assertHierarchy(
		@Nonnull Hierarchy hierarchy,
		@Nonnull GrpcHierarchy selfHierarchy,
		@Nonnull Map<String, GrpcHierarchy> referenceHierarchies
	) {
		assertHierarchy(hierarchy.getSelfHierarchy(), selfHierarchy.getHierarchyMap());
		assertEquals(hierarchy.getReferenceHierarchies().size(), referenceHierarchies.size());
		for (Entry<String, Map<String, List<LevelInfo>>> entry : hierarchy.getReferenceHierarchies().entrySet()) {
			assertHierarchy(
				entry.getValue(),
				referenceHierarchies.get(entry.getKey()).getHierarchyMap()
			);
		}
	}

	public static void assertHierarchy(
		@Nonnull Map<String, List<LevelInfo>> expectedHierarchy,
		@Nonnull Map<String, GrpcLevelInfos> actualHierarchy
	) {
		assertNotNull(expectedHierarchy);
		assertNotNull(actualHierarchy);
		assertEquals(expectedHierarchy.size(), actualHierarchy.size());

		for (Entry<String, List<LevelInfo>> entry : expectedHierarchy.entrySet()) {
			final GrpcLevelInfos grpcLevelInfos = actualHierarchy.get(entry.getKey());
			assertNotNull(grpcLevelInfos);
			final List<LevelInfo> expectedLevelInfos = entry.getValue();

			assertEquals(expectedLevelInfos.size(), grpcLevelInfos.getLevelInfosList().size());
			for (int i = 0; i < expectedLevelInfos.size(); i++) {
				final LevelInfo expectedLevelInfo = expectedLevelInfos.get(i);
				assertInnerStatistics(expectedLevelInfo, grpcLevelInfos.getLevelInfos(i));
			}
		}
	}

	public static void assertInnerStatistics(@Nonnull LevelInfo expectedChild, GrpcLevelInfo actualChild) {
		if (expectedChild.queriedEntityCount() == null) {
			assertFalse(actualChild.hasQueriedEntityCount());
		} else {
			assertEquals(expectedChild.queriedEntityCount(), actualChild.getQueriedEntityCount().getValue());
		}
		if (expectedChild.childrenCount() == null) {
			assertFalse(actualChild.hasChildrenCount());
		} else {
			assertEquals(expectedChild.childrenCount(), actualChild.getChildrenCount().getValue());
		}
		assertEquals(expectedChild.children().size(), actualChild.getItemsCount());
		assertEquals(expectedChild.requested(), actualChild.getRequested());

		final EntityClassifier expectedEntity = expectedChild.entity();
		if (expectedEntity instanceof EntityReference entityReference) {
			final GrpcEntityReference actualEntityReference = actualChild.getEntityReference();
			assertEntityReference(entityReference, actualEntityReference);
		} else if (expectedEntity instanceof SealedEntity sealedEntity) {
			final GrpcSealedEntity actualEntity = actualChild.getEntity();
			assertEntity(sealedEntity, actualEntity);
		}

		for (int i = 0; i < expectedChild.children().size(); i++) {
			final LevelInfo expectedGrandChild = expectedChild.children().get(i);
			final GrpcLevelInfo actualChildrenStatisticsList = actualChild.getItems(i);
			assertInnerStatistics(expectedGrandChild, actualChildrenStatisticsList);
		}
	}

	public static void assertAttributeHistograms(@Nonnull AttributeHistogram attributeHistogram, @Nonnull Map<String, GrpcHistogram> attributeHistogramMap) {
		for (Entry<String, GrpcHistogram> histogramEntry : attributeHistogramMap.entrySet()) {
			final HistogramContract expectedHistogram = attributeHistogram.getHistogram(histogramEntry.getKey());
			final GrpcHistogram actualHistogram = histogramEntry.getValue();

			if (expectedHistogram == null) {
				assertEquals(actualHistogram, actualHistogram.getDefaultInstanceForType());
			} else {
				assertHistograms(expectedHistogram, actualHistogram);
			}
		}
	}

	public static void assertPriceHistogram(@Nonnull PriceHistogram priceHistogram, @Nonnull GrpcHistogram histogram) {
		assertHistograms(priceHistogram, histogram);
	}

	public static void assertReferenceSummary(@Nonnull ReferenceSummary referenceSummary, @Nonnull List<GrpcReferenceGroupStatistics> allGrpcReferenceGroupStatistics) {
		for (GrpcReferenceGroupStatistics grpcReferenceGroupStatistics : allGrpcReferenceGroupStatistics) {
			final ReferenceGroupStatistics expectedReferenceGroupStatistics;
			if (grpcReferenceGroupStatistics.hasGroupEntityReference()) {
				expectedReferenceGroupStatistics = referenceSummary.getReferenceGroupStatistics(grpcReferenceGroupStatistics.getReferenceName(), grpcReferenceGroupStatistics.getGroupEntityReference().getPrimaryKey());
			} else if (grpcReferenceGroupStatistics.hasGroupEntity()) {
				expectedReferenceGroupStatistics = referenceSummary.getReferenceGroupStatistics(grpcReferenceGroupStatistics.getReferenceName(), grpcReferenceGroupStatistics.getGroupEntity().getPrimaryKey());
			} else {
				expectedReferenceGroupStatistics = referenceSummary.getReferenceGroupStatistics(grpcReferenceGroupStatistics.getReferenceName());
			}

			assertNotNull(expectedReferenceGroupStatistics);
			assertEquals(expectedReferenceGroupStatistics.getReferenceName(), grpcReferenceGroupStatistics.getReferenceName());
			final EntityClassifier expectedGroupEntity = expectedReferenceGroupStatistics.getGroupEntity();
			if (expectedGroupEntity == null) {
				assertFalse(grpcReferenceGroupStatistics.hasGroupEntity());
				assertFalse(grpcReferenceGroupStatistics.hasGroupEntityReference());
			} else if (expectedGroupEntity instanceof EntityReference) {
				assertEquals(expectedGroupEntity.getType(), grpcReferenceGroupStatistics.getGroupEntityReference().getEntityType());
				assertEquals(expectedGroupEntity.getPrimaryKey(), grpcReferenceGroupStatistics.getGroupEntityReference().getPrimaryKey());
				assertFalse(grpcReferenceGroupStatistics.hasGroupEntity());
			} else if (expectedGroupEntity instanceof SealedEntity entity) {
				assertEntity(entity, grpcReferenceGroupStatistics.getGroupEntity());
				assertFalse(grpcReferenceGroupStatistics.hasGroupEntityReference());
			}

			for (GrpcFacetStatistics actualFacetStatistics : grpcReferenceGroupStatistics.getFacetStatisticsList()) {
				final int facetId;
				if (actualFacetStatistics.hasFacetEntity()) {
					facetId = actualFacetStatistics.getFacetEntity().getPrimaryKey();
				} else {
					facetId = actualFacetStatistics.getFacetEntityReference().getPrimaryKey();
				}
				final FacetStatistics expectedFacetStatistics = expectedReferenceGroupStatistics.getFacetStatistics(facetId);
				assertNotNull(expectedFacetStatistics);

				final EntityClassifier expectedEntity = expectedFacetStatistics.getFacetEntity();
				if (expectedEntity instanceof EntityReference) {
					assertEquals(expectedEntity.getType(), actualFacetStatistics.getFacetEntityReference().getEntityType());
					assertEquals(expectedEntity.getPrimaryKey(), actualFacetStatistics.getFacetEntityReference().getPrimaryKey());
					assertFalse(actualFacetStatistics.hasFacetEntity());
				} else if (expectedEntity instanceof SealedEntity entity) {
					assertEntity(entity, actualFacetStatistics.getFacetEntity());
					assertFalse(actualFacetStatistics.hasFacetEntityReference());
				}

				assertEquals(expectedFacetStatistics.getCount(), actualFacetStatistics.getCount());
				assertEquals(expectedFacetStatistics.isRequested(), actualFacetStatistics.getRequested());
				final RequestImpact facetImpact = expectedFacetStatistics.getImpact();
				if (facetImpact == null) {
					assertFalse(actualFacetStatistics.hasImpact());
				} else {
					assertNotNull(expectedFacetStatistics.getImpact());
					assertEquals(expectedFacetStatistics.getImpact().difference(), actualFacetStatistics.getImpact().getValue());
				}

			}
		}
	}

	public static void assertFacetSummary(@Nonnull FacetSummary facetSummary, @Nonnull List<GrpcFacetGroupStatistics> allGrpcFacetGroupStatistics) {
		for (GrpcFacetGroupStatistics grpcFacetGroupStatistics : allGrpcFacetGroupStatistics) {
			final FacetGroupStatistics expectedFacetGroupStatistics;
			if (grpcFacetGroupStatistics.hasGroupEntityReference()) {
				expectedFacetGroupStatistics = facetSummary.getFacetGroupStatistics(grpcFacetGroupStatistics.getReferenceName(), grpcFacetGroupStatistics.getGroupEntityReference().getPrimaryKey());
			} else if (grpcFacetGroupStatistics.hasGroupEntity()) {
				expectedFacetGroupStatistics = facetSummary.getFacetGroupStatistics(grpcFacetGroupStatistics.getReferenceName(), grpcFacetGroupStatistics.getGroupEntity().getPrimaryKey());
			} else {
				expectedFacetGroupStatistics = facetSummary.getFacetGroupStatistics(grpcFacetGroupStatistics.getReferenceName());
			}

			assertNotNull(expectedFacetGroupStatistics);
			assertEquals(expectedFacetGroupStatistics.getReferenceName(), grpcFacetGroupStatistics.getReferenceName());
			final EntityClassifier expectedGroupEntity = expectedFacetGroupStatistics.getGroupEntity();
			if (expectedGroupEntity == null) {
				assertFalse(grpcFacetGroupStatistics.hasGroupEntity());
				assertFalse(grpcFacetGroupStatistics.hasGroupEntityReference());
			} else if (expectedGroupEntity instanceof EntityReference) {
				assertEquals(expectedGroupEntity.getType(), grpcFacetGroupStatistics.getGroupEntityReference().getEntityType());
				assertEquals(expectedGroupEntity.getPrimaryKey(), grpcFacetGroupStatistics.getGroupEntityReference().getPrimaryKey());
				assertFalse(grpcFacetGroupStatistics.hasGroupEntity());
			} else if (expectedGroupEntity instanceof SealedEntity entity) {
				assertEntity(entity, grpcFacetGroupStatistics.getGroupEntity());
				assertFalse(grpcFacetGroupStatistics.hasGroupEntityReference());
			}

			for (GrpcFacetStatistics actualFacetStatistics : grpcFacetGroupStatistics.getFacetStatisticsList()) {
				final int facetId;
				if (actualFacetStatistics.hasFacetEntity()) {
					facetId = actualFacetStatistics.getFacetEntity().getPrimaryKey();
				} else {
					facetId = actualFacetStatistics.getFacetEntityReference().getPrimaryKey();
				}
				final FacetStatistics expectedFacetStatistics = expectedFacetGroupStatistics.getFacetStatistics(facetId);
				assertNotNull(expectedFacetStatistics);

				final EntityClassifier expectedEntity = expectedFacetStatistics.getFacetEntity();
				if (expectedEntity instanceof EntityReference) {
					assertEquals(expectedEntity.getType(), actualFacetStatistics.getFacetEntityReference().getEntityType());
					assertEquals(expectedEntity.getPrimaryKey(), actualFacetStatistics.getFacetEntityReference().getPrimaryKey());
					assertFalse(actualFacetStatistics.hasFacetEntity());
				} else if (expectedEntity instanceof SealedEntity entity) {
					assertEntity(entity, actualFacetStatistics.getFacetEntity());
					assertFalse(actualFacetStatistics.hasFacetEntityReference());
				}

				assertEquals(expectedFacetStatistics.getCount(), actualFacetStatistics.getCount());
				assertEquals(expectedFacetStatistics.isRequested(), actualFacetStatistics.getRequested());
				final RequestImpact facetImpact = expectedFacetStatistics.getImpact();
				if (facetImpact == null) {
					assertFalse(actualFacetStatistics.hasImpact());
				} else {
					assertNotNull(expectedFacetStatistics.getImpact());
					assertEquals(expectedFacetStatistics.getImpact().difference(), actualFacetStatistics.getImpact().getValue());
				}

			}
		}
	}

	public static void assertEntityReference(@Nonnull EntityReference entityReference, @Nonnull GrpcEntityReference grpcEntityReference) {
		assertEquals(entityReference.getPrimaryKey(), grpcEntityReference.getPrimaryKey());
		assertEquals(entityReference.getType(), grpcEntityReference.getEntityType());
	}

	public static void assertEntity(@Nonnull SealedEntity sealedEntity, @Nonnull GrpcSealedEntity enrichedEntity) {
		final Set<Locale> locales = sealedEntity.getLocales();

		//attributes
		assertAttributes(
			sealedEntity.attributesAvailable() ? sealedEntity.getAttributeValues() : Collections.emptyList(),
			enrichedEntity.getLocalizedAttributesMap(),
			enrichedEntity.getGlobalAttributesMap(),
			locales
		);

		//associated data
		final List<AssociatedDataContract.AssociatedDataValue> associatedDataValues;
		if (!sealedEntity.associatedDataAvailable()) {
			associatedDataValues = Collections.emptyList();
		} else if (locales.size() > 1) {
			associatedDataValues = sealedEntity.getAssociatedDataValues().stream().toList();
		} else if (locales.size() == 1) {
			associatedDataValues = sealedEntity.getAssociatedDataValues().stream().filter(
				a -> a.key().locale() == null || a.key().locale().getLanguage().equals(
					locales.stream().findFirst().orElseThrow(() -> new IllegalArgumentException("Suitable locale not found!")).getLanguage()
				)
			).toList();
		} else {
			associatedDataValues = sealedEntity.getAssociatedDataValues().stream().filter(a -> a.key().locale() == null).toList();
		}

		assertEquals(
			associatedDataValues.size(),
			getAssociatedDataCount(enrichedEntity)
		);

		for (final AssociatedDataContract.AssociatedDataValue associatedDataValue : associatedDataValues) {
			final GrpcEvitaAssociatedDataValue actualAssociatedDataValue;
			if (associatedDataValue.key().localized()) {
				final GrpcLocalizedAssociatedData localizedAssociatedData = enrichedEntity.getLocalizedAssociatedDataMap().get(associatedDataValue.key().locale().toLanguageTag());
				actualAssociatedDataValue = localizedAssociatedData.getAssociatedDataMap().get(associatedDataValue.key().associatedDataName());
			} else {
				actualAssociatedDataValue = enrichedEntity.getGlobalAssociatedDataMap().get(associatedDataValue.key().associatedDataName());
			}
			assertAssociatedData(associatedDataValue, actualAssociatedDataValue);
		}

		//prices
		if (sealedEntity.pricesAvailable()) {
			final Collection<PriceContract> priceValues = sealedEntity.getPrices();
			final Collection<GrpcPrice> actualPriceValues = enrichedEntity.getPricesList();
			assertEquals(priceValues.size(), actualPriceValues.size());
			for (PriceContract price : priceValues) {
				final GrpcPrice actualPrice = actualPriceValues.stream()
					.filter(
						p ->
							p.getCurrency().getCode().equals(price.currency().getCurrencyCode()) &&
								p.getPriceId() == price.priceId() &&
								p.getPriceList().equals(price.priceList())
					)
					.findFirst()
					.orElse(null);
				assertNotNull(actualPrice);
				assertPrice(price, actualPrice);
			}

			try {
				assertPrice(
					sealedEntity.getPriceInnerRecordHandling() != PriceInnerRecordHandling.UNKNOWN ? sealedEntity.getPriceForSale().orElse(null) : null,
					enrichedEntity.getPriceForSale()
				);
			} catch (ContextMissingException ex) {
				//
			}
		}

		assertEquals(
			sealedEntity.getPriceInnerRecordHandling().toString(),
			enrichedEntity.getPriceInnerRecordHandling().toString()
		);

		//references
		final Collection<ReferenceContract> references = sealedEntity.referencesAvailable() ?
			sealedEntity.getReferences() : Collections.emptyList();
		final Collection<GrpcReference> actualReferences = enrichedEntity.getReferencesList();
		assertEquals(references.size(), actualReferences.size());
		for (ReferenceContract reference : references) {
			assertReference(
				reference,
				Objects.requireNonNull(
					actualReferences.stream().filter(r -> {
							if (r.hasReferencedEntity()) {
								return r.getReferencedEntity().getPrimaryKey() == reference.getReferencedPrimaryKey() &&
									r.getReferenceName().equals(reference.getReferenceName());
							} else {
								return r.getReferencedEntityReference().getPrimaryKey() == reference.getReferencedPrimaryKey() &&
									r.getReferenceName().equals(reference.getReferenceName());
							}
						}
					).findFirst().orElse(null)
				),
				locales
			);
		}
	}

	public static void assertAttributeValues(@Nonnull AttributesContract.AttributeValue expectedAttributeValue, @Nonnull GrpcEvitaValue actualAttributeValue) {
		final GrpcEvitaValue expectedValue = EvitaDataTypesConverter.toGrpcEvitaValue(expectedAttributeValue.value(), expectedAttributeValue.version());
		assertEquals(expectedValue, actualAttributeValue);
	}

	public static void assertAssociatedData(@Nonnull AssociatedDataContract.AssociatedDataValue expectedAssociatedDataValue, @Nonnull GrpcEvitaAssociatedDataValue actualAssociatedDataValue) {
		final GrpcEvitaAssociatedDataValue expectedValue = EvitaDataTypesConverter.toGrpcEvitaAssociatedDataValue(
			expectedAssociatedDataValue.value(),
			expectedAssociatedDataValue.version(),
			AssociatedDataForm.STRUCTURED_VALUE
		);
		assertEquals(expectedValue, actualAssociatedDataValue);
	}

	public static void assertPrice(@Nullable PriceContract expectedPrice, @Nonnull GrpcPrice actualPrice) {
		if (expectedPrice == null) {
			assertEquals(actualPrice, GrpcPrice.getDefaultInstance());
			return;
		}
		final BigDecimal priceWithoutTax = EvitaDataTypesConverter.toBigDecimal(actualPrice.getPriceWithoutTax());
		final BigDecimal priceWithTax = EvitaDataTypesConverter.toBigDecimal(actualPrice.getPriceWithTax());
		final BigDecimal priceTaxRate = EvitaDataTypesConverter.toBigDecimal(actualPrice.getTaxRate());
		assertEquals(expectedPrice.priceWithoutTax(), priceWithoutTax);
		assertEquals(expectedPrice.priceWithTax(), priceWithTax);
		assertEquals(expectedPrice.taxRate(), priceTaxRate);
		assertEquals(expectedPrice.priceId(), actualPrice.getPriceId());
		assertEquals(expectedPrice.priceKey().priceList(), actualPrice.getPriceList());
		assertEquals(expectedPrice.priceKey().currency().getCurrencyCode(), actualPrice.getCurrency().getCode());

		final Integer priceInnerRecordId = actualPrice.hasInnerRecordId() ? actualPrice.getInnerRecordId().getValue() : null;

		assertEquals(expectedPrice.innerRecordId(), priceInnerRecordId);
		assertEquals(expectedPrice.indexed(), actualPrice.getSellable());
		assertEquals(expectedPrice.indexed(), actualPrice.getIndexed());

		final DateTimeRange expectedDateTimeRange = expectedPrice.validity();

		if (expectedDateTimeRange != null) {
			final DateTimeRange actualValidity = EvitaDataTypesConverter.toDateTimeRange(actualPrice.getValidity());
			if (expectedDateTimeRange.getPreciseFrom() == null) {
				assertNull(actualValidity.getPreciseFrom());
			} else if (LocalDateTime.MIN.toInstant(ZoneOffset.UTC).equals(expectedDateTimeRange.getPreciseFrom().toInstant())) {
				assertEquals(GRPC_MIN_INSTANT, actualValidity.getPreciseFrom().toInstant());
			} else {
				assertEquals(expectedDateTimeRange.getPreciseFrom().toInstant(), actualValidity.getPreciseFrom().toInstant());
			}
			if (expectedDateTimeRange.getPreciseTo() == null) {
				assertNull(actualValidity.getPreciseTo());
			} else if (LocalDateTime.MAX.toInstant(ZoneOffset.UTC).equals(expectedDateTimeRange.getPreciseTo().toInstant())) {
				assertEquals(GRPC_MAX_INSTANT, actualValidity.getPreciseTo().toInstant());
			} else {
				assertEquals(expectedDateTimeRange.getPreciseTo().toInstant(), actualValidity.getPreciseTo().toInstant());
			}
		}
	}

	public static void assertReference(@Nonnull ReferenceContract expectedReference, @Nonnull GrpcReference actualReference, Set<Locale> locales) {
		if (expectedReference.getReferencedEntity().isPresent()) {
			assertEntity(expectedReference.getReferencedEntity().get(), actualReference.getReferencedEntity());
		} else {
			assertEquals(expectedReference.getReferencedPrimaryKey(), actualReference.getReferencedEntityReference().getPrimaryKey());
			assertEquals(expectedReference.getReferencedEntityType(), actualReference.getReferencedEntityReference().getEntityType());
		}
		assertEquals(GrpcCardinality.valueOf(expectedReference.getReferenceCardinality().name()), actualReference.getReferenceCardinality());

		if (expectedReference.getGroupEntity().isPresent()) {
			assertEntity(expectedReference.getGroupEntity().get(), actualReference.getGroupReferencedEntity());
		} else if (expectedReference.getGroup().isPresent()) {
			assertEquals(expectedReference.getGroup().get().getType(), actualReference.getGroupReferencedEntityReference().getEntityType());
			assertEquals(expectedReference.getGroup().get().getPrimaryKey(), actualReference.getGroupReferencedEntityReference().getPrimaryKey());
		} else {
			assertFalse(actualReference.hasGroupReferencedEntityReference());
			assertFalse(actualReference.hasGroupReferencedEntity());
		}

		assertAttributes(
			expectedReference.attributesAvailable() ? expectedReference.getAttributeValues() : Collections.emptyList(),
			expectedReference.attributesAvailable() ? actualReference.getLocalizedAttributesMap() : Collections.emptyMap(),
			expectedReference.attributesAvailable() ? actualReference.getGlobalAttributesMap() : Collections.emptyMap(),
			locales
		);
	}

	public static void assertHistograms(@Nonnull HistogramContract expectedHistogram, @Nonnull GrpcHistogram actualHistogram) {
		final GrpcBigDecimal histogramMin = actualHistogram.getMin();
		assertEquals(expectedHistogram.getMin(), EvitaDataTypesConverter.toBigDecimal(histogramMin));
		final GrpcBigDecimal histogramMax = actualHistogram.getMax();
		assertEquals(expectedHistogram.getMax(), EvitaDataTypesConverter.toBigDecimal(histogramMax));
		assertEquals(expectedHistogram.getOverallCount(), actualHistogram.getOverallCount());
		assertEquals(expectedHistogram.getBuckets().length, actualHistogram.getBucketsCount());

		for (int i = 0; i < expectedHistogram.getBuckets().length; i++) {
			final HistogramContract.Bucket expectedBucket = expectedHistogram.getBuckets()[i];
			final GrpcHistogram.GrpcBucket actualBucket = actualHistogram.getBucketsList().get(i);
			final GrpcBigDecimal bucketThreshold = actualBucket.getThreshold();
			assertEquals(expectedBucket.threshold(), EvitaDataTypesConverter.toBigDecimal(bucketThreshold));
			assertEquals(expectedBucket.occurrences(), actualBucket.getOccurrences());
			assertEquals(expectedBucket.requested(), actualBucket.getRequested());
			assertEquals(expectedBucket.relativeFrequency(), EvitaDataTypesConverter.toBigDecimal(actualBucket.getRelativeFrequency()));
		}
	}

	public static void assertAttributes(Collection<AttributeValue> attributeValues, Map<String, GrpcLocalizedAttribute> localizedAttributesMap, Map<String, GrpcEvitaValue> globalAttributesMap, Set<Locale> locales) {
		final List<AttributesContract.AttributeValue> expectedAttributeValues;
		if (locales.size() > 1) {
			expectedAttributeValues = attributeValues.stream().toList();
		} else if (locales.size() == 1) {
			expectedAttributeValues = attributeValues.stream().filter(
				a -> a.key().locale() == null || a.key().locale().getLanguage().equals(
					locales.stream()
						.findFirst()
						.orElseThrow(() -> new IllegalArgumentException("Suitable locale not found!"))
						.getLanguage()
				)
			).toList();
		} else {
			expectedAttributeValues = attributeValues.stream().filter(a -> a.key().locale() == null).toList();
		}

		assertEquals(
			expectedAttributeValues.size(),
			getAttributeCount(localizedAttributesMap, globalAttributesMap)
		);

		if (expectedAttributeValues.isEmpty())
			return;

		for (final AttributesContract.AttributeValue attributeValue : expectedAttributeValues) {
			final GrpcEvitaValue actualAttributeValue;
			if (attributeValue.key().localized()) {
				final GrpcLocalizedAttribute localizedAttributes = localizedAttributesMap.get(attributeValue.key().locale().toLanguageTag());
				actualAttributeValue = localizedAttributes.getAttributesMap().get(attributeValue.key().attributeName());
			} else {
				actualAttributeValue = globalAttributesMap.get(attributeValue.key().attributeName());
			}
			assertAttributeValues(attributeValue, actualAttributeValue);
		}
	}

	private static int getAssociatedDataCount(@Nonnull GrpcSealedEntity enrichedEntity) {
		return enrichedEntity.getGlobalAssociatedDataCount() +
			enrichedEntity.getLocalizedAssociatedDataMap()
				.values()
				.stream()
				.mapToInt(GrpcLocalizedAssociatedData::getAssociatedDataCount)
				.sum();
	}

	private static int getAttributeCount(Map<String, GrpcLocalizedAttribute> localizedAttributesMap, Map<String, GrpcEvitaValue> globalAttributesMap) {
		return globalAttributesMap.size() +
			localizedAttributesMap.values()
				.stream()
				.mapToInt(GrpcLocalizedAttribute::getAttributesCount)
				.sum();
	}

	public static void assertQueryTelemetry(@Nonnull QueryTelemetry expectedQueryTelemetry, @Nonnull GrpcQueryTelemetry actualQueryTelemetry) {
		assertQueryTelemetry(expectedQueryTelemetry, actualQueryTelemetry, expectedQueryTelemetry.getStart());
	}

	/**
	 * Recursively compares a single node of the expected telemetry tree with its gRPC counterpart.
	 *
	 * @param rootStart raw `nanoTime` reading of the root step of the expected tree - the gRPC form reports starts
	 *                  as offsets from it rather than as raw readings
	 */
	private static void assertQueryTelemetry(@Nonnull QueryTelemetry expectedQueryTelemetry, @Nonnull GrpcQueryTelemetry actualQueryTelemetry, long rootStart) {
		assertEquals(expectedQueryTelemetry.getOperation(), QueryPhase.valueOf(actualQueryTelemetry.getOperation().name()));
		assertEquals(expectedQueryTelemetry.getStart() - rootStart, actualQueryTelemetry.getStart());
		assertEquals(expectedQueryTelemetry.getSpentTime(), actualQueryTelemetry.getSpentTime());
		// self time is derived at the gRPC boundary - the engine object does not carry it
		long expectedChildrenTime = 0;
		for (QueryTelemetry step : expectedQueryTelemetry.getSteps()) {
			expectedChildrenTime += step.getSpentTime();
		}
		assertEquals(
			Math.max(0, expectedQueryTelemetry.getSpentTime() - expectedChildrenTime),
			actualQueryTelemetry.getSelfTime()
		);
		assertArrayEquals(Arrays.stream(expectedQueryTelemetry.getArguments()).map(Object::toString).toArray(), actualQueryTelemetry.getArgumentsList().toArray());
		assertQueryTelemetryMetrics(expectedQueryTelemetry, actualQueryTelemetry);
		assertQueryTelemetryPlan(expectedQueryTelemetry, actualQueryTelemetry);
		// the wall-clock anchor belongs to the root alone - every other node derives its position from it
		assertEquals(expectedQueryTelemetry.getStartedAt() != null, actualQueryTelemetry.hasStartedAt());
		assertEquals(expectedQueryTelemetry.getSteps().size(), actualQueryTelemetry.getStepsCount());
		for (QueryTelemetry queryTelemetry : expectedQueryTelemetry.getSteps()) {
			assertQueryTelemetry(queryTelemetry, actualQueryTelemetry.getStepsList().get(expectedQueryTelemetry.getSteps().indexOf(queryTelemetry)), rootStart);
		}
	}

	/**
	 * Compares the formula plan of a single telemetry node with the one its gRPC counterpart carries.
	 *
	 * The absent-vs-zero rule of the metrics applies here too and matters more: a node the engine never executed
	 * reports no `actualCost` and no `resultCount` at all, and a wire form that defaulted those to zero would
	 * present a rejected plan alternative as one that ran and matched nothing. Presence is therefore asserted
	 * before value, on every node of the tree.
	 *
	 * @param expected node as the engine rendered it
	 * @param actual   the same node as it travels on the wire
	 */
	private static void assertQueryTelemetryPlan(
		@Nonnull QueryTelemetry expected,
		@Nonnull GrpcQueryTelemetry actual
	) {
		assertEquals(expected.getPlan() != null, actual.hasPlan(), "Presence of the formula plan differs!");
		if (expected.getPlan() != null) {
			assertFormulaPlan(expected.getPlan(), actual.getPlan());
		}
	}

	/**
	 * Recursively compares one node of a formula plan with its gRPC counterpart.
	 *
	 * @param expected plan node as the engine rendered it
	 * @param actual   the same node as it travels on the wire
	 */
	private static void assertFormulaPlan(@Nonnull FormulaPlan expected, @Nonnull GrpcFormulaPlan actual) {
		assertEquals(expected.id(), actual.getId());
		assertEquals(expected.hash(), actual.getHash());
		assertEquals(expected.estimatedCost(), actual.getEstimatedCost());
		// a repeat occurrence points back at the node that describes it and carries nothing else - collapsing that
		// into a full copy would invite a reader to count a shared sub-tree's cost twice
		assertEquals(expected.refTo() != null, actual.hasRefTo(), "Presence of `refTo` differs!");
		if (expected.refTo() != null) {
			assertEquals(expected.refTo().intValue(), actual.getRefTo());
		}
		assertEquals(expected.description() != null, actual.hasDescription(), "Presence of `description` differs!");
		if (expected.description() != null) {
			assertEquals(expected.description(), actual.getDescription());
		}
		// the two that say whether this node ran at all
		assertEquals(expected.actualCost() != null, actual.hasActualCost(), "Presence of `actualCost` differs!");
		if (expected.actualCost() != null) {
			assertEquals(expected.actualCost().longValue(), actual.getActualCost());
		}
		assertEquals(expected.resultCount() != null, actual.hasResultCount(), "Presence of `resultCount` differs!");
		if (expected.resultCount() != null) {
			assertEquals(expected.resultCount().intValue(), actual.getResultCount());
		}
		assertEquals(expected.children().size(), actual.getChildrenCount(), "Plan children count differs!");
		for (int i = 0; i < expected.children().size(); i++) {
			assertFormulaPlan(expected.children().get(i), actual.getChildren(i));
		}
	}

	/**
	 * Compares the typed metrics of a single telemetry node with the ones its gRPC counterpart carries.
	 *
	 * Unlike `selfTime`, which the boundary derives, these are measurements: the wire form has to reproduce them
	 * exactly, including *which* of them were taken. A metric the engine never measured must stay unset rather than
	 * arriving as a zero, which is why presence is asserted alongside the value - several of these are legitimately
	 * zero, so comparing values alone would let a "defaults to 0" regression through.
	 *
	 * @param expectedQueryTelemetry node as the engine measured it
	 * @param actualQueryTelemetry   the same node as it travels on the wire
	 */
	private static void assertQueryTelemetryMetrics(
		@Nonnull QueryTelemetry expected,
		@Nonnull GrpcQueryTelemetry actual
	) {
		assertEquals(expected.hasMetrics(), actual.hasMetrics());
		if (!expected.hasMetrics()) {
			return;
		}
		final GrpcQueryTelemetryMetrics metrics = actual.getMetrics();
		assertMetric(
			expected, StepMetric.ESTIMATED_CARDINALITY,
			metrics.hasEstimatedCardinality(), metrics.getEstimatedCardinality()
		);
		assertMetric(
			expected, StepMetric.ACTUAL_CARDINALITY,
			metrics.hasActualCardinality(), metrics.getActualCardinality()
		);
		assertMetric(expected, StepMetric.ESTIMATED_COST, metrics.hasEstimatedCost(), metrics.getEstimatedCost());
		assertMetric(expected, StepMetric.ACTUAL_COST, metrics.hasActualCost(), metrics.getActualCost());
		assertMetric(
			expected, StepMetric.RECORDS_RETURNED,
			metrics.hasRecordsReturned(), metrics.getRecordsReturned()
		);
		assertMetric(expected, StepMetric.IO_FETCH_COUNT, metrics.hasIoFetchCount(), metrics.getIoFetchCount());
		assertMetric(
			expected, StepMetric.IO_FETCHED_SIZE_BYTES,
			metrics.hasIoFetchedSizeBytes(), metrics.getIoFetchedSizeBytes()
		);
		// the engine packs flags as 1/0 into the same numeric container; the wire form publishes them as booleans
		assertMetric(
			expected, StepMetric.PREFETCHED,
			metrics.hasPrefetched(), metrics.getPrefetched() ? 1L : 0L
		);
	}

	/**
	 * Compares one metric of a telemetry node against the wire form, asserting presence before value.
	 *
	 * @param expected        node as the engine measured it
	 * @param metric          the measurement being compared
	 * @param actuallyPresent whether the wire form carries it
	 * @param actualValue     the value the wire form carries, meaningful only when it is present
	 */
	private static void assertMetric(
		@Nonnull QueryTelemetry expected,
		@Nonnull StepMetric metric,
		boolean actuallyPresent,
		long actualValue
	) {
		final OptionalLong expectedValue = expected.getMetric(metric);
		assertEquals(expectedValue.isPresent(), actuallyPresent, "Presence of `" + metric + "` differs!");
		if (expectedValue.isPresent()) {
			assertEquals(expectedValue.getAsLong(), actualValue, "Value of `" + metric + "` differs!");
		}
	}
}
