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

import io.evitadb.api.CatalogState;
import io.evitadb.exception.EvitaInvalidUsageException;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.util.UUID;

/**
 * Exception thrown when attempting to create a second session on a catalog that is in
 * `{@link CatalogState#WARMING_UP}` state.
 *
 * During warm-up, evitaDB allows only a single session for efficient bulk data loading.
 * This session operates without full transactional overhead, enabling fast initial data
 * population. Once the catalog transitions to `{@link CatalogState#ALIVE}` state (via
 * `goLive()`), multiple concurrent sessions become available with full ACID transaction
 * support.
 *
 * **Typical Causes:**
 * - Calling `createSession()` or `createReadOnlySession()` multiple times without closing
 *   the first session
 * - Calling `update()` method on Evita while a session is still open
 * - Forgetting to use try-with-resources for automatic session cleanup
 *
 * **Resolution:**
 * - Close the existing session before creating a new one, or use try-with-resources
 * - If you need concurrent access, call `goLive()` on the active session first to
 *   transition to ALIVE state
 * - Consider whether you actually need multiple sessions, or if reusing the existing one
 *   is sufficient
 *
 * **Design Note:**
 * The warm-up phase is optimized for bulk loading scenarios where transactional safety
 * can be traded for speed. Once data loading is complete, transitioning to ALIVE state
 * enables full concurrent access with ACID guarantees.
 *
 * @author Stɇvɇn Kamenik (kamenik.stepan@gmail.cz) (c) 2021
 **/
public class ConcurrentInitializationException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = -9062588323022507459L;

	/**
	 * Creates a new exception indicating that only one session is allowed in warm-up state.
	 *
	 * @param activeSessionId UUID of the existing active session that blocks creation of
	 *                        a new session
	 */
	public ConcurrentInitializationException(@Nonnull UUID activeSessionId) {
		super(
			"Cannot create more than single session in \"warming up\" state! " +
				"You need to close existing active session `" + activeSessionId + "` first. " +
				"This problem usually occurs when you open the session by `createSession` on Evita instance " +
				"multiple times, or when you create session and subsequently call `update` method on Evita instance " +
				"without closing the existing session first. Parallel sessions are allowed in \"alive\" state which " +
				"applies mutations in separate ACID transaction (considerably slower). You may switch from warming up " +
				"to alive state by calling `goLive` on session contract."
		);
	}

}
