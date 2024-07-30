/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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
import io.evitadb.api.CatalogState;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.file.FileForFetch;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.require.EntityContentRequire;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.query.visitor.FinderVisitor;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture;
import io.evitadb.api.requestResponse.data.DeletedHierarchy;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation;
import io.evitadb.api.requestResponse.data.structure.BinaryEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.SealedCatalogSchema;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.api.requestResponse.system.CatalogVersion;
import io.evitadb.api.task.Task;
import io.evitadb.core.Evita;
import io.evitadb.core.EvitaInternalSessionContract;
import io.evitadb.dataType.DataChunk;
import io.evitadb.dataType.PaginatedList;
import io.evitadb.dataType.StripList;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.externalApi.grpc.builders.query.extraResults.GrpcExtraResultsBuilder;
import io.evitadb.externalApi.grpc.constants.GrpcHeaders;
import io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter;
import io.evitadb.externalApi.grpc.generated.*;
import io.evitadb.externalApi.grpc.generated.GrpcEntitySchemaResponse.Builder;
import io.evitadb.externalApi.grpc.requestResponse.cdc.ChangeCaptureConverter;
import io.evitadb.externalApi.grpc.requestResponse.data.EntityConverter;
import io.evitadb.externalApi.grpc.requestResponse.data.mutation.DelegatingEntityMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.data.mutation.DelegatingLocalMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.EntitySchemaConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.DelegatingLocalCatalogSchemaMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutationConverter;
import io.evitadb.externalApi.grpc.services.interceptors.ServerSessionInterceptor;
import io.evitadb.externalApi.grpc.utils.QueryUtil;
import io.evitadb.externalApi.trace.ExternalApiTracingContextProvider;
import io.evitadb.utils.ArrayUtils;
import io.grpc.Metadata;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter.toGrpcOffsetDateTime;
import static io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter.toGrpcTaskStatus;
import static io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter.toGrpcUuid;
import static io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter.toOffsetDateTime;
import static io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter.toCommitBehavior;
import static io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter.toGrpcCatalogState;
import static io.evitadb.externalApi.grpc.requestResponse.schema.CatalogSchemaConverter.convert;
import static java.util.Optional.empty;
import static java.util.Optional.ofNullable;

