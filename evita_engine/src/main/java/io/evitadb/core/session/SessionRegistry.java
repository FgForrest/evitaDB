/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.EvitaSessionTerminationCallback;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.observability.trace.TracingContext;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.core.session.task.SessionKiller;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.function.Functions;
import io.evitadb.utils.CollectionUtils;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

import static java.util.Optional.of;
import static java.util.Optional.ofNullable;

/**
 * Central coordinator for sessions across all catalogs within a single evitaDB engine instance.
 *
 * This registry provides a lightweight, process‑wide view of active sessions and per‑catalog
 * orchestration via {@link CatalogSessionRegistry} instances. It is created and owned by
 * {@link io.evitadb.core.Evita} and is used from there to:
 *
 * - open new sessions tied to a particular catalog using {@link #createSession(String, BiFunction)}
 * - enumerate or look up active sessions via {@link #getActiveSessions()} and
 *   {@link #getActiveSessionById(UUID)}
 * - coordinate administrative actions that require quiescing activity in a catalog, e.g. closing
 *   all sessions and suspending further operations with
 *   {@link #closeAllSessionsAndSuspend(String, SuspendOperation)}
 * - discard previously established suspension when a catalog goes live again with
 *   {@link #discardSuspension(String)} (this is called by Evita right after a new catalog instance
 *   replaces the live reference)
 * - propagate force‑close information to callers with
 *   {@link #wasSessionForcefullyClosedForCatalog(String, UUID)}
 *
 * The registry maintains a shared {@link CatalogSessionRegistry.SessionRegistryDataStore} that allows
 * fast global lookups of sessions by id while delegating the authoritative per‑catalog logic (session
 * lifecycle, suspension, statistics) to the respective {@link CatalogSessionRegistry} instance.
 *
 * Idle session reaping can be enabled via {@link ServerOptions#closeSessionsAfterSecondsOfInactivity()}.
 * When configured (> 0), a background {@link SessionKiller} task will periodically terminate sessions
 * that have been inactive longer than the configured threshold.
 *
 * Thread‑safety and lifecycle:
 * - The registry is designed to be used concurrently from multiple request threads. Internal maps are
 *   concurrent and session creation/removal is coordinated by the per‑catalog registries.
 * - {@link #close()} is invoked from {@link io.evitadb.core.Evita#close()} to terminate all active
 *   sessions and stop the {@link SessionKiller}.
 *
 * Notes about usage from {@link io.evitadb.core.Evita}:
 * - On session creation, Evita supplies a termination callback that removes the session from its
 *   catalog registry and invokes optional external hooks during internal session creation.
 * - During catalog transitions (activation/deactivation/rename), Evita calls
 *   {@link #closeAllSessionsAndSuspend(String, SuspendOperation)} to ensure no active session holds
 *   references to stale catalog state.
 * - After a new catalog instance replaces the live reference, Evita calls
 *   {@link #discardSuspension(String)} to resume operations for that catalog.
 *
 * This class focuses on coordination and bookkeeping; the heavy lifting (transaction support, read‑
 * only checks, etc.) remains within {@link io.evitadb.core.session.EvitaSession} and the engine.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class SessionRegistry implements AutoCloseable {
	/**
	 * Data store shared among all instances of {@link CatalogSessionRegistry} that holds information about active sessions.
	 */
	private final CatalogSessionRegistry.SessionRegistryDataStore sessionRegistryDataStore = CatalogSessionRegistry.createDataStore();
	/**
	 * Provides the tracing context for tracking the execution flow in the application.
	 **/
	private final TracingContext tracingContext;
	/**
	 * Task that ensures that no inactive session is kept after
	 * {@link io.evitadb.api.configuration.ServerOptions#closeSessionsAfterSecondsOfInactivity()} inactivity timeout.
	 */
	@Getter
	private final SessionKiller sessionKiller;
	/**
	 * Callback that will be called when a new session is created.
	 */
	private final Consumer<EvitaSessionContract> onSessionCreationCallback;
	/**
	 * Callback that will be called when an old session is closed.
	 */
	private final Consumer<EvitaSessionContract> onSessionTerminationCallback;
	/**
	 * Keeps information about session registries for each catalog.
	 * {@link CatalogSessionRegistry} is the primary management service for active sessions, sessions that are stored in
	 * the {@link #sessionRegistryDataStore} map are present only for quick lookup for the session and are actively
	 * updated from the session registry (when the session is closed).
	 */
	private final Map<String, CatalogSessionRegistry> catalogSessionRegistries = CollectionUtils.createConcurrentHashMap(
		64);
	/**
	 * Function that provides access to catalog by its name.
	 */
	private final Function<String, Catalog> catalogAccessor;

	public SessionRegistry(
		@Nonnull ServerOptions serverOptions,
		@Nonnull Scheduler scheduler,
		@Nonnull TracingContext tracingContext,
		@Nonnull Function<String, Catalog> catalogAccessor,
		@Nullable Consumer<EvitaSessionContract> onSessionCreationCallback,
		@Nullable Consumer<EvitaSessionContract> onSessionTerminationCallback
	) {
		this.tracingContext = tracingContext;
		this.catalogAccessor = catalogAccessor;
		this.onSessionCreationCallback = onSessionCreationCallback == null ?
			Functions.noOpConsumer() : onSessionCreationCallback;
		this.onSessionTerminationCallback = onSessionTerminationCallback == null ?
			Functions.noOpConsumer() : onSessionTerminationCallback;
		this.sessionKiller = of(serverOptions.closeSessionsAfterSecondsOfInactivity())
			.filter(it -> it > 0)
			.map(it -> new SessionKiller(it, this, scheduler))
			.orElse(null);
	}

	/**
	 * Method returns active session by its unique id or NULL if such session is not found.
	 */
	@Nonnull
	public Optional<EvitaSessionContract> getActiveSessionById(@Nonnull UUID sessionId) {
		return this.sessionRegistryDataStore.getActiveSessionById(sessionId);
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
		for (CatalogSessionRegistry value : this.catalogSessionRegistries.values()) {
			value.clearTemporaryInformation();
		}
	}

	/**
	 * Discards the suspension state of the session registry associated with the given catalog name, if present.
	 * The method resumes operations for the session registry if it exists for the provided catalog name.
	 *
	 * @param catalogName The name of the catalog whose suspension state should be discarded. Must not be null.
	 */
	public void discardSuspension(@Nonnull String catalogName) {
		ofNullable(this.catalogSessionRegistries.get(catalogName))
			.ifPresent(CatalogSessionRegistry::resumeOperations);
	}

	/**
	 * Retrieves the session registry associated with the specified catalog name.
	 *
	 * @param catalogName the name of the catalog for which the session registry is to be retrieved, must not be null
	 * @return an Optional containing the CatalogSessionRegistry associated with the specified catalog name, or an empty Optional if no registry exists for the given catalog name
	 */
	@Nonnull
	public Optional<CatalogSessionRegistry> getCatalogSessionRegistry(@Nonnull String catalogName) {
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
	 * Registers a session registry for a specific catalog. This ensures that a session
	 * registry is associated with the provided catalog name. If a session registry
	 * for the given catalog name already exists, an error is thrown to prevent overwriting.
	 *
	 * @param catalogName     the name of the catalog to associate with the session registry
	 * @param sessionRegistry the session registry to register with the catalog
	 * @throws GenericEvitaInternalError if a session registry for the specified catalog name already exists
	 */
	public void registerCatalogSessionRegistry(
		@Nonnull String catalogName, @Nonnull CatalogSessionRegistry sessionRegistry) {
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
	public CatalogSessionRegistry registerWithReplaceCatalogSessionRegistry(
		@Nonnull String catalogName, @Nonnull CatalogSessionRegistry sessionRegistry) {
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
	 * Creates a new session for the specified catalog using the provided session creation logic.
	 * This method ensures that the session is properly registered and manages its lifecycle
	 * by invoking the appropriate creation and termination callbacks.
	 *
	 * @param catalogName    the name of the catalog for which the session is to be created; must not be null
	 * @param sessionCreator a functional interface that handles the creation of the session based on
	 *                       the provided {@link CatalogSessionRegistry} and termination callback; must not be null
	 * @return the newly created {@link EvitaInternalSessionContract} associated with the specified catalog
	 */
	@Nonnull
	public EvitaInternalSessionContract createSession(
		@Nonnull String catalogName,
		@Nonnull BiFunction<CatalogSessionRegistry, EvitaSessionTerminationCallback, EvitaInternalSessionContract> sessionCreator
	) {
		return getOrCreateCatalogSessionRegistry(
			catalogName
		).createSession(
			csr -> {
				final EvitaSessionTerminationCallback terminationCallback =
					session -> {
						csr.removeSession((EvitaSession) session);
						this.onSessionTerminationCallback.accept(session);
					};
				final EvitaInternalSessionContract internalSession = sessionCreator.apply(csr, terminationCallback);
				this.onSessionCreationCallback.accept(internalSession);
				return internalSession;
			}
		);
	}

	@Override
	public void close() {
		CompletableFuture.allOf(
			CompletableFuture.runAsync(this::closeAllSessions),
			CompletableFuture.runAsync(() -> {
				if (this.sessionKiller != null) {
					this.sessionKiller.close();
				}
			})
		).join();
	}

	/**
	 * Retrieves the {@link CatalogSessionRegistry} associated with the specified catalog name
	 * if it exists, or creates and registers a new {@link CatalogSessionRegistry} for the catalog name
	 * if none exists.
	 *
	 * @param catalogName the name of the catalog for which the session registry is to be retrieved
	 *                    or created; must not be null
	 * @return the {@link CatalogSessionRegistry} associated with the given catalog name
	 */
	@Nonnull
	private CatalogSessionRegistry getOrCreateCatalogSessionRegistry(@Nonnull String catalogName) {
		return this.catalogSessionRegistries.computeIfAbsent(
			catalogName,
			__ -> new CatalogSessionRegistry(
				this.tracingContext,
				() -> this.catalogAccessor.apply(catalogName),
				this.sessionRegistryDataStore
			)
		);
	}

	/**
	 * Closes all active sessions regardless of target catalog.
	 */
	private void closeAllSessions() {
		final Iterator<CatalogSessionRegistry> sessionRegistryIt = this.catalogSessionRegistries.values().iterator();
		while (sessionRegistryIt.hasNext()) {
			final CatalogSessionRegistry sessionRegistry = sessionRegistryIt.next();
			sessionRegistry.closeAllActiveSessionsAndSuspend(SuspendOperation.REJECT);
			sessionRegistryIt.remove();
		}
	}

}
