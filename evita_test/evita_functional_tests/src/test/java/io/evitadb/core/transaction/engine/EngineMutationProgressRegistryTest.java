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

package io.evitadb.core.transaction.engine;

import io.evitadb.api.CommitProgress.CommitVersions;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.exception.CatalogNotFoundException;
import io.evitadb.api.requestResponse.progress.ProgressRecord;
import io.evitadb.core.exception.EngineMutationFailedException;
import io.evitadb.core.exception.EngineMutationProgressNotFoundException;
import io.evitadb.core.executor.ImmediateScheduledThreadPoolExecutor;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.core.transaction.engine.EngineMutationProgressStatus.Status;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.TASK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests of {@link EngineMutationProgressRegistry} in isolation: the signals its publishers emit, the retention of
 * finished outcomes and its limit. Delivery runs inline (`Runnable::run`) and time is driven by a fake ticker, so
 * every test is deterministic.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
@DisplayName("Engine mutation progress registry")
@Tag(ENGINE)
@Tag(TASK)
class EngineMutationProgressRegistryTest {
	private static final int RETENTION_SECONDS = 60;

	private final AtomicLong nanos = new AtomicLong();
	private ImmediateScheduledThreadPoolExecutor schedulerExecutor;
	private Scheduler scheduler;
	private EngineMutationProgressRegistry registry;

	@BeforeEach
	void setUp() {
		this.schedulerExecutor = new ImmediateScheduledThreadPoolExecutor();
		this.scheduler = new Scheduler(this.schedulerExecutor);
		this.registry = createRegistry(RETENTION_SECONDS, 2);
	}

	@AfterEach
	void tearDown() {
		this.registry.close();
		this.schedulerExecutor.shutdownNow();
	}

	@Nested
	@DisplayName("Live subscription")
	class LiveSubscriptionTest {

		@Test
		@DisplayName("should deliver the current percentage, updates and the terminal item with versions")
		void shouldDeliverCurrentPercentUpdatesAndTerminalItem() {
			final UUID id = UUID.randomUUID();
			final ProgressRecord<CommitVersions> progress = register(id);
			progress.updatePercentCompleted(10);

			final RecordingSubscriber subscriber = subscribe(id, Long.MAX_VALUE);
			progress.updatePercentCompleted(50);
			// a repeated or lower value is not published again
			progress.updatePercentCompleted(40);
			EngineMutationProgressRegistryTest.this.registry.complete(id, new CommitVersions(5L, 3), null);

			assertEquals(List.of(10, 50, 100), subscriber.percentages());
			final EngineMutationProgressStatus terminal = subscriber.items.get(2);
			assertEquals(Status.SUCCEEDED, terminal.status());
			assertTrue(terminal.finished());
			assertEquals(5L, terminal.catalogVersion());
			assertEquals(3, terminal.catalogSchemaVersion());
			assertTrue(subscriber.completed);
			assertNull(subscriber.error);
		}

		@Test
		@DisplayName("should not publish 100 % as an intermediate update")
		void shouldNotPublishHundredPercentAsIntermediateUpdate() {
			final UUID id = UUID.randomUUID();
			final ProgressRecord<CommitVersions> progress = register(id);
			final RecordingSubscriber subscriber = subscribe(id, Long.MAX_VALUE);

			// an engine progress reports 100 % to its listeners also right before it fails
			progress.updatePercentCompleted(100);

			assertEquals(List.of(0), subscriber.percentages());
			assertFalse(subscriber.completed);
		}

		@Test
		@DisplayName("should signal a failure with the error code and public message only")
		void shouldSignalFailureWithPublicPartOnly() {
			final UUID id = UUID.randomUUID();
			register(id);
			final RecordingSubscriber subscriber = subscribe(id, Long.MAX_VALUE);

			final CatalogNotFoundException failure = new CatalogNotFoundException("missing");
			EngineMutationProgressRegistryTest.this.registry.complete(id, null, new CompletionException(failure));

			assertFalse(subscriber.completed, "A failure must never be reported as a success.");
			final EngineMutationFailedException error = assertInstanceOf(
				EngineMutationFailedException.class, subscriber.error
			);
			assertEquals(failure.getErrorCode(), error.getErrorCode());
			assertTrue(error.getPublicMessage().contains(failure.getPublicMessage()));
			assertTrue(subscriber.items.stream().noneMatch(EngineMutationProgressStatus::finished));
		}

