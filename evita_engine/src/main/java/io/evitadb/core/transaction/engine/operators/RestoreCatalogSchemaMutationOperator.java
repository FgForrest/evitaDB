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


import io.evitadb.api.CatalogState;
import io.evitadb.api.requestResponse.progress.ProgressingFuture;
import io.evitadb.api.requestResponse.schema.mutation.engine.RestoreCatalogSchemaMutation;
import io.evitadb.core.Evita;
import io.evitadb.core.ExpandedEngineState;
import io.evitadb.core.UnusableCatalog;
import io.evitadb.core.exception.CatalogInactiveException;
import io.evitadb.core.transaction.engine.AbstractEngineStateUpdater;
import io.evitadb.core.transaction.engine.EngineStateUpdater;
import io.evitadb.utils.Assert;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * This operator registers inactive catalog into evitaDB engine after it has been restored on the file system.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@RequiredArgsConstructor
public class RestoreCatalogSchemaMutationOperator
	implements EngineMutationOperator<Void, RestoreCatalogSchemaMutation> {
	private final Path storageDirectory;

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
		final Path catalogFolder = this.storageDirectory.resolve(catalogName);

		Assert.isTrue(
			catalogFolder.toFile().exists(),
			"Catalog folder `" + catalogFolder + "` does not exist! Please restore the catalog first."
		);

		// transition the engine state to new with catalog in state WARMING_UP
		return new ProgressingFuture<>(
			0,
			__ -> {
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
										catalogFolder,
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
