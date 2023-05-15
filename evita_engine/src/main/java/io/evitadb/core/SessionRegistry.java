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
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.exception.ConcurrentInitializationException;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.exception.EvitaInvalidUsageException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
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
final class SessionRegistry {
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
	 * Returns set of all active (currently open) sessions.
	 */
	public Stream<EvitaInternalSessionContract> getActiveSessions() {
		return activeSessions.values().stream().map(EvitaSessionTuple::proxySession);
	}

	/**
	 * Method returns active session by its unique id or NULL if such session is not found.
	 */
	@Nullable
	public EvitaInternalSessionContract getActiveSessionById(UUID sessionId) {
		return ofNullable(activeSessions.get(sessionId))
			.map(EvitaSessionTuple::proxySession)
			.orElse(null);
	}

	/**
	 * Method closes and removes all active sessions from the registry.
	 * All changes are rolled back.
	 */
	public void closeAllActiveSessions() {
		final Iterator<EvitaSessionTuple> sessionIt = activeSessions.values().iterator();
		while (sessionIt.hasNext()) {
			final EvitaSessionTuple sessionTuple = sessionIt.next();
			final EvitaSession activeSession = sessionTuple.plainSession();
			if (activeSession.isActive()) {
				if (activeSession.isTransactionOpen()) {
					activeSession.setRollbackOnly();
				}
				activeSession.close();
				activeSessionsCounter.decrementAndGet();
				log.info("There is still active session {} - terminating.", activeSession.getId());
			}
			sessionIt.remove();
		}
	}

	/**
	 * Creates and registers new session to the registry.
	 * Method checks that there is only a single active session when catalog is in warm-up mode.
	 */
	@Nonnull
	public EvitaInternalSessionContract addSession(boolean warmUp, Supplier<EvitaSession> sessionSupplier) {
		if (warmUp) {
			activeSessionsCounter.incrementAndGet();
		} else if (!activeSessionsCounter.compareAndSet(0, 1)) {
			throw new ConcurrentInitializationException(activeSessions.keySet().iterator().next());
		}

		final EvitaSession newSession = sessionSupplier.get();
		final EvitaInternalSessionContract newSessionProxy = (EvitaInternalSessionContract) Proxy.newProxyInstance(
			EvitaInternalSessionContract.class.getClassLoader(),
			new Class[]{EvitaInternalSessionContract.class},
			new EvitaSessionProxy(newSession)
		);
		activeSessions.put(newSession.getId(), new EvitaSessionTuple(newSession, newSessionProxy));
		return newSessionProxy;
	}

	/**
	 * Removes session from the registry.
	 */
	public void removeSession(@Nonnull EvitaSessionContract session) {
		if (activeSessions.remove(session.getId()) != null) {
			activeSessionsCounter.decrementAndGet();
		}
	}

	/**
	 * Method updates reference to the catalog in all active session it keeps track of.
	 */
	public void updateCatalogReference(@Nonnull CatalogContract catalog) {
		activeSessions.values()
			.stream()
			.map(EvitaSessionTuple::plainSession)
			// except those with active transaction which are protected by SNAPSHOT isolation
			.filter(it -> !it.isTransactionOpen())
			.forEach(it -> it.updateCatalogReference(catalog));
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

		@Override
		public Object invoke(Object proxy, Method method, Object[] args) {
			// invoke original method on delegate
			return Transaction.executeInTransactionIfProvided(
				evitaSession.getOpenedTransaction().orElse(null),
				() -> {
					try {
						return method.invoke(evitaSession, args);
					} catch (InvocationTargetException ex) {
						// handle the error
						final Throwable targetException = ex.getTargetException();
						if (targetException instanceof EvitaInvalidUsageException evitaInvalidUsageException) {
							// just unwrap and rethrow
							throw evitaInvalidUsageException;
						} else if (targetException instanceof EvitaInternalError evitaInternalError) {
							log.error(
								"Internal Evita error occurred in {}: {}",
								evitaInternalError.getErrorCode(),
								evitaInternalError.getPrivateMessage(),
								targetException
							);
							// unwrap and rethrow
							throw evitaInternalError;
						} else {
							log.error("Unexpected internal Evita error occurred: {}", ex.getCause().getMessage(), ex);
							throw new EvitaInternalError(
								"Unexpected internal Evita error occurred: " + ex.getCause().getMessage(),
								"Unexpected internal Evita error occurred.",
								targetException
							);
						}
					} catch (Throwable ex) {
						log.error("Unexpected system error occurred: {}", ex.getMessage(), ex);
						throw new EvitaInternalError(
							"Unexpected system error occurred: " + ex.getMessage(),
							"Unexpected system error occurred.",
							ex
						);
					}
				}
			);
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

}
