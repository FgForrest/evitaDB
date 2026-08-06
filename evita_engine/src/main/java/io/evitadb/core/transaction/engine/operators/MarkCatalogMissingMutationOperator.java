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
import io.evitadb.api.exception.CatalogMissingException;
import io.evitadb.api.requestResponse.progress.ProgressingFuture;
import io.evitadb.api.requestResponse.schema.mutation.engine.MarkCatalogMissingMutation;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.UnusableCatalog;
import io.evitadb.core.engine.CatalogFolderContext;
import io.evitadb.core.engine.ExpandedEngineState;
import io.evitadb.core.transaction.engine.AbstractEngineStateUpdater;
import io.evitadb.core.transaction.engine.EngineStateUpdater;
import io.evitadb.spi.store.engine.model.CatalogFolderId;

import javax.annotation.Nonnull;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Operator that transitions a catalog to `{@link CatalogState#MISSING}`.
 *
 * The transition records the engine's awareness that the on-disk folder for the catalog is no longer present.
 * The operator:
 *
 * 1. Drops the in-memory `Catalog` reference (if any) — a missing catalog cannot serve requests.
 * 2. Moves the catalog name from the active / inactive / read-only arrays into the dedicated
 *    `missingCatalogs` array.
 * 3. Installs an `UnusableCatalog` placeholder that throws `CatalogMissingException` on every access.
 *
 * The operator is idempotent in shape: if the same mutation is replayed against an engine state that already
 * reflects MISSING, the builder simply keeps the catalog in the `missingCatalogs` bucket and no side effects are
 * observed by the caller.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class MarkCatalogMissingMutationOperator
	implements EngineMutationOperator<Void, MarkCatalogMissingMutation> {

	/**
	 * Storage root directory used to build the placeholder catalog path for the `UnusableCatalog`
	 * instance that reports the missing folder.
	 */
	@Nonnull private final CatalogFolderContext folderContext;

	public MarkCatalogMissingMutationOperator(@Nonnull CatalogFolderContext folderContext) {
		this.folderContext = folderContext;
	}

	@Nonnull
	@Override
	public String getOperationName(@Nonnull MarkCatalogMissingMutation engineMutation) {
		return "Marking catalog `" + engineMutation.getCatalogName() + "` as missing";
	}

	@Nonnull
	@Override
	public ProgressingFuture<Void> applyMutation(
		@Nonnull UUID transactionId,
		@Nonnull MarkCatalogMissingMutation mutation,
		@Nonnull Evita evita,
		@Nonnull Consumer<EngineStateUpdater> transitionEngineStateUpdater,
		@Nonnull Consumer<EngineStateUpdater> completionEngineStateUpdater
	) {
		final String catalogName = mutation.getCatalogName();

		// Single-phase operator — there is no long-running work between the pre-mutation and the
		// post-mutation updates. We go straight to the completion updater, which drops the catalog
		// from the active/inactive/read-only arrays, places it into the missingCatalogs bucket and
		// installs a placeholder `UnusableCatalog` that reports the MISSING state to callers.
		completionEngineStateUpdater.accept(
			new AbstractEngineStateUpdater(transactionId, mutation) {
				@Override
				public ExpandedEngineState apply(long version, @Nonnull ExpandedEngineState expandedEngineState) {
					// `withMissingCatalog` moves the name from active/inactive/read-only into the
					// missing bucket; `withInFlightPlaceholder` (NOT `withCatalog`) then installs the
					// `UnusableCatalog(MISSING)` stub into the catalogs map without re-inserting the
					// name into the inactive bucket — `withCatalog`'s else-branch would do exactly that
					// for non-`Catalog` instances and leave the name in BOTH buckets.
					return ExpandedEngineState
						.builder(expandedEngineState)
						.withVersion(version)
						.withMissingCatalog(catalogName)
						.withInFlightPlaceholder(
							MarkCatalogMissingMutationOperator.this.folderContext.createUnusableCatalog(
								catalogName,
								CatalogState.MISSING,
								(cn, folderId, root) -> new CatalogMissingException(cn)
							)
						)
						.build();
				}
			}
		);
		// Emit the host event AFTER the engine state has been updated so the MISSING settlement
		// is observable by HOST-area subscribers strictly after the underlying mutation.
		evita.notifyCatalogStateSettled(catalogName, CatalogState.MISSING);
		return new ProgressingFuture<>(0, progressingFuture -> null);
	}

	/**
	 * The MISSING transition is purely a state reclassification with no disk I/O or external side effects, so replay
	 * simply re-applies the completion-phase builder transformations against the current expanded state.
	 */
	@Nonnull
	@Override
	public Optional<ExpandedEngineState> replayCompletionState(
		@Nonnull MarkCatalogMissingMutation mutation,
		long targetVersion,
		@Nonnull ExpandedEngineState currentState,
		@Nonnull Evita evita
	) {
		final String catalogName = mutation.getCatalogName();
		return Optional.of(
			ExpandedEngineState
				.builder(currentState)
				.withVersion(targetVersion)
				.withMissingCatalog(catalogName)
				.withInFlightPlaceholder(
					this.folderContext.createUnusableCatalog(
						catalogName,
						CatalogState.MISSING,
						(cn, folderId, root) -> new CatalogMissingException(cn)
					)
				)
				.build()
		);
	}

}
