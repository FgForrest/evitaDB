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

package io.evitadb.externalApi.graphql.api.catalog.dataApi;

import io.evitadb.api.query.require.FacetStatisticsDepth;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.extraResult.AttributeHistogram;
import io.evitadb.api.requestResponse.extraResult.FacetSummary;
import io.evitadb.api.requestResponse.extraResult.HierarchyParents;
import io.evitadb.api.requestResponse.extraResult.HierarchyParents.ParentsByReference;
import io.evitadb.api.requestResponse.extraResult.HierarchyStatistics;
import io.evitadb.api.requestResponse.extraResult.HierarchyStatistics.LevelInfo;
import io.evitadb.api.requestResponse.extraResult.HistogramContract;
import io.evitadb.api.requestResponse.extraResult.PriceHistogram;
import io.evitadb.core.Evita;
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.externalApi.api.catalog.dataApi.model.AttributesDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.EntityDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.PriceDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.RecordPageDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.ReferenceDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.ResponseDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.AttributeHistogramDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.ExtraResultsDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.FacetSummaryDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.FacetSummaryDescriptor.FacetGroupStatisticsDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.FacetSummaryDescriptor.FacetRequestImpactDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.FacetSummaryDescriptor.FacetStatisticsDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HierarchyParentsDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HierarchyParentsDescriptor.ParentsOfEntityDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HierarchyParentsDescriptor.ParentsOfEntityDescriptor.ParentsOfReferenceDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HierarchyStatisticsDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HierarchyStatisticsDescriptor.HierarchyStatisticsLevelInfoDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HistogramDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HistogramDescriptor.BucketDescriptor;
import io.evitadb.test.tester.GraphQLTester;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.UseDataSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.text.NumberFormat;
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
import static io.evitadb.api.query.QueryConstraints.not;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.api.query.order.OrderDirection.DESC;
import static io.evitadb.externalApi.graphql.api.testSuite.TestDataGenerator.*;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.builder.MapBuilder.map;
import static io.evitadb.test.generator.DataGenerator.*;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for GraphQL catalog entity list query.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class CatalogGraphQLQueryEntityQueryFunctionalTest extends CatalogGraphQLDataEndpointFunctionalTest {

	private static final int SEED = 40;

	private static final String PRODUCT_QUERY_PATH = "data.queryProduct";
	private static final String CATEGORY_QUERY_PATH = "data.queryCategory";

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return products by primary key")
	void shouldReturnProductsByPrimaryKey(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final var entities = findEntities(
			originalProductEntities,
			it -> it.getAttribute(ATTRIBUTE_CODE) != null,
			2
		);

		final var expectedBody = createBasicPageResponse(
			entities,
			entity ->
				map()
					.e(TYPENAME_FIELD, "Product")
					.e(EntityDescriptor.PRIMARY_KEY.name(), entity.getPrimaryKey())
					.e(EntityDescriptor.TYPE.name(), Entities.PRODUCT)
					.e(EntityDescriptor.LOCALES.name(), List.of())
					.e(EntityDescriptor.ALL_LOCALES.name(), List.of(CZECH_LOCALE.toLanguageTag(), Locale.ENGLISH.toLanguageTag()))
					.e(EntityDescriptor.ATTRIBUTES.name(), map()
						.e(TYPENAME_FIELD, AttributesDescriptor.THIS.name(createEmptyEntitySchema("Product")))
						.e(ATTRIBUTE_CODE, entity.getAttribute(ATTRIBUTE_CODE, String.class))
						.build())
					.build()
		);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    queryProduct(
	                        filterBy: {
	                            entityPrimaryKeyInSet: [%d, %d]
	                        }
	                    ) {
	                        __typename
	                        recordPage {
	                            __typename
	                            data {
	                                __typename
	                                primaryKey
			                        type
			                        locales
			                        allLocales
		                            attributes {
		                                __typename
		                                code
		                            }
	                            }
	                        }
	                    }
	                }
					""",
				entities.get(0).getPrimaryKey(),
				entities.get(1).getPrimaryKey()
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(PRODUCT_QUERY_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return products by non-localized attribute")
	void shouldReturnProductsByNonLocalizedAttribute(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final var entities = findEntities(
			originalProductEntities,
			it -> it.getAttribute(ATTRIBUTE_NAME, Locale.ENGLISH) != null &&
				it.getAllLocales().contains(CZECH_LOCALE) &&
				it.getAllLocales().contains(Locale.ENGLISH),
			2
		);

		final var expectedBody = createBasicPageResponse(
			entities,
			entity ->
				map()
					.e(EntityDescriptor.PRIMARY_KEY.name(), entity.getPrimaryKey())
					.e(EntityDescriptor.TYPE.name(), Entities.PRODUCT)
					.e(EntityDescriptor.LOCALES.name(), List.of(Locale.ENGLISH.toLanguageTag()))
					.e(EntityDescriptor.ALL_LOCALES.name(), List.of(CZECH_LOCALE.toLanguageTag(), Locale.ENGLISH.toLanguageTag()))
					.e(EntityDescriptor.ATTRIBUTES.name(), map()
						.e(ATTRIBUTE_CODE, entity.getAttribute(ATTRIBUTE_CODE, String.class))
						.e(ATTRIBUTE_NAME, entity.getAttribute(ATTRIBUTE_NAME, Locale.ENGLISH))
						.build())
					.build()
		);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    queryProduct(
	                        filterBy: {
	                            attributeCodeInSet: ["%s", "%s"]
	                        }
	                    ) {
	                        __typename
	                        recordPage {
	                            __typename
	                            data {
	                                primaryKey
			                        type
			                        locales
			                        allLocales
		                            attributes(locale: en) {
		                                code
		                                name
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
			.body(ERRORS_PATH, nullValue())
			.body(PRODUCT_QUERY_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return products by range attribute from variables")
	void shouldReturnProductsByRangeAttributeFromVariables(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final var entities = findEntities(
			originalProductEntities,
			it -> it.getAttribute(ATTRIBUTE_SIZE) != null,
			1
		);
		final var sizeAttributeToCompare = entities.get(0).getAttribute(ATTRIBUTE_SIZE, IntegerNumberRange[].class)[0];

		final var expectedBody = createBasicPageResponse(
			entities,
			entity ->
				map()
					.e(EntityDescriptor.PRIMARY_KEY.name(), entity.getPrimaryKey())
					.build()
		);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query ($size: IntegerNumberRange!) {
	                    queryProduct(
	                        filterBy: {
	                            attributeSizeInSet: [$size]
	                        }
	                    ) {
	                        __typename
	                        recordPage {
	                            __typename
	                            data {
	                                primaryKey
	                            }
	                        }
	                    }
	                }
					"""
			)
			.variable(
				"size",
				new int[] { sizeAttributeToCompare.getPreciseFrom(), sizeAttributeToCompare.getPreciseTo() }
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(PRODUCT_QUERY_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return error when formatted big decimal is missing locale")
	void shouldReturnErrorWhenFormattedBigDecimalIsMissingLocale(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final var entities = findEntities(
			originalProductEntities,
			it -> it.getAttribute(ATTRIBUTE_QUANTITY) != null,
			2
		);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    queryProduct(
	                        filterBy: {
	                            attributeCodeInSet: ["%s", "%s"]
	                        }
                        ) {
                            __typename
	                        recordPage {
	                            __typename
	                            data {
	                                primaryKey
			                        type
		                            attributes {
		                                quantity(formatted: true)
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
			.body(ERRORS_PATH, notNullValue());
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return big decimal attribute variants for products")
	void shouldReturnBigDecimalAttributeVariantsForProducts(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final var entities = findEntities(
			originalProductEntities,
			it -> it.getAttribute(ATTRIBUTE_QUANTITY) != null,
			2
		);

		final var expectedBody = createBasicPageResponse(
			entities,
			entity ->
				map()
					.e(EntityDescriptor.PRIMARY_KEY.name(), entity.getPrimaryKey())
					.e(EntityDescriptor.TYPE.name(), Entities.PRODUCT)
					.e(EntityDescriptor.ATTRIBUTES.name(), map()
						.e(ATTRIBUTE_QUANTITY, entity.getAttribute(ATTRIBUTE_QUANTITY).toString())
						.e("formattedQuantity", NumberFormat.getNumberInstance(CZECH_LOCALE).format(entity.getAttribute(ATTRIBUTE_QUANTITY)))
						.build())
					.e("enAttributes", map()
						.e("enFormattedQuantity", NumberFormat.getNumberInstance(Locale.ENGLISH).format(entity.getAttribute(ATTRIBUTE_QUANTITY)))
						.build())
					.build()
		);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    queryProduct(
	                        filterBy: {
	                            attributeCodeInSet: ["%s", "%s"],
	                            entityLocaleEquals: cs_CZ
	                        }
	                    ) {
	                        __typename
	                        recordPage {
	                            __typename
	                            data {
	                                primaryKey
			                        type
		                            attributes {
		                                quantity
		                                formattedQuantity: quantity(formatted: true)
		                            }
		                            enAttributes: attributes(locale: en) {
		                                enFormattedQuantity: quantity(formatted: true)
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
			.body(ERRORS_PATH, nullValue())
			.body(PRODUCT_QUERY_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return products by localized attribute")
	void shouldReturnProductsByLocalizedAttribute(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final var entities = findEntities(
			originalProductEntities,
			it -> it.getAttribute(ATTRIBUTE_URL, Locale.ENGLISH) != null &&
				it.getAttribute(ATTRIBUTE_NAME, Locale.ENGLISH) != null,
			2
		);

		final var expectedBody = createBasicPageResponse(
			entities,
			entity ->
				map()
					.e(EntityDescriptor.PRIMARY_KEY.name(), entity.getPrimaryKey())
					.e(EntityDescriptor.TYPE.name(), Entities.PRODUCT)
					.e(EntityDescriptor.LOCALES.name(), List.of(Locale.ENGLISH.toLanguageTag()))
					.e(EntityDescriptor.ALL_LOCALES.name(), List.of(CZECH_LOCALE.toLanguageTag(), Locale.ENGLISH.toLanguageTag()))
					.e(EntityDescriptor.ATTRIBUTES.name(), map()
						.e(ATTRIBUTE_URL, entity.getAttribute(ATTRIBUTE_URL, Locale.ENGLISH))
						.e(ATTRIBUTE_NAME, entity.getAttribute(ATTRIBUTE_NAME, Locale.ENGLISH))
						.build())
					.build()
		);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    queryProduct(
	                        filterBy: {
	                            attributeUrlInSet: ["%s", "%s"]
	                            entityLocaleEquals: en
	                        }
	                    ) {
	                        __typename
	                        recordPage {
	                            __typename
	                            data {
	                                primaryKey
			                        type
			                        locales
			                        allLocales
		                            attributes {
		                                url
		                                name
		                            }
	                            }
	                        }
	                    }
	                }
					""",
				entities.get(0).getAttribute(ATTRIBUTE_URL, Locale.ENGLISH),
				entities.get(1).getAttribute(ATTRIBUTE_URL, Locale.ENGLISH)
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(PRODUCT_QUERY_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return error for invalid products fields")
	void shouldReturnErrorForInvalidProductsFields(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final String codeAttribute1 = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_CODE, 2);
		final String codeAttribute2 = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_CODE, 5);
		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    queryProduct(
	                        filterBy: {
	                            attributeCodeInSet: ["%s", "%s"]
	                        }
	                    ) {
	                        primaryKey
	                    }
	                }
					""",
				codeAttribute1,
				codeAttribute2
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, hasSize(greaterThan(0)));
	}
	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return error for invalid argument in products query")
	void shouldReturnErrorForInvalidArgumentInProductsQuery(GraphQLTester tester) {
		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    queryProduct(code: "product") {
	                        __typename
	                        recordPage {
	                            __typename
	                            data {
	                                primaryKey
	                            }
	                        }
	                    }
	                }
					"""
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, hasSize(greaterThan(0)));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should filter by and return price for sale for multiple products")
	void shouldFilterByAndReturnPriceForSaleForMultipleProducts(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final var entities = findEntitiesWithPrice(originalProductEntities, 2);

		final var expectedBody = createBasicPageResponse(entities, this::createEntityDtoWithPriceForSale);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    queryProduct(
	                        filterBy: {
		                        attributeCodeInSet: ["%s", "%s"]
		                        priceInCurrency: CZK,
		                        priceInPriceLists: "basic"
	                        }
                        ) {
                            __typename
	                        recordPage {
	                            __typename
	                            data {
	                                primaryKey
			                        type
		                            priceForSale {
		                                __typename
		                                currency
		                                priceList
		                                priceWithTax
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
			.body(ERRORS_PATH, nullValue())
			.body(PRODUCT_QUERY_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should filter products by non-existent price")
	void shouldFilterProductsByNonExistentPrice(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final List<SealedEntity> entities = findEntitiesWithPrice(originalProductEntities, 2);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    queryProduct(
	                        filterBy: {
	                            attributeCodeInSet: ["%s", "%s"]
		                        priceInCurrency: CZK,
		                        priceInPriceLists: "nonexistent"
	                        }
                        ) {
                            __typename
	                        recordPage {
	                            __typename
	                            data {
	                                primaryKey
	                                type
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
			.body(ERRORS_PATH, nullValue())
			.body(PRODUCT_QUERY_PATH + "." + ResponseDescriptor.RECORD_PAGE.name() + "." + RecordPageDescriptor.DATA.name(), hasSize(0));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return error for filtering products by unknown currency")
	void shouldReturnErrorForFilteringProductsByUnknownCurrency(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final List<SealedEntity> entities = findEntitiesWithPrice(originalProductEntities, 2);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    queryProduct(
	                        filterBy: {
	                            attributeCodeInSet: ["%s", "%s"]
		                        priceInCurrency: AAA,
		                        priceInPriceLists: "basic"
	                        }
                        ) {
                            __typename
	                        recordPage {
	                            __typename
	                            data {
	                                primaryKey
	                                type
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
			.body(ERRORS_PATH, hasSize(greaterThan(0)))
			.body(PRODUCT_QUERY_PATH, nullValue());
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return custom price for sale for products")
	void shouldReturnCustomPriceForSaleForProducts(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final var entities = findEntitiesWithPrice(originalProductEntities, 2);

		final var expectedBody = createBasicPageResponse(
			entities,
			this::createEntityDtoWithPriceForSale
		);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    queryProduct(
	                        filterBy: {
	                            entityPrimaryKeyInSet: [%d, %d]
	                        }
	                    ) {
	                        __typename
	                        recordPage {
	                            __typename
	                            data {
	                                primaryKey
			                        type
		                            priceForSale(currency: CZK, priceList: "basic") {
		                                __typename
		                                currency
		                                priceList
		                                priceWithTax
		                            }
	                            }
	                        }
	                    }
	                }
					""",
				entities.get(0).getPrimaryKey(),
				entities.get(1).getPrimaryKey()
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(PRODUCT_QUERY_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return formatted price for sale with entity locale")
	void shouldReturnFormattedPriceForSaleWithEntityLocale(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final var entities = findEntitiesWithPrice(originalProductEntities, 2);

		final var expectedBody = createBasicPageResponse(entities, this::createEntityDtoWithFormattedPriceForSale);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    queryProduct(
	                        filterBy: {
		                        attributeCodeInSet: ["%s", "%s"]
		                        priceInCurrency: CZK,
		                        priceInPriceLists: "basic",
		                        entityLocaleEquals: cs_CZ
	                        }
                        ) {
                            __typename
	                        recordPage {
	                            __typename
	                            data {
		                            priceForSale {
		                                priceWithTax(formatted: true, withCurrency: true)
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
			.body(ERRORS_PATH, nullValue())
			.body(PRODUCT_QUERY_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return formatted price for sale with custom locale")
	void shouldReturnFormattedPriceForSaleWithCustomLocale(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final var entities = findEntitiesWithPrice(originalProductEntities, 2);

		final var expectedBody = createBasicPageResponse(entities, this::createEntityDtoWithFormattedPriceForSale);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    queryProduct(
	                        filterBy: {
		                        attributeCodeInSet: ["%s", "%s"]
		                        priceInCurrency: CZK,
		                        priceInPriceLists: "basic"
	                        }
                        ) {
                            __typename
	                        recordPage {
	                            __typename
	                            data {
		                            priceForSale(locale: cs_CZ) {
		                                priceWithTax(formatted: true, withCurrency: true)
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
			.body(ERRORS_PATH, nullValue())
			.body(PRODUCT_QUERY_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return formatted price for sale with custom locale")
	void shouldReturnErrorWhenFormattingPriceForSaleWithoutLocale(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final var entities = findEntitiesWithPrice(originalProductEntities, 2);

		final var expectedBody = createBasicPageResponse(entities, this::createEntityDtoWithFormattedPriceForSale);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    queryProduct(
	                        filterBy: {
		                        attributeCodeInSet: ["%s", "%s"]
		                        priceInCurrency: CZK,
		                        priceInPriceLists: "basic"
	                        }
                        ) {
                            __typename
	                        recordPage {
	                            __typename
	                            data {
		                            priceForSale {
		                                priceWithTax(formatted: true, withCurrency: true)
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
			.body(ERRORS_PATH, hasSize(greaterThan(0)));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return price for products with filter inheritance")
	void shouldReturnPriceForProductsWithFilterInheritance(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final var entities = findEntitiesWithPrice(originalProductEntities, 2);

		final var expectedBody = createBasicPageResponse(
			entities,
			this::createEntityDtoWithPrice
		);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    queryProduct(
	                        filterBy: {
	                            attributeCodeInSet: ["%s", "%s"]
	                            priceInCurrency: CZK
	                            priceInPriceLists: "basic"
	                        }
                        ) {
                            __typename
	                        recordPage {
	                            __typename
	                            data {
	                                primaryKey
			                        type
		                            price(priceList: "basic") {
		                                __typename
		                                currency
		                                priceList
		                                priceWithTax
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
			.body(ERRORS_PATH, nullValue())
			.body(PRODUCT_QUERY_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return price for products")
	void shouldReturnPriceForProducts(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final var entities = findEntitiesWithPrice(originalProductEntities, 2);

		final var expectedBody = createBasicPageResponse(
			entities,
			this::createEntityDtoWithPrice
		);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    queryProduct(
	                        filterBy: {
	                            attributeCodeInSet: ["%s", "%s"]
	                        }
	                    ) {
	                        __typename
	                        recordPage {
	                            __typename
	                            data {
	                                primaryKey
			                        type
		                            price(priceList: "basic", currency: CZK) {
		                                __typename
		                                currency
		                                priceList
		                                priceWithTax
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
			.body(ERRORS_PATH, nullValue())
			.body(PRODUCT_QUERY_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return formatted price with entity locale")
	void shouldReturnFormattedPriceWithEntityLocale(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final var entities = findEntitiesWithPrice(originalProductEntities, 2);

		final var expectedBody = createBasicPageResponse(
			entities,
			this::createEntityDtoWithFormattedPrice
		);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    queryProduct(
	                        filterBy: {
	                            attributeCodeInSet: ["%s", "%s"],
	                            entityLocaleEquals: cs_CZ
	                        }
	                    ) {
	                        __typename
	                        recordPage {
	                            __typename
	                            data {
		                            price(priceList: "basic", currency: CZK) {
		                                priceWithTax(formatted: true, withCurrency: true)
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
			.body(ERRORS_PATH, nullValue())
			.body(PRODUCT_QUERY_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return formatted price with custom locale")
	void shouldReturnFormattedPriceWithCustomLocale(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final var entities = findEntitiesWithPrice(originalProductEntities, 2);

		final var expectedBody = createBasicPageResponse(
			entities,
			this::createEntityDtoWithFormattedPrice
		);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    queryProduct(
	                        filterBy: {
	                            attributeCodeInSet: ["%s", "%s"]
	                        }
	                    ) {
	                        __typename
	                        recordPage {
	                            __typename
	                            data {
		                            price(priceList: "basic", currency: CZK, locale: cs_CZ) {
		                                priceWithTax(formatted: true, withCurrency: true)
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
			.body(ERRORS_PATH, nullValue())
			.body(PRODUCT_QUERY_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return when formatting price without locale")
	void shouldReturnErrorWhenFormattingPriceWithoutLocale(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final var entities = findEntitiesWithPrice(originalProductEntities, 2);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    queryProduct(
	                        filterBy: {
	                            attributeCodeInSet: ["%s", "%s"]
	                        }
	                    ) {
	                        __typename
	                        recordPage {
	                            __typename
	                            data {
		                            price(priceList: "basic", currency: CZK) {
		                                priceWithTax(formatted: true, withCurrency: true)
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
			.body(ERRORS_PATH, hasSize(greaterThan(0)));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return all prices for products")
	void shouldReturnAllPricesForProducts(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final List<SealedEntity> entities = findEntities(
			originalProductEntities,
			it -> !it.getPrices().isEmpty(),
			2
		);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    queryProduct(
	                        filterBy: {
	                            attributeCodeInSet: ["%s", "%s"]
	                        }
	                    ) {
	                        __typename
	                        recordPage {
	                            __typename
	                            data {
	                                primaryKey
			                        type
		                            prices {
		                                priceWithTax
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
			.body(ERRORS_PATH, nullValue())
			.body(
				PRODUCT_QUERY_PATH + "." + ResponseDescriptor.RECORD_PAGE.name() + "." + RecordPageDescriptor.DATA.name(),
				hasSize(2)
			)
			.body(
				PRODUCT_QUERY_PATH + "." + ResponseDescriptor.RECORD_PAGE.name() + "." + RecordPageDescriptor.DATA.name() + "[0]." + EntityDescriptor.PRICES.name(),
				hasSize(greaterThan(0))
			)
			.body(
				PRODUCT_QUERY_PATH + "." + ResponseDescriptor.RECORD_PAGE.name() + "." + RecordPageDescriptor.DATA.name() + "[1]." + EntityDescriptor.PRICES.name(),
				hasSize(greaterThan(0))
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return filtered prices for products")
	void shouldReturnFilteredPricesForProducts(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final List<SealedEntity> entities = findEntitiesWithPrice(originalProductEntities, 2);

		final var expectedBody = createBasicPageResponse(
			entities,
			entity ->
				map()
					.e(EntityDescriptor.PRIMARY_KEY.name(), entity.getPrimaryKey())
					.e(EntityDescriptor.TYPE.name(), Entities.PRODUCT)
					.e("prices", List.of(
						map()
							.e(TYPENAME_FIELD, PriceDescriptor.THIS.name())
							.e(PriceDescriptor.CURRENCY.name(), CURRENCY_CZK.toString())
							.e(PriceDescriptor.PRICE_LIST.name(), PRICE_LIST_BASIC)
							.e(PriceDescriptor.PRICE_WITH_TAX.name(), entity.getPrices(CURRENCY_CZK, PRICE_LIST_BASIC).iterator().next().getPriceWithTax().toString())
							.build()
					))
					.build()
		);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    queryProduct(
		                    filterBy: {
		                        entityPrimaryKeyInSet: [%d, %d]
		                    }
	                    ) {
	                        __typename
	                        recordPage {
	                            __typename
	                            data {
	                                primaryKey
			                        type
		                            prices(priceLists: "basic", currency: CZK) {
		                                __typename
		                                currency
		                                priceList
		                                priceWithTax
		                            }
	                            }
	                        }
	                    }
	                }
					""",
				entities.get(0).getPrimaryKey(),
				entities.get(1).getPrimaryKey()
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(PRODUCT_QUERY_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return filtered prices for multiple price lists for products")
	void shouldReturnFilteredPricesForMutliplePriceListsForProducts(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final List<SealedEntity> entities = findEntitiesWithPrice(originalProductEntities, 2, PRICE_LIST_BASIC, PRICE_LIST_VIP);

		final var expectedBody = createBasicPageResponse(
			entities,
			entity ->
				map()
					.e("prices", List.of(
						map()
							.e(PriceDescriptor.PRICE_WITH_TAX.name(), entity.getPrices(CURRENCY_CZK, PRICE_LIST_BASIC).iterator().next().getPriceWithTax().toString())
							.build(),
						map()
							.e(PriceDescriptor.PRICE_WITH_TAX.name(), entity.getPrices(CURRENCY_CZK, PRICE_LIST_VIP).iterator().next().getPriceWithTax().toString())
							.build()
					))
					.build()
		);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    queryProduct(
		                    filterBy: {
		                        entityPrimaryKeyInSet: [%d, %d]
		                    }
	                    ) {
	                        __typename
	                        recordPage {
	                            __typename
	                            data {
		                            prices(priceLists: ["basic", "vip"], currency: CZK) {
		                                priceWithTax
		                            }
	                            }
	                        }
	                    }
	                }
					""",
				entities.get(0).getPrimaryKey(),
				entities.get(1).getPrimaryKey()
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(PRODUCT_QUERY_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return formatted prices with entity locale")
	void shouldReturnFormattedPricesWithEntityLocale(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final List<SealedEntity> entities = findEntitiesWithPrice(originalProductEntities, 2);

		final var expectedBody = createBasicPageResponse(
			entities,
			this::createEntityDtoWithFormattedPrices
		);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    queryProduct(
		                    filterBy: {
		                        entityPrimaryKeyInSet: [%d, %d],
		                        entityLocaleEquals: cs_CZ
		                    }
	                    ) {
	                        __typename
	                        recordPage {
	                            __typename
	                            data {
		                            prices(priceLists: "basic", currency: CZK) {
		                                priceWithTax(formatted: true, withCurrency: true)
		                            }
	                            }
	                        }
	                    }
	                }
					""",
				entities.get(0).getPrimaryKey(),
				entities.get(1).getPrimaryKey()
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(PRODUCT_QUERY_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return formatted prices with custom locale")
	void shouldReturnFormattedPricesWithCustomLocale(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final List<SealedEntity> entities = findEntitiesWithPrice(originalProductEntities, 2);

		final var expectedBody = createBasicPageResponse(
			entities,
			this::createEntityDtoWithFormattedPrices
		);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    queryProduct(
		                    filterBy: {
		                        entityPrimaryKeyInSet: [%d, %d]
		                    }
	                    ) {
	                        __typename
	                        recordPage {
	                            __typename
	                            data {
		                            prices(priceLists: "basic", currency: CZK, locale: cs_CZ) {
		                                priceWithTax(formatted: true, withCurrency: true)
		                            }
	                            }
	                        }
	                    }
	                }
					""",
				entities.get(0).getPrimaryKey(),
				entities.get(1).getPrimaryKey()
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(PRODUCT_QUERY_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return error when formatting prices without locale")
	void shouldReturnErrorWhenFormattingPricesWithoutLocale(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final List<SealedEntity> entities = findEntitiesWithPrice(originalProductEntities, 2);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    queryProduct(
		                    filterBy: {
		                        entityPrimaryKeyInSet: [%d, %d]
		                    }
	                    ) {
	                        __typename
	                        recordPage {
	                            __typename
	                            data {
		                            prices(priceLists: "basic", currency: CZK) {
		                                priceWithTax(formatted: true, withCurrency: true)
		                            }
	                            }
	                        }
	                    }
	                }
					""",
				entities.get(0).getPrimaryKey(),
				entities.get(1).getPrimaryKey()
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, hasSize(greaterThan(0)));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return associated data with inherited locale for products")
	void shouldReturnAssociatedDataWithInheritedLocaleForProducts(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final var entities = findEntities(
			originalProductEntities,
			it -> it.getAssociatedData(ASSOCIATED_DATA_LABELS, Locale.ENGLISH) != null &&
				it.getAllLocales().contains(Locale.ENGLISH),
			 2
		);

		final var expectedBody = createBasicPageResponse(
			entities,
			this::createEntityDtoWithAssociatedData
		);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    queryProduct(
	                        filterBy: {
	                            attributeCodeInSet: ["%s", "%s"]
	                            entityLocaleEquals: en
	                        }
                        ) {
                            __typename
	                        recordPage {
	                            __typename
	                            data {
	                                primaryKey
			                        type
		                            associatedData {
		                                __typename
		                                labels
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
			.body(ERRORS_PATH, nullValue())
			.body(PRODUCT_QUERY_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return associated data with custom locale for products")
	void shouldReturnAssociatedDataWithCustomLocaleForProducts(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final var entities = findEntities(
			originalProductEntities,
			it -> it.getAssociatedData(ASSOCIATED_DATA_LABELS, Locale.ENGLISH) != null,
			2
		);

		final var expectedBody = createBasicPageResponse(
			entities,
			this::createEntityDtoWithAssociatedData
		);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    queryProduct(
	                        filterBy: {
	                            attributeCodeInSet: ["%s", "%s"]
	                        }
	                    ) {
	                        __typename
	                        recordPage {
	                            __typename
	                            data {
	                                primaryKey
			                        type
		                            associatedData(locale: en) {
		                                __typename
		                                labels
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
			.body(ERRORS_PATH, nullValue())
			.body(PRODUCT_QUERY_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return single reference for products")
	void shouldReturnSingleReferenceForProducts(Evita evita, GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final var entities = findEntities(
			originalProductEntities,
			it -> it.getReferences(Entities.PARAMETER).size() == 1 &&
				it.getReferences(Entities.PARAMETER).iterator().next().getAttribute(ATTRIBUTE_MARKET_SHARE) != null,
			2
		);

		final var expectedBody = createBasicPageResponse(
			entities,
			entity -> {
				final ReferenceContract reference = entity.getReferences(Entities.PARAMETER).iterator().next();
				final SealedEntity referencedEntity = evita.queryCatalog(
					TEST_CATALOG,
					session -> {
						return session.getEntity(Entities.PARAMETER, reference.getReferencedPrimaryKey(), attributeContent(ATTRIBUTE_CODE));
					}
				).orElseThrow();
				final SealedEntity groupEntity = evita.queryCatalog(
					TEST_CATALOG,
					session -> {
						return session.getEntity(Entities.PARAMETER_GROUP, reference.getGroup().get().getPrimaryKey(), attributeContent(ATTRIBUTE_CODE));
					}
				).orElseThrow();

				return map()
					.e(EntityDescriptor.PRIMARY_KEY.name(), entity.getPrimaryKey())
					.e(EntityDescriptor.TYPE.name(), Entities.PRODUCT)
					.e("parameter", map()
						.e(TYPENAME_FIELD, ReferenceDescriptor.THIS.name(createEmptyEntitySchema("Product"), createEmptyEntitySchema("Parameter")))
						.e(ReferenceDescriptor.ATTRIBUTES.name(), map()
							.e(TYPENAME_FIELD, AttributesDescriptor.THIS.name(createEmptyEntitySchema("Product"), createEmptyEntitySchema("Parameter")))
							.e(ATTRIBUTE_MARKET_SHARE, reference.getAttribute(ATTRIBUTE_MARKET_SHARE).toString())
							.build())
						.e(ReferenceDescriptor.REFERENCED_ENTITY.name(), map()
							.e(TYPENAME_FIELD, "Parameter")
							.e(EntityDescriptor.PRIMARY_KEY.name(), reference.getReferencedPrimaryKey())
							.e(EntityDescriptor.TYPE.name(), reference.getReferencedEntityType())
							.e(EntityDescriptor.ATTRIBUTES.name(), map()
								.e(ATTRIBUTE_CODE, referencedEntity.getAttribute(ATTRIBUTE_CODE))
								.build())
							.build())
						.e(ReferenceDescriptor.GROUP_ENTITY.name(), map()
							.e(TYPENAME_FIELD, "ParameterGroup")
							.e(EntityDescriptor.PRIMARY_KEY.name(), reference.getGroup().get().getPrimaryKey())
							.e(EntityDescriptor.TYPE.name(), reference.getGroup().get().getType())
							.e(EntityDescriptor.ATTRIBUTES.name(), map()
								.e(ATTRIBUTE_CODE, groupEntity.getAttribute(ATTRIBUTE_CODE))
								.build())
							.build())
						.build())
					.build();
			}
		);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    queryProduct(
	                        filterBy: {
	                            attributeCodeInSet: ["%s", "%s"]
	                        }
	                    ) {
	                        __typename
	                        recordPage {
	                            __typename
	                            data {
	                                primaryKey
			                        type
		                            parameter {
		                                __typename
		                                attributes {
		                                    __typename
		                                    marketShare
		                                }
		                                referencedEntity {
		                                    __typename
		                                    primaryKey
		                                    type
		                                    attributes {
		                                        code
		                                    }
		                                }
		                                groupEntity {
		                                    __typename
		                                    primaryKey
		                                    type
		                                    attributes {
		                                        code
		                                    }
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
			.body(ERRORS_PATH, nullValue())
			.body(PRODUCT_QUERY_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return reference list for products")
	void shouldReturnReferenceListForProducts(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final var entities = findEntities(
			originalProductEntities,
			it -> it.getReferences(Entities.STORE).size() > 1,
			2
		);

		final var expectedBody = createBasicPageResponse(
			entities,
			entity -> {
				final var references = entity.getReferences(Entities.STORE)
					.stream()
					.map(reference ->
						map()
							.e(ReferenceDescriptor.REFERENCED_ENTITY.name(), map()
								.e(EntityDescriptor.PRIMARY_KEY.name(), reference.getReferencedPrimaryKey())
								.build())
							.build())
					.toList();

				return map()
					.e(EntityDescriptor.PRIMARY_KEY.name(), entity.getPrimaryKey())
					.e(EntityDescriptor.TYPE.name(), Entities.PRODUCT)
					.e("store", references)
					.build();
			}
		);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    queryProduct(
	                        filterBy: {
	                            attributeCodeInSet: ["%s", "%s"]
	                        }
	                    ) {
	                        __typename
	                        recordPage {
	                            __typename
	                            data {
	                                primaryKey
			                        type
		                            store {
		                                referencedEntity {
		                                    primaryKey
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
			.body(ERRORS_PATH, nullValue())
			.body(PRODUCT_QUERY_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should find product by complex query")
	void shouldFindProductByComplexQuery(Evita evita, GraphQLTester tester, List<SealedEntity> originalProductEntities) {
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

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    queryProduct(
	                        filterBy: {
	                            or: [
	                                {
	                                    attributeAliasEquals: %b
	                                    attributePriorityEquals: "%s"
	                                },
	                                {
	                                    attributeAliasEquals: %b
	                                    attributePriorityEquals: "%s"
	                                },
	                                {
	                                    attributeAliasEquals: false
	                                    attributePriorityInSet: ["%s", "%s", "%s", "%s"]
	                                }
	                            ]
	                            not: {
	                                attributeCodeEquals: "%s"
	                            }
	                        }
	                    ) {
	                        recordStrip(limit: %d) {
	                            data {
	                                primaryKey
	                            }
	                        }
	                    }
	                }
					""",
				withTrueAlias.get(0).getAttribute(ATTRIBUTE_ALIAS),
				withTrueAlias.get(0).getAttribute(ATTRIBUTE_PRIORITY),
				withTrueAlias.get(1).getAttribute(ATTRIBUTE_ALIAS),
				withTrueAlias.get(1).getAttribute(ATTRIBUTE_PRIORITY),
				(Long)withFalseAlias.get(0).getAttribute(ATTRIBUTE_PRIORITY),
				(Long)withFalseAlias.get(1).getAttribute(ATTRIBUTE_PRIORITY),
				(Long)withFalseAlias.get(2).getAttribute(ATTRIBUTE_PRIORITY),
				(Long)withFalseAlias.get(3).getAttribute(ATTRIBUTE_PRIORITY),
				withFalseAlias.get(4).getAttribute(ATTRIBUTE_CODE),
				Integer.MAX_VALUE
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				PRODUCT_QUERY_PATH + "." + ResponseDescriptor.RECORD_STRIP.name() + "." + RecordPageDescriptor.DATA.name() + "." + EntityDescriptor.PRIMARY_KEY.name(),
				contains(expectedEntities)
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should order entities by complex query")
	void shouldOrderEntitiesByComplexQuery(Evita evita, GraphQLTester tester) {
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

		tester.test(TEST_CATALOG)
			.document(
				"""
		            query {
		                queryProduct(
		                    filterBy: {
		                        attributePriorityLessThan: "35000"
		                    }
		                    orderBy: {
		                        attributeCreatedNatural: DESC,
		                        attributeManufacturedNatural: ASC
		                    }
		                ) {
		                    recordStrip(limit: 30) {
		                        data {
		                            primaryKey
		                        }
		                    }
		                }
		            }
					"""
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				PRODUCT_QUERY_PATH + "."  + ResponseDescriptor.RECORD_STRIP.name() + "." + RecordPageDescriptor.DATA.name() + "." + EntityDescriptor.PRIMARY_KEY.name(),
				contains(expectedEntities)
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return page of entities")
	void shouldReturnPageOfEntities(Evita evita, GraphQLTester tester) {
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
			.document(
				"""
		            query {
		                queryProduct(
		                    filterBy: {
		                        attributePriorityLessThan: "35000"
		                    }
		                ) {
		                    recordPage(number: 2, size: 3) {
		                        data {
		                            primaryKey
		                        }
		                    }
		                }
		            }
					"""
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				PRODUCT_QUERY_PATH + "." + ResponseDescriptor.RECORD_PAGE.name() + "." + RecordPageDescriptor.DATA.name() + "." + EntityDescriptor.PRIMARY_KEY.name(),
				contains(
					expectedEntities.stream()
						.skip(3)
						.limit(3)
						.toArray(Integer[]::new)
				)
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return strip of entities")
	void shouldReturnStripOfEntities(Evita evita, GraphQLTester tester) {
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
			.document(
				"""
		            query {
		                queryProduct(
		                    filterBy: {
		                        attributePriorityLessThan: "35000"
		                    }
		                ) {
		                    recordStrip(offset: 2, limit: 3) {
		                        data {
		                            primaryKey
		                        }
		                    }
		                }
		            }
					"""
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				PRODUCT_QUERY_PATH + "." + ResponseDescriptor.RECORD_STRIP.name() + "." + RecordPageDescriptor.DATA.name() + "." + EntityDescriptor.PRIMARY_KEY.name(),
				contains(
					expectedEntities.stream()
						.skip(2)
						.limit(3)
						.toArray(Integer[]::new)
				)
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return attribute histogram")
	void shouldReturnAttributeHistogram(Evita evita, GraphQLTester tester) {
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
			.document(
				"""
		            query {
		                queryProduct(
		                    filterBy: {
		                        attributeAliasIs: NOT_NULL
		                    }
		                ) {
		                    recordPage(size: %d) {
		                        data {
		                            primaryKey
		                        }
		                    }
		                    extraResults {
		                        __typename
		                        attributeHistogram {
		                            __typename
		                            quantity {
		                                __typename
		                                min
		                                max
		                                overallCount
		                                buckets(requestedCount: 20) {
		                                    __typename
		                                    index
		                                    threshold
		                                    occurrences
		                                }
		                            }
		                        }
		                    }
		                }
		            }
					""",
				Integer.MAX_VALUE
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				PRODUCT_QUERY_PATH + "." + ResponseDescriptor.EXTRA_RESULTS.name() + "." + TYPENAME_FIELD,
				equalTo(ExtraResultsDescriptor.THIS.name(createEmptyEntitySchema("Product")))
			)
			.body(
				PRODUCT_QUERY_PATH + "." + ResponseDescriptor.EXTRA_RESULTS.name() + "." + ExtraResultsDescriptor.ATTRIBUTE_HISTOGRAM.name() + "." + TYPENAME_FIELD,
				equalTo(AttributeHistogramDescriptor.THIS.name(createEmptyEntitySchema("Product")))
			)
			.body(
				PRODUCT_QUERY_PATH + "." + ResponseDescriptor.EXTRA_RESULTS.name() + "." + ExtraResultsDescriptor.ATTRIBUTE_HISTOGRAM.name() + "." + ATTRIBUTE_QUANTITY,
				equalTo(expectedHistogram)
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return multiple attribute histograms")
	void shouldReturnMultipleAttributeHistograms(Evita evita, GraphQLTester tester) {
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
							attributeHistogram(20, ATTRIBUTE_QUANTITY, ATTRIBUTE_PRIORITY)
						)
					),
					EntityReference.class
				);
			}
		);

		final var expectedQuantityHistogram = createAttributeHistogramDto(response, ATTRIBUTE_QUANTITY);
		final var expectedPriorityHistogram = createAttributeHistogramDto(response, ATTRIBUTE_PRIORITY);

		tester.test(TEST_CATALOG)
			.document(
				"""
		            query {
		                queryProduct(
		                    filterBy: {
		                        attributeAliasIs: NOT_NULL
		                    }
		                ) {
		                    recordPage(size: %d) {
		                        data {
		                            primaryKey
		                        }
		                    }
		                    extraResults {
		                        attributeHistogram {
		                            quantity {
		                                __typename
		                                min
		                                max
		                                overallCount
		                                buckets(requestedCount: 20) {
		                                    __typename
		                                    index
		                                    threshold
		                                    occurrences
		                                }
		                            }
		                        }
		                    }
		                    otherExtraResults: extraResults {
		                        attributeHistogram {
		                            priority {
		                                __typename
		                                min
		                                max
		                                overallCount
		                                buckets(requestedCount: 20) {
		                                    __typename
		                                    index
		                                    threshold
		                                    occurrences
		                                }
		                            }
		                        }
		                    }
		                }
		            }
					""",
				Integer.MAX_VALUE
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(PRODUCT_QUERY_PATH + ".extraResults.attributeHistogram.quantity", equalTo(expectedQuantityHistogram))
			.body(PRODUCT_QUERY_PATH + ".otherExtraResults.attributeHistogram.priority", equalTo(expectedPriorityHistogram));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return multiple same attribute histogram buckets")
	void shouldReturnMultipleSameAttributeHistogramBuckets(Evita evita, GraphQLTester tester) {
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
			.document(
				"""
		            query {
		                queryProduct(
		                    filterBy: {
		                        attributeAliasIs: NOT_NULL
		                    }
		                ) {
		                    recordPage(size: %d) {
		                        data {
		                            primaryKey
		                        }
		                    }
		                    extraResults {
		                        attributeHistogram {
		                            quantity {
		                                __typename
		                                min
		                                max
		                                overallCount
		                                buckets(requestedCount: 20) {
		                                    __typename
		                                    index
		                                    threshold
		                                    occurrences
		                                }
		                            }
		                            otherQuantity: quantity {
		                                __typename
		                                min
		                                max
		                                overallCount
		                                buckets(requestedCount: 20) {
		                                    __typename
		                                    index
		                                    threshold
		                                    occurrences
		                                }
		                            }
		                        }
		                    }
		                }
		            }
					""",
				Integer.MAX_VALUE
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				PRODUCT_QUERY_PATH + "." + ResponseDescriptor.EXTRA_RESULTS.name() + "." + ExtraResultsDescriptor.ATTRIBUTE_HISTOGRAM.name() + "." + ATTRIBUTE_QUANTITY,
				equalTo(expectedHistogram))
			.body(
				PRODUCT_QUERY_PATH + "." + ResponseDescriptor.EXTRA_RESULTS.name() + "." + ExtraResultsDescriptor.ATTRIBUTE_HISTOGRAM.name() + ".otherQuantity",
				equalTo(expectedHistogram)
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return error for missing attribute histogram buckets count")
	void shouldReturnErrorForMissingAttributeHistogramBucketsCount(GraphQLTester tester) {
		tester.test(TEST_CATALOG)
			.document(
				"""
		            query {
		                queryProduct(
		                    filterBy: {
		                        attributeAliasIs: NOT_NULL
		                    }
		                ) {
		                    recordPage(size: %d) {
		                        data {
		                            primaryKey
		                        }
		                    }
		                    extraResults {
		                        attributeHistogram {
		                            quantity {
		                                min
		                                max
		                                overallCount
		                                buckets {
		                                    index
		                                    threshold
		                                    occurrences
		                                }
		                            }
		                        }
		                    }
		                }
		            }
					""",
				Integer.MAX_VALUE
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, hasSize(greaterThan(0)));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return error for missing attribute histogram buckets count")
	void shouldReturnErrorForMissingAttributeHistogramBuckets(GraphQLTester tester) {
		tester.test(TEST_CATALOG)
			.document(
				"""
		            query {
		                queryProduct(
		                    filterBy: {
		                        attributeAliasIs: NOT_NULL
		                    }
		                ) {
		                    recordPage(size: %d) {
		                        data {
		                            primaryKey
		                        }
		                    }
		                    extraResults {
		                        attributeHistogram {
		                            quantity {
		                                min
		                                max
		                                overallCount
		                            }
		                        }
		                    }
		                }
		            }
					""",
				Integer.MAX_VALUE
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, hasSize(greaterThan(0)));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return error for multiple attribute histogram buckets count")
	void shouldReturnErrorForMultipleAttributeHistogramBucketsCount(GraphQLTester tester) {
		tester.test(TEST_CATALOG)
			.document(
				"""
		            query {
		                queryProduct(
		                    filterBy: {
		                        attributeAliasIs: NOT_NULL
		                    }
		                ) {
		                    recordPage(size: %d) {
		                        data {
		                            primaryKey
		                        }
		                    }
		                    extraResults {
		                        attributeHistogram {
		                            quantity {
		                                min
		                                max
		                                overallCount
		                                buckets(requestedCount: 10) {
		                                    index
		                                }
		                                otherBuckets: buckets(requestedCount: 20) {
		                                    index
		                                }
		                            }
		                        }
		                    }
		                }
		            }
					""",
				Integer.MAX_VALUE
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, hasSize(greaterThan(0)));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return price histogram")
	void shouldReturnPriceHistogram(Evita evita, GraphQLTester tester) {
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
			.document(
				"""
		            query {
		                queryProduct(
		                    filterBy: {
		                        priceInCurrency: EUR
		                        priceInPriceLists: ["vip", "basic"]
		                    }
		                ) {
		                    recordPage(size: %d) {
		                        data {
		                            primaryKey
		                        }
		                    }
		                    extraResults {
		                        __typename
		                        priceHistogram {
		                            __typename
	                                min
	                                max
	                                overallCount
	                                buckets(requestedCount: 20) {
	                                    __typename
	                                    index
	                                    threshold
	                                    occurrences
	                                }
		                        }
		                    }
		                }
		            }
					""",
				Integer.MAX_VALUE
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				PRODUCT_QUERY_PATH + "." + ResponseDescriptor.EXTRA_RESULTS.name() + "." + TYPENAME_FIELD,
				equalTo(ExtraResultsDescriptor.THIS.name(createEmptyEntitySchema("Product")))
			)
			.body(
				PRODUCT_QUERY_PATH + "." + ResponseDescriptor.EXTRA_RESULTS.name() + "." + ExtraResultsDescriptor.PRICE_HISTOGRAM.name(),
				equalTo(expectedBody)
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return multiple same price histograms")
	void shouldReturnMultipleSamePriceHistograms(Evita evita, GraphQLTester tester) {
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
			.document(
				"""
		            query {
		                queryProduct(
		                    filterBy: {
		                        priceInCurrency: EUR
		                        priceInPriceLists: ["vip", "basic"]
		                    }
		                ) {
		                    recordPage(size: %d) {
		                        data {
		                            primaryKey
		                        }
		                    }
		                    extraResults {
		                        priceHistogram {
		                            __typename
	                                min
	                                max
	                                overallCount
	                                buckets(requestedCount: 20) {
	                                    __typename
	                                    index
	                                    threshold
	                                    occurrences
	                                }
		                        }
		                        otherPriceHistogram: priceHistogram {
		                            __typename
	                                min
	                                max
	                                overallCount
	                                buckets(requestedCount: 20) {
	                                    __typename
	                                    index
	                                    threshold
	                                    occurrences
	                                }
		                        }
		                    }
		                }
		            }
					""",
				Integer.MAX_VALUE
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				PRODUCT_QUERY_PATH + "." + ResponseDescriptor.EXTRA_RESULTS.name() + "." + ExtraResultsDescriptor.PRICE_HISTOGRAM.name(),
				equalTo(expectedBody)
			)
			.body(
				PRODUCT_QUERY_PATH + "." + ResponseDescriptor.EXTRA_RESULTS.name() + ".otherPriceHistogram",
				equalTo(expectedBody)
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return error for missing price histogram buckets count")
	void shouldReturnErrorForMissingPriceHistogramBucketsCount(GraphQLTester tester) {
		tester.test(TEST_CATALOG)
			.document(
				"""
		            query {
		                queryProduct(
		                    filterBy: {
		                        priceInCurrency: EUR
		                        priceInPriceLists: ["vip", "basic"]
		                    }
		                ) {
		                    recordPage(size: %d) {
		                        data {
		                            primaryKey
		                        }
		                    }
		                    extraResults {
		                        priceHistogram {
	                                min
	                                max
	                                overallCount
	                                buckets {
	                                    index
	                                    threshold
	                                    occurrences
	                                }
		                        }
		                    }
		                }
		            }
					""",
				Integer.MAX_VALUE
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, hasSize(greaterThan(0)));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return error for missing price histogram buckets")
	void shouldReturnErrorForMissingPriceHistogramBuckets(GraphQLTester tester) {
		tester.test(TEST_CATALOG)
			.document(
				"""
		            query {
		                queryProduct(
		                    filterBy: {
		                        priceInCurrency: EUR
		                        priceInPriceLists: ["vip", "basic"]
		                    }
		                ) {
		                    recordPage(size: %d) {
		                        data {
		                            primaryKey
		                        }
		                    }
		                    extraResults {
		                        priceHistogram {
	                                min
	                                max
	                                overallCount
		                        }
		                    }
		                }
		            }
					""",
				Integer.MAX_VALUE
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, hasSize(greaterThan(0)));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return error for multiple price histogram buckets counts")
	void shouldReturnErrorForMultiplePriceHistogramBucketsCounts(GraphQLTester tester) {
		tester.test(TEST_CATALOG)
			.document(
				"""
		            query {
		                queryProduct(
		                    filterBy: {
		                        priceInCurrency: EUR
		                        priceInPriceLists: ["vip", "basic"]
		                    }
		                ) {
		                    recordPage(size: %d) {
		                        data {
		                            primaryKey
		                        }
		                    }
		                    extraResults {
		                        priceHistogram {
	                                min
	                                max
	                                overallCount
	                                buckets(requestedCount: 10) {
	                                    index
	                                    threshold
	                                    occurrences
	                                }
	                                otherBuckets: buckets(requestedCount: 20) {
	                                    index
	                                    threshold
	                                    occurrences
	                                }
		                        }
		                    }
		                }
		            }
					""",
				Integer.MAX_VALUE
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, hasSize(greaterThan(0)));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return category parents for products")
	void shouldReturnCategoryParentsForProducts(Evita evita, GraphQLTester tester) {
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
			.document(
				"""
		            query {
		                queryProduct(
		                    filterBy: {
		                        hierarchyCategoryWithin: {
		                            ofParent: 95
		                        }
		                    }
		                ) {
		                    recordPage(size: %d) {
		                        data {
		                            primaryKey
		                        }
		                    }
		                    extraResults {
		                        __typename
		                        hierarchyParents {
		                            __typename
		                            category {
		                                __typename
		                                primaryKey
		                                references {
		                                    __typename
		                                    primaryKey
		                                    parentEntities {
		                                        __typename
		                                        primaryKey
		                                        attributes {
		                                            code
	                                            }
		                                    }
		                                }
		                            }
		                        }
		                    }
		                }
		            }
					""",
				Integer.MAX_VALUE
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				PRODUCT_QUERY_PATH + "." + ResponseDescriptor.EXTRA_RESULTS.name() + "." + TYPENAME_FIELD,
				equalTo(ExtraResultsDescriptor.THIS.name(createEmptyEntitySchema("Product")))
			)
			.body(
				PRODUCT_QUERY_PATH + "." + ResponseDescriptor.EXTRA_RESULTS.name() + "." + ExtraResultsDescriptor.HIERARCHY_PARENTS.name() + "." + TYPENAME_FIELD,
				equalTo(HierarchyParentsDescriptor.THIS.name(createEmptyEntitySchema("Product")))
			)
			.body(
				PRODUCT_QUERY_PATH + "." + ResponseDescriptor.EXTRA_RESULTS.name() + "." + ExtraResultsDescriptor.HIERARCHY_PARENTS.name() + ".category",
				equalTo(expectedBody)
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return category parents for products")
	void shouldReturnErrorForSelfParentsForProducts(GraphQLTester tester) {
		tester.test(TEST_CATALOG)
			.document(
				"""
		            query {
		                queryProduct(
		                    filterBy: {
		                        hierarchyCategoryWithin: {
		                            ofParent: 95
		                        }
		                    }
		                ) {
		                    recordPage(size: %d) {
		                        data {
		                            primaryKey
		                        }
		                    }
		                    extraResults {
		                        __typename
		                        hierarchyParents {
		                            __typename
		                            self {
		                                __typename
		                                primaryKey
		                                references {
		                                    __typename
		                                    primaryKey
		                                    parentEntities {
		                                        __typename
		                                        primaryKey
		                                        attributes {
		                                            code
	                                            }
		                                    }
		                                }
		                            }
		                        }
		                    }
		                }
		            }
					""",
				Integer.MAX_VALUE
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, hasSize(greaterThan(0)));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return self parents for category")
	void shouldReturnSelfParentsForCategory(Evita evita, GraphQLTester tester) {
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
			.document(
				"""
		            query {
		                queryCategory {
		                    recordPage(size: %d) {
		                        data {
		                            primaryKey
		                        }
		                    }
		                    extraResults {
		                        __typename
		                        hierarchyParents {
		                            __typename
		                            self {
		                                __typename
		                                primaryKey
		                                parentEntities {
	                                        __typename
	                                        primaryKey
	                                        attributes {
	                                            code
                                            }
	                                    }
		                            }
		                        }
		                    }
		                }
		            }
					""",
				Integer.MAX_VALUE
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				CATEGORY_QUERY_PATH + "." + ResponseDescriptor.EXTRA_RESULTS.name() + "." + TYPENAME_FIELD,
				equalTo(ExtraResultsDescriptor.THIS.name(createEmptyEntitySchema("Category")))
			)
			.body(
				CATEGORY_QUERY_PATH + "." + ResponseDescriptor.EXTRA_RESULTS.name() + "." + ExtraResultsDescriptor.HIERARCHY_PARENTS.name() + "." + TYPENAME_FIELD,
				equalTo(HierarchyParentsDescriptor.THIS.name(createEmptyEntitySchema("Category")))
			)
			.body(
				CATEGORY_QUERY_PATH + "." + ResponseDescriptor.EXTRA_RESULTS.name() + "." + ExtraResultsDescriptor.HIERARCHY_PARENTS.name() + "." + HierarchyParentsDescriptor.SELF.name(),
				equalTo(expectedBody)
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return error for references in self parents for category")
	void shouldReturnErrorForReferencesInSelfParentsForCategory(GraphQLTester tester) {
		tester.test(TEST_CATALOG)
			.document(
				"""
		            query {
		                queryCategory {
		                    recordPage(size: %d) {
		                        data {
		                            primaryKey
		                        }
		                    }
		                    extraResults {
		                        hierarchyParentsOfSelf {
		                            self {
		                                primaryKey
		                                parentEntities {
	                                        primaryKey
	                                    }
	                                    references {
		                                    primaryKey
		                                }
		                            }
		                        }
		                    }
		                }
		            }
					""",
				Integer.MAX_VALUE
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, hasSize(greaterThan(0)));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should pass locale to parents")
	void shouldPassLocaleToParents(Evita evita, GraphQLTester tester) {
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

		final var expectedBody = createAttributeOfHierarchyParentsDto(response);

		tester.test(TEST_CATALOG)
			.document(
				"""
		            query {
		                queryProduct(
		                    filterBy: {
		                        hierarchyCategoryWithin: {
		                            ofParent: 95
		                        }
		                        entityLocaleEquals: cs_CZ
		                    }
		                ) {
		                    recordPage(size: %d) {
		                        data {
		                            primaryKey
		                        }
		                    }
		                    extraResults {
		                        hierarchyParents {
		                            category {
		                                references {
		                                    parentEntities {
		                                        attributes {
		                                            name
	                                            }
		                                    }
		                                }
		                            }
		                        }
		                    }
		                }
		            }
					""",
				Integer.MAX_VALUE
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				PRODUCT_QUERY_PATH + "." + ResponseDescriptor.EXTRA_RESULTS.name() + "." + ExtraResultsDescriptor.HIERARCHY_PARENTS.name() + ".category",
				equalTo(expectedBody)
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return two levels of category statistics for products")
	void shouldReturnTwoLevelsOfCategoryStatisticsForProducts(Evita evita, GraphQLTester tester) {
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
							hierarchyStatisticsOfReference(Entities.CATEGORY, entityFetch(attributeContent(ATTRIBUTE_CODE)))
						)
					),
					EntityReference.class
				);
			}
		);
		assertFalse(response.getRecordData().isEmpty());

		final var expectedBody = createHierarchyStatisticsDto(response);

		tester.test(TEST_CATALOG)
			.document(
				"""
		            query {
		                queryProduct(
		                    filterBy: {
		                        attributeAliasEquals: true
		                        entityLocaleEquals: cs_CZ
		                    }
		                ) {
		                    recordPage(size: %d) {
		                        data {
		                            primaryKey
		                        }
		                    }
		                    extraResults {
		                        __typename
		                        hierarchyStatistics {
		                            __typename
		                            category {
		                                __typename
		                                cardinality
		                                entity {
		                                    __typename
	                                        primaryKey
	                                        attributes {
	                                            code
	                                        }
	                                    }
		                                childrenStatistics {
		                                    __typename
		                                    cardinality
		                                    entity {
		                                        __typename
		                                        primaryKey
		                                        attributes {
		                                            code
		                                        }
		                                    }
	                                    }
		                            }
		                        }
		                    }
		                }
		            }
					""",
				Integer.MAX_VALUE
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				PRODUCT_QUERY_PATH + "." + ResponseDescriptor.EXTRA_RESULTS.name() + "." + TYPENAME_FIELD,
				equalTo(ExtraResultsDescriptor.THIS.name(createEmptyEntitySchema("Product")))
			)
			.body(
				PRODUCT_QUERY_PATH + "." + ResponseDescriptor.EXTRA_RESULTS.name() + "." + ExtraResultsDescriptor.HIERARCHY_STATISTICS.name() + "." + TYPENAME_FIELD,
				equalTo(HierarchyStatisticsDescriptor.THIS.name(createEmptyEntitySchema("Product")))
			)
			.body(
				PRODUCT_QUERY_PATH + "." + ResponseDescriptor.EXTRA_RESULTS.name() + "." + ExtraResultsDescriptor.HIERARCHY_STATISTICS.name() + ".category",
				equalTo(expectedBody)
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return two levels of self statistics for category")
	void shouldReturnTwoLevelsOfSelfStatisticsForCategory(Evita evita, GraphQLTester tester) {
		final EvitaResponse<EntityReference> response = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							and(
								entityLocaleEquals(CZECH_LOCALE)
							)
						),
						require(
							page(1, Integer.MAX_VALUE),
							hierarchyStatisticsOfSelf(entityFetch(attributeContent(ATTRIBUTE_CODE)))
						)
					),
					EntityReference.class
				);
			}
		);
		assertFalse(response.getRecordData().isEmpty());

		final var expectedBody = createSelfHierarchyStatisticsDto(response);

		tester.test(TEST_CATALOG)
			.document(
				"""
		            query {
		                queryCategory(
		                    filterBy: {
		                        entityLocaleEquals: cs_CZ
		                    }
		                ) {
		                    recordPage(size: %d) {
		                        data {
		                            primaryKey
		                        }
		                    }
		                    extraResults {
		                        __typename
		                        hierarchyStatistics {
		                            __typename
		                            self {
		                                __typename
		                                cardinality
		                                entity {
		                                    __typename
	                                        primaryKey
	                                        attributes {
	                                            code
	                                        }
	                                    }
		                                childrenStatistics {
		                                    __typename
		                                    cardinality
		                                    entity {
		                                        __typename
		                                        primaryKey
		                                        attributes {
		                                            code
		                                        }
		                                    }
	                                    }
		                            }
		                        }
		                    }
		                }
		            }
					""",
				Integer.MAX_VALUE
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				CATEGORY_QUERY_PATH + "." + ResponseDescriptor.EXTRA_RESULTS.name() + "." + TYPENAME_FIELD,
				equalTo(ExtraResultsDescriptor.THIS.name(createEmptyEntitySchema("Category")))
			)
			.body(
				CATEGORY_QUERY_PATH + "." + ResponseDescriptor.EXTRA_RESULTS.name() + "." + ExtraResultsDescriptor.HIERARCHY_STATISTICS.name() + "." + TYPENAME_FIELD,
				equalTo(HierarchyStatisticsDescriptor.THIS.name(createEmptyEntitySchema("Category")))
			)
			.body(
				CATEGORY_QUERY_PATH + "." + ResponseDescriptor.EXTRA_RESULTS.name() + "." + ExtraResultsDescriptor.HIERARCHY_STATISTICS.name() + ".self",
				equalTo(expectedBody)
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return error for self statistics of product")
	void shouldReturnErrorForSelfStatisticsOfProduct(GraphQLTester tester) {
		tester.test(TEST_CATALOG)
			.document(
				"""
		            query {
		                queryProduct(
		                    filterBy: {
		                        entityLocaleEquals: cs_CZ
		                    }
		                ) {
		                    recordPage(size: %d) {
		                        data {
		                            primaryKey
		                        }
		                    }
		                    extraResults {
		                        hierarchyStatistics {
		                            self {
		                                cardinality
		                            }
		                        }
		                    }
		                }
		            }
					""",
				Integer.MAX_VALUE
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, hasSize(greaterThan(0)));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should pass locale to hierarchy statistics entities")
	void shouldPassLocaleToHierarchyStatisticsEntities(Evita evita, GraphQLTester tester) {
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
			.document(
				"""
		            query {
		                queryProduct(
		                    filterBy: {
		                        attributeAliasEquals: true
		                        entityLocaleEquals: cs_CZ
		                    }
		                ) {
		                    recordPage(size: %d) {
		                        data {
		                            primaryKey
		                        }
		                    }
		                    extraResults {
		                        hierarchyStatistics {
		                            category {
		                                entity {
	                                        attributes {
	                                            name
	                                        }
	                                    }
		                            }
		                        }
		                    }
		                }
		            }
					""",
				Integer.MAX_VALUE
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				PRODUCT_QUERY_PATH + "." + ResponseDescriptor.EXTRA_RESULTS.name() + "." + ExtraResultsDescriptor.HIERARCHY_STATISTICS.name() + ".category." + HierarchyStatisticsLevelInfoDescriptor.ENTITY.name() + "." + EntityDescriptor.ATTRIBUTES.name() + "." + ATTRIBUTE_NAME,
				equalTo(expectedBody)
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return facet summary with counts for products")
	void shouldReturnFacetSummaryWithCountsForProducts(Evita evita, GraphQLTester tester) {
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
			.document(
				"""
		            query {
		                queryProduct {
		                    extraResults {
		                        __typename
		                        facetSummary {
		                            __typename
		                            brand {
		                                __typename
		                                groupEntity {
			                                __typename
			                                primaryKey
			                                type
			                            }
			                            count
			                            facetStatistics {
			                                __typename
			                                facetEntity {
			                                    __typename
			                                    primaryKey
			                                    type
			                                }
			                                requested
			                                count
			                            }
		                            }
		                        }
		                    }
		                }
		            }
					""",
				Integer.MAX_VALUE
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				PRODUCT_QUERY_PATH + "." + ResponseDescriptor.EXTRA_RESULTS.name() + "." + TYPENAME_FIELD,
				equalTo(ExtraResultsDescriptor.THIS.name(createEmptyEntitySchema("Product")))
			)
			.body(
				PRODUCT_QUERY_PATH + "." + ResponseDescriptor.EXTRA_RESULTS.name() + "." + ExtraResultsDescriptor.FACET_SUMMARY.name() + "." + TYPENAME_FIELD,
				equalTo(FacetSummaryDescriptor.THIS.name(createEmptyEntitySchema("Product")))
			)
			.body(
				PRODUCT_QUERY_PATH + "." + ResponseDescriptor.EXTRA_RESULTS.name() + "." + ExtraResultsDescriptor.FACET_SUMMARY.name() + ".brand",
				equalTo(expectedBody)
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return facet summary with impacts and entities for products")
	void shouldReturnFacetSummaryWithImpactsAndEntitiesForProducts(Evita evita, GraphQLTester tester) {
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
			.document(
				"""
		            query {
		                queryProduct {
		                    extraResults {
		                        facetSummary {
		                            brand {
		                                groupEntity {
		                                    primaryKey
		                                    type
		                                }
		                                count
			                            facetStatistics {
			                                facetEntity {
			                                    primaryKey
			                                    type
			                                    attributes {
			                                        code
			                                    }
			                                }
			                                requested
			                                count
			                                impact {
			                                    __typename
			                                    difference
			                                    matchCount
			                                    hasSense
			                                }
			                            }
		                            }
		                        }
		                    }
		                }
		            }
					""",
				Integer.MAX_VALUE
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				PRODUCT_QUERY_PATH + "." + ResponseDescriptor.EXTRA_RESULTS.name() + "." + ExtraResultsDescriptor.FACET_SUMMARY.name() + ".brand",
				equalTo(expectedBody)
			);;
	}


	@Nonnull
	private List<SealedEntity> findEntities(@Nonnull List<SealedEntity> originalProductEntities,
	                                        @Nonnull Predicate<SealedEntity> filter,
	                                        int limit) {
		final List<SealedEntity> entities = originalProductEntities.stream()
			.filter(filter)
			.limit(limit)
			.toList();
		assertEquals(limit, entities.size());
		return entities;
	}

	@Nonnull
	private List<SealedEntity> findEntitiesWithPrice(@Nonnull List<SealedEntity> originalProductEntities, int limit) {
		return findEntities(
			originalProductEntities,
			it -> it.getPrices(CURRENCY_CZK, PRICE_LIST_BASIC).size() == 1,
			limit
		);
	}

	@Nonnull
	private List<SealedEntity> findEntitiesWithPrice(@Nonnull List<SealedEntity> originalProductEntities,
	                                                 int limit,
	                                                 @Nonnull String... priceLists) {
		return findEntities(
			originalProductEntities,
			it -> Arrays.stream(priceLists).allMatch(pl -> it.getPrices(CURRENCY_CZK, pl).size() == 1),
			limit
		);
	}

	@Nonnull
	private Map<String, Object> createBasicPageResponse(@Nonnull List<SealedEntity> entities,
	                                                    @Nonnull Function<SealedEntity, Map<String, Object>> entityMapper) {
		return map()
			.e(TYPENAME_FIELD, ResponseDescriptor.THIS.name(createEmptyEntitySchema("Product")))
			.e(ResponseDescriptor.RECORD_PAGE.name(), map()
				.e(TYPENAME_FIELD, RecordPageDescriptor.THIS.name(createEmptyEntitySchema("Product")))
				.e(RecordPageDescriptor.DATA.name(), entities.stream()
					.map(entityMapper)
					.toList())
				.build())
			.build();
	}

	@Nonnull
	private Map<String, Object> createAttributeHistogramDto(@Nonnull EvitaResponse<EntityReference> response,
	                                                        @Nonnull String attributeName) {
		final AttributeHistogram attributeHistogram = response.getExtraResult(AttributeHistogram.class);
		final HistogramContract histogram = attributeHistogram.getHistogram(attributeName);

		return map()
			.e(TYPENAME_FIELD, HistogramDescriptor.THIS.name())
			.e(HistogramDescriptor.MIN.name(), histogram.getMin().toString())
			.e(HistogramDescriptor.MAX.name(), histogram.getMax().toString())
			.e(HistogramDescriptor.OVERALL_COUNT.name(), histogram.getOverallCount())
			.e(HistogramDescriptor.BUCKETS.name(), Arrays.stream(histogram.getBuckets())
				.map(bucket -> map()
					.e(TYPENAME_FIELD, BucketDescriptor.THIS.name())
					.e(BucketDescriptor.INDEX.name(), bucket.getIndex())
					.e(BucketDescriptor.THRESHOLD.name(), bucket.getThreshold().toString())
					.e(BucketDescriptor.OCCURRENCES.name(), bucket.getOccurrences())
					.build())
				.toList())
			.build();
	}

	@Nonnull
	private Map<String, Object> createPriceHistogramDto(@Nonnull EvitaResponse<EntityReference> response) {
		final PriceHistogram priceHistogram = response.getExtraResult(PriceHistogram.class);

		return map()
			.e(TYPENAME_FIELD, HistogramDescriptor.THIS.name())
			.e(HistogramDescriptor.MIN.name(), priceHistogram.getMin().toString())
			.e(HistogramDescriptor.MAX.name(), priceHistogram.getMax().toString())
			.e(HistogramDescriptor.OVERALL_COUNT.name(), priceHistogram.getOverallCount())
			.e(HistogramDescriptor.BUCKETS.name(), Arrays.stream(priceHistogram.getBuckets())
				.map(bucket -> map()
					.e(TYPENAME_FIELD, BucketDescriptor.THIS.name())
					.e(BucketDescriptor.INDEX.name(), bucket.getIndex())
					.e(BucketDescriptor.THRESHOLD.name(), bucket.getThreshold().toString())
					.e(BucketDescriptor.OCCURRENCES.name(), bucket.getOccurrences())
					.build())
				.toList())
			.build();
	}

	@Nonnull
	private List<Map<String, Object>> createHierarchyParentsDto(@Nonnull EvitaResponse<EntityReference> response) {
		final HierarchyParents hierarchyParentsOfSelf = response.getExtraResult(HierarchyParents.class);
		final ParentsByReference categoryParents = hierarchyParentsOfSelf.ofType(Entities.CATEGORY);

		final var parentsDtos = new LinkedList<Map<String, Object>>();

		categoryParents.getParents().forEach((productId, parentsForProduct) -> {
			parentsDtos.add(
				map()
					.e(TYPENAME_FIELD, ParentsOfEntityDescriptor.THIS.name(createEmptyEntitySchema("Product"), createEmptyEntitySchema("Category")))
					.e(ParentsOfEntityDescriptor.PRIMARY_KEY.name(), productId)
					.e(ParentsOfEntityDescriptor.REFERENCES.name(), parentsForProduct.entrySet()
						.stream()
						.map(reference -> map()
							.e(TYPENAME_FIELD, ParentsOfReferenceDescriptor.THIS.name(createEmptyEntitySchema("Product"), createEmptyEntitySchema("Category")))
							.e(ParentsOfReferenceDescriptor.PRIMARY_KEY.name(), reference.getKey())
							.e(ParentsOfReferenceDescriptor.PARENT_ENTITIES.name(), Arrays.stream(reference.getValue())
								.map(parentEntity -> map()
									.e(TYPENAME_FIELD, "Category")
									.e(EntityDescriptor.PRIMARY_KEY.name(), parentEntity.getPrimaryKey())
									.e(EntityDescriptor.ATTRIBUTES.name(), map()
										.e(ATTRIBUTE_CODE, ((SealedEntity) parentEntity).getAttribute(ATTRIBUTE_CODE))
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

	@Nonnull
	private List<Map<String, Object>> createAttributeOfHierarchyParentsDto(@Nonnull EvitaResponse<EntityReference> response) {
		final HierarchyParents hierarchyParentsOfSelf = response.getExtraResult(HierarchyParents.class);
		final ParentsByReference categoryParents = hierarchyParentsOfSelf.ofType(Entities.CATEGORY);

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

	@Nonnull
	private List<Map<String, Object>> createSelfHierarchyParentsDto(@Nonnull EvitaResponse<EntityReference> response) {
		final HierarchyParents hierarchyParentsOfSelf = response.getExtraResult(HierarchyParents.class);
		final ParentsByReference selfParents = hierarchyParentsOfSelf.ofSelf();

		final var parentsDtos = new LinkedList<Map<String, Object>>();

		selfParents.getParents().forEach((productId, parentsForCategory) -> {
			parentsDtos.add(
				map()
					.e(TYPENAME_FIELD, ParentsOfEntityDescriptor.THIS.name(createEmptyEntitySchema("Category"), createEmptyEntitySchema("Category")))
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

	@Nonnull
	private List<Map<String, Object>> createHierarchyStatisticsDto(@Nonnull EvitaResponse<EntityReference> response) {
		final HierarchyStatistics hierarchyStatistics = response.getExtraResult(HierarchyStatistics.class);
		final List<LevelInfo> categoryStatistics = hierarchyStatistics.getStatistics(Entities.CATEGORY);

		final var levelInfoDtos = new LinkedList<Map<String, Object>>();

		categoryStatistics.forEach(levelInfo1 ->
			levelInfoDtos.add(
				map()
					.e(TYPENAME_FIELD, HierarchyStatisticsLevelInfoDescriptor.THIS.name(createEmptyEntitySchema("Product"), createEmptyEntitySchema("Category")))
					.e(HierarchyStatisticsLevelInfoDescriptor.CARDINALITY.name(), levelInfo1.cardinality())
					.e(HierarchyStatisticsLevelInfoDescriptor.ENTITY.name(), Optional.of(levelInfo1.entity())
						.map(parentEntity -> map()
							.e(TYPENAME_FIELD, "Category")
							.e(EntityDescriptor.PRIMARY_KEY.name(), parentEntity.getPrimaryKey())
							.e(EntityDescriptor.ATTRIBUTES.name(), map()
								.e(ATTRIBUTE_CODE, ((SealedEntity) parentEntity).getAttribute(ATTRIBUTE_CODE))
								.build())
							.build())
						.get())
					.e(HierarchyStatisticsLevelInfoDescriptor.CHILDREN_STATISTICS.name(), levelInfo1.childrenStatistics()
						.stream()
						.map(levelInfo2 -> map()
							.e(TYPENAME_FIELD, HierarchyStatisticsLevelInfoDescriptor.THIS.name(createEmptyEntitySchema("Product"), createEmptyEntitySchema("Category")))
							.e(HierarchyStatisticsLevelInfoDescriptor.CARDINALITY.name(), levelInfo2.cardinality())
							.e(HierarchyStatisticsLevelInfoDescriptor.ENTITY.name(), Optional.of(levelInfo2.entity())
								.map(parentEntity -> map()
									.e(TYPENAME_FIELD, "Category")
									.e(EntityDescriptor.PRIMARY_KEY.name(), parentEntity.getPrimaryKey())
									.e(EntityDescriptor.ATTRIBUTES.name(), map()
										.e(ATTRIBUTE_CODE, ((SealedEntity) parentEntity).getAttribute(ATTRIBUTE_CODE))
										.build())
									.build())
								.get())
							.build())
						.toList())
					.build()
			)
		);

		return levelInfoDtos;
	}

	@Nonnull
	private List<Map<String, Object>> createSelfHierarchyStatisticsDto(@Nonnull EvitaResponse<EntityReference> response) {
		final HierarchyStatistics hierarchyStatistics = response.getExtraResult(HierarchyStatistics.class);
		final List<LevelInfo> categoryStatistics = hierarchyStatistics.getSelfStatistics();

		final var levelInfoDtos = new LinkedList<Map<String, Object>>();

		categoryStatistics.forEach(levelInfo1 ->
			levelInfoDtos.add(
				map()
					.e(TYPENAME_FIELD, HierarchyStatisticsLevelInfoDescriptor.THIS.name(createEmptyEntitySchema("Category"), createEmptyEntitySchema("Category")))
					.e(HierarchyStatisticsLevelInfoDescriptor.CARDINALITY.name(), levelInfo1.cardinality())
					.e(HierarchyStatisticsLevelInfoDescriptor.ENTITY.name(), Optional.of(levelInfo1.entity())
						.map(parentEntity -> map()
							.e(TYPENAME_FIELD, "Category")
							.e(EntityDescriptor.PRIMARY_KEY.name(), parentEntity.getPrimaryKey())
							.e(EntityDescriptor.ATTRIBUTES.name(), map()
								.e(ATTRIBUTE_CODE, ((SealedEntity) parentEntity).getAttribute(ATTRIBUTE_CODE))
								.build())
							.build())
						.get())
					.e(HierarchyStatisticsLevelInfoDescriptor.CHILDREN_STATISTICS.name(), levelInfo1.childrenStatistics()
						.stream()
						.map(levelInfo2 -> map()
							.e(TYPENAME_FIELD, HierarchyStatisticsLevelInfoDescriptor.THIS.name(createEmptyEntitySchema("Category"), createEmptyEntitySchema("Category")))
							.e(HierarchyStatisticsLevelInfoDescriptor.CARDINALITY.name(), levelInfo2.cardinality())
							.e(HierarchyStatisticsLevelInfoDescriptor.ENTITY.name(), Optional.of(levelInfo2.entity())
								.map(parentEntity -> map()
									.e(TYPENAME_FIELD, "Category")
									.e(EntityDescriptor.PRIMARY_KEY.name(), parentEntity.getPrimaryKey())
									.e(EntityDescriptor.ATTRIBUTES.name(), map()
										.e(ATTRIBUTE_CODE, ((SealedEntity) parentEntity).getAttribute(ATTRIBUTE_CODE))
										.build())
									.build())
								.get())
							.build())
						.toList())
					.build()
			)
		);

		return levelInfoDtos;
	}

	@Nonnull
	private List<Map<String, Object>> createFacetSummaryWithCountsDto(@Nonnull EvitaResponse<EntityReference> response) {
		final FacetSummary facetSummary = response.getExtraResult(FacetSummary.class);

		return facetSummary.getFacetGroupStatistics()
			.stream()
			.filter(groupStatistics -> groupStatistics.getReferenceName().equals(Entities.BRAND))
			.map(groupStatistics ->
				map()
					.e(TYPENAME_FIELD, FacetGroupStatisticsDescriptor.THIS.name(createEmptyEntitySchema("Product"), createEmptyEntitySchema("Brand")))
					.e(FacetGroupStatisticsDescriptor.GROUP_ENTITY.name(), null)
					.e(FacetGroupStatisticsDescriptor.COUNT.name(), groupStatistics.getCount())
					.e(FacetGroupStatisticsDescriptor.FACET_STATISTICS.name(), groupStatistics.getFacetStatistics()
						.stream()
						.map(facetStatistics ->
							map()
								.e(TYPENAME_FIELD, FacetStatisticsDescriptor.THIS.name(createEmptyEntitySchema("Product"), createEmptyEntitySchema("Brand")))
								.e(FacetStatisticsDescriptor.FACET_ENTITY.name(), map()
									.e(TYPENAME_FIELD, "Brand")
									.e(EntityDescriptor.PRIMARY_KEY.name(), facetStatistics.facetEntity().getPrimaryKey())
									.e(EntityDescriptor.TYPE.name(), facetStatistics.facetEntity().getType())
									.build())
								.e(FacetStatisticsDescriptor.REQUESTED.name(), facetStatistics.requested())
								.e(FacetStatisticsDescriptor.COUNT.name(), facetStatistics.count())
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
					.e(FacetGroupStatisticsDescriptor.COUNT.name(), groupStatistics.getCount())
					.e(FacetGroupStatisticsDescriptor.FACET_STATISTICS.name(), groupStatistics.getFacetStatistics()
						.stream()
						.map(facetStatistics ->
							map()
								.e(FacetStatisticsDescriptor.FACET_ENTITY.name(), map()
									.e(EntityDescriptor.PRIMARY_KEY.name(), facetStatistics.facetEntity().getPrimaryKey())
									.e(EntityDescriptor.TYPE.name(), facetStatistics.facetEntity().getType())
									.e(EntityDescriptor.ATTRIBUTES.name(), map()
										.e(ATTRIBUTE_CODE, ((SealedEntity) facetStatistics.facetEntity()).getAttribute(ATTRIBUTE_CODE))
										.build())
									.build())
								.e(FacetStatisticsDescriptor.REQUESTED.name(), facetStatistics.requested())
								.e(FacetStatisticsDescriptor.COUNT.name(), facetStatistics.count())
								.e(FacetStatisticsDescriptor.IMPACT.name(), map()
									.e(TYPENAME_FIELD, FacetRequestImpactDescriptor.THIS.name())
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
