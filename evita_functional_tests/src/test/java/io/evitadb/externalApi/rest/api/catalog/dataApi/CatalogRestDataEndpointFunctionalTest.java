/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.externalApi.rest.api.catalog.dataApi;

import com.github.javafaker.Faker;
import io.evitadb.api.requestResponse.data.AssociatedDataContract;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataKey;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataValue;
import io.evitadb.api.requestResponse.data.AttributesContract;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.EntityClassifierWithParent;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.Evita;
import io.evitadb.dataType.DataChunk;
import io.evitadb.dataType.PaginatedList;
import io.evitadb.dataType.PlainChunk;
import io.evitadb.dataType.StripList;
import io.evitadb.externalApi.ExternalApiFunctionTestsSupport;
import io.evitadb.externalApi.api.catalog.dataApi.model.AttributesProviderDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.DataChunkDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.EntityDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.PaginatedListDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.PriceDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.ReferenceDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.StripListDescriptor;
import io.evitadb.externalApi.api.catalog.model.VersionedDescriptor;
import io.evitadb.externalApi.rest.RestProvider;
import io.evitadb.externalApi.rest.api.catalog.dataApi.dto.DataChunkType;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.entity.RestEntityDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.entity.SectionedAssociatedDataDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.entity.SectionedAttributesDescriptor;
import io.evitadb.externalApi.rest.api.testSuite.RestEndpointFunctionalTest;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.extension.DataCarrier;
import io.evitadb.test.generator.DataGenerator;
import io.evitadb.utils.Assert;
import io.evitadb.utils.MapBuilder;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import static io.evitadb.api.query.QueryConstraints.entityFetchAllContent;
import static io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.serializer.EntityJsonSerializer.separateAssociatedDataKeysByLocale;
import static io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.serializer.EntityJsonSerializer.separateAttributeKeysByLocale;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_EAN;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_NAME;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_QUANTITY;
import static io.evitadb.utils.MapBuilder.map;

/**
 * Ancestor for tests for REST catalog endpoint.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 * @author Martin Veska, FG Forrest a.s. (c) 2022
 */
public abstract class CatalogRestDataEndpointFunctionalTest extends RestEndpointFunctionalTest implements ExternalApiFunctionTestsSupport {

	protected static final int SEED = 40;

	protected static final String REST_HUNDRED_PRODUCTS_FOR_SEGMENTS = "RestHundredProductsForSegments";

	@DataSet(value = REST_HUNDRED_PRODUCTS_FOR_SEGMENTS, openWebApi = RestProvider.CODE, readOnly = false, destroyAfterClass = true)
	DataCarrier setUpForSegments(Evita evita) {
		return evita.updateCatalog(TEST_CATALOG, session -> {
			final DataGenerator dataGenerator = new DataGenerator();
			final BiFunction<String, Faker, Integer> randomEntityPicker = (entityType, faker) -> {
				final int entityCount = session.getEntityCollectionSize(entityType);
				final int primaryKey = entityCount == 0 ? 0 : faker.random().nextInt(1, entityCount);
				return primaryKey == 0 ? null : primaryKey;
			};
			dataGenerator.generateEntities(
					dataGenerator.getSampleBrandSchema(session),
					randomEntityPicker,
					SEED
				)
				.limit(5)
				.forEach(session::upsertEntity);

			dataGenerator.generateEntities(
					dataGenerator.getSampleCategorySchema(session),
					randomEntityPicker,
					SEED
				)
				.limit(10)
				.forEach(session::upsertEntity);

			dataGenerator.generateEntities(
					dataGenerator.getSamplePriceListSchema(session),
					randomEntityPicker,
					SEED
				)
				.limit(4)
				.forEach(session::upsertEntity);

			dataGenerator.generateEntities(
					dataGenerator.getSampleStoreSchema(session),
					randomEntityPicker,
					SEED
				)
				.limit(12)
				.forEach(session::upsertEntity);

			final List<EntityReference> storedProducts = dataGenerator.generateEntities(
					dataGenerator.getSampleProductSchema(
						session,
						builder -> {
							builder
								.withAttribute(ATTRIBUTE_NAME, String.class, whichIs -> whichIs.localized(() -> false).filterable().sortable().nullable(() -> false))
								.withAttribute(ATTRIBUTE_EAN, String.class, whichIs -> whichIs.filterable().sortable().nullable(() -> false))
								.withAttribute(ATTRIBUTE_QUANTITY, BigDecimal.class, whichIs -> whichIs.filterable().sortable().nullable(() -> false));
						}
					),
					randomEntityPicker,
					SEED
				)
				.limit(100)
				.map(session::upsertEntity)
				.toList();

			return new DataCarrier(
				"originalProductEntities",
				storedProducts.stream()
					.map(it -> session.getEntity(it.getType(), it.getPrimaryKey(), entityFetchAllContent()).orElseThrow())
					.collect(Collectors.toList())
			);
		});
	}

