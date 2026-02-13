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
 * Exception thrown when a transaction commit operation fails and the transaction is rolled back
 * instead of being committed.
 *
 * This exception signals that changes made within a transaction were not persisted to the
 * database and have been discarded. It typically wraps an underlying cause that prevented the
 * commit from succeeding, such as:
 *
 * - Conflict with concurrent transactions (optimistic locking failure)
 * - Constraint violations discovered during commit validation
 * - I/O errors during write-ahead log (WAL) persistence
 * - Schema validation failures
 * - Transaction timeout or session closure
 *
 * When this exception is thrown, the transaction is automatically rolled back, discarding all
 * mutations. The client may choose to retry the transaction, inspect the cause for specific
 * error handling, or propagate the failure.
 *
 * This exception is raised by {@link io.evitadb.api.EvitaSessionContract} implementations during
 * the commit phase of transactional operations. It extends {@link EvitaInvalidUsageException}
 * because transaction rollback is often triggered by invalid client operations (e.g., constraint
 * violations) rather than internal system errors.
 *
 * **Thread-Safety**: This exception may be thrown from transaction finalizers running on
 * background threads during asynchronous commit processing.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class RollbackException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = -3250029332684512530L;

	/**
	 * Constructs a new rollback exception with a descriptive message.
	 *
	 * @param message explanation of why the transaction was rolled back
	 */
	public RollbackException(@Nonnull String message) {
		super(message);
	}

	/**
	 * Constructs a new rollback exception with a descriptive message and the underlying cause
	 * that triggered the rollback.
	 *
	 * @param message explanation of why the transaction was rolled back
	 * @param cause   the underlying exception that caused the commit to fail and rollback to occur
	 */
	public RollbackException(@Nonnull String message, @Nonnull Throwable cause) {
		super(message, cause);
	}

}
