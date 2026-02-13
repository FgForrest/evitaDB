/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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
 * Exception thrown when a client attempts to access an evitaDB session that cannot be found by its
 * unique identifier. This can occur in several scenarios:
 *
 * - The session was never created with the specified ID
 * - The session was explicitly closed by the client using {@link io.evitadb.api.EvitaSessionContract#close()}
 * - The session was killed by the server due to inactivity timeout
 * - The session ID was mistyped or corrupted during transmission
 *
 * This exception typically indicates a client-side error, such as attempting to reuse a session after
 * it has been closed, or using an invalid session identifier. Clients should create a new session rather
 * than attempting to recover from this exception.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class SessionNotFoundException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = -5737363693173850419L;

	/**
	 * Creates a new exception with the given error message describing which session was not found.
	 */
	public SessionNotFoundException(@Nonnull String publicMessage) {
		super(publicMessage);
	}

}