	@Nonnull
	public static List<Map<String, Object>> createEntityDtos(@Nonnull List<? extends EntityClassifier> entityClassifiers) {
		return createEntityDtos(entityClassifiers, false);
	}

	@Nonnull
	public static List<Map<String, Object>> createEntityDtos(@Nonnull List<? extends EntityClassifier> entityClassifiers, boolean localized) {
		return entityClassifiers.stream()
			.map(it -> createEntityDto(it, localized))
			.toList();
	}

	@Nullable
	public static Map<String, Object> createEntityDto(@Nullable EntityClassifier entityClassifier) {
		return createEntityDto(entityClassifier, false);
	}

	@Nullable
	public static Map<String, Object> createEntityDto(@Nullable EntityClassifier entityClassifier, boolean localized) {
		if (entityClassifier == null) {
			return null;
		}

		final MapBuilder dto = map()
			.e(EntityDescriptor.PRIMARY_KEY.name(), entityClassifier.getPrimaryKey())
			.e(EntityDescriptor.TYPE.name(), entityClassifier.getType());

		if (entityClassifier instanceof SealedEntity entity) {
			createEntityBodyDto(dto, entity, localized);
			createAttributesDto(dto, entity.getLocales(), entity, localized);
			createAssociatedDataDto(dto, entity.getLocales(), entity, localized);
			createPricesDto(dto, entity);
			createReferencesDto(dto, entity, localized);
		} else if (entityClassifier instanceof EntityClassifierWithParent entity) {
			entity.getParentEntity()
				.ifPresent(parent -> dto.e(RestEntityDescriptor.PARENT_ENTITY.name(), createEntityDto(parent, localized)));
		}

		return dto.build();
	}

	public static void createEntityBodyDto(@Nonnull MapBuilder entityDto, @Nonnull SealedEntity entity, boolean localized) {
		entityDto.e(VersionedDescriptor.VERSION.name(), entity.version());
		entityDto.e(EntityDescriptor.SCOPE.name(), entity.getScope().name());

		if (entity.parentAvailable()) {
			entity.getParentEntity().ifPresent(parent -> entityDto.e(RestEntityDescriptor.PARENT_ENTITY.name(), createEntityDto(parent, localized)));
		}

		if (!entity.getLocales().isEmpty()) {
			entityDto.e(EntityDescriptor.LOCALES.name(), entity.getLocales().stream().map(Locale::toLanguageTag).toList());
		}
		if (!entity.getAllLocales().isEmpty()) {
			entityDto.e(EntityDescriptor.ALL_LOCALES.name(), entity.getAllLocales().stream().map(Locale::toLanguageTag).toList());
		}
		if (entity.getPriceInnerRecordHandling() != PriceInnerRecordHandling.UNKNOWN) {
			entityDto.e(EntityDescriptor.PRICE_INNER_RECORD_HANDLING.name(), entity.getPriceInnerRecordHandling().name());
		}
	}

	public static void createAttributesDto(@Nonnull MapBuilder parentDto,
	                                 @Nonnull Set<Locale> locales,
	                                 @Nonnull AttributesContract attributes,
	                                 boolean localized) {
		if (attributes.attributesAvailable() && !attributes.getAttributeKeys().isEmpty()) {
			final Set<AttributeKey> attributeKeys = attributes.getAttributeKeys();
			final MapBuilder attributesDto;
			if (localized) {
				Assert.isPremiseValid(
					locales.size() == 1,
					"Localized entity must have exactly one locale."
				);
				attributesDto = createAttributesOfLangDto(attributes, attributeKeys, locales.iterator().next());
			} else {
				attributesDto = map();

				final Map<String, List<AttributeKey>> localeSeparatedKeys = separateAttributeKeysByLocale(locales, attributeKeys);

				final List<AttributesContract.AttributeKey> globalAttributeKeys = localeSeparatedKeys.remove(SectionedAttributesDescriptor.GLOBAL.name());
				if (!globalAttributeKeys.isEmpty()) {
					attributesDto.e(SectionedAttributesDescriptor.GLOBAL.name(), createAttributesOfLangDto(attributes, globalAttributeKeys, null));
				}

				final MapBuilder localizedAttributesBuilder = map();
				for (Entry<String, List<AttributeKey>> entry : localeSeparatedKeys.entrySet()) {
					final MapBuilder attributesForLocale = createAttributesOfLangDto(attributes, entry.getValue(), Locale.forLanguageTag(entry.getKey()));
					if (!attributesForLocale.isEmpty()) {
						localizedAttributesBuilder.e(entry.getKey(), attributesForLocale);
					}
				}
				if (!localizedAttributesBuilder.isEmpty()) {
					attributesDto.e(SectionedAttributesDescriptor.LOCALIZED.name(), localizedAttributesBuilder);
				}
			}

			parentDto.e(AttributesProviderDescriptor.ATTRIBUTES.name(), attributesDto);
		}
	}

