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

package io.evitadb.core.session;

import io.evitadb.api.CommitProgress.CommitVersions;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.SessionTraits;
import io.evitadb.api.TransactionContract.CommitBehavior;
import io.evitadb.api.exception.ConcurrentInitializationException;
import io.evitadb.api.exception.InstanceTerminatedException;
import io.evitadb.api.observability.trace.TracingContext;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.catalog.CatalogConsumerControl;
import io.evitadb.core.exception.CatalogTransitioningException;
import io.evitadb.core.exception.SessionBusyException;
import io.evitadb.core.metric.event.transaction.TransactionResolution;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Proxy;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static java.util.Optional.of;
import static java.util.Optional.ofNullable;

/**
 * Session registry maintains all active sessions for a single catalog in the {@link Evita} instance.
 *
 * ## Responsibilities
 *
 * - **Session Lifecycle**: Creates, registers, and removes sessions
 * - **Suspension Handling**: Supports catalog rename/replace by suspending session creation
 * - **Version Tracking**: Tracks which catalog versions are consumed by active sessions
 * - **Thread Safety**: All operations are thread-safe using concurrent data structures
 *
 * ## Thread Safety Model
 *
 * Uses {@link ConcurrentHashMap} for session storage, {@link AtomicReference} for suspension state,
 * and per-session {@link ReentrantLock} for atomic operations.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2022
 * @see EvitaSessionProxy for session proxy implementation
 */
@Slf4j
public final class SessionRegistry {
	/**
	 * Provides the tracing context for tracking the execution flow in the application.
	 **/
	private final TracingContext tracingContext;
	/**
	 * Supplier that provides reference to the current {@link Catalog} this instance is bound to.
	 */
	private final Supplier<Catalog> catalogSupplier;
	/**
	 * Keeps information about currently active sessions in one big data store that contains index across all catalogs.
	 */
	private final SessionRegistryDataStore sharedDataStore;
	/**
	 * Keeps information about currently active sessions.
	 */
	private final Map<UUID, EvitaSessionTuple> activeSessions;
	/**
	 * This field is used to keep track of the current suspend operation (if any).
	 */
	private final AtomicReference<InSuspension> currentSuspension;
	/**
	 * This field is used to keep track of the sessions that were forcefully closed due to a suspension operation.
	 * The information is held only for a limited time.
	 */
	private final AtomicReference<SuspensionInformation> lastSuspensionInfo = new AtomicReference<>(null);
	/**
	 * Keeps information about sessions sorted according to date of creation.
	 */
	private final ConcurrentLinkedQueue<EvitaSessionTuple> sessionsFifoQueue;
	/**
	 * The catalogConsumedVersions variable is used to keep track of consumed versions along with number of sessions
	 * tied to them indexed by catalog names.
	 */
	private final ConcurrentHashMap<String, VersionConsumingSessions> catalogConsumedVersions;

	/**
	 * Created data store to be shared among all SessionRegistry instances.
	 *
	 * @return the data store
	 */
	@Nonnull
	public static SessionRegistryDataStore createDataStore() {
		return new SessionRegistryDataStore();
	}

	public SessionRegistry(
		@Nonnull TracingContext tracingContext,
		@Nonnull Supplier<Catalog> catalogSupplier,
		@Nonnull SessionRegistryDataStore sharedDataStore
	) {
		this.tracingContext = tracingContext;
		this.catalogSupplier = catalogSupplier;
		this.sharedDataStore = sharedDataStore;
		this.activeSessions = CollectionUtils.createConcurrentHashMap(512);
		this.currentSuspension = new AtomicReference<>(null);
		this.sessionsFifoQueue = new ConcurrentLinkedQueue<>();
		this.catalogConsumedVersions = CollectionUtils.createConcurrentHashMap(32);
	}

