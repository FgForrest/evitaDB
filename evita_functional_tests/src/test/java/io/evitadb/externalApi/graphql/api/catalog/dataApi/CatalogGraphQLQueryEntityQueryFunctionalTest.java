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
import io.evitadb.api.query.require.FacetStatisticsDepth;
import io.evitadb.api.query.require.HistogramBehavior;
import io.evitadb.api.query.require.PriceContentMode;
import io.evitadb.api.query.require.StatisticsBase;
import io.evitadb.api.query.require.StatisticsType;
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
import io.evitadb.api.requestResponse.extraResult.AttributeHistogram;
import io.evitadb.api.requestResponse.extraResult.FacetSummary;
import io.evitadb.api.requestResponse.extraResult.Hierarchy;
import io.evitadb.api.requestResponse.extraResult.Hierarchy.LevelInfo;
import io.evitadb.api.requestResponse.extraResult.HistogramContract;
import io.evitadb.api.requestResponse.extraResult.PriceHistogram;
import io.evitadb.comparator.LocalizedStringComparator;
import io.evitadb.core.Evita;
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.dataType.Scope;
import io.evitadb.externalApi.api.catalog.dataApi.model.AttributesDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.AttributesProviderDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.DataChunkDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.EntityDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.PriceDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.RecordPageDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.ReferenceDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.ResponseDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.AttributeHistogramDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.ExtraResultsDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.FacetSummaryDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.FacetSummaryDescriptor.FacetGroupStatisticsDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.FacetSummaryDescriptor.FacetRequestImpactDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.FacetSummaryDescriptor.FacetStatisticsDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HierarchyDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HistogramDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HistogramDescriptor.BucketDescriptor;
import io.evitadb.externalApi.api.catalog.model.VersionedDescriptor;
import io.evitadb.externalApi.graphql.GraphQLProvider;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.GraphQLEntityDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.entity.PriceForSaleDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.extraResult.LevelInfoDescriptor;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.DataCarrier;
import io.evitadb.test.tester.GraphQLTester;
import io.evitadb.utils.Assert;
import io.evitadb.utils.MapBuilder;
import io.evitadb.utils.StringUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.text.Collator;
import java.text.NumberFormat;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for GraphQL catalog entity list query.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class CatalogGraphQLQueryEntityQueryFunctionalTest extends CatalogGraphQLDataEndpointFunctionalTest {

	private static final String PRODUCT_QUERY_PATH = "data.queryProduct";
	public static final String PRODUCT_QUERY_DATA_PATH = PRODUCT_QUERY_PATH + "." + ResponseDescriptor.RECORD_PAGE.name() + "." + DataChunkDescriptor.DATA.name();
	private static final String CATEGORY_QUERY_PATH = "data.queryCategory";
	public static final String CATEGORY_QUERY_DATA_PATH = CATEGORY_QUERY_PATH + "." + ResponseDescriptor.RECORD_PAGE.name() + "." + DataChunkDescriptor.DATA.name();

	private static final String CATEGORY_HIERARCHY_PATH = CATEGORY_QUERY_PATH + "." + ResponseDescriptor.EXTRA_RESULTS.name() + "." + ExtraResultsDescriptor.HIERARCHY.name();
	private static final String SELF_HIERARCHY_PATH = CATEGORY_HIERARCHY_PATH + "." + HierarchyDescriptor.SELF.name();
	private static final String SELF_MEGA_MENU_PATH = SELF_HIERARCHY_PATH + ".megaMenu";
	private static final String SELF_ROOT_SIBLINGS_PATH = SELF_HIERARCHY_PATH + ".rootSiblings";

	private static final String PRODUCT_HIERARCHY_PATH = PRODUCT_QUERY_PATH + "." + ResponseDescriptor.EXTRA_RESULTS.name() + "." + ExtraResultsDescriptor.HIERARCHY.name();
	private static final String REFERENCED_HIERARCHY_PATH = PRODUCT_HIERARCHY_PATH + ".category";
	private static final String REFERENCED_MEGA_MENU_PATH = REFERENCED_HIERARCHY_PATH + ".megaMenu";
	private static final String REFERENCED_ROOT_SIBLINGS_PATH = REFERENCED_HIERARCHY_PATH + ".rootSiblings";

	private static final String GRAPHQL_THOUSAND_PRODUCTS_FOR_EMPTY_QUERY = GRAPHQL_THOUSAND_PRODUCTS + "forEmptyQuery";

	@DataSet(value = GRAPHQL_THOUSAND_PRODUCTS_FOR_EMPTY_QUERY, openWebApi = GraphQLProvider.CODE, readOnly = false, destroyAfterClass = true)
	protected DataCarrier setUpForEmptyQuery(Evita evita) {
		return super.setUpData(evita, 0, false);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return products by primary key")
	void shouldReturnProductsByPrimaryKey(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final var entities = findEntities(
			originalProductEntities,
			it -> it.getAttribute(ATTRIBUTE_CODE) != null,
			2
		);

		final var expectedBody = createBasicPageResponse(
			entities,
			entity ->
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
		);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    queryProduct(
	                        filterBy: {
	                            entityPrimaryKeyInSet: [%d, %d]
	                        }
	                    ) {
	                        __typename
	                        recordPage {
	                            __typename
	                            data {
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
	                    }
	                }
					""",
				entities.get(0).getPrimaryKey(),
				entities.get(1).getPrimaryKey()
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(PRODUCT_QUERY_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return product version")
	void shouldReturnProductVersions(Evita evita, GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final List<SealedEntity> entities = getEntities(
			evita,
			query(
				collection(Entities.PRODUCT),
				filterBy(
					attributeIsNotNull(ATTRIBUTE_CODE)
				),
				require(
					page(1, 2),
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
					.build())
			.toList();

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    queryProduct(
	                        filterBy: {
	                            entityPrimaryKeyInSet: [%d, %d]
	                        }
	                    ) {
	                        recordPage {
	                            data {
	                                primaryKey
			                        type
			                        version
	                            }
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
			.body(PRODUCT_QUERY_DATA_PATH, equalTo(expectedBody));
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
	                    queryProduct(
	                        filterBy: {
	                            entityPrimaryKeyInSet: [%d, %d],
	                            scope: ARCHIVED
	                        }
	                    ) {
	                        recordPage {
	                            data {
	                                primaryKey
			                        scope
	                            }
	                        }
	                    }
	                }
					""",
				archivedEntities.get(0).getPrimaryKey(),
				archivedEntities.get(1).getPrimaryKey()
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(PRODUCT_QUERY_DATA_PATH, equalTo(expectedBodyOfArchivedEntities));
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
	                    queryProduct(
	                        filterBy: {
	                            entityPrimaryKeyInSet: [%d, %d, %d, %d],
	                            scope: [LIVE, ARCHIVED]
	                        }
	                    ) {
	                        recordPage {
	                            data {
	                                primaryKey
			                        scope
	                            }
	                        }
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
			.body(PRODUCT_QUERY_DATA_PATH, containsInAnyOrder(expectedBodyOfArchivedEntities.toArray()));
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
	                    queryProduct(
	                         filterBy: {
							     entityPrimaryKeyInSet: %d
							 }
                        ) {
                            recordPage {
                                data {
                                    primaryKey
	                                scope
                                }
                            }
	                    }
	                }
					""",
				archivedEntity.getPrimaryKey()
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(PRODUCT_QUERY_DATA_PATH, emptyIterable());
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
					queryProduct(
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
						recordPage {
							data {
								primaryKey
								scope
							}
						}
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
			.body(PRODUCT_QUERY_DATA_PATH, containsInAnyOrder(expectedBody.toArray()));
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
					queryProduct(
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
						recordPage {
							data {
								primaryKey
							}
						}
					}
				}
				""",
				liveEntities.get(0).getPrimaryKey(),
				liveEntities.get(1).getPrimaryKey(),
				archivedEntities.get(0).getPrimaryKey(),
				archivedEntities.get(1).getPrimaryKey())
			.executeAndExpectOkAndThen()
			.body(ERRORS_PATH, nullValue())
			.body(PRODUCT_QUERY_DATA_PATH, containsInAnyOrder(expectedBody.toArray()));
	}

	@Test
	@UseDataSet(value = GRAPHQL_THOUSAND_PRODUCTS_FOR_EMPTY_QUERY, destroyAfterTest = true)
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
	                    queryProduct(
	                        filterBy: {
	                            entityPrimaryKeyInSet: %d
	                        }
	                    ) {
	                        recordPage {
	                            data {
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
	                    }
	                }
					""",
				primaryKey
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				resultPath(PRODUCT_QUERY_PATH, ResponseDescriptor.RECORD_PAGE, DataChunkDescriptor.DATA),
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
				it.getAllLocales().contains(Locale.ENGLISH),
			2
		);

		final var expectedBody = createBasicPageResponse(
			entities,
			entity ->
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
		);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    queryProduct(
	                        filterBy: {
	                            attributeCodeInSet: ["%s", "%s"]
	                        }
	                    ) {
	                        __typename
	                        recordPage {
	                            __typename
	                            data {
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
	                    }
	                }
					""",
				entities.get(0).getAttribute(ATTRIBUTE_CODE),
				entities.get(1).getAttribute(ATTRIBUTE_CODE)
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(PRODUCT_QUERY_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return products by range attribute from variables")
	void shouldReturnProductsByRangeAttributeFromVariables(GraphQLTester tester, Evita evita, List<SealedEntity> originalProductEntities) {
		final List<IntegerNumberRange> uniqueRanges = originalProductEntities.stream()
			.flatMap(entity -> Arrays.stream(entity.getAttribute(ATTRIBUTE_SIZE, IntegerNumberRange[].class)))
			.collect(Collectors.groupingBy(Function.identity(), Collectors.counting()))
			.entrySet()
			.stream()
			.filter(it -> it.getValue() == 1)
			.map(Entry::getKey)
			.toList();
		assertFalse(uniqueRanges.isEmpty());
		final IntegerNumberRange uniqueRange = uniqueRanges.get(0);

		final EvitaResponse<SealedEntity> entities = queryEntities(
			evita,
			query(
				collection(Entities.PRODUCT),
				filterBy(
					attributeEquals(ATTRIBUTE_SIZE, uniqueRange)
				),
				require(
					entityFetch()
				)
			),
			SealedEntity.class
		);

		final var expectedBody = createBasicPageResponse(
			entities.getRecordData(),
			entity ->
				map()
					.e(EntityDescriptor.PRIMARY_KEY.name(), entity.getPrimaryKey())
					.build()
		);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query ($size: IntegerNumberRange!) {
	                    queryProduct(
	                        filterBy: {
	                            attributeSizeInSet: [$size]
	                        }
	                    ) {
	                        __typename
	                        recordPage {
	                            __typename
	                            data {
	                                primaryKey
	                            }
	                        }
	                    }
	                }
					"""
			)
			.variable(
				"size",
				new int[]{uniqueRange.getPreciseFrom(), uniqueRange.getPreciseTo()}
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(PRODUCT_QUERY_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return error when formatted big decimal is missing locale")
	void shouldReturnErrorWhenFormattedBigDecimalIsMissingLocale(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final var entities = findEntities(
			originalProductEntities,
			it -> it.getAttribute(ATTRIBUTE_QUANTITY) != null,
			2
		);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    queryProduct(
	                        filterBy: {
	                            attributeCodeInSet: ["%s", "%s"]
	                        }
                        ) {
                            __typename
	                        recordPage {
	                            __typename
	                            data {
	                                primaryKey
			                        type
		                            attributes {
		                                quantity(formatted: true)
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
			.body(ERRORS_PATH, notNullValue());
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return big decimal attribute variants for products")
	void shouldReturnBigDecimalAttributeVariantsForProducts(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final var entities = findEntities(
			originalProductEntities,
			it -> it.getAttribute(ATTRIBUTE_QUANTITY) != null,
			2
		);

		final var expectedBody = createBasicPageResponse(
			entities,
			entity ->
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
		);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    queryProduct(
	                        filterBy: {
	                            attributeCodeInSet: ["%s", "%s"],
	                            entityLocaleEquals: cs_CZ
	                        }
	                    ) {
	                        __typename
	                        recordPage {
	                            __typename
	                            data {
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
	                    }
	                }
					""",
				entities.get(0).getAttribute(ATTRIBUTE_CODE),
				entities.get(1).getAttribute(ATTRIBUTE_CODE)
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(PRODUCT_QUERY_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return products by localized attribute")
	void shouldReturnProductsByLocalizedAttribute(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final var entities = findEntities(
			originalProductEntities,
			it -> it.getAttribute(ATTRIBUTE_URL, Locale.ENGLISH) != null &&
				it.getAttribute(ATTRIBUTE_NAME, Locale.ENGLISH) != null,
			2
		);

		final var expectedBody = createBasicPageResponse(
			entities,
			entity ->
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
		);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    queryProduct(
	                        filterBy: {
	                            attributeUrlInSet: ["%s", "%s"]
	                            entityLocaleEquals: en
	                        }
	                    ) {
	                        __typename
	                        recordPage {
	                            __typename
	                            data {
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
	                    }
	                }
					""",
				entities.get(0).getAttribute(ATTRIBUTE_URL, Locale.ENGLISH),
				entities.get(1).getAttribute(ATTRIBUTE_URL, Locale.ENGLISH)
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(PRODUCT_QUERY_PATH, equalTo(expectedBody));
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
	                    queryProduct(
	                        filterBy: {
	                            attributeCodeInSet: ["%s", "%s"]
	                        }
	                    ) {
	                        primaryKey
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
	                    queryProduct(code: "product") {
	                        __typename
	                        recordPage {
	                            __typename
	                            data {
	                                primaryKey
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
	@DisplayName("Should return direct category parent entity references")
	void shouldReturnAllDirectCategoryParentEntityReferences(Evita evita, GraphQLTester tester) {
		final SealedEntity category = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final SealedEntity c = session.queryOneSealedEntity(
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
					queryCategory(
						filterBy: {
							entityPrimaryKeyInSet: 16
						}
					) {
						recordPage {
							data {
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
			.body(CATEGORY_QUERY_DATA_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return direct category parent entities")
	void shouldReturnAllDirectCategoryParentEntities(Evita evita, GraphQLTester tester) {
		final SealedEntity category = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final SealedEntity c = session.queryOneSealedEntity(
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
					queryCategory(
						filterBy: {
							entityPrimaryKeyInSet: 16
						}
					) {
						recordPage {
							data {
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
			.body(CATEGORY_QUERY_DATA_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return only direct category parent")
	void shouldReturnOnlyDirectCategoryParent(Evita evita, GraphQLTester tester) {
		final SealedEntity category = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final SealedEntity c = session.queryOneSealedEntity(
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
					queryCategory(
						filterBy: {
							entityPrimaryKeyInSet: 16
						}
					) {
						recordPage {
							data {
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
			.body(CATEGORY_QUERY_DATA_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return all direct product parent entity references")
	void shouldReturnAllDirectProductParentEntityReferences(Evita evita, GraphQLTester tester) {
		final SealedEntity product = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final List<SealedEntity> products = session.queryListOfSealedEntities(
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
					queryProduct(
						filterBy: {
							hierarchyCategoryWithin: {
								ofParent: {
									entityPrimaryKeyInSet: 26
								}
							}
						}
					) {
						recordPage(size: 1) {
							data {
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
					}
				}
				""")
			.executeAndExpectOkAndThen()
			.body(PRODUCT_QUERY_DATA_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return all direct product parent entities")
	void shouldReturnAllDirectProductParentEntities(Evita evita, GraphQLTester tester) {
		final SealedEntity product = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final List<SealedEntity> products = session.queryListOfSealedEntities(
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
					queryProduct(
						filterBy: {
							hierarchyCategoryWithin: {
								ofParent: {
									entityPrimaryKeyInSet: 26
								}
							}
						}
					) {
						recordPage(size: 1) {
							data {
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
					}
				}
				""")
			.executeAndExpectOkAndThen()
			.body(PRODUCT_QUERY_DATA_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return only direct product parent")
	void shouldReturnOnlyDirectProductParent(Evita evita, GraphQLTester tester) {
		final SealedEntity product = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final List<SealedEntity> products = session.queryListOfSealedEntities(
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
					queryProduct(
						filterBy: {
							hierarchyCategoryWithin: {
								ofParent: {
									entityPrimaryKeyInSet: 16
								}
							}
						}
					) {
						recordPage(size: 1) {
							data {
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
					}
				}
				""")
			.executeAndExpectOkAndThen()
			.body(PRODUCT_QUERY_DATA_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should filter by and return price for sale for multiple products")
	void shouldFilterByAndReturnPriceForSaleForMultipleProducts(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final var entities = findEntitiesWithPrice(originalProductEntities, 2);

		final var expectedBody = createBasicPageResponse(entities, this::createEntityDtoWithOnlyOnePriceForSale);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    queryProduct(
	                        filterBy: {
		                        attributeCodeInSet: ["%s", "%s"]
		                        priceInCurrency: CZK,
		                        priceInPriceLists: "basic"
	                        }
                        ) {
                            __typename
	                        recordPage {
	                            __typename
	                            data {
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
	                    }
	                }
					""",
				entities.get(0).getAttribute(ATTRIBUTE_CODE),
				entities.get(1).getAttribute(ATTRIBUTE_CODE)
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(PRODUCT_QUERY_PATH, equalTo(expectedBody));
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

		final var expectedBody = createBasicPageResponse(entities, entity -> createEntityDtoWithAllPricesForSale(entity, priceLists.toArray(String[]::new)));

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    queryProduct(
	                        filterBy: {
		                        entityPrimaryKeyInSet: [%d, %d]
		                        priceInCurrency: CZK,
		                        priceInPriceLists: %s
	                        }
                        ) {
                            __typename
	                        recordPage {
	                            __typename
	                            data {
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
			.body(PRODUCT_QUERY_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return custom multiple prices for sale flag for master products")
	void shouldReturnCustomMultiplePricesForSaleForMasterProducts(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final var entities1 = findEntitiesWithPrice(originalProductEntities, 2);

		final var expectedBody1 = createBasicPageResponse(entities1, entity -> createEntityDtoWithMultiplePricesForSaleAvailable(entity, false));

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    queryProduct(
	                        filterBy: {
		                        attributeCodeInSet: ["%s", "%s"]
	                        }
                        ) {
                            __typename
	                        recordPage {
	                            __typename
	                            data {
	                                primaryKey
			                        type
		                            multiplePricesForSaleAvailable(currency: CZK, priceLists: "basic")
	                            }
	                        }
	                    }
	                }
					""",
				entities1.get(0).getAttribute(ATTRIBUTE_CODE),
				entities1.get(1).getAttribute(ATTRIBUTE_CODE)
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(PRODUCT_QUERY_PATH, equalTo(expectedBody1));

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

		final var expectedBody2 = createBasicPageResponse(entities2, entity -> createEntityDtoWithMultiplePricesForSaleAvailable(entity, true));

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    queryProduct(
	                        filterBy: {
		                        entityPrimaryKeyInSet: [%d, %d]
	                        }
                        ) {
                            __typename
	                        recordPage {
	                            __typename
	                            data {
	                                primaryKey
			                        type
			                        multiplePricesForSaleAvailable(currency: CZK, priceLists: %s)
	                            }
	                        }
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
			.body(PRODUCT_QUERY_PATH, equalTo(expectedBody2));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should filter products by non-existent price")
	void shouldFilterProductsByNonExistentPrice(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final List<SealedEntity> entities = findEntitiesWithPrice(originalProductEntities, 2);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    queryProduct(
	                        filterBy: {
	                            attributeCodeInSet: ["%s", "%s"]
		                        priceInCurrency: CZK,
		                        priceInPriceLists: "nonexistent"
	                        }
                        ) {
                            __typename
	                        recordPage {
	                            __typename
	                            data {
	                                primaryKey
	                                type
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
			.body(PRODUCT_QUERY_PATH + "." + ResponseDescriptor.RECORD_PAGE.name() + "." + DataChunkDescriptor.DATA.name(), hasSize(0));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return error for filtering products by unknown currency")
	void shouldReturnErrorForFilteringProductsByUnknownCurrency(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final List<SealedEntity> entities = findEntitiesWithPrice(originalProductEntities, 2);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    queryProduct(
	                        filterBy: {
	                            attributeCodeInSet: ["%s", "%s"]
		                        priceInCurrency: AAA,
		                        priceInPriceLists: "basic"
	                        }
                        ) {
                            __typename
	                        recordPage {
	                            __typename
	                            data {
	                                primaryKey
	                                type
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
			.body(ERRORS_PATH, hasSize(greaterThan(0)))
			.body(PRODUCT_QUERY_PATH, nullValue());
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return custom price for sale for products")
	void shouldReturnCustomPriceForSaleForProducts(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final var entities = findEntities(
			originalProductEntities,
			it -> it.getPrices(CURRENCY_CZK, PRICE_LIST_BASIC).size() == 1 &&
				it.getPrices(CURRENCY_CZK, PRICE_LIST_BASIC).stream().allMatch(PriceContract::indexed) &&
				!it.getPrices(CURRENCY_EUR).isEmpty() &&
				it.getPrices(CURRENCY_EUR).stream().allMatch(PriceContract::indexed),
			2
		);

		final var expectedBody = createBasicPageResponse(
			entities,
			this::createEntityDtoWithPriceForSale
		);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    queryProduct(
	                        filterBy: {
	                            entityPrimaryKeyInSet: [%d, %d]
	                            priceInCurrency: EUR
	                        }
	                    ) {
	                        __typename
	                        recordPage {
	                            __typename
	                            data {
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
	                    }
	                }
					""",
				entities.get(0).getPrimaryKey(),
				entities.get(1).getPrimaryKey()
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(PRODUCT_QUERY_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return formatted price for sale with entity locale")
	void shouldReturnFormattedPriceForSaleWithEntityLocale(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final var entities = findEntitiesWithPrice(originalProductEntities, 2);

		final var expectedBody = createBasicPageResponse(entities, this::createEntityDtoWithFormattedPriceForSale);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    queryProduct(
	                        filterBy: {
		                        attributeCodeInSet: ["%s", "%s"]
		                        priceInCurrency: CZK,
		                        priceInPriceLists: "basic",
		                        entityLocaleEquals: cs_CZ
	                        }
                        ) {
                            __typename
	                        recordPage {
	                            __typename
	                            data {
		                            priceForSale {
		                                priceWithTax(formatted: true, withCurrency: true)
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
			.body(PRODUCT_QUERY_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return formatted price for sale with custom locale")
	void shouldReturnFormattedPriceForSaleWithCustomLocale(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final var entities = findEntitiesWithPrice(originalProductEntities, 2);

		final var expectedBody = createBasicPageResponse(entities, this::createEntityDtoWithFormattedPriceForSale);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    queryProduct(
	                        filterBy: {
		                        attributeCodeInSet: ["%s", "%s"]
		                        priceInCurrency: CZK,
		                        priceInPriceLists: "basic"
	                        }
                        ) {
                            __typename
	                        recordPage {
	                            __typename
	                            data {
		                            priceForSale(locale: cs_CZ) {
		                                priceWithTax(formatted: true, withCurrency: true)
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
			.body(PRODUCT_QUERY_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return formatted price for sale with custom locale")
	void shouldReturnErrorWhenFormattingPriceForSaleWithoutLocale(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final var entities = findEntitiesWithPrice(originalProductEntities, 2);

		final var expectedBody = createBasicPageResponse(entities, this::createEntityDtoWithFormattedPriceForSale);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    queryProduct(
	                        filterBy: {
		                        attributeCodeInSet: ["%s", "%s"]
		                        priceInCurrency: CZK,
		                        priceInPriceLists: "basic"
	                        }
                        ) {
                            __typename
	                        recordPage {
	                            __typename
	                            data {
		                            priceForSale {
		                                priceWithTax(formatted: true, withCurrency: true)
		                            }
	                            }
	                        }
	                    }
	                }
					""",
				entities.get(0).getAttribute(ATTRIBUTE_CODE),
				entities.get(1).getAttribute(ATTRIBUTE_CODE)
			)
			.executeAndExpectErrorsAndThen();
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return price for products with filter inheritance")
	void shouldReturnPriceForProductsWithFilterInheritance(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final var entities = findEntitiesWithPrice(originalProductEntities, 2);

		final var expectedBody = createBasicPageResponse(
			entities,
			this::createEntityDtoWithPrice
		);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    queryProduct(
	                        filterBy: {
	                            attributeCodeInSet: ["%s", "%s"]
	                            priceInCurrency: CZK
	                            priceInPriceLists: "basic"
	                        }
                        ) {
                            __typename
	                        recordPage {
	                            __typename
	                            data {
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
	                    }
	                }
					""",
				entities.get(0).getAttribute(ATTRIBUTE_CODE),
				entities.get(1).getAttribute(ATTRIBUTE_CODE)
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(PRODUCT_QUERY_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return price for products outside of filter")
	void shouldReturnPriceForProductsOutsideOfFilter(Evita evita, GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final int pk = findEntityPk(
			originalProductEntities,
			it -> it.getPrices().stream().anyMatch(price -> price.priceList().equals(PRICE_LIST_BASIC) && price.currency().equals(CURRENCY_CZK)) &&
				it.getPrices().stream().anyMatch(price -> price.priceList().equals(PRICE_LIST_VIP) && price.currency().equals(CURRENCY_EUR))
		);

		final SealedEntity entity = getEntity(
			evita,
			query(
				collection(Entities.PRODUCT),
				filterBy(
					entityPrimaryKeyInSet(pk),
					priceInCurrency(CURRENCY_CZK),
					priceInPriceLists(PRICE_LIST_BASIC)
				),
				require(
					entityFetch(
						priceContentAll()
					)
				)
			),
			SealedEntity.class
		);
		final Collection<PriceContract> vipPrices = entity.getPrices(CURRENCY_EUR, PRICE_LIST_VIP);
		assertEquals(1, vipPrices.size());

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    queryProduct(
	                        filterBy: {
	                            entityPrimaryKeyInSet: %d
	                            priceInCurrency: CZK
	                            priceInPriceLists: "basic"
	                        }
                        ) {
	                        recordPage {
	                            data {
		                            price(currency: EUR, priceList: "vip") {
		                                priceId
		                            }
	                            }
	                        }
	                    }
	                }
					""",
				pk
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				resultPath(PRODUCT_QUERY_DATA_PATH, GraphQLEntityDescriptor.PRICE),
				equalTo(List.of(
					map()
						.e(PriceDescriptor.PRICE_ID.name(), vipPrices.iterator().next().priceId())
						.build()
				))
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return prices for products outside of filter")
	void shouldReturnPricesForProductsOutsideOfFilter(Evita evita, GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final int pk = findEntityPk(
			originalProductEntities,
			it -> it.getPrices().stream().anyMatch(price -> price.priceList().equals(PRICE_LIST_BASIC) && price.currency().equals(CURRENCY_CZK)) &&
				it.getPrices().stream().anyMatch(price -> price.priceList().equals(PRICE_LIST_VIP) && price.currency().equals(CURRENCY_EUR))
		);

		final SealedEntity entity = getEntity(
			evita,
			query(
				collection(Entities.PRODUCT),
				filterBy(
					entityPrimaryKeyInSet(pk),
					priceInCurrency(CURRENCY_CZK),
					priceInPriceLists(PRICE_LIST_BASIC)
				),
				require(
					entityFetch(
						priceContentAll()
					)
				)
			),
			SealedEntity.class
		);
		final Collection<PriceContract> vipPrices = entity.getPrices(CURRENCY_EUR, PRICE_LIST_VIP);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    queryProduct(
	                        filterBy: {
	                            entityPrimaryKeyInSet: %d
	                            priceInCurrency: CZK
	                            priceInPriceLists: "basic"
	                        }
                        ) {
	                        recordPage {
	                            data {
		                            prices(currency: EUR, priceLists: "vip") {
		                                priceId
		                            }
	                            }
	                        }
	                    }
	                }
					""",
				pk
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				resultPath(PRODUCT_QUERY_DATA_PATH, EntityDescriptor.PRICES),
				equalTo(List.of(
					vipPrices.stream()
						.map(price -> map()
							.e(PriceDescriptor.PRICE_ID.name(), price.priceId())
							.build())
						.toList()
				))
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return price for products")
	void shouldReturnPriceForProducts(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final var entities = findEntitiesWithPrice(originalProductEntities, 2);

		final var expectedBody = createBasicPageResponse(
			entities,
			this::createEntityDtoWithPrice
		);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    queryProduct(
	                        filterBy: {
	                            attributeCodeInSet: ["%s", "%s"]
	                        }
	                    ) {
	                        __typename
	                        recordPage {
	                            __typename
	                            data {
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
	                    }
	                }
					""",
				entities.get(0).getAttribute(ATTRIBUTE_CODE),
				entities.get(1).getAttribute(ATTRIBUTE_CODE)
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(PRODUCT_QUERY_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return formatted price with entity locale")
	void shouldReturnFormattedPriceWithEntityLocale(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final var entities = findEntitiesWithPrice(originalProductEntities, 2);

		final var expectedBody = createBasicPageResponse(
			entities,
			this::createEntityDtoWithFormattedPrice
		);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    queryProduct(
	                        filterBy: {
	                            attributeCodeInSet: ["%s", "%s"],
	                            entityLocaleEquals: cs_CZ
	                        }
	                    ) {
	                        __typename
	                        recordPage {
	                            __typename
	                            data {
		                            price(priceList: "basic", currency: CZK) {
		                                priceWithTax(formatted: true, withCurrency: true)
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
			.body(PRODUCT_QUERY_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return formatted price with custom locale")
	void shouldReturnFormattedPriceWithCustomLocale(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final var entities = findEntitiesWithPrice(originalProductEntities, 2);

		final var expectedBody = createBasicPageResponse(
			entities,
			this::createEntityDtoWithFormattedPrice
		);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    queryProduct(
	                        filterBy: {
	                            attributeCodeInSet: ["%s", "%s"]
	                        }
	                    ) {
	                        __typename
	                        recordPage {
	                            __typename
	                            data {
		                            price(priceList: "basic", currency: CZK, locale: cs_CZ) {
		                                priceWithTax(formatted: true, withCurrency: true)
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
			.body(PRODUCT_QUERY_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return when formatting price without locale")
	void shouldReturnErrorWhenFormattingPriceWithoutLocale(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final var entities = findEntitiesWithPrice(originalProductEntities, 2);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    queryProduct(
	                        filterBy: {
	                            attributeCodeInSet: ["%s", "%s"]
	                        }
	                    ) {
	                        __typename
	                        recordPage {
	                            __typename
	                            data {
		                            price(priceList: "basic", currency: CZK) {
		                                priceWithTax(formatted: true, withCurrency: true)
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
			.body(ERRORS_PATH, hasSize(greaterThan(0)));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return all prices for products")
	void shouldReturnAllPricesForProducts(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final List<SealedEntity> entities = findEntities(
			originalProductEntities,
			it -> !it.getPrices().isEmpty(),
			2
		);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    queryProduct(
	                        filterBy: {
	                            attributeCodeInSet: ["%s", "%s"]
	                        }
	                    ) {
	                        __typename
	                        recordPage {
	                            __typename
	                            data {
	                                primaryKey
			                        type
		                            prices {
		                                priceWithTax
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
			.body(
				PRODUCT_QUERY_PATH + "." + ResponseDescriptor.RECORD_PAGE.name() + "." + DataChunkDescriptor.DATA.name(),
				hasSize(2)
			)
			.body(
				PRODUCT_QUERY_PATH + "." + ResponseDescriptor.RECORD_PAGE.name() + "." + DataChunkDescriptor.DATA.name() + "[0]." + EntityDescriptor.PRICES.name(),
				hasSize(greaterThan(0))
			)
			.body(
				PRODUCT_QUERY_PATH + "." + ResponseDescriptor.RECORD_PAGE.name() + "." + DataChunkDescriptor.DATA.name() + "[1]." + EntityDescriptor.PRICES.name(),
				hasSize(greaterThan(0))
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return filtered prices for products")
	void shouldReturnFilteredPricesForProducts(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final List<SealedEntity> entities = findEntitiesWithPrice(originalProductEntities, 2);

		final var expectedBody = createBasicPageResponse(entities, this::createEntityDtoWithPrices);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    queryProduct(
		                    filterBy: {
		                        entityPrimaryKeyInSet: [%d, %d]
		                    }
	                    ) {
	                        __typename
	                        recordPage {
	                            __typename
	                            data {
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
	                    }
	                }
					""",
				entities.get(0).getPrimaryKey(),
				entities.get(1).getPrimaryKey()
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(PRODUCT_QUERY_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return filtered prices for multiple price lists for products")
	void shouldReturnFilteredPricesForMutliplePriceListsForProducts(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final List<SealedEntity> entities = findEntitiesWithPrice(originalProductEntities, 2, PRICE_LIST_BASIC, PRICE_LIST_VIP);

		final var expectedBody = createBasicPageResponse(
			entities,
			entity ->
				map()
					.e("prices", List.of(
						map()
							.e(PriceDescriptor.PRICE_WITH_TAX.name(), entity.getPrices(CURRENCY_CZK, PRICE_LIST_BASIC).iterator().next().priceWithTax().toString())
							.build(),
						map()
							.e(PriceDescriptor.PRICE_WITH_TAX.name(), entity.getPrices(CURRENCY_CZK, PRICE_LIST_VIP).iterator().next().priceWithTax().toString())
							.build()
					))
					.build()
		);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    queryProduct(
		                    filterBy: {
		                        entityPrimaryKeyInSet: [%d, %d]
		                    }
	                    ) {
	                        __typename
	                        recordPage {
	                            __typename
	                            data {
		                            prices(priceLists: ["basic", "vip"], currency: CZK) {
		                                priceWithTax
		                            }
	                            }
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
			.body(PRODUCT_QUERY_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return formatted prices with entity locale")
	void shouldReturnFormattedPricesWithEntityLocale(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final List<SealedEntity> entities = findEntitiesWithPrice(originalProductEntities, 2);

		final var expectedBody = createBasicPageResponse(
			entities,
			this::createEntityDtoWithFormattedPrices
		);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    queryProduct(
		                    filterBy: {
		                        entityPrimaryKeyInSet: [%d, %d],
		                        entityLocaleEquals: cs_CZ
		                    }
	                    ) {
	                        __typename
	                        recordPage {
	                            __typename
	                            data {
		                            prices(priceLists: "basic", currency: CZK) {
		                                priceWithTax(formatted: true, withCurrency: true)
		                            }
	                            }
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
			.body(PRODUCT_QUERY_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return formatted prices with custom locale")
	void shouldReturnFormattedPricesWithCustomLocale(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final List<SealedEntity> entities = findEntitiesWithPrice(originalProductEntities, 2);

		final var expectedBody = createBasicPageResponse(
			entities,
			this::createEntityDtoWithFormattedPrices
		);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    queryProduct(
		                    filterBy: {
		                        entityPrimaryKeyInSet: [%d, %d]
		                    }
	                    ) {
	                        __typename
	                        recordPage {
	                            __typename
	                            data {
		                            prices(priceLists: "basic", currency: CZK, locale: cs_CZ) {
		                                priceWithTax(formatted: true, withCurrency: true)
		                            }
	                            }
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
			.body(PRODUCT_QUERY_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return error when formatting prices without locale")
	void shouldReturnErrorWhenFormattingPricesWithoutLocale(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final List<SealedEntity> entities = findEntitiesWithPrice(originalProductEntities, 2);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    queryProduct(
		                    filterBy: {
		                        entityPrimaryKeyInSet: [%d, %d]
		                    }
	                    ) {
	                        __typename
	                        recordPage {
	                            __typename
	                            data {
		                            prices(priceLists: "basic", currency: CZK) {
		                                priceWithTax(formatted: true, withCurrency: true)
		                            }
	                            }
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
			.map(EntityContract::getPrimaryKey)
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

		final Map<String, Object> expectedBody = createBasicPageResponse(
			exampleResponse.getRecordData(),
			this::createEntityDtoWithAccompanyingPricesForSinglePriceForSale
		);
		tester.test(TEST_CATALOG)
			.document("""
				query {
					queryProduct(
						filterBy: {
							entityPrimaryKeyInSet: %s,
							priceInPriceLists: "basic",
							priceInCurrency: CZK
						}
					) {
						__typename
						recordPage {
							__typename
							data {
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
					}
				}
				""",
				serializeIntArrayToQueryString(desiredEntities))
			.executeAndExpectOkAndThen()
			.body(PRODUCT_QUERY_PATH, equalTo(expectedBody));
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

		final Map<String, Object> expectedBody = createBasicPageResponse(
			exampleResponse.getRecordData(),
			this::createEntityDtoWithDefaultAccompanyingPriceForSinglePriceForSale
		);
		tester.test(TEST_CATALOG)
			.document("""
				query {
					queryProduct(
						filterBy: {
							entityPrimaryKeyInSet: %s,
							priceInPriceLists: "basic",
							priceInCurrency: EUR
						},
						require: {
							priceDefaultAccompanyingPriceLists: "b2b"
						}
					) {
						__typename
						recordPage {
							__typename
							data {
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
					}
				}
				""",
				serializeIntArrayToQueryString(desiredEntities))
			.executeAndExpectOkAndThen()
			.body(PRODUCT_QUERY_PATH, equalTo(expectedBody));
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

		final Map<String, Object> expectedBody = createBasicPageResponse(
			exampleResponse.getRecordData(),
			this::createEntityDtoWithDefaultAndCustomAccompanyingPricesForSinglePriceForSale
		);
		tester.test(TEST_CATALOG)
			.document("""
				query {
					queryProduct(
						filterBy: {
							entityPrimaryKeyInSet: %s,
							priceInPriceLists: "basic",
							priceInCurrency: EUR
						},
						require: {
							priceDefaultAccompanyingPriceLists: "b2b"
						}
					) {
						__typename
						recordPage {
							__typename
							data {
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
					}
				}
				""",
				serializeIntArrayToQueryString(desiredEntities))
			.executeAndExpectOkAndThen()
			.body(PRODUCT_QUERY_PATH, equalTo(expectedBody));
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

		final Map<String, Object> expectedBody = createBasicPageResponse(
			exampleResponse.getRecordData(),
			this::createEntityDtoWithAccompanyingPricesForSinglePriceForSale
		);
		tester.test(TEST_CATALOG)
			.document("""
				query {
					queryProduct(
						filterBy: {
							entityPrimaryKeyInSet: %s
						}
					) {
						__typename
						recordPage {
							__typename
							data {
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
					}
				}
				""",
				serializeIntArrayToQueryString(desiredEntities))
			.executeAndExpectOkAndThen()
			.body(PRODUCT_QUERY_PATH, equalTo(expectedBody));
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

		final Map<String, Object> expectedBody = createBasicPageResponse(
			exampleResponse.getRecordData(),
			this::createEntityDtoWithAccompanyingPricesForAllPricesForSale
		);
		tester.test(TEST_CATALOG)
			.document("""
				query {
					queryProduct(
						filterBy: {
							entityPrimaryKeyInSet: %s,
							priceInPriceLists: "basic",
							priceInCurrency: CZK
						},
						require: {
							priceDefaultAccompanyingPriceLists: "reference"
						}
					) {
						__typename
						recordPage {
							__typename
							data {
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
					}
				}
				""",
				serializeIntArrayToQueryString(desiredEntities))
			.executeAndExpectOkAndThen()
			.body(PRODUCT_QUERY_PATH, equalTo(expectedBody));
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

		final Map<String, Object> expectedBody = createBasicPageResponse(
			exampleResponse.getRecordData(),
			this::createEntityDtoWithAccompanyingPricesForAllPricesForSale
		);
		tester.test(TEST_CATALOG)
			.document("""
				query {
					queryProduct(
						filterBy: {
							entityPrimaryKeyInSet: %s,
							priceInPriceLists: "basic",
							priceInCurrency: CZK
						}
					) {
						__typename
						recordPage {
							__typename
							data {
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
					}
				}
				""",
				serializeIntArrayToQueryString(desiredEntities))
			.executeAndExpectOkAndThen()
			.body(PRODUCT_QUERY_PATH, equalTo(expectedBody));
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

		final Map<String, Object> expectedBody = createBasicPageResponse(
			exampleResponse.getRecordData(),
			this::createEntityDtoWithAccompanyingPricesForAllPricesForSale
		);
		tester.test(TEST_CATALOG)
			.document("""
				query {
					queryProduct(
						filterBy: {
							entityPrimaryKeyInSet: %s
						}
					) {
						__typename
						recordPage {
							__typename
							data {
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
					}
				}
				""",
				serializeIntArrayToQueryString(desiredEntities))
			.executeAndExpectOkAndThen()
			.body(PRODUCT_QUERY_PATH, equalTo(expectedBody));
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
					queryProduct(
						filterBy: {
							entityPrimaryKeyInSet: %s,
							priceInPriceLists: "basic",
							priceInCurrency: CZK
						}
					) {
						__typename
						recordPage {
							__typename
							data {
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
					queryProduct(
						filterBy: {
							entityPrimaryKeyInSet: %s,
							priceInPriceLists: "basic",
							priceInCurrency: CZK
						}
					) {
						__typename
						recordPage {
							__typename
							data {
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
				it.getAllLocales().contains(Locale.ENGLISH),
			2
		);

		final var expectedBody = createBasicPageResponse(
			entities,
			this::createEntityDtoWithAssociatedData
		);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    queryProduct(
	                        filterBy: {
	                            attributeCodeInSet: ["%s", "%s"]
	                            entityLocaleEquals: en
	                        }
                        ) {
                            __typename
	                        recordPage {
	                            __typename
	                            data {
	                                primaryKey
			                        type
		                            associatedData {
		                                __typename
		                                labels
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
			.body(PRODUCT_QUERY_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return associated data with custom locale for products")
	void shouldReturnAssociatedDataWithCustomLocaleForProducts(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final var entities = findEntities(
			originalProductEntities,
			it -> it.getAssociatedData(ASSOCIATED_DATA_LABELS, Locale.ENGLISH) != null,
			2
		);

		final var expectedBody = createBasicPageResponse(
			entities,
			this::createEntityDtoWithAssociatedData
		);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    queryProduct(
	                        filterBy: {
	                            attributeCodeInSet: ["%s", "%s"]
	                        }
	                    ) {
	                        __typename
	                        recordPage {
	                            __typename
	                            data {
	                                primaryKey
			                        type
		                            associatedData(locale: en) {
		                                __typename
		                                labels
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
			.body(PRODUCT_QUERY_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return single reference for products")
	void shouldReturnSingleReferenceForProducts(Evita evita, GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final var entities = findEntities(
			originalProductEntities,
			it -> it.getReferences(Entities.PARAMETER).size() == 1 &&
				it.getReferences(Entities.PARAMETER).iterator().next().getAttribute(ATTRIBUTE_MARKET_SHARE) != null,
			2
		);

		final var expectedBody = createBasicPageResponse(
			entities,
			entity -> {
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
			}
		);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    queryProduct(
	                        filterBy: {
	                            attributeCodeInSet: ["%s", "%s"]
	                        }
	                    ) {
	                        __typename
	                        recordPage {
	                            __typename
	                            data {
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
	                    }
	                }
					""",
				entities.get(0).getAttribute(ATTRIBUTE_CODE),
				entities.get(1).getAttribute(ATTRIBUTE_CODE)
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(PRODUCT_QUERY_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return reference list for products")
	void shouldReturnReferenceListForProducts(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final var entities = findEntities(
			originalProductEntities,
			it -> it.getReferences(Entities.STORE).size() > 1,
			2
		);

		final var expectedBody = createBasicPageResponse(
			entities,
			entity -> {
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
			}
		);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    queryProduct(
	                        filterBy: {
	                            attributeCodeInSet: ["%s", "%s"]
	                        }
	                    ) {
	                        __typename
	                        recordPage {
	                            __typename
	                            data {
	                                primaryKey
			                        type
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
				entities.get(0).getAttribute(ATTRIBUTE_CODE),
				entities.get(1).getAttribute(ATTRIBUTE_CODE)
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(PRODUCT_QUERY_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return filtered and ordered reference list for products")
	void shouldReturnFilteredAndOrderedReferenceListForProducts(GraphQLTester tester, List<SealedEntity> originalProductsEntities, List<SealedEntity> originalStoreEntities) {
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

		final var expectedBody = createBasicPageResponse(
			productsWithLotsOfStores.keySet()
				.stream()
				.sorted()
				.map(id -> originalProductsEntities.stream()
					.filter(it -> it.getPrimaryKey().equals(id))
					.findFirst()
					.orElseThrow())
				.toList(),
			entity -> {
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
					.map(reference ->
						map()
							.e(ReferenceDescriptor.REFERENCED_ENTITY.name(), map()
								.e(EntityDescriptor.PRIMARY_KEY.name(), reference.getPrimaryKey()))
							.build())
					.toList();
				assertFalse(references.isEmpty());

				return map()
					.e(EntityDescriptor.PRIMARY_KEY.name(), entity.getPrimaryKey())
					.e(EntityDescriptor.TYPE.name(), Entities.PRODUCT)
					.e("store", references)
					.build();
			}
		);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    queryProduct(
	                        filterBy: {
	                            entityPrimaryKeyInSet: [%s],
	                            entityLocaleEquals: cs_CZ
	                        }
	                    ) {
	                        __typename
	                        recordPage(number: 1, size: %d) {
	                            __typename
	                            data {
	                                primaryKey
			                        type
		                            store(
		                                filterBy: {
		                                    entityHaving: {
		                                        attributeCodeInSet: [%s]
		                                    }
		                                },
		                                orderBy: {
		                                    entityProperty: {
		                                        attributeNameNatural: DESC
		                                    }
		                                }
		                            ) {
		                                referencedEntity {
		                                    primaryKey
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
			.body(ERRORS_PATH, nullValue())
			.body(PRODUCT_QUERY_PATH, equalTo(expectedBody));
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

		final var expectedBody = createBasicPageResponse(
			entities,
			entity -> map()
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
		);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    queryProduct(
	                        filterBy: {
	                            attributeCodeInSet: ["%s", "%s"]
	                        }
	                    ) {
	                        __typename
	                        recordPage {
	                            __typename
	                            data {
	                                primaryKey
			                        type
		                            store(limit: 2) {
		                                referencedEntity {
		                                    primaryKey
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
			.body(PRODUCT_QUERY_PATH, equalTo(expectedBody));
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

		final var expectedBody = createBasicPageResponse(
			entities,
			entity -> map()
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
		);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    queryProduct(
	                        filterBy: {
	                            attributeCodeInSet: ["%s", "%s"]
	                        }
	                    ) {
	                        __typename
	                        recordPage {
	                            __typename
	                            data {
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
	                    }
	                }
					""",
				entities.get(0).getAttribute(ATTRIBUTE_CODE),
				entities.get(1).getAttribute(ATTRIBUTE_CODE)
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(PRODUCT_QUERY_PATH, equalTo(expectedBody));
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

		final var expectedBody = createBasicPageResponse(
			entities,
			entity -> map()
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
		);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    queryProduct(
	                        filterBy: {
	                            attributeCodeInSet: ["%s", "%s"]
	                        }
	                    ) {
	                        __typename
	                        recordPage {
	                            __typename
	                            data {
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
	                    }
	                }
					""",
				entities.get(0).getAttribute(ATTRIBUTE_CODE),
				entities.get(1).getAttribute(ATTRIBUTE_CODE)
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(PRODUCT_QUERY_PATH, equalTo(expectedBody));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return reference total count for products")
	void shouldReturnReferenceTotalCountForProducts(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final var entities = findEntities(
			originalProductEntities,
			it -> it.getReferences(Entities.STORE).size() >= 4,
			2
		);

		final var expectedBody = createBasicPageResponse(
			entities,
			entity -> map()
				.e(EntityDescriptor.PRIMARY_KEY.name(), entity.getPrimaryKey())
				.e(EntityDescriptor.TYPE.name(), Entities.PRODUCT)
				.e("storePage", map()
					.e(DataChunkDescriptor.TOTAL_RECORD_COUNT.name(), entity.getReferences(Entities.STORE).size()))
				.build()
		);

		tester.test(TEST_CATALOG)
			.document(
				"""
	                query {
	                    queryProduct(
	                        filterBy: {
	                            attributeCodeInSet: ["%s", "%s"]
	                        }
	                    ) {
	                        __typename
	                        recordPage {
	                            __typename
	                            data {
	                                primaryKey
			                        type
		                            storePage {
		                                totalRecordCount
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
			.body(PRODUCT_QUERY_PATH, equalTo(expectedBody));
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
			.document(
				"""
	                query {
	                    queryProduct(
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
	                    ) {
	                        recordStrip(limit: %d) {
	                            data {
	                                primaryKey
	                            }
	                        }
	                    }
	                }
					""",
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
			.body(ERRORS_PATH, nullValue())
			.body(
				PRODUCT_QUERY_PATH + "." + ResponseDescriptor.RECORD_STRIP.name() + "." + DataChunkDescriptor.DATA.name() + "." + EntityDescriptor.PRIMARY_KEY.name(),
				contains(expectedEntities)
			);
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
		                queryProduct(
		                    filterBy: {
		                        attributePriorityLessThan: "35000"
		                    }
		                    orderBy: {
		                        attributeCreatedNatural: DESC,
		                        attributeManufacturedNatural: ASC
		                    }
		                ) {
		                    recordStrip(limit: 30) {
		                        data {
		                            primaryKey
		                        }
		                    }
		                }
		            }
					"""
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				PRODUCT_QUERY_PATH + "." + ResponseDescriptor.RECORD_STRIP.name() + "." + DataChunkDescriptor.DATA.name() + "." + EntityDescriptor.PRIMARY_KEY.name(),
				contains(expectedEntities)
			);
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
		                queryProduct(
		                    filterBy: {
		                        entityLocaleEquals: cs_CZ
		                    },
		                    orderBy: {
		                        attributeCodeNameNatural: DESC
		                    }
		                ) {
		                    recordStrip(limit: 30) {
		                        data {
		                            primaryKey
		                        }
		                    }
		                }
		            }
					"""
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				PRODUCT_QUERY_PATH + "." + ResponseDescriptor.RECORD_STRIP.name() + "." + DataChunkDescriptor.DATA.name() + "." + EntityDescriptor.PRIMARY_KEY.name(),
				contains(expectedEntities)
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return page of entities")
	void shouldReturnPageOfEntities(Evita evita, GraphQLTester tester) {
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
			.document(
				"""
		            query {
		                queryProduct(
		                    filterBy: {
		                        attributePriorityLessThan: "35000"
		                    }
		                ) {
		                    recordPage(number: 2, size: 3) {
		                        data {
		                            primaryKey
		                        }
		                    }
		                }
		            }
					"""
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				PRODUCT_QUERY_PATH + "." + ResponseDescriptor.RECORD_PAGE.name() + "." + DataChunkDescriptor.DATA.name() + "." + EntityDescriptor.PRIMARY_KEY.name(),
				contains(
					expectedEntities.stream()
						.skip(3)
						.limit(3)
						.toArray(Integer[]::new)
				)
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return strip of entities")
	void shouldReturnStripOfEntities(Evita evita, GraphQLTester tester) {
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
			.document(
				"""
		            query {
		                queryProduct(
		                    filterBy: {
		                        attributePriorityLessThan: "35000"
		                    }
		                ) {
		                    recordStrip(offset: 2, limit: 3) {
		                        data {
		                            primaryKey
		                        }
		                    }
		                }
		            }
					"""
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				PRODUCT_QUERY_PATH + "." + ResponseDescriptor.RECORD_STRIP.name() + "." + DataChunkDescriptor.DATA.name() + "." + EntityDescriptor.PRIMARY_KEY.name(),
				contains(
					expectedEntities.stream()
						.skip(2)
						.limit(3)
						.toArray(Integer[]::new)
				)
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return attribute histogram")
	void shouldReturnAttributeHistogram(Evita evita, GraphQLTester tester) {
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
			.document(
				"""
		            query {
		                queryProduct {
		                    recordPage(size: %d) {
		                        data {
		                            primaryKey
		                        }
		                    }
		                    extraResults {
		                        __typename
		                        attributeHistogram {
		                            __typename
		                            quantity {
		                                __typename
		                                min
		                                max
		                                overallCount
		                                buckets(requestedCount: 20) {
		                                    __typename
		                                    threshold
		                                    occurrences
		                                    requested
		                                }
		                            }
		                        }
		                    }
		                }
		            }
					""",
				Integer.MAX_VALUE
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				resultPath(PRODUCT_QUERY_PATH, ResponseDescriptor.EXTRA_RESULTS, TYPENAME_FIELD),
				equalTo(ExtraResultsDescriptor.THIS.name(createEmptyEntitySchema("Product")))
			)
			.body(
				resultPath(PRODUCT_QUERY_PATH, ResponseDescriptor.EXTRA_RESULTS, ExtraResultsDescriptor.ATTRIBUTE_HISTOGRAM, TYPENAME_FIELD),
				equalTo(AttributeHistogramDescriptor.THIS.name(createEmptyEntitySchema("Product")))
			)
			.body(
				resultPath(PRODUCT_QUERY_PATH, ResponseDescriptor.EXTRA_RESULTS, ExtraResultsDescriptor.ATTRIBUTE_HISTOGRAM, ATTRIBUTE_QUANTITY),
				equalTo(expectedHistogram)
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return optimized attribute histogram")
	void shouldReturnOptimizedAttributeHistogram(Evita evita, GraphQLTester tester) {
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
							attributeHistogram(20, HistogramBehavior.OPTIMIZED, ATTRIBUTE_QUANTITY)
						)
					),
					EntityReference.class
				);
			}
		);

		final var expectedHistogram = createAttributeHistogramDto(response, ATTRIBUTE_QUANTITY);

		tester.test(TEST_CATALOG)
			.document(
				"""
		            query {
		                queryProduct {
		                    recordPage(size: %d) {
		                        data {
		                            primaryKey
		                        }
		                    }
		                    extraResults {
		                        __typename
		                        attributeHistogram {
		                            __typename
		                            quantity {
		                                __typename
		                                min
		                                max
		                                overallCount
		                                buckets(requestedCount: 20, behavior: OPTIMIZED) {
		                                    __typename
		                                    threshold
		                                    occurrences
		                                    requested
		                                }
		                            }
		                        }
		                    }
		                }
		            }
					""",
				Integer.MAX_VALUE
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				resultPath(PRODUCT_QUERY_PATH, ResponseDescriptor.EXTRA_RESULTS, TYPENAME_FIELD),
				equalTo(ExtraResultsDescriptor.THIS.name(createEmptyEntitySchema("Product")))
			)
			.body(
				resultPath(PRODUCT_QUERY_PATH, ResponseDescriptor.EXTRA_RESULTS, ExtraResultsDescriptor.ATTRIBUTE_HISTOGRAM, TYPENAME_FIELD),
				equalTo(AttributeHistogramDescriptor.THIS.name(createEmptyEntitySchema("Product")))
			)
			.body(
				resultPath(PRODUCT_QUERY_PATH, ResponseDescriptor.EXTRA_RESULTS, ExtraResultsDescriptor.ATTRIBUTE_HISTOGRAM, ATTRIBUTE_QUANTITY),
				equalTo(expectedHistogram)
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return attribute histogram without being affected by attribute filter")
	void shouldReturnAttributeHistogramWithoutBeingAffectedByAttributeFilter(Evita evita, GraphQLTester tester) {
		final EvitaResponse<EntityReference> response = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							attributeIsNotNull(ATTRIBUTE_ALIAS),
							userFilter(
								attributeBetween(ATTRIBUTE_QUANTITY, 100, 900)
							)
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
			.document(
				"""
		            query {
		                queryProduct(
		                    filterBy: {
		                        userFilter: {
		                            attributeQuantityBetween: ["100", "900"]
	                            }
		                    }
		                ) {
		                    recordPage(size: %d) {
		                        data {
		                            primaryKey
		                        }
		                    }
		                    extraResults {
		                        __typename
		                        attributeHistogram {
		                            __typename
		                            quantity {
		                                __typename
		                                min
		                                max
		                                overallCount
		                                buckets(requestedCount: 20) {
		                                    __typename
		                                    threshold
		                                    occurrences
		                                    requested
		                                }
		                            }
		                        }
		                    }
		                }
		            }
					""",
				Integer.MAX_VALUE
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				resultPath(PRODUCT_QUERY_PATH, ResponseDescriptor.EXTRA_RESULTS, TYPENAME_FIELD),
				equalTo(ExtraResultsDescriptor.THIS.name(createEmptyEntitySchema("Product")))
			)
			.body(
				resultPath(PRODUCT_QUERY_PATH, ResponseDescriptor.EXTRA_RESULTS, ExtraResultsDescriptor.ATTRIBUTE_HISTOGRAM, TYPENAME_FIELD),
				equalTo(AttributeHistogramDescriptor.THIS.name(createEmptyEntitySchema("Product")))
			)
			.body(
				resultPath(PRODUCT_QUERY_PATH, ResponseDescriptor.EXTRA_RESULTS, ExtraResultsDescriptor.ATTRIBUTE_HISTOGRAM, ATTRIBUTE_QUANTITY),
				equalTo(expectedHistogram)
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return multiple attribute histograms")
	void shouldReturnMultipleAttributeHistograms(Evita evita, GraphQLTester tester) {
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
							attributeHistogram(20, ATTRIBUTE_QUANTITY, ATTRIBUTE_PRIORITY)
						)
					),
					EntityReference.class
				);
			}
		);

		final var expectedQuantityHistogram = createAttributeHistogramDto(response, ATTRIBUTE_QUANTITY);
		final var expectedPriorityHistogram = createAttributeHistogramDto(response, ATTRIBUTE_PRIORITY);

		tester.test(TEST_CATALOG)
			.document(
				"""
		            query {
		                queryProduct {
		                    recordPage(size: %d) {
		                        data {
		                            primaryKey
		                        }
		                    }
		                    extraResults {
		                        attributeHistogram {
		                            quantity {
		                                __typename
		                                min
		                                max
		                                overallCount
		                                buckets(requestedCount: 20) {
		                                    __typename
		                                    threshold
		                                    occurrences
		                                    requested
		                                }
		                            }
		                        }
		                    }
		                    otherExtraResults: extraResults {
		                        attributeHistogram {
		                            priority {
		                                __typename
		                                min
		                                max
		                                overallCount
		                                buckets(requestedCount: 20) {
		                                    __typename
		                                    threshold
		                                    occurrences
		                                    requested
		                                }
		                            }
		                        }
		                    }
		                }
		            }
					""",
				Integer.MAX_VALUE
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				resultPath(PRODUCT_QUERY_PATH, "extraResults", ExtraResultsDescriptor.ATTRIBUTE_HISTOGRAM, ATTRIBUTE_QUANTITY),
				equalTo(expectedQuantityHistogram)
			)
			.body(
				resultPath(PRODUCT_QUERY_PATH, "otherExtraResults", ExtraResultsDescriptor.ATTRIBUTE_HISTOGRAM, ATTRIBUTE_PRIORITY),
				equalTo(expectedPriorityHistogram)
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return multiple same attribute histogram buckets")
	void shouldReturnMultipleSameAttributeHistogramBuckets(Evita evita, GraphQLTester tester) {
		final EvitaResponse<EntityReference> response = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.query(
					query(
						collection(Entities.PRODUCT),
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
			.document(
				"""
		            query {
		                queryProduct {
		                    recordPage(size: %d) {
		                        data {
		                            primaryKey
		                        }
		                    }
		                    extraResults {
		                        attributeHistogram {
		                            quantity {
		                                __typename
		                                min
		                                max
		                                overallCount
		                                buckets(requestedCount: 20) {
		                                    __typename
		                                    threshold
		                                    occurrences
		                                    requested
		                                }
		                            }
		                            otherQuantity: quantity {
		                                __typename
		                                min
		                                max
		                                overallCount
		                                buckets(requestedCount: 20, behavior: STANDARD) {
		                                    __typename
		                                    threshold
		                                    occurrences
		                                    requested
		                                }
		                            }
		                        }
		                    }
		                }
		            }
					""",
				Integer.MAX_VALUE
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				PRODUCT_QUERY_PATH + "." + ResponseDescriptor.EXTRA_RESULTS.name() + "." + ExtraResultsDescriptor.ATTRIBUTE_HISTOGRAM.name() + "." + ATTRIBUTE_QUANTITY,
				equalTo(expectedHistogram))
			.body(
				PRODUCT_QUERY_PATH + "." + ResponseDescriptor.EXTRA_RESULTS.name() + "." + ExtraResultsDescriptor.ATTRIBUTE_HISTOGRAM.name() + ".otherQuantity",
				equalTo(expectedHistogram)
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return error for missing attribute histogram buckets count")
	void shouldReturnErrorForMissingAttributeHistogramBucketsCount(GraphQLTester tester) {
		tester.test(TEST_CATALOG)
			.document(
				"""
		            query {
		                queryProduct(
		                    filterBy: {
		                        attributeAliasIs: NOT_NULL
		                    }
		                ) {
		                    recordPage(size: %d) {
		                        data {
		                            primaryKey
		                        }
		                    }
		                    extraResults {
		                        attributeHistogram {
		                            quantity {
		                                min
		                                max
		                                overallCount
		                                buckets {
		                                    threshold
		                                    occurrences
		                                }
		                            }
		                        }
		                    }
		                }
		            }
					""",
				Integer.MAX_VALUE
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, hasSize(greaterThan(0)));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return error for missing attribute histogram buckets count")
	void shouldReturnErrorForMissingAttributeHistogramBuckets(GraphQLTester tester) {
		tester.test(TEST_CATALOG)
			.document(
				"""
		            query {
		                queryProduct(
		                    filterBy: {
		                        attributeAliasIs: NOT_NULL
		                    }
		                ) {
		                    recordPage(size: %d) {
		                        data {
		                            primaryKey
		                        }
		                    }
		                    extraResults {
		                        attributeHistogram {
		                            quantity {
		                                min
		                                max
		                                overallCount
		                            }
		                        }
		                    }
		                }
		            }
					""",
				Integer.MAX_VALUE
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, hasSize(greaterThan(0)));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return error for multiple attribute histogram buckets behavior")
	void shouldReturnErrorForMultipleAttributeHistogramBucketsBehavior(GraphQLTester tester) {
		tester.test(TEST_CATALOG)
			.document(
				"""
		            query {
		                queryProduct(
		                    filterBy: {
		                        attributeAliasIs: NOT_NULL
		                    }
		                ) {
		                    recordPage(size: %d) {
		                        data {
		                            primaryKey
		                        }
		                    }
		                    extraResults {
		                        attributeHistogram {
		                            quantity {
		                                min
		                                max
		                                overallCount
		                                buckets(requestedCount: 10, behavior: OPTIMIZED) {
		                                    threshold
		                                }
		                                otherBuckets: buckets(requestedCount: 10) {
		                                    threshold
		                                }
		                            }
		                        }
		                    }
		                }
		            }
					""",
				Integer.MAX_VALUE
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, hasSize(greaterThan(0)));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return error for multiple attribute histogram buckets count")
	void shouldReturnErrorForMultipleAttributeHistogramBucketsCount(GraphQLTester tester) {
		tester.test(TEST_CATALOG)
			.document(
				"""
		            query {
		                queryProduct(
		                    filterBy: {
		                        attributeAliasIs: NOT_NULL
		                    }
		                ) {
		                    recordPage(size: %d) {
		                        data {
		                            primaryKey
		                        }
		                    }
		                    extraResults {
		                        attributeHistogram {
		                            quantity {
		                                min
		                                max
		                                overallCount
		                                buckets(requestedCount: 10) {
		                                    threshold
		                                }
		                                otherBuckets: buckets(requestedCount: 20) {
		                                    threshold
		                                }
		                            }
		                        }
		                    }
		                }
		            }
					""",
				Integer.MAX_VALUE
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, hasSize(greaterThan(0)));
	}


	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return price histogram")
	void shouldReturnPriceHistogram(Evita evita, GraphQLTester tester) {
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
			.document(
				"""
		            query {
		                queryProduct(
		                    filterBy: {
		                        priceInCurrency: EUR
		                        priceInPriceLists: ["vip", "basic"]
		                    }
		                ) {
		                    recordPage(size: %d) {
		                        data {
		                            primaryKey
		                        }
		                    }
		                    extraResults {
		                        __typename
		                        priceHistogram {
		                            __typename
	                                min
	                                max
	                                overallCount
	                                buckets(requestedCount: 20) {
	                                    __typename
	                                    threshold
	                                    occurrences
	                                    requested
	                                }
		                        }
		                    }
		                }
		            }
					""",
				Integer.MAX_VALUE
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				PRODUCT_QUERY_PATH + "." + ResponseDescriptor.EXTRA_RESULTS.name() + "." + TYPENAME_FIELD,
				equalTo(ExtraResultsDescriptor.THIS.name(createEmptyEntitySchema("Product")))
			)
			.body(
				PRODUCT_QUERY_PATH + "." + ResponseDescriptor.EXTRA_RESULTS.name() + "." + ExtraResultsDescriptor.PRICE_HISTOGRAM.name(),
				equalTo(expectedBody)
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return optimized price histogram")
	void shouldReturnOptimizedPriceHistogram(Evita evita, GraphQLTester tester) {
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
							priceHistogram(20, HistogramBehavior.OPTIMIZED)
						)
					),
					EntityReference.class
				);
			}
		);

		final var expectedBody = createPriceHistogramDto(response);

		tester.test(TEST_CATALOG)
			.document(
				"""
		            query {
		                queryProduct(
		                    filterBy: {
		                        priceInCurrency: EUR
		                        priceInPriceLists: ["vip", "basic"]
		                    }
		                ) {
		                    recordPage(size: %d) {
		                        data {
		                            primaryKey
		                        }
		                    }
		                    extraResults {
		                        __typename
		                        priceHistogram {
		                            __typename
	                                min
	                                max
	                                overallCount
	                                buckets(requestedCount: 20, behavior: OPTIMIZED) {
	                                    __typename
	                                    threshold
	                                    occurrences
	                                    requested
	                                }
		                        }
		                    }
		                }
		            }
					""",
				Integer.MAX_VALUE
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				PRODUCT_QUERY_PATH + "." + ResponseDescriptor.EXTRA_RESULTS.name() + "." + TYPENAME_FIELD,
				equalTo(ExtraResultsDescriptor.THIS.name(createEmptyEntitySchema("Product")))
			)
			.body(
				PRODUCT_QUERY_PATH + "." + ResponseDescriptor.EXTRA_RESULTS.name() + "." + ExtraResultsDescriptor.PRICE_HISTOGRAM.name(),
				equalTo(expectedBody)
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return price histogram without being affected by price filter")
	void shouldReturnPriceHistogramWithoutBeingAffectedByPriceFilter(Evita evita, GraphQLTester tester) {
		final BigDecimal from = new BigDecimal("80");
		final BigDecimal to = new BigDecimal("150");
		final EvitaResponse<EntityReference> response = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								priceInCurrency(CURRENCY_EUR),
								priceInPriceLists(PRICE_LIST_VIP, PRICE_LIST_BASIC),
								userFilter(
									priceBetween(from, to)
								)
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
			.document(
				"""
		            query {
		                queryProduct(
		                    filterBy: {
		                        priceInCurrency: EUR
		                        priceInPriceLists: ["vip", "basic"]
		                        userFilter: {
		                            priceBetween: ["80", "150"]
		                        }
		                    }
		                ) {
		                    recordPage(size: %d) {
		                        data {
		                            primaryKey
		                        }
		                    }
		                    extraResults {
		                        __typename
		                        priceHistogram {
		                            __typename
	                                min
	                                max
	                                overallCount
	                                buckets(requestedCount: 20) {
	                                    __typename
	                                    threshold
	                                    occurrences
	                                    requested
	                                }
		                        }
		                    }
		                }
		            }
					""",
				Integer.MAX_VALUE
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				PRODUCT_QUERY_PATH + "." + ResponseDescriptor.EXTRA_RESULTS.name() + "." + TYPENAME_FIELD,
				equalTo(ExtraResultsDescriptor.THIS.name(createEmptyEntitySchema("Product")))
			)
			.body(
				PRODUCT_QUERY_PATH + "." + ResponseDescriptor.EXTRA_RESULTS.name() + "." + ExtraResultsDescriptor.PRICE_HISTOGRAM.name(),
				equalTo(expectedBody)
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return multiple same price histograms")
	void shouldReturnMultipleSamePriceHistograms(Evita evita, GraphQLTester tester) {
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
			.document(
				"""
		            query {
		                queryProduct(
		                    filterBy: {
		                        priceInCurrency: EUR
		                        priceInPriceLists: ["vip", "basic"]
		                    }
		                ) {
		                    recordPage(size: %d) {
		                        data {
		                            primaryKey
		                        }
		                    }
		                    extraResults {
		                        priceHistogram {
		                            __typename
	                                min
	                                max
	                                overallCount
	                                buckets(requestedCount: 20) {
	                                    __typename
	                                    threshold
	                                    occurrences
	                                    requested
	                                }
		                        }
		                        otherPriceHistogram: priceHistogram {
		                            __typename
	                                min
	                                max
	                                overallCount
	                                buckets(requestedCount: 20, behavior: STANDARD) {
	                                    __typename
	                                    threshold
	                                    occurrences
	                                    requested
	                                }
		                        }
		                    }
		                }
		            }
					""",
				Integer.MAX_VALUE
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				PRODUCT_QUERY_PATH + "." + ResponseDescriptor.EXTRA_RESULTS.name() + "." + ExtraResultsDescriptor.PRICE_HISTOGRAM.name(),
				equalTo(expectedBody)
			)
			.body(
				PRODUCT_QUERY_PATH + "." + ResponseDescriptor.EXTRA_RESULTS.name() + ".otherPriceHistogram",
				equalTo(expectedBody)
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return error for missing price histogram buckets count")
	void shouldReturnErrorForMissingPriceHistogramBucketsCount(GraphQLTester tester) {
		tester.test(TEST_CATALOG)
			.document(
				"""
		            query {
		                queryProduct(
		                    filterBy: {
		                        priceInCurrency: EUR
		                        priceInPriceLists: ["vip", "basic"]
		                    }
		                ) {
		                    recordPage(size: %d) {
		                        data {
		                            primaryKey
		                        }
		                    }
		                    extraResults {
		                        priceHistogram {
	                                min
	                                max
	                                overallCount
	                                buckets {
	                                    threshold
	                                    occurrences
	                                    requested
	                                }
		                        }
		                    }
		                }
		            }
					""",
				Integer.MAX_VALUE
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, hasSize(greaterThan(0)));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return error for missing price histogram buckets")
	void shouldReturnErrorForMissingPriceHistogramBuckets(GraphQLTester tester) {
		tester.test(TEST_CATALOG)
			.document(
				"""
		            query {
		                queryProduct(
		                    filterBy: {
		                        priceInCurrency: EUR
		                        priceInPriceLists: ["vip", "basic"]
		                    }
		                ) {
		                    recordPage(size: %d) {
		                        data {
		                            primaryKey
		                        }
		                    }
		                    extraResults {
		                        priceHistogram {
	                                min
	                                max
	                                overallCount
		                        }
		                    }
		                }
		            }
					""",
				Integer.MAX_VALUE
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, hasSize(greaterThan(0)));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return error for multiple price histogram buckets counts")
	void shouldReturnErrorForMultiplePriceHistogramBucketsCounts(GraphQLTester tester) {
		tester.test(TEST_CATALOG)
			.document(
				"""
		            query {
		                queryProduct(
		                    filterBy: {
		                        priceInCurrency: EUR
		                        priceInPriceLists: ["vip", "basic"]
		                    }
		                ) {
		                    recordPage(size: %d) {
		                        data {
		                            primaryKey
		                        }
		                    }
		                    extraResults {
		                        priceHistogram {
	                                min
	                                max
	                                overallCount
	                                buckets(requestedCount: 10) {
	                                    threshold
	                                    occurrences
	                                }
	                                otherBuckets: buckets(requestedCount: 20) {
	                                    threshold
	                                    occurrences
	                                }
		                        }
		                    }
		                }
		            }
					""",
				Integer.MAX_VALUE
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, hasSize(greaterThan(0)));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return error for multiple price histogram buckets behaviors")
	void shouldReturnErrorForMultiplePriceHistogramBucketsBehaviors(GraphQLTester tester) {
		tester.test(TEST_CATALOG)
			.document(
				"""
		            query {
		                queryProduct(
		                    filterBy: {
		                        priceInCurrency: EUR
		                        priceInPriceLists: ["vip", "basic"]
		                    }
		                ) {
		                    recordPage(size: %d) {
		                        data {
		                            primaryKey
		                        }
		                    }
		                    extraResults {
		                        priceHistogram {
	                                min
	                                max
	                                overallCount
	                                buckets(requestedCount: 10, behavior: OPTIMIZED) {
	                                    threshold
	                                    occurrences
	                                }
	                                otherBuckets: buckets(requestedCount: 10) {
	                                    threshold
	                                    occurrences
	                                }
		                        }
		                    }
		                }
		            }
					""",
				Integer.MAX_VALUE
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, hasSize(greaterThan(0)));
	}

	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return self hierarchy from root")
	@ParameterizedTest
	@MethodSource("statisticTypeAndBaseVariants")
	void shouldReturnSelfHierarchyFromRoot(EnumSet<StatisticsType> statisticsType, StatisticsBase base, Evita evita, GraphQLTester tester) {
		var expectedHierarchy = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> response = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							entityLocaleEquals(CZECH_LOCALE)
						),
						require(
							// we don't need the results whatsoever
							page(1, 0),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							// we need only data about cardinalities
							hierarchyOfSelf(
								orderBy(attributeNatural(ATTRIBUTE_CODE, DESC)),
								fromRoot(
									"megaMenu",
									entityFetch(hierarchyContent(), attributeContent()),
									stopAt(distance(2)),
									statisticsType.isEmpty() ? new io.evitadb.api.query.require.HierarchyStatistics(base) :
										new io.evitadb.api.query.require.HierarchyStatistics(base, statisticsType.toArray(StatisticsType[]::new))
								)
							)
						)
					),
					EntityReference.class
				);

				final Hierarchy hierarchy = response.getExtraResult(Hierarchy.class);
				final List<LevelInfo> megaMenu = hierarchy.getSelfHierarchy("megaMenu");
				final List<Map<String, Object>> flattenedMegaMenu = createFlattenedHierarchy(megaMenu);
				assertFalse(flattenedMegaMenu.isEmpty());
				return flattenedMegaMenu;
			});

		tester.test(TEST_CATALOG)
			.document("""
				{
					queryCategory(
						filterBy: {
							entityLocaleEquals: cs_CZ
						}
					) {
						recordPage(size: 0) {data {primaryKey}}
						extraResults {
							hierarchy {
								self(orderBy: {attributeCodeNatural: DESC}) {
									megaMenu: fromRoot(
										stopAt: { distance: 2 }
										%s
									) { %s }
								}
							}
						}
					}
				}
				""",
				getHierarchyStatisticsBaseArgument(base),
				getLevelInfoFragment(statisticsType))
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(SELF_MEGA_MENU_PATH, equalTo(expectedHierarchy));
	}

	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return self hierarchy from node")
	@ParameterizedTest
	@MethodSource("statisticTypeAndBaseVariants")
	void shouldReturnSelfHierarchyFromNode(EnumSet<StatisticsType> statisticsType, StatisticsBase base, Evita evita, GraphQLTester tester) {
		var expectedHierarchy = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> response = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							and(
								entityLocaleEquals(CZECH_LOCALE),
								hierarchyWithinSelf(entityPrimaryKeyInSet(6))
							)
						),
						require(
							// we don't need the results whatsoever
							page(1, 0),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							// we need only data about cardinalities
							hierarchyOfSelf(
								orderBy(attributeNatural(ATTRIBUTE_CODE, DESC)),
								fromNode(
									"megaMenu",
									node(filterBy(entityPrimaryKeyInSet(2))),
									entityFetch(hierarchyContent(), attributeContent()),
									stopAt(distance(2)),
									statisticsType.isEmpty() ? new io.evitadb.api.query.require.HierarchyStatistics(base) :
										new io.evitadb.api.query.require.HierarchyStatistics(base, statisticsType.toArray(StatisticsType[]::new))
								)
							)
						)
					),
					EntityReference.class
				);

				final Hierarchy hierarchy = response.getExtraResult(Hierarchy.class);
				final List<LevelInfo> megaMenu = hierarchy.getSelfHierarchy("megaMenu");
				final List<Map<String, Object>> flattenedMegaMenu = createFlattenedHierarchy(megaMenu);
				assertFalse(flattenedMegaMenu.isEmpty());
				return flattenedMegaMenu;
			});

		tester.test(TEST_CATALOG)
			.document("""
				{
					queryCategory(
						filterBy: {
							entityLocaleEquals: cs_CZ,
							hierarchyWithinSelf: { ofParent: { entityPrimaryKeyInSet: 6 } }
						}
					) {
						recordPage(size: 0) {data {primaryKey}}
						extraResults {
							hierarchy {
								self(orderBy: {attributeCodeNatural: DESC}) {
									megaMenu: fromNode(
										node: { filterBy: { entityPrimaryKeyInSet: 2 }}
										stopAt: { distance: 2 }
										%s
									) { %s }
								}
							}
						}
					}
				}
				""",
				getHierarchyStatisticsBaseArgument(base),
				getLevelInfoFragment(statisticsType))
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(SELF_MEGA_MENU_PATH, equalTo(expectedHierarchy));
	}

	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return self hierarchy children")
	@ParameterizedTest
	@MethodSource("statisticTypeAndBaseVariants")
	void shouldReturnSelfHierarchyChildren(EnumSet<StatisticsType> statisticsType, StatisticsBase base, Evita evita, GraphQLTester tester) {
		var expectedHierarchy = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> response = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							and(
								entityLocaleEquals(CZECH_LOCALE),
								hierarchyWithinSelf(entityPrimaryKeyInSet(1))
							)
						),
						require(
							// we don't need the results whatsoever
							page(1, 0),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							// we need only data about cardinalities
							hierarchyOfSelf(
								orderBy(attributeNatural(ATTRIBUTE_CODE, DESC)),
								children(
									"megaMenu",
									entityFetch(hierarchyContent(), attributeContent()),
									stopAt(distance(1)),
									statisticsType.isEmpty() ? new io.evitadb.api.query.require.HierarchyStatistics(base) :
										new io.evitadb.api.query.require.HierarchyStatistics(base, statisticsType.toArray(StatisticsType[]::new))
								)
							)
						)
					),
					EntityReference.class
				);

				final Hierarchy hierarchy = response.getExtraResult(Hierarchy.class);
				final List<LevelInfo> megaMenu = hierarchy.getSelfHierarchy("megaMenu");
				final List<Map<String, Object>> flattenedMegaMenu = createFlattenedHierarchy(megaMenu);
				assertFalse(flattenedMegaMenu.isEmpty());
				return flattenedMegaMenu;
			});

		tester.test(TEST_CATALOG)
			.document("""
				{
					queryCategory(
						filterBy: {
							entityLocaleEquals: cs_CZ,
							hierarchyWithinSelf: { ofParent: { entityPrimaryKeyInSet: 1 } }
						}
					) {
						recordPage(size: 0) {data {primaryKey}}
						extraResults {
							hierarchy {
								self(orderBy: {attributeCodeNatural: DESC}) {
									megaMenu: children(
										stopAt: { distance: 1 }
										%s
									) { %s }
								}
							}
						}
					}
				}
				""",
				getHierarchyStatisticsBaseArgument(base),
				getLevelInfoFragment(statisticsType))
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(SELF_MEGA_MENU_PATH, equalTo(expectedHierarchy));
	}

	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return self hierarchy parents without siblings")
	@ParameterizedTest
	@MethodSource("statisticTypeAndBaseVariants")
	void shouldReturnSelfHierarchyParentsWithoutSiblings(EnumSet<StatisticsType> statisticsType, StatisticsBase base, Evita evita, GraphQLTester tester) {
		var expectedHierarchy = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> response = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							and(
								entityLocaleEquals(CZECH_LOCALE),
								hierarchyWithinSelf(entityPrimaryKeyInSet(30))
							)
						),
						require(
							// we don't need the results whatsoever
							page(1, 0),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							// we need only data about cardinalities
							hierarchyOfSelf(
								orderBy(attributeNatural(ATTRIBUTE_CODE, DESC)),
								parents(
									"megaMenu",
									entityFetch(hierarchyContent(), attributeContent()),
									statisticsType.isEmpty() ? new io.evitadb.api.query.require.HierarchyStatistics(base) :
										new io.evitadb.api.query.require.HierarchyStatistics(base, statisticsType.toArray(StatisticsType[]::new))
								)
							)
						)
					),
					EntityReference.class
				);

				final Hierarchy hierarchy = response.getExtraResult(Hierarchy.class);
				final List<LevelInfo> megaMenu = hierarchy.getSelfHierarchy("megaMenu");
				final List<Map<String, Object>> flattenedMegaMenu = createFlattenedHierarchy(megaMenu);
				assertFalse(flattenedMegaMenu.isEmpty());
				return flattenedMegaMenu;
			});

		tester.test(TEST_CATALOG)
			.document("""
				{
					queryCategory(
						filterBy: {
							entityLocaleEquals: cs_CZ,
							hierarchyWithinSelf: { ofParent: { entityPrimaryKeyInSet: 30 } }
						}
					) {
						recordPage(size: 0) {data {primaryKey}}
						extraResults {
							hierarchy {
								self(orderBy: {attributeCodeNatural: DESC}) {
									megaMenu: parents(
										stopAt: { distance: 100 }
										%s
									) { %s }
								}
							}
						}
					}
				}
				""",
				getHierarchyStatisticsBaseArgument(base),
				getLevelInfoFragment(statisticsType))
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(SELF_MEGA_MENU_PATH, equalTo(expectedHierarchy));
	}

	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return self hierarchy parents with siblings")
	@ParameterizedTest
	@MethodSource("statisticTypeAndBaseVariants")
	void shouldReturnSelfHierarchyParentsWithSiblings(EnumSet<StatisticsType> statisticsType, StatisticsBase base, Evita evita, GraphQLTester tester) {
		var expectedHierarchy = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> response = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							and(
								entityLocaleEquals(CZECH_LOCALE),
								hierarchyWithinSelf(entityPrimaryKeyInSet(30))
							)
						),
						require(
							// we don't need the results whatsoever
							page(1, 0),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							// we need only data about cardinalities
							hierarchyOfSelf(
								orderBy(attributeNatural(ATTRIBUTE_CODE, DESC)),
								parents(
									"megaMenu",
									entityFetch(hierarchyContent(), attributeContent()),
									siblings(
										entityFetch(hierarchyContent(), attributeContent()),
										stopAt(distance(2)),
										statisticsType.isEmpty() ? new io.evitadb.api.query.require.HierarchyStatistics(base) :
											new io.evitadb.api.query.require.HierarchyStatistics(base, statisticsType.toArray(StatisticsType[]::new))
									),
									statisticsType.isEmpty() ? new io.evitadb.api.query.require.HierarchyStatistics(base) :
										new io.evitadb.api.query.require.HierarchyStatistics(base, statisticsType.toArray(StatisticsType[]::new))
								)
							)
						)
					),
					EntityReference.class
				);

				final Hierarchy hierarchy = response.getExtraResult(Hierarchy.class);
				final List<LevelInfo> megaMenu = hierarchy.getSelfHierarchy("megaMenu");
				final List<Map<String, Object>> flattenedMegaMenu = createFlattenedHierarchy(megaMenu);
				assertFalse(flattenedMegaMenu.isEmpty());
				return flattenedMegaMenu;
			});

		tester.test(TEST_CATALOG)
			.document("""
				{
					queryCategory(
						filterBy: {
							entityLocaleEquals: cs_CZ,
							hierarchyWithinSelf: { ofParent: { entityPrimaryKeyInSet: 30 } }
						}
					) {
						recordPage(size: 0) {data {primaryKey}}
						extraResults {
							hierarchy {
								self(orderBy: {attributeCodeNatural: DESC}) {
									megaMenu: parents(
										stopAt: { distance: 100 }
										siblings: {
											stopAt: {
												distance: 2
											}
										}
										%s
									) { %s }
								}
							}
						}
					}
				}
				""",
				getHierarchyStatisticsBaseArgument(base),
				getLevelInfoFragment(statisticsType))
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(SELF_MEGA_MENU_PATH, equalTo(expectedHierarchy));
	}

	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return self hierarchy root siblings")
	@ParameterizedTest
	@MethodSource("statisticTypeAndBaseVariants")
	void shouldReturnSelfHierarchyRootSiblings(EnumSet<StatisticsType> statisticsType, StatisticsBase base, Evita evita, GraphQLTester tester) {
		var expectedHierarchy = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> response = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							and(
								entityLocaleEquals(CZECH_LOCALE)
							)
						),
						require(
							// we don't need the results whatsoever
							page(1, 0),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							// we need only data about cardinalities
							hierarchyOfSelf(
								orderBy(attributeNatural(ATTRIBUTE_CODE, DESC)),
								siblings(
									"rootSiblings",
									entityFetch(hierarchyContent(), attributeContent()),
									stopAt(distance(1)),
									statisticsType.isEmpty() ? new io.evitadb.api.query.require.HierarchyStatistics(base) :
										new io.evitadb.api.query.require.HierarchyStatistics(base, statisticsType.toArray(StatisticsType[]::new))
								)
							)
						)
					),
					EntityReference.class
				);

				final Hierarchy hierarchy = response.getExtraResult(Hierarchy.class);
				final List<LevelInfo> rootSiblings = hierarchy.getSelfHierarchy("rootSiblings");
				final List<Map<String, Object>> flattenedRootSiblings = createFlattenedHierarchy(rootSiblings);
				assertFalse(flattenedRootSiblings.isEmpty());
				return flattenedRootSiblings;
			});

		tester.test(TEST_CATALOG)
			.document("""
				{
					queryCategory(
						filterBy: {
							entityLocaleEquals: cs_CZ
						}
					) {
						recordPage(size: 0) {data {primaryKey}}
						extraResults {
							hierarchy {
								self(orderBy: {attributeCodeNatural: DESC}) {
									rootSiblings: siblings(
										stopAt: { distance: 1 }
										%s
									) { %s }
								}
							}
						}
					}
				}
				""",
				getHierarchyStatisticsBaseArgument(base),
				getLevelInfoFragment(statisticsType))
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(SELF_ROOT_SIBLINGS_PATH, equalTo(expectedHierarchy));
	}

	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return multiple different self hierarchies")
	@ParameterizedTest
	@MethodSource("statisticTypeAndBaseVariants")
	void shouldReturnMultipleDifferentSelfHierarchies(EnumSet<StatisticsType> statisticsType, StatisticsBase base, Evita evita, GraphQLTester tester) {
		var hierarchy = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> response = session.query(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							and(
								entityLocaleEquals(CZECH_LOCALE)
							)
						),
						require(
							// we don't need the results whatsoever
							page(1, 0),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							// we need only data about cardinalities
							hierarchyOfSelf(
								orderBy(attributeNatural(ATTRIBUTE_CODE, DESC)),
								fromRoot(
									"megaMenu",
									entityFetch(hierarchyContent(), attributeContent()),
									stopAt(distance(2)),
									statisticsType.isEmpty() ? new io.evitadb.api.query.require.HierarchyStatistics(base) :
										new io.evitadb.api.query.require.HierarchyStatistics(base, statisticsType.toArray(StatisticsType[]::new))
								),
								siblings(
									"rootSiblings",
									entityFetch(hierarchyContent(), attributeContent()),
									stopAt(distance(1)),
									statisticsType.isEmpty() ? new io.evitadb.api.query.require.HierarchyStatistics(base) :
										new io.evitadb.api.query.require.HierarchyStatistics(base, statisticsType.toArray(StatisticsType[]::new))
								)
							)
						)
					),
					EntityReference.class
				);

				return response.getExtraResult(Hierarchy.class);
			});

		final List<LevelInfo> megaMenu = hierarchy.getSelfHierarchy("megaMenu");
		final List<Map<String, Object>> flattenedMegaMenu = createFlattenedHierarchy(megaMenu);
		assertFalse(flattenedMegaMenu.isEmpty());

		final List<LevelInfo> rootSiblings = hierarchy.getSelfHierarchy("rootSiblings");
		final List<Map<String, Object>> flattenedRootSiblings = createFlattenedHierarchy(rootSiblings);
		assertFalse(flattenedRootSiblings.isEmpty());

		tester.test(TEST_CATALOG)
			.document("""
				{
					queryCategory(
						filterBy: {
							entityLocaleEquals: cs_CZ
						}
					) {
						recordPage(size: 0) {data {primaryKey}}
						extraResults {
							hierarchy {
								self(orderBy: {attributeCodeNatural: DESC}) {
									megaMenu: fromRoot(
										stopAt: { distance: 2 }
										%s
									) { %s }
									rootSiblings: siblings(
										stopAt: { distance: 1 }
										%s
									) { %s }
								}
							}
						}
					}
				}
				""",
				getHierarchyStatisticsBaseArgument(base),
				getLevelInfoFragment(statisticsType),
				getHierarchyStatisticsBaseArgument(base),
				getLevelInfoFragment(statisticsType))
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(SELF_MEGA_MENU_PATH, equalTo(flattenedMegaMenu))
			.body(SELF_ROOT_SIBLINGS_PATH, equalTo(flattenedRootSiblings));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should not return multiple self hierarchies with same output name")
	void shouldNotReturnMultipleSelfHierarchiesWithSameOutputName(Evita evita, GraphQLTester tester) {
		tester.test(TEST_CATALOG)
			.document("""
				{
					queryCategory(
						filterBy: {
							entityLocaleEquals: cs_CZ
						}
					) {
						recordPage(size: 0) {data {primaryKey}}
						extraResults {
							hierarchy {
								self {
									megaMenu: fromRoot { %s }
								}
							}
							otherHierarchy {
								self {
									megaMenu: siblings { %s }
								}
							}
						}
					}
				}
				""",
				getLevelInfoFragment(),
				getLevelInfoFragment())
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, hasSize(greaterThan(0)));
	}

	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return referenced hierarchy from root")
	@ParameterizedTest
	@MethodSource("statisticTypeAndBaseVariants")
	void shouldReturnReferencedHierarchyFromRoot(EnumSet<StatisticsType> statisticsType, StatisticsBase base, Evita evita, GraphQLTester tester) {
		var expectedHierarchy = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> response = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								entityLocaleEquals(CZECH_LOCALE),
								hierarchyWithinRoot(Entities.CATEGORY)
							)
						),
						require(
							// we don't need the results whatsoever
							page(1, 0),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							// we need only data about cardinalities
							hierarchyOfReference(
								Entities.CATEGORY,
								orderBy(attributeNatural(ATTRIBUTE_CODE, DESC)),
								fromRoot(
									"megaMenu",
									entityFetch(hierarchyContent(), attributeContent()),
									stopAt(distance(2)),
									statisticsType.isEmpty() ? new io.evitadb.api.query.require.HierarchyStatistics(base) :
										new io.evitadb.api.query.require.HierarchyStatistics(base, statisticsType.toArray(StatisticsType[]::new))
								)
							)
						)
					),
					EntityReference.class
				);

				final Hierarchy hierarchy = response.getExtraResult(Hierarchy.class);
				final List<LevelInfo> megaMenu = hierarchy.getReferenceHierarchy(Entities.CATEGORY, "megaMenu");
				final List<Map<String, Object>> flattenedMegaMenu = createFlattenedHierarchy(megaMenu);
				assertFalse(flattenedMegaMenu.isEmpty());
				return flattenedMegaMenu;
			});

		tester.test(TEST_CATALOG)
			.document("""
				{
					queryProduct(
						filterBy: {
							entityLocaleEquals: cs_CZ
						}
					) {
						recordPage(size: 0) {data {primaryKey}}
						extraResults {
							hierarchy {
								category(
									orderBy: {attributeCodeNatural: DESC},
									emptyHierarchicalEntityBehaviour: REMOVE_EMPTY
								) {
									megaMenu: fromRoot(
										stopAt: { distance: 2 }
										%s
									) { %s }
								}
							}
						}
					}
				}
				""",
				getHierarchyStatisticsBaseArgument(base),
				getLevelInfoFragment(statisticsType))
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(REFERENCED_MEGA_MENU_PATH, equalTo(expectedHierarchy));
	}

	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return referenced hierarchy from node")
	@ParameterizedTest
	@MethodSource("statisticTypeAndBaseVariants")
	void shouldReturnReferencedHierarchyFromNode(EnumSet<StatisticsType> statisticsType, StatisticsBase base, Evita evita, GraphQLTester tester) {
		var expectedHierarchy = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> response = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								entityLocaleEquals(CZECH_LOCALE),
								hierarchyWithin(Entities.CATEGORY,entityPrimaryKeyInSet(6))
							)
						),
						require(
							// we don't need the results whatsoever
							page(1, 0),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							// we need only data about cardinalities
							hierarchyOfReference(
								Entities.CATEGORY,
								orderBy(attributeNatural(ATTRIBUTE_CODE, DESC)),
								fromNode(
									"megaMenu",
									node(filterBy(entityPrimaryKeyInSet(2))),
									entityFetch(hierarchyContent(), attributeContent()),
									stopAt(distance(2)),
									statisticsType.isEmpty() ? new io.evitadb.api.query.require.HierarchyStatistics(base) :
										new io.evitadb.api.query.require.HierarchyStatistics(base, statisticsType.toArray(StatisticsType[]::new))
								)
							)
						)
					),
					EntityReference.class
				);

				final Hierarchy hierarchy = response.getExtraResult(Hierarchy.class);
				final List<LevelInfo> megaMenu = hierarchy.getReferenceHierarchy(Entities.CATEGORY, "megaMenu");
				final List<Map<String, Object>> flattenedMegaMenu = createFlattenedHierarchy(megaMenu);
				assertFalse(flattenedMegaMenu.isEmpty());
				return flattenedMegaMenu;
			});

		tester.test(TEST_CATALOG)
			.document("""
				{
					queryProduct(
						filterBy: {
							entityLocaleEquals: cs_CZ,
							hierarchyCategoryWithin: { ofParent: { entityPrimaryKeyInSet: 6 } }
						}
					) {
						recordPage(size: 0) {data {primaryKey}}
						extraResults {
							hierarchy {
								category(
									orderBy: {attributeCodeNatural: DESC},
									emptyHierarchicalEntityBehaviour: REMOVE_EMPTY
								) {
									megaMenu: fromNode(
										node: { filterBy: { entityPrimaryKeyInSet: 2 }}
										stopAt: { distance: 2 }
										%s
									) { %s }
								}
							}
						}
					}
				}
				""",
				getHierarchyStatisticsBaseArgument(base),
				getLevelInfoFragment(statisticsType))
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(REFERENCED_MEGA_MENU_PATH, equalTo(expectedHierarchy));
	}

	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return referenced hierarchy children")
	@ParameterizedTest
	@MethodSource("statisticTypeAndBaseVariants")
	void shouldReturnReferencedHierarchyChildren(EnumSet<StatisticsType> statisticsType, StatisticsBase base, Evita evita, GraphQLTester tester) {
		var expectedHierarchy = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> response = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								entityLocaleEquals(CZECH_LOCALE),
								hierarchyWithin(Entities.CATEGORY, entityPrimaryKeyInSet(1))
							)
						),
						require(
							// we don't need the results whatsoever
							page(1, 0),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							// we need only data about cardinalities
							hierarchyOfReference(
								Entities.CATEGORY,
								orderBy(attributeNatural(ATTRIBUTE_CODE, DESC)),
								children(
									"megaMenu",
									entityFetch(hierarchyContent(), attributeContent()),
									stopAt(distance(1)),
									statisticsType.isEmpty() ? new io.evitadb.api.query.require.HierarchyStatistics(base) :
										new io.evitadb.api.query.require.HierarchyStatistics(base, statisticsType.toArray(StatisticsType[]::new))
								)
							)
						)
					),
					EntityReference.class
				);

				final Hierarchy hierarchy = response.getExtraResult(Hierarchy.class);
				final List<LevelInfo> megaMenu = hierarchy.getReferenceHierarchy(Entities.CATEGORY, "megaMenu");
				final List<Map<String, Object>> flattenedMegaMenu = createFlattenedHierarchy(megaMenu);
				assertFalse(flattenedMegaMenu.isEmpty());
				return flattenedMegaMenu;
			});

		tester.test(TEST_CATALOG)
			.document("""
				{
					queryProduct(
						filterBy: {
							entityLocaleEquals: cs_CZ,
							hierarchyCategoryWithin: { ofParent: { entityPrimaryKeyInSet: 1 } }
						}
					) {
						recordPage(size: 0) {data {primaryKey}}
						extraResults {
							hierarchy {
								category(
									orderBy: {attributeCodeNatural: DESC},
									emptyHierarchicalEntityBehaviour: REMOVE_EMPTY
								) {
									megaMenu: children(
										stopAt: { distance: 1 }
										%s
									) { %s }
								}
							}
						}
					}
				}
				""",
				getHierarchyStatisticsBaseArgument(base),
				getLevelInfoFragment(statisticsType))
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(REFERENCED_MEGA_MENU_PATH, equalTo(expectedHierarchy));
	}

	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return referenced hierarchy parents without siblings")
	@ParameterizedTest
	@MethodSource("statisticTypeAndBaseVariants")
	void shouldReturnReferencedHierarchyParentsWithoutSiblings(EnumSet<StatisticsType> statisticsType, StatisticsBase base, Evita evita, GraphQLTester tester) {
		var expectedHierarchy = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> response = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								entityLocaleEquals(CZECH_LOCALE),
								hierarchyWithin(Entities.CATEGORY, entityPrimaryKeyInSet(30))
							)
						),
						require(
							// we don't need the results whatsoever
							page(1, 0),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							// we need only data about cardinalities
							hierarchyOfReference(
								Entities.CATEGORY,
								orderBy(attributeNatural(ATTRIBUTE_CODE, DESC)),
								parents(
									"megaMenu",
									entityFetch(hierarchyContent(), attributeContent()),
									statisticsType.isEmpty() ? new io.evitadb.api.query.require.HierarchyStatistics(base) :
										new io.evitadb.api.query.require.HierarchyStatistics(base, statisticsType.toArray(StatisticsType[]::new))
								)
							)
						)
					),
					EntityReference.class
				);

				final Hierarchy hierarchy = response.getExtraResult(Hierarchy.class);
				final List<LevelInfo> megaMenu = hierarchy.getReferenceHierarchy(Entities.CATEGORY, "megaMenu");
				final List<Map<String, Object>> flattenedMegaMenu = createFlattenedHierarchy(megaMenu);
				assertFalse(flattenedMegaMenu.isEmpty());
				return flattenedMegaMenu;
			});

		tester.test(TEST_CATALOG)
			.document("""
				{
					queryProduct(
						filterBy: {
							entityLocaleEquals: cs_CZ,
							hierarchyCategoryWithin: { ofParent: { entityPrimaryKeyInSet: 30 } }
						}
					) {
						recordPage(size: 0) {data {primaryKey}}
						extraResults {
							hierarchy {
								category(
									orderBy: {attributeCodeNatural: DESC},
									emptyHierarchicalEntityBehaviour: REMOVE_EMPTY
								) {
									megaMenu: parents(
										stopAt: { distance: 100 }
										%s
									) { %s }
								}
							}
						}
					}
				}
				""",
				getHierarchyStatisticsBaseArgument(base),
				getLevelInfoFragment(statisticsType))
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(REFERENCED_MEGA_MENU_PATH, equalTo(expectedHierarchy));
	}

	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return referenced hierarchy parents with siblings")
	@ParameterizedTest
	@MethodSource("statisticTypeAndBaseVariants")
	void shouldReturnReferencedHierarchyParentsWithSiblings(EnumSet<StatisticsType> statisticsType, StatisticsBase base, Evita evita, GraphQLTester tester) {
		var expectedHierarchy = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> response = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								entityLocaleEquals(CZECH_LOCALE),
								hierarchyWithin(Entities.CATEGORY, entityPrimaryKeyInSet(30))
							)
						),
						require(
							// we don't need the results whatsoever
							page(1, 0),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							// we need only data about cardinalities
							hierarchyOfReference(
								Entities.CATEGORY,
								orderBy(attributeNatural(ATTRIBUTE_CODE, DESC)),
								parents(
									"megaMenu",
									entityFetch(hierarchyContent(), attributeContent()),
									siblings(
										entityFetch(hierarchyContent(), attributeContent()),
										stopAt(distance(2)),
										statisticsType.isEmpty() ? new io.evitadb.api.query.require.HierarchyStatistics(base) :
											new io.evitadb.api.query.require.HierarchyStatistics(base, statisticsType.toArray(StatisticsType[]::new))
									),
									statisticsType.isEmpty() ? new io.evitadb.api.query.require.HierarchyStatistics(base) :
										new io.evitadb.api.query.require.HierarchyStatistics(base, statisticsType.toArray(StatisticsType[]::new))
								)
							)
						)
					),
					EntityReference.class
				);

				final Hierarchy hierarchy = response.getExtraResult(Hierarchy.class);
				final List<LevelInfo> megaMenu = hierarchy.getReferenceHierarchy(Entities.CATEGORY, "megaMenu");
				final List<Map<String, Object>> flattenedMegaMenu = createFlattenedHierarchy(megaMenu);
				assertFalse(flattenedMegaMenu.isEmpty());
				return flattenedMegaMenu;
			});

		tester.test(TEST_CATALOG)
			.document("""
				{
					queryProduct(
						filterBy: {
							entityLocaleEquals: cs_CZ,
							hierarchyCategoryWithin: { ofParent: { entityPrimaryKeyInSet: 30 } }
						}
					) {
						recordPage(size: 0) {data {primaryKey}}
						extraResults {
							hierarchy {
								category(
									orderBy: {attributeCodeNatural: DESC},
									emptyHierarchicalEntityBehaviour: REMOVE_EMPTY
								) {
									megaMenu: parents(
										stopAt: { distance: 100 }
										siblings: { stopAt: { distance: 2 }}
										%s
									) { %s }
								}
							}
						}
					}
				}
				""",
				getHierarchyStatisticsBaseArgument(base),
				getLevelInfoFragment(statisticsType))
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(REFERENCED_MEGA_MENU_PATH, equalTo(expectedHierarchy));
	}

	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return referenced hierarchy root siblings")
	@ParameterizedTest
	@MethodSource("statisticTypeAndBaseVariants")
	void shouldReturnReferencedHierarchyRootSiblings(EnumSet<StatisticsType> statisticsType, StatisticsBase base, Evita evita, GraphQLTester tester) {
		var expectedHierarchy = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> response = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								entityLocaleEquals(CZECH_LOCALE),
								hierarchyWithinRoot(Entities.CATEGORY)
							)
						),
						require(
							// we don't need the results whatsoever
							page(1, 0),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							// we need only data about cardinalities
							hierarchyOfReference(
								Entities.CATEGORY,
								orderBy(attributeNatural(ATTRIBUTE_CODE, DESC)),
								siblings(
									"rootSiblings",
									entityFetch(hierarchyContent(), attributeContent()),
									stopAt(distance(1)),
									statisticsType.isEmpty() ? new io.evitadb.api.query.require.HierarchyStatistics(base) :
										new io.evitadb.api.query.require.HierarchyStatistics(base, statisticsType.toArray(StatisticsType[]::new))
								)
							)
						)
					),
					EntityReference.class
				);

				final Hierarchy hierarchy = response.getExtraResult(Hierarchy.class);
				final List<LevelInfo> rootSiblings = hierarchy.getReferenceHierarchy(Entities.CATEGORY, "rootSiblings");
				final List<Map<String, Object>> flattenedRootSiblings = createFlattenedHierarchy(rootSiblings);
				assertFalse(flattenedRootSiblings.isEmpty());
				return flattenedRootSiblings;
			});

		tester.test(TEST_CATALOG)
			.document("""
				{
					queryProduct(
						filterBy: {
							entityLocaleEquals: cs_CZ,
							hierarchyCategoryWithinRoot: {}
						}
					) {
						recordPage(size: 0) {data {primaryKey}}
						extraResults {
							hierarchy {
								category(
									orderBy: {attributeCodeNatural: DESC},
									emptyHierarchicalEntityBehaviour: REMOVE_EMPTY
								) {
									rootSiblings: siblings(
										stopAt: { distance: 1 }
										%s
									) { %s }
								}
							}
						}
					}
				}
				""",
				getHierarchyStatisticsBaseArgument(base),
				getLevelInfoFragment(statisticsType))
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(REFERENCED_ROOT_SIBLINGS_PATH, equalTo(expectedHierarchy));
	}

	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return multiple different referenced hierarchies")
	@ParameterizedTest
	@MethodSource("statisticTypeAndBaseVariants")
	void shouldReturnMultipleDifferentReferencedHierarchies(EnumSet<StatisticsType> statisticsType, StatisticsBase base, Evita evita, GraphQLTester tester) {
		var hierarchy = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> response = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								entityLocaleEquals(CZECH_LOCALE),
								hierarchyWithinRoot(Entities.CATEGORY)
							)
						),
						require(
							// we don't need the results whatsoever
							page(1, 0),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							// we need only data about cardinalities
							hierarchyOfReference(
								Entities.CATEGORY,
								orderBy(attributeNatural(ATTRIBUTE_CODE, DESC)),
								fromRoot(
									"megaMenu",
									entityFetch(hierarchyContent(), attributeContent()),
									stopAt(distance(2)),
									statisticsType.isEmpty() ? new io.evitadb.api.query.require.HierarchyStatistics(base) :
										new io.evitadb.api.query.require.HierarchyStatistics(base, statisticsType.toArray(StatisticsType[]::new))
								),
								siblings(
									"rootSiblings",
									entityFetch(hierarchyContent(), attributeContent()),
									stopAt(distance(1)),
									statisticsType.isEmpty() ? new io.evitadb.api.query.require.HierarchyStatistics(base) :
										new io.evitadb.api.query.require.HierarchyStatistics(base, statisticsType.toArray(StatisticsType[]::new))
								)
							)
						)
					),
					EntityReference.class
				);

				return response.getExtraResult(Hierarchy.class);
			});

		final List<LevelInfo> megaMenu = hierarchy.getReferenceHierarchy(Entities.CATEGORY, "megaMenu");
		final List<Map<String, Object>> flattenedMegaMenu = createFlattenedHierarchy(megaMenu);
		assertFalse(flattenedMegaMenu.isEmpty());

		final List<LevelInfo> rootSiblings = hierarchy.getReferenceHierarchy(Entities.CATEGORY, "rootSiblings");
		final List<Map<String, Object>> flattenedRootSiblings = createFlattenedHierarchy(rootSiblings);
		assertFalse(flattenedRootSiblings.isEmpty());

		tester.test(TEST_CATALOG)
			.document("""
				{
					queryProduct(
						filterBy: {
							entityLocaleEquals: cs_CZ,
							hierarchyCategoryWithinRoot: {}
						}
					) {
						recordPage(size: 0) {data {primaryKey}}
						extraResults {
							hierarchy {
								category(
									orderBy: {attributeCodeNatural: DESC},
									emptyHierarchicalEntityBehaviour: REMOVE_EMPTY
								) {
									megaMenu: fromRoot(
										stopAt: { distance: 2 }
										%s
									) { %s }
									rootSiblings: siblings(
										stopAt: { distance: 1 }
										%s
									) { %s }
								}
							}
						}
					}
				}
				""",
				getHierarchyStatisticsBaseArgument(base),
				getLevelInfoFragment(statisticsType),
				getHierarchyStatisticsBaseArgument(base),
				getLevelInfoFragment(statisticsType))
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(REFERENCED_MEGA_MENU_PATH, equalTo(flattenedMegaMenu))
			.body(REFERENCED_ROOT_SIBLINGS_PATH, equalTo(flattenedRootSiblings));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should not return multiple referenced hierarchies with same output name")
	void shouldNotReturnMultipleSelfHierarchiesWithReferencedOutputName(Evita evita, GraphQLTester tester) {
		tester.test(TEST_CATALOG)
			.document("""
				{
					queryProduct(
						filterBy: {
							entityLocaleEquals: cs_CZ,
							hierarchyCategoryWithinRoot: {}
						}
					) {
						recordPage(size: 0) {data {primaryKey}}
						extraResults {
							hierarchy {
								category(emptyHierarchicalEntityBehaviour: REMOVE_EMPTY) {
									megaMenu: fromRoot { %s }
								}
							}
							otherHierarchy {
								category(emptyHierarchicalEntityBehaviour: REMOVE_EMPTY) {
									megaMenu: siblings { %s }
								}
							}
						}
					}
				}
				""",
				getLevelInfoFragment(),
				getLevelInfoFragment())
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, hasSize(greaterThan(0)));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return facet summary with counts for products")
	void shouldReturnFacetSummaryWithCountsForProducts(Evita evita, GraphQLTester tester) {
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
		assertFalse(response.getExtraResult(FacetSummary.class).getReferenceStatistics().isEmpty());

		final var expectedBody = createNonGroupedFacetSummaryWithCountsDto(response, Entities.BRAND);

		tester.test(TEST_CATALOG)
			.document(
				"""
		            query {
		                queryProduct {
		                    extraResults {
		                        __typename
		                        facetSummary {
		                            __typename
		                            brand {
		                                __typename
			                            count
			                            facetStatistics {
			                                __typename
			                                facetEntity {
			                                    __typename
			                                    primaryKey
			                                    type
			                                }
			                                requested
			                                count
			                            }
		                            }
		                        }
		                    }
		                }
		            }
					""",
				Integer.MAX_VALUE
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				PRODUCT_QUERY_PATH + "." + ResponseDescriptor.EXTRA_RESULTS.name() + "." + TYPENAME_FIELD,
				equalTo(ExtraResultsDescriptor.THIS.name(createEmptyEntitySchema("Product")))
			)
			.body(
				PRODUCT_QUERY_PATH + "." + ResponseDescriptor.EXTRA_RESULTS.name() + "." + ExtraResultsDescriptor.FACET_SUMMARY.name() + "." + TYPENAME_FIELD,
				equalTo(FacetSummaryDescriptor.THIS.name(createEmptyEntitySchema("Product")))
			)
			.body(
				PRODUCT_QUERY_PATH + "." + ResponseDescriptor.EXTRA_RESULTS.name() + "." + ExtraResultsDescriptor.FACET_SUMMARY.name() + ".brand",
				equalTo(expectedBody)
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return facet summary with impacts and entities for products")
	void shouldReturnFacetSummaryWithImpactsAndEntitiesForProducts(Evita evita, GraphQLTester tester) {
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
		assertFalse(response.getExtraResult(FacetSummary.class).getReferenceStatistics().isEmpty());

		final var expectedBody = createNonGroupedFacetSummaryWithImpactsDto(response, Entities.BRAND);

		tester.test(TEST_CATALOG)
			.document(
				"""
		            query {
		                queryProduct {
		                    extraResults {
		                        facetSummary {
		                            brand {
		                                count
			                            facetStatistics {
			                                facetEntity {
			                                    primaryKey
			                                    type
			                                    attributes {
			                                        code
			                                    }
			                                }
			                                requested
			                                count
			                                impact {
			                                    __typename
			                                    difference
			                                    matchCount
			                                    hasSense
			                                }
			                            }
		                            }
		                        }
		                    }
		                }
		            }
					""",
				Integer.MAX_VALUE
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				PRODUCT_QUERY_PATH + "." + ResponseDescriptor.EXTRA_RESULTS.name() + "." + ExtraResultsDescriptor.FACET_SUMMARY.name() + ".brand",
				equalTo(expectedBody)
			);
		;
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return facet summary with filtered and ordered facet groups")
	void shouldReturnFacetSummaryWithFilteredAndOrderedFacetGroups(Evita evita, GraphQLTester tester) {
		final EvitaResponse<EntityReference> response = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.query(
					query(
						collection(Entities.PRODUCT),
						require(
							facetSummaryOfReference(
								Entities.PARAMETER,
								FacetStatisticsDepth.COUNTS,
								filterGroupBy(
									attributeLessThanEquals(ATTRIBUTE_CODE, "K"),
									entityLocaleEquals(Locale.ENGLISH)
								),
								orderGroupBy(attributeNatural(ATTRIBUTE_NAME, OrderDirection.DESC))
							)
						)
					),
					EntityReference.class
				);
			}
		);
		assertFalse(response.getExtraResult(FacetSummary.class).getReferenceStatistics().isEmpty());

		final var expectedBody = createFacetSummaryWithCountsDto(response, Entities.PARAMETER);

		tester.test(TEST_CATALOG)
			.document(
				"""
		            query {
		                queryProduct {
		                    extraResults {
		                        facetSummary {
		                            parameter(
		                                filterGroupBy: {
		                                    attributeCodeLessThanEquals: "K",
		                                    entityLocaleEquals: en                                   
			                            },
			                            orderGroupBy: {
			                                attributeNameNatural: DESC
			                            }
		                            ) {
		                                __typename
		                                groupEntity {
			                                __typename
			                                primaryKey
			                                type
			                            }
			                            count
			                            facetStatistics {
			                                __typename
			                                facetEntity {
			                                    __typename
			                                    primaryKey
			                                    type
			                                }
			                                requested
			                                count
			                            }
		                            }
		                        }
		                    }
		                }
		            }
					""",
				Integer.MAX_VALUE
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				PRODUCT_QUERY_PATH + "." + ResponseDescriptor.EXTRA_RESULTS.name() + "." + ExtraResultsDescriptor.FACET_SUMMARY.name() + ".parameter",
				equalTo(expectedBody)
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return empty facet summary of non-grouped reference if missing reference")
	void shouldReturnEmptyFacetSummaryOfNonGroupedReferenceIfMissingReference(Evita evita, GraphQLTester tester) {
		final EntityClassifier entityWithoutBrand = getEntity(
			evita,
			query(
				collection(Entities.PRODUCT),
				filterBy(
					not(referenceHaving(Entities.BRAND))
				),
				require(
					strip(0, 1)
				)
			)
		);
		assertNotNull(entityWithoutBrand);

		tester.test(TEST_CATALOG)
			.document(
				"""
		            query {
		                queryProduct(
		                    filterBy: {
		                        entityPrimaryKeyInSet: %d
		                    }
		                ) {
		                    extraResults {
		                        facetSummary {
		                            brand {
			                            count
		                            }
		                        }
		                    }
		                }
		            }
					""",
				entityWithoutBrand.getPrimaryKey()
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				resultPath(PRODUCT_QUERY_PATH),
				equalTo(
					map()
						.e(ResponseDescriptor.EXTRA_RESULTS.name(), map()
							.e(ExtraResultsDescriptor.FACET_SUMMARY.name(), map()
								.e("brand", null)))
						.build()
				)
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return facet summary with filtered and ordered facets")
	void shouldReturnFacetSummaryWithFilteredAndOrderedFacets(Evita evita, GraphQLTester tester) {
		final EvitaResponse<EntityReference> response = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.query(
					query(
						collection(Entities.PRODUCT),
						require(
							facetSummaryOfReference(
								Entities.PARAMETER,
								FacetStatisticsDepth.COUNTS,
								filterBy(
									attributeLessThanEquals(ATTRIBUTE_CODE, "K"),
									entityLocaleEquals(Locale.ENGLISH)
								),
								orderBy(attributeNatural(ATTRIBUTE_NAME, OrderDirection.DESC))
							)
						)
					),
					EntityReference.class
				);
			}
		);
		assertFalse(response.getExtraResult(FacetSummary.class).getReferenceStatistics().isEmpty());

		final var expectedBody = createFacetSummaryWithCountsDto(response, Entities.PARAMETER);

		tester.test(TEST_CATALOG)
			.document(
				"""
		            query {
		                queryProduct {
		                    extraResults {
		                        facetSummary {
		                            parameter {
		                                __typename
		                                groupEntity {
			                                __typename
			                                primaryKey
			                                type
			                            }
			                            count
			                            facetStatistics(
			                                filterBy: {
		                                        attributeCodeLessThanEquals: "K",
		                                        entityLocaleEquals: en
				                            },
				                            orderBy: {
				                                attributeNameNatural: DESC
				                            }
			                            ) {
			                                __typename
			                                facetEntity {
			                                    __typename
			                                    primaryKey
			                                    type
			                                }
			                                requested
			                                count
			                            }
		                            }
		                        }
		                    }
		                }
		            }
					""",
				Integer.MAX_VALUE
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				PRODUCT_QUERY_PATH + "." + ResponseDescriptor.EXTRA_RESULTS.name() + "." + ExtraResultsDescriptor.FACET_SUMMARY.name() + ".parameter",
				equalTo(expectedBody)
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return facet summary with marked facets")
	void shouldReturnFacetSummaryWithMarkedFacets(Evita evita, GraphQLTester tester) {
		final EvitaResponse<EntityReference> response = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							userFilter(
								facetHaving(
									Entities.PARAMETER,
									entityHaving(
										attributeLessThanEquals(ATTRIBUTE_CODE, "H")
									)
								)
							)
						),
						require(
							facetSummaryOfReference(
								Entities.PARAMETER,
								FacetStatisticsDepth.COUNTS,
								filterBy(
									attributeLessThanEquals(ATTRIBUTE_CODE, "K"),
									entityLocaleEquals(Locale.ENGLISH)
								),
								orderBy(attributeNatural(ATTRIBUTE_NAME, OrderDirection.DESC))
							)
						)
					),
					EntityReference.class
				);
			}
		);
		assertFalse(response.getExtraResult(FacetSummary.class).getReferenceStatistics().isEmpty());

		final var expectedBody = createFacetSummaryWithCountsDto(response, Entities.PARAMETER);

		tester.test(TEST_CATALOG)
			.document(
				"""
		            query {
		                queryProduct(
		                    filterBy: {
		                        userFilter: {
		                            facetParameterHaving: {
		                                entityHaving: {
		                                    attributeCodeLessThanEquals: "H"
		                                }
		                            }
		                        }
		                    }
		                ) {
		                    extraResults {
		                        facetSummary {
		                            parameter {
		                                __typename
		                                groupEntity {
			                                __typename
			                                primaryKey
			                                type
			                            }
			                            count
			                            facetStatistics(
			                                filterBy: {
		                                        attributeCodeLessThanEquals: "K",
		                                        entityLocaleEquals: en		                                       
				                            },
				                            orderBy: {
				                                attributeNameNatural: DESC
				                            }
			                            ) {
			                                __typename
			                                facetEntity {
			                                    __typename
			                                    primaryKey
			                                    type
			                                }
			                                requested
			                                count
			                            }
		                            }
		                        }
		                    }
		                }
		            }
					""",
				Integer.MAX_VALUE
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				PRODUCT_QUERY_PATH + "." + ResponseDescriptor.EXTRA_RESULTS.name() + "." + ExtraResultsDescriptor.FACET_SUMMARY.name() + ".parameter",
				equalTo(expectedBody)
			);
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
				final Predicate<Integer> expectedEntitiesCountValidator = size -> size == 5;

				compareGraphQLResultPksToEvitaDBResultPks(
					"First page must be sorted by name in descending order.",
					session, tester,
					fabricateEvitaQLSegmentedQuery(1, 5, evitaQLSegments),
					fabricateGraphQLSegmentedQuery(1, 5, graphQLSegments),
					expectedEntitiesCountValidator
				);

				compareGraphQLResultPksToEvitaDBResultPks(
					"Second page must be sorted by ean in descending order and quantity in asceding order.",
					session, tester,
					fabricateEvitaQLSegmentedQuery(2, 5, evitaQLSegments),
					fabricateGraphQLSegmentedQuery(2, 5, graphQLSegments),
					expectedEntitiesCountValidator
				);

				compareGraphQLResultPksToEvitaDBResultPks(
					"Third page must be sorted by PK in ascending order.",
					session, tester,
					fabricateEvitaQLSegmentedQuery(3, 5, evitaQLSegments),
					fabricateGraphQLSegmentedQuery(3, 5, graphQLSegments),
					expectedEntitiesCountValidator
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
				final Predicate<Integer> expectedEntitiesCountValidator = size -> size == 5;

				compareGraphQLResultPksToEvitaDBResultPks(
					"First page must be sorted by name in descending order.",
					session, tester,
					fabricateEvitaQLSegmentedQuery(1, 5, evitaQLSegments),
					fabricateGraphQLSegmentedQuery(1, 5, graphQLSegments),
					expectedEntitiesCountValidator
				);

				compareGraphQLResultPksToEvitaDBResultPks(
					"Second page must be sorted by name in descending order.",
					session, tester,
					fabricateEvitaQLSegmentedQuery(2, 5, evitaQLSegments),
					fabricateGraphQLSegmentedQuery(2, 5, graphQLSegments),
					expectedEntitiesCountValidator
				);

				compareGraphQLResultPksToEvitaDBResultPks(
					"Third page must be sorted by EAN in descending order (excluding items on first two pages).",
					session, tester,
					fabricateEvitaQLSegmentedQuery(3, 5, evitaQLSegments),
					fabricateGraphQLSegmentedQuery(3, 5, graphQLSegments),
					expectedEntitiesCountValidator
				);

				compareGraphQLResultPksToEvitaDBResultPks(
					"Fourth page contains 3 entities sorted according to EAN in descending order and ends with first 2 entities sorted according to quantity in ascending order.",
					session, tester,
					fabricateEvitaQLSegmentedQuery(4, 5, evitaQLSegments),
					fabricateGraphQLSegmentedQuery(4, 5, graphQLSegments),
					expectedEntitiesCountValidator
				);

				compareGraphQLResultPksToEvitaDBResultPks(
					"Fifth page must have only 4 entities be sorted by quantity in ascending order and must end with first entity sorted by PK in ascending order.",
					session, tester,
					fabricateEvitaQLSegmentedQuery(5, 5, evitaQLSegments),
					fabricateGraphQLSegmentedQuery(5, 5, graphQLSegments),
					expectedEntitiesCountValidator
				);

				compareGraphQLResultPksToEvitaDBResultPks(
					"Sixth page must be sorted by PK in ascending order (but only from those entities that hasn't been already provided).",
					session, tester,
					fabricateEvitaQLSegmentedQuery(6, 5, evitaQLSegments),
					fabricateGraphQLSegmentedQuery(6, 5, graphQLSegments),
					expectedEntitiesCountValidator
				);

				compareGraphQLResultPksToEvitaDBResultPks(
					"Seventh page must be sorted by PK in ascending order (but only from those entities that hasn't been already provided).",
					session, tester,
					fabricateEvitaQLSegmentedQuery(7, 5, evitaQLSegments),
					fabricateGraphQLSegmentedQuery(7, 5, graphQLSegments),
					expectedEntitiesCountValidator
				);

				return null;
			}
		);
	}

	@DisplayName("Should insert spaces into paginated results")
	@UseDataSet(GRAPHQL_HUNDRED_PRODUCTS_FOR_SEGMENTS)
	@Test
	void shouldInsertSpaces(Evita evita, GraphQLTester tester) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				for (int i = 1; i <= 10; i++) {
					compareGraphQLResultPksToEvitaDBResultPks(
						"Page " + i,
						session, tester,
						fabricateEvitaQLSpacingQuery(i, 10),
						fabricateGraphQLSpacingQuery(i, 10),
						size -> size > 0
					);
				}
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
	                    queryProduct(
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
	                        recordPage {
	                            totalRecordCount
	                        }
	                    }
	                }
					"""
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(resultPath(PRODUCT_QUERY_PATH, ResponseDescriptor.RECORD_PAGE, DataChunkDescriptor.TOTAL_RECORD_COUNT), greaterThan(0));
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
					queryProduct(
						filterBy: {
							priceInPriceLists: "basic",
							priceInCurrency: CZK,
							priceValidInNow: true
						},
						orderBy: {
							priceNatural: DESC
						}
					) {
						recordPage {
							data {
								primaryKey
							}
						}
					}
				}
				""")
			.executeAndExpectOkAndThen()
			.body(
				resultPath(PRODUCT_QUERY_DATA_PATH, EntityDescriptor.PRIMARY_KEY.name()),
				equalTo(expectedEntities.getRecordData().stream().map(EntityClassifier::getPrimaryKeyOrThrowException).toList())
			);
	}

	/**
	 * Creates a query for retrieving paginated product entities with specified spacing conditions.
	 *
	 * @param pageNumber the page number to retrieve, must be greater than 0
	 * @param pageSize the number of items per page, must be greater than 0
	 * @return a constructed Query object with the specified pagination and spacing conditions
	 */
	@Nonnull
	private static Query fabricateEvitaQLSpacingQuery(int pageNumber, int pageSize) {
		return query(
			collection(Entities.PRODUCT),
			require(
				page(
					pageNumber, pageSize,
					spacing(
						gap(2, "(($pageNumber - 1) % 2 == 0) && $pageNumber <= 6"),
						gap(1, "($pageNumber % 2 == 0) && $pageNumber <= 6")
					)
				),
				debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
			)
		);
	}

	@Nonnull
	private static String fabricateGraphQLSpacingQuery(int pageNumber, int pageSize) {
		return String.format(
			"""
				{
					queryProduct {
						recordPage(
							number: %d,
							size: %d,
							spacing: [
								{
									gap: {
										size: 2,
										onPage: "(($pageNumber - 1) %%%% 2 == 0) && $pageNumber <= 6"
									}
								},
								{
									gap: {
										size: 1,
										onPage: "($pageNumber %%%% 2 == 0) && $pageNumber <= 6"
									}
								}
							]
						) {
							data {
								primaryKey
							}
						}
					}
				}
				""",
			pageNumber,
			pageSize
		);
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
	private List<SealedEntity> findEntitiesWithPrice(@Nonnull List<SealedEntity> originalProductEntities, int limit) {
		return findEntities(
			originalProductEntities,
			it -> it.getPrices(CURRENCY_CZK, PRICE_LIST_BASIC).size() == 1,
			limit
		);
	}

	@Nonnull
	private List<SealedEntity> findEntitiesWithPrice(@Nonnull List<SealedEntity> originalProductEntities,
	                                                 int limit,
	                                                 @Nonnull String... priceLists) {
		return findEntities(
			originalProductEntities,
			it -> Arrays.stream(priceLists).allMatch(pl -> it.getPrices(CURRENCY_CZK, pl).size() == 1),
			limit
		);
	}

	@Nonnull
	private List<SealedEntity> findEntitiesWithPrices(@Nonnull List<SealedEntity> originalProductEntities,
	                                                 int limit,
													 @Nonnull Currency currency,
	                                                 @Nonnull String... priceLists) {
		return findEntities(
			originalProductEntities,
			it -> Arrays.stream(priceLists).allMatch(pl -> it.getPrices(currency, pl).size() > 1),
			limit
		);
	}

	@Nonnull
	private List<SealedEntity> findEntitiesWithPrices(@Nonnull List<SealedEntity> originalProductEntities,
	                                                  int limit,
	                                                  @Nonnull String priceList,
	                                                  @Nonnull Currency... currencies) {
		return findEntities(
			originalProductEntities,
			it -> Arrays.stream(currencies).allMatch(c -> it.getPrices(c, priceList).size() > 1),
			limit
		);
	}

	@Nonnull
	private Map<String, Object> createBasicPageResponse(@Nonnull List<SealedEntity> entities,
	                                                    @Nonnull Function<SealedEntity, Map<String, Object>> entityMapper) {
		return map()
			.e(TYPENAME_FIELD, ResponseDescriptor.THIS.name(createEmptyEntitySchema("Product")))
			.e(ResponseDescriptor.RECORD_PAGE.name(), map()
				.e(TYPENAME_FIELD, RecordPageDescriptor.THIS.name(createEmptyEntitySchema("Product")))
				.e(
					DataChunkDescriptor.DATA.name(), entities.stream()
					                                         .map(entityMapper)
					                                         .toList())
				.build())
			.build();
	}

	@Nonnull
	private Map<String, Object> createAttributeHistogramDto(@Nonnull EvitaResponse<EntityReference> response,
	                                                        @Nonnull String attributeName) {
		final AttributeHistogram attributeHistogram = response.getExtraResult(AttributeHistogram.class);
		final HistogramContract histogram = attributeHistogram.getHistogram(attributeName);
		Assert.isPremiseValid(
			histogram.getBuckets().length > 0,
			"Attribute histogram must have at least one bucket"
		);

		return map()
			.e(TYPENAME_FIELD, HistogramDescriptor.THIS.name())
			.e(HistogramDescriptor.MIN.name(), histogram.getMin().toString())
			.e(HistogramDescriptor.MAX.name(), histogram.getMax().toString())
			.e(HistogramDescriptor.OVERALL_COUNT.name(), histogram.getOverallCount())
			.e(HistogramDescriptor.BUCKETS.name(), Arrays.stream(histogram.getBuckets())
				.map(bucket -> map()
					.e(TYPENAME_FIELD, BucketDescriptor.THIS.name())
					.e(BucketDescriptor.THRESHOLD.name(), bucket.threshold().toString())
					.e(BucketDescriptor.OCCURRENCES.name(), bucket.occurrences())
					.e(BucketDescriptor.REQUESTED.name(), bucket.requested())
					.build())
				.toList())
			.build();
	}

	@Nonnull
	private Map<String, Object> createPriceHistogramDto(@Nonnull EvitaResponse<EntityReference> response) {
		final PriceHistogram priceHistogram = response.getExtraResult(PriceHistogram.class);
		Assert.isPremiseValid(
			priceHistogram.getBuckets().length > 0,
			"Attribute histogram must have at least one bucket"
		);

		return map()
			.e(TYPENAME_FIELD, HistogramDescriptor.THIS.name())
			.e(HistogramDescriptor.MIN.name(), priceHistogram.getMin().toString())
			.e(HistogramDescriptor.MAX.name(), priceHistogram.getMax().toString())
			.e(HistogramDescriptor.OVERALL_COUNT.name(), priceHistogram.getOverallCount())
			.e(HistogramDescriptor.BUCKETS.name(), Arrays.stream(priceHistogram.getBuckets())
				.map(bucket -> map()
					.e(TYPENAME_FIELD, BucketDescriptor.THIS.name())
					.e(BucketDescriptor.THRESHOLD.name(), bucket.threshold().toString())
					.e(BucketDescriptor.OCCURRENCES.name(), bucket.occurrences())
					.e(BucketDescriptor.REQUESTED.name(), bucket.requested())
					.build())
				.toList())
			.build();
	}

	@Nonnull
	private List<Map<String, Object>> createFlattenedHierarchy(@Nonnull List<LevelInfo> hierarchy) {
		final List<Map<String, Object>> flattenedHierarchy = new LinkedList<>();
		hierarchy.forEach(levelInfo -> createFlattenedHierarchy(flattenedHierarchy, levelInfo, 1));
		return flattenedHierarchy;
	}

	private void createFlattenedHierarchy(@Nonnull List<Map<String, Object>> flattenedHierarchy,
	                                      @Nonnull LevelInfo levelInfo,
	                                      int currentLevel) {
		final SealedEntity entity = (SealedEntity) levelInfo.entity();

		final MapBuilder currentLevelInfoDto = map()
			.e(LevelInfoDescriptor.LEVEL.name(), currentLevel)
			.e(LevelInfoDescriptor.ENTITY.name(), map()
				.e(EntityDescriptor.PRIMARY_KEY.name(), levelInfo.entity().getPrimaryKey())
				.e(GraphQLEntityDescriptor.PARENT_PRIMARY_KEY.name(), entity.parentAvailable() && entity.getParentEntity().isPresent() ? entity.getParentEntity().get().getPrimaryKey() : null)
				.e(
					AttributesProviderDescriptor.ATTRIBUTES.name(), map()
					.e(ATTRIBUTE_CODE, entity.getAttribute(ATTRIBUTE_CODE))))
			.e(LevelInfoDescriptor.REQUESTED.name(), levelInfo.requested());

		if (levelInfo.queriedEntityCount() != null) {
			currentLevelInfoDto.e(LevelInfoDescriptor.QUERIED_ENTITY_COUNT.name(), levelInfo.queriedEntityCount());
		}
		if (levelInfo.childrenCount() != null) {
			currentLevelInfoDto.e(LevelInfoDescriptor.CHILDREN_COUNT.name(), levelInfo.childrenCount());
		}
		flattenedHierarchy.add(currentLevelInfoDto.build());

		levelInfo.children()
			.forEach(childLevelInfo -> createFlattenedHierarchy(flattenedHierarchy, childLevelInfo, currentLevel + 1));
	}

	@Nonnull
	private Map<String, Object> createNonGroupedFacetSummaryWithCountsDto(@Nonnull EvitaResponse<EntityReference> response,
	                                                                      @Nonnull String referenceName) {
		final FacetSummary facetSummary = response.getExtraResult(FacetSummary.class);

		return Optional.ofNullable(facetSummary.getFacetGroupStatistics(referenceName))
			.map(groupStatistics ->
				map()
					.e(TYPENAME_FIELD, FacetGroupStatisticsDescriptor.THIS.name(createEmptyEntitySchema(Entities.PRODUCT), createEmptyEntitySchema(referenceName)))
					.e(FacetGroupStatisticsDescriptor.COUNT.name(), groupStatistics.getCount())
					.e(FacetGroupStatisticsDescriptor.FACET_STATISTICS.name(), groupStatistics.getFacetStatistics()
						.stream()
						.map(facetStatistics ->
							map()
								.e(TYPENAME_FIELD, FacetStatisticsDescriptor.THIS.name(createEmptyEntitySchema(Entities.PRODUCT), createEmptyEntitySchema(referenceName)))
								.e(FacetStatisticsDescriptor.FACET_ENTITY.name(), map()
									.e(TYPENAME_FIELD, StringUtils.toPascalCase(referenceName))
									.e(EntityDescriptor.PRIMARY_KEY.name(), facetStatistics.getFacetEntity().getPrimaryKey())
									.e(EntityDescriptor.TYPE.name(), facetStatistics.getFacetEntity().getType())
									.build())
								.e(FacetStatisticsDescriptor.REQUESTED.name(), facetStatistics.isRequested())
								.e(FacetStatisticsDescriptor.COUNT.name(), facetStatistics.getCount())
								.build())
						.toList())
					.build()
			)
			.orElseThrow(() -> new IllegalStateException("Facet summary must contain facet group statistics for reference " + referenceName));
	}

	@Nonnull
	private List<Map<String, Object>> createFacetSummaryWithCountsDto(@Nonnull EvitaResponse<EntityReference> response,
	                                                                  @Nonnull String referenceName) {
		final FacetSummary facetSummary = response.getExtraResult(FacetSummary.class);

		return facetSummary.getReferenceStatistics()
			.stream()
			.filter(groupStatistics -> groupStatistics.getReferenceName().equals(referenceName))
			.map(groupStatistics ->
				map()
					.e(TYPENAME_FIELD, FacetGroupStatisticsDescriptor.THIS.name(createEmptyEntitySchema(Entities.PRODUCT), createEmptyEntitySchema(referenceName)))
					.e(FacetGroupStatisticsDescriptor.GROUP_ENTITY.name(), groupStatistics.getGroupEntity() != null
						? map()
							.e(TYPENAME_FIELD, StringUtils.toPascalCase(groupStatistics.getGroupEntity().getType()))
							.e(EntityDescriptor.PRIMARY_KEY.name(), groupStatistics.getGroupEntity().getPrimaryKey())
							.e(EntityDescriptor.TYPE.name(), groupStatistics.getGroupEntity().getType())
						: null)
					.e(FacetGroupStatisticsDescriptor.COUNT.name(), groupStatistics.getCount())
					.e(FacetGroupStatisticsDescriptor.FACET_STATISTICS.name(), groupStatistics.getFacetStatistics()
						.stream()
						.map(facetStatistics ->
							map()
								.e(TYPENAME_FIELD, FacetStatisticsDescriptor.THIS.name(createEmptyEntitySchema(Entities.PRODUCT), createEmptyEntitySchema(referenceName)))
								.e(FacetStatisticsDescriptor.FACET_ENTITY.name(), map()
									.e(TYPENAME_FIELD, StringUtils.toPascalCase(referenceName))
									.e(EntityDescriptor.PRIMARY_KEY.name(), facetStatistics.getFacetEntity().getPrimaryKey())
									.e(EntityDescriptor.TYPE.name(), facetStatistics.getFacetEntity().getType())
									.build())
								.e(FacetStatisticsDescriptor.REQUESTED.name(), facetStatistics.isRequested())
								.e(FacetStatisticsDescriptor.COUNT.name(), facetStatistics.getCount())
								.build())
						.toList())
					.build()
			)
			.toList();
	}

	@Nonnull
	private Map<String, Object> createNonGroupedFacetSummaryWithImpactsDto(@Nonnull EvitaResponse<EntityReference> response,
	                                                                   @Nonnull String referenceName) {
		final FacetSummary facetSummary = response.getExtraResult(FacetSummary.class);

		return Optional.ofNullable(facetSummary.getFacetGroupStatistics(referenceName))
			.map(groupStatistics ->
				map()
					.e(FacetGroupStatisticsDescriptor.COUNT.name(), groupStatistics.getCount())
					.e(FacetGroupStatisticsDescriptor.FACET_STATISTICS.name(), groupStatistics.getFacetStatistics()
						.stream()
						.map(facetStatistics ->
							map()
								.e(FacetStatisticsDescriptor.FACET_ENTITY.name(), map()
									.e(EntityDescriptor.PRIMARY_KEY.name(), facetStatistics.getFacetEntity().getPrimaryKey())
									.e(EntityDescriptor.TYPE.name(), facetStatistics.getFacetEntity().getType())
									.e(
										AttributesProviderDescriptor.ATTRIBUTES.name(), map()
										.e(ATTRIBUTE_CODE, ((SealedEntity) facetStatistics.getFacetEntity()).getAttribute(ATTRIBUTE_CODE))
										.build())
									.build())
								.e(FacetStatisticsDescriptor.REQUESTED.name(), facetStatistics.isRequested())
								.e(FacetStatisticsDescriptor.COUNT.name(), facetStatistics.getCount())
								.e(FacetStatisticsDescriptor.IMPACT.name(), map()
									.e(TYPENAME_FIELD, FacetRequestImpactDescriptor.THIS.name())
									.e(FacetRequestImpactDescriptor.DIFFERENCE.name(), facetStatistics.getImpact().difference())
									.e(FacetRequestImpactDescriptor.MATCH_COUNT.name(), facetStatistics.getImpact().matchCount())
									.e(FacetRequestImpactDescriptor.HAS_SENSE.name(), facetStatistics.getImpact().hasSense())
									.build())
								.build())
						.toList())
					.build()
			)
			.orElseThrow(() -> new IllegalStateException("Facet summary must contain facet group statistics for reference " + referenceName));
	}

	@Nonnull
	private List<Map<String, Object>> createFacetSummaryWithImpactsDto(@Nonnull EvitaResponse<EntityReference> response,
	                                                                   @Nonnull String referenceName) {
		final FacetSummary facetSummary = response.getExtraResult(FacetSummary.class);

		return facetSummary.getReferenceStatistics()
			.stream()
			.filter(groupStatistics -> groupStatistics.getReferenceName().equals(referenceName))
			.map(groupStatistics ->
				map()
					.e(FacetGroupStatisticsDescriptor.GROUP_ENTITY.name(), null)
					.e(FacetGroupStatisticsDescriptor.COUNT.name(), groupStatistics.getCount())
					.e(FacetGroupStatisticsDescriptor.FACET_STATISTICS.name(), groupStatistics.getFacetStatistics()
						.stream()
						.map(facetStatistics ->
							map()
								.e(FacetStatisticsDescriptor.FACET_ENTITY.name(), map()
									.e(EntityDescriptor.PRIMARY_KEY.name(), facetStatistics.getFacetEntity().getPrimaryKey())
									.e(EntityDescriptor.TYPE.name(), facetStatistics.getFacetEntity().getType())
									.e(
										AttributesProviderDescriptor.ATTRIBUTES.name(), map()
										.e(ATTRIBUTE_CODE, ((SealedEntity) facetStatistics.getFacetEntity()).getAttribute(ATTRIBUTE_CODE))
										.build())
									.build())
								.e(FacetStatisticsDescriptor.REQUESTED.name(), facetStatistics.isRequested())
								.e(FacetStatisticsDescriptor.COUNT.name(), facetStatistics.getCount())
								.e(FacetStatisticsDescriptor.IMPACT.name(), map()
									.e(TYPENAME_FIELD, FacetRequestImpactDescriptor.THIS.name())
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

	private void compareGraphQLResultPksToEvitaDBResultPks(@Nonnull String message,
	                                                       @Nonnull EvitaSessionContract session,
	                                                       @Nonnull GraphQLTester tester,
	                                                       @Nonnull Query sampleEvitaQLQuery,
	                                                       @Nonnull String targetGraphQLQuery,
	                                                       @Nonnull Predicate<Integer> entitiesCountValidator) {
		final int[] expectedEntities = session.query(sampleEvitaQLQuery, EntityReference.class)
			.getRecordData()
			.stream()
			.mapToInt(EntityReference::getPrimaryKey)
			.toArray();
		assertTrue(entitiesCountValidator.test(expectedEntities.length));
		final List<Integer> actualEntities = tester.test(TEST_CATALOG)
			.document(targetGraphQLQuery)
			.executeAndExpectOkAndThen()
			.extract()
			.body()
			.jsonPath()
			.getList(resultPath(PRODUCT_QUERY_DATA_PATH, EntityDescriptor.PRIMARY_KEY.name()), Integer.class);
		assertSortedResultEquals(
			message,
			actualEntities,
			expectedEntities
		);
	}

	@Nonnull
	private static String getHierarchyStatisticsBaseArgument(@Nonnull StatisticsBase base) {
		return "statisticsBase: " + base.name();
	}

	@Nonnull
	private static String getLevelInfoFragment() {
		return getLevelInfoFragment(EnumSet.noneOf(StatisticsType.class));
	}

	@Nonnull
	private static String getLevelInfoFragment(@Nonnull EnumSet<StatisticsType> statisticsTypes) {
		return String.format(
            """
				level
				entity {
					primaryKey
					parentPrimaryKey
					attributes {
						code
					}
				}
				requested
				%s
				%s
				""",
			statisticsTypes.contains(StatisticsType.QUERIED_ENTITY_COUNT) ? LevelInfoDescriptor.QUERIED_ENTITY_COUNT.name() : "",
			statisticsTypes.contains(StatisticsType.CHILDREN_COUNT) ? LevelInfoDescriptor.CHILDREN_COUNT.name() : ""
		);
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
	private static String fabricateGraphQLSegmentedQuery(int pageNumber, int pageSize, @Nonnull String segments) {
		return String.format(
			"""
			{
			  queryProduct(
			    filterBy: {
			      entityLocaleEquals: en
			    }
			    orderBy: {
			      %s
			    }
			  ) {
			    recordPage(number: %d, size: %d) {
			      data {
			        primaryKey
			      }
			    }
			  }
			}
			""",
			segments,
			pageNumber,
			pageSize
		);
	}
}
