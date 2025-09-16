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

package io.evitadb.externalApi.grpc.services;

import com.google.protobuf.Empty;
import com.google.protobuf.GeneratedMessageV3;
import com.linecorp.armeria.server.ServiceRequestContext;
import io.evitadb.api.CatalogState;
import io.evitadb.api.CommitProgress;
import io.evitadb.api.CommitProgress.CommitVersions;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.TransactionContract.CommitBehavior;
import io.evitadb.api.exception.SessionNotFoundException;
import io.evitadb.api.file.FileForFetch;
import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.HeadConstraint;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.head.Head;
import io.evitadb.api.query.head.Label;
import io.evitadb.api.query.require.EntityContentRequire;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.query.visitor.FinderVisitor;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.cdc.ChangeCaptureSubscription;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture;
import io.evitadb.api.requestResponse.data.DeletedHierarchy;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation;
import io.evitadb.api.requestResponse.data.structure.BinaryEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.progress.Progress;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.SealedCatalogSchema;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.api.requestResponse.system.StoredVersion;
import io.evitadb.api.task.Task;
import io.evitadb.core.Evita;
import io.evitadb.core.EvitaInternalSessionContract;
import io.evitadb.core.executor.ObservableExecutorServiceWithHardDeadline;
import io.evitadb.dataType.DataChunk;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.dataType.PaginatedList;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.StripList;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.externalApi.configuration.HeaderOptions;
import io.evitadb.externalApi.grpc.GrpcProvider;
import io.evitadb.externalApi.grpc.builders.query.extraResults.GrpcExtraResultsBuilder;
import io.evitadb.externalApi.grpc.constants.GrpcHeaders;
import io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter;
import io.evitadb.externalApi.grpc.generated.*;
import io.evitadb.externalApi.grpc.generated.GrpcEntitySchemaResponse.Builder;
import io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter;
import io.evitadb.externalApi.grpc.requestResponse.cdc.ChangeCaptureConverter;
import io.evitadb.externalApi.grpc.requestResponse.data.EntityConverter;
import io.evitadb.externalApi.grpc.requestResponse.data.mutation.DelegatingEntityMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.data.mutation.DelegatingLocalMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.EntitySchemaConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.DelegatingLocalCatalogSchemaMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutationConverter;
import io.evitadb.externalApi.grpc.services.interceptors.GlobalExceptionHandlerInterceptor;
import io.evitadb.externalApi.grpc.services.interceptors.ServerSessionInterceptor;
import io.evitadb.externalApi.grpc.utils.QueryUtil;
import io.evitadb.externalApi.grpc.utils.QueryWithParameters;
import io.evitadb.externalApi.trace.ExternalApiTracingContextProvider;
import io.evitadb.externalApi.utils.ExternalApiTracingContext;
import io.evitadb.function.QuadriConsumer;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.UUIDUtil;
import io.evitadb.utils.VersionUtils.SemVer;
import io.grpc.Context;
import io.grpc.Metadata;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.UnaryOperator;
import java.util.stream.BaseStream;
import java.util.stream.Stream;

import static io.evitadb.api.query.QueryConstraints.head;
import static io.evitadb.api.query.QueryConstraints.label;
import static io.evitadb.externalApi.grpc.constants.GrpcHeaders.SESSION_ID_HEADER;
import static io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter.toGrpcOffsetDateTime;
import static io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter.toGrpcTaskStatus;
import static io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter.toGrpcUuid;
import static io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter.toOffsetDateTime;
import static io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter.toCommitBehavior;
import static io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter.toGrpcCatalogState;
import static io.evitadb.externalApi.grpc.requestResponse.schema.CatalogSchemaConverter.convert;
import static io.evitadb.externalApi.grpc.services.interceptors.ServerSessionInterceptor.METADATA;
import static java.util.Optional.empty;
import static java.util.Optional.ofNullable;

/**
 * This service contains methods that could be called by gRPC clients on {@link GrpcEvitaSessionAPI}.
 *
 * @author Tomáš Pozler, 2022
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 * @author Jan Novotný, FG Forrest a.s. (c) 2024
 */
@Slf4j
public class EvitaSessionService extends EvitaSessionServiceGrpc.EvitaSessionServiceImplBase {

	private static final String GRPC_SOURCE_TYPE_LABEL_VALUE = "gRPC";

	/**
	 * Instance of Evita upon which will be executed service calls
	 */
	@Nonnull private final Evita evita;
	/**
	 * Tracing context for the gRPC calls.
	 */
	@Nonnull private final ExternalApiTracingContext<Metadata> tracingContext;
	/**
	 * Flag indicating whether source queries should be tracked.
	 */
	private final boolean trackSourceQueries;

	/**
	 * Executes entire lambda function within the scope of a tracing context.
	 *
	 * @param lambda   lambda function to be executed
	 * @param executor executor service to be used as a carrier for a lambda function
	 */
	static void executeWithClientContext(
		@Nonnull Consumer<EvitaInternalSessionContract> lambda,
		@Nonnull ObservableExecutorServiceWithHardDeadline executor,
		@Nonnull StreamObserver<?> responseObserver,
		@Nonnull ExternalApiTracingContext<Metadata> context
	) {
		// Retrieve the deadline from the context
		final long requestTimeoutMillis = ServiceRequestContext.current().requestTimeoutMillis();
		final Metadata metadata = METADATA.get();
		final String methodName = GrpcHeaders.getGrpcTraceTaskNameWithMethodName(metadata);
		final EvitaInternalSessionContract session = ServerSessionInterceptor.SESSION.get();
		final Context grpcContext = Context.current();
		executor.execute(
			executor.createTask(
				methodName,
				() -> {
					try {
						grpcContext.run(
							() -> context.executeWithinBlock(
								methodName,
								metadata,
								() -> lambda.accept(session)
							)
						);
					} catch (RuntimeException exception) {
						// Delegate exception handling to GlobalExceptionHandlerInterceptor
						GlobalExceptionHandlerInterceptor.sendErrorToClient(exception, responseObserver);
					}
				},
				requestTimeoutMillis
			)
		);
	}

	/**
	 * Internal method used to query catalog expecting only one record returned by calling {@link EvitaSessionContract#queryOne(Query, Class)}.
	 *
	 * @param session          session on which the query will be executed
	 * @param query            query to be executed
	 * @param responseObserver observer on which errors might be thrown and result returned
	 */
	private static void queryOneInternal(
		@Nonnull StreamObserver<GrpcQueryOneResponse> responseObserver,
		@Nonnull EvitaInternalSessionContract session,
		@Nullable Query query,
		@Nullable Label... additionalLabels
	) {
		if (query != null) {
			final EvitaRequest evitaRequest = new EvitaRequest(
				normalizeQueryWithAddingLabel(query, additionalLabels),
				OffsetDateTime.now(),
				EntityClassifier.class,
				null
			);

			final GrpcQueryOneResponse.Builder responseBuilder = GrpcQueryOneResponse.newBuilder();
			session.queryOne(evitaRequest)
			       .ifPresent(responseEntity -> {
				       if (responseEntity instanceof final EntityReference entityReference) {
					       responseBuilder.setEntityReference(GrpcEntityReference.newBuilder()
					                                                             .setEntityType(
						                                                             entityReference.getType())
					                                                             .setPrimaryKey(
						                                                             entityReference.getPrimaryKey())
					                                                             .build());
				       } else if (responseEntity instanceof final SealedEntity sealedEntity) {
					       final SemVer clientVersion = ServerSessionInterceptor.getClientVersion().orElse(null);
					       responseBuilder.setSealedEntity(
						       EntityConverter.toGrpcSealedEntity(
							       sealedEntity,
							       clientVersion
						       )
					       );
				       } else if (responseEntity instanceof final BinaryEntity binaryEntity) {
					       responseBuilder.setBinaryEntity(EntityConverter.toGrpcBinaryEntity(binaryEntity));
				       } else {
					       throw new GenericEvitaInternalError(
						       "Unsupported entity class `" + responseEntity.getClass().getName() + "`.");
				       }
			       });
			responseObserver.onNext(responseBuilder.build());
		}
		responseObserver.onCompleted();
	}

	/**
	 * Internal method used to query catalog expecting list of records returned by calling {@link EvitaSessionContract#queryList(Query, Class)}.
	 *
	 * @param session          session on which the query will be executed
	 * @param query            query to be executed
	 * @param responseObserver observer on which errors might be thrown and result returned
	 */
	private static void queryListInternal(
		@Nonnull StreamObserver<GrpcQueryListResponse> responseObserver,
		@Nonnull EvitaInternalSessionContract session,
		@Nullable Query query,
		@Nullable Label... additionalLabels
	) {
		if (query != null) {
			final EvitaRequest evitaRequest = new EvitaRequest(
				normalizeQueryWithAddingLabel(query, additionalLabels),
				OffsetDateTime.now(),
				EntityClassifier.class,
				null
			);
			final List<EntityClassifier> responseEntities = session.queryList(evitaRequest);
			final GrpcQueryListResponse.Builder responseBuilder = GrpcQueryListResponse.newBuilder();
			final EntityFetch entityFetchRequirement = evitaRequest.getEntityRequirement();
			if (entityFetchRequirement != null) {
				if (session.isBinaryFormat()) {
					responseEntities.forEach(e ->
						                         responseBuilder.addBinaryEntities(
							                         EntityConverter.toGrpcBinaryEntity((BinaryEntity) e))
					);
				} else {
					final SemVer clientVersion = ServerSessionInterceptor.getClientVersion().orElse(null);
					responseEntities.forEach(entity ->
						                         responseBuilder.addSealedEntities(
							                         EntityConverter.toGrpcSealedEntity(
								                         (SealedEntity) entity,
								                         clientVersion
							                         ))
					);
				}
			} else {
				responseEntities.forEach(e ->
					                         responseBuilder.addEntityReferences(GrpcEntityReference.newBuilder()
					                                                                                .setEntityType(
						                                                                                e.getType())
					                                                                                .setPrimaryKey(
						                                                                                ((EntityReference) e).getPrimaryKey())
					                                                                                .build())
				);
			}

			responseObserver.onNext(responseBuilder.build());
		}
		responseObserver.onCompleted();
	}

