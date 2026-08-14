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

		final Runnable undoOperations = () -> {
			// What belongs under the target name is decided by what answered to it when the operation started,
			// never by what answers to it now - the completion phase publishes the surviving catalog's registry
			// there before the commit, so by this point the name may legitimately carry a registry this operation
			// put there itself.
			if (quiescedTargetRegistry.isPresent()) {
				// A replace: the target is still there and still answers to this name, so its own registry goes
				// back under it. Restored rather than merely resumed, because the pre-commit publication may have
				// displaced it - and with `registerWithReplace` rather than `register` for the same reason, since
				// the stricter call refuses a name occupied by a registry other than the one being restored, which
				// on that path is exactly what it is.
				//
				// The registry may be one this operation installed rather than found, and it is put back either
				// way: a drain can give up with sessions still inside it, and a registry that cannot be reached
				// through the map is invisible to every later quiesce - replace, delete, engine shutdown - so those
				// sessions would outlive the catalog they are bound to.
				evita.registerWithReplaceCatalogSessionRegistry(catalogNameToBeReplaced, quiescedTargetRegistry.get());
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
				// the registry the completion phase published, and it goes back with the rest of it. The surviving
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
					final CatalogContract replacedCatalog = replacedCatalogs.iterator().next();

					// The surviving catalog's registry is published under the target name *before* the commit, and
					// stays suspended until after it. The commit is what makes that name resolve, and it does so
					// synchronously - `updateEngineStateAfterEngineMutation` sets the next engine state under the
					// engine-state lock and returns - so from the instant it returns the name is servable. Published
					// after the commit, as it used to be, there is a window in which the name resolves to a live
					// catalog that has no registry behind it: `createSessionInternal` builds a fresh and
					// *unsuspended* one through `computeIfAbsent` and admits a session into it, the handoff below
					// then displaces that registry, and the session inside it is reachable through no name at all -
					// invisible to every later quiesce, and still bound to a catalog a subsequent rename, delete or
					// shutdown will destroy underneath it.
					//
					// Publishing early is only safe because a registry built by `withDifferentCatalogSupplier`
					// shares its active sessions, its suspension and its registration gate with the one it was
					// derived from. What is published here is therefore already suspended, and a caller that
					// resolves the target name inside the window waits out the POSTPONE and is then served by the
					// very registry the catalog keeps afterwards, rather than by one about to be thrown away.
					final SessionRegistry displacedRegistry = prevailingCatalogSessionRegistry
						.map(
							sessionRegistry -> evita.registerWithReplaceCatalogSessionRegistry(
								catalogNameToBeReplaced,
								sessionRegistry.withDifferentCatalogSupplier(
									() -> (Catalog) evita.getCatalogInstanceOrThrowException(catalogNameToBeReplaced)
								)
							)
						)
						.orElse(null);
					// Nothing else can be occupying the target name here: a rename's target names no catalog until
					// the commit below lands and session creation refuses a name it cannot resolve, while a
					// replace's target carries the registry this operation quiesced above. Asserted rather than
					// cleaned up after, because the only available cleanup - draining a registry full of live
					// sessions and giving up after five seconds - is the silent half-measure this whole ordering
					// exists to remove. Throwing is safe at this point precisely because the commit has not run
					// yet, so it reaches `undoOperations` like any other pre-commit failure.
					Assert.isPremiseValid(
						displacedRegistry == null || displacedRegistry == quiescedTargetRegistry.orElse(null),
						() -> new GenericEvitaInternalError(
							"Catalog `" + catalogNameToBeReplaced + "` gained a session registry while it was " +
								"being renamed or replaced - the sessions it holds would be orphaned by the handoff!"
						)
					);

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
					// it happened to be reached by. The registry is already published under the target name above;
					// what is left here is retiring the name it used to answer to, and lifting the suspension.
					prevailingCatalogSessionRegistry.ifPresentOrElse(
						sessionRegistry -> {
							// Dropped only now, never alongside the publication above: until the commit lands the
							// source name still resolves to the live catalog, and a name left empty is a name the
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

}
