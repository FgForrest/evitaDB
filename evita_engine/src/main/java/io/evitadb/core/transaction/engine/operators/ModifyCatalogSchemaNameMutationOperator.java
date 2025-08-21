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
import io.evitadb.api.CommitProgress.CommitVersions;
import io.evitadb.api.exception.CatalogAlreadyPresentException;
import io.evitadb.api.requestResponse.progress.ProgressingFuture;
import io.evitadb.api.requestResponse.schema.mutation.CatalogSchemaMutation.CatalogSchemaWithImpactOnEntitySchemas;
import io.evitadb.api.requestResponse.schema.mutation.engine.ModifyCatalogSchemaNameMutation;
import io.evitadb.core.Catalog;
import io.evitadb.core.Evita;
import io.evitadb.core.ExpandedEngineState;
import io.evitadb.core.ExpandedEngineState.Builder;
import io.evitadb.core.SessionRegistry;
import io.evitadb.core.SuspendOperation;
import io.evitadb.core.transaction.engine.AbstractEngineStateUpdater;
import io.evitadb.core.transaction.engine.EngineStateUpdater;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static io.evitadb.utils.Assert.isTrue;

/**
 * Replaces or renames existing catalog in evitaDB.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class ModifyCatalogSchemaNameMutationOperator implements EngineMutationOperator<CommitVersions, ModifyCatalogSchemaNameMutation> {

	@Nonnull
	@Override
	public String getOperationName(@Nonnull ModifyCatalogSchemaNameMutation engineMutation) {
		if (engineMutation.isOverwriteTarget()) {
			return "Replacing catalog `" + engineMutation.getCatalogName() + "` with `" + engineMutation.getNewCatalogName() + "`";
		} else {
			return "Renaming catalog `" + engineMutation.getCatalogName() + "` to `" + engineMutation.getNewCatalogName() + "`";
		}
	}

	@Nonnull
	@Override
	public ProgressingFuture<CommitVersions> applyMutation(
		@Nonnull UUID transactionId,
		@Nonnull ModifyCatalogSchemaNameMutation mutation,
		@Nonnull Evita evita,
		@Nonnull Consumer<EngineStateUpdater> transitionEngineStateUpdater,
		@Nonnull Consumer<EngineStateUpdater> completionEngineStateUpdater
	) {
		if (mutation.isOverwriteTarget()) {
			final String catalogNameToBeReplacedWith = mutation.getCatalogName();
			final String catalogNameToBeReplaced = mutation.getNewCatalogName();
			final CatalogContract catalogToBeReplaced = evita.getCatalogInstance(catalogNameToBeReplaced).orElse(null);
			final CatalogContract catalogToBeReplacedWith = evita.getCatalogInstanceOrThrowException(catalogNameToBeReplacedWith);
			return doReplaceCatalogInternal(
				catalogNameToBeReplaced, catalogNameToBeReplacedWith,
				catalogToBeReplaced, catalogToBeReplacedWith,
				transactionId, mutation, evita, completionEngineStateUpdater
			);
		} else {
			final String currentName = mutation.getCatalogName();
			final String newName = mutation.getNewCatalogName();
			isTrue(!evita.getCatalogNames().contains(newName), () -> new CatalogAlreadyPresentException(newName, newName));
			final CatalogContract catalogToBeRenamed = evita.getCatalogInstanceOrThrowException(currentName);
			return doReplaceCatalogInternal(
				newName, currentName,
				catalogToBeRenamed, catalogToBeRenamed,
				transactionId, mutation, evita, completionEngineStateUpdater
			);
		}
	}

	/**
	 * Internal shared implementation of catalog replacement used both from rename and replace existing catalog methods.
	 */
	@Nonnull
	protected ProgressingFuture<CommitVersions> doReplaceCatalogInternal(
		@Nonnull String catalogNameToBeReplaced,
		@Nonnull String catalogNameToBeReplacedWith,
		@Nullable CatalogContract catalogToBeReplaced,
		@Nonnull CatalogContract catalogToBeReplacedWith,
		@Nonnull UUID transactionId,
		@Nonnull ModifyCatalogSchemaNameMutation mutation,
		@Nonnull Evita evita,
		@Nonnull Consumer<EngineStateUpdater> completionEngineStateUpdater
	) {
		// close all active sessions to the catalog that will replace the original one
		final Optional<SessionRegistry> prevailingCatalogSessionRegistry = evita.getCatalogSessionRegistry(catalogNameToBeReplacedWith);
		// this will be always empty if catalogToBeReplaced == catalogToBeReplacedWith
		Optional<SessionRegistry> removedCatalogSessionRegistry = evita.getCatalogSessionRegistry(catalogNameToBeReplaced);

		prevailingCatalogSessionRegistry
			.ifPresent(sessionRegistry -> sessionRegistry.closeAllActiveSessionsAndSuspend(SuspendOperation.POSTPONE));

		final Runnable undoOperations = () -> {
			// revert session registry swap
			if (removedCatalogSessionRegistry.isPresent()) {
				evita.registerCatalogSessionRegistry(catalogNameToBeReplaced, removedCatalogSessionRegistry.get());
			} else {
				evita.removeCatalogSessionRegistryIfPresent(catalogNameToBeReplaced);
			}
		};

		try {
			final boolean replaceOperation = catalogToBeReplaced != catalogToBeReplacedWith;
			// first terminate the catalog that is being replaced (unless it's the very same catalog)
			if (replaceOperation) {
				removedCatalogSessionRegistry
					.ifPresent(it -> it.closeAllActiveSessionsAndSuspend(SuspendOperation.REJECT));
			} else {
				Assert.isPremiseValid(removedCatalogSessionRegistry.isEmpty(), "Expectation failed!");
			}

			final CatalogSchemaWithImpactOnEntitySchemas updatedSchemaWrapper = mutation.mutate(catalogToBeReplacedWith.getSchema());
			Assert.isPremiseValid(
				updatedSchemaWrapper != null,
				"Result of modify catalog schema mutation must not be null."
			);

			return new ProgressingFuture<>(
				1,
				Collections.singleton(
					catalogToBeReplacedWith
						.replace(
							updatedSchemaWrapper.updatedCatalogSchema(),
							catalogToBeReplaced
						)
				),
				(theFuture, replacedCatalogs) -> {
					final CatalogContract replacedCatalog = replacedCatalogs.iterator().next();

					completionEngineStateUpdater.accept(
						new AbstractEngineStateUpdater(transactionId, mutation) {
							@Override
							public ExpandedEngineState apply(long version, @Nonnull ExpandedEngineState expandedEngineState) {
								final Builder stateAfterAddingRenamedCatalog = ExpandedEngineState
									.builder(expandedEngineState)
									.withVersion(version)
									.withCatalog(replacedCatalog);
								if (!catalogNameToBeReplaced.equals(catalogNameToBeReplacedWith)) {
									stateAfterAddingRenamedCatalog.withoutCatalog(catalogNameToBeReplacedWith);
								}
								return stateAfterAddingRenamedCatalog.build();
							}
						}
					);

					// notify callback that it's now a live snapshot
					((Catalog) replacedCatalog).notifyCatalogPresentInLiveView();

					if (replaceOperation) {
						// we can resume suspended operations on catalogs
						prevailingCatalogSessionRegistry.ifPresent(
							sessionRegistry -> {
								evita.removeCatalogSessionRegistryIfPresent(catalogNameToBeReplacedWith);
								final SessionRegistry previous = evita.registerWithReplaceCatalogSessionRegistry(
									catalogNameToBeReplaced,
									sessionRegistry.withDifferentCatalogSupplier(
										() -> (Catalog) evita.getCatalogInstanceOrThrowException(
											catalogNameToBeReplaced))
								);
								Assert.isPremiseValid(
									previous == null || previous == removedCatalogSessionRegistry.get(),
									"Unexpected instance of the session registry was replaced!"
								);
								sessionRegistry.resumeOperations();
							}
						);
					} else {
						removedCatalogSessionRegistry.ifPresent(SessionRegistry::resumeOperations);
					}

					// terminate the catalog that was replaced
					if (replaceOperation && catalogToBeReplaced != null) {
						catalogToBeReplaced.terminate();
					}

					return new CommitVersions(
						replacedCatalog.getVersion(),
						replacedCatalog.getSchema().version()
					);
				},
				ex -> undoOperations.run()
			);
		} catch (RuntimeException ex) {
			undoOperations.run();
			throw ex;
		}
	}

}
