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
import io.evitadb.core.Evita;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.externalApi.api.catalog.dataApi.model.AttributesDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.EntityDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.PriceDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.ReferenceDescriptor;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.tester.GraphQLTester;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Predicate;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.externalApi.graphql.api.testSuite.TestDataGenerator.ATTRIBUTE_MARKET_SHARE;
import static io.evitadb.externalApi.graphql.api.testSuite.TestDataGenerator.GRAPHQL_THOUSAND_PRODUCTS;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.builder.MapBuilder.map;
import static io.evitadb.test.generator.DataGenerator.*;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for GraphQL catalog unknown single entity query.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class CatalogGraphQLGetUnknownEntityQueryFunctionalTest extends CatalogGraphQLDataEndpointFunctionalTest {

	private static final String GET_ENTITY_PATH = "data.getEntity";

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return unknown entity by globally unique attribute")
	void shouldReturnUnknownEntityByGloballyUniqueAttribute(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final String codeAttribute = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_CODE);
		final SealedEntity entityWithCode = findEntity(
			originalProductEntities,
			it -> Objects.equals(it.getAttribute(ATTRIBUTE_CODE), codeAttribute)
		);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    getEntity(code: "%s") {
	                        __typename
	                        primaryKey
	                        type
	                    }
	                }
					""",
				codeAttribute
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				GET_ENTITY_PATH,
				equalTo(
					map()
						.e(TYPENAME_FIELD, "Product")
						.e(EntityDescriptor.PRIMARY_KEY.name(), entityWithCode.getPrimaryKey())
						.e(EntityDescriptor.TYPE.name(), Entities.PRODUCT)
						.build()
				)
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return rich unknown entity by localized globally unique attribute")
	void shouldReturnRichUnknownEntityByLocalizedGloballyUniqueAttribute(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final String urlAttribute = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_URL, Locale.ENGLISH);
		final SealedEntity entityWithUrl = findEntity(
			originalProductEntities,
			it -> Objects.equals(it.getAttribute(ATTRIBUTE_URL, Locale.ENGLISH), urlAttribute)
		);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    getEntity(url: "%s") {
	                        primaryKey
	                        type
	                        ... on Product {
	                            attributes {
	                                __typename
	                                url
	                            }
	                        }
	                    }
	                }
					""",
				urlAttribute
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				GET_ENTITY_PATH,
				equalTo(
					map()
						.e(EntityDescriptor.PRIMARY_KEY.name(), entityWithUrl.getPrimaryKey())
						.e(EntityDescriptor.TYPE.name(), Entities.PRODUCT)
						.e(EntityDescriptor.ATTRIBUTES.name(), map()
							.e(TYPENAME_FIELD, AttributesDescriptor.THIS.name(createEmptyEntitySchema("Product")))
							.e(ATTRIBUTE_URL, urlAttribute)
							.build())
						.build()
				)
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return entity with multiple different global attributes")
	void shouldReturnUnknownEntityWithMultipleDifferentGlobalAttributes(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final String urlAttribute = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_URL, Locale.ENGLISH);
		final SealedEntity entity = findEntity(
			originalProductEntities,
			it -> Objects.equals(it.getAttribute(ATTRIBUTE_URL, Locale.ENGLISH), urlAttribute)
		);
		final String codeAttribute = entity.getAttribute(ATTRIBUTE_CODE);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    getEntity(url: "%s", code: "%s") {
	                        primaryKey
	                    }
	                }
					""",
				urlAttribute,
				codeAttribute
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				GET_ENTITY_PATH,
				equalTo(
					map()
						.e(EntityDescriptor.PRIMARY_KEY.name(), entity.getPrimaryKey())
						.build()
				)
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return entity by multiple different global attributes")
	void shouldReturnUnknownEntityByMultipleDifferentGlobalAttributes(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final String urlAttribute = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_URL, Locale.ENGLISH);
		final SealedEntity entity = findEntity(
			originalProductEntities,
			it -> Objects.equals(it.getAttribute(ATTRIBUTE_URL, Locale.ENGLISH), urlAttribute)
		);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    getEntity(url: "%s", code: "%s", join: OR) {
	                        primaryKey
	                    }
	                }
					""",
				urlAttribute,
				"somethingWhichDoesntExist"
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				GET_ENTITY_PATH,
				equalTo(
					map()
						.e(EntityDescriptor.PRIMARY_KEY.name(), entity.getPrimaryKey())
						.build()
				)
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return error for invalid argument in unknown entity query")
	void shouldReturnErrorForInvalidArgumentInUnknownEntityQuery(GraphQLTester tester) {
		tester.test(TEST_CATALOG)
			.document(
				"""
		            query {
		                getEntity(limit: 2) {
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
	@DisplayName("Should return error for invalid unknown entity fields")
	void shouldReturnErrorForInvalidUnknownEntityFields(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final String codeAttribute = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_CODE);
		tester.test(TEST_CATALOG)
			.document(
				"""
		            query {
		                getEntity(code: "%s") {
		                    primaryKey
		                    code
		                }
		            }
					""",
				codeAttribute
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, hasSize(greaterThan(0)));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return direct category parent entity references")
	void shouldReturnAllDirectCategoryParentEntityReferences(Evita evita, GraphQLTester tester) {
		final SealedEntity category = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final List<SealedEntity> categories = session.queryList(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							entityPrimaryKeyInSet(16)
						),
						require(
							entityFetch(
								hierarchyContent()
							)
						)
					),
					SealedEntity.class
				);

				assertEquals(1, categories.size());
				final SealedEntity c = categories.get(0);
				// check that it has at least 2 parents
				assertTrue(c.getParentEntity().isPresent());
				assertTrue(c.getParentEntity().get().getParentEntity().isPresent());
				return c;
			}
		);

		final var expectedBody = createEntityWithSelfParentsDto(category, false);

		tester.test(TEST_CATALOG)
			.document("""
				{
					getEntity(code: "Automotive-21") {
						... on Category {
							parentPrimaryKey
							parents {
								primaryKey
								parentPrimaryKey
							}
						}
					}
				}
				""")
			.executeAndExpectOkAndThen()
			.body(GET_ENTITY_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return direct category parent entities")
	void shouldReturnAllDirectCategoryParentEntities(Evita evita, GraphQLTester tester) {
		final SealedEntity category = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final List<SealedEntity> categories = session.queryList(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							entityPrimaryKeyInSet(16)
						),
						require(
							entityFetch(
								hierarchyContent(
									entityFetch(
										attributeContent(ATTRIBUTE_CODE)
									)
								)
							)
						)
					),
					SealedEntity.class
				);

				assertEquals(1, categories.size());
				final SealedEntity c = categories.get(0);
				// check that it has at least 2 parents
				assertTrue(c.getParentEntity().isPresent());
				assertTrue(c.getParentEntity().get().getParentEntity().isPresent());
				return c;
			}
		);

		final var expectedBody = createEntityWithSelfParentsDto(category, true);

		tester.test(TEST_CATALOG)
			.document("""
				{
					getEntity(code: "Automotive-21") {
						... on Category {
							parentPrimaryKey
							parents {
								primaryKey
								parentPrimaryKey
								allLocales
								attributes {
									code
								}
							}
						}
					}
				}
				""")
			.executeAndExpectOkAndThen()
			.body(GET_ENTITY_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return only direct category parent")
	void shouldReturnOnlyDirectCategoryParent(Evita evita, GraphQLTester tester) {
		final SealedEntity category = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final List<SealedEntity> categories = session.queryList(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							entityPrimaryKeyInSet(16)
						),
						require(
							entityFetch(
								hierarchyContent(
									stopAt(distance(1))
								)
							)
						)
					),
					SealedEntity.class
				);

				assertEquals(1, categories.size());
				final SealedEntity c = categories.get(0);
				// check that it has only one direct parent
				assertTrue(c.getParentEntity().isPresent());
				assertTrue(c.getParentEntity().get().getParentEntity().isEmpty());
				return c;
			}
		);

		final var expectedBody = createEntityWithSelfParentsDto(category, false);

		tester.test(TEST_CATALOG)
			.document("""
				{
					getEntity(code: "Automotive-21") {
						... on Category {
							parentPrimaryKey
							parents(
								stopAt: {
									distance: 1
								}
						    ) {
								primaryKey
								parentPrimaryKey
							}
						}
					}
				}
				""")
			.executeAndExpectOkAndThen()
			.body(GET_ENTITY_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return custom price for sale for single entity")
	void shouldReturnCustomPriceForSaleForSingleEntity(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final SealedEntity entity = findEntityWithPrice(originalProductEntities);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    getEntity(code: "%s") {
	                        primaryKey
	                        type
                            ... on Product {
                                priceForSale(currency: CZK, priceList: "basic") {
                                    __typename
		                            currency
		                            priceList
		                            priceWithTax
		                        }
	                        }
	                    }
	                }
					""",
				entity.getAttribute(ATTRIBUTE_CODE, String.class)
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				GET_ENTITY_PATH,
				equalTo(
					map()
						.e(EntityDescriptor.PRIMARY_KEY.name(), entity.getPrimaryKey())
						.e(EntityDescriptor.TYPE.name(), Entities.PRODUCT)
						.e(EntityDescriptor.PRICE_FOR_SALE.name(), map()
							.e(TYPENAME_FIELD, PriceDescriptor.THIS.name())
							.e(PriceDescriptor.CURRENCY.name(), CURRENCY_CZK.toString())
							.e(PriceDescriptor.PRICE_LIST.name(), PRICE_LIST_BASIC)
							.e(PriceDescriptor.PRICE_WITH_TAX.name(), entity.getPrices(CURRENCY_CZK, PRICE_LIST_BASIC).iterator().next().priceWithTax().toString())
							.build())
						.build()
				)
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return formatted price for sale with custom locale")
	void shouldReturnFormattedPriceForSaleWithCustomLocale(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final SealedEntity entity = findEntityWithPrice(originalProductEntities);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    getEntity(code: "%s") {
                            ... on Product {
                                priceForSale(currency: CZK, priceList: "basic", locale: cs_CZ) {
		                            priceWithTax(formatted: true, withCurrency: true)
		                        }
	                        }
	                    }
	                }
					""",
				entity.getAttribute(ATTRIBUTE_CODE, String.class)
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(GET_ENTITY_PATH, equalTo(createEntityDtoWithFormattedPriceForSale(entity)));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return error when formatting price for sale without locale")
	void shouldReturnErrorWhenFormattingPriceForSaleWithoutLocale(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final SealedEntity entity = findEntityWithPrice(originalProductEntities);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    getEntity(code: "%s") {
                            ... on Product {
                                priceForSale(currency: CZK, priceList: "basic") {
		                            priceWithTax(formatted: true, withCurrency: true)
		                        }
	                        }
	                    }
	                }
					""",
				entity.getAttribute(ATTRIBUTE_CODE, String.class)
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, hasSize(greaterThan(0)));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return price for single entity")
	void shouldReturnPriceForSingleEntity(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final SealedEntity entity = findEntityWithPrice(originalProductEntities);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    getEntity(code: "%s") {
	                        primaryKey
	                        type
                            ... on Product {
                                price(priceList: "basic", currency: CZK) {
                                    __typename
	                                currency
	                                priceList
	                                priceWithTax
	                            }
                            }
	                    }
	                }
					""",
				entity.getAttribute(ATTRIBUTE_CODE, String.class)
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(GET_ENTITY_PATH, equalTo(createEntityDtoWithPrice(entity)));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return formatted price with custom locale")
	void shouldReturnFormattedPriceWithCustomLocale(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final SealedEntity entity = findEntityWithPrice(originalProductEntities);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    getEntity(code: "%s") {
                            ... on Product {
                                price(priceList: "basic", currency: CZK, locale: cs_CZ) {
	                                priceWithTax(formatted: true, withCurrency: true)
	                            }
                            }
	                    }
	                }
					""",
				entity.getAttribute(ATTRIBUTE_CODE, String.class)
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(GET_ENTITY_PATH, equalTo(createEntityDtoWithFormattedPrice(entity)));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return error when formatting price without locale")
	void shouldReturnErrorWhenFormattingPriceWithoutLocale(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final SealedEntity entity = findEntityWithPrice(originalProductEntities);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    getEntity(code: "%s") {
                            ... on Product {
                                price(priceList: "basic", currency: CZK) {
	                                priceWithTax(formatted: true, withCurrency: true)
	                            }
                            }
	                    }
	                }
					""",
				entity.getAttribute(ATTRIBUTE_CODE, String.class)
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, hasSize(greaterThan(0)));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return all prices for single entity")
	void shouldReturnAllPricesForSingleEntity(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final SealedEntity entity = findEntity(
			originalProductEntities,
			it -> !it.getPrices().isEmpty()
		);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    getEntity(code: "%s") {
                            ... on Product {
	                            prices {
	                                priceWithTax
	                            }
                            }
	                    }
	                }
					""",
				entity.getAttribute(ATTRIBUTE_CODE, String.class)
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				GET_ENTITY_PATH + "." + EntityDescriptor.PRICES.name(),
				hasSize(greaterThan(0))
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return filtered prices for single entity")
	void shouldReturnFilteredPricesForSingleEntity(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final SealedEntity entity = findEntityWithPrice(originalProductEntities);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    getEntity(code: "%s") {
	                        primaryKey
	                        type
                            ... on Product {
                                prices(priceLists: "basic", currency: CZK) {
                                    __typename
	                                currency
	                                priceList
	                                priceWithTax
	                            }
                            }
	                    }
	                }
					""",
				entity.getAttribute(ATTRIBUTE_CODE, String.class)
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				GET_ENTITY_PATH,
				equalTo(
					map()
						.e(EntityDescriptor.PRIMARY_KEY.name(), entity.getPrimaryKey())
						.e(EntityDescriptor.TYPE.name(), Entities.PRODUCT)
						.e(EntityDescriptor.PRICES.name(), List.of(
							map()
								.e(TYPENAME_FIELD, PriceDescriptor.THIS.name())
								.e(PriceDescriptor.CURRENCY.name(), CURRENCY_CZK.toString())
								.e(PriceDescriptor.PRICE_LIST.name(), PRICE_LIST_BASIC)
								.e(PriceDescriptor.PRICE_WITH_TAX.name(), entity.getPrices(CURRENCY_CZK, PRICE_LIST_BASIC).iterator().next().priceWithTax().toString())
								.build()
						))
						.build()
				)
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return filtered prices for multiple price lists for single entity")
	void shouldReturnFilteredPricesForMutliplePriceListsForSingleEntity(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final SealedEntity entity = findEntityWithPrice(originalProductEntities, PRICE_LIST_BASIC, PRICE_LIST_VIP);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    getEntity(code: "%s") {
                            ... on Product {
                                prices(priceLists: ["basic", "vip"], currency: CZK) {
	                                priceWithTax
	                            }
                            }
	                    }
	                }
					""",
				entity.getAttribute(ATTRIBUTE_CODE, String.class)
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				GET_ENTITY_PATH,
				equalTo(
					map()
						.e(EntityDescriptor.PRICES.name(), List.of(
							map()
								.e(PriceDescriptor.PRICE_WITH_TAX.name(), entity.getPrices(CURRENCY_CZK, PRICE_LIST_BASIC).iterator().next().priceWithTax().toString())
								.build(),
							map()
								.e(PriceDescriptor.PRICE_WITH_TAX.name(), entity.getPrices(CURRENCY_CZK, PRICE_LIST_VIP).iterator().next().priceWithTax().toString())
								.build()
						))
						.build()
				)
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return formatted prices with custom locale")
	void shouldReturnFormattedPricesWithCustomLocale(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final SealedEntity entity = findEntityWithPrice(originalProductEntities);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    getEntity(code: "%s") {
                            ... on Product {
                                prices(priceLists: "basic", currency: CZK, locale: cs_CZ) {
	                                priceWithTax(formatted: true, withCurrency: true)
	                            }
                            }
	                    }
	                }
					""",
				entity.getAttribute(ATTRIBUTE_CODE, String.class)
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(GET_ENTITY_PATH, equalTo(createEntityDtoWithFormattedPrices(entity)));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return error when formatting prices without locale")
	void shouldReturnErrorWhenFormattingPricesWithoutLocale(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final SealedEntity entity = findEntityWithPrice(originalProductEntities);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    getEntity(code: "%s") {
                            ... on Product {
                                prices(priceLists: "basic", currency: CZK) {
	                                priceWithTax(formatted: true, withCurrency: true)
	                            }
                            }
	                    }
	                }
					""",
				entity.getAttribute(ATTRIBUTE_CODE, String.class)
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, hasSize(greaterThan(0)));
	}


	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return associated data with custom locale for single entity")
	void shouldReturnAssociatedDataWithCustomLocaleForSingleEntity(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final SealedEntity entity = findEntity(
			originalProductEntities,
			it -> it.getAssociatedData(ASSOCIATED_DATA_LABELS, Locale.ENGLISH) != null
		);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    getEntity(code: "%s") {
	                        primaryKey
	                        type
                            ... on Product {
                                associatedData(locale: en) {
                                    __typename
	                                labels
	                            }
                            }
	                    }
	                }
					""",
				entity.getAttribute(ATTRIBUTE_CODE, String.class)
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(GET_ENTITY_PATH, equalTo(createEntityDtoWithAssociatedData(entity)));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return single reference for single entity")
	void shouldReturnSingleReferenceForSingleEntity(Evita evita, GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final SealedEntity entity = findEntity(
			originalProductEntities,
			it -> it.getReferences(Entities.PARAMETER).size() == 1 &&
				it.getReferences(Entities.PARAMETER).iterator().next().getAttribute(ATTRIBUTE_MARKET_SHARE) != null
		);

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

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    getEntity(code: "%s") {
	                        primaryKey
	                        type
                            ... on Product {
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
					""",
				entity.getAttribute(ATTRIBUTE_CODE, String.class)
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				GET_ENTITY_PATH,
				equalTo(
					map()
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
						.build()
				)
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return reference list for single entity")
	void shouldReturnReferenceListForSingleEntity(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final SealedEntity entity = findEntity(
			originalProductEntities,
			it -> it.getReferences(Entities.STORE).size() > 1
		);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    getEntity(code: "%s") {
	                        primaryKey
	                        type
                            ... on Product {
                                store {
	                                referencedEntity {
	                                    primaryKey
	                                }
	                            }
                            }
	                    }
	                }
					""",
				entity.getAttribute(ATTRIBUTE_CODE, String.class)
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				GET_ENTITY_PATH + ".store." + ReferenceDescriptor.REFERENCED_ENTITY.name() + "." + EntityDescriptor.PRIMARY_KEY.name(),
				containsInAnyOrder(
					entity.getReferences(Entities.STORE)
						.stream()
						.map(ReferenceContract::getReferencedPrimaryKey)
						.toArray(Integer[]::new)
				)
			);
	}

	@Nonnull
	private SealedEntity findEntity(@Nonnull List<SealedEntity> originalProductEntities,
	                                @Nonnull Predicate<SealedEntity> filter) {
		return originalProductEntities.stream()
			.filter(filter)
			.findFirst()
			.orElseThrow(() -> new EvitaInternalError("No entity to test."));
	}

	@Nonnull
	private SealedEntity findEntityWithPrice(List<SealedEntity> originalProductEntities) {
		return findEntity(
			originalProductEntities,
			it -> it.getPrices(CURRENCY_CZK, PRICE_LIST_BASIC).size() == 1
		);
	}

	@Nonnull
	private SealedEntity findEntityWithPrice(List<SealedEntity> originalProductEntities, @Nonnull String... priceLists) {
		return findEntity(
			originalProductEntities,
			it -> Arrays.stream(priceLists).allMatch(pl -> it.getPrices(CURRENCY_CZK, pl).size() == 1)
		);
	}
}