	public static MapBuilder createAttributesOfLangDto(@Nonnull AttributesContract attributes,
	                                             @Nonnull Collection<AttributesContract.AttributeKey> attributeKeys,
	                                             @Nullable Locale locale) {
		final MapBuilder dto = map();
		attributeKeys.forEach(attributeKey -> {
			final Optional<AttributeValue> attributeValue;
			if (attributeKey.localized()) {
				Assert.isPremiseValid(
					locale != null,
					"Locale must be provided for localized attribute key."
				);
				attributeValue = attributes.getAttributeValue(attributeKey.attributeName(), locale);
			} else {
				attributeValue = attributes.getAttributeValue(attributeKey.attributeName());
			}

			if (attributeValue.isPresent()) {
				dto.e(attributeKey.attributeName(), serializeToJsonValue(attributeValue.get().value()));
			} else {
				dto.e(attributeKey.attributeName(), null);
			}
		});
		return dto;
	}

	public static void createAssociatedDataDto(@Nonnull MapBuilder parentDto,
	                                     @Nonnull Set<Locale> locales,
	                                     @Nonnull AssociatedDataContract associatedData,
	                                     boolean localized) {
		if (associatedData.associatedDataAvailable() && !associatedData.getAssociatedDataKeys().isEmpty()) {
			final Set<AssociatedDataKey> associatedDataKeys = associatedData.getAssociatedDataKeys();
			final MapBuilder associatedDataDto;
			if (localized) {
				Assert.isPremiseValid(
					locales.size() == 1,
					"Localized entity must have exactly one locale."
				);
				associatedDataDto = createAssociatedDataOfLangDto(associatedData, associatedDataKeys, locales.iterator().next());
			} else {
				associatedDataDto = map();

				final Map<String, List<AssociatedDataKey>> localeSeparatedKeys = separateAssociatedDataKeysByLocale(locales, associatedDataKeys);

				final List<AssociatedDataKey> globalAssociatedData = localeSeparatedKeys.remove(SectionedAssociatedDataDescriptor.GLOBAL.name());
				if (!globalAssociatedData.isEmpty()) {
					associatedDataDto.e(SectionedAssociatedDataDescriptor.GLOBAL.name(), createAssociatedDataOfLangDto(associatedData, globalAssociatedData, null));
				}

				final MapBuilder localizedAssociatedDataBuilder = map();
				for (Entry<String, List<AssociatedDataKey>> entry : localeSeparatedKeys.entrySet()) {
					final MapBuilder associatedDataForLocale = createAssociatedDataOfLangDto(associatedData, entry.getValue(), Locale.forLanguageTag(entry.getKey()));
					if (!associatedDataForLocale.isEmpty()) {
						localizedAssociatedDataBuilder.e(entry.getKey(), associatedDataForLocale);
					}
				}
				if (!localizedAssociatedDataBuilder.isEmpty()) {
					associatedDataDto.e(SectionedAssociatedDataDescriptor.LOCALIZED.name(), localizedAssociatedDataBuilder);
				}
			}

			parentDto.e(EntityDescriptor.ASSOCIATED_DATA.name(), associatedDataDto);
		}
	}

	public static MapBuilder createAssociatedDataOfLangDto(@Nonnull AssociatedDataContract associatedData,
	                                                 @Nonnull Collection<AssociatedDataKey> associatedDataKeys,
	                                                 @Nullable Locale locale) {
		final MapBuilder dto = map();
		associatedDataKeys.forEach(associatedDataKey -> {
			final Optional<AssociatedDataValue> associatedDataValue;
			if (associatedDataKey.localized()) {
				Assert.isPremiseValid(
					locale != null,
					"Locale must be provided for localized associated data key."
				);
				associatedDataValue = associatedData.getAssociatedDataValue(associatedDataKey.associatedDataName(), locale);
			} else {
				associatedDataValue = associatedData.getAssociatedDataValue(associatedDataKey.associatedDataName());
			}

			if (associatedDataValue.isPresent()) {
				dto.e(associatedDataKey.associatedDataName(), serializeToJsonValue(associatedDataValue.get().value()));
			} else {
				dto.e(associatedDataKey.associatedDataName(), null);
			}
		});
		return dto;
	}