		@Test
		@DisplayName("should keep only the type of a failure that is not an evitaDB error")
		void shouldKeepOnlyTypeOfForeignFailure() {
			final UUID id = UUID.randomUUID();
			register(id);
			EngineMutationProgressRegistryTest.this.registry.complete(
				id, null, new IllegalStateException("secret internal detail")
			);

			final RecordingSubscriber subscriber = subscribe(id, Long.MAX_VALUE);
			final EngineMutationFailedException error = assertInstanceOf(
				EngineMutationFailedException.class, subscriber.error
			);
			assertTrue(error.getPublicMessage().contains("IllegalStateException"));
			assertFalse(error.getPublicMessage().contains("secret"));
		}

		@Test
		@DisplayName("should let a slow subscriber skip intermediate values but always deliver the terminal item")
		void shouldDeliverTerminalItemToSlowSubscriber() {
			final UUID id = UUID.randomUUID();
			final ProgressRecord<CommitVersions> progress = register(id);
			final RecordingSubscriber subscriber = subscribe(id, 0);
			for (int i = 1; i < 100; i++) {
				progress.updatePercentCompleted(i);
			}
			EngineMutationProgressRegistryTest.this.registry.complete(id, new CommitVersions(1L, 1), null);

			subscriber.request(Long.MAX_VALUE);

			assertTrue(subscriber.completed);
			assertTrue(subscriber.items.size() < 10, "Intermediate values must have been skipped.");
			assertEquals(100, subscriber.items.get(subscriber.items.size() - 1).percentCompleted());
			assertEquals(Status.SUCCEEDED, subscriber.items.get(subscriber.items.size() - 1).status());
		}

		@Test
		@DisplayName("should stop delivering to a subscriber that cancelled")
		void shouldStopDeliveringAfterCancel() {
			final UUID id = UUID.randomUUID();
			final ProgressRecord<CommitVersions> progress = register(id);
			final RecordingSubscriber subscriber = subscribe(id, Long.MAX_VALUE);

			subscriber.subscription.cancel();
			progress.updatePercentCompleted(30);
			EngineMutationProgressRegistryTest.this.registry.complete(id, null, null);

			assertEquals(List.of(0), subscriber.percentages());
			assertFalse(subscriber.completed);
		}

		@Test
		@DisplayName("should complete open subscriptions and new ones when closed")
		void shouldCompleteSubscriptionsWhenClosed() {
			final UUID id = UUID.randomUUID();
			register(id);
			final RecordingSubscriber subscriber = subscribe(id, Long.MAX_VALUE);

			EngineMutationProgressRegistryTest.this.registry.close();
			EngineMutationProgressRegistryTest.this.registry.complete(id, null, null);

			assertTrue(subscriber.completed);
			assertEquals(List.of(0), subscriber.percentages());
			final RecordingSubscriber late = subscribe(id, Long.MAX_VALUE);
			assertTrue(late.completed);
			assertTrue(late.items.isEmpty());
		}
	}

	@Nested
	@DisplayName("Asynchronous delivery")
	class AsynchronousDeliveryTest {

