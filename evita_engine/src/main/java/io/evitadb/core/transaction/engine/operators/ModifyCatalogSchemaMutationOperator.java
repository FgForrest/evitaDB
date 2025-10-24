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
import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.requestResponse.progress.ProgressingFuture;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.mutation.engine.ModifyCatalogSchemaMutation;
import io.evitadb.core.Evita;
import io.evitadb.core.ExpandedEngineState;
import io.evitadb.core.Transaction;
import io.evitadb.core.transaction.engine.AbstractEngineStateUpdater;
import io.evitadb.core.transaction.engine.EngineStateUpdater;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Modifies the catalog schema by applying the provided schema mutation. This method ensures that
 * the modification takes place within an existing session and updates the catalog schema accordingly.
 * It also observes progress and reports completion or errors.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class ModifyCatalogSchemaMutationOperator implements EngineMutationOperator<CommitVersions, ModifyCatalogSchemaMutation> {

	@Nonnull
	@Override
	public String getOperationName(@Nonnull ModifyCatalogSchemaMutation engineMutation) {
		return "Modifying catalog schema `" + engineMutation.getCatalogName() + "`";
	}

	@Nonnull
	@Override
	public ProgressingFuture<CommitVersions> applyMutation(
		@Nonnull UUID transactionId,
		@Nonnull ModifyCatalogSchemaMutation mutation,
		@Nonnull Evita evita,
		@Nonnull Consumer<EngineStateUpdater> transitionEngineStateUpdater,
		@Nonnull Consumer<EngineStateUpdater> completionEngineStateUpdater
	) {
		Assert.isTrue(
			mutation.getSessionId() != null,
			() -> new InvalidSchemaMutationException(
				"Cannot modify catalog schema outside an existing session! " +
					"Please use methods available on `EvitaSessionContract` interface."
			)
		);

		//noinspection resource
		final Transaction theTransaction = Transaction.getTransaction().orElse(null);

		return new ProgressingFuture<>(
			1,
			theFuture -> Transaction.executeInTransactionIfProvided(
				theTransaction,
				() -> {
					final String catalogName = mutation.getCatalogName();
					final CatalogContract catalog = evita.getCatalogInstanceOrThrowException(catalogName);
					final CatalogSchemaContract newSchema = catalog.updateSchema(
						evita, mutation.getSessionId(), mutation.getSchemaMutations()
					);
					completionEngineStateUpdater.accept(increaseEngineVersionOnly(transactionId, mutation));
					return new CommitVersions(
						// we need to fetch catalog again, because by schema update version is incremented
						evita.getCatalogInstanceOrThrowException(catalogName).getVersion(),
						newSchema.version()
					);
				}
			)
		);
	}

	/**
	 * Creates an instance of an {@link AbstractEngineStateUpdater} that increments the engine version
	 * without modifying the actual engine state. This method facilitates an internal state update
	 * process triggered by a transaction and schema mutation.
	 *
	 * @param transactionId the unique identifier for the transaction associated with this operation
	 * @param mutation the mutation defining changes to the catalog schema, used in association with the transaction
	 * @return a new instance of {@link AbstractEngineStateUpdater} that updates the engine's version only
	 */
	@Nonnull
	public static AbstractEngineStateUpdater increaseEngineVersionOnly(
		@Nonnull UUID transactionId,
		@Nonnull ModifyCatalogSchemaMutation mutation
	) {
		return new AbstractEngineStateUpdater(transactionId, mutation) {
			@Override
			public ExpandedEngineState apply(long version, @Nonnull ExpandedEngineState expandedEngineState) {
				// no actual change of the engine state
				return ExpandedEngineState
					.builder(expandedEngineState)
					.withVersion(version)
					.build();
			}
		};
	}

}
