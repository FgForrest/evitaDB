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
import io.evitadb.core.catalog.UnusableCatalog;
import io.evitadb.core.engine.ExpandedEngineState;
import io.evitadb.core.exception.CatalogTransitioningException;
import io.evitadb.core.session.SuspendOperation;
import io.evitadb.core.transaction.engine.AbstractEngineStateUpdater;
import io.evitadb.core.transaction.engine.EngineStateUpdater;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Removes a catalog and all its associated data based on the provided mutation.
 * This operation also closes any active sessions associated with the catalog and cleans up its resources.
 *
 * Forward-replay is intentionally **not** implemented here. Catalog removal has destructive side effects (session
 * closure, folder deletion) that cannot be safely re-applied during recovery — depending on where the original crash
 * occurred, the catalog folder may or may not still exist on disk, and interacting with `terminateAndDelete` a second
 * time is not safe. The default `Optional.empty()` in `EngineMutationOperator` causes the transaction manager to
 * wedge loudly.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@RequiredArgsConstructor
public class RemoveCatalogSchemaMutationOperator implements EngineMutationOperator<Void, RemoveCatalogSchemaMutation> {
	private final Path storageDirectory;

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

		transitionEngineStateUpdater.accept(
			new AbstractEngineStateUpdater(transactionId, mutation) {
				@Override
				public ExpandedEngineState apply(long version, @Nonnull ExpandedEngineState expandedEngineState) {
					return ExpandedEngineState
						.builder(expandedEngineState)
						.withVersion(version)
						.withCatalog(
							new UnusableCatalog(
								catalogName,
								CatalogState.BEING_DELETED,
								RemoveCatalogSchemaMutationOperator.this.storageDirectory.resolve(
									catalogName),
								(cn, path) -> new CatalogTransitioningException(
									cn, path, CatalogState.BEING_DELETED)
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
								.build();
						}
					}
				);

				// Wrap the destructive side-effects in try-finally so the host event fires even
				// if `terminateAndDelete` throws (e.g. transient I/O failure on disk wipe). The
				// engine state has already advanced through the live view at this point — fire the
				// host event regardless so HOST subscribers do not miss the removal.
				try {
					evita.removeCatalogSessionRegistryIfPresent(catalogName);
					catalogToRemove.terminateAndDelete();
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

}
