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

package io.evitadb.externalApi.grpc.query;

import io.evitadb.api.requestResponse.EvitaEntityResponse;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.EvitaResponseExtraResult;
import io.evitadb.api.requestResponse.extraResult.AttributeHistogram;
import io.evitadb.api.requestResponse.extraResult.FacetSummary;
import io.evitadb.api.requestResponse.extraResult.FacetSummary.FacetGroupStatistics;
import io.evitadb.api.requestResponse.extraResult.FacetSummary.FacetStatistics;
import io.evitadb.api.requestResponse.extraResult.FacetSummary.RequestImpact;
import io.evitadb.api.requestResponse.extraResult.Hierarchy;
import io.evitadb.api.requestResponse.extraResult.Hierarchy.LevelInfo;
import io.evitadb.api.requestResponse.extraResult.Histogram;
import io.evitadb.api.requestResponse.extraResult.HistogramContract.Bucket;
import io.evitadb.api.requestResponse.extraResult.PriceHistogram;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.dataType.DataChunk;
import io.evitadb.dataType.PaginatedList;
import io.evitadb.dataType.StripList;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.externalApi.grpc.generated.*;
import io.evitadb.externalApi.grpc.generated.GrpcHistogram.GrpcBucket;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import static io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter.toBigDecimal;
import static io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter.toQueryPhase;
import static io.evitadb.externalApi.grpc.requestResponse.data.EntityConverter.toEntityReference;
import static io.evitadb.externalApi.grpc.requestResponse.data.EntityConverter.toSealedEntity;

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
			throw new EvitaInternalError(
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
			result.add(
				new Hierarchy(
					toHierarchy(entitySchemaFetcher, evitaRequest, extraResults.getSelfHierarchy()),
					extraResults.getHierarchyMap()
						.entrySet()
						.stream()
						.collect(
							Collectors.toMap(
								Entry::getKey,
								it -> toHierarchy(entitySchemaFetcher, evitaRequest, it.getValue())
							)
						)
				)
			);
		}
		if (extraResults.getFacetGroupStatisticsCount() > 0) {
			result.add(
				new FacetSummary(
					extraResults.getFacetGroupStatisticsList()
						.stream()
						.map(it -> toFacetGroupStatistics(entitySchemaFetcher, evitaRequest, it))
						.toList()
				)
			);
		}
		return result.toArray(EvitaResponseExtraResult[]::new);
	}

	/**
	 * Method converts {@link GrpcFacetGroupStatistics} to {@link FacetGroupStatistics}.
	 */
	@Nonnull
	private static FacetGroupStatistics toFacetGroupStatistics(
		@Nonnull Function<GrpcSealedEntity, SealedEntitySchema> entitySchemaFetcher,
		@Nonnull EvitaRequest evitaRequest,
		@Nonnull GrpcFacetGroupStatistics grpcFacetGroupStatistics
	) {
		return new FacetGroupStatistics(
			grpcFacetGroupStatistics.getReferenceName(),
			grpcFacetGroupStatistics.hasGroupEntity() ?
				toSealedEntity(entitySchemaFetcher, evitaRequest, grpcFacetGroupStatistics.getGroupEntity()) :
				toEntityReference(grpcFacetGroupStatistics.getGroupEntityReference()),
			grpcFacetGroupStatistics.getCount(),
			grpcFacetGroupStatistics.getFacetStatisticsList()
				.stream()
				.map(it -> toFacetStatistics(entitySchemaFetcher, evitaRequest, it))
				.collect(
					Collectors.toMap(
						it -> it.getFacetEntity().getPrimaryKey(),
						Function.identity()
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
		@Nonnull GrpcFacetStatistics grpcFacetStatistics
	) {
		return new FacetStatistics(
			grpcFacetStatistics.hasFacetEntity() ?
				toSealedEntity(entitySchemaFetcher, evitaRequest, grpcFacetStatistics.getFacetEntity()) :
				toEntityReference(grpcFacetStatistics.getFacetEntityReference()),
			grpcFacetStatistics.getRequested(),
			grpcFacetStatistics.getCount(),
			grpcFacetStatistics.hasImpact() && grpcFacetStatistics.hasMatchCount() ?
				new RequestImpact(
					grpcFacetStatistics.getImpact().getValue(),
					grpcFacetStatistics.getMatchCount().getValue()
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
		@Nonnull GrpcHierarchy grpcHierarchy
	) {
		return grpcHierarchy
			.getHierarchyMap()
			.entrySet()
			.stream()
			.collect(
				Collectors.toMap(
					Entry::getKey,
					it -> it.getValue().getLevelInfosList()
						.stream()
						.map(x -> toLevelInfo(entitySchemaFetcher, evitaRequest, x))
						.collect(Collectors.toList())
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
		@Nonnull GrpcLevelInfo grpcLevelInfo
	) {
		return new LevelInfo(
			grpcLevelInfo.hasEntity() ? toSealedEntity(entitySchemaFetcher, evitaRequest, grpcLevelInfo.getEntity()) : toEntityReference(grpcLevelInfo.getEntityReference()),
			grpcLevelInfo.getQueriedEntityCount().isInitialized() ? grpcLevelInfo.getQueriedEntityCount().getValue() : null,
			grpcLevelInfo.getChildrenCount().isInitialized() ? grpcLevelInfo.getChildrenCount().getValue() : null,
			grpcLevelInfo.getItemsList().stream().map(it -> toLevelInfo(entitySchemaFetcher, evitaRequest, it)).collect(Collectors.toList())
		);
	}

	/**
	 * Method converts {@link GrpcQueryTelemetry} to {@link QueryTelemetry}.
	 */
	@Nonnull
	private static QueryTelemetry toQueryTelemetry(@Nonnull GrpcQueryTelemetry grpcQueryTelemetry) {
		return new QueryTelemetry(
			toQueryPhase(grpcQueryTelemetry.getOperation()),
			grpcQueryTelemetry.getStart(),
			grpcQueryTelemetry.getSpentTime(),
			grpcQueryTelemetry.getArgumentsList().toArray(String[]::new),
			grpcQueryTelemetry.getStepsList().stream().map(ResponseConverter::toQueryTelemetry).toArray(QueryTelemetry[]::new)
		);
	}

	/**
	 * Method converts {@link GrpcHistogram} to {@link Histogram}.
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
	 * Method converts {@link GrpcBucket} to {@link Bucket}.
	 */
	@Nonnull
	private static Bucket toBucket(@Nonnull GrpcBucket grpcBucket) {
		return new Bucket(
			grpcBucket.getIndex(),
			toBigDecimal(grpcBucket.getThreshold()),
			grpcBucket.getOccurrences()
		);
	}
}
