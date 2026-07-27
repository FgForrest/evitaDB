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

package io.evitadb.store.catalog;

import io.evitadb.core.executor.Scheduler;
import io.evitadb.store.kryo.ObservableOutput;
import io.evitadb.store.offsetIndex.io.ReadOnlyHandle;
import io.evitadb.store.offsetIndex.io.WriteOnlyHandle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

import static io.evitadb.test.TestTags.STORAGE;
import static io.evitadb.test.TestTags.TRANSACTION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the two properties the deferred-checkpoint design rests on: that no file owing a device flush is ever
 * dropped from the pending set, and that a catalog which falls silent still gets checkpointed.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Checkpoint coordinator")
@Tag(STORAGE)
@Tag(TRANSACTION)
class CheckpointCoordinatorTest {
	private static final String CATALOG_NAME = "testCatalog";

	private final ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(2);
	private final Scheduler scheduler = new Scheduler(this.executor);
	private CheckpointCoordinator coordinator;

	@AfterEach
	void tearDown() {
		if (this.coordinator != null) {
			this.coordinator.close();
		}
		this.executor.shutdownNow();
	}

	@Test
	@DisplayName("should force every registered handle")
	void shouldForceEveryRegisteredHandle() {
		this.coordinator = new CheckpointCoordinator(CATALOG_NAME, 1_000, this.scheduler, new ReentrantLock(), () -> {});

		final RecordingHandle first = new RecordingHandle();
		final RecordingHandle second = new RecordingHandle();
		this.coordinator.noteSyncPending(first);
		this.coordinator.noteSyncPending(second);
		// registering twice must not force twice - the set is keyed by handle, not by write
		this.coordinator.noteSyncPending(first);

		this.coordinator.forcePendingSyncs();

		assertEquals(1, first.forceCount.get());
		assertEquals(1, second.forceCount.get());

		// nothing left owing, so a second checkpoint has no work
		this.coordinator.forcePendingSyncs();
		assertEquals(1, first.forceCount.get());
		assertEquals(1, second.forceCount.get());
	}

	@Test
	@DisplayName("should keep a handle registered during the force still pending afterwards")
	void shouldKeepHandleRegisteredDuringForceStillPending() {
		this.coordinator = new CheckpointCoordinator(CATALOG_NAME, 1_000, this.scheduler, new ReentrantLock(), () -> {});

		try (final RecordingHandle lateComer = new RecordingHandle()) {
			// models the real race: `doSoftFlush` runs on a request thread and may register a handle while the
			// checkpoint is already forcing. Clearing the set instead of removing the forced snapshot would drop this
			// handle, leaving a durable bootstrap record pointing at bytes that were never synced.
			final RecordingHandle registeringHandle = new RecordingHandle(
				() -> this.coordinator.noteSyncPending(lateComer)
			);
			this.coordinator.noteSyncPending(registeringHandle);

			this.coordinator.forcePendingSyncs();

			assertEquals(1, registeringHandle.forceCount.get());
			assertEquals(
				0, lateComer.forceCount.get(),
				"The handle registered mid-force was not part of the snapshot, so it must not have been forced yet."
			);

			// and it must still be owed, not silently dropped
			this.coordinator.forcePendingSyncs();
			assertEquals(
				1, lateComer.forceCount.get(),
				"The handle registered mid-force was dropped from the pending set instead of being carried over!"
			);
		}
	}

	@Test
	@DisplayName("should checkpoint a silent catalog through the ticker")
	void shouldCheckpointSilentCatalogThroughTicker() throws InterruptedException {
		final CountDownLatch checkpointed = new CountDownLatch(1);
		this.coordinator = new CheckpointCoordinator(
			CATALOG_NAME, 100, this.scheduler, new ReentrantLock(),
			() -> {
				checkpointed.countDown();
				this.coordinator.noteCheckpointCompleted();
			}
		);

		// exactly one deferred round and then silence - nothing else will ever drive a checkpoint
		this.coordinator.noteCheckpointDeferred();

		assertTrue(
			checkpointed.await(5, TimeUnit.SECONDS),
			"A catalog that went silent after a deferred round was never checkpointed!"
		);
	}

