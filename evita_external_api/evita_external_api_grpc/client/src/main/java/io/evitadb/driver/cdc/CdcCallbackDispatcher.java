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
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Hands consumer-supplied change-data-capture callbacks to the shared client pool, and — when that pool refuses
 * the task — to a one-shot rescue thread rather than to the thread that tried to submit it.
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
 * Dropping the callback instead is not an option either: a terminal `onError`/`onComplete` that never arrives
 * leaves the consumer believing its subscription is still alive, which is a silent, permanent capture outage.
 * So the callback is moved off-thread even at the cost of an ad-hoc thread.
 *
 * Driver-internal cleanup is deliberately **not** routed through here — removing a subscription from the
 * publisher, cancelling the gRPC stream or flipping a `closed` flag is local, non-blocking and non-re-entrant,
 * so it is cheaper and more deterministic to run it in place. See
 * `ClientChangeCapturePublisher.ClientSubscription#cancel`.
 *
 * **What the rescue-thread bound actually is.** At most one rescue thread per subscription is ever *in flight*
 * for the capture drain (the `currentlyConsuming` CAS enforces that), plus one for a terminal notification and
 * one for the delegate close. It is **not** bounded over time: the drain task tail-calls `consume()`, so under
 * sustained saturation each drain cycle creates a fresh short-lived thread. That churn is the reason the
 * rejection is logged — a saturated client pool is a condition an operator has to fix, not one to ride out.
 *
 * Ordering note: callbacks dispatched **through this class directly** are not serialized with respect to one
 * another. Where ordering is required, the caller submits through {@link SerialCdcExecutor} instead, which
 * layers a queue and a single active drain on top of this dispatcher — that is how
 * {@link HeartBeatSensor} notifications keep their order, including when the pool is saturated and the drain
 * runs on a rescue thread. The driver's own heartbeat gap detection stays on the inbound thread and is
 * unaffected either way.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Slf4j
final class CdcCallbackDispatcher {
	/**
	 * Numbers the rescue threads so a thread dump taken during a pool saturation names them unambiguously.
	 */
	private static final AtomicLong RESCUE_THREAD_COUNTER = new AtomicLong();

	/**
	 * This class is a stateless utility holder and must never be instantiated.
	 */
	private CdcCallbackDispatcher() {
		// utility class
	}

	/**
	 * Submits `callback` to `executorService`, falling back to a fresh daemon thread if the pool refuses it.
	 * Never throws and never runs `callback` on the calling thread.
	 *
	 * @param executorService the shared client pool the callback is normally dispatched on
	 * @param callback        the consumer-facing callback to run
	 * @param description     short description of the callback, used in the diagnostic log messages
	 * @return true if the callback was accepted for execution, false if it could not be scheduled at all
	 *         (only reachable when the JVM cannot create a thread) and the caller must account for the work
	 *         never happening. Note that `true` means *accepted*, not *guaranteed to run*: a task sitting in
	 *         the pool's queue is still discarded by `ExecutorService#shutdownNow`, which
	 *         {@link io.evitadb.driver.EvitaClient#close()} calls after cancelling the publishers.
	 */
	static boolean dispatch(
		@Nonnull Executor executorService,
		@Nonnull Runnable callback,
		@Nonnull String description
	) {
		// consumer code must never take down a driver thread — contain it at the boundary, which also covers
		// the rescue thread, whose uncaught exceptions would otherwise bypass logging and land on stderr
		final Runnable guardedCallback = () -> {
			try {
				callback.run();
			} catch (Throwable ex) {
				log.error("Change data capture callback `{}` failed.", description, ex);
			}
		};
		try {
			executorService.execute(guardedCallback);
			return true;
		} catch (Throwable ex) {
			// the shared pool is saturated or shutting down — the callback still must not run here, because
			// "here" may be the event loop that has to stay free to read the connection
			if (executorService instanceof ExecutorService pool && pool.isShutdown()) {
				// routine during `EvitaClient#close`: the pool is stopped before the transport is torn down,
				// so every still-live capture stream reports its terminal notification through this path.
				// Telling an operator to widen a pool that is merely closing would be actively misleading.
				log.debug(
					"The evitaDB client is shutting down; the change data capture callback `{}` runs on " +
						"a one-shot rescue thread.",
					description
				);
			} else {
				log.warn(
					"The evitaDB client thread pool refused the change data capture callback `{}`; running it " +
						"on a one-shot rescue thread instead. This means the client pool is saturated - " +
						"consider widening it via `ThreadPoolOptions.clientThreadPoolBuilder()`.",
					description
				);
			}
			return runOnRescueThread(guardedCallback, description);
		}
	}

	/**
	 * Runs `callback` on a fresh daemon thread - the last resort once the shared pool has refused it.
	 *
	 * @param callback    the already-guarded callback to run
	 * @param description short description of the callback, used in the diagnostic log message
	 * @return true if the thread was started, false if the JVM could not create one at all
	 */
	private static boolean runOnRescueThread(@Nonnull Runnable callback, @Nonnull String description) {
		try {
			final Thread rescueThread = new Thread(
				callback,
				"evita-client-cdc-rescue-" + RESCUE_THREAD_COUNTER.incrementAndGet()
			);
			rescueThread.setDaemon(true);
			rescueThread.start();
			return true;
		} catch (Throwable fatal) {
			// the JVM could not create a thread at all — there is nowhere safe left to run the callback,
			// so report it loudly rather than silently swallowing it or running it on the caller
			log.error(
				"Failed to dispatch the change data capture callback `{}` - it will not be executed.",
				description, fatal
			);
			return false;
		}
	}

}