	private SessionRegistry(
		@Nonnull TracingContext tracingContext,
		@Nonnull Supplier<Catalog> catalogSupplier,
		@Nonnull SessionRegistryDataStore sharedDataStore,
		@Nonnull Map<UUID, EvitaSessionTuple> activeSessions,
		@Nonnull AtomicReference<InSuspension> currentSuspension,
		@Nonnull ConcurrentLinkedQueue<EvitaSessionTuple> sessionsFifoQueue,
		@Nonnull ConcurrentHashMap<String, VersionConsumingSessions> catalogConsumedVersions
	) {
		this.tracingContext = tracingContext;
		this.catalogSupplier = catalogSupplier;
		this.sharedDataStore = sharedDataStore;
		this.activeSessions = activeSessions;
		this.currentSuspension = currentSuspension;
		this.sessionsFifoQueue = sessionsFifoQueue;
		this.catalogConsumedVersions = catalogConsumedVersions;
	}

	/**
	 * Retrieves the catalog associated with the registry.
	 *
	 * @return the current catalog instance
	 */
	@Nonnull
	public Catalog getCatalog() {
		return this.catalogSupplier.get();
	}

	/**
	 * Method closes and removes all active sessions from the registry.
	 * All changes are rolled back.
	 */
	@Nonnull
	public Optional<SuspensionInformation> closeAllActiveSessionsAndSuspend(
		@Nonnull SuspendOperation suspendOperation
	) {
		if (this.currentSuspension.compareAndSet(null, new InSuspension(suspendOperation))) {
			// init information about closed sessions
			final SuspensionInformation suspensionInformation = new SuspensionInformation(
				this.activeSessions.size()
			);
			this.lastSuspensionInfo.set(suspensionInformation);
			final long start = System.currentTimeMillis();
			// reuse list across iterations to reduce allocations
			final List<CompletableFuture<CommitVersions>> futures = new ArrayList<>(this.activeSessions.size());
			do {
				futures.clear();
				for (EvitaSessionTuple sessionTuple : this.activeSessions.values()) {
					//noinspection resource
					final EvitaSession plainSession = sessionTuple.plainSession();
					//noinspection resource
					final EvitaInternalSessionContract proxySession = sessionTuple.proxySession();
					if (proxySession.isActive()) {
						proxySession
							// close the session once the running method is finished
							// or immediately if there is no method running
							.executeWhenMethodIsNotRunning(
								() -> {
									if (plainSession.isActive()) {
										if (plainSession.isTransactionOpen()) {
											plainSession.setRollbackOnly();
										}
										final UUID sessionId = plainSession.getId();
										log.info("There is still an active session {} - terminating.", sessionId);
										suspensionInformation.addForcefullyClosedSession(sessionId);
										futures.add(
											plainSession.closeNow(CommitBehavior.WAIT_FOR_WAL_PERSISTENCE)
												.toCompletableFuture()
												// ignore exceptions, we don't care about them here
												.exceptionally(ex -> null)
										);
									}
								}
							);
					}
				}
				// wait for all futures to complete
				CompletableFuture
					.allOf(futures.toArray(new CompletableFuture[0]))
					.join();
				// wait for active sessions to be empty, but at most 5 seconds
			} while (!this.activeSessions.isEmpty() && System.currentTimeMillis() - start < 5000);

			Assert.isPremiseValid(
				this.activeSessions.isEmpty(),
				() -> {
					final StringBuilder sb = new StringBuilder("Some of the sessions didn't clean themselves (");
					boolean first = true;
					for (EvitaSessionTuple tuple : this.activeSessions.values()) {
						final EvitaSession session = tuple.plainSession();
						if (!first) {
							sb.append(", ");
						}
						first = false;
						sb.append(session.getId()).append(session.isActive() ? ": active" : ": closed");
					}
					sb.append(")!");
					return sb.toString();
				}
			);
			return of(suspensionInformation);
		}
		return ofNullable(this.lastSuspensionInfo.get());
	}

	/**
	 * Method resumes operations on this registry - i.e. creating new sessions.
	 */
	public void resumeOperations() {
		final InSuspension inSuspension = this.currentSuspension.getAndSet(null);
		if (inSuspension != null) {
			inSuspension.suspendFuture().complete(null);
		}
	}

