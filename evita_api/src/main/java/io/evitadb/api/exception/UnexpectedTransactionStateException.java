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

import io.evitadb.exception.EvitaInvalidUsageException;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Exception thrown when a transaction operation is attempted but the current transaction state does not
 * permit the operation. This represents a programming error where the client makes incorrect assumptions
 * about transaction lifecycle.
 *
 * **Common violations:**
 *
 * - Calling {@link io.evitadb.api.TransactionContract#setRollbackOnly()} when no transaction is active
 * - Attempting to commit or rollback when no transaction has been opened
 * - Trying to open a new transaction when one is already active in the session
 * - Calling transaction-specific methods on a session after the transaction has ended
 *
 * This exception typically indicates a logical bug in client code where transaction boundaries are not
 * properly managed. Clients should ensure they:
 *
 * ```java
 * // Correct pattern:
 * session.openTransaction();
 * try {
 *     // ... perform mutations ...
 *     session.setRollbackOnly(); // Only valid inside transaction
 * } finally {
 *     session.closeTransaction();
 * }
 *
 * // Or use the convenience method:
 * session.execute((theSession) -> {
 *     // Transaction is automatically managed
 *     theSession.upsertEntity(...);
 * });
 * ```
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class UnexpectedTransactionStateException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = -475125333242552407L;

	/**
	 * Creates a new exception with an error message describing the invalid transaction state.
	 *
	 * @param message description of what transaction state was expected vs. actual
	 */
	public UnexpectedTransactionStateException(@Nonnull String message) {
		super(message);
	}

}
