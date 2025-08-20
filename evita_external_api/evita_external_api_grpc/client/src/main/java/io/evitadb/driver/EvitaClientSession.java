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

package io.evitadb.driver;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.protobuf.Empty;
import com.google.protobuf.Int32Value;
import com.google.protobuf.Int64Value;
import com.linecorp.armeria.client.grpc.GrpcClientBuilder;
import io.evitadb.api.CatalogState;
import io.evitadb.api.CommitProgress;
import io.evitadb.api.CommitProgress.CommitVersions;
import io.evitadb.api.CommitProgressRecord;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.SchemaPostProcessor;
import io.evitadb.api.SchemaPostProcessorCapturingResult;
import io.evitadb.api.SessionTraits;
import io.evitadb.api.TransactionContract.CommitBehavior;
import io.evitadb.api.exception.*;
import io.evitadb.api.file.FileForFetch;
import io.evitadb.api.proxy.ProxyFactory;
import io.evitadb.api.proxy.SealedEntityProxy;
import io.evitadb.api.proxy.SealedEntityReferenceProxy;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.RequireConstraint;
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
import io.evitadb.api.requestResponse.cdc.ChangeCapturePublisher;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCaptureRequest;
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
import io.evitadb.api.requestResponse.progress.Progress;
import io.evitadb.api.requestResponse.progress.ProgressRecord;
import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.CatalogSchemaEditor;
import io.evitadb.api.requestResponse.schema.CatalogSchemaEditor.CatalogSchemaBuilder;
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
import io.evitadb.api.requestResponse.system.StoredVersion;
import io.evitadb.api.task.Task;
import io.evitadb.dataType.DataChunk;
import io.evitadb.dataType.Scope;
import io.evitadb.driver.cdc.ClientChangeCatalogCaptureProcessor;
import io.evitadb.driver.config.EvitaClientConfiguration;
import io.evitadb.driver.exception.EvitaClientServerCallException;
import io.evitadb.driver.exception.EvitaClientTimedOutException;
import io.evitadb.driver.interceptor.ClientSessionInterceptor.SessionIdHolder;
import io.evitadb.driver.requestResponse.schema.ClientCatalogSchemaDecorator;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter;
import io.evitadb.externalApi.grpc.generated.*;
import io.evitadb.externalApi.grpc.generated.EvitaSessionServiceGrpc.EvitaSessionServiceFutureStub;
import io.evitadb.externalApi.grpc.generated.EvitaSessionServiceGrpc.EvitaSessionServiceStub;
import io.evitadb.externalApi.grpc.generated.GrpcBackupCatalogRequest.Builder;
import io.evitadb.externalApi.grpc.query.QueryConverter;
import io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter;
import io.evitadb.externalApi.grpc.requestResponse.ResponseConverter;
import io.evitadb.externalApi.grpc.requestResponse.cdc.ChangeCaptureConverter;
import io.evitadb.externalApi.grpc.requestResponse.data.EntityConverter;
import io.evitadb.externalApi.grpc.requestResponse.data.EntityConverter.TypeConverter;
import io.evitadb.externalApi.grpc.requestResponse.data.mutation.DelegatingEntityMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.CatalogSchemaConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.EntitySchemaConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.DelegatingLocalCatalogSchemaMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutationConverter;
import io.evitadb.utils.Assert;
import io.evitadb.utils.ReflectionLookup;
import io.grpc.ClientCall;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.StreamObserver;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.api.requestResponse.schema.ClassSchemaAnalyzer.extractEntityTypeFromClass;
import static io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter.toGrpcOffsetDateTime;
import static io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter.toOffsetDateTime;
import static io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter.toTaskStatus;
import static io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter.toUuid;
import static io.evitadb.externalApi.grpc.requestResponse.cdc.ChangeCaptureConverter.toGrpcChangeCaptureRequest;
import static io.evitadb.externalApi.grpc.requestResponse.data.EntityConverter.SEALED_ENTITY_TYPE_CONVERTER;
import static io.evitadb.utils.Assert.isPremiseValid;
import static io.evitadb.utils.Assert.isTrue;
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
	private static final Scope[] LIVE_SCOPE_ONLY = {Scope.LIVE};

	/**
	 * Evita instance this session is connected to.
	 */
	@Getter private final EvitaClient evita;
	/**
	 * Executor service used for asynchronous operations.
	 */
	private final ExecutorService executor;
	/**
	 * Service class used for tracking tasks on the server side.
	 */
	private final EvitaClientManagement management;
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
	 * Entity session service that works with futures.
	 */
	private final EvitaSessionServiceFutureStub evitaSessionServiceFutureStub;
	/**
	 * Entity session service.
	 */
	private final EvitaSessionServiceStub evitaSessionServiceStub;
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
	 * Contains unique catalog id that doesn't change with catalog schema changes - such as renaming.
	 * The id is assigned to the catalog when it is created and never changes.
	 */
	private final UUID catalogId;
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
	 * Contains commit progress that is initialized only if the client session is closed with requesting transaction
	 * progress updates.
	 */
	private CommitProgressRecord commitProgress;
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
	 * Current timeout for the session.
	 */
	private final LinkedList<Timeout> callTimeout = new LinkedList<>();
	/**
	 * Future that is instantiated when the session is closed. When initialized, subsequent calls of the close method
	 * will return the same future. When the future is non-null any calls after {@link #close()} method has been called.
	 */
	private CompletableFuture<CommitVersions> closedFuture;
	/**
	 * Timestamp of the last session activity (call).
	 */
	private long lastCall;

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

	/**
	 * Converts a given entity of type {@code T} into an {@link EntityReference}.
	 *
	 * @param <T>    The type of the entity, which must be a {@link Serializable}.
	 * @param entity The entity to be converted. Must not be null.
	 * @return An {@link EntityReference} representing the given entity.
	 * @throws EvitaInvalidUsageException if the entity type is unsupported.
	 */
	@Nonnull
	private static <T extends Serializable> EntityReference toEntityReference(@Nonnull T entity) {
		final EntityReference entityReference;
		if (entity instanceof EntityClassifier entityClassifier) {
			entityReference = new EntityReference(
				entityClassifier.getType(),
				Objects.requireNonNull(entityClassifier.getPrimaryKey())
			);
		} else if (entity instanceof SealedEntityProxy sealedEntityProxy) {
			entityReference = new EntityReference(
				sealedEntityProxy.entity().getType(),
				Objects.requireNonNull(sealedEntityProxy.entity().getPrimaryKey())
			);
		} else {
			throw new EvitaInvalidUsageException(
				"Unsupported entity type `" + entity.getClass() + "`! The class doesn't implement EntityClassifier nor represents a SealedEntityProxy!",
				"Unsupported entity type!"
			);
		}
		return entityReference;
	}

	public EvitaClientSession(
		@Nonnull EvitaClient evita,
		@Nonnull ExecutorService executor,
		@Nonnull EvitaClientManagement management,
		@Nonnull EvitaEntitySchemaCache schemaCache,
		@Nonnull GrpcClientBuilder grpcClientBuilder,
		@Nonnull String catalogName,
		@Nonnull CatalogState catalogState,
		@Nonnull UUID catalogId,
		@Nonnull UUID sessionId,
		@Nonnull CommitBehavior commitBehaviour,
		@Nonnull SessionTraits sessionTraits,
		@Nonnull Consumer<EvitaClientSession> onTerminationCallback,
		@Nonnull Timeout timeout
	) {
		this.evita = evita;
		this.executor = executor;
		this.management = management;
		this.reflectionLookup = evita.getReflectionLookup();
		this.proxyFactory = schemaCache.getProxyFactory();
		this.schemaCache = schemaCache;
		this.evitaSessionServiceFutureStub = grpcClientBuilder.build(EvitaSessionServiceFutureStub.class);
		this.evitaSessionServiceStub = grpcClientBuilder.build(EvitaSessionServiceStub.class);
		this.catalogName = catalogName;
		this.catalogState = catalogState;
		this.commitBehaviour = commitBehaviour;
		this.catalogId = catalogId;
		this.sessionId = sessionId;
		this.sessionTraits = sessionTraits;
		this.onTerminationCallback = onTerminationCallback;
		this.callTimeout.add(timeout);
	}

	@Nonnull
	@Override
	public UUID getId() {
		return this.sessionId;
	}

	@Nonnull
	@Override
	public UUID getCatalogId() {
		return this.catalogId;
	}

	@Nonnull
	@Override
	public SealedCatalogSchema getCatalogSchema() {
		assertActive();
		return this.schemaCache.getLatestCatalogSchema(
			this::fetchCatalogSchema,
			this.clientEntitySchemaAccessor
		);
	}

	@Nonnull
	@Override
	public String getCatalogName() {
		return this.catalogName;
	}

	@Nonnull
	@Override
	public CatalogState getCatalogState() {
		return this.catalogState;
	}

	@Override
	public long getCatalogVersion() {
		assertActive();
		return this.schemaCache.getLastKnownCatalogVersion();
	}

	@Override
	public boolean isActive() {
		return this.closedFuture == null;
	}

	@Nonnull
	@Override
	public Progress<CommitVersions> goLiveAndCloseWithProgress(@Nullable IntConsumer progressObserver) {
		assertActive();

		final ProgressRecord<CommitVersions> goLiveProgress = new ProgressRecord<>(
			"Making catalog `" + this.catalogName + "` alive",
			progressObserver
		);
		executeWithAsyncEvitaSessionService(
			evitaSessionService -> {
				final StreamObserver<GrpcGoLiveAndCloseWithProgressResponse> observer = new StreamObserver<>() {
					private long catalogVersion = -1;
					private int catalogSchemaVersion = -1;

					@Override
					public void onNext(GrpcGoLiveAndCloseWithProgressResponse grpcResponse) {
						goLiveProgress.updatePercentCompleted(
							grpcResponse.getProgressInPercent()
						);

						this.catalogVersion = grpcResponse.getCatalogVersion();
						this.catalogSchemaVersion = grpcResponse.getCatalogSchemaVersion();

						if (progressObserver != null) {
							progressObserver.accept(grpcResponse.getProgressInPercent());
						}
					}

					@Override
					public void onError(Throwable throwable) {
						goLiveProgress.completeExceptionally(throwable);
					}

					@Override
					public void onCompleted() {
						goLiveProgress.complete(
							new CommitVersions(this.catalogVersion, this.catalogSchemaVersion)
						);

						if (this.catalogVersion > -1 && this.catalogSchemaVersion > -1) {
							EvitaClientSession.this.schemaCache.updateLastKnownCatalogVersion(
								this.catalogVersion, this.catalogSchemaVersion
							);
						}
					}
				};
				evitaSessionService.goLiveAndCloseWithProgress(
					Empty.newBuilder().build(),
					observer
				);
				return null;
			}
		);

		return goLiveProgress;
	}

	@Nonnull
	@Override
	public CompletionStage<CommitVersions> closeNow(@Nonnull CommitBehavior commitBehaviour) {
		if (isActive()) {
			final CompletableFuture<CommitVersions> result = closeInternally();
			executeWithAsyncEvitaSessionService(
				evitaSessionService -> {
					final StreamObserver<GrpcCloseResponse> observer = new StreamObserver<>() {
						@Override
						public void onNext(GrpcCloseResponse grpcResponse) {
							result.complete(
								new CommitVersions(
									grpcResponse.getCatalogVersion(),
									grpcResponse.getCatalogSchemaVersion()
								)
							);
							EvitaClientSession.this.schemaCache.updateLastKnownCatalogVersion(
								grpcResponse.getCatalogVersion(), grpcResponse.getCatalogSchemaVersion()
							);
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
							.setCatalogName(this.catalogName)
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
	public ChangeCapturePublisher<ChangeCatalogCapture> registerChangeCatalogCapture(@Nonnull ChangeCatalogCaptureRequest request) {
		//noinspection unchecked
		return (ChangeCapturePublisher<ChangeCatalogCapture>) this.evita.activePublishers.compute(
			request,
			(theRequest, existingInstance) ->
				existingInstance == null || existingInstance.isClosed() ?
					new ClientChangeCatalogCaptureProcessor(
						this.evita.getConfiguration().changeCaptureQueueSize(),
						this.executor,
						subscriber -> executeWithAsyncEvitaSessionService(
							evitaService -> {
								evitaService.registerChangeCatalogCapture(
									ChangeCaptureConverter.toGrpcChangeCatalogCaptureRequest((ChangeCatalogCaptureRequest)theRequest),
									subscriber
								);
								return null;
							}
						),
						publisher -> this.evita.activePublishers.remove(theRequest, publisher)
					) : existingInstance
		);
	}

	@Nonnull
	@Override
	public CommitProgress closeNowWithProgress() {
		if (isActive()) {
			final CompletableFuture<CommitVersions> result = closeInternally();
			this.commitProgress = new CommitProgressRecord();
			this.commitProgress.on(this.commitBehaviour).thenAccept(result::complete);
			executeWithAsyncEvitaSessionService(
				evitaSessionService -> {
					final StreamObserver<GrpcCloseWithProgressResponse> observer = new StreamObserver<>() {
						@Override
						public void onNext(GrpcCloseWithProgressResponse grpcResponse) {
							final CommitVersions commitVersions = new CommitVersions(
								grpcResponse.getCatalogVersion(),
								grpcResponse.getCatalogSchemaVersion()
							);
							final GrpcTransactionPhase finishedPhase = grpcResponse.getFinishedPhase();
							switch (finishedPhase) {
								case CONFLICTS_RESOLVED -> EvitaClientSession.this.commitProgress.complete(CommitBehavior.WAIT_FOR_CONFLICT_RESOLUTION, commitVersions);
								case WAL_PERSISTED -> EvitaClientSession.this.commitProgress.complete(CommitBehavior.WAIT_FOR_WAL_PERSISTENCE, commitVersions);
								case CHANGES_VISIBLE -> {
									EvitaClientSession.this.schemaCache.updateLastKnownCatalogVersion(
										grpcResponse.getCatalogVersion(), grpcResponse.getCatalogSchemaVersion()
									);
									EvitaClientSession.this.commitProgress.complete(CommitBehavior.WAIT_FOR_CHANGES_VISIBLE, commitVersions);
								}
							}
						}

						@Override
						public void onError(Throwable throwable) {
							result.completeExceptionally(throwable);
						}

						@Override
						public void onCompleted() {

						}
					};
					evitaSessionService.closeWithProgress(
						GrpcCloseWithProgressRequest.newBuilder()
							.setCatalogName(this.catalogName)
							.build(),
						observer
					);
					return null;
				}
			);
		}
		return this.commitProgress;
	}

	@Override
	public int updateCatalogSchema(@Nonnull CatalogSchemaBuilder catalogSchemaBuilder) throws SchemaAlteringException {
		assertActive();
		return catalogSchemaBuilder
			.toMutation()
			.map(
				modifyMutation ->
					executeInTransactionIfPossible(
						session -> {
							final LocalCatalogSchemaMutation[] schemaMutations = modifyMutation.getSchemaMutations();
							final GrpcUpdateCatalogSchemaRequest.Builder builder = GrpcUpdateCatalogSchemaRequest.newBuilder();
							final GrpcUpdateCatalogSchemaRequest request = builder
								.addAllSchemaMutations(
									Arrays.stream(schemaMutations)
									      .map(
										      DelegatingLocalCatalogSchemaMutationConverter.INSTANCE::convert)
									      .collect(Collectors.toList())
								)
								.build();
							final GrpcUpdateCatalogSchemaResponse response = executeWithBlockingEvitaSessionService(
								evitaSessionService -> evitaSessionService.updateCatalogSchema(request)
							);
							this.schemaCache.analyzeMutations(schemaMutations);
							return response.getVersion();
						}
					)
			)
			.orElseGet(
				() -> this.schemaCache.getLatestCatalogSchema(
					this::fetchCatalogSchema,
					this.clientEntitySchemaAccessor
				).version()
			);
	}

	@Nonnull
	@Override
	public SealedCatalogSchema updateAndFetchCatalogSchema(
		@Nonnull CatalogSchemaBuilder catalogSchemaBuilder
	) throws SchemaAlteringException {
		assertActive();
		return catalogSchemaBuilder
			.toMutation()
			.map(
				modifyMutation ->
					executeInTransactionIfPossible(
						session -> {
							final LocalCatalogSchemaMutation[] schemaMutations = modifyMutation.getSchemaMutations();
							final GrpcUpdateCatalogSchemaRequest.Builder builder = GrpcUpdateCatalogSchemaRequest.newBuilder();
							final GrpcUpdateCatalogSchemaRequest request = builder
								.addAllSchemaMutations(
									Arrays.stream(schemaMutations)
									      .map(
										      DelegatingLocalCatalogSchemaMutationConverter.INSTANCE::convert)
									      .collect(Collectors.toList())
								)
								.build();
							final GrpcUpdateAndFetchCatalogSchemaResponse response = executeWithBlockingEvitaSessionService(
								evitaSessionService -> evitaSessionService.updateAndFetchCatalogSchema(request)
							);
							this.schemaCache.analyzeMutations(schemaMutations);
							return (SealedCatalogSchema) new ClientCatalogSchemaDecorator(
								CatalogSchemaConverter.convert(response.getCatalogSchema(), this.clientEntitySchemaAccessor),
								this.clientEntitySchemaAccessor
							);
						}
					)
			)
			.orElseGet(
				() -> this.schemaCache.getLatestCatalogSchema(
					this::fetchCatalogSchema,
					this.clientEntitySchemaAccessor
				)
			);
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
			this.schemaCache.setLatestEntitySchema(theSchema);
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
				final ClassSchemaAnalyzer classSchemaAnalyzer = new ClassSchemaAnalyzer(modelClass, this.reflectionLookup);
				final CatalogSchemaEditor.CatalogSchemaBuilder catalogBuilder = session.getCatalogSchema().openForWrite();
				final AnalysisResult analysisResult = classSchemaAnalyzer.analyze(this, catalogBuilder);
				updateCatalogSchema(analysisResult.mutations());
				return getEntitySchemaOrThrowException(analysisResult.entityType());
			}
		);
	}

	@Nonnull
	@Override
	public SealedEntitySchema defineEntitySchemaFromModelClass(@Nonnull Class<?> modelClass, @Nonnull SchemaPostProcessor postProcessor) {
		assertActive();
		return executeInTransactionIfPossible(
			session -> {
				final ClassSchemaAnalyzer classSchemaAnalyzer = new ClassSchemaAnalyzer(modelClass, this.reflectionLookup, postProcessor);
				final CatalogSchemaEditor.CatalogSchemaBuilder catalogBuilder = session.getCatalogSchema().openForWrite();
				final AnalysisResult analysisResult = classSchemaAnalyzer.analyze(this, catalogBuilder);
				if (postProcessor instanceof SchemaPostProcessorCapturingResult capturingResult) {
					capturingResult.captureResult(analysisResult.mutations());
				}
				updateCatalogSchema(analysisResult.mutations());
				return getEntitySchemaOrThrowException(analysisResult.entityType());
			}
		);
	}

	@Nonnull
	@Override
	public Optional<SealedEntitySchema> getEntitySchema(@Nonnull String entityType) {
		assertActive();
		return this.schemaCache.getLatestEntitySchema(entityType, this::fetchEntitySchema, this::getCatalogSchema);
	}

	@Nonnull
	@Override
	public Optional<SealedEntitySchema> getEntitySchema(@Nonnull Class<?> modelClass) throws EntityClassInvalidException {
		return getEntitySchema(
			extractEntityTypeFromClass(modelClass, this.reflectionLookup)
				.orElseThrow(() -> new CollectionNotFoundException(modelClass))
		);
	}

	@Nonnull
	@Override
	public SealedEntitySchema getEntitySchemaOrThrowException(@Nonnull String entityType) throws CollectionNotFoundException {
		assertActive();
		return getEntitySchema(entityType)
			.orElseThrow(() -> new CollectionNotFoundException(entityType));
	}

	@Nonnull
	@Override
	public SealedEntitySchema getEntitySchemaOrThrowException(@Nonnull Class<?> modelClass) throws CollectionNotFoundException, EntityClassInvalidException {
		return getEntitySchemaOrThrowException(
			extractEntityTypeFromClass(modelClass, this.reflectionLookup)
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
			extractEntityTypeFromClass(expectedType, this.reflectionLookup).orElse(null)
		);
		return queryOneInternal(query, expectedType, evitaRequest, this::createEntityProxy);
	}

	@Nonnull
	@Override
	public <S extends Serializable> List<S> queryList(@Nonnull Query query, @Nonnull Class<S> expectedType) throws UnexpectedResultException, InstanceTerminatedException {
		final EvitaRequest evitaRequest = new EvitaRequest(
			query,
			OffsetDateTime.now(),
			expectedType,
			extractEntityTypeFromClass(expectedType, this.reflectionLookup).orElse(null)
		);
		return queryListInternal(query, expectedType, evitaRequest, this::createEntityProxy);
	}

	@Nonnull
	@Override
	public <S extends Serializable, T extends EvitaResponse<S>> T query(@Nonnull Query query, @Nonnull Class<S> expectedType) throws UnexpectedResultException, InstanceTerminatedException {
		assertActive();
		final Query finalQuery = assertRequestMakesSenseAndEntityTypeIsPresent(query, expectedType, this.reflectionLookup);
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
			final int[] primaryKeys = grpcResponse.getRecordPage().getEntityReferencesList().stream()
				.mapToInt(GrpcEntityReference::getPrimaryKey)
				.toArray();
			final DataChunk<EntityReference> recordPage = ResponseConverter.convertToDataChunk(
				grpcResponse,
				grpcRecordPage -> EntityConverter.toEntityReferences(grpcRecordPage.getEntityReferencesList())
			);
			//noinspection unchecked
			return (T) new EvitaEntityReferenceResponse(
				finalQuery, recordPage, primaryKeys,
				getEvitaResponseExtraResults(
					grpcResponse,
					new EvitaRequest(
						finalQuery,
						OffsetDateTime.now(),
						EntityReference.class,
						null
					)
				)
			);
		} else {
			final String expectedEntityType = ofNullable(finalQuery.getCollection())
				.map(io.evitadb.api.query.head.Collection::getEntityType)
				.orElse(null);

			final int[] primaryKeys;
			final DataChunk<S> recordPage;
			if (grpcResponse.getRecordPage().getBinaryEntitiesList().isEmpty()) {
				primaryKeys = grpcResponse.getRecordPage().getSealedEntitiesList().stream()
					.mapToInt(GrpcSealedEntity::getPrimaryKey)
					.toArray();
				// convert to Sealed entities
				recordPage = ResponseConverter.convertToDataChunk(
					grpcResponse,
					grpcRecordPage -> EntityConverter.toEntities(
						grpcRecordPage.getSealedEntitiesList(),
						new EvitaRequest(
							finalQuery,
							OffsetDateTime.now(),
							expectedType,
							expectedEntityType
						),
						(entityType, schemaVersion) -> this.schemaCache.getEntitySchemaOrThrowException(
							entityType, schemaVersion, this::fetchEntitySchema, this::getCatalogSchema
						),
						expectedType,
						this::createEntityProxy
					)
				);
			} else {
				primaryKeys = grpcResponse.getRecordPage().getBinaryEntitiesList().stream()
					.mapToInt(GrpcBinaryEntity::getPrimaryKey)
					.toArray();
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
				finalQuery, recordPage, primaryKeys,
				getEvitaResponseExtraResults(
					grpcResponse,
					new EvitaRequest(
						finalQuery,
						OffsetDateTime.now(),
						expectedType,
						expectedEntityType
					)
				)
			);
		}
	}

	@Nonnull
	@Override
	public Optional<SealedEntity> getEntity(@Nonnull String entityType, int primaryKey, EntityContentRequire... require) {
		return getEntity(entityType, primaryKey, LIVE_SCOPE_ONLY, require);
	}

	@Nonnull
	@Override
	public Optional<SealedEntity> getEntity(@Nonnull String entityType, int primaryKey, @Nonnull Scope[] scopes, EntityContentRequire... require) {
		isTrue(scopes.length > 0, "At least one scope must be provided!");

		final EvitaRequest evitaRequest = new EvitaRequest(
			Query.query(
				collection(entityType),
				filterBy(
					entityPrimaryKeyInSet(primaryKey),
					scope(scopes)
				),
				require(
					entityFetch(require)
				)
			),
			OffsetDateTime.now(),
			SealedEntity.class,
			null
		);
		return getEntityInternal(entityType, SealedEntity.class, SEALED_ENTITY_TYPE_CONVERTER, primaryKey, evitaRequest, require);
	}

	@Nonnull
	@Override
	public <T extends Serializable> Optional<T> getEntity(@Nonnull Class<T> expectedType, int primaryKey, @Nonnull Scope[] scope, EntityContentRequire... require) throws EntityClassInvalidException {
		final String entityType = extractEntityTypeFromClass(expectedType, this.reflectionLookup)
			.orElseThrow(() -> new CollectionNotFoundException(expectedType));
		final EvitaRequest evitaRequest = new EvitaRequest(
			Query.query(
				collection(entityType),
				filterBy(
					scope(scope)
				),
				require(
					entityFetch(require)
				)
			),
			OffsetDateTime.now(),
			expectedType,
			entityType
		);
		return getEntityInternal(entityType, expectedType, this::createEntityProxy, primaryKey, evitaRequest, require);
	}

	@Nonnull
	@Override
	public <T extends Serializable> T enrichEntity(@Nonnull T partiallyLoadedEntity, EntityContentRequire... require) {
		assertActive();

		/* TOBEDONE https://github.com/FgForrest/evitaDB/issues/13 */
		final EntityReference entityReference = toEntityReference(partiallyLoadedEntity);
		final Class<?> expectedType = partiallyLoadedEntity instanceof SealedEntityProxy sealedEntityProxy ?
			sealedEntityProxy.getProxyClass() : partiallyLoadedEntity.getClass();
		final EvitaRequest evitaRequest = new EvitaRequest(
			Query.query(
				collection(entityReference.type()),
				require(
					entityFetch(require)
				)
			),
			OffsetDateTime.now(),
			expectedType,
			extractEntityTypeFromClass(expectedType, this.reflectionLookup)
				.orElse(null)
		);

		//noinspection unchecked
		return (T) getEntityInternal(entityReference.type(), expectedType, this::createEntityProxy, entityReference.primaryKey(), evitaRequest, require)
			.orElseThrow(() -> new EntityAlreadyRemovedException(entityReference.type(), entityReference.primaryKey()));
	}

	@Nonnull
	@Override
	public <T extends Serializable> T enrichOrLimitEntity(@Nonnull T partiallyLoadedEntity, EntityContentRequire... require) {
		assertActive();

		/* TOBEDONE https://github.com/FgForrest/evitaDB/issues/13 */
		final EntityReference entityReference = toEntityReference(partiallyLoadedEntity);
		final Class<?> expectedType = partiallyLoadedEntity instanceof SealedEntityProxy sealedEntityProxy ?
			sealedEntityProxy.getProxyClass() : partiallyLoadedEntity.getClass();

		final EvitaRequest evitaRequest = new EvitaRequest(
			Query.query(
				collection(entityReference.type()),
				require(
					entityFetch(require)
				)
			),
			OffsetDateTime.now(),
			expectedType,
			extractEntityTypeFromClass(expectedType, this.reflectionLookup)
				.orElse(null)
		);

		//noinspection unchecked
		return (T) getEntityInternal(entityReference.type(), expectedType, this::createEntityProxy, entityReference.primaryKey(), evitaRequest, require)
			.orElseThrow(() -> new EntityAlreadyRemovedException(entityReference.type(), entityReference.primaryKey()));
	}

	@Override
	public int updateCatalogSchema(@Nonnull LocalCatalogSchemaMutation... schemaMutation) throws SchemaAlteringException {
		assertActive();
		return executeInTransactionIfPossible(session -> {
			final List<GrpcLocalCatalogSchemaMutation> grpcSchemaMutations = Arrays.stream(schemaMutation)
				.map(DelegatingLocalCatalogSchemaMutationConverter.INSTANCE::convert)
				.toList();

			final GrpcUpdateCatalogSchemaRequest request = GrpcUpdateCatalogSchemaRequest.newBuilder()
				.addAllSchemaMutations(grpcSchemaMutations)
				.build();

			final GrpcUpdateCatalogSchemaResponse response = executeWithBlockingEvitaSessionService(
				evitaSessionService -> evitaSessionService.updateCatalogSchema(request)
			);

			this.schemaCache.analyzeMutations(schemaMutation);
			return response.getVersion();
		});

	}

	@Nonnull
	@Override
	public SealedCatalogSchema updateAndFetchCatalogSchema(@Nonnull LocalCatalogSchemaMutation... schemaMutation) throws SchemaAlteringException {
		assertActive();
		return executeInTransactionIfPossible(session -> {
			final List<GrpcLocalCatalogSchemaMutation> grpcSchemaMutations = Arrays.stream(schemaMutation)
				.map(DelegatingLocalCatalogSchemaMutationConverter.INSTANCE::convert)
				.toList();

			final GrpcUpdateCatalogSchemaRequest request = GrpcUpdateCatalogSchemaRequest.newBuilder()
				.addAllSchemaMutations(grpcSchemaMutations)
				.build();

			final GrpcUpdateAndFetchCatalogSchemaResponse response = executeWithBlockingEvitaSessionService(
				evitaSessionService -> evitaSessionService.updateAndFetchCatalogSchema(request)
			);

			final CatalogSchema updatedCatalogSchema = CatalogSchemaConverter.convert(
				response.getCatalogSchema(), this.clientEntitySchemaAccessor
			);
			final SealedCatalogSchema updatedSchema = new ClientCatalogSchemaDecorator(updatedCatalogSchema, this.clientEntitySchemaAccessor);
			this.schemaCache.analyzeMutations(schemaMutation);
			this.schemaCache.setLatestCatalogSchema(updatedCatalogSchema);
			return updatedSchema;
		});
	}

	@Override
	public int updateEntitySchema(@Nonnull ModifyEntitySchemaMutation schemaMutation) throws SchemaAlteringException {
		assertActive();
		return executeInTransactionIfPossible(session -> {
			final GrpcModifyEntitySchemaMutation grpcSchemaMutation = ModifyEntitySchemaMutationConverter.INSTANCE.convert(schemaMutation);
			final GrpcUpdateEntitySchemaRequest request = GrpcUpdateEntitySchemaRequest.newBuilder()
				.setSchemaMutation(grpcSchemaMutation)
				.build();
			final GrpcUpdateEntitySchemaResponse response = executeWithBlockingEvitaSessionService(
				evitaSessionService -> evitaSessionService.updateEntitySchema(request)
			);
			this.schemaCache.analyzeMutations(schemaMutation);
			return response.getVersion();
		});
	}

	@Nonnull
	@Override
	public SealedEntitySchema updateAndFetchEntitySchema(@Nonnull ModifyEntitySchemaMutation schemaMutation) throws SchemaAlteringException {
		assertActive();
		return executeInTransactionIfPossible(session -> {
			final GrpcModifyEntitySchemaMutation grpcSchemaMutation = ModifyEntitySchemaMutationConverter.INSTANCE.convert(schemaMutation);
			final GrpcUpdateEntitySchemaRequest request = GrpcUpdateEntitySchemaRequest.newBuilder()
				.setSchemaMutation(grpcSchemaMutation)
				.build();

			final GrpcUpdateAndFetchEntitySchemaResponse response = executeWithBlockingEvitaSessionService(
				evitaSessionService -> evitaSessionService.updateAndFetchEntitySchema(request)
			);

			final EntitySchema updatedSchema = EntitySchemaConverter.convert(response.getEntitySchema());
			this.schemaCache.analyzeMutations(schemaMutation);
			this.schemaCache.setLatestEntitySchema(updatedSchema);
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
				this.schemaCache.removeLatestEntitySchema(entityType);
				return grpcResponse.getDeleted();
			}
		);
	}

	@Override
	public boolean deleteCollection(@Nonnull Class<?> modelClass) throws EntityClassInvalidException {
		return deleteCollection(
			extractEntityTypeFromClass(modelClass, this.reflectionLookup)
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
				this.schemaCache.removeLatestEntitySchema(entityType);
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
				this.schemaCache.removeLatestEntitySchema(entityTypeToBeReplaced);
				this.schemaCache.removeLatestEntitySchema(entityTypeToBeReplacedWith);
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
					entitySchema = getEntitySchemaOrThrowException(entityType);
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
			extractEntityTypeFromClass(expectedType, this.reflectionLookup)
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
					entitySchema = getEntitySchemaOrThrowException(entityType);
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
			extractEntityTypeFromClass(expectedType, this.reflectionLookup)
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
					return new EntityReference(entity.getType(), Objects.requireNonNull(entity.getPrimaryKey()));
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
					return new EntityReference(entity.getType(), Objects.requireNonNull(entity.getPrimaryKey()));
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
			final GrpcEntityMutation grpcEntityMutation = DelegatingEntityMutationConverter.INSTANCE.convert(entityMutation);
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
				() -> getEntity(entityBuilder.getType(), Objects.requireNonNull(entityBuilder.getPrimaryKey()), require)
					.orElseThrow(() -> new EvitaInvalidUsageException("Entity `" + entityBuilder.getType() + "` with id `" + entityBuilder.getPrimaryKey() + "` doesn't exist!"))
			);
	}

	@Nonnull
	@Override
	public SealedEntity upsertAndFetchEntity(@Nonnull EntityMutation entityMutation, EntityContentRequire... require) {
		assertActive();
		return executeInTransactionIfPossible(session -> {
			final GrpcEntityMutation grpcEntityMutation = DelegatingEntityMutationConverter.INSTANCE.convert(entityMutation);
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
				entity -> this.schemaCache.getEntitySchemaOrThrowException(
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
					null
				),
				grpcResponse.getEntity(),
				SealedEntity.class,
				SEALED_ENTITY_TYPE_CONVERTER
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
			extractEntityTypeFromClass(modelClass, this.reflectionLookup)
				.orElseThrow(() -> new CollectionNotFoundException(modelClass)),
			primaryKey
		);
	}

	@Nonnull
	@Override
	public Optional<SealedEntity> deleteEntity(@Nonnull String entityType, int primaryKey, EntityContentRequire... require) {
		return deleteEntityInternal(entityType, SealedEntity.class, SEALED_ENTITY_TYPE_CONVERTER, primaryKey, require);
	}

	@Nonnull
	@Override
	public <T extends Serializable> Optional<T> deleteEntity(@Nonnull Class<T> modelClass, int primaryKey, EntityContentRequire... require) throws EntityClassInvalidException {
		return deleteEntityInternal(
			extractEntityTypeFromClass(modelClass, this.reflectionLookup)
				.orElseThrow(() -> new CollectionNotFoundException(modelClass)),
			modelClass, this::createEntityProxy, primaryKey, require
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
		return deleteEntityHierarchyInternal(entityType, SealedEntity.class, SEALED_ENTITY_TYPE_CONVERTER, primaryKey, require);
	}

	@Nonnull
	@Override
	public <T extends Serializable> DeletedHierarchy<T> deleteEntityAndItsHierarchy(@Nonnull Class<T> modelClass, int primaryKey, EntityContentRequire... require) throws EvitaInvalidUsageException {
		return deleteEntityHierarchyInternal(
			extractEntityTypeFromClass(modelClass, this.reflectionLookup)
				.orElseThrow(() -> new CollectionNotFoundException(modelClass)),
			modelClass, this::createEntityProxy, primaryKey, require
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
				null
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
						entity -> this.schemaCache.getEntitySchemaOrThrowException(
							entity.getEntityType(), entity.getSchemaVersion(), this::fetchEntitySchema, this::getCatalogSchema
						),
						evitaRequest,
						it,
						SealedEntity.class,
						SEALED_ENTITY_TYPE_CONVERTER
					)
				)
				.toArray(SealedEntity[]::new);
		});
	}

	@Override
	public boolean archiveEntity(@Nonnull String entityType, int primaryKey) {
		assertActive();
		return executeInTransactionIfPossible(session -> {
			final GrpcArchiveEntityResponse grpcResponse = executeWithBlockingEvitaSessionService(
				evitaSessionService ->
					evitaSessionService.archiveEntity(
						GrpcArchiveEntityRequest
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
	public boolean archiveEntity(@Nonnull Class<?> modelClass, int primaryKey) throws EntityClassInvalidException {
		return archiveEntity(
			extractEntityTypeFromClass(modelClass, this.reflectionLookup)
				.orElseThrow(() -> new CollectionNotFoundException(modelClass)),
			primaryKey
		);
	}

	@Nonnull
	@Override
	public Optional<SealedEntity> archiveEntity(@Nonnull String entityType, int primaryKey, EntityContentRequire... require) {
		return archiveEntityInternal(entityType, SealedEntity.class, SEALED_ENTITY_TYPE_CONVERTER, primaryKey, require);
	}

	@Nonnull
	@Override
	public <T extends Serializable> Optional<T> archiveEntity(@Nonnull Class<T> modelClass, int primaryKey, EntityContentRequire... require) throws EntityClassInvalidException {
		return archiveEntityInternal(
			extractEntityTypeFromClass(modelClass, this.reflectionLookup)
				.orElseThrow(() -> new CollectionNotFoundException(modelClass)),
			modelClass, this::createEntityProxy, primaryKey, require
		);
	}

	@Override
	public boolean restoreEntity(@Nonnull String entityType, int primaryKey) {
		assertActive();
		return executeInTransactionIfPossible(session -> {
			final GrpcRestoreEntityResponse grpcResponse = executeWithBlockingEvitaSessionService(
				evitaSessionService ->
					evitaSessionService.restoreEntity(
						GrpcRestoreEntityRequest
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
	public boolean restoreEntity(@Nonnull Class<?> modelClass, int primaryKey) throws EntityClassInvalidException {
		return restoreEntity(
			extractEntityTypeFromClass(modelClass, this.reflectionLookup)
				.orElseThrow(() -> new CollectionNotFoundException(modelClass)),
			primaryKey
		);
	}

	@Nonnull
	@Override
	public Optional<SealedEntity> restoreEntity(@Nonnull String entityType, int primaryKey, EntityContentRequire... require) {
		return restoreEntityInternal(entityType, SealedEntity.class, SEALED_ENTITY_TYPE_CONVERTER, primaryKey, require);
	}

	@Nonnull
	@Override
	public <T extends Serializable> Optional<T> restoreEntity(@Nonnull Class<T> modelClass, int primaryKey, EntityContentRequire... require) throws EntityClassInvalidException {
		return restoreEntityInternal(
			extractEntityTypeFromClass(modelClass, this.reflectionLookup)
				.orElseThrow(() -> new CollectionNotFoundException(modelClass)),
			modelClass, this::createEntityProxy, primaryKey, require
		);
	}

	@Nonnull
	@Override
	public StoredVersion getCatalogVersionAt(@Nullable OffsetDateTime moment) throws TemporalDataNotAvailableException {
		assertActive();
		final GrpcCatalogVersionAtResponse grpcResponse = executeWithBlockingEvitaSessionService(
			session -> {
				final GrpcCatalogVersionAtRequest.Builder builder = GrpcCatalogVersionAtRequest.newBuilder();
				if (moment != null) {
					builder.setTheMoment(toGrpcOffsetDateTime(moment));
				}
				return session.getCatalogVersionAt(
					builder.build()
				);
			}
		);
		return new StoredVersion(
			grpcResponse.getVersion(),
			toOffsetDateTime(grpcResponse.getIntroducedAt())
		);
	}

	/**
	 * {@inheritDoc}
	 *
	 * If the stream is closed prematurely the server stream is cancelled and the server is notified about it.
	 *
	 * @param request request that specifies the criteria for the changes to be returned
	 */
	@Nonnull
	@Override
	public Stream<ChangeCatalogCapture> getMutationsHistory(@Nonnull ChangeCatalogCaptureRequest request) {
		assertActive();

		// Observer reference that needs to be used for closing the stream
		final MutationsStreamObserver streamObserver = new MutationsStreamObserver();
		// Call reference is needed for cancelling stream on the server side
		final AtomicReference<ClientCall<?, ?>> callRef = new AtomicReference<>();

		executeWithAsyncEvitaSessionService(
			session -> {
				final ClientCall<GetMutationsHistoryRequest, GetMutationsHistoryResponse> call = session.getChannel().newCall(
					EvitaSessionServiceGrpc.getGetMutationsHistoryMethod(),
					session.getCallOptions()
				);
				callRef.set(call);

				ClientCalls.asyncServerStreamingCall(
					call,
					toGrpcChangeCaptureRequest(request),
					streamObserver
				);
				return null;
			}
		);

		// now we wrap the observer to a blocking split iterator that will read from it
		return StreamSupport.stream(
				new ChangeCatalogCaptureSpliterator(streamObserver),
				false
			)
			.onClose(() -> {
				// when stream is closed and the observer is not completed (hasn't received the end of the stream)
				// cancel the stream on the server side, and complete it locally
				if (!streamObserver.isCompleted()) {
					callRef.get().cancel("Stream closed by the client", null);
					streamObserver.onCompleted();
				}
			});
	}

	@Nonnull
	@Override
	public Task<?, FileForFetch> backupCatalog(
		@Nullable OffsetDateTime pastMoment,
		@Nullable Long catalogVersion,
		boolean includingWAL
	) throws TemporalDataNotAvailableException {
		assertActive();
		return executeInTransactionIfPossible(session -> {
			final GrpcBackupCatalogResponse grpcResponse = executeWithBlockingEvitaSessionService(
				evitaSessionService ->
				{
					final Builder builder = GrpcBackupCatalogRequest.newBuilder();
					ofNullable(pastMoment)
						.ifPresent(pm -> builder.setPastMoment(EvitaDataTypesConverter.toGrpcOffsetDateTime(pm)));
					ofNullable(catalogVersion)
						.ifPresent(cv -> builder.setCatalogVersion(Int64Value.newBuilder().setValue(cv).build()));
					return evitaSessionService.backupCatalog(
						builder
							.setIncludingWAL(includingWAL)
							.build()
					);
				}
			);

			//noinspection unchecked
			return (Task<?, FileForFetch>) this.management.createTask(
				toTaskStatus(grpcResponse.getTaskStatus())
			);
		});
	}

	@Nonnull
	@Override
	public Task<?, FileForFetch> fullBackupCatalog() {
		assertActive();
		return executeInTransactionIfPossible(session -> {
			final GrpcFullBackupCatalogResponse grpcResponse = executeWithBlockingEvitaSessionService(
				evitaSessionService -> evitaSessionService.fullBackupCatalog(
					Empty.newBuilder().build()
				)
			);

			//noinspection unchecked
			return (Task<?, FileForFetch>) this.management.createTask(
				toTaskStatus(grpcResponse.getTaskStatus())
			);
		});
	}

	@Nonnull
	@Override
	public Optional<UUID> getOpenedTransactionId() {
		assertActive();
		return ofNullable(this.transactionAccessor.get())
			.filter(EvitaClientTransaction::isClosed)
			.map(EvitaClientTransaction::getTransactionId);
	}

	@Override
	public boolean isRollbackOnly() {
		return getOpenedTransactionId().isPresent() && this.transactionAccessor.get().isRollbackOnly();
	}

	@Override
	public void setRollbackOnly() {
		assertActive();
		if (this.transactionAccessor.get() == null) {
			throw new UnexpectedTransactionStateException("No transaction has been opened!");
		}
		final EvitaClientTransaction transaction = this.transactionAccessor.get();
		transaction.setRollbackOnly();
	}

	@Override
	public boolean isReadOnly() {
		return !this.sessionTraits.isReadWrite();
	}

	@Nonnull
	@Override
	public CommitBehavior getCommitBehavior() {
		return this.commitBehaviour;
	}

	@Override
	public boolean isBinaryFormat() {
		return this.sessionTraits.isBinary();
	}

	@Override
	public boolean isDryRun() {
		return this.sessionTraits.isDryRun();
	}

	@Override
	public long getInactivityDurationInSeconds() {
		return (System.currentTimeMillis() - this.lastCall) / 1000;
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
		return this.schemaCache.getLatestCatalogSchema(
			() -> isActive() ?
				this.fetchCatalogSchema() :
				evita.queryCatalog(
					this.catalogName,
					session -> {
						return ((EvitaClientSession) session).fetchCatalogSchema();
					}
				),
			this.clientEntitySchemaAccessor
		);
	}

	/**
	 * Method internally closes the session
	 */
	@Nonnull
	public CompletableFuture<CommitVersions> closeInternally() {
		if (isActive()) {
			final CompletableFuture<CommitVersions> closeFuture = new CompletableFuture<>();
			// join both futures together and apply termination callback
			this.closedFuture = closeFuture.whenComplete((newCatalogVersion, throwable) -> {
				// then apply termination callbacks
				ofNullable(this.onTerminationCallback)
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
	 * @param lambda  logic to be executed
	 * @param timeout timeout value
	 * @param unit    time unit of the timeout
	 */
	@SuppressWarnings("unused")
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
	 * @param lambda  logic to be executed
	 * @param timeout timeout value
	 * @param unit    time unit of the timeout
	 * @param <T>     type of the result
	 * @return result of the lambda
	 */
	@SuppressWarnings("unused")
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
		return this.schemaCache.getLatestEntitySchemaIndex(
			this::getAllEntityTypes,
			this::fetchEntitySchema,
			this::getCatalogSchema
		);
	}

	@Nonnull
	private <T> Optional<T> getEntityInternal(
		@Nonnull String entityType,
		@Nonnull Class<T> expectedType,
		@Nonnull TypeConverter<T> typeConverter,
		int primaryKey,
		@Nonnull EvitaRequest evitaRequest,
		@Nullable RequireConstraint... require
	) {
		assertActive();

		final GrpcEntityRequest.Builder requestBuilder = GrpcEntityRequest
			.newBuilder()
			.setEntityType(entityType)
			.setPrimaryKey(primaryKey)
			.addAllScopes(
				evitaRequest.getScopes()
					.stream()
					.map(EvitaEnumConverter::toGrpcScope)
					.toList()
			);
		if (require != null) {
			final StringWithParameters stringWithParameters = PrettyPrintingVisitor.toStringWithParameterExtraction(require);
			requestBuilder
				.setRequire(stringWithParameters.query())
				.addAllPositionalQueryParams(
					stringWithParameters.parameters()
						.stream()
						.map(QueryConverter::convertQueryParam)
						.toList()
				);
		}

		final GrpcEntityResponse grpcResponse = executeWithBlockingEvitaSessionService(
			evitaSessionService ->
				evitaSessionService.getEntity(
					requestBuilder.build()
				)
		);

		return grpcResponse.hasEntity() ?
			Optional.of(
				EntityConverter.toEntity(
					entity -> this.schemaCache.getEntitySchemaOrThrowException(
						entity.getEntityType(), entity.getSchemaVersion(), this::fetchEntitySchema, this::getCatalogSchema
					),
					evitaRequest,
					grpcResponse.getEntity(),
					expectedType,
					typeConverter
				)
			) : Optional.empty();
	}

	@Nonnull
	private <S extends Serializable> List<S> queryListInternal(
		@Nonnull Query query,
		@Nonnull Class<S> expectedType,
		@Nonnull EvitaRequest evitaRequest,
		@Nonnull TypeConverter<S> typeConverter
	) {
		assertActive();
		final Query finalQuery = assertRequestMakesSenseAndEntityTypeIsPresent(query, expectedType, this.reflectionLookup);
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
					(entityType, schemaVersion) -> this.schemaCache.getEntitySchemaOrThrowException(
						entityType, schemaVersion, this::fetchEntitySchema, this::getCatalogSchema
					),
					expectedType,
					typeConverter
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
		final Timeout timeout = getCurrentTimeout();
		try {
			SessionIdHolder.setSessionId(getId().toString());
			return lambda.apply(this.evitaSessionServiceFutureStub.withDeadlineAfter(timeout.timeout(), timeout.timeoutUnit()))
				.get(timeout.timeout(), timeout.timeoutUnit());
		} catch (ExecutionException e) {
			final Throwable theException = e.getCause() == null ? e : e.getCause();
			throw EvitaClient.transformException(
				theException,
				() -> {
					// close session and rethrow
					final CompletableFuture<CommitVersions> future = closeInternally();
					future.completeExceptionally(theException);
				}
			);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new EvitaClientServerCallException("Server call interrupted.", e);
		} catch (TimeoutException e) {
			throw new EvitaClientTimedOutException(
				timeout.timeout(), timeout.timeoutUnit()
			);
		} finally {
			SessionIdHolder.reset();
		}
	}

	/**
	 * Retrieves the current {@link Timeout} for the call.
	 *
	 * @return the current Timeout for the call.
	 * @throws IllegalStateException if no timeout has been set for the current call.
	 */
	@Nonnull
	private Timeout getCurrentTimeout() {
		final Timeout timeout = this.callTimeout.peek();
		Assert.isPremiseValid(
			timeout != null,
			"No timeout has been set for the current call! There should be always a timeout present, this is a bug!"
		);
		return timeout;
	}

	/**
	 * Method that is called within the {@link EvitaClientSession} to apply the wanted logic on a channel retrieved
	 * from a channel pool.
	 *
	 * @param lambda function that holds a logic passed by the caller
	 */
	private <T> T executeWithAsyncEvitaSessionService(
		@Nonnull AsyncCallFunction<EvitaSessionServiceStub, T> lambda
	) {
		final Timeout timeout = getCurrentTimeout();
		try {
			SessionIdHolder.setSessionId(getId().toString());
			return lambda.apply(this.evitaSessionServiceStub.withDeadlineAfter(timeout.timeout(), timeout.timeoutUnit()));
		} catch (ExecutionException e) {
			final Throwable theException = e.getCause() == null ? e : e.getCause();
			throw EvitaClient.transformException(
				theException,
				() -> {
					// close session and rethrow
					final CompletableFuture<CommitVersions> future = closeInternally();
					future.completeExceptionally(theException);
				}
			);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new EvitaClientServerCallException("Server call interrupted.", e);
		} catch (TimeoutException e) {
			throw new EvitaClientTimedOutException(
				timeout.timeout(), timeout.timeoutUnit()
			);
		} finally {
			SessionIdHolder.reset();
		}
	}

	@Nonnull
	private <S extends Serializable> Optional<S> queryOneInternal(
		@Nonnull Query query,
		@Nonnull Class<S> expectedType,
		@Nonnull EvitaRequest evitaRequest,
		@Nonnull TypeConverter<S> typeConverter
	) {
		assertActive();
		final Query finalQuery = assertRequestMakesSenseAndEntityTypeIsPresent(query, expectedType, this.reflectionLookup);
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
					entity -> this.schemaCache.getEntitySchemaOrThrowException(
						entity.getEntityType(), entity.getSchemaVersion(), this::fetchEntitySchema, this::getCatalogSchema
					),
					evitaRequest,
					sealedEntity,
					expectedType,
					typeConverter
				));
			}
		}
	}

	@Nonnull
	private <T> Optional<T> deleteEntityInternal(
		@Nonnull String entityType,
		@Nonnull Class<T> expectedType,
		@Nonnull TypeConverter<T> typeConverter,
		int primaryKey,
		@Nonnull EntityContentRequire[] require
	) {
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
						entity -> this.schemaCache.getEntitySchemaOrThrowException(
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
							extractEntityTypeFromClass(expectedType, this.reflectionLookup)
								.orElse(null)
						),
						grpcResponse.getEntity(),
						expectedType,
						typeConverter
					)
				) : empty();
		});
	}

	@Nonnull
	private <T> Optional<T> archiveEntityInternal(
		@Nonnull String entityType,
		@Nonnull Class<T> expectedType,
		@Nonnull TypeConverter<T> typeConverter,
		int primaryKey,
		@Nonnull EntityContentRequire[] require
	) {
		assertActive();
		return executeInTransactionIfPossible(session -> {
			final StringWithParameters stringWithParameters = PrettyPrintingVisitor.toStringWithParameterExtraction(require);
			final GrpcArchiveEntityResponse grpcResponse = executeWithBlockingEvitaSessionService(
				evitaSessionService ->
					evitaSessionService.archiveEntity(
						GrpcArchiveEntityRequest
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
						entity -> this.schemaCache.getEntitySchemaOrThrowException(
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
							extractEntityTypeFromClass(expectedType, this.reflectionLookup)
								.orElse(null)
						),
						grpcResponse.getEntity(),
						expectedType,
						typeConverter
					)
				) : empty();
		});
	}

	@Nonnull
	private <T> Optional<T> restoreEntityInternal(
		@Nonnull String entityType,
		@Nonnull Class<T> expectedType,
		@Nonnull TypeConverter<T> typeConverter,
		int primaryKey,
		@Nonnull EntityContentRequire[] require
	) {
		assertActive();
		return executeInTransactionIfPossible(session -> {
			final StringWithParameters stringWithParameters = PrettyPrintingVisitor.toStringWithParameterExtraction(require);
			final GrpcRestoreEntityResponse grpcResponse = executeWithBlockingEvitaSessionService(
				evitaSessionService ->
					evitaSessionService.restoreEntity(
						GrpcRestoreEntityRequest
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
						entity -> this.schemaCache.getEntitySchemaOrThrowException(
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
							extractEntityTypeFromClass(expectedType, this.reflectionLookup)
								.orElse(null)
						),
						grpcResponse.getEntity(),
						expectedType,
						typeConverter
					)
				) : empty();
		});
	}

	@Nonnull
	private <T> DeletedHierarchy<T> deleteEntityHierarchyInternal(
		@Nonnull String entityType,
		@Nonnull Class<T> expectedType,
		@Nonnull TypeConverter<T> typeConverter,
		int primaryKey,
		@Nonnull EntityContentRequire... require
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
				grpcResponse.getDeletedEntityPrimaryKeysList().stream().mapToInt(Integer::intValue).toArray(),
				grpcResponse.hasDeletedRootEntity() ?
					EntityConverter.toEntity(
						entity -> this.schemaCache.getEntitySchemaOrThrowException(
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
							extractEntityTypeFromClass(expectedType, this.reflectionLookup)
								.orElse(null)
						),
						grpcResponse.getDeletedRootEntity(),
						expectedType,
						typeConverter
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
				(sealedEntity) -> this.schemaCache.getEntitySchemaOrThrowException(
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
				evitaSessionService.getCatalogSchema(
					GrpcGetCatalogSchemaRequest.newBuilder().build()
				)
		);
		return CatalogSchemaConverter.convert(
			grpcResponse.getCatalogSchema(), this.clientEntitySchemaAccessor
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
		if (!this.sessionTraits.isReadWrite()) {
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

		this.schemaCache.updateLastKnownCatalogVersion(grpcResponse.getCatalogVersion());

		this.transactionAccessor.getAndUpdate(transaction -> {
			isPremiseValid(transaction == null, "Transaction unexpectedly found!");
			if (this.sessionTraits.isDryRun()) {
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
		if (this.transactionAccessor.get() == null && getCatalogState() == CatalogState.ALIVE) {
			//noinspection unused
			try (final EvitaClientTransaction newTransaction = createAndInitTransaction()) {
				try {
					return logic.apply(this);
				} catch (Throwable ex) {
					ofNullable(this.transactionAccessor.get())
						.ifPresent(EvitaClientTransaction::setRollbackOnly);
					throw ex;
				}
			}
		} else {
			// the transaction might already exist
			try {
				return logic.apply(this);
			} catch (Throwable ex) {
				ofNullable(this.transactionAccessor.get())
					.ifPresent(EvitaClientTransaction::setRollbackOnly);
				throw ex;
			}
		}
	}

	/**
	 * Stream observer that stores the values returned by the server into the underlying queue. The observer cancels
	 * the transmission when closed.
	 */
	private static class MutationsStreamObserver implements StreamObserver<GetMutationsHistoryResponse> {
		/**
		 * Queue that holds the values returned by the server.
		 */
		private final BlockingQueue<StreamValueWrapper<ChangeCatalogCapture>> queue = new LinkedBlockingQueue<>();
		/**
		 * Flag that signals the stream has been cancelled or completed.
		 */
		private final AtomicBoolean completed = new AtomicBoolean(false);

		@Override
		public void onNext(GetMutationsHistoryResponse getMutationsHistoryResponse) {
			// if we were canceled signalize it to the server side
			if (this.completed.get()) {
				throw new CancellationException("Stream has been completed!");
			}
			// Convert the response and add to the queue
			getMutationsHistoryResponse.getChangeCaptureList()
				.stream()
				.map(ChangeCaptureConverter::toChangeCatalogCapture)
				.forEach(it -> this.queue.add(new StreamValueWrapper<>(it)));
		}

		@Override
		public void onError(Throwable throwable) {
			this.queue.add(new StreamValueWrapper<>(throwable));
		}

		@Override
		public void onCompleted() {
			this.queue.add(StreamValueWrapper.streamCompleted());
			this.completed.set(true);
		}

		/**
		 * Blocks the current thread until the next mutation is available.
		 *
		 * @return next mutation or empty if the stream has been completed
		 */
		@Nonnull
		public Optional<ChangeCatalogCapture> take() {
			try {
				final StreamValueWrapper<ChangeCatalogCapture> valueWrapper = this.queue.take();
				if (valueWrapper.completed()) {
					if (valueWrapper.error() != null) {
						throw new GenericEvitaInternalError(
							"Error while retrieving a mutation stream.",
							valueWrapper.error()
						);
					}
					return Optional.empty();
				} else {
					return Optional.ofNullable(valueWrapper.value());
				}
			} catch (InterruptedException e) {
				// finalize stream
				return Optional.empty();
			}
		}

		/**
		 * Returns true if the stream has been completed.
		 *
		 * @return true if the stream has been completed
		 */
		public boolean isCompleted() {
			return this.completed.get();
		}
	}

	/**
	 * Spliterator that captures the {@link ChangeCatalogCapture} instances from the {@link MutationsStreamObserver}
	 * in a blocking fashion.
	 */
	private static class ChangeCatalogCaptureSpliterator
		extends Spliterators.AbstractSpliterator<ChangeCatalogCapture> {
		/**
		 * Observer that receives the data from the server.
		 */
		private final MutationsStreamObserver mutationsStreamObserver;

		public ChangeCatalogCaptureSpliterator(@Nonnull MutationsStreamObserver mutationsStreamObserver) {
			super(Long.MAX_VALUE, Spliterator.ORDERED);
			this.mutationsStreamObserver = mutationsStreamObserver;
		}

		@Override
		public boolean tryAdvance(Consumer<? super ChangeCatalogCapture> action) {
			// this will block until next mutation is available
			final Optional<ChangeCatalogCapture> capture = this.mutationsStreamObserver.take();
			if (capture.isEmpty()) {
				// if the capture is empty, the stream has been depleted or closed
				return false;
			} else {
				// otherwise, pass the capture to the action
				action.accept(capture.get());
				return true;
			}
		}

	}

	/**
	 * Simple wrapper class that allows to pass capture value, or signalize end of stream with possible error returned
	 * byt the server.
	 *
	 * @param value     value returned by the server
	 * @param error     error returned by the server
	 * @param completed flag that signals the end of the stream
	 */
	public record StreamValueWrapper<T>(
		@Nullable T value,
		@Nullable Throwable error,
		boolean completed
	) {
		public static <T> StreamValueWrapper<T> streamCompleted() {
			return new StreamValueWrapper<>(null, null, true);
		}

		public StreamValueWrapper(@Nullable T value) {
			this(value, null, false);
		}

		public StreamValueWrapper(@Nullable Throwable error) {
			this(null, error, true);
		}

	}

	/**
	 * Internal class that provides access to the {@link EntitySchemaContract} instances for the client session.
	 */
	private class ClientEntitySchemaAccessor implements EntitySchemaProvider {
		@Nonnull
		@Override
		public Collection<EntitySchemaContract> getEntitySchemas() {
			return (
				isActive() ?
					EvitaClientSession.this.getAllEntityTypes() :
					EvitaClientSession.this.evita.queryCatalog(
						EvitaClientSession.this.catalogName,
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
					EvitaClientSession.this.evita.queryCatalog(
						EvitaClientSession.this.catalogName,
						session -> {
							return session.getEntitySchema(entityType);
						}
					)
			).map(EntitySchemaContract.class::cast);
		}
	}
}