	/**
	 * Internal method used to query catalog calling {@link EvitaSessionContract#query(Query, Class)}.
	 *
	 * @param session          session on which the query will be executed
	 * @param query            query to be executed
	 * @param responseObserver observer on which errors might be thrown and result returned
	 */
	private static void queryInternal(
		@Nonnull StreamObserver<GrpcQueryResponse> responseObserver,
		@Nonnull EvitaInternalSessionContract session,
		@Nullable Query query,
		@Nullable Label... additionalLabels
	) {
		if (query != null) {
			final EvitaRequest evitaRequest = new EvitaRequest(
				normalizeQueryWithAddingLabel(query, additionalLabels),
				OffsetDateTime.now(),
				EntityClassifier.class,
				null
			);

			final EvitaResponse<EntityClassifier> evitaResponse = session.query(evitaRequest);
			final GrpcQueryResponse.Builder entityBuilder = GrpcQueryResponse.newBuilder();
			final DataChunk<EntityClassifier> recordPage = evitaResponse.getRecordPage();
			final GrpcDataChunk.Builder dataChunkBuilder = GrpcDataChunk.newBuilder()
			                                                            .setTotalRecordCount(
				                                                            evitaResponse.getTotalRecordCount())
			                                                            .setIsFirst(recordPage.isFirst())
			                                                            .setIsLast(recordPage.isLast())
			                                                            .setHasPrevious(recordPage.hasPrevious())
			                                                            .setHasNext(recordPage.hasNext())
			                                                            .setIsSinglePage(recordPage.isSinglePage())
			                                                            .setIsEmpty(recordPage.isEmpty());

			if (recordPage instanceof PaginatedList<?> paginatedList) {
				dataChunkBuilder.getPaginatedListBuilder()
				                .setPageNumber(paginatedList.getPageNumber())
				                .setPageSize(paginatedList.getPageSize())
				                .setLastPageNumber(paginatedList.getLastPageNumber());
			} else if (recordPage instanceof StripList<?> stripList) {
				dataChunkBuilder.getStripListBuilder()
				                .setOffset(stripList.getOffset())
				                .setLimit(stripList.getLimit());
			}

			entityBuilder.setExtraResults(
				GrpcExtraResultsBuilder.buildExtraResults(evitaResponse)
			);

			final EntityFetch entityRequirement = evitaRequest.getEntityRequirement();
			if (entityRequirement != null) {
				if (session.isBinaryFormat()) {
					final List<GrpcBinaryEntity> binaryEntities = new ArrayList<>(recordPage.getData().size());
					recordPage.stream().forEach(e ->
						                            binaryEntities.add(
							                            EntityConverter.toGrpcBinaryEntity((BinaryEntity) e))
					);
					entityBuilder.setRecordPage(dataChunkBuilder
						                            .addAllBinaryEntities(binaryEntities)
						                            .build()
					);
				} else {
					final SemVer clientVersion = ServerSessionInterceptor.getClientVersion().orElse(null);
					final List<GrpcSealedEntity> sealedEntities = new ArrayList<>(recordPage.getData().size());
					recordPage.stream().forEach(e ->
						                            sealedEntities.add(
							                            EntityConverter.toGrpcSealedEntity(
								                            (SealedEntity) e,
								                            clientVersion
							                            ))
					);
					entityBuilder.setRecordPage(
						dataChunkBuilder
							.addAllSealedEntities(sealedEntities)
							.build()
					);
				}
			} else {
				final List<GrpcEntityReference> entityReferences = new ArrayList<>(recordPage.getData().size());
				recordPage.stream().forEach(e ->
					                            entityReferences.add(
						                            GrpcEntityReference.newBuilder()
						                                               .setEntityType(e.getType())
						                                               .setPrimaryKey(
							                                               ((EntityReference) e).getPrimaryKey())
						                                               .build())
				);
				entityBuilder.setRecordPage(
					             dataChunkBuilder
						             .addAllEntityReferences(entityReferences)
						             .build()
				             )
				             .build();
			}

			responseObserver.onNext(entityBuilder.build());
		}
		responseObserver.onCompleted();
	}

	/**
	 * Normalizes the given query and optionally appends additional labels to the query's head.
	 *
	 * @param query            The query to be normalized. Must not be null.
	 * @param additionalLabels Optional list of labels to be added to the query's head. Can be null or empty.
	 * @return A normalized query object with additional labels appended if provided.
	 */
	@Nonnull
	private static Query normalizeQueryWithAddingLabel(
		@Nonnull Query query,
		@Nullable Label... additionalLabels
	) {
		if (additionalLabels == null) {
			return query.normalizeQuery();
		} else if (query.getHead() == null) {
			return Query.query(
				head(additionalLabels),
				query.getFilterBy(),
				query.getOrderBy(),
				query.getRequire()
			).normalizeQuery();
		} else if (query.getHead() instanceof Head) {
			return query.normalizeQuery(
				new LabelAppender(additionalLabels),
				null, null, null
			);
		} else {
			return Query.query(
				head(
					ArrayUtils.mergeArrays(
						new HeadConstraint[]{query.getHead()},
						additionalLabels
					)
				),
				query.getFilterBy(),
				query.getOrderBy(),
				query.getRequire()
			).normalizeQuery();
		}
	}

	/**
	 * Executes a query by parsing the provided source query, resolving its parameters,
	 * and delegating the handling to a provided consumer.
	 *
	 * @param <T>                the type of the response that extends {@link GeneratedMessageV3}
	 * @param responseObserver   the observer to stream the results back to; cannot be null
	 * @param session            the internal session used for query execution; cannot be null
	 * @param sourceQuery        the query string to be parsed and executed; cannot be null
	 * @param trackSourceQueries a flag indicating whether source queries should be tracked
	 * @param handler            a consumer that handles the parsed query and streams the results using the observer; cannot be null
	 */
	private static <T extends GeneratedMessageV3> void doQuery(
		@Nonnull StreamObserver<T> responseObserver,
		@Nonnull EvitaInternalSessionContract session,
		@Nonnull String sourceQuery,
		@Nonnull QueryWithParameters parsedQuery,
		boolean trackSourceQueries,
		@Nonnull QuadriConsumer<StreamObserver<T>, EvitaInternalSessionContract, Query, Label[]> handler
	) {
		final UUID sourceQueryId = trackSourceQueries ?
			session.recordSourceQuery(
				completeSourceQuery(sourceQuery, parsedQuery.positionalParameters(), parsedQuery.namedParameters()),
				GrpcProvider.CODE,
				null
			) : null;
		try {
			handler.accept(
				responseObserver, session, parsedQuery.parsedQuery(),
				new Label[]{
					label(Label.LABEL_SOURCE_TYPE, GRPC_SOURCE_TYPE_LABEL_VALUE),
					sourceQueryId == null ? null : label(Label.LABEL_SOURCE_QUERY, sourceQueryId)
				}
			);
		} finally {
			if (trackSourceQueries) {
				session.finalizeSourceQuery(sourceQueryId, null);
			}
		}
	}

	/**
	 * Executes a query by parsing the provided source query, resolving its parameters,
	 * and delegating the handling to a provided consumer.
	 *
	 * @param session            the internal session used for query execution; cannot be null
	 * @param sourceQuery        the query string to be parsed and executed; cannot be null
	 * @param trackSourceQueries a flag indicating whether source queries should be tracked
	 */
	private static void trackFailedQuery(
		@Nonnull EvitaInternalSessionContract session,
		@Nonnull String sourceQuery,
		@Nonnull List<Object> positionalQueryParamsList,
		@Nonnull Map<String, Object> namedQueryParamsMap,
		@Nonnull String finishedWithError,
		boolean trackSourceQueries
	) {
		if (trackSourceQueries) {
			final UUID sourceQueryId = session.recordSourceQuery(
				completeSourceQuery(sourceQuery, positionalQueryParamsList, namedQueryParamsMap),
				GrpcProvider.CODE,
				finishedWithError
			);

			session.finalizeSourceQuery(sourceQueryId, null);
		}
	}

