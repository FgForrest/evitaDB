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

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import io.evitadb.api.TransactionContract.CommitBehavior;
import io.evitadb.api.requestResponse.cdc.ChangeCaptureContent;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCaptureCriteria;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCaptureRequest;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.executor.ImmediateExecutorService;
import io.evitadb.dataType.ContainerType;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.test.TestTags.CDC;
import static io.evitadb.test.TestTags.ENGINE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards against a lost wake-up when a catalog version is published during the creation of a shared CDC publisher.
 * The new publisher must deliver the capture already in the write-ahead log without waiting for another commit.
 *
 * Like {@link CatalogChangeObserverCloseRaceTest}, this test parks creation at the observer's log statement.
 * That statement runs inside the map's compute, after reading the catalog snapshot and before making the new
 * publisher visible. A publication in this window updates the observer but cannot notify the new publisher - and a
 * rename published there leaves the publisher on a catalog whose storage is already closed.
 * Direct tests verify that catch-up wakes an existing subscriber and never moves the catalog backwards, even
 * between different instances of the same version.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Catalog change observer publication during shared publisher creation")
@Tag(ENGINE)
@Tag(CDC)
class CatalogChangeObserverPublicationRaceTest implements EvitaTestSupport {

	/** Upper bound for positive waits; successful signals return immediately. */
	private static final long AWAIT_TIMEOUT_SECONDS = 30L;
	/** The existing log seam between reading the catalog and inserting the publisher into the map. */
	private static final String CREATION_MESSAGE = "Creating new shared CDC publisher";
	/** Name the catalog takes when it is renamed during shared publisher creation. */
	private static final String RENAMED_CATALOG = "renamedCatalog";

	/** Storage paths isolated to this test. */
	private TestPaths paths;
	/** Embedded instance used to publish a commit during shared publisher creation. */
	private Evita evita;