	@Test
	@DisplayName("should not run the ticker checkpoint when a round already checkpointed")
	void shouldNotRunTickerCheckpointWhenRoundAlreadyCheckpointed() {
		final AtomicInteger checkpointCount = new AtomicInteger();
		// the interval is irrelevant here - noteCheckpointCompleted() clears the debt unconditionally and
		// checkpointIfOwed() only reads it, so a value far outside the assertion's own execution time keeps
		// the real scheduled ticker from ever firing inside the test window at all
		this.coordinator = new CheckpointCoordinator(
			CATALOG_NAME, 5_000, this.scheduler, new ReentrantLock(), checkpointCount::incrementAndGet
		);

		this.coordinator.noteCheckpointDeferred();
		// a subsequent round found the interval elapsed and checkpointed inline, which disarms the ticker
		this.coordinator.noteCheckpointCompleted();

		// exercise exactly what the ticker body does when it fires (see runTickerCheckpoint) - deterministic,
		// unlike waiting on the scheduler thread to actually fire the ticker within some assumed margin
		this.coordinator.checkpointIfOwed();
		assertEquals(
			0, checkpointCount.get(),
			"The ticker checkpointed even though a round had already settled the debt."
		);
	}

	@Test
	@DisplayName("should record the failure when a ticker checkpoint throws")
	void shouldRecordFailureWhenTickerCheckpointThrows() throws InterruptedException {
		final IllegalStateException failure = new IllegalStateException("device is gone");
		final CountDownLatch attempted = new CountDownLatch(1);
		this.coordinator = new CheckpointCoordinator(
			CATALOG_NAME, 100, this.scheduler, new ReentrantLock(),
			() -> {
				attempted.countDown();
				throw failure;
			}
		);

		this.coordinator.noteCheckpointDeferred();
		assertTrue(attempted.await(5, TimeUnit.SECONDS), "The ticker never ran!");

		// there is no client left to throw into - the failure has to be observable so the catalog can stop
		// acknowledging commits it can no longer make durable
		Throwable recorded = null;
		for (int i = 0; i < 50 && recorded == null; i++) {
			recorded = this.coordinator.getFailure();
			if (recorded == null) {
				Thread.sleep(20);
			}
		}
		assertNotNull(recorded, "A failed checkpoint was swallowed on the scheduler thread!");
		assertSame(failure, recorded);
	}

	@Test
	@DisplayName("should report a checkpoint as due only once the interval has elapsed")
	void shouldReportCheckpointDueOnlyAfterInterval() throws InterruptedException {
		this.coordinator = new CheckpointCoordinator(CATALOG_NAME, 200, this.scheduler, new ReentrantLock(), () -> {});

		assertNull(this.coordinator.getFailure());
		// freshly constructed - the interval is counted from construction so the first round does not
		// immediately checkpoint
		assertFalse(this.coordinator.isCheckpointDue(), "A brand new coordinator reported a checkpoint as overdue.");

		Thread.sleep(300);
		assertTrue(this.coordinator.isCheckpointDue(), "The checkpoint interval elapsed but no checkpoint was due.");
	}

	/**
	 * Minimal {@link WriteOnlyHandle} that only counts {@link #forceDurable()} calls and optionally runs a hook
	 * while forcing, so the mid-force registration race can be reproduced deterministically.
	 */
	private static class RecordingHandle implements WriteOnlyHandle {
		private final AtomicInteger forceCount = new AtomicInteger();
		private final Runnable onForce;

		RecordingHandle() {
			this(null);
		}

		RecordingHandle(@Nullable Runnable onForce) {
			this.onForce = onForce;
		}

		@Override
		public <T> T checkAndExecute(@Nonnull String operation, @Nonnull Runnable premise, @Nonnull Function<ObservableOutput<?>, T> logic) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void checkAndExecuteAndSync(@Nonnull String operation, @Nonnull Runnable premise, @Nonnull Consumer<ObservableOutput<?>> logic) {
			throw new UnsupportedOperationException();
		}

		@Override
		public <S, T> T checkAndExecuteAndSync(@Nonnull String operation, @Nonnull Runnable premise, @Nonnull Function<ObservableOutput<?>, S> logic, @Nonnull BiFunction<ObservableOutput<?>, S, T> postExecutionLogic) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void forceDurable() {
			this.forceCount.incrementAndGet();
			if (this.onForce != null) {
				this.onForce.run();
			}
		}

		@Override
		public long getLastWrittenPosition() {
			return 0;
		}

		@Nonnull
		@Override
		public ReadOnlyHandle toReadOnlyHandle() {
			throw new UnsupportedOperationException();
		}

		@Override
		public void close() {
		}
	}

}
