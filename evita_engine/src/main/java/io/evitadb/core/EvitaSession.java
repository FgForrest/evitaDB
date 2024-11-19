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

package io.evitadb.core;

import io.evitadb.api.CatalogContract;
import io.evitadb.api.CatalogState;
import io.evitadb.api.EntityCollectionContract;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.EvitaSessionTerminationCallback;
import io.evitadb.api.SchemaPostProcessor;
import io.evitadb.api.SchemaPostProcessorCapturingResult;
import io.evitadb.api.SessionTraits;
import io.evitadb.api.TransactionContract.CommitBehavior;
import io.evitadb.api.exception.*;
import io.evitadb.api.file.FileForFetch;
import io.evitadb.api.observability.trace.RepresentsMutation;
import io.evitadb.api.observability.trace.RepresentsQuery;
import io.evitadb.api.observability.trace.Traced;
import io.evitadb.api.proxy.ProxyFactory;
import io.evitadb.api.proxy.SealedEntityProxy;
import io.evitadb.api.proxy.SealedEntityReferenceProxy;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.require.EntityContentRequire;
import io.evitadb.api.query.require.PriceContent;
import io.evitadb.api.query.require.PriceContentMode;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCaptureRequest;
import io.evitadb.api.requestResponse.data.DeletedHierarchy;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.InstanceEditor;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.data.mutation.price.PriceMutation;
import io.evitadb.api.requestResponse.data.structure.EntityDecorator;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.data.structure.Price.PriceKey;
import io.evitadb.api.requestResponse.mutation.MutationPredicate;
import io.evitadb.api.requestResponse.schema.ClassSchemaAnalyzer;
import io.evitadb.api.requestResponse.schema.ClassSchemaAnalyzer.AnalysisResult;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.SealedCatalogSchema;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.CreateEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.api.requestResponse.system.CatalogVersion;
import io.evitadb.api.task.Task;
import io.evitadb.core.async.Interruptible;
import io.evitadb.core.cdc.predicate.MutationPredicateFactory;
import io.evitadb.core.exception.CatalogCorruptedException;
import io.evitadb.core.metric.event.query.EntityEnrichEvent;
import io.evitadb.core.metric.event.query.EntityFetchEvent;
import io.evitadb.core.query.response.ServerEntityDecorator;
import io.evitadb.core.transaction.TransactionWalFinalizer;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.ReflectionLookup;
import io.evitadb.utils.UUIDUtil;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.api.requestResponse.schema.ClassSchemaAnalyzer.extractEntityTypeFromClass;
import static io.evitadb.utils.Assert.isPremiseValid;
import static io.evitadb.utils.Assert.isTrue;
import static java.util.Optional.empty;
import static java.util.Optional.of;
import static java.util.Optional.ofNullable;

