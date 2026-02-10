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

import io.evitadb.exception.EvitaInternalError;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Exception thrown when a transaction commit attempt fails and the transaction is automatically rolled
 * back due to an unexpected error. This indicates a server-side failure during the commit phase, distinct
 * from a client-requested rollback via {@link io.evitadb.api.TransactionContract#setRollbackOnly()}.
 *
 * **Common causes:**
 *
 * - Data integrity violation detected during commit validation
 * - I/O error while persisting transaction to storage
 * - Lock acquisition failure during commit processing
 * - Internal consistency check failure in transaction manager
 * - Out-of-memory condition during commit materialization
 *
 * This exception extends {@link EvitaInternalError} because it represents an abnormal server condition,
 * not a usage error. When this occurs, all transaction changes are discarded and the session remains
 * open but without an active transaction.
 *
 * **Client handling:**
 *
 * - Inspect the error message and cause exception for details about the failure
 * - Log the full stack trace for investigation
 * - Do not retry automatically - the underlying cause needs investigation
 * - Consider whether the transaction violated database constraints or invariants
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class UnexpectedRollbackException extends EvitaInternalError {
	@Serial private static final long serialVersionUID = -3250029332684512530L;

	/**
	 * Creates a new exception with an error message describing why the rollback occurred.
	 *
	 * @param message description of what went wrong during commit
	 */
	public UnexpectedRollbackException(@Nonnull String message) {
		super(message);
	}

	/**
	 * Creates a new exception with an error message and the underlying cause of the rollback.
	 *
	 * @param message description of what went wrong during commit
	 * @param cause the underlying exception that triggered the rollback
	 */
	public UnexpectedRollbackException(@Nonnull String message, @Nonnull Throwable cause) {
		super(message, cause);
	}

}
