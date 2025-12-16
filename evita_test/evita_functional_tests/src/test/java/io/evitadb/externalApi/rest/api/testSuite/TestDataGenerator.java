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

package io.evitadb.externalApi.rest.api.testSuite;

import com.github.javafaker.Faker;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.core.Evita;
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.dataType.Scope;
import io.evitadb.test.Entities;
import io.evitadb.test.extension.DataCarrier;
import io.evitadb.test.generator.DataGenerator;
import lombok.Data;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Random;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import static io.evitadb.api.query.QueryConstraints.entityFetchAllContent;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.generator.DataGenerator.*;

/**
 * Generates artificial Evita data for REST functional testing.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class TestDataGenerator {

	public static final String REST_THOUSAND_PRODUCTS = "RESTThousandProducts";
	public static final String REST_HUNDRED_ARCHIVED_PRODUCTS_WITH_ARCHIVE = "RESTHundredArchiveProductsWithArchive";

	public static final String ENTITY_EMPTY = "empty";
	public static final String ENTITY_EMPTY_WITHOUT_PK = "emptyWithoutPk";
	public static final String ENTITY_BRAND_GROUP = "BrandGroup";
	public static final String ENTITY_STORE_GROUP = "BrandGroup";
	public static final String ATTRIBUTE_RELATIVE_URL = "relativeUrl";
	public static final String ATTRIBUTE_SIZE = "size";
	public static final String ATTRIBUTE_CREATED = "created";
	public static final String ATTRIBUTE_MANUFACTURED = "manufactured";
	public static final String ATTRIBUTE_VISIBLE = "visible";
	public static final String ATTRIBUTE_BRAND_VISIBLE_FOR_B2C = "brandVisibleForB2C";
	public static final String ATTRIBUTE_STORE_VISIBLE_FOR_B2C = "storeVisibleForB2C";
	public static final String ATTRIBUTE_MARKET_SHARE = "marketShare";
	public static final String ATTRIBUTE_FOUNDED = "founded";
	public static final String ATTRIBUTE_CAPACITY = "capacity";
	public static final String ATTRIBUTE_DEPRECATED = "deprecated";
	public static final String SORTABLE_ATTRIBUTE_COMPOUND_CODE_NAME = "codeName";
	public static final String SORTABLE_ATTRIBUTE_COMPOUND_FOUNDED_MARKET_SHARE = "foundedMarketShare";
	public static final String ASSOCIATED_DATA_LOCALIZATION = "localization";
	public static final String REFERENCE_OBSOLETE_BRAND = "obsoleteBrand";
	public static final String REFERENCE_BRAND_WITH_GROUP = "brandWithGroup";
	public static final String REFERENCE_STORE_WITH_GROUP = "storeWithGroup";

	private static final int SEED = 40;

	public static void generateMockCatalogs(@Nonnull Evita evita) {
		evita.defineCatalog("testCatalog2");
		evita.defineCatalog("testCatalog3");
	}

	@Nullable
	public static DataCarrier generateMainCatalogEntities(@Nonnull Evita evita, int productCount, boolean archiveSomeProducts) {
		return evita.updateCatalog(TEST_CATALOG, session -> {
			session.getCatalogSchema()
				.openForWrite()
				.withAttribute(ATTRIBUTE_CODE, String.class, whichIs -> whichIs.sortable().uniqueGloballyInScope(Scope.LIVE, Scope.ARCHIVED))
				.withAttribute(ATTRIBUTE_URL, String.class, whichIs -> whichIs.localized().uniqueGlobally())
				.withAttribute(ATTRIBUTE_RELATIVE_URL, String.class, whichIs -> whichIs.localized().uniqueGloballyWithinLocale().nullable())
				.updateVia(session);

			final Random random = new Random(SEED);
			final DataGenerator dataGenerator = new DataGenerator.Builder()
				.withPriceInnerRecordHandlingGenerator(ALL_PRICE_INNER_RECORD_HANDLING_GENERATOR)
				.withPriceIndexingDecider(DEFAULT_PRICE_INDEXING_DECIDER)
				.withCurrencies(CURRENCY_CZK, CURRENCY_EUR)
				.withPriceLists(PRICE_LIST_BASIC, PRICE_LIST_REFERENCE, PRICE_LIST_VIP)
				.build();
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
				.limit(Math.min(Math.max(10, productCount / 10), 100))
				.forEach(session::upsertEntity);

			dataGenerator.generateEntities(
					dataGenerator.getSamplePriceListSchema(session),
					randomEntityPicker,
					SEED
				)
				.limit(4)
				.forEach(session::upsertEntity);

			final List<EntityReferenceContract> storedStores = dataGenerator.generateEntities(
					dataGenerator.getSampleStoreSchema(session),
					randomEntityPicker,
					SEED
				)
				.limit(12)
				.map(session::upsertEntity)
				.toList();

			dataGenerator.generateEntities(
					dataGenerator.getSampleParameterGroupSchema(session),
					randomEntityPicker,
					SEED
				)
				.limit(15)
				.forEach(session::upsertEntity);

			dataGenerator.generateEntities(
					dataGenerator.getSampleParameterSchema(session),
					randomEntityPicker,
					SEED
				)
				.limit(Math.min(Math.max(10, productCount / 5), 100))
				.forEach(session::upsertEntity);

			dataGenerator.generateEntities(
					getEmptySchema(session),
					randomEntityPicker,
					SEED
				)
				.limit(Math.min(Math.max(10, productCount / 10), 100))
				.forEach(session::upsertEntity);

			dataGenerator.generateEntities(
					getEmptyWithoutPKSchema(session),
					randomEntityPicker,
					SEED
				)
				.limit(Math.min(Math.max(10, productCount / 10), 100))
				.forEach(session::upsertEntity);

			final List<EntityReferenceContract> storedProducts = dataGenerator.generateEntities(
					dataGenerator.getSampleProductSchema(
						session,
						schemaBuilder -> {
							schemaBuilder
								.withDescription("This is a description")
								.withGlobalAttribute(ATTRIBUTE_RELATIVE_URL)
								.withAttribute(ATTRIBUTE_QUANTITY, BigDecimal.class, whichIs -> whichIs
									.withDescription("This is a description")
									.filterable()
									.sortable()
									.indexDecimalPlaces(2)
									.withDefaultValue(BigDecimal.ONE))
								.withAttribute(ATTRIBUTE_PRIORITY, Long.class, whichIs -> whichIs.sortable().filterable())
								.withAttribute(ATTRIBUTE_SIZE, IntegerNumberRange[].class, whichIs -> whichIs.filterable())
								.withAttribute(ATTRIBUTE_CREATED, OffsetDateTime.class, whichIs -> whichIs.filterable().sortable())
								.withAttribute(ATTRIBUTE_MANUFACTURED, LocalDate.class, whichIs -> whichIs.filterable().sortable())
								.withAttribute(ATTRIBUTE_VISIBLE, Boolean.class, whichIs -> whichIs.filterable())
								.withAttribute(ATTRIBUTE_DEPRECATED, String.class, whichIs -> whichIs
									.deprecated("This is deprecated.")
									.nullable())
								.withSortableAttributeCompound(
									SORTABLE_ATTRIBUTE_COMPOUND_CODE_NAME,
									new AttributeElement(ATTRIBUTE_CODE, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
									new AttributeElement(ATTRIBUTE_NAME, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
								)
								.withAssociatedData(ASSOCIATED_DATA_LOCALIZATION, Localization.class, whichIs -> whichIs
									.withDescription("This is a description")
									.nullable())
								.withReferenceToEntity(
									Entities.BRAND,
									Entities.BRAND,
									Cardinality.ZERO_OR_ONE,
									whichIs -> whichIs
										.withDescription("This is a description")
										.withAttribute(ATTRIBUTE_BRAND_VISIBLE_FOR_B2C, Boolean.class, thatIs -> thatIs
											.withDescription("This is a description.")
											.filterable())
										.withAttribute(ATTRIBUTE_MARKET_SHARE, BigDecimal.class, thatIs -> thatIs.filterable().sortable())
										.withAttribute(ATTRIBUTE_FOUNDED, OffsetDateTime.class, thatIs -> thatIs.filterable().sortable())
										.withSortableAttributeCompound(
											SORTABLE_ATTRIBUTE_COMPOUND_FOUNDED_MARKET_SHARE,
											new AttributeElement(ATTRIBUTE_FOUNDED, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
											new AttributeElement(ATTRIBUTE_MARKET_SHARE, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
										)
								)
								.withReferenceToEntity(
									Entities.STORE,
									Entities.STORE,
									Cardinality.ZERO_OR_MORE,
									whichIs -> whichIs
										.withAttribute(ATTRIBUTE_STORE_VISIBLE_FOR_B2C, Boolean.class, thatIs -> thatIs.filterable())
										.withAttribute(ATTRIBUTE_CAPACITY, Long.class, thatIs -> thatIs.filterable().nullable().sortable())
								)
								.withReferenceTo(
									REFERENCE_OBSOLETE_BRAND,
									"nonManagedBrand",
									Cardinality.ZERO_OR_ONE,
									whichIs -> whichIs
										.deprecated("This is deprecated.")
										.withGroupType("ObsoleteBrand")
								)
								.withReferenceToEntity(
									REFERENCE_BRAND_WITH_GROUP,
									Entities.BRAND,
									Cardinality.ZERO_OR_MORE,
									whichIs -> whichIs.withGroupType(ENTITY_BRAND_GROUP)
								)
								.withReferenceTo(
									REFERENCE_STORE_WITH_GROUP,
									"nonManagedStore",
									Cardinality.ZERO_OR_MORE,
									whichIs -> whichIs.faceted().withGroupType(ENTITY_STORE_GROUP)
								)
								.withReferenceToEntity(
									Entities.PARAMETER,
									Entities.PARAMETER,
									Cardinality.EXACTLY_ONE,
									whichIs -> whichIs
										.faceted()
										.withAttribute(ATTRIBUTE_MARKET_SHARE, BigDecimal.class)
										.withGroupTypeRelatedToEntity(Entities.PARAMETER_GROUP)
								);
						}
					),
					randomEntityPicker,
					SEED
				)
				.limit(productCount)
				.map(entity -> {
					final EntityReferenceContract entityReference = session.upsertEntity(entity);
					if (archiveSomeProducts && random.nextInt(productCount) < productCount / 2) {
						// archive 50 % of products if enabled
						session.archiveEntity(entityReference.getType(), entityReference.getPrimaryKey());
					}

					return entityReference;
				})
				.toList();

			final List<SealedEntity> products = storedProducts.stream()
				.map(it -> session.getEntity(it.getType(), it.getPrimaryKey(), Scope.values(), entityFetchAllContent()).orElseThrow())
				.collect(Collectors.toList());
			final List<SealedEntity> stores = storedStores.stream()
				.map(it -> session.getEntity(it.getType(), it.getPrimaryKey(), entityFetchAllContent()).orElseThrow())
				.collect(Collectors.toList());

			return new DataCarrier(
				"originalProductEntities", products,
				"originalStoreEntities", stores
			);
		});
	}

	@Nonnull
	private static EntitySchemaContract getEmptySchema(@Nonnull EvitaSessionContract evitaSession) {
		final EntitySchemaEditor.EntitySchemaBuilder schemaBuilder = new InternalEntitySchemaBuilder(
			evitaSession.getCatalogSchema(),
			EntitySchema._internalBuild(ENTITY_EMPTY)
		)
			/* all is strictly verified */
			.verifySchemaStrictly()
			/* let Evita generates the key */
			.withGeneratedPrimaryKey()
			/* stores are not organized in the tree */
			.withoutHierarchy();

		/* finally apply schema changes */
		return schemaBuilder.toInstance();
	}

	@Nonnull
	private static EntitySchemaContract getEmptyWithoutPKSchema(@Nonnull EvitaSessionContract evitaSession) {
		final EntitySchemaEditor.EntitySchemaBuilder schemaBuilder = new InternalEntitySchemaBuilder(
			evitaSession.getCatalogSchema(),
			EntitySchema._internalBuild(ENTITY_EMPTY_WITHOUT_PK)
		)
			/* all is strictly verified */
			.verifySchemaStrictly()
			/* stores are not organized in the tree */
			.withoutHierarchy();

		/* finally apply schema changes */
		return schemaBuilder.toInstance();
	}

	@Data
	public static class Localization implements Serializable {
		@Serial private static final long serialVersionUID = 1121150156843379388L;
		private String someField = "someValue";

	}
}
