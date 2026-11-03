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
import io.evitadb.api.exception.InstanceTerminatedException;
import io.evitadb.api.requestResponse.cdc.ChangeCaptureContent;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCaptureRequest;
import io.evitadb.core.Evita;
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
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static io.evitadb.test.TestTags.CDC;
import static io.evitadb.test.TestTags.ENGINE;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Verifies that a shared change capture publisher created while its {@link CatalogChangeObserver} is being closed is
 * either refused or closed together with the others - never left behind, detached and open.
 *
 * The failure this exists for: `close()` closed the shared publishers it found in its map and then cleared the map,
 * while a subscription being made at the same time was still creating its shared publisher inside the map's
 * `compute`. The sweep did not see that publisher, the clear removed it, and the subscription was activated on a
 * publisher nothing would ever feed or close again - a consumer left waiting silently for a catalog that was gone.
 *
 * The interleaving is reached through the real deactivation of a real catalog. The subscribing thread is held at the
 * one point inside the creation that runs code a test can reach without a seam - its log statement - until the
 * deactivating thread is parked inside `close()`.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Catalog change observer closed while a shared publisher is being created")
@Tag(ENGINE)
@Tag(CDC)
class CatalogChangeObserverCloseRaceTest implements EvitaTestSupport {
	/**
	 * Upper bound of every wait for a signal. A positive wait returns as soon as the signal arrives.
	 */
	private static final long AWAIT_TIMEOUT_SECONDS = 30L;
	/**
	 * Start of the message the observer logs right before it creates a shared publisher.
	 */
	private static final String CREATION_MESSAGE = "Creating new shared CDC publisher";

	private TestPaths paths;
	private Evita evita;

