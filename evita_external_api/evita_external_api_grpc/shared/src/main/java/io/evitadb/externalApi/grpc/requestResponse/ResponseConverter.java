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

package io.evitadb.externalApi.grpc.requestResponse;

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.QueryUtils;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.query.require.EntityGroupFetch;
import io.evitadb.api.query.require.FacetSummaryOfReference;
import io.evitadb.api.query.require.HierarchyOfReference;
import io.evitadb.api.query.require.HierarchyOfSelf;
import io.evitadb.api.query.require.HierarchyRequireConstraint;
import io.evitadb.api.query.require.ReferenceSummaryOfReference;
import io.evitadb.api.query.require.RootHierarchyConstraint;
import io.evitadb.api.requestResponse.EvitaEntityResponse;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.EvitaResponseExtraResult;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.extraResult.AttributeHistogram;
import io.evitadb.api.requestResponse.extraResult.FacetSummary;
import io.evitadb.api.requestResponse.extraResult.FacetSummary.FacetGroupStatistics;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary.FacetStatistics;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary.ReferenceGroupStatistics;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary.RequestImpact;
import io.evitadb.api.requestResponse.extraResult.Hierarchy;
import io.evitadb.api.requestResponse.extraResult.Hierarchy.LevelInfo;
import io.evitadb.api.requestResponse.extraResult.Histogram;
import io.evitadb.api.requestResponse.extraResult.HistogramContract;
import io.evitadb.api.requestResponse.extraResult.HistogramContract.Bucket;
import io.evitadb.api.requestResponse.extraResult.PriceHistogram;
import io.evitadb.api.requestResponse.extraResult.FormulaPlan;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry.StepMetric;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.dataType.DataChunk;
import io.evitadb.dataType.PaginatedList;
import io.evitadb.dataType.StripList;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.externalApi.grpc.generated.*;
import io.evitadb.externalApi.grpc.generated.GrpcHistogram.GrpcBucket;
import io.evitadb.externalApi.grpc.requestResponse.data.EntityConverter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import static io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter.toBigDecimal;
import static io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter.toOffsetDateTime;
import static io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter.toQueryPhase;
import static io.evitadb.externalApi.grpc.requestResponse.data.EntityConverter.SEALED_ENTITY_TYPE_CONVERTER;
import static io.evitadb.externalApi.grpc.requestResponse.data.EntityConverter.toEntityReference;