	/**
	 * Completes the provided source query by appending either positional or named query parameters,
	 * based on which collection contains values. If both the positional and named query parameter collections
	 * are empty, the source query is returned unchanged.
	 *
	 * @param sourceQuery               the base query string to be completed with additional parameters
	 * @param positionalQueryParamsList a list of positional query parameters to be included in the output
	 * @param namedQueryParamsMap       a map of named query parameters to be included in the output
	 * @return the completed query string with appended query parameters if applicable, otherwise the original query string
	 */
	@Nonnull
	private static String completeSourceQuery(
		@Nonnull String sourceQuery,
		@Nonnull List<Object> positionalQueryParamsList,
		@Nonnull Map<String, Object> namedQueryParamsMap
	) {
		if (positionalQueryParamsList.isEmpty() && namedQueryParamsMap.isEmpty()) {
			return sourceQuery;
		} else if (positionalQueryParamsList.isEmpty()) {
			final StringBuilder sb = new StringBuilder(sourceQuery);
			sb.append("\n");
			for (int i = 0; i < positionalQueryParamsList.size(); i++) {
				final Object param = positionalQueryParamsList.get(i);
				sb.append("Param ").append(i + 1).append(": ").append(
					EvitaDataTypes.formatValue(param instanceof Serializable ser ? ser : "N/Serializable")).append(
					"\n");
			}
			return sb.toString();
		} else {
			final StringBuilder sb = new StringBuilder(sourceQuery);
			for (Entry<String, Object> entry : namedQueryParamsMap.entrySet()) {
				sb.append(entry.getKey())
				  .append(": ")
				  .append(
					  EvitaDataTypes.formatValue(entry.getValue() instanceof Serializable ser ? ser : "N/Serializable"))
				  .append("\n");
			}
			return sb.toString();
		}
	}

	/**
	 * Sends a transaction update to the client by responding to the provided {@link StreamObserver}.
	 * If a {@link Throwable} is provided, it sends the corresponding error to the client.
	 * Otherwise, it sends a response containing commit version details and phase status.
	 *
	 * @param responseObserver the response observer through which the update is sent to the client
	 * @param result           the commit version result containing the catalog version and schema version
	 * @param throwable        the throwable instance in case an error occurred during transaction processing
	 * @param phase            the commit behavior phase to reflect the current state of the transaction
	 */
	private static void sendTransactionUpdate(
		@Nonnull StreamObserver<GrpcCloseWithProgressResponse> responseObserver,
		@Nonnull CommitVersions result,
		@Nonnull Throwable throwable,
		@Nonnull CommitBehavior phase
	) {
		if (throwable != null) {
			GlobalExceptionHandlerInterceptor.sendErrorToClient(throwable, responseObserver);
		} else {
			responseObserver.onNext(
				GrpcCloseWithProgressResponse.newBuilder()
				                             .setCatalogVersion(result.catalogVersion())
				                             .setCatalogSchemaVersion(result.catalogSchemaVersion())
				                             .setFinishedPhase(EvitaEnumConverter.toGrpcCommitPhase(phase))
				                             .build()
			);
		}
	}

	public EvitaSessionService(@Nonnull Evita evita, @Nonnull HeaderOptions headers) {
		this.evita = evita;
		this.trackSourceQueries = evita.getConfiguration().server().trafficRecording().sourceQueryTrackingEnabled();
		this.tracingContext = ExternalApiTracingContextProvider.getContext(headers);
	}

	/**
	 * Produces the {@link CatalogSchema}.
	 */
	@Override
	public void getCatalogSchema(
		GrpcGetCatalogSchemaRequest request, StreamObserver<GrpcCatalogSchemaResponse> responseObserver) {
		executeWithClientContext(
			session -> {
				final SealedCatalogSchema catalogSchema = session.getCatalogSchema();
				responseObserver.onNext(
					GrpcCatalogSchemaResponse.newBuilder()
					                         .setCatalogSchema(convert(catalogSchema, request.getNameVariants()))
					                         .build()
				);
				responseObserver.onCompleted();
			},
			this.evita.getRequestExecutor(),
			responseObserver,
			this.tracingContext
		);
	}

	/**
	 * Returns the current state of the catalog.
	 */
	@Override
	public void getCatalogState(Empty request, StreamObserver<GrpcCatalogStateResponse> responseObserver) {
		executeWithClientContext(
			session -> {
				final CatalogState catalogState = session.getCatalogState();
				responseObserver.onNext(
					GrpcCatalogStateResponse.newBuilder()
					                        .setState(toGrpcCatalogState(catalogState))
					                        .build()
				);
				responseObserver.onCompleted();
			},
			this.evita.getRequestExecutor(),
			responseObserver,
			this.tracingContext
		);
	}

	/**
	 * Method returns valid catalog version and the introduction date for the given moment in time.
	 *
	 * @param request          request containing the moment in time
	 * @param responseObserver observer on which errors might be thrown and result returned
	 */
	@Override
	public void getCatalogVersionAt(
		GrpcCatalogVersionAtRequest request, StreamObserver<GrpcCatalogVersionAtResponse> responseObserver) {
		executeWithClientContext(
			session -> {
				final StoredVersion catalogVersionAt = session.getCatalogVersionAt(
					request.hasTheMoment() ? toOffsetDateTime(request.getTheMoment()) : null
				);
				responseObserver.onNext(
					GrpcCatalogVersionAtResponse.newBuilder()
					                            .setVersion(catalogVersionAt.version())
					                            .setIntroducedAt(toGrpcOffsetDateTime(catalogVersionAt.introducedAt()))
					                            .build()
				);
				responseObserver.onCompleted();
			},
			this.evita.getRequestExecutor(),
			responseObserver,
			this.tracingContext
		);
	}

	/**
	 * Method returns page of historical mutations in the form of change capture events that match given criteria and
	 * pagination settings.
	 *
	 * @param request          request containing the criteria and pagination settings
	 * @param responseObserver observer on which errors might be thrown and result returned
	 */
	@Override
	public void getMutationsHistoryPage(
		GetMutationsHistoryPageRequest request, StreamObserver<GetMutationsHistoryPageResponse> responseObserver) {
		executeWithClientContext(
			session -> {
				final Stream<ChangeCatalogCapture> mutationsHistoryStream = session.getMutationsHistory(
					ChangeCaptureConverter.toChangeCaptureRequest(request, session.getCatalogVersion())
				);
				final GetMutationsHistoryPageResponse.Builder builder = GetMutationsHistoryPageResponse.newBuilder();
				final int page = request.hasPage() ? request.getPage().getValue() : 1;
				final int pageSize = request.hasPageSize() ? request.getPageSize().getValue() : 20;
				mutationsHistoryStream
					.skip(
						PaginatedList.getFirstItemNumberForPage(
							page,
							pageSize
						)
					)
					.limit(pageSize)
					.forEach(cdcEvent -> builder.addChangeCapture(
						ChangeCaptureConverter.toGrpcChangeCatalogCapture(cdcEvent))
					);
				responseObserver.onNext(builder.build());
				responseObserver.onCompleted();
			},
			this.evita.getRequestExecutor(),
			responseObserver,
			this.tracingContext
		);
	}

	/**
	 * Method returns all historical mutations in the form of change capture events that match given criteria.
	 *
	 * @param request          request containing the criteria
	 * @param responseObserver observer on which errors might be thrown and result returned
	 */
	@Override
	public void getMutationsHistory(
		GetMutationsHistoryRequest request, StreamObserver<GetMutationsHistoryResponse> responseObserver) {
		ServerCallStreamObserver<GetMutationsHistoryResponse> serverCallStreamObserver =
			(ServerCallStreamObserver<GetMutationsHistoryResponse>) responseObserver;

		final AtomicReference<Stream<ChangeCatalogCapture>> mutationsHistoryStreamRef = new AtomicReference<>();

		// avoid returning error when client cancels the stream
		serverCallStreamObserver.setOnCancelHandler(
			() -> {
				log.info("Client cancelled the mutation history request.");
				ofNullable(mutationsHistoryStreamRef.get())
					.ifPresent(BaseStream::close);
			}
		);

		executeWithClientContext(
			session -> {
				final Stream<ChangeCatalogCapture> mutationsHistoryStream = session.getMutationsHistory(
					ChangeCaptureConverter.toChangeCaptureRequest(request)
				);
				mutationsHistoryStreamRef.set(mutationsHistoryStream);

				mutationsHistoryStream.forEach(
					cdcEvent -> {
						final GetMutationsHistoryResponse.Builder builder = GetMutationsHistoryResponse.newBuilder();
						final GrpcChangeCatalogCapture event = ChangeCaptureConverter.toGrpcChangeCatalogCapture(
							cdcEvent);
						// we send mutations one by one, but we may want to send them in batches in the future
						builder.addChangeCapture(event);
						responseObserver.onNext(builder.build());
					}
				);
				responseObserver.onCompleted();
			},
			this.evita.getRequestExecutor(),
			responseObserver,
			this.tracingContext
		);
	}

	/**
	 * Produces the {@link EntitySchema} for {@link GrpcEntitySchemaRequest#getEntityType()}.
	 */
	@Override
	public void getEntitySchema(
		GrpcEntitySchemaRequest request, StreamObserver<GrpcEntitySchemaResponse> responseObserver) {
		executeWithClientContext(
			session -> {
				final Builder responseBuilder = GrpcEntitySchemaResponse.newBuilder();
				session.getEntitySchema(request.getEntityType())
				       .ifPresent(
					       entitySchema -> responseBuilder.setEntitySchema(
						       EntitySchemaConverter.convert(
							       session.getCatalogSchema(),
							       entitySchema,
							       request.getNameVariants()
						       )
					       )
				       );

				responseObserver.onNext(
					responseBuilder.build()
				);
				responseObserver.onCompleted();
			},
			this.evita.getRequestExecutor(),
			responseObserver,
			this.tracingContext
		);
	}

