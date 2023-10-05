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

package io.evitadb.core;

import io.evitadb.api.CatalogContract;
import io.evitadb.api.CatalogState;
import io.evitadb.api.EntityCollectionContract;
import io.evitadb.api.EvitaContract;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.EvitaSessionTerminationCallback;
import io.evitadb.api.SessionTraits;
import io.evitadb.api.SessionTraits.SessionFlags;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.exception.CatalogAlreadyPresentException;
import io.evitadb.api.exception.CatalogNotFoundException;
import io.evitadb.api.exception.InstanceTerminatedException;
import io.evitadb.api.exception.ReadOnlyException;
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
import io.evitadb.core.cache.CacheSupervisor;
import io.evitadb.core.cache.HeapMemoryCacheSupervisor;
import io.evitadb.core.cache.NoCacheSupervisor;
import io.evitadb.core.cdc.CatalogChangeCaptureBlock;
import io.evitadb.core.cdc.CatalogChangeObserver;
import io.evitadb.core.cdc.SystemChangeObserver;
import io.evitadb.core.exception.CatalogCorruptedException;
import io.evitadb.core.maintenance.SessionKiller;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.scheduling.RejectingExecutor;
import io.evitadb.core.scheduling.Scheduler;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.thread.TimeoutableThread;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.FileUtils;
import io.evitadb.utils.NamingConvention;
import io.evitadb.utils.ReflectionLookup;
import io.evitadb.utils.StringUtils;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.openhft.hashing.LongHashFunction;
import org.jboss.threads.EnhancedQueueExecutor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow.Publisher;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
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
	 * Keeps information about currently active sessions.
	 */
	private final Map<String, SessionRegistry> activeSessions = new ConcurrentHashMap<>();
	/**
	 * Formula supervisor is an entry point to the Evita cache. The idea is that each {@link Formula} can be identified by
	 * its {@link Formula#computeHash(LongHashFunction)} method and when the supervisor identifies that certain formula
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
	 * Field contains reference to the scheduler that maintains shared Evita asynchronous tasks used for maintenance
	 * operations.
	 */
	private final Scheduler scheduler;
	/**
	 * Reflection lookup is used to speed up reflection operation by memoizing the results for examined classes.
	 */
	private final ReflectionLookup reflectionLookup;
	/**
	 * Change observer that is used to notify all registered {@link ChangeSystemCaptureSubscriber} about changes in the
	 * catalogs.
	 */
	private final SystemChangeObserver changeObserver;
	/**
	 * Java based scheduled executor service.
	 */
	@Getter
	private final EnhancedQueueExecutor executor;
	/**
	 * Kills threads that are marked as timeoutable and their time is up.
	 */
	@SuppressWarnings({"FieldCanBeLocal", "unused"})
	private final TimeoutThreadKiller timeoutThreadKiller;
	/**
	 * Temporary storage that keeps catalog being removed reference so that onDelete callback can still access it.
	 */
	private final ThreadLocal<CatalogContract> removedCatalog = new ThreadLocal<>();
	/**
	 * Flag that is se to TRUE when Evita. is ready to serve application calls.
	 * Aim of this flag is to refuse any calls after {@link #close()} method has been called.
	 */
	private boolean active;
	/**
	 * Flag that is initially set to {@link ServerOptions#readOnly()} from {@link EvitaConfiguration}.
	 * The flag might be changed from false to TRUE one time using internal Evita API. This is used in test support.
	 */
	@Getter private boolean readOnly;

	public Evita(@Nonnull EvitaConfiguration configuration) {
		this.configuration = configuration;
		final RejectingExecutor handoffExecutor = new RejectingExecutor();
		this.executor = new EnhancedQueueExecutor.Builder()
			.setCorePoolSize(configuration.server().coreThreadCount())
			.setMaximumPoolSize(configuration.server().maxThreadCount())
			.setExceptionHandler((t, e) -> log.error("Uncaught error in thread `" + t.getName() + "`: " + e.getMessage(), e))
			.setHandoffExecutor(handoffExecutor)
			.setThreadFactory(new EvitaThreadFactory(configuration.server().threadPriority()))
			.setMaximumQueueSize(configuration.server().queueSize())
			.setRegisterMBean(false)
			.build();
		this.executor.prestartAllCoreThreads();
		this.scheduler = new Scheduler(executor);
		if (configuration.server().killTimedOutShortRunningThreadsEverySeconds() > 0 &&
			configuration.server().shortRunningThreadsTimeoutInSeconds() > 0) {
			this.timeoutThreadKiller = new TimeoutThreadKiller(
				configuration.server().shortRunningThreadsTimeoutInSeconds(),
				configuration.server().killTimedOutShortRunningThreadsEverySeconds(),
				this.executor,
				this.scheduler);
		} else {
			this.timeoutThreadKiller = null;
		}
		this.sessionKiller = of(configuration.server().closeSessionsAfterSecondsOfInactivity())
			.filter(it -> it > 0)
			.map(it -> new SessionKiller(it, this, this.scheduler))
			.orElse(null);
		this.cacheSupervisor = configuration.cache().enabled() ?
			new HeapMemoryCacheSupervisor(configuration.cache(), scheduler) : NoCacheSupervisor.INSTANCE;
		this.reflectionLookup = new ReflectionLookup(configuration.cache().reflection());
		final Path[] directories = FileUtils.listDirectories(configuration.storage().storageDirectoryOrDefault());
		this.changeObserver = new SystemChangeObserver(executor);
		this.catalogs = CollectionUtils.createConcurrentHashMap(directories.length);
		final CountDownLatch startUpLatch = new CountDownLatch(directories.length);
		for (Path directory : directories) {
			final String catalogName = directory.toFile().getName();
			scheduler.execute(() -> {
				CatalogContract catalog;
				try {
					final long start = System.nanoTime();
					catalog = new Catalog(catalogName, directory, cacheSupervisor, configuration.storage(), reflectionLookup);
					log.info("Catalog {} fully loaded in: {}", catalogName, StringUtils.formatNano(System.nanoTime() - start));
				} catch (Throwable ex) {
					log.error("Catalog {} is corrupted!", catalogName);
					catalog = new CorruptedCatalog(catalogName, directory, ex);
				}
				this.catalogs.put(catalogName, catalog);
				startUpLatch.countDown();
			});
		}

		try {
			startUpLatch.await();
			this.active = true;
			this.readOnly = this.configuration.server().readOnly();
		} catch (InterruptedException ex) {
			// terminate evitaDB - it has not properly started
			this.executor.shutdown();
			for (CatalogContract catalog : this.catalogs.values()) {
				catalog.terminate();
			}
		}
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
	public EvitaSessionContract createReadOnlySession(@Nonnull String catalogName) {
		return createEvitaSession(new SessionTraits(catalogName));
	}

	@Override
	@Nonnull
	public EvitaSessionContract createReadWriteSession(@Nonnull String catalogName) {
		return createSession(new SessionTraits(catalogName, SessionFlags.READ_WRITE));
	}

	@Override
	@Nonnull
	public EvitaSessionContract createSession(@Nonnull SessionTraits traits) {
		notNull(traits.catalogName(), "Catalog name is mandatory information.");
		return createEvitaSession(traits);
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
			return true;
		}
	}

	@Override
	public void update(@Nonnull TopLevelCatalogSchemaMutation... catalogMutations) {
		assertActive();
		if (readOnly) {
			throw new ReadOnlyException();
		}
		// TOBEDONE JNO - append mutation to the WAL and execute asynchronously
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
		try (final EvitaSessionContract session = this.createEvitaSession(new SessionTraits(catalogName, flags))) {
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

	@Override
	public <T> T updateCatalog(@Nonnull String catalogName, @Nonnull Function<EvitaSessionContract, T> updater, @Nullable SessionFlags... flags) {
		assertActive();
		if (readOnly && Arrays.stream(flags).noneMatch(it -> it == SessionFlags.DRY_RUN)) {
			throw new ReadOnlyException();
		}
		final SessionTraits traits = new SessionTraits(
			catalogName,
			flags == null ?
				new SessionFlags[]{SessionFlags.READ_WRITE} :
				ArrayUtils.insertRecordIntoArray(SessionFlags.READ_WRITE, flags, flags.length)
		);
		try (final EvitaSessionContract session = this.createSession(traits)) {
			return session.execute(updater);
		}
	}

	@Override
	public void updateCatalog(@Nonnull String catalogName, @Nonnull Consumer<EvitaSessionContract> updater, @Nullable SessionFlags... flags) {
		updateCatalog(
			catalogName,
			evitaSession -> {
				updater.accept(evitaSession);
				return null;
			},
			flags
		);
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
		assertActive();
		active = false;
		final Iterator<SessionRegistry> sessionRegistryIt = activeSessions.values().iterator();
		while (sessionRegistryIt.hasNext()) {
			final SessionRegistry sessionRegistry = sessionRegistryIt.next();
			sessionRegistry.closeAllActiveSessions();
			sessionRegistryIt.remove();
		}
		final Iterator<CatalogContract> it = catalogs.values().iterator();
		while (it.hasNext()) {
			final CatalogContract catalog = it.next();
			catalog.terminate();
			it.remove();
			log.info("Catalog {} successfully terminated.", catalog.getName());
		}
		this.changeObserver.close();

		this.executor.shutdown();
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
		final CatalogSchemaContract catalogSchema = Objects.requireNonNull(createCatalogSchema.mutate(null));
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
						cacheSupervisor,
						configuration.storage(),
						reflectionLookup
					);
				} else {
					throw new CatalogAlreadyPresentException(catalogName, existingCatalog.getName());
				}
			}
		);
		changeObserver.notifyPublishers(catalogName, Operation.CREATE, () -> createCatalogSchema);
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
				modifyCatalogSchemaName.mutate(catalogToBeReplacedWith.getSchema()),
				catalogToBeReplaced
			);
			// now rewrite the original catalog with renamed contents so that the observers could access it
			final CatalogContract previousCatalog = this.catalogs.put(catalogNameToBeReplaced, replacedCatalog);

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
			this.catalogs.remove(catalogNameToBeReplacedWith);

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
		}
	}

	/**
	 * Replaces current catalog reference with updated one. Catalogs
	 */
	@Nonnull
	private CatalogContract replaceCatalogReference(@Nonnull CatalogContract catalog) {
		notNull(catalog, "Sanity check.");
		final String catalogName = catalog.getName();
		// catalog indexes are ConcurrentHashMap - we can do it safely here
		final AtomicReference<CatalogContract> originalCatalog = new AtomicReference<>();
		final CatalogContract actualCatalog = this.catalogs.computeIfPresent(
			catalogName, (cName, currentCatalog) -> {
				// replace catalog only when reference/pointer differs
				// TOBEDONE JNO - we should add `&& currentCatalog.getVersion() < catalog.getVersion()` when the commits are linearized
				if (currentCatalog != catalog) {
					originalCatalog.set(currentCatalog);
					// we have to also atomically update the catalog reference in all active sessions
					ofNullable(activeSessions.get(catalogName))
						.ifPresent(it -> it.updateCatalogReference(catalog));
					return catalog;
				} else {
					return currentCatalog;
				}
			}
		);

		// notify structural changes callbacks
		ofNullable(originalCatalog.get())
			.ifPresent(it -> notifyStructuralChangeObservers(catalog, it));

		return actualCatalog;
	}

	/**
	 * Method iterates over all opened and active {@link EvitaSession sessions} and closes all that relate to passed
	 * `catalogName`.
	 */
	private void closeAllActiveSessionsTo(@Nonnull String catalogName) {
		ofNullable(activeSessions.get(catalogName))
			.ifPresent(SessionRegistry::closeAllActiveSessions);
	}

	/**
	 * Verifies this instance is still active.
	 */
	private void assertActive() {
		if (!active) {
			throw new InstanceTerminatedException("instance");
		}
	}

	/**
	 * Method will examine changes in `newCatalog` compared to `currentCatalog` and notify {@link #changeObserver}
	 * in case there is any key structural change identified.
	 */
	private void notifyStructuralChangeObservers(@Nonnull CatalogContract newCatalog, @Nonnull CatalogContract currentCatalog) {
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
	private EvitaSessionContract createEvitaSession(@Nonnull SessionTraits sessionTraits) {
		final CatalogContract catalogContract = getCatalogInstanceOrThrowException(sessionTraits.catalogName());
		final Catalog catalog;
		if (catalogContract instanceof CorruptedCatalog corruptedCatalog) {
			throw new CatalogCorruptedException(corruptedCatalog);
		} else {
			catalog = (Catalog) catalogContract;
		}

		final SessionRegistry sessionRegistry = activeSessions.computeIfAbsent(
			sessionTraits.catalogName(),
			theCatalogName -> new SessionRegistry()
		);

		final NonTransactionalCatalogDescriptor nonTransactionalCatalogDescriptor =
			catalog.getCatalogState() == CatalogState.WARMING_UP && sessionTraits.isReadWrite() && !sessionTraits.isDryRun() ?
				new NonTransactionalCatalogDescriptor(catalog) : null;

		if (readOnly) {
			isTrue(!sessionTraits.isReadWrite() || sessionTraits.isDryRun(), ReadOnlyException::new);
		}

		final EvitaSessionTerminationCallback terminationCallback = session -> {
			sessionRegistry.removeSession(session);

			if (sessionTraits.isReadWrite()) {
				catalog.decreaseReadWriteSessionCount();
				ofNullable(nonTransactionalCatalogDescriptor)
					.ifPresent(NonTransactionalCatalogDescriptor::notifyStructuralChangeObservers);
			}
		};

		final EvitaSessionContract newSession = sessionRegistry.addSession(
			catalog.supportsTransaction(),
			() -> sessionTraits.isReadWrite() ?
				new EvitaSession(this, catalog, reflectionLookup, terminationCallback, this::replaceCatalogReference, sessionTraits) :
				new EvitaSession(this, catalog, reflectionLookup, terminationCallback, sessionTraits)
		);

		if (sessionTraits.isReadWrite()) {
			catalog.increaseReadWriteSessionCount();
		}

		return newSession;
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
	 * Custom thread factory to manage thread priority and naming.
	 */
	private static class EvitaThreadFactory implements ThreadFactory {
		/**
		 * Counter monitoring the number of threads this factory created.
		 */
		private static final AtomicInteger THREAD_COUNTER = new AtomicInteger();
		/**
		 * Home group for the new threads.
		 */
		private final ThreadGroup group;
		/**
		 * Priority for threads that are created by this factory.
		 * Initialized from {@link ServerOptions#threadPriority()}.
		 */
		private final int priority;

		public EvitaThreadFactory(int priority) {
			this.group = Thread.currentThread().getThreadGroup();
			this.priority = priority;
		}

		@Override
		public Thread newThread(@Nonnull Runnable runnable) {
			final Thread thread = new TimeoutableThread(group, runnable, "Evita-" + THREAD_COUNTER.incrementAndGet());
			if (priority > 0 && thread.getPriority() != priority) {
				thread.setPriority(priority);
			}
			return thread;
		}
	}

	/**
	 * Tries to kill short-running threads that run longer than specified timeout.
	 */
	@RequiredArgsConstructor
	private static class TimeoutThreadKiller implements Runnable {

		@Nonnull private final EnhancedQueueExecutor executor;
		private final long timeoutInSeconds;

		public TimeoutThreadKiller(int timeoutInSeconds,
		                           int checkRateInSeconds,
		                           @Nonnull EnhancedQueueExecutor executor,
		                           @Nonnull Scheduler scheduler) {
			this.timeoutInSeconds = timeoutInSeconds;
			this.executor = executor;
			scheduler.scheduleAtFixedRate(this, Math.min(60, checkRateInSeconds), Math.min(60, checkRateInSeconds), TimeUnit.SECONDS);
		}

		@Override
		public void run() {
			for (Thread runningThread : this.executor.getRunningThreads()) {
				final TimeoutableThread timeoutableThread = (TimeoutableThread) runningThread;

				if (timeoutableThread.isTimedOut(timeoutInSeconds * 1_000_000_000L)) {
					printThreadStackTraces(timeoutableThread);
					timeoutableThread.interrupt();
				}
			}
		}

		private void printThreadStackTraces(@Nonnull TimeoutableThread thread) {
			if (log.isErrorEnabled()) {
				final Map<Thread, StackTraceElement[]> allStackTraces = Thread.getAllStackTraces();

				final String threadStackTraces = allStackTraces.entrySet()
					.stream()
					.filter(it -> it.getKey().getName().equals(thread.getName()))
					.map(Entry::getValue)
					.map(stackTrace -> {
						final StringBuilder printableStackTrace = new StringBuilder();
						for (StackTraceElement stackTraceElement : stackTrace) {
							printableStackTrace
								.append(stackTraceElement.toString())
								.append(System.lineSeparator());
						}
						return printableStackTrace.toString();
					})
					.collect(Collectors.joining("\n\n"));

				log.error("Thread `" + thread.getName() + "` is running for more than `" + timeoutInSeconds + "` seconds and is about to be killed. Stack traces of the thread are: \n\n" + threadStackTraces);
			}
		}
	}

	private record CatalogNameInConvention(
		@Nonnull String catalogName,
		@Nonnull NamingConvention convention,
		@Nonnull String name
	) {}

}
