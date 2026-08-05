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

import io.evitadb.driver.exception.EvitaClientPoolSaturatedException;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Runs submitted tasks on a delegate executor, one at a time and in submission order, without ever running
 * them on the submitting thread.
 *
 * This exists for callbacks that need **both** guarantees at once, which neither the capture callback executor
 * nor {@link CdcCallbackDispatcher} alone provides — the executor is multi-threaded and therefore reorders, and
 * running in place is exactly the event-loop capture this package exists to prevent.
 * {@link HeartBeatSensor} notifications are the case in point: a sensor detects missed heartbeats from the
 * continuity of {@link io.evitadb.externalApi.grpc.requestResponse.cdc.HeartBeat#index()}, so two reordered
 * notifications would manufacture a phantom gap, while running the sensor on the gRPC inbound thread would
 * let a sensor that re-establishes the stream deadlock the connection.
 *
 * The mechanism is the standard queue-plus-drain: tasks land in an unbounded queue and a single drain task
 * is handed to the delegate. The `draining` flag guarantees at most one drain is ever active, which is what
 * makes execution serial regardless of how many threads the delegate has.
 *
 * `execute` never throws: a caller on a gRPC inbound callback has no defined error path.
 *
 * **When the delegate refuses the drain.** There is no fallback thread and nothing runs on the caller: the
 * executor marks itself terminated, drops whatever it is holding, and reports the refusal to the
 * `onDispatchFailure` handler its owner supplied — which fails the owning subscription. Silently retrying
 * later would be worse than failing: heartbeats would resume with a gap the consumer would read as missed
 * server heartbeats, when in fact the driver dropped them.
 *
 * Being terminated is one-way. A subscription whose callbacks could not be delivered is finished, so later
 * submissions are discarded (with a debug line) rather than re-arming a drain nobody will observe.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Slf4j
@ThreadSafe
final class SerialCdcExecutor implements Executor {
	/**
	 * Executor the drain actually runs on - the capture callback executor.
	 */
	private final Executor delegate;
	/**
	 * Short description of what this executor carries, used in the dispatcher's diagnostic messages.
	 */
	private final String description;
	/**
	 * Invoked when the delegate refuses a drain, so the owner can terminate the affected subscription. Called
	 * at most once per instance, never on the submitting thread's behalf more than that.
	 */
	private final Consumer<Throwable> onDispatchFailure;
	/**
	 * Tasks awaiting execution, in submission order.
	 */
	private final Queue<Runnable> tasks = new ConcurrentLinkedQueue<>();
	/**
	 * True while a drain task is scheduled or running. The CAS on this flag is what makes execution serial.
	 */
	private final AtomicBoolean draining = new AtomicBoolean(false);
	/**
	 * Set once the delegate has refused a drain. Terminal - see the class contract.
	 */
	private final AtomicBoolean terminated = new AtomicBoolean(false);

	/**
	 * Creates a serializing view over `delegate`.
	 *
	 * @param delegate          executor the drain runs on
	 * @param description       short description used in diagnostic log messages
	 * @param onDispatchFailure invoked when `delegate` refuses a drain, so the owning subscription can be
	 *                          terminated; it must not block and must not re-enter this executor
	 */
	SerialCdcExecutor(
		@Nonnull Executor delegate,
		@Nonnull String description,
		@Nonnull Consumer<Throwable> onDispatchFailure
	) {
		this.delegate = delegate;
		this.description = description;
		this.onDispatchFailure = onDispatchFailure;
	}

	/**
	 * Enqueues `command` and makes sure a drain is scheduled. Never runs `command` on the calling thread and
	 * never throws.
	 *
	 * @param command the task to run
	 */
	@Override
	public void execute(@Nonnull Runnable command) {
		if (this.terminated.get()) {
			log.debug(
				"Change data capture callback `{}` discarded - its subscription was already terminated by " +
					"an earlier dispatch failure.",
				this.description
			);
			return;
		}
		this.tasks.add(command);
		scheduleDrain();
	}

	/**
	 * Hands a drain task to the delegate unless one is already scheduled or running.
	 */
	private void scheduleDrain() {
		if (!this.draining.compareAndSet(false, true)) {
			// somebody else owns the drain and will pick our task up
			return;
		}
		if (CdcCallbackDispatcher.dispatch(this.delegate, this::drain, this.description)) {
			return;
		}
		// Nothing will run the drain and nothing may run it here. Keep `draining` set: this instance is
		// finished, and leaving the flag raised makes every concurrent submitter take the same "somebody else
		// owns it" path rather than each re-attempting a dispatch that is going to fail identically.
		terminate();
	}

	/**
	 * Marks this executor finished, drops the undeliverable backlog and notifies the owner exactly once.
	 */
	private void terminate() {
		if (!this.terminated.compareAndSet(false, true)) {
			return;
		}
		final int discarded = this.tasks.size();
		this.tasks.clear();
		log.warn(
			"Change data capture callback `{}` could not be dispatched; {} pending callback(s) discarded and " +
				"the subscription is being terminated.",
			this.description, discarded
		);
		try {
			this.onDispatchFailure.accept(
				new EvitaClientPoolSaturatedException()
			);
		} catch (Throwable ex) {
			log.error("Failed to terminate the subscription owning the callback `{}`.", this.description, ex);
		}
	}

	/**
	 * Drains the queue on the delegate's thread. Each task is guarded individually so one failing callback
	 * cannot strand the tasks queued behind it.
	 */
	private void drain() {
		try {
			Runnable task;
			while ((task = this.tasks.poll()) != null) {
				try {
					task.run();
				} catch (Throwable ex) {
					log.error("Change data capture callback `{}` failed.", this.description, ex);
				}
			}
		} finally {
			this.draining.set(false);
			// a task enqueued while we were releasing the flag would otherwise wait for the next submission
			if (!this.tasks.isEmpty()) {
				scheduleDrain();
			}
		}
	}

}