	/**
	 * Clears any temporary information related to forcefully closed sessions in the registry.
	 *
	 * If there is information about sessions that were forcefully closed and the suspension event
	 * occurred more than 5 minutes ago, this method will clear that information.
	 *
	 * This helps in cleaning up outdated suspension data to keep the registry up-to-date
	 * and free from stale information.
	 */
	public void clearTemporaryInformation() {
		final SuspensionInformation suspensionInformation = this.lastSuspensionInfo.get();
		if (suspensionInformation != null &&
			suspensionInformation.getSuspensionDateTime().isBefore(OffsetDateTime.now().minusMinutes(5))) {
			// clear the information about forcefully closed sessions after 5 minutes
			this.lastSuspensionInfo.set(null);
		}
	}

	/**
	 * Determines whether the sessions associated with a catalog were forcefully closed.
	 *
	 * @param sessionId the unique identifier of the session in the registry
	 * @return true if the sessions associated with the catalog were forcefully closed; false otherwise
	 */
	public boolean wereSessionsForcefullyClosedForCatalog(@Nonnull UUID sessionId) {
		return ofNullable(this.lastSuspensionInfo.get())
			.map(it -> it.contains(sessionId))
			.orElse(false);
	}

	/**
	 * Creates and registers new session to the registry.
	 * Method checks that there is only a single active session when catalog is in warm-up mode.
	 */
	@Nonnull
	public EvitaInternalSessionContract addSession(
		boolean transactional,
		@Nonnull Supplier<EvitaSession> sessionSupplier
	) {
		return handleSuspension(() -> {
			if (!transactional && !this.activeSessions.isEmpty()) {
				throw new ConcurrentInitializationException(this.activeSessions.keySet().iterator().next());
			}

			final EvitaSession newSession = sessionSupplier.get();
			final long catalogVersion = newSession.getCatalogVersion();
			final String catalogName = newSession.getCatalogName();

			final EvitaInternalSessionContract newSessionProxy = (EvitaInternalSessionContract) Proxy.newProxyInstance(
				EvitaInternalSessionContract.class.getClassLoader(),
				new Class[]{EvitaInternalSessionContract.class, EvitaProxyFinalization.class},
				new EvitaSessionProxy(newSession, this.tracingContext)
			);
			final EvitaSessionTuple sessionTuple = new EvitaSessionTuple(newSession, newSessionProxy);
			sessionTuple.executeAtomically(
				() -> {
					this.activeSessions.put(newSession.getId(), sessionTuple);
					this.sessionsFifoQueue.add(sessionTuple);
					this.catalogConsumedVersions.computeIfAbsent(catalogName, k -> new VersionConsumingSessions())
						.registerSessionConsumingCatalogInVersion(catalogVersion, newSession.getSessionTraits());
					this.sharedDataStore.addSession(sessionTuple);
				}
			);

			return newSessionProxy;
		});
	}

	/**
	 * Removes session from the registry.
	 */
	public void removeSession(@Nonnull EvitaSession session) {
		final EvitaSessionTuple removedSession = this.activeSessions.remove(session.getId());
		if (removedSession != null) {
			removedSession.executeAtomically(
				() -> {
					final EvitaSessionTuple globalSession = this.sharedDataStore.removeSession(session.getId());
					Assert.isPremiseValid(
						removedSession == globalSession,
						"Session not found in the globally shared data store."
					);
					Assert.isPremiseValid(
						this.sessionsFifoQueue.remove(removedSession),
						"Session not found in the queue."
					);

					session.getTransaction().ifPresent(transaction -> {
						// find oldest session with open transaction using loop to avoid Stream allocation
						OffsetDateTime oldestWithTransaction = null;
						for (EvitaSessionTuple tuple : this.sessionsFifoQueue) {
							final EvitaSession queuedSession = tuple.plainSession();
							if (queuedSession.getOpenedTransaction().isPresent()) {
								oldestWithTransaction = queuedSession.getCreated();
								break;
							}
						}
						// emit event
						transaction.getFinalizationEvent()
							.finishWithResolution(
								oldestWithTransaction,
								transaction.isRollbackOnly() ?
									TransactionResolution.ROLLBACK : TransactionResolution.COMMIT
							).commit();
					});

					this.catalogConsumedVersions.get(session.getCatalogName())
						.unregisterSessionConsumingCatalogInVersion(
							session.getCatalogVersion(),
							session.getSessionTraits(),
							this.catalogSupplier
						);

					// emit event
					//noinspection CastToIncompatibleInterface,resource
					((EvitaProxyFinalization) removedSession.proxySession())
						.finish(
							ofNullable(this.sessionsFifoQueue.peek())
								.map(it -> it.plainSession().getCreated())
								.orElse(null),
							this.activeSessions.size()
						);
				}
			);
		}
	}

