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
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.core;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.TransactionContract.CommitBehavior;
import io.evitadb.api.exception.ConcurrentInitializationException;
import io.evitadb.api.exception.TransactionException;
import io.evitadb.api.trace.Traced;
import io.evitadb.api.trace.TracingContext;
import io.evitadb.api.trace.TracingContext.SpanAttribute;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
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
	 * Keeps information about currently active sessions.
	 */
	private final Map<UUID, EvitaSessionTuple> activeSessions = new ConcurrentHashMap<>(64);
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
	 * Returns set of all active (currently open) sessions.
	 */
	public Stream<EvitaInternalSessionContract> getActiveSessions() {
		return activeSessions.values().stream().map(EvitaSessionTuple::proxySession);
	}

	/**
	 * Method returns active session by its unique id or NULL if such session is not found.
	 */
	@Nullable
	public EvitaInternalSessionContract getActiveSessionById(@Nonnull UUID sessionId) {
		return ofNullable(activeSessions.get(sessionId))
			.map(EvitaSessionTuple::proxySession)
			.orElse(null);
	}

	/**
	 * Method closes and removes all active sessions from the registry.
	 * All changes are rolled back.
	 */
	public void closeAllActiveSessions() {
		for (EvitaSessionTuple sessionTuple : activeSessions.values()) {
			final EvitaSession activeSession = sessionTuple.plainSession();
			if (activeSession.isActive()) {
				if (activeSession.isTransactionOpen()) {
					activeSession.setRollbackOnly();
				}
				activeSession.closeNow(CommitBehavior.WAIT_FOR_WAL_PERSISTENCE);
				log.info("There is still active session {} - terminating.", activeSession.getId());
			}
		}
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
			activeSessionsCounter.incrementAndGet();
		} else if (!activeSessionsCounter.compareAndSet(0, 1)) {
			throw new ConcurrentInitializationException(activeSessions.keySet().iterator().next());
		}

		final EvitaSession newSession = sessionSupplier.get();
		final EvitaInternalSessionContract newSessionProxy = (EvitaInternalSessionContract) Proxy.newProxyInstance(
			EvitaInternalSessionContract.class.getClassLoader(),
			new Class[]{EvitaInternalSessionContract.class},
			new EvitaSessionProxy(newSession, tracingContext)
		);
		activeSessions.put(newSession.getId(), new EvitaSessionTuple(newSession, newSessionProxy));
		final long catalogVersion = newSession.getCatalogVersion();
		catalogConsumedVersions.computeIfAbsent(newSession.getCatalogName(), k -> new VersionConsumingSessions())
			.registerSessionConsumingCatalogInVersion(catalogVersion);
		return newSessionProxy;
	}

	/**
	 * Removes session from the registry.
	 */
	public void removeSession(@Nonnull EvitaSessionContract session) {
		if (activeSessions.remove(session.getId()) != null) {
			this.activeSessionsCounter.decrementAndGet();
			final SessionFinalizationResult finalizationResult = catalogConsumedVersions.get(session.getCatalogName())
				.unregisterSessionConsumingCatalogInVersion(session.getCatalogVersion());
			if (finalizationResult.lastReader()) {
				// notify listeners that the catalog version is no longer used
				final Catalog theCatalog = this.catalog.get();
				theCatalog.catalogVersionBeyondTheHorizon(
					finalizationResult.minimalActiveCatalogVersion()
				);
			}
		}
	}

	/**
	 * This handler is an infrastructural handler that delegates all calls to {@link #evitaSession}. We'll pay some
	 * performance price by wrapping {@link EvitaSession} in a proxy, that uses this error handler (all calls on session
	 * object will be approximately 1.7x less performant -
	 * <a href="http://ordinaryjava.blogspot.com/2008/08/benchmarking-cost-of-dynamic-proxies.html">source</a>) but this
	 * way we can isolate the error logging / translation in one place and avoid cluttering the source code. Graal
	 * supports JDK proxies out-of-the-box so this shouldn't be a problem in the future.
	 */
	@RequiredArgsConstructor
	private static class EvitaSessionProxy implements InvocationHandler {
		private final EvitaSession evitaSession;
		private final TracingContext tracingContext;

		@Nullable
		@Override
		public Object invoke(Object proxy, Method method, Object[] args) {
			try {
				evitaSession.increaseNestLevel();
				// invoke original method on delegate
				return Transaction.executeInTransactionIfProvided(
					evitaSession.getOpenedTransaction().orElse(null),
					() -> {
						final Supplier<Object> invocation = () -> {
							try {
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
									log.error(
										"Internal Evita error occurred in " + evitaInternalError.getErrorCode() + ": " + evitaInternalError.getPrivateMessage(),
										targetException
									);
									// unwrap and rethrow
									throw evitaInternalError;
								} else {
									log.error(
										"Unexpected internal Evita error occurred: " + ex.getCause().getMessage(),
										targetException == null ? ex : targetException
									);
									throw new GenericEvitaInternalError(
										"Unexpected internal Evita error occurred: " + ex.getCause().getMessage(),
										"Unexpected internal Evita error occurred.",
										targetException == null ? ex : targetException
									);
								}
							} catch (Throwable ex) {
								log.error("Unexpected system error occurred: " + ex.getMessage(), ex);
								throw new GenericEvitaInternalError(
									"Unexpected system error occurred: " + ex.getMessage(),
									"Unexpected system error occurred.",
									ex
								);
							}
						};
						if (method.isAnnotationPresent(Traced.class)) {
							return tracingContext.executeWithinBlockIfParentContextAvailable(
								"session call - " + method.getName(),
								invocation,
								() -> {
									final Parameter[] parameters = method.getParameters();
									final SpanAttribute[] spanAttributes = new SpanAttribute[1 + parameters.length];
									spanAttributes[0] = new SpanAttribute("session.id", evitaSession.getId().toString());
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
					evitaSession.isRootLevelExecution()
				);
			} finally {
				evitaSession.decreaseNestLevel();
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

}
