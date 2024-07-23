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
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.store.spi;

import io.evitadb.api.TransactionContract;
import io.evitadb.api.requestResponse.mutation.Mutation;

import javax.annotation.Nonnull;
import java.io.Closeable;
import java.util.UUID;

/**
 * The {@code IsolatedWalPersistenceService} interface represents a service for persisting mutations using Write-Ahead
 * Logging (WAL).  It provides methods for writing mutations to the WAL, retrieving metadata about the WAL, and
 * obtaining a reference to the WAL data. It bears the name "isolated" because it is intended to store WAL records
 * for multiple parallel transactions at the same time. Instance of this service are created and held by the
 * {@link TransactionContract} implementation but need to write to a separate WAL files.
 *
 * The service also implements the {@code Closeable} interface to allow for proper resource cleanup.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public interface IsolatedWalPersistenceService extends Closeable {

	/**
	 * Retrieves the {@link TransactionContract#getTransactionId()} associated with this service.
	 *
	 * @return the transaction ID
	 */
	@Nonnull
	UUID getTransactionId();

	/**
	 * Retrieves the number of mutations stored in the IsolatedWalPersistenceService.
	 *
	 * @return the count of mutations
	 */
	int getMutationCount();

	/**
	 * Retrieves the size of the mutations stored in the IsolatedWalPersistenceService in bytes.
	 *
	 * @return the size of the mutations in bytes
	 */
	long getMutationSizeInBytes();

	/**
	 * Writes a mutation to the IsolatedWalPersistenceService connected to the particular catalog version.
	 *
	 * @param catalogVersion we expect the catalog version might conflict - because we're writing WAL before
	 *                       transaction commit but at least it will somehow describe the version of the catalog
	 *                       the transaction is based on
	 * @param mutation the mutation to write
	 */
	void write(long catalogVersion, @Nonnull Mutation mutation);

	/**
	 * Returns the reference to the Write-Ahead Log (WAL) data.
	 *
	 * @return the reference to the WAL data
	 */
	@Nonnull
	OffHeapWithFileBackupReference getWalReference();

	@Override
	void close();


}
