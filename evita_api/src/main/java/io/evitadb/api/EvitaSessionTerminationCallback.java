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

package io.evitadb.api;

import javax.annotation.Nonnull;

/**
 * Functional interface for session termination callbacks in evitaDB.
 *
 * **Purpose and Usage**
 *
 * This callback interface allows clients to register cleanup logic that executes when an {@link EvitaSessionContract}
 * is being closed. It provides a hook for releasing session-associated resources, performing final logging, or
 * triggering post-session actions.
 *
 * **When to Use**
 *
 * Use this callback when you need to:
 * - Release external resources tied to a session lifecycle (connections, file handles, temporary data)
 * - Log session duration or activity metrics
 * - Trigger cleanup in external systems when a session ends
 * - Perform custom finalization logic that depends on session state
 *
 * **Registration**
 *
 * Callbacks are registered when creating a session via {@link SessionTraits}:
 *
 * ```
 * evita.createSession(new SessionTraits(
 * "catalogName",
 * session -> {
 * // cleanup logic here
 * },
 * SessionFlags.READ_WRITE
 * ));
 * ```
 *
 * **Execution Guarantees**
 *
 * - Called exactly once per session, during {@link EvitaSessionContract#close()} or its variants
 * - Invoked after all session operations are complete but before session resources are fully released
 * - **Must not throw exceptions** - implementations should catch and log all errors internally
 * - No guarantee on execution thread or timing relative to commit completion
 *
 * **Thread-Safety**
 *
 * Implementations must be thread-safe if shared across multiple sessions, though each session invokes its callback
 * exactly once from a single thread.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@FunctionalInterface
public interface EvitaSessionTerminationCallback {

	/**
	 * Invoked when the evitaDB session is being terminated.
	 *
	 * This method is called during session closure, after all session operations are complete. Implementations should:
	 * - Perform cleanup quickly to avoid blocking session termination
	 * - Never throw exceptions (catch and log errors internally)
	 * - Avoid accessing session data, as the session is in the process of being closed
	 *
	 * @param session the session being terminated (partially closed state)
	 */
	void onTermination(@Nonnull EvitaSessionContract session);

}
