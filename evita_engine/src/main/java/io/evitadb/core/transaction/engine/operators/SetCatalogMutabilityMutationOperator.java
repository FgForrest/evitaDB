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
import io.evitadb.api.requestResponse.progress.ProgressingFuture;
import io.evitadb.api.requestResponse.schema.mutation.engine.SetCatalogMutabilityMutation;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.catalog.UnusableCatalog;
import io.evitadb.core.engine.ExpandedEngineState;
import io.evitadb.core.transaction.engine.AbstractEngineStateUpdater;
import io.evitadb.core.transaction.engine.EngineStateUpdater;

import javax.annotation.Nonnull;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Modifies the mutability state of a specified catalog. This method processes the
 * input mutation to either make the catalog mutable (read-write) or immutable (read-only).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class SetCatalogMutabilityMutationOperator
	implements EngineMutationOperator<Void, SetCatalogMutabilityMutation> {

	@Nonnull
	@Override
	public String getOperationName(@Nonnull SetCatalogMutabilityMutation engineMutation) {
		if (engineMutation.isMutable()) {
			return "Setting catalog `" + engineMutation.getCatalogName() + "` to read-write";
		} else {
			return "Setting catalog `" + engineMutation.getCatalogName() + "` to read-only";
		}
	}

	@Nonnull
	@Override
	public ProgressingFuture<Void> applyMutation(
		@Nonnull UUID transactionId,
		@Nonnull SetCatalogMutabilityMutation mutation, @Nonnull Evita evita,
		@Nonnull Consumer<EngineStateUpdater> transitionEngineStateUpdater,
		@Nonnull Consumer<EngineStateUpdater> completionEngineStateUpdater
	) {
		final String catalogName = mutation.getCatalogName();
		if (mutation.isMutable()) {
			return new ProgressingFuture<>(
				0,
				(progressingFuture) -> {
					final CatalogContract catalogContract = evita.getCatalogInstanceOrThrowException(catalogName);
					if (catalogContract instanceof Catalog theCatalog) {
						completionEngineStateUpdater.accept(
							new AbstractEngineStateUpdater(transactionId, mutation) {
								@Override
								public ExpandedEngineState apply(long version, @Nonnull ExpandedEngineState expandedEngineState) {
									theCatalog.setReadOnly(false);
									return ExpandedEngineState
										.builder(expandedEngineState)
										.withVersion(version)
										.withoutReadOnlyCatalog(theCatalog)
										.build();
								}
							}
						);
					} else {
						throw ((UnusableCatalog) catalogContract).getRepresentativeException();
					}
					return null;
				}
			);
		} else {
			return new ProgressingFuture<>(
				0,
				(progressingFuture) -> {
					final CatalogContract catalogContract = evita.getCatalogInstanceOrThrowException(catalogName);
					if (catalogContract instanceof Catalog theCatalog) {
						completionEngineStateUpdater.accept(
							new AbstractEngineStateUpdater(transactionId, mutation) {
								@Override
								public ExpandedEngineState apply(long version, @Nonnull ExpandedEngineState expandedEngineState) {
									theCatalog.setReadOnly(true);
									return ExpandedEngineState
										.builder(expandedEngineState)
										.withVersion(version)
										.withReadOnlyCatalog(theCatalog)
										.build();
								}
							}
						);
					} else {
						throw ((UnusableCatalog) catalogContract).getRepresentativeException();
					}
					return null;
				}
			);
		}
	}

	/**
	 * The completion phase of `SetCatalogMutabilityMutation` toggles the read-only flag on the live `Catalog`
	 * instance and updates the `readOnlyCatalogs` set in engine state. Both steps are idempotent: calling
	 * `setReadOnly(true/false)` a second time is a no-op, and the `ExpandedEngineState.Builder` tolerates adding /
	 * removing a catalog name from the set when it is already present / absent. Safe to replay.
	 *
	 * If the catalog referenced by the mutation is not present as a live `Catalog` at replay time (e.g., it is still
	 * loading asynchronously), we return `Optional.empty()` so the transaction manager wedges rather than applying
	 * an inconsistent snapshot.
	 */
	@Nonnull
	@Override
	public Optional<ExpandedEngineState> replayCompletionState(
		@Nonnull SetCatalogMutabilityMutation mutation,
		long targetVersion,
		@Nonnull ExpandedEngineState currentState,
		@Nonnull Evita evita
	) {
		final String catalogName = mutation.getCatalogName();
		final CatalogContract catalogContract = currentState.getCatalog(catalogName).orElse(null);
		if (!(catalogContract instanceof Catalog theCatalog)) {
			// Forward-replay is not safe when the catalog is not yet a live instance — wedge.
			return Optional.empty();
		}
		// Idempotent: repeated `setReadOnly` calls with the same flag are a no-op.
		theCatalog.setReadOnly(!mutation.isMutable());
		final ExpandedEngineState.Builder builder = ExpandedEngineState
			.builder(currentState)
			.withVersion(targetVersion);
		if (mutation.isMutable()) {
			builder.withoutReadOnlyCatalog(theCatalog);
		} else {
			builder.withReadOnlyCatalog(theCatalog);
		}
		return Optional.of(builder.build());
	}

}
