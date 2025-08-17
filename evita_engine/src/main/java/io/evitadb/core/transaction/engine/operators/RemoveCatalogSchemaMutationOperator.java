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
import io.evitadb.api.requestResponse.schema.mutation.engine.RemoveCatalogSchemaMutation;
import io.evitadb.core.Evita;
import io.evitadb.core.ExpandedEngineState;
import io.evitadb.core.SuspendOperation;
import io.evitadb.core.UnusableCatalog;
import io.evitadb.core.exception.CatalogTransitioningException;
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
				evita.closeAllActiveSessionsAndSuspend(catalogName, SuspendOperation.REJECT);

				theFuture.updateProgress(1);

				final CatalogContract catalogToRemove = evita.getCatalogInstanceOrThrowException(catalogName);
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

				evita.removeCatalogSessionRegistryIfPresent(catalogName);
				catalogToRemove.terminateAndDelete();
				return null;
			}
		);
	}

}
