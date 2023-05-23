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
import io.evitadb.externalApi.api.catalog.dataApi.model.AssociatedDataDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.AttributesDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.EntityDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.PriceDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.ReferenceDescriptor;
import io.evitadb.test.tester.GraphQLTester;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.UseDataSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.api.query.QueryConstraints.entityFetch;
import static io.evitadb.api.query.QueryConstraints.hierarchyContent;
import static io.evitadb.externalApi.graphql.api.testSuite.TestDataGenerator.ATTRIBUTE_MARKET_SHARE;
import static io.evitadb.externalApi.graphql.api.testSuite.TestDataGenerator.GRAPHQL_THOUSAND_PRODUCTS;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.builder.MapBuilder.map;
import static io.evitadb.test.generator.DataGenerator.*;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for GraphQL catalog unknown entity list query.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class CatalogGraphQLListUnknownEntitiesQueryFunctionalTest extends CatalogGraphQLDataEndpointFunctionalTest {

	private static final String ENTITY_LIST_PATH = "data.listEntity";

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return unknown entity list by multiple globally unique attribute")
	void shouldReturnUnknownEntityListByMultipleGloballyUniqueAttribute(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final String codeAttribute1 = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_CODE, 5);
		final String codeAttribute2 = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_CODE, 7);
		final SealedEntity entityWithCode1 = originalProductEntities.stream()
			.filter(it -> Objects.equals(it.getAttribute(ATTRIBUTE_CODE), codeAttribute1))
			.findFirst()
			.orElseThrow(() -> new EvitaInternalError("Missing entity with code attribute"));
		final SealedEntity entityWithCode2 = originalProductEntities.stream()
			.filter(it -> Objects.equals(it.getAttribute(ATTRIBUTE_CODE), codeAttribute2))
			.findFirst()
			.orElseThrow(() -> new EvitaInternalError("Missing entity with code attribute"));

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    listEntity(code: ["%s","%s"]) {
	                        __typename
	                        primaryKey
	                        type
	                    }
	                }
					""",
				codeAttribute1,
				codeAttribute2
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				ENTITY_LIST_PATH,
				equalTo(
					List.of(
						map()
							.e(TYPENAME_FIELD, "Product")
							.e(EntityDescriptor.PRIMARY_KEY.name(), entityWithCode1.getPrimaryKey())
							.e(EntityDescriptor.TYPE.name(), Entities.PRODUCT)
							.build(),
						map()
							.e(TYPENAME_FIELD, "Product")
							.e(EntityDescriptor.PRIMARY_KEY.name(), entityWithCode2.getPrimaryKey())
							.e(EntityDescriptor.TYPE.name(), Entities.PRODUCT)
							.build()
					)
				)
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return rich unknown entity list by multiple localized globally unique attribute")
	void shouldReturnRichUnknownEntityListByMultipleLocalizedGloballyUniqueAttribute(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final String urlAttribute1 = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_URL, Locale.ENGLISH, 5);
		final String urlAttribute2 = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_URL, Locale.ENGLISH, 7);
		final SealedEntity entityWithUrl1 = originalProductEntities.stream()
			.filter(it -> Objects.equals(it.getAttribute(ATTRIBUTE_URL, Locale.ENGLISH), urlAttribute1) &&
				it.getAttribute(ATTRIBUTE_NAME, Locale.ENGLISH) != null)
			.findFirst()
			.orElseThrow(() -> new EvitaInternalError("Missing entity with url attribute"));
		final SealedEntity entityWithUrl2 = originalProductEntities.stream()
			.filter(it -> Objects.equals(it.getAttribute(ATTRIBUTE_URL, Locale.ENGLISH), urlAttribute2) &&
				it.getAttribute(ATTRIBUTE_NAME, Locale.ENGLISH) != null)
			.findFirst()
			.orElseThrow(() -> new EvitaInternalError("Missing entity with url attribute"));

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    listEntity(url: ["%s","%s"]) {
	                        primaryKey
	                        type
	                        ... on Product {
	                            attributes {
	                                __typename
	                                url
	                                name
                                }
	                        }
	                    }
	                }
					""",
				urlAttribute1,
				urlAttribute2
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				ENTITY_LIST_PATH,
				equalTo(
					List.of(
						map()
							.e(EntityDescriptor.PRIMARY_KEY.name(), entityWithUrl1.getPrimaryKey())
							.e(EntityDescriptor.TYPE.name(), Entities.PRODUCT)
							.e(EntityDescriptor.ATTRIBUTES.name(), map()
								.e(TYPENAME_FIELD, AttributesDescriptor.THIS.name(createEmptyEntitySchema("Product")))
								.e(ATTRIBUTE_URL, urlAttribute1)
								.e(ATTRIBUTE_NAME, entityWithUrl1.getAttribute(ATTRIBUTE_NAME, Locale.ENGLISH))
								.build())
							.build(),
						map()
							.e(EntityDescriptor.PRIMARY_KEY.name(), entityWithUrl2.getPrimaryKey())
							.e(EntityDescriptor.TYPE.name(), Entities.PRODUCT)
							.e(EntityDescriptor.ATTRIBUTES.name(), map()
								.e(TYPENAME_FIELD, AttributesDescriptor.THIS.name(createEmptyEntitySchema("Product")))
								.e(ATTRIBUTE_URL, urlAttribute2)
								.e(ATTRIBUTE_NAME, entityWithUrl2.getAttribute(ATTRIBUTE_NAME, Locale.ENGLISH))
								.build())
							.build()
					)
				)
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return error for invalid argument in unknown entity list query")
	void shouldReturnErrorForInvalidArgumentInUnknownEntityListQuery(GraphQLTester tester) {
		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    listEntity(primaryKey: 1, 2) {
	                        primaryKey
	                        type
	                        ... on Product {
	                            attributes {
	                                code
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
	@DisplayName("Should return rich unknown entity list by multiple globally unique attribute")
	void shouldReturnErrorForInvalidUnknownEntityListFields(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final String codeAttribute1 = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_CODE, 5);
		final String codeAttribute2 = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_CODE, 7);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    listEntity(code: ["%s","%s"]) {
	                        primaryKey
	                        code
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

		final List<Map<String, Object>> expectedBody = List.of(
			createEntityWithSelfParentsDto(category, false)
		);

		tester.test(TEST_CATALOG)
			.document("""
				{
					listEntity(code: "Automotive-21") {
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
			.body(ENTITY_LIST_PATH, equalTo(expectedBody));
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

		final List<Map<String, Object>> expectedBody = List.of(
			createEntityWithSelfParentsDto(category, true)
		);

		tester.test(TEST_CATALOG)
			.document("""
				{
					listEntity(code: "Automotive-21") {
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
			.body(ENTITY_LIST_PATH, equalTo(expectedBody));
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

		final List<Map<String, Object>> expectedBody = List.of(
			createEntityWithSelfParentsDto(category, false)
		);

		tester.test(TEST_CATALOG)
			.document("""
				{
					listEntity(code: "Automotive-21") {
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
			.body(ENTITY_LIST_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return custom price for sale for products")
	void shouldReturnCustomPriceForSaleForEntities(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final List<SealedEntity> entities = findEntitiesWithPrice(originalProductEntities);

		final List<Map<String,Object>> expectedBody = entities.stream()
			.map(entity ->
				map()
					.e(EntityDescriptor.PRIMARY_KEY.name(), entity.getPrimaryKey())
					.e(EntityDescriptor.TYPE.name(), Entities.PRODUCT)
					.e(EntityDescriptor.PRICE_FOR_SALE.name(), map()
						.e(TYPENAME_FIELD, PriceDescriptor.THIS.name())
						.e(PriceDescriptor.CURRENCY.name(), CURRENCY_CZK.toString())
						.e(PriceDescriptor.PRICE_LIST.name(), PRICE_LIST_BASIC)
						.e(PriceDescriptor.PRICE_WITH_TAX.name(), entity.getPrices(CURRENCY_CZK, PRICE_LIST_BASIC).iterator().next().getPriceWithTax().toString())
						.build())
					.build()
			)
			.toList();

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    listEntity(
	                        code: ["%s", "%s"]
	                    ) {
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
				entities.get(0).getAttribute(ATTRIBUTE_CODE),
				entities.get(1).getAttribute(ATTRIBUTE_CODE)
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(ENTITY_LIST_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return price for entities")
	void shouldReturnPriceForEntities(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final List<SealedEntity> entities = findEntitiesWithPrice(originalProductEntities);

		final List<Map<String, Object>> expectedBody = entities.stream()
			.map(entity ->
				map()
					.e(EntityDescriptor.PRIMARY_KEY.name(), entity.getPrimaryKey())
					.e(EntityDescriptor.TYPE.name(), Entities.PRODUCT)
					.e(EntityDescriptor.PRICE.name(), map()
						.e(TYPENAME_FIELD, PriceDescriptor.THIS.name())
						.e(PriceDescriptor.CURRENCY.name(), CURRENCY_CZK.toString())
						.e(PriceDescriptor.PRICE_LIST.name(), PRICE_LIST_BASIC)
						.e(PriceDescriptor.PRICE_WITH_TAX.name(), entity.getPrices(CURRENCY_CZK, PRICE_LIST_BASIC).iterator().next().getPriceWithTax().toString())
						.build())
					.build()
			)
			.toList();

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    listEntity(
	                        code: ["%s", "%s"]
	                    ) {
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
				entities.get(0).getAttribute(ATTRIBUTE_CODE),
				entities.get(1).getAttribute(ATTRIBUTE_CODE)
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(ENTITY_LIST_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return all prices for entities")
	void shouldReturnAllPricesForEntities(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final List<SealedEntity> entities = findEntities(
			originalProductEntities,
			it -> !it.getPrices().isEmpty()
		);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    listEntity(
	                        code: ["%s", "%s"]
	                    ) {
	                        primaryKey
	                        type
                            ... on Product {
                                prices {
	                                priceWithTax
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
			.body(ENTITY_LIST_PATH, hasSize(2))
			.body(ENTITY_LIST_PATH + "[0]." + EntityDescriptor.PRICES.name(), hasSize(greaterThan(0)))
			.body(ENTITY_LIST_PATH + "[1]." + EntityDescriptor.PRICES.name(), hasSize(greaterThan(0)));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return filtered prices for entities")
	void shouldReturnFilteredPricesForEntities(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
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

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    listEntity(
		                    code: ["%s", "%s"]
	                    ) {
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
				entities.get(0).getAttribute(ATTRIBUTE_CODE),
				entities.get(1).getAttribute(ATTRIBUTE_CODE)
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(ENTITY_LIST_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return filtered prices for multiple price lists for entities")
	void shouldReturnFilteredPricesForMultiplePriceListsForEntities(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
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

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    listEntity(
		                    code: ["%s", "%s"]
	                    ) {
                            ... on Product {
                                prices(priceLists: ["basic","vip"], currency: CZK) {
	                                priceWithTax
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
			.body(ENTITY_LIST_PATH, equalTo(expectedBody));
	}


	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return associated data with custom locale for entities")
	void shouldReturnAssociatedDataWithCustomLocaleForEntities(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final var entities = findEntities(
			originalProductEntities,
			it -> it.getAssociatedData(ASSOCIATED_DATA_LABELS, Locale.ENGLISH) != null
		);

		final var expectedBody = entities.stream()
			.map(entity ->
				map()
					.e(EntityDescriptor.PRIMARY_KEY.name(), entity.getPrimaryKey())
					.e(EntityDescriptor.TYPE.name(), Entities.PRODUCT)
					.e(EntityDescriptor.ASSOCIATED_DATA.name(), map()
						.e(TYPENAME_FIELD, AssociatedDataDescriptor.THIS.name(createEmptyEntitySchema("Product")))
						.e(ASSOCIATED_DATA_LABELS, map()
							.build())
						.build())
					.build()
			)
			.toList();

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    listEntity(
	                        code: ["%s", "%s"]
	                    ) {
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
				entities.get(0).getAttribute(ATTRIBUTE_CODE),
				entities.get(1).getAttribute(ATTRIBUTE_CODE)
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(ENTITY_LIST_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return single reference for entities")
	void shouldReturnSingleReferenceForEntities(Evita evita, GraphQLTester tester, List<SealedEntity> originalProductEntities) {
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

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    listEntity(
	                        code: ["%s", "%s"]
	                    ) {
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
				entities.get(0).getAttribute(ATTRIBUTE_CODE),
				entities.get(1).getAttribute(ATTRIBUTE_CODE)
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(ENTITY_LIST_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return reference list for entities")
	void shouldReturnReferenceListForEntities(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
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

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    listEntity(
	                        code: ["%s", "%s"]
	                    ) {
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
				entities.get(0).getAttribute(ATTRIBUTE_CODE),
				entities.get(1).getAttribute(ATTRIBUTE_CODE)
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(ENTITY_LIST_PATH, equalTo(expectedBody));
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
			it -> it.getPrices(CURRENCY_CZK, "basic").size() == 1
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
