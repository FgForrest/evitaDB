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

import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.ReferenceContract.GroupEntityReference;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.api.catalog.dataApi.model.EntityDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.PriceDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.ReferenceDescriptor;
import io.evitadb.externalApi.graphql.GraphQLProvider;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.GraphQLEntityDescriptor;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.DataCarrier;
import io.evitadb.test.tester.GraphQLTester;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import static graphql.Assert.assertTrue;
import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.externalApi.graphql.api.testSuite.TestDataGenerator.*;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.builder.MapBuilder.map;
import static io.evitadb.test.generator.DataGenerator.ASSOCIATED_DATA_LABELS;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_NAME;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_QUANTITY;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for GraphQL catalog entity upserts.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class CatalogGraphQLUpsertEntityMutationFunctionalTest extends CatalogGraphQLDataEndpointFunctionalTest {

	private static final String GET_PRODUCT_PATH = "data.getProduct";
	private static final String GET_CATEGORY_PATH = "data.getCategory";
	private static final String UPSERT_PRODUCT_PATH = "data.upsertProduct";
	private static final String UPSERT_EMPTY_PATH = "data.upsertEmpty";
	private static final String UPSERT_EMPTY_WITHOUT_PK_PATH = "data.upsertEmptyWithoutPk";
	private static final String UPSERT_CATEGORY_PATH = "data.upsertCategory";
	private static final String GRAPHQL_THOUSAND_PRODUCTS_FOR_UPDATE = GRAPHQL_THOUSAND_PRODUCTS + "forUpdate";

	@Override
	@DataSet(value = GRAPHQL_THOUSAND_PRODUCTS_FOR_UPDATE, openWebApi = GraphQLProvider.CODE, readOnly = false, destroyAfterClass = true)
	protected DataCarrier setUp(Evita evita) {
		return super.setUpData(evita, 50);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS_FOR_UPDATE)
	@DisplayName("Should insert single empty product without PK")
	void shouldInsertSingleEmptyProductWithoutPK(GraphQLTester tester) {
		tester.test(TEST_CATALOG)
			.document(
				"""
	                mutation {
	                    upsertEmpty (entityExistence: MUST_NOT_EXIST) {
	                        __typename
	                        primaryKey
	                    }
	                }
					"""
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				UPSERT_EMPTY_PATH,
				equalTo(
					map()
						.e(TYPENAME_FIELD, "Empty")
						.e(EntityDescriptor.PRIMARY_KEY.name(), 11)
						.build()
				)
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS_FOR_UPDATE)
	@DisplayName("Should insert single empty product with PK")
	void shouldInsertSingleEmptyProductWithPK(GraphQLTester tester) {
		tester.test(TEST_CATALOG)
			.document(
				"""
	                mutation {
	                    upsertEmptyWithoutPk(primaryKey: 200, entityExistence: MUST_NOT_EXIST) {
	                        primaryKey
	                    }
	                }
					"""
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				UPSERT_EMPTY_WITHOUT_PK_PATH,
				equalTo(
					map()
						.e(EntityDescriptor.PRIMARY_KEY.name(), 200)
						.build()
				)
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS_FOR_UPDATE)
	@DisplayName("Should update product with no mutations")
	void shouldUpdateProductWithNoMutations(GraphQLTester tester) {
		tester.test(TEST_CATALOG)
			.document(
				"""
	                mutation {
	                    upsertProduct(primaryKey: 10, entityExistence: MUST_EXIST) {
	                        primaryKey
	                    }
	                }
					"""
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				UPSERT_PRODUCT_PATH,
				equalTo(
					map()
						.e(EntityDescriptor.PRIMARY_KEY.name(), 10)
						.build()
				)
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS_FOR_UPDATE)
	@DisplayName("Should return error when missing arguments for product upsert")
	void shouldReturnErrorWhenMissingArgumentsForProductUpsert(GraphQLTester tester) {
		tester.test(TEST_CATALOG)
			.document(
				"""
	                mutation {
	                    upsertProduct {
	                        primaryKey
	                    }
	                }
					"""
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, hasSize(greaterThan(0)));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS_FOR_UPDATE)
	@DisplayName("Should return error when missing mutations for product update")
	void shouldReturnErrorWhenMissingMutationsForProductUpdate(GraphQLTester tester) {
		tester.test(TEST_CATALOG)
			.document(
				"""
	                mutation {
	                    upsertProduct(primaryKey: 1001, entityExistence: MAY_EXIST) {
	                        primaryKey
	                    }
	                }
					"""
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, hasSize(greaterThan(0)));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS_FOR_UPDATE)
	@DisplayName("Should update product with attribute mutations")
	void shouldUpdateProductWithAttributeMutations(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final SealedEntity entity = originalProductEntities.stream()
			.filter(it -> it.getAttribute(ATTRIBUTE_DEPRECATED) != null)
			.findFirst()
			.orElseThrow();

		final Map<String, Object> expectedBody = map()
			.e(EntityDescriptor.PRIMARY_KEY.name(), entity.getPrimaryKey())
			.e(EntityDescriptor.ATTRIBUTES.name(), map()
				.e(ATTRIBUTE_NAME, "nový produkt")
				.e(ATTRIBUTE_QUANTITY, ((BigDecimal) entity.getAttribute(ATTRIBUTE_QUANTITY)).add(BigDecimal.TEN).toString())
				.e(ATTRIBUTE_DEPRECATED, null)
				.build())
			.build();

		tester.test(TEST_CATALOG)
			.document(
				"""
	                mutation {
	                    upsertProduct(
	                        primaryKey: %d
	                        entityExistence: MUST_EXIST
	                        mutations: [
	                            {
	                                upsertAttributeMutation: {
	                                    name: "name"
	                                    locale: "cs-CZ"
	                                    value: "nový produkt"
	                                    valueType: String
	                                }
	                            },
	                            {
	                                applyDeltaAttributeMutation: {
	                                    name: "quantity"
	                                    delta: "10.0"
	                                }
	                            },
	                            {
	                                removeAttributeMutation: {
	                                    name: "deprecated"
	                                }
	                            }
	                        ]
                        ) {
	                        primaryKey
	                        attributes(locale: cs_CZ) {
	                            name
	                            quantity
	                            deprecated
	                        }
	                    }
	                }
					""",
				entity.getPrimaryKey()
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(UPSERT_PRODUCT_PATH, equalTo(expectedBody));

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    getProduct(primaryKey: %d) {
	                        primaryKey
	                        attributes(locale: cs_CZ) {
	                            name
	                            quantity
	                            deprecated
	                        }
	                    }
	                }
					""",
				entity.getPrimaryKey()
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(GET_PRODUCT_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS_FOR_UPDATE)
	@DisplayName("Should update product with associated data mutations")
	void shouldUpdateProductWithAssociatedDataMutations(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final SealedEntity entity = originalProductEntities.stream()
			.filter(it -> it.getAssociatedData(ASSOCIATED_DATA_LOCALIZATION) != null)
			.findFirst()
			.orElseThrow();

		final Map<String, Object> expectedBody = map()
			.e(EntityDescriptor.PRIMARY_KEY.name(), entity.getPrimaryKey())
			.e(EntityDescriptor.ASSOCIATED_DATA.name(), map()
				.e(ASSOCIATED_DATA_LABELS, map()
					.e("someField", "differentValue")
					.build())
				.e(ASSOCIATED_DATA_LOCALIZATION, null)
				.build())
			.build();

		tester.test(TEST_CATALOG)
			.document(
				"""
	                mutation {
	                    upsertProduct(
	                        primaryKey: %d
	                        entityExistence: MUST_EXIST
	                        mutations: [
	                            {
	                                upsertAssociatedDataMutation: {
	                                    name: "labels"
	                                    locale: "cs-CZ"
	                                    value: {
	                                        someField: "differentValue"
	                                    }
	                                    valueType: ComplexDataObject
	                                }
	                            },
	                            {
	                                removeAssociatedDataMutation: {
	                                    name: "localization"
	                                }
	                            }
	                        ]
                        ) {
	                        primaryKey
	                        associatedData(locale: cs_CZ) {
	                            labels
	                            localization
	                        }
	                    }
	                }
					""",
				entity.getPrimaryKey()
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(UPSERT_PRODUCT_PATH, equalTo(expectedBody));

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    getProduct(primaryKey: %d) {
	                        primaryKey
	                        associatedData(locale: cs_CZ) {
	                            labels
	                            localization
	                        }
	                    }
	                }
					""",
				entity.getPrimaryKey()
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(GET_PRODUCT_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS_FOR_UPDATE)
	@DisplayName("Should update category with hierarchical placement mutations")
	void shouldUpdateCategoryWithHierarchicalPlacementMutations(Evita evita, GraphQLTester tester) {
		final SealedEntity rootEntity = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.queryOneSealedEntity(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							hierarchyWithinRootSelf(directRelation())
						),
						require(
							strip(0, 1),
							entityFetch()
						)
					)
				).orElseThrow();
			}
		);
		final SealedEntity entityInTree = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.queryOneSealedEntity(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							hierarchyWithinSelf(
								entityPrimaryKeyInSet(rootEntity.getPrimaryKey()),
								directRelation()
							)
						),
						require(
							strip(1, 1),
							entityFetch()
						)
					)
				).orElseThrow();
			}
		);

		assertTrue(rootEntity.getParent().isEmpty());
		assertEquals(rootEntity.getPrimaryKey(), entityInTree.getParent().orElseThrow());

		// remove existing parent reference
		final Map<String, Object> expectedBodyWithoutParent = map()
			.e(GraphQLEntityDescriptor.PRIMARY_KEY.name(), entityInTree.getPrimaryKey())
			.e(GraphQLEntityDescriptor.PARENT_PRIMARY_KEY.name(), null)
			.build();
		tester.test(TEST_CATALOG)
			.document(
				"""
	                mutation {
	                    upsertCategory(
	                        primaryKey: %d
	                        entityExistence: MUST_EXIST
	                        mutations: [
	                            {
	                                removeParentMutation: true
	                            }
	                        ]
                        ) {
	                        primaryKey
	                        parentPrimaryKey
	                    }
	                }
					""",
				entityInTree.getPrimaryKey()
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(UPSERT_CATEGORY_PATH, equalTo(expectedBodyWithoutParent));
		assertParentPrimaryKey(tester, entityInTree.getPrimaryKey(), expectedBodyWithoutParent);

		// revert original parent
		final Map<String, Object> expectedBodyReverted = map()
			.e(GraphQLEntityDescriptor.PRIMARY_KEY.name(), entityInTree.getPrimaryKey())
			.e(GraphQLEntityDescriptor.PARENT_PRIMARY_KEY.name(), rootEntity.getPrimaryKey())
			.build();
		tester.test(TEST_CATALOG)
			.document(
				"""
	                mutation {
	                    upsertCategory(
	                        primaryKey: %d
	                        entityExistence: MUST_EXIST
	                        mutations: [
	                            {
	                                setParentMutation: {
	                                    parentPrimaryKey: %d
	                                }
	                            }
	                        ]
                        ) {
	                        primaryKey
	                        parentPrimaryKey
	                    }
	                }
					""",
				entityInTree.getPrimaryKey(),
				rootEntity.getPrimaryKey()
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(UPSERT_CATEGORY_PATH, equalTo(expectedBodyReverted));
		assertParentPrimaryKey(tester, entityInTree.getPrimaryKey(), expectedBodyReverted);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS_FOR_UPDATE)
	@DisplayName("Should update product with new price mutation")
	void shouldUpdateProductWithNewPriceMutation(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final SealedEntity entity = originalProductEntities.stream()
			.filter(it -> it.getPrices()
				.stream()
				.noneMatch(it2 -> it2.getPriceId() == 1_000_000_000))
			.findFirst()
			.orElseThrow();

		final Map<String, Object> expectedBodyWithNewPrice = map()
			.e(EntityDescriptor.PRIMARY_KEY.name(), entity.getPrimaryKey())
			.e(EntityDescriptor.PRICES.name(), List.of(
				map()
					.e(PriceDescriptor.PRICE_ID.name(), 1_000_000_000)
					.e(PriceDescriptor.PRICE_LIST.name(), "other")
					.e(PriceDescriptor.CURRENCY.name(), "CZK")
					.e(PriceDescriptor.INNER_RECORD_ID.name(), null)
					.e(PriceDescriptor.PRICE_WITHOUT_TAX.name(), "1.0")
					.e(PriceDescriptor.TAX_RATE.name(), "21")
					.e(PriceDescriptor.PRICE_WITH_TAX.name(), "1.21")
					.e(PriceDescriptor.VALIDITY.name(), null)
					.e(PriceDescriptor.SELLABLE.name(), false)
					.build()
			))
			.build();

		tester.test(TEST_CATALOG)
			.document(
				"""
	                mutation {
	                    upsertProduct(
	                        primaryKey: %d
	                        entityExistence: MUST_EXIST
	                        mutations: [
	                            {
	                                upsertPriceMutation: {
	                                    priceId: 1000000000
	                                    priceList: "other"
	                                    currency: "CZK"
	                                    priceWithoutTax: "1.0"
	                                    taxRate: "21"
	                                    priceWithTax: "1.21"
	                                    sellable: false
	                                }
	                            }
	                        ]
                        ) {
	                        primaryKey
	                        prices(priceLists: "other") {
	                            priceId
								priceList
								currency
								innerRecordId
								priceWithoutTax
								taxRate
								priceWithTax
								validity
								sellable
	                        }
	                    }
	                }
					""",
				entity.getPrimaryKey()
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(UPSERT_PRODUCT_PATH, equalTo(expectedBodyWithNewPrice));

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    getProduct(primaryKey: %d) {
	                        primaryKey
	                        prices(priceLists: "other") {
	                            priceId
								priceList
								currency
								innerRecordId
								priceWithoutTax
								taxRate
								priceWithTax
								validity
								sellable
	                        }
	                    }
	                }
					""",
				entity.getPrimaryKey()
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(GET_PRODUCT_PATH, equalTo(expectedBodyWithNewPrice));

		final Map<String, Object> expectedBodyWithoutNewPrice = map()
			.e(EntityDescriptor.PRIMARY_KEY.name(), entity.getPrimaryKey())
			.e(EntityDescriptor.PRICES.name(), List.of())
			.build();

		tester.test(TEST_CATALOG)
			.document(
				"""
	                mutation {
	                    upsertProduct(
	                        primaryKey: %d
	                        entityExistence: MUST_EXIST
	                        mutations: [
	                            {
	                                removePriceMutation: {
	                                    priceId: 1000000000
	                                    priceList: "other"
	                                    currency: "CZK"
	                                }
	                            }
	                        ]
                        ) {
	                        primaryKey
	                        prices(priceLists: "other") {
	                            priceId
	                        }
	                    }
	                }
					""",
				entity.getPrimaryKey()
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(UPSERT_PRODUCT_PATH, equalTo(expectedBodyWithoutNewPrice));

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    getProduct(primaryKey: %d) {
	                        primaryKey
	                        prices(priceLists: "other") {
	                            priceId
	                        }
	                    }
	                }
					""",
				entity.getPrimaryKey()
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(GET_PRODUCT_PATH, equalTo(expectedBodyWithoutNewPrice));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS_FOR_UPDATE)
	@DisplayName("Should update product with price inner handling mutation")
	void shouldUpdateProductWithPriceInnerRecordHandlingMutation(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final SealedEntity entity = originalProductEntities.stream()
			.filter(it -> it.getPriceInnerRecordHandling().equals(PriceInnerRecordHandling.NONE))
			.findFirst()
			.orElseThrow();

		final Map<String, Object> expectedBody = map()
			.e(EntityDescriptor.PRIMARY_KEY.name(), entity.getPrimaryKey())
			.e(EntityDescriptor.PRICE_INNER_RECORD_HANDLING.name(), "SUM")
			.build();

		tester.test(TEST_CATALOG)
			.document(
				"""
	                mutation {
	                    upsertProduct(
	                        primaryKey: %d
	                        entityExistence: MUST_EXIST
	                        mutations: [
	                            {
	                                setPriceInnerRecordHandlingMutation: {
	                                    priceInnerRecordHandling: SUM
	                                }
	                            }
	                        ]
                        ) {
	                        primaryKey
	                        priceInnerRecordHandling
	                    }
	                }
					""",
				entity.getPrimaryKey()
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(UPSERT_PRODUCT_PATH, equalTo(expectedBody));

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    getProduct(primaryKey: %d) {
	                        primaryKey
	                        priceInnerRecordHandling
	                    }
	                }
					""",
				entity.getPrimaryKey()
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(GET_PRODUCT_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS_FOR_UPDATE)
	@DisplayName("Should update product with reference mutations")
	void shouldUpdateProductWithReferenceMutations(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final SealedEntity entity = originalProductEntities.stream()
			.filter(it -> it.getReferences(Entities.STORE)
				.stream()
				.noneMatch(it2 -> it2.getReferencedPrimaryKey() == 1_000_000_000))
			.findFirst()
			.orElseThrow();

		var expectedBody = entity.getReferences(Entities.STORE)
			.stream()
			.map(r -> map()
				.e(ReferenceDescriptor.REFERENCED_PRIMARY_KEY.name(), r.getReferencedPrimaryKey())
				.e(ReferenceDescriptor.ATTRIBUTES.name(), map()
					.e(ATTRIBUTE_STORE_VISIBLE_FOR_B2C, r.getAttribute(ATTRIBUTE_STORE_VISIBLE_FOR_B2C))
					.build())
				.build())
			.toList();
		expectedBody = new LinkedList<>(expectedBody);
		expectedBody.add(map()
			.e(ReferenceDescriptor.REFERENCED_PRIMARY_KEY.name(), 1_000_000_000)
			.e(ReferenceDescriptor.ATTRIBUTES.name(), map()
				.e(ATTRIBUTE_STORE_VISIBLE_FOR_B2C, true))
			.build());

		tester.test(TEST_CATALOG)
			.document(
				"""
	                mutation {
	                    upsertProduct(
	                        primaryKey: %d
	                        entityExistence: MUST_EXIST
	                        mutations: [
	                            {
	                                insertReferenceMutation: {
	                                    name: "STORE"
	                                    primaryKey: 1000000000
	                                }
	                            },
	                            {
	                                referenceAttributeMutation: {
	                                    name: "STORE"
	                                    primaryKey: 1000000000
	                                    attributeMutation: {
	                                        upsertAttributeMutation: {
	                                            name: "storeVisibleForB2C"
	                                            value: true
	                                            valueType: Boolean
	                                        }
	                                    }
	                                }
	                            }
	                        ]
                        ) {
	                        store {
	                            referencedPrimaryKey
	                            attributes {
	                                storeVisibleForB2C
	                            }
	                        }
	                    }
	                }
					""",
				entity.getPrimaryKey()
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(UPSERT_PRODUCT_PATH + ".store", equalTo(expectedBody));

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    getProduct(primaryKey: %d) {
	                        store {
	                            referencedPrimaryKey,
	                            attributes {
	                                storeVisibleForB2C
	                            }
	                        }
	                    }
	                }
					""",
				entity.getPrimaryKey()
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(GET_PRODUCT_PATH + ".store", equalTo(expectedBody));


		tester.test(TEST_CATALOG)
			.document(
				"""
	                mutation {
	                    upsertProduct(
	                        primaryKey: %d
	                        entityExistence: MUST_EXIST
	                        mutations: [
	                            {
	                                removeReferenceMutation: {
	                                    name: "STORE"
	                                    primaryKey: 1000000000
	                                }
	                            }
	                        ]
                        ) {
	                        store {
	                            referencedEntity {
	                                primaryKey
	                            }
	                        }
	                    }
	                }
					""",
				entity.getPrimaryKey()
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(UPSERT_PRODUCT_PATH + ".store." + ReferenceDescriptor.REFERENCED_ENTITY.name() + "." + EntityDescriptor.PRIMARY_KEY.name(), not(containsInRelativeOrder(1_000_000_000)));

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    getProduct(primaryKey: %d) {
	                        store {
	                            referencedEntity {
	                                primaryKey
	                            }
	                        }
	                    }
	                }
					""",
				entity.getPrimaryKey()
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(GET_PRODUCT_PATH + ".store." + ReferenceDescriptor.REFERENCED_ENTITY.name() + "." + EntityDescriptor.PRIMARY_KEY.name(), not(containsInRelativeOrder(1_000_000_000)));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS_FOR_UPDATE)
	@DisplayName("Should update product with reference group mutations")
	void shouldUpdateProductWithReferenceGroupMutations(Evita evita, GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final SealedEntity entity = originalProductEntities.stream()
			.filter(it -> it.getReferences(REFERENCE_BRAND_WITH_GROUP).isEmpty())
			.findFirst()
			.orElseThrow();

		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				entity.openForWrite()
					.setReference(REFERENCE_BRAND_WITH_GROUP, 1)
					.upsertVia(session);
			}
		);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                mutation {
	                    upsertProduct(
	                        primaryKey: %d
	                        entityExistence: MUST_EXIST
	                        mutations: [
	                            {
	                                setReferenceGroupMutation: {
	                                    name: "brandWithGroup"
	                                    primaryKey: 1
	                                    groupPrimaryKey: 100
	                                }
	                            }
	                        ]
                        ) {
	                        primaryKey
	                    }
	                }
					""",
				entity.getPrimaryKey()
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(UPSERT_PRODUCT_PATH + "." + EntityDescriptor.PRIMARY_KEY.name(), equalTo(entity.getPrimaryKey()));

		assertReferenceGroup(evita, entity.getPrimaryKey(), new GroupEntityReference(ENTITY_BRAND_GROUP, 100, 1, false));

		tester.test(TEST_CATALOG)
			.document(
				"""
	                mutation {
	                    upsertProduct(
	                        primaryKey: %d
	                        entityExistence: MUST_EXIST
	                        mutations: [
	                            {
	                                removeReferenceGroupMutation: {
	                                    name: "brandWithGroup"
	                                    primaryKey: 1
	                                }
	                            }
	                        ]
                        ) {
	                       primaryKey
	                    }
	                }
					""",
				entity.getPrimaryKey()
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(UPSERT_PRODUCT_PATH + "." + EntityDescriptor.PRIMARY_KEY.name(), equalTo(entity.getPrimaryKey()));

		assertReferenceGroup(evita, entity.getPrimaryKey(), null);

		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getEntity(Entities.PRODUCT, entity.getPrimaryKey(), referenceContentAll())
					.orElseThrow()
					.openForWrite()
					.removeReference(REFERENCE_BRAND_WITH_GROUP, 1)
					.upsertVia(session);
			}
		);
	}

	private void assertReferenceGroup(@Nonnull Evita evita, int primaryKey, @Nullable GroupEntityReference groupEntityReference) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final SealedEntity updatedEntity = session.getEntity(Entities.PRODUCT, primaryKey, referenceContentAll())
					.orElseThrow();
				assertEquals(
					groupEntityReference,
					updatedEntity.getReferences(REFERENCE_BRAND_WITH_GROUP).iterator().next().getGroup().orElse(null)
				);
			}
		);
	}


	private void assertParentPrimaryKey(@Nonnull GraphQLTester tester, int primaryKey, @Nonnull Map<String, Object> expectedBodyAfterRemoving) {
		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    getCategory(primaryKey: %d) {
	                        primaryKey
	                        parentPrimaryKey
	                    }
	                }
					""",
				primaryKey
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(GET_CATEGORY_PATH, equalTo(expectedBodyAfterRemoving));
	}
}
