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

import io.evitadb.api.CatalogState;
import io.evitadb.api.configuration.ChangeDataCaptureOptions;
import io.evitadb.api.exception.InstanceTerminatedException;
import io.evitadb.api.requestResponse.cdc.ChangeCapture;
import io.evitadb.api.requestResponse.cdc.ChangeCaptureContent;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCaptureRequest;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCapture;
import io.evitadb.api.requestResponse.cdc.HostSystemEvent;
import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.engine.ExpandedEngineState;
import io.evitadb.core.executor.ImmediateExecutorService;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.exception.EvitaInvalidUsageException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import static io.evitadb.test.TestTags.CDC;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.utils.ReflectionUtils.getFieldValue;
import static io.evitadb.test.utils.ReflectionUtils.getNonnullFieldValue;
import static io.evitadb.test.utils.ReflectionUtils.setFieldValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Exercises the shared CDC publishers' registration, retirement and activation concurrency boundaries.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("CDC shared publishers should")
@Tag(ENGINE)
@Tag(CDC)
class SharedPublisherConcurrencyTest {
	private static final long AWAIT_TIMEOUT_SECONDS = 30L;

	/**
	 * Verifies that a delayed close callback from a retired catalog publisher cannot erase the publisher that
	 * replaced it under the same criteria key.
	 */
	@Test
	@DisplayName("keep a live replacement mapped when its predecessor finishes closing")
	void shouldKeepReplacementPublisherMappedWhenRetiredPublisherFinishesClosing() throws Exception {
		final Catalog catalog = createCatalog(10L);
		final CatalogChangeObserver observer = new CatalogChangeObserver(
			ChangeDataCaptureOptions.builder().build(),
			new ImmediateExecutorService(),
			mock(Scheduler.class),
			catalog
		);
		final PausingPublisherMap publisherMap = new PausingPublisherMap();
		setFieldValue(observer, "uniquePublishers", publisherMap);

		final ChangeCatalogCaptureRequest request =
			new ChangeCatalogCaptureRequest(null, null, null, ChangeCaptureContent.BODY);
		final ChangeCatalogCapturePublisher firstFacade =
			(ChangeCatalogCapturePublisher) observer.registerObserver(request);
		firstFacade.subscribe(new SilentSubscriber<>());
		final ChangeCatalogCaptureSharedPublisher retiringPublisher = firstFacade.getSharedPublisher();

		final ExecutorService closeExecutor = createDaemonExecutor("cdc-retiring-publisher");
		try {
			final Future<?> closeFuture = closeExecutor.submit(retiringPublisher::close);
			assertTrue(
				publisherMap.awaitRemoval(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
				"The retired publisher never reached its delayed observer removal callback."
			);

			final ChangeCatalogCapturePublisher secondFacade =
				(ChangeCatalogCapturePublisher) observer.registerObserver(request);
			secondFacade.subscribe(new SilentSubscriber<>());
			final ChangeCatalogCaptureSharedPublisher replacementPublisher = secondFacade.getSharedPublisher();
			assertSame(
				replacementPublisher,
				publisherMap.get(ChangeCatalogCriteriaBundle.CATCH_ALL),
				"The replacement was not installed before the predecessor resumed its close callback."
			);

			publisherMap.allowRemoval();
			closeFuture.get(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);

			assertSame(
				replacementPublisher,
				publisherMap.get(ChangeCatalogCriteriaBundle.CATCH_ALL),
				"The predecessor's delayed callback removed the live replacement mapped under the same criteria."
			);
			assertEquals(1, replacementPublisher.getSubscribersCount());
		} finally {
			publisherMap.allowRemoval();
			closeExecutor.shutdownNow();
			observer.close();
		}
	}

	/**
	 * Verifies that the public catalog-publisher overload has released every reentrant hold of its lock before
	 * invoking subscriber code.
	 */
	@Test
	@DisplayName("invoke catalog onSubscribe without holding the publisher lock")
	void shouldInvokeCatalogOnSubscribeWithoutPublisherLock() {
		final ChangeCatalogCaptureSharedPublisher publisher = new ChangeCatalogCaptureSharedPublisher(
			createCatalog(20L),
			new ImmediateExecutorService(),
			16,
			16,
			ChangeCatalogCriteriaBundle.CATCH_ALL,
			capture -> {
			},
			closingPublisher -> {
			}
		);
		final ReentrantLock publisherLock = getNonnullFieldValue(publisher, "lock");
		final LockObservingSubscriber<ChangeCatalogCapture> subscriber =
			new LockObservingSubscriber<>(publisherLock);

		publisher.subscribe(subscriber);

		assertEquals(
			0,
			subscriber.getPublisherLockHoldCount(),
			"Subscriber#onSubscribe ran while the catalog publisher lock still had a reentrant hold."
		);
		subscriber.cancel();
	}

	/**
	 * Verifies that the public system-publisher overload has released every reentrant hold of its lock before
	 * invoking subscriber code.
	 */
	@Test
	@DisplayName("invoke system onSubscribe without holding the publisher lock")
	void shouldInvokeSystemOnSubscribeWithoutPublisherLock() {
		final ChangeSystemCaptureSharedPublisher publisher = createSystemPublisher(30L, 16);
		final ReentrantLock publisherLock = getNonnullFieldValue(publisher, "lock");
		final LockObservingSubscriber<ChangeSystemCapture> subscriber =
			new LockObservingSubscriber<>(publisherLock);

		publisher.subscribe(subscriber);

		assertEquals(
			0,
			subscriber.getPublisherLockHoldCount(),
			"Subscriber#onSubscribe ran while the system publisher lock still had a reentrant hold."
		);
		subscriber.cancel();
		publisher.close();
	}

	/**
	 * Verifies that a catalog subscription whose queue cannot be constructed leaves no orphaned version slot.
	 */
	@Test
	@DisplayName("leave catalog bookkeeping untouched when subscription construction fails")
	void shouldLeaveCatalogBookkeepingUntouchedWhenSubscriptionConstructionFails() {
		final ChangeCatalogCaptureSharedPublisher publisher = new ChangeCatalogCaptureSharedPublisher(
			createCatalog(40L),
			new ImmediateExecutorService(),
			16,
			0,
			ChangeCatalogCriteriaBundle.CATCH_ALL,
			capture -> {
			},
			closingPublisher -> {
			}
		);
		final ConcurrentSkipListMap<Long, Integer> versionSubscribersCount =
			getNonnullFieldValue(publisher, "versionSubscribersCount");

		assertThrows(
			IllegalArgumentException.class,
			() -> publisher.subscribe(
				new SilentSubscriber<>(),
				new WalPointerWithContent(41L, 0, ChangeCaptureContent.BODY)
			)
		);

		assertEquals(0, publisher.getSubscribersCount());
		assertTrue(
			versionSubscribersCount.isEmpty(),
			"A failed catalog-subscription constructor left an unreachable version-count entry behind."
		);
	}

	/**
	 * Verifies that a system subscription whose queue cannot be constructed leaves no orphaned version slot or
	 * per-subscriber filters in the process-lifetime singleton.
	 */
	@Test
	@DisplayName("leave system bookkeeping untouched when subscription construction fails")
	void shouldLeaveSystemBookkeepingUntouchedWhenSubscriptionConstructionFails() {
		final ChangeSystemCaptureSharedPublisher publisher = createSystemPublisher(50L, 0);
		final ConcurrentSkipListMap<Long, Integer> versionSubscribersCount =
			getNonnullFieldValue(publisher, "versionSubscribersCount");
		final Map<?, ?> hostEventFilters = getNonnullFieldValue(publisher, "hostEventFilters");
		final Map<?, ?> mutationFilters = getNonnullFieldValue(publisher, "mutationFilters");

		assertThrows(
			IllegalArgumentException.class,
			() -> publisher.subscribe(
				new SilentSubscriber<>(),
				new WalPointerWithContent(51L, 0, ChangeCaptureContent.BODY),
				event -> true,
				capture -> true
			)
		);

		assertEquals(0, publisher.getSubscribersCount());
		assertTrue(
			versionSubscribersCount.isEmpty(),
			"A failed system-subscription constructor left an unreachable version-count entry behind."
		);
		assertTrue(hostEventFilters.isEmpty(), "A failed constructor left an unreachable host-event filter behind.");
		assertTrue(mutationFilters.isEmpty(), "A failed constructor left an unreachable mutation filter behind.");
	}

	/**
	 * Verifies that the configuration feeding both catalog and system subscriber queues rejects non-positive
	 * capacities before either publisher can begin registration.
	 */
	@Test
	@DisplayName("reject non-positive subscriber buffer sizes during CDC configuration construction")
	void shouldRejectNonPositiveSubscriberBufferSize() {
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> ChangeDataCaptureOptions.builder().subscriberBufferSize(0).build()
		);
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> ChangeDataCaptureOptions.builder().subscriberBufferSize(-1).build()
		);
	}

	/**
	 * Verifies that an {@link InstanceTerminatedException} thrown by subscriber code is propagated as that
	 * subscriber's failure and is not interpreted as a shared-publisher registration refusal.
	 */
	@Test
	@DisplayName("not retry when the subscriber's own onSubscribe throws InstanceTerminatedException")
	void shouldNotRetryWhenSubscriberOnSubscribeThrowsInstanceTerminatedException() {
		final Catalog catalog = createCatalog(60L);
		final AtomicInteger publisherCreations = new AtomicInteger();
		final ChangeCatalogCapturePublisher facade = new ChangeCatalogCapturePublisher(
			criteria -> {
				publisherCreations.incrementAndGet();
				return new ChangeCatalogCaptureSharedPublisher(
					catalog,
					new ImmediateExecutorService(),
					16,
					16,
					criteria,
					capture -> {
					},
					closingPublisher -> {
					}
				);
			},
			new ChangeCatalogCaptureRequest(null, null, null, ChangeCaptureContent.BODY)
		);
		final InstanceTerminatedException subscriberFailure =
			new InstanceTerminatedException("subscriber callback");
		final ThrowingInstanceTerminatedSubscriber subscriber =
			new ThrowingInstanceTerminatedSubscriber(subscriberFailure);

		final InstanceTerminatedException propagated = assertThrows(
			InstanceTerminatedException.class,
			() -> facade.subscribe(subscriber)
		);

		assertSame(subscriberFailure, propagated);
		assertEquals(
			1,
			subscriber.getOnSubscribeCalls(),
			"The same subscriber was handed a second subscription after its own onSubscribe failed."
		);
		assertEquals(
			1,
			publisherCreations.get(),
			"The subscriber failure was mistaken for a refusal and caused a replacement publisher to be created."
		);
	}

	/**
	 * Forces a host-event delivery thread to own the subscription delivery lock before demand is raised from inside
	 * {@code onSubscribe}, and verifies that delivery returns without waiting for that callback. Once activation is
	 * released, an earlier queued capture must be delivered before the pending host capture.
	 */
	@Test
	@DisplayName("delay a demanded host event until onSubscribe returns")
	void shouldDelayHostEventUntilOnSubscribeReturns() throws Exception {
		final long engineVersion = 70L;
		final ChangeSystemCaptureSharedPublisher publisher = createSystemPublisher(engineVersion, 16);
		final BlockingOnSubscribeSystemSubscriber subscriber = new BlockingOnSubscribeSystemSubscriber();
		final ExecutorService raceExecutor = createDaemonExecutor("cdc-activation-race");
		final Future<DefaultChangeCaptureSubscription<ChangeSystemCapture>> subscribeFuture = raceExecutor.submit(
			() -> publisher.subscribe(
				subscriber,
				new WalPointerWithContent(engineVersion + 1L, 0, ChangeCaptureContent.BODY),
				event -> true,
				capture -> true
			)
		);
		try {
			assertTrue(
				subscriber.awaitSubscription(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
				"The subscriber never entered onSubscribe."
			);
			final DefaultChangeCaptureSubscription<ChangeSystemCapture> subscription = subscriber.getSubscription();
			final ReentrantLock deliveryLock = getNonnullFieldValue(subscription, "lock");
			final Queue<ChangeSystemCapture> deliveryQueue = getNonnullFieldValue(subscription, "queue");
			final ChangeSystemCapture queuedCapture = createHostCapture("queuedBeforeActivation", engineVersion);
			assertTrue(deliveryQueue.offer(queuedCapture));

			final Future<?> hostDeliveryFuture = raceExecutor.submit(
				() -> {
					deliveryLock.lock();
					try {
						subscriber.signalHostOwnsDeliveryLock();
						subscriber.awaitDemandUnchecked();
						subscriber.signalHostDeliveryAttempt();
						publisher.processHostEvent(
							new HostSystemEvent.CatalogInstalledIntoLiveView(
								"activationGate", CatalogState.ALIVE, engineVersion
							)
						);
					} finally {
						deliveryLock.unlock();
					}
				}
			);
			assertTrue(
				subscriber.awaitHostDeliveryAttempt(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
				"The host-event thread never reached delivery after demand was raised."
			);
			hostDeliveryFuture.get(1L, TimeUnit.SECONDS);
			assertFalse(
				subscriber.awaitOnNext(1L, TimeUnit.SECONDS),
				"A capture reached onNext while the subscriber's own onSubscribe callback was still running."
			);

			subscriber.allowOnSubscribeToReturn();
			assertTrue(
				subscriber.awaitBothCaptures(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
				"Demand raised inside onSubscribe was lost instead of being served after activation."
			);
			assertTrue(subscriber.wasOnSubscribeReturnedBeforeOnNext());
			assertEquals(2, subscriber.getCaptures().size());
			assertSame(
				queuedCapture,
				subscriber.getCaptures().get(0),
				"The pending host capture overtook a capture already queued before activation."
			);
			assertEquals(
				"activationGate",
				((HostSystemEvent.CatalogInstalledIntoLiveView) subscriber.getCaptures().get(1).body()).catalogName()
			);
			subscribeFuture.get(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
			hostDeliveryFuture.get(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		} finally {
			subscriber.allowOnSubscribeToReturn();
			subscriber.cancel();
			publisher.close();
			raceExecutor.shutdownNow();
		}
	}

	/**
	 * Verifies that cancellation before activation discards a host capture already retained in the activation slot.
	 */
	@Test
	@DisplayName("discard a pending host event when cancelled before activation")
	void shouldDiscardPendingHostEventWhenCancelledBeforeActivation() throws Exception {
		final DemandingSystemSubscriber subscriber = new DemandingSystemSubscriber();
		final ExecutorService deliveryExecutor = createDaemonExecutor("cdc-cancel-pending-activation");
		final DefaultChangeCaptureSubscription<ChangeSystemCapture> subscription =
			createDetachedSystemSubscription(subscriber, deliveryExecutor);
		try {
			final Future<?> deliveryFuture = deliveryExecutor.submit(
				() -> subscription.deliverImmediate(createHostCapture("cancelled", 80L))
			);
			deliveryFuture.get(1L, TimeUnit.SECONDS);

			subscription.cancel();
			subscription.activate();

			assertTrue(subscriber.getCaptures().isEmpty());
			assertNull(
				getFieldValue(subscription, "pendingActivationCapture"),
				"Cancellation retained the pending host capture after making delivery impossible."
			);
		} finally {
			subscription.cancel();
			deliveryExecutor.shutdownNow();
		}
	}

	/**
	 * Verifies that silent registration retraction discards a host capture already retained in the activation slot.
	 *
	 * The {@code activate()} call below is defence-in-depth, not a reproduction: a retracted registration is never
	 * activated, because {@code trySubscribe} returns an empty result before it reaches that call. What this pins is
	 * that {@code retract()} itself clears the slot, so a capture parked before the retraction cannot outlive it.
	 */
	@Test
	@DisplayName("discard a pending host event when retracted before activation")
	void shouldDiscardPendingHostEventWhenRetractedBeforeActivation() throws Exception {
		final DemandingSystemSubscriber subscriber = new DemandingSystemSubscriber();
		final ExecutorService deliveryExecutor = createDaemonExecutor("cdc-retract-pending-activation");
		final DefaultChangeCaptureSubscription<ChangeSystemCapture> subscription =
			createDetachedSystemSubscription(subscriber, deliveryExecutor);
		try {
			final Future<?> deliveryFuture = deliveryExecutor.submit(
				() -> subscription.deliverImmediate(createHostCapture("retracted", 90L))
			);
			deliveryFuture.get(1L, TimeUnit.SECONDS);

			subscription.retract();
			subscription.activate();

			assertTrue(subscriber.getCaptures().isEmpty());
			assertNull(
				getFieldValue(subscription, "pendingActivationCapture"),
				"Retraction retained the pending host capture after making delivery impossible."
			);
		} finally {
			subscription.retract();
			deliveryExecutor.shutdownNow();
		}
	}

	/**
	 * Verifies that a failed {@code onSubscribe} callback discards a host capture retained while that callback was
	 * still running.
	 */
	@Test
	@DisplayName("discard a pending host event when activation throws")
	void shouldDiscardPendingHostEventWhenActivationThrows() throws Exception {
		final RuntimeException activationFailure = new IllegalStateException("onSubscribe failed");
		final BlockingThrowingSystemSubscriber subscriber =
			new BlockingThrowingSystemSubscriber(activationFailure);
		final ExecutorService deliveryExecutor = createDaemonExecutor("cdc-failed-pending-activation");
		final DefaultChangeCaptureSubscription<ChangeSystemCapture> subscription =
			createDetachedSystemSubscription(subscriber, deliveryExecutor);
		final Future<?> activationFuture = deliveryExecutor.submit(subscription::activate);
		try {
			assertTrue(
				subscriber.awaitOnSubscribe(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
				"The subscriber never entered onSubscribe."
			);
			final Future<?> deliveryFuture = deliveryExecutor.submit(
				() -> subscription.deliverImmediate(createHostCapture("failedActivation", 100L))
			);
			deliveryFuture.get(1L, TimeUnit.SECONDS);

			subscriber.allowOnSubscribeToThrow();
			final ExecutionException propagated = assertThrows(
				ExecutionException.class,
				() -> activationFuture.get(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
			);
			assertSame(activationFailure, propagated.getCause());
			assertTrue(subscriber.getCaptures().isEmpty());
			assertNull(
				getFieldValue(subscription, "pendingActivationCapture"),
				"Failed activation retained a host capture that can never be delivered."
			);
		} finally {
			subscriber.allowOnSubscribeToThrow();
			subscription.cancel();
			deliveryExecutor.shutdownNow();
		}
	}

	/**
	 * Creates a minimal mocked catalog for publisher lifecycle tests.
	 *
	 * @param version catalog version returned by the mock
	 * @return configured catalog mock
	 */
	@Nonnull
	private static Catalog createCatalog(long version) {
		final Catalog catalog = mock(Catalog.class);
		when(catalog.getName()).thenReturn("sharedPublisherTest");
		when(catalog.getVersion()).thenReturn(version);
		return catalog;
	}

	/**
	 * Creates a system publisher backed by a minimal mocked engine state.
	 *
	 * @param version              engine version returned by the mock
	 * @param subscriberBufferSize per-subscriber queue capacity
	 * @return configured system shared publisher
	 */
	@Nonnull
	private static ChangeSystemCaptureSharedPublisher createSystemPublisher(
		long version,
		int subscriberBufferSize
	) {
		final ExpandedEngineState engineState = mock(ExpandedEngineState.class);
		when(engineState.version()).thenReturn(version);
		final Evita evita = mock(Evita.class);
		when(evita.getEngineState()).thenReturn(engineState);
		return new ChangeSystemCaptureSharedPublisher(
			evita,
			new ImmediateExecutorService(),
			16,
			subscriberBufferSize,
			capture -> {
			}
		);
	}

	/**
	 * Creates a standalone system subscription for activation lifecycle tests.
	 *
	 * @param subscriber subscriber used by the subscription
	 * @param executorService executor used for deferred subscription work
	 * @return a new, inactive subscription
	 */
	@Nonnull
	private static DefaultChangeCaptureSubscription<ChangeSystemCapture> createDetachedSystemSubscription(
		@Nonnull Subscriber<ChangeSystemCapture> subscriber,
		@Nonnull ExecutorService executorService
	) {
		return new DefaultChangeCaptureSubscription<>(
			UUID.randomUUID(),
			16,
			new WalPointerWithContent(1L, 0, ChangeCaptureContent.BODY),
			subscriber,
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
	 * Creates a host capture with a recognizable catalog name.
	 *
	 * @param catalogName catalog name stored in the event
	 * @param version correlation version stored in the capture and event
	 * @return a host-event capture
	 */
	@Nonnull
	private static ChangeSystemCapture createHostCapture(@Nonnull String catalogName, long version) {
		return new ChangeSystemCapture(
			version,
			0,
			OffsetDateTime.now(),
			Operation.UPSERT,
			new HostSystemEvent.CatalogInstalledIntoLiveView(catalogName, CatalogState.ALIVE, version)
		);
	}

	/**
	 * Creates a daemon-backed executor so a failed concurrency assertion cannot keep the Surefire JVM alive.
	 *
	 * @param threadName name assigned to worker threads
	 * @return daemon-backed cached executor
	 */
	@Nonnull
	private static ExecutorService createDaemonExecutor(@Nonnull String threadName) {
		return Executors.newCachedThreadPool(
			runnable -> {
				final Thread thread = new Thread(runnable, threadName);
				thread.setDaemon(true);
				return thread;
			}
		);
	}

	/**
	 * Waits for a latch from callback code that cannot declare checked exceptions.
	 *
	 * @param latch latch to wait for
	 * @param failureMessage message used when the wait is interrupted or times out
	 */
	private static void awaitUnchecked(@Nonnull CountDownLatch latch, @Nonnull String failureMessage) {
		try {
			if (!latch.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
				throw new AssertionError(failureMessage);
			}
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new AssertionError(failureMessage, ex);
		}
	}

	/**
	 * Concurrent map that pauses either removal overload immediately before delegating to the actual map operation.
	 */
	private static final class PausingPublisherMap
		extends ConcurrentHashMap<ChangeCatalogCriteriaBundle, ChangeCatalogCaptureSharedPublisher> {
		private final CountDownLatch removalStarted = new CountDownLatch(1);
		private final CountDownLatch removalAllowed = new CountDownLatch(1);

		/**
		 * Waits until a publisher close reaches its observer-map removal.
		 *
		 * @param timeout maximum wait
		 * @param unit timeout unit
		 * @return {@code true} when removal began before the timeout
		 * @throws InterruptedException when interrupted while waiting
		 */
		boolean awaitRemoval(long timeout, @Nonnull TimeUnit unit) throws InterruptedException {
			return this.removalStarted.await(timeout, unit);
		}

		/**
		 * Releases the paused removal.
		 */
		void allowRemoval() {
			this.removalAllowed.countDown();
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		public ChangeCatalogCaptureSharedPublisher remove(Object key) {
			pauseRemoval();
			return super.remove(key);
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		public boolean remove(Object key, Object value) {
			pauseRemoval();
			return super.remove(key, value);
		}

		/**
		 * Pauses a removal after exposing that it began.
		 */
		private void pauseRemoval() {
			this.removalStarted.countDown();
			awaitUnchecked(this.removalAllowed, "The test never released the paused publisher removal.");
		}
	}

	/**
	 * Subscriber that accepts its subscription without raising demand.
	 *
	 * @param <T> capture type
	 */
	private static final class SilentSubscriber<T> implements Subscriber<T> {
		/** {@inheritDoc} */
		@Override
		public void onSubscribe(Subscription subscription) {
		}

		/** {@inheritDoc} */
		@Override
		public void onNext(T item) {
		}

		/** {@inheritDoc} */
		@Override
		public void onError(Throwable throwable) {
		}

		/** {@inheritDoc} */
		@Override
		public void onComplete() {
		}
	}

	/**
	 * Subscriber that records the observed publisher-lock hold count when activation calls it.
	 *
	 * @param <T> capture type
	 */
	private static final class LockObservingSubscriber<T extends ChangeCapture> implements Subscriber<T> {
		private final ReentrantLock publisherLock;
		private final AtomicReference<Subscription> subscription = new AtomicReference<>();
		private final AtomicInteger publisherLockHoldCount = new AtomicInteger(-1);

		/**
		 * Creates a lock-observing subscriber.
		 *
		 * @param publisherLock publisher lock whose current-thread hold count is recorded
		 */
		private LockObservingSubscriber(@Nonnull ReentrantLock publisherLock) {
			this.publisherLock = publisherLock;
		}

		/** {@inheritDoc} */
		@Override
		public void onSubscribe(Subscription subscription) {
			this.publisherLockHoldCount.set(this.publisherLock.getHoldCount());
			this.subscription.set(subscription);
		}

		/** {@inheritDoc} */
		@Override
		public void onNext(T item) {
		}

		/** {@inheritDoc} */
		@Override
		public void onError(Throwable throwable) {
		}

		/** {@inheritDoc} */
		@Override
		public void onComplete() {
		}

		/**
		 * Returns the publisher-lock hold count observed from inside {@code onSubscribe}.
		 *
		 * @return observed reentrant hold count
		 */
		int getPublisherLockHoldCount() {
			return this.publisherLockHoldCount.get();
		}

		/**
		 * Cancels the accepted subscription.
		 */
		void cancel() {
			this.subscription.get().cancel();
		}
	}

	/**
	 * Subscriber whose activation raises the same exception type formerly used as the retry discriminator.
	 */
	private static final class ThrowingInstanceTerminatedSubscriber implements Subscriber<ChangeCatalogCapture> {
		private final InstanceTerminatedException failure;
		private final AtomicInteger onSubscribeCalls = new AtomicInteger();

		/**
		 * Creates a subscriber that always throws the supplied failure from {@code onSubscribe}.
		 *
		 * @param failure failure to throw
		 */
		private ThrowingInstanceTerminatedSubscriber(@Nonnull InstanceTerminatedException failure) {
			this.failure = failure;
		}

		/** {@inheritDoc} */
		@Override
		public void onSubscribe(Subscription subscription) {
			this.onSubscribeCalls.incrementAndGet();
			throw this.failure;
		}

		/** {@inheritDoc} */
		@Override
		public void onNext(ChangeCatalogCapture item) {
		}

		/** {@inheritDoc} */
		@Override
		public void onError(Throwable throwable) {
		}

		/** {@inheritDoc} */
		@Override
		public void onComplete() {
		}

		/**
		 * Returns how often the subscriber was handed a subscription.
		 *
		 * @return invocation count
		 */
		int getOnSubscribeCalls() {
			return this.onSubscribeCalls.get();
		}
	}

	/**
	 * Subscriber that raises demand only after the host-event thread owns its delivery lock, then keeps
	 * {@code onSubscribe} open until the test releases it.
	 */
	private static final class BlockingOnSubscribeSystemSubscriber implements Subscriber<ChangeSystemCapture> {
		private final CountDownLatch subscriptionReceived = new CountDownLatch(1);
		private final CountDownLatch hostOwnsDeliveryLock = new CountDownLatch(1);
		private final CountDownLatch demandRaised = new CountDownLatch(1);
		private final CountDownLatch hostDeliveryAttempted = new CountDownLatch(1);
		private final CountDownLatch allowOnSubscribeReturn = new CountDownLatch(1);
		private final CountDownLatch onNextCalled = new CountDownLatch(1);
		private final CountDownLatch bothCapturesReceived = new CountDownLatch(2);
		private final AtomicReference<DefaultChangeCaptureSubscription<ChangeSystemCapture>> subscription =
			new AtomicReference<>();
		private final AtomicBoolean onSubscribeReturned = new AtomicBoolean(false);
		private final AtomicBoolean onSubscribeReturnedBeforeOnNext = new AtomicBoolean(true);
		private final List<ChangeSystemCapture> captures = new CopyOnWriteArrayList<>();

		/** {@inheritDoc} */
		@Override
		@SuppressWarnings("unchecked")
		public void onSubscribe(Subscription subscription) {
			this.subscription.set((DefaultChangeCaptureSubscription<ChangeSystemCapture>) subscription);
			this.subscriptionReceived.countDown();
			awaitUnchecked(
				this.hostOwnsDeliveryLock,
				"The host-event thread never acquired the subscription delivery lock."
			);
			subscription.request(2L);
			this.demandRaised.countDown();
			awaitUnchecked(this.allowOnSubscribeReturn, "The test never released onSubscribe.");
			this.onSubscribeReturned.set(true);
		}

		/** {@inheritDoc} */
		@Override
		public void onNext(ChangeSystemCapture item) {
			if (!this.onSubscribeReturned.get()) {
				this.onSubscribeReturnedBeforeOnNext.set(false);
			}
			this.captures.add(item);
			this.onNextCalled.countDown();
			this.bothCapturesReceived.countDown();
		}

		/** {@inheritDoc} */
		@Override
		public void onError(Throwable throwable) {
		}

		/** {@inheritDoc} */
		@Override
		public void onComplete() {
		}

		/**
		 * Waits until {@code onSubscribe} receives the subscription.
		 *
		 * @param timeout maximum wait
		 * @param unit timeout unit
		 * @return {@code true} when the subscription arrived
		 * @throws InterruptedException when interrupted while waiting
		 */
		boolean awaitSubscription(long timeout, @Nonnull TimeUnit unit) throws InterruptedException {
			return this.subscriptionReceived.await(timeout, unit);
		}

		/**
		 * Returns the subscription already handed to this subscriber.
		 *
		 * @return received subscription
		 */
		@Nonnull
		DefaultChangeCaptureSubscription<ChangeSystemCapture> getSubscription() {
			return this.subscription.get();
		}

		/**
		 * Signals that the host-event thread owns the delivery lock.
		 */
		void signalHostOwnsDeliveryLock() {
			this.hostOwnsDeliveryLock.countDown();
		}

		/**
		 * Waits until demand has been raised from inside {@code onSubscribe}.
		 */
		void awaitDemandUnchecked() {
			awaitUnchecked(this.demandRaised, "The subscriber never raised demand from inside onSubscribe.");
		}

		/**
		 * Signals that the host thread is entering the immediate-delivery path.
		 */
		void signalHostDeliveryAttempt() {
			this.hostDeliveryAttempted.countDown();
		}

		/**
		 * Waits until the host thread enters the immediate-delivery path.
		 *
		 * @param timeout maximum wait
		 * @param unit timeout unit
		 * @return {@code true} when the attempt began
		 * @throws InterruptedException when interrupted while waiting
		 */
		boolean awaitHostDeliveryAttempt(long timeout, @Nonnull TimeUnit unit) throws InterruptedException {
			return this.hostDeliveryAttempted.await(timeout, unit);
		}

		/**
		 * Waits for host-event delivery.
		 *
		 * @param timeout maximum wait
		 * @param unit timeout unit
		 * @return {@code true} when {@code onNext} ran
		 * @throws InterruptedException when interrupted while waiting
		 */
		boolean awaitOnNext(long timeout, @Nonnull TimeUnit unit) throws InterruptedException {
			return this.onNextCalled.await(timeout, unit);
		}

		/**
		 * Waits until both the queued capture and pending host capture arrive.
		 *
		 * @param timeout maximum wait
		 * @param unit timeout unit
		 * @return {@code true} when both captures arrived
		 * @throws InterruptedException when interrupted while waiting
		 */
		boolean awaitBothCaptures(long timeout, @Nonnull TimeUnit unit) throws InterruptedException {
			return this.bothCapturesReceived.await(timeout, unit);
		}

		/**
		 * Returns captures in callback order.
		 *
		 * @return received captures
		 */
		@Nonnull
		List<ChangeSystemCapture> getCaptures() {
			return this.captures;
		}

		/**
		 * Allows the blocked {@code onSubscribe} callback to return.
		 */
		void allowOnSubscribeToReturn() {
			this.allowOnSubscribeReturn.countDown();
		}

		/**
		 * Returns whether {@code onSubscribe} had returned when {@code onNext} ran.
		 *
		 * @return {@code true} when callback ordering was valid
		 */
		boolean wasOnSubscribeReturnedBeforeOnNext() {
			return this.onSubscribeReturnedBeforeOnNext.get();
		}

		/**
		 * Cancels the accepted subscription.
		 */
		void cancel() {
			final DefaultChangeCaptureSubscription<ChangeSystemCapture> theSubscription = this.subscription.get();
			if (theSubscription != null) {
				theSubscription.cancel();
			}
		}
	}

	/**
	 * Subscriber that raises demand immediately and records every delivered capture.
	 */
	private static class DemandingSystemSubscriber implements Subscriber<ChangeSystemCapture> {
		private final List<ChangeSystemCapture> captures = new CopyOnWriteArrayList<>();

		/** {@inheritDoc} */
		@Override
		public void onSubscribe(Subscription subscription) {
			subscription.request(1L);
		}

		/** {@inheritDoc} */
		@Override
		public void onNext(ChangeSystemCapture item) {
			this.captures.add(item);
		}

		/** {@inheritDoc} */
		@Override
		public void onError(Throwable throwable) {
		}

		/** {@inheritDoc} */
		@Override
		public void onComplete() {
		}

		/**
		 * Returns captures in callback order.
		 *
		 * @return received captures
		 */
		@Nonnull
		List<ChangeSystemCapture> getCaptures() {
			return this.captures;
		}
	}

	/**
	 * Subscriber that holds {@code onSubscribe} open until released and then throws a configured failure.
	 */
	private static final class BlockingThrowingSystemSubscriber extends DemandingSystemSubscriber {
		private final RuntimeException activationFailure;
		private final CountDownLatch onSubscribeEntered = new CountDownLatch(1);
		private final CountDownLatch allowOnSubscribeFailure = new CountDownLatch(1);

		/**
		 * Creates a blocking subscriber that eventually throws the supplied failure.
		 *
		 * @param activationFailure failure thrown after the callback is released
		 */
		private BlockingThrowingSystemSubscriber(@Nonnull RuntimeException activationFailure) {
			this.activationFailure = activationFailure;
		}

		/** {@inheritDoc} */
		@Override
		public void onSubscribe(Subscription subscription) {
			super.onSubscribe(subscription);
			this.onSubscribeEntered.countDown();
			awaitUnchecked(this.allowOnSubscribeFailure, "The test never released the failing onSubscribe callback.");
			throw this.activationFailure;
		}

		/**
		 * Waits for the subscriber to enter {@code onSubscribe}.
		 *
		 * @param timeout maximum wait
		 * @param unit timeout unit
		 * @return {@code true} when the callback was entered
		 * @throws InterruptedException when interrupted while waiting
		 */
		boolean awaitOnSubscribe(long timeout, @Nonnull TimeUnit unit) throws InterruptedException {
			return this.onSubscribeEntered.await(timeout, unit);
		}

		/**
		 * Allows {@code onSubscribe} to throw its configured failure.
		 */
		void allowOnSubscribeToThrow() {
			this.allowOnSubscribeFailure.countDown();
		}
	}
}
