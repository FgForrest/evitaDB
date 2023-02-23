/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
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

package io.evitadb.store.spi;

import javax.annotation.Nonnull;

/**
 * Deferred storage operation represents an IO related operation that needs to be executed when the transaction is
 * committed. The operation represents an I/O manipulation that should be incorporated into the main data storage only
 * when the {@link io.evitadb.core.Transaction} gets committed. If it's rolled back all those IO operations should not
 * be executed at all and should be thrown away along with the transaction itself.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public interface DeferredStorageOperation<T extends PersistenceService> {

	/**
	 * Returns the type of the {@link PersistenceService} this deferred operation requires.
	 */
	@Nonnull
	Class<T> getRequiredPersistenceServiceType();

	/**
	 * Executes the IO operation itself using the passed persistence service.
	 * @param owner a string representing object that produced this instance (just for debugging purposes)
	 * @param transactionId id of the transaction, that is being committed
	 * @param persistenceService persistence service, that can be used for execution of the IO operation
	 */
	void execute(@Nonnull String owner, long transactionId, @Nonnull T persistenceService);

}