		@Test
		@DisplayName("should deliver all signals in order on a separate executor")
		void shouldDeliverSignalsOnSeparateExecutor() throws Exception {
			final ExecutorService delivery = Executors.newSingleThreadExecutor(runnable -> {
				final Thread thread = new Thread(runnable, "progress-delivery");
				thread.setDaemon(true);
				return thread;
			});
			final EngineMutationProgressRegistry asyncRegistry = new EngineMutationProgressRegistry(
				ServerOptions.builder().build(),
				EngineMutationProgressRegistryTest.this.scheduler,
				delivery,
				EngineMutationProgressRegistryTest.this.nanos::get
			);
			try {
				final UUID id = UUID.randomUUID();
				final ProgressRecord<CommitVersions> progress = new ProgressRecord<>("test", null);
				progress.updatePercentCompleted(0);
				asyncRegistry.register(id, progress);
				final CountDownLatch completed = new CountDownLatch(1);
				final RecordingSubscriber subscriber = new RecordingSubscriber(Long.MAX_VALUE) {
					@Override
					public void onComplete() {
						super.onComplete();
						completed.countDown();
					}
				};
				asyncRegistry.subscribe(id).subscribe(subscriber);
				for (int i = 1; i < 100; i++) {
					progress.updatePercentCompleted(i);
				}
				asyncRegistry.complete(id, new CommitVersions(2L, 1), null);

				assertTrue(completed.await(30, TimeUnit.SECONDS));
				final List<Integer> percentages = subscriber.percentages();
				assertEquals(100, percentages.get(percentages.size() - 1));
				for (int i = 1; i < percentages.size(); i++) {
					assertTrue(percentages.get(i - 1) < percentages.get(i), "Percentages must increase: " + percentages);
				}
			} finally {
				asyncRegistry.close();
				delivery.shutdownNow();
			}
		}
	}

	@Nested
	@DisplayName("Late subscription and retention")
	class RetentionTest {

		@Test
		@DisplayName("should deliver the terminal item to a subscriber arriving after completion")
		void shouldDeliverTerminalItemAfterCompletion() {
			final UUID id = UUID.randomUUID();
			register(id);
			EngineMutationProgressRegistryTest.this.registry.complete(id, null, null);

			final RecordingSubscriber subscriber = subscribe(id, Long.MAX_VALUE);

			assertEquals(List.of(100), subscriber.percentages());
			assertNull(subscriber.items.get(0).catalogVersion());
			assertTrue(subscriber.completed);
		}

		@Test
		@DisplayName("should report an unknown id as an error")
		void shouldReportUnknownIdAsError() {
			final RecordingSubscriber subscriber = subscribe(UUID.randomUUID(), Long.MAX_VALUE);

			assertInstanceOf(EngineMutationProgressNotFoundException.class, subscriber.error);
			assertTrue(subscriber.items.isEmpty());
		}

		@Test
		@DisplayName("should forget a finished outcome after the retention but never a running operation")
		void shouldExpireFinishedOutcomeButNotRunningOperation() {
			final UUID finished = UUID.randomUUID();
			final UUID running = UUID.randomUUID();
			register(finished);
			register(running);
			EngineMutationProgressRegistryTest.this.registry.complete(finished, null, null);

			// a lookup must not extend the retention
			advance(RETENTION_SECONDS - 1);
			assertTrue(subscribe(finished, Long.MAX_VALUE).completed);
			advance(2);

			assertInstanceOf(
				EngineMutationProgressNotFoundException.class, subscribe(finished, Long.MAX_VALUE).error
			);
			advance(TimeUnit.DAYS.toSeconds(365));
			final RecordingSubscriber runningSubscriber = subscribe(running, Long.MAX_VALUE);
			assertNull(runningSubscriber.error);
			assertEquals(List.of(0), runningSubscriber.percentages());
		}

		@Test
		@DisplayName("should keep at most the limit of finished outcomes and never evict a running operation")
		void shouldRespectLimitOfFinishedOutcomes() {
			final UUID running = UUID.randomUUID();
			register(running);
			final List<UUID> finished = List.of(
				UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()
			);
			for (UUID id : finished) {
				register(id);
				EngineMutationProgressRegistryTest.this.registry.complete(id, null, null);
			}

			final long retained = finished.stream()
				.filter(id -> subscribe(id, Long.MAX_VALUE).completed)
				.count();
			assertEquals(2, retained);
			assertNull(subscribe(running, Long.MAX_VALUE).error);
		}

