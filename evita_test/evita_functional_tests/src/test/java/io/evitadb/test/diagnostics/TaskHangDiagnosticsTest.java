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
import io.evitadb.test.diagnostics.TaskHangDiagnostics.ServiceThreadStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.evitadb.test.TestTags.MANAGEMENT;
import static io.evitadb.test.TestTags.TEST_HARNESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Verifies that {@link TaskHangDiagnostics} turns a timed-out wait for a server task into a failure that names the
 * task's state and the occupant of the service thread - the only part of a hang on a remote CI runner that survives
 * into its check-run annotations.
 *
 * The hang is real, not simulated: the engine runs with a single service thread, as the long-running fixtures do, and
 * that thread is held by a task of the test's own while a backup is queued behind it. The occupants differ in the one
 * respect each test is about - one waits on a latch inside the JVM, one is blocked in a native call, one burns CPU.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Diagnostics of a server task that does not complete in time")
@Tag(TEST_HARNESS)
@Tag(MANAGEMENT)
class TaskHangDiagnosticsTest implements EvitaTestSupport {
	private static final String CATALOG = "taskHangDiagnosticsCatalog";
	/**
	 * Pause between the two samples - short, so the tests stay fast, yet long enough for a spinning thread to burn
	 * CPU time that is measurable on any clock granularity.
	 */
	private static final Duration SAMPLE_INTERVAL = Duration.ofMillis(500);
	/**
	 * Extracts the CPU time a thread consumed between the samples from its line of the summary.
	 */
	private static final Pattern CPU_TIME = Pattern.compile(" cpu=(\\d+) ms ");
	private TestPaths paths;
	private Evita evita;

	/**
	 * Returns the line of the summary that describes the service thread occupied by this test - the only one whose
	 * quoted frames reach into this class.
	 *
	 * @param message the failure message
	 * @return the line
	 */
	@Nonnull
	private static String occupantLine(@Nonnull String message) {
		return Arrays.stream(message.split("\n"))
			.filter(it -> it.contains("Evita-service-") && it.contains(TaskHangDiagnosticsTest.class.getName()))
			.findFirst()
			.orElseThrow(() -> new AssertionError("No summary line describes the occupant: " + message));
	}

	/**
	 * Returns the attached stack of the service thread occupied by this test, as taken at the timeout.
	 *
	 * @param failure the failure
	 * @return the attached stack
	 */
	@Nonnull
	private static ServiceThreadStack occupantStack(@Nonnull AssertionFailedError failure) {
		return Arrays.stream(failure.getSuppressed())
			.filter(ServiceThreadStack.class::isInstance)
			.map(ServiceThreadStack.class::cast)
			.filter(it -> it.getMessage().contains("sample 1 at the timeout"))
			.filter(
				it -> Arrays.stream(it.getStackTrace())
					.anyMatch(frame -> frame.getClassName().startsWith(TaskHangDiagnosticsTest.class.getName()))
			)
			.findFirst()
			.orElseThrow(
				() -> new AssertionError(
					"The occupant's stack is not attached: " + Arrays.toString(failure.getSuppressed())
				)
			);
	}

