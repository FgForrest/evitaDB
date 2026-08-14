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
import io.evitadb.core.exception.CatalogTransitioningException;
import io.evitadb.core.session.SessionRegistry;
import io.evitadb.core.session.SuspendOperation;
import io.evitadb.core.transaction.engine.AbstractEngineStateUpdater;
import io.evitadb.core.transaction.engine.EngineStateUpdater;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.spi.store.engine.model.CatalogFolderId;
import io.evitadb.utils.Assert;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;
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
 * Forward-replay is still **not** implemented, but the reason has changed and narrowed: the disk work is now
 * idempotent, and what blocks replay is the completion phase's need for a live catalog instance to stage, which
 * does not exist at replay time when every catalog is still a stub. The default `Optional.empty()` in
 * `EngineMutationOperator` causes the transaction manager to wedge loudly.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Slf4j
@RequiredArgsConstructor
public class ModifyCatalogSchemaNameMutationOperator implements EngineMutationOperator<CommitVersions, ModifyCatalogSchemaNameMutation> {
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
				transactionId, mutation, evita, completionEngineStateUpdater
			);
		} else {
			final String currentName = mutation.getCatalogName();
			final String newName = mutation.getNewCatalogName();
			isTrue(!evita.getCatalogNames().contains(newName), () -> new CatalogAlreadyPresentException(newName, newName));
			final CatalogContract catalogToBeRenamed = evita.getCatalogInstanceOrThrowException(currentName);
			return doReplaceCatalogInternal(
				newName, currentName,
				catalogToBeRenamed, catalogToBeRenamed,
				transactionId, mutation, evita, completionEngineStateUpdater
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
		// this will be always empty if catalogToBeReplaced == catalogToBeReplacedWith
		final Optional<SessionRegistry> removedCatalogSessionRegistry =
			evita.getCatalogSessionRegistry(catalogNameToBeReplaced);

		// Written as soon as the target registry is owned - before it is drained, never after - and read only by
		// `undoOperations`, which is declared above that point and cannot capture the local it produces.
		final AtomicReference<SessionRegistry> quiescedTargetRegistryHolder = new AtomicReference<>();

		final Runnable undoOperations = () -> {
			// Which of the three outcomes applies is decided by what answered to the target name when the
			// operation started, never by what answers to it now.
			final SessionRegistry quiescedTargetRegistry = quiescedTargetRegistryHolder.get();
			if (removedCatalogSessionRegistry.isPresent()) {
				// A registry that predates the operation: restored under its name first, resumed after, in that
				// order. Session creation goes through `computeIfAbsent` on this same map, so a name left empty
				// for the length of a resume is a name a racing request can register a *different* registry
				// under - which the restore then refuses, throwing out of the undo path itself.
				evita.registerCatalogSessionRegistry(catalogNameToBeReplaced, removedCatalogSessionRegistry.get());
				// A replace quiesces its target with REJECT before touching anything, and a failure leaves that
				// target standing - the operation changed nothing. Resuming it is therefore part of undoing, or
				// the catalog that was *not* replaced spends the rest of the process answering
				// `InstanceTerminatedException` to every session opened against it. Guarded on the holder so that
				// a suspension this operation never installed is not cleared on someone else's behalf.
				if (quiescedTargetRegistry != null) {
					quiescedTargetRegistry.resumeOperations();
				}
			} else if (quiescedTargetRegistry != null) {
				// A registry this operation installed itself, purely to quiesce the target. The replace failed,
				// so that catalog is still there and still answers to this name - which makes a resumed registry
				// under it exactly what the first session would have built anyway.
				//
				// Resumed **in place**, never unpublished: the drain can give up with sessions still inside it,
				// and a registry that cannot be reached through the map is invisible to every later quiesce -
				// replace, delete, engine shutdown - so those sessions would outlive the catalog they are bound
				// to. Unpublishing while suspended has the mirror-image cost, since a resume that follows the
				// removal lets a racing `createSession` populate a registry nobody can reach any more.
				quiescedTargetRegistry.resumeOperations();
			} else {
				// Nothing was quiesced under this name: a rename, whose target names nothing until the commit
				// lands, or a replace onto a name that names no catalog. Whatever answers to it can only have
				// been put there by the registry swap on the success path, and goes back with the rest of it.
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

			final boolean replaceOperation = catalogToBeReplaced != catalogToBeReplacedWith;
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
			final Optional<SessionRegistry> quiescedTargetRegistry;
			if (replaceOperation) {
				// Obtained rather than looked up, for the reason the surviving catalog is: a target nobody has
				// opened a session on since boot has no registry, so suspending what `getCatalogSessionRegistry`
				// finds quiesces nothing at all - which is how sessions came to be served against a catalog while
				// it was being destroyed. A registry is installed only when the name names a catalog, so a
				// replace onto a target that does not exist keeps answering `CatalogNotFoundException` rather
				// than reporting a terminated instance.
				//
				// Ownership is recorded *between* obtaining and draining, deliberately. The drain publishes the
				// suspension before it waits, so it throws with the suspension standing; recording afterwards
				// would leave `undoOperations` unaware of a registry it has to resume, and the catalog that was
				// not replaced would reject every session until the process ended.
				//
				// Held apart from `removedCatalogSessionRegistry`, which stays the *pre-existing* registry: the
				// captured Optional is empty exactly when this operation installed the registry itself, and undo
				// resumes that one in place rather than restoring anything.
				quiescedTargetRegistry = evita.obtainCatalogSessionRegistry(catalogNameToBeReplaced);
				quiescedTargetRegistry.ifPresent(quiescedTargetRegistryHolder::set);
				quiescedTargetRegistry.ifPresent(
					sessionRegistry -> sessionRegistry.closeAllActiveSessionsAndSuspend(SuspendOperation.REJECT)
				);
			} else {
				Assert.isPremiseValid(removedCatalogSessionRegistry.isEmpty(), "Expectation failed!");
				quiescedTargetRegistry = Optional.empty();
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
					final CatalogContract replacedCatalog = replacedCatalogs.iterator().next();

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
					} catch (RuntimeException ex) {
						log.warn(
							"Failed to relabel storage folder `{}` as catalog `{}` - the folder still carries its " +
								"previous occupant's name, which only affects a human reading it directly.",
							prevailingFolderId.id(), catalogNameToBeReplaced, ex
						);
					}

					// notify callback that it's now a live snapshot
					((Catalog) replacedCatalog).notifyCatalogPresentInLiveView();

					// The catalog survives under the target name in BOTH operations, so its session registry
					// follows it there - it carries the active sessions, the FIFO queue and the consumed-version
					// census that backups pin against, all of which belong to the catalog rather than to the name
					// it happened to be reached by. This used to run for a replace only, and the rename branch
					// resumed `removedCatalogSessionRegistry` - which for a rename is always empty, because the
					// target name must not exist. The source name was therefore left holding a registry suspended
					// for ever, answering `SessionBusyException` where it owed `CatalogNotFoundException`.
					prevailingCatalogSessionRegistry.ifPresentOrElse(
						sessionRegistry -> {
							evita.removeCatalogSessionRegistryIfPresent(catalogNameToBeReplacedWith);
							final SessionRegistry previous = evita.registerWithReplaceCatalogSessionRegistry(
								catalogNameToBeReplaced,
								sessionRegistry.withDifferentCatalogSupplier(
									() -> (Catalog) evita.getCatalogInstanceOrThrowException(
										catalogNameToBeReplaced))
							);
							// resumed before the straggler below is dealt with, so the name the clients want is
							// serving again at the first possible moment rather than after a close that may wait
							sessionRegistry.resumeOperations();
							// compared against the registry this operation quiesced - which may be one it installed
							// itself - rather than against whatever existed beforehand, or a registry we created
							// and suspended would be mistaken for a straggler and closed a second time
							retireStragglerRegistry(
								previous, quiescedTargetRegistry.orElse(null), catalogNameToBeReplaced
							);
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
						} catch (RuntimeException ex) {
							log.warn(
								"Failed to terminate the superseded catalog `{}` - its handles stay open until the " +
									"process ends, and the folder deletion below may be refused as a result.",
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

					return new CommitVersions(
						replacedCatalog.getVersion(),
						replacedCatalog.getSchema().version()
					);
				},
				ex -> undoOperations.run()
			);
		} catch (RuntimeException ex) {
			undoOperations.run();
			throw ex;
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

	/**
	 * Retires the session registry the swap above displaced, when it is not the one this operation suspended.
	 *
	 * A third registry can be there for one reason: the target name had none when the operation started — nothing
	 * had opened a session on it since boot — so there was nothing to suspend, and the first session request that
	 * arrived while the operation ran built one through `Evita#createSessionInternal`. Its sessions are bound to
	 * the catalog this operation is about to terminate, and its catalog supplier resolves a name that no longer
	 * names anything, so it cannot serve another request whatever we do here.
	 *
	 * Closing it with `REJECT` is therefore about *how* those clients find out: an `InstanceTerminatedException`
	 * says the catalog they held is gone, which is true and actionable, where leaving it alone lets them discover
	 * it through whatever the dangling supplier throws next. It also waits for a query still running on it before
	 * the caller terminates the catalog underneath it.
	 *
	 * **Nothing here may propagate.** By this point the engine-state commit is durable and the replace has
	 * succeeded — the target already serves the incoming data and the source name is already gone. Reporting a
	 * failure would be wrong about what happened, and would do it on the rarest path, which is exactly what the
	 * premise assert this replaced did: it turned a completed operation into a `GenericEvitaInternalError`. Both
	 * known throws live inside the close, which waits up to five seconds for sessions to drain and then asserts
	 * that they did.
	 *
	 * @param displaced   the registry the swap replaced, or null when the target name held none
	 * @param suspended   the registry this operation suspended for the target name, or null when it had none
	 * @param catalogName the target name, for the log record
	 */
	private static void retireStragglerRegistry(
		@Nullable SessionRegistry displaced,
		@Nullable SessionRegistry suspended,
		@Nonnull String catalogName
	) {
		if (displaced == null || displaced == suspended) {
			return;
		}
		try {
			displaced.closeAllActiveSessionsAndSuspend(SuspendOperation.REJECT);
		} catch (RuntimeException ex) {
			log.warn(
				"Sessions opened on catalog `{}` while it was being replaced could not be closed — they are " +
					"bound to the superseded catalog and will fail on their next call.",
				catalogName, ex
			);
		}
	}

}
