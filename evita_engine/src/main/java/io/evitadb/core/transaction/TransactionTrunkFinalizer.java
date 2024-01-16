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

import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.core.Catalog;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.index.transactionalMemory.TransactionalLayerMaintainer;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
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
public class TransactionTrunkFinalizer implements TransactionHandler {
	private final AtomicReference<Catalog> currentCatalog;
	private final AtomicLong lastFinalizedCatalogVersion;

	public TransactionTrunkFinalizer(@Nonnull Catalog currentCatalog) {
		this.currentCatalog = new AtomicReference<>(currentCatalog);
		this.lastFinalizedCatalogVersion = new AtomicLong(currentCatalog.getVersion());
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
