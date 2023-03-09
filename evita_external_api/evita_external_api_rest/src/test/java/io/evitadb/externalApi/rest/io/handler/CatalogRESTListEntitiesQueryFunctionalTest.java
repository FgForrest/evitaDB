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

package io.evitadb.externalApi.rest.io.handler;

import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.api.catalog.dataApi.model.EntityDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.SectionedAttributesDescriptor;
import io.evitadb.externalApi.rest.testSuite.RESTTester.Request;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.UseDataSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.function.Predicate;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.api.query.order.OrderDirection.DESC;
import static io.evitadb.externalApi.rest.testSuite.TestDataGenerator.ATTRIBUTE_CREATED;
import static io.evitadb.externalApi.rest.testSuite.TestDataGenerator.ATTRIBUTE_MANUFACTURED;
import static io.evitadb.externalApi.rest.testSuite.TestDataGenerator.ATTRIBUTE_MARKET_SHARE;
import static io.evitadb.externalApi.rest.testSuite.TestDataGenerator.REST_THOUSAND_PRODUCTS;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.builder.MapBuilder.map;
import static io.evitadb.test.generator.DataGenerator.*;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for REST catalog entity list query.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
class CatalogRESTListEntitiesQueryFunctionalTest extends CatalogRESTEndpointFunctionalTest {

	private static final int SEED = 40;