		@Test
		@DisplayName("should retain nothing when the retention is zero")
		void shouldRetainNothingWhenRetentionIsZero() {
			EngineMutationProgressRegistryTest.this.registry.close();
			EngineMutationProgressRegistryTest.this.registry = createRegistry(0, 100);
			final UUID id = UUID.randomUUID();
			register(id);
			final RecordingSubscriber live = subscribe(id, Long.MAX_VALUE);
			EngineMutationProgressRegistryTest.this.registry.complete(id, null, null);

			assertTrue(live.completed, "A live subscriber still receives the outcome.");
			assertInstanceOf(EngineMutationProgressNotFoundException.class, subscribe(id, Long.MAX_VALUE).error);
		}

		@Test
		@DisplayName("should retain nothing when the limit is zero but still track running operations")
		void shouldRetainNothingWhenLimitIsZero() {
			EngineMutationProgressRegistryTest.this.registry.close();
			EngineMutationProgressRegistryTest.this.registry = createRegistry(RETENTION_SECONDS, 0);
			final UUID id = UUID.randomUUID();
			register(id);
			assertNull(subscribe(id, Long.MAX_VALUE).error, "A running operation is never evicted.");
			EngineMutationProgressRegistryTest.this.registry.complete(id, null, null);

			assertInstanceOf(EngineMutationProgressNotFoundException.class, subscribe(id, Long.MAX_VALUE).error);
		}

		@Test
		@DisplayName("should forget an operation removed before it was submitted")
		void shouldForgetRemovedOperation() {
			final UUID id = UUID.randomUUID();
			register(id);
			EngineMutationProgressRegistryTest.this.registry.remove(id);

			assertInstanceOf(EngineMutationProgressNotFoundException.class, subscribe(id, Long.MAX_VALUE).error);
		}
	}

	/**
	 * Creates a registry with inline delivery and the fake ticker.
	 */
	@Nonnull
	private EngineMutationProgressRegistry createRegistry(int retentionSeconds, int limit) {
		return new EngineMutationProgressRegistry(
			ServerOptions.builder()
				.engineMutationProgressRetentionInSeconds(retentionSeconds)
				.engineMutationProgressRetentionLimit(limit)
				.build(),
			this.scheduler,
			Runnable::run,
			this.nanos::get
		);
	}

	/**
	 * Registers a new running operation and returns its progress.
	 */
	@Nonnull
	private ProgressRecord<CommitVersions> register(@Nonnull UUID id) {
		final ProgressRecord<CommitVersions> progress = new ProgressRecord<>("test", null);
		progress.updatePercentCompleted(0);
		this.registry.register(id, progress);
		return progress;
	}

	/**
	 * Subscribes a recording subscriber that initially requests the given number of items.
	 */
	@Nonnull
	private RecordingSubscriber subscribe(@Nonnull UUID id, long initialRequest) {
		final RecordingSubscriber subscriber = new RecordingSubscriber(initialRequest);
		this.registry.subscribe(id).subscribe(subscriber);
		return subscriber;
	}

	/**
	 * Moves the fake ticker forward.
	 */
	private void advance(long seconds) {
		this.nanos.addAndGet(TimeUnit.SECONDS.toNanos(seconds));
	}

	/**
	 * Subscriber recording all signals. With inline delivery every signal is recorded before the call that caused
	 * it returns.
	 */
	private static class RecordingSubscriber implements Flow.Subscriber<EngineMutationProgressStatus> {
		private final long initialRequest;
		private final List<EngineMutationProgressStatus> items = new CopyOnWriteArrayList<>();
		private Flow.Subscription subscription;
		private volatile boolean completed;
		@Nullable private volatile Throwable error;

		RecordingSubscriber(long initialRequest) {
			this.initialRequest = initialRequest;
		}

		@Override
		public void onSubscribe(Flow.Subscription subscription) {
			this.subscription = subscription;
			if (this.initialRequest > 0) {
				subscription.request(this.initialRequest);
			}
		}

		@Override
		public void onNext(EngineMutationProgressStatus item) {
			this.items.add(item);
		}

		@Override
		public void onError(Throwable throwable) {
			this.error = throwable;
		}

		@Override
		public void onComplete() {
			this.completed = true;
		}

		void request(long n) {
			this.subscription.request(n);
		}

		@Nonnull
		List<Integer> percentages() {
			return this.items.stream().map(EngineMutationProgressStatus::percentCompleted).toList();
		}
	}

}
