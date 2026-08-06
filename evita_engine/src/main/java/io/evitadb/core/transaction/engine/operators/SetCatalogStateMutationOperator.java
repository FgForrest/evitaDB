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
import io.evitadb.api.requestResponse.schema.mutation.engine.SetCatalogStateMutation;
import io.evitadb.core.Evita;
import io.evitadb.core.engine.CatalogFolderContext;
import io.evitadb.core.engine.ExpandedEngineState;
import io.evitadb.core.exception.CatalogInactiveException;
import io.evitadb.core.exception.CatalogTransitioningException;
import io.evitadb.core.session.SuspendOperation;
import io.evitadb.core.transaction.engine.AbstractEngineStateUpdater;
import io.evitadb.core.transaction.engine.EngineStateUpdater;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Sets the internal state of the catalog to active or inactive based on the provided mutation.
 * This method handles the activation or deactivation of a catalog and notifies the observer about the progress
 * while executing the task. It also triggers completion or failure callbacks accordingly.
 *
 * Forward-replay is intentionally **not** implemented here. Activation requires a previously-loaded `Catalog`
 * (produced by `evita.loadCatalogInternal`) and deactivation closes all sessions and terminates the catalog. Neither
 * side effect can be recreated from the WAL mutation alone at replay time without re-running the work phase. The
 * default `Optional.empty()` in `EngineMutationOperator` causes the transaction manager to wedge loudly — safer than
 * silently producing an inconsistent catalog snapshot.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@RequiredArgsConstructor
public class SetCatalogStateMutationOperator implements EngineMutationOperator<Void, SetCatalogStateMutation> {
	private final CatalogFolderContext folderContext;

	@Nonnull
	@Override
	public String getOperationName(@Nonnull SetCatalogStateMutation engineMutation) {
		if (engineMutation.isActive()) {
			return "Activating catalog `" + engineMutation.getCatalogName() + "`";
		} else {
			return "Deactivating catalog `" + engineMutation.getCatalogName() + "`";
		}
	}

	@Nonnull
	@Override
	public ProgressingFuture<Void> applyMutation(
		@Nonnull UUID transactionId,
		@Nonnull SetCatalogStateMutation mutation,
		@Nonnull Evita evita,
		@Nonnull Consumer<EngineStateUpdater> transitionEngineStateUpdater,
		@Nonnull Consumer<EngineStateUpdater> completionEngineStateUpdater
	) {
		final String catalogName = mutation.getCatalogName();
		final CatalogState transitionState = mutation.isActive() ?
			CatalogState.BEING_ACTIVATED : CatalogState.BEING_DEACTIVATED;
		final CatalogContract theCatalog = evita.getCatalogInstanceOrThrowException(catalogName);
		final boolean readOnly = evita.getEngineState().isReadOnly(catalogName);

		transitionEngineStateUpdater.accept(
			new AbstractEngineStateUpdater(transactionId, mutation) {
				@Override
				public ExpandedEngineState apply(long version, @Nonnull ExpandedEngineState expandedEngineState) {
					return ExpandedEngineState
						.builder(expandedEngineState)
						.withVersion(version)
						.withCatalog(
							SetCatalogStateMutationOperator.this.folderContext.createUnusableCatalog(
								catalogName,
								transitionState,
								(cn, folderId, root) ->
									new CatalogTransitioningException(cn, folderId, root, transitionState)
							)
						).build();
				}
			}
		);

		if (mutation.isActive()) {
			return new ProgressingFuture<>(
				0,
				Collections.singletonList(evita.loadCatalogInternal(catalogName, readOnly)),
				(progressingFuture, loadedCatalog) -> {
					final CatalogContract installed = loadedCatalog.iterator().next();
					completionEngineStateUpdater.accept(
						new AbstractEngineStateUpdater(transactionId, mutation) {
							@Override
							public ExpandedEngineState apply(long version, @Nonnull ExpandedEngineState expandedEngineState) {
								return ExpandedEngineState
									.builder(expandedEngineState)
									.withVersion(version)
									.withCatalog(installed)
									.build();
							}
						}
					);
					// Emit the host event AFTER the engine state has been updated so the host
					// event lands strictly after the underlying mutation in the system CDC stream.
					evita.notifyCatalogStateSettled(catalogName, installed.getCatalogState());
					return null;
				}
			);
		} else {
			return new ProgressingFuture<>(
				0,
				progressingFuture -> {
					evita.closeAllSessionsAndSuspend(catalogName, SuspendOperation.REJECT);

					completionEngineStateUpdater.accept(
						new AbstractEngineStateUpdater(transactionId, mutation) {
							@Override
							public ExpandedEngineState apply(long version, @Nonnull ExpandedEngineState expandedEngineState) {
								return ExpandedEngineState
									.builder(expandedEngineState)
									.withVersion(version)
									.withCatalog(
										SetCatalogStateMutationOperator.this.folderContext.createUnusableCatalog(
											catalogName, CatalogState.INACTIVE,
											CatalogInactiveException::new
										)
									)
									.build();
							}
						}
					);

					// Wrap the destructive side-effects in try-finally so the host event fires
					// even if `theCatalog.terminate()` throws — the engine state has already
					// transitioned to INACTIVE and HOST subscribers must observe that
					// transition regardless of downstream cleanup failures.
					try {
						evita.removeCatalogSessionRegistryIfPresent(catalogName);
						theCatalog.terminate();
					} finally {
						// Emit the host event AFTER the engine state and the live `Catalog`
						// resources have been torn down so subscribers see the INACTIVE settlement
						// strictly after the mutation in the system CDC stream.
						evita.notifyCatalogStateSettled(catalogName, CatalogState.INACTIVE);
					}
					return null;
				}
			);
		}
	}

}
