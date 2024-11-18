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

import com.esotericsoftware.kryo.util.Null;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.TransactionContract.CommitBehavior;
import io.evitadb.api.exception.ConcurrentInitializationException;
import io.evitadb.api.exception.TransactionException;
import io.evitadb.api.observability.trace.RepresentsMutation;
import io.evitadb.api.observability.trace.RepresentsQuery;
import io.evitadb.api.observability.trace.Traced;
import io.evitadb.api.observability.trace.TracingContext;
import io.evitadb.api.observability.trace.TracingContext.SpanAttribute;
import io.evitadb.core.metric.event.session.ClosedEvent;
import io.evitadb.core.metric.event.session.OpenedEvent;
import io.evitadb.core.metric.event.transaction.TransactionFinishedEvent;
import io.evitadb.core.metric.event.transaction.TransactionResolution;
import io.evitadb.core.metric.event.transaction.TransactionStartedEvent;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Proxy;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static java.util.Optional.ofNullable;

/**
 * Session registry maintains all active sessions for the {@link Evita} instance. It provides access to the sessions,
 * allows to terminate them or update a {@link Catalog} reference in them in a batch mode.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@Slf4j
@RequiredArgsConstructor
final class SessionRegistry {
	/**
	 * Provides the tracing context for tracking the execution flow in the application.
	 **/
	private final TracingContext tracingContext;
	/**
	 * Reference to {@link Catalog} this instance is bound to.
	 */
	private final Supplier<Catalog> catalog;
	/**
	 * Keeps information about currently active sessions in one big data store that contains index across all catalogs.
	 */
	private final SessionRegistryDataStore sharedDataStore;
	/**
	 * Keeps information about currently active sessions.
	 */
	private final Map<UUID, EvitaSessionTuple> activeSessions = CollectionUtils.createConcurrentHashMap(512);
	/**
	 * Keeps information about sessions sorted according to date of creation.
	 */
	private final ConcurrentLinkedQueue<EvitaSessionTuple> sessionsFifoQueue = new ConcurrentLinkedQueue<>();
	/**
	 * Keeps information about count of currently active sessions. Counter is used to safely control single session
	 * limits in parallel execution.
	 */
	private final AtomicInteger activeSessionsCounter = new AtomicInteger();
	/**
	 * The catalogConsumedVersions variable is used to keep track of consumed versions along with number of sessions
	 * tied to them indexed by catalog names.
	 */
	private final ConcurrentHashMap<String, VersionConsumingSessions> catalogConsumedVersions = CollectionUtils.createConcurrentHashMap(32);

	/**
	 * Created data store to be shared among all SessionRegistry instances.
	 * @return the data store
	 */
	@Nonnull
	public static SessionRegistryDataStore createDataStore() {
		return new SessionRegistryDataStore();
	}

	/**
	 * Method closes and removes all active sessions from the registry.
	 * All changes are rolled back.
	 */
	public void closeAllActiveSessions() {
		final List<CompletableFuture<Long>> futures = new LinkedList<>();
		for (EvitaSessionTuple sessionTuple : activeSessions.values()) {
			final EvitaSession activeSession = sessionTuple.plainSession();
			if (activeSession.isActive()) {
				if (activeSession.isTransactionOpen()) {
					activeSession.setRollbackOnly();
				}
				futures.add(activeSession.closeNow(CommitBehavior.WAIT_FOR_WAL_PERSISTENCE));
				log.info("There is still active session {} - terminating.", activeSession.getId());
			}
		}
		// wait for all futures to complete
		CompletableFuture
			.allOf(futures.toArray(new CompletableFuture[0]))
			.join();
		// check that all sessions were closed
		Assert.isPremiseValid(
			activeSessionsCounter.get() == 0,
			"Some of the sessions didn't decrement the session counter!"
		);
	}

	/**
	 * Creates and registers new session to the registry.
	 * Method checks that there is only a single active session when catalog is in warm-up mode.
	 */
	@Nonnull
	public EvitaInternalSessionContract addSession(boolean warmUp, @Nonnull Supplier<EvitaSession> sessionSupplier) {
		if (warmUp) {
			this.activeSessionsCounter.incrementAndGet();
		} else if (!this.activeSessionsCounter.compareAndSet(0, 1)) {
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
		this.activeSessions.put(newSession.getId(), sessionTuple);
		this.sessionsFifoQueue.add(sessionTuple);
		this.catalogConsumedVersions.computeIfAbsent(catalogName, k -> new VersionConsumingSessions())
			.registerSessionConsumingCatalogInVersion(catalogVersion);
		this.sharedDataStore.addSession(sessionTuple);

		return newSessionProxy;
	}

	/**
	 * Removes session from the registry.
	 */
	public void removeSession(@Nonnull EvitaSession session) {
		final EvitaSessionTuple activeSession = this.sharedDataStore.removeSession(session.getId());
		if (activeSession != null) {
			Assert.isPremiseValid(
				this.activeSessions.remove(session.getId()) == activeSession,
				"Session instance doesn't match the information found in the registry."
			);
			this.activeSessionsCounter.decrementAndGet();
			Assert.isPremiseValid(this.sessionsFifoQueue.remove(activeSession), "Session not found in the queue.");

			session.getTransaction().ifPresent(transaction -> {
				// emit event
				transaction.getFinalizationEvent()
					.finishWithResolution(
						this.sessionsFifoQueue.stream()
							.map(EvitaSessionTuple::plainSession)
							.filter(it -> it.getOpenedTransaction().isPresent())
							.map(EvitaSession::getCreated)
							.findFirst()
							.orElse(null),
						transaction.isRollbackOnly() ? TransactionResolution.ROLLBACK : TransactionResolution.COMMIT
					).commit();
			});
			final SessionFinalizationResult finalizationResult = catalogConsumedVersions.get(session.getCatalogName())
				.unregisterSessionConsumingCatalogInVersion(session.getCatalogVersion());
			if (finalizationResult.lastReader()) {
				// notify listeners that the catalog version is no longer used
				final Catalog theCatalog = this.catalog.get();
				if (theCatalog != null) {
					theCatalog.catalogVersionBeyondTheHorizon(
						finalizationResult.minimalActiveCatalogVersion()
					);
				} else {
					log.error(
						"Catalog is not available for the session finalization.",
						new GenericEvitaInternalError("Catalog is not available for the session finalization.")
					);
				}
			}

			// emit event
			//noinspection CastToIncompatibleInterface,resource
			((EvitaProxyFinalization) activeSession.proxySession())
				.finish(
					ofNullable(this.sessionsFifoQueue.peek())
						.map(it -> it.plainSession().getCreated())
						.orElse(null),
					this.activeSessionsCounter.get()
				);
		}
	}

	/**
	 * Internal interface for finalizing the session proxy.
	 */
	private interface EvitaProxyFinalization {

		/**
		 * Method should be called when session proxy is terminated.
		 *
		 * @param oldestSessionTimestamp the oldest active session timestamp
		 * @param activeSessions         the number of still active sessions
		 */
		void finish(@Null OffsetDateTime oldestSessionTimestamp, int activeSessions);

	}

	/**
	 * This handler is an infrastructural handler that delegates all calls to {@link #evitaSession}. We'll pay some
	 * performance price by wrapping {@link EvitaSession} in a proxy, that uses this error handler (all calls on session
	 * object will be approximately 1.7x less performant -
	 * <a href="http://ordinaryjava.blogspot.com/2008/08/benchmarking-cost-of-dynamic-proxies.html">source</a>) but this
	 * way we can isolate the error logging / translation in one place and avoid cluttering the source code. Graal
	 * supports JDK proxies out-of-the-box so this shouldn't be a problem in the future.
	 */
	private static class EvitaSessionProxy implements InvocationHandler {
		private final static Method IS_METHOD_RUNNING;
		private final static Method INACTIVITY_IN_SECONDS;
		private final EvitaSession evitaSession;
		private final TracingContext tracingContext;
		@Getter private final ClosedEvent sessionClosedEvent;
		private final AtomicInteger insideInvocation = new AtomicInteger(0);
		private final AtomicLong lastCall = new AtomicLong(System.currentTimeMillis());

		static {
			try {
				IS_METHOD_RUNNING = EvitaInternalSessionContract.class.getMethod("methodIsRunning");
				INACTIVITY_IN_SECONDS = EvitaInternalSessionContract.class.getMethod("getInactivityDurationInSeconds");
			} catch (NoSuchMethodException ex) {
				throw new GenericEvitaInternalError("Method not found.", ex);
			}
		}

		/**
		 * Handles arguments printing.
		 */
		@Nonnull
		private static String printArguments(@Nonnull Method method, @Nullable Object[] args) {
			final StringBuilder sb = new StringBuilder(256);
			if (args != null) {
				for (int i = 0; i < args.length; i++) {
					Object arg = args[i];
					if (i > 0) {
						sb.append("|");
					}
					sb.append(method.getParameters()[i].getName()).append("=").append(arg);
				}
			}
			return sb.toString();
		}

		public EvitaSessionProxy(@Nonnull EvitaSession evitaSession, @Nonnull TracingContext tracingContext) {
			this.evitaSession = evitaSession;
			this.tracingContext = tracingContext;
			final String catalogName = evitaSession.getCatalogName();

			// emit and prepare events
			new OpenedEvent(catalogName).commit();
			this.sessionClosedEvent = new ClosedEvent(catalogName);

			evitaSession.getTransaction()
				.ifPresent(transaction -> {
					// emit event
					new TransactionStartedEvent(
						catalogName
					).commit();
					// prepare finalization event
					transaction.setFinalizationEvent(
						new TransactionFinishedEvent(catalogName)
					);
				});
		}

		@Nullable
		@Override
		public Object invoke(Object proxy, Method method, Object[] args) {
			if (method.getDeclaringClass().equals(EvitaProxyFinalization.class)) {
				sessionClosedEvent
					.finish((OffsetDateTime) args[0], (int) args[1])
					.commit();
				return null;
			} else if (method.equals(INACTIVITY_IN_SECONDS)) {
				return (System.currentTimeMillis() - this.lastCall.get()) / 1000;
			} else if (method.equals(IS_METHOD_RUNNING)) {
				return this.insideInvocation.get() > 0;
			} else {
				try {
					this.evitaSession.increaseNestLevel();
					// invoke original method on delegate
					return Transaction.executeInTransactionIfProvided(
						this.evitaSession.getOpenedTransaction().orElse(null),
						() -> {
							final Supplier<Object> invocation = () -> {
								try {
									this.insideInvocation.incrementAndGet();
									this.lastCall.set(System.currentTimeMillis());
									return method.invoke(evitaSession, args);
								} catch (InvocationTargetException ex) {
									// handle the error
									final Throwable targetException = ex.getTargetException() instanceof CompletionException completionException ?
										completionException.getCause() : ex.getTargetException();
									if (targetException instanceof TransactionException transactionException) {
										// just unwrap and rethrow
										throw transactionException;
									} else if (targetException instanceof EvitaInvalidUsageException evitaInvalidUsageException) {
										// just unwrap and rethrow
										throw evitaInvalidUsageException;
									} else if (targetException instanceof EvitaInternalError evitaInternalError) {
										if (log.isErrorEnabled()) {
											log.error(
												"Internal Evita error occurred in " + evitaInternalError.getErrorCode() +
													": " + evitaInternalError.getPrivateMessage() + "," +
													" arguments: " + printArguments(method, args),
												targetException
											);
										}
										// unwrap and rethrow
										throw evitaInternalError;
									} else {
										if (log.isErrorEnabled()) {
											log.error(
												"Unexpected internal Evita error occurred: " + ex.getCause().getMessage() + ", " +
													" arguments: " + printArguments(method, args),
												targetException == null ? ex : targetException
											);
										}
										throw new GenericEvitaInternalError(
											"Unexpected internal Evita error occurred: " + ex.getCause().getMessage(),
											"Unexpected internal Evita error occurred.",
											targetException == null ? ex : targetException
										);
									}
								} catch (Throwable ex) {
									if (log.isErrorEnabled()) {
										log.error(
											"Unexpected system error occurred: " + ex.getMessage() + "," +
												" arguments: " + printArguments(method, args),
											ex
										);
									}
									throw new GenericEvitaInternalError(
										"Unexpected system error occurred: " + ex.getMessage(),
										"Unexpected system error occurred.",
										ex
									);
								} finally {
									this.insideInvocation.decrementAndGet();
									this.lastCall.set(System.currentTimeMillis());
								}
							};
							if (method.isAnnotationPresent(RepresentsQuery.class)) {
								this.sessionClosedEvent.recordQuery();
							}
							if (method.isAnnotationPresent(RepresentsMutation.class)) {
								this.sessionClosedEvent.recordMutation();
							}
							if (method.isAnnotationPresent(Traced.class)) {
								return tracingContext.executeWithinBlockIfParentContextAvailable(
									"session call - " + method.getName(),
									invocation,
									() -> {
										final Parameter[] parameters = method.getParameters();
										final SpanAttribute[] spanAttributes = new SpanAttribute[1 + parameters.length];
										spanAttributes[0] = new SpanAttribute("session.id", this.evitaSession.getId().toString());
										if (args == null) {
											return spanAttributes;
										} else {
											int index = 1;
											for (int i = 0; i < args.length; i++) {
												final Object arg = args[i];
												if (EvitaDataTypes.isSupportedType(parameters[i].getType()) && arg != null) {
													spanAttributes[index++] = new SpanAttribute(parameters[i].getName(), arg);
												}
											}
											return index < spanAttributes.length ?
												Arrays.copyOfRange(spanAttributes, 0, index) : spanAttributes;
										}
									}
								);
							} else {
								return invocation.get();
							}
						},
						this.evitaSession.isRootLevelExecution()
					);
				} finally {
					this.evitaSession.decreaseNestLevel();
				}
			}
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
		@Nonnull EvitaInternalSessionContract proxySession
	) {

	}

	/**
	 * This class represents a collection of sessions that are consuming catalogs in different versions.
	 */
	private static class VersionConsumingSessions {
		/**
		 * ConcurrentHashMap representing a collection of sessions that are consuming catalogs in different versions.
		 * The keys of the map are the versions of the catalogs, and the values are the number of sessions consuming
		 * catalogs in that version.
		 */
		private final ConcurrentHashMap<Long, Integer> versionConsumingSessions = CollectionUtils.createConcurrentHashMap(32);

		/**
		 * Registers a session consuming catalog in the specified version.
		 *
		 * @param version the version of the catalog
		 */
		void registerSessionConsumingCatalogInVersion(@Nonnull Long version) {
			versionConsumingSessions.compute(
				version,
				(k, v) -> v == null ? 1 : v + 1
			);
		}

		/**
		 * Unregisters a session that is consuming a catalog in the specified version.
		 *
		 * @param version the version of the catalog
		 * @return the result of the finalization of the session
		 */
		@Nonnull
		SessionFinalizationResult unregisterSessionConsumingCatalogInVersion(long version) {
			final Integer readerCount = versionConsumingSessions.compute(
				version,
				(k, v) -> v == 1 ? null : v - 1
			);
			if (readerCount == null) {
				final OptionalLong minimalVersion = this.versionConsumingSessions.keySet().stream().mapToLong(Long::longValue).min();
				return new SessionFinalizationResult(minimalVersion.isEmpty() ? null : minimalVersion.getAsLong(), true);
			} else {
				return new SessionFinalizationResult(version, false);
			}
		}

	}

	/**
	 * The result of the finalization of the session.
	 *
	 * @param minimalActiveCatalogVersion the minimal active catalog version used by another session now
	 * @param lastReader                  TRUE when the session was the last reader
	 */
	public record SessionFinalizationResult(
		@Nullable Long minimalActiveCatalogVersion,
		boolean lastReader
	) {
	}

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
		 * Method adds an active session to the internal index.
		 * @param activeSession the active session to be added
		 */
		void addSession(@Nonnull EvitaSessionTuple activeSession) {
			this.activeSessions.put(activeSession.plainSession.getId(), activeSession);
		}

		/**
		 * Method removes an active session from the internal index and returns it.
		 * @param sessionId the unique id of the session
		 * @return the session that was removed or NULL if such session is not found
		 */
		@Nullable
		EvitaSessionTuple removeSession(@Nonnull UUID sessionId) {
			return this.activeSessions.remove(sessionId);
		}

		/**
		 * Returns set of all active (currently open) sessions.
		 */
		@Nonnull
		public Stream<EvitaSessionContract> getActiveSessions() {
			return this.activeSessions.values()
				.stream()
				.map(EvitaSessionTuple::proxySession);
		}
	}

}