	/**
	 * Returns control object that allows external objects signalize work with the catalog of particular version.
	 *
	 * @param catalogName the name of the catalog
	 * @return the control object
	 */
	@Nonnull
	public CatalogConsumerControl createCatalogConsumerControl(@Nonnull String catalogName) {
		return new CatalogConsumerControlInternal(
			this.catalogConsumedVersions.computeIfAbsent(catalogName, k -> new VersionConsumingSessions()),
			this.catalogSupplier
		);
	}

	/**
	 * Internal method that creates and initializes session and returns it.
	 *
	 * @param sessionFactory the function that creates the session
	 * @return the created session
	 */
	@Nonnull
	public EvitaInternalSessionContract createSession(
		@Nonnull Function<SessionRegistry, EvitaInternalSessionContract> sessionFactory
	) {
		return handleSuspension(() -> sessionFactory.apply(this));
	}

	/**
	 * Creates a new instance of SessionRegistry using a different supplier for the catalog.
	 * This method allows changing the catalog supplier while re-using the other existing settings
	 * from the current SessionRegistry instance.
	 *
	 * @param catalogSupplier a non-null supplier of the catalog to be used in the new SessionRegistry
	 * @return a new instance of SessionRegistry configured with the provided catalog supplier
	 */
	@Nonnull
	public SessionRegistry withDifferentCatalogSupplier(@Nonnull Supplier<Catalog> catalogSupplier) {
		return new SessionRegistry(
			this.tracingContext,
			catalogSupplier,
			this.sharedDataStore,
			this.activeSessions,
			this.currentSuspension,
			this.sessionsFifoQueue,
			this.catalogConsumedVersions
		);
	}

	/**
	 * Handles a suspension operation based on the current state.
	 * If there is an active suspend operation, it evaluates its behavior and
	 * acts accordingly by either postponing the operation, awaiting completion,
	 * or throwing an exception. If no active suspend operation is detected, it
	 * proceeds with the supplied operation.
	 *
	 * @param <T>      the type of the result provided by the supplier
	 * @param supplier a non-null supplier that provides the operation to execute
	 *                 if suspension allows it
	 * @return the result of the supplier's operation if executed successfully
	 * @throws SessionBusyException        if the suspension operation has been postponed
	 *                                     and could not finish within the timeout period
	 * @throws InstanceTerminatedException if the suspension operation indicates
	 *                                     the instance termination
	 */
	private <T> T handleSuspension(@Nonnull Supplier<T> supplier) {
		final InSuspension inSuspension = this.currentSuspension.get();
		if (inSuspension == null) {
			return supplier.get();
		} else if (inSuspension.suspendOperation() == SuspendOperation.POSTPONE) {
			if (inSuspension.awaitFinish(500, TimeUnit.MILLISECONDS)) {
				return supplier.get();
			} else {
				throw SessionBusyException.INSTANCE;
			}
		} else {
			throw new InstanceTerminatedException("catalog");
		}
	}

	/**
	 * The DTO combines both plain session and the proxy wrapper around it so that one or another can be used on places
	 * where necessary.
	 *
	 * @param plainSession the session object
	 * @param proxySession the proxy wrapper around the very session object
	 */
	private record EvitaSessionTuple(
		@Nonnull EvitaSession plainSession,
		@Nonnull EvitaInternalSessionContract proxySession,
		@Nonnull ReentrantLock atomicLock
	) {

		private EvitaSessionTuple(
			@Nonnull EvitaSession plainSession,
			@Nonnull EvitaInternalSessionContract proxySession
		) {
			this(
				plainSession,
				proxySession,
				new ReentrantLock()
			);
		}

		/**
		 * Method executes the given lambda in an atomic way, ensuring that no other thread can interfere with
		 * the block execution.
		 *
		 * @param lambda the lambda to be executed atomically
		 */
		public void executeAtomically(@Nonnull Runnable lambda) {
			this.atomicLock.lock();
			try {
				lambda.run();
			} finally {
				this.atomicLock.unlock();
			}
		}
	}