	/**
	 * Method used to get set of all stored entity types by calling {@link EvitaSessionContract#getAllEntityTypes()}.
	 *
	 * @param request          empty request
	 * @param responseObserver observer on which errors might be thrown and result returned
	 */
	@Override
	public void getAllEntityTypes(Empty request, StreamObserver<GrpcEntityTypesResponse> responseObserver) {
		executeWithClientContext(
			session -> {
				responseObserver.onNext(
					GrpcEntityTypesResponse.newBuilder()
					                       .addAllEntityTypes(session.getAllEntityTypes())
					                       .build()
				);
				responseObserver.onCompleted();
			},
			this.evita.getRequestExecutor(),
			responseObserver,
			this.tracingContext
		);
	}

	/**
	 * Method used to switch catalog state to {@link CatalogState#ALIVE} and close currently used session by calling
	 * {@link EvitaSessionContract#goLiveAndCloseWithProgress(IntConsumer)}.
	 *
	 * @param request          empty request
	 * @param responseObserver observer on which errors might be thrown and result returned
	 */
	@Override
	public void goLiveAndClose(Empty request, StreamObserver<GrpcGoLiveAndCloseResponse> responseObserver) {
		executeWithClientContext(
			session -> {
				if (session == null) {
					responseObserver.onError(new SessionNotFoundException("No session for going live found!"));
					responseObserver.onCompleted();
				} else {
					session.goLiveAndCloseWithProgress()
						.onCompletion()
						.whenComplete(
						(commitVersions, throwable) -> {
							if (throwable == null) {
								final GrpcGoLiveAndCloseResponse response = GrpcGoLiveAndCloseResponse.newBuilder()
									.setCatalogVersion(commitVersions.catalogVersion())
									.setCatalogSchemaVersion(commitVersions.catalogSchemaVersion())
									.setSuccess(true)
									.build();
								responseObserver.onNext(response);
							} else {
								responseObserver.onError(throwable);
							}
							responseObserver.onCompleted();
					});
				}
			},
			this.evita.getRequestExecutor(),
			responseObserver,
			this.tracingContext
		);
	}

	/**
	 * Method used to switch catalog state to {@link CatalogState#ALIVE} and close currently used session by calling
	 * {@link EvitaSessionContract#goLiveAndCloseWithProgress(IntConsumer)}. This method is streaming variant of
	 * {@link #goLiveAndClose(Empty, StreamObserver)} method that returns progress of the go live operation.
	 *
	 * @param request          empty request
	 * @param responseObserver observer on which errors might be thrown and result returned
	 */
	@Override
	public void goLiveAndCloseWithProgress(Empty request, StreamObserver<GrpcGoLiveAndCloseWithProgressResponse> responseObserver) {
		executeWithClientContext(
			session -> {
				if (session == null) {
					responseObserver.onError(new SessionNotFoundException("No session for going live found!"));
					responseObserver.onCompleted();
				} else {
					final Progress<CommitVersions> goLiveProgress = session.goLiveAndCloseWithProgress(
						new IntConsumer() {
							private int percentDone;
							private long lastUpdate = System.currentTimeMillis();

							@Override
							public void accept(int percentDoneCurrently) {
								if (percentDoneCurrently > this.percentDone && this.lastUpdate + 1000 < System.currentTimeMillis()) {
									this.percentDone = percentDoneCurrently;
									final GrpcGoLiveAndCloseWithProgressResponse response = GrpcGoLiveAndCloseWithProgressResponse.newBuilder()
										.setProgressInPercent(this.percentDone)
										.build();
									responseObserver.onNext(response);
									this.lastUpdate = System.currentTimeMillis();
								}
							}
						}
					);

					goLiveProgress.onCompletion().whenComplete(
						(commitVersions, throwable) -> {
							if (throwable == null) {
								final GrpcGoLiveAndCloseWithProgressResponse response = GrpcGoLiveAndCloseWithProgressResponse.newBuilder()
									.setCatalogVersion(commitVersions.catalogVersion())
									.setCatalogSchemaVersion(commitVersions.catalogSchemaVersion())
									.setProgressInPercent(100)
									.build();
								responseObserver.onNext(response);
							} else {
								responseObserver.onError(throwable);
							}
							responseObserver.onCompleted();
						});
				}
			},
			this.evita.getRequestExecutor(),
			responseObserver,
			this.tracingContext
		);
	}

	/**
	 * Method allows to backup a catalog and send the backup file to the client.
	 *
	 * @param request          empty request
	 * @param responseObserver observer on which errors might be thrown and result returned
	 */
	@Override
	public void backupCatalog(
		GrpcBackupCatalogRequest request, StreamObserver<GrpcBackupCatalogResponse> responseObserver) {
		executeWithClientContext(
			session -> {
				final Task<?, FileForFetch> backupTask = session.backupCatalog(
					request.hasPastMoment() ?
						EvitaDataTypesConverter.toOffsetDateTime(request.getPastMoment()) :
						null,
					request.hasCatalogVersion() ?
						request.getCatalogVersion().getValue() : null,
					request.getIncludingWAL()
				);

				responseObserver.onNext(
					GrpcBackupCatalogResponse.newBuilder()
					                         .setTaskStatus(toGrpcTaskStatus(backupTask.getStatus()))
					                         .build()
				);

				responseObserver.onCompleted();
			},
			this.evita.getRequestExecutor(),
			responseObserver,
			this.tracingContext
		);
	}

	/**
	 * Method allows to fully backup a catalog and send the backup file to the client.
	 *
	 * @param request          empty request
	 * @param responseObserver observer on which errors might be thrown and result returned
	 */
	@Override
	public void fullBackupCatalog(Empty request, StreamObserver<GrpcFullBackupCatalogResponse> responseObserver) {
		executeWithClientContext(
			session -> {
				final Task<?, FileForFetch> backupTask = session.fullBackupCatalog();

				responseObserver.onNext(
					GrpcFullBackupCatalogResponse.newBuilder()
					                             .setTaskStatus(toGrpcTaskStatus(backupTask.getStatus()))
					                             .build()
				);

				responseObserver.onCompleted();
			},
			this.evita.getRequestExecutor(),
			responseObserver,
			this.tracingContext
		);
	}

	/**
	 * Method used to close currently used session by calling {@link EvitaSessionContract#close()}.
	 *
	 * @param request          empty request
	 * @param responseObserver observer on which errors might be thrown and result returned
	 */
	@Override
	public void close(GrpcCloseRequest request, StreamObserver<GrpcCloseResponse> responseObserver) {
		executeWithClientContext(
			session -> {
				if (session != null) {
					final CommitProgress commitProgress = session.closeNowWithProgress();
					final CommitBehavior commitBehavior = toCommitBehavior(request.getCommitBehaviour());
					commitProgress
						.on(commitBehavior)
						.whenComplete(
							(result, throwable) -> {
								if (throwable != null) {
									GlobalExceptionHandlerInterceptor.sendErrorToClient(throwable, responseObserver);
								} else {
									responseObserver.onNext(
										GrpcCloseResponse.newBuilder()
										                 .setCatalogVersion(result.catalogVersion())
										                 .setCatalogSchemaVersion(result.catalogSchemaVersion())
										                 .build()
									);
								}
								responseObserver.onCompleted();
							}
						);
				} else {
					final Metadata metadata = METADATA.get();
					final String sessionId = metadata.get(Metadata.Key.of(SESSION_ID_HEADER, Metadata.ASCII_STRING_MARSHALLER));
					if (sessionId != null && this.evita.wasSessionForcefullyClosedForCatalog(request.getCatalogName(), UUIDUtil.uuid(sessionId))) {
						// sessions has been closed from the server side, due to system action and therefore
						// the session was not found - this is ok, we don't report an error
						responseObserver.onNext(
							// we cannot provide more information in the response, session was closed forcefully
							// but if we wouldn't return this response, the client would receive an error because of completion without response
							GrpcCloseResponse.newBuilder().build()
						);
						responseObserver.onCompleted();
					} else {
						// no session to close, we couldn't return the catalog version, return error
						responseObserver.onError(
							new SessionNotFoundException("No session for closing found!")
						);
					}
				}
			},
			this.evita.getRequestExecutor(),
			responseObserver,
			this.tracingContext
		);
	}

