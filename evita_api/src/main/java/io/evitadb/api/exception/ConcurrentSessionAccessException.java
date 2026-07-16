/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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
import java.util.UUID;

/**
 * Exception thrown when a single evitaDB session is invoked concurrently from more than one thread.
 *
 * `EvitaSession` is `@NotThreadSafe` by contract — a session maintains mutable, unsynchronized
 * state (open transaction, warm-up index trees, buffers) that a second thread entering while the
 * first is still inside would corrupt silently. Rather than counting concurrent invocations and
 * letting the data race proceed, evitaDB rejects the second thread's call immediately and loudly.
 * This matches the defensive-design rule: an unexpected state must surface at runtime, never be
 * silently absorbed.
 *
 * **Typical Causes:**
 * - Sharing one session instance across multiple threads (e.g. issuing upserts from a thread pool
 *   over a single warm-up session during a bulk reindex)
 * - A driver- or application-level retry of a timed-out call that runs concurrently with the
 *   still executing original call on the same session
 *
 * **Resolution:**
 * - Use a separate session per thread — sessions are cheap to open and close
 * - Serialize access to a shared session so that at most one call is in flight at any moment
 * - Ensure a timed-out call has fully finished (or the session has been closed) before retrying it
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class ConcurrentSessionAccessException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = -3502787329552563863L;

	/**
	 * Creates a new exception indicating that a session was accessed concurrently from two threads.
	 *
	 * @param sessionId           the id of the session that was accessed concurrently
	 * @param owningThreadName    the name of the thread that currently owns (is executing on) the
	 *                            session
	 * @param intrudingThreadName the name of the thread whose concurrent call was rejected
	 */
	public ConcurrentSessionAccessException(
		@Nonnull UUID sessionId,
		@Nonnull String owningThreadName,
		@Nonnull String intrudingThreadName
	) {
		super(
			"Session `" + sessionId + "` is being used concurrently by more than one thread: " +
				"thread `" + intrudingThreadName + "` attempted a call while thread `" +
				owningThreadName + "` is still executing on it. An evitaDB session is not " +
				"thread-safe - use a separate session per thread, or serialize access so that at " +
				"most one call is in flight at any moment. If this is a retry of a timed-out call, " +
				"make sure the original call has finished (or close the session) before retrying."
		);
	}

}