	@BeforeEach
	void setUp() throws IOException {
		this.paths = createTestPaths(CatalogChangeObserverPublicationRaceTest.class.getSimpleName());
		Files.createDirectories(this.paths.storage());
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		this.evita.waitUntilFullyInitialized();
		this.evita.defineCatalog(TEST_CATALOG);
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.PRODUCT)
					.withoutGeneratedPrimaryKey()
					.updateVia(session);
				session.goLiveAndClose();
			}
		);
	}

	@AfterEach
	void tearDown() {
		if (this.evita != null && this.evita.isActive()) {
			this.evita.close();
		}
		cleanupTestPaths(this.paths);
	}

	/**
	 * Publishes exactly one matching transaction while creation holds the old catalog snapshot and the map still
	 * hides the publisher. The subscriber starts at that transaction and must receive it without another commit.
	 */
	@Test
	@DisplayName("should deliver the version published while creation is parked without another commit")
	void shouldReceiveCaptureWhenCatalogIsPublishedDuringSharedPublisherCreation() throws Exception {
		final long expectedVersion = liveCatalog().getVersion() + 1;
		final ChangeCatalogCapturePublisher publisher = registerProductCapture(expectedVersion);

		final ChangeCatalogCapture capture = receiveFirstCaptureSubscribedDuringCreation(
			publisher,
			() -> {
				commitProduct();
				assertEquals(expectedVersion, liveCatalog().getVersion());
			}
		);

		assertEquals(
			expectedVersion, capture.version(), "The capture must belong to the version published while parked."
		);
	}

	/**
	 * Renames the catalog while creation holds the pre-rename snapshot and the map still hides the publisher. The
	 * rename keeps the catalog version but closes the storage behind that snapshot, so the publisher must replay the
	 * already committed transaction through the renamed catalog instead.
	 */
	@Test
	@DisplayName("should replay through the renamed catalog when the rename is published while creation is parked")
	void shouldReplayThroughRenamedCatalogWhenRenameIsPublishedDuringSharedPublisherCreation() throws Exception {
		commitProduct();
		final long replayVersion = liveCatalog().getVersion();
		final ChangeCatalogCapturePublisher publisher = registerProductCapture(replayVersion);

		final ChangeCatalogCapture capture = receiveFirstCaptureSubscribedDuringCreation(
			publisher,
			() -> {
				this.evita.renameCatalog(TEST_CATALOG, RENAMED_CATALOG);
				assertEquals(
					replayVersion, this.evita.getCatalogInstance(RENAMED_CATALOG).orElseThrow().getVersion(),
					"The rename must keep the catalog version - otherwise this test exercises an ordinary publication."
				);
			}
		);

		assertEquals(
			replayVersion, capture.version(), "The capture must replay the transaction committed before the rename."
		);
	}

	/**
	 * Registers a facade for product entity captures starting at the given version. Registration alone is lazy: the
	 * first subscription creates the shared publisher for these criteria.
	 */
	@Nonnull
	private ChangeCatalogCapturePublisher registerProductCapture(long sinceVersion) {
		return (ChangeCatalogCapturePublisher) this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.registerChangeCatalogCapture(
					ChangeCatalogCaptureRequest.builder()
						.sinceVersion(sinceVersion)
						.content(ChangeCaptureContent.BODY)
						.criteria(productCriteria())
						.build()
				);
			}
		);
	}

	/**
	 * Subscribes on a separate thread and parks it inside shared publisher creation while `duringCreation` runs on
	 * this thread, then releases it and waits for the first capture. Nothing commits after `duringCreation`, so the
	 * capture must arrive without another commit waking the publisher.
	 *
	 * @param publisher      facade whose subscription creates the first shared publisher for its criteria
	 * @param duringCreation catalog operation published while creation holds its catalog snapshot
	 * @return the first capture delivered to the subscriber
	 */
	@Nonnull
	private ChangeCatalogCapture receiveFirstCaptureSubscribedDuringCreation(
		@Nonnull ChangeCatalogCapturePublisher publisher,
		@Nonnull Runnable duringCreation
	) throws InterruptedException {
		final CountDownLatch creating = new CountDownLatch(1);
		final CountDownLatch release = new CountDownLatch(1);
		final CountDownLatch received = new CountDownLatch(1);
		final AtomicReference<Subscription> subscription = new AtomicReference<>();
		final AtomicReference<ChangeCatalogCapture> capture = new AtomicReference<>();
		final AtomicReference<Throwable> failure = new AtomicReference<>();
		final Thread subscribing = new Thread(
			() -> {
				try {
					publisher.subscribe(new Subscriber<ChangeCatalogCapture>() {
						@Override
						public void onSubscribe(@Nonnull Subscription activeSubscription) {
							subscription.set(activeSubscription);
							activeSubscription.request(1);
						}

						@Override
						public void onNext(@Nonnull ChangeCatalogCapture item) {
							capture.set(item);
							received.countDown();
						}

						@Override
						public void onError(@Nonnull Throwable throwable) {
							failure.compareAndSet(null, throwable);
						}

						@Override
						public void onComplete() {
							// Completion cannot satisfy the capture latch.
						}
					});
				} catch (Throwable ex) {
					failure.compareAndSet(null, ex);
				}
			},
			"cdc-subscribing-during-publication"
		);
		subscribing.setDaemon(true);
		final UnsynchronizedAppenderBase<ILoggingEvent> pause = new UnsynchronizedAppenderBase<>() {
			@Override
			protected void append(ILoggingEvent event) {
				if (Thread.currentThread() == subscribing && event.getFormattedMessage().startsWith(CREATION_MESSAGE)) {
					creating.countDown();
					try {
						if (!release.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
							failure.compareAndSet(
								null, new AssertionError("Publisher creation was not released in time.")
							);
						}
					} catch (InterruptedException ex) {
						failure.compareAndSet(null, ex);
						Thread.currentThread().interrupt();
					}
				}
			}
		};
		final Logger observerLogger = assertInstanceOf(
			Logger.class, LoggerFactory.getLogger(CatalogChangeObserver.class),
			"Logback backend required to hold the subscribing thread."
		);
		pause.start();
		observerLogger.addAppender(pause);
		try {
			try {
				subscribing.start();
				assertTrue(
					creating.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
					"The subscription never parked inside shared publisher creation - " +
						"the publication race was not reached."
				);
				duringCreation.run();
			} finally {
				release.countDown();
				subscribing.join(TimeUnit.SECONDS.toMillis(AWAIT_TIMEOUT_SECONDS));
			}
			assertFalse(subscribing.isAlive(), "The subscription never returned.");
			assertNull(failure.get(), "Publisher creation or subscription failed.");
			assertNotNull(subscription.get(), "The subscriber was not activated.");
			final boolean arrived = received.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
			assertNull(failure.get(), "The subscriber failed while waiting for the capture.");
			assertTrue(
				arrived,
				"The capture owed for the operation published while shared publisher creation was parked did not " +
					"arrive within " + AWAIT_TIMEOUT_SECONDS + " seconds without another commit."
			);
			return capture.get();
		} finally {
			observerLogger.detachAppender(pause);
			pause.stop();
			final Subscription activeSubscription = subscription.get();
			if (activeSubscription != null) {
				activeSubscription.cancel();
			}
		}
	}

	/**
	 * Keeps a subscriber waiting on a stale catalog, then catches up to the committed version. The waiting subscriber
	 * must receive the capture without another commit.
	 */
	@Test
	@DisplayName("should wake a waiting subscriber on catch-up")
	void shouldWakeWaitingSubscriberOnCatchUp() {
		final Catalog stale = liveCatalog();
		final long expectedVersion = stale.getVersion() + 1;
		commitProduct();
		final Catalog fresh = liveCatalog();
		assertEquals(expectedVersion, fresh.getVersion());
		final ChangeCatalogCaptureSharedPublisher publisher = createProductPublisher(stale);
		try {
			final AtomicReference<ChangeCatalogCapture> capture = new AtomicReference<>();
			final AtomicReference<Throwable> failure = new AtomicReference<>();
			publisher.subscribe(
				new Subscriber<ChangeCatalogCapture>() {
					@Override
					public void onSubscribe(@Nonnull Subscription subscription) {
						subscription.request(1);
					}

					@Override
					public void onNext(@Nonnull ChangeCatalogCapture item) {
						capture.set(item);
					}

					@Override
					public void onError(@Nonnull Throwable throwable) {
						failure.set(throwable);
					}

					@Override
					public void onComplete() {
						// Completion cannot supply the requested capture.
					}
				},
				new WalPointerWithContent(expectedVersion, 0, ChangeCaptureContent.BODY)
			);
			assertNull(failure.get(), "The subscriber must wait without failing.");
			assertNull(capture.get(), "The stale catalog must bound the initial WAL read.");

			publisher.catchUpWith(stale, fresh);
			assertNull(failure.get(), "The subscriber must catch up without failing.");
			assertNotNull(capture.get(), "Catch-up must deliver the waiting capture without another commit.");
			assertEquals(expectedVersion, capture.get().version());
			assertEquals(Entities.PRODUCT, capture.get().entityType());
			assertEquals(1, capture.get().entityPrimaryKey());
			assertNotNull(capture.get().body());
		} finally {
			publisher.close();
		}
	}

	/**
	 * Catches up to the committed version, then offers the creation catalog again, as a delayed catch-up on another
	 * thread would. The publisher must keep the newer catalog.
	 */
	@Test
	@DisplayName("should never move the catalog backwards once caught up")
	void shouldNeverMoveCatalogBackwardsOnceCaughtUp() {
		final Catalog stale = liveCatalog();
		commitProduct();
		final Catalog fresh = liveCatalog();
		final ChangeCatalogCaptureSharedPublisher publisher = createProductPublisher(stale);
		try {
			publisher.catchUpWith(stale, fresh);
			assertSame(fresh, publisher.getCatalog(), "Catch-up must adopt the catalog published since creation.");

			publisher.catchUpWith(stale, stale);
			assertSame(
				fresh, publisher.getCatalog(),
				"Catch-up with a stale catalog must never move the publisher backwards."
			);
		} finally {
			publisher.close();
		}
	}

	/**
	 * Catches up across two renames that keep the same version, then offers the intermediate catalog as a delayed
	 * catch-up would. Only the creation instance permits same-version adoption; afterwards the latest must stay held.
	 */
	@Test
	@DisplayName("should keep the latest same-version catalog once caught up")
	void shouldKeepLatestSameVersionCatalogOnceCaughtUp() {
		final Catalog createdWith = liveCatalog();
		this.evita.renameCatalog(TEST_CATALOG, RENAMED_CATALOG);
		final Catalog firstRename = liveCatalog(RENAMED_CATALOG);
		final String renamedAgainCatalog = "renamedAgainCatalog";
		this.evita.renameCatalog(RENAMED_CATALOG, renamedAgainCatalog);
		final Catalog secondRename = liveCatalog(renamedAgainCatalog);
		assertEquals(
			createdWith.getVersion(), firstRename.getVersion(),
			"The first rename must keep the catalog version - otherwise this test does not exercise identity."
		);
		assertEquals(
			createdWith.getVersion(), secondRename.getVersion(),
			"The second rename must keep the catalog version - otherwise this test does not exercise identity."
		);
		final ChangeCatalogCaptureSharedPublisher publisher = createProductPublisher(createdWith);
		try {
			publisher.catchUpWith(createdWith, secondRename);
			assertSame(
				secondRename, publisher.getCatalog(),
				"Catch-up must adopt the latest same-version catalog published since creation."
			);

			publisher.catchUpWith(createdWith, firstRename);
			assertSame(
				secondRename, publisher.getCatalog(),
				"Delayed catch-up with an earlier same-version catalog must keep the latest instance."
			);
		} finally {
			publisher.close();
		}
	}

	/**
	 * Returns the catalog instance currently present in the live view.
	 */
	@Nonnull
	private Catalog liveCatalog() {
		return liveCatalog(TEST_CATALOG);
	}

	/**
	 * Returns the named catalog instance currently present in the live view.
	 */
	@Nonnull
	private Catalog liveCatalog(@Nonnull String catalogName) {
		return (Catalog) this.evita.getCatalogInstance(catalogName).orElseThrow();
	}

	/**
	 * Commits one matching product and returns once the new catalog version is visible.
	 */
	private void commitProduct() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.upsertEntity(session.createNewEntity(Entities.PRODUCT, 1));
			},
			CommitBehavior.WAIT_FOR_CHANGES_VISIBLE
		);
	}

	/**
	 * Creates a shared publisher for product captures outside the observer, delivering on the calling thread.
	 */
	@Nonnull
	private static ChangeCatalogCaptureSharedPublisher createProductPublisher(@Nonnull Catalog createdWith) {
		return new ChangeCatalogCaptureSharedPublisher(
			createdWith,
			new ImmediateExecutorService(),
			16,
			16,
			new ChangeCatalogCriteriaBundle(new ChangeCatalogCaptureCriteria[] {productCriteria()}),
			capture -> {
			},
			closingPublisher -> {
			}
		);
	}

	/**
	 * Returns criteria matching product entity captures, the only entity this test commits.
	 */
	@Nonnull
	private static ChangeCatalogCaptureCriteria productCriteria() {
		return ChangeCatalogCaptureCriteria.builder()
			.dataArea(site -> site.entityType(Entities.PRODUCT).containerType(ContainerType.ENTITY))
			.build();
	}

}
