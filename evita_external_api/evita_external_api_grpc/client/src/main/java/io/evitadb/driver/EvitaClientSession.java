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
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.driver;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.protobuf.Empty;
import com.google.protobuf.Int32Value;
import io.evitadb.api.CatalogState;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.SchemaPostProcessor;
import io.evitadb.api.SchemaPostProcessorCapturingResult;
import io.evitadb.api.SessionTraits;
import io.evitadb.api.TransactionContract.CommitBehavior;
import io.evitadb.api.exception.*;
import io.evitadb.api.proxy.ProxyFactory;
import io.evitadb.api.proxy.SealedEntityProxy;
import io.evitadb.api.proxy.SealedEntityReferenceProxy;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.require.EntityContentRequire;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.query.require.SeparateEntityContentRequireContainer;
import io.evitadb.api.query.visitor.FinderVisitor;
import io.evitadb.api.query.visitor.PrettyPrintingVisitor;
import io.evitadb.api.query.visitor.PrettyPrintingVisitor.StringWithParameters;
import io.evitadb.api.requestResponse.EvitaEntityReferenceResponse;
import io.evitadb.api.requestResponse.EvitaEntityResponse;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.EvitaResponseExtraResult;
import io.evitadb.api.requestResponse.data.DeletedHierarchy;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.InstanceEditor;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.data.structure.InitialEntityBuilder;
import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.CatalogSchemaEditor;
import io.evitadb.api.requestResponse.schema.ClassSchemaAnalyzer;
import io.evitadb.api.requestResponse.schema.ClassSchemaAnalyzer.AnalysisResult;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaDecorator;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.SealedCatalogSchema;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchemaProvider;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.dataType.DataChunk;
import io.evitadb.driver.config.EvitaClientConfiguration;
import io.evitadb.driver.exception.EvitaClientServerCallException;
import io.evitadb.driver.exception.EvitaClientTimedOutException;
import io.evitadb.driver.interceptor.ClientSessionInterceptor.SessionIdHolder;
import io.evitadb.driver.pooling.ChannelPool;
import io.evitadb.driver.requestResponse.schema.ClientCatalogSchemaDecorator;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.externalApi.grpc.generated.*;
import io.evitadb.externalApi.grpc.generated.EvitaSessionServiceGrpc.EvitaSessionServiceFutureStub;
import io.evitadb.externalApi.grpc.generated.EvitaSessionServiceGrpc.EvitaSessionServiceStub;
import io.evitadb.externalApi.grpc.query.QueryConverter;
import io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter;
import io.evitadb.externalApi.grpc.requestResponse.ResponseConverter;
import io.evitadb.externalApi.grpc.requestResponse.data.EntityConverter;
import io.evitadb.externalApi.grpc.requestResponse.data.mutation.DelegatingEntityMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.data.mutation.EntityMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.CatalogSchemaConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.EntitySchemaConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.DelegatingLocalCatalogSchemaMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.SchemaMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutationConverter;
import io.evitadb.utils.Assert;
import io.evitadb.utils.ReflectionLookup;
import io.grpc.ManagedChannel;
import io.grpc.Status.Code;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.entityFetch;
import static io.evitadb.api.query.QueryConstraints.require;
import static io.evitadb.api.requestResponse.schema.ClassSchemaAnalyzer.extractEntityTypeFromClass;
import static io.evitadb.driver.EvitaClient.ERROR_MESSAGE_PATTERN;
import static io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter.toUuid;
import static java.util.Optional.empty;
import static java.util.Optional.of;
import static java.util.Optional.ofNullable;

/**
 * The EvitaClientSession implements {@link EvitaSessionContract} interface and aims to behave identically as if the
 * evitaDB is used as an embedded engine. The EvitaClientSession is not thread-safe. It keeps a gRPC session opened,
 * but it doesn't mean that the session on the server side is still alive. Server can close the session due to
 * the timeout and the client will realize this on the next server call attempt.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 * @see EvitaSessionContract
 */
@Slf4j
@NotThreadSafe
@EqualsAndHashCode(of = "sessionId")
@ToString(of = "sessionId")
public class EvitaClientSession implements EvitaSessionContract {

	private static final SchemaMutationConverter<LocalCatalogSchemaMutation, GrpcLocalCatalogSchemaMutation> CATALOG_SCHEMA_MUTATION_CONVERTER =
		new DelegatingLocalCatalogSchemaMutationConverter();
	private static final SchemaMutationConverter<ModifyEntitySchemaMutation, GrpcModifyEntitySchemaMutation> MODIFY_ENTITY_SCHEMA_MUTATION_CONVERTER =
		new ModifyEntitySchemaMutationConverter();
	private static final EntityMutationConverter<EntityMutation, GrpcEntityMutation> ENTITY_MUTATION_CONVERTER =
		new DelegatingEntityMutationConverter();

	/**
	 * Evita instance this session is connected to.
	 */
	@Getter private final EvitaClient evita;
	/**
	 * Reflection lookup is used to speed up reflection operation by memoizing the results for examined classes.
	 */
	private final ReflectionLookup reflectionLookup;
	/**
	 * Reference to the {@link EvitaEntitySchemaCache} that keeps the deserialized schemas on the client side so that
	 * we avoid frequent re-fetching from the server side. See {@link EvitaEntitySchemaCache} for more details.
	 */
	private final EvitaEntitySchemaCache schemaCache;
	/**
	 * Contains reference to the channel pool that is used for retrieving a channel and applying wanted login onto it.
	 */
	private final ChannelPool channelPool;
	/**
	 * Contains reference to the catalog name targeted by queries / mutations from this session.
	 */
	private final String catalogName;
	/**
	 * The cached state of the remote catalog.
	 */
	private final CatalogState catalogState;
	/**
	 * Contains unique identification of this particular Evita session. The id is not currently used, but may be
	 * propagated into the logs in the future.
	 */
	private final UUID sessionId;
	/**
	 * Contains information passed at the time session was created that defines its behaviour
	 */
	private final SessionTraits sessionTraits;
	/**
	 * Contains commit behaviour for this transaction.
	 *
	 * @see CommitBehavior
	 */
	@Getter private final CommitBehavior commitBehaviour;
	/**
	 * Callback that will be called when session is closed.
	 */
	private final Consumer<EvitaClientSession> onTerminationCallback;
	/**
	 * Reference, that allows to access transaction object.
	 */
	private final AtomicReference<EvitaClientTransaction> transactionAccessor = new AtomicReference<>();
	/**
	 * Contains reference to the proxy factory that is used to create proxies for the entities.
	 */
	@Getter private final ProxyFactory proxyFactory;
	/**
	 * Accessor for the client entity schema.
	 */
	private final ClientEntitySchemaAccessor clientEntitySchemaAccessor = new ClientEntitySchemaAccessor();
	/**
	 * Future that is instantiated when the session is closed. When initialized, subsequent calls of the close method
	 * will return the same future. When the future is non-null any calls after {@link #close()} method has been called.
	 */
	private CompletableFuture<Long> closedFuture;
	/**
	 * Timestamp of the last session activity (call).
	 */
	private long lastCall;
	/**
	 * Current timeout for the session.
	 */
	private final LinkedList<Timeout> callTimeout = new LinkedList<>();

	private static <S extends Serializable> Query assertRequestMakesSenseAndEntityTypeIsPresent(@Nonnull Query query, @Nonnull Class<S> expectedType, @Nonnull ReflectionLookup reflectionLookup) {
		if (EntityContract.class.isAssignableFrom(expectedType) &&
			(query.getRequire() == null ||
				FinderVisitor.findConstraints(query.getRequire(), EntityFetch.class::isInstance, SeparateEntityContentRequireContainer.class::isInstance).isEmpty())) {
			throw new EvitaInvalidUsageException(
				"Method call expects `" + expectedType + "` in result, yet it doesn't define `entityFetch` " +
					"in the requirements. This would imply that only entity references " +
					"will be returned by the server!"
			);
		}
		if (query.getCollection() == null) {
			return extractEntityTypeFromClass(expectedType, reflectionLookup)
				.or(() -> ofNullable(query.getCollection()).map(io.evitadb.api.query.head.Collection::getEntityType))
				.map(
					entityType -> Query.query(
						collection(entityType),
						query.getFilterBy(),
						query.getOrderBy(),
						query.getRequire()
					).normalizeQuery()
				)
				.orElseGet(query::normalizeQuery);
		} else {
			return query.normalizeQuery();
		}
	}