	/**
	 * This class represents a collection of sessions that are consuming catalogs in different versions.
	 */
	private static class VersionConsumingSessions {
		/**
		 * ConcurrentHashMap representing a collection of sessions that are consuming catalogs in different versions.
		 * The keys of the map are the versions of the catalogs, and the values are the number of the read-only sessions
		 * consuming catalogs in that version.
		 */
		private final ConcurrentHashMap<Long, Integer> versionConsumingReadOnlySessions =
			CollectionUtils.createConcurrentHashMap(32);
		/**
		 * ConcurrentHashMap representing a collection of sessions that are consuming catalogs in different versions.
		 * The keys of the map are the versions of the catalogs, and the values are the number of the read-write
		 * sessions consuming catalogs in that version.
		 */
		private final ConcurrentHashMap<Long, Integer> versionConsumingReadWriteSessions =
			CollectionUtils.createConcurrentHashMap(32);

		/**
		 * Registers a session consuming catalog in the specified version.
		 *
		 * @param version the version of the catalog
		 */
		void registerSessionConsumingCatalogInVersion(long version, @Nonnull SessionTraits traits) {
			final ConcurrentHashMap<Long, Integer> targetIndex = traits.isReadWrite() ?
				this.versionConsumingReadWriteSessions :
				this.versionConsumingReadOnlySessions;

			targetIndex.compute(
				version,
				(k, v) -> v == null ? 1 : v + 1
			);
		}

		/**
		 * Unregisters a session that is consuming a catalog in the specified version.
		 *
		 * @param version the version of the catalog
		 * @param catalog the supplier of currently active catalog instance
		 */
		void unregisterSessionConsumingCatalogInVersion(
			long version,
			@Nonnull SessionTraits traits,
			@Nonnull Supplier<Catalog> catalog
		) {
			final ConcurrentHashMap<Long, Integer> targetIndex = traits.isReadWrite() ?
				this.versionConsumingReadWriteSessions :
				this.versionConsumingReadOnlySessions;

			final Integer readerCount = targetIndex.compute(
				version,
				(k, v) -> v == null || v == 1 ? null : v - 1
			);

			// the minimal active catalog version used by another session now
			final OptionalLong minimalActiveCatalogVersion;
			// TRUE when the session was the last reader
			final boolean lastReader;
			if (readerCount == null) {
				minimalActiveCatalogVersion = getMinimalVersionFrom(targetIndex);
				lastReader = true;
			} else {
				minimalActiveCatalogVersion = OptionalLong.of(version);
				lastReader = false;
			}

			if (lastReader) {
				// notify listeners that the catalog version is no longer used
				final Catalog theCatalog;
				try {
					theCatalog = catalog.get();
					// in rare cases (catalog replacement) the catalog might not have been available already
					if (theCatalog != null) {
						final long minimalActiveVersion = minimalActiveCatalogVersion.orElse(theCatalog.getVersion());
						theCatalog.catalogConsumersLeft(
							traits.isReadWrite() ?
								getMinimalVersionFrom(this.versionConsumingReadOnlySessions)
									.orElse(minimalActiveVersion) :
								minimalActiveVersion,
							traits.isReadWrite() ?
								minimalActiveVersion :
								getMinimalVersionFrom(this.versionConsumingReadWriteSessions)
									.orElse(minimalActiveVersion)
						);
					}
				} catch (CatalogTransitioningException ignored) {
					// catalog is transitioning, we cannot notify it anyway
				}
			}
		}

