/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.extraResult.Histogram;
import io.evitadb.api.requestResponse.extraResult.HistogramContract;
import io.evitadb.api.requestResponse.extraResult.HistogramContract.Bucket;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary.ReferenceGroupStatistics;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedReferenceIndexType;
import io.evitadb.dataType.Scope;
import io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter;
import io.evitadb.externalApi.grpc.generated.GrpcExtraResults;
import io.evitadb.externalApi.grpc.generated.GrpcHistogram;
import io.evitadb.externalApi.grpc.generated.GrpcReferenceGroupStatistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.evitadb.externalApi.grpc.builders.query.extraResults.GrpcReferenceSummaryBuilderTest.createFacetEntity;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Data-structure conversion tests for the reference-histogram shape. Verifies that:
 *
 * - {@link GrpcHistogramBuilder#buildHistogram} carries {@code min}, {@code max},
 *   {@code overallCount}, every bucket field, and both optional anchor entities
 *   ({@code minReferencedEntity} / {@code maxReferencedEntity}) into the
 *   {@link GrpcHistogram} proto form;
 * - {@link GrpcReferenceSummaryBuilder#buildReferenceSummary} wires the
 *   per-group {@code histogramStatistics} map into
 *   {@link GrpcReferenceGroupStatistics#getHistogramStatisticsMap()}, keyed by the
 *   histogram index name, independently for each group.
 *
 * These tests operate at the proto-serialization boundary only. They do not exercise the
 * deserialization path in {@link io.evitadb.externalApi.grpc.requestResponse.ResponseConverter}
 * because {@code toReferenceSummary(...)} is package-private and requires a catalog schema
 * fetcher + {@link io.evitadb.api.query.require.EntityFetch} context that is not trivially
 * reproducible in a unit test. The shape-level assertions below are sufficient to pin the
 * serialization contract; any divergence in the deserializer would surface through the
 * functional / external-API tests that round-trip through a live gRPC channel.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Reference histogram — gRPC conversion")
class GrpcReferenceHistogramConversionTest {

	private static final String REF_PARAMETER = "parameter";
	private static final String TYPE_PARAMETER_GROUP = "parameterGroup";
	private static final String TYPE_PARAMETER_VALUE = "parameterValue";
	private static final String HISTOGRAM_PRICE_BUCKET = "priceBucket";
	private static final String HISTOGRAM_QUANTITY_BUCKET = "quantityBucket";

	private static final Bucket[] BUCKETS = new Bucket[]{
		new Bucket(new BigDecimal("1.50"), 3, false, new BigDecimal("10.71")),
		new Bucket(new BigDecimal("2.50"), 5, true, new BigDecimal("17.86")),
		new Bucket(new BigDecimal("3.50"), 4, true, new BigDecimal("14.29")),
		new Bucket(new BigDecimal("4.80"), 6, false, new BigDecimal("21.43")),
		new Bucket(new BigDecimal("8.60"), 10, false, new BigDecimal("35.71"))
	};

	/**
	 * Verifies that a {@link Histogram} carrying both anchor entities survives serialization
	 * to {@link GrpcHistogram} with every field populated, including the optional
	 * {@code minReferencedEntity} / {@code maxReferencedEntity} payloads.
	 */
	@Test
	@DisplayName("should serialize anchor entities alongside buckets")
	void shouldSerializeHistogramWithAnchorEntities() {
		final SealedEntity minEntity = createFacetEntity(TYPE_PARAMETER_VALUE, 101, "lowest");
		final SealedEntity maxEntity = createFacetEntity(TYPE_PARAMETER_VALUE, 202, "highest");

		final Histogram histogram = new Histogram(BUCKETS, new BigDecimal("10.00"), minEntity, maxEntity);
		final GrpcHistogram grpc = GrpcHistogramBuilder.buildHistogram(histogram, null);

		assertEquals(histogram.getMin(), EvitaDataTypesConverter.toBigDecimal(grpc.getMin()));
		assertEquals(histogram.getMax(), EvitaDataTypesConverter.toBigDecimal(grpc.getMax()));
		assertEquals(histogram.getOverallCount(), grpc.getOverallCount());
		assertEquals(histogram.getBuckets().length, grpc.getBucketsCount());

		assertTrue(grpc.hasMinReferencedEntity(), "Min anchor must be present in gRPC form");
		assertTrue(grpc.hasMaxReferencedEntity(), "Max anchor must be present in gRPC form");
		assertEquals(minEntity.getPrimaryKey(), grpc.getMinReferencedEntity().getPrimaryKey());
		assertEquals(maxEntity.getPrimaryKey(), grpc.getMaxReferencedEntity().getPrimaryKey());
		assertEquals(TYPE_PARAMETER_VALUE, grpc.getMinReferencedEntity().getEntityType());
		assertEquals(TYPE_PARAMETER_VALUE, grpc.getMaxReferencedEntity().getEntityType());
	}

	/**
	 * Verifies that a {@link Histogram} without anchor entities leaves both gRPC fields
	 * unset — the absence must be distinguishable from the presence case.
	 */
	@Test
	@DisplayName("should leave anchor entity fields unset when histogram has none")
	void shouldOmitAnchorEntitiesWhenHistogramHasNone() {
		final Histogram histogram = new Histogram(BUCKETS, new BigDecimal("10.00"));
		final GrpcHistogram grpc = GrpcHistogramBuilder.buildHistogram(histogram, null);

		assertFalse(grpc.hasMinReferencedEntity(), "Min anchor must be absent when histogram has none");
		assertFalse(grpc.hasMaxReferencedEntity(), "Max anchor must be absent when histogram has none");
		assertEquals(histogram.getBuckets().length, grpc.getBucketsCount());
	}

	/**
	 * Verifies that {@link GrpcReferenceSummaryBuilder} serializes the per-group
	 * {@code histogramStatistics} map into the canonical {@link GrpcReferenceGroupStatistics}
	 * form, keyed by histogram index name, independently per group.
	 */
	@Test
	@DisplayName("should serialize per-group histogramStatistics map into gRPC form")
	void shouldSerializeHistogramStatisticsPerGroup() {
		final ReferenceSchema paramSchema = buildParameterReferenceSchema();

		final Histogram priceHistogram = new Histogram(
			BUCKETS, new BigDecimal("10.00"),
			createFacetEntity(TYPE_PARAMETER_VALUE, 11, "cheapest"),
			createFacetEntity(TYPE_PARAMETER_VALUE, 19, "priciest")
		);
		final Histogram quantityHistogram = new Histogram(BUCKETS, new BigDecimal("10.00"));

		final ReferenceSummary referenceSummary = new ReferenceSummary(
			List.of(
				newGroupStatistics(paramSchema, 1, Map.of(
					HISTOGRAM_PRICE_BUCKET, priceHistogram,
					HISTOGRAM_QUANTITY_BUCKET, quantityHistogram
				)),
				newGroupStatistics(paramSchema, 2, Map.of(
					HISTOGRAM_PRICE_BUCKET, priceHistogram
				)),
				newGroupStatistics(paramSchema, 3, Collections.emptyMap())
			)
		);

		final GrpcExtraResults.Builder extraResults = GrpcExtraResults.newBuilder();
		GrpcReferenceSummaryBuilder.buildReferenceSummary(extraResults, referenceSummary, null);

		final List<GrpcReferenceGroupStatistics> groups = extraResults.getReferenceGroupStatisticsList();
		assertEquals(3, groups.size());

		final Map<Integer, GrpcReferenceGroupStatistics> byGroupPk = new LinkedHashMap<>();
		for (final GrpcReferenceGroupStatistics g : groups) {
			byGroupPk.put(g.getGroupEntityReference().getPrimaryKey(), g);
		}

		final GrpcReferenceGroupStatistics group1 = byGroupPk.get(1);
		assertNotNull(group1);
		final Map<String, GrpcHistogram> group1Histograms = group1.getHistogramStatisticsMap();
		assertEquals(2, group1Histograms.size());
		assertTrue(group1Histograms.containsKey(HISTOGRAM_PRICE_BUCKET));
		assertTrue(group1Histograms.containsKey(HISTOGRAM_QUANTITY_BUCKET));
		assertTrue(group1Histograms.get(HISTOGRAM_PRICE_BUCKET).hasMinReferencedEntity(),
			"Histogram built with anchor entities must serialize them on the group");
		assertFalse(group1Histograms.get(HISTOGRAM_QUANTITY_BUCKET).hasMinReferencedEntity(),
			"Histogram built without anchor entities must not get them added during gRPC conversion");

		// Per-bucket value round-trip check on group 1's priceBucket histogram — previously only
		// bucket *count* was verified, which would falsely pass even if the bucket serializer
		// dropped or scrambled the payload. Verify every field survives the conversion.
		final GrpcHistogram group1PriceGrpc = group1Histograms.get(HISTOGRAM_PRICE_BUCKET);
		assertEquals(
			BUCKETS.length, group1PriceGrpc.getBucketsCount(),
			"Bucket array length must be preserved through gRPC conversion"
		);
		for (int i = 0; i < BUCKETS.length; i++) {
			final Bucket source = BUCKETS[i];
			final GrpcHistogram.GrpcBucket grpcBucket = group1PriceGrpc.getBuckets(i);
			assertEquals(
				source.threshold(),
				EvitaDataTypesConverter.toBigDecimal(grpcBucket.getThreshold()),
				"Bucket[" + i + "] threshold must survive serialization"
			);
			assertEquals(
				source.occurrences(), grpcBucket.getOccurrences(),
				"Bucket[" + i + "] occurrences must survive serialization"
			);
			assertEquals(
				source.requested(), grpcBucket.getRequested(),
				"Bucket[" + i + "] requested flag must survive serialization"
			);
			assertEquals(
				source.relativeFrequency(),
				EvitaDataTypesConverter.toBigDecimal(grpcBucket.getRelativeFrequency()),
				"Bucket[" + i + "] relativeFrequency must survive serialization"
			);
		}

		final GrpcReferenceGroupStatistics group2 = byGroupPk.get(2);
		assertNotNull(group2);
		assertEquals(1, group2.getHistogramStatisticsCount());

		final GrpcReferenceGroupStatistics group3 = byGroupPk.get(3);
		assertNotNull(group3);
		assertEquals(0, group3.getHistogramStatisticsCount(),
			"Group without histograms must serialize an empty histogramStatistics map");
	}

	@Nonnull
	private static ReferenceSchema buildParameterReferenceSchema() {
		return ReferenceSchema._internalBuild(
			REF_PARAMETER, TYPE_PARAMETER_VALUE, false, Cardinality.ONE_OR_MORE,
			TYPE_PARAMETER_GROUP, false,
			new ScopedReferenceIndexType[]{
				new ScopedReferenceIndexType(Scope.DEFAULT_SCOPE, ReferenceIndexType.FOR_FILTERING)
			},
			new Scope[]{Scope.LIVE}
		);
	}

	@Nonnull
	private static ReferenceGroupStatistics newGroupStatistics(
		@Nonnull ReferenceSchema schema,
		int groupPk,
		@Nonnull Map<String, HistogramContract> histograms
	) {
		return new ReferenceGroupStatistics(
			schema,
			new io.evitadb.api.requestResponse.data.structure.EntityReference(TYPE_PARAMETER_GROUP, groupPk),
			0,
			List.of(),
			histograms
		);
	}
}
