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

package io.evitadb.externalApi.rest.api.catalog.dataApi;

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
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.core.Evita;
import io.evitadb.dataType.Scope;
import io.evitadb.externalApi.api.catalog.dataApi.model.EntityDescriptor;
import io.evitadb.externalApi.rest.api.testSuite.TestDataGenerator;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.tester.RestTester;
import io.evitadb.test.tester.RestTester.Request;
import io.evitadb.utils.Assert;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.api.query.QueryConstraints.not;
import static io.evitadb.api.query.order.OrderDirection.DESC;
import static io.evitadb.externalApi.rest.api.testSuite.TestDataGenerator.REST_HUNDRED_ARCHIVED_PRODUCTS_WITH_ARCHIVE;
import static io.evitadb.externalApi.rest.api.testSuite.TestDataGenerator.REST_THOUSAND_PRODUCTS;
import static io.evitadb.externalApi.rest.api.testSuite.TestDataGenerator.SORTABLE_ATTRIBUTE_COMPOUND_CODE_NAME;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.generator.DataGenerator.*;
import static io.evitadb.utils.AssertionUtils.assertSortedResultEquals;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
			it -> it.getAttribute(ATTRIBUTE_CODE) != null,
			2
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
	@UseDataSet(REST_HUNDRED_ARCHIVED_PRODUCTS_WITH_ARCHIVE)
	@DisplayName("Should return archived entities")
	void shouldReturnArchivedEntities(Evita evita, RestTester tester) {
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
			.map(entity -> new EntityReference(entity.getType(), entity.getPrimaryKey()))
			.map(CatalogRestDataEndpointFunctionalTest::createEntityDto)
			.toList();

		tester.test(TEST_CATALOG)
			.post("/PRODUCT/list")
			.requestBody(
				"""
	                {
						"filterBy": {
						    "entityPrimaryKeyInSet": [%d, %d],
						    "scope": ["ARCHIVED"]
						}
					}
					""",
				archivedEntities.get(0).getPrimaryKey(),
				archivedEntities.get(1).getPrimaryKey()
			)
			.executeAndThen()
			.statusCode(200)
			.body("", containsInAnyOrder(expectedBodyOfArchivedEntities.toArray()));
	}

	@Test
	@UseDataSet(REST_HUNDRED_ARCHIVED_PRODUCTS_WITH_ARCHIVE)
	@DisplayName("Should return both live and archived entities explicitly")
	void shouldReturnBothLiveAndArchivedEntitiesExplicitly(Evita evita, RestTester tester) {
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
			.map(entity -> new EntityReference(entity.getType(), entity.getPrimaryKey()))
			.map(CatalogRestDataEndpointFunctionalTest::createEntityDto)
			.toList();

		tester.test(TEST_CATALOG)
			.post("/PRODUCT/list")
			.requestBody(
				"""
	                {
						"filterBy": {
						    "entityPrimaryKeyInSet": [%d, %d, %d, %d],
						    "scope": ["LIVE", "ARCHIVED"]
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
			.body("", containsInAnyOrder(expectedBodyOfArchivedEntities.toArray()));
	}

	@Test
	@UseDataSet(REST_HUNDRED_ARCHIVED_PRODUCTS_WITH_ARCHIVE)
	@DisplayName("Should not return archived entity without scope")
	void shouldNotReturnArchivedEntityWithoutScope(Evita evita, RestTester tester) {
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
			.post("/PRODUCT/list")
			.requestBody(
				"""
	                {
						"filterBy": {
						    "entityPrimaryKeyInSet": [%d]
						}
					}
					""",
				archivedEntity.getPrimaryKey()
			)
			.executeAndThen()
			.statusCode(200)
			.body("", emptyIterable());
	}

	@Test
	@UseDataSet(REST_HUNDRED_ARCHIVED_PRODUCTS_WITH_ARCHIVE)
	@DisplayName("Should return data based on scope")
	void shouldReturnDataBasedOnScope(Evita evita, RestTester tester) {
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
			.map(entity -> createEntityDto(new EntityReference(entity.getType(), entity.getPrimaryKey())))
			.toList();

		tester.test(TEST_CATALOG)
			.post("/PRODUCT/list")
			.requestBody("""
				{
					"filterBy": {
						"entityPrimaryKeyInSet": [%d, %d, %d, %d],
						"inScope": {
							"scope": "LIVE",
							"filtering": [{
								"attributeCodeEquals": "%s"
							}]
						},
						"scope": ["LIVE", "ARCHIVED"]
					}
				}
				""",
				liveEntities.get(0).getPrimaryKey(),
				liveEntities.get(1).getPrimaryKey(),
				archivedEntities.get(0).getPrimaryKey(),
				archivedEntities.get(1).getPrimaryKey(),
				liveEntities.get(0).getAttribute(ATTRIBUTE_CODE))
			.executeAndExpectOkAndThen()
			.body("", containsInAnyOrder(expectedBody.toArray()));
	}

	@Test
	@UseDataSet(REST_HUNDRED_ARCHIVED_PRODUCTS_WITH_ARCHIVE)
	@DisplayName("Should order data based on scope")
	void shouldOrderDataBasedOnScope(Evita evita, RestTester tester) {
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
			.map(CatalogRestDataEndpointFunctionalTest::createEntityDto)
			.toList();

		tester.test(TEST_CATALOG)
			.post("/PRODUCT/list")
			.requestBody("""
				{
					"filterBy": {
						"entityPrimaryKeyInSet": [%d, %d, %d, %d],
						"scope": ["LIVE", "ARCHIVED"]
					},
					"orderBy": [{
						"inScope": {
							"scope": "LIVE",
							"ordering": [{
								"attributePriorityNatural": "DESC"
							}]
						}
					}]
				}
				""",
				liveEntities.get(0).getPrimaryKey(),
				liveEntities.get(1).getPrimaryKey(),
				archivedEntities.get(0).getPrimaryKey(),
				archivedEntities.get(1).getPrimaryKey())
			.executeAndExpectOkAndThen()
			.body("", containsInAnyOrder(expectedBody.toArray()));
	}

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return products by non-localized attribute")
	void shouldReturnProductsByNonLocalizedAttribute(Evita evita, RestTester tester, List<SealedEntity> originalProductEntities) {
		final var pks = findEntityPks(
			originalProductEntities,
			it -> it.getAttribute(ATTRIBUTE_NAME, Locale.ENGLISH) != null &&
				it.getAllLocales().contains(CZECH_LOCALE) &&
				it.getAllLocales().contains(Locale.ENGLISH),
			2
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
				it.getAllLocales().contains(Locale.ENGLISH),
			2
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
				it.getAttribute(ATTRIBUTE_NAME, Locale.ENGLISH) != null,
			2
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
		final var pks = findEntityWithPricePks(originalProductEntities, 2);

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
	@DisplayName("Should return default and custom accompanying prices for products")
	void shouldReturnDefaultAndCustomAccompanyingPricesForProducts(Evita evita, RestTester tester, List<SealedEntity> originalProductEntities) {
		final List<Integer> desiredEntities = originalProductEntities.stream()
			.filter(entity ->
				entity.getPriceInnerRecordHandling().equals(PriceInnerRecordHandling.NONE) &&
					entity.getPrices().stream().map(PriceContract::currency).anyMatch(CURRENCY_EUR::equals) &&
					entity.getPrices().stream().map(PriceContract::priceList).anyMatch(PRICE_LIST_BASIC::equals) &&
					entity.getPrices().stream().map(PriceContract::priceList).anyMatch(PRICE_LIST_REFERENCE::equals) &&
					entity.getPrices().stream().map(PriceContract::priceList).anyMatch(PRICE_LIST_VIP::equals)
			)
			.map(EntityContract::getPrimaryKey)
			.toList();
		assertFalse(desiredEntities.isEmpty());

		final List<EntityClassifier> entities = getEntities(
			evita,
			query(
				collection(Entities.PRODUCT),
				filterBy(
					entityPrimaryKeyInSet(desiredEntities.toArray(Integer[]::new)),
					priceInPriceLists(PRICE_LIST_BASIC),
					priceInCurrency(CURRENCY_EUR)
				),
				require(
					defaultAccompanyingPriceLists(PRICE_LIST_REFERENCE),
					entityFetch(
						priceContent(PriceContentMode.RESPECTING_FILTER),
						accompanyingPriceContentDefault(),
						accompanyingPriceContent("vipPrice", PRICE_LIST_VIP)
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
						    "priceInCurrency": "EUR",
						    "priceInPriceLists": ["basic"]
						},
						"require": {
							"priceDefaultAccompanyingPriceLists": ["reference"],
						    "entityFetch": {
						        "priceContent": {
						            "contentMode": "RESPECTING_FILTER"
					            },
					            "priceAccompanyingPriceContentDefault": true,
					            "priceAccompanyingPriceContent": {
					                "accompanyingPriceName": "vipPrice",
					                "priceLists": ["vip"]
					            }
						    }
						}
					}
					""",
				serializeIntArrayToQueryString(desiredEntities.toArray(Integer[]::new))
			)
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
					.count() > 1,
			2
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
		final var pks = findEntityWithPricePks(originalProductEntities, 2);

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
		final var pks = findEntityWithPricePks(originalProductEntities, 2);

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
		final var pks = findEntityWithPricePks(originalProductEntities, 2);

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
		final var pks = findEntityWithPricePks(originalProductEntities, 2);

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
			it -> !it.getPrices().isEmpty(),
			2
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
				it.getAllLocales().contains(Locale.ENGLISH),
			2
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
				it.getAllLocales().contains(Locale.ENGLISH),
			2
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
				it.getReferences(Entities.BRAND).iterator().next().getAttribute(TestDataGenerator.ATTRIBUTE_MARKET_SHARE) != null,
			2
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
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return reference page for products")
	void shouldReturnReferencePageForProducts(Evita evita, RestTester tester, List<SealedEntity> originalProductEntities) {
		final var entityPks = findEntityPks(
			originalProductEntities,
			it -> it.getReferences(Entities.STORE).size() >= 4,
			2
		);

		final List<SealedEntity> products = getEntities(
			evita,
			query(
				collection(Entities.PRODUCT),
				filterBy(entityPrimaryKeyInSet(entityPks)),
				require(
					page(1, 2),
					entityFetch(
						referenceContent(
							Entities.STORE,
							entityFetch(),
							page(2, 2)
						)
					)
				)
			),
			p -> {},
			SealedEntity.class
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
	                            "referenceStoreContent": {
	                                "entityFetch": {},
	                                "chunking": {
	                                    "page": {
	                                        "number": 2,
	                                        "size": 2
	                                    }
	                                }
	                            }
	                        }
	                    }
	                }
					""",
				serializeIntArrayToQueryString(entityPks)
			)
			.executeAndThen()
			.statusCode(200)
			.body("", equalTo(createEntityDtos(products)));
	}


	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return reference strip for products")
	void shouldReturnReferenceStripForProducts(Evita evita, RestTester tester, List<SealedEntity> originalProductEntities) {
		final var entityPks = findEntityPks(
			originalProductEntities,
			it -> it.getReferences(Entities.STORE).size() >= 4,
			2
		);

		final List<SealedEntity> products = getEntities(
			evita,
			query(
				collection(Entities.PRODUCT),
				filterBy(entityPrimaryKeyInSet(entityPks)),
				require(
					page(1, 2),
					entityFetch(
						referenceContent(
							Entities.STORE,
							entityFetch(),
							strip(2, 2)
						)
					)
				)
			),
			p -> {},
			SealedEntity.class
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
	                            "referenceStoreContent": {
	                                "entityFetch": {},
	                                "chunking": {
	                                    "strip": {
	                                        "offset": 2,
	                                        "limit": 2
	                                    }
	                                }
	                            }
	                        }
	                    }
	                }
					""",
				serializeIntArrayToQueryString(entityPks)
			)
			.executeAndThen()
			.statusCode(200)
			.body("", equalTo(createEntityDtos(products)));
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
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should order entities by sortable attribute compound")
	void shouldOrderEntitiesBySortableAttributeCompound(Evita evita, RestTester tester) {
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
			.urlPathSuffix("/PRODUCT/list")
			.httpMethod(Request.METHOD_POST)
			.requestBody(
				"""
		            {
			            "filterBy": {
	                        "entityLocaleEquals": "cs-CZ"
	                    },
	                    "orderBy": [{
	                        "attributeCodeNameNatural": "DESC"
	                    }],
	                    "require": {
							"strip": {
								"limit": 30
							}
						}
					}
					"""
			)
			.executeAndExpectOkAndThen()
			.body(
				EntityDescriptor.PRIMARY_KEY.name(),
				contains(expectedEntities)
			);
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


	@Test
	@UseDataSet(REST_HUNDRED_PRODUCTS_FOR_SEGMENTS)
	@DisplayName("Should return entities in manually crafter segmented order")
	void shouldReturnDifferentlySortedSegments(Evita evita, RestTester tester) {
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
			"segments": [
			  {
			    "segment": {
			      "orderBy": [{
			        "attributeNameNatural": "DESC"
			      }],
			      "limit": 5
			    }
			  },
			  {
			    "segment": {
			      "orderBy": [{
			        "attributeEanNatural": "DESC"
			      }],
			      "limit": 2
			    }
			  },
			  {
			    "segment": {
			      "orderBy": [{
			        "attributeQuantityNatural": "ASC"
			      }],
			      "limit": 2
			    }
			  }
			]
			""";


		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				assertRestSegmentedQuery(
					"First page must be sorted by name in descending order.",
					session, tester,
					fabricateEvitaQLSegmentedQuery(1, 5, evitaQLSegments),
					fabricateRestSegmentedQuery(1, 5, graphQLSegments)
				);

				assertRestSegmentedQuery(
					"Second page must be sorted by ean in descending order and quantity in asceding order.",
					session, tester,
					fabricateEvitaQLSegmentedQuery(2, 5, evitaQLSegments),
					fabricateRestSegmentedQuery(2, 5, graphQLSegments)
				);

				assertRestSegmentedQuery(
					"Third page must be sorted by PK in ascending order.",
					session, tester,
					fabricateEvitaQLSegmentedQuery(3, 5, evitaQLSegments),
					fabricateRestSegmentedQuery(3, 5, graphQLSegments)
				);

				return null;
			}
		);
	}

	@Test
	@UseDataSet(REST_HUNDRED_PRODUCTS_FOR_SEGMENTS)
	@DisplayName("Should return filtered entities in manually crafter segmented order")
	void shouldReturnDifferentlySortedAndFilteredSegments(Evita evita, RestTester tester) {
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
			"segments": [
			  {
			    "segment": {
			      "entityHaving": {
			        "attributeNameLessThanEquals": "L"
			      },
			      "orderBy": [{
			        "attributeNameNatural": "DESC"
			      }],
			      "limit": 10
			    }
			  },
			  {
			    "segment": {
			      "entityHaving": {
			        "attributeNameLessThanEquals": "P"
			      },
			      "orderBy": [{
			        "attributeEanNatural": "DESC"
			      }],
			      "limit": 8
			    }
			  },
			  {
			    "segment": {
			      "entityHaving": {
			        "attributeNameLessThanEquals": "T"
			      },
			      "orderBy": [{
			        "attributeQuantityNatural": "ASC"
			      }],
			      "limit": 6
			    }
			  }
			]
			""";

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				assertRestSegmentedQuery(
					"First page must be sorted by name in descending order.",
					session, tester,
					fabricateEvitaQLSegmentedQuery(1, 5, evitaQLSegments),
					fabricateRestSegmentedQuery(1, 5, graphQLSegments)
				);

				assertRestSegmentedQuery(
					"Second page must be sorted by name in descending order.",
					session, tester,
					fabricateEvitaQLSegmentedQuery(2, 5, evitaQLSegments),
					fabricateRestSegmentedQuery(2, 5, graphQLSegments)
				);

				assertRestSegmentedQuery(
					"Third page must be sorted by EAN in descending order (excluding items on first two pages).",
					session, tester,
					fabricateEvitaQLSegmentedQuery(3, 5, evitaQLSegments),
					fabricateRestSegmentedQuery(3, 5, graphQLSegments)
				);

				assertRestSegmentedQuery(
					"Fourth page contains 3 entities sorted according to EAN in descending order and ends with first 2 entities sorted according to quantity in ascending order.",
					session, tester,
					fabricateEvitaQLSegmentedQuery(4, 5, evitaQLSegments),
					fabricateRestSegmentedQuery(4, 5, graphQLSegments)
				);

				assertRestSegmentedQuery(
					"Fifth page must have only 4 entities be sorted by quantity in ascending order and must end with first entity sorted by PK in ascending order.",
					session, tester,
					fabricateEvitaQLSegmentedQuery(5, 5, evitaQLSegments),
					fabricateRestSegmentedQuery(5, 5, graphQLSegments)
				);

				assertRestSegmentedQuery(
					"Sixth page must be sorted by PK in ascending order (but only from those entities that hasn't been already provided).",
					session, tester,
					fabricateEvitaQLSegmentedQuery(6, 5, evitaQLSegments),
					fabricateRestSegmentedQuery(6, 5, graphQLSegments)
				);

				assertRestSegmentedQuery(
					"Seventh page must be sorted by PK in ascending order (but only from those entities that hasn't been already provided).",
					session, tester,
					fabricateEvitaQLSegmentedQuery(7, 5, evitaQLSegments),
					fabricateRestSegmentedQuery(7, 5, graphQLSegments)
				);

				return null;
			}
		);
	}

	@DisplayName("Should pass query labels")
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@Test
	void shouldPassQueryLabels(RestTester tester) {
		tester.test(TEST_CATALOG)
			.urlPathSuffix("/PRODUCT/list")
			.httpMethod(Request.METHOD_POST)
			.requestBody("""
				{
					"head": [
						{
							"label": {
								"name": "myLabel1",
								"value": "myValue1"
							}
						},
						{
							"label": {
								"name": "myLabel2",
								"value": 100
							}
						}
					],
					"filterBy": {
						"attributeCodeContains": "a"
					}
				}
				""")
			.executeAndThen()
			.statusCode(200)
			.body("", hasSize(greaterThan(0)));
	}

	@Nonnull
	private static Query fabricateEvitaQLSegmentedQuery(int pageNumber, int pageSize, @Nonnull Segments segments) {
		return query(
			collection(Entities.PRODUCT),
			filterBy(entityLocaleEquals(Locale.ENGLISH)),
			orderBy(segments),
			require(
				page(pageNumber, pageSize),
				debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
			)
		);
	}


	@Nonnull
	private static String fabricateRestSegmentedQuery(int pageNumber, int pageSize, @Nonnull String segments) {
		return String.format(
			"""
			{
				"filterBy": {
					"entityLocaleEquals": "en"
				},
				"orderBy": [{
					%s
				}],
				"require": {
					"page": {
						"number": %d,
						"size": %d
					}
				}
			}
			""",
			segments,
			pageNumber,
			pageSize
		);
	}

	private void assertRestSegmentedQuery(@Nonnull String message,
	                                      @Nonnull EvitaSessionContract session,
	                                      @Nonnull RestTester tester,
	                                      @Nonnull Query sampleEvitaQLQuery,
	                                      @Nonnull String targetRestQuery) {
		final int[] expectedEntities = session.query(sampleEvitaQLQuery, EntityReference.class)
			.getRecordData()
			.stream()
			.mapToInt(EntityReference::getPrimaryKey)
			.toArray();
		assertEquals(5, expectedEntities.length);
		final List<Integer> actualEntities = tester.test(TEST_CATALOG)
			.urlPathSuffix("/PRODUCT/list")
			.httpMethod(Request.METHOD_POST)
			.requestBody(targetRestQuery)
			.executeAndExpectOkAndThen()
			.extract()
			.body()
			.jsonPath()
			.getList(EntityDescriptor.PRIMARY_KEY.name(), Integer.class);
		assertSortedResultEquals(
			message,
			actualEntities,
			expectedEntities
		);
	}
}
