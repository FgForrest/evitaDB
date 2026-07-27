/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.order.Segments;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.query.require.FacetStatisticsDepth;
import io.evitadb.api.query.require.HierarchyRequireConstraint;
import io.evitadb.api.query.require.HierarchyStatistics;
import io.evitadb.api.query.require.HistogramBehavior;
import io.evitadb.api.query.require.PriceContentMode;
import io.evitadb.api.query.require.StatisticsBase;
import io.evitadb.api.query.require.StatisticsType;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.PriceRangeForSale;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.extraResult.AttributeHistogram;
import io.evitadb.api.requestResponse.extraResult.FacetSummary;
import io.evitadb.api.requestResponse.extraResult.Hierarchy;
import io.evitadb.api.requestResponse.extraResult.Hierarchy.LevelInfo;
import io.evitadb.api.requestResponse.extraResult.HistogramContract;
import io.evitadb.api.requestResponse.extraResult.PriceHistogram;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary;
import io.evitadb.core.Evita;
import io.evitadb.dataType.Scope;
import io.evitadb.externalApi.api.catalog.dataApi.model.EntityDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.ResponseDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.ExtraResultsDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.FacetSummaryDescriptor.FacetGroupStatisticsDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HierarchyDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HistogramDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HistogramDescriptor.BucketDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.ReferenceSummaryDescriptor.FacetRequestImpactDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.ReferenceSummaryDescriptor.FacetStatisticsDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.ReferenceSummaryDescriptor.ReferenceGroupStatisticsDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.extraResult.LevelInfoDescriptor;
import io.evitadb.externalApi.rest.api.testSuite.TestDataGenerator;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.tester.RestTester;
import io.evitadb.test.tester.RestTester.Request;
import io.evitadb.utils.Assert;
import io.evitadb.utils.MapBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.api.query.QueryConstraints.not;
import static io.evitadb.api.query.order.OrderDirection.DESC;
import static io.evitadb.externalApi.rest.api.testSuite.TestDataGenerator.HISTOGRAM_PRICE_INDEX;
import static io.evitadb.externalApi.rest.api.testSuite.TestDataGenerator.REST_HUNDRED_ARCHIVED_PRODUCTS_WITH_ARCHIVE;
import static io.evitadb.externalApi.rest.api.testSuite.TestDataGenerator.REST_THOUSAND_PRODUCTS;
import static io.evitadb.externalApi.rest.api.testSuite.TestDataGenerator.SORTABLE_ATTRIBUTE_COMPOUND_CODE_NAME;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.generator.DataGenerator.*;
import static io.evitadb.utils.AssertionUtils.assertSortedResultEquals;
import static io.evitadb.utils.MapBuilder.map;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.evitadb.test.TestTags.REST;
import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.PRICE;

/**
 * Tests for REST catalog entity list query.
 *
 * @author Martin Veska, FG Forrest a.s. (c) 2022
 */
@SuppressWarnings("deprecation")
@Tag(REST)
@Tag(EXTERNAL_API)
@Tag(QUERY)
class CatalogRestQueryEntityQueryFunctionalTest extends CatalogRestDataEndpointFunctionalTest {

	private static final String DATA_PATH = ResponseDescriptor.RECORD_PAGE.name() + ".data";
	private static final String HIERARCHY_EXTRA_RESULTS_PATH =
		ResponseDescriptor.EXTRA_RESULTS.name() + "." + ExtraResultsDescriptor.HIERARCHY.name();
	private static final String PRICE_HISTOGRAM_RESULTS_PATH =
		ResponseDescriptor.EXTRA_RESULTS.name() + "." + ExtraResultsDescriptor.PRICE_HISTOGRAM.name();

	private static final String SELF_HIERARCHY_EXTRA_RESULTS_PATH =
		HIERARCHY_EXTRA_RESULTS_PATH + "." + HierarchyDescriptor.SELF.name();
	public static final String SELF_MEGA_MENU_PATH = SELF_HIERARCHY_EXTRA_RESULTS_PATH + ".megaMenu";
	public static final String SELF_ROOT_SIBLINGS_PATH = SELF_HIERARCHY_EXTRA_RESULTS_PATH + ".rootSiblings";

	private static final String REFERENCED_HIERARCHY_EXTRA_RESULTS_PATH = HIERARCHY_EXTRA_RESULTS_PATH + ".category";
	private static final String REFERENCED_MEGA_MENU_PATH = REFERENCED_HIERARCHY_EXTRA_RESULTS_PATH + ".megaMenu";
	private static final String REFERENCED_ROOT_SIBLINGS_PATH = REFERENCED_HIERARCHY_EXTRA_RESULTS_PATH + ".rootSiblings";

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

