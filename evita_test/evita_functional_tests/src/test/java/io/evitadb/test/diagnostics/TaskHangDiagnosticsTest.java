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

package io.evitadb.test.diagnostics;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.configuration.ThreadPoolOptions;
import io.evitadb.api.file.FileForFetch;
import io.evitadb.core.Evita;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static io.evitadb.test.TestTags.MANAGEMENT;
import static io.evitadb.test.TestTags.TEST_HARNESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@link TaskHangDiagnostics} turns a timed-out wait for a server task into a failure that names the
 * task's state and the occupant of the service thread - the only part of a hang on a remote CI runner that survives
 * into its check-run annotations.
 *
 * The hang is real, not simulated: the engine runs with a single service thread, as the long-running fixtures do, and
 * that thread is held by a task of the test's own while a backup is queued behind it.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Diagnostics of a server task that does not complete in time")
@Tag(TEST_HARNESS)
@Tag(MANAGEMENT)
class TaskHangDiagnosticsTest implements EvitaTestSupport {
	private static final String CATALOG = "taskHangDiagnosticsCatalog";
	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("TaskHangDiagnosticsTest");
		this.evita = new Evita(
			newTestEvitaConfigurationBuilder(this.paths)
				.server(
					ServerOptions.builder()
						.serviceThreadPool(
							ThreadPoolOptions.serviceThreadPoolBuilder()
								.minThreadCount(1)
								.maxThreadCount(1)
								.build()
						)
						.build()
				)
				.build(),
			true, null, null,
			// real pools - with the immediate executor the backup would run inline and could never be queued
			false
		);
		this.evita.defineCatalog(CATALOG);
		this.evita.updateCatalog(
			CATALOG,
			session -> {
				session.defineEntitySchema(Entities.BRAND);
				session.upsertEntity(session.createNewEntity(Entities.BRAND, 1));
			}
		);
		this.evita.updateCatalog(CATALOG, EvitaSessionContract::goLiveAndClose);
	}

	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	@Test
	@DisplayName("A backup queued behind a busy service thread fails with its state and the thread's occupant named")
	void shouldNameTaskStateAndServiceThreadOccupantWhenWaitTimesOut() throws Exception {
		final CountDownLatch serviceThreadReleased = new CountDownLatch(1);
		final CountDownLatch serviceThreadTaken = new CountDownLatch(1);
		this.evita.getServiceExecutor().execute(
			() -> {
				serviceThreadTaken.countDown();
				try {
					serviceThreadReleased.await();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
		);
		try {
			assertTrue(serviceThreadTaken.await(30, TimeUnit.SECONDS), "The service thread was never taken!");
			final CompletableFuture<FileForFetch> backup =
				this.evita.management().backupCatalog(CATALOG, null, null, false);

			final AssertionFailedError failure = assertThrows(
				AssertionFailedError.class,
				() -> TaskHangDiagnostics.awaitTaskResult(
					backup, 200, TimeUnit.MILLISECONDS, this.evita.management(), null, "BackupTask", CATALOG
				)
			);

			final String message = failure.getMessage();
			assertTrue(
				message.startsWith("BackupTask[" + CATALOG + "] did not complete within 200 MILLISECONDS"),
				message
			);
			assertTrue(message.contains("task QUEUED 0%"), message);
			// the occupant is named by the first engine frame on its stack - this test's own lambda
			assertTrue(message.contains("Evita-service-"), message);
			assertTrue(message.contains("WAITING on java.util.concurrent.CountDownLatch"), message);
			assertTrue(message.contains(TaskHangDiagnosticsTest.class.getName()), message);
			assertInstanceOf(TimeoutException.class, failure.getCause());
		} finally {
			serviceThreadReleased.countDown();
		}
	}

	@Test
	@DisplayName("A task that completes in time is returned unchanged")
	void shouldReturnResultWhenTaskCompletesInTime() throws Exception {
		final FileForFetch backup = TaskHangDiagnostics.awaitTaskResult(
			this.evita.management().backupCatalog(CATALOG, null, null, false),
			30, TimeUnit.SECONDS, this.evita.management(), null, "BackupTask", CATALOG
		);
		assertNotNull(backup);
		assertEquals("application/zip", backup.contentType());
	}

}
