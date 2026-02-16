/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.exception.EvitaInvalidUsageException;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Exception thrown when attempting to modify evitaDB data while operating in read-only mode.
 *
 * evitaDB supports read-only operation at three distinct levels, each enforced by throwing this exception
 * when write operations are attempted:
 *
 * 1. **Engine Level**: The entire evitaDB instance is started with {@link ServerOptions#readOnly()} enabled,
 *    preventing all write operations across all catalogs and sessions.
 *
 * 2. **Catalog Level**: A specific catalog is marked as read-only, preventing modifications to that catalog
 *    while other catalogs remain writable.
 *
 * 3. **Session Level**: A session is opened with read-only mode, preventing write operations within that
 *    session even if the underlying catalog supports writes.
 *
 * Read-only mode is useful for:
 * - Operating on backup/replica instances without risk of accidental modification
 * - Enforcing least-privilege access for read-only clients
 * - Safely inspecting production data without write permissions
 * - Running queries against archived or historical data
 *
 * **Usage Context:**
 * - {@link io.evitadb.core.Evita}: enforces engine-level read-only restrictions
 * - {@link io.evitadb.core.session.EvitaSession}: enforces session-level and catalog-level restrictions
 * - Thrown before any mutation operations when the applicable read-only flag is set
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class ReadOnlyException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = 8880217332792347590L;

	/**
	 * Creates a new exception indicating that the entire evitaDB engine is in read-only mode.
	 *
	 * This is thrown when {@link ServerOptions#readOnly()} is enabled and any write operation is attempted
	 * at the engine level.
	 *
	 * @return a new exception with a message about the engine's read-only state
	 */
	@Nonnull
	public static ReadOnlyException engineReadOnly() {
		return new ReadOnlyException("The evitaDB engine is started in read-only mode. No updates are allowed!");
	}

	/**
	 * Creates a new exception indicating that a specific catalog is in read-only mode.
	 *
	 * This is thrown when a catalog is marked as read-only and a write operation is attempted on that catalog.
	 *
	 * @param catalogName the name of the read-only catalog
	 * @return a new exception indicating the catalog is read-only
	 */
	@Nonnull
	public static ReadOnlyException catalogReadOnly(@Nonnull String catalogName) {
		return new ReadOnlyException("The evitaDB catalog `" + catalogName + "` is read-only. No updates are allowed!");
	}

	/**
	 * Creates a new exception indicating that the current session is read-only.
	 *
	 * This is thrown when a session is opened in read-only mode and a write operation is attempted within
	 * that session, even if the underlying catalog supports writes.
	 *
	 * @return a new exception indicating the session is in read-only mode
	 */
	@Nonnull
	public static ReadOnlyException sessionReadOnly() {
		return new ReadOnlyException("The session is read-only. No updates are allowed!");
	}

	/**
	 * Creates a new read-only exception with a custom message.
	 *
	 * @param publicMessage the error message to display
	 */
	public ReadOnlyException(@Nonnull String publicMessage) {
		super(publicMessage);
	}
}