	/**
	 * Method used to close currently used session by calling {@link EvitaSessionContract#close()} and return a stream
	 * that is updated with progress of the commit.
	 *
	 * @param request          empty request
	 * @param responseObserver observer on which errors might be thrown and result returned
	 */
	@Override
	public void closeWithProgress(GrpcCloseWithProgressRequest request, StreamObserver<GrpcCloseWithProgressResponse> responseObserver) {
		executeWithClientContext(
			session -> {
				if (session != null) {
					final CommitProgress commitProgress = session.closeNowWithProgress();
					commitProgress.onConflictResolved()
					              .whenComplete(
						              (result, throwable) -> sendTransactionUpdate(
							              responseObserver, result, throwable,
							              CommitBehavior.WAIT_FOR_CONFLICT_RESOLUTION
						              )
					              );
					commitProgress.onWalAppended()
					              .whenComplete(
						              (result, throwable) -> sendTransactionUpdate(
							              responseObserver, result, throwable, CommitBehavior.WAIT_FOR_WAL_PERSISTENCE
						              )
					              );
					commitProgress.onChangesVisible()
					              .whenComplete(
						              (result, throwable) -> {
							              sendTransactionUpdate(
								              responseObserver, result, throwable,
								              CommitBehavior.WAIT_FOR_CHANGES_VISIBLE
							              );
							              // transaction reached the final phase, we can close the stream
							              responseObserver.onCompleted();
						              }
					              );
				} else {
					final Metadata metadata = METADATA.get();
					final String sessionId = metadata.get(Metadata.Key.of(SESSION_ID_HEADER, Metadata.ASCII_STRING_MARSHALLER));
					if (sessionId != null && this.evita.wasSessionForcefullyClosedForCatalog(request.getCatalogName(), UUIDUtil.uuid(sessionId))) {
						// sessions has been closed from the server side, due to system action and therefore
						// the session was not found - this is ok, we don't report an error
						responseObserver.onNext(
							// we cannot provide more information in the response, session was closed forcefully
							// but if we wouldn't return this response, the client would receive an error because of completion without response
							GrpcCloseWithProgressResponse.newBuilder().build()
						);
						responseObserver.onCompleted();
					} else {
						// no session to close, we couldn't return the catalog version, return error
						responseObserver.onError(
							new SessionNotFoundException("No session for closing found!")
						);
					}
				}
			},
			this.evita.getRequestExecutor(),
			responseObserver,
			this.tracingContext
		);
	}

	/**
	 * Method used to query catalog expecting only one record returned by calling {@link EvitaSessionContract#queryOne(Query, Class)}.
	 *
	 * @param request          request containing query string form with possible usage of positional or named parameters and their respective collections
	 * @param responseObserver observer on which errors might be thrown and result returned
	 */
	@Override
	public void queryOne(GrpcQueryRequest request, StreamObserver<GrpcQueryOneResponse> responseObserver) {
		executeWithClientContext(
			session -> ofNullable(
				QueryUtil.parseQuery(
					request.getQuery(),
					request.getPositionalQueryParamsList(),
					request.getNamedQueryParamsMap(),
					responseObserver,
					(sourceQuery, positionalParams, namedParams, error) ->
						trackFailedQuery(
							session, sourceQuery, positionalParams, namedParams, error, this.trackSourceQueries)
				)
			).ifPresent(
				theQuery -> doQuery(
					responseObserver, session,
					request.getQuery(),
					theQuery, this.trackSourceQueries,
					EvitaSessionService::queryOneInternal
				)
			),
			this.evita.getRequestExecutor(),
			responseObserver,
			this.tracingContext
		);
	}

	/**
	 * Method used to query catalog expecting list of records returned by calling {@link EvitaSessionContract#queryList(Query, Class)}.
	 *
	 * @param request          request containing query string form with possible usage of positional or named parameters and their respective collections
	 * @param responseObserver observer on which errors might be thrown and result returned
	 */
	@Override
	public void queryList(GrpcQueryRequest request, StreamObserver<GrpcQueryListResponse> responseObserver) {
		executeWithClientContext(
			session -> ofNullable(
				QueryUtil.parseQuery(
					request.getQuery(),
					request.getPositionalQueryParamsList(),
					request.getNamedQueryParamsMap(),
					responseObserver,
					(sourceQuery, positionalParams, namedParams, error) ->
						trackFailedQuery(
							session, sourceQuery, positionalParams, namedParams, error, this.trackSourceQueries)
				)
			).ifPresent(
				theQuery -> doQuery(
					responseObserver, session,
					request.getQuery(),
					theQuery, this.trackSourceQueries,
					EvitaSessionService::queryListInternal
				)
			),
			this.evita.getRequestExecutor(),
			responseObserver,
			this.tracingContext
		);
	}

	/**
	 * Method used to query catalog calling {@link EvitaSessionContract#query(Query, Class)}.
	 *
	 * @param request          request containing query string form with possible usage of positional or named parameters and their respective collections
	 * @param responseObserver observer on which errors might be thrown and result returned
	 */
	@Override
	public void query(GrpcQueryRequest request, StreamObserver<GrpcQueryResponse> responseObserver) {
		executeWithClientContext(
			session -> ofNullable(
				QueryUtil.parseQuery(
					request.getQuery(),
					request.getPositionalQueryParamsList(),
					request.getNamedQueryParamsMap(),
					responseObserver,
					(sourceQuery, positionalParams, namedParams, error) ->
						trackFailedQuery(
							session, sourceQuery, positionalParams, namedParams, error, this.trackSourceQueries)
				)
			).ifPresent(
				theQuery -> doQuery(
					responseObserver, session,
					request.getQuery(),
					theQuery,
					this.trackSourceQueries,
					EvitaSessionService::queryInternal
				)
			),
			this.evita.getRequestExecutor(),
			responseObserver,
			this.tracingContext
		);
	}

	/**
	 * Method used to query catalog expecting only one record returned by calling {@link EvitaSessionContract#queryOne(Query, Class)}.
	 * This method implements UNSAFE approach where values are embedded directly into the query string.
	 *
	 * @param request          request containing query string form with possible usage of positional or named parameters and their respective collections
	 * @param responseObserver observer on which errors might be thrown and result returned
	 */
	@Override
	public void queryOneUnsafe(GrpcQueryUnsafeRequest request, StreamObserver<GrpcQueryOneResponse> responseObserver) {
		executeWithClientContext(
			session -> ofNullable(
				QueryUtil.parseQueryUnsafe(
					request.getQuery(),
					Collections.emptyList(),
					Collections.emptyMap(),
					responseObserver,
					(sourceQuery, positionalParams, namedParams, error) ->
						trackFailedQuery(
							session, sourceQuery, positionalParams, namedParams, error, this.trackSourceQueries)
				)
			).ifPresent(
				theQuery -> doQuery(
					responseObserver, session,
					request.getQuery(),
					theQuery,
					this.trackSourceQueries,
					EvitaSessionService::queryOneInternal
				)
			),
			this.evita.getRequestExecutor(),
			responseObserver,
			this.tracingContext
		);
	}

	/**
	 * Method used to query catalog expecting list of records returned by calling {@link EvitaSessionContract#queryList(Query, Class)}.
	 * This method implements UNSAFE approach where values are embedded directly into the query string.
	 *
	 * @param request          request containing query string form with possible usage of positional or named parameters and their respective collections
	 * @param responseObserver observer on which errors might be thrown and result returned
	 */
	@Override
	public void queryListUnsafe(
		GrpcQueryUnsafeRequest request, StreamObserver<GrpcQueryListResponse> responseObserver) {
		executeWithClientContext(
			session -> ofNullable(
				QueryUtil.parseQueryUnsafe(
					request.getQuery(),
					Collections.emptyList(),
					Collections.emptyMap(),
					responseObserver,
					(sourceQuery, positionalParams, namedParams, error) ->
						trackFailedQuery(
							session, sourceQuery, positionalParams, namedParams, error, this.trackSourceQueries)
				)
			).ifPresent(
				theQuery -> doQuery(
					responseObserver, session,
					request.getQuery(),
					theQuery,
					this.trackSourceQueries,
					EvitaSessionService::queryListInternal
				)
			),
			this.evita.getRequestExecutor(),
			responseObserver,
			this.tracingContext
		);
	}

	/**
	 * Method used to query catalog calling {@link EvitaSessionContract#query(Query, Class)}.
	 * This method implements UNSAFE approach where values are embedded directly into the query string.
	 *
	 * @param request          request containing query string form with possible usage of positional or named parameters and their respective collections
	 * @param responseObserver observer on which errors might be thrown and result returned
	 */
	@Override
	public void queryUnsafe(GrpcQueryUnsafeRequest request, StreamObserver<GrpcQueryResponse> responseObserver) {
		executeWithClientContext(
			session -> ofNullable(
				QueryUtil.parseQueryUnsafe(
					request.getQuery(),
					Collections.emptyList(),
					Collections.emptyMap(),
					responseObserver,
					(sourceQuery, positionalParams, namedParams, error) ->
						trackFailedQuery(
							session, sourceQuery, positionalParams, namedParams, error, this.trackSourceQueries)
				)
			).ifPresent(
				theQuery ->
					doQuery(
						responseObserver, session,
						request.getQuery(),
						theQuery,
						this.trackSourceQueries,
						EvitaSessionService::queryInternal
					)
			),
			this.evita.getRequestExecutor(),
			responseObserver,
			this.tracingContext
		);
	}

