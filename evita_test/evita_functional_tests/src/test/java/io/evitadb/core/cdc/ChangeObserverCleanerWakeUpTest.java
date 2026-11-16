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

import io.evitadb.api.TransactionContract.CommitBehavior;
import io.evitadb.api.configuration.ChangeDataCaptureOptions;
import io.evitadb.api.requestResponse.cdc.ChangeCaptureContent;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCaptureCriteria;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCaptureRequest;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCapture;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCaptureRequest;
import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.mutation.EngineMutation;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.executor.ImmediateExecutorService;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.dataType.ContainerType;
import io.evitadb.spi.store.catalog.wal.VersionSource;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.stream.Stream;

import static io.evitadb.test.TestTags.CDC;
import static io.evitadb.test.TestTags.ENGINE;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that observer cleaner ticks recover refused wake-ups without another publication. A controllable
 * executor refuses submissions until explicitly released, then runs them inline so every assertion follows a
 * completed tick. Real engine and catalog WALs also prove that ticks respect the publisher's visible version.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Observer cleaners recover idle capture subscriptions")
@Tag(ENGINE)
@Tag(CDC)
class ChangeObserverCleanerWakeUpTest implements EvitaTestSupport {
	/** Isolated storage for the embedded engine. */
	private TestPaths paths;
	/** Engine providing real catalog and system mutation logs. */
	private Evita evita;
	/** Scheduler backing the observers; cleaner ticks are invoked directly by these tests. */
	private ScheduledThreadPoolExecutor cleanerPool;

