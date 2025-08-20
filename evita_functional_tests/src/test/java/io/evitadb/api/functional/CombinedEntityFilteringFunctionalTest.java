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

package io.evitadb.api.functional;

import com.github.javafaker.Faker;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.DataCarrier;
import io.evitadb.test.extension.EvitaParameterResolver;
import io.evitadb.test.generator.DataGenerator;
import io.evitadb.utils.AssertionUtils;
import one.edee.oss.pmptt.model.Hierarchy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestConstants.FUNCTIONAL_TEST;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.generator.DataGenerator.*;

/**
 * This test verifies whether entities can be filtered by complex queries.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("Evita entity filtering by combined constraints")
@Tag(FUNCTIONAL_TEST)
@ExtendWith(EvitaParameterResolver.class)
public class CombinedEntityFilteringFunctionalTest {
	private static final String THREE_HUNDRED_PRODUCTS_WITH_ALL_DATA = "HundredProductsWithAllData";
	private static final String ATTRIBUTE_SIZE = "size";
	private static final String ATTRIBUTE_CREATED = "created";
	private static final String ATTRIBUTE_MANUFACTURED = "manufactured";
	private static final String ATTRIBUTE_COMBINED_PRIORITY = "combinedPriority";
	private static final String ATTRIBUTE_TARGET_MARKET = "targetMarket";
	private static final String ATTRIBUTE_BRAND_LOCATED_AT = "brandLocatedAt";
	private static final String ATTRIBUTE_STORE_LOCATED_AT = "storeLocatedAt";
	private static final String ATTRIBUTE_MARKET_SHARE = "marketShare";
	private static final String ATTRIBUTE_FOUNDED = "founded";
	private static final String ATTRIBUTE_CAPACITY = "capacity";

	private static final int SEED = 40;

	@DataSet(value = THREE_HUNDRED_PRODUCTS_WITH_ALL_DATA, destroyAfterClass = true)
	DataCarrier setUp(Evita evita) {
		return evita.updateCatalog(TEST_CATALOG, session -> {
			final BiFunction<String, Faker, Integer> randomEntityPicker = (entityType, faker) -> {
				final int entityCount = session.getEntityCollectionSize(entityType);
				final int primaryKey = entityCount == 0 ? 0 : faker.random().nextInt(1, entityCount);
				return primaryKey == 0 ? null : primaryKey;
			};

			final DataGenerator dataGenerator = new DataGenerator.Builder()
				.withPriceInnerRecordHandlingGenerator(ALL_PRICE_INNER_RECORD_HANDLING_GENERATOR)
				.withPriceIndexingDecider(DEFAULT_PRICE_INDEXING_DECIDER)
				.build();

			final List<EntityReference> storedBrands = dataGenerator.generateEntities(
					dataGenerator.getSampleBrandSchema(session),
					randomEntityPicker,
					SEED
				)
				.limit(5)
				.map(session::upsertEntity)
				.toList();

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
						schemaBuilder -> {
							schemaBuilder
								.withAttribute(ATTRIBUTE_CODE, String.class, whichIs -> whichIs.unique().sortable())
								.withAttribute(ATTRIBUTE_QUANTITY, BigDecimal.class, whichIs -> whichIs.filterable().sortable().indexDecimalPlaces(2))
								.withAttribute(ATTRIBUTE_PRIORITY, Long.class, whichIs -> whichIs.sortable().filterable())
								.withAttribute(ATTRIBUTE_SIZE, IntegerNumberRange[].class, whichIs -> whichIs.filterable())
								.withAttribute(ATTRIBUTE_CREATED, OffsetDateTime.class, whichIs -> whichIs.filterable().sortable())
								.withAttribute(ATTRIBUTE_MANUFACTURED, LocalDate.class, whichIs -> whichIs.filterable().sortable())
								.withAttribute(ATTRIBUTE_COMBINED_PRIORITY, Long.class, whichIs -> whichIs.filterable().sortable())
								.withAttribute(ATTRIBUTE_TARGET_MARKET, String.class, whichIs -> whichIs.filterable())
								.withReferenceToEntity(
									Entities.BRAND,
									Entities.BRAND,
									Cardinality.ZERO_OR_ONE,
									whichIs -> whichIs
										.withAttribute(ATTRIBUTE_BRAND_LOCATED_AT, String.class, thatIs -> thatIs.filterable())
										.withAttribute(ATTRIBUTE_MARKET_SHARE, BigDecimal.class, thatIs -> thatIs.filterable().sortable())
										.withAttribute(ATTRIBUTE_FOUNDED, OffsetDateTime.class, thatIs -> thatIs.filterable().sortable())
								)
								.withReferenceToEntity(
									Entities.STORE,
									Entities.STORE,
									Cardinality.ZERO_OR_MORE,
									whichIs -> whichIs
										.withAttribute(ATTRIBUTE_STORE_LOCATED_AT, String.class, thatIs -> thatIs.filterable())
										.withAttribute(ATTRIBUTE_CAPACITY, Long.class, thatIs -> thatIs.filterable().sortable().nullable())
								);
						}
					),
					randomEntityPicker,
					SEED
				)
				.limit(300)
				.map(session::upsertEntity)
				.toList();

			return new DataCarrier(
				"originalProducts",
				storedProducts.stream()
					.map(it -> session.getEntity(it.getType(), it.getPrimaryKey(), entityFetchAllContent()).orElseThrow())
					.collect(Collectors.toList()),
				"categoryHierarchy",
				dataGenerator.getHierarchy(Entities.CATEGORY),
				"originalBrands",
				storedBrands.stream()
					.map(it -> session.getEntity(it.getType(), it.getPrimaryKey(), entityFetchAllContent()).orElseThrow())
					.collect(Collectors.toList())
			);
		});
	}

	@DisplayName("Should return products having price in currency and hierarchy location")
	@UseDataSet(THREE_HUNDRED_PRODUCTS_WITH_ALL_DATA)
	@Test
	void shouldReturnProductsHavingBothPriceAndHierarchyConstraints(Evita evita, List<SealedEntity> originalProducts, Hierarchy categoryHierarchy) {
		final BigDecimal from = new BigDecimal("30");
		final BigDecimal to = new BigDecimal("70");

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								priceInCurrency(CURRENCY_CZK),
								priceInPriceLists(PRICE_LIST_BASIC),
								priceBetween(from, to),
								hierarchyWithin(Entities.CATEGORY, entityPrimaryKeyInSet(3))
							)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);

				AssertionUtils.assertResultIs(
					originalProducts,
					sealedEntity -> {
						final boolean hasPrice = sealedEntity.hasPriceInInterval(from, to, QueryPriceMode.WITH_TAX, CURRENCY_CZK, null, PRICE_LIST_BASIC);
						final boolean isWithinCategory = sealedEntity
							.getReferences(Entities.CATEGORY)
							.stream()
							.anyMatch(category -> {
								final String categoryId = String.valueOf(category.getReferencedPrimaryKey());
								// is either category 3
								return Objects.equals(categoryId, String.valueOf(3)) ||
									// or has parent category 3
									categoryHierarchy.getParentItems(categoryId)
										.stream()
										.anyMatch(it -> Objects.equals(it.getCode(), String.valueOf(3)));
							});
						return hasPrice && isWithinCategory;
					},
					result.getRecordData()
				);

				return null;
			}
		);
	}

	@DisplayName("Should return products having price in currency and referenced entity")
	@UseDataSet(THREE_HUNDRED_PRODUCTS_WITH_ALL_DATA)
	@Test
	void shouldReturnProductsHavingBothPriceAndReferencedEntity(Evita evita, List<SealedEntity> originalProducts) {
		final BigDecimal from = new BigDecimal("80");
		final BigDecimal to = new BigDecimal("150");

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								priceInCurrency(CURRENCY_CZK),
								priceInPriceLists(PRICE_LIST_BASIC),
								priceBetween(from, to),
								referenceHaving(Entities.BRAND, entityPrimaryKeyInSet(4))
							)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);

				AssertionUtils.assertResultIs(
					originalProducts,
					sealedEntity -> {
						final boolean hasPrice = sealedEntity.hasPriceInInterval(from, to, QueryPriceMode.WITH_TAX, CURRENCY_CZK, null, PRICE_LIST_BASIC);
						final boolean isReferencingBrand = sealedEntity.getReference(Entities.BRAND, 4).isPresent();
						return hasPrice && isReferencingBrand;
					},
					result.getRecordData()
				);

				return null;
			}
		);
	}

	@DisplayName("Should return products having price in currency and referenced entity having")
	@UseDataSet(THREE_HUNDRED_PRODUCTS_WITH_ALL_DATA)
	@Test
	void shouldReturnProductsHavingBothPriceAndReferencedEntityHaving(Evita evita, List<SealedEntity> originalProducts, List<SealedEntity> originalBrands) {
		final BigDecimal from = new BigDecimal("80");
		final BigDecimal to = new BigDecimal("150");

		final Map<Integer, SealedEntity> brandsById = originalBrands.stream()
			.collect(Collectors.toMap(EntityContract::getPrimaryKey, Function.identity()));

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								entityLocaleEquals(CZECH_LOCALE),
								priceInCurrency(CURRENCY_CZK),
								priceInPriceLists(PRICE_LIST_BASIC),
								priceBetween(from, to),
								referenceHaving(
									Entities.BRAND,
									entityHaving(
										attributeStartsWith(ATTRIBUTE_NAME, "L")
									)
								)
							)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							priceType(QueryPriceMode.WITH_TAX)
						)
					),
					EntityReference.class
				);

				AssertionUtils.assertResultIs(
					originalProducts,
					sealedEntity -> {
						final boolean hasPrice = sealedEntity.hasPriceInInterval(from, to, QueryPriceMode.WITH_TAX, CURRENCY_CZK, null, PRICE_LIST_BASIC);
						final boolean hasCzechLocale = sealedEntity.getAllLocales().contains(CZECH_LOCALE);
						final Set<String> names = sealedEntity.getReferences(Entities.BRAND).stream()
							.map(it -> brandsById.get(it.getReferencedPrimaryKey()))
							.map(it -> it.getAttribute(ATTRIBUTE_NAME, CZECH_LOCALE, String.class))
							.filter(Objects::nonNull)
							.collect(Collectors.toSet());
						final boolean hasBrandStartingWithChar = names.stream()
							.anyMatch(it -> it.startsWith("L"));
						return hasPrice && hasCzechLocale && hasBrandStartingWithChar;
					},
					result.getRecordData()
				);

				return null;
			}
		);
	}

	@DisplayName("Should return products having price in currency and hierarchy location and referenced entity")
	@UseDataSet(THREE_HUNDRED_PRODUCTS_WITH_ALL_DATA)
	@Test
	void shouldReturnProductsHavingBothPriceAndHierarchyLocationAndReferencedEntity(Evita evita, List<SealedEntity> originalProducts, Hierarchy categoryHierarchy) {
		final Function<SealedEntity, Boolean> isReferencingBrand = sealedEntity ->
			sealedEntity.getReference(Entities.BRAND, 2).isPresent() ||
				sealedEntity.getReference(Entities.BRAND, 4).isPresent() ||
				sealedEntity.getReference(Entities.BRAND, 5).isPresent();
		final Function<SealedEntity, Boolean> isWithinCategory = sealedEntity -> sealedEntity
			.getReferences(Entities.CATEGORY)
			.stream()
			.anyMatch(category -> {
				final String categoryId = String.valueOf(category.getReferencedPrimaryKey());
				// is either category 4
				return Objects.equals(categoryId, String.valueOf(1)) ||
					// or has parent category 4
					categoryHierarchy.getParentItems(categoryId)
						.stream()
						.anyMatch(it -> Objects.equals(it.getCode(), String.valueOf(1)));
			});

		final BigDecimal[] prices = originalProducts.stream()
			.filter(it -> isReferencingBrand.apply(it) && isWithinCategory.apply(it))
			.flatMap(it -> it.getPrices(CURRENCY_CZK, PRICE_LIST_BASIC).stream())
			.map(PriceContract::priceWithTax)
			.sorted()
			.toArray(BigDecimal[]::new);
		final BigDecimal from = prices[(int) (prices.length * 0.2)];
		final BigDecimal to = prices[(int) (prices.length * 0.8)];

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								priceInCurrency(CURRENCY_CZK),
								priceInPriceLists(PRICE_LIST_BASIC),
								priceBetween(from, to),
								hierarchyWithin(Entities.CATEGORY, entityPrimaryKeyInSet(1)),
								referenceHaving(Entities.BRAND, entityPrimaryKeyInSet(2, 4, 5))
							)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);

				AssertionUtils.assertResultIs(
					originalProducts,
					sealedEntity -> {
						final boolean hasPrice = sealedEntity.hasPriceInInterval(from, to, QueryPriceMode.WITH_TAX, CURRENCY_CZK, null, PRICE_LIST_BASIC);
						return hasPrice && isReferencingBrand.apply(sealedEntity) && isWithinCategory.apply(sealedEntity);
					},
					result.getRecordData()
				);

				return null;
			}
		);
	}

}
