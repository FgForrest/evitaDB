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

import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract.GroupEntityReference;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.api.catalog.dataApi.model.EntityDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.PriceDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.ReferenceDescriptor;
import io.evitadb.externalApi.rest.RestProvider;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.SectionedAssociatedDataDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.SectionedAttributesDescriptor;
import io.evitadb.server.EvitaServer;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.DataCarrier;
import io.evitadb.test.tester.RestTester;
import io.evitadb.test.tester.RestTester.Request;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.externalApi.rest.api.testSuite.TestDataGenerator.*;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.builder.MapBuilder.map;
import static io.evitadb.test.generator.DataGenerator.ASSOCIATED_DATA_LABELS;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_NAME;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_QUANTITY;
import static io.evitadb.test.generator.DataGenerator.CZECH_LOCALE;
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
class CatalogRestUpsertEntityMutationFunctionalTest extends CatalogRestDataEndpointFunctionalTest {

	public static final String REST_THOUSAND_PRODUCTS_FOR_UPDATE = REST_THOUSAND_PRODUCTS + "forUpdate";

	@Override
	@DataSet(value = REST_THOUSAND_PRODUCTS_FOR_UPDATE, openWebApi = RestProvider.CODE, readOnly = false, destroyAfterClass = true)
	protected DataCarrier setUp(Evita evita, EvitaServer evitaServer) {
		return super.setUpData(evita, evitaServer, 50);
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS_FOR_UPDATE)
	@DisplayName("Should insert single empty entity without PK")
	void shouldInsertSingleEmptyEntityWithoutPK(RestTester tester) {
		tester.test(TEST_CATALOG)
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
						.e(EntityDescriptor.PRIMARY_KEY.name(), 11)
						.e(EntityDescriptor.TYPE.name(), "empty")
						.e(EntityDescriptor.LOCALES.name(), Collections.emptyList())
						.e(EntityDescriptor.ALL_LOCALES.name(), Collections.emptyList())
						.e(EntityDescriptor.PRICE_INNER_RECORD_HANDLING.name(), PriceInnerRecordHandling.UNKNOWN.name())
						.build()
				)
			);
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS_FOR_UPDATE)
	@DisplayName("Should insert single empty entity with PK")
	void shouldInsertSingleEmptyEntityWithPK(RestTester tester) {
		tester.test(TEST_CATALOG)
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
	@UseDataSet(REST_THOUSAND_PRODUCTS_FOR_UPDATE)
	@DisplayName("Should update product with no mutations")
	void shouldUpdateProductWithNoMutations(RestTester tester) {
		tester.test(TEST_CATALOG)
			.httpMethod(Request.METHOD_PUT)
			.urlPathSuffix("/product/10")
			.requestBody("""
                    {
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
						.e(EntityDescriptor.PRIMARY_KEY.name(), 10)
						.e(EntityDescriptor.TYPE.name(), "PRODUCT")
						.e(EntityDescriptor.LOCALES.name(), Collections.emptyList())
						.e(EntityDescriptor.ALL_LOCALES.name(), List.of(CZECH_LOCALE.toLanguageTag(), Locale.ENGLISH.toLanguageTag()))
						.e(EntityDescriptor.PRICE_INNER_RECORD_HANDLING.name(), PriceInnerRecordHandling.UNKNOWN.name())
						.build()
				)
			);
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS_FOR_UPDATE)
	@DisplayName("Should return error when missing arguments for product upsert")
	void shouldReturnErrorWhenMissingArgumentsForProductUpsert(RestTester tester) {
		tester.test(TEST_CATALOG)
			.httpMethod(Request.METHOD_POST)
			.urlPathSuffix("/product")
			.requestBody("""
                    {}
                    """)
			.executeAndThen()
			.statusCode(400)
			.body("message", equalTo("EntityExistence is not set in request data."));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS_FOR_UPDATE)
	@DisplayName("Should return error when missing mutations for product update")
	void shouldReturnErrorWhenMissingMutationsForProductUpdate(RestTester tester) {
		tester.test(TEST_CATALOG)
			.httpMethod(Request.METHOD_PUT)
			.urlPathSuffix("/product/100")
			.requestBody("""
                    {
                        "entityExistence": "MUST_EXIST"
                    }
                    """)
			.executeAndThen()
			.statusCode(400)
			.body("message", equalTo("Mutations are not set in request data."));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS_FOR_UPDATE)
	@DisplayName("Should update product with attribute mutations")
	void shouldUpdateProductWithAttributeMutations(RestTester tester, List<SealedEntity> originalProductEntities) {
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

		tester.test(TEST_CATALOG)
			.httpMethod(Request.METHOD_PUT)
			.urlPathSuffix("/product/" + entity.getPrimaryKey())
			.requestBody("""
                {
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
					    "entityFetch": {
							"attributeContent": [
								"name",
								"quantity",
								"deprecated"
							],
							"dataInLocales": ["cs-CZ"]
				        }
					  }
					}
                }
                """)
			.executeAndThen()
			.statusCode(200)
			.body("",equalTo(expectedBody));

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/product/list")
			.httpMethod(Request.METHOD_POST)
			.requestBody("""
				{
					"filterBy": {
						"entityPrimaryKeyInSet": [%d]
					},
					"require": {
						"entityFetch": {
							"attributeContent": [
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
	@UseDataSet(REST_THOUSAND_PRODUCTS_FOR_UPDATE)
	@DisplayName("Should update product with associated data mutations")
	void shouldUpdateProductWithAssociatedDataMutations(RestTester tester, List<SealedEntity> originalProductEntities) {
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

		tester.test(TEST_CATALOG)
			.httpMethod(Request.METHOD_PUT)
			.urlPathSuffix("/product/" + entity.getPrimaryKey())
			.requestBody("""
                {
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
					    "entityFetch": {
							"associatedDataContent": [
								"labels",
								"localization"
							],
							"dataInLocales": ["cs-CZ"]
				        }
					  }
					}
                }
                """)
			.executeAndThen()
			.statusCode(200)
			.body("", equalTo(expectedBody));

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/product/list")
			.httpMethod(Request.METHOD_POST)
			.requestBody("""
				{
					"filterBy": {
						"entityPrimaryKeyInSet": [%d]
					},
					"require": {
						"entityFetch": {
							"associatedDataContent": [
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
	@UseDataSet(REST_THOUSAND_PRODUCTS_FOR_UPDATE)
	@DisplayName("Should update category with hierarchical placement mutations")
	@Disabled("TODO LHO: will be reimplemented")
	void shouldUpdateCategoryWithHierarchicalPlacementMutations(Evita evita, RestTester tester) {
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
							hierarchyWithinSelf(
								entityPrimaryKeyInSet(rootEntity.getPrimaryKey()),
								directRelation()
							)
						),
						require(
							strip(0, 1),
							entityFetch()
						)
					)
				);
			}
		).orElseThrow();

		assertTrue(entityInTree.getParent().isPresent());

		final int parent = entityInTree.getParent().orElseThrow();
		final Map<String, Object> expectedBodyWithHierarchicalPlacement = map()
			.e(EntityDescriptor.PRIMARY_KEY.name(), entityInTree.getPrimaryKey())
			.e(EntityDescriptor.TYPE.name(), Entities.CATEGORY)
			.e(EntityDescriptor.LOCALES.name(), List.of())
			.e(EntityDescriptor.ALL_LOCALES.name(), List.of(CZECH_LOCALE.toLanguageTag(), Locale.ENGLISH.toLanguageTag()))
			// todo lho reimplement parents
//			.e(EntityDescriptor.PARENT.name(), map()
//				.e(ParentDescriptor.PARENT_PRIMARY_KEY.name(), parent + 10)
//				.build())
			.e(EntityDescriptor.PRICE_INNER_RECORD_HANDLING.name(), PriceInnerRecordHandling.UNKNOWN.name())
			.build();

		tester.test(TEST_CATALOG)
			.httpMethod(Request.METHOD_PUT)
			.urlPathSuffix("/category/" + entityInTree.getPrimaryKey())
			.requestBody("""
                {
                    "entityExistence": "MUST_EXIST",
                    "mutations": [
                        {
                            "setHierarchicalPlacementMutation": {
                                "parentPrimaryKey": %d
                            }
                        }
                    ],
					"require": {
					    "entityFetch": {
				        }
					  }
					}
                }
                """,
				parent + 10
			)
			.executeAndThen()
			.statusCode(200)
			.body(
				"",
				equalTo(
					expectedBodyWithHierarchicalPlacement
				)
			);
		assertHierarchicalPlacement(tester, entityInTree.getPrimaryKey(), expectedBodyWithHierarchicalPlacement);

		final Map<String, Object> expectedBodyAfterRemoving = map()
			.e(EntityDescriptor.PRIMARY_KEY.name(), entityInTree.getPrimaryKey())
			.e(EntityDescriptor.TYPE.name(), Entities.CATEGORY)
			.e(EntityDescriptor.LOCALES.name(), List.of())
			.e(EntityDescriptor.ALL_LOCALES.name(), List.of(CZECH_LOCALE.toLanguageTag(), Locale.ENGLISH.toLanguageTag()))
			.e(EntityDescriptor.PRICE_INNER_RECORD_HANDLING.name(), PriceInnerRecordHandling.UNKNOWN.name())
			.build();

		tester.test(TEST_CATALOG)
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
					    "entityFetch": {
				        }
					  }
					}
                }
                """)
			.executeAndThen()
			.statusCode(200)
			.body("", equalTo(expectedBodyAfterRemoving));
		assertHierarchicalPlacement(tester, entityInTree.getPrimaryKey(), expectedBodyAfterRemoving);
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS_FOR_UPDATE)
	@DisplayName("Should update product with new price mutation")
	void shouldUpdateProductWithNewPriceMutation(RestTester tester, List<SealedEntity> originalProductEntities) {
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

		tester.test(TEST_CATALOG)
			.httpMethod(Request.METHOD_PUT)
			.urlPathSuffix("/product/" + entity.getPrimaryKey())
			.requestBody("""
                {
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
					    "entityFetch": {
							"priceContent": {
								"contentMode": "RESPECTING_FILTER"
							}
				        }
					  }
					}
                }
                """)
			.executeAndThen()
			.statusCode(200);
			//.body("", equalTo(expectedBodyWithNewPrice));
		//todo result will be without priceForSale

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/product/list")
			.httpMethod(Request.METHOD_POST)
			.requestBody("""
					{
						"filterBy": {
							"entityPrimaryKeyInSet": [%d],
							"priceInPriceLists":["other"]
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

		tester.test(TEST_CATALOG)
			.httpMethod(Request.METHOD_PUT)
			.urlPathSuffix("/product/" + entity.getPrimaryKey())
			.requestBody("""
                {
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
					    "entityFetch": {
							"priceContent": {
								"contentMode": "RESPECTING_FILTER"
							}
				        }
					  }
					}
                }
                """)
			.executeAndThen()
			.statusCode(200)
			.body("", equalTo(expectedBodyWithoutNewPrice));

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/product/list")
			.httpMethod(Request.METHOD_POST)
			.requestBody("""
				{
					"filterBy": {
						"entityPrimaryKeyInSet": [%d],
						"priceInPriceLists": ["other"]
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
				entity.getPrimaryKey())
			.executeAndThen()
			.statusCode(200)
			.body("", equalTo(Collections.emptyList()));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS_FOR_UPDATE)
	@DisplayName("Should update product with price inner handling mutation")
	void shouldUpdateProductWithPriceInnerRecordHandlingMutation(RestTester tester, List<SealedEntity> originalProductEntities) {
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

		tester.test(TEST_CATALOG)
			.httpMethod(Request.METHOD_PUT)
			.urlPathSuffix("/product/" + entity.getPrimaryKey())
			.requestBody("""
                {
                    "entityExistence": "MUST_EXIST",
                    "mutations": [
                        {
							"setPriceInnerRecordHandlingMutation": {
                                "priceInnerRecordHandling": "SUM"
                            }
                        }
                    ],
					"require": {
					    "entityFetch": {
					        "priceContent": {
					            "contentMode": "RESPECTING_FILTER"
				            }
				        }
					  }
					}
                }
                """)
			.executeAndThen()
			.statusCode(200)
			.body("", equalTo(expectedBody));

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/product/list")
			.httpMethod(Request.METHOD_POST)
			.requestBody("""
				{
					"filterBy": {
						"entityPrimaryKeyInSet": [%d],
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
				entity.getPrimaryKey())
			.executeAndThen()
			.statusCode(200)
			.body("", equalTo(Collections.singletonList(expectedBody)));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS_FOR_UPDATE)
	@DisplayName("Should update product with reference mutations")
	void shouldUpdateProductWithReferenceMutations(RestTester tester, List<SealedEntity> originalProductEntities) {
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
				.e(ReferenceDescriptor.REFERENCED_PRIMARY_KEY.name(), r.getReferencedPrimaryKey())
				.e(ReferenceDescriptor.ATTRIBUTES.name(), map()
					.e(ATTRIBUTE_STORE_VISIBLE_FOR_B2C, r.getAttribute(ATTRIBUTE_STORE_VISIBLE_FOR_B2C))
					.e(ATTRIBUTE_CAPACITY, String.valueOf(r.getAttributeValue(ATTRIBUTE_CAPACITY).get().getValue())))
				.build())
			.toList();
		expectedBody = new LinkedList<>(expectedBody);
		expectedBody.add(map()
			.e(ReferenceDescriptor.REFERENCED_PRIMARY_KEY.name(), 1_000_000_000)
			.e(ReferenceDescriptor.ATTRIBUTES.name(), map()
				.e(ATTRIBUTE_STORE_VISIBLE_FOR_B2C, true))
			.build());

		tester.test(TEST_CATALOG)
			.httpMethod(Request.METHOD_PUT)
			.urlPathSuffix("/product/" + entity.getPrimaryKey())
			.requestBody("""
				{
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
					    "entityFetch": {
					        "attributeContentAll": true,
					        "referenceStoreContent": {}
					    }
					}
				}
				""")
			.executeAndThen()
			.statusCode(200)
			.body("store", equalTo(expectedBody));

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/product/list")
			.httpMethod(Request.METHOD_POST)
			.requestBody("""
				{
					"filterBy": {
						"entityPrimaryKeyInSet": [%d]
					},
					"require": {
						"entityFetch": {
							"attributeContentAll": true,
					        "referenceStoreContent": {
					            "requirements": {}
					        }
				        }
					}
				}
				""",
				entity.getPrimaryKey())
			.executeAndThen()
			.statusCode(200)
			.body("store", equalTo(Collections.singletonList(expectedBody)));


		tester.test(TEST_CATALOG)
			.httpMethod(Request.METHOD_PUT)
			.urlPathSuffix("/product/" + entity.getPrimaryKey())
			.requestBody("""
	            {
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
					    "entityFetch": {
					        "attributeContentAll": true
					    }
					}
				}
	            """)
			.executeAndThen()
			.statusCode(200)
			.body("store.referencedEntity." + EntityDescriptor.PRIMARY_KEY.name(), not(containsInRelativeOrder(1_000_000_000)));

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/product/list")
			.httpMethod(Request.METHOD_POST)
			.requestBody("""
				{
					"filterBy": {
						"entityPrimaryKeyInSet": [%d]
					},
					"require": {
						"entityFetch": {
							"attributeContentAll": true
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
	@UseDataSet(REST_THOUSAND_PRODUCTS_FOR_UPDATE)
	@DisplayName("Should update product with reference group mutations")
	void shouldUpdateProductWithReferenceGroupMutations(Evita evita, RestTester tester, List<SealedEntity> originalProductEntities) {
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
			.httpMethod(Request.METHOD_PUT)
			.urlPathSuffix("/product/" + entity.getPrimaryKey())
			.requestBody("""
	            {
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
	           """)
			.executeAndThen()
			.statusCode(200)
			.body(EntityDescriptor.PRIMARY_KEY.name(), equalTo(entity.getPrimaryKey()));

		assertReferenceGroup(evita, entity.getPrimaryKey(), new GroupEntityReference(ENTITY_BRAND_GROUP, 100, 1, false));


		tester.test(TEST_CATALOG)
			.httpMethod(Request.METHOD_PUT)
			.urlPathSuffix("/product/" + entity.getPrimaryKey())
			.requestBody("""
	            {
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
	            """)
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


	private void assertHierarchicalPlacement(@Nonnull RestTester tester, int primaryKey, @Nonnull Map<String, Object> expectedBodyAfterRemoving) {
		tester.test(TEST_CATALOG)
			.urlPathSuffix("/category/list")
			.httpMethod(Request.METHOD_POST)
			.requestBody("""
					{
						"filterBy": {
							"entityPrimaryKeyInSet": [%d]
						},
						"require": {
							"entityFetch": {
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