	public EvitaClientSession(
		@Nonnull EvitaClient evita,
		@Nonnull EvitaEntitySchemaCache schemaCache,
		@Nonnull ChannelPool channelPool,
		@Nonnull String catalogName,
		@Nonnull CatalogState catalogState,
		@Nonnull UUID sessionId,
		@Nonnull CommitBehavior commitBehaviour,
		@Nonnull SessionTraits sessionTraits,
		@Nonnull Consumer<EvitaClientSession> onTerminationCallback,
		@Nonnull Timeout timeout
		) {
		this.evita = evita;
		this.reflectionLookup = evita.getReflectionLookup();
		this.proxyFactory = schemaCache.getProxyFactory();
		this.schemaCache = schemaCache;
		this.channelPool = channelPool;
		this.catalogName = catalogName;
		this.catalogState = catalogState;
		this.commitBehaviour = commitBehaviour;
		this.sessionId = sessionId;
		this.sessionTraits = sessionTraits;
		this.onTerminationCallback = onTerminationCallback;
		this.callTimeout.add(timeout);
	}

	@Nonnull
	@Override
	public UUID getId() {
		return sessionId;
	}

	@Nonnull
	@Override
	public SealedCatalogSchema getCatalogSchema() {
		assertActive();
		return schemaCache.getLatestCatalogSchema(
			this::fetchCatalogSchema,
			clientEntitySchemaAccessor
		);
	}

	@Nonnull
	@Override
	public String getCatalogName() {
		return catalogName;
	}

	@Nonnull
	@Override
	public CatalogState getCatalogState() {
		return catalogState;
	}

	@Override
	public long getCatalogVersion() {
		assertActive();
		return schemaCache.getLastKnownCatalogVersion();
	}

	@Override
	public boolean isActive() {
		return closedFuture == null;
	}

	@Override
	public boolean goLiveAndClose() {
		assertActive();
		final GrpcGoLiveAndCloseResponse grpcResponse = executeWithBlockingEvitaSessionService(evitaSessionService ->
			evitaSessionService.goLiveAndClose(Empty.newBuilder().build())
		);
		final boolean success = grpcResponse.getSuccess();
		if (success) {
			closeInternally().complete(grpcResponse.getCatalogVersion());
		}
		return success;
	}

	@Nonnull
	@Override
	public CompletableFuture<Long> closeNow(@Nonnull CommitBehavior commitBehaviour) {
		if (isActive()) {
			final CompletableFuture<Long> result = closeInternally();
			executeWithAsyncEvitaSessionService(
				evitaSessionService -> {
					final StreamObserver<GrpcCloseResponse> observer = new StreamObserver<>() {
						@Override
						public void onNext(GrpcCloseResponse grpcCloseResponse) {
							result.complete(grpcCloseResponse.getCatalogVersion());
							schemaCache.updateLastKnownCatalogVersion(grpcCloseResponse.getCatalogVersion());
						}

						@Override
						public void onError(Throwable throwable) {
							result.completeExceptionally(throwable);
						}

						@Override
						public void onCompleted() {

						}
					};
					evitaSessionService.close(
						GrpcCloseRequest.newBuilder()
							.setCommitBehaviour(EvitaEnumConverter.toGrpcCommitBehavior(commitBehaviour))
							.build(),
						observer
					);
					return null;
				}
			);
		}
		return this.closedFuture;
	}

	@Nonnull
	@Override
	public EntitySchemaBuilder defineEntitySchema(@Nonnull String entityType) {
		assertActive();
		final SealedEntitySchema newEntitySchema = executeInTransactionIfPossible(session -> {
			final GrpcDefineEntitySchemaRequest request = GrpcDefineEntitySchemaRequest.newBuilder()
				.setEntityType(entityType)
				.build();

			final GrpcDefineEntitySchemaResponse response = executeWithBlockingEvitaSessionService(evitaSessionService ->
				evitaSessionService.defineEntitySchema(request)
			);

			final EntitySchema theSchema = EntitySchemaConverter.convert(response.getEntitySchema());
			schemaCache.setLatestEntitySchema(theSchema);
			return new EntitySchemaDecorator(this::getCatalogSchema, theSchema);
		});
		return newEntitySchema.openForWrite();
	}

	@Nonnull
	@Override
	public SealedEntitySchema defineEntitySchemaFromModelClass(@Nonnull Class<?> modelClass) {
		assertActive();
		return executeInTransactionIfPossible(
			session -> {
				final ClassSchemaAnalyzer classSchemaAnalyzer = new ClassSchemaAnalyzer(modelClass, reflectionLookup);
				final CatalogSchemaEditor.CatalogSchemaBuilder catalogBuilder = session.getCatalogSchema().openForWrite();
				final AnalysisResult analysisResult = classSchemaAnalyzer.analyze(this, catalogBuilder);
				updateCatalogSchema(analysisResult.mutations());
				return getEntitySchemaOrThrow(analysisResult.entityType());
			}
		);
	}

	@Nonnull
	@Override
	public SealedEntitySchema defineEntitySchemaFromModelClass(@Nonnull Class<?> modelClass, @Nonnull SchemaPostProcessor postProcessor) {
		assertActive();
		return executeInTransactionIfPossible(
			session -> {
				final ClassSchemaAnalyzer classSchemaAnalyzer = new ClassSchemaAnalyzer(modelClass, reflectionLookup, postProcessor);
				final CatalogSchemaEditor.CatalogSchemaBuilder catalogBuilder = session.getCatalogSchema().openForWrite();
				final AnalysisResult analysisResult = classSchemaAnalyzer.analyze(this, catalogBuilder);
				if (postProcessor instanceof SchemaPostProcessorCapturingResult capturingResult) {
					capturingResult.captureResult(analysisResult.mutations());
				}
				updateCatalogSchema(analysisResult.mutations());
				return getEntitySchemaOrThrow(analysisResult.entityType());
			}
		);
	}

	@Nonnull
	@Override
	public Optional<SealedEntitySchema> getEntitySchema(@Nonnull String entityType) {
		assertActive();
		return schemaCache.getLatestEntitySchema(entityType, this::fetchEntitySchema, this::getCatalogSchema);
	}

	@Nonnull
	@Override
	public Optional<SealedEntitySchema> getEntitySchema(@Nonnull Class<?> modelClass) throws EntityClassInvalidException {
		return getEntitySchema(
			extractEntityTypeFromClass(modelClass, reflectionLookup)
				.orElseThrow(() -> new CollectionNotFoundException(modelClass))
		);
	}

	@Nonnull
	@Override
	public SealedEntitySchema getEntitySchemaOrThrow(@Nonnull String entityType) throws CollectionNotFoundException {
		assertActive();
		return getEntitySchema(entityType)
			.orElseThrow(() -> new CollectionNotFoundException(entityType));
	}

	@Nonnull
	@Override
	public SealedEntitySchema getEntitySchemaOrThrow(@Nonnull Class<?> modelClass) throws CollectionNotFoundException, EntityClassInvalidException {
		return getEntitySchemaOrThrow(
			extractEntityTypeFromClass(modelClass, reflectionLookup)
				.orElseThrow(() -> new CollectionNotFoundException(modelClass))
		);
	}

	@Nonnull
	@Override
	public Set<String> getAllEntityTypes() {
		assertActive();
		final GrpcEntityTypesResponse grpcResponse = executeWithBlockingEvitaSessionService(evitaSessionService ->
			evitaSessionService.getAllEntityTypes(Empty.newBuilder().build())
		);
		return new LinkedHashSet<>(
			grpcResponse.getEntityTypesList()
		);
	}

	@Nonnull
	@Override
	public <S extends Serializable> Optional<S> queryOne(@Nonnull Query query, @Nonnull Class<S> expectedType) throws UnexpectedResultException, UnexpectedResultCountException, InstanceTerminatedException {
		final EvitaRequest evitaRequest = new EvitaRequest(
			query,
			OffsetDateTime.now(),
			expectedType,
			extractEntityTypeFromClass(expectedType, reflectionLookup).orElse(null),
			this::createEntityProxy
		);
		return queryOneInternal(query, expectedType, evitaRequest);
	}

	@Nonnull
	@Override
	public <S extends Serializable> List<S> queryList(@Nonnull Query query, @Nonnull Class<S> expectedType) throws UnexpectedResultException, InstanceTerminatedException {
		final EvitaRequest evitaRequest = new EvitaRequest(
			query,
			OffsetDateTime.now(),
			expectedType,
			extractEntityTypeFromClass(expectedType, reflectionLookup).orElse(null),
			this::createEntityProxy
		);
		return queryListInternal(query, expectedType, evitaRequest);
	}

