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
import io.evitadb.api.requestResponse.schema.mutation.engine.DuplicateCatalogMutation;
import io.evitadb.core.Evita;
import io.evitadb.core.ExpandedEngineState;
import io.evitadb.core.UnusableCatalog;
import io.evitadb.core.exception.CatalogInactiveException;
import io.evitadb.core.transaction.engine.AbstractEngineStateUpdater;
import io.evitadb.core.transaction.engine.EngineStateUpdater;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.nio.file.Path;
import java.util.Collections;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Duplicates an existing catalog to create a new catalog with a specified name.
 * Tracks the progress of the duplication process and handles completion or failure.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@RequiredArgsConstructor
public class DuplicateCatalogMutationOperator implements EngineMutationOperator<Void, DuplicateCatalogMutation> {
	private final Path storageDirectory;

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
		return new ProgressingFuture<>(
			0,
			Collections.singletonList(sourceCatalog.duplicateTo(targetCatalogName)),
			(progressingFuture, __) -> {
				completionEngineStateUpdater.accept(
					new AbstractEngineStateUpdater(transactionId, mutation) {
						@Override
						public ExpandedEngineState apply(long version, @Nonnull ExpandedEngineState expandedEngineState) {
							return ExpandedEngineState
								.builder(expandedEngineState)
								.withVersion(version)
								.withCatalog(
									new UnusableCatalog(
										targetCatalogName,
										CatalogState.INACTIVE,
										DuplicateCatalogMutationOperator.this.storageDirectory.resolve(
											targetCatalogName),
										CatalogInactiveException::new
									)
								)
								.build();
						}
					}
				);

				return null;
			}
		);
	}

}
