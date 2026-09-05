/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025-2026
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

package io.evitadb.core.transaction.engine.operators;


import io.evitadb.api.CatalogContract;
import io.evitadb.api.CatalogState;
import io.evitadb.api.CommitProgress.CommitVersions;
import io.evitadb.api.exception.CatalogGoingLiveException;
import io.evitadb.api.exception.CatalogNotFoundException;
import io.evitadb.api.requestResponse.progress.ProgressingFuture;
import io.evitadb.api.requestResponse.schema.mutation.engine.MakeCatalogAliveMutation;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.catalog.UnusableCatalog;
import io.evitadb.core.engine.CatalogFolderContext;
import io.evitadb.core.engine.ExpandedEngineState;
import io.evitadb.core.metric.event.transaction.CatalogGoesLiveEvent;
import io.evitadb.core.session.SessionRegistry;
import io.evitadb.core.session.SuspendOperation;
import io.evitadb.core.transaction.engine.AbstractEngineStateUpdater;
import io.evitadb.core.transaction.engine.EngineStateUpdater;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.spi.store.engine.model.CatalogFolderId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Activates the specified catalog based on its current state. Throws appropriate exceptions
 * for inactive or corrupted catalogs or any unknown catalog type.
 *
 * **This operator owns the catalog's quiescence, and therefore its resume.** Every route into a go-live passes
 * through here - the facade, the session-driven `EvitaSession#goLiveAndCloseWithProgress`, and a raw
 * `Evita#applyMutation` from the wire - so this is the only place that can close the session already open on the
 * warm-up catalog. It drains the registry **synchronously and before `Catalog#flush()`**, because building the
 * flush future pops the catalog's trapped changes on this very thread: a write landing after that pop is not in
 * the flush, and it lands on a `Catalog` instance `goLive()` is about to supersede while the alive catalog serves
 * from index objects it carries by reference. That is issue #1495.
 *
 * Because the drain publishes a suspension, the operator also carries an undo (`undoOperations` below): a go-live
 * that fails before the ALIVE bootstrap is published puts the warm-up catalog back behind its name and lifts the
 * suspension, instead of leaving the catalog refusing every session for the life of the process.
 *
 * Forward-replay is intentionally **not** implemented here. The completion phase depends on `theCatalog.goLive()`,
 * which rewrites on-disk state and cannot be safely re-executed during recovery. The default `Optional.empty()` in
 * `EngineMutationOperator` causes the transaction manager to wedge loudly.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Slf4j
@RequiredArgsConstructor
public class MakeCatalogAliveMutationOperator implements EngineMutationOperator<CommitVersions, MakeCatalogAliveMutation> {
	private final CatalogFolderContext folderContext;

	@Nonnull
	@Override
	public String getOperationName(@Nonnull MakeCatalogAliveMutation engineMutation) {
		return "Making catalog `" + engineMutation.getCatalogName() + "` alive";
	}