	@Nonnull
	@Override
	public <S extends Serializable, T extends EvitaResponse<S>> T query(@Nonnull Query query, @Nonnull Class<S> expectedType) throws UnexpectedResultException, InstanceTerminatedException {
		assertActive();
		final Query finalQuery = assertRequestMakesSenseAndEntityTypeIsPresent(query, expectedType, reflectionLookup);
		final StringWithParameters stringWithParameters = finalQuery.toStringWithParameterExtraction();
		final GrpcQueryResponse grpcResponse = executeWithBlockingEvitaSessionService(
			evitaSessionService ->
				evitaSessionService.query(
					GrpcQueryRequest.newBuilder()
						.setQuery(stringWithParameters.query())
						.addAllPositionalQueryParams(
							stringWithParameters.parameters()
								.stream()
								.map(QueryConverter::convertQueryParam)
								.toList()
						)
						.build()
				)
		);
		if (EntityReferenceContract.class.isAssignableFrom(expectedType)) {
			final DataChunk<EntityReference> recordPage = ResponseConverter.convertToDataChunk(
				grpcResponse,
				grpcRecordPage -> EntityConverter.toEntityReferences(grpcRecordPage.getEntityReferencesList())
			);
			//noinspection unchecked
			return (T) new EvitaEntityReferenceResponse(
				finalQuery, recordPage,
				getEvitaResponseExtraResults(
					grpcResponse,
					new EvitaRequest(
						finalQuery,
						OffsetDateTime.now(),
						EntityReference.class,
						null,
						this::createEntityProxy
					)
				)
			);
		} else {
			final String expectedEntityType = ofNullable(finalQuery.getCollection())
				.map(io.evitadb.api.query.head.Collection::getEntityType)
				.orElse(null);

			final DataChunk<S> recordPage;
			if (grpcResponse.getRecordPage().getBinaryEntitiesList().isEmpty()) {
				// convert to Sealed entities
				recordPage = ResponseConverter.convertToDataChunk(
					grpcResponse,
					grpcRecordPage -> EntityConverter.toEntities(
						grpcRecordPage.getSealedEntitiesList(),
						new EvitaRequest(
							finalQuery,
							OffsetDateTime.now(),
							expectedType,
							expectedEntityType,
							this::createEntityProxy
						),
						(entityType, schemaVersion) -> schemaCache.getEntitySchemaOrThrow(
							entityType, schemaVersion, this::fetchEntitySchema, this::getCatalogSchema
						),
						expectedType
					)
				);
			} else {
				// parse the entities
				//noinspection unchecked
				recordPage = ResponseConverter.convertToDataChunk(
					grpcResponse,
					grpcRecordPage -> grpcRecordPage.getBinaryEntitiesList()
						.stream()
						.map(EntityConverter::parseBinaryEntity)
						.map(it -> (S) it)
						.toList()
				);
			}

			//noinspection unchecked
			return (T) new EvitaEntityResponse<>(
				finalQuery, recordPage,
				getEvitaResponseExtraResults(
					grpcResponse,
					new EvitaRequest(
						finalQuery,
						OffsetDateTime.now(),
						expectedType,
						expectedEntityType,
						this::createEntityProxy
					)
				)
			);
		}
	}

	@Nonnull
	@Override
	public Optional<SealedEntity> getEntity(@Nonnull String entityType, int primaryKey, EntityContentRequire... require) {
		final EvitaRequest evitaRequest = new EvitaRequest(
			Query.query(
				collection(entityType),
				require(
					entityFetch(require)
				)
			),
			OffsetDateTime.now(),
			SealedEntity.class,
			null,
			this::createEntityProxy
		);
		return getEntityInternal(entityType, SealedEntity.class, primaryKey, evitaRequest, require);
	}

	@Nonnull
	@Override
	public <T extends Serializable> Optional<T> getEntity(@Nonnull Class<T> expectedType, int primaryKey, EntityContentRequire... require) throws EntityClassInvalidException {
		final String entityType = extractEntityTypeFromClass(expectedType, reflectionLookup)
			.orElseThrow(() -> new CollectionNotFoundException(expectedType));
		final EvitaRequest evitaRequest = new EvitaRequest(
			Query.query(
				collection(entityType),
				require(
					entityFetch(require)
				)
			),
			OffsetDateTime.now(),
			expectedType,
			entityType,
			this::createEntityProxy
		);
		return getEntityInternal(entityType, expectedType, primaryKey, evitaRequest, require);
	}

	@Nonnull
	@Override
	public <T extends Serializable> T enrichEntity(@Nonnull T partiallyLoadedEntity, EntityContentRequire... require) {
		assertActive();

		/* TOBEDONE https://gitlab.fg.cz/hv/evita/-/issues/118 */
		final String entityType;
		final Integer entityPk;
		if (partiallyLoadedEntity instanceof EntityClassifier entityClassifier) {
			entityType = entityClassifier.getType();
			entityPk = entityClassifier.getPrimaryKey();
		} else if (partiallyLoadedEntity instanceof SealedEntityProxy sealedEntityProxy) {
			entityType = sealedEntityProxy.entity().getType();
			entityPk = sealedEntityProxy.entity().getPrimaryKey();
		} else {
			throw new EvitaInvalidUsageException(
				"Unsupported entity type `" + partiallyLoadedEntity.getClass() + "`! The class doesn't implement EntityClassifier nor represents a SealedEntityProxy!",
				"Unsupported entity type!"
			);
		}

		final Class<?> expectedType = partiallyLoadedEntity instanceof SealedEntityProxy sealedEntityProxy ?
			sealedEntityProxy.getProxyClass() : partiallyLoadedEntity.getClass();
		final EvitaRequest evitaRequest = new EvitaRequest(
			Query.query(
				collection(entityType),
				require(
					entityFetch(require)
				)
			),
			OffsetDateTime.now(),
			expectedType,
			extractEntityTypeFromClass(expectedType, reflectionLookup)
				.orElse(null),
			this::createEntityProxy
		);

		//noinspection unchecked
		return (T) getEntityInternal(entityType, expectedType, entityPk, evitaRequest, require)
			.orElseThrow(() -> new EntityAlreadyRemovedException(entityType, entityPk));
	}

	@Nonnull
	@Override
	public <T extends Serializable> T enrichOrLimitEntity(@Nonnull T partiallyLoadedEntity, EntityContentRequire... require) {
		assertActive();

		/* TOBEDONE https://gitlab.fg.cz/hv/evita/-/issues/118 */
		final String entityType;
		final Integer entityPk;
		if (partiallyLoadedEntity instanceof EntityClassifier entityClassifier) {
			entityType = entityClassifier.getType();
			entityPk = entityClassifier.getPrimaryKey();
		} else if (partiallyLoadedEntity instanceof SealedEntityProxy sealedEntityProxy) {
			entityType = sealedEntityProxy.entity().getType();
			entityPk = sealedEntityProxy.entity().getPrimaryKey();
		} else {
			throw new EvitaInvalidUsageException(
				"Unsupported entity type `" + partiallyLoadedEntity.getClass() + "`! The class doesn't implement EntityClassifier nor represents a SealedEntityProxy!",
				"Unsupported entity type!"
			);
		}

		final Class<?> expectedType = partiallyLoadedEntity instanceof SealedEntityProxy sealedEntityProxy ?
			sealedEntityProxy.getProxyClass() : partiallyLoadedEntity.getClass();

		final EvitaRequest evitaRequest = new EvitaRequest(
			Query.query(
				collection(entityType),
				require(
					entityFetch(require)
				)
			),
			OffsetDateTime.now(),
			expectedType,
			extractEntityTypeFromClass(expectedType, reflectionLookup)
				.orElse(null),
			this::createEntityProxy
		);

		//noinspection unchecked
		return (T) getEntityInternal(entityType, expectedType, entityPk, evitaRequest, require)
			.orElseThrow(() -> new EntityAlreadyRemovedException(entityType, entityPk));
	}

	@Override
	public int updateCatalogSchema(@Nonnull LocalCatalogSchemaMutation... schemaMutation) throws SchemaAlteringException {
		assertActive();
		return executeInTransactionIfPossible(session -> {
			final List<GrpcLocalCatalogSchemaMutation> grpcSchemaMutations = Arrays.stream(schemaMutation)
				.map(CATALOG_SCHEMA_MUTATION_CONVERTER::convert)
				.toList();

			final GrpcUpdateCatalogSchemaRequest request = GrpcUpdateCatalogSchemaRequest.newBuilder()
				.addAllSchemaMutations(grpcSchemaMutations)
				.build();

			final GrpcUpdateCatalogSchemaResponse response = executeWithBlockingEvitaSessionService(
				evitaSessionService -> evitaSessionService.updateCatalogSchema(request)
			);

			schemaCache.analyzeMutations(schemaMutation);
			return response.getVersion();
		});

	}

