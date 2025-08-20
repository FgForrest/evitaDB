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
import io.evitadb.api.SessionTraits.SessionFlags;
import io.evitadb.api.TransactionContract.CommitBehavior;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.exception.CatalogAlreadyPresentException;
import io.evitadb.api.exception.CatalogGoingLiveException;
import io.evitadb.api.exception.CatalogNotFoundException;
import io.evitadb.api.exception.InstanceTerminatedException;
import io.evitadb.api.exception.ReadOnlyException;
import io.evitadb.api.observability.trace.TracingContext;
import io.evitadb.api.observability.trace.TracingContextProvider;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaEditor.CatalogSchemaBuilder;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalCatalogSchemaBuilder;
import io.evitadb.api.requestResponse.schema.mutation.CatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.CatalogSchemaMutation.CatalogSchemaWithImpactOnEntitySchemas;
import io.evitadb.api.requestResponse.schema.mutation.TopLevelCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.CreateCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyCatalogSchemaNameMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.RemoveCatalogSchemaMutation;
import io.evitadb.api.task.ServerTask;
import io.evitadb.core.async.ClientRunnableTask;
import io.evitadb.core.async.EmptySettings;
import io.evitadb.core.async.ObservableExecutorServiceWithHardDeadline;
import io.evitadb.core.async.ObservableThreadExecutor;
import io.evitadb.core.async.Scheduler;
import io.evitadb.core.async.SessionKiller;
import io.evitadb.core.cache.CacheSupervisor;
import io.evitadb.core.cache.HeapMemoryCacheSupervisor;
import io.evitadb.core.cache.NoCacheSupervisor;
import io.evitadb.core.exception.CatalogCorruptedException;
import io.evitadb.core.metric.event.storage.CatalogStatisticsEvent;
import io.evitadb.core.metric.event.system.EvitaStatisticsEvent;
import io.evitadb.core.metric.event.system.RequestForkJoinPoolStatisticsEvent;
import io.evitadb.core.metric.event.system.ScheduledExecutorStatisticsEvent;
import io.evitadb.core.metric.event.system.TransactionForkJoinPoolStatisticsEvent;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.FileUtils;
import io.evitadb.utils.FolderLock;
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
import java.nio.file.Path;
import java.util.*;
import java.util.Map.Entry;
import java.util.ServiceLoader.Provider;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
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
	 * Field contains the global - shared configuration for entire Evita instance.
	 */
	@Getter private final EvitaConfiguration configuration;
	/**
	 * Reflection lookup is used to speed up reflection operation by memoizing the results for examined classes.
	 */
	private final ReflectionLookup reflectionLookup;
	/**
	 * Contains list of all structural change callbacks that needs to be notified when any of the key structural
	 * changes occur in this catalog.
	 */
	private final List<CatalogStructuralChangeObserver> structuralChangeObservers;
	/**
	 * Executor service that handles all requests to the Evita instance.
	 */
	@Getter
	private final ObservableExecutorServiceWithHardDeadline requestExecutor;
	/**
	 * Executor service that handles transaction handling, once transaction gets committed.
	 */
	@Getter
	private final ObservableExecutorServiceWithHardDeadline transactionExecutor;
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
	 * Temporary storage that keeps catalog being removed reference so that onDelete callback can still access it.
	 */
	private final ThreadLocal<CatalogContract> removedCatalog = new ThreadLocal<>();
	/**
	 * Provides the tracing context for tracking the execution flow in the application.
	 **/
	private final TracingContext tracingContext;
	/**
	 * Flag that is se to TRUE when Evita. is ready to serve application calls.
	 * Aim of this flag is to refuse any calls after {@link #close()} method has been called.
	 */
	@Getter private boolean active;
	/**
	 * The folder lock instance that is used for safeguarding exclusive access to the catalog storage directory.
	 */
	private final FolderLock folderLock;
	/**
	 * Flag that is initially set to {@link ServerOptions#readOnly()} from {@link EvitaConfiguration}.
	 * The flag might be changed from false to TRUE one time using internal Evita API. This is used in test support.
	 */
	@Getter private boolean readOnly;
	/**
	 * Last observed steal count of the request executor.
	 */
	private long requestExecutorSteals;
	/**
	 * Last observed steal count of the transactional executor.
	 */
	private long transactionalExecutorSteals;
	/**
	 * Last observed completed task count of the scheduler.
	 */
	private long schedulerCompletedTasks;

	/**
	 * Shuts down passed executor service in a safe manner.
	 *
	 * @param name            name of the executor service
	 * @param executorService executor service to be shut down
	 * @param waitSeconds     number of seconds to wait for the executor service to shut down
	 */
	private static void shutdownScheduler(@Nonnull String name, @Nonnull ExecutorService executorService, int waitSeconds) {
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

		// try to acquire lock over storage directory
		this.folderLock = new FolderLock(configuration.storage().storageDirectory());

		this.serviceExecutor = new Scheduler(
			configuration.server().serviceThreadPool()
		);
		this.requestExecutor = new ObservableThreadExecutor(
			"request", configuration.server().requestThreadPool(),
			this.serviceExecutor,
			configuration.server().queryTimeoutInMilliseconds()
		);
		this.transactionExecutor = new ObservableThreadExecutor(
			"transaction", configuration.server().transactionThreadPool(),
			this.serviceExecutor, configuration.server().transactionTimeoutInMilliseconds()
		);

		this.sessionKiller = of(configuration.server().closeSessionsAfterSecondsOfInactivity())
			.filter(it -> it > 0)
			.map(it -> new SessionKiller(it, this, this.serviceExecutor))
			.orElse(null);
		this.cacheSupervisor = configuration.cache().enabled() ?
			new HeapMemoryCacheSupervisor(configuration.cache(), this.serviceExecutor) : NoCacheSupervisor.INSTANCE;
		this.reflectionLookup = new ReflectionLookup(configuration.cache().reflection());
		this.structuralChangeObservers = ServiceLoader.load(CatalogStructuralChangeObserver.class)
			.stream()
			.map(Provider::get)
			.collect(
				Collectors.toCollection(
					CopyOnWriteArrayList::new
				)
			);

		this.tracingContext = TracingContextProvider.getContext();
		final Path[] directories = FileUtils.listDirectories(configuration.storage().storageDirectory());
		this.catalogs = CollectionUtils.createConcurrentHashMap(directories.length);
		this.management = new EvitaManagement(this);

		try {
			CompletableFuture.allOf(
				Arrays.stream(directories)
					.map(dir -> createLoadCatalogTask(dir.toFile().getName()))
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
		this.structuralChangeObservers
			.stream()
			.filter(CatalogStructuralChangeObserverWithEvitaContractCallback.class::isInstance)
			.map(CatalogStructuralChangeObserverWithEvitaContractCallback.class::cast)
			.forEach(it -> it.onInit(this));
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
			this::emitRequestForkJoinPoolStatistics
		);
		FlightRecorder.addPeriodicEvent(
			TransactionForkJoinPoolStatisticsEvent.class,
			this::emitTransactionalForkJoinPoolStatistics
		);
		FlightRecorder.addPeriodicEvent(
			ScheduledExecutorStatisticsEvent.class,
			this::emitScheduledForkJoinPoolStatistics
		);
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
	 * Emits statistics of the ThreadPool associated with the scheduler.
	 */
	private void emitScheduledForkJoinPoolStatistics() {
		final ScheduledThreadPoolExecutor tp = this.serviceExecutor.getExecutorServiceInternal();
		final long currentlyCompleted = tp.getCompletedTaskCount();
		new ScheduledExecutorStatisticsEvent(
			currentlyCompleted - this.schedulerCompletedTasks,
			tp.getActiveCount(),
			tp.getQueue().size(),
			tp.getQueue().remainingCapacity(),
			tp.getPoolSize(),
			tp.getCorePoolSize(),
			tp.getMaximumPoolSize()
		).commit();
		this.schedulerCompletedTasks = currentlyCompleted;
	}

	/**
	 * Emits statistics of the ForkJoinPool associated with the request executor.
	 */
	private void emitRequestForkJoinPoolStatistics() {
		final ForkJoinPool fj = ((ObservableThreadExecutor) this.requestExecutor).getForkJoinPoolInternal();
		final long currentStealCount = fj.getStealCount();
		new RequestForkJoinPoolStatisticsEvent(
			currentStealCount - this.requestExecutorSteals,
			fj.getQueuedTaskCount(),
			fj.getActiveThreadCount(),
			fj.getRunningThreadCount()
		).commit();
		this.requestExecutorSteals = currentStealCount;
	}

	/**
	 * Emits statistics of the ForkJoinPool associated with the transactional executor.
	 */
	private void emitTransactionalForkJoinPoolStatistics() {
		final ForkJoinPool fj = ((ObservableThreadExecutor) this.transactionExecutor).getForkJoinPoolInternal();
		final long currentStealCount = fj.getStealCount();
		new RequestForkJoinPoolStatisticsEvent(
			currentStealCount - this.transactionalExecutorSteals,
			fj.getQueuedTaskCount(),
			fj.getActiveThreadCount(),
			fj.getRunningThreadCount()
		).commit();
		this.transactionalExecutorSteals = currentStealCount;
	}

	/**
	 * Method for internal use. Can switch Evita from read-write to read-only one time only.
	 */
	public void setReadOnly() {
		Assert.isTrue(!this.readOnly, "Only read-write evita can be switched to read-only instance!");
		this.readOnly = true;
	}

	/**
	 * Method adds a new observer that will be called in case there are structural changes in the catalogs in this
	 * evitaDB instance. Structural change is:
	 *
	 * - adding a catalog
	 * - removing a catalog
	 * - change schema of the catalog
	 *
	 * Part of PRIVATE API.
	 *
	 * @see #notifyStructuralChangeObservers(CatalogContract, CatalogContract)
	 */
	public void registerStructuralChangeObserver(@Nonnull CatalogStructuralChangeObserver observer) {
		this.structuralChangeObservers.add(observer);
		if (observer instanceof CatalogStructuralChangeObserverWithEvitaContractCallback cscowecc) {
			cscowecc.onInit(this);
		}
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
		return Optional.ofNullable(this.catalogs.get(catalogName))
			.map(it -> it instanceof CorruptedCatalog ? CatalogState.CORRUPTED : it.getCatalogState());
	}

	@Override
	@Nonnull
	public CatalogSchemaBuilder defineCatalog(@Nonnull String catalogName) {
		final Optional<CatalogContract> catalogInstance = getCatalogInstance(catalogName);
		if (catalogInstance.isEmpty()) {
			update(new CreateCatalogSchemaMutation(catalogName));
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
	public void renameCatalog(@Nonnull String catalogName, @Nonnull String newCatalogName) {
		assertActive();
		update(new ModifyCatalogSchemaNameMutation(catalogName, newCatalogName, false));
	}

	@Override
	public void replaceCatalog(@Nonnull String catalogNameToBeReplacedWith, @Nonnull String catalogNameToBeReplaced) {
		assertActive();
		update(new ModifyCatalogSchemaNameMutation(catalogNameToBeReplacedWith, catalogNameToBeReplaced, true));
	}

	@Override
	public boolean deleteCatalogIfExists(@Nonnull String catalogName) {
		final CatalogContract catalogToRemove = this.catalogs.get(catalogName);
		if (catalogToRemove == null) {
			return false;
		} else {
			update(new RemoveCatalogSchemaMutation(catalogName));
			emitEvitaStatistics();
			return true;
		}
	}

	@Override
	public void update(@Nonnull TopLevelCatalogSchemaMutation... catalogMutations) {
		assertActiveAndWritable();
		// TOBEDONE JNO #502 - we have to have a special WAL for the evitaDB server instance as well
		for (CatalogSchemaMutation catalogMutation : catalogMutations) {
			if (catalogMutation instanceof CreateCatalogSchemaMutation createCatalogSchema) {
				createCatalogInternal(createCatalogSchema);
			} else if (catalogMutation instanceof ModifyCatalogSchemaNameMutation modifyCatalogSchemaName) {
				if (modifyCatalogSchemaName.isOverwriteTarget() && this.catalogs.containsKey(modifyCatalogSchemaName.getNewCatalogName())) {
					replaceCatalogInternal(modifyCatalogSchemaName);
				} else {
					renameCatalogInternal(modifyCatalogSchemaName);
				}
			} else if (catalogMutation instanceof ModifyCatalogSchemaMutation modifyCatalogSchema) {
				updateCatalog(
					modifyCatalogSchema.getCatalogName(),
					session -> {
						session.updateCatalogSchema(modifyCatalogSchema.getSchemaMutations());
					},
					SessionFlags.READ_WRITE
				);
			} else if (catalogMutation instanceof RemoveCatalogSchemaMutation removeCatalogSchema) {
				removeCatalogInternal(removeCatalogSchema);
			} else {
				throw new EvitaInvalidUsageException("Unknown catalog mutation: `" + catalogMutation.getClass() + "`!");
			}
		}
	}

	@Override
	public <T> T queryCatalog(@Nonnull String catalogName, @Nonnull Function<EvitaSessionContract, T> queryLogic, @Nullable SessionFlags... flags) {
		assertActive();
		try (final EvitaSessionContract session = this.createSession(new SessionTraits(catalogName, flags))) {
			return queryLogic.apply(session);
		}
	}

	@Override
	public void queryCatalog(@Nonnull String catalogName, @Nonnull Consumer<EvitaSessionContract> queryLogic, @Nullable SessionFlags... flags) {
		assertActive();
		try (final EvitaSessionContract session = this.createSession(new SessionTraits(catalogName, flags))) {
			queryLogic.accept(session);
		}
	}

	@Nonnull
	@Override
	public <T> CompletableFuture<T> queryCatalogAsync(@Nonnull String catalogName, @Nonnull Function<EvitaSessionContract, T> queryLogic, @Nullable SessionFlags... flags) {
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
		return ofNullable(this.catalogs.get(catalog))
			.or(() -> Optional.ofNullable(this.removedCatalog.get()));
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
	 * @param supplier supplier to be executed
	 * @return future with result of the supplier
	 * @param <T> type of the result
	 */
	@Nonnull
	public <T> CompletionStage<T> executeAsyncInRequestThreadPool(@Nonnull Supplier<T> supplier) {
		return CompletableFuture.supplyAsync(supplier, this.requestExecutor);
	}

	/**
	 * Asynchronously executes supplier lambda in the transactional thread pool.
	 * @param supplier supplier to be executed
	 * @return future with result of the supplier
	 * @param <T> type of the result
	 */
	@Nonnull
	public <T> CompletableFuture<T> executeAsyncInTransactionThreadPool(@Nonnull Supplier<T> supplier) {
		return CompletableFuture.supplyAsync(supplier, this.transactionExecutor);
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
				log.info("Catalog {} fully loaded in: {}", catalogName, StringUtils.formatNano(System.nanoTime() - start));
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
								.map(name -> new CatalogNameInConvention(it.getName(), name.getKey(), name.getValue()));
						})
						.filter(nameVariant -> nameVariant.name().equals(catalogSchema.getNameVariant(nameVariant.convention())))
						.map(nameVariant -> new CatalogNamingConventionConflict(nameVariant.catalogName(), nameVariant.convention(), nameVariant.name()))
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
		this.structuralChangeObservers.forEach(it -> it.onCatalogCreate(catalogName));
		emitEvitaStatistics();
		emitCatalogStatistics(catalogName);
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
		doReplaceCatalogInternal(modifyCatalogSchemaName, catalogNameToBeReplaced, catalogNameToBeReplacedWith, catalogToBeReplaced, catalogToBeReplacedWith);
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
		final Optional<SessionRegistry> prevailingCatalogSessionRegistry = ofNullable(this.catalogSessionRegistries.get(catalogNameToBeReplacedWith));
		// this will be always empty if catalogToBeReplaced == catalogToBeReplacedWith
		Optional<SessionRegistry> removedCatalogSessionRegistry = ofNullable(this.catalogSessionRegistries.get(catalogNameToBeReplaced));

		prevailingCatalogSessionRegistry
			.ifPresent(sessionRegistry -> {
				sessionRegistry.closeAllActiveSessionsAndSuspend(SuspendOperation.POSTPONE);
				// immediately register the session registry under the new name to start accepting new sessions
				// session creation will be postponed until the catalog is fully available
				this.catalogSessionRegistries.put(
					catalogNameToBeReplaced,
					sessionRegistry.withDifferentCatalogSupplier(() -> (Catalog) this.catalogs.get(catalogNameToBeReplaced))
				);
			});

		try {
			// first terminate the catalog that is being replaced (unless it's the very same catalog)
			if (catalogToBeReplaced != catalogToBeReplacedWith) {
				removedCatalogSessionRegistry.ifPresent(it -> it.closeAllActiveSessionsAndSuspend(SuspendOperation.REJECT));
				catalogToBeReplaced.terminate();
			} else {
				Assert.isPremiseValid(removedCatalogSessionRegistry.isEmpty(), "Expectation failed!");
			}

			final CatalogSchemaWithImpactOnEntitySchemas updatedSchemaWrapper = modifyCatalogSchemaName.mutate(catalogToBeReplacedWith.getSchema());
			Assert.isPremiseValid(
				updatedSchemaWrapper != null,
				"Result of modify catalog schema mutation must not be null."
			);

			final CatalogContract replacedCatalog = catalogToBeReplacedWith.replace(
				updatedSchemaWrapper.updatedCatalogSchema(),
				catalogToBeReplaced
			);
			// now rewrite the original catalog with renamed contents so that the observers could access it
			final CatalogContract previousCatalog = this.catalogs.put(catalogNameToBeReplaced, replacedCatalog);

			this.structuralChangeObservers.forEach(it -> it.onCatalogDelete(catalogNameToBeReplacedWith));
			if (previousCatalog == null) {
				this.structuralChangeObservers.forEach(it -> it.onCatalogCreate(catalogNameToBeReplaced));
			} else {
				this.structuralChangeObservers.forEach(it -> it.onCatalogSchemaUpdate(catalogNameToBeReplaced));
			}

			// now remove the catalog that was renamed to, we need observers to be still able to access it and therefore
			// and therefore the removal only takes place here
			final CatalogContract removedCatalog = this.catalogs.remove(catalogNameToBeReplacedWith);
			this.catalogSessionRegistries.remove(catalogNameToBeReplacedWith);

			// notify callback that it's now a live snapshot
			((Catalog) replacedCatalog).notifyCatalogPresentInLiveView();

			if (removedCatalog instanceof Catalog theCatalog) {
				theCatalog.emitDeleteObservabilityEvents();
			}

			// we need to update catalog statistics
			emitEvitaStatistics();
			emitCatalogStatistics(catalogNameToBeReplaced);

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
			this.structuralChangeObservers.forEach(it -> doWithPretendingCatalogStillPresent(catalogToRemove, () -> it.onCatalogDelete(catalogName)));
			catalogToRemove.terminateAndDelete();
			// we need to update catalog statistics
			emitEvitaStatistics();
			if (catalogToRemove instanceof Catalog theCatalog) {
				theCatalog.emitDeleteObservabilityEvents();
			}
		}
	}

	/**
	 * Replaces current catalog reference with updated one.
	 */
	private void replaceCatalogReference(@Nonnull Catalog catalog) {
		notNull(catalog, "Sanity check.");
		final String catalogName = catalog.getName();
		// catalog indexes are ConcurrentHashMap - we can do it safely here
		final AtomicReference<CatalogContract> originalCatalog = new AtomicReference<>();
		this.catalogs.computeIfPresent(
			catalogName, (cName, currentCatalog) -> {
				// replace catalog only when reference/pointer differs
				if (currentCatalog != catalog && currentCatalog.getVersion() < catalog.getVersion()) {
					originalCatalog.set(currentCatalog);
					return catalog;
				} else {
					return currentCatalog;
				}
			}
		);

		// discard suspension of the session registry for the catalog, if present
		discardSuspension(catalogName);

		// notify structural changes callbacks
		ofNullable(originalCatalog.get())
			.ifPresent(it -> notifyStructuralChangeObservers(catalog, it));

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
	 * Method will examine changes in `newCatalog` compared to `currentCatalog` and notify {@link #structuralChangeObservers}
	 * in case there is any key structural change identified.
	 */
	private void notifyStructuralChangeObservers(@Nonnull CatalogContract newCatalog, @Nonnull CatalogContract currentCatalog) {
		final String catalogName = newCatalog.getName();
		if (currentCatalog.getSchema().version() != newCatalog.getSchema().version()) {
			this.structuralChangeObservers.forEach(it -> it.onCatalogSchemaUpdate(catalogName));
		}
		// and examine catalog entity collection changes
		final Set<String> currentEntityTypes = currentCatalog.getEntityTypes();
		final Set<String> examinedTypes = CollectionUtils.createHashSet(currentEntityTypes.size());
		for (String entityType : currentEntityTypes) {
			examinedTypes.add(entityType);
			final EntityCollectionContract existingCollection = currentCatalog.getCollectionForEntityOrThrowException(entityType);
			final Optional<EntityCollectionContract> updatedCollection = newCatalog.getCollectionForEntity(entityType);
			if (updatedCollection.isEmpty()) {
				this.structuralChangeObservers.forEach(it -> it.onEntityCollectionDelete(catalogName, entityType));
			} else if (existingCollection.getSchema().version() != updatedCollection.get().getSchema().version()) {
				this.structuralChangeObservers.forEach(it -> it.onEntitySchemaUpdate(catalogName, entityType));
			}
		}
		for (String entityType : newCatalog.getEntityTypes()) {
			if (!examinedTypes.contains(entityType)) {
				this.structuralChangeObservers.forEach(it -> it.onEntityCollectionCreate(catalogName, entityType));
			}
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
				final Catalog catalog = sessionRegistry.getCatalog();

				if (catalog.isGoingLive()) {
					throw new CatalogGoingLiveException(catalog.getName());
				}

				final NonTransactionalCatalogDescriptor nonTransactionalCatalogDescriptor =
					catalog.getCatalogState() == CatalogState.WARMING_UP && sessionTraits.isReadWrite() && !sessionTraits.isDryRun() ?
						new NonTransactionalCatalogDescriptor(catalog, this.structuralChangeObservers) : null;

				if (this.readOnly) {
					isTrue(!sessionTraits.isReadWrite() || sessionTraits.isDryRun(), ReadOnlyException::new);
				}

				final EvitaSessionTerminationCallback terminationCallback = session -> {
					sessionRegistry.removeSession((EvitaSession) session);

					if (sessionTraits.isReadWrite()) {
						ofNullable(nonTransactionalCatalogDescriptor)
							.ifPresent(NonTransactionalCatalogDescriptor::notifyStructuralChangeObservers);
					}
				};

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
	 * Method will temporarily make catalog available to be found even if it's not present in {@link #catalogs} anymore.
	 */
	private void doWithPretendingCatalogStillPresent(@Nonnull CatalogContract catalog, @Nonnull Runnable runnable) {
		try {
			this.removedCatalog.set(catalog);
			runnable.run();
		} finally {
			this.removedCatalog.remove();
		}
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
		try {
			// first close all sessions
			this.closeAllSessions();

			CompletableFuture.allOf(
				CompletableFuture.runAsync(this.management::close),
				CompletableFuture.runAsync(() -> shutdownScheduler("request", this.requestExecutor, 60)),
				CompletableFuture.runAsync(() -> shutdownScheduler("transaction", this.transactionExecutor, 60)),
				CompletableFuture.runAsync(() -> shutdownScheduler("service", this.serviceExecutor, 60))
			).join();

			// terminate all catalogs finally (if we did this prematurely, many exceptions would occur)
			CompletableFuture.allOf(
				this.catalogs.values()
					.stream()
					.map(catalog -> CompletableFuture.runAsync(catalog::terminate))
					.toArray(CompletableFuture[]::new)
			).join();
		} catch (RuntimeException ex) {
			log.error("Failed to close evitaDB. Some resources might not have been released properly.", ex);
		} finally {
			// clear map
			this.catalogs.clear();
			// release lock
			IOUtils.closeQuietly(this.folderLock::close);
		}
	}

	/**
	 * This descriptor allows to recognize collection and schema modifications in non-transactional mode where the
	 * contents of the original catalog are directly modified.
	 */
	private static class NonTransactionalCatalogDescriptor {
		/**
		 * Reference to the catalog.
		 */
		private final CatalogContract theCatalog;
		/**
		 * Contains list of all structural change callbacks that needs to be notified when any of the key structural
		 * changes occur in this catalog.
		 */
		private final Collection<CatalogStructuralChangeObserver> structuralChangeObservers;
		/**
		 * Contains observed version of the catalog schema in time this class is instantiated.
		 */
		private final int catalogSchemaVersion;
		/**
		 * Contains observed versions of the catalog entity collection schemas in time this class is instantiated.
		 */
		private final Map<String, Integer> entityCollectionSchemaVersions;

		NonTransactionalCatalogDescriptor(
			@Nonnull CatalogContract catalog,
			@Nonnull Collection<CatalogStructuralChangeObserver> structuralChangeObservers
		) {
			this.theCatalog = catalog;
			this.structuralChangeObservers = structuralChangeObservers;
			this.catalogSchemaVersion = catalog.getSchema().version();
			final Set<String> entityTypes = catalog.getEntityTypes();
			this.entityCollectionSchemaVersions = CollectionUtils.createHashMap(entityTypes.size());
			for (String entityType : entityTypes) {
				catalog.getEntitySchema(entityType)
					.ifPresent(it -> this.entityCollectionSchemaVersions.put(entityType, it.version()));
			}
		}

		/**
		 * Method will examine changes in `newCatalog` compared to `currentCatalog` and notify {@link #structuralChangeObservers}
		 * in case there is any key structural change identified.
		 */
		void notifyStructuralChangeObservers() {
			final String catalogName = this.theCatalog.getName();
			if (isCatalogSchemaModified(this.theCatalog)) {
				this.structuralChangeObservers.forEach(it -> it.onCatalogSchemaUpdate(catalogName));
			}
			// and examine catalog entity collection changes
			this.entityCollectionSchemaVersions
				.keySet()
				.stream()
				.filter(it -> isEntityCollectionSchemaModified(this.theCatalog, it))
				.forEach(it -> this.structuralChangeObservers
					.forEach(observer -> observer.onEntitySchemaUpdate(catalogName, it))
				);
			getCreatedCollections(this.theCatalog)
				.forEach(it -> this.structuralChangeObservers
					.forEach(observer -> observer.onEntityCollectionCreate(catalogName, it))
				);
			getDeletedCollections(this.theCatalog)
				.forEach(it -> this.structuralChangeObservers
					.forEach(observer -> observer.onEntityCollectionDelete(catalogName, it))
				);
		}

		/**
		 * Returns true if passed catalog schema version differs from version originally observed.
		 */
		private boolean isCatalogSchemaModified(@Nonnull CatalogContract catalog) {
			return this.catalogSchemaVersion != catalog.getSchema().version();
		}

		/**
		 * Returns true if passed catalog entity collection schema version differs from version originally observed.
		 */
		private boolean isEntityCollectionSchemaModified(@Nonnull CatalogContract catalog, @Nonnull String entityType) {
			final Integer myVersion = this.entityCollectionSchemaVersions.get(entityType);
			final Integer theirVersion = catalog.getCollectionForEntity(entityType)
				.map(EntityCollectionContract::getSchema)
				.map(EntitySchemaContract::version)
				.orElse(null);
			return !Objects.equals(myVersion, theirVersion) && myVersion != null;
		}

		/**
		 * Returns stream of entity types, that are available in catalog, but was not observed initially.
		 */
		private Stream<String> getCreatedCollections(@Nonnull CatalogContract catalog) {
			return catalog.getEntityTypes()
				.stream()
				.filter(it -> !this.entityCollectionSchemaVersions.containsKey(it));
		}

		/**
		 * Returns stream of entity types, that were observed initially, but are not present in current catalog.
		 */
		private Stream<String> getDeletedCollections(@Nonnull CatalogContract catalog) {
			final Set<String> newEntityTypes = catalog.getEntityTypes();
			return this.entityCollectionSchemaVersions
				.keySet()
				.stream()
				.filter(it -> !newEntityTypes.contains(it));
		}

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
	 * @param session     reference to the created session itself
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
