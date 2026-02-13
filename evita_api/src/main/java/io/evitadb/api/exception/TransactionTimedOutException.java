/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.api.exception;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Exception thrown when a transaction exceeds the configured timeout while waiting to acquire necessary
 * locks or complete internal processing. This prevents transactions from blocking indefinitely and ensures
 * system responsiveness under high contention.
 *
 * **Timeouts can occur at several stages:**
 *
 * - **Conflict resolution lock timeout** - waiting to acquire lock for resolving concurrent modifications
 * - **WAL appending lock timeout** - waiting to write transaction to the Write-Ahead Log
 * - **Catalog propagation lock timeout** - waiting to propagate changes to catalog metadata
 * - **Engine state lock timeout** - waiting to acquire lock for modifying engine state
 *
 * The timeout threshold is configured via `evitaDB.server.transactionTimeoutInMilliseconds` server setting.
 * When this exception occurs, the transaction is automatically rolled back. Clients should:
 *
 * 1. Retry the transaction if the timeout was caused by temporary high load
 * 2. Reduce transaction size if it's consistently timing out
 * 3. Consider increasing the timeout threshold if transactions are legitimately long-running
 *
 * This exception extends {@link TransactionException} and indicates a potentially recoverable condition,
 * unlike other transaction failures that suggest bugs or corruption.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class TransactionTimedOutException extends TransactionException {
	@Serial private static final long serialVersionUID = -2432902995243551105L;

	/**
	 * Creates a new exception with details about which lock or operation timed out.
	 *
	 * @param message description of what timed out and how long it waited
	 */
	public TransactionTimedOutException(@Nonnull String message) {
		super(message);
	}

}