	@Nonnull
	@Override
	public SealedCatalogSchema updateAndFetchCatalogSchema(@Nonnull LocalCatalogSchemaMutation... schemaMutation) throws SchemaAlteringException {
		assertActive();
		return executeInTransactionIfPossible(session -> {
			final List<GrpcLocalCatalogSchemaMutation> grpcSchemaMutations = Arrays.stream(schemaMutation)
				.map(CATALOG_SCHEMA_MUTATION_CONVERTER::convert)
				.toList();

			final GrpcUpdateCatalogSchemaRequest request = GrpcUpdateCatalogSchemaRequest.newBuilder()
				.addAllSchemaMutations(grpcSchemaMutations)
				.build();

			final GrpcUpdateAndFetchCatalogSchemaResponse response = executeWithBlockingEvitaSessionService(
				evitaSessionService -> evitaSessionService.updateAndFetchCatalogSchema(request)
			);

			final CatalogSchema updatedCatalogSchema = CatalogSchemaConverter.convert(
				response.getCatalogSchema(), clientEntitySchemaAccessor
			);
			final SealedCatalogSchema updatedSchema = new ClientCatalogSchemaDecorator(updatedCatalogSchema, clientEntitySchemaAccessor);
			schemaCache.analyzeMutations(schemaMutation);
			schemaCache.setLatestCatalogSchema(updatedCatalogSchema);
			return updatedSchema;
		});
	}

	@Override
	public int updateEntitySchema(@Nonnull ModifyEntitySchemaMutation schemaMutation) throws SchemaAlteringException {
		assertActive();
		return executeInTransactionIfPossible(session -> {
			final GrpcModifyEntitySchemaMutation grpcSchemaMutation = MODIFY_ENTITY_SCHEMA_MUTATION_CONVERTER.convert(schemaMutation);
			final GrpcUpdateEntitySchemaRequest request = GrpcUpdateEntitySchemaRequest.newBuilder()
				.setSchemaMutation(grpcSchemaMutation)
				.build();
			final GrpcUpdateEntitySchemaResponse response = executeWithBlockingEvitaSessionService(
				evitaSessionService -> evitaSessionService.updateEntitySchema(request)
			);
			schemaCache.analyzeMutations(schemaMutation);
			return response.getVersion();
		});
	}

	@Nonnull
	@Override
	public SealedEntitySchema updateAndFetchEntitySchema(@Nonnull ModifyEntitySchemaMutation schemaMutation) throws SchemaAlteringException {
		assertActive();
		return executeInTransactionIfPossible(session -> {
			final GrpcModifyEntitySchemaMutation grpcSchemaMutation = MODIFY_ENTITY_SCHEMA_MUTATION_CONVERTER.convert(schemaMutation);
			final GrpcUpdateEntitySchemaRequest request = GrpcUpdateEntitySchemaRequest.newBuilder()
				.setSchemaMutation(grpcSchemaMutation)
				.build();

			final GrpcUpdateAndFetchEntitySchemaResponse response = executeWithBlockingEvitaSessionService(
				evitaSessionService -> evitaSessionService.updateAndFetchEntitySchema(request)
			);

			final EntitySchema updatedSchema = EntitySchemaConverter.convert(response.getEntitySchema());
			schemaCache.analyzeMutations(schemaMutation);
			schemaCache.setLatestEntitySchema(updatedSchema);
			return new EntitySchemaDecorator(this::getCatalogSchema, updatedSchema);
		});

	}

	@Override
	public boolean deleteCollection(@Nonnull String entityType) {
		assertActive();
		return executeInTransactionIfPossible(
			evitaSessionContract -> {
				final GrpcDeleteCollectionResponse grpcResponse = executeWithBlockingEvitaSessionService(
					evitaSessionService ->
						evitaSessionService.deleteCollection(GrpcDeleteCollectionRequest.newBuilder()
							.setEntityType(entityType)
							.build()
						)
				);
				schemaCache.removeLatestEntitySchema(entityType);
				return grpcResponse.getDeleted();
			}
		);
	}

	@Override
	public boolean deleteCollection(@Nonnull Class<?> modelClass) throws EntityClassInvalidException {
		return deleteCollection(
			extractEntityTypeFromClass(modelClass, reflectionLookup)
				.orElseThrow(() -> new CollectionNotFoundException(modelClass))
		);
	}

	@Override
	public boolean renameCollection(@Nonnull String entityType, @Nonnull String newName) {
		assertActive();
		return executeInTransactionIfPossible(
			evitaSessionContract -> {
				final GrpcRenameCollectionResponse grpcResponse = executeWithBlockingEvitaSessionService(
					evitaSessionService ->
						evitaSessionService.renameCollection(
							GrpcRenameCollectionRequest.newBuilder()
								.setEntityType(entityType)
								.setNewName(newName)
								.build()
						)
				);
				schemaCache.removeLatestEntitySchema(entityType);
				return grpcResponse.getRenamed();
			}
		);
	}

	@Override
	public boolean replaceCollection(@Nonnull String entityTypeToBeReplaced, @Nonnull String entityTypeToBeReplacedWith) {
		assertActive();
		return executeInTransactionIfPossible(
			evitaSessionContract -> {
				final GrpcReplaceCollectionResponse grpcResponse = executeWithBlockingEvitaSessionService(
					evitaSessionService ->
						evitaSessionService.replaceCollection(
							GrpcReplaceCollectionRequest.newBuilder()
								.setEntityTypeToBeReplaced(entityTypeToBeReplaced)
								.setEntityTypeToBeReplacedWith(entityTypeToBeReplacedWith)
								.build()
						)
				);
				schemaCache.removeLatestEntitySchema(entityTypeToBeReplaced);
				schemaCache.removeLatestEntitySchema(entityTypeToBeReplacedWith);
				return grpcResponse.getReplaced();
			}
		);
	}

	@Override
	public int getEntityCollectionSize(@Nonnull String entityType) {
		assertActive();
		final GrpcEntityCollectionSizeResponse grpcResponse = executeWithBlockingEvitaSessionService(
			evitaSessionService ->
				evitaSessionService.getEntityCollectionSize(
					GrpcEntityCollectionSizeRequest
						.newBuilder()
						.setEntityType(entityType)
						.build()
				)
		);
		return grpcResponse.getSize();
	}

	@Nonnull
	@Override
	public EntityBuilder createNewEntity(@Nonnull String entityType) {
		assertActive();
		return executeInTransactionIfPossible(
			session -> {
				final EntitySchemaContract entitySchema;
				if (getCatalogSchema().getCatalogEvolutionMode().contains(CatalogEvolutionMode.ADDING_ENTITY_TYPES)) {
					entitySchema = getEntitySchema(entityType)
						.map(EntitySchemaContract.class::cast)
						.orElseGet(() -> EntitySchema._internalBuild(entityType));
				} else {
					entitySchema = getEntitySchemaOrThrow(entityType);
				}
				return new InitialEntityBuilder(entitySchema, null);
			}
		);
	}

	@Nonnull
	@Override
	public <S extends Serializable> S createNewEntity(@Nonnull Class<S> expectedType) {
		assertActive();
		final EntityBuilder entityBuilder = createNewEntity(
			extractEntityTypeFromClass(expectedType, reflectionLookup)
				.orElseThrow(() -> new CollectionNotFoundException(expectedType))
		);
		return this.proxyFactory.createEntityProxy(expectedType, entityBuilder, getEntitySchemaIndex());
	}

	@Nonnull
	@Override
	public EntityBuilder createNewEntity(@Nonnull String entityType, int primaryKey) {
		assertActive();
		return executeInTransactionIfPossible(
			session -> {
				final EntitySchemaContract entitySchema;
				if (getCatalogSchema().getCatalogEvolutionMode().contains(CatalogEvolutionMode.ADDING_ENTITY_TYPES)) {
					entitySchema = getEntitySchema(entityType)
						.map(EntitySchemaContract.class::cast)
						.orElseGet(() -> EntitySchema._internalBuild(entityType));
				} else {
					entitySchema = getEntitySchemaOrThrow(entityType);
				}
				return new InitialEntityBuilder(entitySchema, primaryKey);
			}
		);
	}

	@Nonnull
	@Override
	public <S extends Serializable> S createNewEntity(@Nonnull Class<S> expectedType, int primaryKey) {
		assertActive();
		final EntityBuilder entityBuilder = createNewEntity(
			extractEntityTypeFromClass(expectedType, reflectionLookup)
				.orElseThrow(() -> new CollectionNotFoundException(expectedType)),
			primaryKey
		);
		return this.proxyFactory.createEntityProxy(expectedType, entityBuilder, getEntitySchemaIndex());
	}

