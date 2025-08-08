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

package io.evitadb.store.spi.model.reference;


import io.evitadb.api.requestResponse.mutation.EngineMutation;
import io.evitadb.api.requestResponse.transaction.TransactionMutation;

import javax.annotation.Nonnull;

/**
 * A record that associates a transaction mutation and its corresponding engine mutation with their location
 * in the Write-Ahead Log (WAL) file system.
 *
 * This class serves as an extended container that holds both a transaction mutation, its associated engine mutation,
 * and a reference to where they're stored in the WAL, allowing the system to track and retrieve both mutations
 * efficiently. It's primarily used when appending transaction mutations along with their engine mutations to the WAL
 * in the persistence service.
 *
 * The class contains three components:
 * - `walFileReference`: A reference to the WAL file where the mutations are stored
 * - `transactionMutation`: The transaction mutation itself, containing information about the transaction
 * - `engineMutation`: The corresponding engine mutation that implements the actual changes
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 * @see LogFileRecordReference
 * @see io.evitadb.api.requestResponse.transaction.TransactionMutation
 * @see io.evitadb.api.requestResponse.mutation.EngineMutation
 * @see io.evitadb.store.spi.EnginePersistenceService#appendWal(long, io.evitadb.api.requestResponse.mutation.EngineMutation)
 */
public record EngineTransactionMutationWithWalFileReference(
	@Nonnull LogFileRecordReference walFileReference,
	@Nonnull TransactionMutation transactionMutation,
	@Nonnull EngineMutation<?> engineMutation
) {
}
