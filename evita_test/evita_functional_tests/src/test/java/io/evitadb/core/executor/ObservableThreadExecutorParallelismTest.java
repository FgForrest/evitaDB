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

package io.evitadb.core.executor;

import io.evitadb.api.configuration.ThreadPoolOptions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for {@link ObservableThreadExecutor} parallelism resolution (issue #1204).
 *
 * The production {@link java.util.concurrent.ForkJoinPool} parallelism used to be clamped to
 * `min(minThreadCount, availableProcessors())`. Because tasks blocking on plain I/O (network,
 * gRPC/HTTP (de)serialization) or lock contention do **not** spawn ForkJoinPool compensation threads, that
 * parallelism level is the effective concurrency cap for such work — so inside cgroup-limited containers
 * where `availableProcessors()` reports ≈1 the pool was throttled to a single in-flight task and the
 * configured `maxThreadCount` became inert, overflowing the bounded queue and rejecting requests.
 *
 * The fix decouples parallelism from the live CPU count and honors the configured
 * {@link ThreadPoolOptions#maxThreadCount()} (overridable via the `-Devita.poolParallelism.&lt;name&gt;`
 * system property). These tests pin that contract: the pool must run **more** concurrent (I/O-blocking)
 * tasks than there are CPUs — on the buggy clamped code the peak concurrency could never exceed
 * `availableProcessors()`.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
class ObservableThreadExecutorParallelismTest {

	private static final int AVAILABLE_PROCESSORS = Runtime.getRuntime().availableProcessors();

	@Test
	void shouldRunMoreConcurrentBlockingTasksThanCpuCountHonoringMaxThreadCount() throws Exception {
		// configured concurrency ceiling deliberately above the CPU count — on the buggy (clamped) code the
		// pool would never run more than AVAILABLE_PROCESSORS tasks at once, regardless of this setting
		final int maxThreadCount = AVAILABLE_PROCESSORS + 8;
		final ObservableThreadExecutor executor = new ObservableThreadExecutor(
			"test-parallelism",
			// (minThreadCount, maxThreadCount, threadPriority, queueSize)
			new ThreadPoolOptions(1, maxThreadCount, Thread.NORM_PRIORITY, 10_000),
			false
		);
		try {
			final int peak = measurePeakConcurrency(executor, maxThreadCount, maxThreadCount);
			assertTrue(
				peak > AVAILABLE_PROCESSORS,
				"Pool must run more concurrent blocking tasks (" + peak + ") than CPUs (" +
					AVAILABLE_PROCESSORS + ") — parallelism must not be clamped to the CPU count"
			);
			assertEquals(
				maxThreadCount, peak,
				"Pool should run up to the configured maxThreadCount tasks concurrently"
			);
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void shouldHonorPoolParallelismSystemPropertyOverride() throws Exception {
		final int maxThreadCount = AVAILABLE_PROCESSORS + 8;
		// override deliberately BETWEEN the CPU count and maxThreadCount: proves the override is actually
		// applied (peak != maxThreadCount) while still decoupling concurrency from the CPU count (peak > CPUs)
		final int override = AVAILABLE_PROCESSORS + 4;
		final String poolName = "test-parallelism-override";
		final String propertyKey = "evita.poolParallelism." + poolName;
		System.setProperty(propertyKey, Integer.toString(override));
		try {
			final ObservableThreadExecutor executor = new ObservableThreadExecutor(
				poolName,
				new ThreadPoolOptions(1, maxThreadCount, Thread.NORM_PRIORITY, 10_000),
				false
			);
			try {
				// submit more tasks than the override allows; only `override` of them may run concurrently
				final int peak = measurePeakConcurrency(executor, maxThreadCount, override);
				assertTrue(
					peak > AVAILABLE_PROCESSORS,
					"Override must still raise concurrency above the CPU count; was " + peak
				);
				assertEquals(
					override, peak,
					"System-property override should cap concurrency at the requested parallelism level"
				);
			} finally {
				executor.shutdownNow();
			}
		} finally {
			System.clearProperty(propertyKey);
		}
	}

	/**
	 * Submits {@code tasksToSubmit} tasks that each block until released, waits until {@code expectedConcurrent}
	 * of them are simultaneously running, records the peak observed concurrency, then releases all tasks and
	 * drains them so the pool is idle before the caller shuts it down.
	 *
	 * Because every started task stays in the running set until it is released, the moment
	 * {@code expectedConcurrent} tasks have signalled start is exactly the moment that many run concurrently —
	 * giving a deterministic peak with no sampling race.
	 *
	 * @param executor           the executor under test
	 * @param tasksToSubmit      total number of blocking tasks to submit
	 * @param expectedConcurrent number of concurrently-running tasks to wait for before measuring the peak
	 * @return the peak number of tasks observed running concurrently
	 */
	private static int measurePeakConcurrency(
		ObservableThreadExecutor executor,
		int tasksToSubmit,
		int expectedConcurrent
	) throws InterruptedException {
		final AtomicInteger concurrent = new AtomicInteger();
		final AtomicInteger peak = new AtomicInteger();
		final CountDownLatch reachedExpected = new CountDownLatch(expectedConcurrent);
		final CountDownLatch release = new CountDownLatch(1);
		final List<CancellableRunnable> tasks = new ArrayList<>(tasksToSubmit);

		for (int i = 0; i < tasksToSubmit; i++) {
			final CancellableRunnable task = executor.createTask("blocking-" + i, () -> {
				final int running = concurrent.incrementAndGet();
				peak.accumulateAndGet(running, Math::max);
				reachedExpected.countDown();
				try {
					release.await(10, TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				} finally {
					concurrent.decrementAndGet();
				}
			});
			executor.execute(task);
			tasks.add(task);
		}

		final boolean reached = reachedExpected.await(10, TimeUnit.SECONDS);
		final int observedPeak = peak.get();
		release.countDown();
		// drain so the pool is idle before shutdown
		for (CancellableRunnable task : tasks) {
			try {
				task.completionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
			} catch (Exception e) {
				// ignore — only the peak concurrency matters for these assertions
			}
		}
		assertTrue(
			reached,
			"Expected " + expectedConcurrent + " tasks to run concurrently within the timeout, " +
				"but the pool never reached that level"
		);
		return observedPeak;
	}
}