	@Nonnull
	@Override
	public <S extends Serializable> EntityReference upsertEntity(@Nonnull S customEntity) {
		if (customEntity instanceof InstanceEditor<?> ie && EntityContract.class.isAssignableFrom(ie.getContract())) {
			return ie.toMutation()
				.map(this::upsertEntity)
				.orElseGet(() -> {
					// no modification occurred, we can return the reference to the original entity
					// the `toInstance` method should be cost-free in this case, as no modifications occurred
					final EntityContract entity = (EntityContract) ie.toInstance();
					return new EntityReference(entity.getType(), entity.getPrimaryKey());
				});
		} else if (customEntity instanceof SealedEntityProxy sealedEntityProxy) {
			return sealedEntityProxy.getEntityBuilderWithCallback()
				.map(entityMutation -> {
					final EntityReference entityReference = upsertEntity(entityMutation.builder());
					entityMutation.updateEntityReference(entityReference);
					return entityReference;
				})
				.orElseGet(() -> {
					// no modification occurred, we can return the reference to the original entity
					// the `toInstance` method should be cost-free in this case, as no modifications occurred
					final EntityContract entity = sealedEntityProxy.entity();
					return new EntityReference(entity.getType(), entity.getPrimaryKey());
				});
		} else {
			throw new EvitaInvalidUsageException(
				"Method `upsertEntity` expects an instance of InstanceEditor, " +
					"yet the provided instance is of type `" + customEntity.getClass() + "` doesn't implement it!",
				"Invalid usage of method `upsertEntity`!"
			);
		}
	}

	@Nonnull
	@Override
	public <S extends Serializable> List<EntityReference> upsertEntityDeeply(@Nonnull S customEntity) {
		if (customEntity instanceof SealedEntityReferenceProxy sealedEntityReferenceProxy) {
			return Stream.concat(
					// we need first to store the referenced entities (deep wise)
					sealedEntityReferenceProxy.getReferencedEntityBuildersWithCallback()
						.map(entityBuilderWithCallback -> {
							final EntityReference entityReference = upsertEntity(entityBuilderWithCallback.builder());
							entityBuilderWithCallback.updateEntityReference(entityReference);
							return entityReference;
						}),
					// and then the reference itself
					sealedEntityReferenceProxy
						.getReferenceBuilderIfPresent()
						.stream()
						.map(it -> {
								final EntityClassifier entityClassifier = sealedEntityReferenceProxy.getEntityClassifier();
								final EntityUpsertMutation entityUpsertMutation = new EntityUpsertMutation(
									entityClassifier.getType(),
									entityClassifier.getPrimaryKey(),
									EntityExistence.MUST_EXIST,
									it.buildChangeSet().collect(Collectors.toList())
								);
								final EntityReference entityReference = this.upsertEntity(entityUpsertMutation);
								sealedEntityReferenceProxy.notifyBuilderUpserted();
								return entityReference;
							}
						)
				)
				.toList();
		} else if (customEntity instanceof SealedEntityProxy sealedEntityProxy) {
			return Stream.concat(
					// we need first to store the referenced entities (deep wise)
					sealedEntityProxy.getReferencedEntityBuildersWithCallback(),
					// then the entity itself
					sealedEntityProxy.getEntityBuilderWithCallback().stream()
				)
				.map(entityBuilderWithCallback -> {
					final EntityReference entityReference = upsertEntity(entityBuilderWithCallback.builder());
					entityBuilderWithCallback.updateEntityReference(entityReference);
					return entityReference;
				})
				.toList();
		} else if (customEntity instanceof InstanceEditor<?> ie) {
			return ie.toMutation()
				.map(this::upsertEntity)
				.map(List::of)
				.orElse(Collections.emptyList());
		} else {
			throw new EvitaInvalidUsageException(
				"Method `upsertEntity` expects an instance of InstanceEditor, " +
					"yet the provided instance is of type `" + customEntity.getClass() + "` doesn't implement it!",
				"Invalid usage of method `upsertEntity`!"
			);
		}
	}

	@Nonnull
	@Override
	public EntityReference upsertEntity(@Nonnull EntityMutation entityMutation) {
		assertActive();
		return executeInTransactionIfPossible(session -> {
			final GrpcEntityMutation grpcEntityMutation = ENTITY_MUTATION_CONVERTER.convert(entityMutation);
			final GrpcUpsertEntityResponse grpcResult = executeWithBlockingEvitaSessionService(
				evitaSessionService ->
					evitaSessionService.upsertEntity(
						GrpcUpsertEntityRequest.newBuilder()
							.setEntityMutation(grpcEntityMutation)
							.build()
					)
			);
			final GrpcEntityReference grpcReference = grpcResult.getEntityReference();
			return new EntityReference(
				grpcReference.getEntityType(), grpcReference.getPrimaryKey()
			);
		});
	}

	@Nonnull
	@Override
	public SealedEntity upsertAndFetchEntity(@Nonnull EntityBuilder entityBuilder, EntityContentRequire... require) {
		return entityBuilder.toMutation()
			.map(it -> upsertAndFetchEntity(it, require))
			.orElseGet(
				() -> getEntity(entityBuilder.getType(), entityBuilder.getPrimaryKey(), require)
					.orElseThrow(() -> new EvitaInvalidUsageException("Entity `" + entityBuilder.getType() + "` with id `" + entityBuilder.getPrimaryKey() + "` doesn't exist!"))
			);
	}

	@Nonnull
	@Override
	public SealedEntity upsertAndFetchEntity(@Nonnull EntityMutation entityMutation, EntityContentRequire... require) {
		assertActive();
		return executeInTransactionIfPossible(session -> {
			final GrpcEntityMutation grpcEntityMutation = ENTITY_MUTATION_CONVERTER.convert(entityMutation);
			final StringWithParameters stringWithParameters = PrettyPrintingVisitor.toStringWithParameterExtraction(require);
			final GrpcUpsertEntityResponse grpcResponse = executeWithBlockingEvitaSessionService(
				evitaSessionService ->
					evitaSessionService.upsertEntity(
						GrpcUpsertEntityRequest
							.newBuilder()
							.setEntityMutation(grpcEntityMutation)
							.setRequire(stringWithParameters.query())
							.addAllPositionalQueryParams(
								stringWithParameters.parameters()
									.stream()
									.map(QueryConverter::convertQueryParam)
									.toList()
							)
							.build()
					)
			);
			return EntityConverter.toEntity(
				entity -> schemaCache.getEntitySchemaOrThrow(
					entity.getEntityType(), entity.getSchemaVersion(), this::fetchEntitySchema, this::getCatalogSchema
				),
				new EvitaRequest(
					Query.query(
						collection(entityMutation.getEntityType()),
						require(
							entityFetch(require)
						)
					),
					OffsetDateTime.now(),
					SealedEntity.class,
					null,
					this::createEntityProxy
				),
				grpcResponse.getEntity(),
				SealedEntity.class
			);
		});
	}

	@Override
	public boolean deleteEntity(@Nonnull String entityType, int primaryKey) {
		assertActive();
		return executeInTransactionIfPossible(session -> {
			final GrpcDeleteEntityResponse grpcResponse = executeWithBlockingEvitaSessionService(
				evitaSessionService ->
					evitaSessionService.deleteEntity(
						GrpcDeleteEntityRequest
							.newBuilder()
							.setEntityType(entityType)
							.setPrimaryKey(Int32Value.newBuilder().setValue(primaryKey).build())
							.build()
					)
			);
			return grpcResponse.hasEntity() || grpcResponse.hasEntityReference();
		});
	}

	@Override
	public boolean deleteEntity(@Nonnull Class<?> modelClass, int primaryKey) throws EntityClassInvalidException {
		return deleteEntity(
			extractEntityTypeFromClass(modelClass, reflectionLookup)
				.orElseThrow(() -> new CollectionNotFoundException(modelClass)),
			primaryKey
		);
	}

	@Nonnull
	@Override
	public Optional<SealedEntity> deleteEntity(@Nonnull String entityType, int primaryKey, EntityContentRequire... require) {
		return deleteEntityInternal(entityType, SealedEntity.class, primaryKey, require);
	}

	@Nonnull
	@Override
	public <T extends Serializable> Optional<T> deleteEntity(@Nonnull Class<T> modelClass, int primaryKey, EntityContentRequire... require) throws EntityClassInvalidException {
		return deleteEntityInternal(
			extractEntityTypeFromClass(modelClass, reflectionLookup)
				.orElseThrow(() -> new CollectionNotFoundException(modelClass)),
			modelClass, primaryKey, require
		);
	}

	@Override
	public int deleteEntityAndItsHierarchy(@Nonnull String entityType, int primaryKey) {
		assertActive();
		return executeInTransactionIfPossible(session -> {
			final GrpcDeleteEntityAndItsHierarchyResponse grpcResponse = executeWithBlockingEvitaSessionService(
				evitaSessionService ->
					evitaSessionService.deleteEntityAndItsHierarchy(
						GrpcDeleteEntityRequest
							.newBuilder()
							.setEntityType(entityType)
							.setPrimaryKey(Int32Value.newBuilder().setValue(primaryKey).build())
							.build()
					)
			);
			return grpcResponse.getDeletedEntities();
		});
	}

	@Nonnull
	@Override
	public DeletedHierarchy<SealedEntity> deleteEntityAndItsHierarchy(@Nonnull String entityType, int primaryKey, EntityContentRequire... require) {
		return deleteEntityHierarchyInternal(entityType, SealedEntity.class, primaryKey, require);
	}

