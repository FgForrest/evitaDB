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

package io.evitadb.externalApi.grpc.services;

import com.google.protobuf.Empty;
import io.evitadb.api.CatalogState;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.EvitaSessionContract.DeletedHierarchy;
import io.evitadb.api.exception.UnexpectedTransactionStateException;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.require.EntityContentRequire;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.query.visitor.FinderVisitor;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.EvitaResponse;
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
import io.evitadb.core.EvitaInternalSessionContract;
import io.evitadb.dataType.DataChunk;
import io.evitadb.dataType.PaginatedList;
import io.evitadb.dataType.StripList;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.externalApi.grpc.builders.query.extraResults.GrpcExtraResultsBuilder;
import io.evitadb.externalApi.grpc.generated.*;
import io.evitadb.externalApi.grpc.generated.GrpcEntitySchemaResponse.Builder;
import io.evitadb.externalApi.grpc.requestResponse.data.EntityConverter;
import io.evitadb.externalApi.grpc.requestResponse.data.mutation.DelegatingEntityMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.data.mutation.DelegatingLocalMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.data.mutation.EntityMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.EntitySchemaConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.DelegatingLocalCatalogSchemaMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.SchemaMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutationConverter;
import io.evitadb.externalApi.grpc.services.interceptors.ServerSessionInterceptor;
import io.evitadb.externalApi.grpc.utils.QueryUtil;
import io.evitadb.utils.ArrayUtils;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter.toGrpcCatalogState;
import static io.evitadb.externalApi.grpc.requestResponse.schema.CatalogSchemaConverter.convert;
import static java.util.Optional.empty;
import static java.util.Optional.ofNullable;

