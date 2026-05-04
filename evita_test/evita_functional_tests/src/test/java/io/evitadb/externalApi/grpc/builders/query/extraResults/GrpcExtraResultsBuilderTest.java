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

package io.evitadb.externalApi.grpc.builders.query.extraResults;

import io.evitadb.api.query.Query;
import io.evitadb.api.query.require.StatisticsType;
import io.evitadb.api.requestResponse.EvitaEntityResponse;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.extraResult.AttributeHistogram;
import io.evitadb.api.requestResponse.extraResult.FacetSummary;
import io.evitadb.api.requestResponse.extraResult.FacetSummary.FacetGroupStatistics;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary.FacetStatistics;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary.ReferenceGroupStatistics;
import io.evitadb.api.requestResponse.extraResult.Hierarchy;
import io.evitadb.api.requestResponse.extraResult.Hierarchy.LevelInfo;
import io.evitadb.api.requestResponse.extraResult.Histogram;
import io.evitadb.api.requestResponse.extraResult.HistogramContract.Bucket;
import io.evitadb.api.requestResponse.extraResult.PriceHistogram;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry.QueryPhase;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedReferenceIndexType;
import io.evitadb.dataType.PaginatedList;
import io.evitadb.dataType.Scope;
import io.evitadb.externalApi.grpc.generated.GrpcExtraResults;
import io.evitadb.externalApi.grpc.generated.GrpcLevelInfos;
import io.evitadb.utils.ArrayUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Tag;

import static io.evitadb.api.query.QueryConstraints.attributeHistogram;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.dataInLocales;
import static io.evitadb.api.query.QueryConstraints.entityFetch;
import static io.evitadb.api.query.QueryConstraints.entityFetchAll;
import static io.evitadb.api.query.QueryConstraints.facetSummary;
import static io.evitadb.api.query.QueryConstraints.fromRoot;
import static io.evitadb.api.query.QueryConstraints.hierarchyOfSelf;
import static io.evitadb.api.query.QueryConstraints.referenceSummary;
import static io.evitadb.api.query.QueryConstraints.require;
import static io.evitadb.api.query.QueryConstraints.statistics;
import static io.evitadb.externalApi.grpc.builders.query.extraResults.GrpcReferenceSummaryBuilderTest.createFacetEntity;
import static io.evitadb.externalApi.grpc.builders.query.extraResults.GrpcReferenceSummaryBuilderTest.createGroupEntity;
import static io.evitadb.externalApi.grpc.builders.query.extraResults.GrpcHierarchyBuilderTest.createHierarchyEntity;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.evitadb.test.TestTags.GRPC;
import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.QUERY;

/**
 * This test verifies functionalities of methods in {@link GrpcExtraResultsBuilder} class.
 *
 * @author Tomáš Pozler, 2022
 */
@SuppressWarnings("deprecation")
@Tag(GRPC)
@Tag(EXTERNAL_API)
@Tag(QUERY)
class GrpcExtraResultsBuilderTest {
	private final static ReferenceSchemaContract REFENCE_SCHEMA = ReferenceSchema._internalBuild(
		"test1", "test1", true, Cardinality.ONE_OR_MORE, "testGroup1", false,
		new ScopedReferenceIndexType[] {
			new ScopedReferenceIndexType(Scope.DEFAULT_SCOPE, ReferenceIndexType.FOR_FILTERING)
		},
		new Scope[] { Scope.LIVE }
	);

	@DisplayName("Mixed deprecated+canonical constraints populate both gRPC fields")
	@Test
	void shouldPopulateBothGrpcFieldsWhenMixedConstraintsUsed() {
		final FacetGroupStatistics facetGroupStats = new FacetGroupStatistics(
			REFENCE_SCHEMA, createGroupEntity("testGroup1"), 10,
			Arrays.asList(
				new FacetStatistics(
					createFacetEntity("test1", 1, "a"), false, 45, null
				),
				new FacetStatistics(
					createFacetEntity("test1", 2, "b"), false, 32, null
				)
			)
		);

		// construct the deprecated FacetSummary DTO (keyed under FacetSummary.class)
		final FacetSummary facetSummaryDto = new FacetSummary(
			Collections.singletonList(facetGroupStats)
		);

		// construct the canonical ReferenceSummary DTO (keyed under ReferenceSummary.class)
		// using the same data to simulate the mixed-constraint producer behavior
		final ReferenceGroupStatistics refGroupStats = new ReferenceGroupStatistics(
			REFENCE_SCHEMA, createGroupEntity("testGroup1"), 10,
			Arrays.asList(
				new FacetStatistics(
					createFacetEntity("test1", 1, "a"), false, 45, null
				),
				new FacetStatistics(
					createFacetEntity("test1", 2, "b"), false, 32, null
				)
			)
		);
		final ReferenceSummary referenceSummaryDto = new ReferenceSummary(
			Collections.singletonList(refGroupStats)
		);

		// mixed query: both deprecated and canonical constraints
		final Query query = Query.query(
			collection("test"),
			require(
				facetSummary(),
				referenceSummary()
			)
		);

		final EvitaEntityResponse<EntityReferenceContract> response = new EvitaEntityResponse<>(
			query,
			new PaginatedList<>(0, 0, 0),
			ArrayUtils.EMPTY_INT_ARRAY,
			facetSummaryDto,
			referenceSummaryDto
		);

		final GrpcExtraResults extraResults = GrpcExtraResultsBuilder.buildExtraResults(response);

		// both gRPC fields must be populated — the deprecated field for the deprecated constraint,
		// and the canonical field for the canonical constraint
		assertFalse(
			extraResults.getFacetGroupStatisticsList().isEmpty(),
			"Deprecated facetGroupStatistics must be populated when facetSummary() is in the query"
		);
		assertFalse(
			extraResults.getReferenceGroupStatisticsList().isEmpty(),
			"Canonical referenceGroupStatistics must be populated when referenceSummary() is in the query"
		);

		// verify correct content in each field
		assertEquals(1, extraResults.getFacetGroupStatisticsCount());
		assertEquals(2, extraResults.getFacetGroupStatistics(0).getFacetStatisticsCount());

		assertEquals(1, extraResults.getReferenceGroupStatisticsCount());
		assertEquals(2, extraResults.getReferenceGroupStatistics(0).getFacetStatisticsCount());
	}

