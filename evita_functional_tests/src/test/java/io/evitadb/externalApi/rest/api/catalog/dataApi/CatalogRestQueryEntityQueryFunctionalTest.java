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

import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.query.require.FacetStatisticsDepth;
import io.evitadb.api.query.require.HierarchyRequireConstraint;
import io.evitadb.api.query.require.HierarchyStatistics;
import io.evitadb.api.query.require.StatisticsBase;
import io.evitadb.api.query.require.StatisticsType;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.extraResult.AttributeHistogram;
import io.evitadb.api.requestResponse.extraResult.FacetSummary;
import io.evitadb.api.requestResponse.extraResult.Hierarchy;
import io.evitadb.api.requestResponse.extraResult.Hierarchy.LevelInfo;
import io.evitadb.api.requestResponse.extraResult.HistogramContract;
import io.evitadb.api.requestResponse.extraResult.PriceHistogram;
import io.evitadb.comparator.LocalizedStringComparator;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.api.catalog.dataApi.model.EntityDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.ResponseDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.ExtraResultsDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.FacetSummaryDescriptor.FacetGroupStatisticsDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.FacetSummaryDescriptor.FacetRequestImpactDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.FacetSummaryDescriptor.FacetStatisticsDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HierarchyDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HistogramDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HistogramDescriptor.BucketDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.entity.SectionedAttributesDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.extraResult.LevelInfoDescriptor;
import io.evitadb.externalApi.rest.api.testSuite.TestDataGenerator;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.builder.MapBuilder;
import io.evitadb.test.tester.RestTester;
import io.evitadb.test.tester.RestTester.Request;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import java.text.Collator;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.api.query.order.OrderDirection.DESC;
import static io.evitadb.externalApi.rest.api.testSuite.TestDataGenerator.REST_THOUSAND_PRODUCTS;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.builder.MapBuilder.map;
import static io.evitadb.test.generator.DataGenerator.*;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for REST catalog entity list query.
 *
 * @author Martin Veska, FG Forrest a.s. (c) 2022
 */
class CatalogRestQueryEntityQueryFunctionalTest extends CatalogRestDataEndpointFunctionalTest {

	private static final int SEED = 40;

	private static final String DATA_PATH = ResponseDescriptor.RECORD_PAGE.name() + ".data";
	private static final String HIERARCHY_EXTRA_RESULTS_PATH = ResponseDescriptor.EXTRA_RESULTS.name() + "." + ExtraResultsDescriptor.HIERARCHY.name();

	private static final String SELF_HIERARCHY_EXTRA_RESULTS_PATH = HIERARCHY_EXTRA_RESULTS_PATH + "." + HierarchyDescriptor.SELF.name();
	public static final String SELF_MEGA_MENU_PATH = SELF_HIERARCHY_EXTRA_RESULTS_PATH + ".megaMenu";
	public static final String SELF_ROOT_SIBLINGS_PATH = SELF_HIERARCHY_EXTRA_RESULTS_PATH + ".rootSiblings";

