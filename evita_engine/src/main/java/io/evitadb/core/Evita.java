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
import io.evitadb.api.exception.CatalogNotFoundException;
import io.evitadb.api.exception.InstanceTerminatedException;
import io.evitadb.api.exception.ReadOnlyException;
import io.evitadb.api.observability.trace.TracingContext;
import io.evitadb.api.observability.trace.TracingContextProvider;
import io.evitadb.api.requestResponse.cdc.ChangeCaptureContent;
import io.evitadb.api.requestResponse.cdc.ChangeCapturePublisher;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCapture;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCaptureRequest;
import io.evitadb.api.requestResponse.data.DevelopmentConstants;
import io.evitadb.api.requestResponse.mutation.EngineMutation;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaEditor.CatalogSchemaBuilder;
import io.evitadb.api.requestResponse.schema.builder.InternalCatalogSchemaBuilder;
import io.evitadb.api.requestResponse.schema.mutation.CatalogSchemaMutation.CatalogSchemaWithImpactOnEntitySchemas;
import io.evitadb.api.requestResponse.schema.mutation.engine.CreateCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.MakeCatalogAliveMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.ModifyCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.ModifyCatalogSchemaNameMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.RemoveCatalogSchemaMutation;
import io.evitadb.api.task.ServerTask;
import io.evitadb.core.SessionRegistry.SuspendOperation;
import io.evitadb.core.cache.CacheSupervisor;
import io.evitadb.core.cache.HeapMemoryCacheSupervisor;
import io.evitadb.core.cache.NoCacheSupervisor;
import io.evitadb.core.cdc.EngineStatisticsPublisher;
import io.evitadb.core.cdc.SystemChangeObserver;
import io.evitadb.core.exception.CatalogCorruptedException;
import io.evitadb.core.exception.CatalogInactiveException;
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
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.task.SessionKiller;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.store.spi.EnginePersistenceService;
import io.evitadb.store.spi.EnginePersistenceServiceFactory;
import io.evitadb.store.spi.model.EngineState;
import io.evitadb.store.spi.model.reference.WalFileReference;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
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
		this.configuration = configuration;

		this.serviceExecutor = DevelopmentConstants.isTestRun() ?
			// in test environment we use immediate (synchronous) executor to avoid race conditions
			new Scheduler(new ImmediateScheduledThreadPoolExecutor()) :
			// in standard environment we use a scheduled thread pool executor
			new Scheduler(configuration.server().serviceThreadPool());
		this.requestExecutor = new ObservableThreadExecutor(
			"request", configuration.server().requestThreadPool(),
			this.serviceExecutor,
			configuration.server().queryTimeoutInMilliseconds(),
			DevelopmentConstants.isTestRun()
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
		this.management = new EvitaManagement(this);

		// register stubs for all inactive catalogs
		Arrays.stream(engineState.inactiveCatalogs())
		      .map(it -> new InactiveCatalog(it, this.configuration.storage().storageDirectory().resolve(it)))
		      .forEach(it -> this.catalogs.put(it.getName(), it));
		// spawn parallel tasks to load all active catalogs
		try {
			CompletableFuture.allOf(
				Arrays.stream(engineState.activeCatalogs())
				      .map(this::createLoadCatalogTask)
				      .map(this.serviceExecutor::submit)
				      .toArray(CompletableFuture[]::new)
			).get();
			this.active = true;
		} catch (Exception ex) {
			log.error("EvitaDB failed to start!", ex);
			// terminate evitaDB - it has not properly started
			this.closeInternal();
		}

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
			.map(it -> {
				if (it instanceof InactiveCatalog) {
					return CatalogState.INACTIVE;
				} else if (it instanceof CorruptedCatalog) {
					return CatalogState.CORRUPTED;
				} else {
					return it.getCatalogState();
				}
			});
	}

	@Override
	@Nonnull
	public CatalogSchemaBuilder defineCatalog(@Nonnull String catalogName) {
		final Optional<CatalogContract> catalogInstance = getCatalogInstance(catalogName);
		if (catalogInstance.isEmpty()) {
			applyMutation(new CreateCatalogSchemaMutation(catalogName));
			return new InternalCatalogSchemaBuilder(
				getCatalogInstanceOrThrowException(catalogName).getSchema()
			);
		} else {
			return new InternalCatalogSchemaBuilder(
				catalogInstance.get().getSchema()
			);
		}
	}

	@Override
	public void makeCatalogAlive(@Nonnull String catalogName) {
		assertActive();
		applyMutation(new MakeCatalogAliveMutation(catalogName));
	}

	@Override
	public void renameCatalog(@Nonnull String catalogName, @Nonnull String newCatalogName) {
		assertActive();
		applyMutation(new ModifyCatalogSchemaNameMutation(catalogName, newCatalogName, false));
	}

	@Override
	public void replaceCatalog(@Nonnull String catalogNameToBeReplacedWith, @Nonnull String catalogNameToBeReplaced) {
		assertActive();
		applyMutation(new ModifyCatalogSchemaNameMutation(catalogNameToBeReplacedWith, catalogNameToBeReplaced, true));
	}

	@Override
	public boolean deleteCatalogIfExists(@Nonnull String catalogName) {
		final CatalogContract catalogToRemove = this.catalogs.get(catalogName);
		if (catalogToRemove == null) {
			return false;
		} else {
			applyMutation(new RemoveCatalogSchemaMutation(catalogName));
			return true;
		}
	}

	@Override
	public void applyMutation(@Nonnull EngineMutation engineMutation) {
		assertActiveAndWritable();
		try {
			if (this.engineStateLock.tryLock(
				this.configuration.server().transactionTimeoutInMilliseconds(), TimeUnit.MILLISECONDS)) {
				final EngineState theEngineState = this.engineState.get();

				// verify that we can perform the mutation
				engineMutation.verifyApplicability(this);

				// first store the mutation into the persistence service
				final WalFileReference walFileReference = this.enginePersistenceService.appendWal(
					theEngineState.version() + 1,
					engineMutation
				);

				// notify system observer about the mutation
				this.changeObserver.processMutation(engineMutation);
				try {
					if (engineMutation instanceof CreateCatalogSchemaMutation createCatalogSchema) {
						createCatalogInternal(createCatalogSchema);
					} else if (engineMutation instanceof ModifyCatalogSchemaNameMutation modifyCatalogSchemaName) {
						if (modifyCatalogSchemaName.isOverwriteTarget() && this.catalogs.containsKey(
							modifyCatalogSchemaName.getNewCatalogName())) {
							replaceCatalogInternal(modifyCatalogSchemaName);
						} else {
							renameCatalogInternal(modifyCatalogSchemaName);
						}
					} else if (engineMutation instanceof ModifyCatalogSchemaMutation modifyCatalogSchema) {
						final CatalogContract catalogContract = this.catalogs.get(modifyCatalogSchema.getCatalogName());
						catalogContract.updateSchema(modifyCatalogSchema.getSessionId(), modifyCatalogSchema.getSchemaMutations());
					} else if (engineMutation instanceof RemoveCatalogSchemaMutation removeCatalogSchema) {
						removeCatalogInternal(removeCatalogSchema);
					} else if (engineMutation instanceof MakeCatalogAliveMutation makeCatalogAliveMutation) {
						makeCatalogAliveInternal(makeCatalogAliveMutation);
					} else {
						throw new EvitaInvalidUsageException(
							"Unknown engine mutation: `" + engineMutation.getClass() + "`!");
					}
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
						.walFileReference(walFileReference)
						.build();
					this.enginePersistenceService.storeEngineState(newState);
					this.engineState.set(newState);
					// finally, notify the change observer about the new version
					this.changeObserver.notifyVersionPresentInLiveView(newState.version());
				} catch (RuntimeException ex) {
					// forget about the mutation in case of error
					this.changeObserver.forgetMutationsAfter(theEngineState.version());
					log.error("EvitaDB failed to apply engine level mutation: {}", engineMutation, ex);
				}
			}
		} catch (InterruptedException e) {
			// do nothing
			Thread.currentThread().interrupt();
		} finally {
			if (this.engineStateLock.isHeldByCurrentThread()) {
				this.engineStateLock.unlock();
			}
		}
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
			throw new ReadOnlyException();
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
			throw new ReadOnlyException();
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

	@Override
	@Nonnull
	public Stream<EngineMutation> getCommittedMutationStream(long catalogVersion) {
		return this.enginePersistenceService.getCommittedMutationStream(catalogVersion);
	}

	@Nonnull
	@Override
	public Stream<EngineMutation> getReversedCommittedMutationStream(@Nullable Long catalogVersion) {
		return this.enginePersistenceService.getReversedCommittedMutationStream(catalogVersion);
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
	 * Loads catalog from the designated directory. If the catalog is corrupted, it will be marked as such, but it'll
	 * still be added to the list of catalogs.
	 *
	 * @param catalogName name of the catalog
	 */
	@Nonnull
	ServerTask<EmptySettings, Void> createLoadCatalogTask(@Nonnull String catalogName) {
		return new ClientRunnableTask<>(
			catalogName,
			"LoadCatalogTask",
			"Loading catalog " + catalogName + " from disk...",
			EmptySettings.INSTANCE,
			() -> {
				final long start = System.nanoTime();
				final Catalog theCatalog = new Catalog(
					catalogName,
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
				log.info(
					"Catalog {} fully loaded in: {}", catalogName, StringUtils.formatNano(System.nanoTime() - start));
				// this will be one day used in more clever way, when entire catalog loading will be split into
				// multiple smaller tasks and done asynchronously after the startup (along with catalog loading / unloading feature)
				theCatalog.processWriteAheadLog(
					updatedCatalog -> {
						this.catalogs.put(catalogName, updatedCatalog);
						if (updatedCatalog instanceof Catalog theUpdatedCatalog) {
							theUpdatedCatalog.notifyCatalogPresentInLiveView();
						}
					}
				);
				this.emitCatalogStatistics(catalogName);
			},
			exception -> {
				log.error("Catalog {} is corrupted!", catalogName, exception);
				this.catalogs.put(
					catalogName,
					new CorruptedCatalog(
						catalogName,
						this.configuration.storage().storageDirectory().resolve(catalogName),
						exception
					)
				);
				this.emitEvitaStatistics();
			}
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
			throw new ReadOnlyException();
		}
	}

	/**
	 * Activates the specified catalog based on its current state. Throws appropriate exceptions
	 * for inactive or corrupted catalogs or any unknown catalog type.
	 *
	 * @param makeCatalogAliveMutation The mutation object containing the catalog name to be activated.
	 *                      Must be non-null and must reference a valid catalog.
	 */
	private void makeCatalogAliveInternal(@Nonnull MakeCatalogAliveMutation makeCatalogAliveMutation) {
		final CatalogContract catalog = this.catalogs.get(makeCatalogAliveMutation.getCatalogName());
		if (catalog instanceof Catalog theCatalog) {
			theCatalog.goLive();
		} else if (catalog instanceof InactiveCatalog inactiveCatalog) {
			throw new CatalogInactiveException(inactiveCatalog);
		} else if (catalog instanceof CorruptedCatalog corruptedCatalog) {
			throw new CatalogCorruptedException(corruptedCatalog);
		} else {
			throw new EvitaInvalidUsageException(
				"Unknown catalog type: `" + catalog.getClass() + "`!");
		}
	}

	/*
		PRIVATE METHODS
	*/

	/**
	 * Creates new catalog in the evitaDB.
	 */
	private void createCatalogInternal(@Nonnull CreateCatalogSchemaMutation createCatalogSchema) {
		final String catalogName = createCatalogSchema.getCatalogName();
		final CatalogSchemaContract catalogSchema = Objects.requireNonNull(createCatalogSchema.mutate(null))
		                                                   .updatedCatalogSchema();

		this.catalogs.compute(
			catalogName,
			(theCatalogName, existingCatalog) -> {
				if (existingCatalog == null) {
					// check the names in all naming conventions are unique in the entity schema
					this.catalogs.values()
					             .stream()
					             .flatMap(it -> {
						             final Stream<Entry<NamingConvention, String>> nameStream;
						             if (it instanceof CorruptedCatalog) {
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
				} else {
					throw new CatalogAlreadyPresentException(catalogName, existingCatalog.getName());
				}
			}
		);
	}

	/**
	 * Renames existing catalog in evitaDB.
	 */
	private void renameCatalogInternal(@Nonnull ModifyCatalogSchemaNameMutation modifyCatalogSchemaName) {
		final String currentName = modifyCatalogSchemaName.getCatalogName();
		final String newName = modifyCatalogSchemaName.getNewCatalogName();
		isTrue(!this.catalogs.containsKey(newName), () -> new CatalogAlreadyPresentException(newName, newName));
		final CatalogContract catalogToBeRenamed = getCatalogInstanceOrThrowException(currentName);
		doReplaceCatalogInternal(modifyCatalogSchemaName, newName, currentName, catalogToBeRenamed, catalogToBeRenamed);
	}

	/**
	 * Replaces existing catalog in evitaDB.
	 */
	private void replaceCatalogInternal(@Nonnull ModifyCatalogSchemaNameMutation modifyCatalogSchemaName) {
		final String catalogNameToBeReplacedWith = modifyCatalogSchemaName.getCatalogName();
		final String catalogNameToBeReplaced = modifyCatalogSchemaName.getNewCatalogName();
		final CatalogContract catalogToBeReplaced = getCatalogInstanceOrThrowException(catalogNameToBeReplaced);
		final CatalogContract catalogToBeReplacedWith = getCatalogInstanceOrThrowException(catalogNameToBeReplacedWith);
		doReplaceCatalogInternal(
			modifyCatalogSchemaName, catalogNameToBeReplaced, catalogNameToBeReplacedWith, catalogToBeReplaced,
			catalogToBeReplacedWith
		);
	}

	/**
	 * Internal shared implementation of catalog replacement used both from rename and replace existing catalog methods.
	 */
	private void doReplaceCatalogInternal(
		@Nonnull ModifyCatalogSchemaNameMutation modifyCatalogSchemaName,
		@Nonnull String catalogNameToBeReplaced,
		@Nonnull String catalogNameToBeReplacedWith,
		@Nonnull CatalogContract catalogToBeReplaced,
		@Nonnull CatalogContract catalogToBeReplacedWith
	) {
		// close all active sessions to the catalog that will replace the original one
		final Optional<SessionRegistry> prevailingCatalogSessionRegistry = ofNullable(
			this.catalogSessionRegistries.get(catalogNameToBeReplacedWith));
		// this will be always empty if catalogToBeReplaced == catalogToBeReplacedWith
		Optional<SessionRegistry> removedCatalogSessionRegistry = ofNullable(
			this.catalogSessionRegistries.get(catalogNameToBeReplaced));

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

		try {
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

			final CatalogContract replacedCatalog = catalogToBeReplacedWith.replace(
				updatedSchemaWrapper.updatedCatalogSchema(),
				catalogToBeReplaced
			);

			this.catalogs.put(catalogNameToBeReplaced, replacedCatalog);
			if (!catalogNameToBeReplacedWith.equals(catalogNameToBeReplaced)) {
				this.catalogs.remove(catalogNameToBeReplacedWith);
				this.catalogSessionRegistries.remove(catalogNameToBeReplacedWith);
			}

			// notify callback that it's now a live snapshot
			((Catalog) replacedCatalog).notifyCatalogPresentInLiveView();

		} catch (RuntimeException ex) {
			// revert session registry swap
			if (removedCatalogSessionRegistry.isPresent()) {
				this.catalogSessionRegistries.put(catalogNameToBeReplaced, removedCatalogSessionRegistry.get());
			} else {
				this.catalogSessionRegistries.remove(catalogNameToBeReplaced);
			}
			// in case of exception return the original catalog to be replaced back
			if (catalogToBeReplaced.isTerminated()) {
				this.serviceExecutor.submit(createLoadCatalogTask(catalogNameToBeReplaced)).join();
			} else {
				this.catalogs.put(catalogNameToBeReplaced, catalogToBeReplaced);
			}
			throw ex;
		} finally {
			// we can resume suspended operations on catalogs
			prevailingCatalogSessionRegistry.ifPresent(SessionRegistry::resumeOperations);
			removedCatalogSessionRegistry.ifPresent(SessionRegistry::resumeOperations);
		}
	}

	/**
	 * Removes existing catalog in evitaDB.
	 */
	private void removeCatalogInternal(@Nonnull RemoveCatalogSchemaMutation removeCatalogSchema) {
		final String catalogName = removeCatalogSchema.getCatalogName();
		ofNullable(this.catalogSessionRegistries.get(catalogName))
			.ifPresent(it -> it.closeAllActiveSessionsAndSuspend(SuspendOperation.REJECT));
		final CatalogContract catalogToRemove = this.catalogs.remove(catalogName);
		this.catalogSessionRegistries.remove(catalogName);
		if (catalogToRemove == null) {
			throw new CatalogNotFoundException(catalogName);
		} else {
			catalogToRemove.terminateAndDelete();
		}
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
				if (catalogContract instanceof CorruptedCatalog corruptedCatalog) {
					throw new CatalogCorruptedException(corruptedCatalog);
				}
				return createSessionNewRegistry(sessionTraits);
			}
		);

		final EvitaInternalSessionContract newSession = catalogSessionRegistry.createSession(
			sessionRegistry -> {
				if (this.readOnly) {
					isTrue(!sessionTraits.isReadWrite() || sessionTraits.isDryRun(), ReadOnlyException::new);
				}

				final EvitaSessionTerminationCallback terminationCallback =
					session -> sessionRegistry.removeSession((EvitaSession) session);

				final Catalog catalog = sessionRegistry.getCatalog();
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
