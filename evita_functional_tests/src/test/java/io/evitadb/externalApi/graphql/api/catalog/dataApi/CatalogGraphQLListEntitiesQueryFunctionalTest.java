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

import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.api.catalog.dataApi.model.AttributesDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.EntityDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.PriceDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.ReferenceDescriptor;
import io.evitadb.externalApi.graphql.api.testSuite.GraphQLTester;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.UseDataSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.text.NumberFormat;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.function.Predicate;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.not;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.api.query.order.OrderDirection.DESC;
import static io.evitadb.externalApi.graphql.api.testSuite.TestDataGenerator.ATTRIBUTE_CREATED;
import static io.evitadb.externalApi.graphql.api.testSuite.TestDataGenerator.ATTRIBUTE_MANUFACTURED;
import static io.evitadb.externalApi.graphql.api.testSuite.TestDataGenerator.ATTRIBUTE_MARKET_SHARE;
import static io.evitadb.externalApi.graphql.api.testSuite.TestDataGenerator.GRAPHQL_THOUSAND_PRODUCTS;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.builder.MapBuilder.map;
import static io.evitadb.test.generator.DataGenerator.*;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for GraphQL catalog entity list query.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class CatalogGraphQLListEntitiesQueryFunctionalTest extends CatalogGraphQLDataEndpointFunctionalTest {

	private static final int SEED = 40;

	private static final String PRODUCT_LIST_PATH = "data.list_product";
	private static final String ERRORS_PATH = "errors";

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return products by primary key")
	void shouldReturnProductsByPrimaryKey(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final var entities = findEntities(
			originalProductEntities,
			it -> it.getAttribute(ATTRIBUTE_CODE) != null
		);

		final var expectedBody = entities.stream()
			.map(entity ->
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
			)
			.toList();

		tester.test()
			.document(
				"""
	                query {
	                    list_product(
	                        filterBy: {
	                            entity_primaryKey_inSet: [%d, %d]
	                        }
	                    ) {
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
					""",
				entities.get(0).getPrimaryKey(),
				entities.get(1).getPrimaryKey()
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(PRODUCT_LIST_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return products by non-localized attribute")
	void shouldReturnProductsByNonLocalizedAttribute(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
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
					.e(EntityDescriptor.ATTRIBUTES.name(), map()
						.e(ATTRIBUTE_CODE, entity.getAttribute(ATTRIBUTE_CODE, String.class))
						.e(ATTRIBUTE_NAME, entity.getAttribute(ATTRIBUTE_NAME, Locale.ENGLISH))
						.build())
					.build()
			)
			.toList();

		tester.test()
			.document(
				"""
	                query {
	                    list_product(
	                        filterBy: {
	                            attribute_code_inSet: ["%s", "%s"]
	                        }
	                    ) {
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
					""",
				entities.get(0).getAttribute(ATTRIBUTE_CODE),
				entities.get(1).getAttribute(ATTRIBUTE_CODE)
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(PRODUCT_LIST_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return error when formatted big decimal is missing locale")
	void shouldReturnErrorWhenFormattedBigDecimalIsMissingLocale(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final var entities = findEntities(
			originalProductEntities,
			it -> it.getAttribute(ATTRIBUTE_QUANTITY) != null
		);

		tester.test()
			.document(
				"""
	                query {
	                    list_product(
	                        filterBy: {
	                            attribute_code_inSet: ["%s", "%s"]
	                        }
                        ) {
	                        primaryKey
	                        type
                            attributes {
                                quantity(formatted: true)
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
			it -> it.getAttribute(ATTRIBUTE_QUANTITY) != null
		);

		final var expectedBody = entities.stream()
			.map(entity ->
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
			)
			.toList();

		tester.test()
			.document(
				"""
	                query {
	                    list_product(
	                        filterBy: {
	                            attribute_code_inSet: ["%s", "%s"],
	                            entity_locale_equals: cs_CZ
	                        }
	                    ) {
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
					""",
				entities.get(0).getAttribute(ATTRIBUTE_CODE),
				entities.get(1).getAttribute(ATTRIBUTE_CODE)
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(PRODUCT_LIST_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return products by localized attribute")
	void shouldReturnProductsByLocalizedAttribute(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
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
					.e(EntityDescriptor.ATTRIBUTES.name(), map()
						.e(ATTRIBUTE_URL, entity.getAttribute(ATTRIBUTE_URL, Locale.ENGLISH))
						.e(ATTRIBUTE_NAME, entity.getAttribute(ATTRIBUTE_NAME, Locale.ENGLISH))
						.build())
					.build()
			)
			.toList();

		tester.test()
			.document(
				"""
	                query {
	                    list_product(
	                        filterBy: {
	                            attribute_url_inSet: ["%s", "%s"]
	                            entity_locale_equals: en
	                        }
	                    ) {
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
					""",
				entities.get(0).getAttribute(ATTRIBUTE_URL, Locale.ENGLISH),
				entities.get(1).getAttribute(ATTRIBUTE_URL, Locale.ENGLISH)
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(PRODUCT_LIST_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return error for invalid products fields")
	void shouldReturnErrorForInvalidProductsFields(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final String codeAttribute1 = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_CODE, 2);
		final String codeAttribute2 = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_CODE, 5);
		tester.test()
			.document(
				"""
	                query {
	                    list_product(
	                        filterBy: {
	                            attribute_code_inSet: ["%s", "%s"]
	                        }
	                    ) {
	                        primaryKey
	                        type
                            relatedData
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
		tester.test()
			.document(
				"""
	                query {
	                    list_product(code: "product") {
	                        primaryKey
	                        type
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
		final List<SealedEntity> entities = findEntitiesWithPrice(originalProductEntities);

		final List<Map<String, Object>> expectedBody = entities.stream()
			.map(this::createEntityDtoWithPriceForSale)
			.toList();

		tester.test()
			.document(
				"""
	                query {
	                    list_product(
	                        filterBy: {
		                        attribute_code_inSet: ["%s", "%s"]
		                        price_inCurrency: CZK,
		                        price_inPriceLists: "basic"
	                        }
                        ) {
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
					""",
				entities.get(0).getAttribute(ATTRIBUTE_CODE),
				entities.get(1).getAttribute(ATTRIBUTE_CODE)
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(PRODUCT_LIST_PATH, equalTo(expectedBody));
	}


	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should filter products by non-existent price")
	void shouldFilterProductsByNonExistentPrice(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final List<SealedEntity> entities = findEntitiesWithPrice(originalProductEntities);

		tester.test()
			.document(
				"""
	                query {
	                    list_product(
	                        filterBy: {
	                            attribute_code_inSet: ["%s", "%s"]
		                        price_inCurrency: CZK,
		                        price_inPriceLists: "nonexistent"
	                        }
                        ) {
	                        primaryKey
	                        type
	                    }
	                }
					""",
				entities.get(0).getAttribute(ATTRIBUTE_CODE),
				entities.get(1).getAttribute(ATTRIBUTE_CODE)
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(PRODUCT_LIST_PATH, hasSize(0));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return error for filtering products by unknown currency")
	void shouldReturnErrorForFilteringProductsByUnknownCurrency(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final List<SealedEntity> entities = findEntitiesWithPrice(originalProductEntities);

		tester.test()
			.document(
				"""
	                query {
	                    list_product(
	                        filterBy: {
	                            attribute_code_inSet: ["%s", "%s"]
		                        price_inCurrency: AAA,
		                        price_inPriceLists: "basic"
	                        }
                        ) {
	                        primaryKey
	                        type
	                    }
	                }
					""",
				entities.get(0).getAttribute(ATTRIBUTE_CODE),
				entities.get(1).getAttribute(ATTRIBUTE_CODE)
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, hasSize(greaterThan(0)))
			.body(PRODUCT_LIST_PATH, nullValue());
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return custom price for sale for products")
	void shouldReturnCustomPriceForSaleForProducts(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final List<SealedEntity> entities = findEntitiesWithPrice(originalProductEntities);

		final List<Map<String,Object>> expectedBody = entities.stream()
			.map(this::createEntityDtoWithPriceForSale)
			.toList();

		tester.test()
			.document(
				"""
	                query {
	                    list_product(
	                        filterBy: {
	                            entity_primaryKey_inSet: [%d, %d]
	                        }
	                    ) {
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
					""",
				entities.get(0).getPrimaryKey(),
				entities.get(1).getPrimaryKey()
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(PRODUCT_LIST_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return formatted price for sale by entity locale")
	void shouldReturnFormattedPriceForSaleByEntityLocale(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final List<SealedEntity> entities = findEntitiesWithPrice(originalProductEntities);

		final List<Map<String, Object>> expectedBody = entities.stream()
			.map(this::createEntityDtoWithFormattedPriceForSale)
			.toList();

		tester.test()
			.document(
				"""
	                query {
	                    list_product(
	                        filterBy: {
		                        attribute_code_inSet: ["%s", "%s"]
		                        price_inCurrency: CZK,
		                        price_inPriceLists: "basic",
		                        entity_locale_equals: cs_CZ
	                        }
                        ) {
	                        priceForSale(locale: cs_CZ) {
                                priceWithTax(formatted: true, withCurrency: true)
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
			.body(PRODUCT_LIST_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return formatted price for sale by custom locale")
	void shouldReturnFormattedPriceForSaleByCustomLocale(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final List<SealedEntity> entities = findEntitiesWithPrice(originalProductEntities);

		final List<Map<String, Object>> expectedBody = entities.stream()
			.map(this::createEntityDtoWithFormattedPriceForSale)
			.toList();

		tester.test()
			.document(
				"""
	                query {
	                    list_product(
	                        filterBy: {
		                        attribute_code_inSet: ["%s", "%s"]
		                        price_inCurrency: CZK,
		                        price_inPriceLists: "basic"
	                        }
                        ) {
                            priceForSale(locale: cs_CZ) {
                                priceWithTax(formatted: true, withCurrency: true)
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
			.body(PRODUCT_LIST_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return error when formatting price for sale without locale")
	void shouldReturnErrorWhenFormattingPriceForSaleWithoutLocale(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final List<SealedEntity> entities = findEntitiesWithPrice(originalProductEntities);

		tester.test()
			.document(
				"""
	                query {
	                    list_product(
	                        filterBy: {
		                        attribute_code_inSet: ["%s", "%s"]
		                        price_inCurrency: CZK,
		                        price_inPriceLists: "basic"
	                        }
                        ) {
                            priceForSale {
                                priceWithTax(formatted: true, withCurrency: true)
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
		final List<SealedEntity> entities = findEntitiesWithPrice(originalProductEntities);

		final List<Map<String, Object>> expectedBody = entities.stream()
			.map(this::createEntityDtoWithPrice)
			.toList();

		tester.test()
			.document(
				"""
	                query {
	                    list_product(
	                        filterBy: {
	                            attribute_code_inSet: ["%s", "%s"]
	                            price_inCurrency: CZK
	                            price_inPriceLists: "basic"
	                        }
                        ) {
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
					""",
				entities.get(0).getAttribute(ATTRIBUTE_CODE),
				entities.get(1).getAttribute(ATTRIBUTE_CODE)
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(PRODUCT_LIST_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return price for products")
	void shouldReturnPriceForProducts(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final List<SealedEntity> entities = findEntitiesWithPrice(originalProductEntities);

		final List<Map<String, Object>> expectedBody = entities.stream()
			.map(this::createEntityDtoWithPrice)
			.toList();

		tester.test()
			.document(
				"""
	                query {
	                    list_product(
	                        filterBy: {
	                            attribute_code_inSet: ["%s", "%s"]
	                        }
	                    ) {
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
					""",
				entities.get(0).getAttribute(ATTRIBUTE_CODE),
				entities.get(1).getAttribute(ATTRIBUTE_CODE)
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(PRODUCT_LIST_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return formatted price with entity locale")
	void shoudReturnFormattedPriceWithEntityLocale(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final List<SealedEntity> entities = findEntitiesWithPrice(originalProductEntities);

		final List<Map<String, Object>> expectedBody = entities.stream()
			.map(this::createEntityDtoWithFormattedPrice)
			.toList();

		tester.test()
			.document(
				"""
	                query {
	                    list_product(
	                        filterBy: {
	                            attribute_code_inSet: ["%s", "%s"],
	                            entity_locale_equals: cs_CZ
	                        }
	                    ) {
                            price(priceList: "basic", currency: CZK) {
                                priceWithTax(formatted: true, withCurrency: true)
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
			.body(PRODUCT_LIST_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return error when formatting price without locale")
	void shoudReturnErrorWhenFormattingPriceWithoutLocale(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final List<SealedEntity> entities = findEntitiesWithPrice(originalProductEntities);

		tester.test()
			.document(
				"""
	                query {
	                    list_product(
	                        filterBy: {
	                            attribute_code_inSet: ["%s", "%s"]
	                        }
	                    ) {
	                        primaryKey
	                        type
                            price(priceList: "basic", currency: CZK) {
                                priceWithTax(formatted: true, withCurrency: true)
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
			it -> !it.getPrices().isEmpty()
		);

		tester.test()
			.document(
				"""
	                query {
	                    list_product(
	                        filterBy: {
	                            attribute_code_inSet: ["%s", "%s"]
	                        }
	                    ) {
	                        primaryKey
	                        type
                            prices {
                                priceWithTax
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
			.body(PRODUCT_LIST_PATH, hasSize(2))
			.body(PRODUCT_LIST_PATH + "[0]." + EntityDescriptor.PRICES.name(), hasSize(greaterThan(0)))
			.body(PRODUCT_LIST_PATH + "[1]." + EntityDescriptor.PRICES.name(), hasSize(greaterThan(0)));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return filtered prices for products")
	void shouldReturnFilteredPricesForProducts(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final List<SealedEntity> entities = findEntitiesWithPrice(originalProductEntities);

		final List<Map<String, Object>> expectedBody = entities.stream()
			.map(entity ->
				map()
					.e(EntityDescriptor.PRIMARY_KEY.name(), entity.getPrimaryKey())
					.e(EntityDescriptor.TYPE.name(), Entities.PRODUCT)
					.e(EntityDescriptor.PRICES.name(), List.of(
						map()
							.e(TYPENAME_FIELD, PriceDescriptor.THIS.name())
							.e(PriceDescriptor.CURRENCY.name(), CURRENCY_CZK.toString())
							.e(PriceDescriptor.PRICE_LIST.name(), PRICE_LIST_BASIC)
							.e(PriceDescriptor.PRICE_WITH_TAX.name(), entity.getPrices(CURRENCY_CZK, PRICE_LIST_BASIC).iterator().next().getPriceWithTax().toString())
							.build()
					))
					.build()
			)
			.toList();

		tester.test()
			.document(
				"""
	                query {
	                    list_product(
		                    filterBy: {
		                        entity_primaryKey_inSet: [%d, %d]
		                    }
	                    ) {
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
					""",
				entities.get(0).getPrimaryKey(),
				entities.get(1).getPrimaryKey()
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(PRODUCT_LIST_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return filtered prices for multiple price lists for products")
	void shouldReturnFilteredPricesForMultiplePriceListsForProducts(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final List<SealedEntity> entities = findEntitiesWithPrice(originalProductEntities, PRICE_LIST_BASIC, PRICE_LIST_VIP);

		final List<Map<String, Object>> expectedBody = entities.stream()
			.map(entity ->
				map()
					.e(EntityDescriptor.PRICES.name(), List.of(
						map()
							.e(PriceDescriptor.PRICE_WITH_TAX.name(), entity.getPrices(CURRENCY_CZK, PRICE_LIST_BASIC).iterator().next().getPriceWithTax().toString())
							.build(),
						map()
							.e(PriceDescriptor.PRICE_WITH_TAX.name(), entity.getPrices(CURRENCY_CZK, PRICE_LIST_VIP).iterator().next().getPriceWithTax().toString())
							.build()
					))
					.build()
			)
			.toList();

		tester.test()
			.document(
				"""
	                query {
	                    list_product(
		                    filterBy: {
		                        entity_primaryKey_inSet: [%d, %d]
		                    }
	                    ) {
                            prices(priceLists: ["basic", "vip"], currency: CZK) {
                                priceWithTax
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
			.body(PRODUCT_LIST_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return formatted prices with entity locale")
	void shouldReturnFormattedPricesWithEntityLocale(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final List<SealedEntity> entities = findEntitiesWithPrice(originalProductEntities);

		final List<Map<String, Object>> expectedBody = entities.stream()
			.map(this::createEntityDtoWithFormattedPrices)
			.toList();

		tester.test()
			.document(
				"""
	                query {
	                    list_product(
		                    filterBy: {
		                        entity_primaryKey_inSet: [%d, %d],
		                        entity_locale_equals: cs_CZ
		                    }
	                    ) {
                            prices(priceLists: "basic", currency: CZK) {
                                priceWithTax(formatted: true, withCurrency: true)
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
			.body(PRODUCT_LIST_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return formatted prices with entity locale")
	void shouldReturnFormattedPricesWithCustomLocale(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final List<SealedEntity> entities = findEntitiesWithPrice(originalProductEntities);

		final List<Map<String, Object>> expectedBody = entities.stream()
			.map(this::createEntityDtoWithFormattedPrices)
			.toList();

		tester.test()
			.document(
				"""
	                query {
	                    list_product(
		                    filterBy: {
		                        entity_primaryKey_inSet: [%d, %d]
		                    }
	                    ) {
                            prices(priceLists: "basic", currency: CZK, locale: cs_CZ) {
                                priceWithTax(formatted: true, withCurrency: true)
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
			.body(PRODUCT_LIST_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return error when formatting prices without locale")
	void shouldReturnErrorWhenFormattingPricesWithoutLocale(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final List<SealedEntity> entities = findEntitiesWithPrice(originalProductEntities);

		final List<Map<String, Object>> expectedBody = entities.stream()
			.map(this::createEntityDtoWithFormattedPrices)
			.toList();

		tester.test()
			.document(
				"""
	                query {
	                    list_product(
		                    filterBy: {
		                        entity_primaryKey_inSet: [%d, %d]
		                    }
	                    ) {
	                        primaryKey
	                        type
                            prices(priceLists: "basic", currency: CZK) {
                                priceWithTax(formatted: true, withCurrency: true)
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
				it.getAllLocales().contains(Locale.ENGLISH)
		);

		final var expectedBody = entities.stream()
			.map(this::createEntityDtoWithAssociatedData)
			.toList();

		tester.test()
			.document(
				"""
	                query {
	                    list_product(
	                        filterBy: {
	                            attribute_code_inSet: ["%s", "%s"]
	                            entity_locale_equals: en
	                        }
                        ) {
	                        primaryKey
	                        type
                            associatedData {
                                __typename
                                labels
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
			.body(PRODUCT_LIST_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return associated data with custom locale for products")
	void shouldReturnAssociatedDataWithCustomLocaleForProducts(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final var entities = findEntities(
			originalProductEntities,
			it -> it.getAssociatedData(ASSOCIATED_DATA_LABELS, Locale.ENGLISH) != null
		);

		final var expectedBody = entities.stream()
			.map(this::createEntityDtoWithAssociatedData)
			.toList();

		tester.test()
			.document(
				"""
	                query {
	                    list_product(
	                        filterBy: {
	                            attribute_code_inSet: ["%s", "%s"]
	                        }
	                    ) {
	                        primaryKey
	                        type
                            associatedData(locale: en) {
                                __typename
                                labels
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
			.body(PRODUCT_LIST_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return single reference for products")
	void shouldReturnSingleReferenceForProducts(Evita evita, GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final var entities = findEntities(
			originalProductEntities,
			it -> it.getReferences(Entities.PARAMETER).size() == 1 &&
				it.getReferences(Entities.PARAMETER).iterator().next().getAttribute(ATTRIBUTE_MARKET_SHARE) != null
		);

		final var expectedBody = entities.stream()
			.map(entity -> {
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
			})
			.toList();

		tester.test()
			.document(
				"""
	                query {
	                    list_product(
	                        filterBy: {
	                            attribute_code_inSet: ["%s", "%s"]
	                        }
	                    ) {
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
					""",
				entities.get(0).getAttribute(ATTRIBUTE_CODE),
				entities.get(1).getAttribute(ATTRIBUTE_CODE)
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(PRODUCT_LIST_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return reference list for products")
	void shouldReturnReferenceListForProducts(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final var entities = findEntities(
			originalProductEntities,
			it -> it.getReferences(Entities.STORE).size() > 1
		);

		final var expectedBody = entities.stream()
			.map(entity -> {
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
			})
			.toList();

		tester.test()
			.document(
				"""
	                query {
	                    list_product(
	                        filterBy: {
	                            attribute_code_inSet: ["%s", "%s"]
	                        }
	                    ) {
	                        primaryKey
	                        type
                            store {
                                referencedEntity {
                                    primaryKey
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
			.body(PRODUCT_LIST_PATH, equalTo(expectedBody));
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

		tester.test()
			.document(
				"""
	                query {
	                    list_product(
	                        filterBy: {
	                            or: [
	                                {
	                                    attribute_alias_equals: %b
	                                    attribute_priority_equals: "%s"
	                                },
	                                {
	                                    attribute_alias_equals: %b
	                                    attribute_priority_equals: "%s"
	                                },
	                                {
	                                    attribute_alias_equals: false
	                                    attribute_priority_inSet: ["%s", "%s", "%s", "%s"]
	                                }
	                            ]
	                            not: {
	                                attribute_code_equals: "%s"
	                            }
	                        }
	                        limit: %d
	                    ) {
	                        primaryKey
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
			.body(PRODUCT_LIST_PATH + "." + EntityDescriptor.PRIMARY_KEY.name(), contains(expectedEntities));
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

		tester.test()
			.document(
				"""
		            query {
		                list_product(
		                    filterBy: {
		                        attribute_priority_lessThan: "35000"
		                    }
		                    orderBy: {
		                        attribute_created_natural: DESC,
		                        attribute_manufactured_natural: ASC
		                    }
		                    limit: 30
		                ) {
		                    primaryKey
		                }
		            }
					"""
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(PRODUCT_LIST_PATH + "." + EntityDescriptor.PRIMARY_KEY.name(), contains(expectedEntities));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should limit returned entities")
	void shouldLimitReturnedEntities(Evita evita, GraphQLTester tester) {
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

		tester.test()
			.document(
				"""
		            query {
		                list_product(
		                    filterBy: {
		                        attribute_priority_lessThan: "35000"
		                    }
		                    limit: 5
		                ) {
		                    primaryKey
		                }
		            }
					"""
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(PRODUCT_LIST_PATH + "." + EntityDescriptor.PRIMARY_KEY.name(), contains(expectedEntities.stream().limit(5).toArray(Integer[]::new)));
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
	private List<SealedEntity> findEntitiesWithPrice(List<SealedEntity> originalProductEntities, @Nonnull String... priceLists) {
		return findEntities(
			originalProductEntities,
			it -> Arrays.stream(priceLists).allMatch(pl -> it.getPrices(CURRENCY_CZK, pl).size() == 1)
		);
	}
}