	/**
	 * Method used to get entity by calling {@link EvitaSessionContract#getEntity(String, int, EntityContentRequire...)}.
	 *
	 * @param request          request containing entity type, primary key and string form of {@link EntityContentRequire} constraints
	 * @param responseObserver observer on which errors might be thrown and result returned
	 */
	@Override
	public void getEntity(GrpcEntityRequest request, StreamObserver<GrpcEntityResponse> responseObserver) {
		executeWithClientContext(
			session -> {
				final String require = request.getRequire();
				final Optional<SealedEntity> entity;
				final EntityContentRequire[] entityContentRequires = require.isEmpty() ?
					new EntityContentRequire[0] :
					QueryUtil.parseEntityRequiredContents(
						request.getRequire(),
						request.getPositionalQueryParamsList(),
						request.getNamedQueryParamsMap(),
						responseObserver
					);
				final Scope[] scopes = request.getScopesList()
				                              .stream()
				                              .map(EvitaEnumConverter::toScope)
				                              .toArray(Scope[]::new);

				entity = scopes.length == 0 ?
					session.getEntity(request.getEntityType(), request.getPrimaryKey(), entityContentRequires) :
					session.getEntity(request.getEntityType(), request.getPrimaryKey(), scopes, entityContentRequires);

				final GrpcEntityResponse.Builder evitaEntityResponseBuilder = GrpcEntityResponse.newBuilder();
				entity.ifPresent(it -> {
					final SemVer clientVersion = ServerSessionInterceptor.getClientVersion().orElse(null);
					evitaEntityResponseBuilder.setEntity(EntityConverter.toGrpcSealedEntity(it, clientVersion));
				});
				responseObserver.onNext(evitaEntityResponseBuilder.build());
				responseObserver.onCompleted();
			},
			this.evita.getRequestExecutor(),
			responseObserver,
			this.tracingContext
		);
	}

	@Override
	public void updateCatalogSchema(
		GrpcUpdateCatalogSchemaRequest request, StreamObserver<GrpcUpdateCatalogSchemaResponse> responseObserver) {
		executeWithClientContext(
			session -> {
				final LocalCatalogSchemaMutation[] schemaMutations = request.getSchemaMutationsList()
				                                                            .stream()
				                                                            .map(
					                                                            DelegatingLocalCatalogSchemaMutationConverter.INSTANCE::convert)
				                                                            .toArray(LocalCatalogSchemaMutation[]::new);
				final int newSchemaVersion = session.updateCatalogSchema(schemaMutations);

				final GrpcUpdateCatalogSchemaResponse response = GrpcUpdateCatalogSchemaResponse.newBuilder()
				                                                                                .setVersion(
					                                                                                newSchemaVersion)
				                                                                                .build();
				responseObserver.onNext(response);
				responseObserver.onCompleted();
			},
			this.evita.getRequestExecutor(),
			responseObserver,
			this.tracingContext
		);
	}

	@Override
	public void updateAndFetchCatalogSchema(
		GrpcUpdateCatalogSchemaRequest request,
		StreamObserver<GrpcUpdateAndFetchCatalogSchemaResponse> responseObserver
	) {
		executeWithClientContext(
			session -> {
				final LocalCatalogSchemaMutation[] schemaMutations = request.getSchemaMutationsList()
				                                                            .stream()
				                                                            .map(
					                                                            DelegatingLocalCatalogSchemaMutationConverter.INSTANCE::convert)
				                                                            .toArray(LocalCatalogSchemaMutation[]::new);
				final SealedCatalogSchema newCatalogSchema = session.updateAndFetchCatalogSchema(schemaMutations);

				final GrpcUpdateAndFetchCatalogSchemaResponse response = GrpcUpdateAndFetchCatalogSchemaResponse.newBuilder()
				                                                                                                .setCatalogSchema(
					                                                                                                convert(
						                                                                                                newCatalogSchema,
						                                                                                                false
					                                                                                                ))
				                                                                                                .build();
				responseObserver.onNext(response);
				responseObserver.onCompleted();
			},
			this.evita.getRequestExecutor(),
			responseObserver,
			this.tracingContext
		);
	}

	@Override
	public void defineEntitySchema(
		GrpcDefineEntitySchemaRequest request, StreamObserver<GrpcDefineEntitySchemaResponse> responseObserver) {
		executeWithClientContext(
			session -> {
				final EntitySchemaBuilder entitySchemaBuilder = session.defineEntitySchema(request.getEntityType());

				final GrpcDefineEntitySchemaResponse response = GrpcDefineEntitySchemaResponse.newBuilder()
				                                                                              .setEntitySchema(
					                                                                              EntitySchemaConverter.convert(
						                                                                              session.getCatalogSchema(),
						                                                                              entitySchemaBuilder.toInstance(),
						                                                                              false
					                                                                              )
				                                                                              )
				                                                                              .build();
				responseObserver.onNext(response);
				responseObserver.onCompleted();
			},
			this.evita.getRequestExecutor(),
			responseObserver,
			this.tracingContext
		);
	}

	@Override
	public void updateEntitySchema(
		GrpcUpdateEntitySchemaRequest request, StreamObserver<GrpcUpdateEntitySchemaResponse> responseObserver) {
		executeWithClientContext(
			session -> {
				final ModifyEntitySchemaMutation schemaMutation = ModifyEntitySchemaMutationConverter.INSTANCE.convert(
					request.getSchemaMutation());
				final int newSchemaVersion = session.updateEntitySchema(schemaMutation);

				final GrpcUpdateEntitySchemaResponse response = GrpcUpdateEntitySchemaResponse.newBuilder()
				                                                                              .setVersion(
					                                                                              newSchemaVersion)
				                                                                              .build();
				responseObserver.onNext(response);
				responseObserver.onCompleted();
			},
			this.evita.getRequestExecutor(),
			responseObserver,
			this.tracingContext
		);
	}

	@Override
	public void updateAndFetchEntitySchema(
		GrpcUpdateEntitySchemaRequest request,
		StreamObserver<GrpcUpdateAndFetchEntitySchemaResponse> responseObserver
	) {
		executeWithClientContext(
			session -> {
				final ModifyEntitySchemaMutation schemaMutation = ModifyEntitySchemaMutationConverter.INSTANCE.convert(
					request.getSchemaMutation());
				final SealedEntitySchema newEntitySchema = session.updateAndFetchEntitySchema(schemaMutation);

				final GrpcUpdateAndFetchEntitySchemaResponse response = GrpcUpdateAndFetchEntitySchemaResponse.newBuilder()
				                                                                                              .setEntitySchema(
					                                                                                              EntitySchemaConverter.convert(
						                                                                                              session.getCatalogSchema(),
						                                                                                              newEntitySchema,
						                                                                                              false
					                                                                                              ))
				                                                                                              .build();
				responseObserver.onNext(response);
				responseObserver.onCompleted();
			},
			this.evita.getRequestExecutor(),
			responseObserver,
			this.tracingContext
		);
	}

	/**
	 * Method used to delete collection by calling {@link EvitaSessionContract#deleteCollection(String)}.
	 *
	 * @param request          request containing name of collection which is to be deleted
	 * @param responseObserver observer on which errors might be thrown and result returned
	 */
	@Override
	public void deleteCollection(
		GrpcDeleteCollectionRequest request, StreamObserver<GrpcDeleteCollectionResponse> responseObserver) {
		executeWithClientContext(
			session -> {
				responseObserver.onNext(
					GrpcDeleteCollectionResponse.newBuilder()
					                            .setDeleted(session.deleteCollection(request.getEntityType()))
					                            .build()
				);
				responseObserver.onCompleted();
			},
			this.evita.getRequestExecutor(),
			responseObserver,
			this.tracingContext
		);
	}

	/**
	 * Method used to rename one collection to a new name.
	 *
	 * @param request          request containing entity type and new - renamed entity type
	 * @param responseObserver observer on which errors might be thrown and result returned
	 * @see EvitaSessionContract#renameCollection(String, String) (String, String)
	 */
	@Override
	public void renameCollection(
		GrpcRenameCollectionRequest request, StreamObserver<GrpcRenameCollectionResponse> responseObserver) {
		executeWithClientContext(
			session -> {
				final boolean renamed = session.renameCollection(request.getEntityType(), request.getNewName());

				final GrpcRenameCollectionResponse response = GrpcRenameCollectionResponse.newBuilder()
				                                                                          .setRenamed(renamed)
				                                                                          .build();
				responseObserver.onNext(response);
				responseObserver.onCompleted();
			},
			this.evita.getRequestExecutor(),
			responseObserver,
			this.tracingContext
		);
	}

	/**
	 * Method used to replace one collection with another.
	 *
	 * @param request          request containing entity type and replaced entity type
	 * @param responseObserver observer on which errors might be thrown and result returned
	 * @see EvitaSessionContract#replaceCollection(String, String)
	 */
	@Override
	public void replaceCollection(
		GrpcReplaceCollectionRequest request, StreamObserver<GrpcReplaceCollectionResponse> responseObserver) {
		executeWithClientContext(
			session -> {
				final boolean replaced = session.replaceCollection(
					request.getEntityTypeToBeReplaced(), request.getEntityTypeToBeReplacedWith());

				final GrpcReplaceCollectionResponse response = GrpcReplaceCollectionResponse.newBuilder()
				                                                                            .setReplaced(replaced)
				                                                                            .build();
				responseObserver.onNext(response);
				responseObserver.onCompleted();
			},
			this.evita.getRequestExecutor(),
			responseObserver,
			this.tracingContext
		);
	}

