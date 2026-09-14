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
import io.evitadb.core.executor.EvitaRejectingExecutorHandler;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.test.TestTags.CDC;
import static io.evitadb.test.TestTags.ENGINE;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

	@DisplayName("a capture queue that is genuinely full must raise the rejection the release path has to survive")
	@Test
	void shouldRefuseSubmissionWhenTheBoundedCaptureQueueIsFull() throws InterruptedException {
		try (SaturatedCaptureExecutor saturated = new SaturatedCaptureExecutor()) {
			assertThrows(
				RejectedExecutionException.class,
				() -> saturated.executor().submit(() -> {
				}),
				"A saturated capture executor accepted another task. The whole reason a terminated " +
					"subscription cannot rely on handing its release to that executor is that " +
					"EvitaRejectingExecutorHandler raises RejectedExecutionException on a full bounded queue " +
					"and not only at shutdown - if that premise no longer holds, the sweep below is guarding " +
					"a condition that cannot arise and the deferral could be trusted on its own again."
			);
		}
	}

	@DisplayName("a release the capture executor refuses must still happen, through the publisher's sweep")
	@Test
	void shouldReleaseATerminatedSubscriptionFromTheSweepWhenTheExecutorRefusedIt() throws InterruptedException {
		final RecordingSubscriber subscriber = new RecordingSubscriber();
		final UUID subscriptionId = UUID.randomUUID();
		final List<UUID> releasedFor = Collections.synchronizedList(new ArrayList<>());

		try (SaturatedCaptureExecutor saturated = new SaturatedCaptureExecutor()) {
			final DefaultChangeCaptureSubscription<ChangeCatalogCapture> subscription =
				newSubscription(subscriptionId, subscriber, saturated.executor(), releasedFor);

			// a non-positive request is a protocol violation the subscription reports through onError - the
			// shortest public route to a terminal signal that defers its release to the capture executor
			subscription.request(-1);

			assertTrue(
				subscription.isFinished(),
				"A non-positive request left the subscription live. It is a protocol violation and must " +
					"terminate the subscription rather than be ignored."
			);
			assertNotNull(
				subscriber.getError(),
				"The subscriber was never told its subscription had been terminated by the protocol violation."
			);
			assertTrue(
				releasedFor.isEmpty(),
				"The registration was released even though the capture executor refused the deferred task. " +
					"If this ever passes, the test is no longer reproducing the state the sweep exists for."
			);

			// this is what CatalogChangeObserver#cleanInactivePublishers drives, one publisher at a time
			assertTrue(
				subscription.releaseIfTerminated(),
				"The sweep did not recognise a terminated subscription. `finished` is the only thing that " +
					"distinguishes one, and it was set above."
			);
			assertEquals(
				List.of(subscriptionId),
				releasedFor,
				"The sweep did not release the registration the refused task was supposed to release. Left " +
					"held, it keeps the entry in the publisher's subscribers map for the lifetime of the " +
					"process: the ring buffer can never be trimmed past the version that entry still tracks " +
					"and the shared publisher can never be retired."
			);

			// the sweep runs every minute and the refused task may yet arrive; neither may release twice
			assertTrue(subscription.releaseIfTerminated(), "A terminated subscription stopped reporting itself as terminated.");
			assertEquals(
				List.of(subscriptionId),
				releasedFor,
				"The registration was released more than once. A second release would unsubscribe an id the " +
					"publisher may since have reused and close the subscriber's transport again."
			);
		}
	}

	@DisplayName("the sweep must leave a subscription that is still live exactly as it found it")
	@Test
	void shouldLeaveALiveSubscriptionUntouchedBySweep() throws InterruptedException {
		final RecordingSubscriber subscriber = new RecordingSubscriber();
		final List<UUID> releasedFor = Collections.synchronizedList(new ArrayList<>());

		try (SaturatedCaptureExecutor saturated = new SaturatedCaptureExecutor()) {
			final DefaultChangeCaptureSubscription<ChangeCatalogCapture> subscription =
				newSubscription(UUID.randomUUID(), subscriber, saturated.executor(), releasedFor);

			assertFalse(
				subscription.releaseIfTerminated(),
				"The sweep claimed a live subscription had terminated. It runs over every entry in the " +
					"publisher's map on a timer, so treating a live one as terminated would silently " +
					"unsubscribe a healthy subscriber."
			);
			assertTrue(
				releasedFor.isEmpty(),
				"The sweep released a live subscription's registration, cutting off a subscriber that was " +
					"still being delivered to."
			);
			assertFalse(subscription.isFinished(), "The sweep terminated a live subscription.");
			assertNull(subscriber.getError(), "The sweep reported an error to a live subscriber.");
			assertFalse(subscriber.isCompleted(), "The sweep completed a live subscriber's stream.");
		}
	}

	/**
	 * Builds a subscription that does nothing on its own, for tests that drive its accounting directly.
	 *
	 * @param executorService the executor the subscription would submit asynchronous work to
	 * @param version         the version the subscription is registered at
	 * @return a subscription with an inert queue filler and no deregistration hook
	 */
	@Nonnull
	private static DefaultChangeCaptureSubscription<ChangeCatalogCapture> createInertSubscription(
		@Nonnull ExecutorService executorService,
		long version
	) {
		return new DefaultChangeCaptureSubscription<>(
			UUID.randomUUID(),
			16,
			new WalPointerWithContent(version, 0, ChangeCaptureContent.BODY),
			new RecordingSubscriber(),
			executorService,
			(walPointer, theSubscription, queue) -> {
			},
			capture -> {
			},
			subscriptionId -> {
			}
		);
	}

	/**
	 * A subscription owns exactly one unit of the publisher's version accounting, and only one code path may give
	 * it back.
	 *
	 * `unsubscribe` reclaims that unit the moment it wins the removal from the subscribers map. The queue fill that
	 * moves the unit forward runs on the delivery thread and reads the write-ahead log, so it can still be in
	 * flight by then - and a move landing afterwards decrements a version this subscription no longer holds, which
	 * takes the slot of a subscriber still sitting on it. The publisher trims its ring buffer to the lowest key of
	 * that map, so the survivor is trimmed past captures it has not read, and where write-ahead-log retention has
	 * already reclaimed that segment it stalls instead of falling back to disk.
	 *
	 * Both halves take this subscription's lock, so under concurrency one of the two orderings below is what
	 * actually happens. This pins the decision each ordering has to reach - which is the whole of the guarantee;
	 * the lock only decides which of them a given race gets.
	 */
	@DisplayName("a version slot must not move after the subscription has given it back, in either ordering")
	@Test
	void shouldGiveBackTheVersionSlotExactlyOnce() {
		final long registeredAt = 100L;
		final long advancedTo = 140L;
		final ExecutorService executorService = createDaemonExecutor();
		try {
			// ordering A - the release wins the lock first, and a fill still in flight must not move the slot
			final AtomicBoolean movedAfterRelease = new AtomicBoolean(false);
			final AtomicLong releaseReportedVersion = new AtomicLong(-1L);
			final DefaultChangeCaptureSubscription<ChangeCatalogCapture> releasedFirst =
				createInertSubscription(executorService, registeredAt);

			releasedFirst.releaseAccounting(releaseReportedVersion::set);
			releasedFirst.setTrackedVersion(advancedTo, (from, to) -> movedAfterRelease.set(true));

			assertEquals(
				registeredAt, releaseReportedVersion.get(),
				"The release gave back a version this subscription never held."
			);
			assertFalse(
				movedAfterRelease.get(),
				"A fill still in flight moved the version slot after `unsubscribe` had already reclaimed it. The " +
					"decrement lands on a version this subscription no longer holds, so it takes the slot of a " +
					"subscriber still sitting there - and the lowest key of that map is the only thing stopping the " +
					"ring buffer being trimmed past captures the survivor has not read."
			);

			// ordering B - the fill wins, so the release has to give back the version the fill moved to
			final AtomicBoolean moved = new AtomicBoolean(false);
			final AtomicLong releaseAfterMoveReportedVersion = new AtomicLong(-1L);
			final DefaultChangeCaptureSubscription<ChangeCatalogCapture> movedFirst =
				createInertSubscription(executorService, registeredAt);

			movedFirst.setTrackedVersion(advancedTo, (from, to) -> moved.set(true));
			movedFirst.releaseAccounting(releaseAfterMoveReportedVersion::set);

			assertTrue(
				moved.get(),
				"A live subscription refused to move its version slot forward, so the publisher can never learn " +
					"that this subscriber has advanced and the ring buffer stays anchored where it registered."
			);
			assertEquals(
				advancedTo, releaseAfterMoveReportedVersion.get(),
				"The release gave back the version the subscription registered at rather than the one it had " +
					"advanced to, so the slot it actually held is left pinned for the lifetime of the process."
			);

			// a second release must do nothing - the sweep and an explicit cancel race in exactly this shape
			final AtomicInteger secondReleaseCount = new AtomicInteger();
			movedFirst.releaseAccounting(version -> secondReleaseCount.incrementAndGet());

			assertEquals(
				0, secondReleaseCount.get(),
				"The accounting was given back twice for one subscription. The publisher's periodic sweep and an " +
					"explicit cancel both reach this, so a second decrement drops a version slot that belongs to a " +
					"different subscriber."
			);
		} finally {
			executorService.shutdownNow();
		}
	}

	@DisplayName("a deregistration that throws must still close the subscriber's transport")
	@Test
	void shouldCloseTheSubscriberEvenWhenDeregistrationThrows() throws InterruptedException {
		final RuntimeException deregistrationFailure = new IllegalStateException("publisher refused the removal");
		final AtomicBoolean subscriberClosed = new AtomicBoolean(false);
		final RecordingSubscriber subscriber = new ClosingRecordingSubscriber(subscriberClosed);

		try (SaturatedCaptureExecutor saturated = new SaturatedCaptureExecutor()) {
			final DefaultChangeCaptureSubscription<ChangeCatalogCapture> subscription =
				new DefaultChangeCaptureSubscription<>(
					UUID.randomUUID(),
					16,
					new WalPointerWithContent(1L, 0, ChangeCaptureContent.BODY),
					subscriber,
					saturated.executor(),
					(walPointer, theSubscription, queue) -> {
					},
					capture -> {
					},
					subscriptionId -> {
						throw deregistrationFailure;
					}
				);

			assertThrows(
				IllegalStateException.class,
				subscription::cancel,
				"The deregistration failure was swallowed. It has to reach the caller - the sweep logs it per " +
					"entry and carries on - because a release that silently did nothing is the leak this whole " +
					"mechanism exists to prevent."
			);

			assertTrue(
				subscriberClosed.get(),
				"The subscriber's transport was left open because deregistering it threw first. The release " +
					"flips its `released` CAS before doing either half, so `cancel()`, the deferred task and " +
					"the periodic sweep all short-circuit afterwards - nothing can ever retry the close, and " +
					"the transport stays open for the lifetime of the process."
			);
		}
	}

	/**
	 * Builds a subscription whose queue filler does nothing, for the tests that are about the release rather than
	 * about the fill.
	 *
	 * @param subscriptionId  the id the release is expected to be reported for
	 * @param subscriber      the subscriber to attach
	 * @param executorService the capture executor the subscription defers its release to
	 * @param releasedFor     collects every id the subscription releases, in order, so a double release is visible
	 * @return the subscription under test
	 */
	@Nonnull
	private static DefaultChangeCaptureSubscription<ChangeCatalogCapture> newSubscription(
		@Nonnull UUID subscriptionId,
		@Nonnull RecordingSubscriber subscriber,
		@Nonnull ExecutorService executorService,
		@Nonnull List<UUID> releasedFor
	) {
		return new DefaultChangeCaptureSubscription<>(
			subscriptionId,
			16,
			new WalPointerWithContent(1L, 0, ChangeCaptureContent.BODY),
			subscriber,
			executorService,
			(walPointer, theSubscription, queue) -> {
			},
			capture -> {
			},
			releasedFor::add
		);
	}

	/**
	 * A capture executor in the state that makes the deferred release fail: one worker, occupied, and its single
	 * queue slot taken, behind the production {@link EvitaRejectingExecutorHandler}.
	 *
	 * The handler is the real one on purpose. The behaviour under test is not "an executor that throws" - it is
	 * that evitaDB's own rejection policy raises {@link RejectedExecutionException} on a full queue and not only
	 * after shutdown, which is what the release path used to assume. A hand-written throwing executor would
	 * assert that assumption rather than measure it.
	 */
	private static final class SaturatedCaptureExecutor implements AutoCloseable {
		private final ThreadPoolExecutor executor;
		private final CountDownLatch releaseWorker = new CountDownLatch(1);

		SaturatedCaptureExecutor() throws InterruptedException {
			this.executor = new ThreadPoolExecutor(
				1, 1, 0L, TimeUnit.MILLISECONDS,
				new LinkedBlockingQueue<>(1),
				runnable -> {
					final Thread thread = new Thread(runnable, "cdc-saturated-test");
					thread.setDaemon(true);
					return thread;
				},
				new EvitaRejectingExecutorHandler("cdc-test", () -> {
				})
			);
			// occupy the single worker, and wait until it is genuinely running - submitting the queue filler
			// below before that would put both tasks in the queue and reject the second one during setup
			final CountDownLatch workerStarted = new CountDownLatch(1);
			this.executor.execute(
				() -> {
					workerStarted.countDown();
					try {
						this.releaseWorker.await();
					} catch (InterruptedException ex) {
						Thread.currentThread().interrupt();
					}
				}
			);
			assertTrue(
				workerStarted.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
				"The capture executor never started its only worker, so the fixture is not saturated."
			);
			// take the one free queue slot behind the occupied worker
			this.executor.execute(() -> {
			});
		}

		@Nonnull
		ExecutorService executor() {
			return this.executor;
		}

		@Override
		public void close() {
			this.releaseWorker.countDown();
			this.executor.shutdownNow();
		}
	}

	/**
	 * A {@link RecordingSubscriber} that also owns a closeable resource, standing in for a subscriber holding a
	 * transport stream - which is what {@code releaseRegistration} closes alongside deregistering.
	 */
	private static class ClosingRecordingSubscriber extends RecordingSubscriber implements AutoCloseable {
		private final AtomicBoolean closed;

		ClosingRecordingSubscriber(@Nonnull AtomicBoolean closed) {
			this.closed = closed;
		}

		@Override
		public void close() {
			this.closed.set(true);
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
