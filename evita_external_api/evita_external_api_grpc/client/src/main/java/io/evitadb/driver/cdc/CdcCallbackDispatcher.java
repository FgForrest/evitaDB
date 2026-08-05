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

package io.evitadb.driver.cdc;

import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;

/**
 * Hands consumer-supplied change-data-capture callbacks to the capture callback executor, and reports whether
 * that executor accepted them.
 *
 * This exists to protect a single invariant:
 *
 * **Consumer callbacks must never run on the thread that submitted them.** That covers every downstream
 * notification: `Flow.Subscriber#onNext`, `#onError`, `#onComplete`, {@link HeartBeatSensor#onHeartBeat} and
 * a closeable delegate's `close`.
 *
 * The submitting thread is frequently an Armeria event loop — every inbound gRPC frame is delivered on one, and
 * a client normally has a single event loop per endpoint. Consumer callbacks routinely re-enter the driver
 * (a subscriber whose `onError` handler re-subscribes is the ordinary pattern, and a
 * {@link HeartBeatSensor} that re-establishes a stale stream is the very purpose of that SPI), and
 * {@link ClientChangeCapturePublisher#subscribe} blocks in
 * {@link ClientChangeCaptureSubscriber#awaitAcknowledgement()} until the server acknowledges — on an inbound
 * frame that only the event loop can deliver. Running a consumer callback on the event loop therefore risks
 * parking the one thread that could complete the wait, killing the whole HTTP/2 connection. That is exactly the
 * failure `ThreadPoolExecutor.CallerRunsPolicy` used to produce, and simply catching the rejection and running
 * the task in place would reproduce it verbatim.
 *
 * **What happens when the executor refuses.** The callback is *not* run, anywhere, and `dispatch` returns
 * false; the caller then terminates the affected subscription with
 * {@link io.evitadb.driver.exception.EvitaClientPoolSaturatedException}. A capture stream that cannot deliver
 * to its consumer is not a capture stream, so failing it loudly is the honest outcome — and the driver-internal
 * half of that teardown (cancelling the gRPC stream, de-registering from the publisher) is safe to run in place
 * precisely because it is local, non-blocking and non-re-entrant.
 *
 * An earlier revision instead moved refused callbacks onto a fresh one-shot thread, on the grounds that
 * a terminal `onError`/`onComplete` which never arrives leaves the consumer believing its subscription is
 * alive. That reasoning is sound, but the remedy was not: nothing bounded those threads, and because the
 * capture drain re-submits itself, sustained saturation meant unbounded thread creation on an already
 * struggling JVM — trading a capture outage for a process-wide one. The bound now comes from the right place:
 * captures own a **separate** executor from the shared client pool (see `EvitaClient#cdcCallbackExecutor()`),
 * so ordinary query load can no longer refuse a capture callback at all, and a refusal genuinely means the
 * consumer's own callbacks are not keeping up.
 *
 * Driver-internal cleanup is deliberately **not** routed through here — removing a subscription from the
 * publisher, cancelling the gRPC stream or flipping a `closed` flag is local, non-blocking and non-re-entrant,
 * so it is cheaper and more deterministic to run it in place. See
 * `ClientChangeCapturePublisher.ClientSubscription#cancel`.
 *
 * Ordering note: callbacks dispatched **through this class directly** are not serialized with respect to one
 * another. Where ordering is required, the caller submits through {@link SerialCdcExecutor} instead, which
 * layers a queue and a single active drain on top of this dispatcher — that is how
 * {@link HeartBeatSensor} notifications keep their order. The driver's own heartbeat gap detection stays on the
 * inbound thread and is unaffected either way.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Slf4j
final class CdcCallbackDispatcher {

	/**
	 * This class is a stateless utility holder and must never be instantiated.
	 */
	private CdcCallbackDispatcher() {
		// utility class
	}

	/**
	 * Submits `callback` to `executorService`. Never throws, and never runs `callback` on the calling thread —
	 * not even when the submission is refused.
	 *
	 * @param executorService the capture callback executor the callback is dispatched on
	 * @param callback        the consumer-facing callback to run
	 * @param description     short description of the callback, used in the diagnostic log messages
	 * @return NULL if the callback was accepted for execution, otherwise **the refusal the executor threw** — in
	 *         which case the callback will never run and **the caller must terminate the affected subscription**
	 *         with the returned cause, because a consumer that is not notified is left believing a dead
	 *         subscription is alive. The cause is returned rather than reduced to a flag because the two
	 *         refusals a driver executor produces are operationally opposite: an
	 *         {@link io.evitadb.driver.exception.EvitaClientPoolSaturatedException} raised by
	 *         `EvitaClientRejectingExecutorHandler` under saturation names the
	 *         `maxThreadCount`/`queueSize` knobs that widen the pool, while the shutdown variant carries no
	 *         knobs and must not send anyone looking for them. Synthesizing an exception here would pick one of
	 *         those messages for the consumer at random.
	 *
	 *         Note that NULL means *accepted*, not *guaranteed to run*: a task sitting in the executor's queue is
	 *         still discarded by `ExecutorService#shutdownNow`. `EvitaClient#close()` therefore drains this
	 *         executor before tearing it down, so that close-time notifications are delivered rather than thrown
	 *         away.
	 */
	@Nullable
	static Throwable dispatch(
		@Nonnull Executor executorService,
		@Nonnull Runnable callback,
		@Nonnull String description
	) {
		// consumer code must never take down a driver thread — contain it at the boundary
		final Runnable guardedCallback = () -> {
			try {
				callback.run();
			} catch (Throwable ex) {
				log.error("Change data capture callback `{}` failed.", description, ex);
			}
		};
		try {
			executorService.execute(guardedCallback);
			return null;
		} catch (Throwable ex) {
			// the callback still must not run *here*, because "here" may be the event loop that has to stay
			// free to read the connection — so it does not run at all, and the caller fails the subscription
			if (executorService instanceof ExecutorService pool && pool.isShutdown()) {
				// routine during `EvitaClient#close`: whatever could not be drained in time is reported once,
				// at debug level. Telling an operator to widen an executor that is merely closing would be
				// actively misleading.
				log.debug(
					"The evitaDB client is shutting down; the change data capture callback `{}` will not run.",
					description
				);
			} else {
				log.warn(
					"The evitaDB change data capture callback executor refused the callback `{}`; the affected " +
						"capture subscription will be terminated. Consumer callbacks are not keeping up - widen " +
						"the executor via `ThreadPoolOptions.clientThreadPoolBuilder()` (`maxThreadCount`, " +
						"`queueSize`) or make the callbacks faster.",
					description
				);
			}
			return ex;
		}
	}

}