	public static void createPricesDto(@Nonnull MapBuilder entityDto,
	                                   @Nonnull EntityContract entity) {
		if (entity.pricesAvailable()) {
			if (!entity.getPrices().isEmpty()) {
				entityDto.e(
					EntityDescriptor.PRICES.name(),
					entity.getPrices()
						.stream()
						.map(price -> createEntityPriceDto(price).build())
						.toList()
				);
			}

			entity.getPriceForSaleWithAccompanyingPricesIfAvailable().ifPresent(price -> {
				entityDto.e(EntityDescriptor.PRICE_FOR_SALE.name(), createEntityPriceDto(price.priceForSale()));

				final Map<String, Optional<PriceContract>> accompanyingPrices = price.accompanyingPrices();
				if (!accompanyingPrices.isEmpty()) {
					final MapBuilder accompanyingPricesObject = map();
					accompanyingPrices.forEach((accompanyingPriceName, accompanyingPrice) -> {
						accompanyingPricesObject.e(accompanyingPriceName, accompanyingPrice.map(CatalogRestDataEndpointFunctionalTest::createEntityPriceDto).orElse(null));
					});
					entityDto.e(RestEntityDescriptor.ACCOMPANYING_PRICES.name(), accompanyingPricesObject);
				}

				if (!entity.getPriceInnerRecordHandling().equals(PriceInnerRecordHandling.NONE)) {
					entityDto.e(EntityDescriptor.MULTIPLE_PRICES_FOR_SALE_AVAILABLE.name(), entity.getAllPricesForSale().size() > 1);
				}
			});
		}
	}

	@Nonnull
	public static MapBuilder createEntityPriceDto(@Nonnull PriceContract price) {
		return map()
			.e(PriceDescriptor.PRICE_ID.name(), price.priceId())
			.e(PriceDescriptor.PRICE_LIST.name(), price.priceList())
			.e(PriceDescriptor.CURRENCY.name(), price.currencyCode())
			.e(PriceDescriptor.INNER_RECORD_ID.name(), price.innerRecordId())
			.e(PriceDescriptor.INDEXED.name(), price.indexed())
			.e(PriceDescriptor.PRICE_WITHOUT_TAX.name(), price.priceWithoutTax().toString())
			.e(PriceDescriptor.PRICE_WITH_TAX.name(), price.priceWithTax().toString())
			.e(PriceDescriptor.TAX_RATE.name(), price.taxRate().toString())
			.e(PriceDescriptor.VALIDITY.name(), Optional.ofNullable(price.validity())
				.map(RestEndpointFunctionalTest::serializeToJsonValue)
				.orElse(null));
	}

	public static void createReferencesDto(@Nonnull MapBuilder entityDto,
	                                 @Nonnull SealedEntity entity,
	                                 boolean localized) {
		if (entity.referencesAvailable()) {
			entity.getReferenceNames()
				.forEach(it -> createReferencesOfNameDto(entityDto, entity, it, localized));
		}
	}

