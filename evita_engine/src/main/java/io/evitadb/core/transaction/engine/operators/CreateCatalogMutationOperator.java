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
import io.evitadb.api.CommitProgress.CommitVersions;
import io.evitadb.api.requestResponse.progress.ProgressingFuture;
import io.evitadb.api.requestResponse.schema.mutation.engine.CreateCatalogSchemaMutation;
import io.evitadb.core.Evita;
import io.evitadb.core.ExpandedEngineState;
import io.evitadb.core.UnusableCatalog;
import io.evitadb.core.exception.CatalogTransitioningException;
import io.evitadb.core.transaction.engine.AbstractEngineStateUpdater;
import io.evitadb.core.transaction.engine.EngineStateUpdater;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * This operator creates a new catalog in the evitaDB engine based on the provided mutation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@RequiredArgsConstructor
public class CreateCatalogMutationOperator
	implements EngineMutationOperator<CommitVersions, CreateCatalogSchemaMutation> {
	private final Path storageDirectory;

	@Nonnull
	@Override
	public String getOperationName(@Nonnull CreateCatalogSchemaMutation engineMutation) {
		return "Creating catalog `" + engineMutation.getCatalogName() + "`";
	}

	@Nonnull
	@Override
	public ProgressingFuture<CommitVersions> applyMutation(
		@Nonnull UUID transactionId,
		@Nonnull CreateCatalogSchemaMutation mutation,
		@Nonnull Evita evita,
		@Nonnull Consumer<EngineStateUpdater> transitionEngineStateUpdater,
		@Nonnull Consumer<EngineStateUpdater> completionEngineStateUpdater
	) {
		final String catalogName = mutation.getCatalogName();

		// transition the engine state to new with catalog in state BEING_CREATED
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
								CatalogState.BEING_CREATED,
								CreateCatalogMutationOperator.this.storageDirectory.resolve(
									catalogName),
								(cn, path) -> new CatalogTransitioningException(
									cn, path, CatalogState.BEING_CREATED)
							)
						).build();
				}
			}
		);

		// transition the engine state to new with catalog in state WARMING_UP
		return new ProgressingFuture<>(
			0,
			__ -> {
				final CatalogContract theCatalog = evita.createCatalog(
					Objects.requireNonNull(mutation.mutate(null))
					       .updatedCatalogSchema()
				);

				completionEngineStateUpdater.accept(
					new AbstractEngineStateUpdater(transactionId, mutation) {
						@Override
						public ExpandedEngineState apply(long version, @Nonnull ExpandedEngineState expandedEngineState) {
							return ExpandedEngineState
								.builder(expandedEngineState)
								.withVersion(version)
								.withCatalog(theCatalog)
								.build();
						}
					}
				);
				return new CommitVersions(theCatalog.getVersion(), theCatalog.getSchema().version());
			}
		);
	}

}