	/** Creates the embedded engine and a daemon scheduler without opening web servers. */
	@BeforeEach
	void setUp() throws IOException {
		this.paths = createTestPaths(ChangeObserverCleanerWakeUpTest.class.getSimpleName());
		Files.createDirectories(this.paths.storage());
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		this.evita.waitUntilFullyInitialized();
		this.evita.defineCatalog(TEST_CATALOG);
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.PRODUCT).withoutGeneratedPrimaryKey().updateVia(session);
				session.goLiveAndClose();
			}
		);
		this.cleanerPool = new ScheduledThreadPoolExecutor(
			1,
			runnable -> {
				final Thread thread = new Thread(runnable, "cdc-cleaner-wake-test");
				thread.setDaemon(true);
				return thread;
			}
		);
	}

	/** Releases the scheduler and embedded storage even after an assertion fails. */
	@AfterEach
	void tearDown() {
		if (this.cleanerPool != null) {
			this.cleanerPool.shutdownNow();
		}
		if (this.evita != null) {
			this.evita.close();
		}
		cleanupTestPaths(this.paths);
	}

	/**
	 * Refuses publication and cleaner wake-ups, then delivers on the next tick with no further commit. Later ticks
	 * must make one bounded attempt apiece and must not replay a delivered capture or expose an unpublished version.
	 */
	@Test
	@DisplayName("catalog cleaner retries refused wake-ups without another publication")
	void shouldWakeCatalogSubscriberOnCleanerTickWithoutAnotherPublication() {
		final long expectedVersion = liveCatalog().getVersion() + 1;
		try (
			final RefusingCaptureExecutor executor = new RefusingCaptureExecutor();
			final CatalogChangeObserver observer = new CatalogChangeObserver(
				ChangeDataCaptureOptions.builder().build(), executor, new Scheduler(this.cleanerPool), liveCatalog()
			)
		) {
			final AwaitableCaptureSubscriber<ChangeCatalogCapture> subscriber = AwaitableCaptureSubscriber.unbounded();
			observer.registerObserver(
				ChangeCatalogCaptureRequest.builder()
					.sinceVersion(expectedVersion)
					.content(ChangeCaptureContent.BODY)
					.criteria(ChangeCatalogCaptureCriteria.builder()
						.dataArea(site -> site.entityType(Entities.PRODUCT).containerType(ContainerType.ENTITY))
						.build())
					.build()
			).subscribe(subscriber);
			commitProduct(1);
			assertDoesNotThrow(() -> observer.notifyCatalogPresentInLiveView(liveCatalog()));
			assertEquals(1, executor.submissions, "Publication must attempt the refused wake-up.");
			assertTrue(subscriber.getItems().isEmpty(), "A refused wake-up must leave the capture waiting.");

			assertDoesNotThrow(observer::cleanInactivePublishers);
			assertTrue(subscriber.getItems().isEmpty(), "A refused cleaner wake-up must leave the capture waiting.");
			executor.refusing = false;
			observer.cleanInactivePublishers();
			assertEquals(
				List.of(expectedVersion), subscriber.getItems().stream().map(ChangeCatalogCapture::version).toList(),
				"The catalog cleaner did not deliver the waiting capture without another publication."
			);
			assertEquals(3, executor.submissions, "A refused cleaner wake-up must be retried on the next tick.");
			final List<ChangeCatalogCapture> delivered = subscriber.getItems();
			observer.cleanInactivePublishers();
			observer.cleanInactivePublishers();
			assertEquals(5, executor.submissions, "Each idle tick must submit exactly one bounded delivery attempt.");
			assertEquals(delivered, subscriber.getItems(), "Idle ticks must not duplicate captures.");

			commitProduct(2);
			observer.cleanInactivePublishers();
			assertEquals(delivered, subscriber.getItems(), "A cleaner must not advance the published catalog version.");
			observer.notifyCatalogPresentInLiveView(liveCatalog());
			assertEquals(
				List.of(expectedVersion, expectedVersion + 1),
				subscriber.getItems().stream().map(ChangeCatalogCapture::version).toList(),
				"Recovery must preserve capture order and deliver each capture once."
			);
			assertNull(subscriber.getError());
		}
	}

	/**
	 * A subscriber that has received everything it requested is not owed a delivery, so cleaner ticks must not hand
	 * its subscription to the capture executor - the tick retries only subscriptions with outstanding demand.
	 */
	@Test
	@DisplayName("catalog cleaner does not wake a subscriber whose demand is spent")
	void shouldNotWakeCatalogSubscriberWithoutOutstandingDemandOnCleanerTick() {
		final long expectedVersion = liveCatalog().getVersion() + 1;
		try (
			final RefusingCaptureExecutor executor = new RefusingCaptureExecutor();
			final CatalogChangeObserver observer = new CatalogChangeObserver(
				ChangeDataCaptureOptions.builder().build(), executor, new Scheduler(this.cleanerPool), liveCatalog()
			)
		) {
			executor.refusing = false;
			final AwaitableCaptureSubscriber<ChangeCatalogCapture> subscriber = new AwaitableCaptureSubscriber<>(1L);
			observer.registerObserver(
				ChangeCatalogCaptureRequest.builder()
					.sinceVersion(expectedVersion)
					.content(ChangeCaptureContent.BODY)
					.criteria(ChangeCatalogCaptureCriteria.builder()
						.dataArea(site -> site.entityType(Entities.PRODUCT).containerType(ContainerType.ENTITY))
						.build())
					.build()
			).subscribe(subscriber);
			commitProduct(1);
			observer.notifyCatalogPresentInLiveView(liveCatalog());
			final List<ChangeCatalogCapture> delivered = subscriber.getItems();
			assertEquals(
				List.of(expectedVersion), delivered.stream().map(ChangeCatalogCapture::version).toList(),
				"The publication did not deliver the one capture the subscriber requested."
			);
			final int submissionsAfterDelivery = executor.submissions;

			observer.cleanInactivePublishers();
			observer.cleanInactivePublishers();

			assertEquals(
				submissionsAfterDelivery, executor.submissions,
				"A cleaner tick woke a subscription whose subscriber has no outstanding demand."
			);
			assertEquals(delivered, subscriber.getItems(), "A cleaner tick delivered a capture nobody requested.");
			assertNull(subscriber.getError());
		}
	}

	/**
	 * Exercises the same refused-wake recovery for the singleton system publisher, including harmless empty ticks
	 * and a committed engine mutation that this observer has not yet published.
	 */
	@Test
	@DisplayName("system cleaner retries refused wake-ups without another publication")
	void shouldWakeSystemSubscriberOnCleanerTickWithoutAnotherPublication() {
		final long expectedVersion = this.evita.getEngineState().version() + 1;
		try (
			final RefusingCaptureExecutor executor = new RefusingCaptureExecutor();
			final SystemChangeObserver observer = new SystemChangeObserver(
				this.evita, ChangeDataCaptureOptions.builder().build(), executor, new Scheduler(this.cleanerPool)
			)
		) {
			final AwaitableCaptureSubscriber<ChangeSystemCapture> subscriber = AwaitableCaptureSubscriber.unbounded();
			observer.registerObserver(
				new ChangeSystemCaptureRequest(expectedVersion, 0, null, ChangeCaptureContent.BODY)
			).subscribe(subscriber);
			this.evita.defineCatalog("firstWaitingCatalog");
			assertEquals(expectedVersion, this.evita.getEngineState().version());
			assertDoesNotThrow(() -> publishSystemVersion(observer, expectedVersion));
			assertEquals(1, executor.submissions, "Publication must attempt the refused wake-up.");
			assertTrue(subscriber.getItems().isEmpty(), "A refused wake-up must leave the capture waiting.");

			assertDoesNotThrow(observer::cleanSubscribers);
			assertTrue(subscriber.getItems().isEmpty(), "A refused cleaner wake-up must leave the capture waiting.");
			executor.refusing = false;
			observer.cleanSubscribers();
			assertEquals(
				List.of(expectedVersion, expectedVersion),
				subscriber.getItems().stream().map(ChangeSystemCapture::version).toList(),
				"The system cleaner did not deliver the waiting capture without another publication."
			);
			assertEquals(3, executor.submissions, "A refused cleaner wake-up must be retried on the next tick.");
			final List<ChangeSystemCapture> delivered = subscriber.getItems();
			assertEquals(List.of(0, 1), delivered.stream().map(ChangeSystemCapture::index).toList());
			assertEquals(
				List.of(Operation.TRANSACTION, Operation.UPSERT),
				delivered.stream().map(ChangeSystemCapture::operation).toList(),
				"A system transaction must deliver its header followed by the catalog creation mutation."
			);
			observer.cleanSubscribers();
			observer.cleanSubscribers();
			assertEquals(5, executor.submissions, "Each idle tick must submit exactly one bounded delivery attempt.");
			assertEquals(delivered, subscriber.getItems(), "Idle ticks must not duplicate captures.");

			this.evita.defineCatalog("secondWaitingCatalog");
			observer.cleanSubscribers();
			assertEquals(delivered, subscriber.getItems(), "A cleaner must not advance the published engine version.");
			publishSystemVersion(observer, this.evita.getEngineState().version());
			assertEquals(
				List.of(expectedVersion, expectedVersion, expectedVersion + 1, expectedVersion + 1),
				subscriber.getItems().stream().map(ChangeSystemCapture::version).toList(),
				"Recovery must preserve capture order and deliver each capture once."
			);
			assertEquals(
				List.of(0, 1, 0, 1), subscriber.getItems().stream().map(ChangeSystemCapture::index).toList(),
				"Recovery must preserve the order of the captures within each engine version."
			);
			assertNull(subscriber.getError());
		}
	}

	/**
	 * Feeds a committed engine transaction to the standalone observer before publishing its version, matching the
	 * engine's normal mutation-before-notification order and populating the system publisher's recent-event ring.
	 *
	 * @param observer observer receiving the committed transaction
	 * @param version committed engine version to publish
	 */
	private void publishSystemVersion(@Nonnull SystemChangeObserver observer, long version) {
		try (
			final Stream<EngineMutation<?>> mutations = this.evita.getCommittedLiveMutationStream(
				version, version, VersionSource.INTERNAL
			)
		) {
			mutations.forEach(observer::processMutation);
		}
		observer.notifyVersionPresentInLiveView(version);
	}

	/** Returns the catalog snapshot currently visible to embedded readers. */
	@Nonnull
	private Catalog liveCatalog() {
		return (Catalog) this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
	}

	/** Commits one matching capture and waits until it is visible in the embedded engine. */
	private void commitProduct(int primaryKey) {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> { session.upsertEntity(session.createNewEntity(Entities.PRODUCT, primaryKey)); },
			CommitBehavior.WAIT_FOR_CHANGES_VISIBLE
		);
	}

	/**
	 * Deterministically refuses wake-ups, then executes accepted tasks on the test thread. Only the standalone
	 * observer uses this executor; engine commits and its internal observers keep their real executors.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	private static final class RefusingCaptureExecutor extends ImmediateExecutorService {
		/** Whether submissions are currently refused. */
		private boolean refusing = true;
		/** Number of wake-up submissions, including refusals. */
		private int submissions;

		/** Counts each wake-up and refuses it until the test releases the executor. */
		@Nonnull
		@Override
		public Future<?> submit(@Nonnull Runnable task) {
			this.submissions++;
			if (this.refusing) {
				throw new RejectedExecutionException("Capture wake-up refused by the test executor.");
			}
			return super.submit(task);
		}
	}
}