	@Nonnull
	@Override
	public ProgressingFuture<CommitVersions> applyMutation(
		@Nonnull UUID transactionId,
		@Nonnull MakeCatalogAliveMutation mutation, @Nonnull Evita evita,
		@Nonnull Consumer<EngineStateUpdater> transitionEngineStateUpdater,
		@Nonnull Consumer<EngineStateUpdater> completionEngineStateUpdater
	) {
		final String catalogName = mutation.getCatalogName();

		final CatalogContract catalog = evita.getCatalogInstanceOrThrowException(catalogName);
		if (catalog instanceof Catalog theCatalog) {
			final CatalogFolderId catalogFolder = this.folderContext.folderIdFor(catalogName);
			// Ownership of the registry is taken before anything that can fail, so that the undo below knows what
			// it has to resume. Installs a registry when the catalog has none - a catalog nobody has opened a session
			// on has no incumbent to drain, but the registry then exists for the placeholder-vs-suspension pair to
			// agree on, exactly as deactivation and drop arrange it (see `Evita#suspendCatalogSessions`, which this
			// operator cannot use because it has to be able to lift the suspension it takes).
			final Optional<SessionRegistry> sessionRegistry = evita.obtainCatalogSessionRegistry(catalogName);

			transitionEngineStateUpdater.accept(
				new AbstractEngineStateUpdater(transactionId, mutation) {
					@Override
					public ExpandedEngineState apply(long version, @Nonnull ExpandedEngineState expandedEngineState) {
						return ExpandedEngineState
							.builder(expandedEngineState)
							.withVersion(version)
							.withCatalog(
								MakeCatalogAliveMutationOperator.this.folderContext.createUnusableCatalog(
									catalogName, catalogFolder, CatalogState.GOING_ALIVE,
									(cn, folderId, root) -> new CatalogGoingLiveException(cn)
								)
							)
							.build();
					}
				}
			);

			// Set once `goLive()` has returned, i.e. once the ALIVE bootstrap record is durable and the new instance
			// exists. It decides which catalog the undo puts back behind the name.
			final AtomicReference<Catalog> aliveCatalog = new AtomicReference<>();
			final Consumer<Throwable> undoOperations = failure -> {
				final Catalog catalogToRestore = aliveCatalog.get();
				try {
					// An in-place exchange of the instance behind the name, version untouched: nothing is being
					// committed, the operation failed. `setNextEngineState` accepts an unchanged version for exactly
					// this (see `ModifyCatalogSchemaNameMutationOperator`).
					//
					// **Restoring the warm-up instance is right in both cases that reach it, and neither is obvious.**
					// (a) The flush failed: `Catalog#flush` has already called `markUnpublishable` and scheduled a
					// deactivation, so the restored catalog serves readers while every write and every flush refuses
					// with `CatalogUnpublishableException` - which is what happens after any other warm-up flush
					// failure - and the deactivation settles the name. Should that mutation lose the conflict-key race
					// against this operation's own finalization it is logged and not retried, and the catalog then
					// stays published and refusing, which is the property `Catalog#scheduleDeactivation` guarantees.
					// (b) `goLive()` threw after `persistenceService.goLive(1L)` published the ALIVE bootstrap but
					// before the new instance existed: the restored instance is still WARMING_UP in memory, its next
					// flush refuses on the header-state premise, `markUnpublishable` follows, and a reload recovers
					// the catalog in its published ALIVE state. Nothing on disk is damaged in either case.
					transitionEngineStateUpdater.accept(
						new AbstractEngineStateUpdater(transactionId, mutation) {
							@Override
							public ExpandedEngineState apply(
								long version, @Nonnull ExpandedEngineState expandedEngineState
							) {
								return ExpandedEngineState
									.builder(expandedEngineState)
									.withCatalog(catalogToRestore == null ? theCatalog : catalogToRestore)
									.build();
							}
						}
					);
					if (catalogToRestore != null) {
						// Past the point of no return: the ALIVE bootstrap is published, so the catalog IS alive and a
						// reload would say so. What failed is the engine-level record of the transition. Nothing on
						// disk is damaged - see `.claude/rules/durability-model.md`.
						log.error(
							"Catalog `{}` went live, but the engine failed to record the transition - the " +
								"alive catalog is installed anyway; a restart reloads it in its published " +
								"ALIVE state.",
							catalogName, failure
						);
						// Owed here exactly as on the success path, and logged first so that a throw from either
						// call cannot cost the accurate message above. The alive catalog SHARES its
						// `TransactionManager` with the warm-up instance it supersedes (`Catalog:915`), and that
						// manager's `livingCatalog` is what conflict resolution and the write-ahead-log append read.
						// Leaving it pointed at the superseded instance while the resume below lets sessions back in
						// would commit them against the wrong base; the settled notification is what tells the
						// external APIs the catalog is live at all.
						catalogToRestore.notifyCatalogPresentInLiveView();
						evita.notifyCatalogStateSettled(catalogName, CatalogState.ALIVE);
					}
				} catch (Throwable declarationFailure) {
					// `Throwable`, so that a failure putting the catalog back cannot skip the resume below - a catalog
					// left behind a GOING_ALIVE placeholder is bad, a catalog behind that placeholder with its registry
					// suspended for the life of the process is worse.
					log.error(
						"Failed to restore catalog `{}` behind its name after a failed go-live - it stays refusing " +
							"sessions as GOING_ALIVE until the server is restarted.",
						catalogName, declarationFailure
					);
				}
				// Unconditional: `resumeOperations` lifts a suspension if one is standing and does nothing otherwise,
				// and this runs on paths that failed before the drain could suspend as well. Also lifts the suspension
				// the session-driven path (`EvitaSession#goLiveAndCloseWithProgress`) takes before applying this
				// mutation, which used to stay for the life of the process when the go-live failed.
				sessionRegistry.ifPresent(SessionRegistry::resumeOperations);
			};

			try {
				// The drain. An incumbent warm-up session holds the very `Catalog` instance `goLive()` is about to
				// supersede and writes to it in place; closing it here - after its running method returns, and a
				// warm-up close flushes - is what keeps its writes in the flush below. New sessions are already
				// refused by the placeholder; REJECT keeps the registry saying the same.
				//
				// Synchronous and before `flush()` on purpose: building the flush future pops the trapped changes on
				// this thread, so a write landing after it would be lost - which is issue #1495.
				//
				// Runs under the engine state lock, and the drain's five-second cap bounds only the case it is
				// written for: a session whose method has not returned yet, where the close is deferred, no future
				// is waited on and the loop simply times out. A close that DID start and whose flush hangs is
				// waited on unbounded, and holds the engine state lock with it. That is the same exposure the
				// rename operator's drain has (`ModifyCatalogSchemaNameMutationOperator`), not a new one.
				//
				// Idempotent against the session-driven path, which suspends this registry before applying the
				// mutation: a second call under a standing suspension drains nothing and returns.
				sessionRegistry.ifPresent(it -> it.closeAllActiveSessionsAndSuspend(SuspendOperation.REJECT));

				final CatalogGoesLiveEvent event = new CatalogGoesLiveEvent(catalogName);
				return new ProgressingFuture<>(
					1,
					Collections.singletonList(theCatalog.flush()),
					(theFuture, __) -> {
						final Catalog newCatalog = theCatalog.goLive();
						aliveCatalog.set(newCatalog);
						theFuture.updateProgress(1);
						// emit the event
						event.finish().commit();

						completionEngineStateUpdater.accept(
							new AbstractEngineStateUpdater(transactionId, mutation) {
								@Override
								public ExpandedEngineState apply(
									long version, @Nonnull ExpandedEngineState expandedEngineState
								) {
									return ExpandedEngineState
										.builder(expandedEngineState)
										.withVersion(version)
										.withCatalog(newCatalog)
										.build();
								}
							}
						);

						newCatalog.notifyCatalogPresentInLiveView();
						// lifts the suspension this operator (or the session-driven path before it) established
						sessionRegistry.ifPresent(SessionRegistry::resumeOperations);
						// Emit the host event AFTER the live-view callback so the system CDC stream
						// reflects the ALIVE settlement strictly after the underlying mutation.
						evita.notifyCatalogStateSettled(catalogName, CatalogState.ALIVE);

						return new CommitVersions(newCatalog.getVersion(), newCatalog.getSchema().version());
					},
					undoOperations
				);
			} catch (Throwable ex) {
				// `Throwable` rather than `RuntimeException`: an `Error` escaping the drain or the flush without
				// reaching `undoOperations` leaves the registry suspended and the placeholder standing for the life
				// of the process. Rethrown unchanged.
				undoOperations.accept(ex);
				throw ex;
			}
		} else if (catalog instanceof UnusableCatalog unusableCatalog) {
			throw unusableCatalog.getRepresentativeException();
		} else if (catalog == null) {
			throw new CatalogNotFoundException(catalogName);
		} else {
			throw new EvitaInvalidUsageException("Unknown catalog type: `" + catalog.getClass() + "`!");
		}
	}

}