		/**
		 * Retrieves the minimal version from the provided ConcurrentHashMap of versions.
		 *
		 * @param targetIndex a ConcurrentHashMap where the keys represent version numbers
		 *                    and the values are associated integer data.
		 * @return an {@link OptionalLong} containing the minimum version number
		 * if the ConcurrentHashMap is not empty, otherwise an empty {@link OptionalLong}.
		 */
		@Nonnull
		private static OptionalLong getMinimalVersionFrom(@Nonnull ConcurrentHashMap<Long, Integer> targetIndex) {
			if (targetIndex.isEmpty()) {
				return OptionalLong.empty();
			}
			long min = Long.MAX_VALUE;
			for (Long version : targetIndex.keySet()) {
				if (version < min) {
					min = version;
				}
			}
			return OptionalLong.of(min);
		}

	}

	/**
	 * The SessionRegistryDataStore is a utility class used to manage active sessions.
	 * It maintains an internal index of sessions and provides methods for session retrieval,
	 * addition, and removal.
	 */
	public static class SessionRegistryDataStore {
		/**
		 * Keeps information about currently active sessions.
		 */
		private final Map<UUID, EvitaSessionTuple> activeSessions = CollectionUtils.createConcurrentHashMap(512);

		/**
		 * Method returns active session by its unique id or empty value if such session is not found.
		 */
		@Nonnull
		public Optional<EvitaSessionContract> getActiveSessionById(@Nonnull UUID sessionId) {
			return ofNullable(this.activeSessions.get(sessionId))
				.map(EvitaSessionTuple::proxySession);
		}

		/**
		 * Returns a stream of all active (currently open) sessions.
		 */
		@Nonnull
		public Stream<EvitaSessionContract> getActiveSessions() {
			return this.activeSessions.values()
				.stream()
				.map(EvitaSessionTuple::proxySession);
		}

		/**
		 * Method adds an active session to the internal index.
		 *
		 * @param activeSession the active session to be added
		 */
		void addSession(@Nonnull EvitaSessionTuple activeSession) {
			this.activeSessions.put(activeSession.plainSession.getId(), activeSession);
		}

		/**
		 * Method removes an active session from the internal index and returns it.
		 *
		 * @param sessionId the unique id of the session
		 * @return the session that was removed or NULL if such session is not found
		 */
		@Nullable
		EvitaSessionTuple removeSession(@Nonnull UUID sessionId) {
			return this.activeSessions.remove(sessionId);
		}
	}

	/**
	 * This interface allows external objects signalize work with the catalog of particular version.
	 */
	@RequiredArgsConstructor
	private static class CatalogConsumerControlInternal implements CatalogConsumerControl {
		private final VersionConsumingSessions versionConsumingSessions;
		private final Supplier<Catalog> catalog;

		@Override
		public void registerConsumerOfCatalogInVersion(long version, @Nonnull SessionTraits traits) {
			this.versionConsumingSessions.registerSessionConsumingCatalogInVersion(version, traits);
		}

		@Override
		public void unregisterConsumerOfCatalogInVersion(long version, @Nonnull SessionTraits traits) {
			this.versionConsumingSessions.unregisterSessionConsumingCatalogInVersion(version, traits, this.catalog);
		}

	}

	/**
	 * This record is used to keep information about the current suspension period.
	 */
	private record InSuspension(
		@Nonnull SuspendOperation suspendOperation,
		@Nonnull CompletableFuture<Void> suspendFuture
	) {

		InSuspension(@Nonnull SuspendOperation suspendOperation) {
			this(
				suspendOperation,
				new CompletableFuture<>()
			);
		}

		/**
		 * Waits for the suspension period to finish or times out after the specified duration.
		 *
		 * @param timeout  the maximum time to wait for the suspension to finish, in the given time unit
		 * @param timeUnit the unit of time for the timeout parameter, must not be null
		 * @return true if the suspension period finishes within the specified timeout, false if the timeout occurs
		 * @throws SessionBusyException if an error occurs during the wait or the current thread is interrupted
		 */
		boolean awaitFinish(int timeout, @Nonnull TimeUnit timeUnit) {
			return FutureAwaiter.awaitWithTimeout(this.suspendFuture, timeout, timeUnit);
		}

	}

}
