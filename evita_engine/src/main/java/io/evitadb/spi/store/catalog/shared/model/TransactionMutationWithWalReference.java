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

package io.evitadb.spi.store.catalog.shared.model;

import io.evitadb.api.requestResponse.mutation.infrastructure.TransactionMutation;

import javax.annotation.Nonnull;
import java.io.Serializable;

/**
 * Pairs a transaction-level mutation with a reference to the durable record written to the
 * write-ahead log (WAL).
 *
 * In contrast to {@link LogRecordReference} which only identifies a single persistent log record,
 * this interface couples two pieces of information that travel together through the persistence
 * pipeline:
 *
 * - the actual business operation represented by {@link #transactionMutation()} and
 * - the reference to the WAL entry that durably stores that operation
 *   ({@link #walReference()}).
 *
 * This pairing allows components responsible for acknowledgment, recovery, de-duplication,
 * replication or replays to reason not only about *where* the change is stored, but also *what*
 * the change is, in a single value object. Implementations are expected to be simple value holders
 * and preferably immutable.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public interface TransactionMutationWithWalReference extends Serializable {

	/**
	 * Reference to the WAL record that durably persisted the associated mutation.
	 *
	 * The returned reference can be used to track durability, perform recovery/replay, or
	 * coordinate replication where an explicit pointer to the underlying log entry is required.
	 *
	 * @return non-null reference to the corresponding WAL record
	 */
	@Nonnull
	LogRecordReference walReference();

	/**
	 * The mutation that should be applied within the transaction scope.
	 *
	 * This is the high-level change that was serialized and written to the WAL, and which will be
	 * executed against in-memory structures and persisted storage.
	 *
	 * @return non-null transaction mutation
	 */
	@Nonnull
	TransactionMutation transactionMutation();

}
