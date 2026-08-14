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
import io.evitadb.api.exception.CatalogRequiresUpgradeException;
import io.evitadb.api.exception.InstanceTerminatedException;
import io.evitadb.api.exception.ReadOnlyException;
import io.evitadb.api.observability.trace.TracingContext;
import io.evitadb.api.observability.trace.TracingContextProvider;
import io.evitadb.api.proxy.ProxyFactory;
import io.evitadb.api.requestResponse.cdc.ChangeCaptureContent;
import io.evitadb.api.requestResponse.cdc.ChangeCapturePublisher;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCapture;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCaptureRequest;
import io.evitadb.api.requestResponse.cdc.HostSystemEvent;
import io.evitadb.api.requestResponse.data.DevelopmentConstants;
import io.evitadb.api.requestResponse.mutation.EngineMutation;
import io.evitadb.api.requestResponse.progress.Progress;
import io.evitadb.api.requestResponse.progress.ProgressingFuture;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaEditor.CatalogSchemaBuilder;
import io.evitadb.api.requestResponse.schema.builder.InternalCatalogSchemaBuilder;
import io.evitadb.api.requestResponse.schema.mutation.engine.CreateCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.DuplicateCatalogMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.MakeCatalogAliveMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.MarkCatalogMissingMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.ModifyCatalogSchemaNameMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.RemoveCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.RestoreCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.SetCatalogMutabilityMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.SetCatalogStateMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.UpgradeCatalogFormatMutation;
import io.evitadb.api.requestResponse.mutation.infrastructure.TransactionMutation;
import io.evitadb.core.cache.CacheSupervisor;
import io.evitadb.core.cache.CollationKeyCacheSweeper;
import io.evitadb.core.cache.HeapMemoryCacheSupervisor;
import io.evitadb.core.cache.NoCacheSupervisor;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.catalog.UnusableCatalog;
import io.evitadb.core.cdc.EngineStatisticsPublisher;
import io.evitadb.core.cdc.SystemChangeObserver;
import io.evitadb.core.engine.CatalogFolderContext;
import io.evitadb.core.engine.CatalogFolderResolver;
import io.evitadb.core.engine.ExpandedEngineState;
import io.evitadb.core.exception.CatalogCorruptedException;
import io.evitadb.core.exception.CatalogInactiveException;
import io.evitadb.core.exception.CatalogTransitioningException;
import io.evitadb.core.exception.StorageImplementationNotFoundException;
import io.evitadb.core.executor.ImmediateScheduledThreadPoolExecutor;
import io.evitadb.core.executor.ObservableExecutorServiceWithCancellationSupport;
import io.evitadb.core.executor.ObservableThreadExecutor;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.core.management.EvitaManagement;
import io.evitadb.core.metric.event.storage.CatalogStatisticsEvent;
import io.evitadb.core.metric.event.system.EvitaStatisticsEvent;
import io.evitadb.core.metric.event.system.RequestThreadPoolStatisticsEvent;
import io.evitadb.core.metric.event.system.ScheduledExecutorStatisticsEvent;
import io.evitadb.core.metric.event.system.TransactionThreadPoolStatisticsEvent;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.sequence.SequenceService;
import io.evitadb.core.sequence.SequenceType;
import io.evitadb.core.session.EvitaInternalSessionContract;
import io.evitadb.core.session.EvitaSession;
import io.evitadb.core.session.SessionRegistry;
import io.evitadb.core.session.SuspendOperation;
import io.evitadb.core.session.SuspensionInformation;
import io.evitadb.core.session.task.SessionKiller;
import io.evitadb.core.transaction.engine.EngineTransactionManager;
import io.evitadb.core.transaction.engine.operators.DefaultUpgradeExecutor;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.function.Functions;
import io.evitadb.spi.store.catalog.shared.model.LogRecordReference;
import io.evitadb.spi.store.engine.EnginePersistenceService;
import io.evitadb.spi.store.engine.EnginePersistenceServiceFactory;
import io.evitadb.spi.store.engine.model.AdoptableCatalogFolder;
import io.evitadb.spi.store.engine.model.CatalogFolderId;
import io.evitadb.spi.store.engine.model.CatalogGenerationPeak;
import io.evitadb.spi.store.engine.model.EngineState;
import io.evitadb.spi.store.engine.model.CatalogInventoryDivergence;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static io.evitadb.utils.Assert.isTrue;
import static io.evitadb.utils.Assert.notNull;
import static java.util.Optional.empty;
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
	 * Task that periodically releases collation keys that are no longer being compared, so that the memory a bulk
	 * import or a large transaction needed is not retained for the rest of the process lifetime. Null when retention is
	 * unbounded, i.e. {@link ServerOptions#dropCollationKeysAfterSecondsOfInactivity()} is zero.
	 */
	@Nullable private final CollationKeyCacheSweeper collationKeyCacheSweeper;
	/**
	 * Field contains the global - shared configuration for the entire Evita instance.
	 */
	@Getter private final EvitaConfiguration configuration;
	/**
	 * Resolves the on-disk directory holding a catalog's data. This is the single sanctioned way to answer
	 * "which folder is catalog `X`?" — see {@link CatalogFolderResolver} for why the catalog
	 * name must stop doubling as its on-disk identity.
	 */
	@Getter private final CatalogFolderContext catalogFolderContext;
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
	@Getter private final EngineTransactionManager engineTransactionManager;
	/**
	 * Contains the main evitaDB management service.
	 */
	private final EvitaManagement management;
	/**
	 * Contains reference to the proxy factory that is used to create proxies for the entities.
	 */
	@Getter private final ProxyFactory proxyFactory;
	/**
	 * Provides the tracing context for tracking the execution flow in the application.
	 **/
	private final TracingContext tracingContext;
	/**
	 * Reference keeps the current state of the evitaDB engine instance.
	 */
	private final AtomicReference<ExpandedEngineState> engineState = new AtomicReference<>();
	/**
	 * Generation counters numbering the storage folders each catalog occupies over its lifetime, keyed by catalog
	 * name under {@link SequenceType#CATALOG_GENERATION}.
	 *
	 * This is an engine-scoped instance, distinct from the per-catalog {@link SequenceService} that hands out
	 * entity and index primary keys and dies with its catalog: a folder generation has to outlive the catalog
	 * object that occupied it, because a folder left behind by a failed operation is exactly what the next
	 * allocation must not collide with. Its counters burn a number per attempt rather than per success, and are
	 * seeded at boot from the peaks the engine state carries.
	 */
	@Getter private final SequenceService catalogGenerationSequences = new SequenceService();
	/**
	 * List of futures that are used to load all catalogs in parallel during startup and when all are completed
	 * the list is cleared.
	 */
	private final AtomicReference<ProgressingFuture<Catalog>[]> initialLoadCatalogFutures;
	/**
	 * Tracks whether {@link #scheduleInitialCatalogLoading()} has been invoked. This is the guard
	 * for the two-phase boot contract: callers must construct `Evita`, attach observers (e.g.
	 * register external API providers that subscribe to the system CDC stream), and only then
	 * schedule catalog loading. Calling {@link #waitUntilFullyInitialized()} before scheduling is
	 * a programming error (would deadlock) and is rejected with a fail-fast exception.
	 */
	private final AtomicBoolean catalogLoadingScheduled = new AtomicBoolean(false);
	/**
	 * Flag that is set to TRUE when Evita fully loads all catalogs, that should be active after startup.
	 */
	@Getter private final CompletableFuture<Void> fullyInitialized;
	/**
	 * Flag that is se to TRUE when Evita. is ready to serve application calls.
	 * Aim of this flag is to refuse any calls after {@link #close()} method has been called.
	 */
	@Getter private final AtomicBoolean active = new AtomicBoolean(false);
	/**
	 * Flag that is initially set to {@link ServerOptions#readOnly()} from {@link EvitaConfiguration}.
	 * The flag might be changed from false to TRUE one time using internal Evita API. This is used in test support.
	 */
	@Getter private boolean readOnly;
	/**
	 * Callback that will be called when a new session is created.
	 */
	private final Consumer<EvitaSessionContract> onSessionCreationCallback;
	/**
	 * Callback that will be called when an old session is closed.
	 */
	private final Consumer<EvitaSessionContract> onSessionTerminationCallback;

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
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * Constructs a new `Evita` instance with the given configuration. Initial catalog loading is
	 * scheduled automatically.
	 *
	 * **Caveat for callers who construct `ExternalApiServer` (or any other host-CDC subscriber)
	 * manually against this instance:** use {@link #Evita(EvitaConfiguration, boolean)} with
	 * `scheduleCatalogLoading=false` instead. Otherwise host events for fast-loading catalogs may
	 * fire before subscribers attach (HOST-area events are live-tail only) and those catalogs will
	 * be invisible to the subscribers, leaving e.g. GraphQL/REST endpoints unregistered.
	 * `EvitaServer` already handles this correctly — its users do not need the boolean overload.
	 *
	 * @param configuration evita configuration; never `null`
	 */
	public Evita(@Nonnull EvitaConfiguration configuration) {
		this(configuration, true, null, null);
	}

	/**
	 * Constructs a new `Evita` instance with session lifecycle callbacks. Initial catalog loading
	 * is scheduled automatically. Same caveat as {@link #Evita(EvitaConfiguration)} for callers
	 * wiring up host-CDC subscribers manually.
	 */
	public Evita(
		@Nonnull EvitaConfiguration configuration,
		@Nullable Consumer<EvitaSessionContract> onSessionCreationCallback,
		@Nullable Consumer<EvitaSessionContract> onSessionTerminationCallback
	) {
		this(
			configuration,
			true,
			onSessionCreationCallback,
			onSessionTerminationCallback
		);
	}

	/**
	 * Constructs a new `Evita` instance with explicit control over the second phase of the boot
	 * sequence.
	 *
	 * Pass `scheduleCatalogLoading=false` when you intend to attach host-CDC subscribers (e.g. by
	 * constructing `ExternalApiServer` against this instance) before catalogs are allowed to begin
	 * loading. Then call {@link #scheduleInitialCatalogLoading()} (or rely on
	 * `ExternalApiServer.start()` to call it for you) after every subscriber is in place. This
	 * avoids the live-tail-only race described on {@link #Evita(EvitaConfiguration)}.
	 *
	 * Pass `true` (or use {@link #Evita(EvitaConfiguration)}) for pure embedded use without
	 * external-API surfaces. `EvitaServer` uses `false` internally — its users do not need this
	 * overload.
	 *
	 * @param configuration            evita configuration; never `null`
	 * @param scheduleCatalogLoading   `true` schedules loading immediately; `false` defers it to
	 *                                 an explicit {@link #scheduleInitialCatalogLoading()} call
	 */
	public Evita(
		@Nonnull EvitaConfiguration configuration,
		boolean scheduleCatalogLoading
	) {
		this(
			configuration,
			scheduleCatalogLoading,
			null,
			null
		);
	}

	/**
	 * Constructs a new `Evita` instance with session lifecycle callbacks and explicit control over the
	 * second phase of the boot sequence (see {@link #Evita(EvitaConfiguration, boolean)}). The executor kind
	 * defaults to {@link DevelopmentConstants#isTestRun()} — an immediate (synchronous) executor during test
	 * runs, real thread pools otherwise; use
	 * {@link #Evita(EvitaConfiguration, boolean, Consumer, Consumer, boolean)} to choose it explicitly.
	 *
	 * @param configuration                evita configuration; never `null`
	 * @param scheduleCatalogLoading       `true` schedules loading immediately; `false` defers it to an
	 *                                     explicit {@link #scheduleInitialCatalogLoading()} call
	 * @param onSessionCreationCallback    optional callback invoked when a session is created
	 * @param onSessionTerminationCallback optional callback invoked when a session is terminated
	 */
	public Evita(
		@Nonnull EvitaConfiguration configuration,
		boolean scheduleCatalogLoading,
		@Nullable Consumer<EvitaSessionContract> onSessionCreationCallback,
		@Nullable Consumer<EvitaSessionContract> onSessionTerminationCallback
	) {
		this(
			configuration,
			scheduleCatalogLoading,
			onSessionCreationCallback,
			onSessionTerminationCallback,
			// in test runs default to the immediate (synchronous) executor so embedded execution stays
			// deterministic; networked / production callers pass an explicit value to the constructor below
			DevelopmentConstants.isTestRun()
		);
	}

	/**
	 * Constructs a new `Evita` instance with explicit control over both the second phase of the boot
	 * sequence and the executor kind used for the request pool and the scheduler.
	 *
	 * This is the full constructor all other overloads delegate to. Networked hosts (e.g.
	 * `EvitaServer`) and any caller that needs real (asynchronous) thread pools pass
	 * `directExecutor=false`; embedded callers that omit the flag get the {@link DevelopmentConstants#isTestRun()}
	 * default, which collapses the request pool and scheduler into an immediate (synchronous) executor
	 * during test runs to keep embedded execution deterministic.
	 *
	 * @param configuration                       evita configuration; never `null`
	 * @param scheduleCatalogLoading              `true` schedules loading immediately; `false` defers it to
	 *                                            an explicit {@link #scheduleInitialCatalogLoading()} call
	 * @param onSessionCreationCallback           optional callback invoked when a session is created
	 * @param onSessionTerminationCallback        optional callback invoked when a session is terminated
	 * @param directExecutor                      `true` uses the immediate (synchronous) executor for the
	 *                                            request pool and scheduler; `false` uses real thread pools
	 */
	public Evita(
		@Nonnull EvitaConfiguration configuration,
		boolean scheduleCatalogLoading,
		@Nullable Consumer<EvitaSessionContract> onSessionCreationCallback,
		@Nullable Consumer<EvitaSessionContract> onSessionTerminationCallback,
		boolean directExecutor
	) {
		this.configuration = configuration;
		this.onSessionCreationCallback = onSessionCreationCallback == null ?
			Functions.noOpConsumer() : onSessionCreationCallback;
		this.onSessionTerminationCallback = onSessionTerminationCallback == null ?
			Functions.noOpConsumer() : onSessionTerminationCallback;

		this.serviceExecutor = directExecutor ?
			// in test environment we use immediate (synchronous) executor to avoid race conditions
			new Scheduler(new ImmediateScheduledThreadPoolExecutor()) :
			// in standard environment we use a scheduled thread pool executor
			new Scheduler(configuration.server().serviceThreadPool());
		this.requestExecutor = new ObservableThreadExecutor(
			"request", configuration.server().requestThreadPool(),
			directExecutor,
			RequestThreadPoolStatisticsEvent::new
		);
		this.transactionExecutor = new ObservableThreadExecutor(
			"transaction",
			configuration.server().transactionThreadPool(),
			// transaction handling must always run in a separate thread pool, even in tests
			// because it uses thread local variables for transaction management
			false,
			TransactionThreadPoolStatisticsEvent::new
		);

		this.sessionKiller = of(configuration.server().closeSessionsAfterSecondsOfInactivity())
			.filter(it -> it > 0)
			.map(it -> new SessionKiller(it, this, this.serviceExecutor))
			.orElse(null);
		this.cacheSupervisor = configuration.cache().enabled() ?
			new HeapMemoryCacheSupervisor(configuration.cache(), this.serviceExecutor) : NoCacheSupervisor.INSTANCE;
		this.collationKeyCacheSweeper = of(configuration.server().dropCollationKeysAfterSecondsOfInactivity())
			.filter(it -> it > 0)
			.map(it -> new CollationKeyCacheSweeper(it, this.serviceExecutor))
			.orElse(null);
		this.reflectionLookup = new ReflectionLookup(configuration.cache().reflection());
		this.tracingContext = TracingContextProvider.getContext();

		final ServiceLoader<EnginePersistenceServiceFactory> svcLoader = ServiceLoader.load(
			EnginePersistenceServiceFactory.class
		);

		//noinspection unchecked
		final EnginePersistenceService<LogRecordReference> enginePersistenceService = svcLoader
			.findFirst()
			.map(it -> it.create(configuration.storage(), configuration.transaction(), this.serviceExecutor))
			.orElseThrow(StorageImplementationNotFoundException::new);

		// Built only once the persistence service exists, because whole-folder operations are performed by the
		// storage layer on the engine's behalf - the engine binds catalogs to opaque folder tokens and never
		// joins one onto the storage root itself. See `CatalogFolderId` for the boundary rule.
		this.catalogFolderContext = new CatalogFolderContext(
			catalogName -> this.engineState.get().boundFolderIdFor(catalogName),
			enginePersistenceService,
			configuration.storage().storageDirectory(),
			// one number per allocation *attempt* - a name that could not be created is never redrawn, which is
			// what stops a folder the filesystem refuses to clear from making the catalog unallocatable forever
			catalogName -> this.catalogGenerationSequences
				.getOrCreateSequence(catalogName, SequenceType.CATALOG_GENERATION, 0)
				.incrementAndGet()
		);

		this.management = new EvitaManagement(this);
		this.proxyFactory = ProxyFactory.createInstance(this.reflectionLookup);

		final EngineState<LogRecordReference> engineState = enginePersistenceService.getEngineState();

		// The resolver above reads the engine state, and the stubs built below resolve their folder through it,
		// so the persisted snapshot has to be published before the first lookup. It is published with no catalog
		// instances attached and immediately superseded once the stubs exist - only the name-to-folder mapping is
		// read in between.
		this.engineState.set(ExpandedEngineState.create(engineState, Map.of()));
		seedCatalogGenerationSequences(engineState);

		final HashMap<String, CatalogContract> catalogs = CollectionUtils.createHashMap(
			engineState.activeCatalogs().length + engineState.inactiveCatalogs().length
		);

		// Install pre-divergence stubs for everything the persisted state knows about. The boot-time
		// divergence drain below uses `EngineTransactionManager` to apply WAL-backed mutations that
		// will replace the relevant stubs (active/inactive catalogs whose folder vanished get an
		// `UnusableCatalog(MISSING)` placeholder; folders that came back / were auto-discovered get an
		// `UnusableCatalog(INACTIVE)` placeholder) before the catalog load futures are spawned.
		Arrays.stream(engineState.inactiveCatalogs())
		      .map(
			      it -> this.catalogFolderContext.createUnusableCatalog(
				      it, CatalogState.INACTIVE, CatalogInactiveException::new
			      )
		      )
		      .forEach(it -> catalogs.put(it.getName(), it));
		Arrays.stream(engineState.activeCatalogs())
		      .map(
			      it -> this.catalogFolderContext.createUnusableCatalog(
				      it, CatalogState.BEING_ACTIVATED,
				      (cn, folderId, root) ->
					      new CatalogTransitioningException(cn, folderId, root, CatalogState.BEING_ACTIVATED)
			      )
		      )
		      .forEach(it -> catalogs.put(it.getName(), it));

		// initialize engine state with the pre-divergence stubs so the transaction manager has a
		// consistent view to mutate during the divergence drain.
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
			this, this.changeObserver, this.transactionExecutor, enginePersistenceService,
			new DefaultUpgradeExecutor(
				this.configuration.storage(), this.configuration.transaction(),
				this.serviceExecutor, this.management.exportService(),
				this.catalogFolderContext
			)
		);

		// Drain catalog inventory divergence detected during persistence-service construction. Each entry produces
		// a WAL record and bumps the engine version, so boot-time reconciliation flows through the same WAL-first
		// path as runtime mutations and is observable through CDC.
		drainPendingCatalogInventoryDivergence(enginePersistenceService.getPendingCatalogInventoryDivergence());

		// Spawn catalog load futures from the POST-divergence active catalogs — names that became
		// MISSING above are no longer in the active list and must NOT be loaded.
		final ExpandedEngineState postDivergence = this.engineState.get();
		//noinspection unchecked
		this.initialLoadCatalogFutures = new AtomicReference<>(
			Arrays.stream(postDivergence.engineState().activeCatalogs())
			      .map(catalogName -> this.loadCatalogInternal(
				      catalogName,
				      ArrayUtils.computeInsertPositionOfObjInOrderedArray(
					      catalogName, postDivergence.engineState().readOnlyCatalogs()
				      ).alreadyPresent()
			      ))
			      .toArray(ProgressingFuture[]::new)
		);

		this.fullyInitialized = CompletableFuture.allOf(
			this.initialLoadCatalogFutures.get()
		).whenComplete(
			(__, throwable) -> {
				if (throwable != null) {
					// CORRUPTED is the source of truth for genuine boot-time failures; the
					// auto-upgrade path completes the first-attempt future exceptionally by
					// design and is logged separately by `scheduleStorageProtocolUpgradeAndRetry`.
					final long corruptedCount = this.getCatalogs()
						.stream()
						.map(CatalogContract::getCatalogState)
						.filter(CatalogState.CORRUPTED::equals)
						.count();
					if (corruptedCount > 0) {
						log.error(
							"Errors encountered during start - {} catalog(s) could not be loaded!",
							corruptedCount
						);
					}
				}
				// clear the initial load catalog futures, we don't need them anymore
				this.initialLoadCatalogFutures.set(null);
			}
		);

		this.active.set(true);
		this.readOnly = this.configuration.server().readOnly();

		// register the system observer that will capture changes in the system and emit observability events
		this.changeObserver.registerObserver(
			new ChangeSystemCaptureRequest(null, null, null, ChangeCaptureContent.BODY)
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

	@Override
	public boolean isActive() {
		return this.active.get();
	}

	/**
	 * Schedules the initial loading of catalogs by executing all future tasks in the
	 * `initialLoadCatalogFutures` collection using the engine transaction executor.
	 *
	 * **Two-phase boot contract.** This method is the second phase of the boot sequence: it
	 * must be called *after* any host-level observers (external API providers, change-capture
	 * subscribers, etc.) have attached to the system CDC stream. Once invoked, host events
	 * such as {@link io.evitadb.api.requestResponse.cdc.HostSystemEvent.CatalogInstalledIntoLiveView}
	 * begin firing as catalogs settle into the live view; subscribers attached before this call
	 * are guaranteed to receive every event.
	 *
	 * Calling this method twice is a no-op for the second call (futures only execute once).
	 */
	public void scheduleInitialCatalogLoading() {
		// idempotent: only the first call kicks off the executions; subsequent invocations
		// (e.g. from `ExternalApiServer.start()` AND `EvitaServer.run()`) are silent no-ops
		if (!this.catalogLoadingScheduled.compareAndSet(false, true)) {
			return;
		}
		final ProgressingFuture<Catalog>[] progressingFutures = this.initialLoadCatalogFutures.get();
		if (progressingFutures != null) {
			final Executor unrejectableExecutor = ProgressingFuture.unrejectableExecutor(this.engineTransactionManager.getExecutor());
			for (ProgressingFuture<Catalog> loadCatalogFuture : progressingFutures) {
				loadCatalogFuture.execute(unrejectableExecutor);
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
	 * @return An instance of {@link ObservableExecutorServiceWithCancellationSupport}
	 * that handles request execution with hard deadlines for tasks.
	 */
	@Nonnull
	public ObservableExecutorServiceWithCancellationSupport getRequestExecutor() {
		return this.requestExecutor;
	}

	/**
	 * Provides access to the transaction executor service, which is responsible for managing
	 * and executing transactional operations within the Evita instance.
	 *
	 * @return An instance of {@link ObservableExecutorServiceWithCancellationSupport} that handles
	 * transaction execution with hard deadlines for tasks.
	 */
	@Nonnull
	public ObservableExecutorServiceWithCancellationSupport getTransactionExecutor() {
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
			RequestThreadPoolStatisticsEvent.class,
			this.requestExecutor::emitStatistics
		);
		FlightRecorder.addPeriodicEvent(
			TransactionThreadPoolStatisticsEvent.class,
			this.transactionExecutor::emitStatistics
		);
		FlightRecorder.addPeriodicEvent(
			ScheduledExecutorStatisticsEvent.class,
			this.serviceExecutor::emitStatistics
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
	 * Blocks the current thread until the initial catalog loading is fully completed.
	 *
	 * **Pure-wait semantics.** This method does NOT trigger loading — that is the job of
	 * {@link #scheduleInitialCatalogLoading()}. Calling this method before scheduling has
	 * happened (and when there is at least one catalog still pending) is a programming error
	 * and is rejected with {@link GenericEvitaInternalError} rather than silently deadlocking.
	 *
	 * Use {@link #loadCatalogsAndWaitUntilFullyInitialized()} when you want the combined
	 * "schedule and wait" behavior in a single call.
	 *
	 * @throws GenericEvitaInternalError if catalog loading is pending and has not been scheduled
	 */
	public void waitUntilFullyInitialized() {
		Assert.isPremiseValid(
			this.fullyInitialized.isDone() || this.catalogLoadingScheduled.get(),
			() -> new GenericEvitaInternalError(
				"Catalog loading has not been scheduled — call scheduleInitialCatalogLoading() first " +
					"or use loadCatalogsAndWaitUntilFullyInitialized() instead."
			)
		);
		this.fullyInitialized.join();
	}

	/**
	 * Convenience combinator that schedules catalog loading and then blocks until it
	 * completes. Equivalent to:
	 *
	 * ```
	 * scheduleInitialCatalogLoading();
	 * waitUntilFullyInitialized();
	 * ```
	 *
	 * Use this when no host-level observers need to be attached between scheduling and waiting
	 * (typical in tests, or in embedded uses that do not subscribe to the system CDC stream).
	 * In server contexts where external API providers must subscribe before loading begins,
	 * call {@link #scheduleInitialCatalogLoading()} and {@link #waitUntilFullyInitialized()}
	 * separately so the provider construction can run between them.
	 */
	public void loadCatalogsAndWaitUntilFullyInitialized() {
		scheduleInitialCatalogLoading();
		waitUntilFullyInitialized();
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
		if (this.active.compareAndSet(true, false)) {
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
			this,
			this.proxyFactory,
			this.management.exportService(),
			this.management.fileManagementService(),
			this::replaceCatalogReference,
			this.tracingContext
		);
	}

	/**
	 * Loads catalog from the designated directory. If the catalog is corrupted, it will be marked as such, but it'll
	 * still be added to the list of catalogs.
	 *
	 * **Auto-upgrade of old-protocol catalogs:** when the underlying persistence service throws
	 * {@link CatalogRequiresUpgradeException} — signalling that the on-disk storage protocol is older than the engine
	 * supports — this method schedules an asynchronous upgrade flow instead of marking the catalog CORRUPTED: it
	 * issues an `UpgradeCatalogFormatMutation` through the engine transaction manager, and on that mutation's
	 * completion re-invokes the load (exactly once). The real `Catalog` is installed via the `replaceCatalogReference`
	 * side-effect when the retry succeeds. The first-attempt future still completes exceptionally, but boot continues
	 * normally because the retry runs in the background and does not block `fullyInitialized`.
	 *
	 * @param catalogName name of the catalog
	 * @param readOnly    when {@code true} the catalog is opened in read-only mode
	 * @return future that completes with the loaded {@link Catalog} instance
	 */
	@Nonnull
	public ProgressingFuture<Catalog> loadCatalogInternal(@Nonnull String catalogName, boolean readOnly) {
		return loadCatalogInternal(catalogName, readOnly, LoadMode.INITIAL);
	}

	/**
	 * See {@link #loadCatalogInternal(String, boolean)}. The {@code mode} discriminates between the
	 * initial load and the auto-upgrade retry path: {@link LoadMode#RETRY_AFTER_UPGRADE} prevents
	 * infinite retry loops by treating a second `CatalogRequiresUpgradeException` (after an upgrade
	 * mutation has already run) as a terminal failure that marks the catalog CORRUPTED.
	 *
	 * @param catalogName name of the catalog
	 * @param readOnly    when {@code true} the catalog is opened in read-only mode
	 * @param mode        load mode — {@link LoadMode#INITIAL} for first attempts (auto-upgrade
	 *                    enabled), {@link LoadMode#RETRY_AFTER_UPGRADE} for the post-upgrade retry
	 *                    (auto-upgrade disabled)
	 * @return future that completes with the loaded {@link Catalog} instance
	 */
	@Nonnull
	private ProgressingFuture<Catalog> loadCatalogInternal(
		@Nonnull String catalogName, boolean readOnly, @Nonnull LoadMode mode
	) {
		final long start = System.nanoTime();
		return Catalog.loadCatalog(
			catalogName,
			readOnly,
			this.cacheSupervisor,
			this,
			this.proxyFactory,
			this.management.exportService(),
			this.management.fileManagementService(),
			this::replaceCatalogReference,
			(cn, catalog) -> {
				log.info("Catalog {} fully loaded in: {}", catalogName, StringUtils.formatNano(System.nanoTime() - start));
				catalog.processWriteAheadLog(
					updatedCatalog -> {
						final ExpandedEngineState afterReplay = this.engineState.updateAndGet(
							existingState -> {
								if (existingState == null) {
									// may be null, when the engine is shutting down
									return null;
								} else {
									return existingState.withUpdatedCatalogInstance(updatedCatalog);
								}
							}
						);
						if (updatedCatalog instanceof Catalog theUpdatedCatalog) {
							theUpdatedCatalog.notifyCatalogPresentInLiveView();
						}
						// Emit the host event so HOST-area subscribers learn that the
						// post-WAL-replay catalog reference has settled on this host. This path
						// bypasses `replaceCatalogReference` (which is the canonical chokepoint
						// elsewhere) so the emit must be wired explicitly here — see issue #1151.
						// Skip the emit when the engine state collapsed to null mid-shutdown.
						//
						// We deliberately do NOT emit `CatalogSchemaUpdated` here even when WAL
						// replay advances the schema: live observers had no view of the catalog
						// before this callback (it wasn't in the live view yet), so the
						// `CatalogInstalledIntoLiveView` event already carries the post-replay
						// schema and drives a single register/refresh in GraphQL / REST observers.
						// Adding a second host event would only force a redundant rebuild.
						if (afterReplay != null) {
							notifyCatalogStateSettled(
								updatedCatalog.getName(), updatedCatalog.getCatalogState()
							);
						}
					}
				);
				this.emitCatalogStatistics(catalogName);
			},
			(cn, exception) -> {
				final Throwable cause = ExceptionUtils.unwrapCompletionWrappers(exception);
				if (mode == LoadMode.INITIAL && cause instanceof CatalogRequiresUpgradeException upgradeRequired) {
					scheduleStorageProtocolUpgradeAndRetry(cn, readOnly, upgradeRequired);
					// Deliberately skip the CORRUPTED marking — the retry path will install the
					// real Catalog (or mark CORRUPTED itself if the upgrade mutation fails).
					return;
				}
				log.error("Catalog {} is corrupted!", cn, exception);
				markCatalogCorrupted(cn, exception);
			},
			this.tracingContext
		);
	}

	/**
	 * Auto-upgrade driver invoked from `loadCatalogInternal`'s onFailure callback when the
	 * persistence service reports that the catalog's on-disk storage protocol is behind the
	 * engine's current version. Issues an `UpgradeCatalogFormatMutation` and, once it settles,
	 * re-invokes the load exactly once. See class-level JavaDoc on {@link #loadCatalogInternal}.
	 *
	 * Runs asynchronously on the service executor so it does not stall the failure callback's
	 * thread (which typically belongs to the transaction/request executor).
	 *
	 * @param catalogName     name of the catalog to upgrade and reload
	 * @param readOnly        propagated to the retried load — same value the original load used
	 * @param upgradeRequired the upgrade-required exception carrying the from/to protocol versions
	 */
	private void scheduleStorageProtocolUpgradeAndRetry(
		@Nonnull String catalogName, boolean readOnly,
		@Nonnull CatalogRequiresUpgradeException upgradeRequired
	) {
		// Defensive guard — the single-arg `CatalogRequiresUpgradeException(name)` ctor sets both
		// protocol versions to -1 (used by reporting paths that do not inspect the on-disk header).
		// If such an exception reaches us here we must NOT synthesize a mutation with nonsense
		// version numbers — writing `UpgradeCatalogFormatMutation(name, -1, -1)` into the engine
		// WAL would leave a malformed record that cannot be replayed. Fall back to marking the
		// catalog CORRUPTED so the operator can intervene. Predicate lives on the exception so the
		// guard is unit-testable independently of the Evita instance.
		if (!upgradeRequired.hasValidProtocolMetadata()) {
			log.error(
				"Catalog {} reported as requiring storage-protocol upgrade but with invalid " +
					"version metadata (from=v{}, to=v{}) — marking CORRUPTED instead of issuing a " +
					"malformed UpgradeCatalogFormatMutation.",
				catalogName,
				upgradeRequired.getFromProtocolVersion(),
				upgradeRequired.getToProtocolVersion()
			);
			markCatalogCorrupted(catalogName, upgradeRequired);
			return;
		}
		log.info(
			"Catalog {} requires storage-protocol upgrade from v{} to v{} — auto-issuing " +
				"UpgradeCatalogFormatMutation.",
			catalogName, upgradeRequired.getFromProtocolVersion(), upgradeRequired.getToProtocolVersion()
		);
		// Fire-and-forget is intentional: the resulting future is not tracked by `initialLoadCatalogFutures`
		// because the lifecycle is bounded by `serviceExecutor`, which `closeAndDestroy()` shuts down after the
		// catalogs are closed. An in-flight upgrade therefore either completes before close (the engine ingests
		// the mutation through the normal `applyMutation` path, the retry installs the live Catalog) or is
		// interrupted/cancelled by executor shutdown — in either case there is no shutdown race that could
		// corrupt on-disk state. The applyMutation call itself is durable through the WAL, so a partial
		// completion still leaves a recoverable on-disk snapshot.
		CompletableFuture.runAsync(
			() -> {
				try {
					this.engineTransactionManager.applyMutation(
						new UpgradeCatalogFormatMutation(
							catalogName,
							upgradeRequired.getFromProtocolVersion(),
							upgradeRequired.getToProtocolVersion()
						),
						null
					)
						.onCompletion()
						.toCompletableFuture()
						.join();
				} catch (Throwable upgradeEx) {
					log.error(
						"Auto-upgrade of catalog {} failed — marking CORRUPTED.",
						catalogName, upgradeEx
					);
					markCatalogCorrupted(catalogName, upgradeEx);
					return;
				}
				// Mutation completed — retry the load exactly once (RETRY_AFTER_UPGRADE ensures
				// that a second CatalogRequiresUpgradeException marks CORRUPTED instead of looping).
				final ProgressingFuture<Catalog> retryFuture = loadCatalogInternal(
					catalogName, readOnly, LoadMode.RETRY_AFTER_UPGRADE
				);
				retryFuture.execute(
					ProgressingFuture.unrejectableExecutor(this.engineTransactionManager.getExecutor())
				);
			},
			this.serviceExecutor
		);
	}

	/**
	 * Fast-forwards the engine-scoped folder generation counters to the peaks the persisted state carries.
	 *
	 * Two terms are applied and they are complementary rather than redundant:
	 *
	 * - the **persisted peaks** carry a number that was handed out for a name which may be unusable yet
	 *   invisible to a scan — `Files.exists` reports an `AccessDeniedException` as absence, so such a name
	 *   would be drawn again after every restart;
	 * - the **disk scan** observes a folder an operation created before dying without persisting anything, which
	 *   no peak knows about.
	 *
	 * Neither subsumes the other, so the counter is fast-forwarded past both.
	 *
	 * **Nothing writes the peaks yet.** No production path records a `CatalogGenerationPeak`, so the peak set
	 * is empty on every installation and only the disk scan is live today. The scan covers the ordinary case; the
	 * gap it leaves is exactly the case described above — a generation burned against a name the filesystem then
	 * refuses to report — which is redrawn after a restart. Recording the peak belongs in the engine-state commit
	 * of whichever operation drew the number.
	 *
	 * @param engineState persisted snapshot whose generation peaks are to be applied
	 */
	private void seedCatalogGenerationSequences(@Nonnull EngineState<LogRecordReference> engineState) {
		// `getOrCreateSequence` only ever fast-forwards, so seeding is idempotent and can never walk a counter
		// back onto a number it has already handed out - which also makes the order of the two terms irrelevant.
		for (final CatalogGenerationPeak peak : engineState.generationPeaks()) {
			this.catalogGenerationSequences.getOrCreateSequence(
				peak.catalogName(), SequenceType.CATALOG_GENERATION, peak.peak()
			);
		}
		for (final Entry<String, Integer> observed :
			this.catalogFolderContext.getFolderOperations().observedFolderGenerationPeaks().entrySet()) {
			this.catalogGenerationSequences.getOrCreateSequence(
				observed.getKey(), SequenceType.CATALOG_GENERATION, observed.getValue()
			);
		}
	}

	/**
	 * Drains the boot-time catalog inventory divergence by applying one WAL-backed engine mutation per entry. The
	 * persistence service computed this divergence as a pure value during its construction; here we replay it through
	 * the regular `EngineTransactionManager.applyMutation` path so each reconciliation step produces a WAL record,
	 * advances the engine version, and is observable through CDC.
	 *
	 * Apply order matters and is enforced by phasing:
	 *
	 * 1. Phase 1 — `becomeMissing`: `MarkCatalogMissingMutation` for each name. Drained to completion BEFORE phase 2
	 *    is dispatched, so names freed from the active/inactive arrays cannot collide with subsequent
	 *    `RestoreCatalogSchemaMutation` `verifyApplicability` checks for auto-discovered or reappeared catalogs.
	 *    `applyMutation` only holds the engine-state lock during `verifyApplicability`/conflict-key registration —
	 *    the actual state transition runs asynchronously after the lock is released — so synchronous awaiting at
	 *    phase boundaries is what makes the ordering invariant load-bearing.
	 * 2. Phase 2 — `reappeared` + `autoDiscovered`: `RestoreCatalogSchemaMutation` for each name. The two groups
	 *    operate on disjoint name sets (a name cannot simultaneously reappear from the MISSING bucket and be newly
	 *    auto-discovered), so they run in parallel. Each auto-discovered folder is adopted first — renamed into
	 *    the shape the engine allocates and reserved — so the mutation binds the catalog to the folder that
	 *    rename produced.
	 *
	 * A crash between an adoption and its binding leaves a renamed, unreferenced folder, which classifies as
	 * unclaimed: reported, never touched, and recoverable by renaming it back to a suffix-free name. That is the
	 * same window `CatalogFolderContext#completeFolder` opens for create and restore, accepted here for the same
	 * reason — closing it would need the binding to be committed before the folder is in its final place, which
	 * trades a cosmetic gap for a live binding pointing at a directory that is about to move.
	 *
	 * Each phase awaits its `onCompletion` futures so the engine state is stable by the time we exit. Failures wedge
	 * the boot loudly via `GenericEvitaInternalError` — silently degrading would mask the WAL/engine-state drift
	 * this code is meant to prevent.
	 *
	 * **Known gap: the divergence is computed before forward replay and drained after it.** The persistence service
	 * builds it in its constructor, from the folders it finds on disk against the bootstrap as the crash left it;
	 * the transaction manager replays a crashed commit a layer up, and only then is this called. A catalog the
	 * replay has just re-registered can therefore be marked MISSING here, on the strength of a reading taken before
	 * the replay existed. The engine's own operations cannot produce it — every operator that unbinds a folder
	 * deletes it strictly *after* its own commit, so a crash inside the commit window always leaves the folder
	 * standing — but a folder that vanishes by other means (an external deletion, a filesystem failure) while an
	 * unrecovered record sits in the log does. Closing it means recomputing the divergence after the replay rather
	 * than reusing the one computed before it.
	 *
	 * @param divergence divergence record returned by the persistence service; never null
	 */
	private void drainPendingCatalogInventoryDivergence(@Nonnull CatalogInventoryDivergence divergence) {
		// Noted before the emptiness check and outside it: confirming a tombstoned folder gone produces no
		// mutation of its own, so a boot whose only finding is a discharged tombstone still has to record it.
		// The entry is then dropped from persisted state by whichever engine mutation comes next - possibly one
		// of the mutations dispatched below, possibly the first catalog operation of the run.
		for (final CatalogFolderId drained : divergence.drainedFolders()) {
			this.catalogFolderContext.noteFolderDrained(drained);
		}
		if (!divergence.isEmpty()) {
			log.info(
				"Draining boot-time catalog inventory divergence: {} becomeMissing, {} reappeared, {} autoDiscovered.",
				divergence.becomeMissing().size(), divergence.reappeared().size(), divergence.autoDiscovered().size()
			);
			try {
				// Phase 1 — drain `becomeMissing` to completion before any restore is dispatched, so that
				// `RestoreCatalogSchemaMutation.verifyApplicability` for phase 2 sees a state in which the
				// soon-to-be-missing names have already been freed from the active/inactive arrays (and from
				// their naming-convention slots).
				if (!divergence.becomeMissing().isEmpty()) {
					final List<CompletableFuture<?>> phase1 = new ArrayList<>(divergence.becomeMissing().size());
					for (final String name : divergence.becomeMissing()) {
						phase1.add(
							this.engineTransactionManager
								.applyMutation(new MarkCatalogMissingMutation(name), null)
								.onCompletion().toCompletableFuture()
						);
					}
					CompletableFuture.allOf(phase1.toArray(CompletableFuture[]::new)).join();
				}
				// Phase 2 — `reappeared` and `autoDiscovered` operate on disjoint name sets, so they can run
				// in parallel. The broadened applicability rules and operator path (`Builder#withCatalogNoLongerMissing`)
				// handle the MISSING → INACTIVE bucket move for `reappeared`; for `autoDiscovered` names the
				// missing-bucket clearance is a no-op.
				final List<CompletableFuture<?>> phase2 = new ArrayList<>(
					divergence.reappeared().size() + divergence.autoDiscovered().size()
				);
				for (final String name : divergence.reappeared()) {
					phase2.add(
						this.engineTransactionManager
							.applyMutation(new RestoreCatalogSchemaMutation(name), null)
							.onCompletion().toCompletableFuture()
					);
				}
				for (final AdoptableCatalogFolder discovered : divergence.autoDiscovered()) {
					// Adopt before dispatching, never after: the operator reads the folder to bind from the
					// reservation this call leaves behind, and it must already name the folder the rename
					// produced. Doing it the other way round would bind the pre-rename name and then move the
					// folder out from under a live binding.
					//
					// Adoption is deliberately sequential while the mutations that follow are parallel — these
					// are directory renames drawing from a shared generation counter, and there is nothing to
					// win by overlapping them.
					this.catalogFolderContext.adoptFolderFor(discovered.catalogName(), discovered.folderId());
					phase2.add(
						this.engineTransactionManager
							.applyMutation(new RestoreCatalogSchemaMutation(discovered.catalogName()), null)
							.onCompletion().toCompletableFuture()
					);
				}
				if (!phase2.isEmpty()) {
					CompletableFuture.allOf(phase2.toArray(CompletableFuture[]::new)).join();
				}
			} catch (Throwable t) {
				throw new GenericEvitaInternalError(
					"Boot-time catalog-inventory-divergence reconciliation failed: " + ExceptionUtils.unwrapCompletionWrappers(t).getMessage(),
					t
				);
			}
		}
	}

	/**
	 * Marks the specified catalog as CORRUPTED in the engine state. Extracted from the inline
	 * onFailure callback so the auto-upgrade path can reuse the same terminal-failure bookkeeping
	 * when a retry fails.
	 *
	 * @param catalogName name of the catalog to mark CORRUPTED
	 * @param cause       the failure carried by the resulting {@link CatalogCorruptedException}
	 */
	private void markCatalogCorrupted(@Nonnull String catalogName, @Nonnull Throwable cause) {
		final ExpandedEngineState updated = this.engineState.updateAndGet(
			existingState -> {
				if (existingState == null) {
					return null;
				} else {
					return existingState.withUpdatedCatalogInstance(
						this.catalogFolderContext.createUnusableCatalog(
							catalogName,
							CatalogState.CORRUPTED,
							(tcn, folderId, root) -> new CatalogCorruptedException(tcn, folderId, root, cause)
						)
					);
				}
			}
		);
		// Emit the host event so HOST-area subscribers (GraphQL/REST/gRPC) learn about
		// the CORRUPTED transition without a server restart — see issue #1151. Skip the emit when
		// the engine state is null (shutdown race) so we do not call into a closed observer.
		if (updated != null) {
			notifyCatalogStateSettled(catalogName, CatalogState.CORRUPTED);
		}
		this.emitEvitaStatistics();
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
	 * Suspends a catalog's sessions, **installing a registry for it when it has none**.
	 *
	 * {@link #closeAllSessionsAndSuspend(String, SuspendOperation)} suspends only a registry that already
	 * exists, and a catalog nobody has opened a session on since boot has none — so an operation that quiesces
	 * a catalog through it suspends nothing at all, and every session request arriving while the operation runs
	 * is served against the catalog the operation is about to destroy. Measured on the replace path before this
	 * existed: 850 sessions opened on a catalog while it was being replaced.
	 *
	 * Registering the registry *first* and suspending it after is what closes the window: session creation goes
	 * through `computeIfAbsent` on the same map, so a racing request either builds nothing (this call got there
	 * first) or is the one that built it (and this call suspends the very instance it built).
	 *
	 * **A name that names no catalog gets no registry**, so a request for it keeps answering
	 * {@link CatalogNotFoundException} rather than the "terminated" that a placeholder would produce — a replace
	 * is allowed to target a catalog that does not exist.
	 *
	 * The caller is responsible for what happens next: a registry this call created must be **resumed or
	 * removed** if the operation fails, or the name is left permanently refusing sessions.
	 *
	 * **An operation that has to undo the suspension cannot use this method**, because the drain it performs
	 * throws when the sessions refuse to leave — and it throws with the suspension already published, so the
	 * caller never learns which registry it now owns. Such a caller takes ownership through
	 * {@link #obtainCatalogSessionRegistry(String)} first and drains the registry itself.
	 *
	 * @param catalogName      catalog whose sessions are to be closed and suspended
	 * @param suspendOperation how requests arriving during the suspension are to be treated
	 * @return the registry now suspended under that name, or empty when the name names no catalog
	 */
	@Nonnull
	public Optional<SessionRegistry> suspendCatalogSessions(
		@Nonnull String catalogName,
		@Nonnull SuspendOperation suspendOperation
	) {
		final Optional<SessionRegistry> registry = obtainCatalogSessionRegistry(catalogName);
		registry.ifPresent(it -> it.closeAllActiveSessionsAndSuspend(suspendOperation));
		return registry;
	}

	/**
	 * Returns the session registry of the passed catalog, **installing one when it has none**.
	 *
	 * This is the half of {@link #suspendCatalogSessions(String, SuspendOperation)} that cannot fail: it takes
	 * ownership of a registry without touching the sessions inside it. An operation that must be able to lift its
	 * own suspension calls this first, records what it now owns, and only then drains — because the drain
	 * publishes the suspension before it waits, and a drain that gives up throws with that suspension standing.
	 *
	 * The registry is installed through `computeIfAbsent` on the very map session creation uses, so a racing
	 * request either finds the registry this call installed or is the one that installed it, and this call then
	 * returns that same instance. **A name that names no catalog gets no registry**, so a request for it keeps
	 * answering {@link CatalogNotFoundException} rather than the "terminated" a placeholder would produce.
	 *
	 * @param catalogName catalog whose registry is to be obtained
	 * @return the registry registered under that name, or empty when the name names no catalog
	 */
	@Nonnull
	public Optional<SessionRegistry> obtainCatalogSessionRegistry(@Nonnull String catalogName) {
		return ofNullable(
			this.catalogSessionRegistries.computeIfAbsent(
				catalogName,
				name -> getCatalogInstance(name)
					.map(__ -> createSessionNewRegistry(new SessionTraits(name)))
					.orElse(null)
			)
		);
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
				if (existingRegistry != null && existingRegistry != sessionRegistry) {
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
	public void assertActive() {
		if (!this.active.get()) {
			throw new InstanceTerminatedException("instance");
		}
	}

	/**
	 * Verifies this instance is still active and not in read-only mode.
	 */
	public void assertActiveAndWritable() {
		assertActive();
		if (this.readOnly) {
			throw ReadOnlyException.engineReadOnly();
		}
	}

	/**
	 * Replaces current catalog reference with updated one.
	 *
	 * Delegates the swap (and the prior-vs-new schema-version comparison) to
	 * {@link ExpandedEngineState#replaceCatalogReference(Catalog)}, which performs both atomically
	 * and reports back whether the catalog schema version advanced. Emits a
	 * {@link HostSystemEvent.CatalogSchemaUpdated} on the system CDC stream when it did, so HOST-area
	 * subscribers (GraphQL / REST schema-refresh observers) coalesce a single refresh per real
	 * schema bump instead of per-commit. Pure data-only commits emit nothing.
	 *
	 * Note: state-transition emits ({@link HostSystemEvent.CatalogInstalledIntoLiveView}) are NOT
	 * issued here. This method is reached only via the commit pipeline (`TransactionManager#propagateCatalogSnapshot`),
	 * where prior+new are always real `Catalog` instances of the same state. State transitions
	 * (WARMING_UP→ALIVE, INACTIVE/MISSING/CORRUPTED settlements) flow through the matching
	 * mutation-operator completion-phase callbacks which call `notifyCatalogStateSettled` directly.
	 */
	private void replaceCatalogReference(@Nonnull Catalog catalog) {
		notNull(catalog, "Sanity check.");

		final ExpandedEngineState snapshot = this.engineState.get();
		if (snapshot == null) {
			// engine is shutting down — host events are best-effort on shutdown
			return;
		}

		final boolean schemaAdvanced = snapshot.replaceCatalogReference(catalog);

		// discard suspension of the session registry for the catalog, if present
		discardSuspension(catalog.getName());

		// notify callback that it's now a live snapshot
		catalog.notifyCatalogPresentInLiveView();

		if (schemaAdvanced) {
			notifyCatalogSchemaUpdated(catalog.getName(), catalog.getSchema().version());
		}
	}

	/**
	 * Emits a {@link HostSystemEvent.CatalogInstalledIntoLiveView} on the system CDC stream for
	 * the given catalog. Used by `replaceCatalogReference` and by completion-phase callbacks of
	 * mutation operators that install a settled catalog reference (real `Catalog` for ALIVE /
	 * WARMING_UP, `UnusableCatalog` for INACTIVE / MISSING / OUT_OF_DATE / CORRUPTED) without
	 * funneling through `replaceCatalogReference`.
	 *
	 * The event is host-local and live-tail only — it does NOT advance the engine version
	 * counter; the snapshot version it carries is for correlation.
	 *
	 * @param catalogName  name of the catalog whose reference settled; never `null`
	 * @param observedState the non-transient state the catalog settled into; the precondition is
	 *                      enforced by the {@link HostSystemEvent.CatalogInstalledIntoLiveView}
	 *                      record's compact constructor — passing a transitional state will fail
	 *                      fast as a programming error
	 */
	public void notifyCatalogStateSettled(
		@Nonnull String catalogName,
		@Nonnull CatalogState observedState
	) {
		// Defensive no-op on shutdown — see issue #1151. Both the engine state and the change
		// observer are torn down concurrently with in-flight operators during close; an operator's
		// completion-phase emit reaching this method post-close must NOT wedge the operator's
		// future with an `InstanceTerminatedException`. Host events are best-effort on shutdown.
		final ExpandedEngineState snapshot = this.engineState.get();
		if (snapshot == null || !this.changeObserver.isActive()) {
			return;
		}
		final HostSystemEvent.CatalogInstalledIntoLiveView event =
			new HostSystemEvent.CatalogInstalledIntoLiveView(
				catalogName,
				observedState,
				snapshot.version()
			);
		this.changeObserver.processHostEvent(event);
	}

	/**
	 * Emits a {@link HostSystemEvent.CatalogSchemaUpdated} on the system CDC stream for the
	 * given catalog. Used by `EvitaSession` at WARMING_UP termination and by `replaceCatalogReference`
	 * at ALIVE catalog version installation. Coalesces what was previously a per-mutation
	 * `refreshCatalog` storm in GraphQL / REST observers.
	 *
	 * The event is host-local and live-tail only — it does NOT advance the engine version
	 * counter; the snapshot version it carries is for correlation only.
	 *
	 * @param catalogName       name of the catalog whose schema version increased; never `null`
	 * @param newSchemaVersion  the new (current) catalog schema version on this host; non-negative
	 */
	public void notifyCatalogSchemaUpdated(
		@Nonnull String catalogName,
		int newSchemaVersion
	) {
		// Defensive no-op on shutdown — see `notifyCatalogStateSettled`.
		final ExpandedEngineState snapshot = this.engineState.get();
		if (snapshot == null || !this.changeObserver.isActive()) {
			return;
		}
		final HostSystemEvent.CatalogSchemaUpdated event =
			new HostSystemEvent.CatalogSchemaUpdated(
				catalogName,
				newSchemaVersion,
				snapshot.version()
			);
		this.changeObserver.processHostEvent(event);
	}

	/**
	 * Emits a {@link HostSystemEvent.CatalogRemovedFromLiveView} on the system CDC stream for the
	 * given catalog. Used by completion-phase callbacks of mutation operators that fully remove a
	 * catalog from the live view (e.g. `RemoveCatalogSchemaMutationOperator`).
	 *
	 * The event is host-local and live-tail only — it does NOT advance the engine version.
	 *
	 * @param catalogName name of the catalog removed from the live view; never `null`
	 */
	public void notifyCatalogRemovedFromLiveView(@Nonnull String catalogName) {
		// Defensive no-op on shutdown — see `notifyCatalogStateSettled`.
		final ExpandedEngineState snapshot = this.engineState.get();
		if (snapshot == null || !this.changeObserver.isActive()) {
			return;
		}
		final HostSystemEvent.CatalogRemovedFromLiveView event =
			new HostSystemEvent.CatalogRemovedFromLiveView(
				catalogName,
				snapshot.version()
			);
		this.changeObserver.processHostEvent(event);
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
					session -> {
						sessionRegistry.removeSession((EvitaSession) session);
						this.onSessionTerminationCallback.accept(session);
					};

				final EvitaInternalSessionContract internalSession = sessionRegistry.addSession(
					catalog.supportsTransaction(),
					() -> new EvitaSession(
						this, catalog, this.reflectionLookup,
						terminationCallback,
						ofNullable(sessionTraits.commitBehaviour()).orElse(CommitBehavior.defaultBehaviour()),
						sessionTraits,
						sessionRegistry::createCatalogConsumerControl
					)
				);

				this.onSessionCreationCallback.accept(internalSession);

				return internalSession;
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
		if (this.engineState.get() != null) {
			try {
				// emit the event
				new EvitaStatisticsEvent(
					this.configuration,
					this.management().getSystemStatus()
				).commit();
			} catch (Throwable t) {
				log.error("Emitting observability events failed!", t);
			}
		}
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
					try {
						if (Evita.this.isActive()) {
							final ExpandedEngineState theEngineState = Evita.this.getEngineState();
							// in very rare race conditions the engine state may be null here
							// (if evita is closed already)
							// noinspection ConstantValue
							if (theEngineState != null) {
								theEngineState
									.getCatalog(catalogName)
									.ifPresentOrElse(
										catalogContract -> {
											if (catalogContract instanceof Catalog monitoredCatalog) {
												monitoredCatalog.emitObservabilityEvents();
											} else {
												FlightRecorder.removePeriodicEvent(this);
											}
										},
										() -> {
											log.warn("Catalog {} does not exist, cannot emit statistics!", catalogName);
											FlightRecorder.removePeriodicEvent(this);
										}
									);
							}
						}
					} catch (Throwable t) {
						log.error("Emitting observability events failed!", t);
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
				CompletableFuture.runAsync(this.changeObserver::close),
				CompletableFuture.runAsync(this.cacheSupervisor::close),
				CompletableFuture.runAsync(() -> {
					if (this.sessionKiller != null) {
						this.sessionKiller.close();
					}
				}),
				CompletableFuture.runAsync(() -> {
					if (this.collationKeyCacheSweeper != null) {
						this.collationKeyCacheSweeper.close();
					}
				})
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

		// first we need to cancel all initial load futures - just in case some are still running
		final ProgressingFuture<Catalog>[] initialFutures = this.getInitialLoadCatalogFutures();
		for (ProgressingFuture<Catalog> initialFuture : initialFutures) {
			initialFuture.cancel(true);
		}
		// then we need to close all catalogs in parallel
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
			},
			Functions.noOpConsumer()
		);
		closedFuture.execute(ProgressingFuture.unrejectableExecutor(executor));
		return closedFuture;
	}

	/**
	 * Discriminator for {@link #loadCatalogInternal(String, boolean, LoadMode)} that distinguishes
	 * the first load attempt from the post-upgrade retry. Exists so the auto-upgrade flow can suppress
	 * a second auto-upgrade attempt and avoid infinite retry loops.
	 */
	private enum LoadMode {

		/**
		 * Initial load attempt — auto-upgrade is enabled. A {@link CatalogRequiresUpgradeException}
		 * triggers an asynchronous `UpgradeCatalogFormatMutation` and a single retry under
		 * {@link #RETRY_AFTER_UPGRADE}.
		 */
		INITIAL,

		/**
		 * Retry triggered by the auto-upgrade flow after an `UpgradeCatalogFormatMutation` has run.
		 * Auto-upgrade is disabled — a second {@link CatalogRequiresUpgradeException} is treated as a
		 * terminal failure and marks the catalog CORRUPTED.
		 */
		RETRY_AFTER_UPGRADE

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
