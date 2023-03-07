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

package io.evitadb.externalApi.rest.io.handler;

import io.evitadb.api.requestResponse.data.HierarchicalPlacementContract;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract.GroupEntityReference;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.api.catalog.dataApi.model.EntityDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.HierarchicalPlacementDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.PriceDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.ReferenceDescriptor;
import io.evitadb.externalApi.rest.api.catalog.model.SectionedAssociatedDataDescriptor;
import io.evitadb.externalApi.rest.api.catalog.model.SectionedAttributesDescriptor;
import io.evitadb.externalApi.rest.testSuite.RESTTester.Request;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.UseDataSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.externalApi.rest.testSuite.TestDataGenerator.*;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.builder.MapBuilder.map;
import static io.evitadb.test.generator.DataGenerator.ASSOCIATED_DATA_LABELS;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_NAME;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_QUANTITY;
import static io.evitadb.test.generator.DataGenerator.CZECH_LOCALE;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsInRelativeOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for REST API catalog entity upserts.
 *
 * @author Martin Veska, FG Forrest a.s. (c) 2022
 */
class CatalogRESTUpsertEntityMutationFunctionalTest extends CatalogRESTEndpointFunctionalTest {

	@Nonnull
	@Override
	protected String getEndpointPath() {
		return "/test-catalog";
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should insert single empty entity without PK")
	void shouldInsertSingleEmptyEntityWithoutPK(Evita evita) {
		testRESTCall()
			.httpMethod(Request.METHOD_POST)
			.urlPathSuffix("/empty")
			.requestBody("""
                    {
                        "entityExistence": "MUST_NOT_EXIST",
                        "mutations": []
                    }
                    """)
			.executeAndThen()
			.statusCode(200)
			.body(
				"",
				equalTo(
					map()
						.e(EntityDescriptor.PRIMARY_KEY.name(), 101)
						.e(EntityDescriptor.TYPE.name(), "empty")
						.e(EntityDescriptor.LOCALES.name(), Collections.emptyList())
						.e(EntityDescriptor.ALL_LOCALES.name(), Collections.emptyList())
						.e(EntityDescriptor.PRICE_INNER_RECORD_HANDLING.name(), PriceInnerRecordHandling.UNKNOWN.name())
						.build()
				)
			);
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should insert single empty entity with PK")
	void shouldInsertSingleEmptyEntityWithPK(Evita evita) {
		testRESTCall()
			.httpMethod(Request.METHOD_PUT)
			.urlPathSuffix("/empty-without-pk/110")
			.requestBody("""
                    {
                        "entityExistence": "MUST_NOT_EXIST",
                        "mutations": []
                    }
                    """)
			.executeAndThen()
			.statusCode(200)
			.body(
				"",
				equalTo(
					map()
						.e(EntityDescriptor.PRIMARY_KEY.name(), 110)
						.e(EntityDescriptor.TYPE.name(), "emptyWithoutPk")
						.e(EntityDescriptor.LOCALES.name(), Collections.emptyList())
						.e(EntityDescriptor.ALL_LOCALES.name(), Collections.emptyList())
						.e(EntityDescriptor.PRICE_INNER_RECORD_HANDLING.name(), PriceInnerRecordHandling.UNKNOWN.name())
						.build()
				)
			);
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should update product with no mutations")
	void shouldUpdateProductWithNoMutations(Evita evita) {
		testRESTCall()
			.httpMethod(Request.METHOD_POST)
			.urlPathSuffix("/product")
			.requestBody("""
                    {
                        "primaryKey": 100,
                        "entityExistence": "MUST_EXIST",
                        "mutations": []
                    }
                    """)
			.executeAndThen()
			.statusCode(200)
			.body(
				"",
				equalTo(
					map()
						.e(EntityDescriptor.PRIMARY_KEY.name(), 100)
						.e(EntityDescriptor.TYPE.name(), "PRODUCT")
						.e(EntityDescriptor.LOCALES.name(), Collections.emptyList())
						.e(EntityDescriptor.ALL_LOCALES.name(), List.of(CZECH_LOCALE.toLanguageTag(), Locale.ENGLISH.toLanguageTag()))
						.e(EntityDescriptor.PRICE_INNER_RECORD_HANDLING.name(), PriceInnerRecordHandling.UNKNOWN.name())
						.build()
				)
			);
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return error when missing arguments for product upsert")
	void shouldReturnErrorWhenMissingArgumentsForProductUpsert(Evita evita) {
		testRESTCall()
			.httpMethod(Request.METHOD_POST)
			.urlPathSuffix("/product")
			.requestBody("""
                    {
                    }
                    """)
			.executeAndThen()
			.statusCode(400)
			.body("message", equalTo("EntityExistence is not set in request data."));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return error when missing mutations for product update")
	void shouldReturnErrorWhenMissingMutationsForProductUpdate(Evita evita) {
		testRESTCall()
			.httpMethod(Request.METHOD_POST)
			.urlPathSuffix("/product")
			.requestBody("""
                    {
                        "primaryKey": 100,
                        "entityExistence": "MUST_EXIST"
                    }
                    """)
			.executeAndThen()
			.statusCode(400)
			.body("message", equalTo("Mutations are not set in request data."));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should update product with attribute mutations")
	void shouldUpdateProductWithAttributeMutations(Evita evita, List<SealedEntity> originalProductEntities) {
		final SealedEntity entity = originalProductEntities.stream()
			.filter(it -> it.getAttribute(ATTRIBUTE_DEPRECATED) != null)
			.findFirst()
			.orElseThrow();

		final Map<String, Object> expectedBody = map()
			.e(EntityDescriptor.PRIMARY_KEY.name(), entity.getPrimaryKey())
			.e(EntityDescriptor.TYPE.name(), Entities.PRODUCT)
			.e(EntityDescriptor.LOCALES.name(), List.of(CZECH_LOCALE.toLanguageTag()))
			.e(EntityDescriptor.ALL_LOCALES.name(), List.of(CZECH_LOCALE.toLanguageTag(), Locale.ENGLISH.toLanguageTag()))
			.e(EntityDescriptor.PRICE_INNER_RECORD_HANDLING.name(), PriceInnerRecordHandling.UNKNOWN.name())
			.e(EntityDescriptor.ATTRIBUTES.name(), map()
				.e(SectionedAttributesDescriptor.GLOBAL.name(), map()
					.e(ATTRIBUTE_QUANTITY, ((BigDecimal) entity.getAttribute(ATTRIBUTE_QUANTITY)).add(BigDecimal.TEN).toString())
					.build())
				.e(SectionedAttributesDescriptor.LOCALIZED.name(), map()
					.e(CZECH_LOCALE.toLanguageTag(), map()
						.e(ATTRIBUTE_NAME, "nový produkt")
						.build())
					.build())
				.build())
			.build();

		testRESTCall()
			.httpMethod(Request.METHOD_POST)
			.urlPathSuffix("/product")
			.requestBody("""
                {
                    "primaryKey": %d,
                    "entityExistence": "MUST_EXIST",
                    "mutations": [
                        {
                            "upsertAttributeMutation": {
                                "name": "name",
                                "locale": "cs-CZ",
                                "value": "nový produkt",
                                "valueType": "String"
                            },
                            "applyDeltaAttributeMutation": {
                                "name": "quantity",
                                "delta": "10.0"
                            },
                            "removeAttributeMutation": {
                                "name": "deprecated"
                            }
                        }
                    ],
					"require": {
					    "entity_fetch": {
							"attribute_content": [
								"name",
								"quantity",
								"deprecated"
							],
							"dataInLocales": ["cs-CZ"]
				        }
					  }
					}
                }
                """,
				entity.getPrimaryKey())
			.executeAndThen()
			.statusCode(200)
			.body("",equalTo(expectedBody));

		testRESTCall()
			.urlPathSuffix("/product/list")
			.httpMethod(Request.METHOD_POST)
			.requestBody("""
				{
					"filterBy": {
						"entity_primaryKey_inSet": [%d]
					},
					"require": {
						"entity_fetch": {
							"attribute_content": [
								"name",
								"quantity",
								"deprecated"
							],
							"dataInLocales": ["cs-CZ"]
						}
					}
				}
				""",
				entity.getPrimaryKey())
			.executeAndThen()
			.statusCode(200)
			.body("",equalTo(Collections.singletonList(expectedBody)));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should update product with associated data mutations")
	void shouldUpdateProductWithAssociatedDataMutations(Evita evita, List<SealedEntity> originalProductEntities) {
		final SealedEntity entity = originalProductEntities.stream()
			.filter(it -> it.getAssociatedData(ASSOCIATED_DATA_LOCALIZATION) != null)
			.findFirst()
			.orElseThrow();

		final Map<String, Object> expectedBody = map()
			.e(EntityDescriptor.PRIMARY_KEY.name(), entity.getPrimaryKey())
			.e(EntityDescriptor.TYPE.name(), Entities.PRODUCT)
			.e(EntityDescriptor.LOCALES.name(), List.of(CZECH_LOCALE.toLanguageTag()))
			.e(EntityDescriptor.ALL_LOCALES.name(), List.of(CZECH_LOCALE.toLanguageTag(), Locale.ENGLISH.toLanguageTag()))
			.e(EntityDescriptor.PRICE_INNER_RECORD_HANDLING.name(), PriceInnerRecordHandling.UNKNOWN.name())
			.e(EntityDescriptor.ASSOCIATED_DATA.name(), map()
				.e(SectionedAssociatedDataDescriptor.LOCALIZED.name(), map()
					.e(CZECH_LOCALE.toLanguageTag(), map()
						.e(ASSOCIATED_DATA_LABELS, map()
							.e("someField", "differentValue")
							.build())
						.build())
					.build())
				.build())
			.build();

		testRESTCall()
			.httpMethod(Request.METHOD_POST)
			.urlPathSuffix("/product")
			.requestBody("""
                {
                    "primaryKey": %d,
                    "entityExistence": "MUST_EXIST",
                    "mutations": [
                        {
                            "upsertAssociatedDataMutation": {
                                "name": "labels",
                                "locale": "cs-CZ",
                                "value": {
                                    "someField": "differentValue"
                                },
                                "valueType": "ComplexDataObject"
                            }
                        },
                        {
                            "removeAssociatedDataMutation": {
                                "name": "localization"
                            }
                        }
                    ],
					"require": {
					    "entity_fetch": {
							"associatedData_content": [
								"labels",
								"localization"
							],
							"dataInLocales": ["cs-CZ"]
				        }
					  }
					}
                }
                """,
				entity.getPrimaryKey())
			.executeAndThen()
			.statusCode(200)
			.body("", equalTo(expectedBody));

		testRESTCall()
			.urlPathSuffix("/product/list")
			.httpMethod(Request.METHOD_POST)
			.requestBody("""
				{"filterBy": {
					"entity_primaryKey_inSet": [%d]
					},
					"require": {
						"entity_fetch": {
							"associatedData_content": [
								"labels",
								"localization"
							],
							"dataInLocales": ["cs-CZ"]
						}
					}
				}
				""",
				entity.getPrimaryKey())
			.executeAndThen()
			.statusCode(200)
			.body("", equalTo(Collections.singletonList(expectedBody)));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should update category with hierarchical placement mutations")
	void shouldUpdateCategoryWithHierarchicalPlacementMutations(Evita evita) {
		final SealedEntity entityInTree = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final SealedEntity rootEntity = session.queryOneSealedEntity(
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
				return session.queryOneSealedEntity(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							hierarchyWithinSelf(rootEntity.getPrimaryKey(), directRelation())
						),
						require(
							strip(0, 1),
							entityFetch()
						)
					)
				);
			}
		).orElseThrow();

		assertTrue(entityInTree.getHierarchicalPlacement().isPresent());

		final HierarchicalPlacementContract hierarchicalPlacementContract = entityInTree.getHierarchicalPlacement().orElseThrow();
		final Map<String, Object> expectedBodyWithHierarchicalPlacement = map()
			.e(EntityDescriptor.PRIMARY_KEY.name(), entityInTree.getPrimaryKey())
			.e(EntityDescriptor.TYPE.name(), Entities.CATEGORY)
			.e(EntityDescriptor.LOCALES.name(), List.of())
			.e(EntityDescriptor.ALL_LOCALES.name(), List.of(CZECH_LOCALE.toLanguageTag(), Locale.ENGLISH.toLanguageTag()))
			.e(EntityDescriptor.HIERARCHICAL_PLACEMENT.name(), map()
				.e(HierarchicalPlacementDescriptor.PARENT_PRIMARY_KEY.name(), null)
				.e(HierarchicalPlacementDescriptor.ORDER_AMONG_SIBLINGS.name(), hierarchicalPlacementContract.getOrderAmongSiblings() + 10)
				.build())
			.e(EntityDescriptor.PRICE_INNER_RECORD_HANDLING.name(), PriceInnerRecordHandling.UNKNOWN.name())
			.build();

		testRESTCall()
			.httpMethod(Request.METHOD_PUT)
			.urlPathSuffix("/category/" + entityInTree.getPrimaryKey())
			.requestBody("""
                {
                    "entityExistence": "MUST_EXIST",
                    "mutations": [
                        {
                            "setHierarchicalPlacementMutation": {
                                "orderAmongSiblings": %d
                            }
                        }
                    ],
					"require": {
					    "entity_fetch": {
				        }
					  }
					}
                }
                """,
				hierarchicalPlacementContract.getOrderAmongSiblings() + 10
			)
			.executeAndThen()
			.statusCode(200)
			.body(
				"",
				equalTo(
					expectedBodyWithHierarchicalPlacement
				)
			);
		assertHierarchicalPlacement(entityInTree.getPrimaryKey(), expectedBodyWithHierarchicalPlacement);

		final Map<String, Object> expectedBodyAfterRemoving = map()
			.e(EntityDescriptor.PRIMARY_KEY.name(), entityInTree.getPrimaryKey())
			.e(EntityDescriptor.TYPE.name(), Entities.CATEGORY)
			.e(EntityDescriptor.LOCALES.name(), List.of())
			.e(EntityDescriptor.ALL_LOCALES.name(), List.of(CZECH_LOCALE.toLanguageTag(), Locale.ENGLISH.toLanguageTag()))
			.e(EntityDescriptor.PRICE_INNER_RECORD_HANDLING.name(), PriceInnerRecordHandling.UNKNOWN.name())
			.build();

		testRESTCall()
			.httpMethod(Request.METHOD_PUT)
			.urlPathSuffix("/category/" + entityInTree.getPrimaryKey())
			.requestBody("""
                {
                    "entityExistence": "MUST_EXIST",
                    "mutations": [
                        {
                            "removeHierarchicalPlacementMutation": true
                        }
                    ],
					"require": {
					    "entity_fetch": {
				        }
					  }
					}
                }
                """)
			.executeAndThen()
			.statusCode(200)
			.body("", equalTo(expectedBodyAfterRemoving));
		assertHierarchicalPlacement(entityInTree.getPrimaryKey(), expectedBodyAfterRemoving);
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should update product with new price mutation")
	void shouldUpdateProductWithNewPriceMutation(Evita evita, List<SealedEntity> originalProductEntities) {
		final SealedEntity entity = originalProductEntities.stream()
			.filter(it -> it.getPrices()
				.stream()
				.noneMatch(it2 -> it2.getPriceId() == 1_000_000_000))
			.findFirst()
			.orElseThrow();

		final Map<String, Object> expectedBodyWithNewPrice = map()
			.e(EntityDescriptor.PRIMARY_KEY.name(), entity.getPrimaryKey())
			.e(EntityDescriptor.TYPE.name(), Entities.PRODUCT)
			.e(EntityDescriptor.LOCALES.name(), List.of())
			.e(EntityDescriptor.ALL_LOCALES.name(), List.of(CZECH_LOCALE.toLanguageTag(), Locale.ENGLISH.toLanguageTag()))
			.e(EntityDescriptor.PRICE_INNER_RECORD_HANDLING.name(), PriceInnerRecordHandling.NONE.name())
			.e(EntityDescriptor.PRICES.name(), List.of(
				map()
					.e(PriceDescriptor.PRICE_ID.name(), 1_000_000_000)
					.e(PriceDescriptor.PRICE_LIST.name(), "other")
					.e(PriceDescriptor.CURRENCY.name(), "CZK")
					.e(PriceDescriptor.INNER_RECORD_ID.name(), null)
					.e(PriceDescriptor.SELLABLE.name(), true)
					.e(PriceDescriptor.PRICE_WITHOUT_TAX.name(), "1.0")
					.e(PriceDescriptor.PRICE_WITH_TAX.name(), "1.21")
					.e(PriceDescriptor.TAX_RATE.name(), "21")
					.e(PriceDescriptor.VALIDITY.name(), null)
					.build()
			))
			.build();

		testRESTCall()
			.httpMethod(Request.METHOD_POST)
			.urlPathSuffix("/product")
			.requestBody("""
                {
                    "primaryKey": %d,
                    "entityExistence": "MUST_EXIST",
                    "mutations": [
                        {
							"upsertPriceMutation": {
						        "priceId": 1000000000,
						        "priceList": "other",
						        "currency": "CZK",
						        "priceWithoutTax": "1.0",
						        "taxRate": "21",
						        "priceWithTax": "1.21",
						        "sellable": true
                            }
                        }
                    ],
					"require": {
					    "entity_fetch": {
							"price_content": "RESPECTING_FILTER"
				        }
					  }
					}
                }
                """,
				entity.getPrimaryKey()
			)
			.executeAndThen()
			.statusCode(200);
			//.body("", equalTo(expectedBodyWithNewPrice));
		//todo result will be without priceForSale

		testRESTCall()
			.urlPathSuffix("/product/list")
			.httpMethod(Request.METHOD_POST)
			.requestBody("""
						{
							"filterBy": {
								"entity_primaryKey_inSet": [%d],
											"price_inPriceLists":["other"]
							},
							"require": {
								"entity_fetch": {
									"price_content": "RESPECTING_FILTER"
								}
						    }
						}
					""",
				entity.getPrimaryKey())
			.executeAndThen()
			.statusCode(200)
			.body("", equalTo(Collections.singletonList(expectedBodyWithNewPrice)));

		final Map<String, Object> expectedBodyWithoutNewPrice = map()
			.e(EntityDescriptor.PRIMARY_KEY.name(), entity.getPrimaryKey())
			.e(EntityDescriptor.TYPE.name(), Entities.PRODUCT)
			.e(EntityDescriptor.LOCALES.name(), List.of())
			.e(EntityDescriptor.ALL_LOCALES.name(), List.of(CZECH_LOCALE.toLanguageTag(), Locale.ENGLISH.toLanguageTag()))
			.e(EntityDescriptor.PRICE_INNER_RECORD_HANDLING.name(), PriceInnerRecordHandling.NONE.name())
			.build();

		testRESTCall()
			.httpMethod(Request.METHOD_POST)
			.urlPathSuffix("/product")
			.requestBody("""
                {
                    "primaryKey": %d,
                    "entityExistence": "MUST_EXIST",
                    "mutations": [
                        {
							"removePriceMutation": {
						        "priceId": 1000000000,
						        "priceList": "other",
						        "currency": "CZK"
                            }
                        }
                    ],
					"require": {
					    "entity_fetch": {
							"price_content": "RESPECTING_FILTER"
				        }
					  }
					}
                }
                """,
				entity.getPrimaryKey())
			.executeAndThen()
			.statusCode(200)
			.body("", equalTo(expectedBodyWithoutNewPrice));

		testRESTCall()
			.urlPathSuffix("/product/list")
			.httpMethod(Request.METHOD_POST)
			.requestBody("""
				{"filterBy": {
					"entity_primaryKey_inSet": [%d],
					"price_inPriceLists": ["other"]
					},
					"require": {
						"entity_fetch": {
							"price_content": "RESPECTING_FILTER"
				        }
					}
				}
				""",
				entity.getPrimaryKey())
			.executeAndThen()
			.statusCode(200)
			.body("", equalTo(Collections.emptyList()));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should update product with price inner handling mutation")
	void shouldUpdateProductWithPriceInnerRecordHandlingMutation(Evita evita, List<SealedEntity> originalProductEntities) {
		final SealedEntity entity = originalProductEntities.stream()
			.filter(it -> it.getPrimaryKey().equals(3))
			.findFirst()
			.orElseThrow();

		final Map<String, Object> expectedBody = map()
			.e(EntityDescriptor.PRIMARY_KEY.name(), entity.getPrimaryKey())
			.e(EntityDescriptor.TYPE.name(), Entities.PRODUCT)
			.e(EntityDescriptor.LOCALES.name(), List.of())
			.e(EntityDescriptor.ALL_LOCALES.name(), List.of(CZECH_LOCALE.toLanguageTag(), Locale.ENGLISH.toLanguageTag()))
			.e(EntityDescriptor.PRICE_INNER_RECORD_HANDLING.name(), "SUM")
			.e(EntityDescriptor.PRICES.name(), List.of(
				map()
					.e(PriceDescriptor.PRICE_ID.name(), 9)
					.e(PriceDescriptor.PRICE_LIST.name(), "basic")
					.e(PriceDescriptor.CURRENCY.name(), "USD")
					.e(PriceDescriptor.INNER_RECORD_ID.name(), null)
					.e(PriceDescriptor.SELLABLE.name(), true)
					.e(PriceDescriptor.PRICE_WITHOUT_TAX.name(), "77.99")
					.e(PriceDescriptor.TAX_RATE.name(), "21")
					.e(PriceDescriptor.PRICE_WITH_TAX.name(), "94.37")
					.e(PriceDescriptor.VALIDITY.name(), null)
					.build()
			))
			.build();

		testRESTCall()
			.httpMethod(Request.METHOD_POST)
			.urlPathSuffix("/product")
			.requestBody("""
                {
                    "primaryKey": %d,
                    "entityExistence": "MUST_EXIST",
                    "mutations": [
                        {
							"setPriceInnerRecordHandlingMutation": {
                                "priceInnerRecordHandling": "SUM"
                            }
                        }
                    ],
					"require": {
					    "entity_fetch": {
					        "price_content": "RESPECTING_FILTER"
				        }
					  }
					}
                }
                """,
				entity.getPrimaryKey())
			.executeAndThen()
			.statusCode(200)
			.body("", equalTo(expectedBody));

		testRESTCall()
			.urlPathSuffix("/product/list")
			.httpMethod(Request.METHOD_POST)
			.requestBody("""
				{"filterBy": {
					"entity_primaryKey_inSet": [%d],
					"price_inPriceLists": ["basic"]
					},
					"require": {
						"entity_fetch": {
							"price_content": "RESPECTING_FILTER"
				        }
					}
				}
				""",
				entity.getPrimaryKey())
			.executeAndThen()
			.statusCode(200)
			.body("", equalTo(Collections.singletonList(expectedBody)));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should update product with reference mutations")
	void shouldUpdateProductWithReferenceMutations(Evita evita, List<SealedEntity> originalProductEntities) {
		final SealedEntity entity = originalProductEntities.stream()
			.filter(it -> it.getReferences(Entities.STORE)
				.stream()
				.noneMatch(it2 -> it2.getReferencedPrimaryKey() == 1_000_000_000))
			.filter(it -> it.getPrimaryKey().equals(3))
			.findFirst()
			.orElseThrow();

		var expectedBody = entity.getReferences(Entities.STORE)
			.stream()
			.map(r -> map()
				.e("referencedEntity", map()
					.e(EntityDescriptor.PRIMARY_KEY.name(), r.getReferencedPrimaryKey())
					.e(EntityDescriptor.TYPE.name(), r.getReferencedEntityType())
					.e(EntityDescriptor.LOCALES.name(), new ArrayList<>(1))
					.e(EntityDescriptor.ALL_LOCALES.name(), new ArrayList<>(Arrays.asList(CZECH_LOCALE.toLanguageTag(), Locale.ENGLISH.toLanguageTag())))
					.e(EntityDescriptor.PRICE_INNER_RECORD_HANDLING.name(), PriceInnerRecordHandling.UNKNOWN.name())
					.build())
				.e(ReferenceDescriptor.ATTRIBUTES.name(), map()
					.e(ATTRIBUTE_STORE_VISIBLE_FOR_B2C, r.getAttribute(ATTRIBUTE_STORE_VISIBLE_FOR_B2C))
					.e(ATTRIBUTE_CAPACITY, String.valueOf(r.getAttributeValue(ATTRIBUTE_CAPACITY).get().getValue()))
					.build())
				.build())
			.toList();
		expectedBody = new LinkedList<>(expectedBody);
		expectedBody.add(map()
			.e("referencedEntity", map()
				.e(EntityDescriptor.PRIMARY_KEY.name(), 1_000_000_000)
				.e(EntityDescriptor.TYPE.name(), Entities.STORE)
				.build())
			.e(ReferenceDescriptor.ATTRIBUTES.name(), map()
				.e(ATTRIBUTE_STORE_VISIBLE_FOR_B2C, true)
				.build())
			.build());

		testRESTCall()
			.httpMethod(Request.METHOD_POST)
			.urlPathSuffix("/product")
			.requestBody("""
	           {
	               "primaryKey": %d,
	               "entityExistence": "MUST_EXIST",
	               "mutations": [
	                   {
                          "insertReferenceMutation": {
                            "name": "STORE",
                            "primaryKey": 1000000000
                          },
                          "referenceAttributeMutation": {
							"name": "STORE",
							"primaryKey": 1000000000,
							"attributeMutation": {
							  "upsertAttributeMutation": {
							    "name": "storeVisibleForB2C",
							    "value": true,
							    "valueType": "Boolean"
							  }
							}
                          }
                       }
	               ],
					"require": {
					    "entity_fetch": {
					        "attribute_contentAll": true,
					        "reference_store_content": {
					            "entity_fetch": {
					            }
					        }
					    }
					}
					           }
					           """,
				entity.getPrimaryKey())
			.executeAndThen()
			.statusCode(200)
			.body("store", containsInAnyOrder(expectedBody.toArray()));

		testRESTCall()
			.urlPathSuffix("/product/list")
			.httpMethod(Request.METHOD_POST)
			.requestBody("""
				{
					"filterBy": {
						"entity_primaryKey_inSet": [%d]
					},
					"require": {
						"entity_fetch": {
							"attribute_contentAll": true,
					        "reference_store_content": {
					            "entity_fetch": {
					            }
					        }
				        }
					}
				}
				""",
				entity.getPrimaryKey())
			.executeAndThen()
			.statusCode(200)
			.body("store", equalTo(Collections.singletonList(expectedBody)));


		testRESTCall()
			.httpMethod(Request.METHOD_POST)
			.urlPathSuffix("/product")
			.requestBody("""
	            {
	               "primaryKey": %d,
	               "entityExistence": "MUST_EXIST",
	               "mutations": [
	                   {
							"removeReferenceMutation": {
	                            "name": "STORE",
	                            "primaryKey": 1000000000
	                        }
	                   }
	               ],
					"require": {
					    "entity_fetch": {
					        "attribute_contentAll": true
					    }
					}
				}
	           """,
				entity.getPrimaryKey())
			.executeAndThen()
			.statusCode(200)
			.body("store.referencedEntity." + EntityDescriptor.PRIMARY_KEY.name(), not(containsInRelativeOrder(1_000_000_000)));

		testRESTCall()
			.urlPathSuffix("/product/list")
			.httpMethod(Request.METHOD_POST)
			.requestBody("""
				{
					"filterBy": {
						"entity_primaryKey_inSet": [%d]
					},
					"require": {
						"entity_fetch": {
							"attribute_contentAll": true
				        }
					}
				}
				""",
				entity.getPrimaryKey())
			.executeAndThen()
			.statusCode(200)
			.body("store.referencedEntity." + EntityDescriptor.PRIMARY_KEY.name(), not(containsInRelativeOrder(1_000_000_000)));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should update product with reference group mutations")
	void shouldUpdateProductWithReferenceGroupMutations(Evita evita, List<SealedEntity> originalProductEntities) {
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

		testRESTCall()
			.httpMethod(Request.METHOD_POST)
			.urlPathSuffix("/product")
			.requestBody("""
	            {
	               "primaryKey": %d,
	               "entityExistence": "MUST_EXIST",
	               "mutations": [
	                   {
	                        "setReferenceGroupMutation": {
	                            "name": "brandWithGroup",
	                            "primaryKey": 1,
	                            "groupPrimaryKey": 100
	                        }
	                    }
	               ]
				}
	           """,
				entity.getPrimaryKey())
			.executeAndThen()
			.statusCode(200)
			.body(EntityDescriptor.PRIMARY_KEY.name(), equalTo(entity.getPrimaryKey()));

		assertReferenceGroup(evita, entity.getPrimaryKey(), new GroupEntityReference(ENTITY_BRAND_GROUP, 100, 1, false));


		testRESTCall()
			.httpMethod(Request.METHOD_POST)
			.urlPathSuffix("/product")
			.requestBody("""
	            {
					"primaryKey": %d,
					"entityExistence": "MUST_EXIST",
					"mutations": [
						{
							"removeReferenceGroupMutation": {
								"name": "brandWithGroup",
								"primaryKey": 1
							}
						}
					]
				}
	        """,
			entity.getPrimaryKey())
			.executeAndThen()
			.statusCode(200)
			.body(EntityDescriptor.PRIMARY_KEY.name(), equalTo(entity.getPrimaryKey()));

		assertReferenceGroup(evita, entity.getPrimaryKey(), null);

		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getEntity(Entities.PRODUCT, entity.getPrimaryKey(), referenceContent())
					.orElseThrow()
					.openForWrite()
					.removeReference(REFERENCE_BRAND_WITH_GROUP, 1)
					.upsertVia(session);
			}
		);
	}

	private void assertReferenceGroup(@Nonnull Evita evita, int primaryKey, @Nullable ReferenceContract.GroupEntityReference groupEntityReference) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final SealedEntity updatedEntity = session.getEntity(Entities.PRODUCT, primaryKey, referenceContent())
					.orElseThrow();
				assertEquals(
					groupEntityReference,
					updatedEntity.getReferences(REFERENCE_BRAND_WITH_GROUP).iterator().next().getGroup().orElse(null)
				);
			}
		);
	}


	private void assertHierarchicalPlacement(int primaryKey, @Nonnull Map<String, Object> expectedBodyAfterRemoving) {
		testRESTCall()
			.urlPathSuffix("/category/list")
			.httpMethod(Request.METHOD_POST)
			.requestBody("""
					{
						"filterBy": {
							"entity_primaryKey_inSet": [%d]
						},
						"require": {
							"entity_fetch": {
					        }
						}
					}
					""",
				primaryKey)
			.executeAndThen()
			.statusCode(200)
			.body("", equalTo(Collections.singletonList(expectedBodyAfterRemoving)));
	}

}