/**
 * This service contains methods that could be called by gRPC clients on {@link GrpcEvitaSession}
 *
 * @author Tomáš Pozler, 2022
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@RequiredArgsConstructor
public class EvitaSessionService extends EvitaSessionServiceGrpc.EvitaSessionServiceImplBase {

	private static final SchemaMutationConverter<LocalCatalogSchemaMutation, GrpcLocalCatalogSchemaMutation> CATALOG_SCHEMA_MUTATION_CONVERTER =
		new DelegatingLocalCatalogSchemaMutationConverter();
	private static final SchemaMutationConverter<ModifyEntitySchemaMutation, GrpcModifyEntitySchemaMutation> ENTITY_SCHEMA_MUTATION_CONVERTER =
		new ModifyEntitySchemaMutationConverter();
	private static final EntityMutationConverter<EntityMutation, GrpcEntityMutation> ENTITY_MUTATION_CONVERTER =
		new DelegatingEntityMutationConverter();

	/**
	 * Produces the {@link CatalogSchema}.
	 */
	@Override
	public void getCatalogSchema(Empty request, StreamObserver<GrpcCatalogSchemaResponse> responseObserver) {
		final EvitaSessionContract session = ServerSessionInterceptor.SESSION.get();

		final SealedCatalogSchema catalogSchema = session.getCatalogSchema();
		responseObserver.onNext(
			GrpcCatalogSchemaResponse.newBuilder()
				.setCatalogSchema(convert(catalogSchema))
				.build()
		);
		responseObserver.onCompleted();
	}

	/**
	 * Returns the current state of the catalog.
	 */
	@Override
	public void getCatalogState(Empty request, StreamObserver<GrpcCatalogStateResponse> responseObserver) {
		final EvitaSessionContract session = ServerSessionInterceptor.SESSION.get();
		final CatalogState catalogState = session.getCatalogState();
		responseObserver.onNext(
			GrpcCatalogStateResponse.newBuilder()
				.setState(toGrpcCatalogState(catalogState))
				.build()
		);
		responseObserver.onCompleted();
	}

	/**
	 * Produces the {@link EntitySchema} for {@link GrpcEntitySchemaRequest#getEntityType()}.
	 */
	@Override
	public void getEntitySchema(GrpcEntitySchemaRequest request, StreamObserver<GrpcEntitySchemaResponse> responseObserver) {
		final EvitaSessionContract session = ServerSessionInterceptor.SESSION.get();

		final Builder responseBuilder = GrpcEntitySchemaResponse.newBuilder();
		session.getEntitySchema(request.getEntityType())
			.ifPresent(it -> responseBuilder.setEntitySchema(EntitySchemaConverter.convert(it)));
		responseObserver.onNext(
			responseBuilder.build()
		);
		responseObserver.onCompleted();
	}

	/**
	 * Method used to get set of all stored entity types by calling {@link EvitaSessionContract#getAllEntityTypes()}.
	 *
	 * @param request          empty request
	 * @param responseObserver observer on which errors might be thrown and result returned
	 */
	@Override
	public void getAllEntityTypes(@Nullable Empty request, @Nonnull StreamObserver<GrpcEntityTypesResponse> responseObserver) {
		final EvitaSessionContract session = ServerSessionInterceptor.SESSION.get();

		responseObserver.onNext(
			GrpcEntityTypesResponse.newBuilder()
				.addAllEntityTypes(session.getAllEntityTypes())
				.build()
		);

		responseObserver.onCompleted();
	}

	/**
	 * Method used to get size of specified collection by calling {@link EvitaSessionContract#getEntityCollectionSize(String)}.
	 *
	 * @param request          request containing name of collection which size is to be returned
	 * @param responseObserver observer on which errors might be thrown and result returned
	 */
	@Override
	public void getEntityCollectionSize(@Nonnull GrpcEntityCollectionSizeRequest request, @Nonnull StreamObserver<GrpcEntityCollectionSizeResponse> responseObserver) {
		final EvitaSessionContract session = ServerSessionInterceptor.SESSION.get();

		responseObserver.onNext(
			GrpcEntityCollectionSizeResponse.newBuilder()
				.setSize(session.getEntityCollectionSize(request.getEntityType()))
				.build()
		);

		responseObserver.onCompleted();
	}

	/**
	 * Method used to delete collection by calling {@link EvitaSessionContract#deleteCollection(String)}.
	 *
	 * @param request          request containing name of collection which is to be deleted
	 * @param responseObserver observer on which errors might be thrown and result returned
	 */
	@Override
	public void deleteCollection(@Nonnull GrpcDeleteCollectionRequest request, @Nonnull StreamObserver<GrpcDeleteCollectionResponse> responseObserver) {
		final EvitaSessionContract session = ServerSessionInterceptor.SESSION.get();

		handleTransactionCall(session, s -> responseObserver.onNext(
			GrpcDeleteCollectionResponse.newBuilder()
				.setDeleted(s.deleteCollection(request.getEntityType()))
				.build()
		));
		responseObserver.onCompleted();
	}

	/**
	 * Method used to switch catalog state to {@link CatalogState#ALIVE} and close currently used session by calling {@link EvitaSessionContract#goLiveAndClose()}.
	 *
	 * @param request          empty request
	 * @param responseObserver observer on which errors might be thrown and result returned
	 */
	@Override
	public void goLiveAndClose(@Nonnull Empty request, @Nonnull StreamObserver<GrpcGoLiveAndCloseResponse> responseObserver) {
		final EvitaSessionContract session = ServerSessionInterceptor.SESSION.get();

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
	}

	/**
	 * Method used to get entity by calling {@link EvitaSessionContract#getEntity(String, int, EntityContentRequire...)}.
	 *
	 * @param request          request containing entity type, primary key and string form of {@link EntityContentRequire} constraints
	 * @param responseObserver observer on which errors might be thrown and result returned
	 */
	@Override
	public void getEntity(@Nonnull GrpcEntityRequest request, @Nonnull StreamObserver<GrpcEntityResponse> responseObserver) {
		final EvitaSessionContract session = ServerSessionInterceptor.SESSION.get();

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
	}

	@Override
	public void updateCatalogSchema(@Nonnull GrpcUpdateCatalogSchemaRequest request, @Nonnull StreamObserver<GrpcUpdateCatalogSchemaResponse> responseObserver) {
		final EvitaSessionContract session = ServerSessionInterceptor.SESSION.get();

		final LocalCatalogSchemaMutation[] schemaMutations = request.getSchemaMutationsList()
			.stream()
			.map(CATALOG_SCHEMA_MUTATION_CONVERTER::convert)
			.toArray(LocalCatalogSchemaMutation[]::new);
		final int newSchemaVersion = session.updateCatalogSchema(schemaMutations);

		final GrpcUpdateCatalogSchemaResponse response = GrpcUpdateCatalogSchemaResponse.newBuilder()
			.setVersion(newSchemaVersion)
			.build();
		responseObserver.onNext(response);
		responseObserver.onCompleted();
	}

	@Override
	public void updateAndFetchCatalogSchema(@Nonnull GrpcUpdateCatalogSchemaRequest request, @Nonnull StreamObserver<GrpcUpdateAndFetchCatalogSchemaResponse> responseObserver) {
		final EvitaSessionContract session = ServerSessionInterceptor.SESSION.get();

		final LocalCatalogSchemaMutation[] schemaMutations = request.getSchemaMutationsList()
			.stream()
			.map(CATALOG_SCHEMA_MUTATION_CONVERTER::convert)
			.toArray(LocalCatalogSchemaMutation[]::new);
		final SealedCatalogSchema newCatalogSchema = session.updateAndFetchCatalogSchema(schemaMutations);

		final GrpcUpdateAndFetchCatalogSchemaResponse response = GrpcUpdateAndFetchCatalogSchemaResponse.newBuilder()
			.setCatalogSchema(convert(newCatalogSchema))
			.build();
		responseObserver.onNext(response);
		responseObserver.onCompleted();
	}

	@Override
	public void defineEntitySchema(@Nonnull GrpcDefineEntitySchemaRequest request, @Nonnull StreamObserver<GrpcDefineEntitySchemaResponse> responseObserver) {
		final EvitaSessionContract session = ServerSessionInterceptor.SESSION.get();

		final EntitySchemaBuilder entitySchemaBuilder = session.defineEntitySchema(request.getEntityType());

		final GrpcDefineEntitySchemaResponse response = GrpcDefineEntitySchemaResponse.newBuilder()
			.setEntitySchema(EntitySchemaConverter.convert(entitySchemaBuilder.toInstance()))
			.build();
		responseObserver.onNext(response);
		responseObserver.onCompleted();
	}

	@Override
	public void updateEntitySchema(@Nonnull GrpcUpdateEntitySchemaRequest request, @Nonnull StreamObserver<GrpcUpdateEntitySchemaResponse> responseObserver) {
		final EvitaSessionContract session = ServerSessionInterceptor.SESSION.get();

		final ModifyEntitySchemaMutation schemaMutation = ENTITY_SCHEMA_MUTATION_CONVERTER.convert(request.getSchemaMutation());
		final int newSchemaVersion = session.updateEntitySchema(schemaMutation);

		final GrpcUpdateEntitySchemaResponse response = GrpcUpdateEntitySchemaResponse.newBuilder()
			.setVersion(newSchemaVersion)
			.build();
		responseObserver.onNext(response);
		responseObserver.onCompleted();
	}

	@Override
	public void updateAndFetchEntitySchema(@Nonnull GrpcUpdateEntitySchemaRequest request, @Nonnull StreamObserver<GrpcUpdateAndFetchEntitySchemaResponse> responseObserver) {
		final EvitaSessionContract session = ServerSessionInterceptor.SESSION.get();

		final ModifyEntitySchemaMutation schemaMutation = ENTITY_SCHEMA_MUTATION_CONVERTER.convert(request.getSchemaMutation());
		final SealedEntitySchema newEntitySchema = session.updateAndFetchEntitySchema(schemaMutation);

		final GrpcUpdateAndFetchEntitySchemaResponse response = GrpcUpdateAndFetchEntitySchemaResponse.newBuilder()
			.setEntitySchema(EntitySchemaConverter.convert(newEntitySchema))
			.build();
		responseObserver.onNext(response);
		responseObserver.onCompleted();
	}

	/**
	 * Method used to upsert or removing entities by internally calling {@link EvitaSessionContract#upsertEntity(EntityMutation)} with passing a custom collection of mutations processed by {@link DelegatingLocalMutationConverter}.
	 *
	 * @param request          request containing mutation to be performed
	 * @param responseObserver observer on which errors might be thrown and result returned
	 */
	@Override
	public void upsertEntity(GrpcUpsertEntityRequest request, StreamObserver<GrpcUpsertEntityResponse> responseObserver) {
		final EvitaSessionContract session = ServerSessionInterceptor.SESSION.get();
		final GrpcUpsertEntityResponse.Builder builder = GrpcUpsertEntityResponse.newBuilder();
		handleTransactionCall(session, s -> {
			final EntityMutation entityMutation = ENTITY_MUTATION_CONVERTER.convert(request.getEntityMutation());

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
		});

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
	public void deleteEntity(@Nonnull GrpcDeleteEntityRequest request, @Nonnull StreamObserver<GrpcDeleteEntityResponse> responseObserver) {
		final EvitaInternalSessionContract session = ServerSessionInterceptor.SESSION.get();
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
	}

	/**
	 * Method used to rename one collection to a new name.
	 *
	 * @see EvitaSessionContract#renameCollection(String, String) (String, String)
	 * @param request          request containing entity type and new - renamed entity type
	 * @param responseObserver observer on which errors might be thrown and result returned
	 */
	@Override
	public void renameCollection(@Nonnull GrpcRenameCollectionRequest request, @Nonnull StreamObserver<GrpcRenameCollectionResponse> responseObserver) {
		final EvitaInternalSessionContract session = ServerSessionInterceptor.SESSION.get();
		final boolean renamed = session.renameCollection(request.getEntityType(), request.getNewName());

		final GrpcRenameCollectionResponse response = GrpcRenameCollectionResponse.newBuilder()
			.setRenamed(renamed)
			.build();
		responseObserver.onNext(response);
		responseObserver.onCompleted();
	}

	/**
	 * Method used to replace one collection with another.
	 *
	 * @see EvitaSessionContract#replaceCollection(String, String)
	 * @param request          request containing entity type and replaced entity type
	 * @param responseObserver observer on which errors might be thrown and result returned
	 */
	@Override
	public void replaceCollection(GrpcReplaceCollectionRequest request, StreamObserver<GrpcReplaceCollectionResponse> responseObserver) {
		final EvitaInternalSessionContract session = ServerSessionInterceptor.SESSION.get();
		final boolean replaced = session.replaceCollection(request.getEntityTypeToBeReplaced(), request.getEntityTypeToBeReplacedWith());

		final GrpcReplaceCollectionResponse response = GrpcReplaceCollectionResponse.newBuilder()
			.setReplaced(replaced)
			.build();
		responseObserver.onNext(response);
		responseObserver.onCompleted();
	}

	/**
	 * Method used to remove single entity along with its nested hierarchy tree by primary key by calling
	 * {@link EvitaSessionContract#deleteEntityAndItsHierarchy(String, Integer)}.
	 *
	 * @param request          request containing entity type and primary key of removed entity
	 * @param responseObserver observer on which errors might be thrown and result returned
	 */
	@Override
	public void deleteEntityAndItsHierarchy(@Nonnull GrpcDeleteEntityRequest request, @Nonnull StreamObserver<GrpcDeleteEntityAndItsHierarchyResponse> responseObserver) {
		final EvitaInternalSessionContract session = ServerSessionInterceptor.SESSION.get();
		final String entityType = request.getEntityType();
		final int primaryKey = request.getPrimaryKey().getValue();
		final String require = request.getRequire();
		final DeletedHierarchy deletedHierarchy;
		final EntityContentRequire[] entityContentRequires = require.isEmpty() ?
			new EntityContentRequire[0] :
			QueryUtil.parseEntityRequiredContents(
				request.getRequire(),
				request.getPositionalQueryParamsList(),
				request.getNamedQueryParamsMap(),
				responseObserver
			);

		if (ArrayUtils.isEmpty(entityContentRequires)) {
			deletedHierarchy = new DeletedHierarchy(
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
	}

	/**
	 * Method used to remove multiple entities by query by calling {@link EvitaSessionContract#deleteEntities(Query)}.
	 *
	 * @param request          request containing the removal query
	 * @param responseObserver observer on which errors might be thrown and result returned
	 */
	@Override
	public void deleteEntities(@Nonnull GrpcDeleteEntitiesRequest request, @Nonnull StreamObserver<GrpcDeleteEntitiesResponse> responseObserver) {
		final EvitaInternalSessionContract session = ServerSessionInterceptor.SESSION.get();

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
				deletedEntityBodies = session.deleteEntitiesAndReturnBodies(query);
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
	}

	/**
	 * Method used to query catalog calling {@link EvitaSessionContract#query(Query, Class)}.
	 *
	 * @param request          request containing query string form with possible usage of positional or named parameters and their respective collections
	 * @param responseObserver observer on which errors might be thrown and result returned
	 */
	@Override
	public void query(@Nonnull GrpcQueryRequest request, @Nonnull StreamObserver<GrpcQueryResponse> responseObserver) {
		final EvitaInternalSessionContract session = ServerSessionInterceptor.SESSION.get();

		final Query query = QueryUtil.parseQuery(
			request.getQuery(),
			request.getPositionalQueryParamsList(),
			request.getNamedQueryParamsMap(),
			responseObserver
		);

		if (query != null) {
			final EvitaRequest evitaRequest = new EvitaRequest(
				query.normalizeQuery(),
				OffsetDateTime.now()
			);

			final EvitaResponse<EntityClassifier> evitaResponse = session.query(evitaRequest, EntityClassifier.class);
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
	 * Method used to query catalog expecting only one record returned by calling {@link EvitaSessionContract#queryOne(Query, Class)}.
	 *
	 * @param request          request containing query string form with possible usage of positional or named parameters and their respective collections
	 * @param responseObserver observer on which errors might be thrown and result returned
	 */
	@Override
	public void queryOne(@Nonnull GrpcQueryRequest request, @Nonnull StreamObserver<GrpcQueryOneResponse> responseObserver) {
		final EvitaSessionContract session = ServerSessionInterceptor.SESSION.get();

		final Query query = QueryUtil.parseQuery(
			request.getQuery(),
			request.getPositionalQueryParamsList(),
			request.getNamedQueryParamsMap(),
			responseObserver
		);

		if (query != null) {
			final GrpcQueryOneResponse.Builder responseBuilder = GrpcQueryOneResponse.newBuilder();
			session.queryOne(query, EntityClassifier.class).ifPresent(responseEntity -> {
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
					throw new EvitaInternalError("Unsupported entity class `" + responseEntity.getClass().getName() + "`.");
				}
			});
			responseObserver.onNext(responseBuilder.build());
		}
		responseObserver.onCompleted();
	}

	/**
	 * Method used to query catalog expecting list of records returned by calling {@link EvitaSessionContract#queryList(Query, Class)}.
	 *
	 * @param request          request containing query string form with possible usage of positional or named parameters and their respective collections
	 * @param responseObserver observer on which errors might be thrown and result returned
	 */
	@Override
	public void queryList(@Nonnull GrpcQueryRequest request, @Nonnull StreamObserver<GrpcQueryListResponse> responseObserver) {
		final EvitaInternalSessionContract session = ServerSessionInterceptor.SESSION.get();

		final Query query = QueryUtil.parseQuery(
			request.getQuery(),
			request.getPositionalQueryParamsList(),
			request.getNamedQueryParamsMap(),
			responseObserver
		);

		if (query != null) {
			final EvitaRequest evitaRequest = new EvitaRequest(query, OffsetDateTime.now());
			final List<EntityClassifier> responseEntities = session.queryList(evitaRequest, EntityClassifier.class);
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
	 * Method used to close currently used session by calling {@link EvitaSessionContract#close()} ()}.
	 *
	 * @param request          empty request
	 * @param responseObserver observer on which errors might be thrown and result returned
	 */
	@Override
	public void close(@Nonnull Empty request, @Nonnull StreamObserver<Empty> responseObserver) {
		final EvitaSessionContract session = ServerSessionInterceptor.SESSION.get();
		if (session != null) {
			session.close();
		}
		responseObserver.onNext(Empty.getDefaultInstance());
		responseObserver.onCompleted();
	}

	/**
	 * Opens a transaction on the current session. Each changes performed since will be visible only for the current session until {@link #closeTransaction(GrpcCloseTransactionRequest, StreamObserver)}  is called.
	 *
	 * @param request          empty request
	 * @param responseObserver observer on which errors might be thrown and result returned
	 * @see EvitaSessionContract#openTransaction()
	 */
	@Override
	public void openTransaction(@Nonnull Empty request, @Nonnull StreamObserver<GrpcOpenTransactionResponse> responseObserver) {
		final EvitaSessionContract session = ServerSessionInterceptor.SESSION.get();
		final long txId = session.openTransaction();
		responseObserver.onNext(
			GrpcOpenTransactionResponse
				.newBuilder()
				.setAlreadyOpenedBefore(false)
				.setTransactionId(txId)
				.build()
		);
		responseObserver.onCompleted();
	}

	/**
	 * Closes a transaction on the current session. Each changes performed since will be written to the database and changes made will be visible to the sessions created after this call.
	 *
	 * @param request          empty request
	 * @param responseObserver observer on which errors might be thrown and result returned
	 * @see EvitaSessionContract#closeTransaction()
	 */
	@Override
	public void closeTransaction(GrpcCloseTransactionRequest request, StreamObserver<Empty> responseObserver) {
		final EvitaSessionContract session = ServerSessionInterceptor.SESSION.get();
		if (request.getRollback()) {
			session.setRollbackOnly();
		}
		session.closeTransaction();
		responseObserver.onNext(Empty.getDefaultInstance());
		responseObserver.onCompleted();
	}

	/**
	 * Logic sent to this method could be executed in two ways. It can be executed right away if transaction has already benn opened, changes would be written
	 * to the database only after manual call of {@link #closeTransaction(GrpcCloseTransactionRequest, StreamObserver)} . The second way executes {@link EvitaSessionContract#execute(Consumer)} which will wrap the call
	 * in adhoc created transaction - the changes would be written to the database immediately.
	 *
	 * @param session used to perform specified logic in lambda
	 * @param logic   logic to be performed
	 */
	private void handleTransactionCall(@Nonnull EvitaSessionContract session, @Nonnull Consumer<EvitaSessionContract> logic) {
		try {
			session.execute(logic);
		} catch (UnexpectedTransactionStateException ex) {
			logic.accept(session);
		}
	}
}