/**
 * This class is used to translate {@link GrpcQueryResponse} to a sub-object of {@link EvitaEntityResponse}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class ResponseConverter {

	/**
	 * Converts {@link GrpcQueryResponse} to {@link DataChunk} using proper implementation - either {@link PaginatedList}
	 * or {@link StripList} depending on the information in the response.
	 */
	@Nonnull
	public static <T extends Serializable> DataChunk<T> convertToDataChunk(
		@Nonnull GrpcQueryResponse grpcResponse,
		@Nonnull Function<GrpcDataChunk, List<T>> converter
	) {
		final GrpcDataChunk grpcRecordPage = grpcResponse.getRecordPage();
		if (grpcRecordPage.hasPaginatedList()) {
			final GrpcPaginatedList grpcPaginatedList = grpcRecordPage.getPaginatedList();
			return new PaginatedList<>(
				grpcPaginatedList.getPageNumber(),
				grpcPaginatedList.getLastPageNumber(),
				grpcPaginatedList.getPageSize(),
				grpcRecordPage.getTotalRecordCount(),
				converter.apply(grpcRecordPage)
			);
		} else if (grpcRecordPage.hasStripList()) {
			final GrpcStripList grpcStripList = grpcRecordPage.getStripList();
			return new StripList<>(
				grpcStripList.getOffset(),
				grpcStripList.getLimit(),
				grpcRecordPage.getTotalRecordCount(),
				converter.apply(grpcRecordPage)
			);
		} else {
			throw new GenericEvitaInternalError(
				"Only PaginatedList or StripList expected, but got none!"
			);
		}
	}

	/**
	 * The method is used to convert {@link GrpcExtraResults} to list of appropriate {@link EvitaResponseExtraResult}
	 */
	@Nonnull
	public static EvitaResponseExtraResult[] toExtraResults(
		@Nonnull Function<GrpcSealedEntity, SealedEntitySchema> entitySchemaFetcher,
		@Nonnull EvitaRequest evitaRequest,
		@Nonnull GrpcExtraResults extraResults
	) {
		final List<EvitaResponseExtraResult> result = new LinkedList<>();
		if (extraResults.hasQueryTelemetry()) {
			result.add(
				toQueryTelemetry(
					extraResults.getQueryTelemetry()
				)
			);
		}
		if (extraResults.hasPriceHistogram()) {
			result.add(
				new PriceHistogram(
					toHistogram(extraResults.getPriceHistogram())
				)
			);
		}
		if (extraResults.getAttributeHistogramCount() > 0) {
			result.add(
				new AttributeHistogram(
					extraResults.getAttributeHistogramMap()
						.entrySet()
						.stream()
						.collect(
							Collectors.toMap(
								Entry::getKey,
								it -> toHistogram(it.getValue())
							)
						)
				)
			);
		}
		if (extraResults.hasSelfHierarchy() || extraResults.getHierarchyCount() > 0) {
			final List<RootHierarchyConstraint> hierarchyConstraints = QueryUtils.findRequires(
				evitaRequest.getQuery(), RootHierarchyConstraint.class
			);
			result.add(
				new Hierarchy(
					extraResults.hasSelfHierarchy() ?
						toHierarchy(
							entitySchemaFetcher, evitaRequest,
							hierarchyConstraints.stream().filter(HierarchyOfSelf.class::isInstance).findFirst().orElseThrow(),
							extraResults.getSelfHierarchy()
						) : null,
					extraResults.getHierarchyMap()
						.entrySet()
						.stream()
						.collect(
							Collectors.toMap(
								Entry::getKey,
								it -> toHierarchy(
									entitySchemaFetcher,
									evitaRequest,
									hierarchyConstraints.stream()
										.filter(HierarchyOfReference.class::isInstance)
										.map(HierarchyOfReference.class::cast)
										.filter(hor -> Arrays.stream(hor.getReferenceNames()).anyMatch(refName -> Objects.equals(refName, it.getKey())))
										.findFirst()
										.orElseThrow(),
									it.getValue()
								)
							)
						)
				)
			);
		}
		if (extraResults.getReferenceGroupStatisticsCount() > 0) {
			result.add(
				toReferenceSummary(
					entitySchemaFetcher,
					evitaRequest,
					extraResults
				)
			);
		} else if (extraResults.getFacetGroupStatisticsCount() > 0) {
			// fallback for backward-compatibility
			result.add(
				toFacetSummary(
					entitySchemaFetcher,
					evitaRequest,
					extraResults
				)
			);
		}

		return result.toArray(EvitaResponseExtraResult[]::new);
	}

	@Nonnull
	private static ReferenceSummary toReferenceSummary(
		@Nonnull Function<GrpcSealedEntity, SealedEntitySchema> entitySchemaFetcher,
		@Nonnull EvitaRequest evitaRequest,
		@Nonnull GrpcExtraResults extraResults
	) {
		final io.evitadb.api.query.require.ReferenceSummary referenceSummaryRequirementDefaults =
			QueryUtils.findRequire(
				evitaRequest.getQuery(),
				io.evitadb.api.query.require.ReferenceSummary.class
			);

		final EntityFetch defaultEntityFetch = Optional.ofNullable(referenceSummaryRequirementDefaults)
			.map(io.evitadb.api.query.require.ReferenceSummary::getReferenceEntityRequirement)
			.filter(Optional::isPresent)
			.map(Optional::get)
			.orElse(null);
		final EntityGroupFetch defaultEntityGroupFetch = Optional.ofNullable(referenceSummaryRequirementDefaults)
			.map(io.evitadb.api.query.require.ReferenceSummary::getGroupEntityRequirement)
			.filter(Optional::isPresent)
			.map(Optional::get)
			.orElse(null);

		final Map<String, ReferenceSummaryOfReference> facetSummaryRequestIndex = QueryUtils.findRequires(
				evitaRequest.getQuery(),
				ReferenceSummaryOfReference.class
			)
			.stream()
			.collect(
				Collectors.toMap(
					ReferenceSummaryOfReference::getReferenceName,
					Function.identity()
				)
			);

		return new ReferenceSummary(
			extraResults.getReferenceGroupStatisticsList()
				.stream()
				.map(it -> {
					final String referenceName = it.getReferenceName();
					final EntityFetch entityFetch = Optional.ofNullable(facetSummaryRequestIndex.get(referenceName))
						.map(io.evitadb.api.query.require.ReferenceSummaryOfReference::getReferenceEntityRequirement)
						.filter(Optional::isPresent)
						.map(Optional::get)
						.orElse(defaultEntityFetch);
					final EntityGroupFetch entityGroupFetch = Optional.ofNullable(facetSummaryRequestIndex.get(referenceName))
						.map(io.evitadb.api.query.require.ReferenceSummaryOfReference::getGroupEntityRequirement)
						.filter(Optional::isPresent)
						.map(Optional::get)
						.orElse(defaultEntityGroupFetch);

					return toReferenceGroupStatistics(
						entitySchemaFetcher, evitaRequest,
						entityFetch, entityGroupFetch,
						it
					);
				})
				.toList()
		);
	}

	@Nonnull
	private static FacetSummary toFacetSummary(
		@Nonnull Function<GrpcSealedEntity, SealedEntitySchema> entitySchemaFetcher,
		@Nonnull EvitaRequest evitaRequest,
		@Nonnull GrpcExtraResults extraResults
	) {
		final io.evitadb.api.query.require.FacetSummary facetSummaryRequirementDefaults =
			QueryUtils.findRequire(
				evitaRequest.getQuery(),
				io.evitadb.api.query.require.FacetSummary.class
			);

		final EntityFetch defaultEntityFetch = Optional.ofNullable(facetSummaryRequirementDefaults)
			.map(io.evitadb.api.query.require.FacetSummary::getFacetEntityRequirement)
			.filter(Optional::isPresent)
			.map(Optional::get)
			.orElse(null);
		final EntityGroupFetch defaultEntityGroupFetch = Optional.ofNullable(facetSummaryRequirementDefaults)
			.map(io.evitadb.api.query.require.FacetSummary::getGroupEntityRequirement)
			.filter(Optional::isPresent)
			.map(Optional::get)
			.orElse(null);

		final Map<String, FacetSummaryOfReference> facetSummaryRequestIndex = QueryUtils.findRequires(
				evitaRequest.getQuery(),
				FacetSummaryOfReference.class
			)
			.stream()
			.collect(
				Collectors.toMap(
					FacetSummaryOfReference::getReferenceName,
					Function.identity()
				)
			);

		return new FacetSummary(
			extraResults.getFacetGroupStatisticsList()
				.stream()
				.map(it -> {
					final String referenceName = it.getReferenceName();
					final EntityFetch entityFetch = Optional.ofNullable(facetSummaryRequestIndex.get(referenceName))
						.map(io.evitadb.api.query.require.FacetSummaryOfReference::getFacetEntityRequirement)
						.filter(Optional::isPresent)
						.map(Optional::get)
						.orElse(defaultEntityFetch);
					final EntityGroupFetch entityGroupFetch = Optional.ofNullable(facetSummaryRequestIndex.get(referenceName))
						.map(io.evitadb.api.query.require.FacetSummaryOfReference::getGroupEntityRequirement)
						.filter(Optional::isPresent)
						.map(Optional::get)
						.orElse(defaultEntityGroupFetch);

					return toFacetGroupStatistics(
						entitySchemaFetcher, evitaRequest,
						entityFetch, entityGroupFetch,
						it
					);
				})
				.toList()
		);
	}

	/**
	 * Method converts {@link GrpcReferenceGroupStatistics} to {@link ReferenceGroupStatistics}.
	 */
	@Nonnull
	private static ReferenceGroupStatistics toReferenceGroupStatistics(
		@Nonnull Function<GrpcSealedEntity, SealedEntitySchema> entitySchemaFetcher,
		@Nonnull EvitaRequest evitaRequest,
		@Nullable EntityFetch entityFetch,
		@Nullable EntityGroupFetch entityGroupFetch,
		@Nonnull GrpcReferenceGroupStatistics grpcReferenceGroupStatistics
	) {
		// deserialize histogram statistics (backward-compatible: map is empty when server does not send them)
		final Map<String, GrpcHistogram> grpcHistograms = grpcReferenceGroupStatistics.getHistogramStatisticsMap();
		final Map<String, HistogramContract> histogramStatistics;
		if (grpcHistograms.isEmpty()) {
			histogramStatistics = Collections.emptyMap();
		} else {
			histogramStatistics = new LinkedHashMap<>(grpcHistograms.size());
			for (Map.Entry<String, GrpcHistogram> entry : grpcHistograms.entrySet()) {
				histogramStatistics.put(
					entry.getKey(),
					toHistogram(entitySchemaFetcher, evitaRequest, entityFetch, entry.getValue())
				);
			}
		}

		return new ReferenceGroupStatistics(
			grpcReferenceGroupStatistics.getReferenceName(),
			grpcReferenceGroupStatistics.hasGroupEntity() ?
				EntityConverter.toEntity(
					entitySchemaFetcher,
					evitaRequest.deriveCopyWith(
						grpcReferenceGroupStatistics.getGroupEntity().getEntityType(), entityGroupFetch
					),
					grpcReferenceGroupStatistics.getGroupEntity(),
					SealedEntity.class,
					SEALED_ENTITY_TYPE_CONVERTER
				) :
				(grpcReferenceGroupStatistics.hasGroupEntityReference() ? toEntityReference(grpcReferenceGroupStatistics.getGroupEntityReference()) : null),
			grpcReferenceGroupStatistics.getCount(),
			grpcReferenceGroupStatistics.getFacetStatisticsList()
				.stream()
				.map(
					it -> toFacetStatistics(entitySchemaFetcher, evitaRequest, entityFetch, it)
				)
				.collect(
					Collectors.toMap(
						it -> it.getFacetEntity().getPrimaryKey(),
						Function.identity(),
						(o, o2) -> {
							throw new GenericEvitaInternalError("Duplicate facet statistics for entity " + o.getFacetEntity().getPrimaryKey());
						},
						LinkedHashMap::new
					)
				),
			histogramStatistics
		);
	}

	/**
	 * Method converts {@link GrpcFacetGroupStatistics} to {@link FacetGroupStatistics}.
	 */
	@Nonnull
	private static FacetGroupStatistics toFacetGroupStatistics(
		@Nonnull Function<GrpcSealedEntity, SealedEntitySchema> entitySchemaFetcher,
		@Nonnull EvitaRequest evitaRequest,
		@Nullable EntityFetch entityFetch,
		@Nullable EntityGroupFetch entityGroupFetch,
		@Nonnull GrpcFacetGroupStatistics grpcFacetGroupStatistics
	) {
		return new FacetGroupStatistics(
			grpcFacetGroupStatistics.getReferenceName(),
			grpcFacetGroupStatistics.hasGroupEntity() ?
				EntityConverter.toEntity(
					entitySchemaFetcher,
					evitaRequest.deriveCopyWith(
						grpcFacetGroupStatistics.getGroupEntity().getEntityType(), entityGroupFetch
					),
					grpcFacetGroupStatistics.getGroupEntity(),
					SealedEntity.class,
					SEALED_ENTITY_TYPE_CONVERTER
				) :
				(grpcFacetGroupStatistics.hasGroupEntityReference() ? toEntityReference(grpcFacetGroupStatistics.getGroupEntityReference()) : null),
			grpcFacetGroupStatistics.getCount(),
			grpcFacetGroupStatistics.getFacetStatisticsList()
				.stream()
				.map(
					it -> toFacetStatistics(entitySchemaFetcher, evitaRequest, entityFetch, it)
				)
				.collect(
					Collectors.toMap(
						it -> it.getFacetEntity().getPrimaryKey(),
						Function.identity(),
						(o, o2) -> {
							throw new GenericEvitaInternalError("Duplicate facet statistics for entity " + o.getFacetEntity().getPrimaryKey());
						},
						LinkedHashMap::new
					)
				)
		);
	}

	/**
	 * Method converts {@link GrpcFacetStatistics} to {@link FacetStatistics}.
	 */
	@Nonnull
	private static FacetStatistics toFacetStatistics(
		@Nonnull Function<GrpcSealedEntity, SealedEntitySchema> entitySchemaFetcher,
		@Nonnull EvitaRequest evitaRequest,
		@Nullable EntityFetch entityFetch,
		@Nonnull GrpcFacetStatistics grpcFacetStatistics
	) {
		return new FacetStatistics(
			grpcFacetStatistics.hasFacetEntity() ?
				EntityConverter.toEntity(
					entitySchemaFetcher,
					evitaRequest.deriveCopyWith(grpcFacetStatistics.getFacetEntity().getEntityType(), entityFetch),
					grpcFacetStatistics.getFacetEntity(),
					SealedEntity.class,
					SEALED_ENTITY_TYPE_CONVERTER
				) :
				toEntityReference(grpcFacetStatistics.getFacetEntityReference()),
			grpcFacetStatistics.getRequested(),
			grpcFacetStatistics.getCount(),
			grpcFacetStatistics.hasImpact() && grpcFacetStatistics.hasMatchCount() ?
				new RequestImpact(
					grpcFacetStatistics.getImpact().getValue(),
					grpcFacetStatistics.getMatchCount().getValue(),
					grpcFacetStatistics.getHasSense()
				) :
				null
		);
	}

	/**
	 * Method converts {@link GrpcHierarchy} to map of named lists of {@link LevelInfo}.
	 */
	@Nonnull
	private static Map<String, List<LevelInfo>> toHierarchy(
		@Nonnull Function<GrpcSealedEntity, SealedEntitySchema> entitySchemaFetcher,
		@Nonnull EvitaRequest evitaRequest,
		@Nonnull RootHierarchyConstraint rootHierarchyConstraint,
		@Nonnull GrpcHierarchy grpcHierarchy
	) {
		return grpcHierarchy
			.getHierarchyMap()
			.entrySet()
			.stream()
			.collect(
				Collectors.toMap(
					Entry::getKey,
					it -> {
						final Constraint<?> hierarchyConstraint = QueryUtils.findConstraint(
							rootHierarchyConstraint,
							cnt -> cnt instanceof HierarchyRequireConstraint hrc && Objects.equals(it.getKey(), hrc.getOutputName())
						);
						final EntityFetch entityFetch = QueryUtils.findConstraint(hierarchyConstraint, EntityFetch.class);
						return it.getValue().getLevelInfosList()
							.stream()
							.map(x -> toLevelInfo(entitySchemaFetcher, evitaRequest, entityFetch, x))
							.collect(Collectors.toList());
					}
				)
			);
	}

	/**
	 * Method converts {@link GrpcLevelInfo} to {@link LevelInfo}.
	 */
	@Nonnull
	private static LevelInfo toLevelInfo(
		@Nonnull Function<GrpcSealedEntity, SealedEntitySchema> entitySchemaFetcher,
		@Nonnull EvitaRequest evitaRequest,
		@Nullable EntityFetch entityFetch,
		@Nonnull GrpcLevelInfo grpcLevelInfo
	) {
		return new LevelInfo(
			grpcLevelInfo.hasEntity() ?
				EntityConverter.toEntity(
					entitySchemaFetcher,
					evitaRequest.deriveCopyWith(
						grpcLevelInfo.getEntity().getEntityType(),
						entityFetch
					),
					grpcLevelInfo.getEntity(),
					SealedEntity.class,
					SEALED_ENTITY_TYPE_CONVERTER
				) :
				toEntityReference(grpcLevelInfo.getEntityReference()),
			grpcLevelInfo.getRequested(),
			grpcLevelInfo.getQueriedEntityCount().isInitialized() ? grpcLevelInfo.getQueriedEntityCount().getValue() : null,
			grpcLevelInfo.getChildrenCount().isInitialized() ? grpcLevelInfo.getChildrenCount().getValue() : null,
			grpcLevelInfo.getItemsList().stream().map(it -> toLevelInfo(entitySchemaFetcher, evitaRequest, entityFetch, it)).collect(Collectors.toList())
		);
	}

	/**
	 * Method converts {@link GrpcQueryTelemetry} to {@link QueryTelemetry}.
	 */
	@Nonnull
	private static QueryTelemetry toQueryTelemetry(@Nonnull GrpcQueryTelemetry grpcQueryTelemetry) {
		final QueryTelemetry result = new QueryTelemetry(
			toQueryPhase(grpcQueryTelemetry.getOperation()),
			grpcQueryTelemetry.getStart(),
			grpcQueryTelemetry.getSpentTime(),
			// only the root step carries the wall-clock stamp that anchors the whole tree in time
			grpcQueryTelemetry.hasStartedAt() ? toOffsetDateTime(grpcQueryTelemetry.getStartedAt()) : null,
			grpcQueryTelemetry.getArgumentsList().toArray(String[]::new),
			grpcQueryTelemetry.getStepsList().stream().map(ResponseConverter::toQueryTelemetry).toArray(QueryTelemetry[]::new)
		);
		// unlike selfTime, which the server derives and the client can derive just as well, metrics are measured -
		// so they are restored here rather than dropped, which is what keeps a telemetry tree read through the
		// driver equal to the one an embedded caller would have got for the same query
		if (grpcQueryTelemetry.hasMetrics()) {
			restoreMetrics(result, grpcQueryTelemetry.getMetrics());
		}
		// the plan is measured rather than derived too, so it is restored for the same reason - a driver caller
		// asking for `queryTelemetry(PLAN)` gets the same tree an embedded caller would
		if (grpcQueryTelemetry.hasPlan()) {
			result.recordPlan(restorePlan(grpcQueryTelemetry.getPlan()));
		}
		return result;
	}

	/**
	 * Rebuilds a formula plan node, and everything below it, from its gRPC representation.
	 *
	 * Each optional field maps back to `null`, which is the value that means "not computed" - restoring an absent
	 * `actualCost` as `0` would turn a formula the engine deliberately never ran into one that reportedly ran for
	 * free. The recursion terminates at back-reference nodes of its own accord, since they carry no children.
	 *
	 * @param plan the plan node as it arrived on the wire
	 * @return the rebuilt node together with everything nested below it
	 */
	@Nonnull
	private static FormulaPlan restorePlan(@Nonnull GrpcFormulaPlan plan) {
		final List<FormulaPlan> children = new ArrayList<>(plan.getChildrenCount());
		for (final GrpcFormulaPlan child : plan.getChildrenList()) {
			children.add(restorePlan(child));
		}
		return new FormulaPlan(
			plan.getId(),
			plan.hasRefTo() ? plan.getRefTo() : null,
			plan.getHash(),
			plan.hasDescription() ? plan.getDescription() : null,
			plan.getEstimatedCost(),
			plan.hasActualCost() ? plan.getActualCost() : null,
			plan.hasResultCount() ? plan.getResultCount() : null,
			children
		);
	}

	/**
	 * Restores the metrics carried by a {@link GrpcQueryTelemetryMetrics} message onto the telemetry step rebuilt
	 * from it.
	 *
	 * They are recorded after construction rather than passed to the constructor because recording is a plain
	 * mutation with no lifecycle attached to it - a step accepts metrics whether or not it has been finished - and
	 * threading eight optional values through an already six-argument deserialization constructor would obscure it.
	 *
	 * Each field is restored only when the message actually carries it: the metrics are `optional` precisely because
	 * several of them are legitimately `0`, so reading them unconditionally would invent measurements that were
	 * never taken.
	 *
	 * @param queryTelemetry the rebuilt step to record onto
	 * @param metrics        the measurements as they arrived on the wire
	 */
	private static void restoreMetrics(
		@Nonnull QueryTelemetry queryTelemetry,
		@Nonnull GrpcQueryTelemetryMetrics metrics
	) {
		if (metrics.hasEstimatedCardinality()) {
			queryTelemetry.recordMetric(StepMetric.ESTIMATED_CARDINALITY, metrics.getEstimatedCardinality());
		}
		if (metrics.hasActualCardinality()) {
			queryTelemetry.recordMetric(StepMetric.ACTUAL_CARDINALITY, metrics.getActualCardinality());
		}
		if (metrics.hasEstimatedCost()) {
			queryTelemetry.recordMetric(StepMetric.ESTIMATED_COST, metrics.getEstimatedCost());
		}
		if (metrics.hasActualCost()) {
			queryTelemetry.recordMetric(StepMetric.ACTUAL_COST, metrics.getActualCost());
		}
		if (metrics.hasRecordsReturned()) {
			queryTelemetry.recordMetric(StepMetric.RECORDS_RETURNED, metrics.getRecordsReturned());
		}
		if (metrics.hasIoFetchCount()) {
			queryTelemetry.recordMetric(StepMetric.IO_FETCH_COUNT, metrics.getIoFetchCount());
		}
		if (metrics.hasIoFetchedSizeBytes()) {
			queryTelemetry.recordMetric(StepMetric.IO_FETCHED_SIZE_BYTES, metrics.getIoFetchedSizeBytes());
		}
		if (metrics.hasPrefetched()) {
			queryTelemetry.recordMetric(StepMetric.PREFETCHED, metrics.getPrefetched());
		}
	}

	/**
	 * Method converts {@link GrpcHistogram} to {@link Histogram}. Used for attribute / price histograms which
	 * never carry anchor entities.
	 */
	@Nonnull
	private static Histogram toHistogram(@Nonnull GrpcHistogram grpcHistogram) {
		return new Histogram(
			grpcHistogram.getBucketsList()
				.stream()
				.filter(Objects::nonNull)
				.map(ResponseConverter::toBucket)
				.toArray(Bucket[]::new),
			toBigDecimal(grpcHistogram.getMax())
		);
	}

	/**
	 * Method converts {@link GrpcHistogram} to {@link Histogram} including optional anchor referenced entities.
	 * Used for histograms living inside a reference summary where the server may attach the entities whose values
	 * anchor the minimum and maximum buckets. The supplied {@link EntityFetch} describes the fetch requirements
	 * that were asked for the reference.
	 */
	@Nonnull
	private static Histogram toHistogram(
		@Nonnull Function<GrpcSealedEntity, SealedEntitySchema> entitySchemaFetcher,
		@Nonnull EvitaRequest evitaRequest,
		@Nullable EntityFetch entityFetch,
		@Nonnull GrpcHistogram grpcHistogram
	) {
		final Bucket[] buckets = grpcHistogram.getBucketsList()
			.stream()
			.filter(Objects::nonNull)
			.map(ResponseConverter::toBucket)
			.toArray(Bucket[]::new);
		final SealedEntity minReferencedEntity = grpcHistogram.hasMinReferencedEntity()
			? EntityConverter.toEntity(
				entitySchemaFetcher,
				evitaRequest.deriveCopyWith(grpcHistogram.getMinReferencedEntity().getEntityType(), entityFetch),
				grpcHistogram.getMinReferencedEntity(),
				SealedEntity.class,
				SEALED_ENTITY_TYPE_CONVERTER
			)
			: null;
		final SealedEntity maxReferencedEntity = grpcHistogram.hasMaxReferencedEntity()
			? EntityConverter.toEntity(
				entitySchemaFetcher,
				evitaRequest.deriveCopyWith(grpcHistogram.getMaxReferencedEntity().getEntityType(), entityFetch),
				grpcHistogram.getMaxReferencedEntity(),
				SealedEntity.class,
				SEALED_ENTITY_TYPE_CONVERTER
			)
			: null;
		return new Histogram(
			buckets,
			toBigDecimal(grpcHistogram.getMax()),
			minReferencedEntity,
			maxReferencedEntity
		);
	}

	/**
	 * Method converts {@link GrpcBucket} to {@link Bucket}.
	 */
	@Nonnull
	private static Bucket toBucket(@Nonnull GrpcBucket grpcBucket) {
		return new Bucket(
			toBigDecimal(grpcBucket.getThreshold()),
			grpcBucket.getOccurrences(),
			grpcBucket.getRequested(),
			grpcBucket.hasRelativeFrequency() ? toBigDecimal(grpcBucket.getRelativeFrequency()) : BigDecimal.ZERO
		);
	}
}