	/**
	 * Creates a query for retrieving paginated product entities with specified spacing conditions.
	 *
	 * @param pageNumber the page number to retrieve, must be greater than 0
	 * @param pageSize   the number of items per page, must be greater than 0
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
	private static String fabricateRestSpacingQuery(int pageNumber, int pageSize) {
		return String.format(
			"""
				{
					"require": {
						"page": {
							"number": %d,
							"size": %d,
							"spacing": [
								{
									"gap": {
										"size": 2,
										"onPage": "(($pageNumber - 1) %%%% 2 == 0) && $pageNumber <= 6"
									}
								},
								{
									"gap": {
										"size": 1,
										"onPage": "($pageNumber %%%% 2 == 0) && $pageNumber <= 6"
									}
								}
							]
						}
					}
				}
				""",
			pageNumber,
			pageSize
		);
	}

	@Nonnull
	private static Map<String, Object> createPriceHistogramDto(
		@Nonnull EvitaResponse<? extends EntityClassifier> response
	) {
		final PriceHistogram priceHistogram = response.getExtraResult(PriceHistogram.class);

		return map()
			.e(HistogramDescriptor.MIN.name(), priceHistogram.getMin().toString())
			.e(HistogramDescriptor.MAX.name(), priceHistogram.getMax().toString())
			.e(HistogramDescriptor.OVERALL_COUNT.name(), priceHistogram.getOverallCount())
			.e(
				HistogramDescriptor.BUCKETS.name(), Arrays.stream(priceHistogram.getBuckets())
					.map(bucket -> map()
						.e(BucketDescriptor.THRESHOLD.name(), bucket.threshold().toString())
						.e(BucketDescriptor.OCCURRENCES.name(), bucket.occurrences())
						.e(BucketDescriptor.REQUESTED.name(), bucket.requested())
						.e(BucketDescriptor.RELATIVE_FREQUENCY.name(), bucket.relativeFrequency().toString())
						.build())
					.toList()
			)
			.e(HistogramDescriptor.MIN.name(), priceHistogram.getMin().toString())
			.e(HistogramDescriptor.OVERALL_COUNT.name(), priceHistogram.getOverallCount())
			.build();
	}

	@Nonnull
	private static List<Map<String, Object>> createFacetSummaryDto(
		@Nonnull EvitaResponse<? extends EntityClassifier> response,
		@Nonnull String referenceName
	) {
		final FacetSummary facetSummary = response.getExtraResult(FacetSummary.class);

		return facetSummary.getReferenceStatistics()
			.stream()
			.filter(groupStatistics -> groupStatistics.getReferenceName().equals(referenceName))
			.map(groupStatistics ->
				     map()
					     .e(
						     FacetGroupStatisticsDescriptor.GROUP_ENTITY.name(),
						     createEntityDto(groupStatistics.getGroupEntity())
					     )
					     .e(FacetGroupStatisticsDescriptor.COUNT.name(), groupStatistics.getCount())
					     .e(
						     FacetGroupStatisticsDescriptor.FACET_STATISTICS.name(),
						     groupStatistics.getFacetStatistics()
							     .stream()
							     .map(facetStatistics -> {
								     final MapBuilder facetStatisticsDto = map()
									     .e(
										     FacetStatisticsDescriptor.REQUESTED.name(),
										     facetStatistics.isRequested()
									     )
									     .e(FacetStatisticsDescriptor.COUNT.name(), facetStatistics.getCount())
									     .e(
										     FacetStatisticsDescriptor.FACET_ENTITY.name(),
										     createEntityDto(facetStatistics.getFacetEntity())
									     );

								     Optional.ofNullable(facetStatistics.getImpact())
									     .ifPresent(impact -> facetStatisticsDto.e(
										     FacetStatisticsDescriptor.IMPACT.name(), map()
											     .e(
												     FacetRequestImpactDescriptor.DIFFERENCE.name(),
												     facetStatistics.getImpact().difference()
											     )
											     .e(
												     FacetRequestImpactDescriptor.MATCH_COUNT.name(),
												     facetStatistics.getImpact().matchCount()
											     )
											     .e(
												     FacetRequestImpactDescriptor.HAS_SENSE.name(),
												     facetStatistics.getImpact().hasSense()
											     )
											     .build()
									     ));

								     return facetStatisticsDto.build();
							     })
							     .toList()
					     )
					     .build()
			)
			.toList();
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

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
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
			.post("/PRODUCT/query")
			.requestBody(
				"""
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
				entities.get(1).getPrimaryKey()
			)
			.executeAndThen()
			.statusCode(200)
			.body(DATA_PATH, equalTo(createEntityDtos(entities)));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return products by primary key greater than")
	void shouldReturnProductsByPrimaryKeyGreaterThan(
		Evita evita, RestTester tester, List<SealedEntity> originalProductEntities
	) {
		final int threshold = originalProductEntities
			.get(originalProductEntities.size() / 2)
			.getPrimaryKeyOrThrowException();

		final List<EntityClassifier> entities = getEntities(
			evita,
			query(
				collection(Entities.PRODUCT),
				filterBy(
					entityPrimaryKeyGreaterThan(threshold)
				),
				require(
					page(1, 20),
					entityFetch(
						attributeContent(ATTRIBUTE_CODE)
					)
				)
			)
		);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/PRODUCT/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody(
				"""
					{
						"filterBy": {
							"entityPrimaryKeyGreaterThan": %d
						},
						"require": {
							"page": {
								"number": 1,
								"size": 20
							},
							"entityFetch": {
								"attributeContent": ["code"]
							}
						}
					}
					""",
				threshold
			)
			.executeAndThen()
			.statusCode(200)
			.body(DATA_PATH, equalTo(createEntityDtos(entities)));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return products by primary key greater than or equals")
	void shouldReturnProductsByPrimaryKeyGreaterThanEquals(
		Evita evita, RestTester tester, List<SealedEntity> originalProductEntities
	) {
		final int threshold = originalProductEntities
			.get(originalProductEntities.size() / 2)
			.getPrimaryKeyOrThrowException();

		final List<EntityClassifier> entities = getEntities(
			evita,
			query(
				collection(Entities.PRODUCT),
				filterBy(
					entityPrimaryKeyGreaterThanEquals(threshold)
				),
				require(
					page(1, 20),
					entityFetch(
						attributeContent(ATTRIBUTE_CODE)
					)
				)
			)
		);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/PRODUCT/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody(
				"""
					{
						"filterBy": {
							"entityPrimaryKeyGreaterThanEquals": %d
						},
						"require": {
							"page": {
								"number": 1,
								"size": 20
							},
							"entityFetch": {
								"attributeContent": ["code"]
							}
						}
					}
					""",
				threshold
			)
			.executeAndThen()
			.statusCode(200)
			.body(DATA_PATH, equalTo(createEntityDtos(entities)));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return products by primary key less than")
	void shouldReturnProductsByPrimaryKeyLessThan(
		Evita evita, RestTester tester, List<SealedEntity> originalProductEntities
	) {
		final int threshold = originalProductEntities
			.get(originalProductEntities.size() / 2)
			.getPrimaryKeyOrThrowException();

		final List<EntityClassifier> entities = getEntities(
			evita,
			query(
				collection(Entities.PRODUCT),
				filterBy(
					entityPrimaryKeyLessThan(threshold)
				),
				require(
					page(1, 20),
					entityFetch(
						attributeContent(ATTRIBUTE_CODE)
					)
				)
			)
		);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/PRODUCT/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody(
				"""
					{
						"filterBy": {
							"entityPrimaryKeyLessThan": %d
						},
						"require": {
							"page": {
								"number": 1,
								"size": 20
							},
							"entityFetch": {
								"attributeContent": ["code"]
							}
						}
					}
					""",
				threshold
			)
			.executeAndThen()
			.statusCode(200)
			.body(DATA_PATH, equalTo(createEntityDtos(entities)));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return products by primary key less than or equals")
	void shouldReturnProductsByPrimaryKeyLessThanEquals(
		Evita evita, RestTester tester, List<SealedEntity> originalProductEntities
	) {
		final int threshold = originalProductEntities
			.get(originalProductEntities.size() / 2)
			.getPrimaryKeyOrThrowException();

		final List<EntityClassifier> entities = getEntities(
			evita,
			query(
				collection(Entities.PRODUCT),
				filterBy(
					entityPrimaryKeyLessThanEquals(threshold)
				),
				require(
					page(1, 20),
					entityFetch(
						attributeContent(ATTRIBUTE_CODE)
					)
				)
			)
		);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/PRODUCT/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody(
				"""
					{
						"filterBy": {
							"entityPrimaryKeyLessThanEquals": %d
						},
						"require": {
							"page": {
								"number": 1,
								"size": 20
							},
							"entityFetch": {
								"attributeContent": ["code"]
							}
						}
					}
					""",
				threshold
			)
			.executeAndThen()
			.statusCode(200)
			.body(DATA_PATH, equalTo(createEntityDtos(entities)));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return products by primary key between")
	void shouldReturnProductsByPrimaryKeyBetween(
		Evita evita, RestTester tester, List<SealedEntity> originalProductEntities
	) {
		final int from = originalProductEntities
			.get(originalProductEntities.size() / 4)
			.getPrimaryKeyOrThrowException();
		final int to = originalProductEntities
			.get(originalProductEntities.size() / 2)
			.getPrimaryKeyOrThrowException();

		final List<EntityClassifier> entities = getEntities(
			evita,
			query(
				collection(Entities.PRODUCT),
				filterBy(
					entityPrimaryKeyBetween(from, to)
				),
				require(
					page(1, 20),
					entityFetch(
						attributeContent(ATTRIBUTE_CODE)
					)
				)
			)
		);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/PRODUCT/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody(
				"""
					{
						"filterBy": {
							"entityPrimaryKeyBetween": [%d, %d]
						},
						"require": {
							"page": {
								"number": 1,
								"size": 20
							},
							"entityFetch": {
								"attributeContent": ["code"]
							}
						}
					}
					""",
				from,
				to
			)
			.executeAndThen()
			.statusCode(200)
			.body(DATA_PATH, equalTo(createEntityDtos(entities)));
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
			.map(entity -> createEntityDto(new EntityReference(entity.getType(), entity.getPrimaryKey())))
			.toList();

		tester.test(TEST_CATALOG)
			.post("/PRODUCT/query")
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
			.body(DATA_PATH, containsInAnyOrder(expectedBodyOfArchivedEntities.toArray()));
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
			.post("/PRODUCT/query")
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
			.body(DATA_PATH, containsInAnyOrder(expectedBodyOfArchivedEntities.toArray()));
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
			.post("/PRODUCT/query")
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
			.body(DATA_PATH, emptyIterable());
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
			.post("/PRODUCT/query")
			.requestBody(
				"""
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
				liveEntities.get(0).getAttribute(ATTRIBUTE_CODE)
			)
			.executeAndExpectOkAndThen()
			.body(DATA_PATH, containsInAnyOrder(expectedBody.toArray()));
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
			.post("/PRODUCT/query")
			.requestBody(
				"""
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
				archivedEntities.get(1).getPrimaryKey()
			)
			.executeAndExpectOkAndThen()
			.body(DATA_PATH, containsInAnyOrder(expectedBody.toArray()));
	}

	@Test
	@UseDataSet(REST_HUNDRED_ARCHIVED_PRODUCTS_WITH_ARCHIVE)
	@DisplayName("Should require data based on scope")
	void shouldRequireDataBasedOnScope(Evita evita, RestTester tester) {
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
					scope(Scope.LIVE, Scope.ARCHIVED),
					inScope(
						Scope.LIVE,
						priceInPriceLists(PRICE_LIST_VIP, PRICE_LIST_BASIC),
						priceInCurrency(CURRENCY_EUR)
					)
				),
				require(
					inScope(
						Scope.LIVE,
						priceHistogram(5)
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
			.post("/PRODUCT/query")
			.requestBody(
				"""
					{
						"filterBy": {
							"entityPrimaryKeyInSet": [%d, %d, %d, %d],
							"scope": ["LIVE", "ARCHIVED"],
							"inScope": {
								"scope": "LIVE",
								"filtering": [{
									"priceInPriceLists": ["vip", "basic"],
									"priceInCurrency": "EUR"
								}]
							}
						},
						"require": {
							"inScope": {
								"scope": "LIVE",
								"require": {
									"priceHistogram": {
										"requestedBucketCount" : 5
									}
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
			.executeAndExpectOkAndThen()
			.body(DATA_PATH, containsInAnyOrder(expectedBody.toArray()))
			.body(PRICE_HISTOGRAM_RESULTS_PATH, equalTo(createPriceHistogramDto(expectedEntities)));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return products by non-localized attribute")
	void shouldReturnProductsByNonLocalizedAttribute(
		Evita evita, RestTester tester, List<SealedEntity> originalProductEntities
	) {
		final var pks = findEntityPks(
			originalProductEntities,
			it -> it.getAttribute(ATTRIBUTE_NAME, Locale.ENGLISH) != null &&
				it.getAllLocales().contains(CZECH_LOCALE) &&
				it.getAllLocales().contains(Locale.ENGLISH),
			2
		);

		final List<String> codes = getAttributesByPks(evita, pks, ATTRIBUTE_CODE);

		final List<EntityClassifier> entities = getEntities(
			evita,
			query(
				collection(Entities.PRODUCT),
				filterBy(
					attributeInSet(ATTRIBUTE_CODE, codes.toArray(String[]::new)),
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
			.urlPathSuffix("/PRODUCT/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody(
				"""
					               {
						"filterBy": {
						    "attributeCodeInSet": %s,
						    "entityLocaleEquals": "en"
						},
						"require": {
						    "entityFetch": {
						        "attributeContent": ["code", "name"]
						    }
					    }
					}
					""",
				serializeStringArrayToQueryString(codes)
			)
			.executeAndThen()
			.statusCode(200)
			.body(DATA_PATH, equalTo(createEntityDtos(entities)));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return products by localized attribute")
	void shouldReturnProductsByLocalizedAttribute(
		Evita evita, RestTester tester, List<SealedEntity> originalProductEntities
	) {
		final var pks = findEntityPks(
			originalProductEntities,
			it -> it.getAttribute(ATTRIBUTE_URL, Locale.ENGLISH) != null &&
				it.getAttribute(ATTRIBUTE_NAME, Locale.ENGLISH) != null,
			2
		);

		final List<String> urls = getAttributesByPks(evita, pks, ATTRIBUTE_URL, Locale.ENGLISH);

		final List<EntityClassifier> entities = getEntities(
			evita,
			query(
				collection(Entities.PRODUCT),
				filterBy(
					attributeInSet(ATTRIBUTE_URL, urls.toArray(String[]::new)),
					entityLocaleEquals(Locale.ENGLISH)
				),
				require(
					entityFetch(
						attributeContent(ATTRIBUTE_URL, ATTRIBUTE_NAME)
					)
				)
			)
		);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/PRODUCT/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody(
				"""
					               {
						"filterBy": {
							"attributeUrlInSet": %s,
						    "entityLocaleEquals": "en"
						},
						"require": {
					        "entityFetch": {
					            "attributeContent": ["url", "name"]
					        }
					    }
					}
					""",
				serializeStringArrayToQueryString(urls)
			)
			.executeAndThen()
			.statusCode(200)
			.body(DATA_PATH, equalTo(createEntityDtos(entities)));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return products by localized attribute with locale in URL")
	void shouldReturnProductsByLocalizedAttributeWithLocaleInUrl(
		Evita evita, RestTester tester, List<SealedEntity> originalProductEntities
	) {
		final var pks = findEntityPks(
			originalProductEntities,
			it -> it.getAttribute(ATTRIBUTE_URL, Locale.ENGLISH) != null &&
				it.getAttribute(ATTRIBUTE_NAME, Locale.ENGLISH) != null,
			2
		);

		final List<String> urls = getAttributesByPks(evita, pks, ATTRIBUTE_URL, Locale.ENGLISH);

		final List<EntityClassifier> entities = getEntities(
			evita,
			query(
				collection(Entities.PRODUCT),
				filterBy(
					attributeInSet(ATTRIBUTE_URL, urls.toArray(String[]::new)),
					entityLocaleEquals(Locale.ENGLISH)
				),
				require(
					entityFetch(
						attributeContent(ATTRIBUTE_URL, ATTRIBUTE_NAME)
					)
				)
			)
		);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/" + Locale.ENGLISH.toLanguageTag() + "/PRODUCT/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody(
				"""
					               {
						"filterBy": {
						    "attributeUrlInSet": %s
						},
						"require": {
						    "entityFetch": {
						        "attributeContent": ["url", "name"]
						    }
					    }
					}
					""",
				serializeStringArrayToQueryString(urls)
			)
			.executeAndThen()
			.statusCode(200)
			.body(DATA_PATH, equalTo(createEntityDtos(entities, true)));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should fail when locale is both in body and in URL")
	void shouldFailWhenLocaleIsBothInBodyAndInUrl(RestTester tester, List<SealedEntity> originalProductEntities) {
		tester.test(TEST_CATALOG)
			.urlPathSuffix("/" + Locale.ENGLISH.toLanguageTag() + "/PRODUCT/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody("""
				             {
				             	"filterBy": {
				             		"attributeUrlInSet": ["some_url"],
				             		"entityLocaleEquals": "en"
				             	}
				             }
				             """)
			.executeAndThen()
			.statusCode(400)
			.body("message", notNullValue());
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return error for invalid argument in products query")
	void shouldReturnErrorForInvalidArgumentInProductsQuery(RestTester tester) {
		tester.test(TEST_CATALOG)
			.urlPathSuffix("/PRODUCT/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody("""
				             {
				             	"filterBy": {
				             		"attributeUrlInSet": ["xxx"]
				             	},
				             	"require": {
				             		"entityFetch_xxx": {
				             			"attributeContent": ["url", "name"]
				             		}
				             	}
				             }
				             """)
			.executeAndThen()
			.statusCode(400)
			.body("message", notNullValue());
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return error for invalid query when single value is sent instead of array.")
	void shouldReturnErrorForInvalidQueryWhenSingleValueIsSentInsteadOfArray(
		RestTester tester, List<SealedEntity> originalProductEntities
	) {
		final var pks = findEntityWithPricePks(originalProductEntities, 2);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/PRODUCT/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody(
				"""
					{
						"filterBy": {
							"entityPrimaryKeyInSet": %d
						}
					}
					""",
				pks[0]
			)
			.executeAndThen()
			.statusCode(400)
			.body("message", notNullValue());
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
			c -> {
				// check that it has at least 2 parents
				assertTrue(c.getParentEntity().isPresent());
				assertTrue(c.getParentEntity().get().getParentEntity().isPresent());
			},
			SealedEntity.class
		);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/CATEGORY/query")
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
			.body(DATA_PATH, equalTo(createEntityDtos(categories)));
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
			c -> {
				// check that it has at least 2 parents
				assertTrue(c.getParentEntity().isPresent());
				assertTrue(c.getParentEntity().get().getParentEntity().isPresent());
			},
			SealedEntity.class
		);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/CATEGORY/query")
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
			.body(DATA_PATH, equalTo(createEntityDtos(categories)));
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
			c -> {
				// check that it has only one direct parent
				assertTrue(c.getParentEntity().isPresent());
				assertTrue(c.getParentEntity().get().getParentEntity().isEmpty());
			},
			SealedEntity.class
		);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/CATEGORY/query")
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
			.body(DATA_PATH, equalTo(createEntityDtos(categories)));
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
			p -> {
				// check that it has at least 2 referenced parents
				assertTrue(p.getReferences(Entities.CATEGORY)
					           .iterator()
					           .next()
					           .getReferencedEntity()
					           .orElseThrow()
					           .getParentEntity()
					           .orElseThrow()
					           .getParentEntity()
					           .isPresent());
			},
			SealedEntity.class
		);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/PRODUCT/query")
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
			.body(DATA_PATH, equalTo(createEntityDtos(products)));
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
			p -> {
				// check that it has at least 2 referenced parents
				assertTrue(p.getReferences(Entities.CATEGORY)
					           .iterator()
					           .next()
					           .getReferencedEntity()
					           .orElseThrow()
					           .getParentEntity()
					           .orElseThrow()
					           .getParentEntity()
					           .isPresent());
			},
			SealedEntity.class
		);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/PRODUCT/query")
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
			.body(DATA_PATH, equalTo(createEntityDtos(products)));
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
			p -> {
				// check that it has only one referenced parents
				assertTrue(p.getReferences(Entities.CATEGORY)
					           .iterator()
					           .next()
					           .getReferencedEntity()
					           .orElseThrow()
					           .getParentEntity()
					           .orElseThrow()
					           .getParentEntity()
					           .isEmpty());
			},
			SealedEntity.class
		);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/PRODUCT/query")
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
			.body(DATA_PATH, equalTo(createEntityDtos(products)));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return reference page for products")
	void shouldReturnReferencePageForProducts(
		Evita evita, RestTester tester, List<SealedEntity> originalProductEntities) {
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
			.urlPathSuffix("/PRODUCT/query")
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
			.body(DATA_PATH, equalTo(createEntityDtos(products)));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return reference strip for products")
	void shouldReturnReferenceStripForProducts(
		Evita evita, RestTester tester, List<SealedEntity> originalProductEntities
	) {
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
			.urlPathSuffix("/PRODUCT/query")
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
			.body(DATA_PATH, equalTo(createEntityDtos(products)));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should filter by and return price for sale for multiple products")
	void shouldFilterByAndReturnPriceForSaleForMultipleProducts(
		Evita evita, RestTester tester, List<SealedEntity> originalProductEntities
	) {
		final var pks = findEntityWithPricePks(originalProductEntities, 2);

		final List<EntityClassifier> entities = getEntities(
			evita,
			query(
				collection(Entities.PRODUCT),
				filterBy(
					entityPrimaryKeyInSet(pks),
					priceInCurrency(CURRENCY_CZK),
					priceInPriceLists(PRICE_LIST_BASIC)
				),
				require(
					entityFetch(
						priceContentRespectingFilter()
					)
				)
			)
		);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/PRODUCT/query")
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
				serializeIntArrayToQueryString(pks)
			)
			.executeAndThen()
			.statusCode(200)
			.body(DATA_PATH, equalTo(createEntityDtos(entities)));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return all prices for sale for master products")
	void shouldReturnAllPricesForSaleForMasterProducts(
		Evita evita, RestTester tester, List<SealedEntity> originalProductEntities
	) {
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
			.urlPathSuffix("/PRODUCT/query")
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
			.body(DATA_PATH, equalTo(createEntityDtos(entities)));
	}

	@Test
	@Tag(PRICE)
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return price for sale range for products with NONE inner record handling")
	void shouldReturnPriceForSaleRangeForNoneInnerRecordHandling(
		Evita evita, RestTester tester, List<SealedEntity> originalProductEntities
	) {
		final Integer[] pks = findEntityPks(
			originalProductEntities,
			it -> it.getPriceInnerRecordHandling().equals(PriceInnerRecordHandling.NONE) &&
				it.getPrices(CURRENCY_CZK, PRICE_LIST_BASIC).size() == 1,
			2
		);

		final List<EntityClassifier> entities = getEntities(
			evita,
			query(
				collection(Entities.PRODUCT),
				filterBy(
					entityPrimaryKeyInSet(pks),
					priceInCurrency(CURRENCY_CZK),
					priceInPriceLists(PRICE_LIST_BASIC)
				),
				require(
					entityFetch(
						priceContentRespectingFilter()
					)
				)
			)
		);

		// sanity: ensure SDK reports the expected NONE-strategy collapse so the test asserts something meaningful
		entities.forEach(classifier -> {
			final SealedEntity entity = (SealedEntity) classifier;
			final PriceRangeForSale range = entity.getPriceRangeForSale().orElseThrow();
			assertEqualPrices(range.lowestPrice(), range.priceForSale());
			assertEqualPrices(range.highestPrice(), range.priceForSale());
		});

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/PRODUCT/query")
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
				serializeIntArrayToQueryString(pks)
			)
			.executeAndThen()
			.statusCode(200)
			.body(DATA_PATH, equalTo(createEntityDtos(entities)))
			.body(
				DATA_PATH + "[0]." + EntityDescriptor.PRICE_FOR_SALE_MIN.name(),
				notNullValue()
			)
			.body(
				DATA_PATH + "[0]." + EntityDescriptor.PRICE_FOR_SALE_MAX.name(),
				notNullValue()
			);
	}

	@Test
	@Tag(PRICE)
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return price for sale range for master products with LOWEST_PRICE inner record handling")
	void shouldReturnPriceForSaleRangeForLowestPriceInnerRecordHandling(
		Evita evita, RestTester tester, List<SealedEntity> originalProductEntities
	) {
		final Integer[] pks = findEntityPks(
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

		final Set<Integer> pksSet = Arrays.stream(pks).collect(Collectors.toSet());
		final List<String> priceLists = originalProductEntities.stream()
			.filter(it -> pksSet.contains(it.getPrimaryKey()))
			.flatMap(it -> it.getPrices(CURRENCY_CZK).stream().map(PriceContract::priceList))
			.distinct()
			.toList();

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

		// sanity: ensure SDK reports lowest == priceForSale and highest >= priceForSale for LOWEST_PRICE strategy
		entities.forEach(classifier -> {
			final SealedEntity entity = (SealedEntity) classifier;
			final PriceRangeForSale range = entity.getPriceRangeForSale().orElseThrow();
			assertEqualPrices(range.lowestPrice(), range.priceForSale());
			assertTrue(
				range.highestPrice().priceWithTax().compareTo(range.lowestPrice().priceWithTax()) >= 0,
				"Highest price must be greater than or equal to the lowest price for LOWEST_PRICE strategy."
			);
		});

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/PRODUCT/query")
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
			.body(DATA_PATH, equalTo(createEntityDtos(entities)))
			.body(
				DATA_PATH + "[0]." + EntityDescriptor.PRICE_FOR_SALE_MIN.name(),
				notNullValue()
			)
			.body(
				DATA_PATH + "[0]." + EntityDescriptor.PRICE_FOR_SALE_MAX.name(),
				notNullValue()
			);
	}

	@Test
	@Tag(PRICE)
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return price for sale range for master products with SUM inner record handling")
	void shouldReturnPriceForSaleRangeForSumInnerRecordHandling(
		Evita evita, RestTester tester, List<SealedEntity> originalProductEntities
	) {
		final Integer[] pks = findEntityPks(
			originalProductEntities,
			it -> it.getPriceInnerRecordHandling().equals(PriceInnerRecordHandling.SUM) &&
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

		// sanity: ensure SDK reports component prices for SUM strategy bounded by priceForSale
		entities.forEach(classifier -> {
			final SealedEntity entity = (SealedEntity) classifier;
			final PriceRangeForSale range = entity.getPriceRangeForSale().orElseThrow();
			assertTrue(
				range.lowestPrice().priceWithTax().compareTo(range.highestPrice().priceWithTax()) <= 0,
				"Lowest price must be less than or equal to the highest price for SUM strategy."
			);
			assertTrue(
				range.priceForSale().priceWithTax().compareTo(range.highestPrice().priceWithTax()) >= 0,
				"Cumulated SUM price must be at least as large as the highest component price."
			);
		});

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/PRODUCT/query")
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
			.body(DATA_PATH, equalTo(createEntityDtos(entities)))
			.body(
				DATA_PATH + "[0]." + EntityDescriptor.PRICE_FOR_SALE_MIN.name(),
				notNullValue()
			)
			.body(
				DATA_PATH + "[0]." + EntityDescriptor.PRICE_FOR_SALE_MAX.name(),
				notNullValue()
			);
	}

	/**
	 * Asserts the two price contracts represent the same price by comparing their identity tuple
	 * (priceId, priceList, currency, innerRecordId).
	 */
	private static void assertEqualPrices(
		@Nonnull PriceContract expected, @Nonnull PriceContract actual
	) {
		assertTrue(
			expected.priceId() == actual.priceId() &&
				expected.priceList().equals(actual.priceList()) &&
				expected.currency().equals(actual.currency()) &&
				Objects.equals(expected.innerRecordId(), actual.innerRecordId()),
			"Expected the same price (priceId/priceList/currency/innerRecordId), but got `" +
				expected + "` vs `" + actual + "`."
		);
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return default and custom accompanying prices for products")
	void shouldReturnDefaultAndCustomAccompanyingPricesForProducts(
		Evita evita, RestTester tester, List<SealedEntity> originalProductEntities
	) {
		final List<Integer> desiredEntities = originalProductEntities.stream()
			.filter(entity ->
				        entity.getPriceInnerRecordHandling().equals(PriceInnerRecordHandling.NONE) &&
					        entity.getPrices().stream().map(PriceContract::currency).anyMatch(CURRENCY_EUR::equals) &&
					        entity.getPrices()
						        .stream()
						        .map(PriceContract::priceList)
						        .anyMatch(PRICE_LIST_BASIC::equals) &&
					        entity.getPrices()
						        .stream()
						        .map(PriceContract::priceList)
						        .anyMatch(PRICE_LIST_REFERENCE::equals) &&
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
			.urlPathSuffix("/PRODUCT/query")
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
			.body(DATA_PATH, equalTo(createEntityDtos(entities)));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should filter products by non-existent price")
	void shouldFilterProductsByNonExistentPrice(
		Evita evita, RestTester tester, List<SealedEntity> originalProductEntities
	) {
		final var pks = findEntityWithPricePks(originalProductEntities, 2);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/PRODUCT/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody(
				"""
					               {
						"filterBy": {
						  "entityPrimaryKeyInSet": %s,
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
				serializeIntArrayToQueryString(pks)
			)
			.executeAndThen()
			.statusCode(200)
			.body(DATA_PATH, hasSize(0));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return error for filtering products by unknown currency")
	void shouldReturnErrorForFilteringProductsByUnknownCurrency(
		RestTester tester, List<SealedEntity> originalProductEntities
	) {
		final var pks = findEntityWithPricePks(originalProductEntities, 2);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/PRODUCT/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody(
				"""
					               {
						"filterBy": {
						    "entityPrimaryKeyInSet": %s,
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
				serializeIntArrayToQueryString(pks)
			)
			.executeAndThen()
			.statusCode(400)
			.body("message", notNullValue());
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return custom price for sale for products")
	void shouldReturnCustomPriceForSaleForProducts(
		Evita evita, RestTester tester, List<SealedEntity> originalProductEntities
	) {
		final var pks = findEntityWithPricePks(originalProductEntities, 2);

		final List<EntityClassifier> entities = getEntities(
			evita,
			query(
				collection(Entities.PRODUCT),
				filterBy(
					entityPrimaryKeyInSet(pks),
					priceInCurrency(CURRENCY_CZK),
					priceInPriceLists(PRICE_LIST_BASIC)
				),
				require(
					entityFetch(
						priceContentRespectingFilter()
					)
				)
			)
		);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/PRODUCT/query")
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
				serializeIntArrayToQueryString(pks)
			)
			.executeAndThen()
			.statusCode(200)
			.body(DATA_PATH, equalTo(createEntityDtos(entities)));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return associated data")
	void shouldReturnAssociatedData(Evita evita, RestTester tester, List<SealedEntity> originalProductEntities) {
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
			.urlPathSuffix("/PRODUCT/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody(
				"""
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
				serializeIntArrayToQueryString(pks)
			)
			.executeAndThen()
			.statusCode(200)
			.body(DATA_PATH, equalTo(createEntityDtos(entities)));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return associated data with locale in URL")
	void shouldReturnAssociatedDataWithLocaleInUrl(
		Evita evita, RestTester tester, List<SealedEntity> originalProductEntities
	) {
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
			.urlPathSuffix("/" + Locale.ENGLISH.toLanguageTag() + "/PRODUCT/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody(
				"""
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
				serializeIntArrayToQueryString(pks)
			)
			.executeAndThen()
			.statusCode(200)
			.body(DATA_PATH, equalTo(createEntityDtos(entities, true)));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return single reference for products")
	void shouldReturnSingleReferenceForProducts(
		Evita evita, RestTester tester, List<SealedEntity> originalProductEntities
	) {
		final var pks = findEntityPks(
			originalProductEntities,
			it -> it.getReferences(Entities.BRAND).size() == 1 &&
				it.getReferences(Entities.BRAND)
					.iterator()
					.next()
					.getAttribute(TestDataGenerator.ATTRIBUTE_MARKET_SHARE) != null,
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
						referenceContent(
							Entities.BRAND,
							entityFetch()
						)
					)
				)
			)
		);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/PRODUCT/query")
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
				serializeIntArrayToQueryString(pks)
			)
			.executeAndThen()
			.statusCode(200)
			.body(DATA_PATH, equalTo(createEntityDtos(entities)));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return reference list for products")
	void shouldReturnReferenceListForProducts(
		Evita evita, RestTester tester, List<SealedEntity> originalProductEntities) {
		final var pks = findEntityPks(
			originalProductEntities,
			it -> it.getReferences(Entities.STORE).size() > 1,
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
						referenceContent(
							Entities.STORE,
							entityFetch()
						)
					)
				)
			)
		);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/PRODUCT/query")
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
					                "entityFetch": {}
						        }
						    }
					    }
					}
					""",
				serializeIntArrayToQueryString(pks)
			)
			.executeAndThen()
			.statusCode(200)
			.body(DATA_PATH, equalTo(createEntityDtos(entities)));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return filtered and ordered reference list for products")
	void shouldReturnFilteredAndOrderedReferenceListForProducts(
		Evita evita, RestTester tester,
		List<SealedEntity> originalProductsEntities, List<SealedEntity> originalStoreEntities
	) {
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

		final List<EntityClassifier> entities = getEntities(
			evita,
			query(
				collection(Entities.PRODUCT),
				filterBy(
					entityPrimaryKeyInSet(productsWithLotsOfStores.keySet().toArray(Integer[]::new)),
					entityLocaleEquals(CZECH_LOCALE)
				),
				require(
					page(1, Integer.MAX_VALUE),
					entityFetch(
						referenceContent(
							Entities.STORE,
							filterBy(
								entityHaving(
									attributeInSet(ATTRIBUTE_CODE, randomStores)
								)
							),
							orderBy(
								entityProperty(
									attributeNatural(ATTRIBUTE_NAME, DESC)
								)
							)
						)
					)
				)
			)
		);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/PRODUCT/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody(
				"""
					             {
					                "filterBy": {
					                    "entityPrimaryKeyInSet": %s,
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
					                                 "attributeCodeInSet": %s
					                             }
					                         },
					                         "orderBy": [{
					                             "entityProperty": [{
					                                 "attributeNameNatural": "DESC"
					                             }]
					                         }]
					                     }
					                    }
					                }
					             }
					""",
				serializeIntArrayToQueryString(productsWithLotsOfStores.keySet().toArray(Integer[]::new)),
				Integer.MAX_VALUE,
				serializeStringArrayToQueryString(randomStores)
			)
			.executeAndThen()
			.statusCode(200)
			.body(DATA_PATH, equalTo(createEntityDtos(entities)));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should find product by complex query")
	void shouldFindProductByComplexQuery(Evita evita, RestTester tester, List<SealedEntity> originalProductEntities) {
		final Random rnd = new Random(SEED);
		final List<SealedEntity> withTrueAlias = originalProductEntities.stream()
			.filter(it -> Objects.equals(Boolean.TRUE, it.getAttribute(ATTRIBUTE_ALIAS))
				&& it.getAttribute(ATTRIBUTE_PRIORITY) != null)
			.filter(it -> rnd.nextInt(100) > 85)
			.limit(2)
			.toList();
		final List<SealedEntity> withFalseAlias = originalProductEntities.stream()
			.filter(it -> Objects.equals(Boolean.FALSE, it.getAttribute(ATTRIBUTE_ALIAS))
				&& it.getAttribute(ATTRIBUTE_CODE) != null
				&& it.getAttribute(ATTRIBUTE_PRIORITY) != null)
			.filter(it -> rnd.nextInt(100) > 85)
			.limit(5)
			.toList();

		final Integer[] expectedEntities = getEntities(
			evita,
			query(
				collection(Entities.PRODUCT),
				filterBy(
					and(
						or(
							and(
								attributeEquals(ATTRIBUTE_ALIAS, withTrueAlias.get(0).getAttribute(ATTRIBUTE_ALIAS)),
								attributeEquals(
									ATTRIBUTE_PRIORITY, withTrueAlias.get(0).getAttribute(ATTRIBUTE_PRIORITY))
							),
							and(
								attributeEquals(ATTRIBUTE_ALIAS, withTrueAlias.get(1).getAttribute(ATTRIBUTE_ALIAS)),
								attributeEquals(
									ATTRIBUTE_PRIORITY, withTrueAlias.get(1).getAttribute(ATTRIBUTE_PRIORITY))
							),
							and(
								attributeEquals(ATTRIBUTE_ALIAS, false),
								attributeInSet(
									ATTRIBUTE_PRIORITY,
									withFalseAlias.get(0).getAttribute(ATTRIBUTE_PRIORITY),
									withFalseAlias.get(1).getAttribute(ATTRIBUTE_PRIORITY),
									withFalseAlias.get(2).getAttribute(ATTRIBUTE_PRIORITY),
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
			)
		)
			.stream()
			.map(EntityClassifier::getPrimaryKey)
			.toArray(Integer[]::new);

		assertTrue(expectedEntities.length > 0);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/PRODUCT/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody(
				"""
					{
						"filterBy": {
							"or": [
								{
									"attributeAliasEquals": %b,
									"attributePriorityEquals": "%s"
								},
								{
									"attributeAliasEquals": %b,
									"attributePriorityEquals": "%s"
								},
								{
									"attributeAliasEquals": false,
									"attributePriorityInSet": ["%s", "%s", "%s", "%s"]
								}
							],
							"not": {
								"attributeCodeEquals": "%s"
							}
						},
						"require": {
							"strip": {
								"limit": %d
							}
						}
					}
					""",
				withTrueAlias.get(0).getAttribute(ATTRIBUTE_ALIAS),
				withTrueAlias.get(0).getAttribute(ATTRIBUTE_PRIORITY),
				withTrueAlias.get(1).getAttribute(ATTRIBUTE_ALIAS),
				withTrueAlias.get(1).getAttribute(ATTRIBUTE_PRIORITY),
				withFalseAlias.get(0).getAttribute(ATTRIBUTE_PRIORITY),
				withFalseAlias.get(1).getAttribute(ATTRIBUTE_PRIORITY),
				withFalseAlias.get(2).getAttribute(ATTRIBUTE_PRIORITY),
				withFalseAlias.get(3).getAttribute(ATTRIBUTE_PRIORITY),
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
	@DisplayName("Should not allow defining multiple order constraints in one container")
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	void shouldNotAllowDefiningMultipleOrderConstraintsInOneContainer(Evita evita, RestTester tester) {
		tester.test(TEST_CATALOG)
			.urlPathSuffix("/PRODUCT/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody("""
				             {
				             	"filterBy": {
				             		"priceInPriceLists": ["basic"],
				             		"priceInCurrency": "CZK",
				             		"priceValidInNow": true
				             	},
				             	"orderBy": [{
				             		"priceNatural": "DESC",
				             		"attributeCodeNatural": "ASC"
				             	}]
				             }
				             """)
			.executeAndExpectBadRequestAndThen();

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/PRODUCT/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody("""
				             {
				             	"filterBy": {
				             		"priceInPriceLists": ["basic"],
				             		"priceInCurrency": "CZK",
				             		"priceValidInNow": true
				             	},
				             	"orderBy": [
				             		{
				             			"priceNatural": "DESC"
				             		},
				             		{
				             			"attributeCodeNatural": "ASC"
				             		}
				             	]
				             }
				             """)
			.executeAndExpectOkAndThen();
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should order entities by complex query")
	void shouldOrderEntitiesByComplexQuery(Evita evita, RestTester tester) {
		final Integer[] expectedEntities = getEntities(
			evita,
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
			)
		)
			.stream()
			.map(EntityClassifier::getPrimaryKey)
			.toArray(Integer[]::new);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/PRODUCT/query")
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
				             		"strip": {
				             			"limit": 30
				             		}
				             	}
				             }
				             """)
			.executeAndThen()
			.statusCode(200)
			.body(
				DATA_PATH + "." + EntityDescriptor.PRIMARY_KEY.name(),
				contains(expectedEntities)
			);
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
			.urlPathSuffix("/PRODUCT/query")
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
				resultPath(DATA_PATH, EntityDescriptor.PRIMARY_KEY.name()),
				contains(expectedEntities)
			);
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return page of entities")
	void shouldReturnPageOfEntities(Evita evita, RestTester tester) {
		final List<Integer> expectedEntities = getEntities(
			evita,
			query(
				collection(Entities.PRODUCT),
				filterBy(
					attributeLessThan(ATTRIBUTE_PRIORITY, 35000L)
				),
				require(
					page(1, Integer.MAX_VALUE)
				)
			)
		)
			.stream()
			.map(EntityClassifier::getPrimaryKey)
			.toList();
		assertTrue(expectedEntities.size() > 10);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/PRODUCT/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody("""
				             {
				             	"filterBy": {
				             		"attributePriorityLessThan": 35000
				             	},
				             	"require": {
				             		"page": {
				             			"number": 2,
				             			"size": 3
				             		}
				             	}
				             }
				             """)
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
		final List<Integer> expectedEntities = getEntities(
			evita,
			query(
				collection(Entities.PRODUCT),
				filterBy(
					attributeLessThan(ATTRIBUTE_PRIORITY, 35000L)
				),
				require(
					page(1, Integer.MAX_VALUE)
				)
			)
		)
			.stream()
			.map(EntityClassifier::getPrimaryKey)
			.toList();
		assertTrue(expectedEntities.size() > 10);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/PRODUCT/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody("""
				             {
				             	"filterBy": {
				             		"attributePriorityLessThan": 35000
				             	},
				             	"require": {
				             		"strip": {
				             			"offset": 2,
				             			"limit": 3
				             		}
				             	}
				             }
				             """)
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
		final EvitaResponse<EntityClassifier> response = queryEntities(
			evita,
			query(
				collection(Entities.PRODUCT),
				filterBy(
					attributeIsNotNull(ATTRIBUTE_ALIAS)
				),
				require(
					page(1, Integer.MAX_VALUE),
					attributeHistogram(20, ATTRIBUTE_QUANTITY)
				)
			)
		);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/PRODUCT/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody(
				"""
					{
						"require": {
							"page": {
								"number": 1,
								"size": %d
							},
							"attributeHistogram": {
								"requestedBucketCount": 20,
								"attributeNames": ["%s"]
							}
						}
					}
					""",
				Integer.MAX_VALUE,
				ATTRIBUTE_QUANTITY
			)
			.executeAndThen()
			.statusCode(200)
			.body(
				resultPath(
					ResponseDescriptor.EXTRA_RESULTS, ExtraResultsDescriptor.ATTRIBUTE_HISTOGRAM, ATTRIBUTE_QUANTITY),
				equalTo(createAttributeHistogramDto(response, ATTRIBUTE_QUANTITY))
			);
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return optimized attribute histogram")
	void shouldReturnOptimizedAttributeHistogram(Evita evita, RestTester tester) {
		final EvitaResponse<EntityClassifier> response = queryEntities(
			evita,
			query(
				collection(Entities.PRODUCT),
				filterBy(
					attributeIsNotNull(ATTRIBUTE_ALIAS)
				),
				require(
					page(1, Integer.MAX_VALUE),
					attributeHistogram(20, HistogramBehavior.OPTIMIZED, ATTRIBUTE_QUANTITY)
				)
			)
		);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/PRODUCT/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody(
				"""
					{
						"require": {
							"page": {
								"number": 1,
								"size": %d
							},
							"attributeHistogram": {
								"requestedBucketCount": 20,
								"behavior": "OPTIMIZED",
								"attributeNames": ["%s"]
							}
						}
					}
					""",
				Integer.MAX_VALUE,
				ATTRIBUTE_QUANTITY
			)
			.executeAndThen()
			.statusCode(200)
			.body(
				resultPath(
					ResponseDescriptor.EXTRA_RESULTS, ExtraResultsDescriptor.ATTRIBUTE_HISTOGRAM, ATTRIBUTE_QUANTITY),
				equalTo(createAttributeHistogramDto(response, ATTRIBUTE_QUANTITY))
			);
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return attribute histogram without being affected by attribute filter")
	void shouldReturnAttributeHistogramWithoutBeingAffectedByAttributeFilter(Evita evita, RestTester tester) {
		final EvitaResponse<EntityClassifier> response = queryEntities(
			evita,
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
			)
		);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/PRODUCT/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody(
				"""
					{
						"filterBy": {
							"userFilter": [{
								"attributeQuantityBetween": ["100", "900"]
							}]
						},
						"require": {
							"page": {
								"number": 1,
								"size": %d
							},
							"attributeHistogram": {
								"requestedBucketCount": 20,
								"attributeNames": ["%s"]
							}
						}
					}
					""",
				Integer.MAX_VALUE,
				ATTRIBUTE_QUANTITY
			)
			.executeAndThen()
			.statusCode(200)
			.body(
				resultPath(
					ResponseDescriptor.EXTRA_RESULTS, ExtraResultsDescriptor.ATTRIBUTE_HISTOGRAM, ATTRIBUTE_QUANTITY),
				equalTo(createAttributeHistogramDto(response, ATTRIBUTE_QUANTITY))
			);
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return error for missing attribute histogram buckets count")
	void shouldReturnErrorForMissingAttributeHistogramBucketsCount(RestTester tester) {
		tester.test(TEST_CATALOG)
			.urlPathSuffix("/PRODUCT/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody(
				"""
					{
						"require": {
							"page": {
								"number": 1,
								"size": %d
							},
							"attributeHistogram": {
								"attributeNames": ["%s"]
							}
						}
					}
					""",
				Integer.MAX_VALUE,
				ATTRIBUTE_QUANTITY
			)
			.executeAndThen()
			.statusCode(400)
			.body(
				"message",
				equalTo("Constraint `attributeHistogram` requires parameter `requestedBucketCount` to be non-null.")
			);
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return price histogram without being affected by price filter")
	void shouldReturnPriceHistogramWithoutBeingAffectedByPriceFilter(Evita evita, RestTester tester) {
		final BigDecimal from = new BigDecimal("80");
		final BigDecimal to = new BigDecimal("150");
		final EvitaResponse<EntityClassifier> response = queryEntities(
			evita,
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
			)
		);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/PRODUCT/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody(
				"""
					{
						"filterBy": {
							"priceInCurrency": "EUR",
							"priceInPriceLists": ["vip", "basic"],
							"userFilter": [{
								"priceBetween": ["80", "150"]
							}]
						},
						"require": {
							"page": {
								"number": 1,
								"size": %d
							},
							"priceHistogram": {
								"requestedBucketCount": 20
							}
						}
					}
					""",
				Integer.MAX_VALUE
			)
			.executeAndThen()
			.statusCode(200)
			.body(
				resultPath(ResponseDescriptor.EXTRA_RESULTS, ExtraResultsDescriptor.PRICE_HISTOGRAM),
				equalTo(createPriceHistogramDto(response))
			);
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return price histogram")
	void shouldReturnPriceHistogram(Evita evita, RestTester tester) {
		final EvitaResponse<EntityClassifier> response = queryEntities(
			evita,
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
			)
		);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/PRODUCT/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody(
				"""
					{
						"filterBy": {
							"priceInCurrency": "EUR",
							"priceInPriceLists": ["vip", "basic"]
						},
						"require": {
							"page": {
								"number": 1,
								"size": %d
							},
							"priceHistogram": {
								"requestedBucketCount": 20
							}
						}
					}
					""",
				Integer.MAX_VALUE
			)
			.executeAndThen()
			.statusCode(200)
			.body(
				resultPath(ResponseDescriptor.EXTRA_RESULTS, ExtraResultsDescriptor.PRICE_HISTOGRAM),
				equalTo(createPriceHistogramDto(response))
			);
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return optimized price histogram")
	void shouldReturnOptimizedPriceHistogram(Evita evita, RestTester tester) {
		final EvitaResponse<EntityClassifier> response = queryEntities(
			evita,
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
			)
		);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/PRODUCT/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody(
				"""
					{
						"filterBy": {
							"priceInCurrency": "EUR",
							"priceInPriceLists": ["vip", "basic"]
						},
						"require": {
							"page": {
								"number": 1,
								"size": %d
							},
							"priceHistogram": {
								"requestedBucketCount": 20,
								"behavior": "OPTIMIZED"
							}
						}
					}
					""",
				Integer.MAX_VALUE
			)
			.executeAndThen()
			.statusCode(200)
			.body(
				resultPath(ResponseDescriptor.EXTRA_RESULTS, ExtraResultsDescriptor.PRICE_HISTOGRAM),
				equalTo(createPriceHistogramDto(response))
			);
	}

	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return self hierarchy from root")
	@ParameterizedTest
	@MethodSource("statisticTypeAndBaseVariants")
	void shouldReturnSelfHierarchyFromRoot(
		EnumSet<StatisticsType> statisticsType, StatisticsBase base, Evita evita, RestTester tester
	) {
		final Hierarchy hierarchy = createExpectedSelfHierarchy(
			evita,
			entityLocaleEquals(CZECH_LOCALE),
			fromRoot(
				"megaMenu",
				entityFetch(attributeContent(ATTRIBUTE_CODE)),
				stopAt(distance(2)),
				statisticsType.isEmpty() ? new HierarchyStatistics(base) :
					new HierarchyStatistics(base, statisticsType.toArray(StatisticsType[]::new))
			)
		);
		final List<Map<String, Object>> megaMenuDto = createHierarchyDto(hierarchy.getSelfHierarchy("megaMenu"));

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
	void shouldReturnSelfHierarchyFromNode(
		EnumSet<StatisticsType> statisticsType, StatisticsBase base, Evita evita, RestTester tester
	) {
		final Hierarchy hierarchy = createExpectedSelfHierarchy(
			evita,
			and(
				entityLocaleEquals(CZECH_LOCALE),
				hierarchyWithinSelf(entityPrimaryKeyInSet(6))
			),
			fromNode(
				"megaMenu",
				node(filterBy(entityPrimaryKeyInSet(2))),
				entityFetch(attributeContent(ATTRIBUTE_CODE)),
				stopAt(distance(2)),
				statisticsType.isEmpty() ? new io.evitadb.api.query.require.HierarchyStatistics(base) :
					new io.evitadb.api.query.require.HierarchyStatistics(
						base, statisticsType.toArray(StatisticsType[]::new))
			)
		);
		final List<Map<String, Object>> megaMenuDto = createHierarchyDto(hierarchy.getSelfHierarchy("megaMenu"));

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
	void shouldReturnSelfHierarchyChildren(
		EnumSet<StatisticsType> statisticsType, StatisticsBase base, Evita evita, RestTester tester
	) {
		final Hierarchy hierarchy = createExpectedSelfHierarchy(
			evita,
			and(
				entityLocaleEquals(CZECH_LOCALE),
				hierarchyWithinSelf(entityPrimaryKeyInSet(1))
			),
			children(
				"megaMenu",
				entityFetch(attributeContent(ATTRIBUTE_CODE)),
				stopAt(distance(1)),
				statisticsType.isEmpty() ? new io.evitadb.api.query.require.HierarchyStatistics(base) :
					new io.evitadb.api.query.require.HierarchyStatistics(
						base, statisticsType.toArray(StatisticsType[]::new))
			)
		);
		final List<Map<String, Object>> megaMenuDto = createHierarchyDto(hierarchy.getSelfHierarchy("megaMenu"));

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
	void shouldReturnSelfHierarchyParentsWithoutSiblings(
		EnumSet<StatisticsType> statisticsType, StatisticsBase base, Evita evita, RestTester tester
	) {
		final Hierarchy hierarchy = createExpectedSelfHierarchy(
			evita,
			and(
				entityLocaleEquals(CZECH_LOCALE),
				hierarchyWithinSelf(entityPrimaryKeyInSet(30))
			),
			parents(
				"megaMenu",
				entityFetch(attributeContent(ATTRIBUTE_CODE)),
				statisticsType.isEmpty() ? new io.evitadb.api.query.require.HierarchyStatistics(base) :
					new io.evitadb.api.query.require.HierarchyStatistics(
						base, statisticsType.toArray(StatisticsType[]::new))
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
	void shouldReturnSelfHierarchyParentsWithSiblings(
		EnumSet<StatisticsType> statisticsType, StatisticsBase base, Evita evita, RestTester tester
	) {
		final Hierarchy hierarchy = createExpectedSelfHierarchy(
			evita,
			and(
				entityLocaleEquals(CZECH_LOCALE),
				hierarchyWithinSelf(entityPrimaryKeyInSet(30))
			),
			parents(
				"megaMenu",
				entityFetch(attributeContent(ATTRIBUTE_CODE)),
				siblings(
					entityFetch(attributeContent(ATTRIBUTE_CODE)),
					stopAt(distance(2)),
					statisticsType.isEmpty() ? new io.evitadb.api.query.require.HierarchyStatistics(base) :
						new io.evitadb.api.query.require.HierarchyStatistics(
							base, statisticsType.toArray(StatisticsType[]::new))
				),
				statisticsType.isEmpty() ? new io.evitadb.api.query.require.HierarchyStatistics(base) :
					new io.evitadb.api.query.require.HierarchyStatistics(
						base, statisticsType.toArray(StatisticsType[]::new))
			)
		);
		final List<Map<String, Object>> megaMenuDto = createHierarchyDto(hierarchy.getSelfHierarchy("megaMenu"));

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
	void shouldReturnSelfHierarchyRootSiblings(
		EnumSet<StatisticsType> statisticsType, StatisticsBase base, Evita evita, RestTester tester
	) {
		final Hierarchy hierarchy = createExpectedSelfHierarchy(
			evita,
			entityLocaleEquals(CZECH_LOCALE),
			siblings(
				"rootSiblings",
				entityFetch(attributeContent(ATTRIBUTE_CODE)),
				stopAt(distance(1)),
				statisticsType.isEmpty() ? new io.evitadb.api.query.require.HierarchyStatistics(base) :
					new io.evitadb.api.query.require.HierarchyStatistics(
						base, statisticsType.toArray(StatisticsType[]::new))
			)
		);
		final List<Map<String, Object>> rootSiblingsDto = createHierarchyDto(
			hierarchy.getSelfHierarchy("rootSiblings"));

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
	void shouldReturnMultipleDifferentSelfHierarchies(
		EnumSet<StatisticsType> statisticsType, StatisticsBase base, Evita evita, RestTester tester
	) {
		final Hierarchy hierarchy = createExpectedSelfHierarchy(
			evita,
			entityLocaleEquals(CZECH_LOCALE),
			fromRoot(
				"megaMenu",
				entityFetch(attributeContent(ATTRIBUTE_CODE)),
				stopAt(distance(2)),
				statisticsType.isEmpty() ? new io.evitadb.api.query.require.HierarchyStatistics(base) :
					new io.evitadb.api.query.require.HierarchyStatistics(
						base, statisticsType.toArray(StatisticsType[]::new))
			),
			siblings(
				"rootSiblings",
				entityFetch(attributeContent(ATTRIBUTE_CODE)),
				stopAt(distance(1)),
				statisticsType.isEmpty() ? new io.evitadb.api.query.require.HierarchyStatistics(base) :
					new io.evitadb.api.query.require.HierarchyStatistics(
						base, statisticsType.toArray(StatisticsType[]::new))
			)
		);

		final List<Map<String, Object>> megaMenuDto = createHierarchyDto(hierarchy.getSelfHierarchy("megaMenu"));
		final List<Map<String, Object>> rootSiblingsDto = createHierarchyDto(
			hierarchy.getSelfHierarchy("rootSiblings"));

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
	void shouldReturnReferencedHierarchyFromRoot(
		EnumSet<StatisticsType> statisticsType, StatisticsBase base, Evita evita, RestTester tester
	) {
		final Hierarchy hierarchy = createExpectedReferencedHierarchy(
			evita,
			and(
				entityLocaleEquals(CZECH_LOCALE),
				hierarchyWithinRoot(Entities.CATEGORY)
			),
			fromRoot(
				"megaMenu",
				entityFetch(attributeContent(ATTRIBUTE_CODE)),
				stopAt(distance(2)),
				statisticsType.isEmpty() ? new io.evitadb.api.query.require.HierarchyStatistics(base) :
					new io.evitadb.api.query.require.HierarchyStatistics(
						base, statisticsType.toArray(StatisticsType[]::new))
			)
		);
		final List<Map<String, Object>> megaMenuDto = createHierarchyDto(
			hierarchy.getReferenceHierarchy(Entities.CATEGORY, "megaMenu")
		);

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
	void shouldReturnReferencedHierarchyFromNode(
		EnumSet<StatisticsType> statisticsType, StatisticsBase base, Evita evita, RestTester tester
	) {
		final Hierarchy hierarchy = createExpectedReferencedHierarchy(
			evita,
			and(
				entityLocaleEquals(CZECH_LOCALE),
				hierarchyWithin(Entities.CATEGORY, entityPrimaryKeyInSet(6))
			),
			fromNode(
				"megaMenu",
				node(filterBy(entityPrimaryKeyInSet(2))),
				entityFetch(attributeContent(ATTRIBUTE_CODE)),
				stopAt(distance(2)),
				statisticsType.isEmpty() ? new io.evitadb.api.query.require.HierarchyStatistics(base) :
					new io.evitadb.api.query.require.HierarchyStatistics(
						base, statisticsType.toArray(StatisticsType[]::new))
			)
		);
		final List<Map<String, Object>> megaMenuDto = createHierarchyDto(
			hierarchy.getReferenceHierarchy(Entities.CATEGORY, "megaMenu")
		);

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
	void shouldReturnReferencedHierarchyChildren(
		EnumSet<StatisticsType> statisticsType, StatisticsBase base, Evita evita, RestTester tester
	) {
		final Hierarchy hierarchy = createExpectedReferencedHierarchy(
			evita,
			and(
				entityLocaleEquals(CZECH_LOCALE),
				hierarchyWithin(Entities.CATEGORY, entityPrimaryKeyInSet(1))
			),
			children(
				"megaMenu",
				entityFetch(attributeContent(ATTRIBUTE_CODE)),
				stopAt(distance(1)),
				statisticsType.isEmpty() ? new io.evitadb.api.query.require.HierarchyStatistics(base) :
					new io.evitadb.api.query.require.HierarchyStatistics(
						base, statisticsType.toArray(StatisticsType[]::new))
			)
		);
		final List<Map<String, Object>> megaMenuDto = createHierarchyDto(
			hierarchy.getReferenceHierarchy(Entities.CATEGORY, "megaMenu")
		);

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
	void shouldReturnReferencedHierarchyParentsWithoutSiblings(
		EnumSet<StatisticsType> statisticsType, StatisticsBase base, Evita evita, RestTester tester
	) {
		final Hierarchy hierarchy = createExpectedReferencedHierarchy(
			evita,
			and(
				entityLocaleEquals(CZECH_LOCALE),
				hierarchyWithin(Entities.CATEGORY, entityPrimaryKeyInSet(30))
			),
			parents(
				"megaMenu",
				entityFetch(attributeContent(ATTRIBUTE_CODE)),
				statisticsType.isEmpty() ? new io.evitadb.api.query.require.HierarchyStatistics(base) :
					new io.evitadb.api.query.require.HierarchyStatistics(
						base, statisticsType.toArray(StatisticsType[]::new))
			)
		);
		final List<Map<String, Object>> megaMenuDto = createHierarchyDto(
			hierarchy.getReferenceHierarchy(Entities.CATEGORY, "megaMenu")
		);

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
	void shouldReturnReferencedHierarchyParentsWithSiblings(
		EnumSet<StatisticsType> statisticsType, StatisticsBase base, Evita evita, RestTester tester
	) {
		final Hierarchy hierarchy = createExpectedReferencedHierarchy(
			evita,
			and(
				entityLocaleEquals(CZECH_LOCALE),
				hierarchyWithin(Entities.CATEGORY, entityPrimaryKeyInSet(30))
			),
			parents(
				"megaMenu",
				entityFetch(attributeContent(ATTRIBUTE_CODE)),
				siblings(
					entityFetch(attributeContent(ATTRIBUTE_CODE)),
					stopAt(distance(2)),
					statisticsType.isEmpty() ? new io.evitadb.api.query.require.HierarchyStatistics(base) :
						new io.evitadb.api.query.require.HierarchyStatistics(
							base, statisticsType.toArray(StatisticsType[]::new))
				),
				statisticsType.isEmpty() ? new io.evitadb.api.query.require.HierarchyStatistics(base) :
					new io.evitadb.api.query.require.HierarchyStatistics(
						base, statisticsType.toArray(StatisticsType[]::new))
			)
		);
		final List<Map<String, Object>> megaMenuDto = createHierarchyDto(
			hierarchy.getReferenceHierarchy(Entities.CATEGORY, "megaMenu")
		);

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
	void shouldReturnReferencedHierarchyRootSiblings(
		EnumSet<StatisticsType> statisticsType, StatisticsBase base, Evita evita, RestTester tester
	) {
		final Hierarchy hierarchy = createExpectedReferencedHierarchy(
			evita,
			and(
				entityLocaleEquals(CZECH_LOCALE),
				hierarchyWithinRoot(Entities.CATEGORY)
			),
			siblings(
				"rootSiblings",
				entityFetch(attributeContent(ATTRIBUTE_CODE)),
				stopAt(distance(1)),
				statisticsType.isEmpty() ? new io.evitadb.api.query.require.HierarchyStatistics(base) :
					new io.evitadb.api.query.require.HierarchyStatistics(
						base, statisticsType.toArray(StatisticsType[]::new))
			)
		);
		final List<Map<String, Object>> rootSiblingsDto = createHierarchyDto(
			hierarchy.getReferenceHierarchy(Entities.CATEGORY, "rootSiblings")
		);

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
	void shouldReturnMultipleDifferentReferencedHierarchies(
		EnumSet<StatisticsType> statisticsType, StatisticsBase base, Evita evita, RestTester tester
	) {
		final Hierarchy hierarchy = createExpectedReferencedHierarchy(
			evita,
			and(
				entityLocaleEquals(CZECH_LOCALE),
				hierarchyWithinRoot(Entities.CATEGORY)
			),
			fromRoot(
				"megaMenu",
				entityFetch(attributeContent(ATTRIBUTE_CODE)),
				stopAt(distance(2)),
				statisticsType.isEmpty() ? new io.evitadb.api.query.require.HierarchyStatistics(base) :
					new io.evitadb.api.query.require.HierarchyStatistics(
						base, statisticsType.toArray(StatisticsType[]::new))
			),
			siblings(
				"rootSiblings",
				entityFetch(attributeContent(ATTRIBUTE_CODE)),
				stopAt(distance(1)),
				statisticsType.isEmpty() ? new io.evitadb.api.query.require.HierarchyStatistics(base) :
					new io.evitadb.api.query.require.HierarchyStatistics(
						base, statisticsType.toArray(StatisticsType[]::new))
			)
		);

		final List<Map<String, Object>> flattenedMegaMenu = createHierarchyDto(
			hierarchy.getReferenceHierarchy(Entities.CATEGORY, "megaMenu")
		);
		final List<Map<String, Object>> flattenedRootSiblings = createHierarchyDto(
			hierarchy.getReferenceHierarchy(Entities.CATEGORY, "rootSiblings")
		);

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
	void shouldReturnNonGroupedFacetSummaryWithCountsForProducts(Evita evita, RestTester tester) {
		final EvitaResponse<EntityClassifier> response = queryEntities(
			evita,
			query(
				collection(Entities.PRODUCT),
				require(
					facetSummaryOfReference(Entities.BRAND, FacetStatisticsDepth.COUNTS)
				)
			)
		);
		assertFalse(response.getExtraResult(FacetSummary.class).getReferenceStatistics().isEmpty());

		final var expectedBody = createNonGroupedFacetSummaryDto(response, Entities.BRAND);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/PRODUCT/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody(
				"""
					{
						"require": {
							"facetBrandSummary": {
								"statisticsDepth":"COUNTS"
					        }
						}
					}
					""",
				Integer.MAX_VALUE
			)
			.executeAndThen()
			.statusCode(200)
			.body(
				resultPath(ResponseDescriptor.EXTRA_RESULTS, ExtraResultsDescriptor.FACET_SUMMARY, "brand"),
				equalTo(expectedBody)
			);
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return facet summary with impacts and entities for products")
	void shouldReturnNonGroupedFacetSummaryWithImpactsAndEntitiesForProducts(Evita evita, RestTester tester) {
		final EvitaResponse<EntityClassifier> response = queryEntities(
			evita,
			query(
				collection(Entities.PRODUCT),
				require(
					facetSummaryOfReference(
						Entities.BRAND,
						FacetStatisticsDepth.IMPACT,
						entityFetch(attributeContent(ATTRIBUTE_CODE))
					)
				)
			)
		);
		assertFalse(response.getExtraResult(FacetSummary.class).getReferenceStatistics().isEmpty());

		final var expectedBody = createNonGroupedFacetSummaryDto(response, Entities.BRAND);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/PRODUCT/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody(
				"""
					{
						"require": {
							"facetBrandSummary": {
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
				Integer.MAX_VALUE
			)
			.executeAndThen()
			.statusCode(200)
			.body(
				resultPath(ResponseDescriptor.EXTRA_RESULTS, ExtraResultsDescriptor.FACET_SUMMARY, "brand"),
				equalTo(expectedBody)
			);
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return facet summary with counts for products")
	void shouldReturnFacetSummaryWithCountsForProducts(Evita evita, RestTester tester) {
		final EvitaResponse<EntityClassifier> response = queryEntities(
			evita,
			query(
				collection(Entities.PRODUCT),
				require(
					facetSummaryOfReference(Entities.PARAMETER, FacetStatisticsDepth.COUNTS)
				)
			)
		);
		assertFalse(response.getExtraResult(FacetSummary.class).getReferenceStatistics().isEmpty());

		final var expectedBody = createFacetSummaryDto(response, Entities.PARAMETER);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/PRODUCT/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody(
				"""
					{
						"require": {
							"facetParameterSummary": {
								"statisticsDepth":"COUNTS"
					        }
						}
					}
					""",
				Integer.MAX_VALUE
			)
			.executeAndThen()
			.statusCode(200)
			.body(
				resultPath(ResponseDescriptor.EXTRA_RESULTS, ExtraResultsDescriptor.FACET_SUMMARY, "parameter"),
				equalTo(expectedBody)
			);
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return facet summary with impacts and entities for products")
	void shouldReturnFacetSummaryWithImpactsAndEntitiesForProducts(Evita evita, RestTester tester) {
		final EvitaResponse<EntityClassifier> response = queryEntities(
			evita,
			query(
				collection(Entities.PRODUCT),
				require(
					facetSummaryOfReference(
						Entities.PARAMETER,
						FacetStatisticsDepth.IMPACT,
						entityFetch(attributeContent(ATTRIBUTE_CODE))
					)
				)
			)
		);
		assertFalse(response.getExtraResult(FacetSummary.class).getReferenceStatistics().isEmpty());

		final var expectedBody = createFacetSummaryDto(response, Entities.PARAMETER);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/PRODUCT/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody(
				"""
					{
						"require": {
							"facetParameterSummary": {
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
				Integer.MAX_VALUE
			)
			.executeAndThen()
			.statusCode(200)
			.body(
				resultPath(ResponseDescriptor.EXTRA_RESULTS, ExtraResultsDescriptor.FACET_SUMMARY, "parameter"),
				equalTo(expectedBody)
			);
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return reference summary with counts for products")
	void shouldReturnNonGroupedReferenceSummaryWithCountsForProducts(Evita evita, RestTester tester) {
		final EvitaResponse<EntityClassifier> response = queryEntities(
			evita,
			query(
				collection(Entities.PRODUCT),
				require(
					referenceSummaryOfReference(Entities.BRAND, FacetStatisticsDepth.COUNTS)
				)
			)
		);
		assertFalse(response.getExtraResult(ReferenceSummary.class).getReferenceStatistics().isEmpty());

		final var expectedBody = createNonGroupedReferenceSummaryDto(response, Entities.BRAND);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/PRODUCT/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody(
				"""
					{
						"require": {
							"referenceBrandSummary": {
								"statisticsDepth":"COUNTS"
					        }
						}
					}
					""",
				Integer.MAX_VALUE
			)
			.executeAndThen()
			.statusCode(200)
			.body(
				resultPath(ResponseDescriptor.EXTRA_RESULTS, ExtraResultsDescriptor.REFERENCE_SUMMARY, "brand"),
				equalTo(expectedBody)
			);
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return reference summary with impacts and entities for products")
	void shouldReturnNonGroupedReferenceSummaryWithImpactsAndEntitiesForProducts(Evita evita, RestTester tester) {
		final EvitaResponse<EntityClassifier> response = queryEntities(
			evita,
			query(
				collection(Entities.PRODUCT),
				require(
					referenceSummaryOfReference(
						Entities.BRAND,
						FacetStatisticsDepth.IMPACT,
						entityFetch(attributeContent(ATTRIBUTE_CODE))
					)
				)
			)
		);
		assertFalse(response.getExtraResult(ReferenceSummary.class).getReferenceStatistics().isEmpty());

		final var expectedBody = createNonGroupedReferenceSummaryDto(response, Entities.BRAND);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/PRODUCT/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody(
				"""
					{
						"require": {
							"referenceBrandSummary": {
								"statisticsDepth":"IMPACT",
								"requirements": [
									{
						   				"entityFetch": {
						   					"attributeContent": ["code"]
						      			}
						   			}
								]
					        }
						}
					}
					""",
				Integer.MAX_VALUE
			)
			.executeAndThen()
			.statusCode(200)
			.body(
				resultPath(ResponseDescriptor.EXTRA_RESULTS, ExtraResultsDescriptor.REFERENCE_SUMMARY, "brand"),
				equalTo(expectedBody)
			);
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return grouped reference summary with counts for products")
	void shouldReturnReferenceSummaryWithCountsForProducts(Evita evita, RestTester tester) {
		final EvitaResponse<EntityClassifier> response = queryEntities(
			evita,
			query(
				collection(Entities.PRODUCT),
				require(
					referenceSummaryOfReference(Entities.PARAMETER, FacetStatisticsDepth.COUNTS)
				)
			)
		);
		assertFalse(response.getExtraResult(ReferenceSummary.class).getReferenceStatistics().isEmpty());

		final var expectedBody = createReferenceSummaryDto(response, Entities.PARAMETER);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/PRODUCT/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody(
				"""
					{
						"require": {
							"referenceParameterSummary": {
								"statisticsDepth":"COUNTS"
					        }
						}
					}
					""",
				Integer.MAX_VALUE
			)
			.executeAndThen()
			.statusCode(200)
			.body(
				resultPath(ResponseDescriptor.EXTRA_RESULTS, ExtraResultsDescriptor.REFERENCE_SUMMARY, "parameter"),
				equalTo(expectedBody)
			);
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return grouped reference summary with impacts and entities for products")
	void shouldReturnReferenceSummaryWithImpactsAndEntitiesForProducts(Evita evita, RestTester tester) {
		final EvitaResponse<EntityClassifier> response = queryEntities(
			evita,
			query(
				collection(Entities.PRODUCT),
				require(
					referenceSummaryOfReference(
						Entities.PARAMETER,
						FacetStatisticsDepth.IMPACT,
						entityFetch(attributeContent(ATTRIBUTE_CODE))
					)
				)
			)
		);
		assertFalse(response.getExtraResult(ReferenceSummary.class).getReferenceStatistics().isEmpty());

		final var expectedBody = createReferenceSummaryDto(response, Entities.PARAMETER);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/PRODUCT/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody(
				"""
					{
						"require": {
							"referenceParameterSummary": {
								"statisticsDepth":"IMPACT",
								"requirements": [
									{
						   				"entityFetch": {
						   					"attributeContent": ["code"]
						      			}
						   			}
								]
					        }
						}
					}
					""",
				Integer.MAX_VALUE
			)
			.executeAndThen()
			.statusCode(200)
			.body(
				resultPath(ResponseDescriptor.EXTRA_RESULTS, ExtraResultsDescriptor.REFERENCE_SUMMARY, "parameter"),
				equalTo(expectedBody)
			);
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return reference summary with histogram statistics for products")
	void shouldReturnReferenceSummaryWithHistogramStatisticsForProducts(Evita evita, RestTester tester) {
		queryEntities(
			evita,
			query(
				collection(Entities.PRODUCT),
				require(
					referenceSummaryOfReferenceWithHistograms(
						Entities.PARAMETER,
						FacetStatisticsDepth.COUNTS,
						entityFetch(attributeContent(ATTRIBUTE_CODE)),
						null,
						histogramStatistics(20, "priceIndex")
					)
				)
			)
		);

		// JsonPath root of the first parameter group's `priceIndex` histogram in the response — used as
		// prefix for shape assertions that check min/max/overallCount and bucket-level fields below.
		final String firstGroupHistogramPath = resultPath(
			ResponseDescriptor.EXTRA_RESULTS, ExtraResultsDescriptor.REFERENCE_SUMMARY
		) + ".parameter[0].histogramStatistics.priceIndex";

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/PRODUCT/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody(
				"""
					{
						"require": {
							"referenceParameterSummaryWithHistograms": {
								"statisticsDepth":"COUNTS",
								"requirements": [
									{
						   				"entityFetch": {
						   					"attributeContent": ["code"]
						      			}
						   			},
									{
										"histogramStatistics": {
											"requestedBucketCount": 20,
											"indexNames": ["priceIndex"]
										}
									}
								]
					        }
						}
					}
					""",
				Integer.MAX_VALUE
			)
			.executeAndThen()
			.statusCode(200)
			// shape-only assertions: numeric values depend on the generated dataset and are not pinned.
			.body(firstGroupHistogramPath, notNullValue())
			.body(firstGroupHistogramPath + ".min", notNullValue())
			.body(firstGroupHistogramPath + ".max", notNullValue())
			.body(firstGroupHistogramPath + ".overallCount", allOf(notNullValue(), greaterThan(0)))
			.body(firstGroupHistogramPath + ".buckets", notNullValue())
			.body(firstGroupHistogramPath + ".buckets.size()", greaterThan(0))
			.body(firstGroupHistogramPath + ".buckets[0].threshold", notNullValue())
			.body(firstGroupHistogramPath + ".buckets[0].occurrences", notNullValue())
			.body(firstGroupHistogramPath + ".buckets[0].requested", notNullValue());
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should accept histogram-level entityFetch and request boundary entities on reference histogram")
	void shouldReturnReferenceSummaryWithHistogramStatisticsIncludingBoundaryEntities(
		Evita evita, RestTester tester
	) {
		// The test requests `entityFetch` both at the reference level and inside `histogramStatistics`.
		// Unlike the GraphQL mirror this REST payload doesn't select boundary entity fields explicitly
		// — the contract under test is that the REST surface accepts the extended requirement shape
		// and produces a well-formed histogram with at least one bucket.
		queryEntities(
			evita,
			query(
				collection(Entities.PRODUCT),
				require(
					referenceSummaryOfReferenceWithHistograms(
						Entities.PARAMETER,
						FacetStatisticsDepth.COUNTS,
						entityFetch(attributeContent(ATTRIBUTE_CODE)),
						null,
						histogramStatistics(
							20,
							entityFetch(attributeContent(ATTRIBUTE_CODE)),
							"priceIndex"
						)
					)
				)
			)
		);

		final String firstGroupHistogramPath = resultPath(
			ResponseDescriptor.EXTRA_RESULTS, ExtraResultsDescriptor.REFERENCE_SUMMARY
		) + ".parameter[0].histogramStatistics.priceIndex";

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/PRODUCT/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody(
				"""
					{
						"require": {
							"referenceParameterSummaryWithHistograms": {
								"statisticsDepth":"COUNTS",
								"requirements": [
									{
						   				"entityFetch": {
						   					"attributeContent": ["code"]
						      			}
						   			},
									{
										"histogramStatistics": {
											"requestedBucketCount": 20,
											"indexNames": ["priceIndex"],
											"entityFetch": {
												"attributeContent": ["code"]
											}
										}
									}
								]
					        }
						}
					}
					""",
				Integer.MAX_VALUE
			)
			.executeAndThen()
			.statusCode(200)
			// shape only: histogram must be populated and carry buckets; boundary entities are
			// optional (present for REFERENCED_ENTITY_ATTRIBUTE, absent for REFERENCE_ATTRIBUTE).
			.body(firstGroupHistogramPath, notNullValue())
			.body(firstGroupHistogramPath + ".buckets.size()", greaterThan(0))
			.body(firstGroupHistogramPath + ".minReferencedEntity", notNullValue())
			.body(firstGroupHistogramPath + ".maxReferencedEntity", notNullValue());
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should narrow results via histogramHaving in userFilter while keeping reference histogram populated")
	void shouldApplyHistogramHavingInUserFilter(Evita evita, RestTester tester) {
		// Two-step rationale:
		//   1. Pre-compute a catalog-wide `priceIndex` histogram so we know a realistic [min, max] span
		//      to draw the user-filter slider from.
		//   2. Pick a sub-range inside that span so the `histogramHaving` constraint narrows the page,
		//      but the reference histogram in extra results is still computed against the pre-slider
		//      baseline (userFilter children are peeled off when computing the extra-result histogram).
		// The final assertions prove narrowing (totalRecordCount shrinks vs. catalog total) AND that the
		// reference histogram survives the peel (at least one group with non-null min).
		// Baseline capture:
		final EvitaResponse<EntityClassifier> baselineResponse = queryEntities(
			evita,
			query(
				collection(Entities.PRODUCT),
				require(
					referenceSummaryOfReferenceWithHistograms(
						Entities.PARAMETER,
						FacetStatisticsDepth.COUNTS,
						null,
						null,
						histogramStatistics(20, HISTOGRAM_PRICE_INDEX)
					)
				)
			)
		);
		final HistogramContract baselineHistogram = baselineResponse.getExtraResult(ReferenceSummary.class)
			.getReferenceStatistics()
			.stream()
			.map(stats -> stats.getHistogramStatistics(HISTOGRAM_PRICE_INDEX))
			.filter(histogram -> histogram != null && histogram.getBuckets().length > 0)
			.findFirst()
			.orElseThrow();

		// pick a sub-range inside the catalog-wide span — covering the lower half of the slider —
		// so narrowing still retains some products but strictly fewer than the baseline count.
		final BigDecimal baselineMin = baselineHistogram.getMin();
		final BigDecimal baselineMax = baselineHistogram.getMax();
		final BigDecimal rangeFrom = baselineMin;
		final BigDecimal rangeTo = baselineMin.add(
			baselineMax.subtract(baselineMin).divide(new BigDecimal("2"), java.math.RoundingMode.HALF_UP)
		);
		assertTrue(rangeFrom.compareTo(rangeTo) <= 0,
			"computed narrowing range must be ordered; got from=" + rangeFrom + " to=" + rangeTo);

		final EvitaResponse<EntityClassifier> narrowedResponse = queryEntities(
			evita,
			query(
				collection(Entities.PRODUCT),
				filterBy(
					userFilter(
						histogramHaving(
							Entities.PARAMETER,
							HISTOGRAM_PRICE_INDEX,
							rangeFrom,
							rangeTo
						)
					)
				),
				require(
					page(1, Integer.MAX_VALUE),
					referenceSummaryOfReferenceWithHistograms(
						Entities.PARAMETER,
						FacetStatisticsDepth.COUNTS,
						null,
						null,
						histogramStatistics(20, HISTOGRAM_PRICE_INDEX)
					)
				)
			)
		);

		// sanity-check on the evitaDB side: the narrowed record count must be strictly less than the
		// catalog total — this is the primary proof that `histogramHaving` performs its narrowing role.
		final EvitaResponse<EntityClassifier> totalsResponse = queryEntities(
			evita,
			query(collection(Entities.PRODUCT), require(page(1, 1)))
		);
		assertTrue(narrowedResponse.getTotalRecordCount() < totalsResponse.getTotalRecordCount(),
			"histogramHaving must narrow the result set below the catalog-wide total");

		// exercise the full REST → query → histogram path: the `referenceParameterHistogramHaving`
		// field (derived from `reference` prefix + `Parameter` classifier + `HistogramHaving` full name)
		// must be accepted inside `userFilter`, route through the engine, and mirror the evitaDB-side
		// invariants — narrowing the result set and surfacing a populated reference histogram.
		// The REST resolver decodes `from`/`to` as String when the target slot is `Serializable` without
		// a schema type hint, then the engine-side validator compares them with String.compareTo, so we
		// must emit values of equal length to avoid lexicographic ordering flipping the bounds check.
		final String rangeFromLiteral = padToMatchLength(rangeFrom.toPlainString(), rangeTo.toPlainString());
		final String rangeToLiteral = padToMatchLength(rangeTo.toPlainString(), rangeFrom.toPlainString());

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/PRODUCT/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody(
				"""
					{
						"filterBy": {
							"userFilter": [{
								"referenceParameterHistogramHaving": {
									"histogramName": "%s",
									"from": "%s",
									"to": "%s"
								}
							}]
						},
						"require": {
							"page": {
								"number": 1,
								"size": %d
							},
							"referenceParameterSummaryWithHistograms": {
								"statisticsDepth": "COUNTS",
								"requirements": [
									{
										"histogramStatistics": {
											"requestedBucketCount": 20,
											"indexNames": ["%s"]
										}
									}
								]
							}
						}
					}
					""",
				HISTOGRAM_PRICE_INDEX,
				rangeFromLiteral,
				rangeToLiteral,
				Integer.MAX_VALUE,
				HISTOGRAM_PRICE_INDEX
			)
			.executeAndThen()
			.statusCode(200)
			// result set narrowing: REST must report the same totalRecordCount as the evitaDB query —
			// matching counts prove the filter routed through the REST resolver, translator, engine.
			.body(
				resultPath(ResponseDescriptor.RECORD_PAGE) + ".totalRecordCount",
				equalTo(narrowedResponse.getTotalRecordCount())
			)
			// at least one `priceIndex` histogram carries a non-null min/max — this is the slider-peeled
			// baseline span, shown to the user regardless of the narrowing applied by `histogramHaving`.
			.body(
				resultPath(ResponseDescriptor.EXTRA_RESULTS, ExtraResultsDescriptor.REFERENCE_SUMMARY) +
					".parameter.findAll { it.histogramStatistics?.priceIndex?.min != null }.size()",
				greaterThan(0)
			);
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should narrow results via groupHaving nested in the generic referenceParameterHaving container")
	void shouldApplyGroupHavingInReferenceHaving(Evita evita, RestTester tester, List<SealedEntity> originalProductEntities) {
		// Regression: the schema builder never generated the GROUP constraint slot for generic
		// referenceXxxHaving/facetXxxHaving containers — only histogramHaving's own dedicated
		// groupSelector slot worked. This exercises groupHaving nested in referenceParameterHaving.
		final SealedEntity sampleProduct = originalProductEntities.stream()
			.filter(it -> it.getReferences(Entities.PARAMETER).stream()
				.anyMatch(reference -> reference.getGroup().isPresent()))
			.findFirst()
			.orElseThrow();
		final ReferenceContract sampleParameterReference = sampleProduct.getReferences(Entities.PARAMETER)
			.stream()
			.filter(reference -> reference.getGroup().isPresent())
			.findFirst()
			.orElseThrow();
		final int groupPk = sampleParameterReference.getGroup().orElseThrow().getPrimaryKey();
		final String groupCode = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.getEntity(Entities.PARAMETER_GROUP, groupPk, attributeContent(ATTRIBUTE_CODE));
			}
		).orElseThrow().getAttribute(ATTRIBUTE_CODE);
		assertNotNull(groupCode, "sampled parameter group must carry a code — fixture sanity check");

		final EvitaResponse<EntityClassifier> baselineResponse = queryEntities(
			evita,
			query(
				collection(Entities.PRODUCT),
				filterBy(
					referenceHaving(
						Entities.PARAMETER,
						groupHaving(attributeEquals(ATTRIBUTE_CODE, groupCode))
					)
				),
				require(page(1, 1))
			)
		);
		final int expectedCount = baselineResponse.getTotalRecordCount();
		assertTrue(expectedCount > 0, "baseline query must match at least the sample product — fixture sanity check");

		final EvitaResponse<EntityClassifier> catalogWideResponse = queryEntities(
			evita,
			query(collection(Entities.PRODUCT), require(page(1, 1)))
		);
		assertTrue(expectedCount < catalogWideResponse.getTotalRecordCount(),
			"groupHaving must strictly narrow the result set (got " + expectedCount +
				" with the group filter vs " + catalogWideResponse.getTotalRecordCount() + " catalog-wide)");

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/PRODUCT/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody(
				"""
					{
						"filterBy": {
							"referenceParameterHaving": [
								{
									"groupHaving": {
										"attributeCodeEquals": "%s"
									}
								}
							]
						},
						"require": {
							"page": {
								"number": 1,
								"size": %d
							}
						}
					}
					""",
				groupCode,
				Integer.MAX_VALUE
			)
			.executeAndThen()
			.statusCode(200)
			.body(
				resultPath(ResponseDescriptor.RECORD_PAGE) + ".totalRecordCount",
				equalTo(expectedCount)
			);
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return error for reference summary with histogram statistics for non-bucketed reference for products")
	void shouldReturnErrorForReferenceSummaryHistogramStatisticsForNonBucketedReferenceForProducts(Evita evita, RestTester tester) {
		tester.test(TEST_CATALOG)
			.urlPathSuffix("/PRODUCT/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody(
				"""
					{
						"require": {
							"referenceStoreSummary": {
								"statisticsDepth":"COUNTS",
								"requirements": [
									{
						   				"entityFetch": {
						   					"attributeContent": ["code"]
						      			}
						   			},
									{
										"histogramStatistics": {
											"requestedBucketCount": 20,
											"indexNames": ["priceIndex"]
										}
									}
								]
					        }
						}
					}
					""",
				Integer.MAX_VALUE
			)
			.executeAndThen()
			.statusCode(400);
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
				final Predicate<Integer> expectedEntitiesCountValidator = size -> size == 5;

				compareRestResultPksToEvitaDBResultPks(
					"First page must be sorted by name in descending order.",
					session, tester,
					fabricateEvitaQLSegmentedQuery(1, 5, evitaQLSegments),
					fabricateRestSegmentedQuery(1, 5, graphQLSegments),
					expectedEntitiesCountValidator
				);

				compareRestResultPksToEvitaDBResultPks(
					"Second page must be sorted by ean in descending order and quantity in asceding order.",
					session, tester,
					fabricateEvitaQLSegmentedQuery(2, 5, evitaQLSegments),
					fabricateRestSegmentedQuery(2, 5, graphQLSegments),
					expectedEntitiesCountValidator
				);

				compareRestResultPksToEvitaDBResultPks(
					"Third page must be sorted by PK in ascending order.",
					session, tester,
					fabricateEvitaQLSegmentedQuery(3, 5, evitaQLSegments),
					fabricateRestSegmentedQuery(3, 5, graphQLSegments),
					expectedEntitiesCountValidator
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
				final Predicate<Integer> expectedEntitiesCountValidator = size -> size == 5;

				compareRestResultPksToEvitaDBResultPks(
					"First page must be sorted by name in descending order.",
					session, tester,
					fabricateEvitaQLSegmentedQuery(1, 5, evitaQLSegments),
					fabricateRestSegmentedQuery(1, 5, graphQLSegments),
					expectedEntitiesCountValidator
				);

				compareRestResultPksToEvitaDBResultPks(
					"Second page must be sorted by name in descending order.",
					session, tester,
					fabricateEvitaQLSegmentedQuery(2, 5, evitaQLSegments),
					fabricateRestSegmentedQuery(2, 5, graphQLSegments),
					expectedEntitiesCountValidator
				);

				compareRestResultPksToEvitaDBResultPks(
					"Third page must be sorted by EAN in descending order (excluding items on first two pages).",
					session, tester,
					fabricateEvitaQLSegmentedQuery(3, 5, evitaQLSegments),
					fabricateRestSegmentedQuery(3, 5, graphQLSegments),
					expectedEntitiesCountValidator
				);

				compareRestResultPksToEvitaDBResultPks(
					"Fourth page contains 3 entities sorted according to EAN in descending order and " +
						"ends with first 2 entities sorted according to quantity in ascending order.",
					session, tester,
					fabricateEvitaQLSegmentedQuery(4, 5, evitaQLSegments),
					fabricateRestSegmentedQuery(4, 5, graphQLSegments),
					expectedEntitiesCountValidator
				);

				compareRestResultPksToEvitaDBResultPks(
					"Fifth page must have only 4 entities be sorted by quantity in ascending order and " +
						"must end with first entity sorted by PK in ascending order.",
					session, tester,
					fabricateEvitaQLSegmentedQuery(5, 5, evitaQLSegments),
					fabricateRestSegmentedQuery(5, 5, graphQLSegments),
					expectedEntitiesCountValidator
				);

				compareRestResultPksToEvitaDBResultPks(
					"Sixth page must be sorted by PK in ascending order " +
						"(but only from those entities that hasn't been already provided).",
					session, tester,
					fabricateEvitaQLSegmentedQuery(6, 5, evitaQLSegments),
					fabricateRestSegmentedQuery(6, 5, graphQLSegments),
					expectedEntitiesCountValidator
				);

				compareRestResultPksToEvitaDBResultPks(
					"Seventh page must be sorted by PK in ascending order " +
						"(but only from those entities that hasn't been already provided).",
					session, tester,
					fabricateEvitaQLSegmentedQuery(7, 5, evitaQLSegments),
					fabricateRestSegmentedQuery(7, 5, graphQLSegments),
					expectedEntitiesCountValidator
				);

				return null;
			}
		);
	}

	@DisplayName("Should insert spaces into paginated results")
	@UseDataSet(REST_HUNDRED_PRODUCTS_FOR_SEGMENTS)
	@Test
	void shouldInsertSpaces(Evita evita, RestTester tester) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				for (int i = 1; i <= 10; i++) {
					compareRestResultPksToEvitaDBResultPks(
						"Page " + i,
						session, tester,
						fabricateEvitaQLSpacingQuery(i, 10),
						fabricateRestSpacingQuery(i, 10),
						size -> size > 0
					);
				}
			}
		);
	}

	@DisplayName("Should pass query labels")
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@Test
	void shouldPassQueryLabels(RestTester tester) {
		tester.test(TEST_CATALOG)
			.urlPathSuffix("/PRODUCT/query")
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
			.body(DATA_PATH, hasSize(greaterThan(0)));
	}

	private void compareRestResultPksToEvitaDBResultPks(
		@Nonnull String message,
		@Nonnull EvitaSessionContract session,
		@Nonnull RestTester tester,
		@Nonnull Query sampleEvitaQLQuery,
		@Nonnull String targetRestQuery,
		@Nonnull Predicate<Integer> entitiesCountValidator
	) {
		final int[] expectedEntities = session.query(sampleEvitaQLQuery, EntityReference.class)
			.getRecordData()
			.stream()
			.mapToInt(EntityReference::getPrimaryKey)
			.toArray();
		assertTrue(entitiesCountValidator.test(expectedEntities.length));
		final List<Integer> actualEntities = tester.test(TEST_CATALOG)
			.urlPathSuffix("/PRODUCT/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody(targetRestQuery)
			.executeAndExpectOkAndThen()
			.extract()
			.body()
			.jsonPath()
			.getList(resultPath(DATA_PATH, EntityDescriptor.PRIMARY_KEY.name()), Integer.class);
		assertSortedResultEquals(
			message,
			actualEntities,
			expectedEntities
		);
	}

	/**
	 * Pads `value` with leading zeros (inserting them before the integer portion) until it matches
	 * `reference` in total character length. Used to make two decimal literals lexicographically
	 * ordered — necessary because the REST resolver deserialises `Serializable`-typed bounds as
	 * Strings and the engine-side validator then uses `String.compareTo` rather than numeric ordering.
	 *
	 * @param value     the decimal literal to pad
	 * @param reference the reference literal whose length determines the target width
	 * @return `value` padded with leading zeros so its length matches `reference`
	 */
	@Nonnull
	private static String padToMatchLength(@Nonnull String value, @Nonnull String reference) {
		if (value.length() >= reference.length()) {
			return value;
		}
		final StringBuilder padded = new StringBuilder(reference.length());
		padded.append("0".repeat(reference.length() - value.length()));
		padded.append(value);
		return padded.toString();
	}

	@Nonnull
	private static Map<String, Object> createAttributeHistogramDto(
		@Nonnull EvitaResponse<? extends EntityClassifier> response,
		@Nonnull String attributeName
	) {
		final AttributeHistogram attributeHistogram = response.getExtraResult(AttributeHistogram.class);
		final HistogramContract histogram = Objects.requireNonNull(attributeHistogram.getHistogram(attributeName));

		return map()
			.e(HistogramDescriptor.MAX.name(), histogram.getMax().toString())
			.e(
				HistogramDescriptor.BUCKETS.name(), Arrays.stream(histogram.getBuckets())
					.map(bucket -> map()
						.e(BucketDescriptor.THRESHOLD.name(), bucket.threshold().toString())
						.e(BucketDescriptor.OCCURRENCES.name(), bucket.occurrences())
						.e(BucketDescriptor.REQUESTED.name(), bucket.requested())
						.e(BucketDescriptor.RELATIVE_FREQUENCY.name(), bucket.relativeFrequency().toString())
						.build())
					.toList()
			)
			.e(HistogramDescriptor.MIN.name(), histogram.getMin().toString())
			.e(HistogramDescriptor.OVERALL_COUNT.name(), histogram.getOverallCount())
			.build();
	}

	@Nullable
	private Hierarchy createExpectedSelfHierarchy(
		@Nonnull Evita evita,
		@Nonnull FilterConstraint filterBy,
		@Nonnull HierarchyRequireConstraint... hierarchies
	) {
		return queryEntities(
			evita,
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
			true
		)
			.getExtraResult(Hierarchy.class);
	}

	@Nonnull
	private static RestTester.Request fetchSelfHierarchy(
		@Nonnull RestTester tester,
		@Nonnull String filterBy,
		@Nonnull String hierarchies,
		@Nonnull Object... args
	) {
		return tester.test(TEST_CATALOG)
			.urlPathSuffix("/CATEGORY/query")
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
									"orderBy": [{
										"attributeCodeNatural": "DESC"
									}],
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
				args
			);
	}

	@Nullable
	private Hierarchy createExpectedReferencedHierarchy(
		@Nonnull Evita evita,
		@Nonnull FilterConstraint filterBy,
		@Nonnull HierarchyRequireConstraint... hierarchies
	) {
		return queryEntities(
			evita,
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
			true
		)
			.getExtraResult(Hierarchy.class);
	}

	@Nonnull
	private static RestTester.Request fetchReferencedHierarchy(
		@Nonnull RestTester tester,
		@Nonnull String filterBy,
		@Nonnull String hierarchies,
		@Nonnull Object... args
	) {
		return tester.test(TEST_CATALOG)
			.urlPathSuffix("/PRODUCT/query")
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
									"orderBy": [{
										"attributeCodeNatural": "DESC"
									}],
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
				args
			);
	}

	@Nonnull
	private static String getHierarchyStatisticsConstraint(
		@Nonnull StatisticsBase base, @Nonnull EnumSet<StatisticsType> types) {
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
		final List<Map<String, Object>> hierarchyDto = hierarchy.stream()
			.map(this::createLevelInfoDto)
			.toList();
		assertFalse(hierarchyDto.isEmpty());
		return hierarchyDto;
	}

	private Map<String, Object> createLevelInfoDto(@Nonnull LevelInfo levelInfo) {
		final SealedEntity entity = (SealedEntity) levelInfo.entity();
		final Map<String, Object> entityDto = createEntityDto(entity);

		final MapBuilder currentLevelInfoDto = map()
			.e(LevelInfoDescriptor.ENTITY.name(), entityDto)
			.e(LevelInfoDescriptor.REQUESTED.name(), levelInfo.requested());

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

	@Nonnull
	private static Map<String, Object> createNonGroupedFacetSummaryDto(
		@Nonnull EvitaResponse<? extends EntityClassifier> response,
		@Nonnull String referenceName
	) {
		final FacetSummary facetSummary = Objects.requireNonNull(response.getExtraResult(FacetSummary.class));

		return Optional.ofNullable(facetSummary.getFacetGroupStatistics(referenceName))
			.map(groupStatistics ->
				     map()
					     .e(FacetGroupStatisticsDescriptor.COUNT.name(), groupStatistics.getCount())
					     .e(
						     FacetGroupStatisticsDescriptor.FACET_STATISTICS.name(),
						     groupStatistics.getFacetStatistics()
							     .stream()
							     .map(facetStatistics -> {
								     final MapBuilder facetStatisticsDto = map()
									     .e(
										     FacetStatisticsDescriptor.REQUESTED.name(),
										     facetStatistics.isRequested()
									     )
									     .e(FacetStatisticsDescriptor.COUNT.name(), facetStatistics.getCount())
									     .e(
										     FacetStatisticsDescriptor.FACET_ENTITY.name(),
										     createEntityDto(facetStatistics.getFacetEntity())
									     );

								     Optional.ofNullable(facetStatistics.getImpact())
									     .ifPresent(impact -> facetStatisticsDto.e(
										     FacetStatisticsDescriptor.IMPACT.name(), map()
											     .e(
												     FacetRequestImpactDescriptor.DIFFERENCE.name(),
												     facetStatistics.getImpact().difference()
											     )
											     .e(
												     FacetRequestImpactDescriptor.MATCH_COUNT.name(),
												     facetStatistics.getImpact().matchCount()
											     )
											     .e(
												     FacetRequestImpactDescriptor.HAS_SENSE.name(),
												     facetStatistics.getImpact().hasSense()
											     )
											     .build()
									     ));

								     return facetStatisticsDto.build();
							     })
							     .toList()
					     )
					     .build()
			)
			.orElseThrow(() -> new IllegalStateException(
				"Facet summary must contain facet group statistics for reference " + referenceName
			));
	}

	@Nonnull
	private static Map<String, Object> createNonGroupedReferenceSummaryDto(
		@Nonnull EvitaResponse<? extends EntityClassifier> response,
		@Nonnull String referenceName
	) {
		final ReferenceSummary referenceSummary = Objects.requireNonNull(
			response.getExtraResult(ReferenceSummary.class)
		);

		return Optional.ofNullable(referenceSummary.getReferenceGroupStatistics(referenceName))
			.map(groupStatistics ->
				     map()
					     .e(ReferenceGroupStatisticsDescriptor.COUNT.name(), groupStatistics.getCount())
					     .e(
						     ReferenceGroupStatisticsDescriptor.FACET_STATISTICS.name(),
						     groupStatistics.getFacetStatistics()
							     .stream()
							     .map(facetStatistics -> {
								     final MapBuilder facetStatisticsDto = map()
									     .e(
										     FacetStatisticsDescriptor.REQUESTED.name(),
										     facetStatistics.isRequested()
									     )
									     .e(FacetStatisticsDescriptor.COUNT.name(), facetStatistics.getCount())
									     .e(
										     FacetStatisticsDescriptor.FACET_ENTITY.name(),
										     createEntityDto(facetStatistics.getFacetEntity())
									     );

								     Optional.ofNullable(facetStatistics.getImpact())
									     .ifPresent(impact -> facetStatisticsDto.e(
										     FacetStatisticsDescriptor.IMPACT.name(), map()
											     .e(
												     FacetRequestImpactDescriptor.DIFFERENCE.name(),
												     facetStatistics.getImpact().difference()
											     )
											     .e(
												     FacetRequestImpactDescriptor.MATCH_COUNT.name(),
												     facetStatistics.getImpact().matchCount()
											     )
											     .e(
												     FacetRequestImpactDescriptor.HAS_SENSE.name(),
												     facetStatistics.getImpact().hasSense()
											     )
											     .build()
									     ));

								     return facetStatisticsDto.build();
							     })
							     .toList()
					     )
					     .build()
			)
			.orElseThrow(() -> new IllegalStateException(
				"Reference summary must contain reference group statistics for reference " + referenceName
			));
	}

	@Nonnull
	private static List<Map<String, Object>> createReferenceSummaryDto(
		@Nonnull EvitaResponse<? extends EntityClassifier> response,
		@Nonnull String referenceName
	) {
		final ReferenceSummary referenceSummary = Objects.requireNonNull(
			response.getExtraResult(ReferenceSummary.class)
		);

		return referenceSummary.getReferenceStatistics()
			.stream()
			.filter(groupStatistics -> groupStatistics.getReferenceName().equals(referenceName))
			.map(groupStatistics ->
				     map()
					     .e(
						     ReferenceGroupStatisticsDescriptor.GROUP_ENTITY.name(),
						     createEntityDto(groupStatistics.getGroupEntity())
					     )
					     .e(ReferenceGroupStatisticsDescriptor.COUNT.name(), groupStatistics.getCount())
					     .e(
						     ReferenceGroupStatisticsDescriptor.FACET_STATISTICS.name(),
						     groupStatistics.getFacetStatistics()
							     .stream()
							     .map(facetStatistics -> {
								     final MapBuilder facetStatisticsDto = map()
									     .e(
										     FacetStatisticsDescriptor.REQUESTED.name(),
										     facetStatistics.isRequested()
									     )
									     .e(FacetStatisticsDescriptor.COUNT.name(), facetStatistics.getCount())
									     .e(
										     FacetStatisticsDescriptor.FACET_ENTITY.name(),
										     createEntityDto(facetStatistics.getFacetEntity())
									     );

								     Optional.ofNullable(facetStatistics.getImpact())
									     .ifPresent(impact -> facetStatisticsDto.e(
										     FacetStatisticsDescriptor.IMPACT.name(), map()
											     .e(
												     FacetRequestImpactDescriptor.DIFFERENCE.name(),
												     facetStatistics.getImpact().difference()
											     )
											     .e(
												     FacetRequestImpactDescriptor.MATCH_COUNT.name(),
												     facetStatistics.getImpact().matchCount()
											     )
											     .e(
												     FacetRequestImpactDescriptor.HAS_SENSE.name(),
												     facetStatistics.getImpact().hasSense()
											     )
											     .build()
									     ));

								     return facetStatisticsDto.build();
							     })
							     .toList()
					     )
					     .build()
			)
			.toList();
	}
}
