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
 * Exception is thrown when the global {@link ServerOptions#readOnly()} flag is enabled and the client attempts
 * to update evitaDB data.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class ReadOnlyException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = 8880217332792347590L;

	/**
	 * Creates a new {@link ReadOnlyException} with a message indicating that the evitaDB engine is in read-only mode
	 * and updates are not allowed.
	 *
	 * @return a new {@link ReadOnlyException} with a preconfigured message about the engine's read-only state.
	 */
	@Nonnull
	public static ReadOnlyException engineReadOnly() {
		return new ReadOnlyException("The evitaDB engine is started in read-only mode. No updates are allowed!");
	}

	/**
	 * Creates a new {@link ReadOnlyException} indicating that the specified evitaDB catalog is in read-only mode
	 * and does not allow updates.
	 *
	 * @param catalogName the name of the catalog that is read-only
	 * @return a new {@link ReadOnlyException} instance with a message indicating the catalog is read-only
	 */
	@Nonnull
	public static ReadOnlyException catalogReadOnly(@Nonnull String catalogName) {
		return new ReadOnlyException("The evitaDB catalog `" + catalogName + "` is read-only. No updates are allowed!");
	}

	/**
	 * Creates a new {@link ReadOnlyException} with a message indicating that the session is read-only
	 * and no updates are allowed.
	 *
	 * @return a new {@link ReadOnlyException} instance indicating the session is in read-only mode.
	 */
	@Nonnull
	public static ReadOnlyException sessionReadOnly() {
		return new ReadOnlyException("The session is read-only. No updates are allowed!");
	}

	public ReadOnlyException(@Nonnull String publicMessage) {
		super(publicMessage);
	}
}
