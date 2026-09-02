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
import io.evitadb.api.exception.CatalogAlreadyPresentException;
import io.evitadb.api.requestResponse.progress.ProgressingFuture;
import io.evitadb.api.requestResponse.schema.mutation.CatalogSchemaMutation.CatalogSchemaWithImpactOnEntitySchemas;
import io.evitadb.api.requestResponse.schema.mutation.engine.ModifyCatalogSchemaNameMutation;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.engine.CatalogFolderContext;
import io.evitadb.core.engine.ExpandedEngineState;
import io.evitadb.core.engine.ExpandedEngineState.Builder;
import io.evitadb.core.exception.CatalogCorruptedException;
import io.evitadb.core.exception.CatalogTransitioningException;
import io.evitadb.core.session.SessionRegistry;
import io.evitadb.core.session.SuspendOperation;
import io.evitadb.core.transaction.engine.AbstractEngineStateUpdater;
import io.evitadb.core.transaction.engine.EngineStateUpdater;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.spi.store.catalog.persistence.CatalogHandoverFailedException;
import io.evitadb.spi.store.engine.model.CatalogFolderId;
import io.evitadb.utils.Assert;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static io.evitadb.utils.Assert.isTrue;

/**
 * Replaces or renames existing catalog in evitaDB.
 *
 * Both are the same operation and both are a **pointer swap**: the target name is bound to the folder the
 * source catalog already occupies, the source name stops naming anything, and — on a replace — the folder the
 * target used to occupy is tombstoned for deletion. No folder is created, none is moved, nothing is copied. The
 * only disk work is rewriting the catalog name stored *inside* the folder, which `replaceWith` does before the
 * commit, and deleting the superseded folder, which happens after it and is allowed to fail.
 *
 * That ordering is what bounds the damage a crash can do. Before the commit nothing has been repointed, so both
 * catalogs are untouched and the operation simply did not happen — the contract's warning that the source is
 * "unknown and should be treated as damaged" no longer describes any failure that is not a crash of the commit
 * itself. After it, the worst residue is a folder that outlived its tombstone, which the next boot drains.
 *
 * Forward-replay **is** implemented — see `replayCompletionState`. What made it possible is that ordering: the
 * disk work is idempotent and already done by the time the commit record exists, so replay is purely the three
 * state edits, with a placeholder standing in for the catalog instance the crash lost. It still declines rather
 * than guesses where the recorded state cannot be rebuilt safely — an unbound source, or two names resolving to
 * one folder — and those paths return `Optional.empty()`, which wedges the transaction manager loudly.
 *
 * That matters to anyone reasoning about a failed rename: a commit record left durable at `walV == stateV + 1`
 * is **completed** by the next boot, not discarded. A failure the engine reports and declares in memory can
 * therefore still turn into a finished rename after a restart.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Slf4j
@RequiredArgsConstructor
public class ModifyCatalogSchemaNameMutationOperator implements EngineMutationOperator<CommitVersions, ModifyCatalogSchemaNameMutation> {
	/**
	 * How far down a failure's cause chain the handover marker is looked for. Generous enough for the few
	 * wrappers a nested future adds, and finite so a self-referencing chain cannot hang the failure path.
	 */
	private static final int MAX_INSPECTED_CAUSE_DEPTH = 32;

	private final CatalogFolderContext folderContext;

	@Nonnull
	@Override
	public String getOperationName(@Nonnull ModifyCatalogSchemaNameMutation engineMutation) {
		if (engineMutation.isOverwriteTarget()) {
			return "Replacing catalog `" + engineMutation.getCatalogName() + "` with `" + engineMutation.getNewCatalogName() + "`";
		} else {
			return "Renaming catalog `" + engineMutation.getCatalogName() + "` to `" + engineMutation.getNewCatalogName() + "`";
		}
	}

	@Nonnull
	@Override
	public ProgressingFuture<CommitVersions> applyMutation(
		@Nonnull UUID transactionId,
		@Nonnull ModifyCatalogSchemaNameMutation mutation,
		@Nonnull Evita evita,
		@Nonnull Consumer<EngineStateUpdater> transitionEngineStateUpdater,
		@Nonnull Consumer<EngineStateUpdater> completionEngineStateUpdater
	) {
		if (mutation.isOverwriteTarget()) {
			final String catalogNameToBeReplacedWith = mutation.getCatalogName();
			final String catalogNameToBeReplaced = mutation.getNewCatalogName();
			final CatalogContract catalogToBeReplaced = evita.getCatalogInstance(catalogNameToBeReplaced).orElse(null);
			final CatalogContract catalogToBeReplacedWith = evita.getCatalogInstanceOrThrowException(catalogNameToBeReplacedWith);
			return doReplaceCatalogInternal(
				catalogNameToBeReplaced, catalogNameToBeReplacedWith,
				catalogToBeReplaced, catalogToBeReplacedWith,
				transactionId, mutation, evita, transitionEngineStateUpdater, completionEngineStateUpdater
			);
		} else {
			final String currentName = mutation.getCatalogName();
			final String newName = mutation.getNewCatalogName();
			isTrue(!evita.getCatalogNames().contains(newName), () -> new CatalogAlreadyPresentException(newName, newName));
			final CatalogContract catalogToBeRenamed = evita.getCatalogInstanceOrThrowException(currentName);
			return doReplaceCatalogInternal(
				newName, currentName,
				catalogToBeRenamed, catalogToBeRenamed,
				transactionId, mutation, evita, transitionEngineStateUpdater, completionEngineStateUpdater
			);
		}
	}

	/**
	 * Internal shared implementation of catalog replacement used both from rename and replace existing catalog methods.
	 */
	@Nonnull
	protected ProgressingFuture<CommitVersions> doReplaceCatalogInternal(
		@Nonnull String catalogNameToBeReplaced,
		@Nonnull String catalogNameToBeReplacedWith,
		@Nullable CatalogContract catalogToBeReplaced,
		@Nonnull CatalogContract catalogToBeReplacedWith,
		@Nonnull UUID transactionId,
		@Nonnull ModifyCatalogSchemaNameMutation mutation,
		@Nonnull Evita evita,
		@Nonnull Consumer<EngineStateUpdater> transitionEngineStateUpdater,
		@Nonnull Consumer<EngineStateUpdater> completionEngineStateUpdater
	) {
		// Obtained rather than merely looked up, so that a catalog which has no registry gets one installed here:
		// registries are built lazily by the first session, so a catalog nobody has opened one on since boot has
		// none at all, and suspending "whatever is registered" then suspends nothing. A session racing the
		// operation would bind to the very catalog whose persistence service `replace` is about to close, and the
		// completion path - which captured an empty Optional - would neither hand that late registry to the new
		// name nor drain it.
		final Optional<SessionRegistry> prevailingCatalogSessionRegistry =
			evita.obtainCatalogSessionRegistry(catalogNameToBeReplacedWith);
		// Read before the target is obtained below, and used for the rename invariant asserted further down and for
		// nothing else - obtaining installs a registry where a catalog has none, so the same read afterwards could
		// no longer tell "the target already had one" from "this operation has just made one". Always empty when
		// catalogToBeReplaced == catalogToBeReplacedWith.
		final Optional<SessionRegistry> removedCatalogSessionRegistry =
			evita.getCatalogSessionRegistry(catalogNameToBeReplaced);

		final boolean replaceOperation = catalogToBeReplaced != catalogToBeReplacedWith;
		// Owned here, above everything that can fail, and drained further down. Ownership cannot wait for the
		// drain that establishes it: `closeAllActiveSessionsAndSuspend` publishes the suspension before waiting
		// for the sessions to leave, so it throws with that suspension standing. Nor can it wait for anything
		// else inside the `try` - the surviving catalog's own drain runs ahead of it and can spend five seconds
		// failing, and throughout all of it the target is a live, unquiesced catalog. A session opened against it
		// in that window installs a registry through the same `computeIfAbsent`, and an undo that had not
		// recorded ownership would mistake that registry for its own leftover and unpublish it - splitting the
		// catalog's session bookkeeping across two registries and hiding one of them from every later quiesce.
		final Optional<SessionRegistry> quiescedTargetRegistry = replaceOperation ?
			evita.obtainCatalogSessionRegistry(catalogNameToBeReplaced) : Optional.empty();

		// **Whether the storage handover has run**, which is what separates compensating from declaring - and it
		// is recorded as a fact here rather than read off the failure, because past the handover most failures
		// are raised by code that has never heard of it: the engine-state commit, the premise check guarding the
		// registry handoff, the bookkeeping either side of them. Marking each such site individually was tried
		// and does not hold - three successive review rounds found three further unmarked exits, each one a
		// catalog served through a persistence service the handover had already closed. Asking "did we get that
		// far" once, where the answer is known, closes the class rather than another instance of it.
		final AtomicBoolean handoverCompleted = new AtomicBoolean();
		// The replacement catalog, once the handover has built one. Recorded so the declaration below can close
		// it: it holds the *new* persistence service, opened by `replaceWith` and handed to a catalog that the
		// failed commit never published, so nothing else will ever close it.
		final AtomicReference<CatalogContract> replacementCatalog = new AtomicReference<>();

		final Consumer<Throwable> undoOperations = failure -> {
			// **Past the point of no return, compensating is the wrong verb.** Everything below puts session
			// bookkeeping back, which is the whole of what a failure before the handover disturbed. Once the
			// handover has relabelled the folder, it has disturbed something no bookkeeping reaches: the
			// folder's stored identity no longer agrees with engine state, and the commit that would have
			// settled the disagreement is never going to run. Resuming its sessions regardless is how a
			// rename that merely failed becomes a catalog that serves reads, accepts a write, appends it to
			// the write-ahead log and then wedges the next boot replaying it against a name engine state has
			// never heard of - the very symptom this issue is named after, re-created by the code that fixes
			// it.
			//
			// So the name is declared unusable instead, and **declared before the resume below**, never
			// after: the resume is what lets the waiting callers through, and there must be nothing usable
			// on the other side of it when they arrive. What they get is a `CatalogCorruptedException`
			// naming the handover as its cause - refused, legibly, until a restart rebuilds the catalog
			// from its folder, which it can, because the commit never ran and the load path reconciles the
			// name the folder was left carrying.
			//
			// **Nothing that reaches here can be a committed operation.** Neither the engine commit nor the
			// completion work after it reports failure any more - the commit is best-effort and logged from
			// the moment it becomes durable, and the block that follows it is wrapped for the same reason - so
			// a failure arriving here is always one the durable state has no record of. That is what lets this
			// path choose between exactly two answers instead of having to recognise a third.
			//
			// Either the storage layer marked the failure, or the handover had already completed and whatever
			// failed afterwards is past the same line without knowing it. The latch is the load-bearing half:
			// the marker only ever covers failures raised *inside* the handover.
			if (handoverCompleted.get() || isHandoverFailure(failure)) {
				// Routed through the *transition* updater rather than mutating engine state directly, because
				// this is a read-derive-write on shared state and every other writer performs it under
				// `EngineTransactionManager#engineStateLock`. A bare CAS loses to them by construction: a
				// concurrent engine mutation reads the state, appends and fsyncs its WAL record, then calls
				// `setNextEngineState`, whose accumulator returns its own derived value regardless of what
				// landed in between. A declaration made outside the lock inside that window is simply erased -
				// and the window spans an fsync - which puts the damaged catalog back to serving with nothing
				// to show that it ever stopped. This is the idiom `SetCatalogStateMutationOperator` already
				// uses to publish its placeholder.
				//
				// **A deliberate exception to the updater's usual timing**, and the only one in the codebase:
				// `EngineMutationOperator#applyMutation` describes this updater as running once before the heavy
				// work, and this operator instead runs it zero times on the path that succeeds and once here, on
				// the path that cannot be compensated for. The contract is written that way because the updater
				// is the only route that holds the engine state lock, and a terminal declaration needs that lock
				// for the same reason a pre-mutation transition does.
				//
				// The version deliberately stays where it is: nothing is being committed here, the operation
				// failed. `setNextEngineState` accepts an unchanged version, so the swap is an in-place
				// exchange of the instance behind the name rather than a state version a failed operation has
				// no business consuming.
				//
				// Wrapped so that a failure *declaring* the failure cannot cancel the resumes below. Every
				// throw in here should be impossible, and if one happens anyway the worst outcome available is
				// a catalog left serving - not both registries suspended for the life of the process, which is
				// the very symptom this operation exists to stop producing.
				boolean declared = false;
				try {
					transitionEngineStateUpdater.accept(
						new AbstractEngineStateUpdater(transactionId, mutation) {
							@Nonnull
							@Override
							public ExpandedEngineState apply(
								long version, @Nonnull ExpandedEngineState expandedEngineState
							) {
								return ExpandedEngineState
									.builder(expandedEngineState)
									.withCatalog(
										ModifyCatalogSchemaNameMutationOperator.this.folderContext
											.createUnusableCatalog(
												catalogNameToBeReplacedWith,
												CatalogState.CORRUPTED,
												(cn, folderId, root) ->
													new CatalogCorruptedException(cn, folderId, root, failure)
											)
									)
									.build();
							}
						}
					);
					evita.notifyCatalogStateSettled(catalogNameToBeReplacedWith, CatalogState.CORRUPTED);
					declared = true;
				} catch (Throwable declarationFailure) {
					// `Throwable`, because narrowing this to `RuntimeException` does not make an `Error` any
					// louder: it would be caught by `ProgressingFuture#completeExceptionally` and logged under
					// a generic message anyway, and the only thing the narrower catch buys is skipping the
					// resumes below - leaving both registries suspended for the life of the process, which
					// this comment's own reasoning ranks as the worse of the two outcomes.
					log.error(
						"Failed to declare catalog `{}` unusable after its handover failed past the point of no " +
							"return - it stays published and may serve sessions against storage that no longer " +
							"agrees with the engine state, until the server is restarted.",
						catalogNameToBeReplacedWith, declarationFailure
					);
				}
				// The declaration swapped an `UnusableCatalog` in behind the name, so nothing reaches the
				// instances that used to answer to it - but dropping the last reference to a catalog does not
				// close what it holds open, and a failed handover can leave *two* services open. Best-effort
				// and after the declaration, never before it: releasing handles is hygiene, and a failure to
				// do it must not cost the refusal that keeps the damaged catalog off the wire.
				//
				// **Only once the declaration actually succeeded.** If it did not, the catalog is still
				// published and still serving - the worst case the log above accepts - and terminating it then
				// would convert "left serving" into "published and throwing", which is worse than either.
				if (declared) {
					// The original service, still open when the failure landed before `replaceWith` reached its
					// own `close()` - the header write and the bootstrap publish are both inside that window.
					// Guarded rather than caught, so the already-closed windows do not log a warning about
					// handles that are not held.
					if (!catalogToBeReplacedWith.isTerminated()) {
						terminateQuietly(catalogToBeReplacedWith, catalogNameToBeReplacedWith);
					}
					// The *replacement* service, which is what leaks when the handover succeeded and the commit
					// failed: `replaceWith` opened it and handed it to a catalog the commit never published, so
					// nothing else holds a reference that would ever close it.
					final CatalogContract replacement = replacementCatalog.get();
					if (replacement != null && !replacement.isTerminated()) {
						terminateQuietly(replacement, catalogNameToBeReplaced);
					}
				}
			}
			// What belongs under the target name is decided by what answered to it when the operation started,
			// never by what answers to it now.
			if (quiescedTargetRegistry.isPresent()) {
				// A replace onto a name that already had a catalog: its registry has held that name throughout -
				// the completion phase deliberately does not displace it before the commit - so there is nothing
				// to put back, only a suspension to lift.
				//
				// Resumed **in place**, never unpublished: a drain can give up with sessions still inside it, and
				// a registry that cannot be reached through the map is invisible to every later quiesce - replace,
				// delete, engine shutdown - so those sessions would outlive the catalog they are bound to.
				//
				// A replace quiesces its target with REJECT before touching anything, and a failure leaves that
				// target standing - the operation changed nothing. Resuming it is therefore part of undoing, or
				// the catalog that was *not* replaced spends the rest of the process answering
				// `InstanceTerminatedException` to every session opened against it. Safe on the paths that failed
				// before the drain ran, because `resumeOperations` lifts a suspension if one is standing and does
				// nothing at all otherwise.
				quiescedTargetRegistry.get().resumeOperations();
			} else {
				// A rename, whose target names nothing until the commit lands, or a replace onto a name that names
				// no catalog. Nothing outside this operation can have installed a registry under such a name -
				// session creation refuses a name it cannot resolve to a catalog - so whatever answers to it now is
				// the registry the completion phase published ahead of the commit, and it goes back with the rest
				// of it. Anyone already waiting on it is released by the resume below and then finds that the name
				// still resolves to no catalog, which is the answer a failed operation owes them. The surviving
				// catalog keeps serving through the source name, whose registry stays published until the commit.
				evita.removeCatalogSessionRegistryIfPresent(catalogNameToBeReplaced);
			}
			// and revert the suspension the operation opened with. Resuming used to happen on the success path
			// only, so any failure between the suspend above and that resume left the surviving catalog listed
			// but unusable: every session against it answered `SessionBusyException` for the life of the
			// process, with no way back short of a restart. That is the session half of issue #1414, and it
			// outlived the folder decoupling that removed the operation's other failure modes.
			//
			// Unconditional because `resumeOperations` clears a suspension if one is standing and does nothing
			// otherwise - and this runs on paths that failed before the suspend could take effect as well.
			prevailingCatalogSessionRegistry.ifPresent(SessionRegistry::resumeOperations);
		};

		try {
			// Drained inside the `try`, so that a drain which gives up reaches `undoOperations` at all.
			// `closeAllActiveSessionsAndSuspend` publishes the suspension and only then waits for the sessions to
			// leave, and throws when they do not - with that suspension standing. Published before the `try`
			// began, as it used to be, a session that outlasted the five-second drain left the surviving catalog
			// answering `SessionBusyException` to everything for the rest of the process.
			prevailingCatalogSessionRegistry.ifPresent(
				sessionRegistry -> sessionRegistry.closeAllActiveSessionsAndSuspend(SuspendOperation.POSTPONE)
			);

			// Both folders are resolved here, in the read-only phase, and never again. The commit below repoints
			// the target name at the source folder, so re-reading either afterwards would answer about the world
			// the commit has just created rather than the one it acted on.
			final CatalogFolderId prevailingFolderId = this.folderContext.folderIdFor(catalogNameToBeReplacedWith);
			final CatalogFolderId supersededFolderId = replaceOperation && catalogToBeReplaced != null ?
				this.folderContext.folderIdFor(catalogNameToBeReplaced) : null;
			// The one failure in this operation that destroys data rather than merely failing: if the two names
			// resolved to the same folder, the commit below would tombstone the folder it has just bound the
			// surviving catalog to, and the delete that follows would take the live data with it. Two names
			// cannot share a binding by construction - `withoutCatalog` drops the old one in the same build that
			// installs the new one - so reaching this is a broken invariant, and asserting is far cheaper than
			// discovering it from an empty catalog.
			Assert.isPremiseValid(
				supersededFolderId == null || !supersededFolderId.equals(prevailingFolderId),
				() -> new GenericEvitaInternalError(
					"Refusing to replace catalog `" + catalogNameToBeReplaced + "` with `" +
						catalogNameToBeReplacedWith + "`: both resolve to storage folder `" +
						prevailingFolderId.id() + "`, so retiring the superseded folder would destroy the " +
						"surviving catalog!"
				)
			);
			// first terminate the catalog that is being replaced (unless it's the very same catalog)
			if (replaceOperation) {
				// The registry was obtained above, before anything that can fail; only the drain belongs here.
				// Draining it is what stops sessions being served against a catalog that is being destroyed, and
				// the registry it drains may be one this operation installed - a target nobody has opened a
				// session on since boot has none, so a name-keyed lookup would have quiesced nothing at all.
				//
				// Kept apart from `removedCatalogSessionRegistry`, which stays the *pre-existing* registry: that
				// Optional is empty exactly when this operation, rather than an earlier session, put the registry
				// there, and undo resumes such a registry in place rather than restoring anything.
				quiescedTargetRegistry.ifPresent(
					sessionRegistry -> sessionRegistry.closeAllActiveSessionsAndSuspend(SuspendOperation.REJECT)
				);
			} else {
				Assert.isPremiseValid(removedCatalogSessionRegistry.isEmpty(), "Expectation failed!");
			}

			final CatalogSchemaWithImpactOnEntitySchemas updatedSchemaWrapper = mutation.mutate(catalogToBeReplacedWith.getSchema());
			Assert.isPremiseValid(
				updatedSchemaWrapper != null,
				"Result of modify catalog schema mutation must not be null."
			);

			return new ProgressingFuture<>(
				1,
				Collections.singleton(
					catalogToBeReplacedWith
						.replace(
							updatedSchemaWrapper.updatedCatalogSchema(),
							catalogToBeReplaced
						)
				),
				(theFuture, replacedCatalogs) -> {
					// Reaching here at all means the nested `replace(...)` completed, so the folder has been
					// relabelled, the previous persistence service closed and the source catalog's schema
					// exchanged for the target's. **Recorded as the first statement**, before anything below can
					// fail: everything from this line on is as irreversible as the handover itself, whether or
					// not the failure that interrupts it knows so.
					handoverCompleted.set(true);
					final CatalogContract replacedCatalog = replacedCatalogs.iterator().next();
					replacementCatalog.set(replacedCatalog);

					// The registry the target name is to be served through once this operation completes. It shares
					// its active sessions, its suspension and its registration gate with the registry it is derived
					// from, so it is already suspended and stays so until the resume at the end.
					final SessionRegistry targetRegistry = prevailingCatalogSessionRegistry
						.map(
							sessionRegistry -> sessionRegistry.withDifferentCatalogSupplier(
								() -> (Catalog) evita.getCatalogInstanceOrThrowException(catalogNameToBeReplaced)
							)
						)
						.orElse(null);

					// **When the target name has no registry of its own, it gets this one before the commit.** The
					// commit is what makes that name resolve, and it does so synchronously -
					// `updateEngineStateAfterEngineMutation` sets the next engine state under the engine-state lock
					// and returns - so from the instant it returns the name is servable. Published only afterwards,
					// there is a window in which the name resolves to a live catalog with no registry behind it:
					// `createSessionInternal` builds a fresh and *unsuspended* one through `computeIfAbsent` and
					// admits a session into it, the handoff below then displaces that registry, and the session
					// inside it is reachable through no name at all - invisible to every later quiesce, and still
					// bound to a catalog a subsequent rename, delete or shutdown will destroy underneath it.
					//
					// **A replace onto a name that already had a catalog is left alone**, and deliberately so. Its
					// own registry holds that name from the read-only phase to the handoff, so the window above
					// never opens there and publishing early would buy nothing - while costing something real. The
					// published registry is what a caller captures before waiting out a suspension, and neither
					// `handleSuspension` nor `registerWhileNotSuspended` looks at the map again once it has waited.
					// An early publication that a failed commit then took back would leave such a caller holding an
					// unpublished alias, resolving a target catalog the failure left standing, and registering into
					// the *source* registry's session map - orphaned from the very name it asked for. The two cases
					// that do publish early are immune to that: a rename's target and a replace onto a name that
					// names nothing both still name no catalog once the operation has failed, so such a waiter is
					// released into a `CatalogNotFoundException` rather than into a session.
					final boolean publishedAheadOfCommit = quiescedTargetRegistry.isEmpty() && targetRegistry != null;
					if (publishedAheadOfCommit) {
						final SessionRegistry displacedRegistry =
							evita.registerWithReplaceCatalogSessionRegistry(catalogNameToBeReplaced, targetRegistry);
						// Nothing can be occupying this name: it names no catalog until the commit below lands, and
						// session creation refuses a name it cannot resolve. Asserted rather than cleaned up after,
						// because the only available cleanup - draining a registry full of live sessions and giving
						// up after five seconds - is the silent half-measure this whole ordering exists to remove.
						// Throwing here reaches `undoOperations` with the handover latch already set, so the
						// catalog is declared unusable rather than compensated for - which is what this window
						// needs. Being ahead of the commit does not make a failure reversible: the relabel is
						// already on disk by the time this runs, and it is the relabel, not the commit, that
						// decides which of the two the failure path owes its caller.
						Assert.isPremiseValid(
							displacedRegistry == null,
							() -> new GenericEvitaInternalError(
								"Catalog `" + catalogNameToBeReplaced + "` gained a session registry while its name " +
									"was being claimed - the sessions it holds would be orphaned by the handoff!"
							)
						);
					}

					completionEngineStateUpdater.accept(
						new AbstractEngineStateUpdater(transactionId, mutation) {
							@Override
							public ExpandedEngineState apply(long version, @Nonnull ExpandedEngineState expandedEngineState) {
								// The entire operation, on disk and in state: the target name is pointed at the
								// folder the source was already living in, and the source name stops naming
								// anything. Nothing moved - `replace(...)` only rewrote the name stored inside
								// that folder - so this is a pointer swap and a crash either side of it leaves
								// one of two consistent worlds rather than a half-renamed directory.
								final Builder stateAfterAddingRenamedCatalog = ExpandedEngineState
									.builder(expandedEngineState)
									.withVersion(version)
									// The target may be sitting in the missing bucket, and `stageCatalog` below
									// would then put its name into `activeCatalogs` while it is still listed as
									// missing. Nothing asserts the buckets are disjoint, so that survives the
									// commit and wedges the next boot: the same name is staged both as loadable
									// and as reappeared, and the reappearance mutation then fails against its own
									// live stub. Clearing it first is what keeps the buckets exclusive.
									.withCatalogNoLongerMissing(catalogNameToBeReplaced)
									.withCatalogBoundTo(replacedCatalog, prevailingFolderId);
								if (!catalogNameToBeReplaced.equals(catalogNameToBeReplacedWith)) {
									stateAfterAddingRenamedCatalog.withoutCatalog(catalogNameToBeReplacedWith);
								}
								if (supersededFolderId != null) {
									// The folder the replaced catalog lived in is now unreachable, and the
									// tombstone is what authorises deleting it: an unreferenced folder with no
									// positive evidence of our ownership is deliberately never destroyed. It is
									// staged in this same commit so that a crash before the delete still leaves
									// the instruction behind for the next boot.
									stateAfterAddingRenamedCatalog.withRetiredFolder(
										catalogNameToBeReplaced, supersededFolderId
									);
								}
								return stateAfterAddingRenamedCatalog.build();
							}
						}
					);

					// **The commit above has landed, and nothing below may report failure.** Past that point the
					// operation has happened: the durable state records it and the next boot will bring it up. A
					// throw from here would reach `undoOperations` with the handover latch set and be answered by
					// declaring the catalog unusable - taking a rename that *succeeded* off the wire, removing the
					// committed name's registry and orphaning the sessions inside it. Each step below already
					// tolerates its own failure; this wrap is what makes that a property of the block rather than a
					// habit its statements happen to share, so a statement added later cannot quietly undo it.
					try {
						// The folder now holds a different catalog than it did a moment ago, so its label has to
						// move with it or disaster recovery reads the previous occupant's name. Written after the
						// commit rather than inside it: the state updater runs under the engine-state lock, and a
						// file only humans read has no business being written while every other mutation waits.
						//
						// The catch is here so that "nothing after the commit may report failure" can be verified by
						// reading this method, rather than by tracing three layers into the store module and hoping
						// nobody changes them. It is not, however, what absorbs an I/O failure: the write itself is
						// best-effort at its own site, so a full disk or an unwritable folder never reaches this far.
						// What can is a `CatalogFolderOperations` implementation that refuses the call outright - a
						// wiring error rather than an operational one - and even that must not turn a rename that has
						// already committed into a reported failure.
						try {
							this.folderContext.recordCatalogName(catalogNameToBeReplaced, prevailingFolderId);
						} catch (Throwable ex) {
							log.warn(
								"Failed to relabel storage folder `{}` as catalog `{}` - the folder still " +
									"carries its previous occupant's name, which only affects a human reading " +
									"it directly.",
								prevailingFolderId.id(), catalogNameToBeReplaced, ex
							);
						}

						// notify callback that it's now a live snapshot
						//
						// Caught at the site rather than left to the wrap below, because this statement sits
						// *before* the registry handover and the two cannot be reordered: the handover admits
						// sessions, and admitting one before the transaction manager points at the replacement
						// lets a write commit against a pipeline still based on the old instance. So the
						// ordering stays and the failure is contained instead. Left to the wrap, a throw here
						// would skip the handover entirely - and for a replace onto an existing catalog that
						// leaves the quiesced target still in the map under the committed name, REJECT-suspended
						// and answering `InstanceTerminatedException` for the life of the process, beneath an
						// operation that reported success. The `finally` below does not reach it, and must not:
						// once the swap *has* happened that registry answers to no name and resuming it would
						// admit sessions nothing can later quiesce.
						try {
							((Catalog) replacedCatalog).notifyCatalogPresentInLiveView();
						} catch (Throwable ex) {
							log.error(
								"Catalog `{}` could not be published to its transaction manager after the rename " +
									"committed - transaction processing may still resolve the instance it " +
									"replaced until the server is restarted.",
								catalogNameToBeReplaced, ex
							);
						}

						// The catalog survives under the target name in BOTH operations, so its session registry
						// follows it there - it carries the active sessions, the FIFO queue and the consumed-version
						// census that backups pin against, all of which belong to the catalog rather than to the name
						// it happened to be reached by. For a rename it is already published under the target
						// name above, and what is left here is retiring the name it used to answer to and
						// lifting the suspension;
						// for a replace onto an existing catalog the swap happens here, where taking it back is no
						// longer something a failure can ask for.
						prevailingCatalogSessionRegistry.ifPresentOrElse(
							sessionRegistry -> {
								if (!publishedAheadOfCommit) {
									evita.registerWithReplaceCatalogSessionRegistry(
										catalogNameToBeReplaced, targetRegistry
									);
								}
								// Dropped only now, never before the commit: until the commit lands the source
								// name still resolves to the live catalog, and a name left empty is a name the
								// next `createSessionInternal` fills with a fresh, unsuspended registry - serving
								// sessions against the very catalog this operation is renaming. Past the commit the
								// name resolves to nothing, so the same call answers `CatalogNotFoundException`
								// instead, which is what a renamed-away name owes its callers. This used to run for a
								// replace only, and the rename branch resumed `removedCatalogSessionRegistry` - always
								// empty for a rename, because the target name must not exist - so the source name was
								// left holding a registry suspended for ever, answering `SessionBusyException`.
								evita.removeCatalogSessionRegistryIfPresent(catalogNameToBeReplacedWith);
								// One suspension is shared by both views of this registry, so this single call releases
								// the callers waiting on either name, and it is the last thing the handoff owes them.
								sessionRegistry.resumeOperations();
							},
							// The surviving catalog has no registry to hand over. Reachable only if it stopped being a
							// catalog between the `obtainCatalogSessionRegistry` that opens this method and here,
							// which the engine's serialised mutations should make impossible - kept as cleanup rather
							// than an assertion because this runs *after* the commit, where reporting a failure is
							// worse than tidying up. Whatever is registered under the target name is then the registry
							// this operation installed purely to quiesce it, and leaving that behind would keep the
							// name rejecting sessions for the life of the process.
							//
							// Unpublished and left suspended, never resumed: a resumed registry that no longer answers
							// to any name still accepts sessions from whoever holds a reference to it, and those
							// sessions are then invisible to every later quiesce - which walks the registry map. The
							// caller that loses this race is refused rather than served, which is precisely what
							// quiescing the target is for.
							() -> {
								if (quiescedTargetRegistry.isPresent()) {
									evita.removeCatalogSessionRegistryIfPresent(catalogNameToBeReplaced);
								}
							}
						);

						// Terminate the catalog that was replaced. A failure here costs open handles into a folder
						// that is about to be deleted, which makes the delete below more likely to be refused - and
						// that is already accounted for, because a refused delete leaves the tombstone standing for
						// the next boot drain. What must not happen is propagating: the replace has committed.
						if (replaceOperation && catalogToBeReplaced != null) {
							try {
								catalogToBeReplaced.terminate();
							} catch (Throwable ex) {
								log.warn(
									"Failed to terminate the superseded catalog `{}` - its handles stay open " +
										"until the process ends, and the folder deletion below may be refused as " +
										"a result.",
									catalogNameToBeReplaced, ex
								);
							}
						}

						// Strictly after `terminate()`, never before: the delete has to follow the close of every
						// handle into that folder, or an operating system that refuses to remove an open directory
						// turns this into an intermittent failure - the exact class of bug the pointer-only design
						// exists to remove. A refusal here is not an error either way; the tombstone staged above
						// survives the run and the next boot drains it.
						if (supersededFolderId != null) {
							this.folderContext.deleteRetiredFolder(supersededFolderId);
						}

					} catch (Throwable ex) {
						log.error(
							"Catalog `{}` now answers to `{}` and that is durable, but the work that follows the " +
								"commit did not finish - a superseded folder may be left for the next boot to " +
								"drain, and change data capture subscribers may have missed the rename.",
							catalogNameToBeReplacedWith, catalogNameToBeReplaced, ex
						);
					} finally {
						// **The resume is owed unconditionally once the commit has landed**, and the block above
						// is the only place that performs it - so a throw anywhere before it would leave both
						// names answering `SessionBusyException` for the life of the process, beneath an
						// operation that reported success. That is this issue's own headline symptom, restored
						// on the one path that claims to have worked, and swallowing the failure is what would
						// have hidden it. Idempotent, so the ordinary path pays nothing for running it twice:
						// `resumeOperations` lifts a suspension if one stands and does nothing otherwise.
						//
						// Only the surviving catalog's registry. A replace's quiesced target holds a *different*
						// suspension, which the handover above leaves standing on purpose - a registry that no
						// longer answers to any name must not start admitting sessions again.
						prevailingCatalogSessionRegistry.ifPresent(SessionRegistry::resumeOperations);
					}
					return new CommitVersions(
						replacedCatalog.getVersion(),
						replacedCatalog.getSchema().version()
					);
				},
				undoOperations::accept
			);
		} catch (Throwable ex) {
			// `Throwable` rather than `RuntimeException` for the same reason the handover uses it: the drain and
			// the schema mutation above run with both registries suspended, and an `Error` escaping without
			// reaching `undoOperations` leaves them suspended for the life of the process - answering
			// `SessionBusyException` to every session, which is the session half of issue #1414 restored by the
			// code that fixes it. Rethrown unchanged; nothing in the block above throws a checked exception, so
			// precise rethrow keeps the declared signature.
			undoOperations.accept(ex);
			throw ex;
		}
	}

	/**
	 * Tells whether a failure was raised at or after the handover's point of no return - the moment inside
	 * `replaceWith` where the folder's stored identity becomes the incoming catalog's and stops agreeing with
	 * engine state.
	 *
	 * **This is the narrower of the two things the failure path asks.** It recognises only failures raised
	 * *inside* the handover; a failure raised after it returned carries no marker and is recognised by the
	 * latch the completion phase sets instead. Both lead to the same answer, and the latch is the one that
	 * covers the open-ended half.
	 *
	 * @param failure failure reported by the operation, if any
	 * @return true when the failure crossed the point of no return
	 */
	private static boolean isHandoverFailure(@Nullable Throwable failure) {
		Throwable current = failure;
		// bounded rather than "until null": a cause chain that refers back into itself would otherwise spin
		// here forever, and a failure path is the worst possible place to discover that
		for (int depth = 0; current != null && depth < MAX_INSPECTED_CAUSE_DEPTH; depth++) {
			if (current instanceof CatalogHandoverFailedException) {
				return true;
			}
			final Throwable cause = current.getCause();
			current = cause == current ? null : cause;
		}
		return false;
	}

	/**
	 * Terminates a catalog whose failed handover left its persistence service open, reporting a refusal rather
	 * than propagating it.
	 *
	 * @param catalog     catalog to release
	 * @param catalogName name to report it under, which is the name it answered to rather than the one it holds
	 */
	private static void terminateQuietly(@Nonnull CatalogContract catalog, @Nonnull String catalogName) {
		try {
			catalog.terminate();
		} catch (Throwable terminationFailure) {
			log.warn(
				"Failed to terminate catalog `{}` after declaring it unusable - the handles its persistence " +
					"service holds into the storage folder stay open until the server is restarted, and a " +
					"later attempt to delete that folder may be refused as a result.",
				catalogName, terminationFailure
			);
		}
	}

	@Nonnull
	@Override
	public Optional<ExpandedEngineState> replayCompletionState(
		@Nonnull ModifyCatalogSchemaNameMutation mutation,
		long targetVersion,
		@Nonnull ExpandedEngineState currentState,
		@Nonnull Evita evita
	) {
		final String sourceName = mutation.getCatalogName();
		final String targetName = mutation.getNewCatalogName();
		// Read from the state being replayed onto, never through the folder context: its resolver answers about
		// whichever state is live, and the whole point here is to rebuild the state the crashed commit would
		// have produced.
		final CatalogFolderId prevailingFolderId = currentState.boundFolderIdFor(sourceName);
		if (prevailingFolderId == null) {
			// The source is not bound, so the crashed run got further than we can see - or the WAL record does
			// not describe this installation at all. Either way the safe answer is the wedge, not a guess.
			return Optional.empty();
		}
		final CatalogFolderId supersededFolderId = mutation.isOverwriteTarget() ?
			currentState.boundFolderIdFor(targetName) : null;
		// The same premise the live path asserts: if both names resolved to one folder, the tombstone below would
		// destroy the surviving catalog. Refuse rather than replay it.
		if (supersededFolderId != null && supersededFolderId.equals(prevailingFolderId)) {
			return Optional.empty();
		}

		// Everything the crashed completion phase did on disk is already done - `replaceWith` rewrote the name
		// inside the folder before the commit - so replay is purely the three state edits. The placeholder stands
		// in for the catalog the work phase built and the crash lost; boot replaces it with the real instance as
		// soon as the load completes, and `reconcileStoredCatalogIdentity` settles the stored name on the way in.
		final ExpandedEngineState.Builder builder = ExpandedEngineState
			.builder(currentState)
			.withVersion(targetVersion)
			.withCatalogNoLongerMissing(targetName)
			.withActiveCatalogBoundTo(
				this.folderContext.createUnusableCatalog(
					targetName, prevailingFolderId, CatalogState.BEING_ACTIVATED,
					(cn, folderId, root) ->
						new CatalogTransitioningException(cn, folderId, root, CatalogState.BEING_ACTIVATED)
				),
				prevailingFolderId
			);
		if (!targetName.equals(sourceName)) {
			builder.withoutCatalog(sourceName);
		}
		if (supersededFolderId != null) {
			builder.withRetiredFolder(targetName, supersededFolderId);
		}
		return Optional.of(builder.build());
	}

}
