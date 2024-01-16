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
import io.evitadb.api.exception.TransactionTimedOutException;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation;
import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.api.requestResponse.transaction.TransactionMutation;
import io.evitadb.core.Catalog;
import io.evitadb.core.EntityCollection;
import io.evitadb.core.Evita;
import io.evitadb.core.Transaction;
import io.evitadb.core.transaction.TransactionTrunkFinalizer;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.utils.Assert;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Iterator;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.SynchronousQueue;

/**
 * TODO JNO - document me
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Slf4j
public class TrunkIncorporationTransactionStage implements TransactionStage {
	private final Evita evita;
	private final TrunkIncorporationTransactionStage trunkIncorporationTransactionStage;

	private final SynchronousQueue<Boolean> newWorkQueue = new SynchronousQueue<>();
	private final
	private final ConcurrentHashMap<String, BlockingQueue<TrunkIncorporationTransactionTask>> queue = new ConcurrentHashMap<>(32);
	private final long timeout;

	public TrunkIncorporationTransactionStage(Evita evita, TrunkIncorporationTransactionStage trunkIncorporationTransactionStage) {
		this.evita = evita;
		this.trunkIncorporationTransactionStage = trunkIncorporationTransactionStage;
		// convert timeout to nanos
		/* TODO JNO - možná zvážit založení nového konfiguráku čistě pro transakce?! */
		this.timeout = /*evita.getConfiguration().storage().()*/ 100 * 1_000_000L;
	}

	public void processQueue() {
		while (true) {
			try {
				final TrunkIncorporationTransactionTask task = queue.take();
				try {
					final Catalog catalog = (Catalog) evita.getCatalogInstanceOrThrowException(task.catalogName());
					final Iterator<Mutation> mutationIterator = catalog.getCommittedMutationIterator(task.catalogVersion());
					if (mutationIterator.hasNext()) {
						final long start = System.nanoTime();
						Mutation leadingMutation = mutationIterator.next();
						do {
							Assert.isPremiseValid(leadingMutation instanceof TransactionMutation, "First mutation must be transaction mutation!");
							final TransactionMutation transactionMutation = (TransactionMutation) leadingMutation;
							// read WAL and apply mutations to the catalog
							final Transaction transaction = new Transaction(
								transactionMutation.getTransactionId(),
								new TransactionTrunkFinalizer(catalog)
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

									lastFinalizedCatalogVersion.set(transactionMutation.getCatalogVersion());
									// TODO JNO - toto by se mělo posunout za další while a ty transakce by měly sdílet rozpracovanou práci
									transaction.close();
								}
							);
						} while (System.nanoTime() - start < this.timeout && mutationIterator.hasNext());
					}

					if (task.commitBehaviour() == CommitBehaviour.WAIT_FOR_INDEX_PROPAGATION && task.future() != null) {
						task.future().complete(task.catalogVersion());
					}
				} catch (Throwable ex) {
					task.future.completeExceptionally(ex);
					throw ex;
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			} catch (Throwable e) {
				log.error("Error while processing trunk incorporation task!", e);
			}
		}
	}

	public void submit(
		@Nonnull String catalogName,
		@Nonnull UUID transactionId,
		long catalogVersion,
		@Nonnull CommitBehaviour commitBehaviour,
		@Nullable CompletableFuture<Long> future
	) {
		final boolean accepted = queue
			.computeIfAbsent(
				catalogName, theCatalogName -> new ArrayBlockingQueue<>(32)
			).offer(
				new TrunkIncorporationTransactionTask(
					catalogName,
					catalogVersion,
					transactionId,
					commitBehaviour,
					future
				)
			);
		newWorkQueue.offer(true);
		if (!accepted) {
			throw new TransactionTimedOutException(
				"Transaction timed out - trunk incorporation process reached maximal capacity (" + queue.size() + ")!"
			);
		}
	}

	@Nonnull
	@Override
	public CompletableFuture<Void> removeCatalog() {
		// TODO JNO - implement
		return null;
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
	) {
	}

}
