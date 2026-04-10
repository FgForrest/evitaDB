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
import io.evitadb.core.metric.event.system.BackgroundTaskTimedOutEvent;
import io.evitadb.core.metric.event.system.ForkJoinPoolSaturatedEvent;
import jdk.jfr.Event;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.lang.Thread.UncaughtExceptionHandler;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BiFunction;

/**
 * An {@link ExecutorService} implementation built on top of {@link ForkJoinPool} that adds task lifecycle
 * observability, bounded queue depth, interrupt-based cancellation, and tracing context propagation.
 *
 * Two instances are created per evitaDB server — one for incoming API requests (higher priority) and one for
 * transaction processing (lower priority). Both are configured via {@link ThreadPoolOptions}.
 *
 * Key design points:
 *
 * - **Bounded queue**: before any task is dispatched to the underlying pool, an internal atomic counter is
 *   incremented and compared against `{@link ThreadPoolOptions#queueSize()}`. If the limit is exceeded,
 *   a {@link RejectedExecutionException} is thrown immediately and {@link #rejectedTaskCount} is bumped.
 *   The counter is decremented when the task finishes (or is cancelled) via the `onCompletion` callback.
 * - **Cancellable tasks**: every submitted {@link Runnable}/{@link Callable} is wrapped in
 *   {@link ObservableRunnable}/{@link ObservableCallable} which record the executing thread so that
 *   {@link CancellableTask#cancel()} can call {@link Thread#interrupt()} on it. The interrupt flag is
 *   cleared in the `finally` block so it cannot leak to the next task picked up by the same
 *   {@link ForkJoinPool} worker thread.
 * - **Tracing context propagation**: the MDC and thread-local tracing state present on the submitting thread
 *   is captured at construction time via {@link TracingContext#captureContext()} and restored on the worker
 *   thread for the duration of the task, enabling correlated logging across thread boundaries.
 * - **JFR statistics**: {@link #emitPoolStatistics} publishes a JFR event carrying incremental steal counts
 *   and other pool metrics. It is called periodically by {@link io.evitadb.core.Evita} via
 *   {@code FlightRecorder.addPeriodicEvent}.
 * - **Test mode**: when {@code immediateExecutorService} is {@code true} (test environments), an
 *   {@link ImmediateExecutorService} is used instead of {@link ForkJoinPool} so that tasks run
 *   synchronously on the calling thread, eliminating concurrency in unit tests.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Slf4j
public class ObservableThreadExecutor implements ObservableExecutorServiceWithCancellationSupport {
	/**
	 * Java based fork join pool that does the heavy lifting.
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
	 * Rejection handler that is invoked when the executor service rejects a task.
	 */
	private final EvitaRejectingExecutorHandler rejectedExecutionHandler;
	/**
	 * Atomic counter tracking the number of tasks currently in the queue (submitted but not yet finished).
	 * Incremented before dispatch and decremented via the `onCompletion` callback once a task completes
	 * or is cancelled. Used to enforce {@link #queueLimit}.
	 */
	private final AtomicInteger queueSize = new AtomicInteger();
	/**
	 * Maximum number of tasks that may be simultaneously queued (in-flight + waiting). Sourced from
	 * {@link ThreadPoolOptions#queueSize()}. When {@link #queueSize} exceeds this value, new task
	 * submissions are rejected with a {@link RejectedExecutionException}.
	 * Note that the effective capacity is {@code queueLimit + 1}: the check is `{@code > queueLimit}`,
	 * so a task at exactly {@code queueLimit} is still accepted.
	 */
	private final int queueLimit;
	/**
	 * Cached method reference for decrementing queue size, avoiding per-task lambda allocation.
	 */
	private final Runnable queueSizeDecrementer = this.queueSize::decrementAndGet;
	/**
	 * Last observed steal count of the request executor.
	 */
	private long executorSteals;

	/**
	 * Creates a new executor configured according to the supplied options.
	 *
	 * In production mode a {@link ForkJoinPool} is created in async mode (LIFO work-stealing) with parallelism
	 * capped at `min(options.minThreadCount(), availableProcessors)`. Worker threads are daemon threads named
	 * `Evita-{@code name}-N` with the configured priority.
	 *
	 * @param name                   logical name of this executor, used in thread names, log messages, and JFR events
	 * @param options                pool sizing and priority settings; see {@link ThreadPoolOptions}
	 * @param immediateExecutorService when {@code true}, an {@link ImmediateExecutorService} is used instead of
	 *                               {@link ForkJoinPool} — intended for test environments where synchronous
	 *                               execution avoids concurrency-related flakiness
	 */
	public ObservableThreadExecutor(
		@Nonnull String name,
		@Nonnull ThreadPoolOptions options,
		boolean immediateExecutorService
	) {
		final int processorsCount = Runtime.getRuntime().availableProcessors();
		this.rejectedExecutionHandler = new EvitaRejectingExecutorHandler(name, this.rejectedTaskCount::increment);
		this.queueLimit = options.queueSize();
		this.executorService = immediateExecutorService ?
			// in test environment we use a simplified executor that runs tasks immediately (synchronously)
			new ImmediateExecutorService() :
			// in standard environment we use a ForkJoinPool that allows to run tasks asynchronously
			new ForkJoinPool(
				Math.min(options.minThreadCount(), processorsCount),
				pool -> new EvitaWorkerThread(pool, name, options.threadPriority()),
				LoggingUncaughtExceptionHandler.INSTANCE,
				true,
				options.minThreadCount(),
				options.maxThreadCount(),
				1,
				pool -> {
					new ForkJoinPoolSaturatedEvent(name).commit();
					log.warn(
						"ObservableThreadExecutor ForkJoinPool `{}` saturated " +
							"— all compensating threads exhausted, " +
							"allowing current thread to block.",
						name
					);
					return true;
				},
				60,
				TimeUnit.SECONDS
			);
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
			this.rejectedExecutionHandler.rejectedExecution();
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
	 * @throws RejectedExecutionException if the queue limit is exceeded or the pool has been shut down
	 */
	@Nonnull
	@Override
	public <T> Future<T> submit(@Nonnull Callable<T> task) {
		ObservableCallable<T> wrapped = null;
		try {
			wrapped = wrapToCancellableTask(task);
			final Future<T> result = this.executorService.submit(wrapped);
			this.submittedTaskCount.increment();
			return result;
		} catch (RejectedExecutionException e) {
			if (wrapped != null) {
				this.queueSize.decrementAndGet();
			}
			this.rejectedExecutionHandler.rejectedExecution();
			throw e;
		}
	}

	/**
	 * Wraps the runnable in an {@link ObservableRunnable}, enforces the queue limit, and delegates to the
	 * underlying pool. The supplied {@code result} value is returned by the {@link Future} on completion.
	 *
	 * @throws RejectedExecutionException if the queue limit is exceeded or the pool has been shut down
	 */
	@Nonnull
	@Override
	public <T> Future<T> submit(@Nonnull Runnable task, T result) {
		ObservableRunnable wrapped = null;
		try {
			wrapped = wrapToCancellableTask(task);
			final Future<T> forkJoinTask = this.executorService.submit(wrapped, result);
			this.submittedTaskCount.increment();
			return forkJoinTask;
		} catch (RejectedExecutionException e) {
			if (wrapped != null) {
				this.queueSize.decrementAndGet();
			}
			this.rejectedExecutionHandler.rejectedExecution();
			throw e;
		}
	}

	/**
	 * Wraps the runnable in an {@link ObservableRunnable}, enforces the queue limit, and delegates to the
	 * underlying pool. The returned {@link Future} completes with {@code null} when the task finishes.
	 *
	 * @throws RejectedExecutionException if the queue limit is exceeded or the pool has been shut down
	 */
	@Nonnull
	@Override
	public Future<?> submit(@Nonnull Runnable task) {
		ObservableRunnable wrapped = null;
		try {
			wrapped = wrapToCancellableTask(task);
			final Future<?> forkJoinTask = this.executorService.submit(wrapped);
			this.submittedTaskCount.increment();
			return forkJoinTask;
		} catch (RejectedExecutionException e) {
			if (wrapped != null) {
				this.queueSize.decrementAndGet();
			}
			this.rejectedExecutionHandler.rejectedExecution();
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
		try {
			final List<ObservableCallable<T>> tasksToSubmit = tasks.stream().map(this::wrapToCancellableTask).toList();
			final List<Future<T>> futures = this.executorService.invokeAll(tasksToSubmit);
			this.submittedTaskCount.add(futures.size());
			return futures;
		} catch (RejectedExecutionException e) {
			this.rejectedExecutionHandler.rejectedExecution();
			throw e;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RejectedExecutionException("Thread was interrupted while waiting for tasks to complete.", e);
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
		try {
			final List<ObservableCallable<T>> tasksToSubmit = tasks.stream().map(this::wrapToCancellableTask).toList();
			final List<Future<T>> futures = this.executorService.invokeAll(tasksToSubmit, timeout, unit);
			this.submittedTaskCount.add(futures.size());
			return futures;
		} catch (RejectedExecutionException e) {
			this.rejectedExecutionHandler.rejectedExecution();
			throw e;
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
		try {
			final List<ObservableCallable<T>> tasksToSubmit = tasks.stream().map(this::wrapToCancellableTask).toList();
			final T result = this.executorService.invokeAny(tasksToSubmit);
			this.submittedTaskCount.increment();
			return result;
		} catch (RejectedExecutionException e) {
			this.rejectedExecutionHandler.rejectedExecution();
			throw e;
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
		try {
			final List<ObservableCallable<T>> tasksToSubmit = tasks.stream().map(this::wrapToCancellableTask).toList();
			final T result = this.executorService.invokeAny(tasksToSubmit, timeout, unit);
			this.submittedTaskCount.increment();
			return result;
		} catch (RejectedExecutionException e) {
			this.rejectedExecutionHandler.rejectedExecution();
			throw e;
		}
	}

	/**
	 * Publishes a JFR event with current {@link ForkJoinPool} statistics, called periodically by
	 * {@link io.evitadb.core.Evita} via {@code FlightRecorder.addPeriodicEvent}.
	 *
	 * The steal count reported in the event is **incremental** — it reflects only the steals that occurred
	 * since the last call, not a cumulative total. This makes the metric suitable for rate-based dashboards.
	 * The raw pool reference is passed to the factory so it can also read other live metrics such as queued
	 * task count and active thread count.
	 *
	 * When the underlying executor is an {@link ImmediateExecutorService} (test mode), this method is a no-op.
	 *
	 * @param eventFactory a function that receives the live {@link ForkJoinPool} and the incremental steal
	 *                     count and returns a ready-to-commit JFR {@link Event}
	 */
	public void emitPoolStatistics(@Nonnull BiFunction<ForkJoinPool, Long, Event> eventFactory) {
		try {
			if (this.executorService instanceof ForkJoinPool fjp) {
				// emit statistics of the pool
				final long currentStealCount = fjp.getStealCount();
				eventFactory.apply(fjp, currentStealCount - this.executorSteals).commit();
				this.executorSteals = currentStealCount;
			} else {
				// emit no statistics if the pool is not a ForkJoinPool
			}
		} catch (Throwable t) {
			log.error("Emitting observability events failed!", t);
		}
	}

	/**
	 * Enforces the queue limit and, if the runnable is not already an {@link ObservableRunnable}, wraps it
	 * in one (capturing the current tracing context and wiring the {@link #queueSizeDecrementer} callback).
	 *
	 * The queue-size increment is performed with a compare-and-reject pattern: if the counter already exceeds
	 * {@link #queueLimit} the increment is rolled back immediately and a {@link RejectedExecutionException}
	 * is thrown before the task ever reaches the pool.
	 *
	 * @param runnable the task to run; if it is already an {@link ObservableRunnable} it is returned as-is
	 *                 to avoid double-wrapping and double-decrement of the queue counter
	 * @return the observable wrapper, ready to be submitted to the underlying pool
	 * @throws RejectedExecutionException if the number of in-flight tasks exceeds {@link #queueLimit}
	 */
	@Nonnull
	private ObservableRunnable wrapToCancellableTask(@Nonnull Runnable runnable) {
		if (this.queueSize.getAndIncrement() > this.queueLimit) {
			final boolean unrejectable = runnable instanceof UnrejectableTask ||
				(runnable instanceof ObservableRunnable or && or.isUnrejectable());
			if (!unrejectable) {
				this.queueSize.decrementAndGet();
				throw new RejectedExecutionException("Task queue limit exceeded. Task rejected.");
			}
		}
		return runnable instanceof ObservableRunnable or
			? or
			: new ObservableRunnable(runnable, this.queueSizeDecrementer);
	}

	/**
	 * Enforces the queue limit and, if the callable is not already an {@link ObservableCallable}, wraps it
	 * in one (capturing the current tracing context and wiring the {@link #queueSizeDecrementer} callback).
	 *
	 * @param callable the task to run; if it is already an {@link ObservableCallable} it is returned as-is
	 *                 to avoid double-wrapping and double-decrement of the queue counter
	 * @param <V>      the type of the result produced by the callable
	 * @return the observable wrapper, ready to be submitted to the underlying pool
	 * @throws RejectedExecutionException if the number of in-flight tasks exceeds {@link #queueLimit}
	 */
	@Nonnull
	private <V> ObservableCallable<V> wrapToCancellableTask(@Nonnull Callable<V> callable) {
		if (this.queueSize.getAndIncrement() > this.queueLimit) {
			final boolean unrejectable = callable instanceof UnrejectableTask ||
				(callable instanceof ObservableCallable<V> oc && oc.isUnrejectable());
			if (!unrejectable) {
				this.queueSize.decrementAndGet();
				throw new RejectedExecutionException("Task queue limit exceeded. Task rejected.");
			}
		}
		return callable instanceof ObservableCallable<V> or
			? or
			: new ObservableCallable<>(callable, this.queueSizeDecrementer);
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
	 * Cancellation contract: calling {@link #cancel()} before {@link #run()} causes the run to return
	 * immediately without invoking the delegate. Calling {@link #cancel()} during {@link #run()} interrupts
	 * the worker thread; the task still clears the interrupt flag in its {@code finally} block so it does
	 * not leak into the next task on the same {@link ForkJoinPool} worker.
	 */
	static class ObservableRunnable implements CancellableRunnable {
		/**
		 * Human-readable description of the task; {@code null} when the unnamed constructor was used,
		 * in which case {@link #toString()} delegates to the delegate's {@code toString()}.
		 */
		private final String name;
		/**
		 * The actual work to execute. Never invoked directly by the outer executor — always invoked
		 * via {@link #run()} which adds tracing, lifecycle tracking, and interrupt cleanup around it.
		 */
		private final Runnable delegate;
		/**
		 * Snapshot of the submitting thread's MDC and thread-local tracing state, captured eagerly at
		 * construction time. Restored onto the worker thread in {@link #run()} so that log entries emitted
		 * by the delegate carry the same trace/client IDs as the original request.
		 * May be {@link TracingContext.CapturedContext#EMPTY} when no tracing context was active.
		 */
		private final TracingContext.CapturedContext capturedContext = TracingContext.captureContext();
		/**
		 * Completes when the task finishes: normally via {@link CompletableFuture#complete(Object)},
		 * exceptionally via {@link CompletableFuture#completeExceptionally(Throwable)}, or by cancellation
		 * via {@link CompletableFuture#cancel(boolean)}. Exposed to callers through {@link #completionStage()}.
		 */
		private final CompletableFuture<Void> future = new CompletableFuture<>();
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
		@Nullable private volatile Thread executingThread;

		/**
		 * Creates an unnamed observable wrapper around the given delegate.
		 * The task description will fall back to {@link Object#toString()} of the delegate.
		 *
		 * @param delegate     the actual work to execute
		 * @param onCompletion callback fired exactly once when the task finishes or is cancelled;
		 *                     typically {@link ObservableThreadExecutor#queueSizeDecrementer}
		 */
		public ObservableRunnable(@Nonnull Runnable delegate, @Nonnull Runnable onCompletion) {
			this.name = null;
			this.delegate = delegate;
			this.onCompletion = onCompletion;
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
			this.name = name;
			this.delegate = delegate;
			this.onCompletion = onCompletion;
		}

		@Override
		public boolean isFinished() {
			return this.future.isDone();
		}

		/**
		 * Returns whether the delegate implements {@link UnrejectableTask}, meaning this task
		 * should bypass queue limit rejection.
		 */
		public boolean isUnrejectable() {
			return this.delegate instanceof UnrejectableTask;
		}

		/**
		 * Cancels this task.
		 *
		 * If the task has not started yet, the next call to {@link #run()} will return immediately without
		 * executing the delegate. If the task is currently executing, the worker thread is interrupted.
		 * In both cases {@link #onCompletion} is fired (at most once) and the internal future is
		 * transitioned to the cancelled state.
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
		public CompletableFuture<Void> completionStage() {
			return this.future;
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
		 * 6. Fires {@link #onCompletion} exactly once (via {@link #completionFired}) and clears
		 *    {@link #executingThread}.
		 * 7. Clears the thread-interrupt flag if the task was cancelled, preventing it from leaking to the
		 *    next task the {@link ForkJoinPool} worker picks up.
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
				fireCompletion();
				this.executingThread = null;
				// clear interrupt flag to prevent leaking to ForkJoinPool's next task
				if (this.future.isCancelled()) {
					//noinspection ResultOfMethodCallIgnored
					Thread.interrupted();
				}
			}
		}

		private void fireCompletion() {
			if (this.completionFired.compareAndSet(false, true)) {
				this.onCompletion.run();
			}
		}

		@Override
		public String toString() {
			return this.name == null ? this.delegate.toString() : this.name;
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
	static class ObservableCallable<V> implements CancellableCallable<V> {
		/**
		 * Human-readable description of the task; {@code null} when the unnamed constructor was used,
		 * in which case {@link #toString()} delegates to the delegate's {@code toString()}.
		 */
		private final String name;
		/**
		 * The actual work to execute. Never invoked directly by the outer executor — always invoked
		 * via {@link #call()} which adds tracing, lifecycle tracking, and interrupt cleanup around it.
		 */
		private final Callable<V> delegate;
		/**
		 * Snapshot of the submitting thread's MDC and thread-local tracing state, captured eagerly at
		 * construction time. Restored onto the worker thread in {@link #call()} so that log entries emitted
		 * by the delegate carry the same trace/client IDs as the original request.
		 * May be {@link TracingContext.CapturedContext#EMPTY} when no tracing context was active.
		 */
		private final TracingContext.CapturedContext capturedContext = TracingContext.captureContext();
		/**
		 * Completes when the task finishes: normally via {@link CompletableFuture#complete(Object)},
		 * exceptionally via {@link CompletableFuture#completeExceptionally(Throwable)}, or by cancellation
		 * via {@link CompletableFuture#cancel(boolean)}. Exposed to callers through {@link #completionStage()}.
		 */
		private final CompletableFuture<V> future = new CompletableFuture<>();
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
		@Nullable private volatile Thread executingThread;

		/**
		 * Creates an unnamed observable wrapper around the given delegate.
		 * The task description will fall back to {@link Object#toString()} of the delegate.
		 *
		 * @param delegate     the actual work to execute
		 * @param onCompletion callback fired exactly once when the task finishes or is cancelled;
		 *                     typically {@link ObservableThreadExecutor#queueSizeDecrementer}
		 */
		public ObservableCallable(@Nonnull Callable<V> delegate, @Nonnull Runnable onCompletion) {
			this.name = null;
			this.delegate = delegate;
			this.onCompletion = onCompletion;
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
			this.name = name;
			this.delegate = delegate;
			this.onCompletion = onCompletion;
		}

		@Override
		public boolean isFinished() {
			return this.future.isDone();
		}

		/**
		 * Returns whether the delegate implements {@link UnrejectableTask}, meaning this task
		 * should bypass queue limit rejection.
		 */
		public boolean isUnrejectable() {
			return this.delegate instanceof UnrejectableTask;
		}

		/**
		 * Cancels this task.
		 *
		 * If the task has not started yet, the next call to {@link #call()} will return {@code null} immediately
		 * without executing the delegate. If the task is currently executing, the worker thread is interrupted.
		 * In both cases {@link #onCompletion} is fired (at most once) and the internal future is
		 * transitioned to the cancelled state.
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
		 * Executes the delegate on the calling (worker) thread and returns its result.
		 *
		 * Execution sequence mirrors {@link ObservableRunnable#run()} — see that method for a step-by-step
		 * description. The differences specific to this callable variant are:
		 *
		 * - Returns {@code null} if the task was already cancelled before execution started.
		 * - Returns the delegate's result on success.
		 * - Re-throws {@link RuntimeException}s from the delegate directly; checked exceptions are wrapped in
		 *   {@link ObservableExecutionException} for propagation through the {@link ForkJoinPool}, then
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
				fireCompletion();
				this.executingThread = null;
				// clear interrupt flag to prevent leaking to ForkJoinPool's next task
				if (this.future.isCancelled()) {
					//noinspection ResultOfMethodCallIgnored
					Thread.interrupted();
				}
			}
		}

		private void fireCompletion() {
			if (this.completionFired.compareAndSet(false, true)) {
				this.onCompletion.run();
			}
		}

		@Override
		public String toString() {
			return this.name == null ? this.delegate.toString() : this.name;
		}
	}

	/**
	 * Custom {@link ForkJoinWorkerThread} that configures each worker thread with a deterministic name,
	 * a configurable priority, daemon status, and a {@link LoggingUncaughtExceptionHandler}.
	 *
	 * Thread names follow the pattern {@code Evita-{name}-{N}} where {@code name} is the logical pool name
	 * (e.g. "request" or "transaction") and {@code N} is a monotonically increasing integer from
	 * {@link #THREAD_COUNTER}. Daemon status ensures the threads do not prevent JVM shutdown.
	 */
	private static class EvitaWorkerThread extends ForkJoinWorkerThread {
		/**
		 * Counter monitoring the number of threads this factory created.
		 */
		private static final AtomicInteger THREAD_COUNTER = new AtomicInteger();

		/**
		 * @param pool     the owning {@link ForkJoinPool}
		 * @param name     logical pool name embedded in the thread name
		 * @param priority {@link Thread} priority in the range 1–10
		 */
		protected EvitaWorkerThread(@Nonnull ForkJoinPool pool, @Nonnull String name, int priority) {
			super(pool);
			setDaemon(true);
			setName("Evita-" + name + "-" + THREAD_COUNTER.incrementAndGet());
			setPriority(priority);
			setUncaughtExceptionHandler(LoggingUncaughtExceptionHandler.INSTANCE);
		}
	}

	/**
	 * {@link UncaughtExceptionHandler} that logs uncaught exceptions at ERROR level.
	 *
	 * A single shared instance ({@link #INSTANCE}) is installed on every {@link EvitaWorkerThread}
	 * and on the {@link ForkJoinPool} itself.
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
	 * {@link Runnable#run()} / {@link ForkJoinPool} boundary inside {@link ObservableRunnable#run()}.
	 *
	 * In {@link ObservableRunnable#run()} any exception from the delegate is caught, logged, and then
	 * re-thrown as an {@link ObservableExecutionException} so it can propagate through the
	 * {@link ForkJoinPool}. In {@link ObservableCallable#call()} the outer {@code catch} block unwraps
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