/**
 * This service contains methods that could be called by gRPC clients on {@link GrpcEvitaSessionAPI}.
 *
 * @author Tomáš Pozler, 2022
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@Slf4j
@RequiredArgsConstructor
public class EvitaSessionService extends EvitaSessionServiceGrpc.EvitaSessionServiceImplBase {

	/**
	 * Instance of Evita upon which will be executed service calls
	 */
	@Nonnull private final Evita evita;

	/**
	 * Executes entire lambda function within the scope of a tracing context.
	 */
	private static void executeWithClientContext(@Nonnull Consumer<EvitaInternalSessionContract> lambda) {
		final Metadata metadata = ServerSessionInterceptor.METADATA.get();
		ExternalApiTracingContextProvider.getContext()
			.executeWithinBlock(
				GrpcHeaders.getGrpcTraceTaskNameWithMethodName(metadata),
				metadata,
				() -> {
					final EvitaInternalSessionContract session = ServerSessionInterceptor.SESSION.get();
					lambda.accept(session);
				}
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
		@Nullable Query query
	) {
		if (query != null) {
			final EvitaRequest evitaRequest = new EvitaRequest(
				query,
				OffsetDateTime.now(),
				EntityClassifier.class,
				null,
				EvitaRequest.CONVERSION_NOT_SUPPORTED
			);

			final GrpcQueryOneResponse.Builder responseBuilder = GrpcQueryOneResponse.newBuilder();
			session.queryOne(evitaRequest).ifPresent(responseEntity -> {
				if (responseEntity instanceof final EntityReference entityReference) {
					responseBuilder.setEntityReference(GrpcEntityReference.newBuilder()
						.setEntityType(entityReference.getType())
						.setPrimaryKey(entityReference.getPrimaryKey())
						.build());
				} else if (responseEntity instanceof final SealedEntity sealedEntity) {
					responseBuilder.setSealedEntity(EntityConverter.toGrpcSealedEntity(sealedEntity));
				} else if (responseEntity instanceof final BinaryEntity binaryEntity) {
					responseBuilder.setBinaryEntity(EntityConverter.toGrpcBinaryEntity(binaryEntity));
				} else {
					throw new GenericEvitaInternalError("Unsupported entity class `" + responseEntity.getClass().getName() + "`.");
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
		@Nullable Query query
	) {
		if (query != null) {
			final EvitaRequest evitaRequest = new EvitaRequest(
				query,
				OffsetDateTime.now(),
				EntityClassifier.class,
				null,
				EvitaRequest.CONVERSION_NOT_SUPPORTED
			);
			final List<EntityClassifier> responseEntities = session.queryList(evitaRequest);
			final GrpcQueryListResponse.Builder responseBuilder = GrpcQueryListResponse.newBuilder();
			final EntityFetch entityFetchRequirement = evitaRequest.getEntityRequirement();
			if (entityFetchRequirement != null) {
				if (session.isBinaryFormat()) {
					responseEntities.forEach(e ->
						responseBuilder.addBinaryEntities(EntityConverter.toGrpcBinaryEntity((BinaryEntity) e))
					);
				} else {
					responseEntities.forEach(entity ->
						responseBuilder.addSealedEntities(EntityConverter.toGrpcSealedEntity((SealedEntity) entity))
					);
				}
			} else {
				responseEntities.forEach(e ->
					responseBuilder.addEntityReferences(GrpcEntityReference.newBuilder()
						.setEntityType(e.getType())
						.setPrimaryKey(((EntityReference) e).getPrimaryKey())
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
		@Nullable Query query
	) {
		if (query != null) {
			final EvitaRequest evitaRequest = new EvitaRequest(
				query.normalizeQuery(),
				OffsetDateTime.now(),
				EntityClassifier.class,
				null,
				EvitaRequest.CONVERSION_NOT_SUPPORTED
			);

			final EvitaResponse<EntityClassifier> evitaResponse = session.query(evitaRequest);
			final GrpcQueryResponse.Builder entityBuilder = GrpcQueryResponse.newBuilder();
			final DataChunk<EntityClassifier> recordPage = evitaResponse.getRecordPage();
			final GrpcDataChunk.Builder dataChunkBuilder = GrpcDataChunk.newBuilder()
				.setTotalRecordCount(evitaResponse.getTotalRecordCount())
				.setIsFirst(recordPage.isFirst())
				.setIsLast(recordPage.isLast())
				.setHasPrevious(recordPage.hasPrevious())
				.setHasNext(recordPage.hasNext())
				.setIsSinglePage(recordPage.isSinglePage())
				.setIsEmpty(recordPage.isEmpty());

			if (recordPage instanceof PaginatedList<?> paginatedList) {
				dataChunkBuilder.getPaginatedListBuilder()
					.setPageNumber(paginatedList.getPageNumber())
					.setPageSize(paginatedList.getPageSize());
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
						binaryEntities.add(EntityConverter.toGrpcBinaryEntity((BinaryEntity) e))
					);
					entityBuilder.setRecordPage(dataChunkBuilder
						.addAllBinaryEntities(binaryEntities)
						.build()
					);
				} else {
					final List<GrpcSealedEntity> sealedEntities = new ArrayList<>(recordPage.getData().size());
					recordPage.stream().forEach(e ->
						sealedEntities.add(EntityConverter.toGrpcSealedEntity((SealedEntity) e))
					);
					entityBuilder.setRecordPage(dataChunkBuilder
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
							.setPrimaryKey(((EntityReference) e).getPrimaryKey())
							.build())
				);
				entityBuilder.setRecordPage(dataChunkBuilder
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
	 * Produces the {@link CatalogSchema}.
	 */
	@Override
	public void getCatalogSchema(GrpcGetCatalogSchemaRequest request, StreamObserver<GrpcCatalogSchemaResponse> responseObserver) {
		executeWithClientContext(session -> {
			final SealedCatalogSchema catalogSchema = session.getCatalogSchema();
			responseObserver.onNext(
				GrpcCatalogSchemaResponse.newBuilder()
					.setCatalogSchema(convert(catalogSchema, request.getNameVariants()))
					.build()
			);
			responseObserver.onCompleted();
		});
	}

	/**
	 * Returns the current state of the catalog.
	 */
	@Override
	public void getCatalogState(Empty request, StreamObserver<GrpcCatalogStateResponse> responseObserver) {
		executeWithClientContext(session -> {
			final CatalogState catalogState = session.getCatalogState();
			responseObserver.onNext(
				GrpcCatalogStateResponse.newBuilder()
					.setState(toGrpcCatalogState(catalogState))
					.build()
			);
			responseObserver.onCompleted();
		});
	}

	/**
	 * Method returns valid catalog version and the introduction date for the given moment in time.
	 *
	 * @param request          request containing the moment in time
	 * @param responseObserver observer on which errors might be thrown and result returned
	 */
	/* TODO JNO - dopsat testy na tyto nové metody */
	@Override
	public void getCatalogVersionAt(GrpcCatalogVersionAtRequest request, StreamObserver<GrpcCatalogVersionAtResponse> responseObserver) {
		executeWithClientContext(session -> {
			final CatalogVersion catalogVersionAt = session.getCatalogVersionAt(
				toOffsetDateTime(request.getTheMoment())
			);
			responseObserver.onNext(
				GrpcCatalogVersionAtResponse.newBuilder()
					.setVersion(catalogVersionAt.version())
					.setIntroducedAt(toGrpcOffsetDateTime(catalogVersionAt.introducedAt()))
					.build()
			);
			responseObserver.onCompleted();
		});
	}

	/**
	 * Method returns page of historical mutations in the form of change capture events that match given criteria and
	 * pagination settings.
	 *
	 * @param request          request containing the criteria and pagination settings
	 * @param responseObserver observer on which errors might be thrown and result returned
	 */
	@Override
	public void getMutationsHistoryPage(GetMutationsHistoryPageRequest request, StreamObserver<GetMutationsHistoryPageResponse> responseObserver) {
		executeWithClientContext(session -> {
			final Stream<ChangeCatalogCapture> mutationsHistoryStream = session.getMutationsHistory(
				ChangeCaptureConverter.toChangeCaptureRequest(request)
			);
			final GetMutationsHistoryPageResponse.Builder builder = GetMutationsHistoryPageResponse.newBuilder();
			mutationsHistoryStream
				.skip(PaginatedList.getFirstItemNumberForPage(request.getPage(), request.getPageSize()))
				.limit(request.getPageSize())
				.forEach(cdcEvent -> builder.addChangeCapture(ChangeCaptureConverter.toGrpcChangeCatalogCapture(cdcEvent)));
			responseObserver.onNext(builder.build());
			responseObserver.onCompleted();
		});
	}

	/**
	 * Method returns all historical mutations in the form of change capture events that match given criteria.
	 *
	 * @param request          request containing the criteria
	 * @param responseObserver observer on which errors might be thrown and result returned
	 */
	@Override
	public void getMutationsHistory(GetMutationsHistoryRequest request, StreamObserver<GetMutationsHistoryResponse> responseObserver) {
		executeWithClientContext(session -> {
			final Stream<ChangeCatalogCapture> mutationsHistoryStream = session.getMutationsHistory(
				ChangeCaptureConverter.toChangeCaptureRequest(request)
			);
			mutationsHistoryStream.forEach(
				cdcEvent -> {
					final GetMutationsHistoryResponse.Builder builder = GetMutationsHistoryResponse.newBuilder();
					builder.addChangeCapture(ChangeCaptureConverter.toGrpcChangeCatalogCapture(cdcEvent));
					responseObserver.onNext(builder.build());
				}
			);
			responseObserver.onCompleted();
		});
	}

	/**
	 * Produces the {@link EntitySchema} for {@link GrpcEntitySchemaRequest#getEntityType()}.
	 */
	@Override
	public void getEntitySchema(GrpcEntitySchemaRequest request, StreamObserver<GrpcEntitySchemaResponse> responseObserver) {
		executeWithClientContext(session -> {
			final Builder responseBuilder = GrpcEntitySchemaResponse.newBuilder();
			session.getEntitySchema(request.getEntityType())
				.ifPresent(it -> responseBuilder.setEntitySchema(EntitySchemaConverter.convert(it, request.getNameVariants())));

			responseObserver.onNext(
				responseBuilder.build()
			);
			responseObserver.onCompleted();
		});
	}

	/**
	 * Method used to get set of all stored entity types by calling {@link EvitaSessionContract#getAllEntityTypes()}.
	 *
	 * @param request          empty request
	 * @param responseObserver observer on which errors might be thrown and result returned
	 */
	@Override
	public void getAllEntityTypes(Empty request, StreamObserver<GrpcEntityTypesResponse> responseObserver) {
		executeWithClientContext(session -> {
			responseObserver.onNext(
				GrpcEntityTypesResponse.newBuilder()
					.addAllEntityTypes(session.getAllEntityTypes())
					.build()
			);
			responseObserver.onCompleted();
		});
	}

	/**
	 * Method used to switch catalog state to {@link CatalogState#ALIVE} and close currently used session by calling {@link EvitaSessionContract#goLiveAndClose()}.
	 *
	 * @param request          empty request
	 * @param responseObserver observer on which errors might be thrown and result returned
	 */
	@Override
	public void goLiveAndClose(Empty request, StreamObserver<GrpcGoLiveAndCloseResponse> responseObserver) {
		executeWithClientContext(session -> {
			final boolean success;
			if (session == null) {
				success = false;
			} else {
				success = session.goLiveAndClose();
			}

			final GrpcGoLiveAndCloseResponse response = GrpcGoLiveAndCloseResponse.newBuilder()
				.setSuccess(success)
				.build();
			responseObserver.onNext(response);
			responseObserver.onCompleted();
		});
	}

	/**
	 * Method allows to backup a catalog and send the backup file to the client.
	 *
	 * @param request          empty request
	 * @param responseObserver observer on which errors might be thrown and result returned
	 */
	@Override
	public void backupCatalog(GrpcBackupCatalogRequest request, StreamObserver<GrpcBackupCatalogResponse> responseObserver) {
		executeWithClientContext(session -> {
			final Task<?, FileForFetch> backupTask = session.backupCatalog(
				request.hasPastMoment() ?
					EvitaDataTypesConverter.toOffsetDateTime(request.getPastMoment()) :
					null,
				request.getIncludingWAL()
			);

			responseObserver.onNext(
				GrpcBackupCatalogResponse.newBuilder()
					.setTaskStatus(toGrpcTaskStatus(backupTask.getStatus()))
					.build()
			);

			responseObserver.onCompleted();
		});
	}

	/**
	 * Method used to close currently used session by calling {@link EvitaSessionContract#close()}.
	 *
	 * @param request          empty request
	 * @param responseObserver observer on which errors might be thrown and result returned
	 */
	@Override
	public void close(GrpcCloseRequest request, StreamObserver<GrpcCloseResponse> responseObserver) {
		executeWithClientContext(session -> {
			if (session != null) {
				final CompletableFuture<Long> future = session.closeNow(toCommitBehavior(request.getCommitBehaviour()));
				future.whenComplete((version, throwable) -> {
					if (throwable != null) {
						responseObserver.onError(throwable);
					} else {
						responseObserver.onNext(GrpcCloseResponse.newBuilder().setCatalogVersion(version).build());
					}
					responseObserver.onCompleted();
				});
			} else {
				// no session to close, we couldn't return the catalog version
				responseObserver.onCompleted();
			}
		});
	}

	/**
	 * Method used to query catalog expecting only one record returned by calling {@link EvitaSessionContract#queryOne(Query, Class)}.
	 *
	 * @param request          request containing query string form with possible usage of positional or named parameters and their respective collections
	 * @param responseObserver observer on which errors might be thrown and result returned
	 */
	@Override
	public void queryOne(GrpcQueryRequest request, StreamObserver<GrpcQueryOneResponse> responseObserver) {
		executeWithClientContext(session -> {
			final Query query = QueryUtil.parseQuery(
				request.getQuery(),
				request.getPositionalQueryParamsList(),
				request.getNamedQueryParamsMap(),
				responseObserver
			);

			queryOneInternal(responseObserver, session, query);
		});
	}

	/**
	 * Method used to query catalog expecting list of records returned by calling {@link EvitaSessionContract#queryList(Query, Class)}.
	 *
	 * @param request          request containing query string form with possible usage of positional or named parameters and their respective collections
	 * @param responseObserver observer on which errors might be thrown and result returned
	 */
	@Override
	public void queryList(GrpcQueryRequest request, StreamObserver<GrpcQueryListResponse> responseObserver) {
		executeWithClientContext(session -> {
			final Query query = QueryUtil.parseQuery(
				request.getQuery(),
				request.getPositionalQueryParamsList(),
				request.getNamedQueryParamsMap(),
				responseObserver
			);

			queryListInternal(responseObserver, session, query);
		});
	}

	/**
	 * Method used to query catalog calling {@link EvitaSessionContract#query(Query, Class)}.
	 *
	 * @param request          request containing query string form with possible usage of positional or named parameters and their respective collections
	 * @param responseObserver observer on which errors might be thrown and result returned
	 */
	@Override
	public void query(GrpcQueryRequest request, StreamObserver<GrpcQueryResponse> responseObserver) {
		executeWithClientContext(session -> {
			final Query query = QueryUtil.parseQuery(
				request.getQuery(),
				request.getPositionalQueryParamsList(),
				request.getNamedQueryParamsMap(),
				responseObserver
			);

			queryInternal(responseObserver, session, query);
		});
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
		executeWithClientContext(session -> {
			final Query query = QueryUtil.parseQueryUnsafe(
				request.getQuery(),
				responseObserver
			);

			queryOneInternal(responseObserver, session, query);
		});
	}

	/**
	 * Method used to query catalog expecting list of records returned by calling {@link EvitaSessionContract#queryList(Query, Class)}.
	 * This method implements UNSAFE approach where values are embedded directly into the query string.
	 *
	 * @param request          request containing query string form with possible usage of positional or named parameters and their respective collections
	 * @param responseObserver observer on which errors might be thrown and result returned
	 */
	@Override
	public void queryListUnsafe(GrpcQueryUnsafeRequest request, StreamObserver<GrpcQueryListResponse> responseObserver) {
		executeWithClientContext(session -> {
			final Query query = QueryUtil.parseQueryUnsafe(
				request.getQuery(),
				responseObserver
			);

			queryListInternal(responseObserver, session, query);
		});
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
		executeWithClientContext(session -> {
			final Query query = QueryUtil.parseQueryUnsafe(
				request.getQuery(),
				responseObserver
			);

			queryInternal(responseObserver, session, query);
		});
	}

	/**
	 * Method used to get entity by calling {@link EvitaSessionContract#getEntity(String, int, EntityContentRequire...)}.
	 *
	 * @param request          request containing entity type, primary key and string form of {@link EntityContentRequire} constraints
	 * @param responseObserver observer on which errors might be thrown and result returned
	 */
	@Override
	public void getEntity(GrpcEntityRequest request, StreamObserver<GrpcEntityResponse> responseObserver) {
		executeWithClientContext(session -> {
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

			entity = session.getEntity(request.getEntityType(), request.getPrimaryKey(), entityContentRequires);
			final GrpcEntityResponse.Builder evitaEntityResponseBuilder = GrpcEntityResponse.newBuilder();
			entity.ifPresent(it -> evitaEntityResponseBuilder.setEntity(EntityConverter.toGrpcSealedEntity(it)));
			responseObserver.onNext(evitaEntityResponseBuilder.build());
			responseObserver.onCompleted();
		});
	}

	@Override
	public void updateCatalogSchema(GrpcUpdateCatalogSchemaRequest request, StreamObserver<GrpcUpdateCatalogSchemaResponse> responseObserver) {
		executeWithClientContext(session -> {
			final LocalCatalogSchemaMutation[] schemaMutations = request.getSchemaMutationsList()
				.stream()
				.map(DelegatingLocalCatalogSchemaMutationConverter.INSTANCE::convert)
				.toArray(LocalCatalogSchemaMutation[]::new);
			final int newSchemaVersion = session.updateCatalogSchema(schemaMutations);

			final GrpcUpdateCatalogSchemaResponse response = GrpcUpdateCatalogSchemaResponse.newBuilder()
				.setVersion(newSchemaVersion)
				.build();
			responseObserver.onNext(response);
			responseObserver.onCompleted();
		});
	}

	@Override
	public void updateAndFetchCatalogSchema(GrpcUpdateCatalogSchemaRequest request, StreamObserver<GrpcUpdateAndFetchCatalogSchemaResponse> responseObserver) {
		executeWithClientContext(session -> {
			final LocalCatalogSchemaMutation[] schemaMutations = request.getSchemaMutationsList()
				.stream()
				.map(DelegatingLocalCatalogSchemaMutationConverter.INSTANCE::convert)
				.toArray(LocalCatalogSchemaMutation[]::new);
			final SealedCatalogSchema newCatalogSchema = session.updateAndFetchCatalogSchema(schemaMutations);

			final GrpcUpdateAndFetchCatalogSchemaResponse response = GrpcUpdateAndFetchCatalogSchemaResponse.newBuilder()
				.setCatalogSchema(convert(newCatalogSchema, false))
				.build();
			responseObserver.onNext(response);
			responseObserver.onCompleted();
		});
	}

	@Override
	public void defineEntitySchema(GrpcDefineEntitySchemaRequest request, StreamObserver<GrpcDefineEntitySchemaResponse> responseObserver) {
		executeWithClientContext(session -> {
			final EntitySchemaBuilder entitySchemaBuilder = session.defineEntitySchema(request.getEntityType());

			final GrpcDefineEntitySchemaResponse response = GrpcDefineEntitySchemaResponse.newBuilder()
				.setEntitySchema(EntitySchemaConverter.convert(entitySchemaBuilder.toInstance(), false))
				.build();
			responseObserver.onNext(response);
			responseObserver.onCompleted();
		});
	}

	@Override
	public void updateEntitySchema(GrpcUpdateEntitySchemaRequest request, StreamObserver<GrpcUpdateEntitySchemaResponse> responseObserver) {
		executeWithClientContext(session -> {
			final ModifyEntitySchemaMutation schemaMutation = ModifyEntitySchemaMutationConverter.INSTANCE.convert(request.getSchemaMutation());
			final int newSchemaVersion = session.updateEntitySchema(schemaMutation);

			final GrpcUpdateEntitySchemaResponse response = GrpcUpdateEntitySchemaResponse.newBuilder()
				.setVersion(newSchemaVersion)
				.build();
			responseObserver.onNext(response);
			responseObserver.onCompleted();
		});
	}

	@Override
	public void updateAndFetchEntitySchema(GrpcUpdateEntitySchemaRequest request, StreamObserver<GrpcUpdateAndFetchEntitySchemaResponse> responseObserver) {
		executeWithClientContext(session -> {
			final ModifyEntitySchemaMutation schemaMutation = ModifyEntitySchemaMutationConverter.INSTANCE.convert(request.getSchemaMutation());
			final SealedEntitySchema newEntitySchema = session.updateAndFetchEntitySchema(schemaMutation);

			final GrpcUpdateAndFetchEntitySchemaResponse response = GrpcUpdateAndFetchEntitySchemaResponse.newBuilder()
				.setEntitySchema(EntitySchemaConverter.convert(newEntitySchema, false))
				.build();
			responseObserver.onNext(response);
			responseObserver.onCompleted();
		});
	}

	/**
	 * Method used to delete collection by calling {@link EvitaSessionContract#deleteCollection(String)}.
	 *
	 * @param request          request containing name of collection which is to be deleted
	 * @param responseObserver observer on which errors might be thrown and result returned
	 */
	@Override
	public void deleteCollection(GrpcDeleteCollectionRequest request, StreamObserver<GrpcDeleteCollectionResponse> responseObserver) {
		final EvitaInternalSessionContract session = ServerSessionInterceptor.SESSION.get();
		responseObserver.onNext(
			GrpcDeleteCollectionResponse.newBuilder()
				.setDeleted(session.deleteCollection(request.getEntityType()))
				.build()
		);
		responseObserver.onCompleted();
	}

	/**
	 * Method used to rename one collection to a new name.
	 *
	 * @param request          request containing entity type and new - renamed entity type
	 * @param responseObserver observer on which errors might be thrown and result returned
	 * @see EvitaSessionContract#renameCollection(String, String) (String, String)
	 */
	@Override
	public void renameCollection(GrpcRenameCollectionRequest request, StreamObserver<GrpcRenameCollectionResponse> responseObserver) {
		executeWithClientContext(session -> {
			final boolean renamed = session.renameCollection(request.getEntityType(), request.getNewName());

			final GrpcRenameCollectionResponse response = GrpcRenameCollectionResponse.newBuilder()
				.setRenamed(renamed)
				.build();
			responseObserver.onNext(response);
			responseObserver.onCompleted();
		});
	}

	/**
	 * Method used to replace one collection with another.
	 *
	 * @param request          request containing entity type and replaced entity type
	 * @param responseObserver observer on which errors might be thrown and result returned
	 * @see EvitaSessionContract#replaceCollection(String, String)
	 */
	@Override
	public void replaceCollection(GrpcReplaceCollectionRequest request, StreamObserver<GrpcReplaceCollectionResponse> responseObserver) {
		executeWithClientContext(session -> {
			final boolean replaced = session.replaceCollection(request.getEntityTypeToBeReplaced(), request.getEntityTypeToBeReplacedWith());

			final GrpcReplaceCollectionResponse response = GrpcReplaceCollectionResponse.newBuilder()
				.setReplaced(replaced)
				.build();
			responseObserver.onNext(response);
			responseObserver.onCompleted();
		});
	}

	/**
	 * Method used to get size of specified collection by calling {@link EvitaSessionContract#getEntityCollectionSize(String)}.
	 *
	 * @param request          request containing name of collection which size is to be returned
	 * @param responseObserver observer on which errors might be thrown and result returned
	 */
	@Override
	public void getEntityCollectionSize(GrpcEntityCollectionSizeRequest request, StreamObserver<GrpcEntityCollectionSizeResponse> responseObserver) {
		executeWithClientContext(session -> {
			responseObserver.onNext(
				GrpcEntityCollectionSizeResponse.newBuilder()
					.setSize(session.getEntityCollectionSize(request.getEntityType()))
					.build()
			);
			responseObserver.onCompleted();
		});
	}

	/**
	 * Method used to upsert or removing entities by internally calling {@link EvitaSessionContract#upsertEntity(EntityMutation)} with passing a custom collection of mutations processed by {@link DelegatingLocalMutationConverter}.
	 *
	 * @param request          request containing mutation to be performed
	 * @param responseObserver observer on which errors might be thrown and result returned
	 */
	@Override
	public void upsertEntity(GrpcUpsertEntityRequest request, StreamObserver<GrpcUpsertEntityResponse> responseObserver) {
		final EvitaInternalSessionContract session = ServerSessionInterceptor.SESSION.get();
		final GrpcUpsertEntityResponse.Builder builder = GrpcUpsertEntityResponse.newBuilder();
		final EntityMutation entityMutation = DelegatingEntityMutationConverter.INSTANCE.convert(request.getEntityMutation());

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
			final SealedEntity updatedEntity = session.upsertAndFetchEntity(entityMutation, entityContentRequires);
			builder.setEntity(EntityConverter.toGrpcSealedEntity(updatedEntity));
		}
		responseObserver.onNext(builder.build());
		responseObserver.onCompleted();
	}

	/**
	 * Method used to remove single entity by primary key by calling {@link EvitaSessionContract#deleteEntity(String, int)}.
	 *
	 * @param request          request containing entity type and primary key of removed entity
	 * @param responseObserver observer on which errors might be thrown and result returned
	 */
	@Override
	public void deleteEntity(GrpcDeleteEntityRequest request, StreamObserver<GrpcDeleteEntityResponse> responseObserver) {
		executeWithClientContext(session -> {
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
			entity.ifPresent(it -> response.setEntity(EntityConverter.toGrpcSealedEntity(it)));
			responseObserver.onNext(
				response.build()
			);
			responseObserver.onCompleted();
		});
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
		executeWithClientContext(session -> {
			final String entityType = request.getEntityType();
			final int primaryKey = request.getPrimaryKey().getValue();
			final String require = request.getRequire();
			final DeletedHierarchy<SealedEntity> deletedHierarchy;
			final EntityContentRequire[] entityContentRequires = require.isEmpty() ?
				new EntityContentRequire[0] :
				QueryUtil.parseEntityRequiredContents(
					request.getRequire(),
					request.getPositionalQueryParamsList(),
					request.getNamedQueryParamsMap(),
					responseObserver
				);

			if (ArrayUtils.isEmpty(entityContentRequires)) {
				deletedHierarchy = new DeletedHierarchy<>(
					session.deleteEntityAndItsHierarchy(entityType, primaryKey),
					null
				);
			} else {
				deletedHierarchy = session.deleteEntityAndItsHierarchy(entityType, primaryKey, entityContentRequires);
			}

			final GrpcDeleteEntityAndItsHierarchyResponse.Builder response = GrpcDeleteEntityAndItsHierarchyResponse
				.newBuilder()
				.setDeletedEntities(deletedHierarchy.deletedEntities());
			ofNullable(deletedHierarchy.deletedRootEntity())
				.ifPresent(it -> response.setDeletedRootEntity(EntityConverter.toGrpcSealedEntity(it)));
			responseObserver.onNext(
				response.build()
			);
			responseObserver.onCompleted();
		});
	}

	/**
	 * Method used to remove multiple entities by query by calling {@link EvitaSessionContract#deleteEntities(Query)}.
	 *
	 * @param request          request containing the removal query
	 * @param responseObserver observer on which errors might be thrown and result returned
	 */
	@Override
	public void deleteEntities(GrpcDeleteEntitiesRequest request, StreamObserver<GrpcDeleteEntitiesResponse> responseObserver) {
		executeWithClientContext(session -> {
			final Query query = QueryUtil.parseQuery(
				request.getQuery(),
				request.getPositionalQueryParamsList(),
				request.getNamedQueryParamsMap(),
				responseObserver
			);

			if (query != null) {
				final int deletedEntities;
				final SealedEntity[] deletedEntityBodies;
				if (query.getRequire() == null ||
					FinderVisitor.findConstraints(query.getRequire(), EntityFetch.class::isInstance).isEmpty()) {
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
						it -> Arrays.stream(it)
							.map(EntityConverter::toGrpcSealedEntity)
							.forEach(response::addDeletedEntityBodies)
					);
				responseObserver.onNext(
					response.build()
				);
			}
			responseObserver.onCompleted();
		});
	}

	/**
	 * Method returns current transaction id if any is opened.
	 *
	 * @param request          empty request
	 * @param responseObserver observer on which errors might be thrown and result returned
	 */
	@Override
	public void getTransactionId(Empty request, StreamObserver<GrpcTransactionResponse> responseObserver) {
		final GrpcTransactionResponse.Builder builder = GrpcTransactionResponse
			.newBuilder();
		final EvitaInternalSessionContract session = ServerSessionInterceptor.SESSION.get();
		session.getOpenedTransactionId().ifPresent(txId ->
			builder.setTransactionId(toGrpcUuid(txId))
		);
		responseObserver.onNext(
			builder
				.setCatalogVersion(session.getCatalogVersion())
				.build()
		);
		responseObserver.onCompleted();
	}

}
