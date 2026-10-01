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
import org.opentest4j.AssertionFailedError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

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
 * **Two outputs, deliberately of different size.** The failure message carries a one-line summary: the awaited task's
 * state and progress, and what each service thread is doing. That is the part that survives into check-run
 * annotations. The full evidence goes to this class's logger at DEBUG level only, so it lands in the failing test's
 * `system-out` in the surefire XML report without raising the verbosity of anything else. Enabling that DEBUG level is
 * the job of the logback configuration the test module runs with - no other logger may be lowered for it.
 *
 * **The server is always asked directly, never through the path under suspicion.** The management contract passed as
 * `serverManagement` must be the embedded engine's own, so that a stuck gRPC channel cannot also hang the diagnostic.
 * The client view is optional and bounded by {@link #CLIENT_QUERY_TIMEOUT_SECONDS}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class TaskHangDiagnostics {
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

	private TaskHangDiagnostics() {
		// utility class
	}

	/**
	 * Waits for the result of a server task and, when the wait times out, fails the test with a summary of the state
	 * the server was in at that moment, logging the full evidence at DEBUG level.
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
		try {
			return future.get(timeout, unit);
		} catch (TimeoutException ex) {
			final String awaited = taskType + (catalogName == null ? "" : "[" + catalogName + "]");
			final String summary;
			try {
				summary = diagnose(future, serverManagement, clientManagement, taskType, catalogName);
			} catch (RuntimeException diagnosticFailure) {
				// the diagnostic is best-effort - its own failure must never hide the timeout it was meant to explain
				ex.addSuppressed(diagnosticFailure);
				throw new AssertionFailedError(
					awaited + " did not complete within " + timeout + " " + unit + " (diagnostics failed: " +
						diagnosticFailure + ")",
					ex
				);
			}
			throw new AssertionFailedError(
				awaited + " did not complete within " + timeout + " " + unit + ": " + summary, ex
			);
		}
	}

	/**
	 * Collects the evidence, logs it at DEBUG level and returns the one-line summary for the failure message.
	 *
	 * @param future           the future of the awaited task's result
	 * @param serverManagement the embedded engine's management contract
	 * @param clientManagement a client's management contract, or NULL
	 * @param taskType         the type of the awaited task
	 * @param catalogName      the catalog the awaited task works on, or NULL
	 * @return the one-line summary
	 */
	@Nonnull
	private static String diagnose(
		@Nonnull Future<?> future,
		@Nonnull EvitaManagementContract serverManagement,
		@Nullable EvitaManagementContract clientManagement,
		@Nonnull String taskType,
		@Nullable String catalogName
	) {
		final StringBuilder full = new StringBuilder(16_384);
		full.append("=== Task hang diagnostics: ").append(taskType)
			.append(catalogName == null ? "" : " on catalog `" + catalogName + "`").append(" ===\n");
		full.append("Awaited future: done=").append(future.isDone())
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

		// (c) - the threads, with lock owners
		final ThreadInfo[] threads = ManagementFactory.getThreadMXBean().dumpAllThreads(true, true);
		final List<ThreadInfo> relevant = Arrays.stream(threads)
			.filter(it -> RELEVANT_THREAD_PREFIXES.stream().anyMatch(prefix -> it.getThreadName().startsWith(prefix)))
			.sorted(Comparator.comparing(ThreadInfo::getThreadName))
			.toList();
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

		// every engine in the JVM names its service threads alike - concurrently running test classes each have one -
		// so the busy ones are named and the idle ones only counted
		final List<ThreadInfo> allServiceThreads = relevant.stream()
			.filter(it -> it.getThreadName().startsWith(SERVICE_THREAD_PREFIX))
			.toList();
		final long idleServiceThreads = allServiceThreads.stream().filter(TaskHangDiagnostics::isIdlePoolWorker).count();
		final String busyServiceThreads = allServiceThreads.stream()
			.filter(it -> !isIdlePoolWorker(it))
			.map(TaskHangDiagnostics::describeBriefly)
			.collect(Collectors.joining("; "));
		final String serviceThreads = (busyServiceThreads.isEmpty() ? "" : busyServiceThreads + "; ") +
			idleServiceThreads + " idle";
		return "task " + awaitedTask.map(TaskHangDiagnostics::describeBriefly)
			.orElse(serverTaskFailure == null ? "NOT FOUND" : "UNAVAILABLE (" + serverTaskFailure + ")") +
			(clientState == null ? "" : ", client sees " + clientState) +
			"; future done=" + future.isDone() +
			"; server tasks " + countByState(allTasks) +
			"; service threads: " + (allServiceThreads.isEmpty() ? "none" : serviceThreads) +
			" [full dump at DEBUG in " + TaskHangDiagnostics.class.getName() + "]";
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
	 * Describes a thread briefly, for the summary line: its name, its state, and the first engine frame it is in -
	 * or `idle` when it is a pool worker waiting for its next task.
	 *
	 * @param thread the thread
	 * @return the brief description
	 */
	@Nonnull
	private static String describeBriefly(@Nonnull ThreadInfo thread) {
		if (isIdlePoolWorker(thread)) {
			return thread.getThreadName() + " idle";
		}
		final StackTraceElement[] stack = thread.getStackTrace();
		final String where = Arrays.stream(stack)
			.filter(it -> it.getClassName().startsWith("io.evitadb."))
			.findFirst()
			.or(() -> stack.length > 0 ? Optional.of(stack[0]) : Optional.empty())
			.map(StackTraceElement::toString)
			.orElse("no stack");
		return thread.getThreadName() + " " + thread.getThreadState() +
			(thread.getLockName() == null ? "" : " on " + thread.getLockName() +
				(thread.getLockOwnerName() == null ? "" : " held by " + thread.getLockOwnerName())) +
			" at " + where;
	}

	/**
	 * Tells whether the thread is a pool worker waiting for its next task - parked in the pool's own task queue with no
	 * engine frame on its stack.
	 *
	 * @param thread the thread
	 * @return TRUE when the thread is an idle pool worker
	 */
	private static boolean isIdlePoolWorker(@Nonnull ThreadInfo thread) {
		final StackTraceElement[] stack = thread.getStackTrace();
		return Arrays.stream(stack)
			.anyMatch(it -> "getTask".equals(it.getMethodName()) &&
				it.getClassName().startsWith("java.util.concurrent.ThreadPoolExecutor")) &&
			Arrays.stream(stack).noneMatch(it -> it.getClassName().startsWith("io.evitadb."));
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

}
