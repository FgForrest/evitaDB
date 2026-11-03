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

package io.evitadb.core.cdc;

import io.evitadb.api.requestResponse.cdc.ChangeCapture;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * Subscriber recording every signal it receives, safe to read from a thread other than the one delivering them.
 *
 * Captures of a catalog that is being written to are delivered on whichever thread publishes the new catalog
 * version, which need not be the test thread. Every state change therefore wakes up {@link #awaitUntil}, which
 * waits - bounded - for a condition over the received captures or for a terminal signal, whichever comes first.
 * A terminal signal ends the wait at once, so a test that expects a capture but receives an error fails on the
 * error immediately instead of on the timeout.
 *
 * @param <T> type of the change capture
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
final class AwaitableCaptureSubscriber<T extends ChangeCapture> implements Subscriber<T> {
	/**
	 * Number of captures requested when the subscription starts.
	 */
	private final long initialDemand;
	/**
	 * Captures received so far, in delivery order. Guarded by this instance's monitor.
	 */
	private final List<T> items = new ArrayList<>(64);
	/**
	 * The error the publisher terminated the stream with, if any. Guarded by this instance's monitor.
	 */
	@Nullable private Throwable error;
	/**
	 * Whether the publisher completed the stream. Guarded by this instance's monitor.
	 */
	private boolean completed;

	/**
	 * Creates a subscriber that requests the given number of captures once subscribed and nothing more.
	 *
	 * @param initialDemand the number of captures to request, `Long.MAX_VALUE` for an unbounded demand
	 */
	AwaitableCaptureSubscriber(long initialDemand) {
		this.initialDemand = initialDemand;
	}

	/**
	 * Creates a subscriber that requests every capture the publisher has.
	 *
	 * @param <T> type of the change capture
	 * @return a subscriber with an unbounded demand
	 */
	@Nonnull
	static <T extends ChangeCapture> AwaitableCaptureSubscriber<T> unbounded() {
		return new AwaitableCaptureSubscriber<>(Long.MAX_VALUE);
	}

	@Override
	public void onSubscribe(@Nonnull Subscription subscription) {
		subscription.request(this.initialDemand);
	}

	@Override
	public synchronized void onNext(@Nonnull T item) {
		this.items.add(item);
		notifyAll();
	}

	@Override
	public synchronized void onError(@Nonnull Throwable throwable) {
		this.error = throwable;
		notifyAll();
	}

	@Override
	public synchronized void onComplete() {
		this.completed = true;
		notifyAll();
	}

	/**
	 * Returns a copy of the captures received so far.
	 *
	 * @return the received captures in delivery order
	 */
	@Nonnull
	synchronized List<T> getItems() {
		return new ArrayList<>(this.items);
	}

	/**
	 * Returns the error the stream was terminated with.
	 *
	 * @return the error, or `null` when the stream has not failed
	 */
	@Nullable
	synchronized Throwable getError() {
		return this.error;
	}

	/**
	 * Returns whether the publisher completed the stream.
	 *
	 * @return true when `onComplete` was received
	 */
	synchronized boolean isCompleted() {
		return this.completed;
	}

	/**
	 * Waits until the received captures satisfy the condition, the stream terminates, or the timeout elapses.
	 *
	 * @param condition condition over the captures received so far
	 * @param timeout   the longest time to wait
	 * @param unit      unit of `timeout`
	 * @return true when the condition holds on return
	 * @throws InterruptedException when the waiting thread is interrupted
	 */
	synchronized boolean awaitUntil(
		@Nonnull Predicate<List<T>> condition,
		long timeout,
		@Nonnull TimeUnit unit
	) throws InterruptedException {
		final long deadline = System.nanoTime() + unit.toNanos(timeout);
		while (!condition.test(this.items) && this.error == null && !this.completed) {
			final long remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
			if (remainingMillis <= 0L) {
				break;
			}
			wait(remainingMillis);
		}
		return condition.test(this.items);
	}

}
