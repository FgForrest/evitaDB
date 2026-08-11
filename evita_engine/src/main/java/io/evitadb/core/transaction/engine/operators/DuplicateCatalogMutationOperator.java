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
import io.evitadb.api.requestResponse.schema.mutation.engine.DuplicateCatalogMutation;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.catalog.UnusableCatalog;
import io.evitadb.core.engine.CatalogFolderContext;
import io.evitadb.core.engine.CatalogFolderReservation;
import io.evitadb.core.engine.ExpandedEngineState;
import io.evitadb.core.exception.CatalogInactiveException;
import io.evitadb.core.transaction.engine.AbstractEngineStateUpdater;
import io.evitadb.core.transaction.engine.EngineStateUpdater;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.spi.store.engine.model.CatalogFolderId;
import io.evitadb.utils.Assert;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Duplicates an existing catalog to create a new catalog with a specified name.
 * Tracks the progress of the duplication process and handles completion or failure.
 *
 * Forward-replay is intentionally **not** implemented here. Duplicating a catalog involves deep folder-copy work in
 * the work phase. Re-applying the completion is still conceptually safe (the duplicate folder already exists), but
 * there is no guarantee the WAL-visible mutation carries enough information to rebuild the `UnusableCatalog` stub
 * with the same identity the original run would have produced. We prefer to wedge loudly rather than risk silent
 * drift — the default `Optional.empty()` in `EngineMutationOperator` causes the transaction manager to log a loud
 * error and stop.
 *
 * Forward-replay is **not** implemented, and the blocker is sharper than "the folder may exist": the target
 * folder is allocated during the work phase and its token lives only in an in-memory reservation, which a
 * restart discards. `DuplicateCatalogMutation` does not carry it, so replay cannot know which folder the copy
 * was written into — and binding the wrong one would leave the copy unreferenced.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@RequiredArgsConstructor
public class DuplicateCatalogMutationOperator implements EngineMutationOperator<Void, DuplicateCatalogMutation> {
	private final CatalogFolderContext folderContext;

	@Nonnull
	@Override
	public String getOperationName(@Nonnull DuplicateCatalogMutation engineMutation) {
		return "Duplicating catalog `" + engineMutation.getCatalogName() + "` to `" + engineMutation.getNewCatalogName() + "`";
	}

	@Nonnull
	@Override
	public ProgressingFuture<Void> applyMutation(
		@Nonnull UUID transactionId,
		@Nonnull DuplicateCatalogMutation mutation,
		@Nonnull Evita evita,
		@Nonnull Consumer<EngineStateUpdater> transitionEngineStateUpdater,
		@Nonnull Consumer<EngineStateUpdater> completionEngineStateUpdater
	) {
		final String catalogName = mutation.getCatalogName();
		final String targetCatalogName = mutation.getNewCatalogName();

		final CatalogContract sourceCatalog = evita.getCatalogInstanceOrThrowException(catalogName);
		// `verifyApplicability` has already refused anything that is not an active catalog, and the mutation's
		// conflict key keeps the name from transitioning underneath us - so a placeholder here is a broken
		// invariant rather than a user error, and must surface as one instead of being quietly reported as a
		// failed duplication
		Assert.isPremiseValid(
			sourceCatalog instanceof Catalog,
			() -> new GenericEvitaInternalError(
				"Catalog `" + catalogName + "` cannot be duplicated - it is a `" +
					sourceCatalog.getClass().getSimpleName() + "` placeholder rather than a live catalog!"
			)
		);
		// Allocated here rather than derived from the target name: the duplicate is one of the three paths that
		// materialise a folder, and it was the last one still writing into a directory named after its catalog.
		final CatalogFolderReservation reservation = this.folderContext.allocateFolderFor(targetCatalogName);
		final CatalogFolderId targetFolder = reservation.folderId();
		// The copy is started here rather than inside the future below, and that is why it needs its own catch:
		// `duplicateCatalog` verifies the target directory, takes a read hold and walks the whole source before
		// it returns a future at all, so anything it throws escapes before there is a future to hang a release
		// on. Create needs the same guard for the same reason, and has it.
		final ProgressingFuture<Void> copy;
		try {
			copy = ((Catalog) sourceCatalog).duplicateTo(targetCatalogName, targetFolder);
		} catch (RuntimeException ex) {
			reservation.close();
			throw ex;
		}
		final ProgressingFuture<Void> duplication = new ProgressingFuture<>(
			0,
			Collections.singletonList(copy),
			(progressingFuture, __) -> {
				// Declares the folder complete - and therefore loadable - **before** the commit below binds a
				// catalog to it. The reverse order leaves a referenced folder still wearing its "incomplete"
				// marker, which boot classification matches as referenced and loads anyway. Labelling the folder
				// with its catalog name rides along with it.
				DuplicateCatalogMutationOperator.this.folderContext.completeFolder(
					targetCatalogName, targetFolder
				);
				completionEngineStateUpdater.accept(
					new AbstractEngineStateUpdater(transactionId, mutation) {
						@Override
						public ExpandedEngineState apply(long version, @Nonnull ExpandedEngineState expandedEngineState) {
							// The duplicate is registered here for the first time, so its folder binding is
							// established rather than looked up - and it names the folder allocation created,
							// which is the one `duplicateTo` has just written the data into.
							return ExpandedEngineState
								.builder(expandedEngineState)
								.withVersion(version)
								// A name in the missing bucket is invisible to `getCatalogNames()` after a
								// restart, so applicability lets a duplicate through onto it - and the binding
								// that bucket entry kept alive would then refuse to move, leaving the copy
								// unreferenced and un-adoptable under its own name. Nothing is lost by clearing
								// it: a missing catalog is one whose folder is gone (#649).
								// A name in the missing bucket is invisible to `getCatalogNames()` after a
								// restart, so applicability lets a duplicate through onto it - and the binding
								// that bucket entry kept alive would then refuse to move, leaving the copy
								// unreferenced and un-adoptable under its own name. Nothing is lost by clearing
								// it: a missing catalog is one whose folder is gone (#649).
								.withCatalogNoLongerMissing(targetCatalogName)
								.withCatalog(
									DuplicateCatalogMutationOperator.this.folderContext.createUnusableCatalog(
										targetCatalogName, targetFolder, CatalogState.INACTIVE,
										CatalogInactiveException::new
									),
									targetFolder
								)
								.build();
						}
					}
				);

				// Emit the host event AFTER the engine state update so the freshly-duplicated
				// (and INACTIVE-by-default) target catalog is observable on the system stream
				// strictly after the underlying mutation.
				evita.notifyCatalogStateSettled(targetCatalogName, CatalogState.INACTIVE);

				return null;
			}
		);
		// The release rides on `whenComplete` rather than on the mapper above, because that mapper is a
		// `thenApply` continuation of the copy: it is skipped precisely when the copy fails, which is the case
		// the release exists for. `whenComplete` fires on failure and cancellation as well as on success, and
		// `close()` is idempotent, so the completeFolder path removing the entry first is harmless.
		//
		// Releasing this early is safe only because duplicate never resolves its folder by name - it carries
		// `targetFolder` from the allocation into the commit. Restore cannot do this: its claim has to outlive
		// the call that took it, because its registering mutation looks the folder up by catalog name.
		duplication.whenComplete((result, ex) -> reservation.close());
		return duplication;
	}

}
