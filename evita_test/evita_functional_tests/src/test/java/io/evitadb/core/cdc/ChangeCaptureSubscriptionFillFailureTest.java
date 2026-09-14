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

import io.evitadb.api.requestResponse.cdc.ChangeCaptureContent;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.test.TestTags.CDC;
import static io.evitadb.test.TestTags.ENGINE;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that a failure raised while filling the capture queue reaches the subscriber.
 *
 * The queue filler reads the write-ahead log, so it can fail for reasons that have nothing to do with the
 * subscriber. {@code DefaultChangeCaptureSubscription#consumeQueue()} invokes it inside a block guarded only by
 * `finally { unlock }` - there is no `catch` - so such a failure used to escape the method altogether. Nothing
 * downstream recovered it: the subscription's `finished` flag stayed false and the subscriber received neither
 * `onError` nor `onComplete`, so it simply stopped receiving events for the lifetime of the process.
 *
 * `consumeQueue()` is reached from two entry points and both are covered here, because the failure looked
 * different on each. `request(n)` calls it synchronously on the caller's thread, so the throw propagated straight
 * out of {@link Subscription#request(long)} - which reactive-streams requires never to throw - into the gRPC
 * producer loop or into embedded application code. {@link DefaultChangeCaptureSubscription#notifySubscriber()}
 * submits it to an executor instead, where the same throw died inside the task with nothing to observe it at all.
 *
 * Reaching the subscriber is only half of what a terminal signal owes, and the release of the subscription's
 * registration with its publisher is the other half - the half nothing downstream can make up for, because the
 * signal that wins the race to terminate the subscription is the last code that ever runs for it. Both signals a
 * publisher can raise are covered here for that property: the `onError` the failed fill routes into, and the
 * `onComplete` of an orderly end.
 */
@DisplayName("Change capture subscription: a terminal signal must reach the subscriber and release the subscription")
@Tag(ENGINE)
@Tag(CDC)
class ChangeCaptureSubscriptionFillFailureTest {
	/**
	 * How long a test waits for the asynchronous entry point to report the failure. Deliberately generous: this
	 * is a positive wait, so a loaded machine can only push it towards expiry, and it returns the instant the
	 * subscriber is told.
	 */
	private static final long AWAIT_TIMEOUT_SECONDS = 30L;

	/**
	 * Creates the single-threaded executor the subscription submits its asynchronous queue consumption to. The
	 * thread is a daemon, so a fixture that fails before its `shutdownNow()` cannot keep the surefire fork alive.
	 *
	 * @return a fresh single-threaded executor backed by a daemon thread
	 */
	@Nonnull
	private static ExecutorService createDaemonExecutor() {
		return Executors.newSingleThreadExecutor(
			runnable -> {
				final Thread thread = new Thread(runnable, "cdc-fill-failure-test");
				thread.setDaemon(true);
				return thread;
			}
		);
	}

	@DisplayName("a throwing queue filler must terminate the subscription via onError instead of escaping request()")
	@Test
	void shouldRouteQueueFillFailureToOnErrorRatherThanEscaping() throws InterruptedException {
		final RuntimeException fillFailure = new IllegalStateException("WAL read failed while filling the queue");
		final RecordingSubscriber subscriber = new RecordingSubscriber();
		final UUID subscriptionId = UUID.randomUUID();
		final AtomicReference<UUID> cancellationReportedFor = new AtomicReference<>();
		final CountDownLatch cancellationLatch = new CountDownLatch(1);

		final ExecutorService executorService = createDaemonExecutor();
		try {
			final DefaultChangeCaptureSubscription<ChangeCatalogCapture> subscription =
				new DefaultChangeCaptureSubscription<>(
					subscriptionId,
					16,
					new WalPointerWithContent(1L, 0, ChangeCaptureContent.BODY),
					subscriber,
					executorService,
					(walPointer, theSubscription, queue) -> {
						throw fillFailure;
					},
					capture -> {
					},
					reportedSubscriptionId -> {
						cancellationReportedFor.set(reportedSubscriptionId);
						cancellationLatch.countDown();
					}
				);

			assertDoesNotThrow(
				() -> subscription.request(1),
				"A failure raised while filling the capture queue escaped Subscription#request(long). " +
					"Reactive-streams requires request() never to throw, and the caller here is the gRPC " +
					"producer loop or embedded application code, neither of which expects it."
			);

			assertSame(
				fillFailure,
				subscriber.getError(),
				"The subscriber was never told that filling its queue failed. Without an onError it is left " +
					"neither completed nor failed and simply stops receiving events for the rest of the " +
					"process lifetime, with nothing in the stream saying why."
			);
			assertFalse(
				subscriber.isCompleted(),
				"The subscriber was told onComplete as well as - or instead of - onError. A failed queue fill " +
					"is not an orderly end of the capture stream and must never be reported as one."
			);
			assertTrue(
				subscriber.getItems().isEmpty(),
				"A capture was delivered even though the fill that was supposed to produce it threw."
			);
			assertTrue(
				subscription.isFinished(),
				"The subscription is still live after its queue filler failed. `finished` is what the shared " +
					"publisher reaps on, so a subscription that stays false here is delivered to forever while " +
					"its subscriber has already been told the stream is over."
			);

			assertTrue(
				cancellationLatch.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
				"onError terminated the subscription without releasing its registration with the publisher. " +
					"Only the terminal signal that wins the `finished` race can perform that release - every " +
					"later cancel(), including the one the transport makes when it notices the stream is " +
					"dead, returns immediately - so the subscription stays in the subscribers map for the " +
					"lifetime of the process, pinning the WAL version it last read in the ring buffer and " +
					"keeping the shared publisher from ever being retired."
			);
			assertEquals(
				subscriptionId,
				cancellationReportedFor.get(),
				"The release was reported for a different subscription than the one whose queue fill failed."
			);
		} finally {
			executorService.shutdownNow();
		}
	}

	@DisplayName("an orderly completion must release the subscription registration just as a cancellation does")
	@Test
	void shouldReleaseTheSubscriptionRegistrationOnOrderlyCompletion() throws InterruptedException {
		final RecordingSubscriber subscriber = new RecordingSubscriber();
		final UUID subscriptionId = UUID.randomUUID();
		final AtomicReference<UUID> cancellationReportedFor = new AtomicReference<>();
		final CountDownLatch cancellationLatch = new CountDownLatch(1);

		final ExecutorService executorService = createDaemonExecutor();
		try {
			final DefaultChangeCaptureSubscription<ChangeCatalogCapture> subscription =
				new DefaultChangeCaptureSubscription<>(
					subscriptionId,
					16,
					new WalPointerWithContent(1L, 0, ChangeCaptureContent.BODY),
					subscriber,
					executorService,
					(walPointer, theSubscription, queue) -> {
					},
					capture -> {
					},
					reportedSubscriptionId -> {
						cancellationReportedFor.set(reportedSubscriptionId);
						cancellationLatch.countDown();
					}
				);

			subscription.onComplete();

			assertTrue(
				subscriber.isCompleted(),
				"The subscriber was never told that the capture stream ended in an orderly way."
			);
			assertTrue(
				subscription.isFinished(),
				"The subscription is still live after it reported an orderly completion."
			);
			assertTrue(
				cancellationLatch.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
				"An orderly completion terminated the subscription without releasing its registration with " +
					"the publisher. This is the normal way a capture stream ends, and it leaves exactly the " +
					"same residue as a terminating failure does: the entry stays in the subscribers map for " +
					"the lifetime of the process, pinning the WAL version the subscription last read."
			);
			assertEquals(
				subscriptionId,
				cancellationReportedFor.get(),
				"The release was reported for a different subscription than the one that completed."
			);
		} finally {
			executorService.shutdownNow();
		}
	}

	@DisplayName("a throwing queue filler must also reach the subscriber when the fill runs on the executor")
	@Test
	void shouldRouteQueueFillFailureToOnErrorFromTheAsynchronousEntryPoint() throws InterruptedException {
		final RuntimeException fillFailure = new IllegalStateException("WAL read failed while filling the queue");
		final RecordingSubscriber subscriber = new RecordingSubscriber();
		// the first fill - the synchronous one request(1) performs - must succeed and simply find nothing, so
		// that the subscription is still live and still has outstanding demand when notifySubscriber() runs
		final AtomicInteger fillAttempts = new AtomicInteger();

		final ExecutorService executorService = createDaemonExecutor();
		try {
			final DefaultChangeCaptureSubscription<ChangeCatalogCapture> subscription =
				new DefaultChangeCaptureSubscription<>(
					UUID.randomUUID(),
					16,
					new WalPointerWithContent(1L, 0, ChangeCaptureContent.BODY),
					subscriber,
					executorService,
					(walPointer, theSubscription, queue) -> {
						if (fillAttempts.incrementAndGet() > 1) {
							throw fillFailure;
						}
					},
					capture -> {
					},
					subscriptionId -> {
					}
				);

			subscription.request(1);
			assertNull(
				subscriber.getError(),
				"The first queue fill was supposed to find nothing and return quietly, leaving the demand " +
					"outstanding. It failed instead, so this test never reached the asynchronous entry point."
			);

			subscription.notifySubscriber();

			assertTrue(
				subscriber.awaitError(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
				"A queue fill that failed on the executor thread never reached the subscriber within " +
					AWAIT_TIMEOUT_SECONDS + " s. Nothing observes a task submitted by notifySubscriber(), so " +
					"an uncaught throw there leaves the subscriber waiting on a stream that will never " +
					"produce another event and will never say why."
			);
			assertSame(fillFailure, subscriber.getError(), "A different failure than the one raised was reported.");
			assertTrue(
				subscription.isFinished(),
				"The subscription is still live after its asynchronous queue fill failed."
			);
		} finally {
			executorService.shutdownNow();
		}
	}

	/**
	 * Records every terminal signal the subscription delivers.
	 *
	 * Deliberately not {@link MockCatalogChangeSubscriber}: that one calls `request(1)` from its own
	 * `onSubscribe`, which the subscription constructor invokes eagerly - the fill would then happen inside the
	 * constructor rather than inside the `request(long)` call these tests drive, which is the exact call whose
	 * no-throw contract is under test.
	 */
	private static class RecordingSubscriber implements Subscriber<ChangeCatalogCapture> {
		/**
		 * Captures received before the terminal signal, if any.
		 */
		private final List<ChangeCatalogCapture> items = new ArrayList<>(4);
		/**
		 * Counts down when `onError` is delivered, so the asynchronous entry point can be awaited.
		 */
		private final CountDownLatch errorLatch = new CountDownLatch(1);
		/**
		 * The error reported to this subscriber, or `null` when none was.
		 */
		@Nullable private volatile Throwable error;
		/**
		 * Whether `onComplete` was delivered.
		 */
		private volatile boolean completed;

		@Override
		public void onSubscribe(Subscription subscription) {
			// deliberately empty - the tests drive request(long) themselves
		}

		@Override
		public void onNext(ChangeCatalogCapture item) {
			this.items.add(item);
		}

		@Override
		public void onError(Throwable throwable) {
			this.error = throwable;
			this.errorLatch.countDown();
		}

		@Override
		public void onComplete() {
			this.completed = true;
		}

		/**
		 * Returns the captures delivered to this subscriber before its terminal signal.
		 *
		 * @return the received captures, in delivery order
		 */
		@Nonnull
		List<ChangeCatalogCapture> getItems() {
			return this.items;
		}

		/**
		 * Returns the error reported to this subscriber.
		 *
		 * @return the reported error, or `null` when none was reported
		 */
		@Nullable
		Throwable getError() {
			return this.error;
		}

		/**
		 * Returns whether the publisher signalled an orderly completion.
		 *
		 * @return true when `onComplete` was delivered
		 */
		boolean isCompleted() {
			return this.completed;
		}

		/**
		 * Waits until an error is reported to this subscriber.
		 *
		 * @param timeout maximum time to wait
		 * @param unit    unit of the timeout argument
		 * @return true when an error arrived before the timeout elapsed
		 * @throws InterruptedException when the waiting thread is interrupted
		 */
		boolean awaitError(long timeout, @Nonnull TimeUnit unit) throws InterruptedException {
			return this.errorLatch.await(timeout, unit);
		}
	}

}
