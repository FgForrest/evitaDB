/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
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
import io.evitadb.api.requestResponse.extraResult.FacetSummary.FacetStatistics;
import io.evitadb.api.requestResponse.extraResult.FacetSummary.RequestImpact;
import io.evitadb.api.requestResponse.extraResult.HierarchyParents.ParentsByReference;
import io.evitadb.api.requestResponse.extraResult.Hierarchy;
import io.evitadb.api.requestResponse.extraResult.Hierarchy.LevelInfo;
import io.evitadb.api.requestResponse.extraResult.HistogramContract;
import io.evitadb.api.requestResponse.extraResult.PriceHistogram;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry.QueryPhase;
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EvolutionMode;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter;
import io.evitadb.externalApi.grpc.generated.*;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.Collection;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;

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
			assertEquals(expectedAttributeSchema.isUniqueGlobally(), actualAttributeSchema.getUniqueGlobally());
		}
	}

	public static void assertAttributes(@Nonnull Map<String, AttributeSchemaContract> expectedAttributesMap, @Nonnull Map<String, GrpcAttributeSchema> actualAttributesMap) {
		assertEquals(expectedAttributesMap.size(), actualAttributesMap.size());
		for (Map.Entry<String, AttributeSchemaContract> expectedAttributeEntry : expectedAttributesMap.entrySet()) {
			final AttributeSchemaContract expectedAttributeSchema = expectedAttributeEntry.getValue();
			final GrpcAttributeSchema actualAttributeSchema = actualAttributesMap.get(expectedAttributeEntry.getKey());
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
			assertEquals(expectedReferenceSchema.isReferencedEntityTypeManaged(), actualReferenceSchema.getEntityTypeRelatesToEntity());
			if (expectedReferenceSchema.getReferencedGroupType() == null) {
				assertEquals(actualReferenceSchema.getGroupType().getDefaultInstanceForType(), actualReferenceSchema.getGroupType());
			} else {
				assertEquals(expectedReferenceSchema.getReferencedGroupType(), actualReferenceSchema.getGroupType().getValue());
			}
			assertEquals(expectedReferenceSchema.isReferencedGroupTypeManaged(), actualReferenceSchema.getGroupTypeRelatesToEntity());
			assertEquals(expectedReferenceSchema.isFilterable(), actualReferenceSchema.getIndexed());
			assertEquals(expectedReferenceSchema.isFaceted(), actualReferenceSchema.getFaceted());
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

	public static void assertStatistics(@Nonnull Hierarchy hierarchy, @Nonnull Map<String, GrpcLevelInfos> hierarchyStatisticsMap, @Nonnull String entityType) {
		/* TODO LHO alter assertion
		final Map<String, List<LevelInfo>> expectedLevelInfos = hierarchyStatistics.getStatistics(entityType);
		final GrpcLevelInfos actualLevelInfos = hierarchyStatisticsMap.get(entityType);

		assertStatistics(expectedLevelInfos, actualLevelInfos);
		 */
	}

	public static void assertStatistics(Map<String, List<LevelInfo>> expectedLevelInfos, GrpcLevelInfos actualLevelInfos) {
		assertNotNull(expectedLevelInfos);
		assertNotNull(actualLevelInfos);

		/*
		TODO LHO alter assertion
		for (int i = 0; i < expectedLevelInfos.size(); i++) {
			final LevelInfo expectedLevelInfo = expectedLevelInfos.get(i);
			final GrpcLevelInfo actualLevelInfo = actualLevelInfos.getLevelInfosList().get(i);

			if (expectedLevelInfo.entity() instanceof EntityReference) {
				assertEquals(expectedLevelInfo.entity().getPrimaryKey(), actualLevelInfo.getEntityReference().getPrimaryKey());
			} else if (expectedLevelInfo.entity() instanceof SealedEntity sealedEntity) {
				assertEquals(sealedEntity.getPrimaryKey(), actualLevelInfo.getEntity().getPrimaryKey());
			} else {
				fail("Unsupported entity type");
			}
			assertEquals(expectedLevelInfo.cardinality(), actualLevelInfo.getCardinality());
			assertEquals(expectedLevelInfo.childrenStatistics().size(), actualLevelInfo.getChildrenStatisticsCount());
			for (int j = 0; j < expectedLevelInfo.childrenStatistics().size(); j++) {
				final LevelInfo expectedChild = expectedLevelInfo.childrenStatistics().get(j);
				final GrpcLevelInfo actualChild = actualLevelInfo.getChildrenStatisticsList().get(j);
				assertInnerStatistics(expectedChild, actualChild);
			}
		}
		 */
	}

	public static <T extends Serializable> void assertInnerStatistics(@Nonnull LevelInfo expectedChild, GrpcLevelInfo actualChild) {
		assertEquals(expectedChild.queriedEntityCount(), actualChild.getCardinality());
		assertEquals(expectedChild.children().size(), actualChild.getChildrenStatisticsCount());
		if (expectedChild.children().isEmpty()) {
			return;
		}

		for (int i = 0; i < expectedChild.children().size(); i++) {
			final LevelInfo expectedGrandChild = expectedChild.children().get(i);
			final GrpcLevelInfo actualChildrenStatisticsList = actualChild.getChildrenStatistics(i);
			assertInnerStatistics(expectedGrandChild, actualChildrenStatisticsList);
		}
	}

	public static <T extends Serializable> void assertParents(@Nullable ParentsByReference expectedParents, @Nullable GrpcHierarchyParentsByReference actualParents) {
		assertNotNull(expectedParents);
		assertNotNull(actualParents);

		for (Entry<Integer, Map<Integer, EntityClassifier[]>> parentEntitiesEntry : expectedParents.getParents().entrySet()) {
			final GrpcHierarchyParentEntities parentEntities = actualParents.getHierarchyParentsByReferenceMap().get(parentEntitiesEntry.getKey());
			assertNotNull(parentEntities);
			for (Entry<Integer, EntityClassifier[]> parentEntity : parentEntitiesEntry.getValue().entrySet()) {
				final GrpcHierarchyParentEntity actualEntity = parentEntities.getHierarchyParentEntitiesMap().get(parentEntity.getKey());
				assertNotNull(actualEntity);
				for (int i = 0; i < parentEntity.getValue().length; i++) {
					final EntityClassifier expectedParent = parentEntity.getValue()[i];
					assertNotNull(expectedParent);
					if (expectedParent instanceof SealedEntity sealedEntity) {
						final GrpcSealedEntity actualParent = actualEntity.getEntitiesList().get(i);
						assertEquals(sealedEntity.getPrimaryKey(), actualParent.getPrimaryKey());
						assertEquals(sealedEntity.getType(), actualParent.getEntityType());
						assertEquals(sealedEntity.getAssociatedDataNames().size(), getAssociatedDataCount(actualParent));
						assertEquals(sealedEntity.getAttributeNames().size(), getAttributeCount(actualParent.getLocalizedAttributesMap(), actualParent.getGlobalAttributesMap()));
						assertEquals(sealedEntity.getReferences().size(), actualParent.getReferencesCount());
						assertEquals(sealedEntity.getPrices().size(), actualParent.getPricesCount());
					} else if (expectedParent instanceof EntityReference entityReference) {
						final GrpcEntityReference actualParent = actualEntity.getEntityReferencesList().get(i);
						assertEquals(entityReference.getPrimaryKey(), actualParent.getPrimaryKey());
						assertEquals(entityReference.getType(), actualParent.getEntityType());
					} else {
						fail("Unsupported entity type");
					}
				}
			}
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

				final EntityClassifier expectedEntity = expectedFacetStatistics.facetEntity();
				if (expectedEntity instanceof EntityReference) {
					assertEquals(expectedEntity.getType(), actualFacetStatistics.getFacetEntityReference().getEntityType());
					assertEquals(expectedEntity.getPrimaryKey(), actualFacetStatistics.getFacetEntityReference().getPrimaryKey());
					assertFalse(actualFacetStatistics.hasFacetEntity());
				} else if (expectedEntity instanceof SealedEntity entity) {
					assertEntity(entity, actualFacetStatistics.getFacetEntity());
					assertFalse(actualFacetStatistics.hasFacetEntityReference());
				}

				assertEquals(expectedFacetStatistics.count(), actualFacetStatistics.getCount());
				assertEquals(expectedFacetStatistics.requested(), actualFacetStatistics.getRequested());
				final RequestImpact facetImpact = expectedFacetStatistics.impact();
				if (facetImpact == null) {
					assertFalse(actualFacetStatistics.hasImpact());
				} else {
					assertNotNull(expectedFacetStatistics.impact());
					assertEquals(expectedFacetStatistics.impact().difference(), actualFacetStatistics.getImpact().getValue());
				}

			}
		}
	}

	public static void assertEntity(@Nonnull SealedEntity sealedEntity, @Nonnull GrpcSealedEntity enrichedEntity) {
		final Set<Locale> locales = sealedEntity.getLocales();

		//attributes
		assertAttributes(
			sealedEntity.getAttributeValues(),
			enrichedEntity.getLocalizedAttributesMap(),
			enrichedEntity.getGlobalAttributesMap(),
			locales
		);

		//associated data
		final List<AssociatedDataContract.AssociatedDataValue> associatedDataValues;
		if (locales.size() > 1) {
			associatedDataValues = sealedEntity.getAssociatedDataValues().stream().toList();
		} else if (locales.size() == 1) {
			associatedDataValues = sealedEntity.getAssociatedDataValues().stream().filter(
				a -> a.getKey().getLocale() == null ||
					a.getKey().getLocale() != null && a.getKey().getLocale().getLanguage().equals(
						locales.stream().findFirst().orElseThrow(() -> new IllegalArgumentException("Suitable locale not found!")).getLanguage()
					)
			).toList();
		} else {
			associatedDataValues = sealedEntity.getAssociatedDataValues().stream().filter(a -> a.getKey().getLocale() == null).toList();
		}

		assertEquals(
			associatedDataValues.size(),
			getAssociatedDataCount(enrichedEntity)
		);

		for (final AssociatedDataContract.AssociatedDataValue associatedDataValue : associatedDataValues) {
			final GrpcEvitaAssociatedDataValue actualAssociatedDataValue;
			if (associatedDataValue.getKey().isLocalized()) {
				final GrpcLocalizedAssociatedData localizedAssociatedData = enrichedEntity.getLocalizedAssociatedDataMap().get(associatedDataValue.getKey().getLocale().toLanguageTag());
				actualAssociatedDataValue = localizedAssociatedData.getAssociatedDataMap().get(associatedDataValue.getKey().getAssociatedDataName());
			} else {
				actualAssociatedDataValue = enrichedEntity.getGlobalAssociatedDataMap().get(associatedDataValue.getKey().getAssociatedDataName());
			}
			assertAssociatedData(associatedDataValue, actualAssociatedDataValue);
		}

		//prices
		final Collection<PriceContract> priceValues = sealedEntity.getPrices();
		final Collection<GrpcPrice> actualPriceValues = enrichedEntity.getPricesList();
		assertEquals(priceValues.size(), actualPriceValues.size());
		for (PriceContract price : priceValues) {
			final GrpcPrice actualPrice = actualPriceValues.stream()
				.filter(
					p ->
						p.getCurrency().getCode().equals(price.getCurrency().getCurrencyCode()) &&
							p.getPriceId() == price.getPriceId() &&
							p.getPriceList().equals(price.getPriceList())
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

		assertEquals(
			sealedEntity.getPriceInnerRecordHandling().toString(),
			enrichedEntity.getPriceInnerRecordHandling().toString()
		);

		//references
		final Collection<ReferenceContract> references = sealedEntity.getReferences();
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
			//TOBEDONE #45: LATER HANDLE SWITCH BETWEEN REFERENCE AND WHOLE ENTITY
		}
	}

	public static void assertAttributeValues(@Nonnull AttributesContract.AttributeValue expectedAttributeValue, @Nonnull GrpcEvitaValue actualAttributeValue) {
		final GrpcEvitaValue expectedValue = EvitaDataTypesConverter.toGrpcEvitaValue(expectedAttributeValue.getValue(), expectedAttributeValue.getVersion());
		assertEquals(expectedValue, actualAttributeValue);
	}

	public static void assertAssociatedData(@Nonnull AssociatedDataContract.AssociatedDataValue expectedAssociatedDataValue, @Nonnull GrpcEvitaAssociatedDataValue actualAssociatedDataValue) {
		final GrpcEvitaAssociatedDataValue expectedValue = EvitaDataTypesConverter.toGrpcEvitaAssociatedDataValue(expectedAssociatedDataValue.getValue(), expectedAssociatedDataValue.getVersion());
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
		assertEquals(expectedPrice.getPriceWithoutTax(), priceWithoutTax);
		assertEquals(expectedPrice.getPriceWithTax(), priceWithTax);
		assertEquals(expectedPrice.getTaxRate(), priceTaxRate);
		assertEquals(expectedPrice.getPriceId(), actualPrice.getPriceId());
		assertEquals(expectedPrice.getPriceKey().getPriceList(), actualPrice.getPriceList());
		assertEquals(expectedPrice.getPriceKey().getCurrency().getCurrencyCode(), actualPrice.getCurrency().getCode());

		final Integer priceInnerRecordId = actualPrice.hasInnerRecordId() ? actualPrice.getInnerRecordId().getValue() : null;

		assertEquals(expectedPrice.getInnerRecordId(), priceInnerRecordId);
		assertEquals(expectedPrice.isSellable(), actualPrice.getSellable());

		final DateTimeRange expectedDateTimeRange = expectedPrice.getValidity();

		if (expectedDateTimeRange != null) {
			final DateTimeRange actualValidity = EvitaDataTypesConverter.toDateTimeRange(actualPrice.getValidity());
			assertEquals(expectedDateTimeRange.getFrom(), actualValidity.getFrom());
			assertEquals(expectedDateTimeRange.getTo(), actualValidity.getTo());
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
			expectedReference.getAttributeValues(),
			actualReference.getLocalizedAttributesMap(),
			actualReference.getGlobalAttributesMap(),
			locales
		);
	}

	public static void assertHistograms(@Nonnull HistogramContract expectedHistogram, @Nonnull GrpcHistogram actualHistogram) {
		final GrpcBigDecimal histogramMin = actualHistogram.getMin();
		assertEquals(expectedHistogram.getMin(), new BigDecimal(histogramMin.getValueString(), new MathContext(histogramMin.getPrecision())).setScale(histogramMin.getScale(), RoundingMode.UNNECESSARY));
		final GrpcBigDecimal histogramMax = actualHistogram.getMax();
		assertEquals(expectedHistogram.getMax(), new BigDecimal(histogramMax.getValueString(), new MathContext(histogramMax.getPrecision())).setScale(histogramMax.getScale(), RoundingMode.UNNECESSARY));
		assertEquals(expectedHistogram.getOverallCount(), actualHistogram.getOverallCount());
		assertEquals(expectedHistogram.getBuckets().length, actualHistogram.getBucketsCount());

		for (int i = 0; i < expectedHistogram.getBuckets().length; i++) {
			final HistogramContract.Bucket expectedBucket = expectedHistogram.getBuckets()[i];
			final GrpcHistogram.GrpcBucket actualBucket = actualHistogram.getBucketsList().get(i);
			assertEquals(expectedBucket.getIndex(), actualBucket.getIndex());
			final GrpcBigDecimal bucketThreshold = actualBucket.getThreshold();
			assertEquals(expectedBucket.getThreshold(), new BigDecimal(bucketThreshold.getValueString(), new MathContext(bucketThreshold.getPrecision())).setScale(bucketThreshold.getScale(), RoundingMode.UNNECESSARY));
			assertEquals(expectedBucket.getOccurrences(), actualBucket.getOccurrences());
		}
	}

	public static void assertAttributes(Collection<AttributeValue> attributeValues, Map<String, GrpcLocalizedAttribute> localizedAttributesMap, Map<String, GrpcEvitaValue> globalAttributesMap, Set<Locale> locales) {
		final List<AttributesContract.AttributeValue> expectedAttributeValues;
		if (locales.size() > 1) {
			expectedAttributeValues = attributeValues.stream().toList();
		} else if (locales.size() == 1) {
			expectedAttributeValues = attributeValues.stream().filter(
				a -> a.getKey().getLocale() == null ||
					a.getKey().getLocale() != null && a.getKey().getLocale().getLanguage().equals(
						locales.stream().findFirst().orElseThrow(() -> new IllegalArgumentException("Suitable locale not found!")).getLanguage()
					)
			).toList();
		} else {
			expectedAttributeValues = attributeValues.stream().filter(a -> a.getKey().getLocale() == null).toList();
		}

		assertEquals(
			expectedAttributeValues.size(),
			getAttributeCount(localizedAttributesMap, globalAttributesMap)
		);

		if (expectedAttributeValues.size() == 0)
			return;

		for (final AttributesContract.AttributeValue attributeValue : expectedAttributeValues) {
			final GrpcEvitaValue actualAttributeValue;
			if (attributeValue.getKey().isLocalized()) {
				final GrpcLocalizedAttribute localizedAttributes = localizedAttributesMap.get(attributeValue.getKey().getLocale().toLanguageTag());
				actualAttributeValue = localizedAttributes.getAttributesMap().get(attributeValue.getKey().getAttributeName());
			} else {
				actualAttributeValue = globalAttributesMap.get(attributeValue.getKey().getAttributeName());
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
		assertEquals(expectedQueryTelemetry.getOperation(), QueryPhase.valueOf(actualQueryTelemetry.getOperation().name()));
		assertEquals(expectedQueryTelemetry.getStart(), actualQueryTelemetry.getStart());
		assertEquals(expectedQueryTelemetry.getSpentTime(), actualQueryTelemetry.getSpentTime());
		assertArrayEquals(Arrays.stream(expectedQueryTelemetry.getArguments()).map(Object::toString).toArray(), actualQueryTelemetry.getArgumentsList().toArray());
		assertEquals(expectedQueryTelemetry.getSteps().size(), actualQueryTelemetry.getStepsCount());
		for (QueryTelemetry queryTelemetry : expectedQueryTelemetry.getSteps()) {
			assertQueryTelemetry(queryTelemetry, actualQueryTelemetry.getStepsList().get(expectedQueryTelemetry.getSteps().indexOf(queryTelemetry)));
		}
	}
}