	@Nonnull
	@Override
	protected String getEndpointPath() {
		return "/test-catalog";
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return products by primary key")
	void shouldReturnProductsByPrimaryKey(Evita evita, List<SealedEntity> originalProductEntities) {
		final var entities = findEntities(
			originalProductEntities,
			it -> it.getAttribute(ATTRIBUTE_CODE) != null
		);

		final var expectedBody = entities.stream()
			.map(entity ->
				map()
					.e(EntityDescriptor.PRIMARY_KEY.name(), entity.getPrimaryKey())
					.e(EntityDescriptor.TYPE.name(), Entities.PRODUCT)
					.e(EntityDescriptor.LOCALES.name(), List.of())
					.e(EntityDescriptor.ALL_LOCALES.name(), List.of(CZECH_LOCALE.toLanguageTag(), Locale.ENGLISH.toLanguageTag()))
					.e(EntityDescriptor.PRICE_INNER_RECORD_HANDLING.name(), PriceInnerRecordHandling.UNKNOWN.name())
					.e(EntityDescriptor.ATTRIBUTES.name(), map()
						.e(SectionedAttributesDescriptor.GLOBAL.name(), map()
							.e(ATTRIBUTE_CODE, entity.getAttribute(ATTRIBUTE_CODE))
							.build())
						.build())
					.build()
			)
			.toList();

		testRESTCall()
			.urlPathSuffix("/product/list")
			.httpMethod(Request.METHOD_POST)
			.requestBody("{" +
					"\"filterBy\": {" +
					"  \"and\": [ {" +
					"    \"entity_primaryKey_inSet\": [%d, %d]" +
					"     }" +
					"  ]" +
					"}," +
					"\"require\": {" +
					"  \"entity_fetch\": {" +
					"     \"attribute_content\": [\"code\"]" +
					"    }" +
					"  }" +
					"}",
				entities.get(0).getPrimaryKey(),
				entities.get(1).getPrimaryKey())
			.executeAndThen()
			.statusCode(200)
			.body("", equalTo(expectedBody));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return products by non-localized attribute")
	void shouldReturnProductsByNonLocalizedAttribute(Evita evita, List<SealedEntity> originalProductEntities) {
		final var entities = findEntities(
			originalProductEntities,
			it -> it.getAttribute(ATTRIBUTE_NAME, Locale.ENGLISH) != null &&
				it.getAllLocales().contains(CZECH_LOCALE) &&
				it.getAllLocales().contains(Locale.ENGLISH)
		);

		final var expectedBody = entities.stream()
			.map(entity ->
				map()
					.e(EntityDescriptor.PRIMARY_KEY.name(), entity.getPrimaryKey())
					.e(EntityDescriptor.TYPE.name(), Entities.PRODUCT)
					.e(EntityDescriptor.LOCALES.name(), List.of(Locale.ENGLISH.toLanguageTag()))
					.e(EntityDescriptor.ALL_LOCALES.name(), List.of(CZECH_LOCALE.toLanguageTag(), Locale.ENGLISH.toLanguageTag()))
					.e(EntityDescriptor.PRICE_INNER_RECORD_HANDLING.name(), PriceInnerRecordHandling.UNKNOWN.name())
					.e(EntityDescriptor.ATTRIBUTES.name(), map()
						.e(SectionedAttributesDescriptor.GLOBAL.name(), map()
							.e(ATTRIBUTE_CODE, entity.getAttribute(ATTRIBUTE_CODE))
							.build())
						.e(SectionedAttributesDescriptor.LOCALIZED.name(), map()
							.e(Locale.ENGLISH.toLanguageTag(),
								map()
									.e(ATTRIBUTE_NAME, entity.getAttribute(ATTRIBUTE_NAME, Locale.ENGLISH))
									.build())
							.build()
						)
						.build())
					.build()
			)
			.toList();

		testRESTCall()
			.urlPathSuffix("/product/list")
			.httpMethod(Request.METHOD_POST)
			.requestBody("{" +
					"\"filterBy\": {" +
					"  \"and\": [ {" +
					"    \"entity_primaryKey_inSet\": [%d, %d]," +
					"    \"entity_locale_equals\": \"en\"" +
					"     }" +
					"  ]" +
					"}," +
					"\"require\": {" +
					"  \"entity_fetch\": {" +
					"     \"attribute_content\": [\"code\",\"name\"]" +
					"    }" +
					"  }" +
					"}",
				entities.get(0).getPrimaryKey(),
				entities.get(1).getPrimaryKey())
			.executeAndThen()
			.statusCode(200)
			.body("", equalTo(expectedBody));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return products by non-localized attribute with locale in URL")
	void shouldReturnProductsByNonLocalizedAttributeWithLocaleInUrl(Evita evita, List<SealedEntity> originalProductEntities) {
		final var entities = findEntities(
			originalProductEntities,
			it -> it.getAttribute(ATTRIBUTE_NAME, Locale.ENGLISH) != null &&
				it.getAllLocales().contains(CZECH_LOCALE) &&
				it.getAllLocales().contains(Locale.ENGLISH)
		);

		final var expectedBody = entities.stream()
			.map(entity ->
				map()
					.e(EntityDescriptor.PRIMARY_KEY.name(), entity.getPrimaryKey())
					.e(EntityDescriptor.TYPE.name(), Entities.PRODUCT)
					.e(EntityDescriptor.LOCALES.name(), List.of(Locale.ENGLISH.toLanguageTag()))
					.e(EntityDescriptor.ALL_LOCALES.name(), List.of(CZECH_LOCALE.toLanguageTag(), Locale.ENGLISH.toLanguageTag()))
					.e(EntityDescriptor.PRICE_INNER_RECORD_HANDLING.name(), PriceInnerRecordHandling.UNKNOWN.name())
					.e(EntityDescriptor.ATTRIBUTES.name(), map()
							.e(ATTRIBUTE_CODE, entity.getAttribute(ATTRIBUTE_CODE))
							.e(ATTRIBUTE_NAME, entity.getAttribute(ATTRIBUTE_NAME, Locale.ENGLISH))
						.build())
					.build()
			)
			.toList();

		testRESTCall()
			.urlPathSuffix("/" + Locale.ENGLISH.toLanguageTag() + "/product/list")
			.httpMethod(Request.METHOD_POST)
			.requestBody("{" +
					"\"filterBy\": {" +
					"  \"and\": [ {" +
					"    \"entity_primaryKey_inSet\": [%d, %d]" +
					"     }" +
					"  ]" +
					"}," +
					"\"require\": {" +
					"  \"entity_fetch\": {" +
					"     \"attribute_content\": [\"code\",\"name\"]" +
					"    }" +
					"  }" +
					"}",
				entities.get(0).getPrimaryKey(),
				entities.get(1).getPrimaryKey())
			.executeAndThen()
			.statusCode(200)
			.body("", equalTo(expectedBody));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return products by localized attribute")
	void shouldReturnProductsByLocalizedAttribute(Evita evita, List<SealedEntity> originalProductEntities) {
		final var entities = findEntities(
			originalProductEntities,
			it -> it.getAttribute(ATTRIBUTE_URL, Locale.ENGLISH) != null &&
				it.getAttribute(ATTRIBUTE_NAME, Locale.ENGLISH) != null
		);

		final var expectedBody = entities.stream()
			.map(entity ->
				map()
					.e(EntityDescriptor.PRIMARY_KEY.name(), entity.getPrimaryKey())
					.e(EntityDescriptor.TYPE.name(), Entities.PRODUCT)
					.e(EntityDescriptor.LOCALES.name(), List.of(Locale.ENGLISH.toLanguageTag()))
					.e(EntityDescriptor.ALL_LOCALES.name(), List.of(CZECH_LOCALE.toLanguageTag(), Locale.ENGLISH.toLanguageTag()))
					.e(EntityDescriptor.PRICE_INNER_RECORD_HANDLING.name(), PriceInnerRecordHandling.UNKNOWN.name())
					.e(EntityDescriptor.ATTRIBUTES.name(), map()
						.e(SectionedAttributesDescriptor.LOCALIZED.name(), map()
							.e(Locale.ENGLISH.toLanguageTag(),
								map()
									.e(ATTRIBUTE_URL, entity.getAttribute(ATTRIBUTE_URL, Locale.ENGLISH))
									.e(ATTRIBUTE_NAME, entity.getAttribute(ATTRIBUTE_NAME, Locale.ENGLISH))
									.build())
							.build()
						)
						.build())
					.build()
			)
			.toList();

		testRESTCall()
			.urlPathSuffix("/product/list")
			.httpMethod(Request.METHOD_POST)
			.requestBody("{" +
					"\"filterBy\": {" +
					"  \"and\": [ {" +
					"    \"attribute_url_inSet\": [\"%s\", \"%s\"]," +
					"    \"entity_locale_equals\": \"en\"" +
					"     }" +
					"  ]" +
					"}," +
					"\"require\": {" +
					"  \"entity_fetch\": {" +
					"     \"attribute_content\": [\"url\",\"name\"]" +
					"    }" +
					"  }" +
					"}",
				entities.get(0).getAttribute(ATTRIBUTE_URL, Locale.ENGLISH),
				entities.get(1).getAttribute(ATTRIBUTE_URL, Locale.ENGLISH))
			.executeAndThen()
			.statusCode(200)
			.body("", equalTo(expectedBody));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should filter by and return price for sale for multiple products")
	void shouldFilterByAndReturnPriceForSaleForMultipleProducts(Evita evita, List<SealedEntity> originalProductEntities) {
		final List<SealedEntity> entities = findEntitiesWithPrice(originalProductEntities);

		final List<Map<String, Object>> expectedBody = entities.stream()
			.map(sealedEntity -> createPriceForSaleDto(sealedEntity, CURRENCY_CZK, "basic"))
			.toList();

		testRESTCall()
			.urlPathSuffix("/product/list")
			.httpMethod(Request.METHOD_POST)
			.requestBody("{" +
					"\"filterBy\": {" +
					"    \"attribute_code_inSet\": [\"%s\", \"%s\"]," +
					"    \"price_inCurrency\": \"CZK\"," +
					"    \"price_inPriceLists\": [\"basic\"]" +
					"}," +
					"\"require\": {" +
					"  \"entity_fetch\": {" +
					"     \"price_content\": \"RESPECTING_FILTER\"" +
					"    }" +
					"  }" +
					"}",
				entities.get(0).getAttribute(ATTRIBUTE_CODE),
				entities.get(1).getAttribute(ATTRIBUTE_CODE))
			.executeAndThen()
			.statusCode(200)
			.body("priceForSale", equalTo(expectedBody));
	}


	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should filter products by non-existent price")
	void shouldFilterProductsByNonExistentPrice(Evita evita, List<SealedEntity> originalProductEntities) {
		final List<SealedEntity> entities = findEntitiesWithPrice(originalProductEntities);

		testRESTCall()
			.urlPathSuffix("/product/list")
			.httpMethod(Request.METHOD_POST)
			.requestBody("{" +
					"\"filterBy\": {" +
					"    \"attribute_code_inSet\": [\"%s\", \"%s\"]," +
					"    \"price_inCurrency\": \"CZK\"," +
					"    \"price_inPriceLists\": [\"nonexistent\"]" +
					"   }" +
					"}",
				entities.get(0).getAttribute(ATTRIBUTE_CODE),
				entities.get(1).getAttribute(ATTRIBUTE_CODE))
			.executeAndThen()
			.statusCode(200)
			.body("", hasSize(0));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return error for filtering products by unknown currency")
	void shouldReturnErrorForFilteringProductsByUnknownCurrency(Evita evita, List<SealedEntity> originalProductEntities) {
		final List<SealedEntity> entities = findEntitiesWithPrice(originalProductEntities);

		testRESTCall()
			.urlPathSuffix("/product/list")
			.httpMethod(Request.METHOD_POST)
			.requestBody("{" +
					"\"filterBy\": {" +
					"    \"attribute_code_inSet\": [\"%s\", \"%s\"]," +
					"    \"price_inCurrency\": \"AAA\"," +
					"    \"price_inPriceLists\": [\"basic\"]" +
					"   }" +
					"}",
				entities.get(0).getAttribute(ATTRIBUTE_CODE),
				entities.get(1).getAttribute(ATTRIBUTE_CODE))
			.executeAndThen()
			.statusCode(400)
			.body("message", equalTo("The value `AAA` cannot be converted to the type `java.util.Currency`!"));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return custom price for sale for products")
	void shouldReturnCustomPriceForSaleForProducts(Evita evita, List<SealedEntity> originalProductEntities) {
		final List<SealedEntity> entities = findEntitiesWithPrice(originalProductEntities);

		final List<Map<String,Object>> expectedBody = entities.stream()
			.map(sealedEntity -> createPriceForSaleDto(sealedEntity, CURRENCY_CZK, "basic"))
			.toList();

		testRESTCall()
			.urlPathSuffix("/product/list")
			.httpMethod(Request.METHOD_POST)
			.requestBody("{" +
					"\"filterBy\": {" +
					"    \"entity_primaryKey_inSet\": [%d, %d]," +
					"    \"price_inCurrency\": \"CZK\"," +
					"    \"price_inPriceLists\": [\"basic\"]" +
					"}," +
					"\"require\": {" +
					"  \"entity_fetch\": {" +
					"     \"price_content\": \"RESPECTING_FILTER\"" +
					"    }" +
					"  }" +
					"}",
				entities.get(0).getPrimaryKey(),
				entities.get(1).getPrimaryKey())
			.executeAndThen()
			.statusCode(200)
			.body("priceForSale", equalTo(expectedBody));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return price for products")
	void shouldReturnPriceForProducts(Evita evita, List<SealedEntity> originalProductEntities) {
		final List<SealedEntity> entities = findEntitiesWithPrice(originalProductEntities);

		final List<ArrayList<Map<String, Object>>> expectedBody = entities.stream()
			.map(sealedEntity -> createPricesDto(sealedEntity, CURRENCY_CZK, "basic"))
			.toList();

		testRESTCall()
			.urlPathSuffix("/product/list")
			.httpMethod(Request.METHOD_POST)
			.requestBody("{" +
					"\"filterBy\": {" +
					"    \"attribute_code_inSet\": [\"%s\", \"%s\"]," +
					"    \"price_inCurrency\": \"CZK\"," +
					"    \"price_inPriceLists\": [\"basic\"]" +
					"}," +
					"\"require\": {" +
					"  \"entity_fetch\": {" +
					"     \"price_content\": \"RESPECTING_FILTER\"" +
					"    }" +
					"  }" +
					"}",
				entities.get(0).getAttribute(ATTRIBUTE_CODE),
				entities.get(1).getAttribute(ATTRIBUTE_CODE))
			.executeAndThen()
			.statusCode(200)
			.body("prices", equalTo(expectedBody));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return all prices for products")
	void shouldReturnAllPricesForProducts(Evita evita, List<SealedEntity> originalProductEntities) {
		final List<SealedEntity> entities = findEntities(
			originalProductEntities,
			it -> !it.getPrices().isEmpty()
		);

		testRESTCall()
			.urlPathSuffix("/product/list")
			.httpMethod(Request.METHOD_POST)
			.requestBody("{" +
					"\"filterBy\": {" +
					"    \"attribute_code_inSet\": [\"%s\", \"%s\"]" +
					"}," +
					"\"require\": {" +
					"  \"entity_fetch\": {" +
					"     \"price_content\": \"RESPECTING_FILTER\"" +
					"    }" +
					"  }" +
					"}",
				entities.get(0).getAttribute(ATTRIBUTE_CODE),
				entities.get(1).getAttribute(ATTRIBUTE_CODE))
			.executeAndThen()
			.statusCode(200)
			.body("", hasSize(2))
			.body(EntityDescriptor.PRICES.name(), hasSize(greaterThan(0)))
			.body(EntityDescriptor.PRICES.name(), hasSize(greaterThan(0)));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return associated data for products")
	void shouldReturnAssociatedDataForProducts(Evita evita, List<SealedEntity> originalProductEntities) {
		final var entities = findEntities(
			originalProductEntities,
			it -> it.getAssociatedData(ASSOCIATED_DATA_LABELS, Locale.ENGLISH) != null &&
				it.getAllLocales().contains(Locale.ENGLISH)
		);

		final var expectedBody = entities.stream()
			.map(entity -> createEntityDtoWithAssociatedData(entity, Locale.ENGLISH, true))
			.toList();

		testRESTCall()
			.urlPathSuffix("/product/list")
			.httpMethod(Request.METHOD_POST)
			.requestBody("{" +
					"\"filterBy\": {" +
					"    \"attribute_code_inSet\": [\"%s\", \"%s\"]," +
					"    \"entity_locale_equals\": \"en\"" +
					"}," +
					"\"require\": {" +
					"  \"entity_fetch\": {" +
					"     \"associatedData_content\": [\"labels\"]" +
					"    }" +
					"  }" +
					"}",
				entities.get(0).getAttribute(ATTRIBUTE_CODE),
				entities.get(1).getAttribute(ATTRIBUTE_CODE))
			.executeAndThen()
			.statusCode(200)
			.body("", equalTo(expectedBody));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return associated data for products with locale in URL")
	void shouldReturnAssociatedDataForProductsWithLocaleInUrl(Evita evita, List<SealedEntity> originalProductEntities) {
		final var entities = findEntities(
			originalProductEntities,
			it -> it.getAssociatedData(ASSOCIATED_DATA_LABELS, Locale.ENGLISH) != null &&
				it.getAllLocales().contains(Locale.ENGLISH)
		);

		final var expectedBody = entities.stream()
			.map(entity -> createEntityDtoWithAssociatedData(entity, Locale.ENGLISH, false))
			.toList();

		testRESTCall()
			.urlPathSuffix("/" + Locale.ENGLISH.toLanguageTag() + "/product/list")
			.httpMethod(Request.METHOD_POST)
			.requestBody("{" +
					"\"filterBy\": {" +
					"    \"attribute_code_inSet\": [\"%s\", \"%s\"]" +
					"}," +
					"\"require\": {" +
					"  \"entity_fetch\": {" +
					"     \"associatedData_content\": [\"labels\"]" +
					"    }" +
					"  }" +
					"}",
				entities.get(0).getAttribute(ATTRIBUTE_CODE),
				entities.get(1).getAttribute(ATTRIBUTE_CODE))
			.executeAndThen()
			.statusCode(200)
			.body("", equalTo(expectedBody));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return single reference for products")
	void shouldReturnSingleReferenceForProducts(Evita evita, List<SealedEntity> originalProductEntities) {
		final var entities = findEntities(
			originalProductEntities,
			it -> it.getReferences(Entities.BRAND).size() == 1 &&
				it.getReferences(Entities.BRAND).iterator().next().getAttribute(ATTRIBUTE_MARKET_SHARE) != null
		);

		final var expectedBody = entities.stream()
			.map(entity -> {
				final ReferenceContract reference = entity.getReferences(Entities.BRAND).iterator().next();
				final SealedEntity referencedEntity = evita.queryCatalog(
					TEST_CATALOG,
					session -> {
						return session.getEntity(Entities.BRAND, reference.getReferencedPrimaryKey(), attributeContent(ATTRIBUTE_CODE));
					}
				).orElseThrow();

				return map()
					.e(EntityDescriptor.PRIMARY_KEY.name(), entity.getPrimaryKey())
					.e(EntityDescriptor.TYPE.name(), Entities.PRODUCT)
					.e(EntityDescriptor.LOCALES.name(), new ArrayList<>(0))
					.e(EntityDescriptor.ALL_LOCALES.name(), List.of(CZECH_LOCALE.toLanguageTag(), Locale.ENGLISH.toLanguageTag()))
					.e(EntityDescriptor.PRICE_INNER_RECORD_HANDLING.name(), PriceInnerRecordHandling.UNKNOWN.name())
					.e(Entities.BRAND.toLowerCase(), createReferenceDto(entity, Entities.BRAND, true))
					.build();
			})
			.toList();

		testRESTCall()
			.urlPathSuffix("/product/list")
			.httpMethod(Request.METHOD_POST)
			.requestBody("{" +
					"\"filterBy\": {" +
					"    \"attribute_code_inSet\": [\"%s\", \"%s\"]" +
					"}," +
					"\"require\": {" +
					"  \"entity_fetch\": {" +
					"     \"reference_brand_content\": {" +
					"        \"entity_fetch\": {" +
					"          \"attribute_content\": [\"marketShare\"]" +
					"          }" +
					"       }" +
					"    }" +
					"  }" +
					" }" +
					"}",
				entities.get(0).getAttribute(ATTRIBUTE_CODE),
				entities.get(1).getAttribute(ATTRIBUTE_CODE))
			.executeAndThen()
			.statusCode(200)
			.body("", equalTo(expectedBody));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should find product by complex query")
	void shouldFindProductByComplexQuery(Evita evita, List<SealedEntity> originalProductEntities) {
		final Random rnd = new Random(SEED);
		final List<SealedEntity> withTrueAlias = originalProductEntities.stream()
			.filter(it -> Objects.equals(Boolean.TRUE, it.getAttribute(ATTRIBUTE_ALIAS)) && it.getAttribute(ATTRIBUTE_PRIORITY) != null)
			.filter(it -> rnd.nextInt(100) > 85)
			.limit(2)
			.toList();
		final List<SealedEntity> withFalseAlias = originalProductEntities.stream()
			.filter(it -> Objects.equals(Boolean.FALSE, it.getAttribute(ATTRIBUTE_ALIAS)) && it.getAttribute(ATTRIBUTE_CODE) != null && it.getAttribute(ATTRIBUTE_PRIORITY) != null)
			.filter(it -> rnd.nextInt(100) > 85)
			.limit(5)
			.toList();

		final Integer[] expectedEntities = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								or(
									and(
										attributeEquals(ATTRIBUTE_ALIAS, withTrueAlias.get(0).getAttribute(ATTRIBUTE_ALIAS)),
										attributeEquals(ATTRIBUTE_PRIORITY, withTrueAlias.get(0).getAttribute(ATTRIBUTE_PRIORITY))
									),
									and(
										attributeEquals(ATTRIBUTE_ALIAS, withTrueAlias.get(1).getAttribute(ATTRIBUTE_ALIAS)),
										attributeEquals(ATTRIBUTE_PRIORITY, withTrueAlias.get(1).getAttribute(ATTRIBUTE_PRIORITY))
									),
									and(
										attributeEquals(ATTRIBUTE_ALIAS, false),
										attributeInSet(
											ATTRIBUTE_PRIORITY,
											(Long)withFalseAlias.get(0).getAttribute(ATTRIBUTE_PRIORITY),
											(Long)withFalseAlias.get(1).getAttribute(ATTRIBUTE_PRIORITY),
											(Long)withFalseAlias.get(2).getAttribute(ATTRIBUTE_PRIORITY),
											(Long)withFalseAlias.get(3).getAttribute(ATTRIBUTE_PRIORITY)
										)
									)
								),
								not(
									attributeEquals(ATTRIBUTE_CODE, withFalseAlias.get(4).getAttribute(ATTRIBUTE_CODE))
								)
							)
						),
						require(
							page(1, Integer.MAX_VALUE)
						)
					),
					EntityReference.class
				)
					.getRecordData()
					.stream()
					.map(EntityReference::getPrimaryKey)
					.toArray(Integer[]::new);
			}
		);

		assertTrue(expectedEntities.length > 0);

		testRESTCall()
			.urlPathSuffix("/product/list")
			.httpMethod(Request.METHOD_POST)
			.requestBody("{" +
					"\"filterBy\": {" +
					"\"or\": [" +
					"    {" +
					"        \"attribute_alias_equals\": %b," +
					"        \"attribute_priority_equals\": \"%s\"" +
					"    }," +
					"    {" +
					"        \"attribute_alias_equals\": %b," +
					"        \"attribute_priority_equals\": \"%s\"" +
					"    }," +
					"    {" +
					"        \"attribute_alias_equals\": false," +
					"        \"attribute_priority_inSet\": [\"%s\", \"%s\", \"%s\", \"%s\"]" +
					"    }" +
					"]," +
					"\"not\": {" +
					"    \"attribute_code_equals\": \"%s\"" +
					"}" +
					"}," +
					"\"require\": {" +
					"  \"page\": {" +
					"     \"number\": 0," +
					"     \"size\": %d"+
					"    }" +
					"  }" +
					"}",
				withTrueAlias.get(0).getAttribute(ATTRIBUTE_ALIAS),
				withTrueAlias.get(0).getAttribute(ATTRIBUTE_PRIORITY),
				withTrueAlias.get(1).getAttribute(ATTRIBUTE_ALIAS),
				withTrueAlias.get(1).getAttribute(ATTRIBUTE_PRIORITY),
				(Long)withFalseAlias.get(0).getAttribute(ATTRIBUTE_PRIORITY),
				(Long)withFalseAlias.get(1).getAttribute(ATTRIBUTE_PRIORITY),
				(Long)withFalseAlias.get(2).getAttribute(ATTRIBUTE_PRIORITY),
				(Long)withFalseAlias.get(3).getAttribute(ATTRIBUTE_PRIORITY),
				withFalseAlias.get(4).getAttribute(ATTRIBUTE_CODE),
				Integer.MAX_VALUE)
			.executeAndThen()
			.statusCode(200)
			.body(EntityDescriptor.PRIMARY_KEY.name(), contains(expectedEntities));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should order entities by complex query")
	void shouldOrderEntitiesByComplexQuery(Evita evita) {
		final Integer[] expectedEntities = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.query(
						query(
							collection(Entities.PRODUCT),
							filterBy(
								attributeLessThan(ATTRIBUTE_PRIORITY, 35000L)
							),
							orderBy(
								attributeNatural(ATTRIBUTE_CREATED, DESC),
								attributeNatural(ATTRIBUTE_MANUFACTURED)
							),
							require(
								page(1, 30)
							)
						),
						EntityReference.class
					)
					.getRecordData()
					.stream()
					.map(EntityReference::getPrimaryKey)
					.toArray(Integer[]::new);
			}
		);

		testRESTCall()
			.urlPathSuffix("/product/list")
			.httpMethod(Request.METHOD_POST)
			.requestBody("{" +
					"\"filterBy\": {" +
					"  \"attribute_priority_lessThan\": 35000" +
					"}," +
					"\"orderBy\": {" +
					"  \"attribute_created_natural\": \"DESC\"," +
					"  \"attribute_manufactured_natural\": \"ASC\"" +
					"}," +
					"\"require\": {" +
					"  \"page\": {" +
					"     \"number\": 0," +
					"     \"size\": 30"+
					"    }" +
					"  }" +
					"}")
			.executeAndThen()
			.statusCode(200)
			.body(EntityDescriptor.PRIMARY_KEY.name(), contains(expectedEntities));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should limit returned entities")
	void shouldLimitReturnedEntities(Evita evita) {
		final List<Integer> expectedEntities = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.query(
						query(
							collection(Entities.PRODUCT),
							filterBy(
								attributeLessThan(ATTRIBUTE_PRIORITY, 35000L)
							),
							require(
								page(1, Integer.MAX_VALUE)
							)
						),
						EntityReference.class
					)
					.getRecordData()
					.stream()
					.map(EntityReference::getPrimaryKey)
					.toList();
			}
		);
		assertTrue(expectedEntities.size() > 5);

		testRESTCall()
			.urlPathSuffix("/product/list")
			.httpMethod(Request.METHOD_POST)
			.requestBody("{" +
				"\"filterBy\": {" +
				"  \"attribute_priority_lessThan\": 35000" +
				"}," +
				"\"require\": {" +
				"  \"page\": {" +
				"     \"number\": 0," +
				"     \"size\": 5"+
				"    }" +
				"  }" +
				"}")
			.executeAndThen()
			.statusCode(200)
			.body(EntityDescriptor.PRIMARY_KEY.name(), contains(expectedEntities.stream().limit(5).toArray(Integer[]::new)));
	}


	@Nonnull
	private List<SealedEntity> findEntities(@Nonnull List<SealedEntity> originalProductEntities,
	                                        @Nonnull Predicate<SealedEntity> filter) {
		final List<SealedEntity> entities = originalProductEntities.stream()
			.filter(filter)
			.limit(2)
			.toList();
		assertEquals(2, entities.size());
		return entities;
	}

	@Nonnull
	private List<SealedEntity> findEntitiesWithPrice(List<SealedEntity> originalProductEntities) {
		return findEntities(
			originalProductEntities,
			it -> it.getPrices(CURRENCY_CZK, PRICE_LIST_BASIC).size() == 1
		);
	}
}
