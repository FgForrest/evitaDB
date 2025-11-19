/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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

package io.evitadb.externalApi.grpc.services.converter;

import com.google.protobuf.StringValue;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation;
import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.api.requestResponse.schema.mutation.EntitySchemaMutation;
import io.evitadb.api.requestResponse.trafficRecording.*;
import io.evitadb.api.requestResponse.trafficRecording.TrafficRecordingCaptureRequest.TrafficRecordingType;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter;
import io.evitadb.externalApi.grpc.generated.*;
import io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter;
import io.evitadb.externalApi.grpc.requestResponse.data.mutation.DelegatingEntityMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.DelegatingEntitySchemaMutationConverter;

import javax.annotation.Nonnull;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

import static io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter.toGrpcOffsetDateTime;
import static io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter.toGrpcUuid;
import static io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter.toOffsetDateTime;
import static io.evitadb.externalApi.grpc.generated.GrpcTrafficRecord.newBuilder;

/**
 * This class contains conversion methods for CDC (Change Data Capture) requests and responses.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class TrafficCaptureConverter {

	/**
	 * Converts a {@link GetTrafficHistoryRequest} to a {@link TrafficRecordingCaptureRequest}.
	 *
	 * @param request the request to convert
	 * @return the converted request
	 */
	@Nonnull
	public static TrafficRecordingCaptureRequest toTrafficRecordingCaptureRequest(@Nonnull GetTrafficHistoryRequest request) {
		return getTrafficRecordingCaptureRequest(request.getCriteria());
	}

	/**
	 * Converts a {@link GetTrafficHistoryListRequest} to a {@link TrafficRecordingCaptureRequest}.
	 *
	 * @param request the request to convert
	 * @return the converted request
	 */
	@Nonnull
	public static TrafficRecordingCaptureRequest toTrafficRecordingCaptureRequest(@Nonnull GetTrafficHistoryListRequest request) {
		return getTrafficRecordingCaptureRequest(request.getCriteria());
	}

	/**
	 * Converts a given GrpcQueryLabel object to a Label object.
	 *
	 * @param grpcLabel the GrpcQueryLabel object to be converted; must not be null.
	 * @return a Label object created from the provided GrpcQueryLabel.
	 */
	@Nonnull
	public static Label toLabel(@Nonnull GrpcQueryLabel grpcLabel) {
		return new Label(grpcLabel.getName(), grpcLabel.getValue());
	}

	/**
	 * Converts a {@link TrafficRecording} and its {@link TrafficRecordingContent} into a {@link GrpcTrafficRecord}.
	 *
	 * @param trafficRecording the traffic recording to be converted, must not be null
	 * @param content          the content type that determines inclusion of the body details, must not be null
	 * @return the converted gRPC traffic record
	 */
	@Nonnull
	public static GrpcTrafficRecord toGrpcGrpcTrafficRecord(
		@Nonnull TrafficRecording trafficRecording,
		@Nonnull TrafficRecordingContent content
	) {
		final GrpcTrafficRecord.Builder builder = newBuilder()
			.setSessionSequenceOrder(Objects.requireNonNull(trafficRecording.sessionSequenceOrder()))
			.setSessionId(toGrpcUuid(trafficRecording.sessionId()))
			.setRecordSessionOffset(trafficRecording.recordSessionOffset())
			.setSessionRecordsCount(Objects.requireNonNull(trafficRecording.sessionRecordsCount()))
			.setType(EvitaEnumConverter.toGrpcTrafficRecordingType(trafficRecording.type()))
			.setCreated(toGrpcOffsetDateTime(trafficRecording.created()))
			.setDurationInMilliseconds(trafficRecording.durationInMilliseconds())
			.setIoFetchCount(trafficRecording.ioFetchCount())
			.setIoFetchedSizeBytes(trafficRecording.ioFetchedSizeBytes());

		final String finishedWithError = trafficRecording.finishedWithError();
		if (finishedWithError != null) {
			builder.setFinishedWithError(StringValue.newBuilder().setValue(finishedWithError).build());
		}

		if (content == TrafficRecordingContent.BODY) {
			switch (trafficRecording.type()) {
				case SESSION_START -> convertSessionStartContainer(trafficRecording, builder);
				case SESSION_CLOSE -> convertSessionCloseContainer(trafficRecording, builder);
				case SOURCE_QUERY -> convertSourceQueryContainer(trafficRecording, builder);
				case SOURCE_QUERY_STATISTICS -> convertSourceQueryStatisticsContainer(trafficRecording, builder);
				case QUERY -> convertQueryContainer(trafficRecording, builder);
				case FETCH -> convertEntityFetchContainer(trafficRecording, builder);
				case ENRICHMENT -> convertEntityEnrichmentContainer(trafficRecording, builder);
				case MUTATION -> convertMutationContainer(trafficRecording, builder);
			}
		}
		return builder.build();
	}

	/**
	 * Converts a {@link TrafficRecording} of type {@link SessionStartContainer} to its equivalent
	 * gRPC representation and sets it to the provided {@link GrpcTrafficRecord.Builder}.
	 * If the provided {@link TrafficRecording} is not of type {@link SessionStartContainer},
	 * a {@link GenericEvitaInternalError} is thrown.
	 *
	 * @param trafficRecording the traffic recording to be converted, must be of type {@link SessionStartContainer}
	 * @param builder          the gRPC record builder where the converted session start container will be set
	 * @throws GenericEvitaInternalError if the provided trafficRecording is not of type {@link SessionStartContainer}
	 */
	private static void convertSessionStartContainer(@Nonnull TrafficRecording trafficRecording, @Nonnull GrpcTrafficRecord.Builder builder) {
		if (trafficRecording instanceof SessionStartContainer sessionStartContainer) {
			builder.setSessionStart(
				GrpcTrafficSessionStartContainer.newBuilder()
					.setCatalogVersion(sessionStartContainer.catalogVersion())
					.build()
			);
		} else {
			throw new GenericEvitaInternalError("Unsupported traffic recording type: " + trafficRecording.type());
		}
	}

	/**
	 * Converts a {@link TrafficRecording} of type {@link SessionCloseContainer} to its equivalent
	 * gRPC representation and sets it to the provided {@link GrpcTrafficRecord.Builder}.
	 * If the provided {@link TrafficRecording} is not of type {@link SessionCloseContainer},
	 * a {@link GenericEvitaInternalError} is thrown.
	 *
	 * @param trafficRecording the traffic recording to be converted, must be of type {@link SessionCloseContainer}
	 * @param builder          the gRPC record builder where the converted session close container will be set
	 * @throws GenericEvitaInternalError if the provided trafficRecording is not of type {@link SessionCloseContainer}
	 */
	private static void convertSessionCloseContainer(@Nonnull TrafficRecording trafficRecording, @Nonnull GrpcTrafficRecord.Builder builder) {
		if (trafficRecording instanceof SessionCloseContainer sessionCloseContainer) {
			builder.setSessionClose(
				GrpcTrafficSessionCloseContainer.newBuilder()
					.setCatalogVersion(sessionCloseContainer.catalogVersion())
					.setTrafficRecordCount(sessionCloseContainer.trafficRecordCount())
					.setQueryCount(sessionCloseContainer.queryCount())
					.setEntityFetchCount(sessionCloseContainer.entityFetchCount())
					.setMutationCount(sessionCloseContainer.mutationCount())
					.build()
			);
		} else {
			throw new GenericEvitaInternalError("Unsupported traffic recording type: " + trafficRecording.type());
		}
	}

	/**
	 * Converts a {@link TrafficRecording} of type {@link SourceQueryContainer} to its equivalent
	 * gRPC representation and sets it to the provided {@link GrpcTrafficRecord.Builder}.
	 * If the provided {@link TrafficRecording} is not of type {@link SourceQueryContainer},
	 * a {@link GenericEvitaInternalError} is thrown.
	 *
	 * @param trafficRecording the traffic recording to be converted, must be of type {@link SourceQueryContainer}
	 * @param builder          the gRPC record builder where the converted source query container will be set
	 * @throws GenericEvitaInternalError if the provided trafficRecording is not of type {@link SourceQueryContainer}
	 */
	private static void convertSourceQueryContainer(@Nonnull TrafficRecording trafficRecording, @Nonnull GrpcTrafficRecord.Builder builder) {
		if (trafficRecording instanceof SourceQueryContainer sourceQueryContainer) {
			builder.setSourceQuery(
				GrpcTrafficSourceQueryContainer.newBuilder()
					.setSourceQueryId(toGrpcUuid(sourceQueryContainer.sourceQueryId()))
					.setSourceQuery(sourceQueryContainer.sourceQuery())
					.addAllLabels(Arrays.stream(sourceQueryContainer.labels()).map(TrafficCaptureConverter::toGrpcQueryLabel).toList())
					.build()
			);
		} else {
			throw new GenericEvitaInternalError("Unsupported traffic recording type: " + trafficRecording.type());
		}
	}

	/**
	 * Converts a {@link TrafficRecording} of type {@link SourceQueryStatisticsContainer} to its equivalent
	 * gRPC representation and sets it to the provided {@link GrpcTrafficRecord.Builder}.
	 * If the provided {@link TrafficRecording} is not of type {@link SourceQueryStatisticsContainer},
	 * a {@link GenericEvitaInternalError} is thrown.
	 *
	 * @param trafficRecording the traffic recording to be converted, must be of type {@link SourceQueryStatisticsContainer}
	 * @param builder          the gRPC record builder where the converted source query statistics container will be set
	 * @throws GenericEvitaInternalError if the provided {@link TrafficRecording} is not of type {@link SourceQueryStatisticsContainer}
	 */
	private static void convertSourceQueryStatisticsContainer(@Nonnull TrafficRecording trafficRecording, @Nonnull GrpcTrafficRecord.Builder builder) {
		if (trafficRecording instanceof SourceQueryStatisticsContainer sourceQueryContainer) {
			builder.setSourceQueryStatistics(
				GrpcTrafficSourceQueryStatisticsContainer.newBuilder()
					.setSourceQueryId(toGrpcUuid(sourceQueryContainer.sourceQueryId()))
					.setReturnedRecordCount(sourceQueryContainer.returnedRecordCount())
					.setTotalRecordCount(sourceQueryContainer.totalRecordCount())
					.build()
			);
		} else {
			throw new GenericEvitaInternalError("Unsupported traffic recording type: " + trafficRecording.type());
		}
	}

	/**
	 * Converts a {@link TrafficRecording} of type {@link QueryContainer} to its equivalent
	 * gRPC representation and sets it to the provided {@link GrpcTrafficRecord.Builder}.
	 * If the provided {@link TrafficRecording} is not of type {@link QueryContainer},
	 * a {@link GenericEvitaInternalError} is thrown.
	 *
	 * @param trafficRecording the traffic recording to be converted, must be of type {@link QueryContainer}
	 * @param builder          the gRPC record builder where the converted query container will be set
	 * @throws GenericEvitaInternalError if the provided trafficRecording is not of type {@link QueryContainer}
	 */
	private static void convertQueryContainer(@Nonnull TrafficRecording trafficRecording, @Nonnull GrpcTrafficRecord.Builder builder) {
		if (trafficRecording instanceof QueryContainer queryContainer) {
			builder.setQuery(
				GrpcTrafficQueryContainer.newBuilder()
					.addAllLabels(Arrays.stream(queryContainer.labels()).map(TrafficCaptureConverter::toGrpcQueryLabel).toList())
					.setQueryDescription(queryContainer.queryDescription())
					.setQuery(queryContainer.query().prettyPrint())
					.setTotalRecordCount(queryContainer.totalRecordCount())
					.addAllPrimaryKeys(Arrays.stream(queryContainer.primaryKeys()).boxed().toList())
					.build()
			);
		} else {
			throw new GenericEvitaInternalError("Unsupported traffic recording type: " + trafficRecording.type());
		}
	}

	/**
	 * Converts a {@link TrafficRecording} of type {@link EntityFetchContainer} to its equivalent
	 * gRPC representation and sets it to the provided {@link GrpcTrafficRecord.Builder}.
	 * If the provided {@link TrafficRecording} is not of type {@link EntityFetchContainer},
	 * a {@link GenericEvitaInternalError} is thrown.
	 *
	 * @param trafficRecording the traffic recording to be converted, must be of type {@link EntityFetchContainer}
	 * @param builder          the gRPC record builder where the converted entity fetch container will be set
	 * @throws GenericEvitaInternalError if the provided trafficRecording is not of type {@link EntityFetchContainer}
	 */
	private static void convertEntityFetchContainer(@Nonnull TrafficRecording trafficRecording, @Nonnull GrpcTrafficRecord.Builder builder) {
		if (trafficRecording instanceof EntityFetchContainer entityFetchContainer) {
			builder.setFetch(
				GrpcTrafficEntityFetchContainer.newBuilder()
					.setQuery(entityFetchContainer.query().toString())
					.setPrimaryKey(entityFetchContainer.primaryKey())
					.build()
			);
		} else {
			throw new GenericEvitaInternalError("Unsupported traffic recording type: " + trafficRecording.type());
		}
	}

	/**
	 * Converts a {@link TrafficRecording} of type {@link EntityEnrichmentContainer} to its equivalent
	 * gRPC representation and sets it to the provided {@link GrpcTrafficRecord.Builder}.
	 * If the provided {@link TrafficRecording} is not of type {@link EntityEnrichmentContainer},
	 * a {@link GenericEvitaInternalError} is thrown.
	 *
	 * @param trafficRecording the traffic recording to be converted, must be of type {@link EntityEnrichmentContainer}
	 * @param builder          the gRPC record builder where the converted entity enrichment container will be set
	 * @throws GenericEvitaInternalError if the provided trafficRecording is not of type {@link EntityEnrichmentContainer}
	 */
	private static void convertEntityEnrichmentContainer(@Nonnull TrafficRecording trafficRecording, @Nonnull GrpcTrafficRecord.Builder builder) {
		if (trafficRecording instanceof EntityEnrichmentContainer entityEnrichmentContainer) {
			builder.setEnrichment(
				GrpcTrafficEntityEnrichmentContainer.newBuilder()
					.setQuery(entityEnrichmentContainer.query().toString())
					.setPrimaryKey(entityEnrichmentContainer.primaryKey())
					.build()
			);
		} else {
			throw new GenericEvitaInternalError("Unsupported traffic recording type: " + trafficRecording.type());
		}
	}

	/**
	 * Converts a {@link TrafficRecording} of type {@link MutationContainer} into its equivalent
	 * gRPC representation and sets it to the provided {@link GrpcTrafficRecord.Builder}.
	 * If the provided {@link TrafficRecording} is not of type {@link MutationContainer}, or contains
	 * an unsupported mutation type, a {@link GenericEvitaInternalError} is thrown.
	 *
	 * @param trafficRecording the traffic recording to be converted, must be of type {@link MutationContainer}
	 * @param builder          the gRPC record builder where the converted mutation container will be set
	 * @throws GenericEvitaInternalError if the provided trafficRecording is not of type {@link MutationContainer}
	 *                                   or if the mutation type is not supported
	 */
	private static void convertMutationContainer(@Nonnull TrafficRecording trafficRecording, @Nonnull GrpcTrafficRecord.Builder builder) {
		if (trafficRecording instanceof MutationContainer mutationContainer) {
			final Mutation mutation = mutationContainer.mutation();
			if (mutation instanceof EntityMutation entityMutation) {
				builder.setMutation(
					GrpcTrafficMutationContainer.newBuilder()
						.setEntityMutation(DelegatingEntityMutationConverter.INSTANCE.convert(entityMutation))
						.build()
				);
			} else if (mutation instanceof EntitySchemaMutation schemaMutation) {
				builder.setMutation(
					GrpcTrafficMutationContainer.newBuilder()
						.setSchemaMutation(DelegatingEntitySchemaMutationConverter.INSTANCE.convert(schemaMutation))
						.build()
				);
			} else {
				throw new GenericEvitaInternalError("Unsupported mutation type: " + mutation.getClass().getSimpleName());
			}
		} else {
			throw new GenericEvitaInternalError("Unsupported traffic recording type: " + trafficRecording.type());
		}
	}

	/**
	 * Converts a {@link Label} object to its gRPC representation {@link GrpcQueryLabel}.
	 *
	 * @param label the label to be converted, must not be null
	 * @return the converted {@link GrpcQueryLabel} instance
	 */
	@Nonnull
	private static GrpcQueryLabel toGrpcQueryLabel(@Nonnull Label label) {
		return GrpcQueryLabel.newBuilder()
			.setName(label.name())
			.setValue(EvitaDataTypes.formatValue(label.value()))
			.build();
	}

	/**
	 * Constructs a {@link TrafficRecordingCaptureRequest} based on the provided gRPC request and criteria.
	 *
	 * @param criteria the gRPC criteria containing additional parameters for filtering the traffic recording
	 * @return a {@link TrafficRecordingCaptureRequest} instance with the data derived from the input parameters
	 */
	@Nonnull
	private static TrafficRecordingCaptureRequest getTrafficRecordingCaptureRequest(
		@Nonnull GrpcTrafficRecordingCaptureCriteria criteria
	) {
		return new TrafficRecordingCaptureRequest(
			EvitaEnumConverter.toCaptureContent(criteria.getContent()),
			criteria.hasSince() ? toOffsetDateTime(criteria.getSince()) : null,
			criteria.hasSinceSessionSequenceId() ? criteria.getSinceSessionSequenceId().getValue() : null,
			criteria.hasSinceRecordSessionOffset() ? criteria.getSinceRecordSessionOffset().getValue() : null,
			criteria.getTypeCount() > 0 ?
				criteria.getTypeList().stream()
					.map(EvitaEnumConverter::toTrafficRecordingType)
					.toArray(TrafficRecordingType[]::new) : null,
			criteria.getSessionIdCount() > 0 ?
				criteria.getSessionIdList()
					.stream()
					.map(EvitaDataTypesConverter::toUuid)
					.toArray(UUID[]::new) : null,
			criteria.hasLongerThanMilliseconds() ? Duration.of(criteria.getLongerThanMilliseconds().getValue(), ChronoUnit.MILLIS) : null,
			criteria.hasFetchingMoreBytesThan() ? criteria.getFetchingMoreBytesThan().getValue() : null,
			criteria.getLabelsCount() > 0 ?
				criteria.getLabelsList().stream()
					.map(TrafficCaptureConverter::toLabel)
					.toArray(Label[]::new) : null
		);
	}

}