	@Test
	void buildExtraResults() {
		final String type = "test1";
		final Query query = Query.query(
			collection("test"),
			require(
				entityFetch(
					dataInLocales(Locale.US)
				),
				attributeHistogram(20, type),
				hierarchyOfSelf(
					fromRoot(
						"megaMenu",
						entityFetchAll(),
						statistics(StatisticsType.CHILDREN_COUNT, StatisticsType.QUERIED_ENTITY_COUNT)
					)
				),
				facetSummary()
			)
		);
		final Histogram histogram = new Histogram(
			new Bucket[]{
				new Bucket(BigDecimal.valueOf(1.5), 3, false, new BigDecimal("10.71")),
				new Bucket(BigDecimal.valueOf(2.5), 5, true, new BigDecimal("17.86")),
				new Bucket(BigDecimal.valueOf(3.5), 4, true, new BigDecimal("14.29")),
				new Bucket(BigDecimal.valueOf(4.8), 6, false, new BigDecimal("21.43")),
				new Bucket(BigDecimal.valueOf(8.6), 10, false, new BigDecimal("35.71")),
			},
			BigDecimal.valueOf(10)
		);
		final EvitaEntityResponse<EntityReferenceContract> response = new EvitaEntityResponse<>(
			query,
			new PaginatedList<>(0, 0, 0),
			ArrayUtils.EMPTY_INT_ARRAY,
			new QueryTelemetry(
				QueryPhase.OVERALL
			).finish(),
			new PriceHistogram(histogram),
			new AttributeHistogram(Map.of(type, histogram)),
			new Hierarchy(
				Map.of(
					"megaMenu",
					Arrays.asList(
						new LevelInfo(
							createHierarchyEntity("test1", 1, "e"), false, 14, 0, Collections.emptyList()
						),
						new LevelInfo(
							createHierarchyEntity("test1", 2, "f"), true, 9, 0, Collections.emptyList()
						)
					)
				),
				Collections.emptyMap()
			),
			new FacetSummary(
				Collections.singletonList(
					new FacetGroupStatistics(
						REFENCE_SCHEMA, createGroupEntity("testGroup1"), 10,
						Arrays.asList(
							new FacetStatistics(
								createFacetEntity("test1", 1, "a"), false, 45, null
							),
							new FacetStatistics(
								createFacetEntity("test1", 2, "b"), false, 32, null
							)
						)
					)
				)
			)
		);

		final GrpcExtraResults extraResults = GrpcExtraResultsBuilder.buildExtraResults(response);

		assertNotNull(extraResults.getQueryTelemetry());

		assertTrue(extraResults.hasPriceHistogram());
		assertEquals(28, extraResults.getPriceHistogram().getOverallCount());

		assertEquals(1, extraResults.getAttributeHistogramCount());
		assertEquals(28, extraResults.getAttributeHistogramMap().values().iterator().next().getOverallCount());

		assertFalse(extraResults.getSelfHierarchy().getHierarchyMap().isEmpty());

		final GrpcLevelInfos megaMenu = extraResults.getSelfHierarchy().getHierarchyMap().get("megaMenu");
		assertNotNull(megaMenu);
		assertEquals(2, megaMenu.getLevelInfosCount());

		assertEquals(1, extraResults.getFacetGroupStatisticsCount());
		assertFalse(extraResults.getFacetGroupStatisticsList().isEmpty());
		assertNotNull(extraResults.getFacetGroupStatistics(0).getGroupEntity());
		assertEquals(2, extraResults.getFacetGroupStatistics(0).getFacetStatisticsCount());
	}
}
