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

package io.evitadb.driver.exception;

import io.evitadb.exception.EvitaInvalidUsageException;

import java.io.Serial;
import java.util.concurrent.RejectedExecutionException;

/**
 * Exception is thrown when a task cannot be handed over to one of the evitaDB client's thread pools — either
 * because the pool is saturated (all threads busy **and** the bounded backlog full) or because the client is
 * shutting down. Two pools raise it: the shared pool serving ordinary calls, and the separate executor carrying
 * change data capture callbacks. Both are sized from the same `ThreadPoolOptions`, so the remedy below applies
 * to either — but only the saturation message names it, which is why the two constructors must not be used
 * interchangeably (see each one's contract).
 *
 * The client pool deliberately fails fast instead of applying `ThreadPoolExecutor.CallerRunsPolicy`. A client
 * library does not control who submits: when the submitting thread is an Armeria event loop, "run it on the caller"
 * turns an asynchronous hand-off into work executed on the I/O thread, which can then park waiting for a message
 * only that very thread could have read — a permanently dead HTTP/2 connection rather than a slowdown. The bounded
 * backlog is the backpressure mechanism; this exception is what reaching the end of it looks like.
 *
 * Deliberately **not** a subclass of {@link RejectedExecutionException}: consumers commonly catch that around
 * submissions to their *own* schedulers to detect shutdown, and inheriting from it would make driver-side pool
 * saturation indistinguishable from a consumer's scheduler stopping — the real condition would be logged as
 * a benign shutdown and swallowed. It extends {@link EvitaInvalidUsageException} instead, consistent with the rest
 * of the client exception family ({@link EvitaClientTimedOutException}, {@link EvitaClientServerCallException}).
 *
 * Both remedies are operator-configurable through `ThreadPoolOptions.clientThreadPoolBuilder()` and are named in
 * the exception message so they can be acted on without reading the source.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class EvitaClientPoolSaturatedException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = -4021395103648254765L;

	/**
	 * Creates an exception describing a saturated pool, naming the two knobs that widen it.
	 *
	 * @param maxThreadCount currently configured maximum thread count of the client pool
	 * @param queueSize      currently configured backlog capacity of the client pool
	 */
	public EvitaClientPoolSaturatedException(int maxThreadCount, int queueSize) {
		super(
			"The evitaDB client thread pool is saturated - all " + maxThreadCount + " threads are busy and " +
				"the backlog of " + queueSize + " tasks is full. Either slow the client down, or widen the pool " +
				"via `ThreadPoolOptions.clientThreadPoolBuilder()` (`maxThreadCount`, currently " + maxThreadCount +
				"; `queueSize`, currently " + queueSize + ")."
		);
	}

	/**
	 * Creates an exception describing a submission that arrived after the client pool was shut down. Kept on the
	 * same type as the saturation case because callers react to both identically - the task will not run
	 * asynchronously and any cleanup it carried has to be completed by the caller.
	 *
	 * **Use this only when the pool really is shutting down.** It is deliberately the *only* variant that names
	 * no remedy, because a closing pool has none. Reaching for it as a generic "submission refused" stand-in -
	 * for instance when re-raising a refusal whose original cause was discarded - tells an operator whose pool
	 * is merely overloaded to go looking for a shutdown that never happened, and hides the
	 * `maxThreadCount`/`queueSize` knobs that would have fixed it. Propagate the refusal the pool threw instead
	 * of re-creating one; `CdcCallbackDispatcher#dispatch` returns it for exactly this reason.
	 */
	public EvitaClientPoolSaturatedException() {
		super(
			"The evitaDB client thread pool no longer accepts tasks because the client is shutting down."
		);
	}

}
