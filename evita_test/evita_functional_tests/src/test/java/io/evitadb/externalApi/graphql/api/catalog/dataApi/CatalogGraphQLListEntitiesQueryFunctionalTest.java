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

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.order.Segments;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.query.require.PriceContentMode;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.PricesContract.AccompanyingPrice;
import io.evitadb.api.requestResponse.data.PricesContract.PriceForSaleWithAccompanyingPrices;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.core.Evita;
import io.evitadb.dataType.Scope;
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
import io.evitadb.utils.Assert;
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
import java.util.Random;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.api.query.QueryConstraints.not;
import static io.evitadb.api.query.order.OrderDirection.DESC;
import static io.evitadb.externalApi.graphql.api.testSuite.TestDataGenerator.*;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.generator.DataGenerator.*;
import static io.evitadb.utils.AssertionUtils.assertSortedResultEquals;
import static io.evitadb.utils.MapBuilder.map;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for GraphQL catalog entity list query.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class CatalogGraphQLListEntitiesQueryFunctionalTest extends CatalogGraphQLDataEndpointFunctionalTest {

	private static final String PRODUCT_LIST_PATH = "data.listProduct";
	public static final String CATEGORY_LIST_PATH = "data.listCategory";

	private static final String GRAPHQL_THOUSAND_PRODUCTS_FOR_EMPTY_LIST = GRAPHQL_THOUSAND_PRODUCTS + "forEmptyList";

	@DataSet(value = GRAPHQL_THOUSAND_PRODUCTS_FOR_EMPTY_LIST, openWebApi = GraphQLProvider.CODE, readOnly = false, destroyAfterClass = true)
	protected DataCarrier setUpForEmptyList(Evita evita) {
		return super.setUpData(evita, 0, false);
	}

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
					.e(EntityDescriptor.ALL_LOCALES.name(), List.of(CZECH_LOCALE.toString(), Locale.ENGLISH.toString()))
					.e(
						AttributesProviderDescriptor.ATTRIBUTES.name(), map()
						.e(TYPENAME_FIELD, AttributesDescriptor.THIS.name(createEmptyEntitySchema("Product")))
						.e(ATTRIBUTE_CODE, entity.getAttribute(ATTRIBUTE_CODE, String.class))
						.build())
					.build()
			)
			.toList();

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    listProduct(
	                        filterBy: {
	                            entityPrimaryKeyInSet: [%d, %d]
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
	@DisplayName("Should return product versions")
	void shouldReturnProductVersions(Evita evita, GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final List<SealedEntity> entities = getEntities(
			evita,
			query(
				collection(Entities.PRODUCT),
				filterBy(
					attributeIsNotNull(ATTRIBUTE_CODE)
				),
				require(
					entityFetch()
				)
			),
			SealedEntity.class
		);

		final var expectedBody = entities.stream()
			.map(entity ->
				map()
					.e(EntityDescriptor.PRIMARY_KEY.name(), entity.getPrimaryKey())
					.e(EntityDescriptor.TYPE.name(), Entities.PRODUCT)
					.e(VersionedDescriptor.VERSION.name(), entity.version())
					.build()
			)
			.toList();

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    listProduct(
	                        filterBy: {
	                            entityPrimaryKeyInSet: %s
	                        }
	                    ) {
	                        primaryKey
	                        type
	                        version
	                    }
	                }
					""",
				serializeIntArrayToQueryString(entities.stream().map(SealedEntity::getPrimaryKey).toArray(Integer[]::new))
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(PRODUCT_LIST_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_HUNDRED_ARCHIVED_PRODUCTS_WITH_ARCHIVE)
	@DisplayName("Should return archived entities")
	void shouldReturnArchivedEntities(Evita evita, GraphQLTester tester) {
		final List<SealedEntity> archivedEntities = getEntities(
			evita,
			query(
				collection(Entities.PRODUCT),
				filterBy(
					scope(Scope.ARCHIVED)
				),
				require(
					page(1, 2),
					entityFetch()
				)
			),
			SealedEntity.class
		);

		final var expectedBodyOfArchivedEntities = archivedEntities.stream()
			.map(entity ->
				map()
					.e(EntityDescriptor.PRIMARY_KEY.name(), entity.getPrimaryKey())
					.e(EntityDescriptor.SCOPE.name(), Scope.ARCHIVED.name())
					.build())
			.toList();

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    listProduct(
	                        filterBy: {
	                            entityPrimaryKeyInSet: [%d, %d],
	                            scope: ARCHIVED
	                        }
	                    ) {
                            primaryKey
	                        scope
	                    }
	                }
					""",
				archivedEntities.get(0).getPrimaryKey(),
				archivedEntities.get(1).getPrimaryKey()
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(PRODUCT_LIST_PATH, equalTo(expectedBodyOfArchivedEntities));
	}

	@Test
	@UseDataSet(GRAPHQL_HUNDRED_ARCHIVED_PRODUCTS_WITH_ARCHIVE)
	@DisplayName("Should return both live and archived entities explicitly")
	void shouldReturnBothLiveAndArchivedEntitiesExplicitly(Evita evita, GraphQLTester tester) {
		final List<SealedEntity> liveEntities = getEntities(
			evita,
			query(
				collection(Entities.PRODUCT),
				filterBy(
					scope(Scope.LIVE)
				),
				require(
					page(1, 2),
					entityFetch()
				)
			),
			SealedEntity.class
		);
		final List<SealedEntity> archivedEntities = getEntities(
			evita,
			query(
				collection(Entities.PRODUCT),
				filterBy(
					scope(Scope.ARCHIVED)
				),
				require(
					page(1, 2),
					entityFetch()
				)
			),
			SealedEntity.class
		);

		final var expectedBodyOfArchivedEntities = Stream.concat(liveEntities.stream(), archivedEntities.stream())
			.map(entity ->
				map()
					.e(EntityDescriptor.PRIMARY_KEY.name(), entity.getPrimaryKey())
					.e(EntityDescriptor.SCOPE.name(), entity.getScope().name())
					.build())
			.toList();

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    listProduct(
	                        filterBy: {
	                            entityPrimaryKeyInSet: [%d, %d, %d, %d],
	                            scope: [LIVE, ARCHIVED]
	                        }
	                    ) {
                            primaryKey
	                        scope
	                    }
	                }
					""",
				liveEntities.get(0).getPrimaryKey(),
				liveEntities.get(1).getPrimaryKey(),
				archivedEntities.get(0).getPrimaryKey(),
				archivedEntities.get(1).getPrimaryKey()
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(PRODUCT_LIST_PATH, containsInAnyOrder(expectedBodyOfArchivedEntities.toArray()));
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
	                    listProduct(
	                         filterBy: {
							     entityPrimaryKeyInSet: %d
							 }
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
			.body(PRODUCT_LIST_PATH, emptyIterable());
	}

	@Test
	@UseDataSet(GRAPHQL_HUNDRED_ARCHIVED_PRODUCTS_WITH_ARCHIVE)
	@DisplayName("Should return data based on scope")
	void shouldReturnDataBasedOnScope(Evita evita, GraphQLTester tester) {
		final List<SealedEntity> liveEntities = getEntities(
			evita,
			query(
				collection(Entities.PRODUCT),
				filterBy(
					scope(Scope.LIVE)
				),
				require(
					page(1, 2),
					entityFetch(attributeContent(ATTRIBUTE_CODE))
				)
			),
			SealedEntity.class
		);
		final List<SealedEntity> archivedEntities = getEntities(
			evita,
			query(
				collection(Entities.PRODUCT),
				filterBy(
					scope(Scope.ARCHIVED)
				),
				require(
					page(1, 2),
					entityFetch()
				)
			),
			SealedEntity.class
		);

		var expectedBody = Stream.concat(Stream.of(liveEntities.get(0)), archivedEntities.stream())
			.map(entity -> map()
				.e(EntityDescriptor.PRIMARY_KEY.name(), entity.getPrimaryKey())
				.e(EntityDescriptor.SCOPE.name(), entity.getScope().name())
				.build())
			.toList();

		tester.test(TEST_CATALOG)
			.document("""
				{
					listProduct(
						filterBy: {
							entityPrimaryKeyInSet: [%d, %d, %d, %d],
							inScope: {
								scope: LIVE,
								filtering: {
									attributeCodeEquals: "%s"
								}
							}
							scope: [LIVE, ARCHIVED]
						}
					) {
						primaryKey
						scope
					}
				}
				""",
				liveEntities.get(0).getPrimaryKey(),
				liveEntities.get(1).getPrimaryKey(),
				archivedEntities.get(0).getPrimaryKey(),
				archivedEntities.get(1).getPrimaryKey(),
				liveEntities.get(0).getAttribute(ATTRIBUTE_CODE))
			.executeAndExpectOkAndThen()
			.body(ERRORS_PATH, nullValue())
			.body(PRODUCT_LIST_PATH, containsInAnyOrder(expectedBody.toArray()));
	}


	@Test
	@UseDataSet(GRAPHQL_HUNDRED_ARCHIVED_PRODUCTS_WITH_ARCHIVE)
	@DisplayName("Should order data based on scope")
	void shouldOrderDataBasedOnScope(Evita evita, GraphQLTester tester) {
		final List<EntityClassifier> liveEntities = getEntities(
			evita,
			query(
				collection(Entities.PRODUCT),
				filterBy(scope(Scope.LIVE)),
				require(page(1, 2))
			),
			EntityClassifier.class
		);
		final List<EntityClassifier> archivedEntities = getEntities(
			evita,
			query(
				collection(Entities.PRODUCT),
				filterBy(scope(Scope.ARCHIVED)),
				require(page(1, 2))
			),
			EntityClassifier.class
		);

		final EvitaResponse<EntityClassifier> expectedEntities = queryEntities(
			evita,
			query(
				collection(Entities.PRODUCT),
				filterBy(
					entityPrimaryKeyInSet(
						Stream.concat(liveEntities.stream(), archivedEntities.stream())
							.map(EntityClassifier::getPrimaryKey)
							.toArray(Integer[]::new)
					),
					scope(Scope.LIVE, Scope.ARCHIVED)
				),
				orderBy(
					inScope(
						Scope.LIVE,
						attributeNatural(ATTRIBUTE_PRIORITY, DESC)
					)
				)
			),
			EntityClassifier.class
		);
		var expectedBody = expectedEntities.getRecordData()
			.stream()
			.map(entity -> map()
				.e(EntityDescriptor.PRIMARY_KEY.name(), entity.getPrimaryKey())
				.build())
			.toList();

		tester.test(TEST_CATALOG)
			.document("""
				{
					listProduct(
						filterBy: {
							entityPrimaryKeyInSet: [%d, %d, %d, %d],
							scope: [LIVE, ARCHIVED]
						},
						orderBy: {
							inScope: {
								scope: LIVE,
								ordering: {
									attributePriorityNatural: DESC
								}
							}
						}
					) {
						primaryKey
					}
				}
				""",
				liveEntities.get(0).getPrimaryKey(),
				liveEntities.get(1).getPrimaryKey(),
				archivedEntities.get(0).getPrimaryKey(),
				archivedEntities.get(1).getPrimaryKey())
			.executeAndExpectOkAndThen()
			.body(ERRORS_PATH, nullValue())
			.body(PRODUCT_LIST_PATH, containsInAnyOrder(expectedBody.toArray()));
	}

	@Test
	@UseDataSet(value = GRAPHQL_THOUSAND_PRODUCTS_FOR_EMPTY_LIST, destroyAfterTest = true)
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
	                    listProduct(
	                        filterBy: {
	                            entityPrimaryKeyInSet: %d
	                        }
	                    ) {
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
				PRODUCT_LIST_PATH,
				equalTo(List.of(
					map()
						.e(EntityDescriptor.PRIMARY_KEY.name(), primaryKey)
						.e(AttributesProviderDescriptor.ATTRIBUTES.name(), null)
						.e(EntityDescriptor.ASSOCIATED_DATA.name(), null)
						.build()
				))
			);
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
					.e(EntityDescriptor.LOCALES.name(), List.of(Locale.ENGLISH.toString()))
					.e(EntityDescriptor.ALL_LOCALES.name(), List.of(CZECH_LOCALE.toString(), Locale.ENGLISH.toString()))
					.e(
						AttributesProviderDescriptor.ATTRIBUTES.name(), map()
						.e(ATTRIBUTE_CODE, entity.getAttribute(ATTRIBUTE_CODE, String.class))
						.e(ATTRIBUTE_NAME, entity.getAttribute(ATTRIBUTE_NAME, Locale.ENGLISH))
						.build())
					.build()
			)
			.toList();

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    listProduct(
	                        filterBy: {
	                            attributeCodeInSet: ["%s", "%s"]
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

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    listProduct(
	                        filterBy: {
	                            attributeCodeInSet: ["%s", "%s"]
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
			.toList();

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    listProduct(
	                        filterBy: {
	                            attributeCodeInSet: ["%s", "%s"],
	                            entityLocaleEquals: cs_CZ
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
					.e(EntityDescriptor.LOCALES.name(), List.of(Locale.ENGLISH.toString()))
					.e(EntityDescriptor.ALL_LOCALES.name(), List.of(CZECH_LOCALE.toString(), Locale.ENGLISH.toString()))
					.e(
						AttributesProviderDescriptor.ATTRIBUTES.name(), map()
						.e(ATTRIBUTE_URL, entity.getAttribute(ATTRIBUTE_URL, Locale.ENGLISH))
						.e(ATTRIBUTE_NAME, entity.getAttribute(ATTRIBUTE_NAME, Locale.ENGLISH))
						.build())
					.build()
			)
			.toList();

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    listProduct(
	                        filterBy: {
	                            attributeUrlInSet: ["%s", "%s"]
	                            entityLocaleEquals: en
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
		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    listProduct(
	                        filterBy: {
	                            attributeCodeInSet: ["%s", "%s"]
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
		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    listProduct(code: "product") {
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
					listCategory(
						filterBy: {
							entityPrimaryKeyInSet: 16
						}
					) {
						parentPrimaryKey
						parents {
							primaryKey
						}
					}
				}
				""")
			.executeAndExpectOkAndThen()
			.body(CATEGORY_LIST_PATH, equalTo(expectedBody));
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
					listCategory(
						filterBy: {
							entityPrimaryKeyInSet: 16
						}
					) {
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
			.body(CATEGORY_LIST_PATH, equalTo(expectedBody));
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
					listCategory(
						filterBy: {
							entityPrimaryKeyInSet: 16
						}
					) {
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
			.body(CATEGORY_LIST_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return all direct product parent entity references")
	void shouldReturnAllDirectProductParentEntityReferences(Evita evita, GraphQLTester tester) {
		final SealedEntity product = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final List<SealedEntity> products = session.queryList(
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
					),
					SealedEntity.class
				);

				assertEquals(1, products.size());
				final SealedEntity p = products.get(0);
				// check that it has at least 2 referenced parents
				assertTrue(p.getReferences(Entities.CATEGORY)
					.iterator()
					.next()
					.getReferencedEntity()
					.orElseThrow()
					.getParentEntity()
					.get()
					.getParentEntity()
					.isPresent());
				return p;
			}
		);

		final List<Map<String, Object>> expectedBody = List.of(
			createEntityWithReferencedParentsDto(product, Entities.CATEGORY, false)
		);

		tester.test(TEST_CATALOG)
			.document("""
				{
					listProduct(
						filterBy: {
							hierarchyCategoryWithin: {
								ofParent: {
									entityPrimaryKeyInSet: 26
								}
							}
						},
						limit: 1
					) {
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
				""")
			.executeAndExpectOkAndThen()
			.body(PRODUCT_LIST_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return all direct product parent entities")
	void shouldReturnAllDirectProductParentEntities(Evita evita, GraphQLTester tester) {
		final SealedEntity product = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final List<SealedEntity> products = session.queryList(
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
					),
					SealedEntity.class
				);

				assertEquals(1, products.size());
				final SealedEntity p = products.get(0);
				// check that it has at least 2 referenced parents
				assertTrue(p.getReferences(Entities.CATEGORY)
					.iterator()
					.next()
					.getReferencedEntity()
					.orElseThrow()
					.getParentEntity()
					.get()
					.getParentEntity()
					.isPresent());
				return p;
			}
		);

		final List<Map<String, Object>> expectedBody = List.of(
			createEntityWithReferencedParentsDto(product, Entities.CATEGORY, true)
		);

		tester.test(TEST_CATALOG)
			.document("""
				{
					listProduct(
						filterBy: {
							hierarchyCategoryWithin: {
								ofParent: {
									entityPrimaryKeyInSet: 26
								}
							}
						},
						limit: 1
					) {
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
				""")
			.executeAndExpectOkAndThen()
			.body(PRODUCT_LIST_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return only direct product parent")
	void shouldReturnOnlyDirectProductParent(Evita evita, GraphQLTester tester) {
		final SealedEntity product = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final List<SealedEntity> products = session.queryList(
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
					),
					SealedEntity.class
				);

				assertEquals(1, products.size());
				final SealedEntity p = products.get(0);
				// check that it has only one referenced parents
				assertTrue(p.getReferences(Entities.CATEGORY)
					.iterator()
					.next()
					.getReferencedEntity()
					.orElseThrow()
					.getParentEntity()
					.get()
					.getParentEntity()
					.isEmpty());
				return p;
			}
		);

		final List<Map<String, Object>> expectedBody = List.of(
			createEntityWithReferencedParentsDto(product, Entities.CATEGORY, false)
		);

		tester.test(TEST_CATALOG)
			.document("""
				{
					listProduct(
						filterBy: {
							hierarchyCategoryWithin: {
								ofParent: {
									entityPrimaryKeyInSet: 16
								}
							}
						},
						limit: 1
					) {
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
				""")
			.executeAndExpectOkAndThen()
			.body(PRODUCT_LIST_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should filter by and return price for sale for multiple products")
	void shouldFilterByAndReturnPriceForSaleForMultipleProducts(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final List<SealedEntity> entities = findEntitiesWithPrice(originalProductEntities);

		final List<Map<String, Object>> expectedBody = entities.stream()
			.map(this::createEntityDtoWithOnlyOnePriceForSale)
			.toList();

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    listProduct(
	                        filterBy: {
		                        attributeCodeInSet: ["%s", "%s"]
		                        priceInCurrency: CZK,
		                        priceInPriceLists: "basic"
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
                            multiplePricesForSaleAvailable
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

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    listProduct(
	                        filterBy: {
	                            attributeCodeInSet: ["%s", "%s"]
		                        priceInCurrency: CZK,
		                        priceInPriceLists: "nonexistent"
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

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    listProduct(
	                        filterBy: {
	                            attributeCodeInSet: ["%s", "%s"]
		                        priceInCurrency: AAA,
		                        priceInPriceLists: "basic"
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
		final List<SealedEntity> entities = findEntities(
			originalProductEntities,
			it -> it.getPrices(CURRENCY_CZK, PRICE_LIST_BASIC).size() == 1 &&
				it.getPrices(CURRENCY_CZK, PRICE_LIST_BASIC).stream().allMatch(PriceContract::indexed) &&
				!it.getPrices(CURRENCY_EUR).isEmpty() &&
				it.getPrices(CURRENCY_EUR).stream().allMatch(PriceContract::indexed)
		);

		final List<Map<String,Object>> expectedBody = entities.stream()
			.map(this::createEntityDtoWithPriceForSale)
			.toList();

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    listProduct(
	                        filterBy: {
	                            entityPrimaryKeyInSet: [%d, %d],
	                            priceInCurrency: EUR
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

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    listProduct(
	                        filterBy: {
		                        attributeCodeInSet: ["%s", "%s"]
		                        priceInCurrency: CZK,
		                        priceInPriceLists: "basic",
		                        entityLocaleEquals: cs_CZ
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

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    listProduct(
	                        filterBy: {
		                        attributeCodeInSet: ["%s", "%s"]
		                        priceInCurrency: CZK,
		                        priceInPriceLists: "basic"
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

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    listProduct(
	                        filterBy: {
		                        attributeCodeInSet: ["%s", "%s"]
		                        priceInCurrency: CZK,
		                        priceInPriceLists: "basic"
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

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    listProduct(
	                        filterBy: {
	                            attributeCodeInSet: ["%s", "%s"]
	                            priceInCurrency: CZK
	                            priceInPriceLists: "basic"
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

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    listProduct(
	                        filterBy: {
	                            attributeCodeInSet: ["%s", "%s"]
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

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    listProduct(
	                        filterBy: {
	                            attributeCodeInSet: ["%s", "%s"],
	                            entityLocaleEquals: cs_CZ
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

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    listProduct(
	                        filterBy: {
	                            attributeCodeInSet: ["%s", "%s"]
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

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    listProduct(
	                        filterBy: {
	                            attributeCodeInSet: ["%s", "%s"]
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
	@DisplayName("Should return all prices for sale for master products")
	void shouldReturnAllPricesForSaleForMasterProducts(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final var entities = findEntities(
			originalProductEntities,
			it -> it.getPriceInnerRecordHandling().equals(PriceInnerRecordHandling.LOWEST_PRICE) &&
				it.getPrices(CURRENCY_CZK)
					.stream()
					.filter(PriceContract::indexed)
					.map(PriceContract::innerRecordId)
					.distinct()
					.count() > 1,
			2
		);

		final List<String> priceLists = entities.stream()
			.flatMap(it -> it.getPrices(CURRENCY_CZK).stream().map(PriceContract::priceList))
			.distinct()
			.toList();
		assertTrue(priceLists.size() > 1);

		final var expectedBody = entities.stream()
			.map(entity -> createEntityDtoWithAllPricesForSale(entity, priceLists.toArray(String[]::new)))
			.toList();

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    listProduct(
	                        filterBy: {
		                        entityPrimaryKeyInSet: [%d, %d]
		                        priceInCurrency: CZK,
		                        priceInPriceLists: %s
	                        }
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
				entities.get(0).getPrimaryKey(),
				entities.get(1).getPrimaryKey(),
				serializeStringArrayToQueryString(priceLists)
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(PRODUCT_LIST_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return custom multiple prices for sale flag for master products")
	void shouldReturnCustomMultiplePricesForSaleForMasterProducts(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final var entities1 = findEntitiesWithPrice(originalProductEntities);

		final var expectedBody1 = entities1.stream()
			.map(entity -> createEntityDtoWithMultiplePricesForSaleAvailable(entity, false))
			.toList();

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    listProduct(
	                        filterBy: {
		                        attributeCodeInSet: ["%s", "%s"]
	                        }
                        ) {
                            primaryKey
	                        type
                            multiplePricesForSaleAvailable(currency: CZK, priceLists: "basic")
	                    }
	                }
					""",
				entities1.get(0).getAttribute(ATTRIBUTE_CODE),
				entities1.get(1).getAttribute(ATTRIBUTE_CODE)
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(PRODUCT_LIST_PATH, equalTo(expectedBody1));

		final var entities2 = findEntities(
			originalProductEntities,
			it -> it.getPriceInnerRecordHandling().equals(PriceInnerRecordHandling.LOWEST_PRICE) &&
				it.getPrices(CURRENCY_CZK)
					.stream()
					.filter(PriceContract::indexed)
					.map(PriceContract::innerRecordId)
					.distinct()
					.count() > 1,
			2
		);

		final List<String> priceLists = entities2.stream()
			.flatMap(it -> it.getPrices(CURRENCY_CZK).stream().map(PriceContract::priceList))
			.distinct()
			.toList();
		assertTrue(priceLists.size() > 1);

		final var expectedBody2 = entities2.stream()
			.map(entity -> createEntityDtoWithMultiplePricesForSaleAvailable(entity, true))
			.toList();

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    listProduct(
	                        filterBy: {
		                        entityPrimaryKeyInSet: [%d, %d]
	                        }
                        ) {
                            primaryKey
	                        type
	                        multiplePricesForSaleAvailable(currency: CZK, priceLists: %s)
	                    }
	                }
					""",
				entities2.get(0).getPrimaryKey(),
				entities2.get(1).getPrimaryKey(),
				serializeStringArrayToQueryString(priceLists)
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(PRODUCT_LIST_PATH, equalTo(expectedBody2));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return filtered prices for products")
	void shouldReturnFilteredPricesForProducts(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final List<SealedEntity> entities = findEntitiesWithPrice(originalProductEntities);

		final List<Map<String, Object>> expectedBody = entities.stream()
			.map(this::createEntityDtoWithPrices)
			.toList();

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    listProduct(
		                    filterBy: {
		                        entityPrimaryKeyInSet: [%d, %d]
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
							.e(PriceDescriptor.PRICE_WITH_TAX.name(), entity.getPrices(CURRENCY_CZK, PRICE_LIST_BASIC).iterator().next().priceWithTax().toString())
							.build(),
						map()
							.e(PriceDescriptor.PRICE_WITH_TAX.name(), entity.getPrices(CURRENCY_CZK, PRICE_LIST_VIP).iterator().next().priceWithTax().toString())
							.build()
					))
					.build()
			)
			.toList();

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    listProduct(
		                    filterBy: {
		                        entityPrimaryKeyInSet: [%d, %d]
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

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    listProduct(
		                    filterBy: {
		                        entityPrimaryKeyInSet: [%d, %d],
		                        entityLocaleEquals: cs_CZ
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

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    listProduct(
		                    filterBy: {
		                        entityPrimaryKeyInSet: [%d, %d]
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

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    listProduct(
		                    filterBy: {
		                        entityPrimaryKeyInSet: [%d, %d]
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
	@DisplayName("Should return accompanying prices for single price for sale")
	void shouldReturnAccompanyingPricesForSinglePriceForSale(Evita evita, GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final List<Integer> desiredEntities = originalProductEntities.stream()
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
			.map(entity -> entity.getPrimaryKey())
			.toList();
		assertTrue(desiredEntities.size() > 1);

		final EvitaResponse<SealedEntity> exampleResponse = queryEntities(
			evita,
			query(
				collection(Entities.PRODUCT),
				filterBy(
					entityPrimaryKeyInSet(desiredEntities.toArray(Integer[]::new)),
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

		final List<Map<String, Object>> expectedBody = exampleResponse.getRecordData()
			.stream()
			.map(this::createEntityDtoWithAccompanyingPricesForSinglePriceForSale)
			.toList();

		tester.test(TEST_CATALOG)
			.document("""
				query {
					listProduct(
						filterBy: {
							entityPrimaryKeyInSet: %s,
							priceInPriceLists: "basic",
							priceInCurrency: CZK
						}
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
				serializeIntArrayToQueryString(desiredEntities))
			.executeAndExpectOkAndThen()
			.body(PRODUCT_LIST_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return accompanying prices for single price for sale")
	void shouldReturnDefaultAccompanyingPriceForSinglePriceForSale(Evita evita, GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final List<Integer> desiredEntities = originalProductEntities.stream()
			.filter(entity ->
				entity.getPriceInnerRecordHandling().equals(PriceInnerRecordHandling.NONE) &&
				entity.getPrices().stream().map(PriceContract::currency).anyMatch(CURRENCY_EUR::equals) &&
					entity.getPrices().stream().map(PriceContract::priceList).anyMatch(PRICE_LIST_BASIC::equals) &&
					entity.getPrices().stream().map(PriceContract::priceList).anyMatch(PRICE_LIST_B2B::equals)
			)
			.map(EntityContract::getPrimaryKey)
			.toList();
		assertFalse(desiredEntities.isEmpty());

		final EvitaResponse<SealedEntity> exampleResponse = queryEntities(
			evita,
			query(
				collection(Entities.PRODUCT),
				filterBy(
					entityPrimaryKeyInSet(desiredEntities.toArray(Integer[]::new)),
					priceInPriceLists(PRICE_LIST_BASIC),
					priceInCurrency(CURRENCY_EUR)
				),
				require(
					defaultAccompanyingPriceLists(PRICE_LIST_B2B),
					entityFetch(
						priceContent(PriceContentMode.RESPECTING_FILTER),
						accompanyingPriceContentDefault()
					)
				)
			),
			SealedEntity.class
		);

		final List<Map<String, Object>> expectedBody = exampleResponse.getRecordData()
			.stream()
			.map(this::createEntityDtoWithDefaultAccompanyingPriceForSinglePriceForSale)
			.toList();

		tester.test(TEST_CATALOG)
			.document("""
				query {
					listProduct(
						filterBy: {
							entityPrimaryKeyInSet: %s,
							priceInPriceLists: "basic",
							priceInCurrency: EUR
						},
						require: {
							priceDefaultAccompanyingPriceLists: "b2b"
						}
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
				serializeIntArrayToQueryString(desiredEntities))
			.executeAndExpectOkAndThen()
			.body(PRODUCT_LIST_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return accompanying prices for single price for sale")
	void shouldReturnDefaultAndCustomAccompanyingPricesForSinglePriceForSale(Evita evita, GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final List<Integer> desiredEntities = originalProductEntities.stream()
			.filter(entity ->
				entity.getPriceInnerRecordHandling().equals(PriceInnerRecordHandling.NONE) &&
					entity.getPrices().stream().map(PriceContract::currency).anyMatch(CURRENCY_EUR::equals) &&
					entity.getPrices().stream().map(PriceContract::priceList).anyMatch(PRICE_LIST_BASIC::equals) &&
					entity.getPrices().stream().map(PriceContract::priceList).anyMatch(PRICE_LIST_B2B::equals) &&
					entity.getPrices().stream().map(PriceContract::priceList).anyMatch(PRICE_LIST_VIP::equals)
			)
			.map(EntityContract::getPrimaryKey)
			.toList();
		assertFalse(desiredEntities.isEmpty());

		final EvitaResponse<SealedEntity> exampleResponse = queryEntities(
			evita,
			query(
				collection(Entities.PRODUCT),
				filterBy(
					entityPrimaryKeyInSet(desiredEntities.toArray(Integer[]::new)),
					priceInPriceLists(PRICE_LIST_BASIC),
					priceInCurrency(CURRENCY_EUR)
				),
				require(
					defaultAccompanyingPriceLists(PRICE_LIST_B2B),
					entityFetch(
						priceContent(PriceContentMode.RESPECTING_FILTER),
						accompanyingPriceContentDefault(),
						accompanyingPriceContent("vipPrice", PRICE_LIST_VIP)
					)
				)
			),
			SealedEntity.class
		);

		final List<Map<String, Object>> expectedBody = exampleResponse.getRecordData()
			.stream()
			.map(this::createEntityDtoWithDefaultAndCustomAccompanyingPricesForSinglePriceForSale)
			.toList();

		tester.test(TEST_CATALOG)
			.document("""
				query {
					listProduct(
						filterBy: {
							entityPrimaryKeyInSet: %s,
							priceInPriceLists: "basic",
							priceInCurrency: EUR
						},
						require: {
							priceDefaultAccompanyingPriceLists: "b2b"
						}
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
							vipPrice: accompanyingPrice(priceLists: "vip") {
								__typename
								priceWithTax
							}
						}
					}
				}
				""",
				serializeIntArrayToQueryString(desiredEntities))
			.executeAndExpectOkAndThen()
			.body(PRODUCT_LIST_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return accompanying prices for single custom price for sale")
	void shouldReturnAccompanyingPricesForSingleCustomPriceForSale(Evita evita, GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final List<Integer> desiredEntities = originalProductEntities.stream()
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
			.toList();
		assertTrue(desiredEntities.size() > 1);

		final EvitaResponse<SealedEntity> exampleResponse = queryEntities(
			evita,
			query(
				collection(Entities.PRODUCT),
				filterBy(
					entityPrimaryKeyInSet(desiredEntities.toArray(Integer[]::new))
				),
				require(
					entityFetch(
						priceContentAll()
					)
				)
			),
			SealedEntity.class
		);

		final List<Map<String, Object>> expectedBody = exampleResponse.getRecordData()
			.stream()
			.map(this::createEntityDtoWithAccompanyingPricesForSinglePriceForSale)
			.toList();

		tester.test(TEST_CATALOG)
			.document("""
				query {
					listProduct(
						filterBy: {
							entityPrimaryKeyInSet: %s
						}
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
				serializeIntArrayToQueryString(desiredEntities))
			.executeAndExpectOkAndThen()
			.body(PRODUCT_LIST_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return default and custom accompanying prices for all prices for sale")
	void shouldReturnDefaultAndCustomAccompanyingPricesForAllPricesForSale(Evita evita, GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final List<Integer> desiredEntities = originalProductEntities.stream()
			.filter(entity ->
				entity.getPriceInnerRecordHandling().equals(PriceInnerRecordHandling.LOWEST_PRICE) &&
					entity.getPrices(CURRENCY_CZK).stream()
						.anyMatch(price -> price.priceList().equals(PRICE_LIST_BASIC)) &&
					entity.getPrices(CURRENCY_CZK).stream()
						.anyMatch(price -> price.priceList().equals(PRICE_LIST_REFERENCE)) &&
					entity.getPrices(CURRENCY_CZK).stream()
						.anyMatch(price -> price.priceList().equals(PRICE_LIST_VIP)))
			.map(entity -> entity.getPrimaryKey())
			.toList();
		assertTrue(desiredEntities.size() > 1);


		final EvitaResponse<SealedEntity> exampleResponse = queryEntities(
			evita,
			query(
				collection(Entities.PRODUCT),
				filterBy(
					entityPrimaryKeyInSet(desiredEntities.toArray(Integer[]::new)),
					priceInPriceLists(PRICE_LIST_BASIC),
					priceInCurrency(CURRENCY_CZK)
				),
				require(
					defaultAccompanyingPriceLists(PRICE_LIST_REFERENCE),
					entityFetch(
						priceContent(PriceContentMode.RESPECTING_FILTER),
						accompanyingPriceContentDefault(),
						accompanyingPriceContent("vipPrice", PRICE_LIST_VIP)
					)
				)
			),
			SealedEntity.class
		);

		final List<Map<String, Object>> expectedBody = exampleResponse.getRecordData()
			.stream()
			.map(this::createEntityDtoWithAccompanyingPricesForAllPricesForSale)
			.toList();
		tester.test(TEST_CATALOG)
			.document("""
				query {
					listProduct(
						filterBy: {
							entityPrimaryKeyInSet: %s,
							priceInPriceLists: "basic",
							priceInCurrency: CZK
						},
						require: {
							priceDefaultAccompanyingPriceLists: "reference"
						}
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
								vipPrice: accompanyingPrice(priceLists: "vip") {
									__typename
									priceWithTax
								}
							}
					}
				}
				""",
				serializeIntArrayToQueryString(desiredEntities))
			.executeAndExpectOkAndThen()
			.body(PRODUCT_LIST_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return accompanying prices for all prices for sale")
	void shouldReturnAccompanyingPricesForAllPricesForSale(Evita evita, GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final List<Integer> desiredEntities = originalProductEntities.stream()
			.filter(entity ->
				entity.getPriceInnerRecordHandling().equals(PriceInnerRecordHandling.LOWEST_PRICE) &&
					entity.getPrices(CURRENCY_CZK).stream()
						.anyMatch(price -> price.priceList().equals(PRICE_LIST_BASIC)) &&
					entity.getPrices(CURRENCY_CZK).stream()
						.anyMatch(price -> price.priceList().equals(PRICE_LIST_REFERENCE)) &&
					entity.getPrices(CURRENCY_CZK).stream()
						.anyMatch(price -> price.priceList().equals(PRICE_LIST_VIP)))
			.map(entity -> entity.getPrimaryKey())
			.toList();
		assertTrue(desiredEntities.size() > 1);

		final EvitaResponse<SealedEntity> exampleResponse = queryEntities(
			evita,
			query(
				collection(Entities.PRODUCT),
				filterBy(
					entityPrimaryKeyInSet(desiredEntities.toArray(Integer[]::new)),
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

		final List<Map<String, Object>> expectedBody = exampleResponse.getRecordData()
			.stream()
			.map(this::createEntityDtoWithAccompanyingPricesForAllPricesForSale)
			.toList();

		tester.test(TEST_CATALOG)
			.document("""
				query {
					listProduct(
						filterBy: {
							entityPrimaryKeyInSet: %s,
							priceInPriceLists: "basic",
							priceInCurrency: CZK
						}
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
				serializeIntArrayToQueryString(desiredEntities))
			.executeAndExpectOkAndThen()
			.body(PRODUCT_LIST_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return accompanying prices for all custom prices for sale")
	void shouldReturnAccompanyingPricesForAllCustomPricesForSale(Evita evita, GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final List<Integer> desiredEntities = originalProductEntities.stream()
			.filter(entity ->
				entity.getPriceInnerRecordHandling().equals(PriceInnerRecordHandling.LOWEST_PRICE) &&
					entity.getPrices(CURRENCY_CZK).stream()
						.anyMatch(price -> price.priceList().equals(PRICE_LIST_BASIC)) &&
					entity.getPrices(CURRENCY_CZK).stream()
						.anyMatch(price -> price.priceList().equals(PRICE_LIST_REFERENCE)) &&
					entity.getPrices(CURRENCY_CZK).stream()
						.anyMatch(price -> price.priceList().equals(PRICE_LIST_VIP)))
			.map(entity -> entity.getPrimaryKey())
			.toList();
		assertTrue(desiredEntities.size() > 1);

		final EvitaResponse<SealedEntity> exampleResponse = queryEntities(
			evita,
			query(
				collection(Entities.PRODUCT),
				filterBy(
					entityPrimaryKeyInSet(desiredEntities.toArray(Integer[]::new))
				),
				require(
					entityFetch(
						priceContentAll()
					)
				)
			),
			SealedEntity.class
		);

		final List<Map<String, Object>> expectedBody = exampleResponse.getRecordData()
			.stream()
			.map(this::createEntityDtoWithAccompanyingPricesForAllPricesForSale)
			.toList();

		tester.test(TEST_CATALOG)
			.document("""
				query {
					listProduct(
						filterBy: {
							entityPrimaryKeyInSet: %s
						}
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
				serializeIntArrayToQueryString(desiredEntities))
			.executeAndExpectOkAndThen()
			.body(PRODUCT_LIST_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return error for accompanying prices without price lists in single price for sale")
	void shouldReturnErrorForAccompanyingPricesWithoutPriceListsInSinglePriceForSale(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final List<Integer> desiredEntities = originalProductEntities.stream()
			.filter(entity ->
				entity.getPrices(CURRENCY_CZK).stream()
					.anyMatch(price -> price.priceList().equals(PRICE_LIST_BASIC)) &&
					entity.getPrices(CURRENCY_CZK).stream()
						.anyMatch(price -> price.priceList().equals(PRICE_LIST_REFERENCE)) &&
					entity.getPrices(CURRENCY_CZK).stream()
						.anyMatch(price -> price.priceList().equals(PRICE_LIST_VIP)))
			.map(entity -> entity.getPrimaryKey())
			.toList();
		assertTrue(desiredEntities.size() > 1);

		tester.test(TEST_CATALOG)
			.document("""
				query {
					listProduct(
						filterBy: {
							entityPrimaryKeyInSet: %s,
							priceInPriceLists: "basic",
							priceInCurrency: CZK
						}
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
				serializeIntArrayToQueryString(desiredEntities))
			.executeAndExpectErrorsAndThen();
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return error for accompanying prices without price lists in all prices for sale")
	void shouldReturnErrorForAccompanyingPricesWithoutPriceListsInAllPricesForSale(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final List<Integer> desiredEntities = originalProductEntities.stream()
			.filter(entity ->
				entity.getPriceInnerRecordHandling().equals(PriceInnerRecordHandling.LOWEST_PRICE) &&
					entity.getPrices(CURRENCY_CZK).stream()
						.anyMatch(price -> price.priceList().equals(PRICE_LIST_BASIC)) &&
					entity.getPrices(CURRENCY_CZK).stream()
						.anyMatch(price -> price.priceList().equals(PRICE_LIST_REFERENCE)) &&
					entity.getPrices(CURRENCY_CZK).stream()
						.anyMatch(price -> price.priceList().equals(PRICE_LIST_VIP)))
			.map(entity -> entity.getPrimaryKey())
			.toList();
		assertTrue(desiredEntities.size() > 1);

		tester.test(TEST_CATALOG)
			.document("""
				query {
					listProduct(
						filterBy: {
							entityPrimaryKeyInSet: %s,
							priceInPriceLists: "basic",
							priceInCurrency: CZK
						}
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
				serializeIntArrayToQueryString(desiredEntities))
			.executeAndExpectErrorsAndThen();
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

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    listProduct(
	                        filterBy: {
	                            attributeCodeInSet: ["%s", "%s"]
	                            entityLocaleEquals: en
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

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    listProduct(
	                        filterBy: {
	                            attributeCodeInSet: ["%s", "%s"]
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
						.e(
							AttributesProviderDescriptor.ATTRIBUTES.name(), map()
							.e(TYPENAME_FIELD, AttributesDescriptor.THIS.name(createEmptyEntitySchema("Product"), createEmptyEntitySchema("Parameter")))
							.e(ATTRIBUTE_MARKET_SHARE, reference.getAttribute(ATTRIBUTE_MARKET_SHARE).toString())
							.build())
						.e(ReferenceDescriptor.REFERENCED_ENTITY.name(), map()
							.e(TYPENAME_FIELD, "Parameter")
							.e(EntityDescriptor.PRIMARY_KEY.name(), reference.getReferencedPrimaryKey())
							.e(EntityDescriptor.TYPE.name(), reference.getReferencedEntityType())
							.e(
								AttributesProviderDescriptor.ATTRIBUTES.name(), map()
								.e(ATTRIBUTE_CODE, referencedEntity.getAttribute(ATTRIBUTE_CODE))
								.build())
							.build())
						.e(ReferenceDescriptor.GROUP_ENTITY.name(), map()
							.e(TYPENAME_FIELD, "ParameterGroup")
							.e(EntityDescriptor.PRIMARY_KEY.name(), reference.getGroup().get().getPrimaryKey())
							.e(EntityDescriptor.TYPE.name(), reference.getGroup().get().getType())
							.e(
								AttributesProviderDescriptor.ATTRIBUTES.name(), map()
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
	                    listProduct(
	                        filterBy: {
	                            attributeCodeInSet: ["%s", "%s"]
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

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    listProduct(
	                        filterBy: {
	                            attributeCodeInSet: ["%s", "%s"]
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
	@DisplayName("Should return sublist of references for products")
	void shouldReturnSublistOfReferencesForProducts(Evita evita, GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final var entities = findEntities(
			originalProductEntities,
			it -> it.getReferences(Entities.STORE).size() >= 4,
			2
		);

		final var expectedBody = entities.stream()
			.map(entity -> map()
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
				.build()
			)
			.toList();

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    listProduct(
	                        filterBy: {
	                            attributeCodeInSet: ["%s", "%s"]
	                        }
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
	@DisplayName("Should return reference page for products")
	void shouldReturnReferencePageForProducts(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final var entities = findEntities(
			originalProductEntities,
			it -> it.getReferences(Entities.STORE).size() >= 4,
			2
		);

		final var expectedBody = entities.stream()
			.map(entity -> map()
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
				.build()
			)
			.toList();

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    listProduct(
	                        filterBy: {
	                            attributeCodeInSet: ["%s", "%s"]
	                        }
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
	@DisplayName("Should return reference strip for products")
	void shouldReturnReferenceStripForProducts(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final var entities = findEntities(
			originalProductEntities,
			it -> it.getReferences(Entities.STORE).size() >= 4,
			2
		);

		final var expectedBody = entities.stream()
			.map(entity -> map()
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
				.build()
			)
			.toList();

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    listProduct(
	                        filterBy: {
	                            attributeCodeInSet: ["%s", "%s"]
	                        }
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

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    listProduct(
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

		tester.test(TEST_CATALOG)
			.document(
				"""
		            query {
		                listProduct(
		                    filterBy: {
		                        attributePriorityLessThan: "35000"
		                    }
		                    orderBy: [
		                        {
			                        attributeCreatedNatural: DESC
			                    },
		                        {
		                            attributeManufacturedNatural: ASC
		                        }
		                    ]
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
	@DisplayName("Should order entities by sortable attribute compound")
	void shouldOrderEntitiesBySortableAttributeCompound(Evita evita, GraphQLTester tester) {
		final Integer[] expectedEntities = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.query(
						query(
							collection(Entities.PRODUCT),
							filterBy(
								entityLocaleEquals(CZECH_LOCALE)
							),
							orderBy(
								attributeNatural(SORTABLE_ATTRIBUTE_COMPOUND_CODE_NAME, DESC)
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
		Assert.isPremiseValid(expectedEntities.length == 30, "Expected entities");

		tester.test(TEST_CATALOG)
			.document(
				"""
		            query {
		                listProduct(
		                    filterBy: {
		                        entityLocaleEquals: cs_CZ
		                    },
		                    orderBy: {
		                        attributeCodeNameNatural: DESC
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
			.body(
				resultPath(PRODUCT_LIST_PATH, EntityDescriptor.PRIMARY_KEY.name()),
				contains(expectedEntities)
			);
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

		tester.test(TEST_CATALOG)
			.document(
				"""
		            query {
		                listProduct(
		                    filterBy: {
		                        attributePriorityLessThan: "35000"
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

	@Test
	@UseDataSet(GRAPHQL_HUNDRED_PRODUCTS_FOR_SEGMENTS)
	@DisplayName("Should return entities in manually crafter segmented order")
	void shouldReturnDifferentlySortedSegments(Evita evita, GraphQLTester tester) {
		final Segments evitaQLSegments = segments(
			segment(
				orderBy(
					attributeNatural(ATTRIBUTE_NAME, OrderDirection.DESC)
				),
				limit(5)
			),
			segment(
				orderBy(
					attributeNatural(ATTRIBUTE_EAN, OrderDirection.DESC)
				),
				limit(2)
			),
			segment(
				orderBy(
					attributeNatural(ATTRIBUTE_QUANTITY, OrderDirection.ASC)
				),
				limit(2)
			)
		);
		final String graphQLSegments = """
			segments: [
			  {
			    segment: {
			      orderBy: {
			        attributeNameNatural: DESC
			      },
			      limit: 5
			    }
			  },
			  {
			    segment: {
			      orderBy: {
			        attributeEanNatural: DESC
			      },
			      limit: 2
			    }
			  },
			  {
			    segment: {
			      orderBy: {
			        attributeQuantityNatural: ASC
			      },
			      limit: 2
			    }
			  }
			]
			""";


		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				assertGraphQLSegmentedQuery(
					"First page must be sorted by name in descending order.",
					session, tester,
					fabricateEvitaQLSegmentedQuery(0, 5, evitaQLSegments),
					fabricateGraphQLSegmentedQuery(0, 5, graphQLSegments)
				);

				assertGraphQLSegmentedQuery(
					"Second page must be sorted by ean in descending order and quantity in asceding order.",
					session, tester,
					fabricateEvitaQLSegmentedQuery(5, 5, evitaQLSegments),
					fabricateGraphQLSegmentedQuery(5, 5, graphQLSegments)
				);

				assertGraphQLSegmentedQuery(
					"Third page must be sorted by PK in ascending order.",
					session, tester,
					fabricateEvitaQLSegmentedQuery(10, 5, evitaQLSegments),
					fabricateGraphQLSegmentedQuery(10, 5, graphQLSegments)
				);

				return null;
			}
		);
	}

	@Test
	@UseDataSet(GRAPHQL_HUNDRED_PRODUCTS_FOR_SEGMENTS)
	@DisplayName("Should return filtered entities in manually crafter segmented order")
	void shouldReturnDifferentlySortedAndFilteredSegments(Evita evita, GraphQLTester tester) {
		final Segments evitaQLSegments = segments(
			segment(
				entityHaving(
					attributeLessThanEquals(ATTRIBUTE_NAME, "L")
				),
				orderBy(
					attributeNatural(ATTRIBUTE_NAME, OrderDirection.DESC)
				),
				limit(10)
			),
			segment(
				entityHaving(
					attributeLessThanEquals(ATTRIBUTE_NAME, "P")
				),
				orderBy(
					attributeNatural(ATTRIBUTE_EAN, OrderDirection.DESC)
				),
				limit(8)
			),
			segment(
				entityHaving(
					attributeLessThanEquals(ATTRIBUTE_NAME, "T")
				),
				orderBy(
					attributeNatural(ATTRIBUTE_QUANTITY, OrderDirection.ASC)
				),
				limit(6)
			)
		);
		final String graphQLSegments = """
			segments: [
			  {
			    segment: {
			      entityHaving: {
			        attributeNameLessThanEquals: "L"
			      },
			      orderBy: {
			        attributeNameNatural: DESC
			      },
			      limit: 10
			    }
			  },
			  {
			    segment: {
			      entityHaving: {
			        attributeNameLessThanEquals: "P"
			      },
			      orderBy: {
			        attributeEanNatural: DESC
			      },
			      limit: 8
			    }
			  },
			  {
			    segment: {
			      entityHaving: {
			        attributeNameLessThanEquals: "T"
			      },
			      orderBy: {
			        attributeQuantityNatural: ASC
			      },
			      limit: 6
			    }
			  }
			]
			""";


		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				assertGraphQLSegmentedQuery(
					"First page must be sorted by name in descending order.",
					session, tester,
					fabricateEvitaQLSegmentedQuery(0, 5, evitaQLSegments),
					fabricateGraphQLSegmentedQuery(0, 5, graphQLSegments)
				);

				assertGraphQLSegmentedQuery(
					"Second page must be sorted by name in descending order.",
					session, tester,
					fabricateEvitaQLSegmentedQuery(5, 5, evitaQLSegments),
					fabricateGraphQLSegmentedQuery(5, 5, graphQLSegments)
				);

				assertGraphQLSegmentedQuery(
					"Third page must be sorted by EAN in descending order (excluding items on first two pages).",
					session, tester,
					fabricateEvitaQLSegmentedQuery(10, 5, evitaQLSegments),
					fabricateGraphQLSegmentedQuery(10, 5, graphQLSegments)
				);

				assertGraphQLSegmentedQuery(
					"Fourth page contains 3 entities sorted according to EAN in descending order and ends with first 2 entities sorted according to quantity in ascending order.",
					session, tester,
					fabricateEvitaQLSegmentedQuery(15, 5, evitaQLSegments),
					fabricateGraphQLSegmentedQuery(15, 5, graphQLSegments)
				);

				assertGraphQLSegmentedQuery(
					"Fifth page must have only 4 entities be sorted by quantity in ascending order and must end with first entity sorted by PK in ascending order.",
					session, tester,
					fabricateEvitaQLSegmentedQuery(20, 5, evitaQLSegments),
					fabricateGraphQLSegmentedQuery(20, 5, graphQLSegments)
				);

				assertGraphQLSegmentedQuery(
					"Sixth page must be sorted by PK in ascending order (but only from those entities that hasn't been already provided).",
					session, tester,
					fabricateEvitaQLSegmentedQuery(25, 5, evitaQLSegments),
					fabricateGraphQLSegmentedQuery(25, 5, graphQLSegments)
				);

				assertGraphQLSegmentedQuery(
					"Seventh page must be sorted by PK in ascending order (but only from those entities that hasn't been already provided).",
					session, tester,
					fabricateEvitaQLSegmentedQuery(30, 5, evitaQLSegments),
					fabricateGraphQLSegmentedQuery(30, 5, graphQLSegments)
				);

				return null;
			}
		);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should pass query labels")
	void shouldPassQueryLabels(GraphQLTester tester) {
		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    listProduct(
	                        head: [
	                            {
	                                label: {
	                                    name: "myLabel1"
	                                    value: "myValue1"
	                                }
	                            },
	                            {
	                                label: {
	                                    name: "myLabel2"
	                                    value: 100
	                                }
	                            }
	                        ]
	                        filterBy: {
	                            attributeCodeContains: "a"
	                        }
	                    ) {
	                        primaryKey
	                    }
	                }
					"""
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(PRODUCT_LIST_PATH, hasSize(greaterThan(0)));
	}

	@DisplayName("Should order by price")
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@Test
	void shouldOrderByPrice(Evita evita, GraphQLTester tester) {
		final EvitaResponse<EntityClassifier> expectedEntities = queryEntities(
			evita,
			query(
				collection(Entities.PRODUCT),
				filterBy(
					priceInPriceLists(PRICE_LIST_BASIC),
					priceInCurrency(CURRENCY_CZK),
					priceValidInNow()
				),
				orderBy(
					priceNatural(DESC)
				)
			),
			EntityClassifier.class
		);

		tester.test(TEST_CATALOG)
			.document("""
				{
					listProduct(
						filterBy: {
							priceInPriceLists: "basic",
							priceInCurrency: CZK,
							priceValidInNow: true
						},
						orderBy: {
							priceNatural: DESC
						}
					) {
						primaryKey
					}
				}
				""")
			.executeAndExpectOkAndThen()
			.body(
				resultPath(PRODUCT_LIST_PATH, EntityDescriptor.PRIMARY_KEY.name()),
				equalTo(expectedEntities.getRecordData().stream().map(EntityClassifier::getPrimaryKeyOrThrowException).toList())
			);
	}



	@Nonnull
	private List<SealedEntity> findEntities(@Nonnull List<SealedEntity> originalProductEntities,
	                                        @Nonnull Predicate<SealedEntity> filter) {
		// backward compatibility default - no special meaning to the "2" limit
		return findEntities(originalProductEntities, filter, 2);
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
	private List<SealedEntity> findEntitiesWithPrice(List<SealedEntity> originalProductEntities) {
		return findEntities(
			originalProductEntities,
			it -> it.getPriceInnerRecordHandling().equals(PriceInnerRecordHandling.NONE) && it.getPrices(CURRENCY_CZK, PRICE_LIST_BASIC).size() == 1
		);
	}

	@Nonnull
	private List<SealedEntity> findEntitiesWithPrice(List<SealedEntity> originalProductEntities, @Nonnull String... priceLists) {
		return findEntities(
			originalProductEntities,
			it -> Arrays.stream(priceLists).allMatch(pl -> it.getPrices(CURRENCY_CZK, pl).size() == 1)
		);
	}


	@Nonnull
	private static Query fabricateEvitaQLSegmentedQuery(int offset, int limit, @Nonnull Segments segments) {
		return query(
			collection(Entities.PRODUCT),
			filterBy(entityLocaleEquals(Locale.ENGLISH)),
			orderBy(segments),
			require(
				strip(offset, limit),
				debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
			)
		);
	}

	@Nonnull
	private static String fabricateGraphQLSegmentedQuery(int offset, int limit, @Nonnull String segments) {
		return String.format(
			"""
			{
			  listProduct(
			    filterBy: {
			      entityLocaleEquals: en
			    }
			    orderBy: {
			      %s
			    }
			    offset: %d
			    limit: %d
			  ) {
		        primaryKey
			  }
			}
			""",
			segments,
			offset,
			limit
		);
	}

	private void assertGraphQLSegmentedQuery(@Nonnull String message,
	                                         @Nonnull EvitaSessionContract session,
	                                         @Nonnull GraphQLTester tester,
	                                         @Nonnull Query sampleEvitaQLQuery,
	                                         @Nonnull String targetGraphQLQuery) {
		final int[] expectedEntities = session.query(sampleEvitaQLQuery, EntityReference.class)
			.getRecordData()
			.stream()
			.mapToInt(EntityReference::getPrimaryKey)
			.toArray();
		assertEquals(5, expectedEntities.length);
		final List<Integer> actualEntities = tester.test(TEST_CATALOG)
			.document(targetGraphQLQuery)
			.executeAndExpectOkAndThen()
			.extract()
			.body()
			.jsonPath()
			.getList(resultPath(PRODUCT_LIST_PATH, EntityDescriptor.PRIMARY_KEY.name()), Integer.class);
		assertSortedResultEquals(
			message,
			actualEntities,
			expectedEntities
		);
	}
}
