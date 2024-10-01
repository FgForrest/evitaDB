/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.externalApi.rest.api.catalog.dataApi;

import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.api.catalog.dataApi.model.EntityDescriptor;
import io.evitadb.externalApi.rest.api.testSuite.TestDataGenerator;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.tester.RestTester;
import io.evitadb.test.tester.RestTester.Request;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.api.query.order.OrderDirection.DESC;
import static io.evitadb.externalApi.rest.api.testSuite.TestDataGenerator.REST_THOUSAND_PRODUCTS;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.generator.DataGenerator.*;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for REST catalog entity list query.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
class CatalogRestListEntitiesQueryFunctionalTest extends CatalogRestDataEndpointFunctionalTest {

	private static final int SEED = 40;

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return products by primary key")
	void shouldReturnProductsByPrimaryKey(Evita evita, RestTester tester, List<SealedEntity> originalProductEntities) {
		final var pks = findEntityPks(
			originalProductEntities,
			it -> it.getAttribute(ATTRIBUTE_CODE) != null
		);

		final List<EntityClassifier> entities = getEntities(
			evita,
			query(
				collection(Entities.PRODUCT),
				filterBy(
					entityPrimaryKeyInSet(pks)
				),
				require(
					entityFetch(
						attributeContent(ATTRIBUTE_CODE)
					)
				)
			)
		);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/PRODUCT/list")
			.httpMethod(Request.METHOD_POST)
			.requestBody("{" +
					"\"filterBy\": {" +
					"  \"and\": [ {" +
					"    \"entityPrimaryKeyInSet\": [%d, %d]" +
					"     }" +
					"  ]" +
					"}," +
					"\"require\": {" +
					"  \"entityFetch\": {" +
					"     \"attributeContent\": [\"code\"]" +
					"    }" +
					"  }" +
					"}",
				entities.get(0).getPrimaryKey(),
				entities.get(1).getPrimaryKey())
			.executeAndThen()
			.statusCode(200)
			.body("", equalTo(createEntityDtos(entities)));
	}

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return products by non-localized attribute")
	void shouldReturnProductsByNonLocalizedAttribute(Evita evita, RestTester tester, List<SealedEntity> originalProductEntities) {
		final var pks = findEntityPks(
			originalProductEntities,
			it -> it.getAttribute(ATTRIBUTE_NAME, Locale.ENGLISH) != null &&
				it.getAllLocales().contains(CZECH_LOCALE) &&
				it.getAllLocales().contains(Locale.ENGLISH)
		);

		final List<EntityClassifier> entities = getEntities(
			evita,
			query(
				collection(Entities.PRODUCT),
				filterBy(
					entityPrimaryKeyInSet(pks),
					entityLocaleEquals(Locale.ENGLISH)
				),
				require(
					entityFetch(
						attributeContent(ATTRIBUTE_CODE, ATTRIBUTE_NAME)
					)
				)
			)
		);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/PRODUCT/list")
			.httpMethod(Request.METHOD_POST)
			.requestBody("""
				{
					"filterBy": {
						"and": [
							{
								"entityPrimaryKeyInSet": %s,
								"entityLocaleEquals": "en"
							}
						]
					},
					"require": {
						"entityFetch": {
							"attributeContent": ["code", "name"]
						}
					}
				}
				""",
				serializeIntArrayToQueryString(pks))
			.executeAndThen()
			.statusCode(200)
			.body("", equalTo(createEntityDtos(entities)));
	}

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return products by non-localized attribute with locale in URL")
	void shouldReturnProductsByNonLocalizedAttributeWithLocaleInUrl(Evita evita, RestTester tester, List<SealedEntity> originalProductEntities) {
		final var pks = findEntityPks(
			originalProductEntities,
			it -> it.getAttribute(ATTRIBUTE_NAME, Locale.ENGLISH) != null &&
				it.getAllLocales().contains(CZECH_LOCALE) &&
				it.getAllLocales().contains(Locale.ENGLISH)
		);

		final List<EntityClassifier> entities = getEntities(
			evita,
			query(
				collection(Entities.PRODUCT),
				filterBy(
					entityPrimaryKeyInSet(pks),
					entityLocaleEquals(Locale.ENGLISH)
				),
				require(
					entityFetch(
						attributeContent(ATTRIBUTE_CODE, ATTRIBUTE_NAME)
					)
				)
			)
		);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/" + Locale.ENGLISH.toLanguageTag() + "/PRODUCT/list")
			.httpMethod(Request.METHOD_POST)
			.requestBody("""
				{
					"filterBy": {
						"and": [
							{
								"entityPrimaryKeyInSet": %s
							}
						]
					},
					"require": {
						"entityFetch": {
							"attributeContent": ["code", "name"]
						}
					}
				}
				""",
				serializeIntArrayToQueryString(pks))
			.executeAndThen()
			.statusCode(200)
			.body("", equalTo(createEntityDtos(entities, true)));
	}

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return products by localized attribute")
	void shouldReturnProductsByLocalizedAttribute(Evita evita, RestTester tester, List<SealedEntity> originalProductEntities) {
		final var pks = findEntityPks(
			originalProductEntities,
			it -> it.getAttribute(ATTRIBUTE_URL, Locale.ENGLISH) != null &&
				it.getAttribute(ATTRIBUTE_NAME, Locale.ENGLISH) != null
		);

		final List<String> urls = getAttributesByPks(evita, pks, ATTRIBUTE_URL, Locale.ENGLISH);

		final List<SealedEntity> entities = getEntities(
			evita,
			query(
				collection(Entities.PRODUCT),
				filterBy(
					entityPrimaryKeyInSet(pks),
					entityLocaleEquals(Locale.ENGLISH)
				),
				require(
					entityFetch(
						attributeContent(ATTRIBUTE_URL, ATTRIBUTE_NAME)
					)
				)
			),
			SealedEntity.class
		);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/PRODUCT/list")
			.httpMethod(Request.METHOD_POST)
			.requestBody("""
				{
					"filterBy": {
						"and": [
							{
								"attributeUrlInSet": %s,
								"entityLocaleEquals": "en"
							}
						]
					},
					"require": {
						"entityFetch": {
							"attributeContent": ["url", "name"]
						}
					}
				}
				""",
				serializeStringArrayToQueryString(urls))
			.executeAndThen()
			.statusCode(200)
			.body("", equalTo(createEntityDtos(entities)));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return direct category parent entity references")
	void shouldReturnAllDirectCategoryParentEntityReferences(Evita evita, RestTester tester) {
		final List<SealedEntity> categories = getEntities(
			evita,
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
			it -> {
				// check that it has at least 2 parents
				assertTrue(it.getParentEntity().isPresent());
				assertTrue(it.getParentEntity().get().getParentEntity().isPresent());
			},
			SealedEntity.class
		);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/CATEGORY/list")
			.httpMethod(Request.METHOD_POST)
			.requestBody("""
				{
					"filterBy": {
						"entityPrimaryKeyInSet": [16]
					},
					"require": {
						"entityFetch": {
							"hierarchyContent": {}
						}
					}
				}
				""")
			.executeAndExpectOkAndThen()
			.body("", equalTo(createEntityDtos(categories)));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return direct category parent entities")
	void shouldReturnAllDirectCategoryParentEntities(Evita evita, RestTester tester) {
		final List<SealedEntity> categories = getEntities(
			evita,
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
			it -> {
				// check that it has at least 2 parents
				assertTrue(it.getParentEntity().isPresent());
				assertTrue(it.getParentEntity().get().getParentEntity().isPresent());
			},
			SealedEntity.class
		);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/CATEGORY/list")
			.httpMethod(Request.METHOD_POST)
			.requestBody("""
				{
					"filterBy": {
						"entityPrimaryKeyInSet": [16]
					},
					"require": {
						"entityFetch": {
							"hierarchyContent": {
								"entityFetch": {
									"attributeContent": ["code"]
								}
							}
						}
					}
				}
				""")
			.executeAndExpectOkAndThen()
			.body("", equalTo(createEntityDtos(categories)));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return only direct category parent")
	void shouldReturnOnlyDirectCategoryParent(Evita evita, RestTester tester) {
		final List<SealedEntity> categories = getEntities(
			evita,
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
			entity -> {
				// check that it has only one direct parent
				assertTrue(entity.getParentEntity().isPresent());
				assertTrue(entity.getParentEntity().get().getParentEntity().isEmpty());
			},
			SealedEntity.class
		);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/CATEGORY/list")
			.httpMethod(Request.METHOD_POST)
			.requestBody("""
				{
					"filterBy": {
						"entityPrimaryKeyInSet": [16]
					},
					"require": {
						"entityFetch": {
							"hierarchyContent": {
								"stopAt": {
									"distance": 1
								}
							}
						}
					}
				}
				""")
			.executeAndExpectOkAndThen()
			.body("", equalTo(createEntityDtos(categories)));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return all direct product parent entity references")
	void shouldReturnAllDirectProductParentEntityReferences(Evita evita, RestTester tester) {
		final List<SealedEntity> products = getEntities(
			evita,
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
			entity -> {
				// check that it has at least 2 referenced parents
				assertTrue(entity.getReferences(Entities.CATEGORY)
					.iterator()
					.next()
					.getReferencedEntity()
					.orElseThrow()
					.getParentEntity()
					.get()
					.getParentEntity()
					.isPresent());
			},
			SealedEntity.class
		);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/PRODUCT/list")
			.httpMethod(Request.METHOD_POST)
			.requestBody("""
				{
					"filterBy": {
						"hierarchyCategoryWithin": {
							"ofParent": {
								"entityPrimaryKeyInSet": [26]
							}
						}
					},
					"require": {
						"page": {
							"number": 1,
							"size": 1
						},
						"entityFetch": {
							"referenceCategoryContent": {
								"entityFetch": {
									"hierarchyContent": {}
								}
							}
						}
					}
				}
				""")
			.executeAndExpectOkAndThen()
			.body("", equalTo(createEntityDtos(products)));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return all direct product parent entities")
	void shouldReturnAllDirectProductParentEntities(Evita evita, RestTester tester) {
		final List<SealedEntity> products = getEntities(
			evita,
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
			entity -> {
				// check that it has at least 2 referenced parents
				assertTrue(entity.getReferences(Entities.CATEGORY)
					.iterator()
					.next()
					.getReferencedEntity()
					.orElseThrow()
					.getParentEntity()
					.get()
					.getParentEntity()
					.isPresent());
			},
			SealedEntity.class
		);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/PRODUCT/list")
			.httpMethod(Request.METHOD_POST)
			.requestBody("""
				{
					"filterBy": {
						"hierarchyCategoryWithin": {
							"ofParent": {
								"entityPrimaryKeyInSet": [26]
							}
						}
					},
					"require": {
						"page": {
							"number": 1,
							"size": 1
						},
						"entityFetch": {
							"referenceCategoryContent": {
								"entityFetch": {
									"hierarchyContent": {
										"entityFetch": {
											"attributeContent": ["code"]
										}
									}
								}
							}
						}
					}
				}
				""")
			.executeAndExpectOkAndThen()
			.body("", equalTo(createEntityDtos(products)));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return only direct product parent")
	void shouldReturnOnlyDirectProductParent(Evita evita, RestTester tester) {
		final List<SealedEntity> products = getEntities(
			evita,
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
			entity -> {
				// check that it has only one referenced parents
				assertTrue(entity.getReferences(Entities.CATEGORY)
					.iterator()
					.next()
					.getReferencedEntity()
					.orElseThrow()
					.getParentEntity()
					.get()
					.getParentEntity()
					.isEmpty());
			},
			SealedEntity.class
		);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/PRODUCT/list")
			.httpMethod(Request.METHOD_POST)
			.requestBody("""
				{
					"filterBy": {
						"hierarchyCategoryWithin": {
							"ofParent": {
								"entityPrimaryKeyInSet": [16]
							}
						}
					},
					"require": {
						"page": {
							"number": 1,
							"size": 1
						},
						"entityFetch": {
							"referenceCategoryContent": {
								"entityFetch": {
									"hierarchyContent": {
										"stopAt": {
											"distance": 1
										}
									}
								}
							}
						}
					}
				}
				""")
			.executeAndExpectOkAndThen()
			.body("", equalTo(createEntityDtos(products)));
	}

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should filter by and return price for sale for multiple products")
	void shouldFilterByAndReturnPriceForSaleForMultipleProducts(Evita evita, RestTester tester, List<SealedEntity> originalProductEntities) {
		final var pks = findEntityWithPricePks(originalProductEntities);

		final List<EntityClassifier> entities = getEntities(
			evita,
			query(
				collection(Entities.PRODUCT),
				filterBy(
					entityPrimaryKeyInSet(pks),
					priceInCurrency(CURRENCY_CZK),
					priceInPriceLists("basic")
				),
				require(
					entityFetch(
						priceContentRespectingFilter()
					)
				)
			)
		);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/PRODUCT/list")
			.httpMethod(Request.METHOD_POST)
			.requestBody(
    """
                    {
						"filterBy": {
						    "entityPrimaryKeyInSet": %s,
						    "priceInCurrency": "CZK",
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
				Arrays.toString(pks))
			.executeAndThen()
			.statusCode(200)
			.body("", equalTo(createEntityDtos(entities)));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return all prices for sale for master products")
	void shouldReturnAllPricesForSaleForMasterProducts(Evita evita, RestTester tester, List<SealedEntity> originalProductEntities) {
		final var pks = findEntityPks(
			originalProductEntities,
			it -> !it.getPriceInnerRecordHandling().equals(PriceInnerRecordHandling.NONE) &&
				it.getPrices(CURRENCY_CZK)
					.stream()
					.filter(PriceContract::indexed)
					.map(PriceContract::innerRecordId)
					.distinct()
					.count() > 1
		);

		final Set<Integer> pksSet = Arrays.stream(pks).collect(Collectors.toSet());
		final List<String> priceLists = originalProductEntities.stream()
			.filter(it -> pksSet.contains(it.getPrimaryKey()))
			.flatMap(it -> it.getPrices(CURRENCY_CZK).stream().map(PriceContract::priceList))
			.distinct()
			.toList();
		assertTrue(priceLists.size() > 1);

		final List<EntityClassifier> entities = getEntities(
			evita,
			query(
				collection(Entities.PRODUCT),
				filterBy(
					entityPrimaryKeyInSet(pks),
					priceInCurrency(CURRENCY_CZK),
					priceInPriceLists(priceLists.toArray(String[]::new))
				),
				require(
					entityFetch(
						priceContentRespectingFilter()
					)
				)
			)
		);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/PRODUCT/list")
			.httpMethod(Request.METHOD_POST)
			.requestBody(
				"""
                    {
						"filterBy": {
						    "entityPrimaryKeyInSet": %s,
						    "priceInCurrency": "CZK",
						    "priceInPriceLists": %s
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
				serializeIntArrayToQueryString(pks),
				serializeStringArrayToQueryString(priceLists)
			)
			.executeAndThen()
			.statusCode(200)
			.body("", equalTo(createEntityDtos(entities)));
	}

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should filter products by non-existent price")
	void shouldFilterProductsByNonExistentPrice(RestTester tester, List<SealedEntity> originalProductEntities) {
		final var pks = findEntityWithPricePks(originalProductEntities);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/PRODUCT/list")
			.httpMethod(Request.METHOD_POST)
			.requestBody("{" +
					"\"filterBy\": {" +
					"    \"entityPrimaryKeyInSet\": %s," +
					"    \"priceInCurrency\": \"CZK\"," +
					"    \"priceInPriceLists\": [\"nonexistent\"]" +
					"   }" +
					"}",
				Arrays.toString(pks))
			.executeAndThen()
			.statusCode(200)
			.body("", hasSize(0));
	}

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return error for filtering products by unknown currency")
	void shouldReturnErrorForFilteringProductsByUnknownCurrency(RestTester tester, List<SealedEntity> originalProductEntities) {
		final var pks = findEntityWithPricePks(originalProductEntities);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/PRODUCT/list")
			.httpMethod(Request.METHOD_POST)
			.requestBody("{" +
					"\"filterBy\": {" +
					"    \"entityPrimaryKeyInSet\": %s," +
					"    \"priceInCurrency\": \"AAA\"," +
					"    \"priceInPriceLists\": [\"basic\"]" +
					"   }" +
					"}",
				Arrays.toString(pks))
			.executeAndThen()
			.statusCode(400)
			.body("message", notNullValue());
	}

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return custom price for sale for products")
	void shouldReturnCustomPriceForSaleForProducts(Evita evita, RestTester tester, List<SealedEntity> originalProductEntities) {
		final var pks = findEntityWithPricePks(originalProductEntities);

		final List<EntityClassifier> entities = getEntities(
			evita,
			query(
				collection(Entities.PRODUCT),
				filterBy(
					entityPrimaryKeyInSet(pks),
					priceInCurrency(CURRENCY_CZK),
					priceInPriceLists("basic")
				),
				require(
					entityFetch(
						priceContentRespectingFilter()
					)
				)
			)
		);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/PRODUCT/list")
			.httpMethod(Request.METHOD_POST)
			.requestBody(
				"""
                    {
						"filterBy": {
						    "entityPrimaryKeyInSet": %s,
						    "priceInCurrency": "CZK",
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
				Arrays.toString(pks))
			.executeAndThen()
			.statusCode(200)
			.body("", equalTo(createEntityDtos(entities)));
	}

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return price for products")
	void shouldReturnPriceForProducts(Evita evita, RestTester tester, List<SealedEntity> originalProductEntities) {
		final var pks = findEntityWithPricePks(originalProductEntities);

		final List<EntityClassifier> entities = getEntities(
			evita,
			query(
				collection(Entities.PRODUCT),
				filterBy(
					entityPrimaryKeyInSet(pks),
					priceInCurrency(CURRENCY_CZK),
					priceInPriceLists("basic")
				),
				require(
					entityFetch(
						priceContentRespectingFilter()
					)
				)
			)
		);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/PRODUCT/list")
			.httpMethod(Request.METHOD_POST)
			.requestBody(
				"""
                    {
						"filterBy": {
						    "entityPrimaryKeyInSet": %s,
						    "priceInCurrency": "CZK",
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
				Arrays.toString(pks))
			.executeAndThen()
			.statusCode(200)
			.body("", equalTo(createEntityDtos(entities)));
	}

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return all prices for products")
	void shouldReturnAllPricesForProducts(Evita evita, RestTester tester, List<SealedEntity> originalProductEntities) {
		final var pks = findEntityPks(
			originalProductEntities,
			it -> !it.getPrices().isEmpty()
		);

		final List<EntityClassifier> entities = getEntities(
			evita,
			query(
				collection(Entities.PRODUCT),
				filterBy(
					entityPrimaryKeyInSet(pks)
				),
				require(
					entityFetch(
						priceContentRespectingFilter()
					)
				)
			)
		);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/PRODUCT/list")
			.httpMethod(Request.METHOD_POST)
			.requestBody(
	"""
                    {
						"filterBy": {
						    "entityPrimaryKeyInSet": %s
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
				Arrays.toString(pks))
			.executeAndThen()
			.statusCode(200)
			.body("", equalTo(createEntityDtos(entities)));
	}

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return associated data for products")
	void shouldReturnAssociatedDataForProducts(Evita evita, RestTester tester, List<SealedEntity> originalProductEntities) {
		final var pks = findEntityPks(
			originalProductEntities,
			it -> it.getAssociatedData(ASSOCIATED_DATA_LABELS, Locale.ENGLISH) != null &&
				it.getAllLocales().contains(Locale.ENGLISH)
		);

		final List<EntityClassifier> entities = getEntities(
			evita,
			query(
				collection(Entities.PRODUCT),
				filterBy(
					entityPrimaryKeyInSet(pks),
					entityLocaleEquals(Locale.ENGLISH)
				),
				require(
					entityFetch(
						associatedDataContent(ASSOCIATED_DATA_LABELS)
					)
				)
			)
		);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/PRODUCT/list")
			.httpMethod(Request.METHOD_POST)
			.requestBody("""
				{
					"filterBy": {
						"entityPrimaryKeyInSet": %s,
						"entityLocaleEquals": "en"
					},
					"require": {
						"entityFetch": {
							"associatedDataContent": ["labels"]
						}
					}
				}
				""",
				Arrays.toString(pks))
			.executeAndThen()
			.statusCode(200)
			.body("", equalTo(createEntityDtos(entities)));
	}

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return associated data for products with locale in URL")
	void shouldReturnAssociatedDataForProductsWithLocaleInUrl(Evita evita, RestTester tester, List<SealedEntity> originalProductEntities) {
		final var pks = findEntityPks(
			originalProductEntities,
			it -> it.getAssociatedData(ASSOCIATED_DATA_LABELS, Locale.ENGLISH) != null &&
				it.getAllLocales().contains(Locale.ENGLISH)
		);

		final List<EntityClassifier> entities = getEntities(
			evita,
			query(
				collection(Entities.PRODUCT),
				filterBy(
					entityPrimaryKeyInSet(pks),
					entityLocaleEquals(Locale.ENGLISH)
				),
				require(
					entityFetch(
						associatedDataContent(ASSOCIATED_DATA_LABELS)
					)
				)
			)
		);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/" + Locale.ENGLISH.toLanguageTag() + "/PRODUCT/list")
			.httpMethod(Request.METHOD_POST)
			.requestBody("""
				{
					"filterBy": {
						"entityPrimaryKeyInSet": %s
					},
					"require": {
						"entityFetch": {
							"associatedDataContent": ["labels"]
						}
					}
				}
				""",
				Arrays.toString(pks))
			.executeAndThen()
			.statusCode(200)
			.body("", equalTo(createEntityDtos(entities, true)));
	}

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return single reference for products")
	void shouldReturnSingleReferenceForProducts(Evita evita, RestTester tester, List<SealedEntity> originalProductEntities) {
		final var pks = findEntityPks(
			originalProductEntities,
			it -> it.getReferences(Entities.BRAND).size() == 1 &&
				it.getReferences(Entities.BRAND).iterator().next().getAttribute(TestDataGenerator.ATTRIBUTE_MARKET_SHARE) != null
		);


		final List<EntityClassifier> entities = getEntities(
			evita,
			query(
				collection(Entities.PRODUCT),
				filterBy(
					entityPrimaryKeyInSet(pks)
				),
				require(
					entityFetch(
						referenceContent(Entities.BRAND, entityFetch())
					)
				)
			)
		);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/PRODUCT/list")
			.httpMethod(Request.METHOD_POST)
			.requestBody(
					"""
                    {
						"filterBy": {
						    "entityPrimaryKeyInSet": %s
						},
						"require": {
						    "entityFetch": {
						        "referenceBrandContent": {
						             "entityFetch": {}
						        }
					        }
					    }
					}
					""",
				Arrays.toString(pks))
			.executeAndThen()
			.statusCode(200)
			.body("", equalTo(createEntityDtos(entities)));
	}

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should find product by complex query")
	void shouldFindProductByComplexQuery(Evita evita, RestTester tester, List<SealedEntity> originalProductEntities) {
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
			.urlPathSuffix("/PRODUCT/list")
			.httpMethod(Request.METHOD_POST)
			.requestBody("{" +
					"\"filterBy\": {" +
					"\"or\": [" +
					"    {" +
					"        \"attributeAliasEquals\": %b," +
					"        \"attributePriorityEquals\": \"%s\"" +
					"    }," +
					"    {" +
					"        \"attributeAliasEquals\": %b," +
					"        \"attributePriorityEquals\": \"%s\"" +
					"    }," +
					"    {" +
					"        \"attributeAliasEquals\": false," +
					"        \"attributePriorityInSet\": [\"%s\", \"%s\", \"%s\", \"%s\"]" +
					"    }" +
					"]," +
					"\"not\": {" +
					"    \"attributeCodeEquals\": \"%s\"" +
					"}" +
					"}," +
					"\"require\": {" +
					"  \"page\": {" +
					"     \"number\": 1," +
					"     \"size\": %d"+
					"    }" +
					"  }" +
					"}",
				withTrueAlias.get(0).getAttribute(ATTRIBUTE_ALIAS),
				withTrueAlias.get(0).getAttribute(ATTRIBUTE_PRIORITY),
				withTrueAlias.get(1).getAttribute(ATTRIBUTE_ALIAS),
				withTrueAlias.get(1).getAttribute(ATTRIBUTE_PRIORITY),
				(Long)withFalseAlias.get(0).getAttribute(ATTRIBUTE_PRIORITY),
				(Long)withFalseAlias.get(1).getAttribute(ATTRIBUTE_PRIORITY),
				(Long)withFalseAlias.get(2).getAttribute(ATTRIBUTE_PRIORITY),
				(Long)withFalseAlias.get(3).getAttribute(ATTRIBUTE_PRIORITY),
				withFalseAlias.get(4).getAttribute(ATTRIBUTE_CODE),
				Integer.MAX_VALUE)
			.executeAndThen()
			.statusCode(200)
			.body(EntityDescriptor.PRIMARY_KEY.name(), contains(expectedEntities));
	}

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should order entities by complex query")
	void shouldOrderEntitiesByComplexQuery(Evita evita, RestTester tester) {
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
								attributeNatural(TestDataGenerator.ATTRIBUTE_CREATED, DESC),
								attributeNatural(TestDataGenerator.ATTRIBUTE_MANUFACTURED)
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
			.urlPathSuffix("/PRODUCT/list")
			.httpMethod(Request.METHOD_POST)
			.requestBody("""
			    {
			        "filterBy": {
			            "attributePriorityLessThan": 35000
			        },
			        "orderBy": [
			            {
				            "attributeCreatedNatural": "DESC"
				        },
				        {
				            "attributeManufacturedNatural": "ASC"
				        }
			        ],
			        "require": {
			            "page": {
			                "number": 1,
			                "size": 30
			            }
			        }
			    }
			    """)
			.executeAndThen()
			.statusCode(200)
			.body(EntityDescriptor.PRIMARY_KEY.name(), contains(expectedEntities));
	}

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should limit returned entities")
	void shouldLimitReturnedEntities(Evita evita, RestTester tester) {
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
			.urlPathSuffix("/PRODUCT/list")
			.httpMethod(Request.METHOD_POST)
			.requestBody("{" +
				"\"filterBy\": {" +
				"  \"attributePriorityLessThan\": 35000" +
				"}," +
				"\"require\": {" +
				"  \"page\": {" +
				"     \"number\": 1," +
				"     \"size\": 5"+
				"    }" +
				"  }" +
				"}")
			.executeAndThen()
			.statusCode(200)
			.body(EntityDescriptor.PRIMARY_KEY.name(), contains(expectedEntities.stream().limit(5).toArray(Integer[]::new)));
	}
}