	public static void createReferencesOfNameDto(@Nonnull MapBuilder entityDto,
	                                             @Nonnull SealedEntity entity,
	                                             @Nonnull String referenceName,
	                                             boolean localized) {
		final ReferenceSchemaContract referenceSchema = entity.getSchema()
			.getReference(referenceName)
			.orElseThrow(() -> new RuntimeException("Schema for reference `" + referenceName + "` not known."));
		final Cardinality referenceCardinality = referenceSchema.getCardinality();

		final DataChunk<ReferenceContract> groupedReferences = entity.getReferenceChunk(referenceName);

		if (referenceCardinality == Cardinality.EXACTLY_ONE || referenceCardinality == Cardinality.ZERO_OR_ONE) {
			Assert.isPremiseValid(groupedReferences.getTotalRecordCount() <= 1, "Reference cardinality is: " +
				referenceCardinality + " but found " + groupedReferences.getTotalRecordCount() +
				" references with same name: " + referenceName);

			final String referencePropertyName = EntityDescriptor.REFERENCE.name(referenceSchema);
			if (groupedReferences.getData().isEmpty()) {
				entityDto.e(referencePropertyName, null);
			} else {
				entityDto.e(referencePropertyName, createReferenceDto(entity.getLocales(), groupedReferences.getData().get(0), localized));
			}
		} else {
			final List<Map<String, Object>> data = groupedReferences.stream()
				.map(it -> createReferenceDto(entity.getLocales(), it, localized).build())
				.toList();

			if (groupedReferences instanceof PlainChunk<ReferenceContract>) {
				entityDto.e(
					EntityDescriptor.REFERENCE.name(referenceSchema),
					data
				);
			} else if (groupedReferences instanceof PaginatedList<ReferenceContract> groupedReferencePage) {
				entityDto.e(
					EntityDescriptor.REFERENCE_PAGE.name(referenceSchema),
					map()
						.e(DataChunkDescriptor.DATA.name(), data)
						.e("type", DataChunkType.PAGE.name())
						.e(DataChunkDescriptor.TOTAL_RECORD_COUNT.name(), groupedReferencePage.getTotalRecordCount())
						.e(DataChunkDescriptor.FIRST.name(), groupedReferencePage.isFirst())
						.e(DataChunkDescriptor.LAST.name(), groupedReferencePage.isLast())
						.e(DataChunkDescriptor.HAS_PREVIOUS.name(), groupedReferencePage.hasPrevious())
						.e(DataChunkDescriptor.HAS_NEXT.name(), groupedReferencePage.hasNext())
						.e(DataChunkDescriptor.SINGLE_PAGE.name(), groupedReferencePage.isSinglePage())
						.e(DataChunkDescriptor.EMPTY.name(), groupedReferencePage.isEmpty())
						.e(PaginatedListDescriptor.PAGE_SIZE.name(), groupedReferencePage.getPageSize())
						.e(PaginatedListDescriptor.PAGE_NUMBER.name(), groupedReferencePage.getPageNumber())
						.e(PaginatedListDescriptor.LAST_PAGE_NUMBER.name(), groupedReferencePage.getLastPageNumber())
						.e(PaginatedListDescriptor.FIRST_PAGE_ITEM_NUMBER.name(), groupedReferencePage.getFirstPageItemNumber())
						.e(PaginatedListDescriptor.LAST_PAGE_ITEM_NUMBER.name(), groupedReferencePage.getLastPageItemNumber())
				);
			} else if (groupedReferences instanceof StripList<ReferenceContract> groupedReferencesStrip) {
				entityDto.e(
					EntityDescriptor.REFERENCE_STRIP.name(referenceSchema),
					map()
						.e(DataChunkDescriptor.DATA.name(), data)
						.e("type", DataChunkType.STRIP.name())
						.e(DataChunkDescriptor.TOTAL_RECORD_COUNT.name(), groupedReferencesStrip.getTotalRecordCount())
						.e(DataChunkDescriptor.FIRST.name(), groupedReferencesStrip.isFirst())
						.e(DataChunkDescriptor.LAST.name(), groupedReferencesStrip.isLast())
						.e(DataChunkDescriptor.HAS_PREVIOUS.name(), groupedReferencesStrip.hasPrevious())
						.e(DataChunkDescriptor.HAS_NEXT.name(), groupedReferencesStrip.hasNext())
						.e(DataChunkDescriptor.SINGLE_PAGE.name(), groupedReferencesStrip.isSinglePage())
						.e(DataChunkDescriptor.EMPTY.name(), groupedReferencesStrip.isEmpty())
						.e(StripListDescriptor.OFFSET.name(), groupedReferencesStrip.getOffset())
						.e(StripListDescriptor.LIMIT.name(), groupedReferencesStrip.getLimit())
				);
			} else {
				throw new IllegalArgumentException("Unsupported data chunk type " + groupedReferences.getClass().getName());
			}
		}
	}

	@Nonnull
	public static MapBuilder createReferenceDto(@Nonnull Set<Locale> locales, @Nonnull ReferenceContract reference, boolean localized) {
		final MapBuilder dto = map()
			.e(ReferenceDescriptor.REFERENCED_PRIMARY_KEY.name(), reference.getReferencedPrimaryKey());

		reference.getReferencedEntity()
			.ifPresent(entity -> dto.e(ReferenceDescriptor.REFERENCED_ENTITY.name(), createEntityDto(entity, localized)));

		reference.getGroupEntity()
			.map(it -> (EntityClassifier) it)
			.or(reference::getGroup)
			.ifPresent(groupEntity -> dto.e(ReferenceDescriptor.GROUP_ENTITY.name(), createEntityDto(groupEntity, localized)));

		createAttributesDto(dto, locales, reference, localized);

		return dto;
	}
}