	@Nonnull
	@Override
	public <T extends Serializable> DeletedHierarchy<T> deleteEntityAndItsHierarchy(@Nonnull Class<T> modelClass, int primaryKey, EntityContentRequire... require) throws EvitaInvalidUsageException, EntityClassInvalidException {
		return deleteEntityHierarchyInternal(
			extractEntityTypeFromClass(modelClass, reflectionLookup)
				.orElseThrow(() -> new CollectionNotFoundException(modelClass)),
			modelClass, primaryKey, require
		);
	}

	@Override
	public int deleteEntities(@Nonnull Query query) {
		assertActive();
		return executeInTransactionIfPossible(session -> {
			final StringWithParameters stringWithParameters = query.normalizeQuery().toStringWithParameterExtraction();
			final GrpcDeleteEntitiesResponse grpcResponse = executeWithBlockingEvitaSessionService(
				evitaSessionService ->
					evitaSessionService.deleteEntities(
						GrpcDeleteEntitiesRequest
							.newBuilder()
							.setQuery(stringWithParameters.query())
							.addAllPositionalQueryParams(
								stringWithParameters.parameters()
									.stream()
									.map(QueryConverter::convertQueryParam)
									.toList()
							)
							.build()
					)
			);
			return grpcResponse.getDeletedEntities();
		});
	}

	@Nonnull
	@Override
	public SealedEntity[] deleteSealedEntitiesAndReturnBodies(@Nonnull Query query) {
		assertActive();
		return executeInTransactionIfPossible(session -> {
			final EvitaRequest evitaRequest = new EvitaRequest(
				query,
				OffsetDateTime.now(),
				SealedEntity.class,
				null,
				this::createEntityProxy
			);
			final StringWithParameters stringWithParameters = query.normalizeQuery().toStringWithParameterExtraction();
			final GrpcDeleteEntitiesResponse grpcResponse = executeWithBlockingEvitaSessionService(
				evitaSessionService ->
					evitaSessionService.deleteEntities(
						GrpcDeleteEntitiesRequest
							.newBuilder()
							.setQuery(stringWithParameters.query())
							.addAllPositionalQueryParams(
								stringWithParameters.parameters()
									.stream()
									.map(QueryConverter::convertQueryParam)
									.toList()
							)
							.build()
					)
			);
			return grpcResponse.getDeletedEntityBodiesList()
				.stream()
				.map(
					it -> EntityConverter.toEntity(
						entity -> schemaCache.getEntitySchemaOrThrow(
							entity.getEntityType(), entity.getSchemaVersion(), this::fetchEntitySchema, this::getCatalogSchema
						),
						evitaRequest,
						it,
						SealedEntity.class
					)
				)
				.toArray(SealedEntity[]::new);
		});
	}

	@Nonnull
	@Override
	public Optional<UUID> getOpenedTransactionId() {
		assertActive();
		return ofNullable(transactionAccessor.get())
			.filter(EvitaClientTransaction::isClosed)
			.map(EvitaClientTransaction::getTransactionId);
	}

	@Override
	public boolean isRollbackOnly() {
		return getOpenedTransactionId().isPresent() && transactionAccessor.get().isRollbackOnly();
	}

	@Override
	public void setRollbackOnly() {
		assertActive();
		if (transactionAccessor.get() == null) {
			throw new UnexpectedTransactionStateException("No transaction has been opened!");
		}
		final EvitaClientTransaction transaction = transactionAccessor.get();
		transaction.setRollbackOnly();
	}

	@Override
	public boolean isReadOnly() {
		return !sessionTraits.isReadWrite();
	}

	@Override
	public boolean isBinaryFormat() {
		return sessionTraits.isBinary();
	}

	@Override
	public boolean isDryRun() {
		return sessionTraits.isDryRun();
	}

	@Override
	public long getInactivityDurationInSeconds() {
		return (System.currentTimeMillis() - lastCall) / 1000;
	}

	/**
	 * This method is internal and is a special form of {@link #getCatalogSchema()} that can handle the situation when
	 * this particular session is already closed and opens a new temporary one for accessing the schemas on the server
	 * side when necessary.
	 *
	 * @param evita - reference to the {@link EvitaClient} instance that is used to open a new temporary session when necessary
	 * @return {@link SealedCatalogSchema} of the catalog targeted by this session
	 */
	@Nonnull
	public SealedCatalogSchema getCatalogSchema(@Nonnull EvitaClient evita) {
		assertActive();
		return schemaCache.getLatestCatalogSchema(
			() -> isActive() ?
				this.fetchCatalogSchema() :
				evita.queryCatalog(
					catalogName,
					session -> {
						return ((EvitaClientSession) session).fetchCatalogSchema();
					}
				),
			clientEntitySchemaAccessor
		);
	}

	/**
	 * Method internally closes the session
	 */
	@Nonnull
	public CompletableFuture<Long> closeInternally() {
		if (isActive()) {
			final CompletableFuture<Long> closeFuture = new CompletableFuture<>();
			// join both futures together and apply termination callback
			this.closedFuture = closeFuture.whenComplete((newCatalogVersion, throwable) -> {
				// then apply termination callbacks
				ofNullable(onTerminationCallback)
					.ifPresent(it -> it.accept(this));
				if (throwable instanceof CancellationException cancellationException) {
					throw cancellationException;
				} else if (throwable instanceof TransactionException transactionException) {
					throw transactionException;
				} else if (throwable != null) {
					throw new TransactionException("Unexpected exception occurred while executing transaction!", throwable);
				}
			});
			return closeFuture;
		}
		return this.closedFuture;
	}

	/**
	 * Method executes lambda using specified timeout for the call ignoring the defaults specified
	 * in {@link EvitaClientConfiguration#timeout()}.
	 *
	 * @param lambda logic to be executed
	 * @param timeout timeout value
	 * @param unit   time unit of the timeout
	 */
	public void executeWithExtendedTimeout(@Nonnull Runnable lambda, long timeout, @Nonnull TimeUnit unit) {
		try {
			this.callTimeout.push(new Timeout(timeout, unit));
			lambda.run();
		} finally {
			this.callTimeout.pop();
		}
	}

	/**
	 * Method executes lambda using specified timeout for the call ignoring the defaults specified
	 * in {@link EvitaClientConfiguration#timeout()}.
	 *
	 * @param lambda logic to be executed
	 * @param timeout timeout value
	 * @param unit   time unit of the timeout
	 * @return result of the lambda
	 * @param <T> type of the result
	 */
	public <T> T executeWithExtendedTimeout(@Nonnull Supplier<T> lambda, long timeout, @Nonnull TimeUnit unit) {
		try {
			this.callTimeout.push(new Timeout(timeout, unit));
			return lambda.get();
		} finally {
			this.callTimeout.pop();
		}
	}

	/**
	 * Delegates call to internal {@link #proxyFactory#createEntityProxy(Class, SealedEntity, Map)}.
	 *
	 * @param contract     contract of the entity to be created
	 * @param sealedEntity sealed entity to be used as a source of data
	 * @param <S>          type of the entity
	 * @return new instance of the entity proxy
	 */
	@Nonnull
	private <S> S createEntityProxy(@Nonnull Class<S> contract, @Nonnull SealedEntity sealedEntity) {
		return this.proxyFactory.createEntityProxy(contract, sealedEntity, getEntitySchemaIndex());
	}

	/**
	 * Returns map with current catalog {@link EntitySchemaContract entity schema} instances indexed by their
	 * {@link EntitySchemaContract#getName() name}.
	 *
	 * @return map with current catalog {@link EntitySchemaContract entity schema} instances
	 * @see EvitaEntitySchemaCache#getLatestEntitySchemaIndex(Supplier, Function, Supplier)
	 */
	@Nonnull
	private Map<String, EntitySchemaContract> getEntitySchemaIndex() {
		return schemaCache.getLatestEntitySchemaIndex(
			this::getAllEntityTypes,
			this::fetchEntitySchema,
			this::getCatalogSchema
		);
	}

	@Nonnull
	private <T> Optional<T> getEntityInternal(
		@Nonnull String entityType,
		@Nonnull Class<T> expectedType,
		int primaryKey,
		@Nonnull EvitaRequest evitaRequest,
		EntityContentRequire... require
	) {
		assertActive();

		final StringWithParameters stringWithParameters = PrettyPrintingVisitor.toStringWithParameterExtraction(require);
		final GrpcEntityResponse grpcResponse = executeWithBlockingEvitaSessionService(
			evitaSessionService ->
				evitaSessionService.getEntity(
					GrpcEntityRequest
						.newBuilder()
						.setEntityType(entityType)
						.setPrimaryKey(primaryKey)
						.setRequire(stringWithParameters.query())
						.addAllPositionalQueryParams(
							stringWithParameters.parameters()
								.stream()
								.map(QueryConverter::convertQueryParam)
								.toList()
						)
						.build()
				)
		);

		return grpcResponse.hasEntity() ?
			Optional.of(
				(T) EntityConverter.toEntity(
					entity -> schemaCache.getEntitySchemaOrThrow(
						entity.getEntityType(), entity.getSchemaVersion(), this::fetchEntitySchema, this::getCatalogSchema
					),
					evitaRequest,
					grpcResponse.getEntity(),
					expectedType
				)
			) : Optional.empty();
	}