/**
 * Session are created by the clients to envelope a "piece of work" with evitaDB. In web environment it's a good idea
 * to have session per request, in batch processing it's recommended to keep session per "record page" or "transaction".
 * There may be multiple {@link Transaction transaction} during single session instance life but there is no support
 * for transactional overlap - there may be at most single transaction open in single session.
 *
 * EvitaSession transaction behaves like <a href="https://en.wikipedia.org/wiki/Snapshot_isolation">Snapshot</a>
 * transaction. When no transaction is explicitly opened - each query to Evita behaves as one small transaction. Data
 * updates are not allowed without explicitly opened transaction.
 *
 * Remember to {@link #close()} when your work with Evita is finished.
 * EvitaSession contract is NOT thread safe.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@NotThreadSafe
@EqualsAndHashCode(of = "id")
@Slf4j
public final class EvitaSession implements EvitaInternalSessionContract {
	private static final Scope[] LIVE_SCOPE_ONLY = {Scope.LIVE};

	/**
	 * Evita instance this session is connected to.
	 */
	@Getter private final Evita evita;
	/**
	 * Date and time of the session creation.
	 */
	@Getter private final OffsetDateTime created = OffsetDateTime.now();
	/**
	 * Contains unique identification of this particular Evita session. The id is not currently used, but may be
	 * propagated into the logs in the future.
	 */
	private final UUID id = UUIDUtil.randomUUID();
	/**
	 * Contains commit behavior for this transaction.
	 *
	 * @see CommitBehavior
	 */
	@Getter private final CommitBehavior commitBehaviour;
	/**
	 * Contains information passed at the time session was created that defines its behaviour
	 */
	private final SessionTraits sessionTraits;
	/**
	 * Reference, which allows to access a transaction object.
	 */
	private final AtomicReference<Transaction> transactionAccessor = new AtomicReference<>();
	/**
	 * Callback that will be called when the session is closed.
	 */
	private final EvitaSessionTerminationCallback terminationCallback;
	/**
	 * Reflection lookup is used to speed up reflection operation by memoizing the results for examined classes.
	 */
	private final ReflectionLookup reflectionLookup;
	/**
	 * Contains reference to the catalog to query / update.
	 */
	private final AtomicReference<CatalogContract> catalog;
	/**
	 * Represents the starting version of the catalog schema.
	 * This variable is used to keep track of the initial version of the catalog schema.
	 */
	private final long startCatalogSchemaVersion;
	/**
	 * Contains reference to the proxy factory that is used to create proxies for the entities.
	 */
	@Getter private final ProxyFactory proxyFactory;
	/**
	 * CompletableFuture representing the finalization of the session that conforms to requested
	 * {@link CommitBehavior} bound to the current transaction (if any).
	 */
	@Nonnull private final CompletableFuture<Long> finalizationFuture;
	/**
	 * Future that is instantiated when the session is closed.
	 * When initialized, subsequent calls of the close method will return the same future.
	 * When the future is non-null any calls after {@link #close()} method has been called.
	 */
	private volatile CompletableFuture<Long> closedFuture;
	/**
	 * Timestamp of the last session activity (call).
	 */
	private long lastCall = System.currentTimeMillis();
	/**
	 * Contains a number of nested session calls.
	 */
	private int nestLevel;
	/**
	 * Flag is set to true, when the session is being closed and only termination callback is executing. The session
	 * should still be operative until the termination callback is finished.
	 */
	private boolean beingClosed;

	/**
	 * Method creates implicit filtering constraint that contains price filtering constraints for price in case
	 * the `entityMutation` contains mutations related to prices and the require constraint require fetching prices
	 * {@link PriceContentMode#RESPECTING_FILTER}.
	 */
	@Nullable
	private static FilterConstraint createQueryForFetchAfterUpsert(@Nonnull EntityMutation entityMutation, @Nullable EntityContentRequire[] require) {
		final boolean shouldPricesRespectFilter = require != null && Arrays.stream(require)
			.filter(it -> it instanceof PriceContent)
			.map(it -> ((PriceContent) it).getFetchMode() == PriceContentMode.RESPECTING_FILTER)
			.findFirst()
			.orElse(false);
		final Set<PriceKey> usedPriceKeys;
		if (shouldPricesRespectFilter) {
			usedPriceKeys = entityMutation.getLocalMutations()
				.stream()
				.filter(it -> it instanceof PriceMutation)
				.map(it -> ((PriceMutation) it).getPriceKey())
				.collect(Collectors.toSet());
		} else {
			usedPriceKeys = Collections.emptySet();
		}
		return usedPriceKeys.isEmpty() ?
			null :
			and(
				priceInPriceLists(usedPriceKeys.stream().map(PriceKey::priceList).distinct().toArray(String[]::new)),
				of(usedPriceKeys.stream().map(PriceKey::currency).distinct().toArray(Currency[]::new))
					.filter(it -> it.length == 1)
					.map(it -> priceInCurrency(it[0]))
					.orElse(null)
			);
	}

	/**
	 * Returns an entity type from the given entity or throws an unified exception.
	 */
	@Nonnull
	private static <T extends Serializable> String getEntityTypeFromEntity(@Nonnull T partiallyLoadedEntity, @Nonnull ReflectionLookup reflectionLookup) {
		final String entityType;
		if (partiallyLoadedEntity instanceof EntityClassifier entityClassifier) {
			entityType = entityClassifier.getType();
		} else if (partiallyLoadedEntity instanceof SealedEntityProxy sealedEntityProxy) {
			entityType = sealedEntityProxy.entity().getType();
		} else {
			entityType = extractEntityTypeFromClass(partiallyLoadedEntity.getClass(), reflectionLookup)
				.orElseThrow(() -> new EvitaInvalidUsageException(
					"Unsupported entity type `" + partiallyLoadedEntity.getClass() + "`! The class doesn't implement EntityClassifier nor represents a SealedEntityProxy!",
					"Unsupported entity type!"
				));
		}
		return entityType;
	}

	EvitaSession(
		@Nonnull Evita evita,
		@Nonnull Catalog catalog,
		@Nonnull ReflectionLookup reflectionLookup,
		@Nullable EvitaSessionTerminationCallback terminationCallback,
		@Nonnull CommitBehavior commitBehaviour,
		@Nonnull SessionTraits sessionTraits
	) {
		this.evita = evita;
		this.catalog = new AtomicReference<>(catalog);
		this.startCatalogSchemaVersion = catalog.getSchema().version();
		this.reflectionLookup = reflectionLookup;
		this.proxyFactory = catalog.getProxyFactory();
		this.commitBehaviour = commitBehaviour;
		this.sessionTraits = sessionTraits;
		this.terminationCallback = terminationCallback;
		this.finalizationFuture = new CompletableFuture<>();
		if (catalog.supportsTransaction() && sessionTraits.isReadWrite()) {
			this.transactionAccessor.set(createAndInitTransaction());
		}
	}

	@Nonnull
	@Override
	public UUID getId() {
		return id;
	}

	@Nonnull
	@Override
	public UUID getCatalogId() {
		return getCatalog().getCatalogId();
	}

	@Interruptible
	@Traced
	@Nonnull
	@Override
	public SealedCatalogSchema getCatalogSchema() {
		assertActive();
		return getCatalog().getSchema();
	}

	@Nonnull
	@Override
	public CatalogState getCatalogState() {
		assertActive();
		return getCatalog().getCatalogState();
	}

	@Override
	public long getCatalogVersion() {
		assertActive();
		return getCatalog().getVersion();
	}

	@Override
	public boolean isActive() {
		return closedFuture == null || beingClosed;
	}

	@Traced
	@Override
	public boolean goLiveAndClose() {
		final CatalogContract theCatalog = getCatalog();
		isTrue(!theCatalog.supportsTransaction(), "Catalog went live already and is currently in transactional mode!");
		if (theCatalog.goLive()) {
			close();
			return true;
		}
		return false;
	}

	@Nonnull
	@Override
	public CompletableFuture<Long> closeNow(@Nonnull CommitBehavior commitBehaviour) {
		if (this.closedFuture == null) {
			// flush changes if we're not in transactional mode
			final CatalogContract theCatalog = this.catalog.get();
			if (theCatalog.getCatalogState() == CatalogState.WARMING_UP) {
				isPremiseValid(
					this.transactionAccessor.get() == null,
					"In warming-up mode no transaction is expected to be opened!"
				);
				// this should be always true - corrupted catalog would have thrown exception on getting catalog state
				if (theCatalog instanceof Catalog theCatalogToFlush) {
					theCatalogToFlush.flush();
				}
				// immediately complete future
				if (!this.finalizationFuture.isCompletedExceptionally()) {
					try {
						validateCatalogSchema(theCatalog);
						this.finalizationFuture.complete(getCatalogVersion());
					} catch (SchemaAlteringException ex) {
						this.finalizationFuture.completeExceptionally(ex);
					}
				}
			} else {
				if (this.transactionAccessor.get() != null) {
					try {
						if (!this.transactionAccessor.get().isRollbackOnly()) {
							validateCatalogSchema(theCatalog);
						}
					} catch (SchemaAlteringException ex) {
						this.finalizationFuture.completeExceptionally(ex);
					} finally {
						// close transaction
						this.transactionAccessor.get().close();
					}
				} else {
					// immediately complete future
					this.finalizationFuture.complete(getCatalogVersion());
				}
			}
			// join both futures together and apply termination callback
			this.closedFuture = this.finalizationFuture.whenComplete((aLong, throwable) -> {
				// then apply termination callbacks
				try {
					this.beingClosed = true;
					ofNullable(terminationCallback)
						.ifPresent(it -> it.onTermination(this));
				} finally {
					this.beingClosed = false;
				}
				if (throwable instanceof CancellationException cancellationException) {
					throw cancellationException;
				} else if (throwable instanceof TransactionException transactionException) {
					throw transactionException;
				} else if (throwable instanceof EvitaInvalidUsageException invalidUsageException) {
					throw invalidUsageException;
				} else if (throwable instanceof EvitaInternalError internalError) {
					throw internalError;
				} else if (throwable != null) {
					throw new TransactionException("Unexpected exception occurred while executing transaction!", throwable);
				}
			});
		}
		return this.closedFuture;
	}

	@Interruptible
	@Traced
	@Nonnull
	@Override
	public EntitySchemaBuilder defineEntitySchema(@Nonnull String entityType) {
		assertActive();
		return executeInTransactionIfPossible(
			session -> getCatalog().getCollectionForEntity(entityType)
				.orElseGet(() -> {
					updateCatalogSchema(new CreateEntitySchemaMutation(entityType));
					return getCatalog().getCollectionForEntityOrThrowException(entityType);
				})
				.getSchema()
				.openForWrite()
		);
	}

	@Interruptible
	@Traced
	@Nonnull
	@Override
	public SealedEntitySchema defineEntitySchemaFromModelClass(@Nonnull Class<?> modelClass) {
		assertActive();
		return executeInTransactionIfPossible(session -> {
			final ClassSchemaAnalyzer classSchemaAnalyzer = new ClassSchemaAnalyzer(modelClass, reflectionLookup);
			final AnalysisResult analysisResult = classSchemaAnalyzer.analyze(this);
			updateCatalogSchema(analysisResult.mutations());
			return getEntitySchemaOrThrowException(analysisResult.entityType());
		});
	}

	@Interruptible
	@Traced
	@Nonnull
	@Override
	public SealedEntitySchema defineEntitySchemaFromModelClass(@Nonnull Class<?> modelClass, @Nonnull SchemaPostProcessor postProcessor) {
		assertActive();
		return executeInTransactionIfPossible(session -> {
			final ClassSchemaAnalyzer classSchemaAnalyzer = new ClassSchemaAnalyzer(modelClass, reflectionLookup, postProcessor);
			final AnalysisResult analysisResult = classSchemaAnalyzer.analyze(this);
			if (postProcessor instanceof SchemaPostProcessorCapturingResult capturingResult) {
				capturingResult.captureResult(analysisResult.mutations());
			}
			updateCatalogSchema(analysisResult.mutations());
			return getEntitySchemaOrThrowException(analysisResult.entityType());
		});
	}

	@Interruptible
	@Traced
	@Override
	@Nonnull
	public Optional<SealedEntitySchema> getEntitySchema(@Nonnull String entityType) {
		assertActive();
		final Optional<EntityCollectionContract> collection = getCatalog().getCollectionForEntity(entityType);
		return collection.map(EntityCollectionContract::getSchema);
	}

	@Interruptible
	@Traced
	@Nonnull
	@Override
	public Optional<SealedEntitySchema> getEntitySchema(@Nonnull Class<?> modelClass) throws EntityClassInvalidException {
		return getEntitySchema(
			extractEntityTypeFromClass(modelClass, reflectionLookup)
				.orElseThrow(() -> new CollectionNotFoundException(modelClass))
		);
	}

	@Interruptible
	@Traced
	@Nonnull
	@Override
	public SealedEntitySchema getEntitySchemaOrThrowException(@Nonnull String entityType) {
		assertActive();
		return getCatalog().getCollectionForEntityOrThrowException(entityType).getSchema();
	}

	@Interruptible
	@Traced
	@Nonnull
	@Override
	public SealedEntitySchema getEntitySchemaOrThrowException(@Nonnull Class<?> modelClass) throws CollectionNotFoundException, EntityClassInvalidException {
		return getEntitySchemaOrThrowException(
			extractEntityTypeFromClass(modelClass, reflectionLookup)
				.orElseThrow(() -> new CollectionNotFoundException(modelClass))
		);
	}

	@Interruptible
	@Traced
	@Override
	@Nonnull
	public Set<String> getAllEntityTypes() {
		return getCatalog().getEntityTypes();
	}

	@RepresentsQuery
	@Nonnull
	@Override
	public <S extends Serializable> Optional<S> queryOne(@Nonnull Query query, @Nonnull Class<S> expectedType) throws UnexpectedResultException, UnexpectedResultCountException, InstanceTerminatedException {
		return queryOne(
			new EvitaRequest(
				query.normalizeQuery(),
				OffsetDateTime.now(),
				expectedType,
				extractEntityTypeFromClass(expectedType, reflectionLookup).orElse(null)
			)
		);
	}

	@Interruptible
	@RepresentsQuery
	@Nonnull
	@Override
	public <S extends Serializable> List<S> queryList(@Nonnull Query query, @Nonnull Class<S> expectedType)
		throws UnexpectedResultException, InstanceTerminatedException {
		return query(query, expectedType).getRecordData();
	}

	@Interruptible
	@RepresentsQuery
	@Nonnull
	@Override
	public <S extends Serializable, T extends EvitaResponse<S>> T query(@Nonnull Query query, @Nonnull Class<S> expectedType) throws UnexpectedResultException, InstanceTerminatedException {
		final EvitaRequest request = new EvitaRequest(
			query.normalizeQuery(),
			OffsetDateTime.now(),
			expectedType,
			extractEntityTypeFromClass(expectedType, reflectionLookup).orElse(null)
		);

		return query(request);
	}

	@Interruptible
	@Traced
	@RepresentsQuery
	@Nonnull
	@Override
	public Optional<SealedEntity> getEntity(@Nonnull String entityType, int primaryKey, EntityContentRequire... require) {
		return getEntity(entityType, primaryKey, LIVE_SCOPE_ONLY, require);
	}

	@Interruptible
	@Traced
	@RepresentsQuery
	@Nonnull
	@Override
	public Optional<SealedEntity> getEntity(@Nonnull String entityType, int primaryKey, @Nonnull Scope[] scopes, EntityContentRequire... require) {
		assertActive();
		isTrue(scopes.length > 0, "At least one scope must be provided!");

		final EntityFetchEvent fetchEvent = new EntityFetchEvent(getCatalogName(), entityType);

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
			EntityReference.class,
			null
		);
		final EntityCollectionContract entityCollection = getCatalog().getCollectionForEntityOrThrowException(entityType);
		final Optional<SealedEntity> resultEntity = entityCollection.getEntity(
			primaryKey,
			evitaRequest,
			this
		);

		// emit the event
		final Optional<ServerEntityDecorator> serverEntityDecorator = resultEntity
			.map(ServerEntityDecorator.class::cast);
		fetchEvent.finish(
			serverEntityDecorator
				.map(ServerEntityDecorator::getIoFetchCount)
				.orElse(0),
			serverEntityDecorator
				.map(ServerEntityDecorator::getIoFetchedBytes)
				.orElse(0)
		).commit();

		return resultEntity;
	}

	@Interruptible
	@Traced
	@RepresentsQuery
	@Nonnull
	@Override
	public <T extends Serializable> Optional<T> getEntity(@Nonnull Class<T> expectedType, int primaryKey, @Nonnull Scope[] scope, EntityContentRequire... require) {
		assertActive();
		final String entityType = extractEntityTypeFromClass(expectedType, reflectionLookup)
			.orElseThrow(() -> new CollectionNotFoundException(expectedType));

		final EntityFetchEvent fetchEvent = new EntityFetchEvent(getCatalogName(), entityType);

		final EntityCollectionContract entityCollection = getCatalog().getCollectionForEntityOrThrowException(entityType);
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
		final Optional<T> resultEntity = entityCollection.getEntity(
			primaryKey,
			evitaRequest,
			this
		).map(it -> this.createEntityProxy(expectedType, it));

		// emit the event
		final Optional<ServerEntityDecorator> serverEntityDecorator = resultEntity
			.map(it -> {
				final EntityContract theEntity = (
					it instanceof SealedEntityProxy sealedEntityProxy ?
						sealedEntityProxy.entity() : (it instanceof EntityContract ec ? ec : null)
				);
				return theEntity instanceof ServerEntityDecorator sed ? sed : null;
			});
		fetchEvent.finish(
			serverEntityDecorator
				.map(ServerEntityDecorator::getIoFetchCount)
				.orElse(0),
			serverEntityDecorator
				.map(ServerEntityDecorator::getIoFetchedBytes)
				.orElse(0)
		).commit();

		return resultEntity;
	}

	@Interruptible
	@Traced
	@RepresentsQuery
	@Nonnull
	@Override
	public <T extends Serializable> T enrichEntity(@Nonnull T partiallyLoadedEntity, EntityContentRequire... require) {
		assertActive();
		final String entityType = getEntityTypeFromEntity(partiallyLoadedEntity, reflectionLookup);
		final EntityEnrichEvent enrichEvent = new EntityEnrichEvent(getCatalogName(), entityType);
		final EntityCollectionContract entityCollection = getCatalog().getCollectionForEntityOrThrowException(entityType);
		final EvitaRequest evitaRequest = new EvitaRequest(
			Query.query(
				collection(entityType),
				require(
					entityFetch(require)
				)
			),
			OffsetDateTime.now(),
			EntityReference.class,
			entityType
		);
		if (partiallyLoadedEntity instanceof SealedEntityProxy sealedEntityProxy) {
			final ServerEntityDecorator enrichedEntity = (ServerEntityDecorator) entityCollection.enrichEntity(
				sealedEntityProxy.entity(),
				evitaRequest,
				this
			);

			// emit the event
			enrichEvent.finish(
				enrichedEntity.getIoFetchCount(),
				enrichedEntity.getIoFetchedBytes()
			).commit();

			//noinspection unchecked
			return (T) this.createEntityProxy(
				sealedEntityProxy.getProxyClass(),
				enrichedEntity
			);
		} else {
			isTrue(partiallyLoadedEntity instanceof EntityDecorator, "Expected entity decorator in the input.");
			final ServerEntityDecorator enrichedEntity = (ServerEntityDecorator) entityCollection.enrichEntity(
				(EntityDecorator) partiallyLoadedEntity,
				evitaRequest,
				this
			);
			// emit the event
			enrichEvent.finish(
				enrichedEntity.getIoFetchCount(),
				enrichedEntity.getIoFetchedBytes()
			).commit();

			//noinspection unchecked
			return (T) enrichedEntity;
		}
	}

	@Interruptible
	@Traced
	@RepresentsQuery
	@Nonnull
	@Override
	public <T extends Serializable> T enrichOrLimitEntity(@Nonnull T partiallyLoadedEntity, EntityContentRequire... require) {
		assertActive();
		final String entityType = getEntityTypeFromEntity(partiallyLoadedEntity, reflectionLookup);
		final EntityEnrichEvent enrichEvent = new EntityEnrichEvent(getCatalogName(), entityType);

		final EntityCollectionContract entityCollection = getCatalog().getCollectionForEntity(entityType)
			.orElseThrow(() -> new CollectionNotFoundException(entityType));
		final EvitaRequest evitaRequest = new EvitaRequest(
			Query.query(
				collection(entityType),
				require(
					entityFetch(require)
				)
			),
			OffsetDateTime.now(),
			partiallyLoadedEntity instanceof SealedEntityProxy sealedEntityProxy ?
				sealedEntityProxy.getProxyClass() : partiallyLoadedEntity.getClass(),
			entityType
		);
		if (partiallyLoadedEntity instanceof SealedEntityProxy sealedEntityProxy) {
			final ServerEntityDecorator enrichedEntity = (ServerEntityDecorator) entityCollection.limitEntity(
				entityCollection.enrichEntity(
					sealedEntityProxy.entity(),
					evitaRequest,
					this
				),
				evitaRequest, this
			);

			// emit the event
			enrichEvent.finish(
				enrichedEntity.getIoFetchCount(),
				enrichedEntity.getIoFetchedBytes()
			).commit();

			//noinspection unchecked
			return (T) this.createEntityProxy(
				sealedEntityProxy.getProxyClass(),
				enrichedEntity
			);
		} else {
			isTrue(partiallyLoadedEntity instanceof EntityDecorator, "Expected entity decorator in the input.");
			final ServerEntityDecorator enrichedEntity = (ServerEntityDecorator) entityCollection.limitEntity(
				entityCollection.enrichEntity((EntityDecorator) partiallyLoadedEntity, evitaRequest, this),
				evitaRequest, this
			);

			// emit the event
			enrichEvent.finish(
				enrichedEntity.getIoFetchCount(),
				enrichedEntity.getIoFetchedBytes()
			).commit();

			//noinspection unchecked
			return (T) enrichedEntity;
		}
	}

	@Interruptible
	@Traced
	@Override
	public int updateCatalogSchema(@Nonnull LocalCatalogSchemaMutation... schemaMutation) throws SchemaAlteringException {
		assertActive();
		if (ArrayUtils.isEmpty(schemaMutation)) {
			return 0;
		}

		return executeInTransactionIfPossible(session -> {
			getCatalog().updateSchema(schemaMutation);
			return getCatalogSchema().version();
		});
	}

	@Interruptible
	@Traced
	@Nonnull
	@Override
	public SealedCatalogSchema updateAndFetchCatalogSchema(@Nonnull LocalCatalogSchemaMutation... schemaMutation) throws SchemaAlteringException {
		assertActive();
		if (ArrayUtils.isEmpty(schemaMutation)) {
			return getCatalogSchema();
		}
		return executeInTransactionIfPossible(session -> {
			getCatalog().updateSchema(schemaMutation);
			return getCatalogSchema();
		});
	}

	@Interruptible
	@Traced
	@Override
	public int updateEntitySchema(@Nonnull ModifyEntitySchemaMutation schemaMutation) throws SchemaAlteringException {
		return updateAndFetchEntitySchema(schemaMutation).version();
	}

	@Interruptible
	@Traced
	@Nonnull
	@Override
	public SealedEntitySchema updateAndFetchEntitySchema(@Nonnull ModifyEntitySchemaMutation schemaMutation) throws SchemaAlteringException {
		assertActive();
		return executeInTransactionIfPossible(session -> {
			getCatalog().updateSchema(schemaMutation);
			return getEntitySchemaOrThrowException(schemaMutation.getEntityType());
		});
	}

	@Interruptible
	@Traced
	@Override
	public boolean deleteCollection(@Nonnull String entityType) {
		assertActive();
		return executeInTransactionIfPossible(session -> getCatalog().deleteCollectionOfEntity(entityType, session));
	}

	@Interruptible
	@Traced
	@Override
	public boolean deleteCollection(@Nonnull Class<?> modelClass) throws EntityClassInvalidException {
		return deleteCollection(
			extractEntityTypeFromClass(modelClass, reflectionLookup)
				.orElseThrow(() -> new CollectionNotFoundException(modelClass))
		);
	}

	@Interruptible
	@Traced
	@Override
	public boolean renameCollection(@Nonnull String entityType, @Nonnull String newName) {
		assertActive();
		return executeInTransactionIfPossible(session -> getCatalog().renameCollectionOfEntity(entityType, newName, session));
	}

	@Interruptible
	@Traced
	@Override
	public boolean replaceCollection(@Nonnull String entityTypeToBeReplaced, @Nonnull String entityTypeToBeReplacedWith) {
		assertActive();
		return executeInTransactionIfPossible(session -> getCatalog().replaceCollectionOfEntity(entityTypeToBeReplaced, entityTypeToBeReplacedWith, session));
	}

	@Interruptible
	@Traced
	@RepresentsQuery
	@Override
	public int getEntityCollectionSize(@Nonnull String entityType) {
		assertActive();
		return getCatalog().getCollectionForEntityOrThrowException(entityType).size();
	}

	@Nonnull
	@Override
	public EntityBuilder createNewEntity(@Nonnull String entityType) {
		assertActive();
		return executeInTransactionIfPossible(session -> {
			final EntityCollectionContract collection = getCatalog().getOrCreateCollectionForEntity(entityType, session);
			return collection.createNewEntity();
		});
	}

	@Nonnull
	@Override
	public <S extends Serializable> S createNewEntity(@Nonnull Class<S> expectedType) {
		return proxyFactory.createEntityProxy(
			expectedType,
			createNewEntity(
				extractEntityTypeFromClass(expectedType, reflectionLookup)
					.orElseThrow(() -> new CollectionNotFoundException(expectedType))
			),
			getEntitySchemaIndex()
		);
	}

	@Nonnull
	@Override
	public EntityBuilder createNewEntity(@Nonnull String entityType, int primaryKey) {
		assertActive();
		return executeInTransactionIfPossible(session -> {
			final EntityCollectionContract collection = getCatalog().getOrCreateCollectionForEntity(entityType, session);
			return collection.createNewEntity(primaryKey);
		});
	}

	@Nonnull
	@Override
	public <S extends Serializable> S createNewEntity(@Nonnull Class<S> expectedType, int primaryKey) {
		return proxyFactory.createEntityProxy(
			expectedType,
			createNewEntity(
				extractEntityTypeFromClass(expectedType, reflectionLookup)
					.orElseThrow(() -> new CollectionNotFoundException(expectedType)),
				primaryKey
			),
			getEntitySchemaIndex()
		);
	}

	@Interruptible
	@Traced
	@RepresentsMutation
	@Nonnull
	@Override
	public <S extends Serializable> EntityReference upsertEntity(@Nonnull S customEntity) {
		if (customEntity instanceof SealedEntityProxy sealedEntityProxy) {
			return sealedEntityProxy.getEntityBuilderWithCallback()
				.map(entityBuilderWithCallback -> {
					final EntityReference entityReference = upsertEntity(entityBuilderWithCallback.builder());
					entityBuilderWithCallback.updateEntityReference(entityReference);
					return entityReference;
				})
				.orElseGet(() -> {
					// no modification occurred, we can return the reference to the original entity
					// the `toInstance` method should be cost-free in this case, as no modifications occurred
					final EntityContract entity = sealedEntityProxy.entity();
					return new EntityReference(entity.getType(), Objects.requireNonNull(entity.getPrimaryKey()));
				});
		} else if (customEntity instanceof InstanceEditor<?> ie) {
			return ie.toMutation()
				.map(this::upsertEntity)
				.orElseGet(() -> {
					// no modification occurred, we can return the reference to the original entity
					// the `toInstance` method should be cost-free in this case, as no modifications occurred
					final EntityContract entity = (EntityContract) ie.toInstance();
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

	@Interruptible
	@Traced
	@RepresentsMutation
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

	@Interruptible
	@Traced
	@RepresentsMutation
	@Nonnull
	@Override
	public EntityReference upsertEntity(@Nonnull EntityMutation entityMutation) {
		assertActive();
		return executeInTransactionIfPossible(session -> {
			final EntityCollectionContract collection = getCatalog().getOrCreateCollectionForEntity(entityMutation.getEntityType(), session);
			return collection.upsertEntity(entityMutation);
		});
	}

	@Interruptible
	@Traced
	@RepresentsMutation
	@RepresentsQuery
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

	@Interruptible
	@Traced
	@RepresentsMutation
	@RepresentsQuery
	@Nonnull
	@Override
	public SealedEntity upsertAndFetchEntity(@Nonnull EntityMutation entityMutation, EntityContentRequire... require) {
		assertActive();
		return executeInTransactionIfPossible(session -> {
			final EntityCollectionContract collection = getCatalog().getOrCreateCollectionForEntity(entityMutation.getEntityType(), session);
			return collection.upsertAndFetchEntity(
				entityMutation,
				new EvitaRequest(
					Query.query(
						collection(entityMutation.getEntityType()),
						filterBy(
							createQueryForFetchAfterUpsert(entityMutation, require)
						),
						require(
							entityFetch(require)
						)
					),
					OffsetDateTime.now(),
					SealedEntity.class,
					null
				),
				this
			);
		});
	}

	@Interruptible
	@Traced
	@RepresentsMutation
	@Override
	public boolean deleteEntity(@Nonnull String entityType, int primaryKey) {
		assertActive();
		return executeInTransactionIfPossible(session -> {
			final EntityCollectionContract collection = getCatalog().getOrCreateCollectionForEntity(entityType, session);
			return collection.deleteEntity(primaryKey);
		});
	}

	@Interruptible
	@Traced
	@RepresentsMutation
	@Override
	public boolean deleteEntity(@Nonnull Class<?> modelClass, int primaryKey) throws EntityClassInvalidException {
		return deleteEntity(
			extractEntityTypeFromClass(modelClass, reflectionLookup)
				.orElseThrow(() -> new CollectionNotFoundException(modelClass)),
			primaryKey
		);
	}

	@Interruptible
	@Traced
	@RepresentsMutation
	@RepresentsQuery
	@Nonnull
	@Override
	public Optional<SealedEntity> deleteEntity(@Nonnull String entityType, int primaryKey, EntityContentRequire... require) {
		return deleteEntityInternal(entityType, SealedEntity.class, primaryKey, require);
	}

	@Interruptible
	@Traced
	@RepresentsMutation
	@RepresentsQuery
	@Nonnull
	@Override
	public <T extends Serializable> Optional<T> deleteEntity(@Nonnull Class<T> modelClass, int primaryKey, EntityContentRequire... require) throws EntityClassInvalidException {
		return deleteEntityInternal(
			extractEntityTypeFromClass(modelClass, reflectionLookup)
				.orElseThrow(() -> new CollectionNotFoundException(modelClass)),
			modelClass, primaryKey, require
		);
	}

	@Interruptible
	@Traced
	@RepresentsMutation
	@Override
	public int deleteEntityAndItsHierarchy(@Nonnull String entityType, int primaryKey) {
		assertActive();
		return executeInTransactionIfPossible(session -> {
			final EntityCollectionContract collection = getCatalog().getOrCreateCollectionForEntity(entityType, session);
			isTrue(
				collection.getSchema().isWithHierarchy(),
				"Entity type " + entityType + " doesn't represent a hierarchical entity!"
			);
			return collection.deleteEntityAndItsHierarchy(primaryKey, this);
		});
	}

	@Interruptible
	@Traced
	@RepresentsMutation
	@RepresentsQuery
	@Nonnull
	@Override
	public DeletedHierarchy<SealedEntity> deleteEntityAndItsHierarchy(@Nonnull String entityType, int primaryKey, EntityContentRequire... require) {
		return deleteEntityAndItsHierarchyInternal(entityType, SealedEntity.class, primaryKey, require);
	}

	@Interruptible
	@Traced
	@RepresentsMutation
	@RepresentsQuery
	@Nonnull
	@Override
	public <T extends Serializable> DeletedHierarchy<T> deleteEntityAndItsHierarchy(@Nonnull Class<T> modelClass, int primaryKey, EntityContentRequire... require) throws EvitaInvalidUsageException {
		return deleteEntityAndItsHierarchyInternal(
			extractEntityTypeFromClass(modelClass, reflectionLookup)
				.orElseThrow(() -> new CollectionNotFoundException(modelClass)),
			modelClass, primaryKey, require
		);
	}

	@Interruptible
	@Traced
	@RepresentsMutation
	@Override
	public int deleteEntities(@Nonnull Query query) {
		assertActive();
		final EvitaRequest request = new EvitaRequest(
			query.normalizeQuery(),
			OffsetDateTime.now(),
			EntityReference.class,
			null
		);
		return executeInTransactionIfPossible(session -> {
			final EntityCollectionContract collection = getCatalog()
				.getOrCreateCollectionForEntity(request.getEntityTypeOrThrowException("entities to be deleted"), session);
			return collection.deleteEntities(request, session);
		});
	}

	@Interruptible
	@Traced
	@RepresentsMutation
	@RepresentsQuery
	@Nonnull
	@Override
	public SealedEntity[] deleteSealedEntitiesAndReturnBodies(@Nonnull Query query) {
		assertActive();
		final EvitaRequest request = new EvitaRequest(
			query.normalizeQuery(),
			OffsetDateTime.now(),
			SealedEntity.class,
			null
		);
		return executeInTransactionIfPossible(session -> {
			final EntityCollectionContract collection = getCatalog()
				.getOrCreateCollectionForEntity(request.getEntityTypeOrThrowException("entities to be deleted"), session);
			return collection.deleteEntitiesAndReturnThem(request, session);
		});
	}

	@Interruptible
	@Traced
	@RepresentsMutation
	@Override
	public boolean archiveEntity(@Nonnull String entityType, int primaryKey) {
		assertActive();
		return executeInTransactionIfPossible(session -> {
			final EntityCollectionContract collection = getCatalog().getOrCreateCollectionForEntity(entityType, session);
			return collection.archiveEntity(primaryKey);
		});
	}

	@Interruptible
	@Traced
	@RepresentsMutation
	@Override
	public boolean archiveEntity(@Nonnull Class<?> modelClass, int primaryKey) throws EntityClassInvalidException {
		return archiveEntity(
			extractEntityTypeFromClass(modelClass, reflectionLookup)
				.orElseThrow(() -> new CollectionNotFoundException(modelClass)),
			primaryKey
		);
	}

	@Interruptible
	@Traced
	@RepresentsMutation
	@Nonnull
	@Override
	public Optional<SealedEntity> archiveEntity(@Nonnull String entityType, int primaryKey, EntityContentRequire... require) {
		return archiveEntityInternal(entityType, SealedEntity.class, primaryKey, require);
	}

	@Interruptible
	@Traced
	@RepresentsMutation
	@Nonnull
	@Override
	public <T extends Serializable> Optional<T> archiveEntity(@Nonnull Class<T> modelClass, int primaryKey, EntityContentRequire... require) throws EntityClassInvalidException {
		return archiveEntityInternal(
			extractEntityTypeFromClass(modelClass, reflectionLookup)
				.orElseThrow(() -> new CollectionNotFoundException(modelClass)),
			modelClass, primaryKey, require
		);
	}

	@Interruptible
	@Traced
	@RepresentsMutation
	@Override
	public boolean restoreEntity(@Nonnull String entityType, int primaryKey) {
		assertActive();
		return executeInTransactionIfPossible(session -> {
			final EntityCollectionContract collection = getCatalog().getOrCreateCollectionForEntity(entityType, session);
			return collection.restoreEntity(primaryKey);
		});
	}

	@Interruptible
	@Traced
	@RepresentsMutation
	@Override
	public boolean restoreEntity(@Nonnull Class<?> modelClass, int primaryKey) throws EntityClassInvalidException {
		return restoreEntity(
			extractEntityTypeFromClass(modelClass, reflectionLookup)
				.orElseThrow(() -> new CollectionNotFoundException(modelClass)),
			primaryKey
		);
	}

	@Interruptible
	@Traced
	@RepresentsMutation
	@Nonnull
	@Override
	public Optional<SealedEntity> restoreEntity(@Nonnull String entityType, int primaryKey, EntityContentRequire... require) {
		return restoreEntityInternal(entityType, SealedEntity.class, primaryKey, require);
	}

	@Interruptible
	@Traced
	@RepresentsMutation
	@Nonnull
	@Override
	public <T extends Serializable> Optional<T> restoreEntity(@Nonnull Class<T> modelClass, int primaryKey, EntityContentRequire... require) throws EntityClassInvalidException {
		return restoreEntityInternal(
			extractEntityTypeFromClass(modelClass, reflectionLookup)
				.orElseThrow(() -> new CollectionNotFoundException(modelClass)),
			modelClass, primaryKey, require
		);
	}

	@Nonnull
	@Override
	public CatalogVersion getCatalogVersionAt(@Nullable OffsetDateTime moment) throws TemporalDataNotAvailableException {
		assertActive();
		return getCatalog().getCatalogVersionAt(moment);
	}

	@Nonnull
	@Override
	public Stream<ChangeCatalogCapture> getMutationsHistory(@Nonnull ChangeCatalogCaptureRequest criteria) {
		final MutationPredicate mutationPredicate = MutationPredicateFactory.createReversedChangeCatalogCapturePredicate(criteria);
		return getCatalog()
			.getReversedCommittedMutationStream(criteria.sinceVersion())
			.flatMap(it -> it.toChangeCatalogCapture(mutationPredicate, criteria.content()));
	}

	@Interruptible
	@Traced
	@Nonnull
	@Override
	public Task<?, FileForFetch> backupCatalog(@Nullable OffsetDateTime pastMoment, boolean includingWAL) throws TemporalDataNotAvailableException {
		// added read only check
		isTrue(
			!isReadOnly(),
			ReadOnlyException::new
		);
		return getCatalog().backup(pastMoment, includingWAL);
	}

	@Nonnull
	@Override
	public Optional<UUID> getOpenedTransactionId() {
		return ofNullable(this.transactionAccessor.get())
			.filter(it -> !it.isClosed())
			.map(Transaction::getTransactionId);
	}

	@Override
	public boolean isRollbackOnly() {
		return ofNullable(this.transactionAccessor.get())
			.map(Transaction::isRollbackOnly)
			.orElse(false);
	}

	@Override
	public void setRollbackOnly() {
		getOpenedTransaction()
			.ifPresentOrElse(
				Transaction::setRollbackOnly,
				() -> {
					throw new UnexpectedTransactionStateException("No transaction has been opened!");
				}
			);
	}

	@Override
	public boolean isReadOnly() {
		return !this.sessionTraits.isReadWrite();
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

	@Interruptible
	@RepresentsQuery
	@Nonnull
	@Override
	public <S extends Serializable> Optional<S> queryOne(@Nonnull EvitaRequest evitaRequest)
		throws UnexpectedResultException, UnexpectedResultCountException, InstanceTerminatedException {

		assertActive();
		final String entityType = evitaRequest.getEntityType();
		final EvitaResponse<S> response;
		final CatalogContract theCatalog = getCatalog();
		if (entityType == null) {
			response = theCatalog.getEntities(evitaRequest, this);
		} else {
			final EntityCollectionContract entityCollection = theCatalog.getCollectionForEntityOrThrowException(entityType);
			response = entityCollection.getEntities(evitaRequest, this);
		}

		final List<S> recordData = response.getRecordData();
		if (recordData.isEmpty()) {
			return empty();
		} else if (recordData.size() > 1) {
			throw new UnexpectedResultCountException(recordData.size());
		} else {
			return of(recordData.get(0));
		}
	}

	@Interruptible
	@RepresentsQuery
	@Nonnull
	@Override
	public <S extends Serializable> List<S> queryList(@Nonnull EvitaRequest evitaRequest) throws UnexpectedResultException, InstanceTerminatedException {
		//noinspection unchecked
		return (List<S>) query(evitaRequest).getRecordData();
	}

	@Interruptible
	@RepresentsQuery
	@Nonnull
	@Override
	public <S extends Serializable, T extends EvitaResponse<S>> T query(@Nonnull EvitaRequest evitaRequest) throws UnexpectedResultException, InstanceTerminatedException {
		assertActive();
		final String entityType = evitaRequest.getEntityType();
		final T response;
		final CatalogContract theCatalog = getCatalog();
		if (entityType == null) {
			response = theCatalog.getEntities(evitaRequest, this);
		} else {
			final EntityCollectionContract entityCollection = theCatalog.getCollectionForEntityOrThrowException(entityType);
			response = entityCollection.getEntities(evitaRequest, this);
		}
		return response;
	}

	@Interruptible
	@Traced
	@Override
	public <T> T execute(@Nonnull Function<EvitaSessionContract, T> logic) {
		return executeInTransactionIfPossible(logic);
	}

	@Interruptible
	@Traced
	@Override
	public void execute(@Nonnull Consumer<EvitaSessionContract> logic) {
		executeInTransactionIfPossible(
			evitaSessionContract -> {
				logic.accept(evitaSessionContract);
				return null;
			}
		);
	}

	@Override
	public boolean methodIsRunning() {
		return false;
	}

	/**
	 * Retrieves a CompletableFuture that represents the finalization status of a session. If the catalog is in
	 * transactional mode, the future will respect the requested {@link CommitBehavior} bound to the current transaction.
	 *
	 * @return completable future returning new catalog version introduced by this session
	 */
	@Nonnull
	@Override
	public CompletableFuture<Long> getFinalizationFuture() {
		return this.finalizationFuture;
	}

	/**
	 * Returns a transaction wrapped in optional. If no transaction is bound to the session, an empty optional is returned.
	 *
	 * @return an Optional containing the transaction, if it exists; otherwise, an empty Optional.
	 */
	@Nonnull
	public Optional<Transaction> getTransaction() {
		return ofNullable(transactionAccessor.get());
	}

	/**
	 * Returns an opened transaction wrapped in optional. If no transaction is opened, an empty optional is returned.
	 *
	 * @return an Optional containing the opened transaction, if it exists and is not closed; otherwise, an empty Optional.
	 */
	@Nonnull
	public Optional<Transaction> getOpenedTransaction() {
		return ofNullable(transactionAccessor.get())
			.filter(it -> !it.isClosed());
	}

	@Override
	public String toString() {
		return (isReadOnly() ?
			"Read-only session: " :
			(getOpenedTransactionId().map(txId -> "Read-write session (tx `" + txId + "` opened): ").orElse("Read-write session: ")))
			+ id + (isActive() ? "" : " (terminated)");
	}

	/**
	 * Determines if the current execution is at the root level.
	 *
	 * @return {@code true} if the execution is at the root level, {@code false} otherwise.
	 */
	boolean isRootLevelExecution() {
		return nestLevel == 1;
	}

	/**
	 * Increases the session execution nest level by one.
	 *
	 * @return the incremented nest level
	 */
	int increaseNestLevel() {
		return nestLevel++;
	}

	/**
	 * Decreases the session execution nest level by one.
	 *
	 * @return The updated nest level.
	 */
	int decreaseNestLevel() {
		return nestLevel--;
	}

	/**
	 * Validates the schema of the given catalog.
	 *
	 * @param theCatalog  the catalog to be validated, must not be null
	 */
	private void validateCatalogSchema(
		@Nonnull CatalogContract theCatalog
	) throws SchemaAlteringException {
		try {
			final SealedCatalogSchema catalogSchema = theCatalog.getSchema();
			final int catalogVersion = catalogSchema.version();
			if (catalogVersion > this.startCatalogSchemaVersion) {
				catalogSchema.validate();
			}
		} catch (SchemaAlteringException ex) {
			/* if validation fails and transaction is available, mark it as rollback only */
			ofNullable(this.transactionAccessor.get())
				.ifPresent(Transaction::setRollbackOnly);
			throw ex;
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
	public <S> S createEntityProxy(@Nonnull Class<S> contract, @Nonnull SealedEntity sealedEntity) {
		return this.proxyFactory.createEntityProxy(contract, sealedEntity, getEntitySchemaIndex());
	}

	/**
	 * Returns map with current {@link Catalog#getSchema() catalog} {@link EntitySchemaContract entity schema} instances
	 * indexed by their {@link EntitySchemaContract#getName() name}.
	 *
	 * @return map with current {@link Catalog#getSchema() catalog} {@link EntitySchemaContract entity schema} instances
	 */
	@Nonnull
	private Map<String, EntitySchemaContract> getEntitySchemaIndex() {
		return catalog.get().getEntitySchemaIndex();
	}

	/**
	 * Internal implementation for deleting the entity.
	 *
	 * @see #deleteEntity(String, int)
	 * @see #deleteEntity(Class, int)
	 */
	@Nonnull
	private <T> Optional<T> deleteEntityInternal(
		@Nonnull String entityType,
		@Nonnull Class<T> expectedType,
		int primaryKey,
		EntityContentRequire... require
	) {
		assertActive();
		return executeInTransactionIfPossible(session -> {
			final EntityCollectionContract collection = getCatalog().getOrCreateCollectionForEntity(entityType, session);
			//noinspection unchecked
			return (Optional<T>) collection.deleteEntity(
				new EvitaRequest(
					Query.query(
						collection(entityType),
						filterBy(entityPrimaryKeyInSet(primaryKey)),
						require(
							entityFetch(require)
						)
					),
					OffsetDateTime.now(),
					expectedType,
					extractEntityTypeFromClass(expectedType, reflectionLookup)
						.orElse(null)
				),
				this
			);
		});
	}

	/**
	 * Internal implementation for deleting the entity with hierarchy.
	 *
	 * @see #deleteEntityAndItsHierarchy(String, int, EntityContentRequire...)
	 * @see #deleteEntityAndItsHierarchy(Class, int, EntityContentRequire...)
	 */
	@Nonnull
	private <T> DeletedHierarchy<T> deleteEntityAndItsHierarchyInternal(
		@Nonnull String entityType,
		@Nonnull Class<T> expectedType,
		int primaryKey,
		EntityContentRequire... require
	) {
		assertActive();
		//noinspection unchecked
		return (DeletedHierarchy<T>) executeInTransactionIfPossible(session -> {
			final EntityCollectionContract collection = getCatalog().getOrCreateCollectionForEntity(entityType, session);
			isTrue(
				collection.getSchema().isWithHierarchy(),
				"Entity type " + entityType + " doesn't represent a hierarchical entity!"
			);
			return collection.deleteEntityAndItsHierarchy(
				new EvitaRequest(
					Query.query(
						collection(entityType),
						filterBy(entityPrimaryKeyInSet(primaryKey)),
						require(
							entityFetch(require)
						)
					),
					OffsetDateTime.now(),
					expectedType,
					extractEntityTypeFromClass(expectedType, reflectionLookup)
						.orElse(null)
				),
				this
			);
		});
	}

	/**
	 * Internal implementation for archiving of the entity.
	 *
	 * @see #archiveEntity(String, int)
	 * @see #archiveEntity(Class, int)
	 */
	@Nonnull
	private <T> Optional<T> archiveEntityInternal(
		@Nonnull String entityType,
		@Nonnull Class<T> expectedType,
		int primaryKey,
		EntityContentRequire... require
	) {
		assertActive();
		return executeInTransactionIfPossible(session -> {
			final EntityCollectionContract collection = getCatalog().getOrCreateCollectionForEntity(entityType, session);
			//noinspection unchecked
			return (Optional<T>) collection.archiveEntity(
				new EvitaRequest(
					Query.query(
						collection(entityType),
						filterBy(entityPrimaryKeyInSet(primaryKey)),
						require(
							entityFetch(require)
						)
					),
					OffsetDateTime.now(),
					expectedType,
					extractEntityTypeFromClass(expectedType, reflectionLookup)
						.orElse(null)
				),
				this
			);
		});
	}

	/**
	 * Internal implementation for restoring of the entity.
	 *
	 * @see #restoreEntity(String, int)
	 * @see #restoreEntity(Class, int)
	 */
	@Nonnull
	private <T> Optional<T> restoreEntityInternal(
		@Nonnull String entityType,
		@Nonnull Class<T> expectedType,
		int primaryKey,
		EntityContentRequire... require
	) {
		assertActive();
		return executeInTransactionIfPossible(session -> {
			final EntityCollectionContract collection = getCatalog().getOrCreateCollectionForEntity(entityType, session);
			//noinspection unchecked
			return (Optional<T>) collection.restoreEntity(
				new EvitaRequest(
					Query.query(
						collection(entityType),
						filterBy(entityPrimaryKeyInSet(primaryKey)),
						require(
							entityFetch(require)
						)
					),
					OffsetDateTime.now(),
					expectedType,
					extractEntityTypeFromClass(expectedType, reflectionLookup)
						.orElse(null)
				),
				this
			);
		});
	}

	/**
	 * Returns the catalog instance this session refers to.
	 */
	private CatalogContract getCatalog() {
		return catalog.get();
	}

	/**
	 * Creates new transaction a wraps it into carrier object.
	 */
	private Transaction createTransaction(@Nonnull CommitBehavior commitBehaviour) {
		isTrue(
			!isReadOnly(),
			ReadOnlyException::new
		);
		final CatalogContract currentCatalog = getCatalog();
		if (currentCatalog instanceof Catalog theCatalog) {
			final Transaction transaction = new Transaction(
				UUID.randomUUID(),
				new TransactionWalFinalizer(
					theCatalog,
					getId(),
					commitBehaviour,
					theCatalog::createIsolatedWalService,
					this.finalizationFuture
				),
				false
			);
			// when the session is marked as "dry run" we never commit the transaction but always roll-back
			if (sessionTraits.isDryRun()) {
				transaction.setRollbackOnly();
			}
			return transaction;
		} else {
			throw new CatalogCorruptedException((CorruptedCatalog) currentCatalog);
		}
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
	private Transaction createAndInitTransaction() {
		if (!sessionTraits.isReadWrite()) {
			throw new TransactionNotSupportedException("Transaction cannot be opened in read only session!");
		}
		if (!getCatalog().supportsTransaction()) {
			throw new TransactionNotSupportedException("Catalog " + getCatalog().getName() + " doesn't support transaction yet. Call `goLiveAndClose()` method first!");
		}
		final Transaction tx = createTransaction(commitBehaviour);
		transactionAccessor.getAndUpdate(transaction -> {
			isPremiseValid(transaction == null, "Transaction unexpectedly found!");
			return tx;
		});
		return tx;
	}

	/**
	 * Executes passed lambda in existing transaction or throws exception.
	 *
	 * @throws UnexpectedTransactionStateException if transaction is not open
	 */
	private <T> T executeInTransactionIfPossible(@Nonnull Function<EvitaSessionContract, T> logic) {
		if (transactionAccessor.get() == null && getCatalog().supportsTransaction()) {
			try (final Transaction newTransaction = createTransaction(commitBehaviour)) {
				increaseNestLevel();
				transactionAccessor.set(newTransaction);
				return Transaction.executeInTransactionIfProvided(
					newTransaction,
					() -> logic.apply(this),
					isRootLevelExecution()
				);
			} catch (Throwable ex) {
				ofNullable(transactionAccessor.get())
					.ifPresent(tx -> {
						if (isRootLevelExecution()) {
							tx.setRollbackOnlyWithException(ex);
						}
					});
				throw ex;
			} finally {
				decreaseNestLevel();
				transactionAccessor.set(null);
			}
		} else {
			// the transaction might already exist
			try {
				increaseNestLevel();
				return Transaction.executeInTransactionIfProvided(
					transactionAccessor.get(),
					() -> logic.apply(this),
					isRootLevelExecution()
				);
			} catch (Throwable ex) {
				if (isRootLevelExecution()) {
					ofNullable(transactionAccessor.get())
						.ifPresentOrElse(
							tx -> tx.setRollbackOnlyWithException(ex),
							() -> this.finalizationFuture.completeExceptionally(ex)
						);
				}
				throw ex;
			} finally {
				decreaseNestLevel();
			}
		}
	}

}
