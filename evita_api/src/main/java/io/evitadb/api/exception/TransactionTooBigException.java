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
 * Exception thrown when a single transaction attempts to modify more data than the configured size limit
 * allows. Large transactions consume excessive memory, increase lock contention, and can destabilize the
 * database by creating long-lived memory allocations.
 *
 * **Size limits are measured by:**
 *
 * - Number of modified entities
 * - Total mutation count across all entities
 * - Memory footprint of accumulated changes before commit
 * - Size of the transaction's WAL (Write-Ahead Log) entry
 *
 * The maximum transaction size is controlled by server configuration. When this exception occurs, the
 * transaction is rolled back automatically. Clients should split large transactions into smaller batches:
 *
 * ```java
 * // Instead of:
 * session.upsertEntity(entity1);
 * session.upsertEntity(entity2);
 * // ... thousands of entities ...
 * session.commit();
 *
 * // Do:
 * for (List<Entity> batch : batches) {
 *     for (Entity e : batch) {
 *         session.upsertEntity(e);
 *     }
 *     session.commit(); // Commit each batch separately
 * }
 * ```
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class TransactionTooBigException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = 6870692024926622358L;

	/**
	 * Creates a new exception with separate private and public messages.
	 *
	 * @param privateMessage detailed message for server-side logging including size metrics
	 * @param publicMessage sanitized message suitable for client consumption
	 */
	public TransactionTooBigException(@Nonnull String privateMessage, @Nonnull String publicMessage) {
		super(privateMessage, publicMessage);
	}

}
