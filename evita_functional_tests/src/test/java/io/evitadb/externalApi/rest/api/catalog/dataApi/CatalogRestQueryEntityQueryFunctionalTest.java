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

package io.evitadb.externalApi.rest.api.catalog.dataApi;

import io.evitadb.api.query.require.FacetStatisticsDepth;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.HierarchicalPlacementContract;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.extraResult.AttributeHistogram;
import io.evitadb.api.requestResponse.extraResult.FacetSummary;
import io.evitadb.api.requestResponse.extraResult.HierarchyParents;
import io.evitadb.api.requestResponse.extraResult.HierarchyParents.ParentsByReference;
import io.evitadb.api.requestResponse.extraResult.HierarchyStatistics;
import io.evitadb.api.requestResponse.extraResult.HistogramContract;
import io.evitadb.api.requestResponse.extraResult.PriceHistogram;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.api.catalog.dataApi.model.EntityDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.HierarchicalPlacementDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.ResponseDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.ExtraResultsDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.FacetSummaryDescriptor.FacetGroupStatisticsDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.FacetSummaryDescriptor.FacetRequestImpactDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.FacetSummaryDescriptor.FacetStatisticsDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HierarchyParentsDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HierarchyParentsDescriptor.ParentsOfEntityDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HierarchyParentsDescriptor.ParentsOfEntityDescriptor.ParentsOfReferenceDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HierarchyStatisticsDescriptor.HierarchyStatisticsLevelInfoDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HistogramDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HistogramDescriptor.BucketDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.SectionedAttributesDescriptor;
import io.evitadb.externalApi.rest.api.testSuite.RestTester.Request;
import io.evitadb.externalApi.rest.api.testSuite.TestDataGenerator;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.builder.MapBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.function.Function;
import java.util.function.Predicate;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.api.query.order.OrderDirection.DESC;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.builder.MapBuilder.map;
import static io.evitadb.test.generator.DataGenerator.*;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for REST catalog entity list query.
 *
 * @author Martin Veska, FG Forrest a.s. (c) 2022
 */
class CatalogRestQueryEntityQueryFunctionalTest extends CatalogRestEndpointFunctionalTest {

	private static final int SEED = 40;

	private static final String DATA_PATH = ResponseDescriptor.RECORD_PAGE.name() + ".data";

