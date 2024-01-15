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

package io.evitadb.core.transaction;

import io.evitadb.api.CatalogContract;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation;
import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.api.requestResponse.transaction.TransactionMutation;
import io.evitadb.core.Catalog;
import io.evitadb.core.EntityCollection;
import io.evitadb.core.Transaction;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.index.transactionalMemory.TransactionalLayerMaintainer;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.util.Iterator;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * TODO JNO - document me
 * TODO JNO - implementovat slučování více transakcí do jedné, pokud se stíhají v intervalu
 * (TO BY BYLO ASI MOŽNÉ TÍM, ŽE SE STÁVAJÍCÍ TRANSACTION MEMORY MÍSTO ZAHOZENÍ PŘEDÁ DO DALŠÍ TRANSAKCE - POKUD TATO
 * TRANSAKCE OBSAHUJE POUZE TAKOVÝ POČET MUTACÍ, KTERÉ SE PRAVDĚPODOBNĚ STÍHAJÍ ZPRACOVAT V INTERVALU)
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class TransactionTrunkFinalizer implements Callable<CatalogContract>, TransactionHandler {
	private final AtomicReference<Catalog> currentCatalog;
	private final Iterator<Mutation> mutationIterator;
	private final AtomicLong lastFinalizedCatalogVersion;
	private final long timeout;

	public TransactionTrunkFinalizer(@Nonnull Catalog currentCatalog, int timeoutMillis) {
		this.currentCatalog = new AtomicReference<>(currentCatalog);
		// convert timeout to nanos
		this.timeout = timeoutMillis * 1_000_000L;
		this.mutationIterator = currentCatalog.getCommittedMutationIterator(currentCatalog.getVersion());
		this.lastFinalizedCatalogVersion = new AtomicLong(currentCatalog.getVersion());
	}

	@Override
	public CatalogContract call() throws Exception {
		Catalog processedCatalog = this.currentCatalog.get();
		if (mutationIterator.hasNext()) {
			final long start = System.nanoTime();
			Mutation leadingMutation = mutationIterator.next();
			do {
				Assert.isPremiseValid(leadingMutation instanceof TransactionMutation, "First mutation must be transaction mutation!");
				final TransactionMutation transactionMutation = (TransactionMutation) leadingMutation;
				// read WAL and apply mutations to the catalog
				final Transaction transaction = new Transaction(transactionMutation.getTransactionId(), this);
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
								processedCatalog.updateSchema(schemaMutation);
							} else if (mutation instanceof EntityMutation entityMutation) {
								// reuse last collection if the entity type is the same
								final String mutationEntityType = entityMutation.getEntityType();
								collection = collection == null || !entityType.equals(mutationEntityType) ?
									processedCatalog.getCollectionForEntityOrThrowException(mutationEntityType) :
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
		return processedCatalog;
	}

	@Override
	public void commit(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		final Catalog catalogToUpdate = this.currentCatalog.get();
		// init new catalog with the same collections as previous one
		final Catalog newCatalog = transactionalLayer.getStateCopyWithCommittedChanges(catalogToUpdate);
		// now let's flush the catalog on the disk
		newCatalog.flush(lastFinalizedCatalogVersion.get());
		// and set reference to a new catalog
		final Catalog witness = this.currentCatalog.compareAndExchange(catalogToUpdate, newCatalog);
		// verify expectations
		Assert.isPremiseValid(witness == catalogToUpdate, "Catalog was changed by other thread in the meantime!");
	}

	@Override
	public void rollback(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		throw new EvitaInternalError("Rollback is not supported here!");
	}

	@Override
	public void registerMutation(@Nonnull Mutation mutation) {
		// mutations are not written to WAL in this implementation, we process them here
	}

}
