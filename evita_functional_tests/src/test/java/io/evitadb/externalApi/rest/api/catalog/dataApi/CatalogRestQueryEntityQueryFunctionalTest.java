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
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.HierarchicalPlacementContract;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.ReferenceContract;
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
import io.evitadb.externalApi.api.catalog.dataApi.model.ReferenceDescriptor;
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
import io.evitadb.externalApi.rest.api.testSuite.TestDataGenerator;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.builder.MapBuilder;
import io.evitadb.test.tester.RestTester;
import io.evitadb.test.tester.RestTester.Request;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.api.query.order.OrderDirection.DESC;
import static io.evitadb.externalApi.rest.api.testSuite.TestDataGenerator.REST_THOUSAND_PRODUCTS;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.builder.MapBuilder.map;
import static io.evitadb.test.generator.DataGenerator.*;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for REST catalog entity list query.
 *
 * @author Martin Veska, FG Forrest a.s. (c) 2022
 */
class CatalogRestQueryEntityQueryFunctionalTest extends CatalogRestDataEndpointFunctionalTest {

	private static final int SEED = 40;

	private static final String DATA_PATH = ResponseDescriptor.RECORD_PAGE.name() + ".data";

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return products by primary key")
	void shouldReturnProductsByPrimaryKey(RestTester tester, List<SealedEntity> originalProductEntities) {
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

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/product/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody("{" +
					"\"filterBy\": {" +
					"  \"entityPrimaryKeyInSet\": [%d, %d]" +
					"}," +
					"\"require\": {" +
					"  \"entityFetch\": {" +
					"     \"attributeContent\": [\"code\"]" +
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
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return products by non-localized attribute")
	void shouldReturnProductsByNonLocalizedAttribute(RestTester tester, List<SealedEntity> originalProductEntities) {
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

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/product/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody("{" +
					"\"filterBy\": {" +
					"  \"attributeCodeInSet\": [\"%s\", \"%s\"]," +
					"  \"entityLocaleEquals\": \"en\"" +
					"}," +
					"\"require\": {" +
					"  \"entityFetch\": {" +
					"     \"attributeContent\": [\"code\", \"name\"]" +
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
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return products by localized attribute")
	void shouldReturnProductsByLocalizedAttribute(RestTester tester, List<SealedEntity> originalProductEntities) {
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

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/product/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody("{" +
					"\"filterBy\": {" +
					"  \"attributeUrlInSet\": [\"%s\", \"%s\"]," +
					"  \"entityLocaleEquals\": \"en\"" +
					"}," +
					"\"require\": {" +
					"  \"entityFetch\": {" +
					"     \"attributeContent\": [\"url\", \"name\"]" +
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
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return products by localized attribute with locale in URL")
	void shouldReturnProductsByLocalizedAttributeWithLocaleInUrl(RestTester tester, List<SealedEntity> originalProductEntities) {
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

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/" + Locale.ENGLISH.toLanguageTag() + "/product/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody("{" +
					"\"filterBy\": {" +
					"  \"attributeUrlInSet\": [\"%s\", \"%s\"]" +
					"}," +
					"\"require\": {" +
					"  \"entityFetch\": {" +
					"     \"attributeContent\": [\"url\", \"name\"]" +
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
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should fail when locale is both in body and in URL")
	void shouldFailWhenLocaleIsBothInBodyAndInUrl(RestTester tester, List<SealedEntity> originalProductEntities) {
		tester.test(TEST_CATALOG)
			.urlPathSuffix("/" + Locale.ENGLISH.toLanguageTag() + "/product/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody("{" +
					"\"filterBy\": {" +
					"  \"attributeUrlInSet\": [\"some_url\"]," +
					"  \"entityLocaleEquals\": \"en\"" +
					"} }"
			)
			.executeAndThen()
			.statusCode(400)
			.body("message", notNullValue());
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return error for invalid argument in products query")
	void shouldReturnErrorForInvalidArgumentInProductsQuery(RestTester tester) {
		tester.test(TEST_CATALOG)
			.urlPathSuffix("/product/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody("{" +
				"\"filterBy\": {" +
				"  \"attributeUrlInSet\": [\"xxx\"]" +
				"}," +
				"\"require\": {" +
				"  \"entityFetch_xxx\": {" +
				"     \"attributeContent\": [\"url\", \"name\"]" +
				"    }" +
				"  }" +
				"}"
			)
			.executeAndThen()
			.statusCode(400)
			.body("message", notNullValue());
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return error for invalid query when single value is sent instead of array.")
	void shouldReturnErrorForInvalidQueryWhenSingleValueIsSentInsteadOfArray(RestTester tester, List<SealedEntity> originalProductEntities) {
		final var entities = findEntitiesWithPrice(originalProductEntities);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/product/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody("{" +
					"\"filterBy\": {" +
					"  \"attributeCodeInSet\": \"%s\"" +
					"}" +
					"}",
				entities.get(0).getAttribute(ATTRIBUTE_CODE, String.class)
			)
			.executeAndThen()
			.statusCode(400)
			.body("message", notNullValue());
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should filter by and return price for sale for multiple products")
	void shouldFilterByAndReturnPriceForSaleForMultipleProducts(RestTester tester, List<SealedEntity> originalProductEntities) {
		final var entities = findEntitiesWithPrice(originalProductEntities);

		final List<ArrayList<Map<String, Object>>> expectedBody = entities.stream()
			.map(sealedEntity -> createPricesDto(sealedEntity, CURRENCY_CZK, "basic"))
			.toList();

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/product/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody(
				"""
                    {
						"filterBy": {
						    "attributeCodeInSet": ["%s", "%s"],
						    "priceInCurrency": "CZK",
						    "priceInPriceLists": ["basic"]
						},
						"require": {
						    "entityFetch": {
						        "priceContent": {
						            "contentMode": "RESPECTING_FILTER"
					            }
						    }
						}
					}
					""",
				entities.get(0).getAttribute(ATTRIBUTE_CODE),
				entities.get(1).getAttribute(ATTRIBUTE_CODE)
			)
			.executeAndThen()
			.statusCode(200)
			.body(DATA_PATH + ".prices", equalTo(expectedBody));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should filter products by non-existent price")
	void shouldFilterProductsByNonExistentPrice(RestTester tester, List<SealedEntity> originalProductEntities) {
		final List<SealedEntity> entities = findEntitiesWithPrice(originalProductEntities);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/product/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody(
				"""
                    {
						"filterBy": {
						  "attributeCodeInSet": ["%s", "%s"],
						  "priceInCurrency": "CZK",
						  "priceInPriceLists": ["nonexistent"]
						},
						"require": {
							"entityFetch": {
								"priceContent": {
									"contentMode": "RESPECTING_FILTER"
								}
							}
						}
					}
					""",
				entities.get(0).getAttribute(ATTRIBUTE_CODE),
				entities.get(1).getAttribute(ATTRIBUTE_CODE)
			)
			.executeAndThen()
			.statusCode(200)
			.body(DATA_PATH, hasSize(0));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return error for filtering products by unknown currency")
	void shouldReturnErrorForFilteringProductsByUnknownCurrency(RestTester tester, List<SealedEntity> originalProductEntities) {
		final List<SealedEntity> entities = findEntitiesWithPrice(originalProductEntities);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/product/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody(
				"""
                    {
						"filterBy": {
						    "attributeCodeInSet": ["%s", "%s"],
						    "priceInCurrency": "AAA",
						    "priceInPriceLists": ["basic"]
						},
						"require": {
						    "entityFetch": {
						        "priceContent": {
						            "contentMode": "RESPECTING_FILTER"
					            }
						    }
						}
					}
					""",
				entities.get(0).getAttribute(ATTRIBUTE_CODE),
				entities.get(1).getAttribute(ATTRIBUTE_CODE)
			)
			.executeAndThen()
			.statusCode(400)
			.body("message", notNullValue());
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return custom price for sale for products")
	void shouldReturnCustomPriceForSaleForProducts(RestTester tester, List<SealedEntity> originalProductEntities) {
		final var entities = findEntitiesWithPrice(originalProductEntities);

		final List<Map<String, Object>> expectedBody = entities.stream()
			.map(sealedEntity -> createPriceForSaleDto(sealedEntity, CURRENCY_CZK, "basic"))
			.toList();

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/product/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody(
				"""
                    {
						"filterBy": {
						    "attributeCodeInSet": ["%s", "%s"],
						    "priceInCurrency": "CZK",
						    "priceInPriceLists": ["basic"]
						},
						"require": {
						    "entityFetch": {
						        "priceContent": {
						            "contentMode": "RESPECTING_FILTER"
					            }
						    }
						}
					}
					""",
				entities.get(0).getAttribute(ATTRIBUTE_CODE),
				entities.get(1).getAttribute(ATTRIBUTE_CODE)
			)
			.executeAndThen()
			.statusCode(200)
			.body(DATA_PATH + ".priceForSale", equalTo(expectedBody));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return associated data")
	void shouldReturnAssociatedData(RestTester tester, List<SealedEntity> originalProductEntities) {
		final var entities = findEntities(
			originalProductEntities,
			it -> it.getAssociatedData(ASSOCIATED_DATA_LABELS, Locale.ENGLISH) != null &&
				it.getAllLocales().contains(Locale.ENGLISH)
		);

		final var expectedBody = entities.stream()
			.map(entity -> createEntityDtoWithAssociatedData(entity, Locale.ENGLISH, true))
			.toList();

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/product/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody("{" +
					"\"filterBy\": {" +
					"  \"attributeCodeInSet\": [\"%s\", \"%s\"]," +
					"  \"entityLocaleEquals\": \"en\"" +
					"}," +
					"\"require\": {" +
					"  \"entityFetch\": {" +
					"     \"associatedDataContent\": [\"labels\"]" +
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
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return associated data with locale in URL")
	void shouldReturnAssociatedDataWithLocaleInUrl(RestTester tester, List<SealedEntity> originalProductEntities) {
		final var entities = findEntities(
			originalProductEntities,
			it -> it.getAssociatedData(ASSOCIATED_DATA_LABELS, Locale.ENGLISH) != null &&
				it.getAllLocales().contains(Locale.ENGLISH)
		);

		final var expectedBody = entities.stream()
			.map(entity -> createEntityDtoWithAssociatedData(entity, Locale.ENGLISH, false))
			.toList();

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/" + Locale.ENGLISH.toLanguageTag() + "/product/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody("{" +
					"\"filterBy\": {" +
					"  \"attributeCodeInSet\": [\"%s\", \"%s\"]" +
					"}," +
					"\"require\": {" +
					"  \"entityFetch\": {" +
					"     \"associatedDataContent\": [\"labels\"]" +
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
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return single reference for products")
	void shouldReturnSingleReferenceForProducts(RestTester tester, List<SealedEntity> originalProductEntities) {
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

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/product/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody("""
                    {
						"filterBy": {
						    "attributeCodeInSet": ["%s", "%s"]
						},
						"require": {
						    "entityFetch": {
						        "referenceBrandContent": {
						            "requirements": {
						                "entityFetch": {
							                "attributeContent": ["marketShare"]
							            }
						            }
						        }
						    }
					    }
					}
					""",
				entities.get(0).getAttribute(ATTRIBUTE_CODE),
				entities.get(1).getAttribute(ATTRIBUTE_CODE)
			)
			.executeAndThen()
			.statusCode(200)
			.body(DATA_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return reference list for products")
	void shouldReturnReferenceListForProducts(RestTester tester, List<SealedEntity> originalProductEntities) {
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

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/product/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody(
					"""
                    {
						"filterBy": {
						  "attributeCodeInSet": ["%s", "%s"]
						},
						"require": {
						    "entityFetch": {
						        "referenceStoreContent": {
						            "requirements": {
						                "entityFetch": {
							                "attributeContent": ["marketShare"]
							            }
						            }
						        }
						    }
					    }
					}
					""",
				entities.get(0).getAttribute(ATTRIBUTE_CODE),
				entities.get(1).getAttribute(ATTRIBUTE_CODE)
			)
			.executeAndThen()
			.statusCode(200)
			.body(DATA_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return filtered and ordered reference list for products")
	void shouldReturnFilteredAndOrderedReferenceListForProducts(RestTester tester, List<SealedEntity> originalProductsEntities, List<SealedEntity> originalStoreEntities) {
		final Map<Integer, SealedEntity> storesIndexedByPk = originalStoreEntities.stream()
			.collect(Collectors.toMap(
				EntityContract::getPrimaryKey,
				Function.identity()
			));

		final Map<Integer, Set<String>> productsWithLotsOfStores = originalProductsEntities.stream()
			.filter(it -> it.getReferences(Entities.STORE).size() > 4 && it.getLocales().contains(CZECH_LOCALE))
			.collect(
				Collectors.toMap(
					EntityContract::getPrimaryKey,
					it -> it.getReferences(Entities.STORE)
						.stream()
						.map(ref -> ref.getReferenceKey().primaryKey())
						.map(storesIndexedByPk::get)
						.map(store -> store.getAttribute(ATTRIBUTE_CODE, String.class))
						.collect(Collectors.toSet())
				)
			);

		final AtomicBoolean atLeastFirst = new AtomicBoolean();
		final Random rnd = new Random(5);
		final String[] randomStores = productsWithLotsOfStores
			.values()
			.stream()
			.flatMap(Collection::stream)
			.filter(it -> atLeastFirst.compareAndSet(false, true) || rnd.nextInt(10) == 0)
			.distinct()
			.toArray(String[]::new);

		final var expectedBody = productsWithLotsOfStores.keySet()
			.stream()
			.sorted()
			.map(id -> originalProductsEntities.stream()
				.filter(it -> it.getPrimaryKey().equals(id))
				.findFirst()
				.orElseThrow())
			.map(entity -> {
				final var references = entity.getReferences(Entities.STORE)
					.stream()
					.map(it -> storesIndexedByPk.get(it.getReferencedPrimaryKey()))
					.filter(it -> it.getAttribute(ATTRIBUTE_NAME, CZECH_LOCALE) != null)
					.sorted(Comparator.comparing(it -> (String) it.getAttribute(ATTRIBUTE_NAME, CZECH_LOCALE), Comparator.reverseOrder()))
					.map(referencedEntity -> entity.getReference(referencedEntity.getType(), referencedEntity.getPrimaryKey()).orElseThrow())
					.toList();
				assertFalse(references.isEmpty());

				return map()
					.e(EntityDescriptor.PRIMARY_KEY.name(), entity.getPrimaryKey())
					.e(EntityDescriptor.TYPE.name(), Entities.PRODUCT)
					.e(EntityDescriptor.LOCALES.name(), List.of(CZECH_LOCALE.toLanguageTag()))
					.e(EntityDescriptor.ALL_LOCALES.name(), List.of(CZECH_LOCALE.toLanguageTag(), Locale.ENGLISH.toLanguageTag()))
					.e(EntityDescriptor.PRICE_INNER_RECORD_HANDLING.name(), PriceInnerRecordHandling.UNKNOWN.name())
					.e(Entities.STORE.toLowerCase(), createReferencesDto(references, false))
					.build();
			})
			.toList();

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/product/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody(
				"""
	                 {
	                    "filterBy": {
	                        "entityPrimaryKeyInSet": [%s],
	                        "entityLocaleEquals": "cs-CZ"
	                    },
	                    "require": {
	                        "page": {
	                            "number": 1,
	                            "size": %d
	                        },
	                        "entityFetch": {
	                            "referenceStoreContent": {
		                            "filterBy": {
		                                "entityHaving": {
		                                    "attributeCodeInSet": [%s]
		                                }
		                            },
		                            "orderBy": {
		                                "entityProperty": {
		                                    "attributeNameNatural": "DESC"
		                                }
		                            }
		                        }
	                        }
	                    }
	                 }
					""",
				productsWithLotsOfStores.keySet().stream().map(Object::toString).collect(Collectors.joining(",")),
				Integer.MAX_VALUE,
				Arrays.stream(randomStores).map(it -> "\"" + it + "\"").collect(Collectors.joining(","))
			)
			.executeAndThen()
			.statusCode(200)
			.body(DATA_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should find product by complex query")
	void shouldFindProductByComplexQuery(Evita evita, RestTester tester, List<SealedEntity> originalProductEntities) {
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

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/product/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody("{" +
					"\"filterBy\": {" +
					"\"or\": [" +
					"    {" +
					"        \"attributeAliasEquals\": %b," +
					"        \"attributePriorityEquals\": \"%s\"" +
					"    }," +
					"    {" +
					"        \"attributeAliasEquals\": %b," +
					"        \"attributePriorityEquals\": \"%s\"" +
					"    }," +
					"    {" +
					"        \"attributeAliasEquals\": false," +
					"        \"attributePriorityInSet\": [\"%s\", \"%s\", \"%s\", \"%s\"]" +
					"    }" +
					"]," +
					"\"not\": {" +
					"    \"attributeCodeEquals\": \"%s\"" +
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
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should order entities by complex query")
	void shouldOrderEntitiesByComplexQuery(Evita evita, RestTester tester) {
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

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/product/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody("{" +
				"\"filterBy\": {" +
				"  \"attributePriorityLessThan\": 35000" +
				"}," +
				"\"orderBy\": {" +
				"  \"attributeCreatedNatural\": \"DESC\"," +
				"  \"attributeManufacturedNatural\": \"ASC\"" +
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
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return page of entities")
	void shouldReturnPageOfEntities(Evita evita, RestTester tester) {
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

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/product/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody("{" +
				"\"filterBy\": {" +
				"  \"attributePriorityLessThan\": 35000" +
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
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return strip of entities")
	void shouldReturnStripOfEntities(Evita evita, RestTester tester) {
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

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/product/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody("{" +
				"\"filterBy\": {" +
				"  \"attributePriorityLessThan\": 35000" +
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
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return attribute histogram")
	void shouldReturnAttributeHistogram(Evita evita, RestTester tester) {
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

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/product/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody("{" +
					"\"filterBy\": {" +
					"  \"attributeAliasIs\": \"NOT_NULL\"" +
					"}," +
					"\"require\": {" +
					"  \"page\": {" +
					"     \"number\": 1," +
					"     \"size\": %d" +
					"    }," +
					"  \"attributeHistogram\": {" +
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
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return error for missing attribute histogram buckets count")
	void shouldReturnErrorForMissingAttributeHistogramBucketsCount(RestTester tester) {
		tester.test(TEST_CATALOG)
			.urlPathSuffix("/product/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody("{" +
					"\"filterBy\": {" +
					"  \"attributeAliasIs\": \"NOT_NULL\"" +
					"}," +
					"\"require\": {" +
					"  \"page\": {" +
					"     \"number\": 1," +
					"     \"size\": %d" +
					"    }," +
					"  \"attributeHistogram\": {" +
					"     \"attributeName\": [\"" + ATTRIBUTE_QUANTITY + "\"]" +
					"    }" +
					"  }" +
					"}",
				Integer.MAX_VALUE)
			.executeAndThen()
			.statusCode(400)
			.body("message", equalTo("Constraint `attributeHistogram` requires parameter `requestedBucketCount` to be non-null."));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return price histogram")
	void shouldReturnPriceHistogram(Evita evita, RestTester tester) {
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

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/product/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody("{" +
					"\"filterBy\": {" +
					"  \"priceInCurrency\": \"EUR\"," +
					"  \"priceInPriceLists\": [\"vip\",\"basic\"]" +
					"}," +
					"\"require\": {" +
					"  \"page\": {" +
					"     \"number\": 1," +
					"     \"size\": %d" +
					"    }," +
					"  \"priceHistogram\": 20" +
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
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return error for missing price histogram buckets count")
	void shouldReturnErrorForMissingPriceHistogramBucketsCount(RestTester tester) {
		tester.test(TEST_CATALOG)
			.urlPathSuffix("/product/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody("""
					{
						"filterBy": {
							"priceInCurrency": "EUR",
							"priceInPriceLists": ["vip","basic"]
						},
						"require": {
							"page": {
								"number": 1,
								"size": %d
							},
							"priceHistogram": null
						}
					}
					""",
				Integer.MAX_VALUE)
			.executeAndThen()
			.statusCode(400)
			.body("message", equalTo("Constraint `priceHistogram` requires non-null value."));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return category parents for products")
	void shouldReturnCategoryParentsForProducts(Evita evita, RestTester tester) {
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

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/product/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody("""
					{
						"filterBy": {
							"hierarchyCategoryWithin": {
								"ofParent": 95
							}
						},
						"require": {
							"page": {
								"number": 1,
								"size": %d
							},
							"hierarchyCategoryParentsOfReference": {
						        "entityFetch": {
									"attributeContent": [
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
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return self parents for category")
	void shouldReturnSelfParentsForCategory(Evita evita, RestTester tester) {
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

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/category/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody("""
					{
						"require": {
							"page": {
								"number": 1,
								"size": %d
							},
							"hierarchyParentsOfSelf": {
						        "entityFetch": {
									"attributeContent": [
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
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should pass locale to parents")
	void shouldPassLocaleToParents(Evita evita, RestTester tester) {
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

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/product/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody("""
					{
						"filterBy": {
							"hierarchyCategoryWithin": {
								"ofParent": 95
							},
							"entityLocaleEquals": "cs-CZ"
						},
						"require": {
							"page": {
								"number": 1,
								"size": %d
							},
							"hierarchyCategoryParentsOfReference": {
						        "entityFetch": {
									"attributeContent": [
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
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return error for self statistics of product")
	void shouldReturnErrorForSelfStatisticsOfProduct(RestTester tester) {
		tester.test(TEST_CATALOG)
			.urlPathSuffix("/product/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody("""
					{
						"filterBy": {
							"entityLocaleEquals": "cs-CZ"
						},
						"require": {
							"page": {
								"number": 1,
								"size": %d
							},
							"hierarchyParentsOfSelf": {
						        "entityFetch": {
									"attributeContent": [
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
			.body("message", notNullValue());
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should pass locale to hierarchy statistics entities")
	void shouldPassLocaleToHierarchyStatisticsEntities(Evita evita, RestTester tester) {
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

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/product/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody("""
					{
						"filterBy": {
							"attributeAliasEquals": true,
							"entityLocaleEquals": "cs-CZ"
						},
						"require": {
							"page": {
								"number": 1,
								"size": %d
							},
							"hierarchyCategoryStatisticsOfReference": {
						        "entityFetch": {
									"attributeContent": [
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
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should get hierarchy statistics with entities reference only")
	void shouldGetHierarchyStatisticsWithEntitiesReferenceOnly(Evita evita, RestTester tester) {
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

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/product/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody("""
					{
						"filterBy": {
							"attributeAliasEquals": true,
							"entityLocaleEquals": "cs-CZ"
						},
						"require": {
							"page": {
								"number": 1,
								"size": %d
							},
							"hierarchyCategoryStatisticsOfReference": {}
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
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return facet summary with counts for products")
	void shouldReturnFacetSummaryWithCountsForProducts(Evita evita, RestTester tester) {
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

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/product/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody("""
					{
						"require": {
							"facetSummary": {
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
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return facet summary with impacts and entities for products")
	void shouldReturnFacetSummaryWithImpactsAndEntitiesForProducts(Evita evita, RestTester tester) {
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

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/product/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody("""
					{
						"require": {
							"facetSummary": {
								"statisticsDepth":"IMPACT",
								"requirements": {
					   				"entityFetch": {
					   					"attributeContent": ["code"]
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
