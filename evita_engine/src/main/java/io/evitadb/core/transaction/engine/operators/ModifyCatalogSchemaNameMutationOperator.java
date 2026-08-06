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
import io.evitadb.core.session.SessionRegistry;
import io.evitadb.core.session.SuspendOperation;
import io.evitadb.core.transaction.engine.AbstractEngineStateUpdater;
import io.evitadb.core.transaction.engine.EngineStateUpdater;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.spi.store.engine.model.CatalogFolderId;
import io.evitadb.utils.Assert;
import lombok.RequiredArgsConstructor;

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
 * Both are the same operation and both are a **pointer swap** (#649): the target name is bound to the folder the
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
		// close all active sessions to the catalog that will replace the original one
		final Optional<SessionRegistry> prevailingCatalogSessionRegistry = evita.getCatalogSessionRegistry(catalogNameToBeReplacedWith);
		// this will be always empty if catalogToBeReplaced == catalogToBeReplacedWith
		Optional<SessionRegistry> removedCatalogSessionRegistry = evita.getCatalogSessionRegistry(catalogNameToBeReplaced);

		prevailingCatalogSessionRegistry
			.ifPresent(sessionRegistry -> sessionRegistry.closeAllActiveSessionsAndSuspend(SuspendOperation.POSTPONE));

		final Runnable undoOperations = () -> {
			// revert session registry swap
			if (removedCatalogSessionRegistry.isPresent()) {
				evita.registerCatalogSessionRegistry(catalogNameToBeReplaced, removedCatalogSessionRegistry.get());
			} else {
				evita.removeCatalogSessionRegistryIfPresent(catalogNameToBeReplaced);
			}
		};

		try {
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
			if (replaceOperation) {
				removedCatalogSessionRegistry
					.ifPresent(it -> it.closeAllActiveSessionsAndSuspend(SuspendOperation.REJECT));
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
					this.folderContext.recordCatalogName(catalogNameToBeReplaced, prevailingFolderId);

					// notify callback that it's now a live snapshot
					((Catalog) replacedCatalog).notifyCatalogPresentInLiveView();

					if (replaceOperation) {
						// we can resume suspended operations on catalogs
						prevailingCatalogSessionRegistry.ifPresent(
							sessionRegistry -> {
								evita.removeCatalogSessionRegistryIfPresent(catalogNameToBeReplacedWith);
								final SessionRegistry previous = evita.registerWithReplaceCatalogSessionRegistry(
									catalogNameToBeReplaced,
									sessionRegistry.withDifferentCatalogSupplier(
										() -> (Catalog) evita.getCatalogInstanceOrThrowException(
											catalogNameToBeReplaced))
								);
								Assert.isPremiseValid(
									previous == null || previous == removedCatalogSessionRegistry.orElse(null),
									"Unexpected instance of the session registry was replaced!"
								);
								sessionRegistry.resumeOperations();
							}
						);
					} else {
						removedCatalogSessionRegistry.ifPresent(SessionRegistry::resumeOperations);
					}

					// terminate the catalog that was replaced
					if (replaceOperation && catalogToBeReplaced != null) {
						catalogToBeReplaced.terminate();
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

}
