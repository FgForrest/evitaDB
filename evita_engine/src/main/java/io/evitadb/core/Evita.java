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
import io.evitadb.api.exception.CatalogGoingLiveException;
import io.evitadb.api.exception.CatalogNotFoundException;
import io.evitadb.api.exception.InstanceTerminatedException;
import io.evitadb.api.exception.ReadOnlyException;
import io.evitadb.api.observability.trace.TracingContext;
import io.evitadb.api.observability.trace.TracingContextProvider;
import io.evitadb.api.requestResponse.cdc.ChangeCaptureContent;
import io.evitadb.api.requestResponse.cdc.ChangeCapturePublisher;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCapture;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCaptureRequest;
import io.evitadb.api.requestResponse.mutation.EngineMutation;
import io.evitadb.api.requestResponse.progress.Progress;
import io.evitadb.api.requestResponse.progress.ProgressingFuture;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaEditor.CatalogSchemaBuilder;
import io.evitadb.api.requestResponse.schema.builder.InternalCatalogSchemaBuilder;
import io.evitadb.api.requestResponse.schema.mutation.engine.CreateCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.DuplicateCatalogMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.MakeCatalogAliveMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.ModifyCatalogSchemaNameMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.RemoveCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.RestoreCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.SetCatalogMutabilityMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.SetCatalogStateMutation;
import io.evitadb.api.requestResponse.transaction.TransactionMutation;
import io.evitadb.core.cache.CacheSupervisor;
import io.evitadb.core.cache.HeapMemoryCacheSupervisor;
import io.evitadb.core.cache.NoCacheSupervisor;
import io.evitadb.core.cdc.EngineStatisticsPublisher;
import io.evitadb.core.cdc.SystemChangeObserver;
import io.evitadb.core.exception.CatalogCorruptedException;
import io.evitadb.core.exception.CatalogInactiveException;
import io.evitadb.core.exception.CatalogTransitioningException;
import io.evitadb.core.exception.StorageImplementationNotFoundException;
import io.evitadb.core.executor.ImmediateScheduledThreadPoolExecutor;
import io.evitadb.core.executor.ObservableExecutorServiceWithHardDeadline;
import io.evitadb.core.executor.ObservableThreadExecutor;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.core.metric.event.storage.CatalogStatisticsEvent;
import io.evitadb.core.metric.event.system.EvitaStatisticsEvent;
import io.evitadb.core.metric.event.system.RequestForkJoinPoolStatisticsEvent;
import io.evitadb.core.metric.event.system.ScheduledExecutorStatisticsEvent;
import io.evitadb.core.metric.event.system.TransactionForkJoinPoolStatisticsEvent;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.task.SessionKiller;
import io.evitadb.core.transaction.engine.EngineTransactionManager;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.store.spi.EnginePersistenceService;
import io.evitadb.store.spi.EnginePersistenceServiceFactory;
import io.evitadb.store.spi.model.EngineState;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.ExceptionUtils;
import io.evitadb.utils.ReflectionLookup;
import io.evitadb.utils.StringUtils;
import jdk.jfr.FlightRecorder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Closeable;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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
	 * Transaction manager that is responsible for managing engine transactions in the evitaDB engine.
	 */
	private final EngineTransactionManager engineTransactionManager;
	/**
	 * Contains the main evitaDB management service.
	 */
	private final EvitaManagement management;
	/**
	 * Provides the tracing context for tracking the execution flow in the application.
	 **/
	private final TracingContext tracingContext;
	/**
	 * Reference keeps the current state of the evitaDB engine instance.
	 */
	private final AtomicReference<ExpandedEngineState> engineState = new AtomicReference<>();
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
	 * Flag that is se to TRUE when Evita. is ready to serve application calls.
	 * Aim of this flag is to refuse any calls after {@link #close()} method has been called.
	 */
	@Getter private boolean active;
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
		final EnginePersistenceService enginePersistenceService = svcLoader
			.findFirst()
			.map(it -> it.create(configuration.storage(), configuration.transaction(), this.serviceExecutor))
			.orElseThrow(StorageImplementationNotFoundException::new);

		this.management = new EvitaManagement(this);

		final EngineState engineState = enginePersistenceService.getEngineState();
		final HashMap<String, CatalogContract> catalogs = CollectionUtils.createHashMap(
			engineState.activeCatalogs().length + engineState.inactiveCatalogs().length
		);

		// register stubs for all inactive catalogs
		Arrays.stream(engineState.inactiveCatalogs())
		      .map(
			      it -> new UnusableCatalog(
				      it, CatalogState.INACTIVE,
				      this.configuration.storage().storageDirectory().resolve(it),
				      CatalogInactiveException::new
			      )
		      )
		      .forEach(it -> catalogs.put(it.getName(), it));

		// spawn parallel tasks to load all active catalogs, but don't wait for them to finish
		//noinspection unchecked
		this.initialLoadCatalogFutures = new AtomicReference<>(
			Arrays.stream(engineState.activeCatalogs())
			      .map(catalogName -> {
				      catalogs.put(
					      catalogName,
					      new UnusableCatalog(
						      catalogName,
						      CatalogState.BEING_ACTIVATED,
						      this.configuration.storage().storageDirectory().resolve(catalogName),
						      (cn, path) -> new CatalogTransitioningException(cn, path, CatalogState.BEING_ACTIVATED)
					      )
				      );
					  return this.loadCatalogInternal(
						  catalogName,
						  ArrayUtils.computeInsertPositionOfObjInOrderedArray(catalogName, engineState.readOnlyCatalogs())
						            .alreadyPresent()
					  );
			      })
			      .toArray(ProgressingFuture[]::new)
		);
		// now init state with catalog stubs
		this.engineState.set(
			ExpandedEngineState.create(
				engineState,
				catalogs
			)
		);

		this.changeObserver = new SystemChangeObserver(
			this,
			this.configuration.server().changeDataCapture(),
			this.requestExecutor,
			this.serviceExecutor
		);

		this.engineTransactionManager = new EngineTransactionManager(
			this, this.changeObserver, this.transactionExecutor, enginePersistenceService
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
				loadCatalogFuture.execute(this.engineTransactionManager.getExecutor());
			}
		}
	}

	/**
	 * Retrieves an array of ProgressingFuture objects representing the initial catalog load futures.
	 * If no initial catalog load futures exist, returns an empty array.
	 *
	 * @return an array of ProgressingFuture objects for the initial catalog load,
	 * or an empty array if none are present.
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
		return this.getEngineState().getCatalogCollection();
	}

	@Override
	@Nonnull
	@SuppressWarnings("resource")
	public EvitaSessionContract createSession(@Nonnull SessionTraits traits) {
		notNull(traits.catalogName(), "Catalog name is mandatory information.");
		return createSessionInternal(traits).session();
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
		return this.getEngineState().catalogs().keySet();
	}

	@Nonnull
	@Override
	public Optional<CatalogState> getCatalogState(@Nonnull String catalogName) {
		return this.getEngineState().getCatalog(catalogName)
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
	public Progress<Void> duplicateCatalogWithProgress(@Nonnull String catalogName, @Nonnull String newCatalogName) {
		assertActive();
		return applyMutation(new DuplicateCatalogMutation(catalogName, newCatalogName));
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
	public Progress<CommitVersions> renameCatalogWithProgress(
		@Nonnull String catalogName, @Nonnull String newCatalogName) {
		assertActive();
		return applyMutation(new ModifyCatalogSchemaNameMutation(catalogName, newCatalogName, false));
	}

	@Nonnull
	@Override
	public Progress<CommitVersions> replaceCatalogWithProgress(
		@Nonnull String catalogNameToBeReplacedWith, @Nonnull String catalogNameToBeReplaced) {
		assertActive();
		return applyMutation(
			new ModifyCatalogSchemaNameMutation(catalogNameToBeReplacedWith, catalogNameToBeReplaced, true));
	}

	@Nonnull
	@Override
	public Optional<Progress<Void>> deleteCatalogIfExistsWithProgress(@Nonnull String catalogName) {
		assertActive();
		return this.getEngineState().getCatalog(catalogName)
			.map(__ -> applyMutation(new RemoveCatalogSchemaMutation(catalogName)));
	}

	@Nonnull
	@Override
	public <T> Progress<T> applyMutation(
		@Nonnull EngineMutation<T> engineMutation,
		@Nullable IntConsumer progressObserver
	) {
		assertActiveAndWritable();
		return this.engineTransactionManager.applyMutation(
			engineMutation,
			progressObserver
		);
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
		if (this.getEngineState().isReadOnly(catalogName)) {
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
		if (this.getEngineState().isReadOnly(catalogName)) {
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
	 * Checks if sessions were forcefully closed for the specified catalog and session ID.
	 *
	 * @param catalogName the name of the catalog for which to check if sessions were forcefully closed; must not be null
	 * @param sessionId   the unique identifier of the session to check; must not be null
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

	/**
	 * Blocks the current thread until the associated initialization process is fully completed.
	 * This method waits for the internal process associated with the `fullyInitialized` object
	 * to complete its execution. It ensures that the calling thread does not proceed
	 * until the initialization process is finalized.
	 */
	public void waitUntilFullyInitialized() {
		this.fullyInitialized.join();
	}

	/**
	 * Adds a catalog to the list of inactive catalogs and updates the engine state accordingly.
	 *
	 * @param catalogName The name of the catalog that was restored and shoul be registered as inactive.
	 */
	public void registerRestoredCatalog(@Nonnull String catalogName) {
		assertActive();
		applyMutation(new RestoreCatalogSchemaMutation(catalogName))
			.onCompletion()
			.toCompletableFuture()
			.join();
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
		return this.engineTransactionManager.getCommittedMutationStream(version);
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
		return this.engineTransactionManager.getReversedCommittedMutationStream(version);
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
		return this.getEngineState().getCatalog(catalog);
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
	public ExpandedEngineState getEngineState() {
		return this.engineState.get();
	}

	/**
	 * Updates the engine state to the provided next state if the transition is valid.
	 * Validates that the version of the next engine state is one higher than the current engine state's version.
	 *
	 * @param engineState the next engine state to set; must have a version that is one higher than the current engine state.
	 * @throws GenericEvitaInternalError if the version of the provided engine state is not one higher than the current state's version.
	 */
	public void setNextEngineState(@Nonnull ExpandedEngineState engineState) {
		this.engineState.getAndAccumulate(
			engineState,
			(current, next) -> {
				Assert.isPremiseValid(
					current.version() == next.version() || current.version() + 1 == next.version(),
					() -> new GenericEvitaInternalError(
						"Next engine state must have version that is one higher than current engine state version! " +
							"Current version: " + current.version() + ", next version: " + next.version(),
						"Next engine state must have version that is one higher than current engine state version!"
					)
				);
				return next;
			}
		);
	}

	/**
	 * Retrieves the mutation progress of the engine for a specified catalog.
	 *
	 * @param catalogName the name of the catalog whose mutation progress is to be retrieved; must not be null
	 * @return an Optional containing the mutation progress if present, or an empty Optional if no progress data exists for the specified catalog
	 */
	@Nonnull
	public Optional<Progress<?>> getEngineMutationProgress(@Nonnull String catalogName) {
		return this.engineTransactionManager.getEngineMutationProgress(catalogName);
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

	/**
	 * Creates a new CatalogContract instance using the provided catalog schema and other dependencies.
	 *
	 * @param catalogSchema the schema definition for the catalog; must not be null
	 * @return a new instance of CatalogContract
	 */
	@Nonnull
	public CatalogContract createCatalog(@Nonnull CatalogSchemaContract catalogSchema) {
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

	/**
	 * Loads catalog from the designated directory. If the catalog is corrupted, it will be marked as such, but it'll
	 * still be added to the list of catalogs.
	 *
	 * @param catalogName name of the catalog
	 */
	@Nonnull
	public ProgressingFuture<Catalog> loadCatalogInternal(@Nonnull String catalogName, boolean readOnly) {
		final long start = System.nanoTime();
		return Catalog.loadCatalog(
			catalogName,
			readOnly,
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
						this.engineState.updateAndGet(
							existingState -> existingState.withUpdatedCatalogInstance(updatedCatalog)
						);
						if (updatedCatalog instanceof Catalog theUpdatedCatalog) {
							theUpdatedCatalog.notifyCatalogPresentInLiveView();
						}
					}
				);
				this.emitCatalogStatistics(catalogName);
			},
			(cn, exception) -> {
				log.error("Catalog {} is corrupted!", cn, exception);
				this.engineState.updateAndGet(
					existingState -> existingState.withUpdatedCatalogInstance(
						new UnusableCatalog(
							cn,
							CatalogState.CORRUPTED,
							this.configuration.storage().storageDirectory().resolve(cn),
							(tcn, path) -> new CatalogCorruptedException(tcn, path, exception)
						)
					)
				);
				this.emitEvitaStatistics();
			},
			this.tracingContext
		);
	}

	/**
	 * Retrieves the session registry associated with the specified catalog name.
	 *
	 * @param catalogName the name of the catalog for which the session registry is to be retrieved, must not be null
	 * @return an Optional containing the SessionRegistry associated with the specified catalog name, or an empty Optional if no registry exists for the given catalog name
	 */
	@Nonnull
	public Optional<SessionRegistry> getCatalogSessionRegistry(@Nonnull String catalogName) {
		return ofNullable(this.catalogSessionRegistries.get(catalogName));
	}

	/**
	 * Closes all active sessions associated with the specified catalog name
	 * and suspends them using the provided suspend operation.
	 *
	 * @param catalogName the name of the catalog whose active sessions are to be closed and suspended
	 * @param suspendOperation the operation to be performed to suspend the sessions
	 */
	public void closeAllActiveSessionsAndSuspend(
		@Nonnull String catalogName,
		@Nonnull SuspendOperation suspendOperation
	) {
		ofNullable(this.catalogSessionRegistries.get(catalogName))
			.ifPresent(it -> it.closeAllActiveSessionsAndSuspend(suspendOperation));
	}

	/**
	 * Registers a session registry for a specific catalog. This ensures that a session
	 * registry is associated with the provided catalog name. If a session registry
	 * for the given catalog name already exists, an error is thrown to prevent overwriting.
	 *
	 * @param catalogName the name of the catalog to associate with the session registry
	 * @param sessionRegistry the session registry to register with the catalog
	 * @throws GenericEvitaInternalError if a session registry for the specified catalog name already exists
	 */
	public void registerCatalogSessionRegistry(@Nonnull String catalogName, @Nonnull SessionRegistry sessionRegistry) {
		this.catalogSessionRegistries.compute(
			catalogName,
			(__, existingRegistry) -> {
				if (existingRegistry != null) {
					throw new GenericEvitaInternalError(
						"Catalog session registry for catalog `" + catalogName + "` already exists! " +
							"Cannot overwrite it with another one!"
					);
				} else {
					// otherwise we register the new one
					return sessionRegistry;
				}
			}
		);
	}

	/**
	 * Registers a session registry for a specific catalog replacing any potentially existing registry under particular
	 * catalog name. This ensures that a session registry is associated with the provided catalog name.
	 *
	 * @param catalogName     the name of the catalog to associate with the session registry
	 * @param sessionRegistry the session registry to register with the catalog
	 * @return previously registered session registry for the catalog, or null if there was no previous registry
	 */
	@Nullable
	public SessionRegistry registerWithReplaceCatalogSessionRegistry(@Nonnull String catalogName, @Nonnull SessionRegistry sessionRegistry) {
		return this.catalogSessionRegistries.put(catalogName, sessionRegistry);
	}

	/**
	 * Removes the catalog session registry associated with the specified catalog name, if it exists.
	 *
	 * @param catalogName the name of the catalog whose session registry should be removed, must not be null
	 */
	public void removeCatalogSessionRegistryIfPresent(@Nonnull String catalogName) {
		this.catalogSessionRegistries.remove(catalogName);
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
	 * Replaces current catalog reference with updated one.
	 */
	private void replaceCatalogReference(@Nonnull Catalog catalog) {
		notNull(catalog, "Sanity check.");

		// replace catalog reference in the engine state
		getEngineState().replaceCatalogReference(catalog);

		// discard suspension of the session registry for the catalog, if present
		discardSuspension(catalog.getName());

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
				final String catalogName = catalog.getName();
				if (this.getEngineState().isReadOnly(catalogName)) {
					isTrue(!sessionTraits.isReadWrite() || sessionTraits.isDryRun(), () -> ReadOnlyException.catalogReadOnly(catalogName));
				}
				if (catalog.isGoingLive()) {
					throw new CatalogGoingLiveException(catalogName);
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
			() -> this.getEngineState()
			          .getCatalog(catalogName)
			          .map(it -> {
				          if (it instanceof Catalog catalog) {
					          return catalog;
				          } else if (it instanceof UnusableCatalog unusableCatalog) {
					          throw unusableCatalog.getRepresentativeException();
				          } else {
					          throw new GenericEvitaInternalError("Could not happen!");
				          }
			          })
			          .orElseThrow(() -> new CatalogNotFoundException(catalogName)),
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
					Evita.this.getEngineState()
					          .getCatalog(catalogName)
					          .ifPresentOrElse(
						          catalogContract -> {
							          if (catalogContract instanceof Catalog monitoredCatalog) {
								          monitoredCatalog.emitObservabilityEvents();
							          } else {
								          FlightRecorder.removePeriodicEvent(this);
							          }
						          },
						          () -> log.warn("Catalog {} does not exist, cannot emit statistics!", catalogName)
					          );
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
			closeCatalogs().join();
		} catch (RuntimeException ex) {
			if (exception == null) {
				exception = ex;
			} else {
				exception.addSuppressed(ex);
			}
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
		final ExpandedEngineState expandedEngineState = this.engineState.get();
		final Executor executor = this.engineTransactionManager.getExecutor();
		final ProgressingFuture<Void> closedFuture = new ProgressingFuture<>(
			0,
			// first we need to close all catalogs
			expandedEngineState
				.getCatalogCollection()
				.stream()
				.map(
					catalog -> new ProgressingFuture<Void>(
						0,
						future -> {
							catalog.terminate();
							return null;
						}
					)
				)
				.toList(),
			(future, __) -> {
				// then clear engine state and close transaction manager
				this.engineState.set(null);
				this.engineTransactionManager.close();
				return null;
			}
		);
		closedFuture.execute(executor);
		return closedFuture;
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