	private static final String REFERENCED_HIERARCHY_EXTRA_RESULTS_PATH = HIERARCHY_EXTRA_RESULTS_PATH + ".category";
	private static final String REFERENCED_MEGA_MENU_PATH = REFERENCED_HIERARCHY_EXTRA_RESULTS_PATH + ".megaMenu";
	private static final String REFERENCED_ROOT_SIBLINGS_PATH = REFERENCED_HIERARCHY_EXTRA_RESULTS_PATH + ".rootSiblings";

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return products by primary key")
	void shouldReturnProductsByPrimaryKey(RestTester tester, List<SealedEntity> originalProductEntities) {
		final var entities = findEntities(
			originalProductEntities,
			it -> it.getAttribute(ATTRIBUTE_CODE) != null
		);

		final var expectedBody = createBasicPageResponse(
			entities,
			entity -> createEntityDto(entity)
				.e(EntityDescriptor.ATTRIBUTES.name(), map()
					.e(SectionedAttributesDescriptor.GLOBAL.name(), map()
						.e(ATTRIBUTE_CODE, entity.getAttribute(ATTRIBUTE_CODE))))
		);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/product/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody("""
                    {
						"filterBy": {
						    "entityPrimaryKeyInSet": [%d, %d]
						},
						"require": {
						    "entityFetch": {
						        "attributeContent": ["code"]
						    }
					    }
					}
					""",
				entities.get(0).getPrimaryKey(),
				entities.get(1).getPrimaryKey())
			.executeAndThen()
			.statusCode(200)
			.body(DATA_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return products by non-localized attribute")
	void shouldReturnProductsByNonLocalizedAttribute(RestTester tester, List<SealedEntity> originalProductEntities) {
		final var entities = findEntities(
			originalProductEntities,
			it -> it.getAttribute(ATTRIBUTE_NAME, Locale.ENGLISH) != null &&
				it.getAllLocales().contains(CZECH_LOCALE) &&
				it.getAllLocales().contains(Locale.ENGLISH)
		);

		final var expectedBody = createBasicPageResponse(
			entities,
			entity -> createEntityDto(entity, Locale.ENGLISH)
				.e(EntityDescriptor.ATTRIBUTES.name(), map()
					.e(SectionedAttributesDescriptor.GLOBAL.name(), map()
						.e(ATTRIBUTE_CODE, entity.getAttribute(ATTRIBUTE_CODE)))
					.e(SectionedAttributesDescriptor.LOCALIZED.name(), map()
						.e(Locale.ENGLISH.toLanguageTag(), map()
							.e(ATTRIBUTE_NAME, entity.getAttribute(ATTRIBUTE_NAME, Locale.ENGLISH)))))
		);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/product/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody(
				"""
                    {
						"filterBy": {
						    "attributeCodeInSet": ["%s", "%s"],
						    "entityLocaleEquals": "en"
						},
						"require": {
						    "entityFetch": {
						        "attributeContent": ["code", "name"]
						    }
					    }
					}
					""",
				entities.get(0).getAttribute(ATTRIBUTE_CODE),
				entities.get(1).getAttribute(ATTRIBUTE_CODE))
			.executeAndThen()
			.statusCode(200)
			.body(DATA_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return products by localized attribute")
	void shouldReturnProductsByLocalizedAttribute(RestTester tester, List<SealedEntity> originalProductEntities) {
		final var entities = findEntities(
			originalProductEntities,
			it -> it.getAttribute(ATTRIBUTE_URL, Locale.ENGLISH) != null &&
				it.getAttribute(ATTRIBUTE_NAME, Locale.ENGLISH) != null
		);

		final var expectedBody = createBasicPageResponse(
			entities,
			entity -> createEntityDto(entity, Locale.ENGLISH)
				.e(EntityDescriptor.ATTRIBUTES.name(), map()
					.e(SectionedAttributesDescriptor.LOCALIZED.name(), map()
						.e(Locale.ENGLISH.toLanguageTag(), map()
							.e(ATTRIBUTE_NAME, entity.getAttribute(ATTRIBUTE_NAME, Locale.ENGLISH))
							.e(ATTRIBUTE_URL, entity.getAttribute(ATTRIBUTE_URL, Locale.ENGLISH)))))
		);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/product/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody(
                """
                    {
						"filterBy": {
							"attributeUrlInSet": ["%s", "%s"],
						    "entityLocaleEquals": "en"
						},
						"require": {
					        "entityFetch": {
					            "attributeContent": ["url", "name"]
					        }
					    }
					}
					""",
				entities.get(0).getAttribute(ATTRIBUTE_URL, Locale.ENGLISH),
				entities.get(1).getAttribute(ATTRIBUTE_URL, Locale.ENGLISH)
			)
			.executeAndThen()
			.statusCode(200)
			.body(DATA_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return products by localized attribute with locale in URL")
	void shouldReturnProductsByLocalizedAttributeWithLocaleInUrl(RestTester tester, List<SealedEntity> originalProductEntities) {
		final var entities = findEntities(
			originalProductEntities,
			it -> it.getAttribute(ATTRIBUTE_URL, Locale.ENGLISH) != null &&
				it.getAttribute(ATTRIBUTE_NAME, Locale.ENGLISH) != null
		);

		final var expectedBody = createBasicPageResponse(
			entities,
			entity -> createEntityDto(entity, Locale.ENGLISH)
				.e(EntityDescriptor.ATTRIBUTES.name(), map()
					.e(ATTRIBUTE_NAME, entity.getAttribute(ATTRIBUTE_NAME, Locale.ENGLISH))
					.e(ATTRIBUTE_URL, entity.getAttribute(ATTRIBUTE_URL, Locale.ENGLISH)))
		);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/" + Locale.ENGLISH.toLanguageTag() + "/product/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody("""
                    {
						"filterBy": {
						    "attributeUrlInSet": ["%s", "%s"]
						},
						"require": {
						    "entityFetch": {
						        "attributeContent": ["url", "name"]
						    }
					    }
					}
					""",
				entities.get(0).getAttribute(ATTRIBUTE_URL, Locale.ENGLISH),
				entities.get(1).getAttribute(ATTRIBUTE_URL, Locale.ENGLISH)
			)
			.executeAndThen()
			.statusCode(200)
			.body(DATA_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should fail when locale is both in body and in URL")
	void shouldFailWhenLocaleIsBothInBodyAndInUrl(RestTester tester, List<SealedEntity> originalProductEntities) {
		tester.test(TEST_CATALOG)
			.urlPathSuffix("/" + Locale.ENGLISH.toLanguageTag() + "/product/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody("{" +
					"\"filterBy\": {" +
					"  \"attributeUrlInSet\": [\"some_url\"]," +
					"  \"entityLocaleEquals\": \"en\"" +
					"} }"
			)
			.executeAndThen()
			.statusCode(400)
			.body("message", notNullValue());
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return error for invalid argument in products query")
	void shouldReturnErrorForInvalidArgumentInProductsQuery(RestTester tester) {
		tester.test(TEST_CATALOG)
			.urlPathSuffix("/product/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody("{" +
				"\"filterBy\": {" +
				"  \"attributeUrlInSet\": [\"xxx\"]" +
				"}," +
				"\"require\": {" +
				"  \"entityFetch_xxx\": {" +
				"     \"attributeContent\": [\"url\", \"name\"]" +
				"    }" +
				"  }" +
				"}"
			)
			.executeAndThen()
			.statusCode(400)
			.body("message", notNullValue());
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return error for invalid query when single value is sent instead of array.")
	void shouldReturnErrorForInvalidQueryWhenSingleValueIsSentInsteadOfArray(RestTester tester, List<SealedEntity> originalProductEntities) {
		final var entities = findEntitiesWithPrice(originalProductEntities);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/product/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody("{" +
					"\"filterBy\": {" +
					"  \"attributeCodeInSet\": \"%s\"" +
					"}" +
					"}",
				entities.get(0).getAttribute(ATTRIBUTE_CODE, String.class)
			)
			.executeAndThen()
			.statusCode(400)
			.body("message", notNullValue());
	}




	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return direct category parent entity references")
	void shouldReturnAllDirectCategoryParentEntityReferences(Evita evita, RestTester tester) {
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

		final var expectedBody = createBasicPageResponse(
			List.of(category),
			entity -> createEntityWithSelfParentsDto(entity, false)
		);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/category/query")
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
			.body(DATA_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return direct category parent entities")
	void shouldReturnAllDirectCategoryParentEntities(Evita evita, RestTester tester) {
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

		final var expectedBody = createBasicPageResponse(
			List.of(category),
			entity -> createEntityWithSelfParentsDto(entity, true)
		);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/category/query")
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
			.body(DATA_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return only direct category parent")
	void shouldReturnOnlyDirectCategoryParent(Evita evita, RestTester tester) {
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

		final var expectedBody = createBasicPageResponse(
			List.of(category),
			entity -> createEntityWithSelfParentsDto(entity, false)
		);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/category/query")
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
			.body(DATA_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return all direct product parent entity references")
	void shouldReturnAllDirectProductParentEntityReferences(Evita evita, RestTester tester) {
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

		final var expectedBody = createBasicPageResponse(
			List.of(product),
			entity -> createEntityWithReferencedParentsDto(entity,  Entities.CATEGORY, false)
		);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/product/query")
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
									"hierarchyContent": {}
								}
							}
						}
					}
				}
				""")
			.executeAndExpectOkAndThen()
			.body(DATA_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return all direct product parent entities")
	void shouldReturnAllDirectProductParentEntities(Evita evita, RestTester tester) {
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

		final var expectedBody = createBasicPageResponse(
			List.of(product),
			entity -> createEntityWithReferencedParentsDto(entity,  Entities.CATEGORY, true)
		);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/product/query")
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
			.body(DATA_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return only direct product parent")
	void shouldReturnOnlyDirectProductParent(Evita evita, RestTester tester) {
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

		final var expectedBody = createBasicPageResponse(
			List.of(product),
			entity -> createEntityWithReferencedParentsDto(entity,  Entities.CATEGORY, false)
		);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/product/query")
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
			.body(DATA_PATH, equalTo(expectedBody));
	}




	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should filter by and return price for sale for multiple products")
	void shouldFilterByAndReturnPriceForSaleForMultipleProducts(RestTester tester, List<SealedEntity> originalProductEntities) {
		final var entities = findEntitiesWithPrice(originalProductEntities);

		final List<ArrayList<Map<String, Object>>> expectedBody = entities.stream()
			.map(sealedEntity -> createPricesDto(sealedEntity, CURRENCY_CZK, "basic"))
			.toList();

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/product/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody(
				"""
                    {
						"filterBy": {
						    "attributeCodeInSet": ["%s", "%s"],
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
				entities.get(0).getAttribute(ATTRIBUTE_CODE),
				entities.get(1).getAttribute(ATTRIBUTE_CODE)
			)
			.executeAndThen()
			.statusCode(200)
			.body(DATA_PATH + ".prices", equalTo(expectedBody));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should filter products by non-existent price")
	void shouldFilterProductsByNonExistentPrice(RestTester tester, List<SealedEntity> originalProductEntities) {
		final List<SealedEntity> entities = findEntitiesWithPrice(originalProductEntities);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/product/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody(
				"""
                    {
						"filterBy": {
						  "attributeCodeInSet": ["%s", "%s"],
						  "priceInCurrency": "CZK",
						  "priceInPriceLists": ["nonexistent"]
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
				entities.get(0).getAttribute(ATTRIBUTE_CODE),
				entities.get(1).getAttribute(ATTRIBUTE_CODE)
			)
			.executeAndThen()
			.statusCode(200)
			.body(DATA_PATH, hasSize(0));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return error for filtering products by unknown currency")
	void shouldReturnErrorForFilteringProductsByUnknownCurrency(RestTester tester, List<SealedEntity> originalProductEntities) {
		final List<SealedEntity> entities = findEntitiesWithPrice(originalProductEntities);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/product/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody(
				"""
                    {
						"filterBy": {
						    "attributeCodeInSet": ["%s", "%s"],
						    "priceInCurrency": "AAA",
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
				entities.get(0).getAttribute(ATTRIBUTE_CODE),
				entities.get(1).getAttribute(ATTRIBUTE_CODE)
			)
			.executeAndThen()
			.statusCode(400)
			.body("message", notNullValue());
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return custom price for sale for products")
	void shouldReturnCustomPriceForSaleForProducts(RestTester tester, List<SealedEntity> originalProductEntities) {
		final var entities = findEntitiesWithPrice(originalProductEntities);

		final List<Map<String, Object>> expectedBody = entities.stream()
			.map(sealedEntity -> createPriceForSaleDto(sealedEntity, CURRENCY_CZK, "basic"))
			.toList();

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/product/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody(
				"""
                    {
						"filterBy": {
						    "attributeCodeInSet": ["%s", "%s"],
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
				entities.get(0).getAttribute(ATTRIBUTE_CODE),
				entities.get(1).getAttribute(ATTRIBUTE_CODE)
			)
			.executeAndThen()
			.statusCode(200)
			.body(DATA_PATH + ".priceForSale", equalTo(expectedBody));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return associated data")
	void shouldReturnAssociatedData(RestTester tester, List<SealedEntity> originalProductEntities) {
		final var entities = findEntities(
			originalProductEntities,
			it -> it.getAssociatedData(ASSOCIATED_DATA_LABELS, Locale.ENGLISH) != null &&
				it.getAllLocales().contains(Locale.ENGLISH)
		);

		final var expectedBody = entities.stream()
			.map(entity -> createEntityDtoWithAssociatedData(entity, Locale.ENGLISH, true))
			.toList();

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/product/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody("{" +
					"\"filterBy\": {" +
					"  \"attributeCodeInSet\": [\"%s\", \"%s\"]," +
					"  \"entityLocaleEquals\": \"en\"" +
					"}," +
					"\"require\": {" +
					"  \"entityFetch\": {" +
					"     \"associatedDataContent\": [\"labels\"]" +
					"    }" +
					"  }" +
					"}",
				entities.get(0).getAttribute(ATTRIBUTE_CODE),
				entities.get(1).getAttribute(ATTRIBUTE_CODE)
			)
			.executeAndThen()
			.statusCode(200)
			.body(DATA_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return associated data with locale in URL")
	void shouldReturnAssociatedDataWithLocaleInUrl(RestTester tester, List<SealedEntity> originalProductEntities) {
		final var entities = findEntities(
			originalProductEntities,
			it -> it.getAssociatedData(ASSOCIATED_DATA_LABELS, Locale.ENGLISH) != null &&
				it.getAllLocales().contains(Locale.ENGLISH)
		);

		final var expectedBody = entities.stream()
			.map(entity -> createEntityDtoWithAssociatedData(entity, Locale.ENGLISH, false))
			.toList();

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/" + Locale.ENGLISH.toLanguageTag() + "/product/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody("{" +
					"\"filterBy\": {" +
					"  \"attributeCodeInSet\": [\"%s\", \"%s\"]" +
					"}," +
					"\"require\": {" +
					"  \"entityFetch\": {" +
					"     \"associatedDataContent\": [\"labels\"]" +
					"    }" +
					"  }" +
					"}",
				entities.get(0).getAttribute(ATTRIBUTE_CODE),
				entities.get(1).getAttribute(ATTRIBUTE_CODE)
			)
			.executeAndThen()
			.statusCode(200)
			.body(DATA_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return single reference for products")
	void shouldReturnSingleReferenceForProducts(RestTester tester, List<SealedEntity> originalProductEntities) {
		final var entities = findEntities(
			originalProductEntities,
			it -> it.getReferences(Entities.BRAND).size() == 1 &&
				it.getReferences(Entities.BRAND).iterator().next().getAttribute(TestDataGenerator.ATTRIBUTE_MARKET_SHARE) != null
		);

		final var expectedBody = entities.stream()
			.map(entity -> map()
				.e(EntityDescriptor.PRIMARY_KEY.name(), entity.getPrimaryKey())
				.e(EntityDescriptor.TYPE.name(), Entities.PRODUCT)
				.e(EntityDescriptor.ALL_LOCALES.name(), List.of(CZECH_LOCALE.toLanguageTag(), Locale.ENGLISH.toLanguageTag()))
				.e(Entities.BRAND.toLowerCase(), createReferenceDto(entity, Entities.BRAND, true, true))
				.build()
			)
			.toList();

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/product/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody("""
                    {
						"filterBy": {
						    "attributeCodeInSet": ["%s", "%s"]
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
				entities.get(0).getAttribute(ATTRIBUTE_CODE),
				entities.get(1).getAttribute(ATTRIBUTE_CODE)
			)
			.executeAndThen()
			.statusCode(200)
			.body(DATA_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return reference list for products")
	void shouldReturnReferenceListForProducts(RestTester tester, List<SealedEntity> originalProductEntities) {
		final var entities = findEntities(
			originalProductEntities,
			it -> it.getReferences(Entities.STORE).size() > 1
		);

		final var expectedBody = entities.stream()
			.map(entity -> map()
				.e(EntityDescriptor.PRIMARY_KEY.name(), entity.getPrimaryKey())
				.e(EntityDescriptor.TYPE.name(), Entities.PRODUCT)
				.e(EntityDescriptor.ALL_LOCALES.name(), List.of(CZECH_LOCALE.toLanguageTag(), Locale.ENGLISH.toLanguageTag()))
				.e(Entities.STORE.toLowerCase(), createReferencesDto(entity, Entities.STORE, true, true))
				.build()
			)
			.toList();

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/product/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody(
					"""
                    {
						"filterBy": {
						  "attributeCodeInSet": ["%s", "%s"]
						},
						"require": {
						    "entityFetch": {
						        "referenceStoreContent": {
					                "entityFetch": {}
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
			.body(DATA_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return filtered and ordered reference list for products")
	void shouldReturnFilteredAndOrderedReferenceListForProducts(RestTester tester, List<SealedEntity> originalProductsEntities, List<SealedEntity> originalStoreEntities) {
		final Map<Integer, SealedEntity> storesIndexedByPk = originalStoreEntities.stream()
			.collect(Collectors.toMap(
				EntityContract::getPrimaryKey,
				Function.identity()
			));

		final Map<Integer, Set<String>> productsWithLotsOfStores = originalProductsEntities.stream()
			.filter(it -> it.getReferences(Entities.STORE).size() > 4 && it.getLocales().contains(CZECH_LOCALE))
			.collect(
				Collectors.toMap(
					EntityContract::getPrimaryKey,
					it -> it.getReferences(Entities.STORE)
						.stream()
						.map(ref -> ref.getReferenceKey().primaryKey())
						.map(storesIndexedByPk::get)
						.map(store -> store.getAttribute(ATTRIBUTE_CODE, String.class))
						.collect(Collectors.toSet())
				)
			);

		final AtomicBoolean atLeastFirst = new AtomicBoolean();
		final Random rnd = new Random(5);
		final String[] randomStores = productsWithLotsOfStores
			.values()
			.stream()
			.flatMap(Collection::stream)
			.filter(it -> atLeastFirst.compareAndSet(false, true) || rnd.nextInt(10) == 0)
			.distinct()
			.toArray(String[]::new);

		final var expectedBody = productsWithLotsOfStores.keySet()
			.stream()
			.sorted()
			.map(id -> originalProductsEntities.stream()
				.filter(it -> it.getPrimaryKey().equals(id))
				.findFirst()
				.orElseThrow())
			.map(entity -> {
				final var references = entity.getReferences(Entities.STORE)
					.stream()
					.map(it -> storesIndexedByPk.get(it.getReferencedPrimaryKey()))
					.filter(it -> it.getAttribute(ATTRIBUTE_NAME, CZECH_LOCALE) != null)
					.sorted(
						Comparator.comparing(
							it -> it.getAttribute(ATTRIBUTE_NAME, CZECH_LOCALE, String.class),
							new LocalizedStringComparator(Collator.getInstance(CZECH_LOCALE)).reversed()
						)
					)
					.map(referencedEntity -> entity.getReference(referencedEntity.getType(), referencedEntity.getPrimaryKey()).orElseThrow())
					.toList();
				assertFalse(references.isEmpty());

				return map()
					.e(EntityDescriptor.PRIMARY_KEY.name(), entity.getPrimaryKey())
					.e(EntityDescriptor.TYPE.name(), Entities.PRODUCT)
					.e(EntityDescriptor.LOCALES.name(), List.of(CZECH_LOCALE.toLanguageTag()))
					.e(EntityDescriptor.ALL_LOCALES.name(), List.of(CZECH_LOCALE.toLanguageTag(), Locale.ENGLISH.toLanguageTag()))
					.e(Entities.STORE.toLowerCase(), createReferencesDto(references, false, false))
					.build();
			})
			.toList();

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/product/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody(
				"""
	                 {
	                    "filterBy": {
	                        "entityPrimaryKeyInSet": [%s],
	                        "entityLocaleEquals": "cs-CZ"
	                    },
	                    "require": {
	                        "page": {
	                            "number": 1,
	                            "size": %d
	                        },
	                        "entityFetch": {
	                            "referenceStoreContent": {
		                            "filterBy": {
		                                "entityHaving": {
		                                    "attributeCodeInSet": [%s]
		                                }
		                            },
		                            "orderBy": {
		                                "entityProperty": {
		                                    "attributeNameNatural": "DESC"
		                                }
		                            }
		                        }
	                        }
	                    }
	                 }
					""",
				productsWithLotsOfStores.keySet().stream().map(Object::toString).collect(Collectors.joining(",")),
				Integer.MAX_VALUE,
				Arrays.stream(randomStores).map(it -> "\"" + it + "\"").collect(Collectors.joining(","))
			)
			.executeAndThen()
			.statusCode(200)
			.body(DATA_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
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
												(Long) withFalseAlias.get(0).getAttribute(ATTRIBUTE_PRIORITY),
												(Long) withFalseAlias.get(1).getAttribute(ATTRIBUTE_PRIORITY),
												(Long) withFalseAlias.get(2).getAttribute(ATTRIBUTE_PRIORITY),
												(Long) withFalseAlias.get(3).getAttribute(ATTRIBUTE_PRIORITY)
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
			.urlPathSuffix("/product/query")
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
					"  \"strip\": {" +
					"     \"limit\": %d" +
					"    }" +
					"  }" +
					"}",
				withTrueAlias.get(0).getAttribute(ATTRIBUTE_ALIAS),
				withTrueAlias.get(0).getAttribute(ATTRIBUTE_PRIORITY),
				withTrueAlias.get(1).getAttribute(ATTRIBUTE_ALIAS),
				withTrueAlias.get(1).getAttribute(ATTRIBUTE_PRIORITY),
				(Long) withFalseAlias.get(0).getAttribute(ATTRIBUTE_PRIORITY),
				(Long) withFalseAlias.get(1).getAttribute(ATTRIBUTE_PRIORITY),
				(Long) withFalseAlias.get(2).getAttribute(ATTRIBUTE_PRIORITY),
				(Long) withFalseAlias.get(3).getAttribute(ATTRIBUTE_PRIORITY),
				withFalseAlias.get(4).getAttribute(ATTRIBUTE_CODE),
				Integer.MAX_VALUE
			)
			.executeAndThen()
			.statusCode(200)
			.body(
				DATA_PATH + "." + EntityDescriptor.PRIMARY_KEY.name(),
				contains(expectedEntities)
			);
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
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
			.urlPathSuffix("/product/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody("{" +
				"\"filterBy\": {" +
				"  \"attributePriorityLessThan\": 35000" +
				"}," +
				"\"orderBy\": {" +
				"  \"attributeCreatedNatural\": \"DESC\"," +
				"  \"attributeManufacturedNatural\": \"ASC\"" +
				"}," +
				"\"require\": {" +
				"  \"strip\": {" +
				"     \"limit\": 30" +
				"    }" +
				"  }" +
				"}")
			.executeAndThen()
			.statusCode(200)
			.body(
				DATA_PATH + "." + EntityDescriptor.PRIMARY_KEY.name(),
				contains(expectedEntities)
			);
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return page of entities")
	void shouldReturnPageOfEntities(Evita evita, RestTester tester) {
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
		assertTrue(expectedEntities.size() > 10);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/product/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody("{" +
				"\"filterBy\": {" +
				"  \"attributePriorityLessThan\": 35000" +
				"}," +
				"\"require\": {" +
				"  \"page\": {" +
				"     \"number\": 2," +
				"     \"size\": 3" +
				"    }" +
				"  }" +
				"}")
			.executeAndThen()
			.statusCode(200)
			.body(
				DATA_PATH + "." + EntityDescriptor.PRIMARY_KEY.name(),
				contains(
					expectedEntities.stream()
						.skip(3)
						.limit(3)
						.toArray(Integer[]::new)
				)
			);
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return strip of entities")
	void shouldReturnStripOfEntities(Evita evita, RestTester tester) {
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
		assertTrue(expectedEntities.size() > 10);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/product/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody("{" +
				"\"filterBy\": {" +
				"  \"attributePriorityLessThan\": 35000" +
				"}," +
				"\"require\": {" +
				"  \"strip\": {" +
				"     \"offset\": 2," +
				"     \"limit\": 3" +
				"    }" +
				"  }" +
				"}")
			.executeAndThen()
			.statusCode(200)
			.body(
				DATA_PATH + "." + EntityDescriptor.PRIMARY_KEY.name(),
				contains(
					expectedEntities.stream()
						.skip(2)
						.limit(3)
						.toArray(Integer[]::new)
				)
			);
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return attribute histogram")
	void shouldReturnAttributeHistogram(Evita evita, RestTester tester) {
		final EvitaResponse<EntityReference> response = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							attributeIsNotNull(ATTRIBUTE_ALIAS)
						),
						require(
							page(1, Integer.MAX_VALUE),
							attributeHistogram(20, ATTRIBUTE_QUANTITY)
						)
					),
					EntityReference.class
				);
			}
		);

		final var expectedHistogram = createAttributeHistogramDto(response, ATTRIBUTE_QUANTITY);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/product/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody("{" +
					"\"filterBy\": {" +
					"  \"attributeAliasIs\": \"NOT_NULL\"" +
					"}," +
					"\"require\": {" +
					"  \"page\": {" +
					"     \"number\": 1," +
					"     \"size\": %d" +
					"    }," +
					"  \"attributeHistogram\": {" +
					"     \"requestedBucketCount\": 20," +
					"     \"attributeName\": [\"" + ATTRIBUTE_QUANTITY + "\"]" +
					"    }" +
					"  }" +
					"}",
				Integer.MAX_VALUE)
			.executeAndThen()
			.statusCode(200)
			.body(
				ResponseDescriptor.EXTRA_RESULTS.name() + "." + ExtraResultsDescriptor.ATTRIBUTE_HISTOGRAM.name() + "." + ATTRIBUTE_QUANTITY,
				equalTo(expectedHistogram)
			);
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return error for missing attribute histogram buckets count")
	void shouldReturnErrorForMissingAttributeHistogramBucketsCount(RestTester tester) {
		tester.test(TEST_CATALOG)
			.urlPathSuffix("/product/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody("{" +
					"\"filterBy\": {" +
					"  \"attributeAliasIs\": \"NOT_NULL\"" +
					"}," +
					"\"require\": {" +
					"  \"page\": {" +
					"     \"number\": 1," +
					"     \"size\": %d" +
					"    }," +
					"  \"attributeHistogram\": {" +
					"     \"attributeName\": [\"" + ATTRIBUTE_QUANTITY + "\"]" +
					"    }" +
					"  }" +
					"}",
				Integer.MAX_VALUE)
			.executeAndThen()
			.statusCode(400)
			.body("message", equalTo("Constraint `attributeHistogram` requires parameter `requestedBucketCount` to be non-null."));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return price histogram")
	void shouldReturnPriceHistogram(Evita evita, RestTester tester) {
		final EvitaResponse<EntityReference> response = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								priceInCurrency(CURRENCY_EUR),
								priceInPriceLists(PRICE_LIST_VIP, PRICE_LIST_BASIC)
							)
						),
						require(
							page(1, Integer.MAX_VALUE),
							priceHistogram(20)
						)
					),
					EntityReference.class
				);
			}
		);

		final var expectedBody = createPriceHistogramDto(response);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/product/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody("{" +
					"\"filterBy\": {" +
					"  \"priceInCurrency\": \"EUR\"," +
					"  \"priceInPriceLists\": [\"vip\",\"basic\"]" +
					"}," +
					"\"require\": {" +
					"  \"page\": {" +
					"     \"number\": 1," +
					"     \"size\": %d" +
					"    }," +
					"  \"priceHistogram\": 20" +
					"  }" +
					"}",
				Integer.MAX_VALUE)
			.executeAndThen()
			.statusCode(200)
			.body(
				ResponseDescriptor.EXTRA_RESULTS.name() + "." + ExtraResultsDescriptor.PRICE_HISTOGRAM.name(),
				equalTo(expectedBody)
			);
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return error for missing price histogram buckets count")
	void shouldReturnErrorForMissingPriceHistogramBucketsCount(RestTester tester) {
		tester.test(TEST_CATALOG)
			.urlPathSuffix("/product/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody("""
					{
						"filterBy": {
							"priceInCurrency": "EUR",
							"priceInPriceLists": ["vip","basic"]
						},
						"require": {
							"page": {
								"number": 1,
								"size": %d
							},
							"priceHistogram": null
						}
					}
					""",
				Integer.MAX_VALUE)
			.executeAndThen()
			.statusCode(400)
			.body("message", equalTo("Constraint `priceHistogram` requires non-null value."));
	}

	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return self hierarchy from root")
	@ParameterizedTest
	@MethodSource("statisticTypeAndBaseVariants")
	void shouldReturnSelfHierarchyFromRoot(EnumSet<StatisticsType> statisticsType, StatisticsBase base, Evita evita, RestTester tester) {
		final Hierarchy hierarchy = createExpectedSelfHierarchy(
			evita,
			entityLocaleEquals(CZECH_LOCALE),
			fromRoot(
				"megaMenu",
				entityFetch(attributeContent()),
				stopAt(distance(2)),
				statisticsType.isEmpty() ? new HierarchyStatistics(base) :
					new HierarchyStatistics(base, statisticsType.toArray(StatisticsType[]::new))
			)
		);
		final List<Map<String, Object>> megaMenuDto = createHierarchyDto(hierarchy.getSelfHierarchy("megaMenu"));
		assertFalse(megaMenuDto.isEmpty());

		fetchSelfHierarchy(
			tester,
			"""
                "entityLocaleEquals": "cs-CZ"
                """,
			"""
				{
					"fromRoot": {
						"outputName": "megaMenu",
						"entityFetch": {
							"attributeContent": ["code"]
						},
						"requirements": {
							"stopAt": {
								"distance": 2
							}
							%s
						}
					}
				}
				""",
			getHierarchyStatisticsConstraint(base, statisticsType)
		)
			.executeAndExpectOkAndThen()
			.body(SELF_MEGA_MENU_PATH, equalTo(megaMenuDto));
	}

	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return self hierarchy from node")
	@ParameterizedTest
	@MethodSource("statisticTypeAndBaseVariants")
	void shouldReturnSelfHierarchyFromNode(EnumSet<StatisticsType> statisticsType, StatisticsBase base, Evita evita, RestTester tester) {
		final Hierarchy hierarchy = createExpectedSelfHierarchy(
			evita,
			and(
				entityLocaleEquals(CZECH_LOCALE),
				hierarchyWithinSelf(entityPrimaryKeyInSet(6))
			),
			fromNode(
				"megaMenu",
				node(filterBy(entityPrimaryKeyInSet(2))),
				entityFetch(attributeContent()),
				stopAt(distance(2)),
				statisticsType.isEmpty() ? new io.evitadb.api.query.require.HierarchyStatistics(base) :
					new io.evitadb.api.query.require.HierarchyStatistics(base, statisticsType.toArray(StatisticsType[]::new))
			)
		);
		final List<Map<String, Object>> megaMenuDto = createHierarchyDto(hierarchy.getSelfHierarchy("megaMenu"));
		assertFalse(megaMenuDto.isEmpty());

		fetchSelfHierarchy(
			tester,
			"""
                "entityLocaleEquals": "cs-CZ",
				"hierarchyWithinSelf": { "ofParent": { "entityPrimaryKeyInSet": [6] } }
                """,
			"""
				{
					"fromNode": {
						"outputName": "megaMenu",
						"node": {
							"filterBy": {
								"entityPrimaryKeyInSet": [2]
							}
						},
						"entityFetch": {
							"attributeContent": ["code"]
						},
						"requirements": {
							"stopAt": {
								"distance": 2
							}
							%s
						}
					}
				}
				""",
			getHierarchyStatisticsConstraint(base, statisticsType)
		)
			.executeAndExpectOkAndThen()
			.body(SELF_MEGA_MENU_PATH, equalTo(megaMenuDto));
	}

	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return self hierarchy children")
	@ParameterizedTest
	@MethodSource("statisticTypeAndBaseVariants")
	void shouldReturnSelfHierarchyChildren(EnumSet<StatisticsType> statisticsType, StatisticsBase base, Evita evita, RestTester tester) {
		final Hierarchy hierarchy = createExpectedSelfHierarchy(
			evita,
			and(
				entityLocaleEquals(CZECH_LOCALE),
				hierarchyWithinSelf(entityPrimaryKeyInSet(1))
			),
			children(
				"megaMenu",
				entityFetch(attributeContent()),
				stopAt(distance(1)),
				statisticsType.isEmpty() ? new io.evitadb.api.query.require.HierarchyStatistics(base) :
					new io.evitadb.api.query.require.HierarchyStatistics(base, statisticsType.toArray(StatisticsType[]::new))
			)
		);
		final List<Map<String, Object>> megaMenuDto = createHierarchyDto(hierarchy.getSelfHierarchy("megaMenu"));
		assertFalse(megaMenuDto.isEmpty());

		fetchSelfHierarchy(
			tester,
			"""
                "entityLocaleEquals": "cs-CZ",
				"hierarchyWithinSelf": { "ofParent": { "entityPrimaryKeyInSet": [1] } }
                """,
			"""
				{
					"children": {
						"outputName": "megaMenu",
						"entityFetch": {
							"attributeContent": ["code"]
						},
						"requirements": {
							"stopAt": {
								"distance": 1
							}
							%s
						}
					}
				}
				""",
			getHierarchyStatisticsConstraint(base, statisticsType)
		)
			.executeAndExpectOkAndThen()
			.body(SELF_MEGA_MENU_PATH, equalTo(megaMenuDto));
	}

	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return self hierarchy parents without siblings")
	@ParameterizedTest
	@MethodSource("statisticTypeAndBaseVariants")
	void shouldReturnSelfHierarchyParentsWithoutSiblings(EnumSet<StatisticsType> statisticsType, StatisticsBase base, Evita evita, RestTester tester) {
		final Hierarchy hierarchy = createExpectedSelfHierarchy(
			evita,
			and(
				entityLocaleEquals(CZECH_LOCALE),
				hierarchyWithinSelf(entityPrimaryKeyInSet(30))
			),
			parents(
				"megaMenu",
				entityFetch(attributeContent()),
				statisticsType.isEmpty() ? new io.evitadb.api.query.require.HierarchyStatistics(base) :
					new io.evitadb.api.query.require.HierarchyStatistics(base, statisticsType.toArray(StatisticsType[]::new))
			)
		);
		final List<Map<String, Object>> megaMenuDto = createHierarchyDto(hierarchy.getSelfHierarchy("megaMenu"));
		assertFalse(megaMenuDto.isEmpty());

		fetchSelfHierarchy(
			tester,
			"""
                "entityLocaleEquals": "cs-CZ",
				"hierarchyWithinSelf": { "ofParent": { "entityPrimaryKeyInSet": [30] } }
                """,
			"""
				{
					"parents": {
						"outputName": "megaMenu",
						"entityFetch": {
							"attributeContent": ["code"]
						},
						"requirements": {
							"stopAt": {
								"distance": 100
							}
							%s
						}
					}
				}
				""",
			getHierarchyStatisticsConstraint(base, statisticsType)
		)
			.executeAndExpectOkAndThen()
			.body(SELF_MEGA_MENU_PATH, equalTo(megaMenuDto));
	}

	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return self hierarchy parents with siblings")
	@ParameterizedTest
	@MethodSource("statisticTypeAndBaseVariants")
	void shouldReturnSelfHierarchyParentsWithSiblings(EnumSet<StatisticsType> statisticsType, StatisticsBase base, Evita evita, RestTester tester) {
		final Hierarchy hierarchy = createExpectedSelfHierarchy(
			evita,
			and(
				entityLocaleEquals(CZECH_LOCALE),
				hierarchyWithinSelf(entityPrimaryKeyInSet(30))
			),
			parents(
				"megaMenu",
				entityFetch(attributeContent()),
				siblings(
					entityFetch(attributeContent()),
					stopAt(distance(2)),
					statisticsType.isEmpty() ? new io.evitadb.api.query.require.HierarchyStatistics(base) :
						new io.evitadb.api.query.require.HierarchyStatistics(base, statisticsType.toArray(StatisticsType[]::new))
				),
				statisticsType.isEmpty() ? new io.evitadb.api.query.require.HierarchyStatistics(base) :
					new io.evitadb.api.query.require.HierarchyStatistics(base, statisticsType.toArray(StatisticsType[]::new))
			)
		);
		final List<Map<String, Object>> megaMenuDto = createHierarchyDto(hierarchy.getSelfHierarchy("megaMenu"));
		assertFalse(megaMenuDto.isEmpty());

		fetchSelfHierarchy(
			tester,
			"""
                "entityLocaleEquals": "cs-CZ",
				"hierarchyWithinSelf": { "ofParent": { "entityPrimaryKeyInSet": [30] } }
                """,
			"""
				{
					"parents": {
						"outputName": "megaMenu",
						"entityFetch": {
							"attributeContent": ["code"]
						},
						"siblings": {
							"requirements": {
								"stopAt": {
									"distance": 2
								}
							}
						},
						"requirements": {
							"stopAt": {
								"distance": 100
							}
							%s
						}
					}
				}
				""",
			getHierarchyStatisticsConstraint(base, statisticsType)
		)
			.executeAndExpectOkAndThen()
			.body(SELF_MEGA_MENU_PATH, equalTo(megaMenuDto));
	}

	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return self hierarchy root siblings")
	@ParameterizedTest
	@MethodSource("statisticTypeAndBaseVariants")
	void shouldReturnSelfHierarchyRootSiblings(EnumSet<StatisticsType> statisticsType, StatisticsBase base, Evita evita, RestTester tester) {
		final Hierarchy hierarchy = createExpectedSelfHierarchy(
			evita,
			entityLocaleEquals(CZECH_LOCALE),
			siblings(
				"rootSiblings",
				entityFetch(attributeContent()),
				stopAt(distance(1)),
				statisticsType.isEmpty() ? new io.evitadb.api.query.require.HierarchyStatistics(base) :
					new io.evitadb.api.query.require.HierarchyStatistics(base, statisticsType.toArray(StatisticsType[]::new))
			)
		);
		final List<Map<String, Object>> rootSiblingsDto = createHierarchyDto(hierarchy.getSelfHierarchy("rootSiblings"));
		assertFalse(rootSiblingsDto.isEmpty());

		fetchSelfHierarchy(
			tester,
			"""
                "entityLocaleEquals": "cs-CZ"
                """,
			"""
				{
					"siblings": {
						"outputName": "rootSiblings",
						"entityFetch": {
							"attributeContent": ["code"]
						},
						"requirements": {
							"stopAt": {
								"distance": 1
							}
							%s
						}
					}
				}
				""",
			getHierarchyStatisticsConstraint(base, statisticsType)
		)
			.executeAndExpectOkAndThen()
			.body(SELF_ROOT_SIBLINGS_PATH, equalTo(rootSiblingsDto));
	}

	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return multiple different self hierarchies")
	@ParameterizedTest
	@MethodSource("statisticTypeAndBaseVariants")
	void shouldReturnMultipleDifferentSelfHierarchies(EnumSet<StatisticsType> statisticsType, StatisticsBase base, Evita evita, RestTester tester) {
		final Hierarchy hierarchy = createExpectedSelfHierarchy(
			evita,
			entityLocaleEquals(CZECH_LOCALE),
			fromRoot(
				"megaMenu",
				entityFetch(attributeContent()),
				stopAt(distance(2)),
				statisticsType.isEmpty() ? new io.evitadb.api.query.require.HierarchyStatistics(base) :
					new io.evitadb.api.query.require.HierarchyStatistics(base, statisticsType.toArray(StatisticsType[]::new))
			),
			siblings(
				"rootSiblings",
				entityFetch(attributeContent()),
				stopAt(distance(1)),
				statisticsType.isEmpty() ? new io.evitadb.api.query.require.HierarchyStatistics(base) :
					new io.evitadb.api.query.require.HierarchyStatistics(base, statisticsType.toArray(StatisticsType[]::new))
			)
		);

		final List<Map<String, Object>> megaMenuDto = createHierarchyDto(hierarchy.getSelfHierarchy("megaMenu"));
		assertFalse(megaMenuDto.isEmpty());

		final List<Map<String, Object>> rootSiblingsDto = createHierarchyDto(hierarchy.getSelfHierarchy("rootSiblings"));
		assertFalse(rootSiblingsDto.isEmpty());

		fetchSelfHierarchy(
			tester,
			"""
                "entityLocaleEquals": "cs-CZ"
                """,
			"""
				{
					"fromRoot": {
						"outputName": "megaMenu",
						"entityFetch": {
							"attributeContent": ["code"]
						},
						"requirements": {
							"stopAt": {
								"distance": 2
							}
							%s
						}
					},
					"siblings": {
						"outputName": "rootSiblings",
						"entityFetch": {
							"attributeContent": ["code"]
						},
						"requirements": {
							"stopAt": {
								"distance": 1
							}
							%s
						}
					}
				}
				""",
			getHierarchyStatisticsConstraint(base, statisticsType),
			getHierarchyStatisticsConstraint(base, statisticsType)
		)
			.executeAndExpectOkAndThen()
			.body(SELF_MEGA_MENU_PATH, equalTo(megaMenuDto))
			.body(SELF_ROOT_SIBLINGS_PATH, equalTo(rootSiblingsDto));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should not return multiple self hierarchies with same output name")
	void shouldNotReturnMultipleSelfHierarchiesWithSameOutputName(Evita evita, RestTester tester) {
		fetchSelfHierarchy(
			tester,
			"""
                "entityLocaleEquals": "cs-CZ"
                """,
			"""
				{
					"fromRoot": {
						"outputName": "megaMenu"
					}
				},
				{
					"siblings": {
						"outputName": "megaMenu"
					}
				}
				"""
		)
			.executeAndExpectServerErrorAndThen();
	}

	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return referenced hierarchy from root")
	@ParameterizedTest
	@MethodSource("statisticTypeAndBaseVariants")
	void shouldReturnReferencedHierarchyFromRoot(EnumSet<StatisticsType> statisticsType, StatisticsBase base, Evita evita, RestTester tester) {
		final Hierarchy hierarchy = createExpectedReferencedHierarchy(
			evita,
			and(
				entityLocaleEquals(CZECH_LOCALE),
				hierarchyWithinRoot(Entities.CATEGORY)
			),
			fromRoot(
				"megaMenu",
				entityFetch(attributeContent()),
				stopAt(distance(2)),
				statisticsType.isEmpty() ? new io.evitadb.api.query.require.HierarchyStatistics(base) :
					new io.evitadb.api.query.require.HierarchyStatistics(base, statisticsType.toArray(StatisticsType[]::new))
			)
		);
		final List<Map<String, Object>> megaMenuDto = createHierarchyDto(hierarchy.getReferenceHierarchy(Entities.CATEGORY, "megaMenu"));
		assertFalse(megaMenuDto.isEmpty());

		fetchReferencedHierarchy(
			tester,
			"""
                "entityLocaleEquals": "cs-CZ"
                """,
			"""
				{
					"fromRoot": {
						"outputName": "megaMenu",
						"entityFetch": {
							"attributeContent": ["code"]
						},
						"requirements": {
							"stopAt": {
								"distance": 2
							}
							%s
						}
					}
				}
				""",
			getHierarchyStatisticsConstraint(base, statisticsType)
		)
			.executeAndExpectOkAndThen()
			.body(REFERENCED_MEGA_MENU_PATH, equalTo(megaMenuDto));
	}

	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return referenced hierarchy from node")
	@ParameterizedTest
	@MethodSource("statisticTypeAndBaseVariants")
	void shouldReturnReferencedHierarchyFromNode(EnumSet<StatisticsType> statisticsType, StatisticsBase base, Evita evita, RestTester tester) {
		final Hierarchy hierarchy = createExpectedReferencedHierarchy(
			evita,
			and(
				entityLocaleEquals(CZECH_LOCALE),
				hierarchyWithin(Entities.CATEGORY,entityPrimaryKeyInSet(6))
			),
			fromNode(
				"megaMenu",
				node(filterBy(entityPrimaryKeyInSet(2))),
				entityFetch(attributeContent()),
				stopAt(distance(2)),
				statisticsType.isEmpty() ? new io.evitadb.api.query.require.HierarchyStatistics(base) :
					new io.evitadb.api.query.require.HierarchyStatistics(base, statisticsType.toArray(StatisticsType[]::new))
			)
		);
		final List<Map<String, Object>> megaMenuDto = createHierarchyDto(hierarchy.getReferenceHierarchy(Entities.CATEGORY, "megaMenu"));
		assertFalse(megaMenuDto.isEmpty());

		fetchReferencedHierarchy(
			tester,
			"""
                "entityLocaleEquals": "cs-CZ",
				"hierarchyCategoryWithin": { "ofParent": { "entityPrimaryKeyInSet": [6] } }
                """,
			"""
				{
					"fromNode": {
						"outputName": "megaMenu",
						"node": {
							"filterBy": {
								"entityPrimaryKeyInSet": [2]
							}
						},
						"entityFetch": {
							"attributeContent": ["code"]
						},
						"requirements": {
							"stopAt": {
								"distance": 2
							}
							%s
						}
					}
				}
				""",
			getHierarchyStatisticsConstraint(base, statisticsType)
		)
			.executeAndExpectOkAndThen()
			.body(REFERENCED_MEGA_MENU_PATH, equalTo(megaMenuDto));
	}

	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return referenced hierarchy children")
	@ParameterizedTest
	@MethodSource("statisticTypeAndBaseVariants")
	void shouldReturnReferencedHierarchyChildren(EnumSet<StatisticsType> statisticsType, StatisticsBase base, Evita evita, RestTester tester) {
		final Hierarchy hierarchy = createExpectedReferencedHierarchy(
			evita,
			and(
				entityLocaleEquals(CZECH_LOCALE),
				hierarchyWithin(Entities.CATEGORY, entityPrimaryKeyInSet(1))
			),
			children(
				"megaMenu",
				entityFetch(attributeContent()),
				stopAt(distance(1)),
				statisticsType.isEmpty() ? new io.evitadb.api.query.require.HierarchyStatistics(base) :
					new io.evitadb.api.query.require.HierarchyStatistics(base, statisticsType.toArray(StatisticsType[]::new))
			)
		);
		final List<Map<String, Object>> megaMenuDto = createHierarchyDto(hierarchy.getReferenceHierarchy(Entities.CATEGORY, "megaMenu"));
		assertFalse(megaMenuDto.isEmpty());

		fetchReferencedHierarchy(
			tester,
			"""
                "entityLocaleEquals": "cs-CZ",
				"hierarchyCategoryWithin": { "ofParent": { "entityPrimaryKeyInSet": [1] } }
                """,
			"""
				{
					"children": {
						"outputName": "megaMenu",
						"entityFetch": {
							"attributeContent": ["code"]
						},
						"requirements": {
							"stopAt": {
								"distance": 1
							}
							%s
						}
					}
				}
				""",
			getHierarchyStatisticsConstraint(base, statisticsType)
		)
			.executeAndExpectOkAndThen()
			.body(REFERENCED_MEGA_MENU_PATH, equalTo(megaMenuDto));
	}

	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return referenced hierarchy parents without siblings")
	@ParameterizedTest
	@MethodSource("statisticTypeAndBaseVariants")
	void shouldReturnReferencedHierarchyParentsWithoutSiblings(EnumSet<StatisticsType> statisticsType, StatisticsBase base, Evita evita, RestTester tester) {
		final Hierarchy hierarchy = createExpectedReferencedHierarchy(
			evita,
			and(
				entityLocaleEquals(CZECH_LOCALE),
				hierarchyWithin(Entities.CATEGORY, entityPrimaryKeyInSet(30))
			),
			parents(
				"megaMenu",
				entityFetch(attributeContent()),
				statisticsType.isEmpty() ? new io.evitadb.api.query.require.HierarchyStatistics(base) :
					new io.evitadb.api.query.require.HierarchyStatistics(base, statisticsType.toArray(StatisticsType[]::new))
			)
		);
		final List<Map<String, Object>> megaMenuDto = createHierarchyDto(hierarchy.getReferenceHierarchy(Entities.CATEGORY, "megaMenu"));
		assertFalse(megaMenuDto.isEmpty());

		fetchReferencedHierarchy(
			tester,
			"""
                "entityLocaleEquals": "cs-CZ",
				"hierarchyCategoryWithin": { "ofParent": { "entityPrimaryKeyInSet": [30] } }
                """,
			"""
				{
					"parents": {
						"outputName": "megaMenu",
						"entityFetch": {
							"attributeContent": ["code"]
						},
						"requirements": {
							"stopAt": {
								"distance": 100
							}
							%s
						}
					}
				}
				""",
			getHierarchyStatisticsConstraint(base, statisticsType)
		)
			.executeAndExpectOkAndThen()
			.body(REFERENCED_MEGA_MENU_PATH, equalTo(megaMenuDto));
	}

	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return referenced hierarchy parents with siblings")
	@ParameterizedTest
	@MethodSource("statisticTypeAndBaseVariants")
	void shouldReturnReferencedHierarchyParentsWithSiblings(EnumSet<StatisticsType> statisticsType, StatisticsBase base, Evita evita, RestTester tester) {
		final Hierarchy hierarchy = createExpectedReferencedHierarchy(
			evita,
			and(
				entityLocaleEquals(CZECH_LOCALE),
				hierarchyWithin(Entities.CATEGORY, entityPrimaryKeyInSet(30))
			),
			parents(
				"megaMenu",
				entityFetch(attributeContent()),
				siblings(
					entityFetch(attributeContent()),
					stopAt(distance(2)),
					statisticsType.isEmpty() ? new io.evitadb.api.query.require.HierarchyStatistics(base) :
						new io.evitadb.api.query.require.HierarchyStatistics(base, statisticsType.toArray(StatisticsType[]::new))
				),
				statisticsType.isEmpty() ? new io.evitadb.api.query.require.HierarchyStatistics(base) :
					new io.evitadb.api.query.require.HierarchyStatistics(base, statisticsType.toArray(StatisticsType[]::new))
			)
		);
		final List<Map<String, Object>> megaMenuDto = createHierarchyDto(hierarchy.getReferenceHierarchy(Entities.CATEGORY, "megaMenu"));
		assertFalse(megaMenuDto.isEmpty());

		fetchReferencedHierarchy(
			tester,
			"""
                "entityLocaleEquals": "cs-CZ",
				"hierarchyCategoryWithin": { "ofParent": { "entityPrimaryKeyInSet": [30] } }
				""",
			"""
				{
					"parents": {
						"outputName": "megaMenu",
						"entityFetch": {
							"attributeContent": ["code"]
						},
						"siblings": {
							"requirements": {
								"stopAt": {
									"distance": 2
								}
							}
						},
						"requirements": {
							"stopAt": {
								"distance": 100
							}
							%s
						}
					}
				}
				""",
			getHierarchyStatisticsConstraint(base, statisticsType)
		)
			.executeAndExpectOkAndThen()
			.body(REFERENCED_MEGA_MENU_PATH, equalTo(megaMenuDto));
	}

	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return referenced hierarchy root siblings")
	@ParameterizedTest
	@MethodSource("statisticTypeAndBaseVariants")
	void shouldReturnReferencedHierarchyRootSiblings(EnumSet<StatisticsType> statisticsType, StatisticsBase base, Evita evita, RestTester tester) {
		final Hierarchy hierarchy = createExpectedReferencedHierarchy(
			evita,
			and(
				entityLocaleEquals(CZECH_LOCALE),
				hierarchyWithinRoot(Entities.CATEGORY)
			),
			siblings(
				"rootSiblings",
				entityFetch(attributeContent()),
				stopAt(distance(1)),
				statisticsType.isEmpty() ? new io.evitadb.api.query.require.HierarchyStatistics(base) :
					new io.evitadb.api.query.require.HierarchyStatistics(base, statisticsType.toArray(StatisticsType[]::new))
			)
		);
		final List<Map<String, Object>> rootSiblingsDto = createHierarchyDto(hierarchy.getReferenceHierarchy(Entities.CATEGORY, "rootSiblings"));
		assertFalse(rootSiblingsDto.isEmpty());

		fetchReferencedHierarchy(
			tester,
			"""
                "entityLocaleEquals": "cs-CZ",
				"hierarchyCategoryWithinRoot": {}
                """,
			"""
				{
					"siblings": {
						"outputName": "rootSiblings",
						"entityFetch": {
							"attributeContent": ["code"]
						},
						"requirements": {
							"stopAt": {
								"distance": 1
							}
							%s
						}
					}
				}
				""",
			getHierarchyStatisticsConstraint(base, statisticsType)
		)
			.executeAndExpectOkAndThen()
			.body(REFERENCED_ROOT_SIBLINGS_PATH, equalTo(rootSiblingsDto));
	}

	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return multiple different referenced hierarchies")
	@ParameterizedTest
	@MethodSource("statisticTypeAndBaseVariants")
	void shouldReturnMultipleDifferentReferencedHierarchies(EnumSet<StatisticsType> statisticsType, StatisticsBase base, Evita evita, RestTester tester) {
		final Hierarchy hierarchy = createExpectedReferencedHierarchy(
			evita,
			and(
				entityLocaleEquals(CZECH_LOCALE),
				hierarchyWithinRoot(Entities.CATEGORY)
			),
			fromRoot(
				"megaMenu",
				entityFetch(attributeContent()),
				stopAt(distance(2)),
				statisticsType.isEmpty() ? new io.evitadb.api.query.require.HierarchyStatistics(base) :
					new io.evitadb.api.query.require.HierarchyStatistics(base, statisticsType.toArray(StatisticsType[]::new))
			),
			siblings(
				"rootSiblings",
				entityFetch(attributeContent()),
				stopAt(distance(1)),
				statisticsType.isEmpty() ? new io.evitadb.api.query.require.HierarchyStatistics(base) :
					new io.evitadb.api.query.require.HierarchyStatistics(base, statisticsType.toArray(StatisticsType[]::new))
			)
		);

		final List<Map<String, Object>> flattenedMegaMenu = createHierarchyDto(hierarchy.getReferenceHierarchy(Entities.CATEGORY, "megaMenu"));
		assertFalse(flattenedMegaMenu.isEmpty());

		final List<Map<String, Object>> flattenedRootSiblings = createHierarchyDto(hierarchy.getReferenceHierarchy(Entities.CATEGORY, "rootSiblings"));
		assertFalse(flattenedRootSiblings.isEmpty());

		fetchReferencedHierarchy(
			tester,
			"""
                "entityLocaleEquals": "cs-CZ",
				"hierarchyCategoryWithinRoot": {}
				""",
			"""
                {
                    "fromRoot": {
						"outputName": "megaMenu",
						"entityFetch": {
							"attributeContent": ["code"]
						},
						"requirements": {
							"stopAt": {
								"distance": 2
							}
							%s
						}
					},
					"siblings": {
						"outputName": "rootSiblings",
						"entityFetch": {
							"attributeContent": ["code"]
						},
						"requirements": {
							"stopAt": {
								"distance": 1
							}
							%s
						}
					}
				}
				""",
			getHierarchyStatisticsConstraint(base, statisticsType),
			getHierarchyStatisticsConstraint(base, statisticsType)
		)
			.executeAndExpectOkAndThen()
			.body(REFERENCED_MEGA_MENU_PATH, equalTo(flattenedMegaMenu))
			.body(REFERENCED_ROOT_SIBLINGS_PATH, equalTo(flattenedRootSiblings));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should not return multiple self hierarchies with same output name")
	void shouldNotReturnMultipleSelfHierarchiesWithReferencedOutputName(Evita evita, RestTester tester) {
		fetchReferencedHierarchy(
			tester,
			"""
                "entityLocaleEquals": "cs-CZ"
                """,
			"""
				{
					"fromRoot": {
						"outputName": "megaMenu"
					}
				},
				{
					"siblings": {
						"outputName": "megaMenu"
					}
				}
				"""
		)
			.executeAndExpectServerErrorAndThen();
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return facet summary with counts for products")
	void shouldReturnFacetSummaryWithCountsForProducts(Evita evita, RestTester tester) {
		final EvitaResponse<EntityReference> response = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.query(
					query(
						collection(Entities.PRODUCT),
						require(
							facetSummaryOfReference(Entities.BRAND, FacetStatisticsDepth.COUNTS)
						)
					),
					EntityReference.class
				);
			}
		);
		assertFalse(response.getExtraResult(FacetSummary.class).getFacetGroupStatistics().isEmpty());

		final var expectedBody = createFacetSummaryWithCountsDto(response);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/product/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody("""
					{
						"require": {
							"facetSummary": {
								"statisticsDepth":"COUNTS"
					        }
						}
					}
					""",
				Integer.MAX_VALUE)
			.executeAndThen()
			.statusCode(200)
			.body(
				ResponseDescriptor.EXTRA_RESULTS.name() + "." + ExtraResultsDescriptor.FACET_SUMMARY.name() + ".brand",
				equalTo(expectedBody)
			);
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return facet summary with impacts and entities for products")
	void shouldReturnFacetSummaryWithImpactsAndEntitiesForProducts(Evita evita, RestTester tester) {
		final EvitaResponse<EntityReference> response = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.query(
					query(
						collection(Entities.PRODUCT),
						require(
							facetSummaryOfReference(
								Entities.BRAND,
								FacetStatisticsDepth.IMPACT,
								entityFetch(attributeContent(ATTRIBUTE_CODE))
							)
						)
					),
					EntityReference.class
				);
			}
		);
		assertFalse(response.getExtraResult(FacetSummary.class).getFacetGroupStatistics().isEmpty());

		final var expectedBody = createFacetSummaryWithImpactsDto(response);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/product/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody("""
					{
						"require": {
							"facetSummary": {
								"statisticsDepth":"IMPACT",
								"requirements": {
					   				"entityFetch": {
					   					"attributeContent": ["code"]
					      			}
					   			}
					        }
						}
					}
					""",
				Integer.MAX_VALUE)
			.executeAndThen()
			.statusCode(200)
			.body(
				ResponseDescriptor.EXTRA_RESULTS.name() + "." + ExtraResultsDescriptor.FACET_SUMMARY.name() + ".brand",
				equalTo(expectedBody)
			);
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
	private List<Map<String, Object>> createBasicPageResponse(@Nonnull List<SealedEntity> entities,
	                                                          @Nonnull Function<SealedEntity, MapBuilder> entityMapper) {
		return entities.stream()
			.map(entity -> entityMapper.apply(entity).build())
			.toList();
	}

	@Nonnull
	private Map<String, Object> createAttributeHistogramDto(@Nonnull EvitaResponse<EntityReference> response,
	                                                        @Nonnull String attributeName) {
		final AttributeHistogram attributeHistogram = response.getExtraResult(AttributeHistogram.class);
		final HistogramContract histogram = attributeHistogram.getHistogram(attributeName);

		return map()
			.e(HistogramDescriptor.MAX.name(), histogram.getMax().toString())
			.e(HistogramDescriptor.BUCKETS.name(), Arrays.stream(histogram.getBuckets())
				.map(bucket -> map()
					.e(BucketDescriptor.INDEX.name(), bucket.getIndex())
					.e(BucketDescriptor.THRESHOLD.name(), bucket.getThreshold().toString())
					.e(BucketDescriptor.OCCURRENCES.name(), bucket.getOccurrences())
					.build())
				.toList())
			.e(HistogramDescriptor.MIN.name(), histogram.getMin().toString())
			.e(HistogramDescriptor.OVERALL_COUNT.name(), histogram.getOverallCount())
			.build();
	}

	@Nonnull
	private Map<String, Object> createPriceHistogramDto(@Nonnull EvitaResponse<EntityReference> response) {
		final PriceHistogram priceHistogram = response.getExtraResult(PriceHistogram.class);

		return map()
			.e(HistogramDescriptor.MIN.name(), priceHistogram.getMin().toString())
			.e(HistogramDescriptor.MAX.name(), priceHistogram.getMax().toString())
			.e(HistogramDescriptor.OVERALL_COUNT.name(), priceHistogram.getOverallCount())
			.e(HistogramDescriptor.BUCKETS.name(), Arrays.stream(priceHistogram.getBuckets())
				.map(bucket -> map()
					.e(BucketDescriptor.INDEX.name(), bucket.getIndex())
					.e(BucketDescriptor.THRESHOLD.name(), bucket.getThreshold().toString())
					.e(BucketDescriptor.OCCURRENCES.name(), bucket.getOccurrences())
					.build())
				.toList())
			.e(HistogramDescriptor.MIN.name(), priceHistogram.getMin().toString())
			.e(HistogramDescriptor.OVERALL_COUNT.name(), priceHistogram.getOverallCount())
			.build();
	}

	@Nonnull
	private Hierarchy createExpectedSelfHierarchy(@Nonnull Evita evita,
	                                              @Nonnull FilterConstraint filterBy,
	                                              @Nonnull HierarchyRequireConstraint... hierarchies) {
		return evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> response = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(filterBy),
						require(
							// we don't need the results whatsoever
							page(1, 0),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							// we need only data about cardinalities
							hierarchyOfSelf(
								orderBy(attributeNatural(ATTRIBUTE_CODE, DESC)),
								hierarchies
							)
						)
					),
					EntityReference.class
				);

				return response.getExtraResult(Hierarchy.class);
			});
	}

	@Nonnull
	private RestTester.Request fetchSelfHierarchy(@Nonnull RestTester tester,
	                                              @Nonnull String filterBy,
	                                              @Nonnull String hierarchies,
	                                              @Nonnull Object... args) {
		return tester.test(TEST_CATALOG)
			.urlPathSuffix("/category/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody(
				String.format(
					"""
						{
							"filterBy": {
								%s
							},
							"require": {
								"page": {
									"number": 1,
									"size": 0
								},
								"hierarchyOfSelf": {
									"orderBy": {
										"attributeCodeNatural": "DESC"
									},
									"requirements": [
										%s
									]
								}
							}
						}
						""",
					filterBy,
					hierarchies
				),
				args);
	}

	@Nonnull
	private Hierarchy createExpectedReferencedHierarchy(@Nonnull Evita evita,
	                                                    @Nonnull FilterConstraint filterBy,
	                                                    @Nonnull HierarchyRequireConstraint... hierarchies) {
		return evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> response = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(filterBy),
						require(
							// we don't need the results whatsoever
							page(1, 0),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							// we need only data about cardinalities
							hierarchyOfReference(
								Entities.CATEGORY,
								orderBy(attributeNatural(ATTRIBUTE_CODE, DESC)),
								hierarchies
							)
						)
					),
					EntityReference.class
				);

				return response.getExtraResult(Hierarchy.class);
			});
	}

	@Nonnull
	private RestTester.Request fetchReferencedHierarchy(@Nonnull RestTester tester,
	                                                    @Nonnull String filterBy,
	                                                    @Nonnull String hierarchies,
	                                                    @Nonnull Object... args) {
		return tester.test(TEST_CATALOG)
			.urlPathSuffix("/product/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody(
				String.format(
					"""
						{
							"filterBy": {
								%s
							},
							"require": {
								"page": {
									"number": 1,
									"size": 0
								},
								"hierarchyCategoryOfReference": {
									"orderBy": {
										"attributeCodeNatural": "DESC"
									},
									"emptyHierarchicalEntityBehaviour": "REMOVE_EMPTY",
									"requirements": [
										%s
									]
								}
							}
						}
						""",
					filterBy,
					hierarchies
				),
				args);
	}
	@Nonnull
	private String getHierarchyStatisticsConstraint(@Nonnull StatisticsBase base, @Nonnull EnumSet<StatisticsType> types) {
		return String.format(
			"""
				, "statistics": {
					"statisticsBase": "%s",
					"statisticsType": [%s]
				}
				""",
			base.name(),
			types.stream()
				.map(t -> "\"" + t.name() + "\"")
				.collect(Collectors.joining(","))
		);
	}

	@Nonnull
	private List<Map<String, Object>> createHierarchyDto(@Nonnull List<LevelInfo> hierarchy) {
		return hierarchy.stream()
			.map(this::createLevelInfoDto)
			.toList();
	}

	private Map<String, Object> createLevelInfoDto(@Nonnull LevelInfo levelInfo) {
		final SealedEntity entity = (SealedEntity) levelInfo.entity();
		final MapBuilder entityDto = createEntityWithSelfParentsDto(entity, false, CZECH_LOCALE)
			.e(EntityDescriptor.ATTRIBUTES.name(), map()
				.e(SectionedAttributesDescriptor.GLOBAL.name(), map()
					.e(ATTRIBUTE_CODE, entity.getAttribute(ATTRIBUTE_CODE))));

		final MapBuilder currentLevelInfoDto = map()
			.e(LevelInfoDescriptor.ENTITY.name(), entityDto);

		if (levelInfo.queriedEntityCount() != null) {
			currentLevelInfoDto.e(LevelInfoDescriptor.QUERIED_ENTITY_COUNT.name(), levelInfo.queriedEntityCount());
		}
		if (levelInfo.childrenCount() != null) {
			currentLevelInfoDto.e(LevelInfoDescriptor.CHILDREN_COUNT.name(), levelInfo.childrenCount());
		}

		if (!levelInfo.children().isEmpty()) {
			currentLevelInfoDto.e(LevelInfoDescriptor.CHILDREN.name(), createHierarchyDto(levelInfo.children()));
		}

		return currentLevelInfoDto.build();
	}

	protected static Stream<Arguments> statisticTypeAndBaseVariants() {
		return Stream.of(
			Arguments.of(EnumSet.noneOf(StatisticsType.class), StatisticsBase.COMPLETE_FILTER),
			Arguments.of(EnumSet.noneOf(StatisticsType.class), StatisticsBase.WITHOUT_USER_FILTER),
			Arguments.of(EnumSet.allOf(StatisticsType.class), StatisticsBase.COMPLETE_FILTER),
			Arguments.of(EnumSet.allOf(StatisticsType.class), StatisticsBase.WITHOUT_USER_FILTER),
			Arguments.of(EnumSet.of(StatisticsType.QUERIED_ENTITY_COUNT), StatisticsBase.COMPLETE_FILTER),
			Arguments.of(EnumSet.of(StatisticsType.QUERIED_ENTITY_COUNT), StatisticsBase.WITHOUT_USER_FILTER),
			Arguments.of(EnumSet.of(StatisticsType.CHILDREN_COUNT), StatisticsBase.COMPLETE_FILTER),
			Arguments.of(EnumSet.of(StatisticsType.CHILDREN_COUNT), StatisticsBase.WITHOUT_USER_FILTER)
		);
	}

	@Nonnull
	private List<Map<String, Object>> createFacetSummaryWithCountsDto(@Nonnull EvitaResponse<EntityReference> response) {
		final FacetSummary facetSummary = response.getExtraResult(FacetSummary.class);

		return facetSummary.getFacetGroupStatistics()
			.stream()
			.filter(groupStatistics -> groupStatistics.getReferenceName().equals(Entities.BRAND))
			.map(groupStatistics ->
				map()
					.e(FacetGroupStatisticsDescriptor.GROUP_ENTITY.name(), null)
					.e(FacetGroupStatisticsDescriptor.COUNT.name(), groupStatistics.getCount())
					.e(FacetGroupStatisticsDescriptor.FACET_STATISTICS.name(), groupStatistics.getFacetStatistics()
						.stream()
						.map(facetStatistics ->
							map()
								.e(FacetStatisticsDescriptor.REQUESTED.name(), facetStatistics.isRequested())
								.e(FacetStatisticsDescriptor.COUNT.name(), facetStatistics.getCount())
								.e(FacetStatisticsDescriptor.FACET_ENTITY.name(), map()
									.e(EntityDescriptor.PRIMARY_KEY.name(), facetStatistics.getFacetEntity().getPrimaryKey())
									.e(EntityDescriptor.TYPE.name(), facetStatistics.getFacetEntity().getType())
									.build())
								.build())
						.toList())
					.build()
			)
			.toList();
	}

	@Nonnull
	private List<Map<String, Object>> createFacetSummaryWithImpactsDto(@Nonnull EvitaResponse<EntityReference> response) {
		final FacetSummary facetSummary = response.getExtraResult(FacetSummary.class);

		return facetSummary.getFacetGroupStatistics()
			.stream()
			.map(groupStatistics ->
				map()
					.e(FacetGroupStatisticsDescriptor.GROUP_ENTITY.name(), null)
					.e(FacetGroupStatisticsDescriptor.COUNT.name(), groupStatistics.getCount())
					.e(FacetGroupStatisticsDescriptor.FACET_STATISTICS.name(), groupStatistics.getFacetStatistics()
						.stream()
						.map(facetStatistics ->
							map()
								.e(FacetStatisticsDescriptor.REQUESTED.name(), facetStatistics.isRequested())
								.e(FacetStatisticsDescriptor.COUNT.name(), facetStatistics.getCount())
								.e(FacetStatisticsDescriptor.FACET_ENTITY.name(), map()
									.e(EntityDescriptor.PRIMARY_KEY.name(), facetStatistics.getFacetEntity().getPrimaryKey())
									.e(EntityDescriptor.TYPE.name(), facetStatistics.getFacetEntity().getType())
									.e(EntityDescriptor.ALL_LOCALES.name(), Arrays.asList(CZECH_LOCALE.toLanguageTag(), Locale.ENGLISH.toLanguageTag()))
									.e(EntityDescriptor.ATTRIBUTES.name(), map()
										.e(SectionedAttributesDescriptor.GLOBAL.name(), map()
											.e(ATTRIBUTE_CODE, ((SealedEntity) facetStatistics.getFacetEntity()).getAttribute(ATTRIBUTE_CODE))
											.build())
										.build())
									.build())
								.e(FacetStatisticsDescriptor.IMPACT.name(), map()
									.e(FacetRequestImpactDescriptor.DIFFERENCE.name(), facetStatistics.getImpact().difference())
									.e(FacetRequestImpactDescriptor.MATCH_COUNT.name(), facetStatistics.getImpact().matchCount())
									.e(FacetRequestImpactDescriptor.HAS_SENSE.name(), facetStatistics.getImpact().hasSense())
									.build())
								.build())
						.toList())
					.build()
			)
			.toList();
	}
}
