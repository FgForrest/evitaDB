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
 * Exception thrown when internal identifier sequences exceed their safe upper bounds, preventing the database from
 * accepting further modifications.
 *
 * evitaDB uses monotonically increasing counters for various internal identifiers (e.g., transactional object version
 * sequences). These counters are implemented using atomic primitives with fixed size limits. When a counter reaches
 * its maximum safe value (typically near `Long.MAX_VALUE`), this exception is thrown to prevent overflow-related
 * data corruption.
 *
 * **When this exception occurs:**
 *
 * - **Transactional object version overflow**: The system has processed an extremely large number of transactional
 *   operations, exhausting the version sequence counter
 * - **After exceptionally long uptime**: The database has been running and processing modifications for an extended
 *   period without restart
 * - **High-write-volume scenarios**: Systems with very high transaction rates may eventually exhaust sequence space
 *
 * **Why this is critical:**
 *
 * Allowing identifier overflow would cause severe data consistency issues:
 * - Version numbers would wrap around, breaking transactional isolation guarantees
 * - Ordering assumptions would be violated, corrupting temporal data structures
 * - MVCC (Multi-Version Concurrency Control) mechanisms would produce incorrect results
 *
 * **Resolution:**
 *
 * **Restart the database** to reset internal counters and continue operations. The restart will:
 * - Clear transactional state and reset version sequences
 * - Preserve all persisted data
 * - Allow the system to continue accepting modifications safely
 *
 * **Note**: This exception is extremely rare in practice and typically indicates either an exceptionally long-running
 * instance or a test scenario that artificially increments counters at an unrealistic rate.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class IdentifierOverflowException extends EvitaInternalError {
	@Serial private static final long serialVersionUID = -9111617180591842555L;

	/**
	 * Creates exception with detailed internal context about which counter overflowed.
	 *
	 * @param privateMessage internal diagnostic message identifying the specific counter that overflowed
	 */
	public IdentifierOverflowException(@Nonnull String privateMessage) {
		super(
			privateMessage,
			"Internal counters exceeded safe limits, please restart the database to continue."
		);
	}
}
