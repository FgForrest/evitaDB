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


import io.evitadb.api.CatalogState;
import io.evitadb.api.requestResponse.progress.ProgressingFuture;
import io.evitadb.api.requestResponse.schema.mutation.engine.RestoreCatalogSchemaMutation;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.UnusableCatalog;
import io.evitadb.core.engine.CatalogFolderContext;
import io.evitadb.core.engine.ExpandedEngineState;
import io.evitadb.core.exception.CatalogInactiveException;
import io.evitadb.core.transaction.engine.AbstractEngineStateUpdater;
import io.evitadb.core.transaction.engine.EngineStateUpdater;
import io.evitadb.spi.store.engine.model.CatalogFolderId;
import io.evitadb.utils.Assert;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * This operator registers an inactive catalog into evitaDB engine when its folder is present on disk. Three call
 * paths land here:
 *
 * 1. **Restore from backup** — the original use case: an operator-uploaded archive has been written to the
 *    catalog folder and `RestoreCatalogSchemaMutation` is the engine-level acknowledgement.
 * 2. **Auto-discovery** — `Evita`'s boot drains a `RestoreCatalogSchemaMutation` for each folder it found on disk
 *    that the engine state did not know about, registering it as `INACTIVE`.
 * 3. **Flapping recovery** — `Evita`'s boot drains a `RestoreCatalogSchemaMutation` for each name previously sat
 *    in the `missingCatalogs` bucket whose folder has reappeared. The operator additionally clears the missing
 *    bucket entry, and the binding it kept alive, through `Builder#withCatalogNoLongerMissing(...)`.
 *
 * Forward-replay is intentionally **not** implemented here. Although the completion phase looks pure (wrap the
 * restored folder into an `UnusableCatalog` stub), it rests on a precondition the work phase established: that
 * the folder resolved for the catalog exists and holds the restored data. At replay time that folder may or may
 * not have been written, and the reservation naming it did not survive the restart — so rather than re-deriving
 * the invariant we wedge loudly via the default `Optional.empty()` in `EngineMutationOperator`.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@RequiredArgsConstructor
public class RestoreCatalogSchemaMutationOperator
	implements EngineMutationOperator<Void, RestoreCatalogSchemaMutation> {
	private final CatalogFolderContext folderContext;

	@Nonnull
	@Override
	public String getOperationName(@Nonnull RestoreCatalogSchemaMutation engineMutation) {
		return "Restoring catalog `" + engineMutation.getCatalogName() + "`";
	}

	@Nonnull
	@Override
	public ProgressingFuture<Void> applyMutation(
		@Nonnull UUID transactionId,
		@Nonnull RestoreCatalogSchemaMutation mutation, @Nonnull Evita evita,
		@Nonnull Consumer<EngineStateUpdater> transitionEngineStateUpdater,
		@Nonnull Consumer<EngineStateUpdater> completionEngineStateUpdater
	) {
		final String catalogName = mutation.getCatalogName();
		// This operator serves three paths and none of them allocates: the folder always exists by now.
		// - recovery from the missing bucket reads the binding the engine state already holds;
		// - a restore from backup reads the reservation `EvitaManagement` made before writing into the folder;
		// - an auto-discovered folder reads the reservation boot-time adoption made when it renamed the folder
		//   into the shape the engine allocates.
		final CatalogFolderId catalogFolder = this.folderContext.folderIdForBinding(catalogName);

		Assert.isTrue(
			this.folderContext.getFolderOperations().catalogFolderExists(catalogFolder),
			"Catalog folder `" + catalogFolder + "` does not exist! Please restore the catalog first."
		);

		// transition the engine state to new with catalog in state WARMING_UP
		return new ProgressingFuture<>(
			0,
			__ -> {
				// Declare the folder complete BEFORE the binding is committed below - see
				// `CatalogFolderContext#completeFolder` for why that order is load-bearing. A no-op for the
				// missing-bucket and auto-discovery paths, which never allocated and so wear no marker.
				RestoreCatalogSchemaMutationOperator.this.folderContext.completeFolder(catalogName, catalogFolder);

				completionEngineStateUpdater.accept(
					new AbstractEngineStateUpdater(transactionId, mutation) {
						@Override
						public ExpandedEngineState apply(long version, @Nonnull ExpandedEngineState expandedEngineState) {
							// `withCatalogNoLongerMissing` is a no-op for the auto-discovery path, and for the
							// other two it clears the missing-bucket entry *and* the binding that entry was
							// keeping alive. That second half is load-bearing here: without it the binding to
							// the folder that vanished survives, `withCatalog` declines to overwrite it, and
							// the restored data below is left unreferenced.
							return ExpandedEngineState
								.builder(expandedEngineState)
								.withVersion(version)
								.withCatalogNoLongerMissing(catalogName)
								.withCatalog(
									RestoreCatalogSchemaMutationOperator.this.folderContext.createUnusableCatalog(
										catalogName, catalogFolder, CatalogState.INACTIVE,
										CatalogInactiveException::new
									),
									// The name carries no binding by this point in any of the three paths, so the
									// folder resolved above is the one that gets recorded. For flapping recovery
									// that is the folder the catalog already occupied, which has just reappeared;
									// for the other two it is the folder this operation filled.
									catalogFolder
								)
								.build();
						}
					}
				);
				// Emit the host event AFTER the engine state update so the restored catalog's
				// INACTIVE settlement is observable on the system stream strictly after the
				// underlying mutation. The catalog must still be activated to become usable.
				evita.notifyCatalogStateSettled(catalogName, CatalogState.INACTIVE);
				return null;
			}
		);
	}

}