	/**
	 * Method used to get size of specified collection by calling {@link EvitaSessionContract#getEntityCollectionSize(String)}.
	 *
	 * @param request          request containing name of collection which size is to be returned
	 * @param responseObserver observer on which errors might be thrown and result returned
	 */
	@Override
	public void getEntityCollectionSize(
		GrpcEntityCollectionSizeRequest request, StreamObserver<GrpcEntityCollectionSizeResponse> responseObserver) {
		executeWithClientContext(
			session -> {
				responseObserver.onNext(
					GrpcEntityCollectionSizeResponse.newBuilder()
					                                .setSize(session.getEntityCollectionSize(request.getEntityType()))
					                                .build()
				);
				responseObserver.onCompleted();
			},
			this.evita.getRequestExecutor(),
			responseObserver,
			this.tracingContext
		);
	}

	/**
	 * Method used to upsert or removing entities by internally calling {@link EvitaSessionContract#upsertEntity(EntityMutation)} with passing a custom collection of mutations processed by {@link DelegatingLocalMutationConverter}.
	 *
	 * @param request          request containing mutation to be performed
	 * @param responseObserver observer on which errors might be thrown and result returned
	 */
	@Override
	public void upsertEntity(
		GrpcUpsertEntityRequest request, StreamObserver<GrpcUpsertEntityResponse> responseObserver) {
		executeWithClientContext(
			session -> {
				final GrpcUpsertEntityResponse.Builder builder = GrpcUpsertEntityResponse.newBuilder();
				final EntityMutation entityMutation = DelegatingEntityMutationConverter.INSTANCE.convert(
					request.getEntityMutation());

				final String require = request.getRequire();
				final EntityContentRequire[] entityContentRequires = require.isEmpty() ?
					new EntityContentRequire[0] :
					QueryUtil.parseEntityRequiredContents(
						request.getRequire(),
						request.getPositionalQueryParamsList(),
						request.getNamedQueryParamsMap(),
						responseObserver
					);

				if (ArrayUtils.isEmpty(entityContentRequires)) {
					final EntityReference entityReference = session.upsertEntity(entityMutation);
					builder.setEntityReference(GrpcEntityReference.newBuilder()
					                                              .setEntityType(entityReference.getType())
					                                              .setPrimaryKey(entityReference.getPrimaryKey())
					                                              .build()
					);
				} else {
					final SemVer clientVersion = ServerSessionInterceptor.getClientVersion().orElse(null);
					final SealedEntity updatedEntity = session.upsertAndFetchEntity(
						entityMutation, entityContentRequires);
					builder.setEntity(EntityConverter.toGrpcSealedEntity(updatedEntity, clientVersion));
				}
				responseObserver.onNext(builder.build());
				responseObserver.onCompleted();
			},
			this.evita.getRequestExecutor(),
			responseObserver,
			this.tracingContext
		);
	}

	/**
	 * Method used to remove single entity by primary key by calling {@link EvitaSessionContract#deleteEntity(String, int)}.
	 *
	 * @param request          request containing entity type and primary key of removed entity
	 * @param responseObserver observer on which errors might be thrown and result returned
	 */
	@Override
	public void deleteEntity(
		GrpcDeleteEntityRequest request, StreamObserver<GrpcDeleteEntityResponse> responseObserver) {
		executeWithClientContext(
			session -> {
				final String entityType = request.getEntityType();
				final int primaryKey = request.getPrimaryKey().getValue();
				final String require = request.getRequire();
				final Optional<SealedEntity> entity;
				final EntityContentRequire[] entityContentRequires = require.isEmpty() ?
					new EntityContentRequire[0] :
					QueryUtil.parseEntityRequiredContents(
						request.getRequire(),
						request.getPositionalQueryParamsList(),
						request.getNamedQueryParamsMap(),
						responseObserver
					);

				final boolean deleted;
				if (ArrayUtils.isEmpty(entityContentRequires)) {
					entity = empty();
					deleted = session.deleteEntity(entityType, primaryKey);
				} else {
					entity = session.deleteEntity(entityType, primaryKey, entityContentRequires);
					deleted = entity.isPresent();
				}

				final GrpcDeleteEntityResponse.Builder response = GrpcDeleteEntityResponse.newBuilder();
				if (deleted) {
					response.setEntityReference(
						GrpcEntityReference
							.newBuilder()
							.setEntityType(entityType)
							.setPrimaryKey(primaryKey)
							.build()
					);
				}
				entity.ifPresent(it -> {
					final SemVer clientVersion = ServerSessionInterceptor.getClientVersion().orElse(null);
					response.setEntity(EntityConverter.toGrpcSealedEntity(it, clientVersion));
				});
				responseObserver.onNext(
					response.build()
				);
				responseObserver.onCompleted();
			},
			this.evita.getRequestExecutor(),
			responseObserver,
			this.tracingContext
		);
	}

	/**
	 * Method used to remove single entity along with its nested hierarchy tree by primary key by calling
	 * {@link EvitaSessionContract#deleteEntityAndItsHierarchy(String, int)} .
	 *
	 * @param request          request containing entity type and primary key of removed entity
	 * @param responseObserver observer on which errors might be thrown and result returned
	 */
	@Override
	public void deleteEntityAndItsHierarchy(
		GrpcDeleteEntityRequest request,
		StreamObserver<GrpcDeleteEntityAndItsHierarchyResponse> responseObserver
	) {
		executeWithClientContext(
			session -> {
				final String entityType = request.getEntityType();
				final int primaryKey = request.getPrimaryKey().getValue();
				final String require = request.getRequire();
				final EntityContentRequire[] entityContentRequires = require.isEmpty() ?
					new EntityContentRequire[0] :
					QueryUtil.parseEntityRequiredContents(
						request.getRequire(),
						request.getPositionalQueryParamsList(),
						request.getNamedQueryParamsMap(),
						responseObserver
					);

				final DeletedHierarchy<SealedEntity> deletedHierarchy = session.deleteEntityAndItsHierarchy(
					entityType, primaryKey, entityContentRequires);

				final GrpcDeleteEntityAndItsHierarchyResponse.Builder response = GrpcDeleteEntityAndItsHierarchyResponse
					.newBuilder()
					.addAllDeletedEntityPrimaryKeys(Arrays.stream(deletedHierarchy.deletedEntityPrimaryKeys())
					                                      .boxed()
					                                      .toList())
					.setDeletedEntities(deletedHierarchy.deletedEntities());
				ofNullable(deletedHierarchy.deletedRootEntity())
					.ifPresent(it -> {
						final SemVer clientVersion = ServerSessionInterceptor.getClientVersion().orElse(null);
						response.setDeletedRootEntity(EntityConverter.toGrpcSealedEntity(it, clientVersion));
					});
				responseObserver.onNext(
					response.build()
				);
				responseObserver.onCompleted();
			},
			this.evita.getRequestExecutor(),
			responseObserver,
			this.tracingContext
		);
	}

	/**
	 * Method used to remove multiple entities by query by calling {@link EvitaSessionContract#deleteEntities(Query)}.
	 *
	 * @param request          request containing the removal query
	 * @param responseObserver observer on which errors might be thrown and result returned
	 */
	@Override
	public void deleteEntities(
		GrpcDeleteEntitiesRequest request, StreamObserver<GrpcDeleteEntitiesResponse> responseObserver) {
		executeWithClientContext(
			session -> ofNullable(
				QueryUtil.parseQuery(
					request.getQuery(),
					request.getPositionalQueryParamsList(),
					request.getNamedQueryParamsMap(),
					responseObserver,
					(sourceQuery, positionalParams, namedParams, error) ->
						trackFailedQuery(
							session, sourceQuery, positionalParams, namedParams, error, this.trackSourceQueries)
				)
			).ifPresent(
				theQuery -> doQuery(
					responseObserver, session,
					request.getQuery(),
					theQuery,
					this.trackSourceQueries,
					(grpcDeleteEntitiesResponseStreamObserver, evitaInternalSessionContract, query, label) -> {
						if (query != null) {
							final int deletedEntities;
							final SealedEntity[] deletedEntityBodies;
							if (query.getRequire() == null ||
								FinderVisitor.findConstraints(query.getRequire(), EntityFetch.class::isInstance)
								             .isEmpty()) {
								deletedEntities = session.deleteEntities(query);
								deletedEntityBodies = null;
							} else {
								deletedEntityBodies = session.deleteSealedEntitiesAndReturnBodies(query);
								deletedEntities = deletedEntityBodies.length;
							}

							final GrpcDeleteEntitiesResponse.Builder response = GrpcDeleteEntitiesResponse
								.newBuilder()
								.setDeletedEntities(deletedEntities);
							ofNullable(deletedEntityBodies)
								.ifPresent(
									it -> {
										final SemVer clientVersion = ServerSessionInterceptor.getClientVersion().orElse(
											null);
										Arrays.stream(it)
										      .map(entity -> EntityConverter.toGrpcSealedEntity(entity, clientVersion))
										      .forEach(response::addDeletedEntityBodies);
									}
								);
							responseObserver.onNext(
								response.build()
							);
						}
						responseObserver.onCompleted();
					}
				)
			),
			this.evita.getRequestExecutor(),
			responseObserver,
			this.tracingContext
		);
	}

