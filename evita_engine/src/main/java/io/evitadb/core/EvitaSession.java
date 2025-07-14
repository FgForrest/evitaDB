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

package io.evitadb.core;

import io.evitadb.api.*;
import io.evitadb.api.CommitProgress.CommitVersions;
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
import io.evitadb.api.query.require.DataInLocales;
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
import io.evitadb.api.requestResponse.trafficRecording.TrafficRecording;
import io.evitadb.api.requestResponse.trafficRecording.TrafficRecordingCaptureRequest;
import io.evitadb.api.task.ServerTask;
import io.evitadb.api.task.Task;
import io.evitadb.api.task.TaskStatus;
import io.evitadb.core.async.Interruptible;
import io.evitadb.core.async.Scheduler;
import io.evitadb.core.cdc.predicate.MutationPredicateFactory;
import io.evitadb.core.metric.event.query.EntityEnrichEvent;
import io.evitadb.core.metric.event.query.EntityFetchEvent;
import io.evitadb.core.query.response.ServerEntityDecorator;
import io.evitadb.core.traffic.TrafficRecordingEngine;
import io.evitadb.core.traffic.TrafficRecordingSettings;
import io.evitadb.core.traffic.task.TrafficRecorderTask;
import io.evitadb.core.transaction.TransactionWalFinalizer;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.evitadb.utils.ReflectionLookup;
import io.evitadb.utils.UUIDUtil;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.Serializable;
import java.lang.ref.WeakReference;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
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
	private final Catalog catalog;
	/**
	 * Contains reference to a control mechanism allowing to signalize work with tha catalog in particular version
	 * to avoid purging necessary files.
	 */
	private final Function<String, CatalogConsumerControl> catalogConsumerControl;
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
	 * Object that tracks commit progress of the transaction.
	 */
	@Nonnull private final CommitProgressRecord commitProgress;
	/**
	 * This future is created when closing sequence is initiated and is completed when the {@link #closedFuture} is
	 * finally created.
	 */
	private final AtomicReference<CompletableFuture<CompletionStage<CommitVersions>>> closingSequenceFuture = new AtomicReference<>();
	/**
	 * Flag is set to true, when the session is being closed and only termination callback is executing. The session
	 * should still be operative until the termination callback is finished.
	 */
	private final AtomicBoolean beingClosed = new AtomicBoolean(false);
	/**
	 * Future that is instantiated when the session is closed.
	 * When initialized, subsequent calls of the close method will return the same future.
	 * When the future is non-null any calls after {@link #close()} method has been called.
	 */
	private volatile CompletionStage<CommitVersions> closedFuture;
	/**
	 * Timestamp of the last session activity (call).
	 */
	private long lastCall = System.currentTimeMillis();
	/**
	 * Contains a number of nested session calls.
	 */
	private int nestLevel;
	/**
	 * Contains a number of streams that were returned by the {@link #getMutationsHistory(ChangeCatalogCaptureRequest)},
	 * {@link #getRecordings(TrafficRecordingCaptureRequest)}, {@link #getRecordingsReversed(TrafficRecordingCaptureRequest)}
	 * to check whether they have been closed at the end of the session.
	 */
	private Set<WeakReference<Stream<?>>> returnedStreams;

	/**
	 * Method creates implicit filtering constraint that contains price filtering constraints for price in case
	 * the `entityMutation` contains mutations related to prices and the require constraint require fetching prices
	 * {@link PriceContentMode#RESPECTING_FILTER}.
	 */
	@Nullable
	private static FilterConstraint createQueryForFetchAfterUpsert(@Nonnull EntityMutation entityMutation, @Nullable EntityContentRequire[] require) {
		final boolean shouldPricesRespectFilter = require != null && Arrays.stream(require)
			.filter(PriceContent.class::isInstance)
			.map(it -> ((PriceContent) it).getFetchMode() == PriceContentMode.RESPECTING_FILTER)
			.findFirst()
			.orElse(false);
		final Set<PriceKey> usedPriceKeys;
		if (shouldPricesRespectFilter) {
			usedPriceKeys = entityMutation.getLocalMutations()
				.stream()
				.filter(PriceMutation.class::isInstance)
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

	/**
	 * Creates a factory function for generating {@link EvitaRequest} objects while either propagating
	 * the original locale information or including all available locales based on the given requirements.
	 * In other words,  when the client logic doesn't explicitly specify its own set of locales, the enriched entity
	 * will respect the locales of the previously fetched entity.
	 *
	 * @param entityType the type of the entity for which the request is to be created
	 * @param require    an array of {@link EntityContentRequire} instances that specify the data requirements for the request
	 * @return a factory function that, when applied, generates an {@link EvitaRequest} based on the specified locales and requirements
	 */
	@Nonnull
	private static BiFunction<Set<Locale>, Class<?>, EvitaRequest> createEvitaRequestFactoryPropagatingOriginalLocale(
		@Nonnull String entityType,
		@Nonnull EntityContentRequire... require
	) {
		return Arrays.stream(require).anyMatch(DataInLocales.class::isInstance) ?
			(locales, requestedClass) -> new EvitaRequest(
				Query.query(
					collection(entityType),
					require(
						entityFetch(require)
					)
				),
				OffsetDateTime.now(),
				requestedClass,
				entityType
			) :
			(originalLocales, requestedClass) -> new EvitaRequest(
				Query.query(
					collection(entityType),
					require(
						entityFetch(
							ArrayUtils.mergeArrays(
								require,
								new EntityContentRequire[]{
									dataInLocales(originalLocales.toArray(new Locale[0]))
								}
							)
						)
					)
				),
				OffsetDateTime.now(),
				requestedClass,
				entityType
			);
	}

	EvitaSession(
		@Nonnull Evita evita,
		@Nonnull Catalog catalog,
		@Nonnull ReflectionLookup reflectionLookup,
		@Nullable EvitaSessionTerminationCallback terminationCallback,
		@Nonnull CommitBehavior commitBehaviour,
		@Nonnull SessionTraits sessionTraits,
		@Nonnull Function<String, CatalogConsumerControl> catalogConsumerControl
	) {
		this.evita = evita;
		this.catalog = catalog;
		this.startCatalogSchemaVersion = catalog.getSchema().version();
		this.reflectionLookup = reflectionLookup;
		this.proxyFactory = catalog.getProxyFactory();
		this.commitBehaviour = commitBehaviour;
		this.sessionTraits = sessionTraits;
		this.terminationCallback = terminationCallback;
		this.commitProgress = new CommitProgressRecord(
			(commitVersions, throwable) -> executeTerminationSteps(throwable, catalog)
		);
		this.catalogConsumerControl = catalogConsumerControl;
		if (catalog.supportsTransaction() && sessionTraits.isReadWrite()) {
			this.transactionAccessor.set(createAndInitTransaction());
		}
		catalog.getTrafficRecordingEngine()
			.createSession(this.id, catalog.getVersion(), this.created);
	}

	@Nonnull
	@Override
	public UUID getId() {
		return this.id;
	}

	@Nonnull
	@Override
	public UUID getCatalogId() {
		return this.catalog.getCatalogId();
	}

	@Interruptible
	@Traced
	@Nonnull
	@Override
	public SealedCatalogSchema getCatalogSchema() {
		assertActive();
		return this.catalog.getSchema();
	}

	@Nonnull
	@Override
	public CatalogState getCatalogState() {
		assertActive();
		return this.catalog.getCatalogState();
	}

	@Override
	public long getCatalogVersion() {
		assertActive();
		return this.catalog.getVersion();
	}

	@Override
	public boolean isActive() {
		return this.closedFuture == null || this.beingClosed.get();
	}

	@Nonnull
	@Traced
	@Override
	public GoLiveProgress goLiveAndCloseWithProgress(@Nullable IntConsumer progressObserver) {
		// added read only check
		isTrue(
			!isReadOnly(),
			ReadOnlyException::new
		);

		final Catalog theCatalog = this.catalog;
		isTrue(!theCatalog.supportsTransaction(), "Catalog went live already and is currently in transactional mode!");
		if (isActive()) {
			executeTerminationSteps(null, theCatalog);
			this.closedFuture = CompletableFuture.completedFuture(new CommitVersions(this.catalog.getVersion() + 1, this.catalog.getSchema().version()));
		}
		this.evita.closeAllSessionsAndSuspend(this.catalog.getName(), SuspendOperation.REJECT)
			.ifPresent(it -> it.addForcefullyClosedSession(this.id));
		return theCatalog.goLive(progressObserver);
	}

	@Nonnull
	@Override
	public CompletionStage<CommitVersions> closeNow(@Nonnull CommitBehavior commitBehaviour) {
		return closeInternal(commitBehaviour).on(commitBehaviour);
	}

	@Nonnull
	@Override
	public CommitProgress closeNowWithProgress() {
		return closeInternal(this.commitBehaviour);
	}

	@Interruptible
	@Traced
	@Nonnull
	@Override
	public EntitySchemaBuilder defineEntitySchema(@Nonnull String entityType) {
		assertActive();
		return executeInTransactionIfPossible(
			session -> this.catalog.getCollectionForEntity(entityType)
				.orElseGet(() -> {
					updateCatalogSchema(new CreateEntitySchemaMutation(entityType));
					return this.catalog.getCollectionForEntityOrThrowException(entityType);
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
			final ClassSchemaAnalyzer classSchemaAnalyzer = new ClassSchemaAnalyzer(modelClass, this.reflectionLookup);
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
			final ClassSchemaAnalyzer classSchemaAnalyzer = new ClassSchemaAnalyzer(modelClass, this.reflectionLookup, postProcessor);
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
		final Optional<EntityCollectionContract> collection = this.catalog.getCollectionForEntity(entityType);
		return collection.map(EntityCollectionContract::getSchema);
	}

	@Interruptible
	@Traced
	@Nonnull
	@Override
	public Optional<SealedEntitySchema> getEntitySchema(@Nonnull Class<?> modelClass) throws EntityClassInvalidException {
		return getEntitySchema(
			extractEntityTypeFromClass(modelClass, this.reflectionLookup)
				.orElseThrow(() -> new CollectionNotFoundException(modelClass))
		);
	}

	@Interruptible
	@Traced
	@Nonnull
	@Override
	public SealedEntitySchema getEntitySchemaOrThrowException(@Nonnull String entityType) {
		assertActive();
		return this.catalog.getCollectionForEntityOrThrowException(entityType).getSchema();
	}

	@Interruptible
	@Traced
	@Nonnull
	@Override
	public SealedEntitySchema getEntitySchemaOrThrowException(@Nonnull Class<?> modelClass) throws CollectionNotFoundException, EntityClassInvalidException {
		return getEntitySchemaOrThrowException(
			extractEntityTypeFromClass(modelClass, this.reflectionLookup)
				.orElseThrow(() -> new CollectionNotFoundException(modelClass))
		);
	}

	@Interruptible
	@Traced
	@Override
	@Nonnull
	public Set<String> getAllEntityTypes() {
		return this.catalog.getEntityTypes();
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
				extractEntityTypeFromClass(expectedType, this.reflectionLookup).orElse(null)
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
			extractEntityTypeFromClass(expectedType, this.reflectionLookup).orElse(null)
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
		return getEntityInternal(
			entityType,
			SealedEntity.class,
			Function.identity(),
			ServerEntityDecorator.class::cast,
			primaryKey,
			scopes,
			require
		);
	}

	@Interruptible
	@Traced
	@RepresentsQuery
	@Nonnull
	@Override
	public <T extends Serializable> Optional<T> getEntity(@Nonnull Class<T> expectedType, int primaryKey, @Nonnull Scope[] scopes, EntityContentRequire... require) {
		final String entityType = extractEntityTypeFromClass(expectedType, this.reflectionLookup)
			.orElseThrow(() -> new CollectionNotFoundException(expectedType));
		return getEntityInternal(
			entityType,
			expectedType,
			sealedEntity -> this.createEntityProxy(expectedType, sealedEntity),
			entity -> {
				final EntityContract theEntity = (
					entity instanceof SealedEntityProxy sealedEntityProxy ?
						sealedEntityProxy.entity() : (entity instanceof EntityContract ec ? ec : null)
				);
				return theEntity instanceof ServerEntityDecorator sed ? sed : null;
			},
			primaryKey,
			scopes,
			require
		);
	}

	@Interruptible
	@Traced
	@RepresentsQuery
	@Nonnull
	@Override
	public <T extends Serializable> T enrichEntity(@Nonnull T partiallyLoadedEntity, EntityContentRequire... require) {
		assertActive();

		final String entityType = getEntityTypeFromEntity(partiallyLoadedEntity, this.reflectionLookup);
		final EntityEnrichEvent enrichEvent = new EntityEnrichEvent(getCatalogName(), entityType);
		final EntityCollectionContract entityCollection = this.catalog.getCollectionForEntityOrThrowException(entityType);
		final BiFunction<Set<Locale>, Class<?>, EvitaRequest> evitaRequestFactory = createEvitaRequestFactoryPropagatingOriginalLocale(entityType, require);
		if (partiallyLoadedEntity instanceof SealedEntityProxy sealedEntityProxy) {
			final EntityContract innerEntity = sealedEntityProxy.entity();
			final ServerEntityDecorator enrichedEntity = (ServerEntityDecorator) entityCollection.enrichEntity(
				innerEntity,
				evitaRequestFactory.apply(
					innerEntity.getLocales(),
					sealedEntityProxy.getProxyClass()
				),
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
		} else if (partiallyLoadedEntity instanceof EntityDecorator entityDecorator) {
			final ServerEntityDecorator enrichedEntity = (ServerEntityDecorator) entityCollection.enrichEntity(
				entityDecorator,
				evitaRequestFactory.apply(
					entityDecorator.getLocales(),
					partiallyLoadedEntity.getClass()
				),
				this
			);
			// emit the event
			enrichEvent.finish(
				enrichedEntity.getIoFetchCount(),
				enrichedEntity.getIoFetchedBytes()
			).commit();

			//noinspection unchecked
			return (T) enrichedEntity;
		} else {
			throw new EvitaInvalidUsageException("Expected entity decorator in the input.");
		}
	}

	@Interruptible
	@Traced
	@RepresentsQuery
	@Nonnull
	@Override
	public <T extends Serializable> T enrichOrLimitEntity(@Nonnull T partiallyLoadedEntity, EntityContentRequire... require) {
		assertActive();
		final String entityType = getEntityTypeFromEntity(partiallyLoadedEntity, this.reflectionLookup);
		final EntityEnrichEvent enrichEvent = new EntityEnrichEvent(getCatalogName(), entityType);

		final EntityCollectionContract entityCollection = this.catalog.getCollectionForEntity(entityType)
			.orElseThrow(() -> new CollectionNotFoundException(entityType));
		final BiFunction<Set<Locale>, Class<?>, EvitaRequest> evitaRequestFactory = createEvitaRequestFactoryPropagatingOriginalLocale(entityType, require);
		if (partiallyLoadedEntity instanceof SealedEntityProxy sealedEntityProxy) {
			final EvitaRequest evitaRequest = evitaRequestFactory.apply(
				sealedEntityProxy.entity().getLocales(),
				sealedEntityProxy.getProxyClass()
			);
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
		} else if (partiallyLoadedEntity instanceof EntityDecorator entityDecorator) {
			final EvitaRequest evitaRequest = evitaRequestFactory.apply(
				entityDecorator.getLocales(),
				partiallyLoadedEntity.getClass()
			);
			final ServerEntityDecorator enrichedEntity = (ServerEntityDecorator) entityCollection.limitEntity(
				entityCollection.enrichEntity(entityDecorator, evitaRequest, this),
				evitaRequest, this
			);

			// emit the event
			enrichEvent.finish(
				enrichedEntity.getIoFetchCount(),
				enrichedEntity.getIoFetchedBytes()
			).commit();

			//noinspection unchecked
			return (T) enrichedEntity;
		} else {
			throw new EvitaInvalidUsageException("Expected entity decorator in the input.");
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
			this.catalog.updateSchema(session, schemaMutation);
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
			this.catalog.updateSchema(session, schemaMutation);
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
			this.catalog.updateSchema(session, schemaMutation);
			return getEntitySchemaOrThrowException(schemaMutation.getEntityType());
		});
	}

	@Interruptible
	@Traced
	@Override
	public boolean deleteCollection(@Nonnull String entityType) {
		assertActive();
		return executeInTransactionIfPossible(
			session -> this.catalog.deleteCollectionOfEntity(session, entityType)
		);
	}

	@Interruptible
	@Traced
	@Override
	public boolean deleteCollection(@Nonnull Class<?> modelClass) throws EntityClassInvalidException {
		return deleteCollection(
			extractEntityTypeFromClass(modelClass, this.reflectionLookup)
				.orElseThrow(() -> new CollectionNotFoundException(modelClass))
		);
	}

	@Interruptible
	@Traced
	@Override
	public boolean renameCollection(@Nonnull String entityType, @Nonnull String newName) {
		assertActive();
		return executeInTransactionIfPossible(
			session -> this.catalog.renameCollectionOfEntity(entityType, newName, session)
		);
	}

	@Interruptible
	@Traced
	@Override
	public boolean replaceCollection(@Nonnull String entityTypeToBeReplaced, @Nonnull String entityTypeToBeReplacedWith) {
		assertActive();
		return executeInTransactionIfPossible(
			session -> this.catalog.replaceCollectionOfEntity(session, entityTypeToBeReplaced, entityTypeToBeReplacedWith)
		);
	}

	@Interruptible
	@Traced
	@RepresentsQuery
	@Override
	public int getEntityCollectionSize(@Nonnull String entityType) {
		assertActive();
		return this.catalog.getCollectionForEntityOrThrowException(entityType).size();
	}

	@Nonnull
	@Override
	public EntityBuilder createNewEntity(@Nonnull String entityType) {
		assertActive();
		return executeInTransactionIfPossible(session -> {
			final EntityCollectionContract collection = this.catalog.getOrCreateCollectionForEntity(session, entityType);
			return collection.createNewEntity();
		});
	}

	@Nonnull
	@Override
	public <S extends Serializable> S createNewEntity(@Nonnull Class<S> expectedType) {
		return this.proxyFactory.createEntityProxy(
			expectedType,
			createNewEntity(
				extractEntityTypeFromClass(expectedType, this.reflectionLookup)
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
			final EntityCollectionContract collection = this.catalog.getOrCreateCollectionForEntity(session, entityType);
			return collection.createNewEntity(primaryKey);
		});
	}

	@Nonnull
	@Override
	public <S extends Serializable> S createNewEntity(@Nonnull Class<S> expectedType, int primaryKey) {
		return this.proxyFactory.createEntityProxy(
			expectedType,
			createNewEntity(
				extractEntityTypeFromClass(expectedType, this.reflectionLookup)
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
			final EntityCollectionContract collection = this.catalog.getOrCreateCollectionForEntity(session, entityMutation.getEntityType());
			// and return result
			return collection.upsertEntity(session, entityMutation);
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
			final EntityCollectionContract collection = this.catalog.getOrCreateCollectionForEntity(session, entityMutation.getEntityType());
			return collection.upsertAndFetchEntity(
				this, entityMutation,
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
				)
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
			final EntityCollectionContract collection = this.catalog.getOrCreateCollectionForEntity(session, entityType);
			// and return result
			return collection.deleteEntity(this, primaryKey);
		});
	}

	@Interruptible
	@Traced
	@RepresentsMutation
	@Override
	public boolean deleteEntity(@Nonnull Class<?> modelClass, int primaryKey) throws EntityClassInvalidException {
		return deleteEntity(
			extractEntityTypeFromClass(modelClass, this.reflectionLookup)
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
			extractEntityTypeFromClass(modelClass, this.reflectionLookup)
				.orElseThrow(() -> new CollectionNotFoundException(modelClass)),
			modelClass, primaryKey, require
		);
	}

	@Interruptible
	@Traced
	@RepresentsMutation
	@Override
	public int deleteEntityAndItsHierarchy(@Nonnull String entityType, int primaryKey) {
		return deleteEntityAndItsHierarchyInternal(
			entityType, EntityReference.class, primaryKey
		).deletedEntities();
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
			extractEntityTypeFromClass(modelClass, this.reflectionLookup)
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
		return executeInTransactionIfPossible(session -> {
			final EvitaResponse<EntityReference> response = query(query, EntityReference.class);
			final List<EntityReference> recordData = response.getRecordData();
			int deletedCount = 0;
			for (EntityReference entityToRemove : recordData) {
				deletedCount += deleteEntity(entityToRemove.getType(), entityToRemove.getPrimaryKey()) ? 1 : 0;
			}
			return deletedCount;
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
		return executeInTransactionIfPossible(session -> {
			final EvitaResponse<SealedEntity> response = query(query, SealedEntity.class);
			final List<SealedEntity> recordData = response.getRecordData();
			final SealedEntity[] deletedEntities = new SealedEntity[recordData.size()];
			int index = 0;
			for (SealedEntity entityToRemove : recordData) {
				if (deleteEntity(entityToRemove.getType(), entityToRemove.getPrimaryKeyOrThrowException())) {
					deletedEntities[index++] = entityToRemove;
				}
			}
			return index == deletedEntities.length ?
				deletedEntities : Arrays.copyOf(deletedEntities, index);
		});
	}

	@Interruptible
	@Traced
	@RepresentsMutation
	@Override
	public boolean archiveEntity(@Nonnull String entityType, int primaryKey) {
		assertActive();
		return executeInTransactionIfPossible(session -> {
			final EntityCollectionContract collection = this.catalog.getOrCreateCollectionForEntity(session, entityType);
			return collection.archiveEntity(this, primaryKey);
		});
	}

	@Interruptible
	@Traced
	@RepresentsMutation
	@Override
	public boolean archiveEntity(@Nonnull Class<?> modelClass, int primaryKey) throws EntityClassInvalidException {
		return archiveEntity(
			extractEntityTypeFromClass(modelClass, this.reflectionLookup)
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
			extractEntityTypeFromClass(modelClass, this.reflectionLookup)
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
			final EntityCollectionContract collection = this.catalog.getOrCreateCollectionForEntity(session, entityType);
			return collection.restoreEntity(this, primaryKey);
		});
	}

	@Interruptible
	@Traced
	@RepresentsMutation
	@Override
	public boolean restoreEntity(@Nonnull Class<?> modelClass, int primaryKey) throws EntityClassInvalidException {
		return restoreEntity(
			extractEntityTypeFromClass(modelClass, this.reflectionLookup)
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
			extractEntityTypeFromClass(modelClass, this.reflectionLookup)
				.orElseThrow(() -> new CollectionNotFoundException(modelClass)),
			modelClass, primaryKey, require
		);
	}

	@Nonnull
	@Override
	public CatalogVersion getCatalogVersionAt(@Nullable OffsetDateTime moment) throws TemporalDataNotAvailableException {
		assertActive();
		return this.catalog.getCatalogVersionAt(moment);
	}

	@Nonnull
	@Override
	public Stream<ChangeCatalogCapture> getMutationsHistory(@Nonnull ChangeCatalogCaptureRequest criteria) {
		final MutationPredicate mutationPredicate = MutationPredicateFactory.createReversedChangeCatalogCapturePredicate(criteria);
		return registerStreamAndReturnCloseableStream(
			this.catalog
				.getReversedCommittedMutationStream(criteria.sinceVersion())
				.flatMap(it -> it.toChangeCatalogCapture(mutationPredicate, criteria.content()))
		);
	}

	@Interruptible
	@Traced
	@Nonnull
	@Override
	public Task<?, FileForFetch> backupCatalog(
		@Nullable OffsetDateTime pastMoment,
		@Nullable Long catalogVersion,
		boolean includingWAL
	) throws TemporalDataNotAvailableException {
		// added read only check
		isTrue(
			!isReadOnly(),
			ReadOnlyException::new
		);
		final CatalogContract theCatalog = this.catalog;
		final CatalogConsumerControl ccControl = this.catalogConsumerControl.apply(theCatalog.getName());
		return theCatalog.backup(
			pastMoment, catalogVersion, includingWAL,
			ccControl::registerConsumerOfCatalogInVersion,
			ccControl::unregisterConsumerOfCatalogInVersion
		);
	}

	@Interruptible
	@Traced
	@Nonnull
	@Override
	public Task<?, FileForFetch> fullBackupCatalog() {
		// added read only check
		isTrue(
			!isReadOnly(),
			ReadOnlyException::new
		);
		final CatalogContract theCatalog = this.catalog;
		final CatalogConsumerControl ccControl = this.catalogConsumerControl.apply(theCatalog.getName());
		return theCatalog.fullBackup(
			ccControl::registerConsumerOfCatalogInVersion,
			ccControl::unregisterConsumerOfCatalogInVersion
		);
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

	@Nonnull
	@Override
	public Stream<TrafficRecording> getRecordings(@Nonnull TrafficRecordingCaptureRequest request) throws TemporalDataNotAvailableException {
		assertActive();
		return registerStreamAndReturnCloseableStream(
			this.catalog.getTrafficRecordingEngine().getRecordings(request)
		);
	}

	@Nonnull
	@Override
	public Stream<TrafficRecording> getRecordingsReversed(@Nonnull TrafficRecordingCaptureRequest request) throws TemporalDataNotAvailableException, IndexNotReady {
		assertActive();
		return registerStreamAndReturnCloseableStream(
			this.catalog.getTrafficRecordingEngine().getRecordingsReversed(request)
		);
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
		if (entityType == null) {
			response = this.catalog.getEntities(evitaRequest, this);
		} else {
			final EntityCollectionContract entityCollection = this.catalog.getCollectionForEntityOrThrowException(entityType);
			response = entityCollection.getEntities(evitaRequest, this);
		}

		final List<S> recordData = response.getRecordData();
		// and return result
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
		final EvitaResponse<Serializable> response = query(evitaRequest);
		// and return result
		//noinspection unchecked
		return (List<S>) response.getRecordData();
	}

	@Interruptible
	@RepresentsQuery
	@Nonnull
	@Override
	public <S extends Serializable, T extends EvitaResponse<S>> T query(@Nonnull EvitaRequest evitaRequest) throws UnexpectedResultException, InstanceTerminatedException {
		assertActive();
		final String entityType = evitaRequest.getEntityType();
		final T response;
		final CatalogContract theCatalog = this.catalog;
		if (entityType == null) {
			response = theCatalog.getEntities(evitaRequest, this);
		} else {
			final EntityCollectionContract entityCollection = theCatalog.getCollectionForEntityOrThrowException(entityType);
			response = entityCollection.getEntities(evitaRequest, this);
		}
		// and return result
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
		// method invocation should be handled on session proxy level
		throw new UnsupportedOperationException("This method is not supported in this session!");
	}

	@Override
	public void executeWhenMethodIsNotRunning(@Nonnull Runnable lambda) {
		// method invocation should be handled on session proxy level
		throw new UnsupportedOperationException("This method is not supported in this session!");
	}

	@Nonnull
	public CommitProgressRecord getCommitProgress() {
		return this.commitProgress;
	}

	@Nonnull
	@Override
	public UUID recordSourceQuery(
		@Nonnull String sourceQuery,
		@Nonnull String queryType,
		@Nullable String finishedWithError
	) {
		final TrafficRecordingEngine trafficRecorder = this.catalog.getTrafficRecordingEngine();
		final UUID sourceQueryId = UUIDUtil.randomUUID();
		trafficRecorder.setupSourceQuery(this.id, sourceQueryId, sourceQuery, queryType, finishedWithError);
		return sourceQueryId;
	}

	@Override
	public void finalizeSourceQuery(@Nonnull UUID sourceQueryId, @Nullable String finishedWithError) {
		final TrafficRecordingEngine trafficRecorder = this.catalog.getTrafficRecordingEngine();
		trafficRecorder.closeSourceQuery(this.id, sourceQueryId, finishedWithError);
	}

	@Nonnull
	@Override
	public Collection<String> getLabelsNamesOrderedByCardinality(@Nullable String nameStartingWith, int limit) {
		final TrafficRecordingEngine trafficRecorder = this.catalog.getTrafficRecordingEngine();
		return trafficRecorder.getLabelsNamesOrderedByCardinality(nameStartingWith, limit);
	}

	@Nonnull
	@Override
	public Collection<String> getLabelValuesOrderedByCardinality(@Nonnull String labelName, @Nullable String valueStartingWith, int limit) {
		final TrafficRecordingEngine trafficRecorder = this.catalog.getTrafficRecordingEngine();
		return trafficRecorder.getLabelValuesOrderedByCardinality(labelName, valueStartingWith, limit);
	}

	@Nonnull
	@Override
	public ServerTask<TrafficRecordingSettings, FileForFetch> startRecording(
		int samplingRate,
		boolean exportFile,
		@Nullable Duration recordingDuration,
		@Nullable Long recordingSizeLimitInBytes,
		long chunkFileSizeInBytes
	) throws SingletonTaskAlreadyRunningException {
		Assert.isTrue(
			!this.evita.getConfiguration().server().readOnly(),
			ReadOnlyException::new
		);

		final Collection<TrafficRecorderTask> existingTaskStatus = this.evita.management().getTaskStatuses(TrafficRecorderTask.class);
		final TrafficRecorderTask runningTask = existingTaskStatus.stream().filter(it -> !it.getFutureResult().isDone()).findFirst().orElse(null);
		if (runningTask != null) {
			throw new SingletonTaskAlreadyRunningException(runningTask.getStatus().taskName());
		} else {
			final Scheduler scheduler = this.evita.getServiceExecutor();
			final ServerTask<TrafficRecordingSettings, FileForFetch> trafficRecorderTask = new TrafficRecorderTask(
				getCatalogName(), samplingRate, exportFile, recordingDuration, recordingSizeLimitInBytes,
				chunkFileSizeInBytes,
				this.catalog.getTrafficRecordingEngine(),
				this.evita.management().exportFileService(),
				scheduler
			);
			scheduler.submit(trafficRecorderTask);
			return trafficRecorderTask;
		}
	}

	@Nonnull
	@Override
	public TaskStatus<TrafficRecordingSettings, FileForFetch> stopRecording(@Nonnull UUID taskId) {
		Assert.isTrue(
			!this.evita.getConfiguration().server().readOnly(),
			ReadOnlyException::new
		);

		final Collection<TrafficRecorderTask> existingTaskStatus = this.evita.management().getTaskStatuses(TrafficRecorderTask.class);
		final TrafficRecorderTask runningTask = existingTaskStatus.stream().filter(it -> !it.getFutureResult().isDone()).findFirst().orElse(null);
		if (runningTask != null) {
			runningTask.stop();
			return runningTask.getStatus();
		} else {
			throw new EvitaInvalidUsageException(
				"Traffic recording is not running.",
				"Traffic recording is not running. You have to start it first."
			);
		}
	}

	/**
	 * Returns a transaction wrapped in optional. If no transaction is bound to the session, an empty optional is returned.
	 *
	 * @return an Optional containing the transaction, if it exists; otherwise, an empty Optional.
	 */
	@Nonnull
	public Optional<Transaction> getTransaction() {
		return ofNullable(this.transactionAccessor.get());
	}

	/**
	 * Returns an opened transaction wrapped in optional. If no transaction is opened, an empty optional is returned.
	 *
	 * @return an Optional containing the opened transaction, if it exists and is not closed; otherwise, an empty Optional.
	 */
	@Nonnull
	public Optional<Transaction> getOpenedTransaction() {
		return ofNullable(this.transactionAccessor.get())
			.filter(it -> !it.isClosed());
	}

	@Override
	public String toString() {
		return (isReadOnly() ?
			"Read-only session: " :
			(getOpenedTransactionId().map(txId -> "Read-write session (tx `" + txId + "` opened): ").orElse("Read-write session: ")))
			+ this.id + (isActive() ? "" : " (terminated)");
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
	 * Determines if the current execution is at the root level.
	 *
	 * @return {@code true} if the execution is at the root level, {@code false} otherwise.
	 */
	boolean isRootLevelExecution() {
		return this.nestLevel == 1;
	}

	/**
	 * Increases the session execution nest level by one.
	 *
	 * @return the incremented nest level
	 */
	int increaseNestLevel() {
		return this.nestLevel++;
	}

	/**
	 * Decreases the session execution nest level by one.
	 *
	 * @return The updated nest level.
	 */
	int decreaseNestLevel() {
		return this.nestLevel--;
	}

	/**
	 * Safely closes internal resources, commits or rolls back transactions, and handles
	 * termination callbacks. Ensures that the closing operation is executed only once
	 * and performs cleanup, validation, and transaction operations if applicable.
	 *
	 * @param commitBehavior determines in which commit stage the session should be considered as closed
	 * @return A {@code CommitProgress} instance, representing the progress and
	 * completion status of the closure operation. It encapsulates version
	 * information and any issues that might occur during the process.
	 */
	@Nonnull
	private CommitProgress closeInternal(@Nonnull CommitBehavior commitBehavior) {
		if (this.closedFuture == null && this.closingSequenceFuture.get() == null) {
			if (this.closingSequenceFuture.compareAndSet(null, new CompletableFuture<>())) {
				// forcefully close all opened stateful streams
				if (this.returnedStreams != null) {
					// rewrap collection so that we don't get concurrent modification exception
					new ArrayList<>(this.returnedStreams)
						.forEach(weakReference -> {
							final Stream<?> stream = weakReference.get();
							if (stream != null) {
								stream.close();
							}
						});
				}
				// flush changes if we're not in transactional mode
				final Transaction transaction = this.transactionAccessor.get();
				this.commitProgress.setTerminationStage(commitBehavior);

				if (this.catalog.getCatalogState() == CatalogState.WARMING_UP) {
					isPremiseValid(
						transaction == null,
						"In warming-up mode no transaction is expected to be opened!"
					);
					this.catalog.flush(null)
						.whenComplete(
							(__, throwable) -> {
								if (throwable == null) {
									try {
										validateCatalogSchema(this.catalog);
										this.commitProgress.complete(
											new CommitVersions(
												this.catalog.getVersion(),
												this.catalog.getSchema().version()
											)
										);
									} catch (Exception ex) {
										this.commitProgress.completeExceptionally(ex);
									}
								} else {
									this.commitProgress.completeExceptionally(throwable);
								}
							}
						);
				} else {
					if (transaction != null) {
						// close transaction at the end of this block
						try (transaction) {
							if (!transaction.isRollbackOnly()) {
								validateCatalogSchema(this.catalog);
							}
						} catch (Exception ex) {
							this.commitProgress.completeExceptionally(ex);
						}
					} else {
						// immediately complete future
						this.commitProgress.complete(
							new CommitVersions(
								this.catalog.getVersion(),
								this.catalog.getSchema().version()
							)
						);
					}
				}
				// join both futures together and apply termination callback
				this.closedFuture = this.commitProgress
					// complete session on desired commit behaviour
					.on(commitBehavior);

				this.closingSequenceFuture.get().complete(this.closedFuture);
			} else {
				// wait until the closed future is finally created
				this.closingSequenceFuture.get().join();
				return this.commitProgress;
			}
		} else if (this.closedFuture != null && !this.commitProgress.isDone()) {
			// this case is only possible when catalog is going live and session is closed
			this.commitProgress.complete(this.closedFuture.toCompletableFuture().getNow(null));
		}
		return this.commitProgress;
	}

	/**
	 * Executes termination steps for the current transaction session. This involves running termination
	 * callbacks, closing traffic recording sessions, and handling any exceptions that may have occurred
	 * during the process. If certain exception types are encountered, they are rethrown for further handling.
	 *
	 * @param throwable an optional throwable that may have occurred during the execution of the transaction;
	 *                  it will be processed, and relevant exceptions will be rethrown if applicable
	 * @param theCatalog a non-null catalog instance used to manage state and close traffic recording sessions
	 */
	private void executeTerminationSteps(
		@Nullable Throwable throwable,
		@Nonnull Catalog theCatalog
	) {
		// then apply termination callbacks
		String finishedWithError = null;
		try {
			Assert.isPremiseValid(
				this.beingClosed.compareAndSet(false, true),
				"Expectation failed!"
			);
			ofNullable(this.terminationCallback)
				.ifPresent(it -> it.onTermination(this));
		} catch (Throwable tcException) {
			finishedWithError = tcException.getClass().getName() + ": " + (tcException.getMessage() == null ? "no message" : tcException.getMessage());
			log.error("Error occurred while executing termination callback!", tcException);
			if (throwable == null) {
				throw new TransactionException("Error occurred while executing termination callback!", tcException);
			} else {
				throwable.addSuppressed(tcException);
			}
		} finally {
			theCatalog.getTrafficRecordingEngine().closeSession(this.id, finishedWithError);
			Assert.isPremiseValid(
				this.beingClosed.compareAndSet(true, false),
				"Expectation failed!"
			);
		}
	}

	/**
	 * Registers a stream and ensures its closure is tracked. When the stream is closed,
	 * it automatically unregisters itself from the tracked streams.
	 *
	 * @param stream the stream to be registered and tracked; must not be null
	 * @return the same stream passed as input with an added close behavior; never null
	 */
	@Nonnull
	private <T> Stream<T> registerStreamAndReturnCloseableStream(@Nonnull Stream<T> stream) {
		if (this.returnedStreams == null) {
			this.returnedStreams = new HashSet<>(32);
		}
		final WeakReference<Stream<?>> theStream = new WeakReference<>(stream);
		this.returnedStreams.add(theStream);
		return stream.onClose(() -> this.returnedStreams.remove(theStream));
	}

	/**
	 * Validates the schema of the given catalog.
	 *
	 * @param theCatalog the catalog to be validated, must not be null
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
				.ifPresent(it -> it.setRollbackOnlyWithException(ex));
			throw ex;
		}
	}

	/**
	 * Returns map with current {@link Catalog#getSchema() catalog} {@link EntitySchemaContract entity schema} instances
	 * indexed by their {@link EntitySchemaContract#getName() name}.
	 *
	 * @return map with current {@link Catalog#getSchema() catalog} {@link EntitySchemaContract entity schema} instances
	 */
	@Nonnull
	private Map<String, EntitySchemaContract> getEntitySchemaIndex() {
		return this.catalog.getEntitySchemaIndex();
	}

	/**
	 * Retrieves an entity of the specified type, decorated with potentially additional server-side logic.
	 * The method asserts that at least one scope parameter is provided and invokes a fetch operation
	 * within the context of an `EvitaRequest`.
	 *
	 * @param entityType                     the type of entity to retrieve.
	 * @param expectedType                   the class object corresponding to the expected type of the entity.
	 * @param transformer                    a function to transform the retrieved entity into the expected type.
	 * @param serverEntityDecoratorExtractor a function to extract a `ServerEntityDecorator` from the retrieved entity.
	 * @param primaryKey                     the primary key identifying the specific entity to be retrieved.
	 * @param scopes                         an array of scopes that define the context within which the entity is to be fetched.
	 * @param require                        an array of `EntityContentRequire` instructions specifying any additional entity content requirements.
	 * @return an `Optional` containing the entity if it exists, or an empty `Optional` if the entity does not exist.
	 */
	@Nonnull
	private <T extends Serializable> Optional<T> getEntityInternal(
		@Nonnull String entityType,
		@Nonnull Class<T> expectedType,
		@Nonnull Function<SealedEntity, T> transformer,
		@Nonnull Function<T, ServerEntityDecorator> serverEntityDecoratorExtractor,
		int primaryKey,
		@Nonnull Scope[] scopes,
		@Nonnull EntityContentRequire... require
	) {
		assertActive();
		isTrue(scopes.length > 0, "At least one scope must be provided!");

		final EntityFetchEvent fetchEvent = new EntityFetchEvent(getCatalogName(), entityType);
		final EvitaRequest evitaRequest = new EvitaRequest(
			Query.query(
				collection(entityType),
				filterBy(
					scope(scopes)
				),
				require(
					entityFetch(require)
				)
			),
			OffsetDateTime.now(),
			expectedType,
			entityType
		);
		final EntityCollectionContract entityCollection = this.catalog.getCollectionForEntityOrThrowException(entityType);
		final Optional<T> resultEntity = entityCollection.getEntity(
			primaryKey,
			evitaRequest,
			this
		).map(transformer);

		// emit the event
		final Optional<ServerEntityDecorator> serverEntityDecorator = resultEntity
			.map(serverEntityDecoratorExtractor);

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
			final EntityCollectionContract collection = this.catalog.getOrCreateCollectionForEntity(session, entityType);
			// and return result
			//noinspection unchecked
			return (Optional<T>) collection.deleteEntity(
				this, new EvitaRequest(
					Query.query(
						collection(entityType),
						filterBy(entityPrimaryKeyInSet(primaryKey)),
						require(
							entityFetch(require)
						)
					),
					OffsetDateTime.now(),
					expectedType,
					extractEntityTypeFromClass(expectedType, this.reflectionLookup)
						.orElse(null)
				)
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
			final EntityCollectionContract collection = this.catalog.getOrCreateCollectionForEntity(session, entityType);
			isTrue(
				collection.getSchema().isWithHierarchy(),
				"Entity type " + entityType + " doesn't represent a hierarchical entity!"
			);
			// and return response
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
					extractEntityTypeFromClass(expectedType, this.reflectionLookup)
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
			final EntityCollectionContract collection = this.catalog.getOrCreateCollectionForEntity(session, entityType);
			//noinspection unchecked
			return (Optional<T>) collection.archiveEntity(
				this, new EvitaRequest(
					Query.query(
						collection(entityType),
						filterBy(entityPrimaryKeyInSet(primaryKey)),
						require(
							entityFetch(require)
						)
					),
					OffsetDateTime.now(),
					expectedType,
					extractEntityTypeFromClass(expectedType, this.reflectionLookup)
						.orElse(null)
				)
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
			final EntityCollectionContract collection = this.catalog.getOrCreateCollectionForEntity(session, entityType);
			//noinspection unchecked
			return (Optional<T>) collection.restoreEntity(
				this, new EvitaRequest(
					Query.query(
						collection(entityType),
						filterBy(entityPrimaryKeyInSet(primaryKey)),
						require(
							entityFetch(require)
						)
					),
					OffsetDateTime.now(),
					expectedType,
					extractEntityTypeFromClass(expectedType, this.reflectionLookup)
						.orElse(null)
				)
			);
		});
	}

	/**
	 * Creates new transaction a wraps it into carrier object.
	 */
	@Nonnull
	private Transaction createTransaction() {
		isTrue(
			!isReadOnly(),
			ReadOnlyException::new
		);
		final Transaction transaction = new Transaction(
			UUID.randomUUID(),
			new TransactionWalFinalizer(
				this.catalog,
				getId(),
				this.catalog::createIsolatedWalService,
				this.commitProgress
			),
			false
		);
		// when the session is marked as "dry run" we never commit the transaction but always roll-back
		if (this.sessionTraits.isDryRun()) {
			transaction.setRollbackOnly();
		}
		return transaction;
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
		if (!this.sessionTraits.isReadWrite()) {
			throw new TransactionNotSupportedException("Transaction cannot be opened in read only session!");
		}
		if (!this.catalog.supportsTransaction()) {
			throw new TransactionNotSupportedException("Catalog " + this.catalog.getName() + " doesn't support transaction yet. Call `goLiveAndClose()` method first!");
		}
		final Transaction tx = createTransaction();
		this.transactionAccessor.getAndUpdate(transaction -> {
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
		if (this.transactionAccessor.get() == null && this.catalog.supportsTransaction()) {
			try (final Transaction newTransaction = createTransaction()) {
				increaseNestLevel();
				this.transactionAccessor.set(newTransaction);
				return Transaction.executeInTransactionIfProvided(
					newTransaction,
					() -> logic.apply(this),
					isRootLevelExecution()
				);
			} catch (Throwable ex) {
				ofNullable(this.transactionAccessor.get())
					.ifPresent(tx -> {
						if (isRootLevelExecution()) {
							tx.setRollbackOnlyWithException(ex);
						}
					});
				throw ex;
			} finally {
				decreaseNestLevel();
				this.transactionAccessor.set(null);
			}
		} else {
			// the transaction might already exist
			try {
				increaseNestLevel();
				return Transaction.executeInTransactionIfProvided(
					this.transactionAccessor.get(),
					() -> logic.apply(this),
					isRootLevelExecution()
				);
			} catch (Throwable ex) {
				if (isRootLevelExecution()) {
					ofNullable(this.transactionAccessor.get())
						.ifPresentOrElse(
							tx -> tx.setRollbackOnlyWithException(ex),
							() -> this.commitProgress.completeExceptionally(ex)
						);
				}
				throw ex;
			} finally {
				decreaseNestLevel();
			}
		}
	}

}
