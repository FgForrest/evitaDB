/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2026
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

package io.evitadb.core.session.task;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.exception.CollectionNotFoundException;
import io.evitadb.api.exception.InstanceTerminatedException;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.QueryConstraints;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.core.Evita;
import io.evitadb.core.session.EvitaInternalSessionContract;
import io.evitadb.export.file.configuration.FileSystemExportOptions;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static graphql.Assert.assertFalse;
import static graphql.Assert.assertNotNull;
import static graphql.Assert.assertTrue;
import static io.evitadb.test.TestConstants.LONG_RUNNING_TEST;

/**
 * This test verifies the correct functionality of the {@link SessionKiller} class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@DisplayName("Session killer functionality")
@Tag(LONG_RUNNING_TEST)
class SessionKillerTest implements EvitaTestSupport {
	public static final String SUB_DIRECTORY = "SessionKillerTest";
	private Evita evita;
	private SessionKiller sessionKiller;

	@BeforeEach
	void setUp() throws IOException, NoSuchFieldException, IllegalAccessException {
		cleanTestSubDirectory(SUB_DIRECTORY);
		this.evita = new Evita(
			EvitaConfiguration.builder()
				.storage(
					StorageOptions.builder()
						.storageDirectory(getTestDirectory().resolve(SUB_DIRECTORY))
						.build()
				)
				.export(FileSystemExportOptions.temporary())
				.server(
					ServerOptions.builder()
						.closeSessionsAfterSecondsOfInactivity(1)
						.build()
				)
				.build()
		);
		final Field sessionKillerField = Evita.class.getDeclaredField("sessionKiller");
		sessionKillerField.setAccessible(true);
		this.sessionKiller = (SessionKiller) sessionKillerField.get(this.evita);
	}

	@AfterEach
	void tearDown() throws IOException {
		this.evita.close();
		cleanTestSubDirectory(SUB_DIRECTORY);
	}

	@Test
	void shouldKillSessionAfterIntervalOfInactivity() throws InterruptedException {
		this.evita.defineCatalog("test");
		final EvitaSessionContract session = this.evita.createReadOnlySession("test");
		synchronized (this.evita) {
			this.evita.wait(2000);
		}
		this.sessionKiller.run();
		assertFalse(session.isActive());
	}

	@Test
	void shouldNotKillSessionWhenThereAreInvocations() {
		this.evita.defineCatalog("test");
		final EvitaSessionContract session = this.evita.createReadOnlySession("test");
		final long start = System.currentTimeMillis();
		while (System.currentTimeMillis() - start < 2000) {
			assertNotNull(session.getCatalogName());
			Thread.onSpinWait();
		}
		this.sessionKiller.run();
		assertTrue(session.isActive());
	}

	@Test
	void shouldNotKillSessionWhenThereIsLongLastingInvocationCallActive() throws InterruptedException {
		final AtomicBoolean finishMethodCall = new AtomicBoolean(false);
		try {
			this.evita.defineCatalog("test");
			final EvitaSessionContract session = this.evita.createReadOnlySession("test");
			final Runnable asyncCall = () -> {
				final Query query = Mockito.mock(Query.class);
				Mockito.when(query.normalizeQuery()).thenAnswer(invocation -> {
					do {
						Thread.onSpinWait();
					} while (!finishMethodCall.get());
					return Query.query(QueryConstraints.collection("unknownEntity"));
				});
				try {
					session.query(
						query, EntityReference.class
					);
				} catch (CollectionNotFoundException e) {
					// expected
					System.out.println("Async call finished");
				}
			};
			final CompletableFuture<Void> future = CompletableFuture.runAsync(asyncCall);
			synchronized (this.evita) {
				this.evita.wait(2000);
			}

			this.sessionKiller.run();

			System.out.println("Finishing async call");
			finishMethodCall.set(true);
			future.join();

			assertTrue(session.isActive());

			System.out.println("Waiting for session killer to finish");
			synchronized (this.evita) {
				this.evita.wait(2000);
			}

			this.sessionKiller.run();
			assertFalse(session.isActive());
		} finally {
			finishMethodCall.set(true);
		}
	}

	@Test
	void shouldNotKillSessionWhenMethodCompletesJustBeforeTermination() throws InterruptedException {
		// This test verifies that the atomic check prevents race conditions where:
		// 1. Session appears inactive (method started long ago)
		// 2. Method completes and updates lastCall timestamp
		// 3. Session killer checks methodIsRunning (returns false)
		// 4. Session would be incorrectly terminated despite having recent activity
		//
		// The key verification is that immediately after a method completes,
		// isInactiveAndIdle returns false because lastCall was just updated.
		final AtomicBoolean finishMethodCall = new AtomicBoolean(false);
		final AtomicBoolean sessionWasUnexpectedlyKilled = new AtomicBoolean(false);
		final CountDownLatch methodStarted = new CountDownLatch(1);
		final CountDownLatch readyToComplete = new CountDownLatch(1);
		try {
			this.evita.defineCatalog("test");
			final EvitaSessionContract session = this.evita.createReadOnlySession("test");

			// Verify the session implements the internal contract with atomic check
			assertTrue(session instanceof EvitaInternalSessionContract);
			final EvitaInternalSessionContract internalSession = (EvitaInternalSessionContract) session;

			final Runnable asyncCall = () -> {
				final Query query = Mockito.mock(Query.class);
				Mockito.when(query.normalizeQuery()).thenAnswer(invocation -> {
					methodStarted.countDown();
					// Wait until the test is ready for the method to complete
					try {
						readyToComplete.await(10, TimeUnit.SECONDS);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
					do {
						Thread.onSpinWait();
					} while (!finishMethodCall.get());
					return Query.query(QueryConstraints.collection("unknownEntity"));
				});
				try {
					session.query(query, EntityReference.class);
				} catch (CollectionNotFoundException e) {
					// expected - the query references an unknown entity
				} catch (InstanceTerminatedException e) {
					// session was killed while method was running - record this for assertion
					sessionWasUnexpectedlyKilled.set(true);
				}
			};

			final CompletableFuture<Void> future = CompletableFuture.runAsync(asyncCall);

			try {
				// Wait for the method to start
				assertTrue(methodStarted.await(5, TimeUnit.SECONDS));

				// Wait enough time for session to appear "old" based on lastCall timestamp
				synchronized (this.evita) {
					this.evita.wait(2000);
				}

				// At this point, the session's lastCall was set when the method started (2+ seconds ago)
				// The atomic check should correctly identify that a method is still running
				assertFalse(internalSession.isInactiveAndIdle(1L));

				// Now allow the method to complete - this will update lastCall to current time
				readyToComplete.countDown();
				finishMethodCall.set(true);
				future.join();
			} finally {
				future.cancel(true);
			}

			// Check if the session was killed while the method was still running
			assertFalse(
				sessionWasUnexpectedlyKilled.get(),
				"Session was unexpectedly killed while a method was still running. " +
					"The SessionKiller should not terminate sessions with active method calls. " +
					"This indicates the isInactiveAndIdle filter is not working correctly."
			);

			// KEY ASSERTION: Immediately after method completion, the lastCall was just updated,
			// so session should NOT be considered inactive even though no method is currently running.
			// This is the core of the race condition fix - we check both conditions atomically.
			assertFalse(internalSession.isInactiveAndIdle(1L));

			// Run SessionKiller - it should NOT kill the session because lastCall was just updated
			this.sessionKiller.run();
			try {
				assertTrue(
					session.isActive(),
					"Session was unexpectedly killed by SessionKiller despite having recent activity " +
						"(lastCall was just updated after method completion). " +
						"This indicates a race condition in the inactivity check."
				);
			} catch (InstanceTerminatedException e) {
				throw new AssertionError(
					"Session was unexpectedly terminated by SessionKiller despite having recent activity " +
						"(lastCall was just updated after method completion). " +
						"The atomic check in isInactiveAndIdle should have prevented this. " +
						"This indicates a race condition in the inactivity check.",
					e
				);
			}
		} finally {
			finishMethodCall.set(true);
			readyToComplete.countDown();
		}
	}
}