	/**
	 * Tells whether any thread is parked or blocked inside {@link CatalogChangeObserver#close()}. The deactivation
	 * closes the catalog on an engine thread rather than on the thread that requested it, so every thread is looked at.
	 *
	 * @return true when some thread waits somewhere below the observer's close
	 */
	private static boolean isAnyThreadWaitingInObserverClose() {
		for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
			final Thread.State state = entry.getKey().getState();
			if (state != Thread.State.BLOCKED && state != Thread.State.WAITING) {
				continue;
			}
			for (StackTraceElement frame : entry.getValue()) {
				if (CatalogChangeObserver.class.getName().equals(frame.getClassName()) &&
					"close".equals(frame.getMethodName())) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Describes the current stack of the thread, one frame per line.
	 *
	 * @param thread the thread to describe
	 * @return the frames of the thread
	 */
	@Nonnull
	private static String describeStack(@Nonnull Thread thread) {
		final StackTraceElement[] frames = thread.getStackTrace();
		final StringBuilder description = new StringBuilder(frames.length * 96);
		for (StackTraceElement frame : frames) {
			description.append("\tat ").append(frame).append('\n');
		}
		return description.toString();
	}

	@BeforeEach
	void setUp() throws IOException {
		this.paths = createTestPaths(CatalogChangeObserverCloseRaceTest.class.getSimpleName());
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

	@Test
	@DisplayName("should not leave a subscription live on a shared publisher created during the catalog deactivation")
	void shouldNotLeaveSubscriptionLiveOnSharedPublisherCreatedDuringDeactivation() throws Exception {
		// the registration creates no shared publisher yet - the subscription below does, on its own thread
		final ChangeCatalogCapturePublisher publisher = (ChangeCatalogCapturePublisher) this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.registerChangeCatalogCapture(
					new ChangeCatalogCaptureRequest(null, null, null, ChangeCaptureContent.BODY)
				);
			}
		);

		final CountDownLatch creating = new CountDownLatch(1);
		final CountDownLatch release = new CountDownLatch(1);
		final AtomicReference<Subscription> subscription = new AtomicReference<>();
		final AtomicReference<Throwable> subscribeFailure = new AtomicReference<>();
		final AtomicReference<Throwable> deactivationFailure = new AtomicReference<>();
		final Thread subscribing = new Thread(
			() -> {
				try {
					publisher.subscribe(new CapturingSubscriber(subscription));
				} catch (Throwable ex) {
					subscribeFailure.set(ex);
				}
			},
			"cdc-subscribing-during-close"
		);
		final Thread deactivating = new Thread(
			() -> {
				try {
					this.evita.deactivateCatalog(TEST_CATALOG);
				} catch (Throwable ex) {
					deactivationFailure.set(ex);
				}
			},
			"cdc-deactivating-during-creation"
		);
		// holds the subscribing thread inside the shared publisher creation - it has passed the observer's
		// `active` check and has not put the publisher into the map yet
		final UnsynchronizedAppenderBase<ILoggingEvent> pause = new UnsynchronizedAppenderBase<>() {
			@Override
			protected void append(ILoggingEvent event) {
				if (Thread.currentThread() == subscribing && event.getFormattedMessage().startsWith(CREATION_MESSAGE)) {
					creating.countDown();
					try {
						release.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
					} catch (InterruptedException ex) {
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
			subscribing.start();
			assertTrue(
				creating.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
				"The subscription never created a shared publisher - the race this test exists for was not reached."
			);

			deactivating.start();
			final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_TIMEOUT_SECONDS);
			while (!isAnyThreadWaitingInObserverClose()) {
				if (System.nanoTime() - deadline >= 0) {
					fail(
						"The deactivation never reached the change observer's close while the creation was held - " +
							"the requesting thread is " + deactivating.getState() + " at:\n" + describeStack(deactivating)
					);
				}
				LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
			}
		} finally {
			release.countDown();
			subscribing.join(TimeUnit.SECONDS.toMillis(AWAIT_TIMEOUT_SECONDS));
			deactivating.join(TimeUnit.SECONDS.toMillis(AWAIT_TIMEOUT_SECONDS));
			observerLogger.detachAppender(pause);
			pause.stop();
		}

		assertFalse(subscribing.isAlive(), "The subscription never returned.");
		assertFalse(deactivating.isAlive(), "The deactivation never returned.");
		assertNull(deactivationFailure.get(), "The deactivation failed.");
		final Throwable failure = subscribeFailure.get();
		if (failure != null) {
			assertInstanceOf(
				InstanceTerminatedException.class, failure,
				"The subscription was refused, but not because the observer was closed."
			);
		} else {
			final DefaultChangeCaptureSubscription<?> activated = assertInstanceOf(
				DefaultChangeCaptureSubscription.class, subscription.get(),
				"The subscription neither failed nor was activated."
			);
			assertTrue(
				activated.isFinished(),
				"The subscription made while the catalog was deactivated stays live on a shared publisher that " +
					"nothing will ever feed or close."
			);
		}
	}

	/**
	 * Subscriber that keeps the subscription it is handed and requests everything.
	 */
	private static final class CapturingSubscriber implements Subscriber<ChangeCatalogCapture> {
		/**
		 * Holder of the subscription this subscriber was activated with.
		 */
		private final AtomicReference<Subscription> subscription;

		/**
		 * Creates a subscriber that stores its subscription into the passed holder.
		 *
		 * @param subscription the holder of the subscription
		 */
		CapturingSubscriber(@Nonnull AtomicReference<Subscription> subscription) {
			this.subscription = subscription;
		}

		@Override
		public void onSubscribe(@Nonnull Subscription subscription) {
			assertNotNull(subscription);
			this.subscription.set(subscription);
			subscription.request(Long.MAX_VALUE);
		}

		@Override
		public void onNext(@Nonnull ChangeCatalogCapture item) {
			// nothing is committed, so nothing is delivered
		}

		@Override
		public void onError(@Nonnull Throwable throwable) {
			// the outcome is read from the subscription itself
		}

		@Override
		public void onComplete() {
			// the outcome is read from the subscription itself
		}

	}

}
