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

import io.evitadb.api.query.require.PriceContentMode;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.PricesContract.AccompanyingPrice;
import io.evitadb.api.requestResponse.data.PricesContract.PriceForSaleWithAccompanyingPrices;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.core.Evita;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.externalApi.api.catalog.dataApi.model.AttributesDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.AttributesProviderDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.DataChunkDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.EntityDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.PriceDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.ReferenceDescriptor;
import io.evitadb.externalApi.api.catalog.model.VersionedDescriptor;
import io.evitadb.externalApi.graphql.GraphQLProvider;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.entity.PriceForSaleDescriptor;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.DataCarrier;
import io.evitadb.test.tester.GraphQLTester;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.text.NumberFormat;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.externalApi.graphql.api.testSuite.TestDataGenerator.ATTRIBUTE_MARKET_SHARE;
import static io.evitadb.externalApi.graphql.api.testSuite.TestDataGenerator.ATTRIBUTE_STORE_VISIBLE_FOR_B2C;
import static io.evitadb.externalApi.graphql.api.testSuite.TestDataGenerator.GRAPHQL_HUNDRED_ARCHIVED_PRODUCTS_WITH_ARCHIVE;
import static io.evitadb.externalApi.graphql.api.testSuite.TestDataGenerator.GRAPHQL_THOUSAND_PRODUCTS;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.generator.DataGenerator.*;
import static io.evitadb.utils.MapBuilder.map;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for GraphQL catalog single entity query.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class CatalogGraphQLGetEntityQueryFunctionalTest extends CatalogGraphQLDataEndpointFunctionalTest {

	private static final String GET_PRODUCT_PATH = "data.getProduct";
	private static final String GET_CATEGORY_PATH = "data.getCategory";

	private static final String GRAPHQL_THOUSAND_PRODUCTS_FOR_EMPTY_GET = GRAPHQL_THOUSAND_PRODUCTS + "forEmptyGet";

	@DataSet(value = GRAPHQL_THOUSAND_PRODUCTS_FOR_EMPTY_GET, openWebApi = GraphQLProvider.CODE, readOnly = false, destroyAfterClass = true)
	protected DataCarrier setUpForEmptyGet(Evita evita) {
		return super.setUpData(evita, 0, false);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return single product by primary key")
	void shouldReturnSingleProductByPrimaryKey(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final SealedEntity entity = findEntity(
			originalProductEntities,
			it -> it.getAttribute(ATTRIBUTE_CODE) != null
		);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    getProduct(primaryKey: %d) {
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
				entity.getPrimaryKey()
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				GET_PRODUCT_PATH,
				equalTo(
					map()
						.e(TYPENAME_FIELD, "Product")
						.e(EntityDescriptor.PRIMARY_KEY.name(), entity.getPrimaryKey())
						.e(EntityDescriptor.TYPE.name(), Entities.PRODUCT)
						.e(EntityDescriptor.LOCALES.name(), List.of())
						.e(EntityDescriptor.ALL_LOCALES.name(), List.of(CZECH_LOCALE.toString(), Locale.ENGLISH.toString()))
						.e(
							AttributesProviderDescriptor.ATTRIBUTES.name(), map()
							.e(TYPENAME_FIELD, AttributesDescriptor.THIS.name(createEmptyEntitySchema("Product")))
							.e(ATTRIBUTE_CODE, entity.getAttribute(ATTRIBUTE_CODE, String.class))
							.build())
						.build()
				)
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return version for product")
	void shouldReturnVersionForProduct(Evita evita, GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final SealedEntity entity = getEntity(
			evita,
			query(
				collection(Entities.PRODUCT),
				filterBy(attributeIsNotNull(ATTRIBUTE_CODE)),
				require(page(1, 1), entityFetch())
			),
			SealedEntity.class
		);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    getProduct(primaryKey: %d) {
	                        primaryKey
	                        type
	                        version
	                    }
	                }
					""",
				entity.getPrimaryKey()
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				GET_PRODUCT_PATH,
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
					entityFetch()
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
	                    getProduct(
	                        primaryKey: %d
	                        scope: ARCHIVED
	                    ) {
                            primaryKey
	                        scope
	                    }
	                }
					""",
				archivedEntity.getPrimaryKey()
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(GET_PRODUCT_PATH, equalTo(expectedBodyOfArchivedEntity));
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
					entityFetch()
				)
			),
			SealedEntity.class
		);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    getProduct(primaryKey: %d) {
                            primaryKey
	                        scope
	                    }
	                }
					""",
				archivedEntity.getPrimaryKey()
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(GET_PRODUCT_PATH, nullValue());
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return single product by non-localized attribute")
	void shouldReturnSingleProductByNonLocalizedAttribute(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final SealedEntity entity = findEntity(
			originalProductEntities,
			it -> it.getAttribute(ATTRIBUTE_CODE) != null &&
				it.getAttribute(ATTRIBUTE_NAME, CZECH_LOCALE) != null &&
				it.getAllLocales().contains(CZECH_LOCALE) &&
				it.getAllLocales().contains(Locale.ENGLISH)
		);
		final String codeAttribute = entity.getAttribute(ATTRIBUTE_CODE);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    getProduct(code: "%s") {
	                        primaryKey
	                        type
	                        locales
	                        allLocales
                            attributes(locale: cs_CZ) {
                                code
                                name
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
				GET_PRODUCT_PATH,
				equalTo(
					map()
						.e(EntityDescriptor.PRIMARY_KEY.name(), entity.getPrimaryKey())
						.e(EntityDescriptor.TYPE.name(), Entities.PRODUCT)
						.e(EntityDescriptor.LOCALES.name(), List.of(CZECH_LOCALE.toString()))
						.e(EntityDescriptor.ALL_LOCALES.name(), List.of(CZECH_LOCALE.toString(), Locale.ENGLISH.toString()))
						.e(
							AttributesProviderDescriptor.ATTRIBUTES.name(), map()
							.e(ATTRIBUTE_CODE, codeAttribute)
							.e(ATTRIBUTE_NAME, entity.getAttribute(ATTRIBUTE_NAME, CZECH_LOCALE))
							.build())
						.build()
				)
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return error when formatted big decimal is missing locale")
	void shouldReturnErrorWhenFormattedBigDecimalIsMissingLocale(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final SealedEntity entity = findEntity(
			originalProductEntities,
			it -> it.getAttribute(ATTRIBUTE_QUANTITY) != null
		);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    getProduct(code: "%s") {
	                        primaryKey
	                        type
                            attributes {
                                quantity(formatted: true)
                            }
	                    }
	                }
					""",
				entity.getAttribute(ATTRIBUTE_CODE, String.class)
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, notNullValue());
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return big decimal attribute variants for single product")
	void shouldReturnBigDecimalAttributeVariantsForSingleProduct(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final SealedEntity entity = findEntity(
			originalProductEntities,
			it -> it.getAttribute(ATTRIBUTE_QUANTITY) != null
		);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    getProduct(code: "%s", locale: cs_CZ) {
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
				entity.getAttribute(ATTRIBUTE_CODE, String.class)
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				GET_PRODUCT_PATH,
				equalTo(
					map()
						.e(EntityDescriptor.PRIMARY_KEY.name(), entity.getPrimaryKey())
						.e(EntityDescriptor.TYPE.name(), Entities.PRODUCT)
						.e(
							AttributesProviderDescriptor.ATTRIBUTES.name(), map()
							.e(ATTRIBUTE_QUANTITY, entity.getAttribute(ATTRIBUTE_QUANTITY).toString())
							.e("formattedQuantity", NumberFormat.getNumberInstance(CZECH_LOCALE).format(entity.getAttribute(ATTRIBUTE_QUANTITY)))
							.build())
						.e("enAttributes", map()
							.e("enFormattedQuantity", NumberFormat.getNumberInstance(Locale.ENGLISH).format(entity.getAttribute(ATTRIBUTE_QUANTITY)))
							.build())
						.build()
				)
			);
	}

	@Test
	@UseDataSet(value = GRAPHQL_THOUSAND_PRODUCTS_FOR_EMPTY_GET, destroyAfterTest = true)
	@DisplayName("Should return empty attributes and associated data for missing locale")
	void shouldReturnEmptyAttributesAndAssociatedDataForMissingLocale(Evita evita, GraphQLTester tester) {
		// insert new entity without locale
		final int primaryKey = insertMinimalEmptyProduct(tester);

		// verify that GQL can return null on `attributes`/`associatedData` field for missing locale even though
		// inner data may be non-nullable
		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    getProduct(primaryKey: %d) {
	                        primaryKey
	                        attributes(locale: en) {
                                name
                                code
                            },
                            associatedData(locale: en) {
								labels
                            }
	                    }
	                }
					""",
				primaryKey
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				GET_PRODUCT_PATH,
				equalTo(
					map()
						.e(EntityDescriptor.PRIMARY_KEY.name(), primaryKey)
						.e(AttributesProviderDescriptor.ATTRIBUTES.name(), null)
						.e(EntityDescriptor.ASSOCIATED_DATA.name(), null)
						.build()
				)
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return single product by localized attribute")
	void shouldReturnSingleProductByLocalizedAttribute(Evita evita, GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final String urlAttribute = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_URL, Locale.ENGLISH);
		final SealedEntity entityWithUrl = findEntity(
			originalProductEntities,
			it -> Objects.equals(it.getAttribute(ATTRIBUTE_URL, Locale.ENGLISH), urlAttribute)
		);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    getProduct(url: "%s") {
	                        primaryKey
	                        type
	                        locales
	                        allLocales
                            attributes {
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
				GET_PRODUCT_PATH,
				equalTo(
					map()
						.e(EntityDescriptor.PRIMARY_KEY.name(), entityWithUrl.getPrimaryKey())
						.e(EntityDescriptor.TYPE.name(), Entities.PRODUCT)
						.e(EntityDescriptor.LOCALES.name(), List.of(Locale.ENGLISH.toString()))
						.e(EntityDescriptor.ALL_LOCALES.name(), List.of(CZECH_LOCALE.toString(), Locale.ENGLISH.toString()))
						.e(
							AttributesProviderDescriptor.ATTRIBUTES.name(), map()
							.e(ATTRIBUTE_URL, urlAttribute)
							.build())
						.build()
				)
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return error for invalid single product fields")
	void shouldReturnErrorForInvalidSingleProductFields(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final String codeAttribute = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_CODE);
		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    getProduct(code: "%s") {
	                        primaryKey
	                        type
                            relatedData
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
				return session.queryOneSealedEntity(
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
					)
				).orElseThrow();
			}
		);
		// check that it has at least 2 parents
		assertTrue(category.getParentEntity().isPresent());
		assertTrue(category.getParentEntity().get().getParentEntity().isPresent());

		final Map<String, Object> expectedBody = createEntityWithSelfParentsDto(category, false);

		tester.test(TEST_CATALOG)
			.document("""
				{
					getCategory(primaryKey: 16) {
						parentPrimaryKey
						parents {
							primaryKey
						}
					}
				}
				""")
			.executeAndExpectOkAndThen()
			.body(GET_CATEGORY_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return direct category parent entities")
	void shouldReturnAllDirectCategoryParentEntities(Evita evita, GraphQLTester tester) {
		final SealedEntity category = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.queryOneSealedEntity(
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
					)
				).orElseThrow();
			}
		);
		// check that it has at least 2 parents
		assertTrue(category.getParentEntity().isPresent());
		assertTrue(category.getParentEntity().get().getParentEntity().isPresent());

		final Map<String, Object> expectedBody = createEntityWithSelfParentsDto(category, true);

		tester.test(TEST_CATALOG)
			.document("""
				{
					getCategory(primaryKey: 16) {
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
				""")
			.executeAndExpectOkAndThen()
			.body(GET_CATEGORY_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return only direct category parent")
	void shouldReturnOnlyDirectCategoryParent(Evita evita, GraphQLTester tester) {
		final SealedEntity category = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.queryOneSealedEntity(
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
					)
				).orElseThrow();
			}
		);
		// check that it has only one direct parent
		assertTrue(category.getParentEntity().isPresent());
		assertTrue(category.getParentEntity().get().getParentEntity().isEmpty());

		final Map<String, Object> expectedBody = createEntityWithSelfParentsDto(category, false);

		tester.test(TEST_CATALOG)
			.document("""
				{
					getCategory(primaryKey: 16) {
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
				""")
			.executeAndExpectOkAndThen()
			.body(GET_CATEGORY_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return all direct product parent entity references")
	void shouldReturnAllDirectProductParentEntityReferences(Evita evita, GraphQLTester tester) {
		final SealedEntity product = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.queryOneSealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							hierarchyWithin(
								Entities.CATEGORY,
								entityPrimaryKeyInSet(26)
							)
						),
						require(
							page(1, 1),
							entityFetch(
								referenceContent(
									Entities.CATEGORY,
									entityFetch(
										hierarchyContent()
									)
								)
							)
						)
					)
				).orElseThrow();
			}
		);
		// check that it has at least 2 referenced parents
		assertTrue(product.getReferences(Entities.CATEGORY)
			.iterator()
			.next()
			.getReferencedEntity()
			.orElseThrow()
			.getParentEntity()
			.get()
			.getParentEntity()
			.isPresent());

		final Map<String, Object> expectedBody = createEntityWithReferencedParentsDto(product, Entities.CATEGORY, false);

		tester.test(TEST_CATALOG)
			.document("""
				{
					getProduct(primaryKey: %d) {
						category {
							referencedEntity {
								parentPrimaryKey
								parents {
									primaryKey
								}
							}
						}
					}
				}
				""",
				product.getPrimaryKey())
			.executeAndExpectOkAndThen()
			.body(GET_PRODUCT_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return all direct product parent entities")
	void shouldReturnAllDirectProductParentEntities(Evita evita, GraphQLTester tester) {
		final SealedEntity product = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.queryOneSealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							hierarchyWithin(
								Entities.CATEGORY,
								entityPrimaryKeyInSet(26)
							)
						),
						require(
							page(1, 1),
							entityFetch(
								referenceContent(
									Entities.CATEGORY,
									entityFetch(
										hierarchyContent(
											entityFetch(
												attributeContent(ATTRIBUTE_CODE)
											)
										)
									)
								)
							)
						)
					)
				).orElseThrow();
			}
		);
		// check that it has at least 2 referenced parents
		assertTrue(product.getReferences(Entities.CATEGORY)
			.iterator()
			.next()
			.getReferencedEntity()
			.orElseThrow()
			.getParentEntity()
			.get()
			.getParentEntity()
			.isPresent());

		final Map<String, Object> expectedBody = createEntityWithReferencedParentsDto(product, Entities.CATEGORY, true);

		tester.test(TEST_CATALOG)
			.document("""
				{
					getProduct(primaryKey: %d) {
						category {
							referencedEntity {
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
				""",
				product.getPrimaryKey())
			.executeAndExpectOkAndThen()
			.body(GET_PRODUCT_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return only direct product parent")
	void shouldReturnOnlyDirectProductParent(Evita evita, GraphQLTester tester) {
		final SealedEntity product = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.queryOneSealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							hierarchyWithin(
								Entities.CATEGORY,
								entityPrimaryKeyInSet(16)
							)
						),
						require(
							page(1, 1),
							entityFetch(
								referenceContent(
									Entities.CATEGORY,
									entityFetch(
										hierarchyContent(
											stopAt(distance(1))
										)
									)
								)
							)
						)
					)
				).orElseThrow();
			}
		);
		// check that it has only one referenced parents
		assertTrue(product.getReferences(Entities.CATEGORY)
			.iterator()
			.next()
			.getReferencedEntity()
			.orElseThrow()
			.getParentEntity()
			.get()
			.getParentEntity()
			.isEmpty());

		final Map<String, Object> expectedBody = createEntityWithReferencedParentsDto(product, Entities.CATEGORY, false);

		tester.test(TEST_CATALOG)
			.document("""
				{
					getProduct(primaryKey: %d) {
						category {
							referencedEntity {
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
				""",
				product.getPrimaryKey())
			.executeAndExpectOkAndThen()
			.body(GET_PRODUCT_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return error for invalid argument in single product query")
	void shouldReturnErrorForInvalidArgumentInSingleProductQuery(GraphQLTester tester) {
		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    getProduct(limit: 1) {
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
	@DisplayName("Should filter by and return price for sale fo single product")
	void shouldFilterByAndReturnPriceForSaleForSingleProduct(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final SealedEntity entity = findEntityWithPrice(originalProductEntities);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    getProduct(
	                        code: "%s"
	                        priceInCurrency: CZK,
	                        priceInPriceLists: "basic"
                        ) {
	                        primaryKey
	                        type
                            priceForSale {
                                __typename
                                currency
                                priceList
                                priceWithTax
                            }
                            multiplePricesForSaleAvailable
	                    }
	                }
					""",
				entity.getAttribute(ATTRIBUTE_CODE, String.class)
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(GET_PRODUCT_PATH, equalTo(createEntityDtoWithOnlyOnePriceForSale(entity)));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should filter single product by non-existent price")
	void shouldFilterSingleProductByNonExistentPrice(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final SealedEntity entity = findEntityWithPrice(originalProductEntities);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    getProduct(
	                        code: "%s"
	                        priceInCurrency: CZK,
	                        priceInPriceLists: "nonexistent"
                        ) {
	                        primaryKey
	                        type
	                    }
	                }
					""",
				entity.getAttribute(ATTRIBUTE_CODE, String.class)
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(GET_PRODUCT_PATH, nullValue());
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return error for filtering product by non-existent currency")
	void shouldReturnErrorForFilteringProductByNonExistentCurrency(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final SealedEntity entity = findEntityWithPrice(originalProductEntities);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    getProduct(
	                        code: "%s"
	                        priceInCurrency: AAA,
	                        priceInPriceLists: "basic"
                        ) {
	                        primaryKey
	                        type
	                    }
	                }
					""",
				(String)entity.getAttribute(ATTRIBUTE_CODE, String.class)
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, hasSize(greaterThan(0)))
			.body(GET_PRODUCT_PATH, nullValue());
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return custom price for sale for single product")
	void shouldReturnCustomPriceForSaleForSingleProduct(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final SealedEntity entity = findEntity(
			originalProductEntities,
			it -> it.getPrices(CURRENCY_CZK, PRICE_LIST_BASIC).size() == 1 &&
				it.getPrices(CURRENCY_CZK, PRICE_LIST_BASIC).stream().allMatch(PriceContract::indexed) &&
				!it.getPrices(CURRENCY_EUR).isEmpty() &&
				it.getPrices(CURRENCY_EUR).stream().allMatch(PriceContract::indexed)
		);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    getProduct(code: "%s", priceInCurrency: EUR) {
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
				entity.getAttribute(ATTRIBUTE_CODE, String.class)
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(GET_PRODUCT_PATH, equalTo(createEntityDtoWithPriceForSale(entity)));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return formatted price for sale with entity locale")
	void shouldReturnFormattedPriceForSaleWithEntityLocale(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final SealedEntity entity = findEntityWithPrice(originalProductEntities);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    getProduct(
	                        code: "%s"
	                        priceInCurrency: CZK,
	                        priceInPriceLists: "basic",
	                        locale: cs_CZ
                        ) {
                            priceForSale {
                                priceWithTax(formatted: true, withCurrency: true)
                            }
	                    }
	                }
					""",
				entity.getAttribute(ATTRIBUTE_CODE, String.class)
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(GET_PRODUCT_PATH, equalTo(createEntityDtoWithFormattedPriceForSale(entity)));
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
	                    getProduct(
	                        code: "%s"
	                        priceInCurrency: CZK,
	                        priceInPriceLists: "basic"
                        ) {
                            priceForSale(locale: cs_CZ) {
                                priceWithTax(formatted: true, withCurrency: true)
                            }
	                    }
	                }
					""",
				entity.getAttribute(ATTRIBUTE_CODE, String.class)
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(GET_PRODUCT_PATH, equalTo(createEntityDtoWithFormattedPriceForSale(entity)));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return error when formatting price for sale without locale")
	void shouldReturnErrorWhenFormattingPriceForSaleWithoutLocale(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final SealedEntity entity = findEntityWithPrice(originalProductEntities);

		final NumberFormat priceFormatter = NumberFormat.getCurrencyInstance(CZECH_LOCALE);
		priceFormatter.setCurrency(CURRENCY_CZK);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    getProduct(
	                        code: "%s"
	                        priceInCurrency: CZK,
	                        priceInPriceLists: "basic"
                        ) {
                            priceForSale {
                                priceWithTax(formatted: true, withCurrency: true)
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
	@DisplayName("Should return price for single product with filter inheritance")
	void shouldReturnPriceForSingleProductWithFilterInheritance(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final SealedEntity entity = findEntityWithPrice(originalProductEntities);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    getProduct(
	                        code: "%s"
	                        priceInCurrency: CZK,
	                        priceInPriceLists: "basic"
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
				entity.getAttribute(ATTRIBUTE_CODE, String.class)
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(GET_PRODUCT_PATH, equalTo(createEntityDtoWithPrice(entity)));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return price for single product")
	void shouldReturnPriceForSingleProduct(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final SealedEntity entity = findEntityWithPrice(originalProductEntities);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    getProduct(code: "%s") {
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
				entity.getAttribute(ATTRIBUTE_CODE, String.class)
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(GET_PRODUCT_PATH, equalTo(createEntityDtoWithPrice(entity)));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return formatted price with entity locale")
	void shouldReturnFormattedPriceWithEntityLocale(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final SealedEntity entity = findEntityWithPrice(originalProductEntities);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    getProduct(code: "%s", locale: cs_CZ) {
                            price(priceList: "basic", currency: CZK) {
                                priceWithTax(formatted: true, withCurrency: true)
                            }
	                    }
	                }
					""",
				entity.getAttribute(ATTRIBUTE_CODE, String.class)
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(GET_PRODUCT_PATH, equalTo(createEntityDtoWithFormattedPrice(entity)));
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
	                    getProduct(code: "%s") {
                            price(priceList: "basic", currency: CZK, locale: cs_CZ) {
                                priceWithTax(formatted: true, withCurrency: true)
                            }
	                    }
	                }
					""",
				entity.getAttribute(ATTRIBUTE_CODE, String.class)
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(GET_PRODUCT_PATH, equalTo(createEntityDtoWithFormattedPrice(entity)));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return error when formatting price without locale")
	void shouldReturnErrorWhenFormattingPriceWithoutLocale(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final SealedEntity entity = findEntityWithPrice(originalProductEntities);

		final NumberFormat priceFormatter = NumberFormat.getCurrencyInstance(CZECH_LOCALE);
		priceFormatter.setCurrency(CURRENCY_CZK);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    getProduct(code: "%s"){
                            price(priceList: "basic", currency: CZK) {
                                priceWithTax(formatted: true, withCurrency: true)
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
	@DisplayName("Should return all prices for single product")
	void shouldReturnAllPricesForSingleProduct(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final SealedEntity entity = findEntity(
			originalProductEntities,
			it -> !it.getPrices().isEmpty()
		);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    getProduct(code: "%s") {
	                        primaryKey
	                        type
                            prices {
                                priceWithTax
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
				GET_PRODUCT_PATH + "." + EntityDescriptor.PRICES.name(),
				hasSize(greaterThan(0))
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return all prices for sale for master product")
	void shouldReturnAllPricesForSaleForMasterProduct(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final var entity = findEntity(
			originalProductEntities,
			it -> it.getPriceInnerRecordHandling().equals(PriceInnerRecordHandling.LOWEST_PRICE) &&
				it.getPrices(CURRENCY_CZK)
					.stream()
					.filter(PriceContract::indexed)
					.map(PriceContract::innerRecordId)
					.distinct()
					.count() > 1
		);

		final List<String> priceLists = entity.getPrices(CURRENCY_CZK)
			.stream()
			.map(PriceContract::priceList)
			.distinct()
			.toList();
		assertTrue(priceLists.size() > 1);

		final var expectedBody = createEntityDtoWithAllPricesForSale(entity, priceLists.toArray(String[]::new));

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    getProduct(
	                        primaryKey: %d
	                        priceInCurrency: CZK,
	                        priceInPriceLists: %s
                        ) {
                            primaryKey
	                        type
	                        multiplePricesForSaleAvailable
                            allPricesForSale {
                                __typename
                                currency
                                priceList
                                priceWithTax
                                innerRecordId
                            }
	                    }
	                }
					""",
				entity.getPrimaryKey(),
				serializeStringArrayToQueryString(priceLists)
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(GET_PRODUCT_PATH, equalTo(expectedBody));
	}


	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return custom multiple prices for sale flag for master products")
	void shouldReturnCustomMultiplePricesForSaleForMasterProducts(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final var entity1 = findEntityWithPrice(originalProductEntities);

		final var expectedBody1 = createEntityDtoWithMultiplePricesForSaleAvailable(entity1, false);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    getProduct(
	                        code: "%s"
                        ) {
                            primaryKey
	                        type
                            multiplePricesForSaleAvailable(currency: CZK, priceLists: "basic")
	                    }
	                }
					""",
				(String) entity1.getAttribute(ATTRIBUTE_CODE)
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(GET_PRODUCT_PATH, equalTo(expectedBody1));

		final var entity2 = findEntity(
			originalProductEntities,
			it -> it.getPriceInnerRecordHandling().equals(PriceInnerRecordHandling.LOWEST_PRICE) &&
				it.getPrices(CURRENCY_CZK)
					.stream()
					.filter(PriceContract::indexed)
					.map(PriceContract::innerRecordId)
					.distinct()
					.count() > 1
		);

		final List<String> priceLists = entity2.getPrices(CURRENCY_CZK)
			.stream()
			.map(PriceContract::priceList)
			.distinct()
			.toList();
		assertTrue(priceLists.size() > 1);

		final var expectedBody2 = createEntityDtoWithMultiplePricesForSaleAvailable(entity2, true);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    getProduct(
	                        primaryKey: %d
                        ) {
                            primaryKey
	                        type
	                        multiplePricesForSaleAvailable(currency: CZK, priceLists: %s)
	                    }
	                }
					""",
				entity2.getPrimaryKey(),
				serializeStringArrayToQueryString(priceLists)
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(GET_PRODUCT_PATH, equalTo(expectedBody2));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return filtered prices for single product")
	void shouldReturnFilteredPricesForSingleProduct(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final SealedEntity entity = findEntityWithPrice(originalProductEntities);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    getProduct(code: "%s") {
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
				entity.getAttribute(ATTRIBUTE_CODE, String.class)
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				GET_PRODUCT_PATH,
				equalTo(createEntityDtoWithPrices(entity))
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return filtered prices for multiple price lists for single product")
	void shouldReturnFilteredPricesForMutliplePriceListsForSingleProduct(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final SealedEntity entity = findEntityWithPrice(originalProductEntities, PRICE_LIST_BASIC, PRICE_LIST_VIP);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    getProduct(code: "%s") {
                            prices(priceLists: ["basic", "vip"], currency: CZK) {
                                priceWithTax
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
				GET_PRODUCT_PATH,
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
	@DisplayName("Should return formatted prices with entity locale")
	void shouldReturnFormattedPricesWithEntityLocale(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final SealedEntity entity = findEntityWithPrice(originalProductEntities);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    getProduct(code: "%s", locale: cs_CZ) {
                            prices(priceLists: "basic", currency: CZK) {
                                priceWithTax(formatted: true, withCurrency: true)
                            }
	                    }
	                }
					""",
				entity.getAttribute(ATTRIBUTE_CODE, String.class)
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(GET_PRODUCT_PATH, equalTo(createEntityDtoWithFormattedPrices(entity)));
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
	                    getProduct(code: "%s") {
                            prices(priceLists: "basic", currency: CZK, locale: cs_CZ) {
                                priceWithTax(formatted: true, withCurrency: true)
                            }
	                    }
	                }
					""",
				entity.getAttribute(ATTRIBUTE_CODE, String.class)
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(GET_PRODUCT_PATH, equalTo(createEntityDtoWithFormattedPrices(entity)));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return error when formatting prices without locale")
	void shouldReturnErrorWhenFormattingPricesWithoutLocale(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final SealedEntity entity = findEntityWithPrice(originalProductEntities);

		final NumberFormat priceFormatter = NumberFormat.getCurrencyInstance(CZECH_LOCALE);
		priceFormatter.setCurrency(CURRENCY_CZK);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    getProduct(code: "%s") {
                            prices(priceLists: "basic", currency: CZK) {
                                priceWithTax(formatted: true, withCurrency: true)
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
	@DisplayName("Should return accompanying prices for single price for sale")
	void shouldReturnAccompanyingPricesForSinglePriceForSale(Evita evita, GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final Integer desiredEntity = originalProductEntities.stream()
			.filter(entity -> {
				final Optional<PriceForSaleWithAccompanyingPrices> prices = entity.getPriceForSaleWithAccompanyingPrices(
					CURRENCY_CZK,
					null,
					new String[]{PRICE_LIST_BASIC},
					new AccompanyingPrice[]{
						new AccompanyingPrice(PriceForSaleDescriptor.ACCOMPANYING_PRICE.name(), PRICE_LIST_REFERENCE),
						new AccompanyingPrice("vipPrice", PRICE_LIST_VIP)
					}
				);
				return prices.isPresent() &&
					prices.get().accompanyingPrices().get(PriceForSaleDescriptor.ACCOMPANYING_PRICE.name()).isPresent() &&
					prices.get().accompanyingPrices().get("vipPrice").isPresent();
			})
			.map(EntityContract::getPrimaryKey)
			.findFirst()
			.orElseThrow();

		final EvitaResponse<SealedEntity> exampleResponse = queryEntities(
			evita,
			query(
				collection(Entities.PRODUCT),
				filterBy(
					entityPrimaryKeyInSet(desiredEntity),
					priceInPriceLists(PRICE_LIST_BASIC),
					priceInCurrency(CURRENCY_CZK)
				),
				require(
					page(1, 1),
					entityFetch(
						priceContent(PriceContentMode.RESPECTING_FILTER, PRICE_LIST_REFERENCE, PRICE_LIST_VIP)
					)
				)
			),
			SealedEntity.class
		);

		final Map<String, Object> expectedBody = Optional.of(exampleResponse.getRecordData().get(0))
			.map(this::createEntityDtoWithAccompanyingPricesForSinglePriceForSale)
			.orElseThrow();

		tester.test(TEST_CATALOG)
			.document("""
				query {
					getProduct(
						primaryKey: %d,
						priceInPriceLists: "basic",
						priceInCurrency: CZK
					) {
						primaryKey
						type
						priceForSale {
							__typename
							priceWithTax
							accompanyingPrice(priceLists: "reference") {
								__typename
								priceWithTax
							}
							vipPrice: accompanyingPrice(priceLists: "vip") {
								__typename
								priceWithTax
							}
						}
					}
				}
				""",
				desiredEntity)
			.executeAndExpectOkAndThen()
			.body(GET_PRODUCT_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return accompanying prices for single custom price for sale")
	void shouldReturnAccompanyingPricesForSingleCustomPriceForSale(Evita evita, GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final Integer desiredEntity = originalProductEntities.stream()
			.filter(entity -> {
				final Optional<PriceForSaleWithAccompanyingPrices> prices = entity.getPriceForSaleWithAccompanyingPrices(
					CURRENCY_CZK,
					null,
					new String[]{PRICE_LIST_BASIC},
					new AccompanyingPrice[]{
						new AccompanyingPrice(PriceForSaleDescriptor.ACCOMPANYING_PRICE.name(), PRICE_LIST_REFERENCE),
						new AccompanyingPrice("vipPrice", PRICE_LIST_VIP)
					}
				);
				return prices.isPresent() &&
					prices.get().accompanyingPrices().get(PriceForSaleDescriptor.ACCOMPANYING_PRICE.name()).isPresent() &&
					prices.get().accompanyingPrices().get("vipPrice").isPresent();
			})
			.map(EntityContract::getPrimaryKey)
			.findFirst()
			.orElseThrow();

		final EvitaResponse<SealedEntity> exampleResponse = queryEntities(
			evita,
			query(
				collection(Entities.PRODUCT),
				filterBy(
					entityPrimaryKeyInSet(desiredEntity)
				),
				require(
					entityFetch(
						priceContentAll()
					)
				)
			),
			SealedEntity.class
		);

		final Map<String, Object> expectedBody = Optional.of(exampleResponse.getRecordData().get(0))
			.map(this::createEntityDtoWithAccompanyingPricesForSinglePriceForSale)
			.orElseThrow();

		tester.test(TEST_CATALOG)
			.document("""
				query {
					getProduct(
						primaryKey: %d
					) {
						primaryKey
						type
						priceForSale(priceLists: "basic", currency: CZK) {
							__typename
							priceWithTax
							accompanyingPrice(priceLists: "reference") {
								__typename
								priceWithTax
							}
							vipPrice: accompanyingPrice(priceLists: "vip") {
								__typename
								priceWithTax
							}
						}
					}
				}
				""",
				desiredEntity)
			.executeAndExpectOkAndThen()
			.body(GET_PRODUCT_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return accompanying prices for all prices for sale")
	void shouldReturnAccompanyingPricesForAllPricesForSale(Evita evita, GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final Integer desiredEntity = originalProductEntities.stream()
			.filter(entity ->
				entity.getPriceInnerRecordHandling().equals(PriceInnerRecordHandling.LOWEST_PRICE) &&
					entity.getPrices(CURRENCY_CZK).stream()
						.anyMatch(price -> price.priceList().equals(PRICE_LIST_BASIC)) &&
					entity.getPrices(CURRENCY_CZK).stream()
						.anyMatch(price -> price.priceList().equals(PRICE_LIST_REFERENCE)) &&
					entity.getPrices(CURRENCY_CZK).stream()
						.anyMatch(price -> price.priceList().equals(PRICE_LIST_VIP)))
			.map(entity -> entity.getPrimaryKey())
			.findFirst()
			.orElseThrow();

		final EvitaResponse<SealedEntity> exampleResponse = queryEntities(
			evita,
			query(
				collection(Entities.PRODUCT),
				filterBy(
					entityPrimaryKeyInSet(desiredEntity),
					priceInPriceLists(PRICE_LIST_BASIC),
					priceInCurrency(CURRENCY_CZK)
				),
				require(
					entityFetch(
						priceContent(PriceContentMode.RESPECTING_FILTER, PRICE_LIST_REFERENCE, PRICE_LIST_VIP)
					)
				)
			),
			SealedEntity.class
		);

		final Map<String, Object> expectedBody = Optional.of(exampleResponse.getRecordData().get(0))
			.map(this::createEntityDtoWithAccompanyingPricesForAllPricesForSale)
			.orElseThrow();

		tester.test(TEST_CATALOG)
			.document("""
				query {
					getProduct(
						primaryKey: %d,
						priceInPriceLists: "basic",
						priceInCurrency: CZK
					) {
						primaryKey
						type
						allPricesForSale {
							__typename
							priceWithTax
							accompanyingPrice(priceLists: "reference") {
								__typename
								priceWithTax
							}
							vipPrice: accompanyingPrice(priceLists: "vip") {
								__typename
								priceWithTax
							}
						}
					}
				}
				""",
				desiredEntity)
			.executeAndExpectOkAndThen()
			.body(GET_PRODUCT_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return accompanying prices for all custom prices for sale")
	void shouldReturnAccompanyingPricesForAllCustomPricesForSale(Evita evita, GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final Integer desiredEntity = originalProductEntities.stream()
			.filter(entity ->
				entity.getPriceInnerRecordHandling().equals(PriceInnerRecordHandling.LOWEST_PRICE) &&
					entity.getPrices(CURRENCY_CZK).stream()
						.anyMatch(price -> price.priceList().equals(PRICE_LIST_BASIC)) &&
					entity.getPrices(CURRENCY_CZK).stream()
						.anyMatch(price -> price.priceList().equals(PRICE_LIST_REFERENCE)) &&
					entity.getPrices(CURRENCY_CZK).stream()
						.anyMatch(price -> price.priceList().equals(PRICE_LIST_VIP)))
			.map(entity -> entity.getPrimaryKey())
			.findFirst()
			.orElseThrow();

		final EvitaResponse<SealedEntity> exampleResponse = queryEntities(
			evita,
			query(
				collection(Entities.PRODUCT),
				filterBy(
					entityPrimaryKeyInSet(desiredEntity)
				),
				require(
					entityFetch(
						priceContentAll()
					)
				)
			),
			SealedEntity.class
		);

		final Map<String, Object> expectedBody = Optional.of(exampleResponse.getRecordData().get(0))
			.map(this::createEntityDtoWithAccompanyingPricesForAllPricesForSale)
			.orElseThrow();

		tester.test(TEST_CATALOG)
			.document("""
				query {
					getProduct(
						primaryKey: %d
					) {
						primaryKey
						type
						allPricesForSale(priceLists: "basic", currency: CZK) {
							__typename
							priceWithTax
							accompanyingPrice(priceLists: "reference") {
								__typename
								priceWithTax
							}
							vipPrice: accompanyingPrice(priceLists: "vip") {
								__typename
								priceWithTax
							}
						}
					}
				}
				""",
				desiredEntity)
			.executeAndExpectOkAndThen()
			.body(GET_PRODUCT_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return error for accompanying prices without price lists in single price for sale")
	void shouldReturnErrorForAccompanyingPricesWithoutPriceListsInSinglePriceForSale(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final Integer desiredEntity = originalProductEntities.stream()
			.filter(entity ->
				entity.getPrices(CURRENCY_CZK).stream()
					.anyMatch(price -> price.priceList().equals(PRICE_LIST_BASIC)) &&
					entity.getPrices(CURRENCY_CZK).stream()
						.anyMatch(price -> price.priceList().equals(PRICE_LIST_REFERENCE)) &&
					entity.getPrices(CURRENCY_CZK).stream()
						.anyMatch(price -> price.priceList().equals(PRICE_LIST_VIP)))
			.map(entity -> entity.getPrimaryKey())
			.findFirst()
			.orElseThrow();

		tester.test(TEST_CATALOG)
			.document("""
				query {
					getProduct(
						primaryKey: %s,
						priceInPriceLists: "basic",
						priceInCurrency: CZK
					) {
						primaryKey
						type
						priceForSale {
							__typename
							priceWithTax
							accompanyingPrice {
								__typename
								priceWithTax
							}
						}
					}
				}
				""",
				desiredEntity)
			.executeAndExpectErrorsAndThen();
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return error for accompanying prices without price lists in all prices for sale")
	void shouldReturnErrorForAccompanyingPricesWithoutPriceListsInAllPricesForSale(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final Integer desiredEntity = originalProductEntities.stream()
			.filter(entity ->
				entity.getPriceInnerRecordHandling().equals(PriceInnerRecordHandling.LOWEST_PRICE) &&
					entity.getPrices(CURRENCY_CZK).stream()
						.anyMatch(price -> price.priceList().equals(PRICE_LIST_BASIC)) &&
					entity.getPrices(CURRENCY_CZK).stream()
						.anyMatch(price -> price.priceList().equals(PRICE_LIST_REFERENCE)) &&
					entity.getPrices(CURRENCY_CZK).stream()
						.anyMatch(price -> price.priceList().equals(PRICE_LIST_VIP)))
			.map(entity -> entity.getPrimaryKey())
			.findFirst()
			.orElseThrow();

		tester.test(TEST_CATALOG)
			.document("""
				query {
					getProduct(
						primaryKey: %s,
						priceInPriceLists: "basic",
						priceInCurrency: CZK
					) {
						primaryKey
						type
						allPricesForSale {
							__typename
							priceWithTax
							accompanyingPrice {
								__typename
								priceWithTax
							}
						}
					}
				}
				""",
				desiredEntity)
			.executeAndExpectErrorsAndThen();
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return associated data with inherited locale for single product")
	void shouldReturnAssociatedDataWithInheritedLocaleForSingleProduct(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final SealedEntity entity = findEntity(
			originalProductEntities,
			it -> it.getAssociatedData(ASSOCIATED_DATA_LABELS, Locale.ENGLISH) != null
		);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    getProduct(code: "%s", locale: en) {
	                        primaryKey
	                        type
                            associatedData {
                                __typename
                                labels
                            }
	                    }
	                }
					""",
				entity.getAttribute(ATTRIBUTE_CODE, String.class)
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(GET_PRODUCT_PATH, equalTo(createEntityDtoWithAssociatedData(entity)));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return associated data with custom locale for single product")
	void shouldReturnAssociatedDataWithCustomLocaleForSingleProduct(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final SealedEntity entity = findEntity(
			originalProductEntities,
			it -> it.getAssociatedData(ASSOCIATED_DATA_LABELS, Locale.ENGLISH) != null
		);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    getProduct(code: "%s") {
	                        primaryKey
	                        type
                            associatedData(locale: en) {
                                __typename
                                labels
                            }
	                    }
	                }
					""",
				entity.getAttribute(ATTRIBUTE_CODE, String.class)
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(GET_PRODUCT_PATH, equalTo(createEntityDtoWithAssociatedData(entity)));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return single reference for single product")
	void shouldReturnSingleReferenceForSingleProduct(Evita evita, GraphQLTester tester, List<SealedEntity> originalProductEntities) {
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
	                    getProduct(code: "%s") {
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
				entity.getAttribute(ATTRIBUTE_CODE, String.class)
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				GET_PRODUCT_PATH,
				equalTo(
					map()
						.e(EntityDescriptor.PRIMARY_KEY.name(), entity.getPrimaryKey())
						.e(EntityDescriptor.TYPE.name(), Entities.PRODUCT)
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
									.e(ATTRIBUTE_CODE, groupEntity.getAttribute(ATTRIBUTE_CODE)))))
						.build()
				)
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return reference list for single product")
	void shouldReturnReferenceListForSingleProduct(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final SealedEntity entity = findEntity(
			originalProductEntities,
			it -> it.getReferences(Entities.STORE).size() > 1
		);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    getProduct(code: "%s") {
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
				entity.getAttribute(ATTRIBUTE_CODE, String.class)
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				GET_PRODUCT_PATH + ".store." + ReferenceDescriptor.REFERENCED_ENTITY.name() + "." + EntityDescriptor.PRIMARY_KEY.name(),
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
	@DisplayName("Should return reference list with attributes for single product")
	void shouldReturnReferenceListWithAttributesForSingleProduct(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final SealedEntity entity = findEntity(
			originalProductEntities,
			it -> it.getReferences(Entities.STORE).size() > 1
		);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    getProduct(code: "%s") {
	                        primaryKey
	                        type
                            store {
                                attributes {
                                    storeVisibleForB2C
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
				GET_PRODUCT_PATH + ".store." + AttributesProviderDescriptor.ATTRIBUTES.name() + "." + ATTRIBUTE_STORE_VISIBLE_FOR_B2C,
				containsInAnyOrder(
					entity.getReferences(Entities.STORE)
						.stream()
						.map(it -> (Boolean) it.getAttribute(ATTRIBUTE_STORE_VISIBLE_FOR_B2C))
						.peek(Assertions::assertNotNull)
						.toArray(Boolean[]::new)
				)
			);
	}


	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return sublist of references for product")
	void shouldReturnSublistOfReferencesForProduct(Evita evita, GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final var entity = findEntity(
			originalProductEntities,
			it -> it.getReferences(Entities.STORE).size() >= 4
		);

		final var expectedBody = map()
			.e(EntityDescriptor.PRIMARY_KEY.name(), entity.getPrimaryKey())
			.e(EntityDescriptor.TYPE.name(), Entities.PRODUCT)
			.e("store", entity.getReferences(Entities.STORE)
				.stream()
				.limit(2)
				.map(reference ->
					map()
						.e(ReferenceDescriptor.REFERENCED_ENTITY.name(), map()
							.e(EntityDescriptor.PRIMARY_KEY.name(), reference.getReferencedPrimaryKey()))
						.build())
				.toList())
			.build();

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    getProduct(
	                        code: "%s"
	                    ) {
                            primaryKey
	                        type
                            store(limit: 2) {
                                referencedEntity {
                                    primaryKey
                                }
                            }
                        }
	                }
					""",
				(String) entity.getAttribute(ATTRIBUTE_CODE)
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(GET_PRODUCT_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return reference page for product")
	void shouldReturnReferencePageForProduct(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final var entity = findEntity(
			originalProductEntities,
			it -> it.getReferences(Entities.STORE).size() >= 4
		);

		final var expectedBody = map()
			.e(EntityDescriptor.PRIMARY_KEY.name(), entity.getPrimaryKey())
			.e(EntityDescriptor.TYPE.name(), Entities.PRODUCT)
			.e("storePage", map()
				.e(DataChunkDescriptor.TOTAL_RECORD_COUNT.name(), entity.getReferences(Entities.STORE).size())
				.e(
					DataChunkDescriptor.DATA.name(), entity.getReferences(Entities.STORE)
					                                       .stream()
					                                       .skip(2)
					                                       .limit(2)
					                                       .map(reference ->
						map()
							.e(ReferenceDescriptor.REFERENCED_ENTITY.name(), map()
								.e(EntityDescriptor.PRIMARY_KEY.name(), reference.getReferencedPrimaryKey()))
							.build())
					                                       .toList()))
			.build();

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    getProduct(
                            code: "%s"
	                    ) {
                            primaryKey
	                        type
                            storePage(number: 2, size: 2) {
                                totalRecordCount
                                data {
	                                referencedEntity {
	                                    primaryKey
	                                }
                                }
                            }
	                    }
	                }
					""",
				(String) entity.getAttribute(ATTRIBUTE_CODE)
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(GET_PRODUCT_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return reference strip for product")
	void shouldReturnReferenceStripForProduct(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final var entity = findEntity(
			originalProductEntities,
			it -> it.getReferences(Entities.STORE).size() >= 4
		);

		final var expectedBody = map()
			.e(EntityDescriptor.PRIMARY_KEY.name(), entity.getPrimaryKey())
			.e(EntityDescriptor.TYPE.name(), Entities.PRODUCT)
			.e("storeStrip", map()
				.e(DataChunkDescriptor.TOTAL_RECORD_COUNT.name(), entity.getReferences(Entities.STORE).size())
				.e(
					DataChunkDescriptor.DATA.name(), entity.getReferences(Entities.STORE)
					                                       .stream()
					                                       .skip(2)
					                                       .limit(2)
					                                       .map(reference ->
						map()
							.e(ReferenceDescriptor.REFERENCED_ENTITY.name(), map()
								.e(EntityDescriptor.PRIMARY_KEY.name(), reference.getReferencedPrimaryKey()))
							.build())
					                                       .toList()))
			.build();

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    getProduct(
                            code: "%s"
	                    ) {
                            primaryKey
	                        type
                            storeStrip(offset: 2, limit: 2) {
                                totalRecordCount
                                data {
	                                referencedEntity {
	                                    primaryKey
	                                }
                                }
                            }
	                    }
	                }
					""",
				(String) entity.getAttribute(ATTRIBUTE_CODE)
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(GET_PRODUCT_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should pass query labels")
	void shouldPassQueryLabels(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final SealedEntity entity = findEntity(
			originalProductEntities,
			it -> it.getAttribute(ATTRIBUTE_CODE) != null
		);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    getProduct(
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
	                        primaryKey: %d
                        ) {
	                        primaryKey
	                    }
	                }
					""",
				entity.getPrimaryKey()
			)
			.executeAndExpectOkAndThen()
			.body(GET_PRODUCT_PATH, notNullValue());
	}

	@Nonnull
	private SealedEntity findEntity(@Nonnull List<SealedEntity> originalProductEntities,
	                                @Nonnull Predicate<SealedEntity> filter) {
		return originalProductEntities.stream()
			.filter(filter)
			.findFirst()
			.orElseThrow(() -> new GenericEvitaInternalError("No entity to test."));
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
