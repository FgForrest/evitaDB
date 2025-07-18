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

import io.evitadb.api.CatalogContract;
import io.evitadb.api.CatalogState;
import io.evitadb.api.CommitProgress;
import io.evitadb.api.CommitProgress.CommitVersions;
import io.evitadb.api.CommitProgressRecord;
import io.evitadb.api.EvitaContract;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.EvitaSessionTerminationCallback;
import io.evitadb.api.SessionTraits;
import io.evitadb.api.SessionTraits.SessionFlags;
import io.evitadb.api.TransactionContract.CommitBehavior;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.exception.CatalogAlreadyPresentException;
import io.evitadb.api.exception.CatalogGoingLiveException;
import io.evitadb.api.exception.CatalogNotFoundException;
import io.evitadb.api.exception.InstanceTerminatedException;
import io.evitadb.api.exception.InvalidMutationException;
import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.exception.ReadOnlyException;
import io.evitadb.api.exception.TransactionTimedOutException;
import io.evitadb.api.observability.trace.TracingContext;
import io.evitadb.api.observability.trace.TracingContextProvider;
import io.evitadb.api.requestResponse.cdc.ChangeCaptureContent;
import io.evitadb.api.requestResponse.cdc.ChangeCapturePublisher;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCapture;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCaptureRequest;
import io.evitadb.api.requestResponse.mutation.EngineMutation;
import io.evitadb.api.requestResponse.progress.Progress;
import io.evitadb.api.requestResponse.progress.ProgressRecord;
import io.evitadb.api.requestResponse.progress.ProgressingFuture;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaEditor.CatalogSchemaBuilder;
import io.evitadb.api.requestResponse.schema.builder.InternalCatalogSchemaBuilder;
import io.evitadb.api.requestResponse.schema.mutation.CatalogSchemaMutation.CatalogSchemaWithImpactOnEntitySchemas;
import io.evitadb.api.requestResponse.schema.mutation.engine.CreateCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.DuplicateCatalogMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.MakeCatalogAliveMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.ModifyCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.ModifyCatalogSchemaNameMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.RemoveCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.SetCatalogMutabilityMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.SetCatalogStateMutation;
import io.evitadb.api.requestResponse.transaction.TransactionMutation;
import io.evitadb.api.task.ServerTask;
import io.evitadb.core.cache.CacheSupervisor;
import io.evitadb.core.cache.HeapMemoryCacheSupervisor;
import io.evitadb.core.cache.NoCacheSupervisor;
import io.evitadb.core.cdc.EngineStatisticsPublisher;
import io.evitadb.core.cdc.SystemChangeObserver;
import io.evitadb.core.exception.CatalogCorruptedException;
import io.evitadb.core.exception.CatalogInactiveException;
import io.evitadb.core.exception.CatalogTransitioningException;
import io.evitadb.core.exception.StorageImplementationNotFoundException;
import io.evitadb.core.executor.ClientRunnableTask;
import io.evitadb.core.executor.EmptySettings;
import io.evitadb.core.executor.ImmediateScheduledThreadPoolExecutor;
import io.evitadb.core.executor.ObservableExecutorServiceWithHardDeadline;
import io.evitadb.core.executor.ObservableThreadExecutor;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.core.metric.event.storage.CatalogStatisticsEvent;
import io.evitadb.core.metric.event.system.EvitaStatisticsEvent;
import io.evitadb.core.metric.event.system.RequestForkJoinPoolStatisticsEvent;
import io.evitadb.core.metric.event.system.ScheduledExecutorStatisticsEvent;
import io.evitadb.core.metric.event.system.TransactionForkJoinPoolStatisticsEvent;
import io.evitadb.core.metric.event.transaction.CatalogGoesLiveEvent;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.task.SessionKiller;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.function.Functions;
import io.evitadb.store.spi.EnginePersistenceService;
import io.evitadb.store.spi.EnginePersistenceServiceFactory;
import io.evitadb.store.spi.model.EngineState;
import io.evitadb.store.spi.model.reference.TransactionMutationWithWalFileReference;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.ExceptionUtils;
import io.evitadb.utils.IOUtils;
import io.evitadb.utils.NamingConvention;
import io.evitadb.utils.ReflectionLookup;
import io.evitadb.utils.StringUtils;
import jdk.jfr.FlightRecorder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Closeable;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static io.evitadb.utils.Assert.isTrue;
import static io.evitadb.utils.Assert.notNull;
import static java.util.Optional.of;
import static java.util.Optional.ofNullable;

