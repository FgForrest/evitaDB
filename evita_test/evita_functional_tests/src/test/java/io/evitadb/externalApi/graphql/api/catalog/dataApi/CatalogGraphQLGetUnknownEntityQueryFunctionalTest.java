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

package io.evitadb.externalApi.graphql.api.catalog.dataApi;

import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.core.Evita;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.externalApi.api.catalog.dataApi.model.AttributesDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.AttributesProviderDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.EntityDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.PriceDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.ReferenceDescriptor;
import io.evitadb.externalApi.api.catalog.model.VersionedDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.GlobalEntityDescriptor;
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
import static io.evitadb.externalApi.graphql.api.testSuite.TestDataGenerator.ATTRIBUTE_RELATIVE_URL;
import static io.evitadb.externalApi.graphql.api.testSuite.TestDataGenerator.GRAPHQL_HUNDRED_ARCHIVED_PRODUCTS_WITH_ARCHIVE;
import static io.evitadb.externalApi.graphql.api.testSuite.TestDataGenerator.GRAPHQL_THOUSAND_PRODUCTS;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.generator.DataGenerator.*;
import static io.evitadb.utils.MapBuilder.map;
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
	void shouldReturnUnknownEntityByGloballyUniqueAttribute(Evita evita, GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final String codeAttribute = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_CODE);

		final EntityClassifier entity = getEntity(
			evita,
			query(
				filterBy(attributeEquals(ATTRIBUTE_CODE, codeAttribute))
			)
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
						.e(TYPENAME_FIELD, GlobalEntityDescriptor.THIS.name())
						.e(EntityDescriptor.PRIMARY_KEY.name(), entity.getPrimaryKey())
						.e(EntityDescriptor.TYPE.name(), Entities.PRODUCT)
						.build()
				)
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return unknown entity by globally unique locale specific attribute without specifying collection")
	void shouldReturnUnknownEntityByGloballyUniqueLocaleSpecificAttributeWithoutSpecifyingCollection(Evita evita, GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final AttributeValue relativeUrl = getRandomAttributeValueObject(originalProductEntities, ATTRIBUTE_RELATIVE_URL);

		final EntityClassifier entity = getEntity(
			evita,
			query(
				filterBy(
					attributeEquals(ATTRIBUTE_RELATIVE_URL, relativeUrl.value()),
					entityLocaleEquals(relativeUrl.key().locale())
				)
			)
		);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    getEntity(relativeUrl: "%s", locale: %s) {
	                        __typename
	                        primaryKey
	                        type
	                        attributes {
	                            relativeUrl
	                        }
	                    }
	                }
					""",
				relativeUrl.value(),
				relativeUrl.key().locale().toString()
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				GET_ENTITY_PATH,
				equalTo(
					map()
						.e(TYPENAME_FIELD, GlobalEntityDescriptor.THIS.name())
						.e(EntityDescriptor.PRIMARY_KEY.name(), entity.getPrimaryKey())
						.e(EntityDescriptor.TYPE.name(), Entities.PRODUCT)
						.e(
							AttributesProviderDescriptor.ATTRIBUTES.name(), map()
							.e(ATTRIBUTE_RELATIVE_URL, relativeUrl.value())
							.build())
						.build()
				)
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return error when filtering by globally unique attribute without locale")
	void shouldReturnErrorWhenFilteringByGloballyUniqueLocalSpecificAttributeWithoutLocale(Evita evita, GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final AttributeValue relativeUrl = getRandomAttributeValueObject(originalProductEntities, ATTRIBUTE_RELATIVE_URL);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    getEntity(relativeUrl: "%s") {
	                        __typename
	                        primaryKey
	                        type
	                    }
	                }
					""",
				relativeUrl.value()
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, hasSize(greaterThan(0)));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return version with entity")
	void shouldReturnVersionWithEntity(Evita evita, GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final String codeAttribute = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_CODE);

		final SealedEntity entity = getEntity(
			evita,
			query(
				filterBy(attributeEquals(ATTRIBUTE_CODE, codeAttribute)),
				require(page(1, 1), entityFetch())
			),
			SealedEntity.class
		);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    getEntity(code: "%s") {
	                        primaryKey
	                        type
	                        version
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
						.e(EntityDescriptor.PRIMARY_KEY.name(), entity.getPrimaryKey())
						.e(EntityDescriptor.TYPE.name(), Entities.PRODUCT)
						.e(VersionedDescriptor.VERSION.name(), entity.version())
						.build()
				)
			);
	}

	@Test
	@UseDataSet(GRAPHQL_HUNDRED_ARCHIVED_PRODUCTS_WITH_ARCHIVE)
	@DisplayName("Should return archived entity")
	void shouldReturnArchivedEntity(Evita evita, GraphQLTester tester) {
		final SealedEntity archivedEntity = getEntity(
			evita,
			query(
				collection(Entities.PRODUCT),
				filterBy(
					scope(Scope.ARCHIVED)
				),
				require(
					page(1, 1),
					entityFetch(attributeContent(ATTRIBUTE_CODE))
				)
			),
			SealedEntity.class
		);

		final var expectedBodyOfArchivedEntity = map()
			.e(EntityDescriptor.PRIMARY_KEY.name(), archivedEntity.getPrimaryKey())
			.e(EntityDescriptor.SCOPE.name(), Scope.ARCHIVED.name())
			.build();

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    getEntity(
	                        code: "%s"
	                        scope: ARCHIVED
	                    ) {
                            primaryKey
                            scope
	                    }
	                }
					""",
				(String) archivedEntity.getAttribute(ATTRIBUTE_CODE)
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(GET_ENTITY_PATH, equalTo(expectedBodyOfArchivedEntity));
	}


	@Test
	@UseDataSet(GRAPHQL_HUNDRED_ARCHIVED_PRODUCTS_WITH_ARCHIVE)
	@DisplayName("Should not return archived entity without scope")
	void shouldNotReturnArchivedEntityWithoutScope(Evita evita, GraphQLTester tester) {
		final SealedEntity archivedEntity = getEntity(
			evita,
			query(
				collection(Entities.PRODUCT),
				filterBy(
					scope(Scope.ARCHIVED)
				),
				require(
					page(1, 1),
					entityFetch(attributeContent(ATTRIBUTE_CODE))
				)
			),
			SealedEntity.class
		);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    listEntity(
	                        code: ["%s"]
                        ) {
                            primaryKey
	                        scope
	                    }
	                }
					""",
				(String) archivedEntity.getAttribute(ATTRIBUTE_CODE)
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(GET_ENTITY_PATH, nullValue());
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return rich unknown entity by localized globally unique attribute")
	void shouldReturnRichUnknownEntityByLocalizedGloballyUniqueAttribute(Evita evita, GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final String urlAttribute = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_URL, Locale.ENGLISH);

		final SealedEntity entity = getEntity(
			evita,
			query(
				filterBy(
					attributeEquals(ATTRIBUTE_URL, urlAttribute),
					entityLocaleEquals(Locale.ENGLISH)
				),
				require(
					entityFetch()
				)
			),
			SealedEntity.class
		);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    getEntity(url: "%s") {
	                        primaryKey
	                        type
	                        locales
	                        allLocales
                            attributes {
                                __typename
                                url
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
						.e(EntityDescriptor.PRIMARY_KEY.name(), entity.getPrimaryKey())
						.e(EntityDescriptor.TYPE.name(), Entities.PRODUCT)
						.e(EntityDescriptor.LOCALES.name(), List.of(Locale.ENGLISH.toString()))
						.e(EntityDescriptor.ALL_LOCALES.name(), entity.getAllLocales().stream().filter(Objects::nonNull).map(Locale::toString).toList())
						.e(
							AttributesProviderDescriptor.ATTRIBUTES.name(), map()
							.e(TYPENAME_FIELD, AttributesDescriptor.THIS_GLOBAL.name())
							.e(ATTRIBUTE_URL, urlAttribute)
							.build())
						.build()
				)
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return localized rich unknown entity by non-localized globally unique attribute")
	void shouldReturnLocalizedRichUnknownEntityByNonLocalizedGloballyUniqueAttribute(Evita evita, GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final String codeAttribute = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_CODE);

		final SealedEntity entity = getEntity(
			evita,
			query(
				filterBy(
					attributeEquals(ATTRIBUTE_CODE, codeAttribute),
					entityLocaleEquals(Locale.ENGLISH),
					attributeIsNotNull(ATTRIBUTE_URL)
				),
				require(
					entityFetch(
						attributeContent(ATTRIBUTE_CODE, ATTRIBUTE_URL)
					)
				)
			),
			SealedEntity.class
		);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    getEntity(code: "%s") {
	                        primaryKey
	                        type
	                        locales
	                        allLocales
                            attributes(locale: en) {
                                __typename
                                code
                                url
                            }
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
						.e(EntityDescriptor.PRIMARY_KEY.name(), entity.getPrimaryKey())
						.e(EntityDescriptor.TYPE.name(), Entities.PRODUCT)
						.e(EntityDescriptor.LOCALES.name(), List.of(Locale.ENGLISH.toString()))
						.e(EntityDescriptor.ALL_LOCALES.name(), entity.getAllLocales().stream().filter(Objects::nonNull).map(Locale::toString).toList())
						.e(
							AttributesProviderDescriptor.ATTRIBUTES.name(), map()
							.e(TYPENAME_FIELD, AttributesDescriptor.THIS_GLOBAL.name())
							.e(ATTRIBUTE_CODE, codeAttribute)
							.e(ATTRIBUTE_URL, entity.getAttribute(ATTRIBUTE_URL, Locale.ENGLISH))
							.build())
						.build()
				)
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return entity with multiple different global attributes")
	void shouldReturnUnknownEntityWithMultipleDifferentGlobalAttributes(Evita evita, GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final String urlAttribute = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_URL, Locale.ENGLISH);

		final SealedEntity entity = getEntity(
			evita,
			query(
				filterBy(
					attributeEquals(ATTRIBUTE_URL, urlAttribute),
					entityLocaleEquals(Locale.ENGLISH),
					attributeIsNotNull(ATTRIBUTE_CODE)
				),
				require(
					entityFetch(
						attributeContent(ATTRIBUTE_CODE, ATTRIBUTE_URL)
					)
				)
			),
			SealedEntity.class
		);

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
				entity.getAttribute(ATTRIBUTE_CODE)
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
	void shouldReturnUnknownEntityByMultipleDifferentGlobalAttributes(Evita evita, GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final String urlAttribute = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_URL, Locale.ENGLISH);

		final SealedEntity entity = getEntity(
			evita,
			query(
				filterBy(
					attributeEquals(ATTRIBUTE_URL, urlAttribute),
					entityLocaleEquals(Locale.ENGLISH)
				),
				require(
					entityFetch()
				)
			),
			SealedEntity.class
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
	void shouldReturnTargetEntityWithAllDirectCategoryParentEntityReferences(Evita evita, GraphQLTester tester) {
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

		final var expectedBody = createTargetEntityDto(createEntityWithSelfParentsDto(category, false));

		tester.test(TEST_CATALOG)
			.document("""
				{
					getEntity(code: "Automotive-21") {
						targetEntity {
							... on Category {
								parentPrimaryKey
								parents {
									primaryKey
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
	@DisplayName("Should return direct category parent entities")
	void shouldReturnTargetEntityWithAllDirectCategoryParentEntities(Evita evita, GraphQLTester tester) {
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

		final var expectedBody = createTargetEntityDto(createEntityWithSelfParentsDto(category, true));

		tester.test(TEST_CATALOG)
			.document("""
				{
					getEntity(code: "Automotive-21") {
						targetEntity {
							... on Category {
								parentPrimaryKey
								parents {
									primaryKey
									allLocales
									attributes {
										code
									}
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
	void shouldReturnTargetEntityWithOnlyDirectCategoryParent(Evita evita, GraphQLTester tester) {
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

		final var expectedBody = createTargetEntityDto(createEntityWithSelfParentsDto(category, false));

		tester.test(TEST_CATALOG)
			.document("""
				{
					getEntity(code: "Automotive-21") {
						targetEntity {
							... on Category {
								parentPrimaryKey
								parents(
									stopAt: {
										distance: 1
									}
							    ) {
									primaryKey
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
	@DisplayName("Should return custom price for sale for single entity")
	void shouldReturnTargetEntityWithCustomPriceForSaleForSingleEntity(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final SealedEntity entity = findEntityWithPrice(originalProductEntities);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    getEntity(code: "%s") {
	                        primaryKey
	                        type
	                        targetEntity {
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
	                }
					""",
				entity.getAttribute(ATTRIBUTE_CODE, String.class)
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				GET_ENTITY_PATH,
				equalTo(createTargetEntityDto(createEntityDtoWithPriceForSale(entity), true))
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return formatted price for sale with custom locale")
	void shouldReturnTargetEntityWithFormattedPriceForSaleWithCustomLocale(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final SealedEntity entity = findEntityWithPrice(originalProductEntities);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    getEntity(code: "%s") {
	                        targetEntity {
	                            ... on Product {
	                                priceForSale(currency: CZK, priceList: "basic", locale: cs_CZ) {
			                            priceWithTax(formatted: true, withCurrency: true)
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
			.body(GET_ENTITY_PATH, equalTo(createTargetEntityDto(createEntityDtoWithFormattedPriceForSale(entity))));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return error when formatting price for sale without locale")
	void shouldReturnTargetEntityWithErrorWhenFormattingPriceForSaleWithoutLocale(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final SealedEntity entity = findEntityWithPrice(originalProductEntities);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    getEntity(code: "%s") {
	                        targetEntity {
	                            ... on Product {
	                                priceForSale(currency: CZK, priceList: "basic") {
			                            priceWithTax(formatted: true, withCurrency: true)
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
			.body(ERRORS_PATH, hasSize(greaterThan(0)));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return price for single entity")
	void shouldReturnTargetEntityWithPriceForSingleEntity(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final SealedEntity entity = findEntityWithPrice(originalProductEntities);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    getEntity(code: "%s") {
	                        primaryKey
	                        type
	                        targetEntity {
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
	                }
					""",
				entity.getAttribute(ATTRIBUTE_CODE, String.class)
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(GET_ENTITY_PATH, equalTo(createTargetEntityDto(createEntityDtoWithPrice(entity), true)));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return formatted price with custom locale")
	void shouldReturnTargetEntityWithFormattedPriceWithCustomLocale(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final SealedEntity entity = findEntityWithPrice(originalProductEntities);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    getEntity(code: "%s") {
	                        targetEntity {
	                            ... on Product {
	                                price(priceList: "basic", currency: CZK, locale: cs_CZ) {
		                                priceWithTax(formatted: true, withCurrency: true)
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
			.body(GET_ENTITY_PATH, equalTo(createTargetEntityDto(createEntityDtoWithFormattedPrice(entity))));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return error when formatting price without locale")
	void shouldReturnTargetEntityWithErrorWhenFormattingPriceWithoutLocale(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final SealedEntity entity = findEntityWithPrice(originalProductEntities);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    getEntity(code: "%s") {
	                        targetEntity {
	                            ... on Product {
	                                price(priceList: "basic", currency: CZK) {
		                                priceWithTax(formatted: true, withCurrency: true)
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
			.body(ERRORS_PATH, hasSize(greaterThan(0)));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return all prices for single entity")
	void shouldReturnTargetEntityWithAllPricesForSingleEntity(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final SealedEntity entity = findEntity(
			originalProductEntities,
			it -> !it.getPrices().isEmpty()
		);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    getEntity(code: "%s") {
	                        targetEntity {
	                            ... on Product {
		                            prices {
		                                priceWithTax
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
				resultPath(GET_ENTITY_PATH, GlobalEntityDescriptor.TARGET_ENTITY, EntityDescriptor.PRICES),
				hasSize(greaterThan(0))
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return filtered prices for single entity")
	void shouldReturnTargetEntityWithFilteredPricesForSingleEntity(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final SealedEntity entity = findEntityWithPrice(originalProductEntities);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    getEntity(code: "%s") {
	                        primaryKey
	                        type
	                        targetEntity {
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
	                }
					""",
				entity.getAttribute(ATTRIBUTE_CODE, String.class)
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				GET_ENTITY_PATH,
				equalTo(createTargetEntityDto(createEntityDtoWithPrices(entity), true))
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return filtered prices for multiple price lists for single entity")
	void shouldReturnTargetEntityWithFilteredPricesForMutliplePriceListsForSingleEntity(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final SealedEntity entity = findEntityWithPrice(originalProductEntities, PRICE_LIST_BASIC, PRICE_LIST_VIP);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    getEntity(code: "%s") {
	                        targetEntity {
	                            ... on Product {
	                                prices(priceLists: ["basic", "vip"], currency: CZK) {
		                                priceWithTax
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
						.e(GlobalEntityDescriptor.TARGET_ENTITY.name(), map()
							.e(EntityDescriptor.PRICES.name(), List.of(
								map()
									.e(PriceDescriptor.PRICE_WITH_TAX.name(), entity.getPrices(CURRENCY_CZK, PRICE_LIST_BASIC).iterator().next().priceWithTax().toString())
									.build(),
								map()
									.e(PriceDescriptor.PRICE_WITH_TAX.name(), entity.getPrices(CURRENCY_CZK, PRICE_LIST_VIP).iterator().next().priceWithTax().toString())
									.build()
							)))
						.build()
				)
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return formatted prices with custom locale")
	void shouldReturnTargetEntityWithFormattedPricesWithCustomLocale(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final SealedEntity entity = findEntityWithPrice(originalProductEntities);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    getEntity(code: "%s") {
	                        targetEntity {
	                            ... on Product {
	                                prices(priceLists: "basic", currency: CZK, locale: cs_CZ) {
		                                priceWithTax(formatted: true, withCurrency: true)
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
			.body(GET_ENTITY_PATH, equalTo(createTargetEntityDto(createEntityDtoWithFormattedPrices(entity))));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return error when formatting prices without locale")
	void shouldReturnTargetEntityWithErrorWhenFormattingPricesWithoutLocale(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final SealedEntity entity = findEntityWithPrice(originalProductEntities);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    getEntity(code: "%s") {
	                        targetEntity {
	                            ... on Product {
	                                prices(priceLists: "basic", currency: CZK) {
		                                priceWithTax(formatted: true, withCurrency: true)
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
			.body(ERRORS_PATH, hasSize(greaterThan(0)));
	}


	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return associated data with custom locale for single entity")
	void shouldReturnTargetEntityWithAssociatedDataWithCustomLocaleForSingleEntity(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
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
	                        targetEntity {
	                            ... on Product {
	                                associatedData(locale: en) {
	                                    __typename
		                                labels
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
			.body(GET_ENTITY_PATH, equalTo(createTargetEntityDto(createEntityDtoWithAssociatedData(entity), true)));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return single reference for single entity")
	void shouldReturnTargetEntityWithSingleReferenceForSingleEntity(Evita evita, GraphQLTester tester, List<SealedEntity> originalProductEntities) {
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
	                        targetEntity {
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
						.e(GlobalEntityDescriptor.TARGET_ENTITY.name(), map()
							.e("parameter", map()
								.e(TYPENAME_FIELD, ReferenceDescriptor.THIS.name(createEmptyEntitySchema("Product"), createEmptyEntitySchema("Parameter")))
								.e(
									AttributesProviderDescriptor.ATTRIBUTES.name(), map()
									.e(TYPENAME_FIELD, AttributesDescriptor.THIS.name(createEmptyEntitySchema("Product"), createEmptyEntitySchema("Parameter")))
									.e(ATTRIBUTE_MARKET_SHARE, reference.getAttribute(ATTRIBUTE_MARKET_SHARE).toString()))
								.e(ReferenceDescriptor.REFERENCED_ENTITY.name(), map()
									.e(TYPENAME_FIELD, "Parameter")
									.e(EntityDescriptor.PRIMARY_KEY.name(), reference.getReferencedPrimaryKey())
									.e(EntityDescriptor.TYPE.name(), reference.getReferencedEntityType())
									.e(
										AttributesProviderDescriptor.ATTRIBUTES.name(), map()
										.e(ATTRIBUTE_CODE, referencedEntity.getAttribute(ATTRIBUTE_CODE))))
								.e(ReferenceDescriptor.GROUP_ENTITY.name(), map()
									.e(TYPENAME_FIELD, "ParameterGroup")
									.e(EntityDescriptor.PRIMARY_KEY.name(), reference.getGroup().get().getPrimaryKey())
									.e(EntityDescriptor.TYPE.name(), reference.getGroup().get().getType())
									.e(
										AttributesProviderDescriptor.ATTRIBUTES.name(), map()
										.e(ATTRIBUTE_CODE, groupEntity.getAttribute(ATTRIBUTE_CODE))))))
						.build()
				)
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return reference list for single entity")
	void shouldReturnTargetEntityWithReferenceListForSingleEntity(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final SealedEntity entity = findEntity(
			originalProductEntities,
			it -> it.getReferences(Entities.STORE).size() > 1
		);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    getEntity(code: "%s") {
	                        targetEntity {
	                            ... on Product {
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
				entity.getAttribute(ATTRIBUTE_CODE, String.class)
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				resultPath(GET_ENTITY_PATH, GlobalEntityDescriptor.TARGET_ENTITY, "store", ReferenceDescriptor.REFERENCED_ENTITY, EntityDescriptor.PRIMARY_KEY),
				containsInAnyOrder(
					entity.getReferences(Entities.STORE)
						.stream()
						.map(ReferenceContract::getReferencedPrimaryKey)
						.toArray(Integer[]::new)
				)
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should pass query labels")
	void shouldPassQueryLabels(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final String codeAttribute = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_CODE);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    getEntity(
	                        labels: [
	                            {
							        name: "myLabel1"
							        value: "myValue1"
								},
								{
							        name: "myLabel2"
							        value: 100
								}
	                        ]
	                        code: "%s"
	                    ) {
	                        primaryKey
	                    }
	                }
					""",
				codeAttribute
			)
			.executeAndExpectOkAndThen()
			.body(GET_ENTITY_PATH, notNullValue());
	}

	@Nonnull
	private static SealedEntity findEntity(@Nonnull List<SealedEntity> originalProductEntities,
	                                       @Nonnull Predicate<SealedEntity> filter) {
		return originalProductEntities.stream()
			.filter(filter)
			.findFirst()
			.orElseThrow(() -> new GenericEvitaInternalError("No entity to test."));
	}

	@Nonnull
	private static SealedEntity findEntityWithPrice(List<SealedEntity> originalProductEntities) {
		return findEntity(
			originalProductEntities,
			it -> it.getPrices(CURRENCY_CZK, PRICE_LIST_BASIC).size() == 1
		);
	}

	@Nonnull
	private static SealedEntity findEntityWithPrice(List<SealedEntity> originalProductEntities, @Nonnull String... priceLists) {
		return findEntity(
			originalProductEntities,
			it -> Arrays.stream(priceLists).allMatch(pl -> it.getPrices(CURRENCY_CZK, pl).size() == 1)
		);
	}


}
