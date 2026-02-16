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
 * Exception thrown when attempting to open a transaction in a session or catalog that does not support
 * transactional operations. Transactions are only available under specific conditions in evitaDB.
 *
 * **This exception occurs when:**
 *
 * - Attempting to open a transaction in a **read-only session** - only read-write sessions created via
 *   {@link io.evitadb.api.EvitaContract#createReadWriteSession(String)} support transactions
 * - Attempting to open a transaction in a catalog that is still in **WARMING_UP** state - the catalog
 *   must first be brought to ALIVE state via `goLiveAndClose()` method
 * - The session was created with flags that explicitly disable transaction support
 *
 * Clients should verify the session type and catalog state before attempting transactional operations.
 * For read-only workloads, use {@link io.evitadb.api.EvitaContract#createReadOnlySession(String)} instead,
 * which provides snapshot isolation without transaction overhead.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class TransactionNotSupportedException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = 6758037223763511863L;

	/**
	 * Creates a new exception with the given error message explaining why transactions are not supported.
	 *
	 * @param message description of why the transaction could not be opened
	 */
	public TransactionNotSupportedException(@Nonnull String message) {
		super(message);
	}

}
