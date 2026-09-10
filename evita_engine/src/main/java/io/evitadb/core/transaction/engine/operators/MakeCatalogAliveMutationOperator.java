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
 * warm-up catalog. It drains the registry **synchronously and before `Catalog#goLive()`**, which is the safety
 * boundary: publishing the ALIVE bootstrap record while a session is still writing to the instance it supersedes
 * leaves those writes on a running catalog that serves them from index objects it carries by reference, and on no
 * bootstrap record at all - issue #1495. Running the drain ahead of `Catalog#flush()` as well is a deliberate
 * preference rather than the safety property; the comment at the drain itself carries what was measured.
 *
 * Because the drain publishes a suspension, the operator also carries an undo (`undoOperations` below), and it has
 * two branches. A failure arriving **before** the new alive instance is recorded puts the warm-up catalog back
 * behind its name - whether or not the ALIVE bootstrap record was published, because a warm-up instance that
 * outlived its own publication refuses every write and every flush. A failure arriving **after** it can only be
 * the engine's record of a transition that is already durable, so that branch installs the **alive** catalog
 * instead and re-runs its notifications: a committed go-live is never rolled back by its own bookkeeping. Both
 * branches lift the suspension, so the catalog is never left refusing every session for the life of the process.
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
				// Tells the two failures below apart, exactly as `ModifyCatalogSchemaNameMutationOperator` tells
				// them apart with its own `declared` flag: a state updater that never ran leaves the catalog behind
				// its placeholder, while a notification failing after it leaves a catalog that is published and
				// serving. The advice an operator needs is the opposite in the two cases.
				boolean restored = false;
				try {
					// An in-place exchange of the instance behind the name, version untouched: nothing is being
					// committed, the operation failed. `setNextEngineState` accepts an unchanged version for exactly
					// this (see `ModifyCatalogSchemaNameMutationOperator`).
					//
					// **Restoring the warm-up instance is right in both cases that reach it, and neither is obvious.**
					// (a) The flush failed: `Catalog#flush` has already called `markUnpublishable` and scheduled a
					// deactivation, so the restored catalog serves readers while every write and every flush refuses
					// with `CatalogUnpublishableException` - which is what happens after any other warm-up flush
					// failure - and the deactivation settles the name. That mutation loses the conflict-key race
					// against this operation's own finalization, which is exactly the case `Catalog#scheduleDeactivation`
					// retries: the key is released as this operation finalizes and a later attempt takes it. Should
					// every attempt lose it, the failure is logged and the catalog stays published and refusing, which
					// is the property that method guarantees whether or not the deactivation ever lands.
					// (b) The ALIVE bootstrap is published but the new instance never reached `aliveCatalog`, and two
					// failures land in it. `goLive()` itself threw past `persistenceService.goLive(1L)`: its own catch
					// has already recorded the unpublishable cause and scheduled the deactivation, so the restored
					// instance serves readers and refuses every write and every flush at `assertPublishable()` straight
					// away. Or one of the two `CommitVersions` reads threw with `goLive()` already returned: the
					// restored instance is still publishable in memory, and its next flush refuses on the header-state
					// premise - the stored header now says ALIVE - with `markUnpublishable` following through the flush
					// future's failure handler. Both end the same way, with a reload recovering the catalog in its
					// published ALIVE state. Nothing on disk is damaged on any of these paths.
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
					restored = true;
					if (catalogToRestore != null) {
						// Past the point of no return: the ALIVE bootstrap is published, so the catalog IS alive and a
						// reload would say so. What failed is the engine-level record of the transition, and only
						// that: everything AFTER the completion updater is best-effort and locally caught, so a
						// failure arriving here with the alive catalog in hand can only have come from the updater
						// itself - which is what makes the message below accurate rather than a guess. Nothing on
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
						// manager's `livingCatalog` is what conflict resolution resolves every incoming
						// transaction's schemas and versions against (`TransactionManager#examineConflictKey`).
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
					//
					// Branched on `restored`, because the state updater above is the line that decides which of two
					// very different situations the operator is in - and the wrong message sends them looking for a
					// wedged catalog that is in fact serving.
					if (restored) {
						log.error(
							"Catalog `{}` is back behind its name after a failed go-live and serves sessions again, " +
								"but the live view or the change data capture stream was not told - until the server " +
								"is restarted, conflict resolution may resolve against the superseded instance and " +
								"the external APIs may not know the catalog settled. Nothing on disk is damaged.",
							catalogName, declarationFailure
						);
					} else {
						log.error(
							"Failed to restore catalog `{}` behind its name after a failed go-live - it stays " +
								"refusing sessions as GOING_ALIVE until the server is restarted.",
							catalogName, declarationFailure
						);
					}
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
				// **The safety boundary is `goLive()`, not `flush()`.** What must not happen is the ALIVE bootstrap
				// record being published while a session is still writing to the instance it supersedes: past that
				// point nothing can persist those writes, the running catalog serves them from index objects it
				// carries by reference, and a reload has never heard of them - issue #1495. The drain therefore has
				// to complete before `theCatalog.goLive()` below, and it does.
				//
				// **Running it before `flush()` is a deliberate preference, not the safety property**, and the
				// difference was measured rather than reasoned: with the drain moved into the completion lambda but
				// still ahead of `goLive()`, the whole suite stays green, because a forced close performs its own
				// warm-up flush and persists whatever this flush's pop missed; only moving it past `goLive()`
				// reproduces the defect. The preference is worth keeping regardless - it is one flush cheaper, and
				// it does not make an incumbent's writes depend on that forced close's own flush succeeding - but
				// do not re-derive a guarantee from the placement that the placement does not carry.
				//
				// Runs under the engine state lock, and the drain is bounded end to end by
				// `SessionRegistry#DRAIN_GIVE_UP_TIMEOUT_MILLIS` - both a session whose method has not returned and
				// a close already under way, whose completion is awaited for whatever is left of that budget rather
				// than indefinitely. Expiry is a failure, not a partial success: the drain fails its premise, and
				// `undoOperations` below restores the warm-up catalog behind its name and lifts the suspension. So
				// the lock is held for a bounded time and a hung close costs this go-live rather than every engine
				// mutation behind it.
				//
				// Idempotent against the session-driven path, which suspends this registry before applying the
				// mutation: a second call under a standing suspension drains nothing and returns.
				//
				// CALIBRATION - the give-up path of this drain is swept by `LongRunningCatalogGoLiveDrainTimeoutTest`;
				// `SessionRegistry#DRAIN_GIVE_UP_TIMEOUT_MILLIS` carries the full statement.
				sessionRegistry.ifPresent(it -> it.closeAllActiveSessionsAndSuspend(SuspendOperation.REJECT));

				// A warm-up catalog whose schema does not validate must not go live, and this is the last place that
				// can say so: the flush below publishes, and `goLive()` after it publishes the ALIVE bootstrap
				// record. An ordinary warm-up session close refuses exactly this state - but the session-driven
				// go-live never reaches that check, because `EvitaSession#goLiveAndCloseWithProgress` terminates its
				// own session through `executeTerminationSteps` rather than through `closeInternal`, where the
				// validation lives. All three go-live entry points funnel through this operator, so the check sits
				// here, ahead of the first publication, and raises the same barrier an ordinary close would.
				// caught as the whole `EvitaInvalidUsageException` family for the same reason, and with the same
				// caveat, as the warm-up close in `EvitaSession#closeInternal`: `validate()` refuses in two
				// vocabularies, and only the narrower one is a `SchemaAlteringException`
				try {
					theCatalog.getSchema().validate();
				} catch (EvitaInvalidUsageException ex) {
					theCatalog.markUnpublishableDueToInvalidSchema(ex);
					throw ex;
				}

				final CatalogGoesLiveEvent event = new CatalogGoesLiveEvent(catalogName);
				return new ProgressingFuture<>(
					1,
					Collections.singletonList(theCatalog.flush()),
					(theFuture, __) -> {
						final Catalog newCatalog = theCatalog.goLive();
						// **Read here, before the alive catalog is recorded**, and therefore long before the commit
						// boundary further down. Both values are available the moment `goLive()` returns, and both
						// getters go through `Objects.requireNonNull` (`Catalog#getVersion`, `Catalog#getSchema`),
						// so they CAN throw. On this side of `aliveCatalog.set` a throw reaches `undoOperations`
						// with no alive catalog in hand, which is the undo's documented case (b): the ALIVE
						// bootstrap is published, the warm-up instance goes back behind the name and refuses every
						// further write, and a reload lands on the published ALIVE state. Reading them any later
						// would either undo a committed go-live, or falsify the undo's own claim that a failure
						// carrying the alive catalog came from the completion updater.
						final CommitVersions commitVersions =
							new CommitVersions(newCatalog.getVersion(), newCatalog.getSchema().version());
						aliveCatalog.set(newCatalog);
						// Guarded, because from the line above this failure handler restores an ALIVE catalog rather
						// than the warm-up one - and neither of these two statements is worth failing a go-live whose
						// bootstrap record is already published. `event.finish().commit()` is the half that can
						// realistically throw, since it hands the event to the recording infrastructure. A throwing
						// client observer is NOT the reason: `ProgressRecord#notifyClientObserver` already wraps
						// every observer in a `catch (Throwable)` of its own, so one cannot escape `updateProgress`.
						// Both are guarded anyway, so that the undo's "only the completion updater can have failed"
						// holds by where the boundary is drawn rather than by an enumeration of which statement
						// throws - an enumeration the next edit to this block would silently invalidate.
						try {
							theFuture.updateProgress(1);
							event.finish().commit();
						} catch (Throwable ex) {
							log.error(
								"Catalog `{}` went live, but reporting its progress did not finish - the transition " +
									"itself is unaffected and continues.",
								catalogName, ex
							);
						}

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

						// **The transition is durable from here on**: the completion updater above appended the
						// write-ahead-log entry and published the engine bootstrap record. Everything below is
						// bookkeeping, and every line of it is best-effort for that reason - a throw would otherwise
						// reach `undoOperations`, which would tell the client a committed go-live failed, re-run these
						// same notifications and log that the engine did not record a transition it did record. Same
						// idiom as the rename operator's post-commit block: log and carry on, with the resume in a
						// `finally` so a failed notification cannot cost it.
						try {
							newCatalog.notifyCatalogPresentInLiveView();
						} catch (Throwable ex) {
							// Says the same thing as the undo's comment above, and it has to: the alive catalog
							// shares its `TransactionManager` with the instance it supersedes, so a live view that
							// was not told leaves conflict resolution resolving against the superseded instance -
							// writes against a stale base, not merely queries reading one. The resume in the
							// `finally` happens anyway, and that is the lesser evil: refusing it would trade a
							// stale base for a catalog that answers `InstanceTerminatedException` to every session
							// for the life of the process, while the stale base is repaired by a restart.
							log.error(
								"Catalog `{}` is alive and that is durable, but the live view was not told - until " +
									"the server is restarted its transaction manager may keep resolving against " +
									"the superseded instance, and sessions are let back in regardless. Nothing on " +
									"disk is damaged.",
								catalogName, ex
							);
						} finally {
							// Owed unconditionally once the transition has committed: a registry left suspended
							// answers `InstanceTerminatedException` to every session for the life of the process,
							// beneath an operation that reported success. Lifts the suspension this operator - or the
							// session-driven path before it - established.
							//
							// Placed between the two notifications rather than after both, restoring the original
							// order: the live view has to agree the catalog is alive before sessions are let back in,
							// and the change data capture stream must not announce ALIVE while the registry is still
							// refusing. The host event is emitted after this, in its own block.
							sessionRegistry.ifPresent(SessionRegistry::resumeOperations);
						}

						try {
							// Emit the host event AFTER the live-view callback so the system CDC stream
							// reflects the ALIVE settlement strictly after the underlying mutation.
							evita.notifyCatalogStateSettled(catalogName, CatalogState.ALIVE);
						} catch (Throwable ex) {
							log.error(
								"Catalog `{}` is alive and that is durable, but the change data capture stream was " +
									"not told it had settled.",
								catalogName, ex
							);
						}

						return commitVersions;
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
