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

import io.evitadb.api.EvitaManagementContract;
import io.evitadb.api.task.TaskStatus;
import io.evitadb.api.task.TaskStatus.TaskSimplifiedState;
import io.evitadb.core.management.EvitaManagement;
import io.evitadb.export.file.ExportFileService;
import org.opentest4j.AssertionFailedError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.Serial;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Waits for the result of a server task and, when the wait times out, explains **why** before failing the test.
 *
 * Built for hangs that occur only on CI runners whose console output nobody can read afterwards - the gRPC backup
 * that never completed on the Windows long-running runner is the case it was written for. A bare
 * {@link TimeoutException} says only that nothing happened; it cannot tell a task stuck in the queue behind a busy
 * service thread from a task blocked on a lock, or from a task that finished while its completion never reached the
 * client. This class captures the evidence that separates those cases at the moment of the timeout:
 *
 * - the server-side status of the awaited task (state, progress, timestamps, failure),
 * - every task the server currently knows about, which names whatever competes for the service pool,
 * - optionally the same task as seen through a client - the view the driver's status poller gets,
 * - a full thread dump, with lock owners, of the engine pools, the Armeria event loops and the driver's threads.
 *
 * **Two samples, because one cannot tell stuck from slow.** A single stack says where a thread is, not whether it
 * moves. Every busy service thread is therefore sampled twice, {@link #DEFAULT_SAMPLE_INTERVAL} apart unless the
 * caller says otherwise, and the pair is reduced to the facts that separate the suspects: whether the thread sits in
 * native code ({@link ThreadInfo#isInNative()}), how much CPU it burned in between, whether its stack changed, and how
 * far the awaited task's progress moved. Total GC time over the same window rules a stop-the-world storm in or out,
 * and the size of the newest export archives with the free space of their file store shows whether bytes still reach
 * the disk. A thread in native code that burns no CPU while the archive does not grow is blocked in the operating
 * system; one that burns a full core in `Deflater` is compressing; one that burns nothing outside native code waits
 * for something in the JVM.
 *
 * **Everything that decides the case travels in the failure itself.** The only output of a CI runner that reaches a
 * reader is the check-run annotation built from the JUnit report - the failure message and the printed stack trace,
 * including its `Suppressed:` blocks. The failure message therefore carries a summary with the top frames of every
 * busy service thread down to the first evitaDB frame, and the full stack of each of them is attached to the failure
 * as a suppressed {@link ServiceThreadStack}. The complete evidence still goes to this class's logger at DEBUG level,
 * so it lands in the failing test's `system-out` in the surefire XML report without raising the verbosity of
 * anything else. Enabling that DEBUG level is the job of the logback configuration the test module runs with - no
 * other logger may be lowered for it.
 *
 * **The hung task is cancelled once it is diagnosed.** A stuck task holds the single service thread the long-running
 * fixtures configure, so every later task of the same test class would time out behind it. Whether the cancellation
 * actually released the worker is evidence too: an interrupt frees a thread waiting in the JVM, but not one blocked
 * in an operating system call.
 *
 * **The server is always asked directly, never through the path under suspicion.** The management contract passed as
 * `serverManagement` must be the embedded engine's own, so that a stuck gRPC channel cannot also hang the diagnostic.
 * The client view is optional and bounded by {@link #CLIENT_QUERY_TIMEOUT_SECONDS}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class TaskHangDiagnostics {
	/**
	 * Default pause between the two samples of the busy service threads - long enough for a thread that merely
	 * writes slowly to show progress and burn measurable CPU, short enough not to prolong a failing test noticeably.
	 */
	public static final Duration DEFAULT_SAMPLE_INTERVAL = Duration.ofSeconds(5);
	private static final Logger log = LoggerFactory.getLogger(TaskHangDiagnostics.class);
	/**
	 * Upper bound on the query made through the client, which travels the very channel that may be stuck.
	 */
	private static final int CLIENT_QUERY_TIMEOUT_SECONDS = 10;
	/**
	 * Number of task statuses requested from the server - far more than a test ever creates, so the page is complete.
	 */
	private static final int TASK_PAGE_SIZE = 500;
	/**
	 * Prefixes of the threads worth dumping: the engine's service, request and transaction pools (`Evita-service-`,
	 * `Evita-request-`, `Evita-transaction-`), Armeria's event loops shared by the server and the driver
	 * (`armeria-`), and the driver's own threads including its task status poller (`evita-client-`).
	 */
	private static final List<String> RELEVANT_THREAD_PREFIXES = List.of("Evita-", "armeria-", "evita-client-");
	/**
	 * Prefix of the engine's service pool threads - the single thread the long-running fixtures configure runs every
	 * server task and every periodic maintenance job, so its occupant is the first suspect of any queued task.
	 */
	private static final String SERVICE_THREAD_PREFIX = "Evita-service-";
	/**
	 * Root package of the project's own classes - engine, driver, external APIs and test support alike, so not only
	 * the engine's. The first frame carrying it, called the first evitaDB frame here, anchors a stack excerpt.
	 */
	private static final String PROJECT_PACKAGE_PREFIX = "io.evitadb.";
	/**
	 * Most frames of one busy thread quoted in the summary. The frames above the first evitaDB frame are the ones that
	 * tell a native write from compression or a lock, and they rarely number more than a dozen.
	 */
	private static final int SUMMARY_FRAME_LIMIT = 12;
	/**
	 * Most busy service threads described in detail and attached as stacks. Concurrently running test classes each
	 * own an engine, so the count is unbounded in principle - and a check-run annotation is capped at 64 KB.
	 */
	private static final int DETAILED_THREAD_LIMIT = 4;
	/**
	 * Most frames of an attached stack, which keeps the failure's printed stack trace well inside the 64 KB a
	 * check-run annotation may carry even with {@link #DETAILED_THREAD_LIMIT} threads sampled twice.
	 */
	private static final int ATTACHED_FRAME_LIMIT = 64;
	/**
	 * Number of most recently modified export archives whose size is followed across the samples - the archive a
	 * hung backup writes is the newest one, the rest only guard against a clock that orders them differently.
	 */
	private static final int EXPORT_ARCHIVE_LIMIT = 3;
	/**
	 * Suffix of the archives the backup tasks write into the export directory.
	 */
	private static final String EXPORT_ARCHIVE_SUFFIX = ".zip";
	/**
	 * Pause between two checks of whether a cancelled task's worker has left the task.
	 */
	private static final long CANCELLATION_POLL_MILLIS = 50;

	private TaskHangDiagnostics() {
		// utility class
	}

	/**
	 * Waits for the result of a server task and, when the wait times out, fails the test with a summary of the state
	 * the server was in at that moment, logging the full evidence at DEBUG level. The busy service threads are
	 * sampled twice, {@link #DEFAULT_SAMPLE_INTERVAL} apart.
	 *
	 * @param future           the future of the awaited task's result
	 * @param timeout          the maximum time to wait
	 * @param unit             the unit of `timeout`
	 * @param serverManagement the embedded engine's management contract - never a client's
	 * @param clientManagement a client's management contract, to report the task as the driver sees it, or NULL
	 * @param taskType         the type of the awaited task, e.g. `BackupTask`, used to find it among all tasks
	 * @param catalogName      the catalog the awaited task works on, or NULL when any catalog matches
	 * @param <T>              the type of the task result
	 * @return the task result
	 * @throws ExecutionException   when the task failed
	 * @throws InterruptedException when the waiting thread was interrupted
	 * @throws AssertionFailedError when the wait timed out, with the original {@link TimeoutException} as its cause
	 */
	public static <T> T awaitTaskResult(
		@Nonnull Future<T> future,
		long timeout,
		@Nonnull TimeUnit unit,
		@Nonnull EvitaManagementContract serverManagement,
		@Nullable EvitaManagementContract clientManagement,
		@Nonnull String taskType,
		@Nullable String catalogName
	) throws ExecutionException, InterruptedException {
		return awaitTaskResult(
			future, timeout, unit, serverManagement, clientManagement, taskType, catalogName, DEFAULT_SAMPLE_INTERVAL
		);
	}

	/**
	 * Waits for the result of a server task and, when the wait times out, fails the test with a summary of the state
	 * the server was in at that moment, logging the full evidence at DEBUG level. The busy service threads are
	 * sampled twice, `sampleInterval` apart; the full stack of each is attached to the failure as a suppressed
	 * {@link ServiceThreadStack}. The awaited task is cancelled afterwards, and the summary says whether its worker
	 * let go of it within `sampleInterval`.
	 *
	 * @param future           the future of the awaited task's result
	 * @param timeout          the maximum time to wait
	 * @param unit             the unit of `timeout`
	 * @param serverManagement the embedded engine's management contract - never a client's
	 * @param clientManagement a client's management contract, to report the task as the driver sees it, or NULL
	 * @param taskType         the type of the awaited task, e.g. `BackupTask`, used to find it among all tasks
	 * @param catalogName      the catalog the awaited task works on, or NULL when any catalog matches
	 * @param sampleInterval   the pause between the two samples of the busy service threads, and the longest wait
	 *                         for a cancelled task's worker to let go of it
	 * @param <T>              the type of the task result
	 * @return the task result
	 * @throws ExecutionException   when the task failed
	 * @throws InterruptedException when the waiting thread was interrupted
	 * @throws AssertionFailedError when the wait timed out, with the original {@link TimeoutException} as its cause
	 */
	public static <T> T awaitTaskResult(
		@Nonnull Future<T> future,
		long timeout,
		@Nonnull TimeUnit unit,
		@Nonnull EvitaManagementContract serverManagement,
		@Nullable EvitaManagementContract clientManagement,
		@Nonnull String taskType,
		@Nullable String catalogName,
		@Nonnull Duration sampleInterval
	) throws ExecutionException, InterruptedException {
		try {
			return future.get(timeout, unit);
		} catch (TimeoutException ex) {
			final String awaited = taskType + (catalogName == null ? "" : "[" + catalogName + "]");
			final Diagnosis diagnosis;
			try {
				diagnosis = diagnose(future, serverManagement, clientManagement, taskType, catalogName, sampleInterval);
			} catch (RuntimeException diagnosticFailure) {
				// the diagnostic is best-effort - its own failure must never hide the timeout it was meant to explain
				ex.addSuppressed(diagnosticFailure);
				throw new AssertionFailedError(
					awaited + " did not complete within " + timeout + " " + unit + " (diagnostics failed: " +
						diagnosticFailure + ")",
					ex
				);
			}
			final AssertionFailedError failure = new AssertionFailedError(
				awaited + " did not complete within " + timeout + " " + unit + ": " + diagnosis.summary(), ex
			);
			// the printed stack trace of the failure - suppressed blocks included - is what reaches the annotation
			for (ServiceThreadStack stack : diagnosis.threadStacks()) {
				failure.addSuppressed(stack);
			}
			throw failure;
		}
	}

	/**
	 * Collects the evidence, logs it at DEBUG level and returns the summary for the failure message together with the
	 * stacks of the busy service threads.
	 *
	 * @param future           the future of the awaited task's result
	 * @param serverManagement the embedded engine's management contract
	 * @param clientManagement a client's management contract, or NULL
	 * @param taskType         the type of the awaited task
	 * @param catalogName      the catalog the awaited task works on, or NULL
	 * @param sampleInterval   the pause between the two samples
	 * @return the summary and the stacks to attach
	 */
	@Nonnull
	private static Diagnosis diagnose(
		@Nonnull Future<?> future,
		@Nonnull EvitaManagementContract serverManagement,
		@Nullable EvitaManagementContract clientManagement,
		@Nonnull String taskType,
		@Nullable String catalogName,
		@Nonnull Duration sampleInterval
	) {
		// taken first - the cancellation at the end completes the future, which must not leak into the summary
		final boolean futureDone = future.isDone();
		final ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
		final boolean cpuTimeMeasured = threadBean.isThreadCpuTimeSupported() && threadBean.isThreadCpuTimeEnabled();
		final Path exportDirectory = exportDirectoryOf(serverManagement);

		final StringBuilder full = new StringBuilder(16_384);
		full.append("=== Task hang diagnostics: ").append(taskType)
			.append(catalogName == null ? "" : " on catalog `" + catalogName + "`").append(" ===\n");
		full.append("Awaited future: done=").append(futureDone)
			.append(", cancelled=").append(future.isCancelled())
			.append(", class=").append(future.getClass().getName()).append('\n');

		// (a) + (b) - everything the server knows about, asked directly
		List<TaskStatus<?, ?>> allTasks;
		String serverTaskFailure = null;
		try {
			allTasks = serverManagement.listTaskStatuses(1, TASK_PAGE_SIZE, null).getData();
		} catch (RuntimeException ex) {
			allTasks = List.of();
			serverTaskFailure = ex.toString();
		}
		final Optional<TaskStatus<?, ?>> awaitedTask = allTasks.stream()
			.filter(it -> taskType.equals(it.taskType()))
			.filter(it -> catalogName == null || catalogName.equals(it.catalogName()))
			.max(Comparator.comparing(TaskStatus::created));

		// (c) - the first sample: the threads with lock owners, and the counters the second sample is compared with
		final ThreadInfo[] threads = threadBean.dumpAllThreads(true, true);
		final List<ThreadInfo> relevant = Arrays.stream(threads)
			.filter(it -> RELEVANT_THREAD_PREFIXES.stream().anyMatch(prefix -> it.getThreadName().startsWith(prefix)))
			.sorted(Comparator.comparing(ThreadInfo::getThreadName))
			.toList();
		// every engine in the JVM names its service threads alike - concurrently running test classes each have one -
		// so the busy ones are examined and the idle ones only counted; the awaited task's own worker goes first
		final List<ThreadInfo> allServiceThreads = relevant.stream()
			.filter(it -> it.getThreadName().startsWith(SERVICE_THREAD_PREFIX))
			.toList();
		final List<ThreadInfo> busyServiceThreads = allServiceThreads.stream()
			.filter(it -> !isIdlePoolWorker(it))
			.sorted(
				Comparator.comparing((ThreadInfo it) -> !runsTask(it, taskType))
					.thenComparing(ThreadInfo::getThreadName)
			)
			.toList();
		final List<ThreadInfo> sampledThreads = busyServiceThreads.subList(
			0, Math.min(DETAILED_THREAD_LIMIT, busyServiceThreads.size())
		);
		final Sample first = Sample.take(
			threadBean, cpuTimeMeasured, sampledThreads.toArray(ThreadInfo[]::new),
			ExportArchives.measure(exportDirectory, null)
		);

		full.append("\n--- Awaited task (server view) ---\n");
		full.append(awaitedTask.map(TaskHangDiagnostics::describe).orElse(
			serverTaskFailure == null ? "NOT FOUND among " + allTasks.size() + " server task(s)" :
				"UNAVAILABLE: " + serverTaskFailure
		)).append('\n');

		// the client view - what the driver's status poller would learn about the same task
		String clientState = null;
		if (clientManagement != null && awaitedTask.isPresent()) {
			clientState = queryThroughClient(clientManagement, awaitedTask.get());
			full.append("\n--- Awaited task (client view) ---\n").append(clientState).append('\n');
		}

		full.append("\n--- All server tasks (").append(allTasks.size()).append(") ---\n");
		for (TaskStatus<?, ?> task : allTasks) {
			full.append(describe(task)).append('\n');
		}

		full.append("\n--- Threads (").append(relevant.size()).append(" of ").append(threads.length)
			.append(" matching ").append(RELEVANT_THREAD_PREFIXES).append(") ---\n");
		// pool workers waiting for their next task all carry the same stack, which says nothing but "idle" - they are
		// named on one line so that the threads doing something are not buried under dozens of identical dumps
		full.append("Idle pool workers: ").append(
			relevant.stream().filter(TaskHangDiagnostics::isIdlePoolWorker).map(ThreadInfo::getThreadName)
				.collect(Collectors.joining(", "))
		).append("\n\n");
		for (ThreadInfo thread : relevant) {
			if (!isIdlePoolWorker(thread)) {
				appendThread(full, thread);
			}
		}

		if (log.isDebugEnabled()) {
			log.debug("{}", full);
		}

		// the second sample - the client query above already spent part of the interval, only the rest is waited for
		pauseUntil(first.nanoTime() + sampleInterval.toNanos());
		final TaskStatus<?, ?> secondTaskStatus = awaitedTask
			.map(it -> statusOf(serverManagement, it))
			.orElse(null);
		final Sample second = Sample.take(
			threadBean, cpuTimeMeasured, first.threadIds(), ExportArchives.measure(exportDirectory, first.exports())
		);

		final ThreadSamples[] threadSamples = new ThreadSamples[first.threads().length];
		for (int i = 0; i < threadSamples.length; i++) {
			threadSamples[i] = new ThreadSamples(
				first.threads()[i], first.cpuNanos()[i], second.threads()[i], second.cpuNanos()[i],
				runsTask(first.threads()[i], taskType)
			);
		}

		// the hung task holds the service thread every later task of the test class needs - let it go, and note
		// whether the interrupt that comes with the cancellation was able to free its worker
		final TaskStatus<?, ?> latestTask = secondTaskStatus == null ? awaitedTask.orElse(null) : secondTaskStatus;
		final String cancellation = Optional.<TaskStatus<?, ?>>ofNullable(latestTask)
			.filter(it -> it.simplifiedState() == TaskSimplifiedState.QUEUED ||
				it.simplifiedState() == TaskSimplifiedState.RUNNING)
			.map(it -> cancel(serverManagement, it, threadBean, threadSamples, taskType, sampleInterval))
			.orElse(null);

		final long idleServiceThreads = allServiceThreads.size() - busyServiceThreads.size();
		final StringBuilder summary = new StringBuilder(2_048 + 1_024 * threadSamples.length);
		summary.append("task ").append(
				awaitedTask.map(TaskHangDiagnostics::describeBriefly)
					.orElse(serverTaskFailure == null ? "NOT FOUND" : "UNAVAILABLE (" + serverTaskFailure + ")")
			)
			.append(clientState == null ? "" : ", client sees " + clientState)
			.append("; future done=").append(futureDone)
			.append("; server tasks ").append(countByState(allTasks));
		summary.append("; over ").append(formatSeconds(second.nanoTime() - first.nanoTime())).append(':');
		if (awaitedTask.isPresent()) {
			summary.append(" progress ").append(awaitedTask.get().progress()).append("% -> ")
				.append(secondTaskStatus == null ? "?" : secondTaskStatus.progress() + "%").append(',');
		}
		summary.append(" GC ").append(second.gcMillis() - first.gcMillis()).append(" ms in ")
			.append(second.gcCount() - first.gcCount()).append(" collection(s)");
		if (exportDirectory != null) {
			summary.append(", ").append(ExportArchives.describeChange(first.exports(), second.exports()));
		}
		summary.append("; service threads: ");
		if (allServiceThreads.isEmpty()) {
			summary.append("none");
		} else {
			summary.append(idleServiceThreads).append(" idle, ").append(busyServiceThreads.size()).append(" busy");
			for (ThreadSamples samples : threadSamples) {
				summary.append("\n  ").append(samples.describe(cpuTimeMeasured, taskType));
			}
			if (busyServiceThreads.size() > threadSamples.length) {
				summary.append("\n  and ").append(busyServiceThreads.size() - threadSamples.length)
					.append(" more busy: ").append(
						busyServiceThreads.subList(threadSamples.length, busyServiceThreads.size()).stream()
							.map(ThreadInfo::getThreadName)
							.collect(Collectors.joining(", "))
					);
			}
		}
		if (cancellation != null) {
			summary.append("\n; ").append(cancellation);
		}
		summary.append("\n [")
			.append(threadSamples.length == 0 ? "" : "full stacks of the busy service threads attached as suppressed; ")
			.append("full dump at DEBUG in ").append(TaskHangDiagnostics.class.getName()).append(']');

		final List<ServiceThreadStack> stacks = new ArrayList<>(threadSamples.length * 2);
		for (ThreadSamples samples : threadSamples) {
			samples.attachTo(stacks, taskType);
		}
		return new Diagnosis(summary.toString(), stacks);
	}

	/**
	 * Cancels the awaited task and, when a service thread was found running it, waits up to `wait` for that thread to
	 * leave the task. The cancellation interrupts the worker, so whether the worker lets go is itself evidence: a
	 * thread waiting inside the JVM unwinds at once, a thread blocked in an operating system call stays where it was.
	 *
	 * @param serverManagement the embedded engine's management contract
	 * @param task             the awaited task as last seen
	 * @param threadBean       the thread management bean
	 * @param threadSamples    the sampled busy service threads
	 * @param taskType         the type of the awaited task
	 * @param wait             the longest wait for the worker to leave the task
	 * @return the outcome of the cancellation, for the summary
	 */
	@Nonnull
	private static String cancel(
		@Nonnull EvitaManagementContract serverManagement,
		@Nonnull TaskStatus<?, ?> task,
		@Nonnull ThreadMXBean threadBean,
		@Nonnull ThreadSamples[] threadSamples,
		@Nonnull String taskType,
		@Nonnull Duration wait
	) {
		final List<ThreadSamples> workers = Arrays.stream(threadSamples)
			.filter(ThreadSamples::runsAwaitedTask)
			.toList();
		final long started = System.nanoTime();
		try {
			if (!serverManagement.cancelTask(task.taskId())) {
				return "cancel refused - the task was no longer pending";
			}
		} catch (RuntimeException ex) {
			return "cancel failed (" + ex + ")";
		}
		if (task.simplifiedState() != TaskSimplifiedState.RUNNING) {
			return "cancel accepted while the task was " + task.simplifiedState();
		}
		if (workers.size() != 1) {
			return "cancel accepted; " + (workers.isEmpty() ?
				"no service thread was running a " + taskType :
				workers.size() + " service threads were running a " + taskType + ", none waited for");
		}
		final long workerId = workers.get(0).first().getThreadId();
		final long deadline = started + wait.toNanos();
		while (true) {
			final ThreadInfo worker = threadBean.getThreadInfo(workerId, Integer.MAX_VALUE);
			if (worker == null || !runsTask(worker, taskType)) {
				return "cancel accepted; the worker let go of the task in " +
					formatSeconds(System.nanoTime() - started);
			}
			if (System.nanoTime() >= deadline) {
				final StackTraceElement[] stack = worker.getStackTrace();
				return "cancel accepted; the worker still runs the task " + formatSeconds(System.nanoTime() - started) +
					" later, " + worker.getThreadState() + " inNative=" + worker.isInNative() +
					" at " + (stack.length == 0 ? "no stack" : stack[0]);
			}
			try {
				Thread.sleep(CANCELLATION_POLL_MILLIS);
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				return "cancel accepted; the wait for the worker was interrupted";
			}
		}
	}

	/**
	 * Returns the export directory of the embedded engine, when it writes its exports to the local file system - the
	 * directory a backup archive grows in.
	 *
	 * @param serverManagement the embedded engine's management contract
	 * @return the export directory, or NULL when the exports do not go to a local directory
	 */
	@Nullable
	private static Path exportDirectoryOf(@Nonnull EvitaManagementContract serverManagement) {
		// export files are what the engine produces for a user, not catalog storage - see module-boundaries.md
		if (serverManagement instanceof EvitaManagement management &&
			management.exportService() instanceof ExportFileService fileService) {
			return fileService.getExportDirectory();
		}
		return null;
	}

	/**
	 * Asks the server again for the status of the awaited task, for the progress at the second sample.
	 *
	 * @param serverManagement the embedded engine's management contract
	 * @param task             the task as the first sample saw it
	 * @return the current status, or NULL when it cannot be obtained
	 */
	@Nullable
	private static TaskStatus<?, ?> statusOf(
		@Nonnull EvitaManagementContract serverManagement,
		@Nonnull TaskStatus<?, ?> task
	) {
		try {
			return serverManagement.getTaskStatus(task.taskId()).orElse(null);
		} catch (RuntimeException ex) {
			return null;
		}
	}

	/**
	 * Sleeps until the given {@link System#nanoTime()} instant. An interrupt ends the pause early and is preserved -
	 * the second sample is then simply taken sooner, and the summary states the real interval.
	 *
	 * @param deadlineNanos the instant to sleep until
	 */
	private static void pauseUntil(long deadlineNanos) {
		final long remainingNanos = deadlineNanos - System.nanoTime();
		if (remainingNanos > 0) {
			try {
				TimeUnit.NANOSECONDS.sleep(remainingNanos);
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		}
	}

	/**
	 * Formats a duration given in nanoseconds as seconds with one decimal place.
	 *
	 * @param nanos the duration
	 * @return e.g. `5.0 s`
	 */
	@Nonnull
	private static String formatSeconds(long nanos) {
		return String.format(Locale.ROOT, "%.1f s", nanos / 1_000_000_000.0);
	}

	/**
	 * Asks the client for the task's status on a separate thread, bounded by {@link #CLIENT_QUERY_TIMEOUT_SECONDS},
	 * because the client call travels the channel that may be the one that is stuck.
	 *
	 * @param clientManagement the client's management contract
	 * @param task             the task as the server reports it
	 * @return the client's view in brief, or the reason it could not be obtained
	 */
	@Nonnull
	private static String queryThroughClient(
		@Nonnull EvitaManagementContract clientManagement,
		@Nonnull TaskStatus<?, ?> task
	) {
		final CompletableFuture<String> query = new CompletableFuture<>();
		final Thread thread = new Thread(
			() -> {
				try {
					query.complete(
						clientManagement.getTaskStatus(task.taskId())
							.map(TaskHangDiagnostics::describeBriefly)
							.orElse("NOT FOUND")
					);
				} catch (Throwable ex) {
					query.complete("FAILED (" + ex + ")");
				}
			},
			"task-hang-diagnostics-client-query"
		);
		thread.setDaemon(true);
		thread.start();
		try {
			return query.get(CLIENT_QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		} catch (TimeoutException ex) {
			return "NO ANSWER within " + CLIENT_QUERY_TIMEOUT_SECONDS + " s";
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return "INTERRUPTED";
		} catch (ExecutionException ex) {
			return "FAILED (" + ex.getCause() + ")";
		}
	}

	/**
	 * Describes a task status in full - one line, everything a reader needs to place it on a timeline.
	 *
	 * @param task the task status
	 * @return the description
	 */
	@Nonnull
	private static String describe(@Nonnull TaskStatus<?, ?> task) {
		return task.taskType() + " `" + task.taskName() + "` id=" + task.taskId() +
			" catalog=" + task.catalogName() +
			" state=" + task.simplifiedState() +
			" progress=" + task.progress() + "%" +
			" created=" + task.created() +
			" issued=" + task.issued() +
			" started=" + task.started() +
			" finished=" + task.finished() +
			(task.publicExceptionMessage() == null ? "" : " error=" + task.publicExceptionMessage()) +
			(task.exceptionWithStackTrace() == null ? "" : "\n" + task.exceptionWithStackTrace());
	}

	/**
	 * Describes a task status briefly, for the summary line.
	 *
	 * @param task the task status
	 * @return the brief description
	 */
	@Nonnull
	private static String describeBriefly(@Nonnull TaskStatus<?, ?> task) {
		return task.simplifiedState() + " " + task.progress() + "%" +
			(task.publicExceptionMessage() == null ? "" : " (" + task.publicExceptionMessage() + ")");
	}

	/**
	 * Quotes the top of a stack for the summary: every frame from the top down to and including the first evitaDB
	 * frame, because the JDK frames above it are what tell a native write, compression and a lock apart, and the
	 * evitaDB frame is what tells which of the project's operations is affected - usually the engine's, but equally a
	 * driver call or a test's own code. At most {@link #SUMMARY_FRAME_LIMIT} frames are quoted; when the first evitaDB
	 * frame lies deeper, the frames in between are elided but the evitaDB frame is kept.
	 *
	 * @param stack the stack, top frame first
	 * @return the frames joined by ` <- `, or `no stack`
	 */
	@Nonnull
	private static String topFrames(@Nonnull StackTraceElement[] stack) {
		if (stack.length == 0) {
			return "no stack";
		}
		int projectFrame = -1;
		for (int i = 0; i < stack.length; i++) {
			if (stack[i].getClassName().startsWith(PROJECT_PACKAGE_PREFIX)) {
				projectFrame = i;
				break;
			}
		}
		final StringBuilder frames = new StringBuilder(128 * SUMMARY_FRAME_LIMIT);
		final int lastQuoted = projectFrame < 0 ? Math.min(stack.length, SUMMARY_FRAME_LIMIT) - 1 : projectFrame;
		for (int i = 0; i <= lastQuoted; i++) {
			if (i == SUMMARY_FRAME_LIMIT - 1 && lastQuoted > i) {
				// keep the anchoring evitaDB frame, drop what lies between it and the frames already quoted
				frames.append(" <- ... ").append(lastQuoted - i).append(" frame(s) ... <- ").append(stack[lastQuoted]);
				break;
			}
			if (i > 0) {
				frames.append(" <- ");
			}
			frames.append(stack[i]);
		}
		return frames.toString();
	}

	/**
	 * Tells whether the thread is a pool worker waiting for its next task - parked in the pool's own task queue with no
	 * evitaDB frame on its stack.
	 *
	 * @param thread the thread
	 * @return TRUE when the thread is an idle pool worker
	 */
	private static boolean isIdlePoolWorker(@Nonnull ThreadInfo thread) {
		final StackTraceElement[] stack = thread.getStackTrace();
		return Arrays.stream(stack)
			.anyMatch(it -> "getTask".equals(it.getMethodName()) &&
				it.getClassName().startsWith("java.util.concurrent.ThreadPoolExecutor")) &&
			Arrays.stream(stack).noneMatch(it -> it.getClassName().startsWith(PROJECT_PACKAGE_PREFIX));
	}

	/**
	 * Tells whether the thread runs a task of the given type - whether a frame of its stack belongs to the task's class
	 * (the task type is the simple name of that class) or to a class nested in it.
	 *
	 * @param thread   the thread
	 * @param taskType the type of the task
	 * @return TRUE when the thread runs a task of that type
	 */
	private static boolean runsTask(@Nonnull ThreadInfo thread, @Nonnull String taskType) {
		for (StackTraceElement frame : thread.getStackTrace()) {
			final String className = frame.getClassName();
			final String simpleName = className.substring(className.lastIndexOf('.') + 1);
			if (simpleName.equals(taskType) || simpleName.startsWith(taskType + "$")) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Describes the locks of a thread briefly: the one it waits for with its owner, and the ones it holds.
	 *
	 * @param thread the thread
	 * @return the description, starting with a space, or an empty string when the thread neither waits nor holds
	 */
	@Nonnull
	private static String describeLocks(@Nonnull ThreadInfo thread) {
		final String waitsFor = thread.getLockName() == null ? "" : " on " + thread.getLockName() +
			(thread.getLockOwnerName() == null ? "" : " held by " + thread.getLockOwnerName());
		final String holds = Stream.concat(
				Arrays.stream(thread.getLockedMonitors()), Arrays.stream(thread.getLockedSynchronizers())
			)
			.map(LockInfo::toString)
			.collect(Collectors.joining(", "));
		return waitsFor + (holds.isEmpty() ? "" : " holding [" + holds + "]");
	}

	/**
	 * Appends a thread's full stack with the locks it waits for and holds. {@link ThreadInfo#toString()} is not used,
	 * because it truncates the stack to eight frames - usually right above the frame that matters.
	 *
	 * @param target the builder to append to
	 * @param thread the thread
	 */
	private static void appendThread(@Nonnull StringBuilder target, @Nonnull ThreadInfo thread) {
		target.append('"').append(thread.getThreadName()).append("\" id=").append(thread.getThreadId())
			.append(' ').append(thread.getThreadState());
		if (thread.getLockName() != null) {
			target.append(" on ").append(thread.getLockName());
		}
		if (thread.getLockOwnerName() != null) {
			target.append(" owned by \"").append(thread.getLockOwnerName())
				.append("\" id=").append(thread.getLockOwnerId());
		}
		target.append('\n');
		final StackTraceElement[] stack = thread.getStackTrace();
		final MonitorInfo[] monitors = thread.getLockedMonitors();
		for (int i = 0; i < stack.length; i++) {
			target.append("\tat ").append(stack[i]).append('\n');
			for (MonitorInfo monitor : monitors) {
				if (monitor.getLockedStackDepth() == i) {
					target.append("\t- locked ").append(monitor).append('\n');
				}
			}
		}
		final LockInfo[] synchronizers = thread.getLockedSynchronizers();
		if (synchronizers.length > 0) {
			target.append("\tLocked synchronizers:\n");
			for (LockInfo synchronizer : synchronizers) {
				target.append("\t- ").append(synchronizer).append('\n');
			}
		}
		target.append('\n');
	}

	/**
	 * Counts tasks by their simplified state, in the order the states are declared.
	 *
	 * @param tasks the tasks
	 * @return the counts, e.g. `{QUEUED=1, RUNNING=1}`
	 */
	@Nonnull
	private static Map<TaskSimplifiedState, Integer> countByState(@Nonnull List<TaskStatus<?, ?>> tasks) {
		final Map<TaskSimplifiedState, Integer> counts = new EnumMap<>(TaskSimplifiedState.class);
		for (TaskStatus<?, ?> task : tasks) {
			counts.merge(task.simplifiedState(), 1, Integer::sum);
		}
		return counts;
	}

	/**
	 * The full stack of a busy service thread, attached to the timeout failure as a suppressed throwable so that the
	 * JUnit report - and the check-run annotation built from it - prints it under `Suppressed:`. It is not an error of
	 * its own: the stack is the sampled thread's, not the place this object was created, which is why it never fills
	 * in a stack of its own.
	 *
	 * The message names the thread, its state, whether it was in native code and which sample the stack comes from.
	 */
	public static final class ServiceThreadStack extends Throwable {
		@Serial private static final long serialVersionUID = 6983806911534253059L;

		/**
		 * Creates the stack of a sampled thread.
		 *
		 * @param message the description of the thread and the sample
		 * @param stack   the sampled stack, top frame first
		 */
		ServiceThreadStack(@Nonnull String message, @Nonnull StackTraceElement[] stack) {
			// no suppression, writable stack - the stack is set below, never filled in (see fillInStackTrace)
			super(message, null, false, true);
			setStackTrace(stack);
		}

		/**
		 * Keeps the stack empty until the sampled one is set - the creator's own stack would only mislead.
		 *
		 * @return this throwable
		 */
		@Override
		public synchronized Throwable fillInStackTrace() {
			return this;
		}
	}

	/**
	 * The outcome of a diagnosis: the summary for the failure message and the stacks to attach to the failure.
	 *
	 * @param summary      the summary
	 * @param threadStacks the stacks of the busy service threads
	 */
	private record Diagnosis(
		@Nonnull String summary,
		@Nonnull List<ServiceThreadStack> threadStacks
	) {
	}

	/**
	 * One sample of the evidence that changes over time: the sampled threads with their consumed CPU time, the total
	 * GC effort of the JVM and the export archives.
	 *
	 * @param nanoTime  the {@link System#nanoTime()} instant the threads were sampled at
	 * @param threads   the sampled threads, an element is NULL when its thread no longer exists
	 * @param threadIds the ids of the sampled threads, in the order of `threads`
	 * @param cpuNanos  the CPU time consumed by each thread so far, -1 when it is not measured
	 * @param gcMillis  the accumulated collection time of all garbage collectors
	 * @param gcCount   the accumulated collection count of all garbage collectors
	 * @param exports   the export archives at the moment of the sample
	 */
	private record Sample(
		long nanoTime,
		@Nonnull ThreadInfo[] threads,
		@Nonnull long[] threadIds,
		@Nonnull long[] cpuNanos,
		long gcMillis,
		long gcCount,
		@Nonnull ExportArchives exports
	) {

		/**
		 * Takes a sample of the given threads, keeping the thread infos the caller already holds.
		 *
		 * @param threadBean      the thread management bean
		 * @param cpuTimeMeasured TRUE when the JVM measures per-thread CPU time
		 * @param threads         the threads, as just dumped
		 * @param exports         the export archives
		 * @return the sample
		 */
		@Nonnull
		static Sample take(
			@Nonnull ThreadMXBean threadBean,
			boolean cpuTimeMeasured,
			@Nonnull ThreadInfo[] threads,
			@Nonnull ExportArchives exports
		) {
			final long nanoTime = System.nanoTime();
			final long[] threadIds = Arrays.stream(threads).mapToLong(ThreadInfo::getThreadId).toArray();
			return create(threadBean, cpuTimeMeasured, nanoTime, threads, threadIds, exports);
		}

		/**
		 * Takes a sample of the threads with the given ids, dumping them anew with their locks.
		 *
		 * @param threadBean      the thread management bean
		 * @param cpuTimeMeasured TRUE when the JVM measures per-thread CPU time
		 * @param threadIds       the ids of the threads
		 * @param exports         the export archives
		 * @return the sample
		 */
		@Nonnull
		static Sample take(
			@Nonnull ThreadMXBean threadBean,
			boolean cpuTimeMeasured,
			@Nonnull long[] threadIds,
			@Nonnull ExportArchives exports
		) {
			final ThreadInfo[] threads = threadIds.length == 0 ?
				new ThreadInfo[0] : threadBean.getThreadInfo(threadIds, true, true);
			final long nanoTime = System.nanoTime();
			return create(threadBean, cpuTimeMeasured, nanoTime, threads, threadIds, exports);
		}

		/**
		 * Completes a sample with the CPU times of its threads and the garbage collectors' totals.
		 *
		 * @param threadBean      the thread management bean
		 * @param cpuTimeMeasured TRUE when the JVM measures per-thread CPU time
		 * @param nanoTime        the instant the threads were sampled at
		 * @param threads         the sampled threads
		 * @param threadIds       their ids
		 * @param exports         the export archives
		 * @return the sample
		 */
		@Nonnull
		private static Sample create(
			@Nonnull ThreadMXBean threadBean,
			boolean cpuTimeMeasured,
			long nanoTime,
			@Nonnull ThreadInfo[] threads,
			@Nonnull long[] threadIds,
			@Nonnull ExportArchives exports
		) {
			final long[] cpuNanos = new long[threadIds.length];
			for (int i = 0; i < threadIds.length; i++) {
				cpuNanos[i] = cpuTimeMeasured ? threadBean.getThreadCpuTime(threadIds[i]) : -1;
			}
			long gcMillis = 0;
			long gcCount = 0;
			for (GarbageCollectorMXBean collector : ManagementFactory.getGarbageCollectorMXBeans()) {
				// both are -1 for a collector that does not report them
				gcMillis += Math.max(0, collector.getCollectionTime());
				gcCount += Math.max(0, collector.getCollectionCount());
			}
			return new Sample(nanoTime, threads, threadIds, cpuNanos, gcMillis, gcCount, exports);
		}
	}

	/**
	 * Both samples of one busy service thread.
	 *
	 * @param first           the thread at the first sample
	 * @param firstCpuNanos   its consumed CPU time at the first sample, -1 when not measured
	 * @param second          the thread at the second sample, NULL when it no longer exists
	 * @param secondCpuNanos  its consumed CPU time at the second sample, -1 when not measured
	 * @param runsAwaitedTask TRUE when the thread runs a task of the awaited type
	 */
	private record ThreadSamples(
		@Nonnull ThreadInfo first,
		long firstCpuNanos,
		@Nullable ThreadInfo second,
		long secondCpuNanos,
		boolean runsAwaitedTask
	) {

		/**
		 * Tells whether the stack differs between the samples.
		 *
		 * @return TRUE when the thread moved, or no longer exists
		 */
		boolean stackChanged() {
			return this.second == null || !Arrays.equals(this.first.getStackTrace(), this.second.getStackTrace());
		}

		/**
		 * Describes the thread for the summary: name, state, locks, native flag, CPU burned between the samples,
		 * whether the stack moved, and the top of the first sample's stack.
		 *
		 * @param cpuTimeMeasured TRUE when the JVM measures per-thread CPU time
		 * @param taskType        the type of the awaited task
		 * @return the description
		 */
		@Nonnull
		String describe(boolean cpuTimeMeasured, @Nonnull String taskType) {
			final StringBuilder description = new StringBuilder(256 + 128 * SUMMARY_FRAME_LIMIT);
			description.append(this.first.getThreadName());
			if (this.runsAwaitedTask) {
				description.append(" [runs ").append(taskType).append(']');
			}
			description.append(' ').append(this.first.getThreadState());
			if (this.second != null && this.second.getThreadState() != this.first.getThreadState()) {
				description.append("->").append(this.second.getThreadState());
			}
			description.append(describeLocks(this.first));
			description.append(" inNative=").append(this.first.isInNative());
			if (this.second != null && this.second.isInNative() != this.first.isInNative()) {
				description.append("->").append(this.second.isInNative());
			}
			description.append(" cpu=");
			if (cpuTimeMeasured && this.firstCpuNanos >= 0 && this.secondCpuNanos >= 0) {
				description.append((this.secondCpuNanos - this.firstCpuNanos) / 1_000_000).append(" ms");
			} else {
				description.append("n/a");
			}
			if (this.second == null) {
				description.append(" thread gone at the second sample");
			} else if (isIdlePoolWorker(this.second)) {
				description.append(" idle at the second sample");
			} else {
				description.append(" stack ").append(stackChanged() ? "changed" : "unchanged");
			}
			description.append(" at ").append(topFrames(this.first.getStackTrace()));
			return description.toString();
		}

		/**
		 * Attaches the first sample's stack and, when the thread moved in between, the second sample's stack too.
		 *
		 * @param target   the list to add the stacks to
		 * @param taskType the type of the awaited task
		 */
		void attachTo(@Nonnull List<ServiceThreadStack> target, @Nonnull String taskType) {
			target.add(toStack(this.first, "sample 1 at the timeout", taskType));
			if (this.second != null && stackChanged()) {
				target.add(toStack(this.second, "sample 2", taskType));
			}
		}

		/**
		 * Turns one sample of the thread into an attachable stack, capped at {@link #ATTACHED_FRAME_LIMIT} frames.
		 *
		 * @param thread   the sampled thread
		 * @param sample   which sample it is
		 * @param taskType the type of the awaited task
		 * @return the attachable stack
		 */
		@Nonnull
		private ServiceThreadStack toStack(
			@Nonnull ThreadInfo thread,
			@Nonnull String sample,
			@Nonnull String taskType
		) {
			final StackTraceElement[] stack = thread.getStackTrace();
			final StackTraceElement[] attached = stack.length > ATTACHED_FRAME_LIMIT ?
				Arrays.copyOf(stack, ATTACHED_FRAME_LIMIT) : stack;
			return new ServiceThreadStack(
				'"' + thread.getThreadName() + "\" id=" + thread.getThreadId() +
					(this.runsAwaitedTask ? " [runs " + taskType + "]" : "") +
					' ' + thread.getThreadState() + describeLocks(thread) +
					" inNative=" + thread.isInNative() + ", " + sample +
					(attached.length < stack.length ?
						" (top " + attached.length + " of " + stack.length + " frames)" : ""),
				attached
			);
		}
	}

	/**
	 * The most recently modified archives of the export directory with their sizes, and the usable space of the file
	 * store holding them - whether a backup's bytes still reach the disk, and whether the disk has room for them.
	 *
	 * @param archives    the archives with their sizes, newest first; a size is -1 when the archive is gone
	 * @param usableSpace the usable space of the file store in bytes, -1 when unknown
	 * @param failure     the reason the directory could not be read, or NULL
	 */
	private record ExportArchives(
		@Nonnull List<ExportArchive> archives,
		long usableSpace,
		@Nullable String failure
	) {
		/**
		 * The measurement used when the engine does not export into a local directory.
		 */
		private static final ExportArchives NONE = new ExportArchives(List.of(), -1, null);

		/**
		 * Measures the export directory. The first sample picks the {@link #EXPORT_ARCHIVE_LIMIT} newest archives;
		 * the second measures the same archives again, so that the two are comparable.
		 *
		 * @param directory the export directory, or NULL
		 * @param previous  the measurement of the first sample, or NULL when this is the first sample
		 * @return the measurement
		 */
		@Nonnull
		static ExportArchives measure(@Nullable Path directory, @Nullable ExportArchives previous) {
			if (directory == null) {
				return NONE;
			}
			try {
				final List<ExportArchive> archives;
				if (previous == null) {
					try (final Stream<Path> files = Files.list(directory)) {
						archives = files
							.filter(it -> it.getFileName().toString().endsWith(EXPORT_ARCHIVE_SUFFIX))
							.filter(Files::isRegularFile)
							.map(ExportArchive::measure)
							.sorted(Comparator.comparing(ExportArchive::lastModified).reversed())
							.limit(EXPORT_ARCHIVE_LIMIT)
							.toList();
					}
				} else {
					archives = previous.archives().stream().map(it -> ExportArchive.measure(it.path())).toList();
				}
				return new ExportArchives(archives, Files.getFileStore(directory).getUsableSpace(), null);
			} catch (IOException | RuntimeException ex) {
				return new ExportArchives(List.of(), -1, ex.toString());
			}
		}

		/**
		 * Describes how the archives and the usable space changed between the samples.
		 *
		 * @param first  the first sample's measurement
		 * @param second the second sample's measurement
		 * @return e.g. `exports [1f2e3d4c.zip 3407872 -> 3407872 B], usable space 14532 -> 14532 MB`
		 */
		@Nonnull
		static String describeChange(@Nonnull ExportArchives first, @Nonnull ExportArchives second) {
			if (first.failure() != null || second.failure() != null) {
				return "exports unavailable (" + (first.failure() == null ? second.failure() : first.failure()) + ")";
			}
			final StringBuilder description = new StringBuilder(64 + 64 * first.archives().size());
			description.append("exports [");
			if (first.archives().isEmpty()) {
				description.append("none");
			}
			for (int i = 0; i < first.archives().size(); i++) {
				final ExportArchive archive = first.archives().get(i);
				final String fileName = archive.path().getFileName().toString();
				if (i > 0) {
					description.append(", ");
				}
				// the archives are named by a random UUID - its first segment tells them apart well enough
				description.append(fileName.length() > 12 ? fileName.substring(0, 8) + "~" : fileName)
					.append(' ').append(archive.size()).append(" -> ")
					.append(i < second.archives().size() ? second.archives().get(i).size() : -1).append(" B");
			}
			return description.append("], usable space ").append(toMegabytes(first.usableSpace())).append(" -> ")
				.append(toMegabytes(second.usableSpace())).append(" MB").toString();
		}

		/**
		 * Converts bytes to whole megabytes, keeping -1 for unknown.
		 *
		 * @param bytes the number of bytes, or -1
		 * @return the number of megabytes, or -1
		 */
		private static long toMegabytes(long bytes) {
			return bytes < 0 ? -1 : bytes / (1024 * 1024);
		}
	}

	/**
	 * One export archive with its size and modification time at the moment it was measured.
	 *
	 * @param path         the archive
	 * @param size         its size in bytes, -1 when it no longer exists
	 * @param lastModified its last modification time, the epoch when it no longer exists
	 */
	private record ExportArchive(
		@Nonnull Path path,
		long size,
		@Nonnull FileTime lastModified
	) {

		/**
		 * Measures an archive; an archive deleted in the meantime is reported with size -1 rather than failing the
		 * whole measurement.
		 *
		 * @param path the archive
		 * @return the measurement
		 */
		@Nonnull
		static ExportArchive measure(@Nonnull Path path) {
			try {
				return new ExportArchive(path, Files.size(path), Files.getLastModifiedTime(path));
			} catch (IOException ex) {
				return new ExportArchive(path, -1, FileTime.fromMillis(0));
			}
		}
	}

}