	/**
	 * Tells whether the given stack contains a frame of the given method.
	 *
	 * @param stack      the stack
	 * @param className  the class of the method
	 * @param methodName the method
	 * @return TRUE when such a frame is present
	 */
	private static boolean hasFrame(
		@Nonnull StackTraceElement[] stack,
		@Nonnull String className,
		@Nonnull String methodName
	) {
		return Arrays.stream(stack)
			.anyMatch(it -> className.equals(it.getClassName()) && methodName.equals(it.getMethodName()));
	}

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
					backup, 200, TimeUnit.MILLISECONDS, this.evita.management(), null, "BackupTask", CATALOG,
					SAMPLE_INTERVAL
				)
			);

			final String message = failure.getMessage();
			assertTrue(
				message.startsWith("BackupTask[" + CATALOG + "] did not complete within 200 MILLISECONDS"),
				message
			);
			assertTrue(message.contains("task QUEUED 0%"), message);
			// the occupant is named by the first evitaDB frame on its stack - this test's own lambda, not the engine's
			assertTrue(message.contains("Evita-service-"), message);
			assertTrue(message.contains("WAITING on java.util.concurrent.CountDownLatch"), message);
			assertTrue(message.contains(TaskHangDiagnosticsTest.class.getName()), message);
			assertInstanceOf(TimeoutException.class, failure.getCause());

			// the window between the two samples: progress, GC effort and the export directory
			assertTrue(message.contains("progress 0% -> 0%"), message);
			assertTrue(message.contains(" GC "), message);
			assertTrue(message.contains("usable space "), message);

			// the JDK frames above the first evitaDB frame are quoted, from the very top of the stack down
			final String occupant = occupantLine(message);
			assertTrue(occupant.contains("inNative=false"), occupant);
			assertTrue(occupant.contains("java.util.concurrent.CountDownLatch.await("), occupant);
			assertTrue(
				occupant.indexOf("java.util.concurrent.locks.LockSupport.park(") <
					occupant.indexOf("java.util.concurrent.CountDownLatch.await("),
				occupant
			);
			assertTrue(occupant.contains(" cpu="), occupant);
			assertTrue(occupant.contains(" stack unchanged at "), occupant);

			// the full stack travels in the failure itself, as a suppressed throwable
			final ServiceThreadStack stack = occupantStack(failure);
			assertTrue(stack.getMessage().contains("Evita-service-"), stack.getMessage());
			assertTrue(stack.getMessage().contains("WAITING"), stack.getMessage());
			assertTrue(stack.getMessage().contains("inNative=false"), stack.getMessage());
			assertTrue(
				hasFrame(stack.getStackTrace(), CountDownLatch.class.getName(), "await"),
				Arrays.toString(stack.getStackTrace())
			);

			// the queued backup was cancelled, so it no longer holds a place in the service queue
			assertTrue(message.contains("cancel accepted while the task was QUEUED"), message);
			assertTrue(backup.isDone(), "The backup was not cancelled!");
		} finally {
			serviceThreadReleased.countDown();
		}
	}

	@Test
	@DisplayName("A service thread blocked in a native call is reported as such")
	void shouldReportServiceThreadBlockedInNativeCall() throws Exception {
		final CountDownLatch serviceThreadTaken = new CountDownLatch(1);
		try (final ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			this.evita.getServiceExecutor().execute(
				() -> {
					serviceThreadTaken.countDown();
					try {
						// no client ever connects - the thread stays in the operating system's accept call
						socket.accept().close();
					} catch (IOException ignored) {
						// closing the socket is how the test releases the thread
					}
				}
			);
			assertTrue(serviceThreadTaken.await(30, TimeUnit.SECONDS), "The service thread was never taken!");
			final CompletableFuture<FileForFetch> backup =
				this.evita.management().backupCatalog(CATALOG, null, null, false);

			final AssertionFailedError failure = assertThrows(
				AssertionFailedError.class,
				() -> TaskHangDiagnostics.awaitTaskResult(
					backup, 200, TimeUnit.MILLISECONDS, this.evita.management(), null, "BackupTask", CATALOG,
					SAMPLE_INTERVAL
				)
			);

			final String occupant = occupantLine(failure.getMessage());
			assertTrue(occupant.contains("RUNNABLE"), occupant);
			assertTrue(occupant.contains("inNative=true"), occupant);
			assertTrue(occupant.contains("java.net.ServerSocket.accept("), occupant);

			final ServiceThreadStack stack = occupantStack(failure);
			assertTrue(stack.getMessage().contains("inNative=true"), stack.getMessage());
			assertTrue(stack.getStackTrace()[0].isNativeMethod(), Arrays.toString(stack.getStackTrace()));
		}
	}

	@Test
	@DisplayName("A service thread that keeps computing is reported with the CPU time it burned between the samples")
	void shouldReportCpuTimeOfSpinningServiceThread() throws Exception {
		final ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
		assumeTrue(
			threadBean.isThreadCpuTimeSupported() && threadBean.isThreadCpuTimeEnabled(),
			"The JVM does not measure per-thread CPU time."
		);
		final AtomicBoolean serviceThreadReleased = new AtomicBoolean();
		final AtomicLong computationSink = new AtomicLong();
		final CountDownLatch serviceThreadTaken = new CountDownLatch(1);
		this.evita.getServiceExecutor().execute(
			() -> {
				serviceThreadTaken.countDown();
				double value = 1.0;
				while (!serviceThreadReleased.get()) {
					value = Math.sqrt(value + 1.0);
				}
				// published, so that the loop cannot be optimized away
				computationSink.set(Double.doubleToLongBits(value));
			}
		);
		try {
			assertTrue(serviceThreadTaken.await(30, TimeUnit.SECONDS), "The service thread was never taken!");
			final CompletableFuture<FileForFetch> backup =
				this.evita.management().backupCatalog(CATALOG, null, null, false);

			final AssertionFailedError failure = assertThrows(
				AssertionFailedError.class,
				() -> TaskHangDiagnostics.awaitTaskResult(
					backup, 200, TimeUnit.MILLISECONDS, this.evita.management(), null, "BackupTask", CATALOG,
					SAMPLE_INTERVAL
				)
			);

			final String occupant = occupantLine(failure.getMessage());
			assertTrue(occupant.contains("inNative=false"), occupant);
			final Matcher cpuTime = CPU_TIME.matcher(occupant);
			assertTrue(cpuTime.find(), occupant);
			assertTrue(Long.parseLong(cpuTime.group(1)) > 0, occupant);
		} finally {
			serviceThreadReleased.set(true);
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
