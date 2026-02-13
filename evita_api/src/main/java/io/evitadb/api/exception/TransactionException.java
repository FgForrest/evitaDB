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
 * Exception thrown when a transaction fails to complete properly due to an internal error during commit
 * or rollback processing. This is a server-side error indicating that the database encountered an
 * unexpected condition while finalizing a transaction.
 *
 * **Common scenarios include:**
 *
 * - Unexpected exception occurred during transaction commit processing
 * - Transaction was rolled back due to an internal error (not user-requested rollback)
 * - Termination callback execution failed after transaction completed
 * - Lock acquisition or release failed during transaction finalization
 *
 * This exception extends {@link EvitaInternalError} rather than {@link io.evitadb.exception.EvitaInvalidUsageException}
 * because it represents a server-side failure, not a client usage error. When this exception occurs,
 * the transaction state is indeterminate and clients should not retry the same transaction without
 * investigating the root cause.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class TransactionException extends EvitaInternalError {
	@Serial private static final long serialVersionUID = -4612052525218524619L;

	/**
	 * Creates a new exception with a single error message.
	 *
	 * @param message description of the transaction failure
	 */
	public TransactionException(@Nonnull String message) {
		super(message);
	}

	/**
	 * Creates a new exception with an error message and the underlying cause.
	 *
	 * @param message description of the transaction failure
	 * @param cause the underlying exception that caused the transaction to fail
	 */
	public TransactionException(@Nonnull String message, @Nonnull Throwable cause) {
		super(message, cause);
	}

	/**
	 * Creates a new exception with separate private and public messages.
	 *
	 * @param privateMessage detailed message for server-side logging
	 * @param publicMessage sanitized message suitable for client consumption
	 */
	public TransactionException(@Nonnull String privateMessage, @Nonnull String publicMessage) {
		super(privateMessage, publicMessage);
	}
}
