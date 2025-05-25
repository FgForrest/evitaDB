/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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

package io.evitadb.core.transaction;

import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.api.requestResponse.transaction.TransactionMutation;
import io.evitadb.core.Catalog;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;

/**
 * This implementation is used to incorporate multiple transaction into the trunk of the catalog. Commits are ignored
 * so that multiple "transaction" can be incorporated in a single "turn". The final commit that may contain at least
 * one full transaction or may also contain multiple shorter full transaction is done by calling
 * {@link #commitCatalogChanges(long, TransactionMutation)}  which makes also additional uses of this instance impossible.
 *
 * This class is not thread-safe.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@NotThreadSafe
public class TransactionTrunkFinalizer implements TransactionHandler {
	/**
	 * Represents the catalog that needs to be updated.
	 */
	private final Catalog catalogToUpdate;
	/**
	 * Represents the last transactional layer committed in the trunk. Once assigned all additional commits must refer
	 * to the very same transactional layer (as if the first transaction is simply prolonged).
	 */
	private TransactionalLayerMaintainer lastTransactionLayer;
	/**
	 * Represents the committed catalog.
	 *
	 * This variable holds the instance of the catalog that has been committed after calling {@link #commitCatalogChanges(long, TransactionMutation)}
	 * method in the TransactionTrunkFinalizer class. It stores the state of the catalog with all the changes made in the
	 * transactional layers. When this variable is non-null additional commits are not allowed.
	 */
	private Catalog committedCatalog;

	public TransactionTrunkFinalizer(@Nonnull Catalog catalogToUpdate) {
		this.catalogToUpdate = catalogToUpdate;
	}

	@Override
	public void commit(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		if (this.lastTransactionLayer == null) {
			this.lastTransactionLayer = transactionalLayer;
		} else {
			Assert.isPremiseValid(
				this.lastTransactionLayer == transactionalLayer,
				"Transaction layer must be the same for all transaction in the trunk!");
		}
	}

	@Override
	public void rollback(@Nonnull TransactionalLayerMaintainer transactionalLayer, @Nullable Throwable cause) {
		throw new GenericEvitaInternalError("Rollback is not supported here!");
	}

	/**
	 * This method should be used to build new transaction on top of the changes made by the last transaction.
	 *
	 * @return last transaction layer
	 */
	@Nullable
	public TransactionalLayerMaintainer getLastTransactionLayer() {
		return this.lastTransactionLayer;
	}

	/**
	 * This method finally commits the catalog changes and returns the new instance of the committed catalog.
	 * @param catalogVersion transaction id
	 * @return committed catalog
	 */
	@Nonnull
	public Catalog commitCatalogChanges(long catalogVersion, @Nonnull TransactionMutation lastProcessedTransaction) {
		Assert.isPremiseValid(this.committedCatalog == null, "Catalog was already committed!");
		Assert.isPremiseValid(lastProcessedTransaction != null, "Information about last processed transaction must be provided!");
		// now let's flush the catalog on the disk
		this.catalogToUpdate.flush(catalogVersion, lastProcessedTransaction);
		// init new catalog with the same collections as the previous one
		final Catalog newCatalog = this.lastTransactionLayer.getStateCopyWithCommittedChanges(this.catalogToUpdate);
		Assert.isPremiseValid(
			newCatalog.getVersion() == catalogVersion,
			"Catalog version mismatch!"
		);
		// verify everything was processed
		this.lastTransactionLayer.verifyLayerWasFullySwept();
		// assign committed catalog
		this.committedCatalog = newCatalog;
		// and return created catalog
		return this.committedCatalog;
	}

	@Override
	public void registerMutation(@Nonnull Mutation mutation) {
		// mutations are not written to WAL in this implementation, we process them here
	}

}
