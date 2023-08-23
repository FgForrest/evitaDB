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

package io.evitadb.driver;

import com.google.protobuf.Empty;
import com.google.protobuf.Int32Value;
import io.evitadb.api.CatalogState;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.SessionTraits;
import io.evitadb.api.exception.CollectionNotFoundException;
import io.evitadb.api.exception.EntityAlreadyRemovedException;
import io.evitadb.api.exception.EntityClassInvalidException;
import io.evitadb.api.exception.InstanceTerminatedException;
import io.evitadb.api.exception.SchemaAlteringException;
import io.evitadb.api.exception.TransactionNotSupportedException;
import io.evitadb.api.exception.UnexpectedResultCountException;
import io.evitadb.api.exception.UnexpectedResultException;
import io.evitadb.api.exception.UnexpectedTransactionStateException;
import io.evitadb.api.proxy.ProxyFactory;
import io.evitadb.api.proxy.SealedEntityProxy;
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
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.annotation.Entity;
import io.evitadb.api.requestResponse.data.annotation.EntityRef;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.data.structure.InitialEntityBuilder;
import io.evitadb.api.requestResponse.schema.CatalogSchemaEditor.CatalogSchemaBuilder;
import io.evitadb.api.requestResponse.schema.ClassSchemaAnalyzer;
import io.evitadb.api.requestResponse.schema.ClassSchemaAnalyzer.AnalysisResult;
import io.evitadb.api.requestResponse.schema.EntitySchemaDecorator;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.SealedCatalogSchema;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.dataType.DataChunk;
import io.evitadb.driver.pooling.ChannelPool;
import io.evitadb.driver.requestResponse.schema.ClientCatalogSchemaDecorator;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.grpc.generated.*;
import io.evitadb.externalApi.grpc.generated.EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub;
import io.evitadb.externalApi.grpc.interceptor.ClientSessionInterceptor.SessionIdHolder;
import io.evitadb.externalApi.grpc.query.QueryConverter;
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
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;

