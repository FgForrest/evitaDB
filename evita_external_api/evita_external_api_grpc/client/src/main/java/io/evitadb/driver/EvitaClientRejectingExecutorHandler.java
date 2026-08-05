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

package io.evitadb.driver;

import io.evitadb.driver.exception.EvitaClientPoolSaturatedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Rejection handler installed on the shared {@link EvitaClient} thread pool. It fails the submission fast with
 * an {@link EvitaClientPoolSaturatedException} instead of running the rejected task on the submitting thread.
 *
 * `ThreadPoolExecutor.CallerRunsPolicy` — the previous policy — cannot implement backpressure safely in a client
 * library, because the library does not control who submits. Armeria delivers every inbound gRPC frame on an event
 * loop thread, and a client normally has exactly one event loop per endpoint, so "run it on the caller" can hand
 * arbitrary driver work to the single thread that also reads the connection. If that work then blocks waiting for
 * an inbound message (as change-data-capture teardown does), the thread that would have delivered the message is
 * the thread that is waiting for it, and the whole HTTP/2 connection dies rather than merely slowing down.
 *
 * The bounded backlog of the pool already provides the backpressure; this handler is what its far end looks like.
 * Callers that submit cleanup work must therefore be prepared for the submission to fail — see
 * `ClientChangeCapturePublisher.ClientSubscription#cancel` and `ClientChangeCaptureSubscriber#close`.
 *
 * Mirrors the server-side `EvitaRejectingExecutorHandler`, but throws a driver exception rather than a raw
 * {@link java.util.concurrent.RejectedExecutionException} — see {@link EvitaClientPoolSaturatedException} for why
 * that distinction matters to consumers.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Slf4j
@RequiredArgsConstructor
class EvitaClientRejectingExecutorHandler implements RejectedExecutionHandler {
	/**
	 * Configured maximum thread count of the guarded pool, reported in the exception message so an operator
	 * learns which knob to turn without reading the source.
	 */
	private final int maxThreadCount;
	/**
	 * Configured backlog capacity of the guarded pool, reported in the exception message alongside
	 * {@link #maxThreadCount}.
	 */
	private final int queueSize;

	/**
	 * Invoked by the {@link ThreadPoolExecutor} when a task cannot be accepted.
	 *
	 * @param command  the task that was refused
	 * @param executor the executor that refused it
	 * @throws EvitaClientPoolSaturatedException always - the point of the handler is to fail the submission
	 */
	@Override
	public void rejectedExecution(@Nonnull Runnable command, @Nonnull ThreadPoolExecutor executor) {
		if (executor.isShutdown()) {
			// a task submitted while the client is closing is an expected, benign race (a late gRPC callback
			// racing `EvitaClient#close`) — it still must fail so the submitter completes the cleanup itself
			// instead of silently dropping it the way `CallerRunsPolicy` did
			log.debug("The evitaDB client thread pool refused a task because the client is shutting down.");
			throw new EvitaClientPoolSaturatedException();
		}
		log.error(
			"The evitaDB client thread pool is saturated - all {} threads are busy and the backlog of {} tasks " +
				"is full. Widen the pool via `ThreadPoolOptions.clientThreadPoolBuilder()` (`maxThreadCount`, " +
				"`queueSize`) or reduce the client's concurrency.",
			this.maxThreadCount, this.queueSize
		);
		throw new EvitaClientPoolSaturatedException(this.maxThreadCount, this.queueSize);
	}

}
