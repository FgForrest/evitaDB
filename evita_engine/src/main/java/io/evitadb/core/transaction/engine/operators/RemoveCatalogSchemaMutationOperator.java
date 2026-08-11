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
import io.evitadb.api.requestResponse.progress.ProgressingFuture;
import io.evitadb.api.requestResponse.schema.mutation.engine.RemoveCatalogSchemaMutation;
import io.evitadb.core.Evita;
import io.evitadb.core.engine.CatalogFolderContext;
import io.evitadb.core.engine.ExpandedEngineState;
import io.evitadb.core.exception.CatalogTransitioningException;
import io.evitadb.core.session.SuspendOperation;
import io.evitadb.core.transaction.engine.AbstractEngineStateUpdater;
import io.evitadb.core.transaction.engine.EngineStateUpdater;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.spi.store.engine.model.CatalogFolderId;
import io.evitadb.utils.Assert;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Removes a catalog and all its associated data based on the provided mutation.
 * This operation also closes any active sessions associated with the catalog and cleans up its resources.
 *
 * The removal commits a **tombstone** for the catalog's folder rather than making the wipe part of the operation.
 * The wipe is then attempted, and is allowed to fail: the operation the user asked for has already
 * succeeded, and a folder the operating system refuses to remove is drained on the next boot instead. That is what
 * makes drop-then-recreate on a locked folder stop being a failure.
 *
 * It is also what makes the removal **replayable**. Recovery no longer has to reason about how far a wipe got: the
 * completion state is a tombstone plus a name removal, both idempotent, and the folder is reclaimed by the boot
 * drain whether or not the crashed attempt managed anything. Before the tombstone the operator had to wedge the
 * engine, because the removal wiped the folder in place, and re-running that against a folder that may or may not
 * still exist was not safe.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@RequiredArgsConstructor
public class RemoveCatalogSchemaMutationOperator implements EngineMutationOperator<Void, RemoveCatalogSchemaMutation> {
	private final CatalogFolderContext folderContext;

	@Nonnull
	@Override
	public String getOperationName(@Nonnull RemoveCatalogSchemaMutation engineMutation) {
		return "Removing catalog `" + engineMutation.getCatalogName() + "`";
	}

	@Nonnull
	@Override
	public ProgressingFuture<Void> applyMutation(
		@Nonnull UUID transactionId,
		@Nonnull RemoveCatalogSchemaMutation mutation, @Nonnull Evita evita,
		@Nonnull Consumer<EngineStateUpdater> transitionEngineStateUpdater,
		@Nonnull Consumer<EngineStateUpdater> completionEngineStateUpdater
	) {
		final String catalogName = mutation.getCatalogName();
		final CatalogContract catalogToRemove = evita.getCatalogInstanceOrThrowException(catalogName);
		// Resolved here, while the catalog is still bound: the commit below drops the binding, so afterwards
		// there is nothing left to ask which folder the removal has to reclaim.
		final CatalogFolderId folderToReclaim = this.folderContext.folderIdFor(catalogName);

		transitionEngineStateUpdater.accept(
			new AbstractEngineStateUpdater(transactionId, mutation) {
				@Override
				public ExpandedEngineState apply(long version, @Nonnull ExpandedEngineState expandedEngineState) {
					return ExpandedEngineState
						.builder(expandedEngineState)
						.withVersion(version)
						.withCatalog(
							RemoveCatalogSchemaMutationOperator.this.folderContext.createUnusableCatalog(
								catalogName,
								CatalogState.BEING_DELETED,
								(cn, folderId, root) -> new CatalogTransitioningException(
									cn, folderId, root, CatalogState.BEING_DELETED)
							)
						).build();
				}
			}
		);

		return new ProgressingFuture<>(
			1,
			theFuture -> {
				evita.closeAllSessionsAndSuspend(catalogName, SuspendOperation.REJECT);

				theFuture.updateProgress(1);

				completionEngineStateUpdater.accept(
					new AbstractEngineStateUpdater(transactionId, mutation) {
						@Override
						public ExpandedEngineState apply(long version, @Nonnull ExpandedEngineState expandedEngineState) {
							return ExpandedEngineState
								.builder(expandedEngineState)
								.withVersion(version)
								.withoutCatalog(catalogToRemove)
								// The tombstone is what makes the wipe optional. Dropping the binding leaves a
								// folder nothing references, and an unreferenced folder is deliberately never
								// destroyed - that rule is what protects an operator's hand-placed directory - so
								// without this entry a wipe the operating system refuses would leave the data
								// behind permanently, with nothing recording that it was meant to go.
								.withRetiredFolder(catalogName, folderToReclaim)
								.build();
						}
					}
				);

				// Wrap the destructive side-effects in try-finally so the host event fires even
				// if the wipe throws (e.g. transient I/O failure). The engine state has already advanced
				// through the live view at this point — fire the host event regardless so HOST subscribers
				// do not miss the removal.
				try {
					evita.removeCatalogSessionRegistryIfPresent(catalogName);
					// Close first, wipe second, and let the wipe fail: the removal is already committed, so
					// propagating a filesystem error here would report a failure for an operation that
					// succeeded, and would do it precisely on the platform where a reader holding the
					// directory open makes the wipe fail - the second Windows failure this design removes.
					catalogToRemove.terminate();
					RemoveCatalogSchemaMutationOperator.this.folderContext.deleteRetiredFolder(folderToReclaim);
				} finally {
					// Emit the host event AFTER the catalog has been fully removed from the live
					// view so HOST-area subscribers can deregister endpoints / clean up
					// caches that referenced the now-gone catalog.
					evita.notifyCatalogRemovedFromLiveView(catalogName);
				}
				return null;
			}
		);
	}

	/**
	 * Rebuilds the completion state of a removal that committed to the WAL but never reached the bootstrap file.
	 *
	 * Nothing destructive is re-attempted: the state at replay time is exactly the tombstone and the name removal
	 * the original completion updater would have produced, and the folder is left for the boot drain that runs
	 * against that tombstone. The side effects the work phase performed — closing sessions, terminating the
	 * catalog, wiping the folder — are all either already done or unnecessary, because replay runs during boot
	 * with no sessions open and every catalog still a placeholder.
	 */
	@Nonnull
	@Override
	public Optional<ExpandedEngineState> replayCompletionState(
		@Nonnull RemoveCatalogSchemaMutation mutation,
		long targetVersion,
		@Nonnull ExpandedEngineState currentState,
		@Nonnull Evita evita
	) {
		final String catalogName = mutation.getCatalogName();
		// read from the state being replayed onto rather than through the folder context, whose resolver reads
		// whichever state is live - the two agree here, but only by an ordering this method must not depend on
		final CatalogFolderId folderToReclaim = currentState.boundFolderIdFor(catalogName);
		Assert.isPremiseValid(
			folderToReclaim != null,
			() -> new GenericEvitaInternalError(
				"Cannot replay removal of catalog `" + catalogName + "` - it is not bound to any storage folder!"
			)
		);
		return Optional.of(
			ExpandedEngineState
				.builder(currentState)
				.withVersion(targetVersion)
				.withoutCatalog(catalogName)
				.withRetiredFolder(catalogName, folderToReclaim)
				.build()
		);
	}

}
