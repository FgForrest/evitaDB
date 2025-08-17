/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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
import io.evitadb.core.ExpandedEngineState;
import io.evitadb.core.SuspendOperation;
import io.evitadb.core.UnusableCatalog;
import io.evitadb.core.exception.CatalogInactiveException;
import io.evitadb.core.exception.CatalogTransitioningException;
import io.evitadb.core.transaction.engine.AbstractEngineStateUpdater;
import io.evitadb.core.transaction.engine.EngineStateUpdater;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.nio.file.Path;
import java.util.Collections;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Sets the internal state of the catalog to active or inactive based on the provided mutation.
 * This method handles the activation or deactivation of a catalog and notifies the observer about the progress
 * while executing the task. It also triggers completion or failure callbacks accordingly.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@RequiredArgsConstructor
public class SetCatalogStateMutationOperator implements EngineMutationOperator<Void, SetCatalogStateMutation> {
	private final Path storageDirectory;

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
							new UnusableCatalog(
								catalogName,
								transitionState,
								SetCatalogStateMutationOperator.this.storageDirectory.resolve(catalogName),
								(cn, path) -> new CatalogTransitioningException(cn, path, transitionState)
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
					completionEngineStateUpdater.accept(
						new AbstractEngineStateUpdater(transactionId, mutation) {
							@Override
							public ExpandedEngineState apply(long version, @Nonnull ExpandedEngineState expandedEngineState) {
								return ExpandedEngineState
									.builder(expandedEngineState)
									.withVersion(version)
									.withCatalog(loadedCatalog.iterator().next())
									.build();
							}
						}
					);
					return null;
				}
			);
		} else {
			return new ProgressingFuture<>(
				0,
				progressingFuture -> {
					evita.closeAllActiveSessionsAndSuspend(catalogName, SuspendOperation.REJECT);

					completionEngineStateUpdater.accept(
						new AbstractEngineStateUpdater(transactionId, mutation) {
							@Override
							public ExpandedEngineState apply(long version, @Nonnull ExpandedEngineState expandedEngineState) {
								return ExpandedEngineState
									.builder(expandedEngineState)
									.withVersion(version)
									.withCatalog(
										new UnusableCatalog(
											catalogName, CatalogState.INACTIVE,
											SetCatalogStateMutationOperator.this.storageDirectory.resolve(catalogName),
											CatalogInactiveException::new
										)
									)
									.build();
							}
						}
					);

					evita.removeCatalogSessionRegistryIfPresent(catalogName);
					theCatalog.terminate();
					return null;
				}
			);
		}
	}

}