	@Nonnull
	@Override
	protected String getEndpointPath() {
		return "/test-catalog";
	}

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return products by primary key")
	void shouldReturnProductsByPrimaryKey(Evita evita, List<SealedEntity> originalProductEntities) {
		final var entities = findEntities(
			originalProductEntities,
			it -> it.getAttribute(ATTRIBUTE_CODE) != null
		);

		final var expectedBody = createBasicPageResponse(
			entities,
			entity ->
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
		);

		testRESTCall()
			.urlPathSuffix("/product/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody("{" +
					"\"filterBy\": {" +
					"  \"entity_primaryKey_inSet\": [%d, %d]" +
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
			.body(DATA_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return products by non-localized attribute")
	void shouldReturnProductsByNonLocalizedAttribute(Evita evita, List<SealedEntity> originalProductEntities) {
		final var entities = findEntities(
			originalProductEntities,
			it -> it.getAttribute(ATTRIBUTE_NAME, Locale.ENGLISH) != null &&
				it.getAllLocales().contains(CZECH_LOCALE) &&
				it.getAllLocales().contains(Locale.ENGLISH)
		);

		final var expectedBody = createBasicPageResponse(
			entities,
			entity ->
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
		);

		testRESTCall()
			.urlPathSuffix("/product/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody("{" +
					"\"filterBy\": {" +
					"  \"attribute_code_inSet\": [\"%s\", \"%s\"]," +
					"  \"entity_locale_equals\": \"en\"" +
					"}," +
					"\"require\": {" +
					"  \"entity_fetch\": {" +
					"     \"attribute_content\": [\"code\", \"name\"]" +
					"    }" +
					"  }" +
					"}",
				entities.get(0).getAttribute(ATTRIBUTE_CODE),
				entities.get(1).getAttribute(ATTRIBUTE_CODE))
			.executeAndThen()
			.statusCode(200)
			.body(DATA_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return products by localized attribute")
	void shouldReturnProductsByLocalizedAttribute(Evita evita, List<SealedEntity> originalProductEntities) {
		final var entities = findEntities(
			originalProductEntities,
			it -> it.getAttribute(ATTRIBUTE_URL, Locale.ENGLISH) != null &&
				it.getAttribute(ATTRIBUTE_NAME, Locale.ENGLISH) != null
		);

		final var expectedBody = createBasicPageResponse(
			entities,
			entity ->
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
									.e(ATTRIBUTE_NAME, entity.getAttribute(ATTRIBUTE_NAME, Locale.ENGLISH))
									.e(ATTRIBUTE_URL, entity.getAttribute(ATTRIBUTE_URL, Locale.ENGLISH))
									.build())
							.build()
						)
						.build())
					.build()
		);

		testRESTCall()
			.urlPathSuffix("/product/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody("{" +
					"\"filterBy\": {" +
					"  \"attribute_url_inSet\": [\"%s\", \"%s\"]," +
					"  \"entity_locale_equals\": \"en\"" +
					"}," +
					"\"require\": {" +
					"  \"entity_fetch\": {" +
					"     \"attribute_content\": [\"url\", \"name\"]" +
					"    }" +
					"  }" +
					"}",
				entities.get(0).getAttribute(ATTRIBUTE_URL, Locale.ENGLISH),
				entities.get(1).getAttribute(ATTRIBUTE_URL, Locale.ENGLISH)
			)
			.executeAndThen()
			.statusCode(200)
			.body(DATA_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return products by localized attribute with locale in URL")
	void shouldReturnProductsByLocalizedAttributeWithLocaleInUrl(Evita evita, List<SealedEntity> originalProductEntities) {
		final var entities = findEntities(
			originalProductEntities,
			it -> it.getAttribute(ATTRIBUTE_URL, Locale.ENGLISH) != null &&
				it.getAttribute(ATTRIBUTE_NAME, Locale.ENGLISH) != null
		);

		final var expectedBody = createBasicPageResponse(
			entities,
			entity ->
				map()
					.e(EntityDescriptor.PRIMARY_KEY.name(), entity.getPrimaryKey())
					.e(EntityDescriptor.TYPE.name(), Entities.PRODUCT)
					.e(EntityDescriptor.LOCALES.name(), List.of(Locale.ENGLISH.toLanguageTag()))
					.e(EntityDescriptor.ALL_LOCALES.name(), List.of(CZECH_LOCALE.toLanguageTag(), Locale.ENGLISH.toLanguageTag()))
					.e(EntityDescriptor.PRICE_INNER_RECORD_HANDLING.name(), PriceInnerRecordHandling.UNKNOWN.name())
					.e(EntityDescriptor.ATTRIBUTES.name(), map()
						.e(ATTRIBUTE_NAME, entity.getAttribute(ATTRIBUTE_NAME, Locale.ENGLISH))
						.e(ATTRIBUTE_URL, entity.getAttribute(ATTRIBUTE_URL, Locale.ENGLISH))
						.build())
					.build()
		);

		testRESTCall()
			.urlPathSuffix("/" + Locale.ENGLISH.toLanguageTag() + "/product/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody("{" +
					"\"filterBy\": {" +
					"  \"attribute_url_inSet\": [\"%s\", \"%s\"]" +
					"}," +
					"\"require\": {" +
					"  \"entity_fetch\": {" +
					"     \"attribute_content\": [\"url\", \"name\"]" +
					"    }" +
					"  }" +
					"}",
				entities.get(0).getAttribute(ATTRIBUTE_URL, Locale.ENGLISH),
				entities.get(1).getAttribute(ATTRIBUTE_URL, Locale.ENGLISH)
			)
			.executeAndThen()
			.statusCode(200)
			.body(DATA_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should fail when locale is both in body and in URL")
	void shouldFailWhenLocaleIsBothInBodyAndInUrl(Evita evita, List<SealedEntity> originalProductEntities) {
		testRESTCall()
			.urlPathSuffix("/" + Locale.ENGLISH.toLanguageTag() + "/product/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody("{" +
					"\"filterBy\": {" +
					"  \"attribute_url_inSet\": [\"some_url\"]," +
					"  \"entity_locale_equals\": \"en\"" +
					"} }"
			)
			.executeAndThen()
			.statusCode(400)
			.body("message", equalTo("When using localized URL path then entity_locale_equals constraint can't be present in filterBy."));
	}

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return error for invalid argument in products query")
	void shouldReturnErrorForInvalidArgumentInProductsQuery(Evita evita) {
		testRESTCall()
			.urlPathSuffix("/product/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody("{" +
				"\"filterBy\": {" +
				"  \"attribute_url_inSet\": [\"xxx\"]" +
				"}," +
				"\"require\": {" +
				"  \"entity_fetch_xxx\": {" +
				"     \"attribute_content\": [\"url\", \"name\"]" +
				"    }" +
				"  }" +
				"}"
			)
			.executeAndThen()
			.statusCode(500)
			.body("message", equalTo("Unknown constraint `entity_fetch_xxx`. Check that it has correct property type and name and support for classifier."));
	}

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return error for invalid query when single value is sent instead of array.")
	void shouldReturnErrorForInvalidQueryWhenSingleValueIsSentInsteadOfArray(Evita evita, List<SealedEntity> originalProductEntities) {
		final var entities = findEntitiesWithPrice(originalProductEntities);

		testRESTCall()
			.urlPathSuffix("/product/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody("{" +
					"\"filterBy\": {" +
					"  \"attribute_code_inSet\": \"%s\"" +
					"}" +
					"}",
				entities.get(0).getAttribute(ATTRIBUTE_CODE, String.class)
			)
			.executeAndThen()
			.statusCode(400)
			.body("message", equalTo("Expecting array but getting single value. Attribute name: attribute_code_inSet"));
	}

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should filter by and return price for sale for multiple products")
	void shouldFilterByAndReturnPriceForSaleForMultipleProducts(Evita evita, List<SealedEntity> originalProductEntities) {
		final var entities = findEntitiesWithPrice(originalProductEntities);

		final List<ArrayList<Map<String, Object>>> expectedBody = entities.stream()
			.map(sealedEntity -> createPricesDto(sealedEntity, CURRENCY_CZK, "basic"))
			.toList();

		testRESTCall()
			.urlPathSuffix("/product/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody("{" +
					"\"filterBy\": {" +
					"  \"attribute_code_inSet\": [\"%s\", \"%s\"]," +
					"  \"price_inCurrency\": \"CZK\"," +
					"  \"price_inPriceLists\": [\"basic\"]" +
					"}," +
					"\"require\": {" +
					"  \"entity_fetch\": {" +
					"     \"price_content\": \"RESPECTING_FILTER\"" +
					"    }" +
					"  }" +
					"}",
				entities.get(0).getAttribute(ATTRIBUTE_CODE),
				entities.get(1).getAttribute(ATTRIBUTE_CODE)
			)
			.executeAndThen()
			.statusCode(200)
			.body(DATA_PATH + ".prices", equalTo(expectedBody));
	}

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should filter products by non-existent price")
	void shouldFilterProductsByNonExistentPrice(Evita evita, List<SealedEntity> originalProductEntities) {
		final List<SealedEntity> entities = findEntitiesWithPrice(originalProductEntities);

		testRESTCall()
			.urlPathSuffix("/product/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody("{" +
					"\"filterBy\": {" +
					"  \"attribute_code_inSet\": [\"%s\", \"%s\"]," +
					"  \"price_inCurrency\": \"CZK\"," +
					"  \"price_inPriceLists\": [\"nonexistent\"]" +
					"}," +
					"\"require\": {" +
					"  \"entity_fetch\": {" +
					"     \"price_content\": \"RESPECTING_FILTER\"" +
					"    }" +
					"  }" +
					"}",
				entities.get(0).getAttribute(ATTRIBUTE_CODE),
				entities.get(1).getAttribute(ATTRIBUTE_CODE)
			)
			.executeAndThen()
			.statusCode(200)
			.body(DATA_PATH, hasSize(0));
	}

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return error for filtering products by unknown currency")
	void shouldReturnErrorForFilteringProductsByUnknownCurrency(Evita evita, List<SealedEntity> originalProductEntities) {
		final List<SealedEntity> entities = findEntitiesWithPrice(originalProductEntities);

		testRESTCall()
			.urlPathSuffix("/product/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody("{" +
					"\"filterBy\": {" +
					"  \"attribute_code_inSet\": [\"%s\", \"%s\"]," +
					"  \"price_inCurrency\": \"AAA\"," +
					"  \"price_inPriceLists\": [\"basic\"]" +
					"}," +
					"\"require\": {" +
					"  \"entity_fetch\": {" +
					"     \"price_content\": \"RESPECTING_FILTER\"" +
					"    }" +
					"  }" +
					"}",
				entities.get(0).getAttribute(ATTRIBUTE_CODE),
				entities.get(1).getAttribute(ATTRIBUTE_CODE)
			)
			.executeAndThen()
			.statusCode(400)
			.body("message", equalTo("The value `AAA` cannot be converted to the type `java.util.Currency`!"));
	}

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return custom price for sale for products")
	void shouldReturnCustomPriceForSaleForProducts(Evita evita, List<SealedEntity> originalProductEntities) {
		final var entities = findEntitiesWithPrice(originalProductEntities);

		final List<Map<String, Object>> expectedBody = entities.stream()
			.map(sealedEntity -> createPriceForSaleDto(sealedEntity, CURRENCY_CZK, "basic"))
			.toList();

		testRESTCall()
			.urlPathSuffix("/product/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody("{" +
					"\"filterBy\": {" +
					"  \"attribute_code_inSet\": [\"%s\", \"%s\"]," +
					"  \"price_inCurrency\": \"CZK\"," +
					"  \"price_inPriceLists\": [\"basic\"]" +
					"}," +
					"\"require\": {" +
					"  \"entity_fetch\": {" +
					"     \"price_content\": \"RESPECTING_FILTER\"" +
					"    }" +
					"  }" +
					"}",
				entities.get(0).getAttribute(ATTRIBUTE_CODE),
				entities.get(1).getAttribute(ATTRIBUTE_CODE)
			)
			.executeAndThen()
			.statusCode(200)
			.body(DATA_PATH + ".priceForSale", equalTo(expectedBody));
	}

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return associated data")
	void shouldReturnAssociatedData(Evita evita, List<SealedEntity> originalProductEntities) {
		final var entities = findEntities(
			originalProductEntities,
			it -> it.getAssociatedData(ASSOCIATED_DATA_LABELS, Locale.ENGLISH) != null &&
				it.getAllLocales().contains(Locale.ENGLISH)
		);

		final var expectedBody = entities.stream()
			.map(entity -> createEntityDtoWithAssociatedData(entity, Locale.ENGLISH, true))
			.toList();

		testRESTCall()
			.urlPathSuffix("/product/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody("{" +
					"\"filterBy\": {" +
					"  \"attribute_code_inSet\": [\"%s\", \"%s\"]," +
					"  \"entity_locale_equals\": \"en\"" +
					"}," +
					"\"require\": {" +
					"  \"entity_fetch\": {" +
					"     \"associatedData_content\": [\"labels\"]" +
					"    }" +
					"  }" +
					"}",
				entities.get(0).getAttribute(ATTRIBUTE_CODE),
				entities.get(1).getAttribute(ATTRIBUTE_CODE)
			)
			.executeAndThen()
			.statusCode(200)
			.body(DATA_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return associated data with locale in URL")
	void shouldReturnAssociatedDataWithLocaleInUrl(Evita evita, List<SealedEntity> originalProductEntities) {
		final var entities = findEntities(
			originalProductEntities,
			it -> it.getAssociatedData(ASSOCIATED_DATA_LABELS, Locale.ENGLISH) != null &&
				it.getAllLocales().contains(Locale.ENGLISH)
		);

		final var expectedBody = entities.stream()
			.map(entity -> createEntityDtoWithAssociatedData(entity, Locale.ENGLISH, false))
			.toList();

		testRESTCall()
			.urlPathSuffix("/" + Locale.ENGLISH.toLanguageTag() + "/product/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody("{" +
					"\"filterBy\": {" +
					"  \"attribute_code_inSet\": [\"%s\", \"%s\"]" +
					"}," +
					"\"require\": {" +
					"  \"entity_fetch\": {" +
					"     \"associatedData_content\": [\"labels\"]" +
					"    }" +
					"  }" +
					"}",
				entities.get(0).getAttribute(ATTRIBUTE_CODE),
				entities.get(1).getAttribute(ATTRIBUTE_CODE)
			)
			.executeAndThen()
			.statusCode(200)
			.body(DATA_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return single reference for products")
	void shouldReturnSingleReferenceForProducts(Evita evita, List<SealedEntity> originalProductEntities) {
		final var entities = findEntities(
			originalProductEntities,
			it -> it.getReferences(Entities.BRAND).size() == 1 &&
				it.getReferences(Entities.BRAND).iterator().next().getAttribute(TestDataGenerator.ATTRIBUTE_MARKET_SHARE) != null
		);

		final var expectedBody = entities.stream()
			.map(entity -> map()
				.e(EntityDescriptor.PRIMARY_KEY.name(), entity.getPrimaryKey())
				.e(EntityDescriptor.TYPE.name(), Entities.PRODUCT)
				.e(EntityDescriptor.LOCALES.name(), new ArrayList<>(0))
				.e(EntityDescriptor.ALL_LOCALES.name(), List.of(CZECH_LOCALE.toLanguageTag(), Locale.ENGLISH.toLanguageTag()))
				.e(EntityDescriptor.PRICE_INNER_RECORD_HANDLING.name(), PriceInnerRecordHandling.UNKNOWN.name())
				.e(Entities.BRAND.toLowerCase(), createReferenceDto(entity, Entities.BRAND, true))
				.build()
			)
			.toList();

		testRESTCall()
			.urlPathSuffix("/product/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody("{" +
					"\"filterBy\": {" +
					"  \"attribute_code_inSet\": [\"%s\", \"%s\"]" +
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
					"}",
				entities.get(0).getAttribute(ATTRIBUTE_CODE),
				entities.get(1).getAttribute(ATTRIBUTE_CODE)
			)
			.executeAndThen()
			.statusCode(200)
			.body(DATA_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return reference list for products")
	void shouldReturnReferenceListForProducts(Evita evita, List<SealedEntity> originalProductEntities) {
		final var entities = findEntities(
			originalProductEntities,
			it -> it.getReferences(Entities.STORE).size() > 1
		);

		final var expectedBody = entities.stream()
			.map(entity -> map()
				.e(EntityDescriptor.PRIMARY_KEY.name(), entity.getPrimaryKey())
				.e(EntityDescriptor.TYPE.name(), Entities.PRODUCT)
				.e(EntityDescriptor.LOCALES.name(), new ArrayList<>(0))
				.e(EntityDescriptor.ALL_LOCALES.name(), List.of(CZECH_LOCALE.toLanguageTag(), Locale.ENGLISH.toLanguageTag()))
				.e(EntityDescriptor.PRICE_INNER_RECORD_HANDLING.name(), PriceInnerRecordHandling.UNKNOWN.name())
				.e(Entities.STORE.toLowerCase(), createReferencesDto(entity, Entities.STORE, true))
				.build()
			)
			.toList();

		testRESTCall()
			.urlPathSuffix("/product/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody("{" +
					"\"filterBy\": {" +
					"  \"attribute_code_inSet\": [\"%s\", \"%s\"]" +
					"}," +
					"\"require\": {" +
					"  \"entity_fetch\": {" +
					"     \"reference_store_content\": {" +
					"        \"entity_fetch\": {" +
					"          \"attribute_content\": [\"marketShare\"]" +
					"          }" +
					"       }" +
					"    }" +
					"  }" +
					"}",
				entities.get(0).getAttribute(ATTRIBUTE_CODE),
				entities.get(1).getAttribute(ATTRIBUTE_CODE)
			)
			.executeAndThen()
			.statusCode(200)
			.body(DATA_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
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
												(Long) withFalseAlias.get(0).getAttribute(ATTRIBUTE_PRIORITY),
												(Long) withFalseAlias.get(1).getAttribute(ATTRIBUTE_PRIORITY),
												(Long) withFalseAlias.get(2).getAttribute(ATTRIBUTE_PRIORITY),
												(Long) withFalseAlias.get(3).getAttribute(ATTRIBUTE_PRIORITY)
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
			.urlPathSuffix("/product/query")
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
					"  \"strip\": {" +
					"     \"limit\": %d" +
					"    }" +
					"  }" +
					"}",
				withTrueAlias.get(0).getAttribute(ATTRIBUTE_ALIAS),
				withTrueAlias.get(0).getAttribute(ATTRIBUTE_PRIORITY),
				withTrueAlias.get(1).getAttribute(ATTRIBUTE_ALIAS),
				withTrueAlias.get(1).getAttribute(ATTRIBUTE_PRIORITY),
				(Long) withFalseAlias.get(0).getAttribute(ATTRIBUTE_PRIORITY),
				(Long) withFalseAlias.get(1).getAttribute(ATTRIBUTE_PRIORITY),
				(Long) withFalseAlias.get(2).getAttribute(ATTRIBUTE_PRIORITY),
				(Long) withFalseAlias.get(3).getAttribute(ATTRIBUTE_PRIORITY),
				withFalseAlias.get(4).getAttribute(ATTRIBUTE_CODE),
				Integer.MAX_VALUE
			)
			.executeAndThen()
			.statusCode(200)
			.body(
				DATA_PATH + "." + EntityDescriptor.PRIMARY_KEY.name(),
				contains(expectedEntities)
			);
	}

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
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
								attributeNatural(TestDataGenerator.ATTRIBUTE_CREATED, DESC),
								attributeNatural(TestDataGenerator.ATTRIBUTE_MANUFACTURED)
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
			.urlPathSuffix("/product/query")
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
				"  \"strip\": {" +
				"     \"limit\": 30" +
				"    }" +
				"  }" +
				"}")
			.executeAndThen()
			.statusCode(200)
			.body(
				DATA_PATH + "." + EntityDescriptor.PRIMARY_KEY.name(),
				contains(expectedEntities)
			);
	}

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return page of entities")
	void shouldReturnPageOfEntities(Evita evita) {
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
		assertTrue(expectedEntities.size() > 10);

		testRESTCall()
			.urlPathSuffix("/product/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody("{" +
				"\"filterBy\": {" +
				"  \"attribute_priority_lessThan\": 35000" +
				"}," +
				"\"require\": {" +
				"  \"page\": {" +
				"     \"number\": 2," +
				"     \"size\": 3" +
				"    }" +
				"  }" +
				"}")
			.executeAndThen()
			.statusCode(200)
			.body(
				DATA_PATH + "." + EntityDescriptor.PRIMARY_KEY.name(),
				contains(
					expectedEntities.stream()
						.skip(3)
						.limit(3)
						.toArray(Integer[]::new)
				)
			);
	}

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return strip of entities")
	void shouldReturnStripOfEntities(Evita evita) {
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
		assertTrue(expectedEntities.size() > 10);

		testRESTCall()
			.urlPathSuffix("/product/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody("{" +
				"\"filterBy\": {" +
				"  \"attribute_priority_lessThan\": 35000" +
				"}," +
				"\"require\": {" +
				"  \"strip\": {" +
				"     \"offset\": 2," +
				"     \"limit\": 3" +
				"    }" +
				"  }" +
				"}")
			.executeAndThen()
			.statusCode(200)
			.body(
				DATA_PATH + "." + EntityDescriptor.PRIMARY_KEY.name(),
				contains(
					expectedEntities.stream()
						.skip(2)
						.limit(3)
						.toArray(Integer[]::new)
				)
			);
	}

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return attribute histogram")
	void shouldReturnAttributeHistogram(Evita evita) {
		final EvitaResponse<EntityReference> response = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							attributeIsNotNull(ATTRIBUTE_ALIAS)
						),
						require(
							page(1, Integer.MAX_VALUE),
							attributeHistogram(20, ATTRIBUTE_QUANTITY)
						)
					),
					EntityReference.class
				);
			}
		);

		final var expectedHistogram = createAttributeHistogramDto(response, ATTRIBUTE_QUANTITY);

		testRESTCall()
			.urlPathSuffix("/product/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody("{" +
					"\"filterBy\": {" +
					"  \"attribute_alias_is\": \"NOT_NULL\"" +
					"}," +
					"\"require\": {" +
					"  \"page\": {" +
					"     \"number\": 1," +
					"     \"size\": %d" +
					"    }," +
					"  \"attribute_histogram\": {" +
					"     \"requestedBucketCount\": 20," +
					"     \"attributeName\": [\"" + ATTRIBUTE_QUANTITY + "\"]" +
					"    }" +
					"  }" +
					"}",
				Integer.MAX_VALUE)
			.executeAndThen()
			.statusCode(200)
			.body(
				ResponseDescriptor.EXTRA_RESULTS.name() + "." + ExtraResultsDescriptor.ATTRIBUTE_HISTOGRAM.name() + "." + ATTRIBUTE_QUANTITY,
				equalTo(expectedHistogram)
			);
	}


	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return error for missing attribute histogram buckets count")
	void shouldReturnErrorForMissingAttributeHistogramBucketsCount(Evita evita) {
		testRESTCall()
			.urlPathSuffix("/product/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody("{" +
					"\"filterBy\": {" +
					"  \"attribute_alias_is\": \"NOT_NULL\"" +
					"}," +
					"\"require\": {" +
					"  \"page\": {" +
					"     \"number\": 1," +
					"     \"size\": %d" +
					"    }," +
					"  \"attribute_histogram\": {" +
					"     \"attributeName\": [\"" + ATTRIBUTE_QUANTITY + "\"]" +
					"    }" +
					"  }" +
					"}",
				Integer.MAX_VALUE)
			.executeAndThen()
			.statusCode(400)
			.body("message", equalTo("Constraint `attribute_histogram` requires parameter `requestedBucketCount` to be non-null."));
	}

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return price histogram")
	void shouldReturnPriceHistogram(Evita evita) {
		final EvitaResponse<EntityReference> response = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								priceInCurrency(CURRENCY_EUR),
								priceInPriceLists(PRICE_LIST_VIP, PRICE_LIST_BASIC)
							)
						),
						require(
							page(1, Integer.MAX_VALUE),
							priceHistogram(20)
						)
					),
					EntityReference.class
				);
			}
		);

		final var expectedBody = createPriceHistogramDto(response);

		testRESTCall()
			.urlPathSuffix("/product/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody("{" +
					"\"filterBy\": {" +
					"  \"price_inCurrency\": \"EUR\"," +
					"  \"price_inPriceLists\": [\"vip\",\"basic\"]" +
					"}," +
					"\"require\": {" +
					"  \"page\": {" +
					"     \"number\": 1," +
					"     \"size\": %d" +
					"    }," +
					"  \"price_histogram\": 20" +
					"  }" +
					"}",
				Integer.MAX_VALUE)
			.executeAndThen()
			.statusCode(200)
			.body(
				ResponseDescriptor.EXTRA_RESULTS.name() + "." + ExtraResultsDescriptor.PRICE_HISTOGRAM.name(),
				equalTo(expectedBody)
			);
	}

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return error for missing price histogram buckets count")
	void shouldReturnErrorForMissingPriceHistogramBucketsCount(Evita evita) {
		testRESTCall()
			.urlPathSuffix("/product/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody("""
					{
						"filterBy": {
							"price_inCurrency": "EUR",
							"price_inPriceLists": ["vip","basic"]
						},
						"require": {
							"page": {
								"number": 1,
								"size": %d
							},
							"price_histogram": null
						}
					}
					""",
				Integer.MAX_VALUE)
			.executeAndThen()
			.statusCode(400)
			.body("message", equalTo("Constraint `price_histogram` requires non-null value."));
	}

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return category parents for products")
	void shouldReturnCategoryParentsForProducts(Evita evita) {
		final EvitaResponse<EntityReference> response = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							hierarchyWithin(Entities.CATEGORY, 95)
						),
						require(
							page(1, Integer.MAX_VALUE),
							hierarchyParentsOfReference(Entities.CATEGORY, entityFetch(attributeContent(ATTRIBUTE_CODE)))
						)
					),
					EntityReference.class
				);
			}
		);
		assertFalse(response.getRecordData().isEmpty());

		final var expectedBody = createHierarchyParentsDto(response);

		testRESTCall()
			.urlPathSuffix("/product/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody("""
					{
						"filterBy": {
							"hierarchy_category_within": {
								"ofParent": 95
							}
						},
						"require": {
							"page": {
								"number": 1,
								"size": %d
							},
							"hierarchy_category_parentsOfReference": {
						        "entity_fetch": {
									"attribute_content": [
										"code"
									]
						        }
					        }
						}
					}
					""",
				Integer.MAX_VALUE)
			.executeAndThen()
			.statusCode(200)
			.body(
				ResponseDescriptor.EXTRA_RESULTS.name() + "." + ExtraResultsDescriptor.HIERARCHY_PARENTS.name() + ".category",
				equalTo(expectedBody)
			);
	}

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return self parents for category")
	void shouldReturnSelfParentsForCategory(Evita evita) {
		final EvitaResponse<EntityReference> response = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.query(
					query(
						collection(Entities.CATEGORY),
						require(
							page(1, Integer.MAX_VALUE),
							hierarchyParentsOfSelf(entityFetch(attributeContent(ATTRIBUTE_CODE)))
						)
					),
					EntityReference.class
				);
			}
		);
		assertFalse(response.getRecordData().isEmpty());

		final var expectedBody = createSelfHierarchyParentsDto(response);

		testRESTCall()
			.urlPathSuffix("/category/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody("""
					{
						"require": {
							"page": {
								"number": 1,
								"size": %d
							},
							"hierarchy_parentsOfSelf": {
						        "entity_fetch": {
									"attribute_content": [
										"code"
									]
						        }
					        }
						}
					}
					""",
				Integer.MAX_VALUE)
			.executeAndThen()
			.statusCode(200)
			.body(
				ResponseDescriptor.EXTRA_RESULTS.name() + "." + ExtraResultsDescriptor.HIERARCHY_PARENTS.name() + "." + HierarchyParentsDescriptor.SELF.name(),
				equalTo(expectedBody)
			);
	}

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should pass locale to parents")
	void shouldPassLocaleToParents(Evita evita) {
		final EvitaResponse<EntityReference> response = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								hierarchyWithin(Entities.CATEGORY, 95),
								entityLocaleEquals(CZECH_LOCALE)
							)
						),
						require(
							page(1, Integer.MAX_VALUE),
							hierarchyParentsOfReference(Entities.CATEGORY, entityFetch(attributeContent(ATTRIBUTE_NAME)))
						)
					),
					EntityReference.class
				);
			}
		);
		assertFalse(response.getRecordData().isEmpty());

		final var expectedBody = createLocalizedAttributeOfHierarchyParentsDto(response);

		testRESTCall()
			.urlPathSuffix("/product/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody("""
					{
						"filterBy": {
							"hierarchy_category_within": {
								"ofParent": 95
							},
							"entity_locale_equals": "cs-CZ"
						},
						"require": {
							"page": {
								"number": 1,
								"size": %d
							},
							"hierarchy_category_parentsOfReference": {
						        "entity_fetch": {
									"attribute_content": [
										"name"
									]
						        }
					        }
						}
					}
					""",
				Integer.MAX_VALUE)
			.executeAndThen()
			.statusCode(200)
			.body(
				ResponseDescriptor.EXTRA_RESULTS.name() + "." + ExtraResultsDescriptor.HIERARCHY_PARENTS.name() + ".category.references.parentEntities.attributes",
				equalTo(expectedBody)
			);
	}

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return error for self statistics of product")
	void shouldReturnErrorForSelfStatisticsOfProduct(Evita evita) {
		testRESTCall()
			.urlPathSuffix("/product/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody("""
					{
						"filterBy": {
							"entity_locale_equals": "cs-CZ"
						},
						"require": {
							"page": {
								"number": 1,
								"size": %d
							},
							"hierarchy_parentsOfSelf": {
						        "entity_fetch": {
									"attribute_content": [
										"name"
									]
						        }
					        }
						}
					}
					""",
				Integer.MAX_VALUE)
			.executeAndThen()
			.statusCode(400)
			.body("message", equalTo("Entity schema for `PRODUCT` doesn't allow hierarchy!"));
	}

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should pass locale to hierarchy statistics entities")
	void shouldPassLocaleToHierarchyStatisticsEntities(Evita evita) {
		final EvitaResponse<EntityReference> response = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								attributeEquals(ATTRIBUTE_ALIAS, true),
								entityLocaleEquals(CZECH_LOCALE)
							)
						),
						require(
							page(1, Integer.MAX_VALUE),
							hierarchyStatisticsOfReference(Entities.CATEGORY, entityFetch(attributeContent(ATTRIBUTE_NAME)))
						)
					),
					EntityReference.class
				);
			}
		);
		assertFalse(response.getRecordData().isEmpty());

		final var expectedBody = response.getExtraResult(HierarchyStatistics.class)
			.getStatistics(Entities.CATEGORY)
			.stream()
			.map(it -> ((SealedEntity) it.entity()).getAttribute(ATTRIBUTE_NAME, CZECH_LOCALE))
			.toList();

		testRESTCall()
			.urlPathSuffix("/product/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody("""
					{
						"filterBy": {
							"attribute_alias_equals": true,
							"entity_locale_equals": "cs-CZ"
						},
						"require": {
							"page": {
								"number": 1,
								"size": %d
							},
							"hierarchy_category_statisticsOfReference": {
						        "entity_fetch": {
									"attribute_content": [
										"name"
									]
						        }
					        }
						}
					}
					""",
				Integer.MAX_VALUE)
			.executeAndThen()
			.statusCode(200)
			.body(
				ResponseDescriptor.EXTRA_RESULTS.name() + "." + ExtraResultsDescriptor.HIERARCHY_STATISTICS.name() + ".category."
					+ HierarchyStatisticsLevelInfoDescriptor.ENTITY.name() + "." + EntityDescriptor.ATTRIBUTES.name() + "."
					+ SectionedAttributesDescriptor.LOCALIZED.name() + "." + CZECH_LOCALE.toLanguageTag() + "." + ATTRIBUTE_NAME,
				equalTo(expectedBody)
			);
	}

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should get hierarchy statistics with entities reference only")
	void shouldGetHierarchyStatisticsWithEntitiesReferenceOnly(Evita evita) {
		final EvitaResponse<EntityReference> response = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								attributeEquals(ATTRIBUTE_ALIAS, true)
							)
						),
						require(
							page(1, Integer.MAX_VALUE),
							hierarchyStatisticsOfReference(Entities.CATEGORY)
						)
					),
					EntityReference.class
				);
			}
		);
		assertFalse(response.getRecordData().isEmpty());

		final var expectedBody = response.getExtraResult(HierarchyStatistics.class)
			.getStatistics(Entities.CATEGORY)
			.stream()
			.map(it -> {
				return map()
					.e(EntityDescriptor.PRIMARY_KEY.name(), it.entity().getPrimaryKey())
					.e(EntityDescriptor.TYPE.name(), it.entity().getType())
					.build();
			})
			.toList();

		testRESTCall()
			.urlPathSuffix("/product/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody("""
					{
						"filterBy": {
							"attribute_alias_equals": true,
							"entity_locale_equals": "cs-CZ"
						},
						"require": {
							"page": {
								"number": 1,
								"size": %d
							},
							"hierarchy_category_statisticsOfReference": {}
						}
					}
					""",
				Integer.MAX_VALUE)
			.executeAndThen()
			.statusCode(200)
			.body(
				ResponseDescriptor.EXTRA_RESULTS.name() + "." + ExtraResultsDescriptor.HIERARCHY_STATISTICS.name() + ".category."
					+ HierarchyStatisticsLevelInfoDescriptor.ENTITY.name(),
				equalTo(expectedBody)
			);
	}

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return facet summary with counts for products")
	void shouldReturnFacetSummaryWithCountsForProducts(Evita evita) {
		final EvitaResponse<EntityReference> response = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.query(
					query(
						collection(Entities.PRODUCT),
						require(
							facetSummaryOfReference(Entities.BRAND, FacetStatisticsDepth.COUNTS)
						)
					),
					EntityReference.class
				);
			}
		);
		assertFalse(response.getExtraResult(FacetSummary.class).getFacetGroupStatistics().isEmpty());

		final var expectedBody = createFacetSummaryWithCountsDto(response);

		testRESTCall()
			.urlPathSuffix("/product/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody("""
					{
						"require": {
							"facet_summary": {
								"statisticsDepth":"COUNTS"
					        }
						}
					}
					""",
				Integer.MAX_VALUE)
			.executeAndThen()
			.statusCode(200)
			.body(
				ResponseDescriptor.EXTRA_RESULTS.name() + "." + ExtraResultsDescriptor.FACET_SUMMARY.name() + ".brand",
				equalTo(expectedBody)
			);
	}

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return facet summary with impacts and entities for products")
	void shouldReturnFacetSummaryWithImpactsAndEntitiesForProducts(Evita evita) {
		final EvitaResponse<EntityReference> response = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.query(
					query(
						collection(Entities.PRODUCT),
						require(
							facetSummaryOfReference(
								Entities.BRAND,
								FacetStatisticsDepth.IMPACT,
								entityFetch(attributeContent(ATTRIBUTE_CODE))
							)
						)
					),
					EntityReference.class
				);
			}
		);
		assertFalse(response.getExtraResult(FacetSummary.class).getFacetGroupStatistics().isEmpty());

		final var expectedBody = createFacetSummaryWithImpactsDto(response);

		testRESTCall()
			.urlPathSuffix("/product/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody("""
					{
						"require": {
							"facet_summary": {
								"statisticsDepth":"IMPACT",
								"requirements": {
					   				"entity_fetch": {
					   					"attribute_content": ["code"]
					      			}
					   			}
					        }
						}
					}
					""",
				Integer.MAX_VALUE)
			.executeAndThen()
			.statusCode(200)
			.body(
				ResponseDescriptor.EXTRA_RESULTS.name() + "." + ExtraResultsDescriptor.FACET_SUMMARY.name() + ".brand",
				equalTo(expectedBody)
			);
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

	@Nonnull
	private List<Map<String, Object>> createBasicPageResponse(@Nonnull List<SealedEntity> entities,
	                                                          @Nonnull Function<SealedEntity, Map<String, Object>> entityMapper) {
		return entities.stream()
			.map(entityMapper)
			.toList();
	}

	@Nonnull
	private Map<String, Object> createAttributeHistogramDto(@Nonnull EvitaResponse<EntityReference> response,
	                                                        @Nonnull String attributeName) {
		final AttributeHistogram attributeHistogram = response.getExtraResult(AttributeHistogram.class);
		final HistogramContract histogram = attributeHistogram.getHistogram(attributeName);

		return map()
			.e(HistogramDescriptor.MAX.name(), histogram.getMax().toString())
			.e(HistogramDescriptor.BUCKETS.name(), Arrays.stream(histogram.getBuckets())
				.map(bucket -> map()
					.e(BucketDescriptor.INDEX.name(), bucket.getIndex())
					.e(BucketDescriptor.THRESHOLD.name(), bucket.getThreshold().toString())
					.e(BucketDescriptor.OCCURRENCES.name(), bucket.getOccurrences())
					.build())
				.toList())
			.e(HistogramDescriptor.MIN.name(), histogram.getMin().toString())
			.e(HistogramDescriptor.OVERALL_COUNT.name(), histogram.getOverallCount())
			.build();
	}

	@Nonnull
	private Map<String, Object> createPriceHistogramDto(@Nonnull EvitaResponse<EntityReference> response) {
		final PriceHistogram priceHistogram = response.getExtraResult(PriceHistogram.class);

		return map()
			.e(HistogramDescriptor.MAX.name(), priceHistogram.getMax().toString())
			.e(HistogramDescriptor.BUCKETS.name(), Arrays.stream(priceHistogram.getBuckets())
				.map(bucket -> map()
					.e(BucketDescriptor.INDEX.name(), bucket.getIndex())
					.e(BucketDescriptor.THRESHOLD.name(), bucket.getThreshold().toString())
					.e(BucketDescriptor.OCCURRENCES.name(), bucket.getOccurrences())
					.build())
				.toList())
			.e(HistogramDescriptor.MIN.name(), priceHistogram.getMin().toString())
			.e(HistogramDescriptor.OVERALL_COUNT.name(), priceHistogram.getOverallCount())
			.build();
	}

	@Nonnull
	private List<Map<String, Object>> createHierarchyParentsDto(@Nonnull EvitaResponse<EntityReference> response) {
		final HierarchyParents hierarchyParents = response.getExtraResult(HierarchyParents.class);
		final ParentsByReference categoryParents = hierarchyParents.ofType(Entities.CATEGORY);

		final var parentsDtos = new LinkedList<Map<String, Object>>();

		categoryParents.getParents().forEach((productId, parentsForProduct) -> {
			parentsDtos.add(
				map()
					.e(ParentsOfEntityDescriptor.PRIMARY_KEY.name(), productId)
					.e(ParentsOfEntityDescriptor.REFERENCES.name(), parentsForProduct.entrySet()
						.stream()
						.map(reference -> map()
							.e(ParentsOfReferenceDescriptor.PRIMARY_KEY.name(), reference.getKey())
							.e(ParentsOfReferenceDescriptor.PARENT_ENTITIES.name(), Arrays.stream(reference.getValue())
								.map(parentEntity -> map()
									.e(EntityDescriptor.PRIMARY_KEY.name(), parentEntity.getPrimaryKey())
									.e(EntityDescriptor.TYPE.name(), parentEntity.getType())
									.e(EntityDescriptor.LOCALES.name(), new ArrayList<>())
									.e(EntityDescriptor.ALL_LOCALES.name(), ((SealedEntity) parentEntity).getAllLocales().stream().map(Locale::toLanguageTag).toList())
									.e(EntityDescriptor.PRICE_INNER_RECORD_HANDLING.name(), PriceInnerRecordHandling.UNKNOWN.name())
									.e(EntityDescriptor.HIERARCHICAL_PLACEMENT.name(), createHierarchicalPlacementDto((SealedEntity) parentEntity))
									.e(EntityDescriptor.ATTRIBUTES.name(), map()
										.e(SectionedAttributesDescriptor.GLOBAL.name(), map()
											.e(ATTRIBUTE_CODE, ((SealedEntity) parentEntity).getAttribute(ATTRIBUTE_CODE))
											.build())
										.build())
									.build())
								.toList())
							.build())
						.toList())
					.build()
			);
		});

		return parentsDtos;
	}

	private static Map<String, Object> createHierarchicalPlacementDto(SealedEntity parentEntity) {
		final Optional<HierarchicalPlacementContract> hierarchicalPlacement = parentEntity.getHierarchicalPlacement();
		if (hierarchicalPlacement.isPresent()) {
			return map()
				.e(HierarchicalPlacementDescriptor.PARENT_PRIMARY_KEY.name(), hierarchicalPlacement.get().getParentPrimaryKey())
				.e(HierarchicalPlacementDescriptor.ORDER_AMONG_SIBLINGS.name(), hierarchicalPlacement.get().getOrderAmongSiblings())
				.build();
		} else {
			return map().build();
		}
	}

	@Nonnull
	private List<List<List<Map<String, Object>>>> createLocalizedAttributeOfHierarchyParentsDto(@Nonnull EvitaResponse<EntityReference> response) {
		final HierarchyParents hierarchyParents = response.getExtraResult(HierarchyParents.class);
		final ParentsByReference categoryParents = hierarchyParents.ofType(Entities.CATEGORY);

		final List<List<List<Map<String, Object>>>> dtos = new LinkedList<>();

		categoryParents.getParents().forEach((productId, parentsForProduct) -> {
			final List<List<Map<String, Object>>> data = parentsForProduct.entrySet()
				.stream()
				.map(reference ->
					Arrays.stream(reference.getValue())
						.map(parentEntity -> map()
							.e(SectionedAttributesDescriptor.LOCALIZED.name(), map()
								.e(CZECH_LOCALE.toLanguageTag(), map()
									.e(ATTRIBUTE_NAME, ((SealedEntity) parentEntity).getAttribute(ATTRIBUTE_NAME, CZECH_LOCALE))
									.build())
								.build())
							.build())
						.toList()
				).toList();

			dtos.add(data);
		});


		return dtos;
	}

	/*
	@Nonnull
	private List<Map<String, Object>> createAttributeOfHierarchyParentsDto(@Nonnull EvitaResponse<EntityReference> response) {
		final HierarchyParents hierarchyParents = response.getExtraResult(HierarchyParents.class);
		final ParentsByReference categoryParents = hierarchyParents.ofType(Entities.CATEGORY);

		final var parentsDtos = new LinkedList<Map<String, Object>>();

		categoryParents.getParents().forEach((productId, parentsForProduct) -> {
			parentsDtos.add(
				map()
					.e(ParentsOfEntityDescriptor.REFERENCES.name(), parentsForProduct.entrySet()
						.stream()
						.map(reference -> map()
							.e(ParentsOfReferenceDescriptor.PARENT_ENTITIES.name(), Arrays.stream(reference.getValue())
								.map(parentEntity -> map()
									.e(EntityDescriptor.ATTRIBUTES.name(), map()
										.e(ATTRIBUTE_NAME, ((SealedEntity) parentEntity).getAttribute(ATTRIBUTE_NAME, CZECH_LOCALE))
										.build())
									.build())
								.toList())
							.build())
						.toList())
					.build()
			);
		});

		return parentsDtos;
	}

	 */

	private List<Map<String, Object>> createSelfHierarchyParentsDto(@Nonnull EvitaResponse<EntityReference> response) {
		final HierarchyParents hierarchyParents = response.getExtraResult(HierarchyParents.class);
		final ParentsByReference categoryParents = hierarchyParents.ofSelf();

		final var parentsDtos = new LinkedList<Map<String, Object>>();

		categoryParents.getParents().forEach((productId, parentsForProduct) -> {
			parentsDtos.add(
				map()
					.e(ParentsOfEntityDescriptor.PRIMARY_KEY.name(), productId)
					.e(ParentsOfEntityDescriptor.REFERENCES.name(), parentsForProduct.entrySet()
						.stream()
						.map(reference -> {
								final MapBuilder mapBuilder = map();
								mapBuilder.e(ParentsOfReferenceDescriptor.PRIMARY_KEY.name(), reference.getKey());
								if (reference.getValue().length > 0) {
									mapBuilder.e(ParentsOfReferenceDescriptor.PARENT_ENTITIES.name(), Arrays.stream(reference.getValue())
										.map(parentEntity -> map()
											.e(EntityDescriptor.PRIMARY_KEY.name(), parentEntity.getPrimaryKey())
											.e(EntityDescriptor.TYPE.name(), parentEntity.getType())
											.e(EntityDescriptor.LOCALES.name(), new ArrayList<>())
											.e(EntityDescriptor.ALL_LOCALES.name(), ((SealedEntity) parentEntity).getAllLocales().stream().map(Locale::toLanguageTag).toList())
											.e(EntityDescriptor.HIERARCHICAL_PLACEMENT.name(), createHierarchicalPlacementDto((SealedEntity) parentEntity))
											.e(EntityDescriptor.PRICE_INNER_RECORD_HANDLING.name(), PriceInnerRecordHandling.UNKNOWN.name())
											.e(EntityDescriptor.ATTRIBUTES.name(), map()
												.e(SectionedAttributesDescriptor.GLOBAL.name(), map()
													.e(ATTRIBUTE_CODE, ((SealedEntity) parentEntity).getAttribute(ATTRIBUTE_CODE))
													.build())
												.build())
											.build())
										.toList());
								}
								return mapBuilder.build();
							}
						)
						.toList())
					.build()
			);
		});

		return parentsDtos;
	}

	//todo
	/*
	@Nonnull
	private List<Map<String, Object>> createSelfHierarchyParentsDto(@Nonnull EvitaResponse<EntityReference> response) {
		final HierarchyParents hierarchyParents = response.getExtraResult(HierarchyParents.class);
		final ParentsByReference selfParents = hierarchyParents.ofSelf();

		final var parentsDtos = new LinkedList<Map<String, Object>>();

		selfParents.getParents().forEach((productId, parentsForCategory) -> {
			parentsDtos.add(
				map()
					.e(TYPENAME_FIELD, ParentsOfEntityDescriptor.THIS.nameAsSuffix("Category_Category"))
					.e(ParentsOfEntityDescriptor.PRIMARY_KEY.name(), productId)
					.e(ParentsOfReferenceDescriptor.PARENT_ENTITIES.name(), Arrays.stream(parentsForCategory.get(parentsForCategory.keySet().iterator().next()))
						.map(parentEntity -> map()
							.e(TYPENAME_FIELD, "Category")
							.e(EntityDescriptor.PRIMARY_KEY.name(), parentEntity.getPrimaryKey())
							.e(EntityDescriptor.ATTRIBUTES.name(), map()
								.e(ATTRIBUTE_CODE, ((SealedEntity) parentEntity).getAttribute(ATTRIBUTE_CODE))
								.build())
							.build())
						.toList())
					.build()
			);
		});

		return parentsDtos;
	}
	 */

	@Nonnull
	private List<Map<String, Object>> createFacetSummaryWithCountsDto(@Nonnull EvitaResponse<EntityReference> response) {
		final FacetSummary facetSummary = response.getExtraResult(FacetSummary.class);

		return facetSummary.getFacetGroupStatistics()
			.stream()
			.filter(groupStatistics -> groupStatistics.getReferenceName().equals(Entities.BRAND))
			.map(groupStatistics ->
				map()
					.e(FacetGroupStatisticsDescriptor.GROUP_ENTITY.name(), null)
					.e(FacetGroupStatisticsDescriptor.FACET_STATISTICS.name(), groupStatistics.getFacetStatistics()
						.stream()
						.map(facetStatistics ->
							map()
								.e(FacetStatisticsDescriptor.REQUESTED.name(), facetStatistics.requested())
								.e(FacetStatisticsDescriptor.COUNT.name(), facetStatistics.count())
								.e(FacetStatisticsDescriptor.FACET_ENTITY.name(), map()
									.e(EntityDescriptor.PRIMARY_KEY.name(), facetStatistics.facetEntity().getPrimaryKey())
									.e(EntityDescriptor.TYPE.name(), facetStatistics.facetEntity().getType())
									.build())
								.build())
						.toList())
					.build()
			)
			.toList();
	}

	@Nonnull
	private List<Map<String, Object>> createFacetSummaryWithImpactsDto(@Nonnull EvitaResponse<EntityReference> response) {
		final FacetSummary facetSummary = response.getExtraResult(FacetSummary.class);

		return facetSummary.getFacetGroupStatistics()
			.stream()
			.map(groupStatistics ->
				map()
					.e(FacetGroupStatisticsDescriptor.GROUP_ENTITY.name(), null)
					.e(FacetGroupStatisticsDescriptor.FACET_STATISTICS.name(), groupStatistics.getFacetStatistics()
						.stream()
						.map(facetStatistics ->
							map()
								.e(FacetStatisticsDescriptor.REQUESTED.name(), facetStatistics.requested())
								.e(FacetStatisticsDescriptor.COUNT.name(), facetStatistics.count())
								.e(FacetStatisticsDescriptor.FACET_ENTITY.name(), map()
									.e(EntityDescriptor.PRIMARY_KEY.name(), facetStatistics.facetEntity().getPrimaryKey())
									.e(EntityDescriptor.TYPE.name(), facetStatistics.facetEntity().getType())
									.e(EntityDescriptor.LOCALES.name(), new ArrayList<>())
									.e(EntityDescriptor.ALL_LOCALES.name(), Arrays.asList(CZECH_LOCALE.toLanguageTag(), Locale.ENGLISH.toLanguageTag()))
									.e(EntityDescriptor.PRICE_INNER_RECORD_HANDLING.name(), PriceInnerRecordHandling.UNKNOWN.name())
									.e(EntityDescriptor.ATTRIBUTES.name(), map()
										.e(SectionedAttributesDescriptor.GLOBAL.name(), map()
											.e(ATTRIBUTE_CODE, ((SealedEntity) facetStatistics.facetEntity()).getAttribute(ATTRIBUTE_CODE))
											.build())
										.build())
									.build())
								.e(FacetStatisticsDescriptor.IMPACT.name(), map()
									.e(FacetRequestImpactDescriptor.DIFFERENCE.name(), facetStatistics.impact().difference())
									.e(FacetRequestImpactDescriptor.MATCH_COUNT.name(), facetStatistics.impact().matchCount())
									.e(FacetRequestImpactDescriptor.HAS_SENSE.name(), facetStatistics.impact().hasSense())
									.build())
								.build())
						.toList())
					.build()
			)
			.toList();
	}
}
