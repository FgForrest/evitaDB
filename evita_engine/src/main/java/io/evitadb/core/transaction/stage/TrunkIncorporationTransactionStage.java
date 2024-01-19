/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.core.transaction.stage;

import io.evitadb.api.TransactionContract.CommitBehaviour;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation;
import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.api.requestResponse.transaction.TransactionMutation;
import io.evitadb.core.Catalog;
import io.evitadb.core.EntityCollection;
import io.evitadb.core.Transaction;
import io.evitadb.core.transaction.TransactionTrunkFinalizer;
import io.evitadb.core.transaction.stage.TrunkIncorporationTransactionStage.TrunkIncorporationTransactionTask;
import io.evitadb.core.transaction.stage.TrunkIncorporationTransactionStage.UpdatedCatalogTransactionTask;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.utils.Assert;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.util.Iterator;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * TODO JNO - document me
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Slf4j
@NotThreadSafe
public final class TrunkIncorporationTransactionStage
	extends AbstractTransactionStage<TrunkIncorporationTransactionTask, UpdatedCatalogTransactionTask> {
	private final Catalog catalog;
	private final long timeout;
	private UUID lastFinalizedTransactionId;
	private long lastFinalizedCatalogVersion;

	public TrunkIncorporationTransactionStage(@Nonnull Executor executor, int maxBufferCapacity, @Nonnull Catalog catalog, long timeout) {
		super(executor, maxBufferCapacity, catalog.getName());
		this.catalog = catalog;
		this.timeout = timeout;
	}

	@Override
	protected String getName() {
		return "trunk incorporation";
	}

	@Override
	protected void handleNext(@Nonnull TrunkIncorporationTransactionTask task) {
		if (task.catalogVersion() <= lastFinalizedCatalogVersion) {
			// the transaction has been already processed
			return;
		} else {
			final TransactionTrunkFinalizer transactionHandler = new TransactionTrunkFinalizer(catalog);
			final Iterator<Mutation> mutationIterator = catalog.getCommittedMutationIterator(task.catalogVersion());
			if (mutationIterator.hasNext()) {
				final long start = System.nanoTime();
				Mutation leadingMutation = mutationIterator.next();
				do {
					Assert.isPremiseValid(leadingMutation instanceof TransactionMutation, "First mutation must be transaction mutation!");
					final TransactionMutation transactionMutation = (TransactionMutation) leadingMutation;
					Assert.isPremiseValid(
						transactionMutation.getCatalogVersion() == lastFinalizedCatalogVersion + 1,
						"Unexpected catalog version! " +
							"Transaction mutation catalog version: " + transactionMutation.getCatalogVersion() + ", " +
							"last finalized catalog version: " + lastFinalizedCatalogVersion + "."
					);

					// read WAL and apply mutations to the catalog
					final Transaction transaction = new Transaction(
						transactionMutation.getTransactionId(),
						transactionHandler
					);

					Transaction.executeInTransactionIfProvided(
						transaction,
						() -> {
							int mutationCount = 0;
							String entityType = null;
							EntityCollection collection = null;
							while (mutationIterator.hasNext() && mutationCount < transactionMutation.getMutationCount()) {
								final Mutation mutation = mutationIterator.next();
								mutationCount++;
								if (mutation instanceof LocalCatalogSchemaMutation schemaMutation) {
									// apply schema mutation to the catalog
									catalog.updateSchema(schemaMutation);
								} else if (mutation instanceof EntityMutation entityMutation) {
									// reuse last collection if the entity type is the same
									final String mutationEntityType = entityMutation.getEntityType();
									collection = collection == null || !entityType.equals(mutationEntityType) ?
										catalog.getCollectionForEntityOrThrowException(mutationEntityType) :
										collection;
									entityType = mutationEntityType.equals(entityType) ?
										entityType : mutationEntityType;
									// apply mutation to the collection
									collection.upsertEntity(entityMutation);
								} else {
									throw new EvitaInternalError("Unexpected mutation type: " + mutation.getClass().getName());
								}
							}

							this.lastFinalizedTransactionId = transaction.getTransactionId();
							this.lastFinalizedCatalogVersion = transactionMutation.getCatalogVersion();
						}
					);

					// this is the last mutation in the transaction
					transaction.close();
				} while (System.nanoTime() - start < this.timeout && mutationIterator.hasNext());
			}

			final Catalog updatedCatalogTrunk = transactionHandler.commitCatalogChanges(lastFinalizedCatalogVersion);
			push(
				task,
				new UpdatedCatalogTransactionTask(
					updatedCatalogTrunk,
					lastFinalizedTransactionId,
					task.commitBehaviour(),
					task.future()
				)
			);
		}
	}

	/**
	 * TODO JNO - document me
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
	 */
	public record TrunkIncorporationTransactionTask(
		@Nonnull String catalogName,
		long catalogVersion,
		@Nonnull UUID transactionId,
		@Nonnull CommitBehaviour commitBehaviour,
		@Nullable CompletableFuture<Long> future
	) implements TransactionTask {
	}

	/**
	 * TODO JNO - document me
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
	 */
	public record UpdatedCatalogTransactionTask(
		@Nonnull Catalog catalog,
		@Nonnull UUID transactionId,
		@Nonnull CommitBehaviour commitBehaviour,
		@Nullable CompletableFuture<Long> future
	) implements TransactionTask {

		@Nonnull
		@Override
		public String catalogName() {
			return catalog.getName();
		}

		@Override
		public long catalogVersion() {
			return catalog.getVersion();
		}
	}

}
