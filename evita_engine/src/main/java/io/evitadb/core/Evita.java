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
import io.evitadb.api.requestResponse.cdc.CaptureArea;
import io.evitadb.api.requestResponse.cdc.ChangeCapturePublisher;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCapture;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCaptureRequest;
import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaEditor.CatalogSchemaBuilder;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalCatalogSchemaBuilder;
import io.evitadb.api.requestResponse.schema.mutation.CatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.TopLevelCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.CreateCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyCatalogSchemaNameMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.RemoveCatalogSchemaMutation;
import io.evitadb.api.task.ServerTask;
import io.evitadb.core.async.ClientRunnableTask;
import io.evitadb.core.async.ObservableExecutorService;
import io.evitadb.core.async.ObservableThreadExecutor;
import io.evitadb.core.async.Scheduler;
import io.evitadb.core.async.SessionKiller;
import io.evitadb.core.cache.CacheSupervisor;
import io.evitadb.core.cache.HeapMemoryCacheSupervisor;
import io.evitadb.core.cache.NoCacheSupervisor;
import io.evitadb.core.cdc.CatalogChangeCaptureBlock;
import io.evitadb.core.cdc.CatalogChangeObserver;
import io.evitadb.core.cdc.SystemChangeObserver;
import io.evitadb.core.exception.CatalogCorruptedException;
import io.evitadb.core.metric.event.storage.EvitaDBCompositionChangedEvent;
import io.evitadb.core.metric.event.system.EvitaStartedEvent;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.FileUtils;
import io.evitadb.utils.NamingConvention;
import io.evitadb.utils.ReflectionLookup;
import io.evitadb.utils.StringUtils;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Closeable;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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
	 * Keeps information about currently active sessions.
	 */
	private final Map<String, SessionRegistry> activeSessions = CollectionUtils.createConcurrentHashMap(512);
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
	 * Change observer that is used to notify all registered subscribers about changes in the catalogs.
	 */
	private final SystemChangeObserver changeObserver;
	/**
	 * Executor service that handles all requests to the Evita instance.
	 */
	@Getter
	private final ObservableExecutorService requestExecutor;
	/**
	 * Executor service that handles transaction handling, once transaction gets committed.
	 */
	@Getter
	private final ObservableExecutorService transactionExecutor;
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
		this.tracingContext = TracingContextProvider.getContext();
		final Path[] directories = FileUtils.listDirectories(configuration.storage().storageDirectoryOrDefault());
		this.changeObserver = new SystemChangeObserver(getServiceExecutor());
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
	}

	/**
	 * Method for internal use - allows emitting start events when observability facilities are already initialized.
	 * If we didn't postpone this initialization, events would become lost.
	 */
	public void emitStartObservabilityEvents() {
		// emit the event
		new EvitaStartedEvent(this.configuration)
			.commit();

		// emit the statistics event
		updateCatalogStatistics();
	}

	/**
	 * Method for internal use. Can switch Evita from read-write to read-only one time only.
	 */
	public void setReadOnly() {
		Assert.isTrue(!readOnly, "Only read-write evita can be switched to read-only instance!");
		this.readOnly = true;
	}

	/**
	 * Returns list of all catalogs maintained by this evitaDB instance.
	 * Part of PRIVATE API.
	 */
	@Nonnull
	public Collection<CatalogContract> getCatalogs() {
		return catalogs.values();
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
	public Optional<EvitaSessionContract> getSessionById(@Nonnull String catalogName, @Nonnull UUID sessionId) {
		return ofNullable(this.activeSessions.get(catalogName))
			.map(it -> it.getActiveSessionById(sessionId));
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
			updateCatalogStatistics();
			return true;
		}
	}

	@Override
	public void update(@Nonnull TopLevelCatalogSchemaMutation... catalogMutations) {
		assertActive();
		if (readOnly) {
			throw new ReadOnlyException();
		}
		// TOBEDONE JNO #502 - we have to have a special WAL for the evitaDB server instance as well
		for (CatalogSchemaMutation catalogMutation : catalogMutations) {
			if (catalogMutation instanceof CreateCatalogSchemaMutation createCatalogSchema) {
				createCatalogInternal(createCatalogSchema);
			} else if (catalogMutation instanceof ModifyCatalogSchemaNameMutation modifyCatalogSchemaName) {
				if (modifyCatalogSchemaName.isOverwriteTarget() && catalogs.containsKey(modifyCatalogSchemaName.getNewCatalogName())) {
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
	public <T> CompletableFuture<T> updateCatalogAsync(
		@Nonnull String catalogName,
		@Nonnull Function<EvitaSessionContract, T> updater,
		@Nonnull CommitBehavior commitBehaviour,
		@Nullable SessionFlags... flags
	) {
		assertActive();
		if (readOnly && Arrays.stream(flags).noneMatch(it -> it == SessionFlags.DRY_RUN)) {
			throw new ReadOnlyException();
		}
		final SessionTraits traits = new SessionTraits(
			catalogName,
			commitBehaviour,
			flags == null ?
				new SessionFlags[]{SessionFlags.READ_WRITE} :
				ArrayUtils.insertRecordIntoArray(SessionFlags.READ_WRITE, flags, flags.length)
		);
		final CreatedSession createdSession = this.createSessionInternal(traits);
		try {
			final T resultValue = createdSession.session().execute(updater);
			// join the transaction future and return the result
			final CompletableFuture<T> result = new CompletableFuture<>();
			createdSession.closeFuture().whenComplete((txId, ex) -> {
				if (ex != null) {
					result.completeExceptionally(ex);
				} else {
					result.complete(resultValue);
				}
			});
			return result;
		} finally {
			createdSession.session().closeNow(commitBehaviour);
		}
	}

	@Nonnull
	@Override
	public CompletableFuture<Long> updateCatalogAsync(
		@Nonnull String catalogName,
		@Nonnull Consumer<EvitaSessionContract> updater,
		@Nonnull CommitBehavior commitBehaviour,
		@Nullable SessionFlags... flags
	) {
		assertActive();
		if (readOnly && Arrays.stream(flags).noneMatch(it -> it == SessionFlags.DRY_RUN)) {
			throw new ReadOnlyException();
		}
		final SessionTraits traits = new SessionTraits(
			catalogName,
			commitBehaviour,
			flags == null ?
				new SessionFlags[]{SessionFlags.READ_WRITE} :
				ArrayUtils.insertRecordIntoArray(SessionFlags.READ_WRITE, flags, flags.length)
		);

		final CreatedSession createdSession = this.createSessionInternal(traits);
		try {
			final EvitaInternalSessionContract theSession = createdSession.session();
			theSession.execute(updater);
			// join the transaction future and return
			final CompletableFuture<Long> result = new CompletableFuture<>();
			createdSession.closeFuture().whenComplete((txId, ex) -> {
				if (ex != null) {
					result.completeExceptionally(ex);
				} else {
					result.complete(txId);
				}
			});
			return result;
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
		return activeSessions.values().stream().flatMap(SessionRegistry::getActiveSessions);
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
	public Optional<CatalogContract> getCatalogInstance(@Nonnull String catalog) throws IllegalArgumentException {
		return ofNullable(catalogs.get(catalog))
			.or(() -> Optional.ofNullable(removedCatalog.get()));
	}

	/**
	 * Returns catalog instance for passed catalog name or throws exception.
	 *
	 * @throws IllegalArgumentException when no catalog of such name is found
	 */
	@Nonnull
	public CatalogContract getCatalogInstanceOrThrowException(@Nonnull String catalog) throws IllegalArgumentException {
		return getCatalogInstance(catalog)
			.orElseThrow(() -> new IllegalArgumentException("Catalog " + catalog + " is not known to Evita!"));
	}

	/**
	 * Asynchronously executes supplier lambda in the request thread pool.
	 * @param supplier supplier to be executed
	 * @return future with result of the supplier
	 * @param <T> type of the result
	 */
	@Nonnull
	public <T> CompletableFuture<T> executeAsyncInRequestThreadPool(@Nonnull Supplier<T> supplier) {
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
	ServerTask<Void, Void> createLoadCatalogTask(@Nonnull String catalogName) {
		return new ClientRunnableTask<>(
			catalogName,
			"Loading catalog " + catalogName + " from disk...",
			null,
			() -> {
				final long start = System.nanoTime();
				final Catalog theCatalog = new Catalog(
					catalogName,
					this.cacheSupervisor,
					this.configuration,
					this.reflectionLookup,
					this.serviceExecutor,
					this.management.exportFileService(),
					this.transactionExecutor,
					this::replaceCatalogReference,
					this.tracingContext
				);
				log.info("Catalog {} fully loaded in: {}", catalogName, StringUtils.formatNano(System.nanoTime() - start));
				// this will be one day used in more clever way, when entire catalog loading will be split into
				// multiple smaller tasks and done asynchronously after the startup (along with catalog loading / unloading feature)
				theCatalog.processWriteAheadLog(
					updatedCatalog -> this.catalogs.put(catalogName, updatedCatalog)
				);
			},
			exception -> {
				log.error("Catalog {} is corrupted!", catalogName, exception);
				this.catalogs.put(
					catalogName,
					new CorruptedCatalog(
						catalogName,
						configuration.storage().storageDirectoryOrDefault().resolve(catalogName),
						exception
					)
				);
			}
		);
	}

	@Override
	public ChangeCapturePublisher<ChangeSystemCapture> registerSystemChangeCapture(@Nonnull ChangeSystemCaptureRequest request) {
		return changeObserver.registerPublisher(request);
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
						this.transactionExecutor,
						this::replaceCatalogReference,
						this.tracingContext
					);
				} else {
					throw new CatalogAlreadyPresentException(catalogName, existingCatalog.getName());
				}
			}
		);
		changeObserver.notifyPublishers(catalogName, Operation.CREATE, () -> createCatalogSchema);
		updateCatalogStatistics();
	}

	/**
	 * Renames existing catalog in evitaDB.
	 */
	private void renameCatalogInternal(@Nonnull ModifyCatalogSchemaNameMutation modifyCatalogSchemaName) {
		final String currentName = modifyCatalogSchemaName.getCatalogName();
		final String newName = modifyCatalogSchemaName.getNewCatalogName();
		isTrue(!catalogs.containsKey(newName), () -> new CatalogAlreadyPresentException(newName, newName));
		final CatalogContract catalogToBeRenamed = getCatalogInstanceOrThrowException(currentName);

		closeAllActiveSessionsTo(currentName);
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

		closeAllActiveSessionsTo(catalogNameToBeReplaced);
		closeAllActiveSessionsTo(catalogNameToBeReplacedWith);

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
		try {
			final CatalogContract replacedCatalog = catalogToBeReplacedWith.replace(
				modifyCatalogSchemaName.mutate(catalogToBeReplacedWith.getSchema())
					.updatedCatalogSchema(),
				catalogToBeReplaced
			);
			// now rewrite the original catalog with renamed contents so that the observers could access it
			final CatalogContract previousCatalog = this.catalogs.put(catalogNameToBeReplaced, replacedCatalog);

			// notify callback that it's now a live snapshot
			((Catalog) replacedCatalog).notifyCatalogPresentInLiveView();

			changeObserver.notifyPublishers(
				catalogNameToBeReplacedWith, Operation.REMOVE,
				() -> modifyCatalogSchemaName
			);
			if (previousCatalog == null) {
				changeObserver.notifyPublishers(
					catalogNameToBeReplaced, Operation.CREATE,
					() -> modifyCatalogSchemaName
				);
			} else {
				changeObserver.notifyPublishers(
					catalogNameToBeReplaced, Operation.UPDATE, () -> modifyCatalogSchemaName
				);
			}

			// now remove the catalog that was renamed to, we need observers to be still able to access it and therefore
			// and therefore the removal only takes place here
			final CatalogContract removedCatalog = this.catalogs.remove(catalogNameToBeReplacedWith);
			if (removedCatalog instanceof Catalog theCatalog) {
				theCatalog.emitDeleteObservabilityEvents();
			}

			// we need to update catalog statistics
			updateCatalogStatistics();

		} catch (RuntimeException ex) {
			// in case of exception return the original catalog to be replaced back
			this.catalogs.put(catalogNameToBeReplaced, catalogToBeReplaced);
			throw ex;
		}
	}

	/**
	 * Removes existing catalog in evitaDB.
	 */
	private void removeCatalogInternal(@Nonnull RemoveCatalogSchemaMutation removeCatalogSchema) {
		final String catalogName = removeCatalogSchema.getCatalogName();
		closeAllActiveSessionsTo(catalogName);
		final CatalogContract catalogToRemove = this.catalogs.remove(catalogName);
		if (catalogToRemove == null) {
			throw new CatalogNotFoundException(catalogName);
		} else {
			doWithPretendingCatalogStillPresent(
				catalogToRemove,
				() -> changeObserver.notifyPublishers(catalogName, Operation.REMOVE, () -> removeCatalogSchema)
			);
			catalogToRemove.terminate();
			catalogToRemove.delete();
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
		final Iterator<SessionRegistry> sessionRegistryIt = this.activeSessions.values().iterator();
		while (sessionRegistryIt.hasNext()) {
			final SessionRegistry sessionRegistry = sessionRegistryIt.next();
			sessionRegistry.closeAllActiveSessions();
			sessionRegistryIt.remove();
		}
	}

	/**
	 * Method iterates over all opened and active {@link EvitaSession sessions} and closes all that relate to passed
	 * `catalogName`.
	 */
	private void closeAllActiveSessionsTo(@Nonnull String catalogName) {
		ofNullable(this.activeSessions.remove(catalogName))
			.ifPresent(SessionRegistry::closeAllActiveSessions);
	}

	/**
	 * Verifies this instance is still active.
	 */
	void assertActive() {
		if (!active) {
			throw new InstanceTerminatedException("instance");
		}
	}

	/**
	 * Method will examine changes in `newCatalog` compared to `currentCatalog` and notify {@link #changeObserver}
	 * in case there is any key structural change identified.
	 */
	private static void notifyStructuralChangeObservers(@Nonnull CatalogContract newCatalog, @Nonnull CatalogContract currentCatalog) {
		if (newCatalog instanceof Catalog newCatalogInstance) {
			final CatalogChangeCaptureBlock captureBlock = newCatalogInstance.createChangeCaptureBlock();
			final int newCatalogVersion = newCatalog.getSchema().version();
			if (currentCatalog.getSchema().version() != newCatalogVersion) {
				newCatalogInstance.notifyObservers(
					CaptureArea.SCHEMA, Operation.UPDATE,
					null, null, null, newCatalogVersion, null,
					captureBlock
				);
			}
			// and examine catalog entity collection changes
			final Set<String> currentEntityTypes = currentCatalog.getEntityTypes();
			final Set<String> examinedTypes = CollectionUtils.createHashSet(currentEntityTypes.size());
			for (String entityType : currentEntityTypes) {
				examinedTypes.add(entityType);
				final EntityCollectionContract existingCollection = currentCatalog.getCollectionForEntityOrThrowException(entityType);
				final Optional<EntityCollectionContract> updatedCollection = newCatalog.getCollectionForEntity(entityType);
				if (updatedCollection.isEmpty()) {
					newCatalogInstance.notifyObservers(
						CaptureArea.SCHEMA, Operation.REMOVE,
						entityType, existingCollection.getEntityTypePrimaryKey(),
						null, null, null,
						captureBlock
					);
				} else {
					final EntityCollectionContract updatedCollectionRef = updatedCollection.get();
					final int newCollectionVersion = updatedCollectionRef.getSchema().version();
					if (existingCollection.getSchema().version() != newCollectionVersion) {
						newCatalogInstance.notifyObservers(
							CaptureArea.SCHEMA, Operation.UPDATE,
							entityType, updatedCollectionRef.getEntityTypePrimaryKey(),
							null, newCollectionVersion, null,
							captureBlock
						);
					}
				}
			}
			for (String entityType : newCatalog.getEntityTypes()) {
				if (!examinedTypes.contains(entityType)) {
					final EntityCollection createdCollection = newCatalogInstance.getCollectionForEntityOrThrowException(entityType);
					final int newCollectionVersion = createdCollection.getSchema().version();
					newCatalogInstance.notifyObservers(
						CaptureArea.SCHEMA, Operation.CREATE,
						entityType, createdCollection.getEntityTypePrimaryKey(),
						null, newCollectionVersion, null,
						captureBlock
					);
				}
			}
			captureBlock.finish();
		}
	}

	/**
	 * Creates {@link EvitaSession} instance and registers all appropriate termination callbacks along.
	 */
	@Nonnull
	private CreatedSession createSessionInternal(@Nonnull SessionTraits sessionTraits) {
		final CatalogContract catalogContract = getCatalogInstanceOrThrowException(sessionTraits.catalogName());
		final Catalog catalog;
		if (catalogContract instanceof CorruptedCatalog corruptedCatalog) {
			throw new CatalogCorruptedException(corruptedCatalog);
		} else {
			catalog = (Catalog) catalogContract;
		}

		final SessionRegistry sessionRegistry = activeSessions.computeIfAbsent(
			sessionTraits.catalogName(),
			theCatalogName -> new SessionRegistry(tracingContext, () -> (Catalog) this.catalogs.get(sessionTraits.catalogName()))
		);

		final NonTransactionalCatalogDescriptor nonTransactionalCatalogDescriptor =
			catalog.getCatalogState() == CatalogState.WARMING_UP && sessionTraits.isReadWrite() && !sessionTraits.isDryRun() ?
				new NonTransactionalCatalogDescriptor(catalog) : null;

		if (readOnly) {
			isTrue(!sessionTraits.isReadWrite() || sessionTraits.isDryRun(), ReadOnlyException::new);
		}

		final EvitaSessionTerminationCallback terminationCallback = session -> {
			sessionRegistry.removeSession((EvitaSession) session);

			if (sessionTraits.isReadWrite()) {
				ofNullable(nonTransactionalCatalogDescriptor)
					.ifPresent(NonTransactionalCatalogDescriptor::notifyStructuralChangeObservers);
			}
		};

		final EvitaInternalSessionContract newSession = sessionRegistry.addSession(
			catalog.supportsTransaction(),
			() -> new EvitaSession(
				this, catalog, reflectionLookup, terminationCallback, sessionTraits.commitBehaviour(), sessionTraits
			)
		);

		final long catalogVersion = catalogContract.getVersion();
		return new CreatedSession(
			newSession,
			newSession.getTransactionFinalizationFuture().orElseGet(() -> {
				// complete immediately
				final CompletableFuture<Long> result = new CompletableFuture<>();
				result.complete(catalogVersion);
				return result;
			})
		);
	}

	/**
	 * Method will temporarily make catalog available to be found even if it's not present in {@link #catalogs} anymore.
	 */
	private void doWithPretendingCatalogStillPresent(@Nonnull CatalogContract catalog, @Nonnull Runnable runnable) {
		try {
			removedCatalog.set(catalog);
			runnable.run();
		} finally {
			removedCatalog.remove();
		}
	}

	/**
	 * Emits the event about catalog statistics in metrics.
	 */
	private void updateCatalogStatistics() {
		// emit the event
		new EvitaDBCompositionChangedEvent(
			this.catalogs.size(),
			(int) this.catalogs.values()
				.stream()
				.filter(it -> it instanceof CorruptedCatalog)
				.count()
		).commit();

		// iterate over all catalogs and emit the event
		for (CatalogContract catalog : this.catalogs.values()) {
			if (catalog instanceof Catalog theCatalog) {
				theCatalog.emitStartObservabilityEvents();
			}
		}
	}

	/**
	 * Attempts to close all resources of evitaDB.
	 */
	private void closeInternal() {
		// first close all sessions and observers
		closeSessionsAndObservers()
			// then shutdown executors
			.thenCompose(unusedv -> shutdownExecutors())
			// terminate all catalogs finally (if we did this prematurely, many exceptions would occur)
			.thenCompose(unused -> closeCatalogs())
			// wait for the completion
			.join();
	}

	/**
	 * First stage of shut down: closes all sessions and observers (subscriptions).
	 * @return future that completes when all sessions and observers are closed
	 */
	@Nonnull
	private CompletableFuture<Void> closeSessionsAndObservers() {
		return CompletableFuture.allOf(
			CompletableFuture.runAsync(this::closeAllSessions),
			CompletableFuture.runAsync(this.changeObserver::close)
		);
	}

	/**
	 * Second stage of shut down: shuts down all executors.
	 * @return future that completes when all executors are shut down
	 */
	@Nonnull
	private CompletableFuture<Void> shutdownExecutors() {
		return CompletableFuture.allOf(
			CompletableFuture.runAsync(() -> shutdownScheduler("request", this.requestExecutor, 60)),
			CompletableFuture.runAsync(() -> shutdownScheduler("transaction", this.transactionExecutor, 60)),
			CompletableFuture.runAsync(() -> shutdownScheduler("service", this.serviceExecutor, 60))
		);
	}

	/**
	 * Third stage of shut down: terminates all catalogs.
	 * @return future that completes when all catalogs are terminated
	 */
	@Nonnull
	private CompletableFuture<Void> closeCatalogs() {
		return CompletableFuture.allOf(
			this.catalogs.values()
				.stream()
				.map(catalog -> CompletableFuture.runAsync(catalog::terminate))
				.toArray(CompletableFuture[]::new)
		).thenCompose(unused -> {
			// and clear the internal map
			this.catalogs.clear();
			return null;
		});
	}

	/**
	 * This descriptor allows to recognize collection and schema modifications in non-transactional mode where the
	 * contents of the original catalog are directly modified.
	 */
	private static class NonTransactionalCatalogDescriptor {
		/**
		 * Reference to the catalog.
		 */
		private final Catalog theCatalog;
		/**
		 * Contains observed version of the catalog schema in time this class is instantiated.
		 */
		private final int catalogSchemaVersion;
		/**
		 * Contains index of entity collection types and their assigned primary keys observed in time this class is instantiated.
		 */
		private final Map<String, Integer> entityCollectionPrimaryKeys;
		/**
		 * Contains observed versions of the catalog entity collection schemas in time this class is instantiated.
		 */
		private final Map<String, Integer> entityCollectionSchemaVersions;

		NonTransactionalCatalogDescriptor(@Nonnull Catalog catalog) {
			this.theCatalog = catalog;
			this.catalogSchemaVersion = catalog.getSchema().version();
			final Set<String> entityTypes = catalog.getEntityTypes();
			this.entityCollectionPrimaryKeys = CollectionUtils.createHashMap(entityTypes.size());
			this.entityCollectionSchemaVersions = CollectionUtils.createHashMap(entityTypes.size());
			for (String entityType : entityTypes) {
				final EntityCollection entityCollection = catalog.getCollectionForEntityOrThrowException(entityType);
				this.entityCollectionPrimaryKeys.put(entityType, entityCollection.getEntityTypePrimaryKey());
				this.entityCollectionSchemaVersions.put(entityType, entityCollection.getSchema().version());
			}
		}

		/**
		 * Method will examine changes in `newCatalog` compared to `currentCatalog` and notify {@link Catalog}
		 * {@link CatalogChangeObserver} in case there is any key structural change identified.
		 */
		void notifyStructuralChangeObservers() {
			final CatalogChangeCaptureBlock captureBlock = theCatalog.createChangeCaptureBlock();

			if (isCatalogSchemaModified(theCatalog)) {
				final int newSchemaVersion = theCatalog.getSchema().version();
				theCatalog.notifyObservers(
					CaptureArea.SCHEMA, Operation.UPDATE,
					null, null, newSchemaVersion, null, null,
					captureBlock
				);
			}
			// and examine catalog entity collection changes
			this.entityCollectionSchemaVersions
				.keySet()
				.stream()
				.filter(it -> isEntityCollectionSchemaModified(theCatalog, it))
				.forEach(it -> {
					final EntityCollection entityCollection = theCatalog.getCollectionForEntityOrThrowException(it);
					final EntitySchemaContract sealedEntitySchema = entityCollection.getSchema();
					theCatalog.notifyObservers(
						CaptureArea.SCHEMA, Operation.UPDATE,
						it, entityCollection.getEntityTypePrimaryKey(),
						null,
						sealedEntitySchema.version(),
						null,
						captureBlock
					);
				});
			getCreatedCollections(theCatalog)
				.forEach(it -> {
					final EntityCollection entityCollection = theCatalog.getCollectionForEntityOrThrowException(it);
					final EntitySchemaContract sealedEntitySchema = entityCollection.getSchema();
					theCatalog.notifyObservers(
						CaptureArea.SCHEMA, Operation.CREATE,
						it, entityCollection.getEntityTypePrimaryKey(),
						null,
						sealedEntitySchema.version(),
						null,
						captureBlock
					);
				});
			getDeletedCollections(theCatalog)
				.forEach(it -> {
					theCatalog.notifyObservers(
						CaptureArea.SCHEMA, Operation.REMOVE,
						it, this.entityCollectionPrimaryKeys.get(it),
						null, null, null,
						captureBlock
					);
				});

			captureBlock.finish();
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
			return theirVersion != null && myVersion != null && !Objects.equals(myVersion, theirVersion);
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
	 * @param closeFuture future that gets completed when session is closed
	 */
	private record CreatedSession(
		@Nonnull EvitaInternalSessionContract session,
		@Nonnull CompletableFuture<Long> closeFuture
	) implements Closeable {

		@Override
		public void close() {
			session.close();
		}

	}

}
