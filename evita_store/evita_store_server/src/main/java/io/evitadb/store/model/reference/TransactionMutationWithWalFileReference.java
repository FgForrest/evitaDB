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

package io.evitadb.store.model.reference;


import io.evitadb.api.requestResponse.mutation.EngineMutation;
import io.evitadb.api.requestResponse.mutation.infrastructure.TransactionMutation;
import io.evitadb.spi.store.catalog.shared.model.TransactionMutationWithWalReference;
import io.evitadb.spi.store.engine.EnginePersistenceService;

import javax.annotation.Nonnull;
import java.util.UUID;

/**
 * A record that associates a transaction mutation with its location in the Write-Ahead Log (WAL) file system.
 *
 * This class serves as a container that holds both a transaction mutation and a reference to where it's stored
 * in the WAL, allowing the system to track and retrieve transaction mutations efficiently. It's primarily used
 * when appending transaction mutations to the WAL in the persistence service.
 *
 * The class contains two components:
 * - `walReference`: A reference to the WAL file where the transaction mutation is stored
 * - `transactionMutation`: The transaction mutation itself, containing information about the transaction
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 * @see LogFileRecordReference
 * @see TransactionMutation
 * @see EnginePersistenceService#appendWal(long, UUID, EngineMutation)
 */
public record TransactionMutationWithWalFileReference(
	@Nonnull LogFileRecordReference walReference,
	@Nonnull TransactionMutation transactionMutation
) implements TransactionMutationWithWalReference {
}
