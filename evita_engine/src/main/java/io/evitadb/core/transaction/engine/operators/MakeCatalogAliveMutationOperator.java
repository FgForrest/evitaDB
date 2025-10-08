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
import io.evitadb.api.exception.CatalogGoingLiveException;
import io.evitadb.api.exception.CatalogNotFoundException;
import io.evitadb.api.requestResponse.progress.ProgressingFuture;
import io.evitadb.api.requestResponse.schema.mutation.engine.MakeCatalogAliveMutation;
import io.evitadb.core.Catalog;
import io.evitadb.core.Evita;
import io.evitadb.core.ExpandedEngineState;
import io.evitadb.core.UnusableCatalog;
import io.evitadb.core.metric.event.transaction.CatalogGoesLiveEvent;
import io.evitadb.core.transaction.engine.AbstractEngineStateUpdater;
import io.evitadb.core.transaction.engine.EngineStateUpdater;
import io.evitadb.exception.EvitaInvalidUsageException;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.nio.file.Path;
import java.util.Collections;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Activates the specified catalog based on its current state. Throws appropriate exceptions
 * for inactive or corrupted catalogs or any unknown catalog type.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@RequiredArgsConstructor
public class MakeCatalogAliveMutationOperator implements EngineMutationOperator<CommitVersions, MakeCatalogAliveMutation> {
	private final Path storageDirectory;

	@Nonnull
	@Override
	public String getOperationName(@Nonnull MakeCatalogAliveMutation engineMutation) {
		return "Making catalog `" + engineMutation.getCatalogName() + "` alive";
	}

	@Nonnull
	@Override
	public ProgressingFuture<CommitVersions> applyMutation(
		@Nonnull UUID transactionId,
		@Nonnull MakeCatalogAliveMutation mutation, @Nonnull Evita evita,
		@Nonnull Consumer<EngineStateUpdater> transitionEngineStateUpdater,
		@Nonnull Consumer<EngineStateUpdater> completionEngineStateUpdater
	) {
		final String catalogName = mutation.getCatalogName();

		final CatalogContract catalog = evita.getCatalogInstanceOrThrowException(catalogName);
		if (catalog instanceof Catalog theCatalog) {
			final Path catalogFolder = this.storageDirectory.resolve(catalogName);
			transitionEngineStateUpdater.accept(
				new AbstractEngineStateUpdater(transactionId, mutation) {
					@Override
					public ExpandedEngineState apply(long version, @Nonnull ExpandedEngineState expandedEngineState) {
						return ExpandedEngineState
							.builder(expandedEngineState)
							.withVersion(version)
							.withCatalog(
								new UnusableCatalog(
									catalogName, CatalogState.GOING_ALIVE,
									catalogFolder,
									(cn, path) -> new CatalogGoingLiveException(cn)
								)
							)
							.build();
					}
				}
			);

			final CatalogGoesLiveEvent event = new CatalogGoesLiveEvent(catalogName);
			return new ProgressingFuture<>(
				1,
				Collections.singletonList(theCatalog.flush()),
				(theFuture, __) -> {
					final Catalog newCatalog = theCatalog.goLive();
					theFuture.updateProgress(1);
					// emit the event
					event.finish().commit();

					completionEngineStateUpdater.accept(
						new AbstractEngineStateUpdater(transactionId, mutation) {
							@Override
							public ExpandedEngineState apply(long version, @Nonnull ExpandedEngineState expandedEngineState) {
								return ExpandedEngineState
									.builder(expandedEngineState)
									.withVersion(version)
									.withCatalog(newCatalog)
									.build();
							}
						}
					);

					newCatalog.notifyCatalogPresentInLiveView();
					evita.discardSuspension(newCatalog.getName());

					return new CommitVersions(newCatalog.getVersion(), newCatalog.getSchema().version());
				}
			);
		} else if (catalog instanceof UnusableCatalog unusableCatalog) {
			throw unusableCatalog.getRepresentativeException();
		} else if (catalog == null) {
			throw new CatalogNotFoundException(catalogName);
		} else {
			throw new EvitaInvalidUsageException("Unknown catalog type: `" + catalog.getClass() + "`!");
		}
	}

}