/**
 * Evita is a specialized database with easy-to-use API for e-commerce systems. Purpose of this research is creating fast
 * and scalable engine that handles all complex tasks that e-commerce systems has to deal with on daily basis. Evita should
 * operate as a fast secondary lookup / search index used by application frontends. We aim for order of magnitude better
 * latency (10x faster or better) for common e-commerce tasks than other solutions based on SQL or NoSQL databases on the
 * same hardware specification. Evita should not be used for storing and handling primary data, and we don't aim for ACID
 * properties nor data corruption guarantees. Evita "index" must be treated as something that could be dropped any time and
 * built up from scratch easily again.
 *
 * This class represents main entrance to the evitaDB contents.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ThreadSafe
@Slf4j
public final class Evita implements EvitaContract {
	/**
	 * Index of {@link Catalog} that holds data specific to the catalog.
	 *
	 * @see Catalog
	 */
	private final Map<String, CatalogContract> catalogs;
	/**
	 * Index of latent {@link Catalog} that exists in the persistence storage, but is not loaded into memory.
	 * These catalogs cannot be queried until they are loaded into memory.
	 */
	private final Set<String> inactiveCatalogs;
	/**
	 * Index of {@link Catalog} that are switched to read-only mode.
	 */
	private final Set<String> readOnlyCatalogs;
	/**
	 * Data store shared among all instances of {@link SessionRegistry} that holds information about active sessions.
	 */
	private final SessionRegistry.SessionRegistryDataStore sessionRegistryDataStore = SessionRegistry.createDataStore();
	/**
	 * Keeps information about session registries for each catalog.
	 * {@link SessionRegistry} is the primary management service for active sessions, sessions that are stored in
	 * the {@link #sessionRegistryDataStore} map are present only for quick lookup for the session and are actively
	 * updated from the session registry (when the session is closed).
	 */
	private final Map<String, SessionRegistry> catalogSessionRegistries = CollectionUtils.createConcurrentHashMap(64);
	/**
	 * Formula supervisor is an entry point to the Evita cache. The idea is that each {@link Formula} can be identified by
	 * its {@link Formula#getHash()} method and when the supervisor identifies that certain formula
	 * is frequently used in query formulas it moves its memoized results to the cache. The non-computed formula
	 * of the same hash will be exchanged in next query that contains it with the cached formula that already contains
	 * memoized result.
	 */
	private final CacheSupervisor cacheSupervisor;
	/**
	 * Task that ensures that no inactive session is kept after
	 * {@link io.evitadb.api.configuration.ServerOptions#closeSessionsAfterSecondsOfInactivity()} inactivity timeout.
	 */
	@SuppressWarnings({"FieldCanBeLocal", "unused"})
	private final SessionKiller sessionKiller;
	/**
	 * Field contains the global - shared configuration for the entire Evita instance.
	 */
	@Getter private final EvitaConfiguration configuration;
	/**
	 * Reflection lookup is used to speed up reflection operation by memoizing the results for examined classes.
	 */
	private final ReflectionLookup reflectionLookup;
	/**
	 * Change observer that is used to notify all registered subscribers about changes in the catalogs.
	 */
	@Getter private final SystemChangeObserver changeObserver;
	/**
	 * Executor service that handles all requests to the Evita instance.
	 */
	private final ObservableThreadExecutor requestExecutor;
	/**
	 * Executor service that handles transaction handling, once transaction gets committed.
	 */
	private final ObservableThreadExecutor transactionExecutor;
	/**
	 * Scheduler service for executing asynchronous service tasks.
	 */
	@Getter
	private final Scheduler serviceExecutor;
	/**
	 * Contains the main evitaDB management service.
	 */
	private final EvitaManagement management;
	/**
	 * Provides the tracing context for tracking the execution flow in the application.
	 **/
	private final TracingContext tracingContext;
	/**
	 * Persistence service that is used to store and retrieve {@link EngineState} information from the persistence
	 * storage.
	 */
	private final EnginePersistenceService enginePersistenceService;
	/**
	 * Reference keeps the current state of the evitaDB engine instance.
	 */
	private final AtomicReference<EngineState> engineState = new AtomicReference<>();
	/**
	 * Lock that is used to synchronize access to the engine state.
	 */
	private final ReentrantLock engineStateLock = new ReentrantLock();
	/**
	 * Flag that is se to TRUE when Evita. is ready to serve application calls.
	 * Aim of this flag is to refuse any calls after {@link #close()} method has been called.
	 */
	@Getter private boolean active;
	/**
	 * List of futures that are used to load all catalogs in parallel during startup and when all are completed
	 * the list is cleared.
	 */
	private final AtomicReference<ProgressingFuture<Catalog>[]> initialLoadCatalogFutures;
	/**
	 * Flag that is set to TRUE when Evita fully loads all catalogs, that should be active after startup.
	 */
	@Getter private final CompletableFuture<Void> fullyInitialized;
	/**
	 * Map that keeps track of currently running mutations for each catalog.
	 */
	private final Map<String, Progress<?>> currentCatalogMutations = CollectionUtils.createConcurrentHashMap(64);
	/**
	 * Flag that is initially set to {@link ServerOptions#readOnly()} from {@link EvitaConfiguration}.
	 * The flag might be changed from false to TRUE one time using internal Evita API. This is used in test support.
	 */
	@Getter private boolean readOnly;

	/**
	 * Shuts down passed executor service in a safe manner.
	 *
	 * @param name            name of the executor service
	 * @param executorService executor service to be shut down
	 * @param waitSeconds     number of seconds to wait for the executor service to shut down
	 */
	private static void shutdownScheduler(
		@Nonnull String name, @Nonnull ExecutorService executorService, int waitSeconds) {
		executorService.shutdown();
		try {
			if (!executorService.awaitTermination(waitSeconds, TimeUnit.SECONDS)) {
				log.warn("EvitaDB executor `" + name + "` did not terminate in time, forcing shutdown.");
				executorService.shutdownNow();
			}
		} catch (InterruptedException ex) {
			log.warn("EvitaDB executor `" + name + "` did not terminate in time (interrupted), forcing shutdown.");
			executorService.shutdownNow();
		}
	}

	public Evita(@Nonnull EvitaConfiguration configuration) {
		this(configuration, true);
	}

	public Evita(@Nonnull EvitaConfiguration configuration, boolean scheduleCatalogLoading) {
		this.configuration = configuration;

		this.serviceExecutor = configuration.server().directExecutor() ?
			// in test environment we use immediate (synchronous) executor to avoid race conditions
			new Scheduler(new ImmediateScheduledThreadPoolExecutor()) :
			// in standard environment we use a scheduled thread pool executor
			new Scheduler(configuration.server().serviceThreadPool());
		this.requestExecutor = new ObservableThreadExecutor(
			"request", configuration.server().requestThreadPool(),
			this.serviceExecutor,
			configuration.server().queryTimeoutInMilliseconds(),
			configuration.server().directExecutor()
		);
		this.transactionExecutor = new ObservableThreadExecutor(
			"transaction",
			configuration.server().transactionThreadPool(),
			this.serviceExecutor,
			configuration.server().transactionTimeoutInMilliseconds(),
			// transaction handling must always run in a separate thread pool, even in tests
			// because it uses thread local variables for transaction management
			false
		);

		this.sessionKiller = of(configuration.server().closeSessionsAfterSecondsOfInactivity())
			.filter(it -> it > 0)
			.map(it -> new SessionKiller(it, this, this.serviceExecutor))
			.orElse(null);
		this.cacheSupervisor = configuration.cache().enabled() ?
			new HeapMemoryCacheSupervisor(configuration.cache(), this.serviceExecutor) : NoCacheSupervisor.INSTANCE;
		this.reflectionLookup = new ReflectionLookup(configuration.cache().reflection());
		this.tracingContext = TracingContextProvider.getContext();

		final ServiceLoader<EnginePersistenceServiceFactory> svcLoader = ServiceLoader.load(
			EnginePersistenceServiceFactory.class);
		this.enginePersistenceService = svcLoader
			.findFirst()
			.map(it -> it.create(configuration.storage(), configuration.transaction(), this.serviceExecutor))
			.orElseThrow(StorageImplementationNotFoundException::new);

		final EngineState engineState = this.enginePersistenceService.getEngineState();
		this.engineState.set(engineState);

		this.changeObserver = new SystemChangeObserver(
			this,
			this.configuration.server().changeDataCapture(),
			this.requestExecutor,
			this.serviceExecutor
		);

		this.catalogs = CollectionUtils.createConcurrentHashMap(engineState.activeCatalogs().length);
		this.inactiveCatalogs = CollectionUtils.createHashSet(engineState.inactiveCatalogs().length);
		Collections.addAll(this.inactiveCatalogs, engineState.inactiveCatalogs());
		this.readOnlyCatalogs = CollectionUtils.createHashSet(engineState.readOnlyCatalogs().length);
		Collections.addAll(this.readOnlyCatalogs, engineState.readOnlyCatalogs());
		this.management = new EvitaManagement(this);

		// register stubs for all inactive catalogs
		Arrays.stream(engineState.inactiveCatalogs())
		      .map(
				  it -> new UnusableCatalog(
				    it, CatalogState.INACTIVE,
				    this.configuration.storage().storageDirectory().resolve(it),
				    CatalogInactiveException::new
				  )
		      )
		      .forEach(it -> this.catalogs.put(it.getName(), it));

		// spawn parallel tasks to load all active catalogs, but don't wait for them to finish
		//noinspection unchecked
		this.initialLoadCatalogFutures = new AtomicReference<>(
			Arrays.stream(engineState.activeCatalogs())
			      .map(this::loadCatalogInternal)
			      .toArray(ProgressingFuture[]::new)
		);
		this.fullyInitialized = CompletableFuture.allOf(
			this.initialLoadCatalogFutures.get()
		).whenComplete(
			(__, throwable) -> {
				if (throwable != null) {
					log.error(
						"Errors encountered during start - {} catalog(s) could not be loaded!",
						this.getCatalogs()
						    .stream()
						    .map(CatalogContract::getCatalogState)
						    .filter(CatalogState.CORRUPTED::equals)
						    .count()
					);
				}
				// clear the initial load catalog futures, we don't need them anymore
				this.initialLoadCatalogFutures.set(null);
			}
		);

		this.active = true;
		this.readOnly = this.configuration.server().readOnly();

		// register the system observer that will capture changes in the system and emit observability events
		this.changeObserver.registerObserver(
			new ChangeSystemCaptureRequest(null, null, ChangeCaptureContent.BODY)
		).subscribe(
			new EngineStatisticsPublisher(
				this::emitEvitaStatistics,
				this::emitCatalogStatistics
			)
		);

		if (scheduleCatalogLoading) {
			scheduleInitialCatalogLoading();
		}
	}

	/**
	 * Schedules the initial loading of catalogs by executing all future tasks
	 * in the `initialLoadCatalogFutures` collection using the provided service executor.
	 * This method ensures that the catalog loading tasks are executed concurrently
	 * or sequentially based on the configuration of the service executor.
	 *
	 * The tasks in `initialLoadCatalogFutures` are instances of `ProgressingFuture`
	 * which encapsulate asynchronous operations for loading catalogs.
	 */
	public void scheduleInitialCatalogLoading() {
		final ProgressingFuture<Catalog>[] progressingFutures = this.initialLoadCatalogFutures.get();
		if (progressingFutures != null) {
			for (ProgressingFuture<Catalog> loadCatalogFuture : progressingFutures) {
				loadCatalogFuture.execute(this.serviceExecutor);
			}
		}
	}

	/**
	 * Retrieves an array of ProgressingFuture objects representing the initial catalog load futures.
	 * If no initial catalog load futures exist, returns an empty array.
	 *
	 * @return an array of ProgressingFuture objects for the initial catalog load,
	 *         or an empty array if none are present.
	 */
	@Nonnull
	public ProgressingFuture<Catalog>[] getInitialLoadCatalogFutures() {
		//noinspection unchecked
		return ofNullable(this.initialLoadCatalogFutures.get())
			.orElse((ProgressingFuture<Catalog>[]) ProgressingFuture.EMPTY_ARRAY);
	}

	/**
	 * Provides access to the request executor service, which is responsible
	 * for managing and executing request-level operations with hard deadlines
	 * within the Evita instance.
	 *
	 * @return An instance of {@link ObservableExecutorServiceWithHardDeadline}
	 * that handles request execution with hard deadlines for tasks.
	 */
	@Nonnull
	public ObservableExecutorServiceWithHardDeadline getRequestExecutor() {
		return this.requestExecutor;
	}

	/**
	 * Provides access to the transaction executor service, which is responsible for managing
	 * and executing transactional operations within the Evita instance.
	 *
	 * @return An instance of {@link ObservableExecutorServiceWithHardDeadline} that handles
	 * transaction execution with hard deadlines for tasks.
	 */
	@Nonnull
	public ObservableExecutorServiceWithHardDeadline getTransactionExecutor() {
		return this.transactionExecutor;
	}

	/**
	 * Method for internal use - allows emitting start events when observability facilities are already initialized.
	 * If we didn't postpone this initialization, events would become lost.
	 */
	public void emitStartObservabilityEvents() {
		// emit the statistics event
		FlightRecorder.addPeriodicEvent(
			EvitaStatisticsEvent.class,
			this::emitEvitaStatistics
		);
		FlightRecorder.addPeriodicEvent(
			RequestForkJoinPoolStatisticsEvent.class,
			() -> this.requestExecutor.emitPoolStatistics(
				(fj, steals) -> new RequestForkJoinPoolStatisticsEvent(
					steals,
					fj.getQueuedTaskCount(),
					fj.getActiveThreadCount(),
					fj.getRunningThreadCount()
				)
			)
		);
		FlightRecorder.addPeriodicEvent(
			TransactionForkJoinPoolStatisticsEvent.class,
			() -> this.transactionExecutor.emitPoolStatistics(
				(fj, steals) -> new TransactionForkJoinPoolStatisticsEvent(
					steals,
					fj.getQueuedTaskCount(),
					fj.getActiveThreadCount(),
					fj.getRunningThreadCount()
				)
			)
		);
		FlightRecorder.addPeriodicEvent(
			ScheduledExecutorStatisticsEvent.class,
			this.serviceExecutor::emitScheduledForkJoinPoolStatistics
		);
	}

	/**
	 * Method for internal use. Can switch Evita from read-write to read-only. This is an irreversible operation and
	 * can be used only once.
	 */
	public void setReadOnly() {
		Assert.isTrue(!this.readOnly, "Only read-write evita can be switched to read-only instance!");
		this.readOnly = true;
	}

	/**
	 * Returns list of all catalogs maintained by this evitaDB instance.
	 * Part of PRIVATE API.
	 */
	@Nonnull
	public Collection<CatalogContract> getCatalogs() {
		return this.catalogs.values();
	}

	@Override
	@Nonnull
	@SuppressWarnings("resource")
	public EvitaSessionContract createSession(@Nonnull SessionTraits traits) {
		notNull(traits.catalogName(), "Catalog name is mandatory information.");
		return createSessionInternal(traits).session();
	}

	/**
	 * Checks if sessions were forcefully closed for the specified catalog and session ID.
	 *
	 * @param catalogName the name of the catalog for which to check if sessions were forcefully closed; must not be null
	 * @param sessionId the unique identifier of the session to check; must not be null
	 * @return true if sessions were forcefully closed for the specified catalog and session ID, false otherwise
	 */
	public boolean wasSessionForcefullyClosedForCatalog(@Nonnull String catalogName, @Nonnull UUID sessionId) {
		return ofNullable(this.catalogSessionRegistries.get(catalogName))
			.map(it -> it.wereSessionsForcefullyClosedForCatalog(sessionId))
			.orElse(false);
	}

	/**
	 * Clears all session registries and their temporary information.
	 */
	public void clearSessionRegistries() {
		for (SessionRegistry value : this.catalogSessionRegistries.values()) {
			value.clearTemporaryInformation();
		}
	}

	/**
	 * Checks whether the current object has been fully initialized.
	 *
	 * @return true if the initialization process is complete, false otherwise
	 */
	public boolean isFullyInitialized() {
		return this.fullyInitialized.isDone();
	}

	@Override
	@Nonnull
	public Optional<EvitaSessionContract> getSessionById(@Nonnull UUID sessionId) {
		return this.sessionRegistryDataStore.getActiveSessionById(sessionId);
	}

	@Override
	public void terminateSession(@Nonnull EvitaSessionContract session) {
		assertActive();
		session.close();
	}

	@Override
	@Nonnull
	public Set<String> getCatalogNames() {
		return this.catalogs.keySet();
	}

	@Nonnull
	@Override
	public Optional<CatalogState> getCatalogState(@Nonnull String catalogName) {
		return ofNullable(this.catalogs.get(catalogName))
			.map(CatalogContract::getCatalogState);
	}

	@Override
	@Nonnull
	public CatalogSchemaBuilder defineCatalog(@Nonnull String catalogName) {
		return getCatalogInstance(catalogName)
			.map(catalogContract -> new InternalCatalogSchemaBuilder(catalogContract.getSchema()))
			.orElseGet(
				() -> ExceptionUtils.unwrapCompletionException(
					() -> {
						// we need to wat synchronously until schema is created
						applyMutation(new CreateCatalogSchemaMutation(catalogName))
							.onCompletion()
							.toCompletableFuture()
							.join();
						return new InternalCatalogSchemaBuilder(
							getCatalogInstanceOrThrowException(catalogName).getSchema()
						);
					}
				)
			);
	}

	@Nonnull
	@Override
	public Progress<CommitVersions> makeCatalogAliveWithProgress(@Nonnull String catalogName) {
		assertActive();
		return applyMutation(new MakeCatalogAliveMutation(catalogName));
	}

	@Nonnull
	@Override
	public Progress<Void> activateCatalogWithProgress(@Nonnull String catalogName) {
		assertActive();
		return applyMutation(new SetCatalogStateMutation(catalogName, true));
	}

	@Nonnull
	@Override
	public Progress<Void> deactivateCatalogWithProgress(@Nonnull String catalogName) {
		assertActive();
		return applyMutation(new SetCatalogStateMutation(catalogName, false));
	}

	@Nonnull
	@Override
	public Progress<Void> makeCatalogMutableWithProgress(@Nonnull String catalogName) {
		assertActive();
		return applyMutation(new SetCatalogMutabilityMutation(catalogName, true));
	}

	@Nonnull
	@Override
	public Progress<Void> makeCatalogImmutableWithProgress(@Nonnull String catalogName) {
		assertActive();
		return applyMutation(new SetCatalogMutabilityMutation(catalogName, false));
	}

	@Nonnull
	@Override
	public Progress<CommitVersions> renameCatalogWithProgress(@Nonnull String catalogName, @Nonnull String newCatalogName) {
		assertActive();
		return applyMutation(new ModifyCatalogSchemaNameMutation(catalogName, newCatalogName, false));
	}

	@Nonnull
	@Override
	public Progress<CommitVersions> replaceCatalogWithProgress(@Nonnull String catalogNameToBeReplacedWith, @Nonnull String catalogNameToBeReplaced) {
		assertActive();
		return applyMutation(new ModifyCatalogSchemaNameMutation(catalogNameToBeReplacedWith, catalogNameToBeReplaced, true));
	}

	@Nonnull
	@Override
	public Optional<Progress<Void>> deleteCatalogIfExistsWithProgress(@Nonnull String catalogName) {
		assertActive();
		if (!this.catalogs.containsKey(catalogName)) {
			// if catalog does not exist, we don't need to do anything
			return Optional.empty();
		} else {
			return Optional.of(applyMutation(new RemoveCatalogSchemaMutation(catalogName)));
		}
	}

	@Nonnull
	@Override
	public <T> Progress<T> applyMutation(
		@Nonnull EngineMutation<T> engineMutation,
		@Nullable IntConsumer progressObserver
	) {
		assertActiveAndWritable();
		final Progress<T> result;
		try {
			if (
				this.engineStateLock.tryLock(this.configuration.server().transactionTimeoutInMilliseconds(), TimeUnit.MILLISECONDS)
			) {
				final EngineState theEngineState = this.engineState.get();

				// verify that we can perform the mutation
				engineMutation.verifyApplicability(this);

				// first store the mutation into the persistence service
				final TransactionMutationWithWalFileReference txMutationWithWalFileReference = this.enginePersistenceService.appendWal(
					theEngineState.version() + 1,
					engineMutation
				);

				// notify system observer about the mutation
				this.changeObserver.processMutation(txMutationWithWalFileReference.transactionMutation());
				this.changeObserver.processMutation(engineMutation);

				final Runnable onCompletion = () -> updateEngineStateAfterEngineMutation(theEngineState, txMutationWithWalFileReference);
				final Runnable onFailure = () -> dropChangesAfterUnsuccessfulEngineMutation(theEngineState);

				final IntConsumer nullSafeObserver = progressObserver == null ? Functions.noOpIntConsumer() : progressObserver;
				if (engineMutation instanceof CreateCatalogSchemaMutation createCatalogSchema) {
					//noinspection unchecked
					result = (Progress<T>) createCatalogInternal(
						createCatalogSchema,
						nullSafeObserver,
						onCompletion,
						onFailure
					);
				} else if (engineMutation instanceof ModifyCatalogSchemaNameMutation modifyCatalogSchemaName) {
					if (modifyCatalogSchemaName.isOverwriteTarget() && this.catalogs.containsKey(
						modifyCatalogSchemaName.getNewCatalogName())) {
						//noinspection unchecked
						result = (Progress<T>) replaceCatalogInternal(
							modifyCatalogSchemaName,
							nullSafeObserver,
							onCompletion,
							onFailure
						);
					} else {
						//noinspection unchecked
						result = (Progress<T>) renameCatalogInternal(
							modifyCatalogSchemaName,
							nullSafeObserver,
							onCompletion,
							onFailure
						);
					}
				} else if (engineMutation instanceof ModifyCatalogSchemaMutation modifyCatalogSchema) {
					//noinspection unchecked
					result = (Progress<T>) modifyCatalogSchemaInternal(
						modifyCatalogSchema,
						nullSafeObserver,
						onCompletion,
						onFailure
					);
				} else if (engineMutation instanceof RemoveCatalogSchemaMutation removeCatalogSchema) {
					//noinspection unchecked
					result = (Progress<T>) removeCatalogInternal(
						removeCatalogSchema,
						nullSafeObserver,
						onCompletion,
						onFailure
					);
				} else if (engineMutation instanceof MakeCatalogAliveMutation makeCatalogAliveMutation) {
					//noinspection unchecked
					result = (Progress<T>) makeCatalogAliveInternal(
						makeCatalogAliveMutation,
						nullSafeObserver,
						onCompletion,
						onFailure
					);
				} else if (engineMutation instanceof SetCatalogStateMutation setCatalogStateMutation) {
					//noinspection unchecked
					result = (Progress<T>) setCatalogStateInternal(
						setCatalogStateMutation,
						nullSafeObserver,
						onCompletion,
						onFailure
					);
				} else if (engineMutation instanceof SetCatalogMutabilityMutation setCatalogMutabilityMutation) {
					//noinspection unchecked
					result = (Progress<T>) setCatalogMutabilityInternal(
						setCatalogMutabilityMutation,
						nullSafeObserver,
						onCompletion,
						onFailure
					);
				} else if (engineMutation instanceof DuplicateCatalogMutation duplicateCatalogMutation) {
					/* TODO JNO - IMPLEMENT ME */
					result = null;
				} else {
					throw new EvitaInvalidUsageException(
						"Unknown engine mutation: `" + engineMutation.getClass() + "`!");
				}
			} else {
				throw new TransactionTimedOutException(
					"EvitaDB transaction timed out while waiting for engine state lock! " +
						"Please increase `evitaDB.server.transactionTimeoutInMilliseconds` setting."
				);
			}
		} catch (InterruptedException e) {
			// do nothing
			Thread.currentThread().interrupt();
			throw new TransactionTimedOutException("Interrupted while waiting for an engine state lock!");
		} finally {
			if (this.engineStateLock.isHeldByCurrentThread()) {
				this.engineStateLock.unlock();
			}
		}

		return result;
	}

	@Override
	public <T> T queryCatalog(
		@Nonnull String catalogName, @Nonnull Function<EvitaSessionContract, T> queryLogic,
		@Nullable SessionFlags... flags
	) {
		assertActive();
		try (final EvitaSessionContract session = this.createSession(new SessionTraits(catalogName, flags))) {
			return queryLogic.apply(session);
		}
	}

	@Override
	public void queryCatalog(
		@Nonnull String catalogName, @Nonnull Consumer<EvitaSessionContract> queryLogic,
		@Nullable SessionFlags... flags
	) {
		assertActive();
		try (final EvitaSessionContract session = this.createSession(new SessionTraits(catalogName, flags))) {
			queryLogic.accept(session);
		}
	}

	@Nonnull
	@Override
	public <T> CompletableFuture<T> queryCatalogAsync(
		@Nonnull String catalogName, @Nonnull Function<EvitaSessionContract, T> queryLogic,
		@Nullable SessionFlags... flags
	) {
		return CompletableFuture.supplyAsync(
			() -> {
				assertActive();
				try (final EvitaSessionContract session = this.createSession(new SessionTraits(catalogName, flags))) {
					return queryLogic.apply(session);
				}
			},
			this.requestExecutor
		);
	}

	@Nonnull
	@Override
	public <T> CompletionStage<T> updateCatalogAsync(
		@Nonnull String catalogName,
		@Nonnull Function<EvitaSessionContract, T> updater,
		@Nonnull CommitBehavior commitBehaviour,
		@Nullable SessionFlags... flags
	) {
		assertActive();
		if (this.readOnly && flags != null && Arrays.stream(flags).noneMatch(it -> it == SessionFlags.DRY_RUN)) {
			throw ReadOnlyException.engineReadOnly();
		}
		if (this.readOnlyCatalogs.contains(catalogName)) {
			throw ReadOnlyException.catalogReadOnly(catalogName);
		}
		final SessionTraits traits = new SessionTraits(
			catalogName,
			commitBehaviour,
			flags == null ?
				new SessionFlags[]{SessionFlags.READ_WRITE} :
				ArrayUtils.insertRecordIntoArrayOnIndex(SessionFlags.READ_WRITE, flags, flags.length)
		);
		final CreatedSession createdSession = this.createSessionInternal(traits);
		try {
			final T resultValue = createdSession.session().execute(updater);
			// join the transaction future and return the result
			return createdSession.commitProgress()
			                     .on(commitBehaviour)
			                     .handle((__, ex) -> {
				                     if (ex != null) {
					                     throw new CompletionException(ex);
				                     }
				                     return resultValue;
			                     });
		} catch (RuntimeException ex) {
			createdSession.commitProgress().completeExceptionally(ex);
			throw ex;
		} finally {
			createdSession.session().closeNow(commitBehaviour);
		}
	}

	@Nonnull
	@Override
	public CommitProgress updateCatalogAsync(
		@Nonnull String catalogName,
		@Nonnull Consumer<EvitaSessionContract> updater,
		@Nonnull CommitBehavior commitBehaviour,
		@Nullable SessionFlags... flags
	) {
		assertActive();
		if (this.readOnly && flags != null && Arrays.stream(flags).noneMatch(it -> it == SessionFlags.DRY_RUN)) {
			throw ReadOnlyException.engineReadOnly();
		}
		if (this.readOnlyCatalogs.contains(catalogName)) {
			throw ReadOnlyException.catalogReadOnly(catalogName);
		}
		final SessionTraits traits = new SessionTraits(
			catalogName,
			commitBehaviour,
			flags == null ?
				new SessionFlags[]{SessionFlags.READ_WRITE} :
				ArrayUtils.insertRecordIntoArrayOnIndex(SessionFlags.READ_WRITE, flags, flags.length)
		);

		final CreatedSession createdSession = this.createSessionInternal(traits);
		try {
			final EvitaInternalSessionContract theSession = createdSession.session();
			theSession.execute(updater);
			return createdSession.commitProgress();
		} catch (Throwable ex) {
			createdSession.commitProgress().completeExceptionally(ex);
			return createdSession.commitProgress();
		} finally {
			createdSession.session().closeNow(commitBehaviour);
		}
	}

	/**
	 * Retrieves a stream of committed mutations starting with a {@link TransactionMutation} that will transition
	 * the engine to the given version. The stream goes through all the mutations in this transaction and continues
	 * forward with next transaction after that until the end of the WAL.
	 *
	 * BEWARE! Stream implements {@link java.io.Closeable} and needs to be closed to release resources.
	 *
	 * @param version version of the engine to start the stream with
	 * @return a stream containing committed mutations
	 */
	@Nonnull
	public Stream<EngineMutation<?>> getCommittedMutationStream(long version) {
		return this.enginePersistenceService.getCommittedMutationStream(version);
	}

	/**
	 * Retrieves a stream of committed mutations starting with a {@link TransactionMutation} that will transition
	 * the engine to the given version. The stream goes through all the mutations in this transaction from last to
	 * first one and continues backward with previous transaction after that until the beginning of the WAL.
	 *
	 * BEWARE! Stream implements {@link java.io.Closeable} and needs to be closed to release resources.
	 *
	 * @param version version of the engine to start the stream with, if null is provided the stream will start
	 *                with the last committed transaction
	 * @return a stream containing committed mutations
	 */
	@Nonnull
	public Stream<EngineMutation<?>> getReversedCommittedMutationStream(@Nullable Long version) {
		return this.enginePersistenceService.getReversedCommittedMutationStream(version);
	}

	@Nonnull
	@Override
	public ChangeCapturePublisher<ChangeSystemCapture> registerSystemChangeCapture(
		@Nonnull ChangeSystemCaptureRequest request
	) {
		return this.changeObserver.registerObserver(request);
	}

	@Nonnull
	@Override
	public EvitaManagement management() {
		return this.management;
	}

	/**
	 * Returns set of all active (currently open) sessions.
	 * Part of PRIVATE API.
	 */
	@Nonnull
	public Stream<EvitaSessionContract> getActiveSessions() {
		return this.sessionRegistryDataStore.getActiveSessions();
	}

	/**
	 * Terminates Evita instance, releases all resources, locks and cleans memory.
	 * This method is idempotent and may be called multiple times. Only first call is really processed and others are
	 * ignored.
	 */
	@Override
	public void close() {
		if (this.active) {
			this.active = false;
			closeInternal();
		}
	}

	/**
	 * Returns catalog instance for passed catalog name or throws exception.
	 *
	 * @throws IllegalArgumentException when no catalog of such name is found
	 */
	@Nonnull
	public Optional<CatalogContract> getCatalogInstance(@Nonnull String catalog) {
		return ofNullable(this.catalogs.get(catalog));
	}

	/**
	 * Returns catalog instance for passed catalog name or throws exception.
	 *
	 * @throws IllegalArgumentException when no catalog of such name is found
	 */
	@Nonnull
	public CatalogContract getCatalogInstanceOrThrowException(@Nonnull String catalog) throws CatalogNotFoundException {
		return getCatalogInstance(catalog)
			.orElseThrow(() -> new CatalogNotFoundException(catalog));
	}

	/**
	 * Asynchronously executes supplier lambda in the request thread pool.
	 *
	 * @param supplier supplier to be executed
	 * @param <T>      type of the result
	 * @return future with result of the supplier
	 */
	@Nonnull
	public <T> CompletionStage<T> executeAsyncInRequestThreadPool(@Nonnull Supplier<T> supplier) {
		return CompletableFuture.supplyAsync(supplier, this.requestExecutor);
	}

	/**
	 * Retrieves the current state of the Evita engine. The engine state represents
	 * the operational condition or status of the Evita instance at the moment of invocation.
	 *
	 * @return the current {@link EngineState} of the Evita instance
	 */
	@Nonnull
	public EngineState getEngineState() {
		return this.engineState.get();
	}

	/**
	 * Retrieves the mutation progress of the engine for a specified catalog.
	 *
	 * @param catalogName the name of the catalog whose mutation progress is to be retrieved; must not be null
	 * @return an Optional containing the mutation progress if present, or an empty Optional if no progress data exists for the specified catalog
	 */
	@Nonnull
	public Optional<Progress<?>> getEngineMutationProgress(@Nonnull String catalogName) {
		return ofNullable(this.currentCatalogMutations.get(catalogName));
	}

	/**
	 * Loads catalog from the designated directory. If the catalog is corrupted, it will be marked as such, but it'll
	 * still be added to the list of catalogs.
	 *
	 * @param catalogName name of the catalog
	 * @return progress wrapped into a server task that can be used to monitor the loading process
	 */
	@Nonnull
	ServerTask<EmptySettings, Void> createLoadCatalogTask(@Nonnull String catalogName) {
		final ProgressingFuture<Catalog> progressingFuture = loadCatalogInternal(catalogName);
		return new ClientRunnableTask<>(
			catalogName,
			"LoadCatalogTask",
			"Loading catalog " + catalogName + " from disk...",
			EmptySettings.INSTANCE,
			() -> {
				progressingFuture.execute(this.transactionExecutor);
				// wait for the catalog to be loaded
				progressingFuture.join();
			},
			exception -> {
				log.error("Catalog {} is corrupted!", catalogName, exception);
				this.catalogs.put(
					catalogName,
					new UnusableCatalog(
						catalogName,
						CatalogState.CORRUPTED,
						this.configuration.storage().storageDirectory().resolve(catalogName),
						(cn, path) -> new CatalogCorruptedException(cn, path, exception)
					)
				);
				this.emitEvitaStatistics();
			}
		);
	}

	/**
	 * Loads catalog from the designated directory. If the catalog is corrupted, it will be marked as such, but it'll
	 * still be added to the list of catalogs.
	 *
	 * @param catalogName name of the catalog
	 */
	@Nonnull
	ProgressingFuture<Catalog> loadCatalogInternal(@Nonnull String catalogName) {
		this.catalogs.put(
			catalogName,
			new UnusableCatalog(
				catalogName,
				CatalogState.BEING_ACTIVATED,
				this.configuration.storage().storageDirectory().resolve(catalogName),
				(cn, path) -> new CatalogTransitioningException(cn, path, CatalogState.BEING_ACTIVATED)
			)
		);

		final long start = System.nanoTime();
		return Catalog.loadCatalog(
			catalogName,
			this.cacheSupervisor,
			this.configuration,
			this.reflectionLookup,
			this.serviceExecutor,
			this.management.exportFileService(),
			this.requestExecutor,
			this.transactionExecutor,
			this::replaceCatalogReference,
			(cn, catalog) -> {
				log.info("Catalog {} fully loaded in: {}", catalogName, StringUtils.formatNano(System.nanoTime() - start));
				catalog.processWriteAheadLog(
					updatedCatalog -> {
						this.catalogs.put(catalogName, updatedCatalog);
						if (updatedCatalog instanceof Catalog theUpdatedCatalog) {
							theUpdatedCatalog.notifyCatalogPresentInLiveView();
						}
					}
				);
				this.emitCatalogStatistics(catalogName);
			},
			(cn, exception) -> {
				log.error("Catalog {} is corrupted!", cn, exception);
				this.catalogs.put(
					cn,
					new UnusableCatalog(
						cn,
						CatalogState.CORRUPTED,
						this.configuration.storage().storageDirectory().resolve(cn),
						(tcn, path) -> new CatalogCorruptedException(tcn, path, exception)
					)
				);
				this.emitEvitaStatistics();
			},
			this.tracingContext
		);
	}

	/**
	 * Verifies this instance is still active.
	 */
	void assertActive() {
		if (!this.active) {
			throw new InstanceTerminatedException("instance");
		}
	}

	/**
	 * Verifies this instance is still active and not in read-only mode.
	 */
	void assertActiveAndWritable() {
		assertActive();
		if (this.readOnly) {
			throw ReadOnlyException.engineReadOnly();
		}
	}

	/**
	 * Activates the specified catalog based on its current state. Throws appropriate exceptions
	 * for inactive or corrupted catalogs or any unknown catalog type.
	 *
	 * @param makeCatalogAliveMutation The mutation object containing the catalog name to be activated.
	 *                                 Must be non-null and must reference a valid catalog.
	 */
	@Nonnull
	private Progress<CommitVersions> makeCatalogAliveInternal(
		@Nonnull MakeCatalogAliveMutation makeCatalogAliveMutation,
		@Nonnull IntConsumer progressObserver,
		@Nonnull Runnable onCompletion,
		@Nonnull Runnable onFailure
	) {
		final String catalogName = makeCatalogAliveMutation.getCatalogName();
		assertNoCatalogEngineMutationInProgress(catalogName);
		final CatalogContract catalog = this.catalogs.get(catalogName);
		if (catalog instanceof Catalog theCatalog) {
			final CatalogGoesLiveEvent event = new CatalogGoesLiveEvent(catalogName);
			return new ProgressRecord<>(
				"Making catalog `" + catalogName + "` alive",
				progressObserver,
				new ProgressingFuture<>(
					1,
					Collections.singletonList(theCatalog.flush()),
					(theFuture, __) -> {
						CommitVersions commitVersions = theCatalog.goLive();
						theFuture.updateProgress(1);
						onCompletion.run();
						log.info("Catalog `{}` is now alive!", catalogName);
						// emit the event
						event.finish().commit();
						this.currentCatalogMutations.remove(catalogName);
						return commitVersions;
					},
					ex -> {
						onFailure.run();
						this.currentCatalogMutations.remove(catalogName);
					}
				),
				progress -> this.currentCatalogMutations.put(catalogName, progress),
				this.transactionExecutor
			);
		} else if (catalog instanceof UnusableCatalog unusableCatalog) {
			throw unusableCatalog.getRepresentativeException();
		} else if (catalog == null) {
			throw new CatalogNotFoundException(catalogName);
		} else {
			throw new EvitaInvalidUsageException("Unknown catalog type: `" + catalog.getClass() + "`!");
		}
	}

	/**
	 * Sets the internal state of the catalog to active or inactive based on the provided mutation.
	 * This method handles the activation or deactivation of a catalog and notifies the observer about the progress
	 * while executing the task. It also triggers completion or failure callbacks accordingly.
	 *
	 * @param setCatalogStateMutation the mutation containing the details about catalog state changes, such as the catalog name and the desired active state.
	 * @param progressObserver the consumer that observes and reports progress updates.
	 * @param onCompletion a callback to be invoked when the operation completes successfully.
	 * @param onFailure a callback to be invoked when the operation fails.
	 * @return a {@link Progress} object tracking the completion and progress of the operation.
	 */
	@Nonnull
	private Progress<Void> setCatalogStateInternal(
		@Nonnull SetCatalogStateMutation setCatalogStateMutation,
		@Nonnull IntConsumer progressObserver,
		@Nonnull Runnable onCompletion,
		@Nonnull Runnable onFailure
	) {
		final String catalogName = setCatalogStateMutation.getCatalogName();
		assertNoCatalogEngineMutationInProgress(catalogName);
		if (setCatalogStateMutation.isActive()) {
			return new ProgressRecord<>(
				"Activating catalog `" + catalogName + "`",
				progressObserver,
				new ProgressingFuture<>(
					0,
					Collections.singletonList(loadCatalogInternal(catalogName)),
					(progressingFuture, theCatalog) -> {
						this.inactiveCatalogs.remove(catalogName);
						onCompletion.run();
						this.currentCatalogMutations.remove(catalogName);
						log.info("Catalog `{}` is now active!", catalogName);
						return null;
					},
					ex -> {
						onFailure.run();
						this.currentCatalogMutations.remove(catalogName);
					}
				),
				progress -> this.currentCatalogMutations.put(catalogName, progress),
				this.transactionExecutor
			);
		} else {
			return new ProgressRecord<>(
				"Deactivating catalog `" + catalogName + "`",
				progressObserver,
				new ProgressingFuture<>(
					0,
					progressingFuture -> {
						final CatalogContract theCatalog = this.catalogs.put(
							catalogName,
							new UnusableCatalog(
								catalogName, CatalogState.INACTIVE,
								this.configuration.storage().storageDirectory().resolve(catalogName),
								CatalogInactiveException::new
							)
						);
						if (theCatalog != null) {
							theCatalog.terminate();
						}
						this.inactiveCatalogs.add(catalogName);
						onCompletion.run();
						this.currentCatalogMutations.remove(catalogName);
						log.info("Catalog `{}` is now inactive!", catalogName);
						return null;
					},
					ex -> {
						onFailure.run();
						this.currentCatalogMutations.remove(catalogName);
					}
				),
				progress -> this.currentCatalogMutations.put(catalogName, progress),
				this.transactionExecutor
			);
		}
	}

	/**
	 * Modifies the mutability state of a specified catalog. This method processes the
	 * input mutation to either make the catalog mutable (read-write) or immutable (read-only).
	 *
	 * @param setCatalogMutabilityMutation The mutation containing the catalog name
	 *                                     and desired mutability state.
	 * @param progressObserver A callback that accepts the progress in the form of an integer.
	 * @param onCompletion A callback that runs when the operation is completed successfully.
	 * @param onFailure A callback that runs when the operation encounters a failure.
	 * @return A {@code Progress<Void>} object representing the progress and result of
	 *         the mutability modification operation.
	 */
	@Nonnull
	private Progress<Void> setCatalogMutabilityInternal(
		@Nonnull SetCatalogMutabilityMutation setCatalogMutabilityMutation,
		@Nonnull IntConsumer progressObserver,
		@Nonnull Runnable onCompletion,
		@Nonnull Runnable onFailure
	) {
		final String catalogName = setCatalogMutabilityMutation.getCatalogName();
		assertNoCatalogEngineMutationInProgress(catalogName);
		if (setCatalogMutabilityMutation.isMutable()) {
			if (this.readOnlyCatalogs.contains(catalogName)) {
				return new ProgressRecord<>(
					"Setting catalog `" + catalogName + "` to read-write",
					progressObserver,
					new ProgressingFuture<>(
						0,
						(progressingFuture) -> {
							this.readOnlyCatalogs.remove(catalogName);
							onCompletion.run();
							this.currentCatalogMutations.remove(catalogName);
							log.info("Catalog `{}` is now read-write!", catalogName);
							return null;
						},
						ex -> {
							onFailure.run();
							this.currentCatalogMutations.remove(catalogName);
						}
					),
					progress -> this.currentCatalogMutations.put(catalogName, progress),
					this.transactionExecutor
				);
			} else {
				throw new InvalidMutationException(
					"Catalog `" + catalogName + "` is already mutable!"
				);
			}
		} else {
			if (!this.readOnlyCatalogs.contains(catalogName)) {
				return new ProgressRecord<>(
					"Setting catalog `" + catalogName + "` to read-only",
					progressObserver,
					new ProgressingFuture<>(
						0,
						(progressingFuture) -> {
							this.readOnlyCatalogs.add(catalogName);
							onCompletion.run();
							this.currentCatalogMutations.remove(catalogName);
							log.info("Catalog `{}` is now read-only!", catalogName);
							return null;
						},
						ex -> {
							onFailure.run();
							this.currentCatalogMutations.remove(catalogName);
						}
					),
					progress -> this.currentCatalogMutations.put(catalogName, progress),
					this.transactionExecutor
				);
			} else {
				throw new InvalidMutationException(
					"Catalog `" + catalogName + "` is already read-only!"
				);
			}
		}
	}

	/**
	 * Closes all active sessions associated with the specified catalog and suspends further operations.
	 *
	 * @param catalogName      the name of the catalog whose sessions are to be closed and suspended
	 * @param suspendOperation the operation to be executed during the suspension of the catalog
	 */
	@Nonnull
	public Optional<SuspensionInformation> closeAllSessionsAndSuspend(
		@Nonnull String catalogName,
		@Nonnull SuspendOperation suspendOperation
	) {
		return ofNullable(this.catalogSessionRegistries.get(catalogName))
			.flatMap(it -> it.closeAllActiveSessionsAndSuspend(suspendOperation));
	}

	/**
	 * Discards the suspension state of the session registry associated with the given catalog name, if present.
	 * The method resumes operations for the session registry if it exists for the provided catalog name.
	 *
	 * @param catalogName The name of the catalog whose suspension state should be discarded. Must not be null.
	 */
	public void discardSuspension(@Nonnull String catalogName) {
		ofNullable(this.catalogSessionRegistries.get(catalogName))
			.ifPresent(SessionRegistry::resumeOperations);
	}

	/*
		PRIVATE METHODS
	*/

	/**
	 * Updates the state of the engine after applying a mutation. This method creates a new engine state
	 * with an incremented version, updates the persistence layer with the new state, and notifies
	 * observers about the change.
	 *
	 * @param theEngineState The current state of the engine to be updated.
	 * @param txMutationWithWalFileReference The transaction mutation containing the WAL file reference to be
	 *                                        associated with the update.
	 */
	private void updateEngineStateAfterEngineMutation(
		@Nonnull EngineState theEngineState,
		@Nonnull TransactionMutationWithWalFileReference txMutationWithWalFileReference
	) {
		this.engineStateLock.lock();
		try {
			// create new engine state with the incremented version, and store it in the persistence service
			final EngineState newState = EngineState
				.builder()
				.version(theEngineState.version() + 1)
				.activeCatalogs(
					this.catalogs.keySet()
					             .stream()
					             .filter(it -> !this.inactiveCatalogs.contains(it))
					             .toArray(String[]::new)
				)
				.inactiveCatalogs(this.inactiveCatalogs.toArray(ArrayUtils.EMPTY_STRING_ARRAY))
				.readOnlyCatalogs(this.readOnlyCatalogs.toArray(ArrayUtils.EMPTY_STRING_ARRAY))
				.walFileReference(txMutationWithWalFileReference.walFileReference())
				.build();
			this.enginePersistenceService.storeEngineState(newState);
			this.engineState.set(newState);
			// finally, notify the change observer about the new version
			this.changeObserver.notifyVersionPresentInLiveView(newState.version());
		} finally {
			this.engineStateLock.unlock();
		}
	}

	/**
	 * Drops all changes recorded after an unsuccessful engine mutation attempt to revert the state
	 * to a stable and consistent version.
	 *
	 * @param theEngineState the current state of the engine, including version information, used to
	 *                       identify and forget mutations made after this version.
	 */
	private void dropChangesAfterUnsuccessfulEngineMutation(@Nonnull EngineState theEngineState) {
		// forget about the mutation in case of error
		this.changeObserver.forgetMutationsAfter(theEngineState.version());
	}

	/**
	 * Asserts that there is no active engine mutation in progress for the specified catalog.
	 * Throws an exception if a mutation is already in progress for the given catalog.
	 *
	 * @param catalogName the name of the catalog to check for active mutations, must not be null
	 */
	private void assertNoCatalogEngineMutationInProgress(@Nonnull String catalogName) {
		Assert.isPremiseValid(
			this.currentCatalogMutations.get(catalogName) == null,
			"Catalog `" + catalogName + "` already has active engine mutation in progress!"
		);
	}

	/**
	 * Creates new catalog in the evitaDB.
	 */
	@Nonnull
	private Progress<CommitVersions> createCatalogInternal(
		@Nonnull CreateCatalogSchemaMutation createCatalogSchema,
		@Nonnull IntConsumer progressObserver,
		@Nonnull Runnable onCompletion,
		@Nonnull Runnable onFailure
	) {
		final String catalogName = createCatalogSchema.getCatalogName();
		this.catalogs.compute(
			catalogName,
			(theCatalogName, existingCatalog) -> {
				if (existingCatalog == null) {
					return new UnusableCatalog(
						catalogName,
						CatalogState.BEING_CREATED,
						this.configuration.storage().storageDirectory().resolve(catalogName),
						(cn, path) -> new CatalogTransitioningException(cn, path, CatalogState.BEING_CREATED)
					);
				} else {
					throw new CatalogAlreadyPresentException(catalogName, existingCatalog.getName());
				}
			}
		);
		final ProgressRecord<CommitVersions> progress = new ProgressRecord<>(
			"Creating catalog `" + catalogName + "`",
			progressObserver
		);
		// there is no slow operation, so we can complete the operation synchronously
		try {
			assertNoCatalogEngineMutationInProgress(catalogName);
			this.currentCatalogMutations.put(catalogName, progress);
			final CatalogContract theCatalog = this.catalogs.compute(
				catalogName,
				(theCatalogName, existingCatalog) -> {
					Assert.isPremiseValid(
						existingCatalog != null &&
							existingCatalog.getCatalogState() == CatalogState.BEING_CREATED &&
							catalogName.equals(existingCatalog.getName()),
						"Sanity check failed!" +
							(existingCatalog == null ?
								" Existing catalog is null!" :
								" Existing catalog `" + existingCatalog.getName() + "` is in state " +
								"`" + existingCatalog.getCatalogState() + "` but expected to be in state `BEING_CREATED`!"
							)

					);
					final CatalogSchemaContract catalogSchema = Objects.requireNonNull(createCatalogSchema.mutate(null))
					                                                   .updatedCatalogSchema();
					// check the names in all naming conventions are unique in the entity schema
					this.catalogs
						.values()
						.stream()
						.filter(it -> it != existingCatalog)
						.flatMap(it -> {
							final Stream<Entry<NamingConvention, String>> nameStream;
							if (it instanceof UnusableCatalog) {
								nameStream = NamingConvention.generate(it.getName())
								                             .entrySet()
								                             .stream();
							} else {
								nameStream = it.getSchema()
								               .getNameVariants()
								               .entrySet()
								               .stream();
							}
							return nameStream
								.map(name -> new CatalogNameInConvention(
									it.getName(), name.getKey(),
									name.getValue()
								));
						})
						.filter(nameVariant -> nameVariant.name()
						                                  .equals(catalogSchema.getNameVariant(
							                                  nameVariant.convention())))
						.map(nameVariant -> new CatalogNamingConventionConflict(
							nameVariant.catalogName(),
							nameVariant.convention(),
							nameVariant.name()
						))
						.forEach(conflict -> {
							throw new CatalogAlreadyPresentException(
								catalogName, conflict.conflictingCatalogName(),
								conflict.convention(), conflict.conflictingName()
							);
						});

					return new Catalog(
						catalogSchema,
						this.cacheSupervisor,
						this.configuration,
						this.reflectionLookup,
						this.serviceExecutor,
						this.management.exportFileService(),
						this.requestExecutor,
						this.transactionExecutor,
						this::replaceCatalogReference,
						this.tracingContext
					);
				}
			);
			onCompletion.run();
			progress.complete(
				new CommitVersions(theCatalog.getVersion(), theCatalog.getSchema().version())
			);
		} catch (Throwable ex) {
			onFailure.run();
			progress.completeExceptionally(ex);
			log.error("Error while creating catalog `{}`!", catalogName, ex);
		} finally {
			this.currentCatalogMutations.remove(catalogName);
		}
		return progress;
	}

	/**
	 * Renames existing catalog in evitaDB.
	 */
	@Nonnull
	private Progress<CommitVersions> renameCatalogInternal(
		@Nonnull ModifyCatalogSchemaNameMutation modifyCatalogSchemaName,
		@Nonnull IntConsumer progressObserver,
		@Nonnull Runnable onCompletion,
		@Nonnull Runnable onFailure
	) {
		final String currentName = modifyCatalogSchemaName.getCatalogName();
		final String newName = modifyCatalogSchemaName.getNewCatalogName();
		isTrue(!this.catalogs.containsKey(newName), () -> new CatalogAlreadyPresentException(newName, newName));
		final CatalogContract catalogToBeRenamed = getCatalogInstanceOrThrowException(currentName);
		return doReplaceCatalogInternal(
			modifyCatalogSchemaName,
			newName, currentName,
			catalogToBeRenamed, catalogToBeRenamed,
			progressObserver, onCompletion, onFailure
		);
	}

	/**
	 * Replaces existing catalog in evitaDB.
	 */
	@Nonnull
	private Progress<CommitVersions> replaceCatalogInternal(
		@Nonnull ModifyCatalogSchemaNameMutation modifyCatalogSchemaName,
		@Nonnull IntConsumer progressObserver,
		@Nonnull Runnable onCompletion,
		@Nonnull Runnable onFailure
	) {
		final String catalogNameToBeReplacedWith = modifyCatalogSchemaName.getCatalogName();
		final String catalogNameToBeReplaced = modifyCatalogSchemaName.getNewCatalogName();
		final CatalogContract catalogToBeReplaced = getCatalogInstanceOrThrowException(catalogNameToBeReplaced);
		final CatalogContract catalogToBeReplacedWith = getCatalogInstanceOrThrowException(catalogNameToBeReplacedWith);
		return doReplaceCatalogInternal(
			modifyCatalogSchemaName,
			catalogNameToBeReplaced, catalogNameToBeReplacedWith,
			catalogToBeReplaced, catalogToBeReplacedWith,
			progressObserver, onCompletion, onFailure
		);
	}

	/**
	 * Internal shared implementation of catalog replacement used both from rename and replace existing catalog methods.
	 */
	@Nonnull
	private Progress<CommitVersions> doReplaceCatalogInternal(
		@Nonnull ModifyCatalogSchemaNameMutation modifyCatalogSchemaName,
		@Nonnull String catalogNameToBeReplaced,
		@Nonnull String catalogNameToBeReplacedWith,
		@Nonnull CatalogContract catalogToBeReplaced,
		@Nonnull CatalogContract catalogToBeReplacedWith,
		@Nonnull IntConsumer progressObserver,
		@Nonnull Runnable onCompletion,
		@Nonnull Runnable onFailure
	) {
		// close all active sessions to the catalog that will replace the original one
		final Optional<SessionRegistry> prevailingCatalogSessionRegistry = ofNullable(
			this.catalogSessionRegistries.get(catalogNameToBeReplacedWith)
		);
		// this will be always empty if catalogToBeReplaced == catalogToBeReplacedWith
		Optional<SessionRegistry> removedCatalogSessionRegistry = ofNullable(
			this.catalogSessionRegistries.get(catalogNameToBeReplaced)
		);

		prevailingCatalogSessionRegistry
			.ifPresent(sessionRegistry -> {
				sessionRegistry.closeAllActiveSessionsAndSuspend(SuspendOperation.POSTPONE);
				// immediately register the session registry under the new name to start accepting new sessions
				// session creation will be postponed until the catalog is fully available
				this.catalogSessionRegistries.put(
					catalogNameToBeReplaced,
					sessionRegistry.withDifferentCatalogSupplier(
						() -> (Catalog) this.catalogs.get(catalogNameToBeReplaced))
				);
			});

		final Runnable undoOperations = () -> {
			onFailure.run();

			// revert session registry swap
			if (removedCatalogSessionRegistry.isPresent()) {
				this.catalogSessionRegistries.put(catalogNameToBeReplaced, removedCatalogSessionRegistry.get());
			} else {
				this.catalogSessionRegistries.remove(catalogNameToBeReplaced);
			}
			// in case of exception return the original catalog to be replaced back
			if (catalogToBeReplaced.isTerminated()) {
				final ProgressingFuture<Catalog> future = loadCatalogInternal(catalogNameToBeReplaced);
				future.execute(this.transactionExecutor);
				future.join();
			} else {
				this.catalogs.put(catalogNameToBeReplaced, catalogToBeReplaced);
			}
		};

		try {
			assertNoCatalogEngineMutationInProgress(catalogNameToBeReplaced);
			assertNoCatalogEngineMutationInProgress(catalogNameToBeReplacedWith);

			// first terminate the catalog that is being replaced (unless it's the very same catalog)
			if (catalogToBeReplaced != catalogToBeReplacedWith) {
				removedCatalogSessionRegistry.ifPresent(
					it -> it.closeAllActiveSessionsAndSuspend(SuspendOperation.REJECT));
				catalogToBeReplaced.terminate();
			} else {
				Assert.isPremiseValid(removedCatalogSessionRegistry.isEmpty(), "Expectation failed!");
			}

			final CatalogSchemaWithImpactOnEntitySchemas updatedSchemaWrapper = modifyCatalogSchemaName.mutate(
				catalogToBeReplacedWith.getSchema());
			Assert.isPremiseValid(
				updatedSchemaWrapper != null,
				"Result of modify catalog schema mutation must not be null."
			);

			return new ProgressRecord<>(
				catalogToBeReplaced == catalogToBeReplacedWith ?
					"Renaming catalog `" + catalogNameToBeReplaced + "` to `" + catalogNameToBeReplacedWith + "`" :
					"Replacing catalog `" + catalogNameToBeReplaced + "` with `" + catalogNameToBeReplacedWith + "`",
				progressObserver,
				new ProgressingFuture<>(
					1,
					Collections.singleton(
						catalogToBeReplacedWith
							.replace(
								updatedSchemaWrapper.updatedCatalogSchema(),
								catalogToBeReplaced
							)
					),
					(theFuture, replacedCatalogs) -> {
						final CatalogContract replacedCatalog = replacedCatalogs.iterator().next();
						this.catalogs.put(catalogNameToBeReplaced, replacedCatalog);
						if (!catalogNameToBeReplacedWith.equals(catalogNameToBeReplaced)) {
							this.catalogs.remove(catalogNameToBeReplacedWith);
							this.catalogSessionRegistries.remove(catalogNameToBeReplacedWith);
						}

						onCompletion.run();

						// notify callback that it's now a live snapshot
						((Catalog) replacedCatalog).notifyCatalogPresentInLiveView();

						// we can resume suspended operations on catalogs
						prevailingCatalogSessionRegistry.ifPresent(
							SessionRegistry::resumeOperations);
						removedCatalogSessionRegistry.ifPresent(SessionRegistry::resumeOperations);

						this.currentCatalogMutations.remove(catalogNameToBeReplaced);
						this.currentCatalogMutations.remove(catalogNameToBeReplacedWith);

						return new CommitVersions(
							replacedCatalog.getVersion(),
							replacedCatalog.getSchema().version()
						);
					},
					ex -> {
						log.error(
							"Error while replacing catalog `{}` with `{}`!",
							catalogNameToBeReplaced, catalogNameToBeReplacedWith, ex
						);

						undoOperations.run();

						this.currentCatalogMutations.remove(catalogNameToBeReplaced);
						this.currentCatalogMutations.remove(catalogNameToBeReplacedWith);
					}
				),
				progress -> {
					this.currentCatalogMutations.put(catalogNameToBeReplaced, progress);
					this.currentCatalogMutations.put(catalogNameToBeReplacedWith, progress);
				},
				this.transactionExecutor
			);
		} catch (RuntimeException ex) {
			undoOperations.run();
			throw ex;
		}
	}

	/**
	 * Modifies the catalog schema by applying the provided schema mutation. This method ensures that
	 * the modification takes place within an existing session and updates the catalog schema accordingly.
	 * It also observes progress and reports completion or errors.
	 *
	 * @param modifyCatalogSchema the mutation containing details about the changes to be applied
	 *                            to the catalog schema, including the session ID and schema modifications.
	 * @param progressObserver    a consumer that observes progress and receives updates as the operation progresses.
	 * @return a Progress instance containing the result of schema modification, specifically
	 * the committed versions of the catalog and schema.
	 * @throws InvalidSchemaMutationException if the operation is attempted outside of an existing session.
	 */
	@Nonnull
	private Progress<CommitVersions> modifyCatalogSchemaInternal(
		@Nonnull ModifyCatalogSchemaMutation modifyCatalogSchema,
		@Nonnull IntConsumer progressObserver,
		@Nonnull Runnable onCompletion,
		@Nonnull Runnable onFailure
	) {
		Assert.isTrue(
			modifyCatalogSchema.getSessionId() != null,
			() -> new InvalidSchemaMutationException(
				"Cannot modify catalog schema outside an existing session! " +
					"Please use methods available on `EvitaSessionContract` interface."
			)
		);

		final String catalogName = modifyCatalogSchema.getCatalogName();
		final CatalogContract catalogContract = this.catalogs.get(catalogName);
		//noinspection resource
		final Transaction theTransaction = Transaction.getTransaction().orElse(null);

		assertNoCatalogEngineMutationInProgress(catalogName);
		return new ProgressRecord<>(
			"Modifying catalog schema `" + catalogName + "`",
			progressObserver,
			new ProgressingFuture<>(
				1,
				theFuture -> Transaction.executeInTransactionIfProvided(
					theTransaction,
					() -> {
						final CatalogSchemaContract newSchema = catalogContract.updateSchema(
							modifyCatalogSchema.getSessionId(), modifyCatalogSchema.getSchemaMutations());
						onCompletion.run();
						this.currentCatalogMutations.remove(catalogName);
						return new CommitVersions(
							catalogContract.getVersion(),
							newSchema.version()
						);
					}
				),
				ex -> {
					onFailure.run();
					this.currentCatalogMutations.remove(catalogName);
				}
			),
			progress -> this.currentCatalogMutations.put(catalogName, progress),
			this.transactionExecutor
		);
	}

	/**
	 * Removes a catalog and all its associated data based on the provided mutation.
	 * This operation also closes any active sessions associated with the catalog and cleans up its resources.
	 *
	 * @param removeCatalogSchema the mutation containing the details of the catalog to be removed
	 * @param progressObserver    an observer to track the progress of the removal operation
	 * @return a progress object indicating the completion state of the removal operation
	 */
	@Nonnull
	private Progress<Void> removeCatalogInternal(
		@Nonnull RemoveCatalogSchemaMutation removeCatalogSchema,
		@Nonnull IntConsumer progressObserver,
		@Nonnull Runnable onCompletion,
		@Nonnull Runnable onFailure
	) {
		final String catalogName = removeCatalogSchema.getCatalogName();
		assertNoCatalogEngineMutationInProgress(catalogName);
		return new ProgressRecord<>(
			"Removing catalog `" + catalogName + "`",
			progressObserver,
			new ProgressingFuture<>(
				1,
				theFuture -> {
					ofNullable(this.catalogSessionRegistries.get(catalogName))
						.ifPresent(it -> it.closeAllActiveSessionsAndSuspend(SuspendOperation.REJECT));

					theFuture.updateProgress(1);

					final CatalogContract catalogToRemove = this.catalogs.remove(catalogName);
					this.catalogSessionRegistries.remove(catalogName);
					if (catalogToRemove == null) {
						throw new CatalogNotFoundException(catalogName);
					} else {
						catalogToRemove.terminateAndDelete();
					}

					onCompletion.run();
					this.currentCatalogMutations.remove(catalogName);
					return null;
				},
				ex -> {
					onFailure.run();
					this.currentCatalogMutations.remove(catalogName);
				}
			),
			progress -> this.currentCatalogMutations.put(catalogName, progress),
			this.transactionExecutor
		);
	}

	/**
	 * Replaces current catalog reference with updated one.
	 */
	private void replaceCatalogReference(@Nonnull Catalog catalog) {
		notNull(catalog, "Sanity check.");
		final String catalogName = catalog.getName();
		// catalog indexes are ConcurrentHashMap - we can do it safely here
		this.catalogs.computeIfPresent(
			catalogName, (cName, currentCatalog) -> {
				// replace catalog only when reference/pointer differs
				if (currentCatalog != catalog && currentCatalog.getVersion() < catalog.getVersion()) {
					return catalog;
				} else {
					return currentCatalog;
				}
			}
		);

		// discard suspension of the session registry for the catalog, if present
		discardSuspension(catalogName);

		// notify callback that it's now a live snapshot
		catalog.notifyCatalogPresentInLiveView();
	}

	/**
	 * Closes all active sessions regardless of target catalog.
	 */
	private void closeAllSessions() {
		final Iterator<SessionRegistry> sessionRegistryIt = this.catalogSessionRegistries.values().iterator();
		while (sessionRegistryIt.hasNext()) {
			final SessionRegistry sessionRegistry = sessionRegistryIt.next();
			sessionRegistry.closeAllActiveSessionsAndSuspend(SuspendOperation.REJECT);
			sessionRegistryIt.remove();
		}
	}

	/**
	 * Creates {@link EvitaSession} instance and registers all appropriate termination callbacks along.
	 */
	@Nonnull
	private CreatedSession createSessionInternal(@Nonnull SessionTraits sessionTraits) {
		final SessionRegistry catalogSessionRegistry = this.catalogSessionRegistries.computeIfAbsent(
			sessionTraits.catalogName(),
			__ -> {
				// we need first to verify whether the catalog exists and is not corrupted
				final CatalogContract catalogContract = getCatalogInstanceOrThrowException(sessionTraits.catalogName());
				if (catalogContract instanceof UnusableCatalog unusableCatalog) {
					throw unusableCatalog.getRepresentativeException();
				}
				return createSessionNewRegistry(sessionTraits);
			}
		);

		final EvitaInternalSessionContract newSession = catalogSessionRegistry.createSession(
			sessionRegistry -> {
				if (this.readOnly) {
					isTrue(!sessionTraits.isReadWrite() || sessionTraits.isDryRun(), ReadOnlyException::engineReadOnly);
				}

				final Catalog catalog = sessionRegistry.getCatalog();
				if (this.readOnlyCatalogs.contains(catalog.getName())) {
					throw ReadOnlyException.catalogReadOnly(catalog.getName());
				}
				if (catalog.isGoingLive()) {
					throw new CatalogGoingLiveException(catalog.getName());
				}

				final EvitaSessionTerminationCallback terminationCallback =
					session -> sessionRegistry.removeSession((EvitaSession) session);

				return sessionRegistry.addSession(
					catalog.supportsTransaction(),
					() -> new EvitaSession(
						this, catalog, this.reflectionLookup,
						terminationCallback,
						ofNullable(sessionTraits.commitBehaviour()).orElse(CommitBehavior.defaultBehaviour()),
						sessionTraits,
						sessionRegistry::createCatalogConsumerControl
					)
				);
			}
		);

		return new CreatedSession(
			newSession,
			newSession.getCommitProgress()
		);
	}

	/**
	 * Creates and initializes a new instance of SessionRegistry using the provided session traits.
	 *
	 * @param sessionTraits the traits of the session, including catalog name and other properties,
	 *                      required to create the session registry instance
	 * @return a newly created SessionRegistry object associated with the given session traits
	 */
	@Nonnull
	private SessionRegistry createSessionNewRegistry(@Nonnull SessionTraits sessionTraits) {
		final String catalogName = sessionTraits.catalogName();
		return new SessionRegistry(
			this.tracingContext,
			() -> (Catalog) this.catalogs.get(catalogName),
			this.sessionRegistryDataStore
		);
	}

	/**
	 * Emits the event about evita engine statistics in metrics.
	 */
	private void emitEvitaStatistics() {
		// emit the event
		new EvitaStatisticsEvent(
			this.configuration,
			this.management().getSystemStatus()
		).commit();
	}

	/**
	 * Emits the event about catalog statistics in metrics.
	 *
	 * @param catalogName name of the catalog
	 */
	private void emitCatalogStatistics(@Nonnull String catalogName) {
		// register regular metrics extraction of the catalog
		FlightRecorder.addPeriodicEvent(
			CatalogStatisticsEvent.class,
			new Runnable() {
				@Override
				public void run() {
					if (Evita.this.catalogs.get(catalogName) instanceof Catalog monitoredCatalog) {
						monitoredCatalog.emitObservabilityEvents();
					} else {
						FlightRecorder.removePeriodicEvent(this);
					}
				}
			}
		);
	}

	/**
	 * Attempts to close all resources of evitaDB.
	 */
	private void closeInternal() {
		RuntimeException exception = null;
		try {
			// first close all sessions
			CompletableFuture.allOf(
				CompletableFuture.runAsync(this::closeAllSessions),
				CompletableFuture.runAsync(this.changeObserver::close)
			).join();
		} catch (RuntimeException ex) {
			exception = ex;
		}

		try {
			// then close all thread pools and management services
			CompletableFuture.allOf(
				CompletableFuture.runAsync(this.management::close),
				CompletableFuture.runAsync(() -> shutdownScheduler("request", this.requestExecutor, 60)),
				CompletableFuture.runAsync(() -> shutdownScheduler("transaction", this.transactionExecutor, 60)),
				CompletableFuture.runAsync(() -> shutdownScheduler("service", this.serviceExecutor, 60))
			).join();
		} catch (RuntimeException ex) {
			if (exception == null) {
				exception = ex;
			} else {
				exception.addSuppressed(ex);
			}
		}

		try {
			// terminate all catalogs finally (if we did this prematurely, many exceptions would occur)
			closeCatalogs().join();
		} catch (RuntimeException ex) {
			if (exception == null) {
				exception = ex;
			} else {
				exception.addSuppressed(ex);
			}
		}

		if (exception != null) {
			log.error("Failed to close evitaDB. Some resources might not have been released properly.", exception);
		}
	}

	/**
	 * Third stage of shut down: terminates all catalogs.
	 *
	 * @return future that completes when all catalogs are terminated
	 */
	@Nonnull
	private CompletableFuture<Void> closeCatalogs() {
		return CompletableFuture.allOf(
			this.catalogs.values()
			             .stream()
			             .map(catalog -> CompletableFuture.runAsync(catalog::terminate))
			             .toArray(CompletableFuture[]::new)
		).thenAccept(__ -> {
			// clear map
			this.catalogs.clear();
			// release lock
			IOUtils.closeQuietly(this.enginePersistenceService::close);
		});
	}

	/**
	 * DTO for passing the identified conflict in catalog names for certain naming convention.
	 */
	record CatalogNamingConventionConflict(
		@Nonnull String conflictingCatalogName,
		@Nonnull NamingConvention convention,
		@Nonnull String conflictingName
	) {
	}

	/**
	 * Represents a catalog name that follows a specific naming convention.
	 *
	 * @param catalogName the original name of the catalog
	 * @param convention  the identification of the convention
	 * @param name        the name of the catalog in particular convention
	 */
	private record CatalogNameInConvention(
		@Nonnull String catalogName,
		@Nonnull NamingConvention convention,
		@Nonnull String name
	) {
	}

	/**
	 * Represents a created session.
	 * This class is a record that encapsulates a session and a future for closing the session.
	 *
	 * @param session        reference to the created session itself
	 * @param commitProgress record containing futures related to a commit progression on session close
	 */
	private record CreatedSession(
		@Nonnull EvitaInternalSessionContract session,
		@Nonnull CommitProgressRecord commitProgress
	) implements Closeable {

		@Override
		public void close() {
			this.session.close();
		}

	}

}