import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.entityFetch;
import static io.evitadb.api.query.QueryConstraints.require;
import static io.evitadb.driver.EvitaClient.ERROR_MESSAGE_PATTERN;
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
	 * Function that extracts the entityType from the entity class in an optimized (memoized) way.
	 */
	private static final BiFunction<ReflectionLookup, Class<?>, String> ENTITY_TYPE_EXTRACTOR = (reflectionLookup, theClass) -> reflectionLookup.extractFromClass(
		theClass, EntityRef.class,
		clazz -> {
			final EntityRef entityRef = reflectionLookup.getClassAnnotation(clazz, EntityRef.class);
			if (entityRef != null) {
				return entityRef.value();
			}
			final Entity entity = reflectionLookup.getClassAnnotation(clazz, Entity.class);
			if (entity != null) {
				return entity.name();
			}
			return null;
		}
	);

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
	 * Flag that is se to TRUE when Evita. is ready to serve application calls.
	 * Aim of this flag is to refuse any calls after {@link #close()} method has been called.
	 */
	private boolean active = true;
	/**
	 * Timestamp of the last session activity (call).
	 */
	private long lastCall;

	private static <S extends Serializable> void assertRequestMakesSense(@Nonnull Query query, @Nonnull Class<S> expectedType) {
		if (EntityContract.class.isAssignableFrom(expectedType) &&
			(query.getRequire() == null ||
				FinderVisitor.findConstraints(query.getRequire(), EntityFetch.class::isInstance, SeparateEntityContentRequireContainer.class::isInstance).isEmpty())) {
			throw new EvitaInvalidUsageException(
				"Method call expects `" + expectedType + "` in result, yet it doesn't define `entityFetch` " +
					"in the requirements. This would imply that only entity references " +
					"will be returned by the server!"
			);
		}
	}

	public EvitaClientSession(
		@Nonnull ReflectionLookup reflectionLookup,
		@Nonnull EvitaEntitySchemaCache schemaCache,
		@Nonnull ChannelPool channelPool,
		@Nonnull String catalogName,
		@Nonnull CatalogState catalogState,
		@Nonnull UUID sessionId,
		@Nonnull SessionTraits sessionTraits,
		@Nonnull Consumer<EvitaClientSession> onTerminationCallback
	) {
		this.reflectionLookup = reflectionLookup;
		this.proxyFactory = schemaCache.getProxyFactory();
		this.schemaCache = schemaCache;
		this.channelPool = channelPool;
		this.catalogName = catalogName;
		this.catalogState = catalogState;
		this.sessionId = sessionId;
		this.sessionTraits = sessionTraits;
		this.onTerminationCallback = onTerminationCallback;
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
		return schemaCache.getLatestCatalogSchema(this::fetchCatalogSchema, this::getEntitySchemaOrThrow);
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
	public boolean isActive() {
		return active;
	}

	@Override
	public boolean goLiveAndClose() {
		assertActive();
		final GrpcGoLiveAndCloseResponse grpcResponse = executeWithEvitaSessionService(evitaSessionService ->
			evitaSessionService.goLiveAndClose(Empty.newBuilder().build())
		);
		final boolean success = grpcResponse.getSuccess();
		if (success) {
			closeInternally();
		}
		return success;
	}

	@Override
	public void close() {
		if (active) {
			executeWithEvitaSessionService(evitaSessionService ->
				evitaSessionService.close(Empty.getDefaultInstance())
			);
			closeInternally();
		}
	}

	@Nonnull
	@Override
	public SealedEntitySchema defineEntitySchemaFromModelClass(@Nonnull Class<?> modelClass) {
		assertActive();
		return executeInTransactionIfPossible(
			session -> {
				final ClassSchemaAnalyzer classSchemaAnalyzer = new ClassSchemaAnalyzer(modelClass, reflectionLookup);
				final AnalysisResult analysisResult = classSchemaAnalyzer.analyze(this);
				updateCatalogSchema(analysisResult.mutations());
				return getEntitySchemaOrThrow(analysisResult.entityType());
			}
		);
	}

	@Nonnull
	@Override
	public SealedEntitySchema defineEntitySchemaFromModelClass(@Nonnull Class<?> modelClass, @Nonnull BiConsumer<CatalogSchemaBuilder, EntitySchemaBuilder> postProcessor) {
		assertActive();
		return executeInTransactionIfPossible(
			session -> {
				final ClassSchemaAnalyzer classSchemaAnalyzer = new ClassSchemaAnalyzer(modelClass, reflectionLookup, postProcessor);
				final AnalysisResult analysisResult = classSchemaAnalyzer.analyze(this);
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
		return getEntitySchema(ENTITY_TYPE_EXTRACTOR.apply(reflectionLookup, modelClass));
	}

	@Nonnull
	@Override
	public SealedEntitySchema getEntitySchemaOrThrow(@Nonnull String entityType) {
		assertActive();
		return getEntitySchema(entityType)
			.orElseThrow(() -> new CollectionNotFoundException(entityType));
	}

	@Nonnull
	@Override
	public SealedEntitySchema getEntitySchemaOrThrow(@Nonnull Class<?> modelClass) throws CollectionNotFoundException, EntityClassInvalidException {
		return getEntitySchemaOrThrow(ENTITY_TYPE_EXTRACTOR.apply(reflectionLookup, modelClass));
	}

	@Nonnull
	@Override
	public Set<String> getAllEntityTypes() {
		assertActive();
		final GrpcEntityTypesResponse grpcResponse = executeWithEvitaSessionService(evitaSessionService ->
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
			ENTITY_TYPE_EXTRACTOR.apply(reflectionLookup, expectedType),
			this.proxyFactory::createEntityProxy
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
			ENTITY_TYPE_EXTRACTOR.apply(reflectionLookup, expectedType),
			this.proxyFactory::createEntityProxy
		);
		return queryListInternal(query, expectedType, evitaRequest);
	}

	@Nonnull
	@Override
	public <S extends Serializable, T extends EvitaResponse<S>> T query(@Nonnull Query query, @Nonnull Class<S> expectedType) throws UnexpectedResultException, InstanceTerminatedException {
		assertActive();
		assertRequestMakesSense(query, expectedType);
		final String entityTypeByExpectedType = ENTITY_TYPE_EXTRACTOR.apply(reflectionLookup, expectedType);

		final StringWithParameters stringWithParameters = query.normalizeQuery().toStringWithParameterExtraction();
		final GrpcQueryResponse grpcResponse = executeWithEvitaSessionService(evitaSessionService ->
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
				query, recordPage,
				getEvitaResponseExtraResults(
					grpcResponse,
					new EvitaRequest(
						query,
						OffsetDateTime.now(),
						EntityReference.class,
						null,
						this.proxyFactory::createEntityProxy
					)
				)
			);
		} else {
			final DataChunk<S> recordPage;
			if (grpcResponse.getRecordPage().getBinaryEntitiesList().isEmpty()) {
				// convert to Sealed entities
				recordPage = ResponseConverter.convertToDataChunk(
					grpcResponse,
					grpcRecordPage -> EntityConverter.toEntities(
						grpcRecordPage.getSealedEntitiesList(),
						new EvitaRequest(
							query,
							OffsetDateTime.now(),
							expectedType,
							entityTypeByExpectedType,
							this.proxyFactory::createEntityProxy
						),
						(entityType, schemaVersion) -> schemaCache.getEntitySchemaOrThrow(
							entityType, schemaVersion, this::fetchEntitySchema, this::getCatalogSchema
						),
						expectedType
					)
				);
			} else {
				// parse the entities
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
				query, recordPage,
				getEvitaResponseExtraResults(
					grpcResponse,
					new EvitaRequest(
						query,
						OffsetDateTime.now(),
						expectedType,
						entityTypeByExpectedType,
						this.proxyFactory::createEntityProxy
					)
				)
			);
		}
	}

	@Nullable
	@Override
	public <T> T execute(@Nonnull Function<EvitaSessionContract, T> logic) {
		assertActive();
		return executeInTransactionIfPossible(logic);
	}

	@Override
	public void execute(@Nonnull Consumer<EvitaSessionContract> logic) {
		assertActive();
		executeInTransactionIfPossible(
			evitaSessionContract -> {
				logic.accept(evitaSessionContract);
				return null;
			}
		);
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
			this.proxyFactory::createEntityProxy
		);
		return getEntityInternal(entityType, SealedEntity.class, primaryKey, evitaRequest, require);
	}

	@Nonnull
	@Override
	public <T extends Serializable> Optional<T> getEntity(@Nonnull Class<T> expectedType, int primaryKey, EntityContentRequire... require) throws EntityClassInvalidException {
		final String entityType = ENTITY_TYPE_EXTRACTOR.apply(reflectionLookup, expectedType);
		final EvitaRequest evitaRequest = new EvitaRequest(
			Query.query(
				collection(entityType),
				require(
					entityFetch(require)
				)
			),
			OffsetDateTime.now(),
			expectedType,
			ENTITY_TYPE_EXTRACTOR.apply(reflectionLookup, expectedType),
			this.proxyFactory::createEntityProxy
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
			entityType = sealedEntityProxy.getSealedEntity().getType();
			entityPk = sealedEntityProxy.getSealedEntity().getPrimaryKey();
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
			ENTITY_TYPE_EXTRACTOR.apply(reflectionLookup, expectedType),
			this.proxyFactory::createEntityProxy
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
			entityType = sealedEntityProxy.getSealedEntity().getType();
			entityPk = sealedEntityProxy.getSealedEntity().getPrimaryKey();
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
			ENTITY_TYPE_EXTRACTOR.apply(reflectionLookup, expectedType),
			this.proxyFactory::createEntityProxy
		);

		//noinspection unchecked
		return (T) getEntityInternal(entityType, expectedType, entityPk, evitaRequest, require)
			.orElseThrow(() -> new EntityAlreadyRemovedException(entityType, entityPk));
	}

	@Nonnull
	@Override
	public EntitySchemaBuilder defineEntitySchema(@Nonnull String entityType) {
		assertActive();
		final SealedEntitySchema newEntitySchema = executeInTransactionIfPossible(session -> {
			final GrpcDefineEntitySchemaRequest request = GrpcDefineEntitySchemaRequest.newBuilder()
				.setEntityType(entityType)
				.build();

			final GrpcDefineEntitySchemaResponse response = executeWithEvitaSessionService(evitaSessionService ->
				evitaSessionService.defineEntitySchema(request)
			);

			final EntitySchema theSchema = EntitySchemaConverter.convert(response.getEntitySchema());
			schemaCache.setLatestEntitySchema(theSchema);
			return new EntitySchemaDecorator(this::getCatalogSchema, theSchema);
		});
		return newEntitySchema.openForWrite();
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

			final GrpcUpdateCatalogSchemaResponse response = executeWithEvitaSessionService(evitaSessionService ->
				evitaSessionService.updateCatalogSchema(request)
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

			final GrpcUpdateAndFetchCatalogSchemaResponse response = executeWithEvitaSessionService(evitaSessionService ->
				evitaSessionService.updateAndFetchCatalogSchema(request)
			);

			final CatalogSchema updatedCatalogSchema = CatalogSchemaConverter.convert(response.getCatalogSchema());
			final SealedCatalogSchema updatedSchema = new ClientCatalogSchemaDecorator(updatedCatalogSchema, this::getEntitySchemaOrThrow);
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
			final GrpcUpdateEntitySchemaResponse response = executeWithEvitaSessionService(evitaSessionService ->
				evitaSessionService.updateEntitySchema(request)
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

			final GrpcUpdateAndFetchEntitySchemaResponse response = executeWithEvitaSessionService(evitaSessionService ->
				evitaSessionService.updateAndFetchEntitySchema(request)
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
				final GrpcDeleteCollectionResponse grpcResponse = executeWithEvitaSessionService(evitaSessionService ->
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
		return deleteCollection(ENTITY_TYPE_EXTRACTOR.apply(reflectionLookup, modelClass));
	}

	@Override
	public boolean renameCollection(@Nonnull String entityType, @Nonnull String newName) {
		assertActive();
		return executeInTransactionIfPossible(
			evitaSessionContract -> {
				final GrpcRenameCollectionResponse grpcResponse = executeWithEvitaSessionService(evitaSessionService ->
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
				final GrpcReplaceCollectionResponse grpcResponse = executeWithEvitaSessionService(evitaSessionService ->
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
		final GrpcEntityCollectionSizeResponse grpcResponse = executeWithEvitaSessionService(evitaSessionService ->
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
			session -> new InitialEntityBuilder(getEntitySchemaOrThrow(entityType), null)
		);
	}

	@Nonnull
	@Override
	public EntityBuilder createNewEntity(@Nonnull String entityType, int primaryKey) {
		assertActive();
		return executeInTransactionIfPossible(
			session -> new InitialEntityBuilder(getEntitySchemaOrThrow(entityType), primaryKey)
		);
	}

	@Nonnull
	@Override
	public EntityReference upsertEntity(@Nonnull EntityBuilder entityBuilder) {
		return entityBuilder.toMutation()
			.map(this::upsertEntity)
			.orElseGet(() -> new EntityReference(entityBuilder.getType(), entityBuilder.getPrimaryKey()));
	}

	@Nonnull
	@Override
	public EntityReference upsertEntity(@Nonnull EntityMutation entityMutation) {
		assertActive();
		return executeInTransactionIfPossible(session -> {
			final GrpcEntityMutation grpcEntityMutation = ENTITY_MUTATION_CONVERTER.convert(entityMutation);
			final GrpcUpsertEntityResponse grpcResult = executeWithEvitaSessionService(evitaSessionService ->
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
			final GrpcUpsertEntityResponse grpcResponse = executeWithEvitaSessionService(evitaSessionService ->
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
					this.proxyFactory::createEntityProxy
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
			final GrpcDeleteEntityResponse grpcResponse = executeWithEvitaSessionService(evitaSessionService ->
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
		return deleteEntity(ENTITY_TYPE_EXTRACTOR.apply(reflectionLookup, modelClass), primaryKey);
	}

	@Nonnull
	@Override
	public Optional<SealedEntity> deleteEntity(@Nonnull String entityType, int primaryKey, EntityContentRequire... require) {
		return deleteEntityInternal(entityType, SealedEntity.class, primaryKey, require);
	}

	@Nonnull
	@Override
	public <T extends Serializable> Optional<T> deleteEntity(@Nonnull Class<T> modelClass, int primaryKey, EntityContentRequire... require) throws EntityClassInvalidException {
		return deleteEntityInternal(ENTITY_TYPE_EXTRACTOR.apply(reflectionLookup, modelClass), modelClass, primaryKey, require);
	}

	@Override
	public int deleteEntityAndItsHierarchy(@Nonnull String entityType, int primaryKey) {
		assertActive();
		return executeInTransactionIfPossible(session -> {
			final GrpcDeleteEntityAndItsHierarchyResponse grpcResponse = executeWithEvitaSessionService(evitaSessionService ->
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
		return deleteEntityHierarchyInternal(ENTITY_TYPE_EXTRACTOR.apply(reflectionLookup, modelClass), modelClass, primaryKey, require);
	}

	@Override
	public int deleteEntities(@Nonnull Query query) {
		assertActive();
		return executeInTransactionIfPossible(session -> {
			final StringWithParameters stringWithParameters = query.normalizeQuery().toStringWithParameterExtraction();
			final GrpcDeleteEntitiesResponse grpcResponse = executeWithEvitaSessionService(evitaSessionService ->
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
				this.proxyFactory::createEntityProxy
			);
			final StringWithParameters stringWithParameters = query.normalizeQuery().toStringWithParameterExtraction();
			final GrpcDeleteEntitiesResponse grpcResponse = executeWithEvitaSessionService(evitaSessionService ->
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

	@Override
	public long openTransaction() {
		assertActive();
		assertTransactionIsNotOpened();
		//noinspection resource
		final EvitaClientTransaction transaction = createAndInitTransaction();
		return transaction.getId();
	}

	@Nonnull
	@Override
	public Optional<Long> getOpenedTransactionId() {
		assertActive();
		return ofNullable(transactionAccessor.get())
			.filter(EvitaClientTransaction::isClosed)
			.map(EvitaClientTransaction::getId);
	}

	@Override
	public void closeTransaction() {
		assertActive();
		final EvitaClientTransaction transaction = transactionAccessor.get();
		if (transaction == null) {
			throw new UnexpectedTransactionStateException("No transaction has been opened!");
		}
		destroyTransaction();
		transaction.close();
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
	 * Method internally closes the session
	 */
	public void closeInternally() {
		if (active) {
			active = false;
			// then apply termination callbacks
			ofNullable(onTerminationCallback)
				.ifPresent(it -> it.accept(this));
		}
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
		final GrpcEntityResponse grpcResponse = executeWithEvitaSessionService(evitaSessionService ->
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
		assertRequestMakesSense(query, expectedType);

		final StringWithParameters stringWithParameters = query.normalizeQuery().toStringWithParameterExtraction();
		final GrpcQueryListResponse grpcResponse = executeWithEvitaSessionService(evitaSessionService ->
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
	 * @param evitaSessionServiceBlockingStub function that holds a logic passed by the caller
	 * @param <T>                             return type of the function
	 * @return result of the applied function
	 */
	private <T> T executeWithEvitaSessionService(@Nonnull Function<EvitaSessionServiceBlockingStub, T> evitaSessionServiceBlockingStub) {
		final ManagedChannel managedChannel = this.channelPool.getChannel();
		try {
			SessionIdHolder.setSessionId(getCatalogName(), getId().toString());
			return evitaSessionServiceBlockingStub.apply(EvitaSessionServiceGrpc.newBlockingStub(managedChannel));
		} catch (StatusRuntimeException statusRuntimeException) {
			final Code statusCode = statusRuntimeException.getStatus().getCode();
			final String description = ofNullable(statusRuntimeException.getStatus().getDescription())
				.orElse("No description.");
			if (statusCode == Code.UNAUTHENTICATED) {
				// close session and rethrow
				closeInternally();
				throw new InstanceTerminatedException("session");
			} else if (statusCode == Code.INVALID_ARGUMENT) {
				final Matcher expectedFormat = ERROR_MESSAGE_PATTERN.matcher(description);
				if (expectedFormat.matches()) {
					throw EvitaInvalidUsageException.createExceptionWithErrorCode(
						expectedFormat.group(2), expectedFormat.group(1)
					);
				} else {
					throw new EvitaInvalidUsageException(description);
				}
			} else {
				final Matcher expectedFormat = ERROR_MESSAGE_PATTERN.matcher(description);
				if (expectedFormat.matches()) {
					throw EvitaInternalError.createExceptionWithErrorCode(
						expectedFormat.group(2), expectedFormat.group(1)
					);
				} else {
					throw new EvitaInternalError(description);
				}
			}
		} catch (EvitaInvalidUsageException | EvitaInternalError evitaError) {
			throw evitaError;
		} catch (Throwable e) {
			log.error("Unexpected internal Evita error occurred: {}", e.getMessage(), e);
			throw new EvitaInternalError(
				"Unexpected internal Evita error occurred: " + e.getMessage(),
				"Unexpected internal Evita error occurred.",
				e
			);
		} finally {
			this.channelPool.releaseChannel(managedChannel);
			SessionIdHolder.reset();
		}
	}

	@Nonnull
	private <S extends Serializable> Optional<S> queryOneInternal(
		@Nonnull Query query,
		@Nonnull Class<S> expectedType,
		@Nonnull EvitaRequest evitaRequest
	) {
		assertActive();
		assertRequestMakesSense(query, expectedType);

		final StringWithParameters stringWithParameters = query.normalizeQuery().toStringWithParameterExtraction();
		final GrpcQueryOneResponse grpcResponse = executeWithEvitaSessionService(evitaSessionService ->
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
			final GrpcDeleteEntityResponse grpcResponse = executeWithEvitaSessionService(evitaSessionService ->
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
							ENTITY_TYPE_EXTRACTOR.apply(reflectionLookup, expectedType),
							this.proxyFactory::createEntityProxy
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
			final GrpcDeleteEntityAndItsHierarchyResponse grpcResponse = executeWithEvitaSessionService(evitaSessionService ->
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
							ENTITY_TYPE_EXTRACTOR.apply(reflectionLookup, expectedType),
							this.proxyFactory::createEntityProxy
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
		final GrpcCatalogSchemaResponse grpcResponse = executeWithEvitaSessionService(evitaSessionService ->
			evitaSessionService.getCatalogSchema(Empty.getDefaultInstance())
		);
		return CatalogSchemaConverter.convert(grpcResponse.getCatalogSchema());
	}

	/**
	 * This internal method will physically call over the network and fetch actual {@link EntitySchema}.
	 */
	@Nonnull
	private Optional<EntitySchema> fetchEntitySchema(@Nonnull String entityType) {
		final GrpcEntitySchemaResponse grpcResponse = executeWithEvitaSessionService(evitaSessionService ->
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
		if (active) {
			this.lastCall = System.currentTimeMillis();
		} else {
			throw new InstanceTerminatedException("session");
		}
	}

	/**
	 * Verifies this instance is still active.
	 */
	private void assertTransactionIsNotOpened() {
		if (transactionAccessor.get() != null) {
			throw new UnexpectedTransactionStateException("Transaction has been already opened. Evita doesn't support nested transactions!");
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
			throw new TransactionNotSupportedException("Catalog " + getCatalogName() + " doesn't support transactions yet. Call `goLiveAndClose()` method first!");
		}

		final GrpcOpenTransactionResponse grpcResponse = executeWithEvitaSessionService(evitaSessionService ->
			evitaSessionService.openTransaction(Empty.newBuilder().build())
		);

		final EvitaClientTransaction tx = new EvitaClientTransaction(this, grpcResponse.getTransactionId());
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
	 * Destroys transaction reference.
	 */
	private void destroyTransaction() {
		transactionAccessor.getAndUpdate(transaction -> {
			Assert.isPremiseValid(transaction != null, "Transaction unexpectedly not present!");
			executeWithEvitaSessionService(evitaSessionService ->
				evitaSessionService.closeTransaction(
					GrpcCloseTransactionRequest
						.newBuilder()
						.setRollback(transaction.isRollbackOnly())
						.build()
				)
			);
			return null;
		});
	}

	/**
	 * Executes passed lambda in existing transaction or throws exception.
	 *
	 * @throws UnexpectedTransactionStateException if transaction is not open
	 */
	private <T> T executeInTransactionIfPossible(Function<EvitaSessionContract, T> logic) {
		if (transactionAccessor.get() == null && getCatalogState() == CatalogState.ALIVE) {
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

}