	/**
	 * Method used to archive single entity by primary key by calling {@link EvitaSessionContract#archiveEntity(String, int)}.
	 *
	 * @param request          request containing entity type and primary key of archived entity
	 * @param responseObserver observer on which errors might be thrown and result returned
	 */
	@Override
	public void archiveEntity(
		GrpcArchiveEntityRequest request, StreamObserver<GrpcArchiveEntityResponse> responseObserver) {
		executeWithClientContext(
			session -> {
				final String entityType = request.getEntityType();
				final int primaryKey = request.getPrimaryKey().getValue();
				final String require = request.getRequire();
				final Optional<SealedEntity> entity;
				final EntityContentRequire[] entityContentRequires = require.isEmpty() ?
					new EntityContentRequire[0] :
					QueryUtil.parseEntityRequiredContents(
						request.getRequire(),
						request.getPositionalQueryParamsList(),
						request.getNamedQueryParamsMap(),
						responseObserver
					);

				final boolean archived;
				if (ArrayUtils.isEmpty(entityContentRequires)) {
					entity = empty();
					archived = session.archiveEntity(entityType, primaryKey);
				} else {
					entity = session.archiveEntity(entityType, primaryKey, entityContentRequires);
					archived = entity.isPresent();
				}

				final GrpcArchiveEntityResponse.Builder response = GrpcArchiveEntityResponse.newBuilder();
				if (archived) {
					response.setEntityReference(
						GrpcEntityReference
							.newBuilder()
							.setEntityType(entityType)
							.setPrimaryKey(primaryKey)
							.build()
					);
				}
				entity.ifPresent(it -> {
					final SemVer clientVersion = ServerSessionInterceptor.getClientVersion().orElse(null);
					response.setEntity(EntityConverter.toGrpcSealedEntity(it, clientVersion));
				});
				responseObserver.onNext(
					response.build()
				);
				responseObserver.onCompleted();
			},
			this.evita.getRequestExecutor(),
			responseObserver,
			this.tracingContext
		);
	}

	/**
	 * Method used to restore single entity by primary key by calling {@link EvitaSessionContract#restoreEntity(String, int)}.
	 *
	 * @param request          request containing entity type and primary key of restored entity
	 * @param responseObserver observer on which errors might be thrown and result returned
	 */
	@Override
	public void restoreEntity(
		GrpcRestoreEntityRequest request, StreamObserver<GrpcRestoreEntityResponse> responseObserver) {
		executeWithClientContext(
			session -> {
				final String entityType = request.getEntityType();
				final int primaryKey = request.getPrimaryKey().getValue();
				final String require = request.getRequire();
				final Optional<SealedEntity> entity;
				final EntityContentRequire[] entityContentRequires = require.isEmpty() ?
					new EntityContentRequire[0] :
					QueryUtil.parseEntityRequiredContents(
						request.getRequire(),
						request.getPositionalQueryParamsList(),
						request.getNamedQueryParamsMap(),
						responseObserver
					);

				final boolean restored;
				if (ArrayUtils.isEmpty(entityContentRequires)) {
					entity = empty();
					restored = session.restoreEntity(entityType, primaryKey);
				} else {
					entity = session.restoreEntity(entityType, primaryKey, entityContentRequires);
					restored = entity.isPresent();
				}

				final GrpcRestoreEntityResponse.Builder response = GrpcRestoreEntityResponse.newBuilder();
				if (restored) {
					response.setEntityReference(
						GrpcEntityReference
							.newBuilder()
							.setEntityType(entityType)
							.setPrimaryKey(primaryKey)
							.build()
					);
				}
				entity.ifPresent(it -> {
					final SemVer clientVersion = ServerSessionInterceptor.getClientVersion().orElse(null);
					response.setEntity(EntityConverter.toGrpcSealedEntity(it, clientVersion));
				});
				responseObserver.onNext(
					response.build()
				);
				responseObserver.onCompleted();
			},
			this.evita.getRequestExecutor(),
			responseObserver,
			this.tracingContext
		);
	}

	/**
	 * Method returns current transaction id if any is opened.
	 *
	 * @param request          empty request
	 * @param responseObserver observer on which errors might be thrown and result returned
	 */
	@Override
	public void getTransactionId(Empty request, StreamObserver<GrpcTransactionResponse> responseObserver) {
		executeWithClientContext(
			session -> {
				final GrpcTransactionResponse.Builder builder = GrpcTransactionResponse
					.newBuilder();
				session.getOpenedTransactionId()
				       .ifPresent(txId -> builder.setTransactionId(toGrpcUuid(txId))
				);
				responseObserver.onNext(
					builder
						.setCatalogVersion(session.getCatalogVersion())
						.build()
				);
				responseObserver.onCompleted();
			},
			this.evita.getRequestExecutor(),
			responseObserver,
			this.tracingContext
		);
	}

	/**
	 * Registers a change catalog capture based on the provided request.
	 *
	 * @param request The gRPC request containing the details for the change catalog capture to be registered.
	 * @param responseObserver The stream observer used to send the response of the operation.
	 */
	@Override
	public void registerChangeCatalogCapture(
		GrpcRegisterChangeCatalogCaptureRequest request,
		StreamObserver<GrpcRegisterChangeCatalogCaptureResponse> responseObserver
	) {
		final CompletableFuture<Subscription> subscriptionFuture = new CompletableFuture<>();
		((ServerCallStreamObserver<GrpcRegisterChangeCatalogCaptureResponse>)responseObserver).setOnCancelHandler(
			() -> {
				try {
					subscriptionFuture.get().cancel();
				} catch (Exception e) {
					log.debug("Failed to remove progress listener on cancel", e);
				}
			}
		);

		executeWithClientContext(
			session -> session.registerChangeCatalogCapture(
				ChangeCaptureConverter.toChangeCatalogCaptureRequest(request)
			).subscribe(
				new ChangeCatalogCaptureSubscriber(responseObserver, subscriptionFuture)
			),
			this.evita.getRequestExecutor(),
			responseObserver,
			this.tracingContext
		);
	}

	/**
	 * The LabelAppender class is a utility that appends a specific label to a HeadConstraint exactly once.
	 * It implements the UnaryOperator interface, taking a HeadConstraint and returning a modified version
	 * of it with the appended label.
	 *
	 * This class is designed to act in an idempotent manner, ensuring that the label is only appended
	 * the first time the apply method is called. Subsequent calls with the same instance will return
	 * the original HeadConstraint without modification.
	 */
	@SuppressWarnings("rawtypes")
	@RequiredArgsConstructor
	private static class LabelAppender implements UnaryOperator<Constraint> {
		private final Label[] labels;
		private boolean appended = false;

		@Override
		public Constraint apply(Constraint constraint) {
			if (this.appended) {
				return constraint;
			} else if (constraint instanceof HeadConstraint headConstraint) {
				this.appended = true;
				final List<HeadConstraint> constraints = new ArrayList<>(this.labels.length + 1);
				constraints.add(headConstraint);
				Arrays.stream(this.labels).filter(Objects::nonNull).forEach(constraints::add);

				//noinspection DataFlowIssue
				return head(constraints.toArray(HeadConstraint[]::new));
			} else {
				throw new UnsupportedOperationException("Cannot append labels to a non-head constraint.");
			}
		}

	}

	/**
	 * A private static class implementing the {@link Subscriber} interface to handle
	 * subscription and communication logic for {@link ChangeCatalogCapture} events.
	 *
	 * This class acts as a bridge between a publisher of {@link ChangeCatalogCapture}
	 * events and a gRPC client through the {@link StreamObserver}. It manages the
	 * lifecycle of the subscription to the publisher and relays received events or
	 * errors to the client.
	 *
	 * Key responsibilities:
	 * - Requests one item at a time from the publisher, ensuring backpressure support.
	 * - Relays each {@link ChangeCatalogCapture} event to the gRPC client in the form
	 *   of a {@link GrpcRegisterChangeCatalogCaptureResponse}.
	 * - Handles the completion and error states of the subscription.
	 */
	@RequiredArgsConstructor
	private static class ChangeCatalogCaptureSubscriber implements Subscriber<ChangeCatalogCapture> {
		private final StreamObserver<GrpcRegisterChangeCatalogCaptureResponse> responseObserver;
		private final CompletableFuture<Subscription> subscriptionFuture;
		private Subscription subscription;

		@Override
		public void onSubscribe(Subscription subscription) {
			this.subscription = subscription;
			this.subscriptionFuture.complete(subscription);
			final GrpcRegisterChangeCatalogCaptureResponse.Builder response = GrpcRegisterChangeCatalogCaptureResponse
				.newBuilder();
			if (subscription instanceof ChangeCaptureSubscription ccs) {
				response.setUuid(toGrpcUuid(ccs.getSubscriptionId()));
			}
			this.responseObserver.onNext(
				response
					.setResponseType(GrpcCaptureResponseType.ACKNOWLEDGEMENT)
					.build()
			);
			subscription.request(1);
		}

		@Override
		public void onNext(ChangeCatalogCapture item) {
			this.responseObserver.onNext(
				GrpcRegisterChangeCatalogCaptureResponse
					.newBuilder()
					.setCapture(ChangeCaptureConverter.toGrpcChangeCatalogCapture(item))
					.setResponseType(GrpcCaptureResponseType.CHANGE)
					.build()
			);
			this.subscription.request(1);
		}

		@Override
		public void onError(Throwable throwable) {
			this.subscriptionFuture.completeExceptionally(throwable);
			this.responseObserver.onError(throwable);
		}

		@Override
		public void onComplete() {
			this.responseObserver.onCompleted();
		}
	}
}