	@Nonnull
	private <S extends Serializable> List<S> queryListInternal(
		@Nonnull Query query,
		@Nonnull Class<S> expectedType,
		@Nonnull EvitaRequest evitaRequest
	) {
		assertActive();
		final Query finalQuery = assertRequestMakesSenseAndEntityTypeIsPresent(query, expectedType, reflectionLookup);
		final StringWithParameters stringWithParameters = finalQuery.toStringWithParameterExtraction();
		final GrpcQueryListResponse grpcResponse = executeWithBlockingEvitaSessionService(
			evitaSessionService ->
				evitaSessionService.queryList(
					GrpcQueryRequest.newBuilder()
						.setQuery(stringWithParameters.query())
						.addAllPositionalQueryParams(
							stringWithParameters.parameters()
								.stream()
								.map(QueryConverter::convertQueryParam)
								.toList()
						)
						.build()
				)
		);
		if (EntityReferenceContract.class.isAssignableFrom(expectedType)) {
			final List<GrpcEntityReference> entityReferencesList = grpcResponse.getEntityReferencesList();
			//noinspection unchecked
			return (List<S>) EntityConverter.toEntityReferences(entityReferencesList);
		} else {
			if (grpcResponse.getBinaryEntitiesList().isEmpty()) {
				// convert to Sealed entities
				return EntityConverter.toEntities(
					grpcResponse.getSealedEntitiesList(),
					evitaRequest,
					(entityType, schemaVersion) -> schemaCache.getEntitySchemaOrThrow(
						entityType, schemaVersion, this::fetchEntitySchema, this::getCatalogSchema
					),
					expectedType
				);
			} else {
				// parse the entities
				//noinspection unchecked
				return grpcResponse.getBinaryEntitiesList()
					.stream()
					.map(EntityConverter::parseBinaryEntity)
					.map(it -> (S) it)
					.toList();
			}
		}
	}

