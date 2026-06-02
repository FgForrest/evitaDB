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

package io.evitadb.core.executor;

import io.evitadb.api.configuration.ThreadPoolOptions;
import io.evitadb.api.observability.trace.TracingContext;
import io.evitadb.api.requestResponse.progress.UnrejectableTask;
import io.evitadb.core.metric.event.system.AbstractThreadPoolStatisticsEvent;
import io.evitadb.core.metric.event.system.BackgroundTaskTimedOutEvent;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.lang.Thread.UncaughtExceptionHandler;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * An {@link ExecutorService} implementation built on top of a {@link ThreadPoolExecutor} that adds task lifecycle
 * observability, bounded queue depth, interrupt-based cancellation, and tracing context propagation.
 *
 * Two instances are created per evitaDB server — one for incoming API requests (higher priority) and one for
 * transaction processing (lower priority). Both are configured via {@link ThreadPoolOptions}.
 *
 * Key design points:
 *
 * - **Sizing for blocking workloads**: {@link ThreadPoolOptions} maps to `corePoolSize = minThreadCount`
 *   (warm floor) and `maximumPoolSize = maxThreadCount` (concurrency ceiling). A custom {@link TaskQueue}
 *   forces the pool to grow towards {@code maxThreadCount} *before* it starts queueing — a plain
 *   {@link ThreadPoolExecutor} with an unbounded queue would otherwise never exceed the core size. The pool
 *   size is therefore the effective concurrency for the predominantly *blocking* request workload (network
 *   I/O, (de)serialization, lock contention), decoupled from {@code availableProcessors()}.
 * - **Bounded backlog**: once {@code maxThreadCount} threads are busy, up to
 *   `{@link ThreadPoolOptions#queueSize()}` tasks are queued; beyond that {@link GrowAwareRejectionHandler}
 *   rejects with a {@link RejectedExecutionException} (and {@link #rejectedTaskCount} is bumped), except for
 *   {@link UnrejectableTask}s submitted via {@code execute(...)}/{@code submit(...)}, which are always enqueued.
 *   The {@code invokeAll}/{@code invokeAny} batch paths do not preserve the unrejectable marker (the JDK
 *   re-wraps each task in a plain {@link FutureTask} before it reaches the rejection handler); they are not used
 *   with unrejectable tasks. An in-flight counter ({@link #queueSize}) drives the
 *   grow-before-queue decision and is decremented when a task finishes via the `onCompletion` callback.
 * - **Cancellable tasks**: every submitted {@link Runnable}/{@link Callable} is wrapped in
 *   {@link ObservableRunnable}/{@link ObservableCallable} which record the executing thread so that
 *   {@link CancellableTask#cancel()} can call {@link Thread#interrupt()} on it. The interrupt flag is
 *   cleared in the `finally` block so it cannot leak to the next task picked up by the same worker thread.
 * - **Tracing context propagation**: the MDC and thread-local tracing state present on the submitting thread
 *   is captured at construction time via {@link TracingContext#captureContext()} and restored on the worker
 *   thread for the duration of the task, enabling correlated logging across thread boundaries.
 * - **JFR statistics**: {@link #emitStatistics()} publishes a JFR event carrying live
 *   {@link ThreadPoolExecutor} metrics (built by the {@link AbstractThreadPoolStatisticsEvent.Factory} bound at
 *   construction). It is called periodically by {@link io.evitadb.core.Evita} via
 *   {@code FlightRecorder.addPeriodicEvent}.
 * - **Test mode**: when {@code immediateExecutorService} is {@code true} (test environments), an
 *   {@link ImmediateExecutorService} is used instead so that tasks run synchronously on the calling thread,
 *   eliminating concurrency in unit tests.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Slf4j
public class ObservableThreadExecutor implements ObservableExecutorServiceWithCancellationSupport {
	/**
	 * The underlying {@link ThreadPoolExecutor} (or an {@link ImmediateExecutorService} in test mode) that does
	 * the heavy lifting.
	 */
	private final ExecutorService executorService;
	/**
	 * Counter monitoring the number of tasks submitted to the executor service.
	 */
	private final LongAdder submittedTaskCount = new LongAdder();
	/**
	 * Counter monitoring the number of tasks rejected by the executor service.
	 */
	private final LongAdder rejectedTaskCount = new LongAdder();
	/**
	 * Atomic counter tracking the number of in-flight tasks (submitted but not yet finished). Incremented
	 * before dispatch and decremented via the `onCompletion` callback once a task completes or is cancelled
	 * (and on the rejection path). It drives the {@link TaskQueue} grow-before-queue decision: when the
	 * in-flight count exceeds the live pool size every worker is busy, so the pool is grown towards
	 * {@code maxThreadCount} instead of queueing.
	 */
	private final AtomicInteger queueSize = new AtomicInteger();
	/**
	 * Maximum number of tasks that may wait in the backlog once all {@code maxThreadCount} worker threads are
	 * busy. Sourced from {@link ThreadPoolOptions#queueSize()} and enforced by the bounded {@link TaskQueue};
	 * when the backlog is full a non-{@link UnrejectableTask} is rejected by {@link GrowAwareRejectionHandler}
	 * with a {@link RejectedExecutionException}. Total admission is therefore {@code maxThreadCount} running
	 * plus {@code queueLimit} waiting.
	 */
	private final int queueLimit;
	/**
	 * Factory that builds this pool's statistics event from a live snapshot, or {@code null} when statistics
	 * emission is not wired (e.g. test environments). Supplied at construction so {@link #emitStatistics()} can
	 * stay a no-argument periodic callback that already knows which concrete event type to emit.
	 */
	@Nullable private final AbstractThreadPoolStatisticsEvent.Factory statisticsEventFactory;
	/**
	 * The cumulative completed-task count observed at the previous {@link #emitStatistics()} call, used to emit
	 * the per-tick completed delta — the metric pipeline accumulates COUNTER fields, so the running total must
	 * not be emitted directly. Only read/written from the single periodic-emit thread.
	 */
	private long lastCompletedTaskCount;
	/**
	 * Cached method reference for decrementing queue size, avoiding per-task lambda allocation.
	 */
	private final Runnable queueSizeDecrementer = this.queueSize::decrementAndGet;

	/**
	 * Creates a new executor configured according to the supplied options, without statistics emission wired.
	 * Equivalent to {@link #ObservableThreadExecutor(String, ThreadPoolOptions, boolean, AbstractThreadPoolStatisticsEvent.Factory)}
	 * with a {@code null} factory — {@link #emitStatistics()} is then a no-op. Intended for tests and callers that
	 * do not publish pool metrics.
	 *
	 * @param name                     logical name of this executor, used in thread names, log messages, and JFR events
	 * @param options                  pool sizing and priority settings; see {@link ThreadPoolOptions}
	 * @param immediateExecutorService when {@code true}, an {@link ImmediateExecutorService} is used instead of a
	 *                                 {@link ThreadPoolExecutor} — intended for test environments where synchronous
	 *                                 execution avoids concurrency-related flakiness
	 */
	public ObservableThreadExecutor(
		@Nonnull String name,
		@Nonnull ThreadPoolOptions options,
		boolean immediateExecutorService
	) {
		this(name, options, immediateExecutorService, null);
	}

	/**
	 * Creates a new executor configured according to the supplied options.
	 *
	 * In production mode a {@link ThreadPoolExecutor} is created with `corePoolSize = minThreadCount` and
	 * `maximumPoolSize = maxThreadCount`, fronted by a {@link TaskQueue} that grows the pool to its maximum
	 * before it starts queueing (so the configured {@code maxThreadCount} is actually reachable for blocking
	 * work) and caps the backlog at {@code queueSize}. Worker threads are daemon threads named
	 * `Evita-{@code name}-N` with the configured priority and are reclaimed after 60s of idleness.
	 *
	 * @param name                     logical name of this executor, used in thread names, log messages, and JFR events
	 * @param options                  pool sizing and priority settings; see {@link ThreadPoolOptions}
	 * @param immediateExecutorService when {@code true}, an {@link ImmediateExecutorService} is used instead of a
	 *                                 {@link ThreadPoolExecutor} — intended for test environments where synchronous
	 *                                 execution avoids concurrency-related flakiness
	 * @param statisticsEventFactory   builds the concrete statistics event emitted by {@link #emitStatistics()};
	 *                                 {@code null} disables statistics emission
	 */
	public ObservableThreadExecutor(
		@Nonnull String name,
		@Nonnull ThreadPoolOptions options,
		boolean immediateExecutorService,
		@Nullable AbstractThreadPoolStatisticsEvent.Factory statisticsEventFactory
	) {
		this.statisticsEventFactory = statisticsEventFactory;
		this.queueLimit = options.queueSize();
		this.executorService = immediateExecutorService ?
			// in test environment we use a simplified executor that runs tasks immediately (synchronously)
			new ImmediateExecutorService() :
			// in standard environment we use a ThreadPoolExecutor sized for the (predominantly blocking) workload
			createThreadPoolExecutor(
				name, options, this.queueSize, this.queueLimit,
				new EvitaRejectingExecutorHandler(name, this.rejectedTaskCount::increment)
			);
	}

	/**
	 * Builds the backing {@link ThreadPoolExecutor} for the supplied options.
	 *
	 * `corePoolSize = minThreadCount` (eagerly-created threads, reclaimed after idle since
	 * {@link ThreadPoolExecutor#allowCoreThreadTimeOut(boolean)} is enabled); `maximumPoolSize = maxThreadCount`
	 * is the concurrency ceiling. The {@link TaskQueue} forces the pool to spawn threads up to the maximum
	 * before it starts queueing — a plain {@link ThreadPoolExecutor} with an unbounded queue never grows past
	 * the core size — and bounds the backlog at {@code queueLimit}. Genuine overflow is handled by
	 * {@link GrowAwareRejectionHandler}.
	 *
	 * @param name             logical pool name (used for thread names and events)
	 * @param options          pool sizing and priority settings
	 * @param inFlightCounter  shared in-flight counter feeding the queue's grow-before-queue heuristic
	 * @param queueLimit       maximum backlog size once all worker threads are busy
	 * @param rejectionHandler handler invoked on genuine overflow (fires the rejection event + counter)
	 * @return the configured executor
	 */
	@Nonnull
	private static ThreadPoolExecutor createThreadPoolExecutor(
		@Nonnull String name,
		@Nonnull ThreadPoolOptions options,
		@Nonnull AtomicInteger inFlightCounter,
		int queueLimit,
		@Nonnull EvitaRejectingExecutorHandler rejectionHandler
	) {
		final int core = Math.max(1, options.minThreadCount());
		final int max = Math.max(core, options.maxThreadCount());
		final TaskQueue workQueue = new TaskQueue(queueLimit, inFlightCounter);
		final ThreadPoolExecutor executor = new ThreadPoolExecutor(
			core,
			max,
			60L, TimeUnit.SECONDS,
			workQueue,
			new EvitaThreadFactory(name, options.threadPriority()),
			new GrowAwareRejectionHandler(rejectionHandler)
		);
		// reclaim idle worker threads (including core ones) instead of pinning `minThreadCount` threads forever
		executor.allowCoreThreadTimeOut(true);
		workQueue.setParent(executor);
		return executor;
	}

	@Override
	@Nonnull
	public CancellableRunnable createTask(@Nonnull String name, @Nonnull Runnable lambda) {
		return new ObservableRunnable(name, lambda, this.queueSizeDecrementer);
	}

	@Override
	@Nonnull
	public CancellableRunnable createTask(@Nonnull Runnable lambda) {
		return new ObservableRunnable(lambda, this.queueSizeDecrementer);
	}

	@Override
	@Nonnull
	public <V> CancellableCallable<V> createTask(@Nonnull String name, @Nonnull Callable<V> lambda) {
		return new ObservableCallable<>(name, lambda, this.queueSizeDecrementer);
	}

	@Override
	@Nonnull
	public <V> CancellableCallable<V> createTask(@Nonnull Callable<V> lambda) {
		return new ObservableCallable<>(lambda, this.queueSizeDecrementer);
	}

	@Override
	public long getSubmittedTaskCount() {
		return this.submittedTaskCount.sum();
	}

	@Override
	public long getRejectedTaskCount() {
		return this.rejectedTaskCount.sum();
	}

	/**
	 * Returns the current number of in-flight tasks (submitted but not yet finished). This is the counter that
	 * drives the {@link TaskQueue} grow-before-queue decision; it must return to its true baseline once every
	 * submitted task has completed or been rejected, otherwise the growth heuristic skews permanently.
	 *
	 * Exposed with package visibility so tests can assert the counter stays balanced across the rejection and
	 * cancellation paths.
	 *
	 * @return the number of tasks that have been submitted but have not yet finished
	 */
	int getInFlightTaskCount() {
		return this.queueSize.get();
	}

	/**
	 * Submits a task for fire-and-forget execution.
	 *
	 * Unlike the {@link #submit} variants, this method catches the initial {@link RejectedExecutionException}
	 * from the queue-limit check and delegates to the {@link EvitaRejectingExecutorHandler}, which logs the event,
	 * fires a JFR event, and then re-throws a {@link RejectedExecutionException}. Callers must therefore still
	 * be prepared for the exception.
	 *
	 * @param command the task to execute; will be wrapped in an {@link ObservableRunnable} unless already one
	 * @throws RejectedExecutionException if the queue limit is exceeded or the pool has been shut down
	 *         (thrown by {@link EvitaRejectingExecutorHandler#rejectedExecution()})
	 */
	@Override
	public void execute(@Nonnull Runnable command) {
		ObservableRunnable wrapped = null;
		try {
			wrapped = wrapToCancellableTask(command);
			this.executorService.execute(wrapped);
			this.submittedTaskCount.increment();
		} catch (RejectedExecutionException e) {
			if (wrapped != null) {
				// Pool rejected the task after queueSize was already incremented;
				// the onCompletion callback will never fire, so balance manually.
				this.queueSize.decrementAndGet();
			}
			// the pool's GrowAwareRejectionHandler has already fired the rejection event and bumped the counter
			throw e;
		}
	}

	@Override
	public void shutdown() {
		this.executorService.shutdown();
	}

	@Nonnull
	@Override
	public List<Runnable> shutdownNow() {
		return this.executorService.shutdownNow();
	}

	@Override
	public boolean isShutdown() {
		return this.executorService.isShutdown();
	}

	@Override
	public boolean isTerminated() {
		return this.executorService.isTerminated();
	}

	@Override
	public boolean awaitTermination(long timeout, @Nonnull TimeUnit unit) throws InterruptedException {
		return this.executorService.awaitTermination(timeout, unit);
	}

	/**
	 * Wraps the callable in an {@link ObservableCallable}, enforces the queue limit, and delegates to the
	 * underlying pool. Increments {@link #submittedTaskCount} on success.
	 *
	 * When the wrapped task is an {@link UnrejectableTask} the future is built locally as an
	 * {@link UnrejectableFutureTask} and dispatched via {@code execute(...)}, so the unrejectable marker survives
	 * to {@link GrowAwareRejectionHandler} instead of being hidden behind the plain {@link FutureTask} that
	 * {@code ThreadPoolExecutor.submit(...)} would otherwise create.
	 *
	 * @throws RejectedExecutionException if the queue limit is exceeded or the pool has been shut down
	 */
	@Nonnull
	@Override
	public <T> Future<T> submit(@Nonnull Callable<T> task) {
		ObservableCallable<T> wrapped = null;
		try {
			wrapped = wrapToCancellableTask(task);
			final Future<T> result;
			if (wrapped.isUnrejectable()) {
				final UnrejectableFutureTask<T> futureTask = new UnrejectableFutureTask<>(wrapped);
				this.executorService.execute(futureTask);
				result = futureTask;
			} else {
				result = this.executorService.submit(wrapped);
			}
			this.submittedTaskCount.increment();
			return result;
		} catch (RejectedExecutionException e) {
			if (wrapped != null) {
				this.queueSize.decrementAndGet();
			}
			// the pool's GrowAwareRejectionHandler already fired the rejection event and bumped the counter
			throw e;
		}
	}

	/**
	 * Wraps the runnable in an {@link ObservableRunnable}, enforces the queue limit, and delegates to the
	 * underlying pool. The supplied {@code result} value is returned by the {@link Future} on completion.
	 *
	 * When the wrapped task is an {@link UnrejectableTask} the future is built locally as an
	 * {@link UnrejectableFutureTask} and dispatched via {@code execute(...)}, so the unrejectable marker survives
	 * to {@link GrowAwareRejectionHandler} instead of being hidden behind the plain {@link FutureTask} that
	 * {@code ThreadPoolExecutor.submit(...)} would otherwise create.
	 *
	 * @throws RejectedExecutionException if the queue limit is exceeded or the pool has been shut down
	 */
	@Nonnull
	@Override
	public <T> Future<T> submit(@Nonnull Runnable task, T result) {
		ObservableRunnable wrapped = null;
		try {
			wrapped = wrapToCancellableTask(task);
			final Future<T> future;
			if (wrapped.isUnrejectable()) {
				final UnrejectableFutureTask<T> futureTask = new UnrejectableFutureTask<>(wrapped, result);
				this.executorService.execute(futureTask);
				future = futureTask;
			} else {
				future = this.executorService.submit(wrapped, result);
			}
			this.submittedTaskCount.increment();
			return future;
		} catch (RejectedExecutionException e) {
			if (wrapped != null) {
				this.queueSize.decrementAndGet();
			}
			// the pool's GrowAwareRejectionHandler already fired the rejection event and bumped the counter
			throw e;
		}
	}

	/**
	 * Wraps the runnable in an {@link ObservableRunnable}, enforces the queue limit, and delegates to the
	 * underlying pool. The returned {@link Future} completes with {@code null} when the task finishes.
	 *
	 * When the wrapped task is an {@link UnrejectableTask} the future is built locally as an
	 * {@link UnrejectableFutureTask} and dispatched via {@code execute(...)}, so the unrejectable marker survives
	 * to {@link GrowAwareRejectionHandler} instead of being hidden behind the plain {@link FutureTask} that
	 * {@code ThreadPoolExecutor.submit(...)} would otherwise create.
	 *
	 * @throws RejectedExecutionException if the queue limit is exceeded or the pool has been shut down
	 */
	@Nonnull
	@Override
	public Future<?> submit(@Nonnull Runnable task) {
		ObservableRunnable wrapped = null;
		try {
			wrapped = wrapToCancellableTask(task);
			final Future<?> future;
			if (wrapped.isUnrejectable()) {
				final UnrejectableFutureTask<Void> futureTask = new UnrejectableFutureTask<>(wrapped, null);
				this.executorService.execute(futureTask);
				future = futureTask;
			} else {
				future = this.executorService.submit(wrapped);
			}
			this.submittedTaskCount.increment();
			return future;
		} catch (RejectedExecutionException e) {
			if (wrapped != null) {
				this.queueSize.decrementAndGet();
			}
			// the pool's GrowAwareRejectionHandler already fired the rejection event and bumped the counter
			throw e;
		}
	}

	/**
	 * Wraps all callables, enforces per-task queue limits, submits them all, and blocks until every task
	 * has completed (or the calling thread is interrupted).
	 *
	 * If the calling thread is interrupted while waiting, the interrupt flag is restored and a
	 * {@link RejectedExecutionException} is thrown so callers do not silently swallow interruptions.
	 *
	 * @throws RejectedExecutionException if any task exceeds the queue limit, the pool is shut down,
	 *         or the calling thread is interrupted while blocking
	 */
	@Nonnull
	@Override
	public <T> List<Future<T>> invokeAll(@Nonnull Collection<? extends Callable<T>> tasks) {
		final List<ObservableCallable<T>> tasksToSubmit = new ArrayList<>(tasks.size());
		for (final Callable<T> task : tasks) {
			tasksToSubmit.add(wrapToCancellableTask(task));
		}
		try {
			final List<Future<T>> futures = this.executorService.invokeAll(tasksToSubmit);
			this.submittedTaskCount.add(futures.size());
			return futures;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RejectedExecutionException("Thread was interrupted while waiting for tasks to complete.", e);
		} finally {
			reconcileInFlight(tasksToSubmit);
		}
	}

	/**
	 * Wraps all callables, enforces per-task queue limits, submits them all, and blocks until every task
	 * has completed or the timeout elapses. Tasks that did not complete within the timeout are cancelled.
	 *
	 * @throws InterruptedException       if the calling thread is interrupted while waiting
	 * @throws RejectedExecutionException if any task exceeds the queue limit or the pool is shut down
	 */
	@Nonnull
	@Override
	public <T> List<Future<T>> invokeAll(@Nonnull Collection<? extends Callable<T>> tasks, long timeout, @Nonnull TimeUnit unit) throws InterruptedException {
		final List<ObservableCallable<T>> tasksToSubmit = new ArrayList<>(tasks.size());
		for (final Callable<T> task : tasks) {
			tasksToSubmit.add(wrapToCancellableTask(task));
		}
		try {
			final List<Future<T>> futures = this.executorService.invokeAll(tasksToSubmit, timeout, unit);
			this.submittedTaskCount.add(futures.size());
			return futures;
		} finally {
			reconcileInFlight(tasksToSubmit);
		}
	}

	/**
	 * Wraps all callables, enforces per-task queue limits, and returns the result of the first task that
	 * succeeds. All other tasks are cancelled once one succeeds.
	 *
	 * Note: only 1 is added to {@link #submittedTaskCount} regardless of how many tasks are provided, because
	 * from the caller's perspective a single logical operation has been submitted.
	 *
	 * @throws InterruptedException       if the calling thread is interrupted while waiting
	 * @throws ExecutionException         if every task failed with an exception
	 * @throws RejectedExecutionException if the queue limit is exceeded or the pool is shut down
	 */
	@Nonnull
	@Override
	public <T> T invokeAny(@Nonnull Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException {
		final List<ObservableCallable<T>> tasksToSubmit = new ArrayList<>(tasks.size());
		for (final Callable<T> task : tasks) {
			tasksToSubmit.add(wrapToCancellableTask(task));
		}
		try {
			final T result = this.executorService.invokeAny(tasksToSubmit);
			this.submittedTaskCount.increment();
			return result;
		} finally {
			reconcileInFlight(tasksToSubmit);
		}
	}

	/**
	 * Wraps all callables, enforces per-task queue limits, and returns the result of the first task that
	 * succeeds within the given timeout. All remaining tasks are cancelled once one succeeds or the
	 * timeout expires.
	 *
	 * @throws InterruptedException       if the calling thread is interrupted while waiting
	 * @throws ExecutionException         if every task failed with an exception
	 * @throws TimeoutException           if no task succeeds within the given timeout
	 * @throws RejectedExecutionException if the queue limit is exceeded or the pool is shut down
	 */
	@Override
	public <T> T invokeAny(@Nonnull Collection<? extends Callable<T>> tasks, long timeout, @Nonnull TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
		final List<ObservableCallable<T>> tasksToSubmit = new ArrayList<>(tasks.size());
		for (final Callable<T> task : tasks) {
			tasksToSubmit.add(wrapToCancellableTask(task));
		}
		try {
			final T result = this.executorService.invokeAny(tasksToSubmit, timeout, unit);
			this.submittedTaskCount.increment();
			return result;
		} finally {
			reconcileInFlight(tasksToSubmit);
		}
	}

	/**
	 * Publishes a JFR event with the current {@link ThreadPoolExecutor} statistics, called periodically by
	 * {@link io.evitadb.core.Evita} via {@code FlightRecorder.addPeriodicEvent}. The
	 * {@link AbstractThreadPoolStatisticsEvent.Factory} bound at construction builds the concrete event from a
	 * live snapshot of the pool.
	 *
	 * The {@code completed} field is emitted as the number of tasks completed *since the previous call* (a delta
	 * against {@link #lastCompletedTaskCount}), because the metric pipeline accumulates COUNTER fields — emitting
	 * the cumulative total each tick would inflate the counter. The backing queue is unbounded (the
	 * {@code queueLimit} is a soft cap enforced in {@link TaskQueue#offer(Runnable)}), so the reported
	 * {@code queueRemaining} is the headroom up to that soft limit rather than the queue's raw remaining capacity.
	 *
	 * When no factory was supplied or the underlying executor is an {@link ImmediateExecutorService} (test mode),
	 * this method is a no-op.
	 */
	public void emitStatistics() {
		final AbstractThreadPoolStatisticsEvent.Factory factory = this.statisticsEventFactory;
		if (factory == null) {
			return;
		}
		try {
			if (this.executorService instanceof ThreadPoolExecutor tpe) {
				// emit the completed-task count as a per-tick delta (the metric pipeline accumulates COUNTERs)
				final long completedNow = tpe.getCompletedTaskCount();
				final long completedDelta = completedNow - this.lastCompletedTaskCount;
				this.lastCompletedTaskCount = completedNow;
				final int queued = tpe.getQueue().size();
				factory.create(
					completedDelta,
					tpe.getActiveCount(),
					queued,
					// the backing queue is unbounded; report headroom up to the soft queueLimit instead
					Math.max(0, this.queueLimit - queued),
					tpe.getPoolSize(),
					tpe.getCorePoolSize(),
					tpe.getMaximumPoolSize(),
					tpe.getLargestPoolSize()
				).commit();
			}
			// no statistics are emitted when the pool is not a ThreadPoolExecutor (the test ImmediateExecutorService)
		} catch (Throwable t) {
			log.error("Emitting observability events failed!", t);
		}
	}

	/**
	 * Increments the in-flight counter and, if the runnable is not already an {@link ObservableRunnable}, wraps
	 * it in one (capturing the current tracing context and wiring the {@link #queueSizeDecrementer} callback).
	 *
	 * Rejection is not enforced here: the bounded {@link TaskQueue} and {@link GrowAwareRejectionHandler} decide
	 * whether to grow the pool, queue, or reject once the task reaches the executor. The counter is decremented
	 * on completion via {@link #queueSizeDecrementer}, or on the rejection path in the submit/execute methods.
	 *
	 * Precondition: a pre-wrapped {@link ObservableRunnable} passed in must have been produced by this executor's
	 * {@code createTask(...)} factory, so its completion callback is this executor's {@link #queueSizeDecrementer}.
	 * Such a wrapper is returned as-is (its in-flight increment will be balanced by its own callback). Passing a
	 * wrapper built with a foreign callback still increments the counter but is never balanced, leaking it — do
	 * not submit externally-constructed wrappers carrying a different completion callback.
	 *
	 * @param runnable the task to run; if it is already an {@link ObservableRunnable} it is returned as-is
	 *                 to avoid double-wrapping and double-decrement of the queue counter
	 * @return the observable wrapper, ready to be submitted to the underlying pool
	 */
	@Nonnull
	private ObservableRunnable wrapToCancellableTask(@Nonnull Runnable runnable) {
		// track the in-flight task; growth/queue/reject decisions are made by the pool's TaskQueue and handler
		this.queueSize.incrementAndGet();
		return runnable instanceof ObservableRunnable observableRunnable
			? observableRunnable
			: new ObservableRunnable(runnable, this.queueSizeDecrementer);
	}

	/**
	 * Increments the in-flight counter and, if the callable is not already an {@link ObservableCallable}, wraps
	 * it in one (capturing the current tracing context and wiring the {@link #queueSizeDecrementer} callback).
	 *
	 * Rejection is not enforced here: the bounded {@link TaskQueue} and {@link GrowAwareRejectionHandler} decide
	 * whether to grow the pool, queue, or reject once the task reaches the executor. The counter is decremented
	 * on completion via {@link #queueSizeDecrementer}, or on the rejection path in the submit methods.
	 *
	 * Precondition: a pre-wrapped {@link ObservableCallable} passed in must have been produced by this executor's
	 * {@code createTask(...)} factory, so its completion callback is this executor's {@link #queueSizeDecrementer}.
	 * Such a wrapper is returned as-is (its in-flight increment will be balanced by its own callback). Passing a
	 * wrapper built with a foreign callback still increments the counter but is never balanced, leaking it — do
	 * not submit externally-constructed wrappers carrying a different completion callback.
	 *
	 * @param callable the task to run; if it is already an {@link ObservableCallable} it is returned as-is
	 *                 to avoid double-wrapping and double-decrement of the queue counter
	 * @param <V>      the type of the result produced by the callable
	 * @return the observable wrapper, ready to be submitted to the underlying pool
	 */
	@Nonnull
	private <V> ObservableCallable<V> wrapToCancellableTask(@Nonnull Callable<V> callable) {
		// track the in-flight task; growth/queue/reject decisions are made by the pool's TaskQueue and handler
		this.queueSize.incrementAndGet();
		return callable instanceof ObservableCallable<V> observableCallable
			? observableCallable
			: new ObservableCallable<>(callable, this.queueSizeDecrementer);
	}

	/**
	 * Balances the in-flight counter for a batch submitted via {@code invokeAll}/{@code invokeAny}.
	 *
	 * Each wrapped task increments the in-flight counter once in {@link #wrapToCancellableTask}; the matching
	 * decrement normally fires from inside the task's {@link ObservableCallable#call()} completion callback. The
	 * JDK `invokeAll`/`invokeAny` implementations, however, may short-circuit some tasks — a rejected batch never
	 * runs the tasks queued after the rejection, and `invokeAny` cancels the losers once one task wins — so their
	 * `call()` (and therefore their decrement) never runs, leaking the counter permanently and skewing the
	 * {@link TaskQueue} grow heuristic. This method fires the pending completion of every task in the batch; the
	 * per-task {@code completionFired} CAS guarantees the decrement happens exactly once whether the task ran on a
	 * worker, was cancelled before running, or is racing to finish right now.
	 *
	 * @param tasks the wrapped tasks of the batch
	 * @param <V>   the result type of the tasks
	 */
	private static <V> void reconcileInFlight(@Nonnull List<ObservableCallable<V>> tasks) {
		for (ObservableCallable<V> task : tasks) {
			task.fireCompletion();
		}
	}

	/**
	 * Common base for {@link ObservableRunnable} and {@link ObservableCallable}. Holds the task lifecycle state
	 * shared by both wrappers — the optional task name, the captured tracing context, the completion
	 * {@link CompletableFuture}, the {@code onCompletion} callback, the exactly-once completion guard and the
	 * executing-thread reference — together with the behaviour that does not depend on whether the delegate is a
	 * {@link Runnable} or a {@link Callable}: cancellation, completion-callback firing, status queries, and the
	 * post-execution cleanup performed in the {@code finally} block of the subclass's run/call method.
	 *
	 * The subclasses contribute only the typed delegate and the run/call method, whose bodies genuinely differ
	 * (the callable variant propagates a return value and unwraps checked exceptions).
	 *
	 * @param <V> the result type exposed through {@link #completionStage()} ({@link Void} for the runnable wrapper)
	 */
	abstract static class AbstractObservableTask<V> implements CancellableTask<V> {
		/**
		 * Human-readable description of the task; {@code null} when an unnamed constructor was used,
		 * in which case {@link #toString()} delegates to the delegate's {@code toString()}.
		 */
		@Nullable private final String name;
		/**
		 * Snapshot of the submitting thread's MDC and thread-local tracing state, captured eagerly at
		 * construction time. Restored onto the worker thread by the subclass's run/call method so that log
		 * entries emitted by the delegate carry the same trace/client IDs as the original request.
		 * May be {@link TracingContext.CapturedContext#EMPTY} when no tracing context was active.
		 */
		protected final TracingContext.CapturedContext capturedContext = TracingContext.captureContext();
		/**
		 * Completes when the task finishes: normally via {@link CompletableFuture#complete(Object)},
		 * exceptionally via {@link CompletableFuture#completeExceptionally(Throwable)}, or by cancellation
		 * via {@link CompletableFuture#cancel(boolean)}. Exposed to callers through {@link #completionStage()}.
		 */
		protected final CompletableFuture<V> future = new CompletableFuture<>();
		/**
		 * Callback invoked exactly once when the task finishes (normally, exceptionally, or via cancellation).
		 * In production this is {@link ObservableThreadExecutor#queueSizeDecrementer}, which decrements the
		 * outer queue counter. Protected against double-invocation by {@link #completionFired}.
		 */
		private final Runnable onCompletion;
		/**
		 * Guard ensuring the onCompletion callback fires at most once, preventing
		 * double-decrement of queueSize when a task is cancelled.
		 */
		private final AtomicBoolean completionFired = new AtomicBoolean(false);
		/**
		 * Reference to the thread currently executing this task. Used for interrupt-based cancellation.
		 * Volatile ensures visibility between the worker thread and any cancelling thread.
		 */
		@Nullable protected volatile Thread executingThread;

		/**
		 * @param name         human-readable task identifier returned by {@link #toString()}, or {@code null}
		 *                     for an unnamed task that falls back to the delegate's {@code toString()}
		 * @param onCompletion callback fired exactly once when the task finishes or is cancelled;
		 *                     typically {@link ObservableThreadExecutor#queueSizeDecrementer}
		 */
		protected AbstractObservableTask(@Nullable String name, @Nonnull Runnable onCompletion) {
			this.name = name;
			this.onCompletion = onCompletion;
		}

		/**
		 * Returns the wrapped delegate ({@link Runnable} or {@link Callable}) so the shared
		 * {@link #isUnrejectable()} and {@link #toString()} can inspect it without knowing its concrete type.
		 *
		 * @return the wrapped delegate
		 */
		@Nonnull
		protected abstract Object getDelegate();

		@Override
		public boolean isFinished() {
			return this.future.isDone();
		}

		/**
		 * Returns whether the delegate implements {@link UnrejectableTask}, meaning this task
		 * should bypass queue limit rejection.
		 *
		 * @return {@code true} if the delegate is an {@link UnrejectableTask}
		 */
		public boolean isUnrejectable() {
			return getDelegate() instanceof UnrejectableTask;
		}

		/**
		 * Cancels this task.
		 *
		 * If the task has not started yet, the next call to the subclass's run/call method returns immediately
		 * without invoking the delegate. If the task is currently executing, the worker thread is interrupted.
		 * In both cases {@link #onCompletion} is fired (at most once) and the internal future is transitioned to
		 * the cancelled state.
		 *
		 * This method is safe to call from any thread and is idempotent.
		 */
		@Override
		public void cancel() {
			this.future.cancel(true);
			fireCompletion();
			final Thread t = this.executingThread;
			if (t != null) {
				t.interrupt();
			}
			new BackgroundTaskTimedOutEvent(
				this.name == null ? "Unknown" : this.name, 1
			).commit();
		}

		@Nonnull
		@Override
		public CompletableFuture<V> completionStage() {
			return this.future;
		}

		/**
		 * Fires {@link #onCompletion} at most once, guarded by the {@link #completionFired} CAS so the outer
		 * in-flight counter is decremented exactly once whether the task completes normally, throws, or is
		 * cancelled. Invoked from {@link #finishExecution()} (the subclass run/call {@code finally} block), from
		 * {@link #cancel()}, and — for the callable wrapper — from
		 * {@link ObservableThreadExecutor#reconcileInFlight} to balance tasks that the JDK {@code invokeAll} /
		 * {@code invokeAny} short-circuited (cancelled before they ran), whose own completion callback would
		 * otherwise never fire.
		 */
		protected void fireCompletion() {
			if (this.completionFired.compareAndSet(false, true)) {
				this.onCompletion.run();
			}
		}

		/**
		 * Post-execution cleanup invoked from the {@code finally} block of the subclass's run/call method: fires
		 * the completion callback exactly once, clears the executing-thread reference, and clears the worker
		 * thread's interrupt flag when the task was cancelled so the interrupt does not leak into the next task
		 * picked up by the same worker thread.
		 */
		protected void finishExecution() {
			fireCompletion();
			this.executingThread = null;
			// clear interrupt flag to prevent leaking to the next task on this worker thread
			if (this.future.isCancelled()) {
				//noinspection ResultOfMethodCallIgnored
				Thread.interrupted();
			}
		}

		@Override
		public String toString() {
			return this.name == null ? getDelegate().toString() : this.name;
		}
	}

	/**
	 * Wrapper around a {@link Runnable} that implements the {@link CancellableRunnable} interface.
	 *
	 * Responsibilities:
	 *
	 * - Captures the caller's MDC / tracing context at construction time and restores it on the worker thread
	 *   for the duration of {@link #run()}, enabling correlated log entries across thread boundaries.
	 * - Tracks the executing thread via a volatile field so that {@link #cancel()} can interrupt it at any time.
	 * - Exposes a {@link CompletableFuture} that is completed (normally, exceptionally, or via cancellation)
	 *   when the task finishes, allowing callers to chain dependent actions.
	 * - Decrements the outer executor's queue-size counter exactly once when the task either completes or is
	 *   cancelled, guarded by {@link #completionFired}.
	 *
	 * All of the above is inherited from {@link AbstractObservableTask}; this wrapper only adds the typed
	 * {@link Runnable} delegate and the {@link #run()} method.
	 *
	 * Cancellation contract: calling {@link #cancel()} before {@link #run()} causes the run to return
	 * immediately without invoking the delegate. Calling {@link #cancel()} during {@link #run()} interrupts
	 * the worker thread; the task still clears the interrupt flag in its {@code finally} block so it does
	 * not leak into the next task on the same worker thread.
	 */
	static class ObservableRunnable extends AbstractObservableTask<Void> implements CancellableRunnable {
		/**
		 * The actual work to execute. Never invoked directly by the outer executor — always invoked
		 * via {@link #run()} which adds tracing, lifecycle tracking, and interrupt cleanup around it.
		 */
		@Nonnull private final Runnable delegate;

		/**
		 * Creates an unnamed observable wrapper around the given delegate.
		 * The task description will fall back to {@link Object#toString()} of the delegate.
		 *
		 * @param delegate     the actual work to execute
		 * @param onCompletion callback fired exactly once when the task finishes or is cancelled;
		 *                     typically {@link ObservableThreadExecutor#queueSizeDecrementer}
		 */
		public ObservableRunnable(@Nonnull Runnable delegate, @Nonnull Runnable onCompletion) {
			super(null, onCompletion);
			this.delegate = delegate;
		}

		/**
		 * Creates a named observable wrapper around the given delegate.
		 *
		 * @param name         human-readable task identifier, returned by {@link #toString()} and useful in logs
		 * @param delegate     the actual work to execute
		 * @param onCompletion callback fired exactly once when the task finishes or is cancelled;
		 *                     typically {@link ObservableThreadExecutor#queueSizeDecrementer}
		 */
		public ObservableRunnable(@Nonnull String name, @Nonnull Runnable delegate, @Nonnull Runnable onCompletion) {
			super(name, onCompletion);
			this.delegate = delegate;
		}

		@Nonnull
		@Override
		protected Object getDelegate() {
			return this.delegate;
		}

		/**
		 * Executes the delegate on the calling (worker) thread.
		 *
		 * Execution sequence:
		 *
		 * 1. Records the current thread in {@link #executingThread} so that a concurrent {@link #cancel()}
		 *    call can interrupt it.
		 * 2. Returns immediately if the future was already cancelled.
		 * 3. Restores the captured tracing context (MDC + thread-local) if one was present at construction time.
		 * 4. Invokes the delegate. On success the future is completed normally; on exception the future is
		 *    completed exceptionally, the error is logged, and the exception is rethrown wrapped in
		 *    {@link ObservableExecutionException}.
		 * 5. Clears the tracing context in a {@code finally} block.
		 * 6. Runs the shared {@link #finishExecution()} cleanup (fires {@link #onCompletion} exactly once,
		 *    clears {@link #executingThread}, and clears a leftover interrupt flag on cancellation).
		 *
		 * @throws ObservableExecutionException wrapping any exception thrown by the delegate
		 */
		@Override
		public void run() {
			this.executingThread = Thread.currentThread();
			try {
				if (this.future.isCancelled()) {
					return;
				}
				final boolean hasContext = !this.capturedContext.isEmpty();
				if (hasContext) {
					TracingContext.setContext(this.capturedContext);
				}
				try {
					this.delegate.run();
					this.future.complete(null);
				} catch (Exception e) {
					this.future.completeExceptionally(e);
					ObservableThreadExecutor.log.error("Uncaught exception in task.", e);
					throw new ObservableExecutionException(e);
				} finally {
					if (hasContext) {
						TracingContext.clearContext();
					}
				}
			} finally {
				finishExecution();
			}
		}
	}

	/**
	 * Wrapper around a {@link Callable} that implements the {@link CancellableCallable} interface.
	 *
	 * Mirrors {@link ObservableRunnable} in design and cancellation contract; see that class for a full
	 * description of responsibilities. The key difference is that {@link #call()} propagates the return
	 * value of the delegate through the {@link CompletableFuture}, and checked exceptions thrown by the
	 * delegate are re-thrown as-is (or re-wrapped in an {@link ObservableExecutionException} if they are
	 * not already {@link RuntimeException}s).
	 *
	 * @param <V> the type of the result
	 */
	static class ObservableCallable<V> extends AbstractObservableTask<V> implements CancellableCallable<V> {
		/**
		 * The actual work to execute. Never invoked directly by the outer executor — always invoked
		 * via {@link #call()} which adds tracing, lifecycle tracking, and interrupt cleanup around it.
		 */
		@Nonnull private final Callable<V> delegate;

		/**
		 * Creates an unnamed observable wrapper around the given delegate.
		 * The task description will fall back to {@link Object#toString()} of the delegate.
		 *
		 * @param delegate     the actual work to execute
		 * @param onCompletion callback fired exactly once when the task finishes or is cancelled;
		 *                     typically {@link ObservableThreadExecutor#queueSizeDecrementer}
		 */
		public ObservableCallable(@Nonnull Callable<V> delegate, @Nonnull Runnable onCompletion) {
			super(null, onCompletion);
			this.delegate = delegate;
		}

		/**
		 * Creates a named observable wrapper around the given delegate.
		 *
		 * @param name         human-readable task identifier, returned by {@link #toString()} and useful in logs
		 * @param delegate     the actual work to execute
		 * @param onCompletion callback fired exactly once when the task finishes or is cancelled;
		 *                     typically {@link ObservableThreadExecutor#queueSizeDecrementer}
		 */
		public ObservableCallable(@Nonnull String name, @Nonnull Callable<V> delegate, @Nonnull Runnable onCompletion) {
			super(name, onCompletion);
			this.delegate = delegate;
		}

		@Nonnull
		@Override
		protected Object getDelegate() {
			return this.delegate;
		}

		/**
		 * Executes the delegate on the calling (worker) thread and returns its result.
		 *
		 * Execution sequence mirrors {@link ObservableRunnable#run()} — see that method for a step-by-step
		 * description. The differences specific to this callable variant are:
		 *
		 * - Returns {@code null} if the task was already cancelled before execution started.
		 * - Returns the delegate's result on success.
		 * - Re-throws {@link RuntimeException}s from the delegate directly; checked exceptions are wrapped in
		 *   {@link ObservableExecutionException} for propagation through the executor, then
		 *   unwrapped again in the outer {@code catch} block so the original checked exception is visible to
		 *   callers.
		 *
		 * @return the result of the delegate, or {@code null} if the task was cancelled before execution
		 * @throws Exception any exception thrown by the delegate
		 */
		@Nullable
		@Override
		public V call() throws Exception {
			this.executingThread = Thread.currentThread();
			try {
				if (this.future.isCancelled()) {
					return null;
				}
				final boolean hasContext = !this.capturedContext.isEmpty();
				if (hasContext) {
					TracingContext.setContext(this.capturedContext);
				}
				try {
					final V result = this.delegate.call();
					this.future.complete(result);
					return result;
				} catch (Exception e) {
					this.future.completeExceptionally(e);
					ObservableThreadExecutor.log.error("Uncaught exception in task.", e);
					throw e instanceof RuntimeException re ?
						re : new ObservableExecutionException(e);
				} finally {
					if (hasContext) {
						TracingContext.clearContext();
					}
				}
			} catch (ObservableExecutionException e) {
				throw e.getDelegate();
			} finally {
				finishExecution();
			}
		}
	}

	/**
	 * {@link ThreadFactory} that configures each worker thread with a deterministic name, a configurable
	 * priority, daemon status, and a {@link LoggingUncaughtExceptionHandler}.
	 *
	 * Thread names follow the pattern {@code Evita-{name}-{N}} where {@code name} is the logical pool name
	 * (e.g. "request" or "transaction") and {@code N} is a monotonically increasing integer. Daemon status
	 * ensures the threads do not prevent JVM shutdown.
	 */
	private static final class EvitaThreadFactory implements ThreadFactory {
		/** Counter feeding the {@code N} suffix of created thread names. */
		private final AtomicInteger threadCounter = new AtomicInteger();
		/** Logical pool name embedded in the thread name. */
		@Nonnull private final String name;
		/** {@link Thread} priority in the range 1–10. */
		private final int priority;

		EvitaThreadFactory(@Nonnull String name, int priority) {
			this.name = name;
			this.priority = priority;
		}

		@Override
		public Thread newThread(@Nonnull Runnable runnable) {
			final Thread thread = new Thread(
				runnable, "Evita-" + this.name + "-" + this.threadCounter.incrementAndGet()
			);
			thread.setDaemon(true);
			thread.setPriority(this.priority);
			thread.setUncaughtExceptionHandler(LoggingUncaughtExceptionHandler.INSTANCE);
			return thread;
		}
	}

	/**
	 * A {@link LinkedBlockingQueue} that makes its owning {@link ThreadPoolExecutor} prefer *spawning a new
	 * worker thread* over *queueing* while the pool can still grow towards its maximum size.
	 *
	 * A vanilla {@link ThreadPoolExecutor} backed by an unbounded queue never creates more than `corePoolSize`
	 * threads, because {@link ThreadPoolExecutor#execute(Runnable)} only grows the pool past the core size once
	 * {@link java.util.concurrent.BlockingQueue#offer(Object) offer} fails. For a predominantly *blocking*
	 * workload that starves concurrency at the core size. This queue therefore returns {@code false} from
	 * {@link #offer(Runnable)} (forcing the executor to add a worker) whenever every existing worker is busy and
	 * the pool has not yet reached its maximum size.
	 *
	 * Once the pool is at its maximum size the queue behaves as a *bounded* backlog of {@link #queueLimit} tasks;
	 * further offers return {@code false}, routing the task to the executor's {@link GrowAwareRejectionHandler}.
	 * The {@link #inFlight} counter (submitted-but-not-finished tasks) distinguishes "all workers busy" from "an
	 * idle worker is available", so tasks are not needlessly handed to brand-new threads when an idle worker
	 * could pick them up.
	 */
	private static final class TaskQueue extends LinkedBlockingQueue<Runnable> {
		@Serial private static final long serialVersionUID = -8294585051583713000L;
		/** Maximum number of tasks allowed to wait once all workers are busy. */
		private final int queueLimit;
		/** Shared in-flight counter (submitted but not yet finished tasks). */
		private final transient AtomicInteger inFlight;
		/** Back-reference to the owning executor, set immediately after construction. */
		private transient ThreadPoolExecutor parent;

		TaskQueue(int queueLimit, @Nonnull AtomicInteger inFlight) {
			super();
			this.queueLimit = queueLimit;
			this.inFlight = inFlight;
		}

		/**
		 * Wires the owning executor. Must be called exactly once, right after the executor is constructed and
		 * before any task is submitted.
		 *
		 * @param parent the owning executor
		 */
		void setParent(@Nonnull ThreadPoolExecutor parent) {
			this.parent = parent;
		}

		@SuppressWarnings("NullableProblems")
		@Override
		public boolean offer(@Nonnull Runnable task) {
			final int poolSize = this.parent.getPoolSize();
			// an idle worker is available (more threads than in-flight tasks) -> hand it over via the backlog
			if (this.inFlight.get() <= poolSize) {
				return size() < this.queueLimit && super.offer(task);
			}
			// every worker is busy but the pool can still grow -> refuse so the executor spawns a new thread
			if (poolSize < this.parent.getMaximumPoolSize()) {
				return false;
			}
			// pool is at maximum size -> accept into the bounded backlog, or signal rejection when it is full
			return size() < this.queueLimit && super.offer(task);
		}

		/**
		 * Enqueues a task ignoring {@link #queueLimit}. Used for {@link UnrejectableTask}s that must never be
		 * rejected even when the backlog is full.
		 *
		 * @param task the task to enqueue
		 * @return {@code true} (the underlying unbounded queue always accepts)
		 */
		boolean enqueueUnbounded(@Nonnull Runnable task) {
			return super.offer(task);
		}

		/**
		 * Enqueues a task only while the backlog is below {@link #queueLimit}. Used by the rejection handler to
		 * resolve the rare race where {@link #offer(Runnable)} refused (to grow the pool) but the executor could
		 * not actually add a worker.
		 *
		 * @param task the task to enqueue
		 * @return {@code true} if the task was enqueued, {@code false} if the backlog is full
		 */
		boolean enqueueWithinLimit(@Nonnull Runnable task) {
			return size() < this.queueLimit && super.offer(task);
		}
	}

	/**
	 * {@link RejectedExecutionHandler} installed on the backing {@link ThreadPoolExecutor}. It is invoked when
	 * {@link TaskQueue#offer(Runnable)} returned {@code false} and the executor could not add a worker:
	 *
	 * - {@link UnrejectableTask}s (recognised via {@link ObservableRunnable#isUnrejectable()}) are force-enqueued
	 *   and never rejected.
	 * - Otherwise, if the bounded backlog still has room (the offer/addWorker race), the task is enqueued instead
	 *   of being dropped.
	 * - Only on genuine overflow does it delegate to {@link EvitaRejectingExecutorHandler}, which fires the
	 *   rejection JFR event, bumps the rejected-task counter, logs, and throws {@link RejectedExecutionException}.
	 */
	private static final class GrowAwareRejectionHandler implements RejectedExecutionHandler {
		/** Delegate that performs the actual (event-emitting, throwing) rejection. */
		private final EvitaRejectingExecutorHandler delegate;

		GrowAwareRejectionHandler(@Nonnull EvitaRejectingExecutorHandler delegate) {
			this.delegate = delegate;
		}

		@Override
		public void rejectedExecution(Runnable task, ThreadPoolExecutor executor) {
			if (executor.isShutdown()) {
				throw new RejectedExecutionException("Executor has been shut down.");
			}
			final TaskQueue queue = (TaskQueue) executor.getQueue();
			// never reject system-critical tasks
			if (task instanceof UnrejectableTask ||
				(task instanceof ObservableRunnable observableTask && observableTask.isUnrejectable())) {
				queue.enqueueUnbounded(task);
				return;
			}
			// resolve the offer()/addWorker() race: if the backlog still has room, enqueue rather than reject
			if (queue.enqueueWithinLimit(task)) {
				return;
			}
			// genuine overload — emit the rejection event, bump the counter, log and throw
			this.delegate.rejectedExecution();
		}
	}

	/**
	 * A {@link FutureTask} that also carries the {@link UnrejectableTask} marker.
	 *
	 * The {@link #submit} variants delegate to {@link java.util.concurrent.AbstractExecutorService#submit}, which
	 * wraps the supplied {@link ObservableRunnable}/{@link ObservableCallable} in a plain {@link FutureTask} before
	 * it reaches the {@link TaskQueue} and {@link GrowAwareRejectionHandler}. That wrapping hides the unrejectable
	 * marker from the rejection handler, so a system-critical task submitted via {@code submit(...)} would be
	 * wrongly rejected on a saturated pool. To preserve the "{@link UnrejectableTask}s are always enqueued"
	 * contract on the submit paths too, an unrejectable submission is wrapped in this marker-carrying future and
	 * dispatched via {@code execute(...)}, so {@link GrowAwareRejectionHandler} recognises it and force-enqueues it.
	 *
	 * @param <V> the type of the result produced by the wrapped task
	 */
	private static final class UnrejectableFutureTask<V> extends FutureTask<V> implements UnrejectableTask {

		UnrejectableFutureTask(@Nonnull Callable<V> callable) {
			super(callable);
		}

		UnrejectableFutureTask(@Nonnull Runnable runnable, @Nullable V result) {
			super(runnable, result);
		}

	}

	/**
	 * {@link UncaughtExceptionHandler} that logs uncaught exceptions at ERROR level.
	 *
	 * A single shared instance ({@link #INSTANCE}) is installed on every worker thread created by
	 * {@link EvitaThreadFactory}.
	 */
	private static class LoggingUncaughtExceptionHandler implements UncaughtExceptionHandler {
		/** Singleton instance shared across all worker threads and the pool. */
		public static final LoggingUncaughtExceptionHandler INSTANCE = new LoggingUncaughtExceptionHandler();

		@Override
		public void uncaughtException(Thread t, Throwable e) {
			ObservableThreadExecutor.log.error("Uncaught exception in thread {}", t.getName(), e);
		}

	}

	/**
	 * Internal transport exception used to smuggle a checked exception through the unchecked
	 * {@link Runnable#run()} / executor boundary inside {@link ObservableRunnable#run()}.
	 *
	 * In {@link ObservableRunnable#run()} any exception from the delegate is caught, logged, and then
	 * re-thrown as an {@link ObservableExecutionException} so it can propagate through the
	 * executor. In {@link ObservableCallable#call()} the outer {@code catch} block unwraps
	 * it back to the original checked exception so that callers see the original type.
	 *
	 * This class is intentionally private — it must never escape the containing executor.
	 */
	private static class ObservableExecutionException extends RuntimeException {
		@Serial private static final long serialVersionUID = -7044403627268312520L;
		/** The original checked exception being transported. */
		@Getter private final Exception delegate;

		/**
		 * @param cause the checked exception to wrap and transport
		 */
		public ObservableExecutionException(@Nonnull Exception cause) {
			super(cause);
			this.delegate = cause;
		}

	}

}
