/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
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

import java.io.Serial;

/**
 * Exception is thrown when the global {@link ServerOptions#readOnly()} flag is enabled and the client attempts
 * to update evitaDB data.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class ReadOnlyException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = 8880217332792347590L;

	public ReadOnlyException() {
		super("The evitaDB server is started in read-only mode. No updates are allowed!");
	}

}