	/**
	 * Method that is called within the {@link EvitaClientSession} to apply the wanted logic on a channel retrieved
	 * from a channel pool.
	 *
	 * @param lambda function that holds a logic passed by the caller
	 * @param <T>    return type of the function
	 * @return result of the applied function
	 */
	private <T> T executeWithBlockingEvitaSessionService(
		@Nonnull AsyncCallFunction<EvitaSessionServiceFutureStub, ListenableFuture<T>> lambda
	) {
		final Timeout timeout = callTimeout.peek();
		try {
			return executeWithEvitaSessionService(
				lambda,
				EvitaSessionServiceGrpc::newFutureStub
			).get(timeout.timeout(), timeout.timeoutUnit());
		} catch (ExecutionException e) {
			if (e.getCause() instanceof EvitaInvalidUsageException invalidUsageException) {
				throw invalidUsageException;
			} else if (e.getCause() instanceof EvitaInternalError internalError) {
				throw internalError;
			} else if (e.getCause() instanceof StatusRuntimeException statusRuntimeException) {
				throw transformStatusRuntimeException(statusRuntimeException);
			} else {
				throw new EvitaClientServerCallException("Server call failed.", e.getCause());
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new EvitaClientServerCallException("Server call interrupted.", e);
		} catch (TimeoutException e) {
			throw new EvitaClientTimedOutException(
				timeout.timeout(), timeout.timeoutUnit()
			);
		}
	}

	/**
	 * Method that is called within the {@link EvitaClientSession} to apply the wanted logic on a channel retrieved
	 * from a channel pool.
	 *
	 * @param lambda function that holds a logic passed by the caller
	 */
	private void executeWithAsyncEvitaSessionService(
		@Nonnull AsyncCallFunction<EvitaSessionServiceStub, Void> lambda
	) {
		executeWithEvitaSessionService(
			lambda,
			EvitaSessionServiceGrpc::newStub
		);
	}

	/**
	 * Method that is called within the {@link EvitaClientSession} to apply the wanted logic on a channel retrieved
	 * from a channel pool.
	 *
	 * @param lambda function that holds a logic passed by the caller
	 * @param <T>    return type of the function
	 * @param <S>    type of the expected stub
	 * @return result of the applied function
	 */
	private <S, T> T executeWithEvitaSessionService(
		@Nonnull AsyncCallFunction<S, T> lambda,
		@Nonnull Function<ManagedChannel, S> stubFactory
	) {
		final ManagedChannel managedChannel = this.channelPool.getChannel();
		try {
			SessionIdHolder.setSessionId(getCatalogName(), getId().toString());
			return lambda.apply(stubFactory.apply(managedChannel));
		} catch (StatusRuntimeException statusRuntimeException) {
			throw transformStatusRuntimeException(statusRuntimeException);
		} catch (EvitaInvalidUsageException | EvitaInternalError evitaError) {
			throw evitaError;
		} catch (Throwable e) {
			log.error("Unexpected internal Evita error occurred: {}", e.getMessage(), e);
			throw new GenericEvitaInternalError(
				"Unexpected internal Evita error occurred: " + e.getMessage(),
				"Unexpected internal Evita error occurred.",
				e
			);
		} finally {
			this.channelPool.releaseChannel(managedChannel);
			SessionIdHolder.reset();
		}
	}

	/**
	 * Handles a {@link StatusRuntimeException} by checking the status code and performing appropriate actions.
	 *
	 * @param statusRuntimeException the {@link StatusRuntimeException} to handle
	 */
	@Nonnull
	private RuntimeException transformStatusRuntimeException(@Nonnull StatusRuntimeException statusRuntimeException) {
		final Code statusCode = statusRuntimeException.getStatus().getCode();
		final String description = ofNullable(statusRuntimeException.getStatus().getDescription())
			.orElse("No description.");
		if (statusCode == Code.UNAUTHENTICATED) {
			// close session and rethrow
			final CompletableFuture<Long> future = closeInternally();
			future.complete(0L);
			return new InstanceTerminatedException("session");
		} else if (statusCode == Code.INVALID_ARGUMENT) {
			final Matcher expectedFormat = ERROR_MESSAGE_PATTERN.matcher(description);
			if (expectedFormat.matches()) {
				return EvitaInvalidUsageException.createExceptionWithErrorCode(
					expectedFormat.group(2), expectedFormat.group(1)
				);
			} else {
				return new EvitaInvalidUsageException(description);
			}
		} else {
			final Matcher expectedFormat = ERROR_MESSAGE_PATTERN.matcher(description);
			if (expectedFormat.matches()) {
				return GenericEvitaInternalError.createExceptionWithErrorCode(
					expectedFormat.group(2), expectedFormat.group(1)
				);
			} else {
				return new GenericEvitaInternalError(description);
			}
		}
	}

	@Nonnull
	private <S extends Serializable> Optional<S> queryOneInternal(
		@Nonnull Query query,
		@Nonnull Class<S> expectedType,
		@Nonnull EvitaRequest evitaRequest
	) {
		assertActive();
		final Query finalQuery = assertRequestMakesSenseAndEntityTypeIsPresent(query, expectedType, reflectionLookup);
		final StringWithParameters stringWithParameters = finalQuery.toStringWithParameterExtraction();
		final GrpcQueryOneResponse grpcResponse = executeWithBlockingEvitaSessionService(
			evitaSessionService ->
				evitaSessionService.queryOne(
					GrpcQueryRequest.newBuilder()
						.setQuery(stringWithParameters.query())
						.addAllPositionalQueryParams(
							stringWithParameters.parameters()
								.stream()
								.map(QueryConverter::convertQueryParam)
								.toList()
						)
						.build()
				)
		);
		if (EntityReferenceContract.class.isAssignableFrom(expectedType)) {
			if (!grpcResponse.hasEntityReference()) {
				return empty();
			}
			final GrpcEntityReference entityReference = grpcResponse.getEntityReference();
			//noinspection unchecked
			return (Optional<S>) of(new EntityReference(
				entityReference.getEntityType(),
				entityReference.getPrimaryKey()
			));
		} else {
			if (grpcResponse.hasBinaryEntity()) {
				// parse the entity!
				return of(EntityConverter.parseBinaryEntity(grpcResponse.getBinaryEntity()));
			} else {
				if (!grpcResponse.hasSealedEntity()) {
					return empty();
				}
				// convert to Sealed entity
				final GrpcSealedEntity sealedEntity = grpcResponse.getSealedEntity();
				return of(EntityConverter.toEntity(
					entity -> schemaCache.getEntitySchemaOrThrow(
						entity.getEntityType(), entity.getSchemaVersion(), this::fetchEntitySchema, this::getCatalogSchema
					),
					evitaRequest,
					sealedEntity,
					expectedType
				));
			}
		}
	}

	@Nonnull
	private <T> Optional<T> deleteEntityInternal(@Nonnull String entityType, @Nonnull Class<T> expectedType, int primaryKey, EntityContentRequire[] require) {
		assertActive();
		return executeInTransactionIfPossible(session -> {
			final StringWithParameters stringWithParameters = PrettyPrintingVisitor.toStringWithParameterExtraction(require);
			final GrpcDeleteEntityResponse grpcResponse = executeWithBlockingEvitaSessionService(
				evitaSessionService ->
					evitaSessionService.deleteEntity(
						GrpcDeleteEntityRequest
							.newBuilder()
							.setEntityType(entityType)
							.setPrimaryKey(Int32Value.newBuilder().setValue(primaryKey).build())
							.setRequire(stringWithParameters.query())
							.addAllPositionalQueryParams(
								stringWithParameters.parameters()
									.stream()
									.map(QueryConverter::convertQueryParam)
									.toList()
							)
							.build()
					)
			);
			return grpcResponse.hasEntity() ?
				of(
					EntityConverter.toEntity(
						entity -> schemaCache.getEntitySchemaOrThrow(
							entity.getEntityType(), entity.getSchemaVersion(), this::fetchEntitySchema, this::getCatalogSchema
						),
						new EvitaRequest(
							Query.query(
								collection(entityType),
								require(
									entityFetch(require)
								)
							),
							OffsetDateTime.now(),
							expectedType,
							extractEntityTypeFromClass(expectedType, reflectionLookup)
								.orElse(null),
							this::createEntityProxy
						),
						grpcResponse.getEntity(),
						expectedType
					)
				) : empty();
		});
	}

	@Nonnull
	private <T> DeletedHierarchy<T> deleteEntityHierarchyInternal(
		@Nonnull String entityType,
		@Nonnull Class<T> expectedType,
		int primaryKey,
		EntityContentRequire... require
	) {
		assertActive();
		return executeInTransactionIfPossible(session -> {
			final StringWithParameters stringWithParameters = PrettyPrintingVisitor.toStringWithParameterExtraction(require);
			final GrpcDeleteEntityAndItsHierarchyResponse grpcResponse = executeWithBlockingEvitaSessionService(
				evitaSessionService ->
					evitaSessionService.deleteEntityAndItsHierarchy(
						GrpcDeleteEntityRequest
							.newBuilder()
							.setEntityType(entityType)
							.setPrimaryKey(Int32Value.newBuilder().setValue(primaryKey).build())
							.setRequire(stringWithParameters.query())
							.addAllPositionalQueryParams(
								stringWithParameters.parameters()
									.stream()
									.map(QueryConverter::convertQueryParam)
									.toList()
							)
							.build()
					)
			);
			return new DeletedHierarchy<>(
				grpcResponse.getDeletedEntities(),
				grpcResponse.hasDeletedRootEntity() ?
					EntityConverter.toEntity(
						entity -> schemaCache.getEntitySchemaOrThrow(
							entity.getEntityType(), entity.getSchemaVersion(), this::fetchEntitySchema, this::getCatalogSchema
						),
						new EvitaRequest(
							Query.query(
								collection(entityType),
								require(
									entityFetch(require)
								)
							),
							OffsetDateTime.now(),
							expectedType,
							extractEntityTypeFromClass(expectedType, reflectionLookup)
								.orElse(null),
							this::createEntityProxy
						),
						grpcResponse.getDeletedRootEntity(),
						expectedType
					) : null
			);
		});
	}

	@Nonnull
	private EvitaResponseExtraResult[] getEvitaResponseExtraResults(
		@Nonnull GrpcQueryResponse grpcResponse,
		@Nonnull EvitaRequest request
	) {
		return grpcResponse.hasExtraResults() ?
			ResponseConverter.toExtraResults(
				(sealedEntity) -> schemaCache.getEntitySchemaOrThrow(
					sealedEntity.getEntityType(), sealedEntity.getSchemaVersion(),
					this::fetchEntitySchema, this::getCatalogSchema
				),
				request,
				grpcResponse.getExtraResults()
			) : new EvitaResponseExtraResult[0];
	}

	/**
	 * This internal method will physically call over the network and fetch actual {@link CatalogSchema}.
	 */
	@Nonnull
	private CatalogSchema fetchCatalogSchema() {
		final GrpcCatalogSchemaResponse grpcResponse = executeWithBlockingEvitaSessionService(
			evitaSessionService ->
				evitaSessionService.getCatalogSchema(Empty.getDefaultInstance())
		);
		return CatalogSchemaConverter.convert(
			grpcResponse.getCatalogSchema(), clientEntitySchemaAccessor
		);
	}

	/**
	 * This internal method will physically call over the network and fetch actual {@link EntitySchema}.
	 */
	@Nonnull
	private Optional<EntitySchema> fetchEntitySchema(@Nonnull String entityType) {
		final GrpcEntitySchemaResponse grpcResponse = executeWithBlockingEvitaSessionService(
			evitaSessionService ->
				evitaSessionService.getEntitySchema(
					GrpcEntitySchemaRequest
						.newBuilder()
						.setEntityType(entityType)
						.build()
				)
		);
		if (!grpcResponse.hasEntitySchema()) {
			return empty();
		}
		return of(EntitySchemaConverter.convert(grpcResponse.getEntitySchema()));
	}

	/**
	 * Verifies this instance is still active.
	 */
	private void assertActive() {
		if (isActive()) {
			this.lastCall = System.currentTimeMillis();
		} else {
			throw new InstanceTerminatedException("session");
		}
	}

	/**
	 * Initializes transaction reference.
	 */
	@Nonnull
	private EvitaClientTransaction createAndInitTransaction() {
		if (!sessionTraits.isReadWrite()) {
			throw new TransactionNotSupportedException("Transaction cannot be opened in read only session!");
		}
		if (getCatalogState() == CatalogState.WARMING_UP) {
			throw new TransactionNotSupportedException("Catalog " + getCatalogName() + " doesn't support transaction yet. Call `goLiveAndClose()` method first!");
		}

		final GrpcTransactionResponse grpcResponse = executeWithBlockingEvitaSessionService(
			evitaSessionService ->
				evitaSessionService.getTransactionId(Empty.newBuilder().build())
		);

		final EvitaClientTransaction tx = new EvitaClientTransaction(
			toUuid(grpcResponse.getTransactionId()),
			grpcResponse.getCatalogVersion()
		);

		schemaCache.updateLastKnownCatalogVersion(grpcResponse.getCatalogVersion());

		transactionAccessor.getAndUpdate(transaction -> {
			Assert.isPremiseValid(transaction == null, "Transaction unexpectedly found!");
			if (sessionTraits.isDryRun()) {
				tx.setRollbackOnly();
			}
			return tx;
		});
		return tx;
	}

	/**
	 * Executes passed lambda in existing transaction or throws exception.
	 *
	 * @throws UnexpectedTransactionStateException if transaction is not open
	 */
	private <T> T executeInTransactionIfPossible(Function<EvitaSessionContract, T> logic) {
		if (transactionAccessor.get() == null && getCatalogState() == CatalogState.ALIVE) {
			//noinspection unused
			try (final EvitaClientTransaction newTransaction = createAndInitTransaction()) {
				try {
					return logic.apply(this);
				} catch (Throwable ex) {
					ofNullable(transactionAccessor.get())
						.ifPresent(EvitaClientTransaction::setRollbackOnly);
					throw ex;
				}
			}
		} else {
			// the transaction might already exist
			try {
				return logic.apply(this);
			} catch (Throwable ex) {
				ofNullable(transactionAccessor.get())
					.ifPresent(EvitaClientTransaction::setRollbackOnly);
				throw ex;
			}
		}
	}

	private class ClientEntitySchemaAccessor implements EntitySchemaProvider {
		@Nonnull
		@Override
		public Collection<EntitySchemaContract> getEntitySchemas() {
			return (
				isActive() ?
					EvitaClientSession.this.getAllEntityTypes() :
					evita.queryCatalog(
						catalogName,
						EvitaSessionContract::getAllEntityTypes
					)
			).stream()
				.map(this::getEntitySchema)
				.filter(Optional::isPresent)
				.map(Optional::get)
				.collect(Collectors.toList());
		}

		@Nonnull
		@Override
		public Optional<EntitySchemaContract> getEntitySchema(@Nonnull String entityType) {
			return (
				isActive() ?
					EvitaClientSession.this.getEntitySchema(entityType) :
					evita.queryCatalog(
						catalogName,
						session -> {
							return session.getEntitySchema(entityType);
						}
					)
			).map(EntitySchemaContract.class::cast);
		}
	}
}
