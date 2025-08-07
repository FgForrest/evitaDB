/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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
import io.evitadb.core.metric.event.system.BackgroundTaskTimedOutEvent;
import io.evitadb.exception.EvitaInvalidUsageException;
import jdk.jfr.Event;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.lang.Thread.UncaughtExceptionHandler;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiFunction;

import static java.util.Optional.ofNullable;

/**
 * This class implementation of {@link ExecutorService} that allows to process asynchronous tasks in a safe and limited
 * manner. It is based on the Java ForkJoinPool and provides additional features like task timeout and task queue
 * monitoring.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Slf4j
public class ObservableThreadExecutor implements ObservableExecutorServiceWithHardDeadline {
	private static final int BUFFER_CAPACITY = 512;
	/**
	 * Buffer used for purging finished tasks.
	 */
	private final ArrayList<WeakReference<ObservableTask>> buffer = new ArrayList<>(BUFFER_CAPACITY);
	/**
	 * Lock synchronizing access to the buffer and purge operation.
	 */
	private final ReentrantLock bufferLock = new ReentrantLock();
	/**
	 * Name used in log messages and events.
	 */
	private final String name;
	/**
	 * Java based fork join pool that does the heavy lifting.
	 */
	private final ExecutorService executorService;
	/**
	 * Counter monitoring the number of tasks submitted to the executor service.
	 */
	private final AtomicLong submittedTaskCount = new AtomicLong();
	/**
	 * Counter monitoring the number of tasks rejected by the executor service.
	 */
	private final AtomicLong rejectedTaskCount = new AtomicLong();
	/**
	 * Rejection handler that is invoked when the executor service rejects a task.
	 */
	private final EvitaRejectingExecutorHandler rejectedExecutionHandler;
	/**
	 * Timeout in milliseconds after which tasks are considered timed out and are removed from the queue.
	 */
	private final long timeoutInMilliseconds;
	/**
	 * Queue that holds the tasks that are currently being executed or waiting to be executed. It could also contain
	 * already finished tasks that are subject to be removed.
	 */
	private final ArrayBlockingQueue<WeakReference<ObservableTask>> queue;
	/**
	 * Last observed steal count of the request executor.
	 */
	private long executorSteals;

	public ObservableThreadExecutor(
		@Nonnull String name,
		@Nonnull ThreadPoolOptions options,
		@Nonnull Scheduler scheduler,
		long timeoutInMilliseconds,
		boolean immediateExecutorService
	) {
		this.name = name;
		final int processorsCount = Runtime.getRuntime().availableProcessors();
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
				null,
				60,
				TimeUnit.SECONDS
			);
		this.rejectedExecutionHandler = new EvitaRejectingExecutorHandler(name, this.rejectedTaskCount::incrementAndGet);
		this.timeoutInMilliseconds = timeoutInMilliseconds;
		// create queue with double the size of the configured queue size to have some breathing room
		this.queue = new ArrayBlockingQueue<>(options.queueSize() << 1);
		if (timeoutInMilliseconds > -1 && timeoutInMilliseconds < 100L) {
			throw new EvitaInvalidUsageException(
				"The timeout must be at least 100 milliseconds."
			);
		} else if (timeoutInMilliseconds > 0) {
			scheduler.schedule(
				this::cancelTimedOutTasks,
				timeoutInMilliseconds,
				TimeUnit.MILLISECONDS
			);
		}
	}

	@Override
	public long getDefaultTimeoutInMilliseconds() {
		return this.timeoutInMilliseconds;
	}

	@Override
	@Nonnull
	public Runnable createTask(@Nonnull String name, @Nonnull Runnable lambda) {
		return new ObservableRunnable(name, lambda, this.timeoutInMilliseconds);
	}

	@Override
	@Nonnull
	public Runnable createTask(@Nonnull Runnable lambda) {
		final StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
		return new ObservableRunnable(
			stackTrace.length > 1 ? stackTrace[1].toString() : "Unknown",
			lambda, this.timeoutInMilliseconds
		);
	}

	@Override
	@Nonnull
	public Runnable createTask(@Nonnull String name, @Nonnull Runnable lambda, long timeoutInMilliseconds) {
		return new ObservableRunnable(name, lambda, timeoutInMilliseconds);
	}

	@Override
	@Nonnull
	public Runnable createTask(@Nonnull Runnable lambda, long timeoutInMilliseconds) {
		final StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
		return new ObservableRunnable(
			stackTrace.length > 1 ? stackTrace[1].toString() : "Unknown",
			lambda, timeoutInMilliseconds
		);
	}

	@Override
	@Nonnull
	public <V> Callable<V> createTask(@Nonnull String name, @Nonnull Callable<V> lambda) {
		return new ObservableCallable<>(name, lambda, this.timeoutInMilliseconds);
	}

	@Override
	@Nonnull
	public <V> Callable<V> createTask(@Nonnull Callable<V> lambda) {
		final StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
		return new ObservableCallable<>(
			stackTrace.length > 1 ? stackTrace[1].toString() : "Unknown",
			lambda, this.timeoutInMilliseconds
		);
	}

	@Override
	@Nonnull
	public <V> Callable<V> createTask(@Nonnull String name, @Nonnull Callable<V> lambda, long timeoutInMilliseconds) {
		return new ObservableCallable<>(name, lambda, timeoutInMilliseconds);
	}

	@Override
	@Nonnull
	public <V> Callable<V> createTask(@Nonnull Callable<V> lambda, long timeoutInMilliseconds) {
		final StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
		return new ObservableCallable<>(
			stackTrace.length > 1 ? stackTrace[1].toString() : "Unknown",
			lambda, timeoutInMilliseconds
		);
	}

	@Override
	public long getSubmittedTaskCount() {
		return this.submittedTaskCount.get();
	}

	@Override
	public long getRejectedTaskCount() {
		return this.rejectedTaskCount.get();
	}

	@Override
	public void execute(@Nonnull Runnable command) {
		try {
			this.executorService.execute(registerTask(command, this.timeoutInMilliseconds));
			this.submittedTaskCount.incrementAndGet();
		} catch (RejectedExecutionException e) {
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

	/*
	 * PRIVATE METHODS
	 */

	@Override
	public boolean awaitTermination(long timeout, @Nonnull TimeUnit unit) throws InterruptedException {
		return this.executorService.awaitTermination(timeout, unit);
	}

	@Nonnull
	@Override
	public <T> Future<T> submit(@Nonnull Callable<T> task) {
		try {
			final Future<T> result = this.executorService.submit(registerTask(task, this.timeoutInMilliseconds));
			this.submittedTaskCount.incrementAndGet();
			return result;
		} catch (RejectedExecutionException e) {
			this.rejectedExecutionHandler.rejectedExecution();
			throw e;
		}
	}

	@Nonnull
	@Override
	public <T> Future<T> submit(@Nonnull Runnable task, T result) {
		try {
			final Future<T> forkJoinTask = this.executorService.submit(registerTask(task, this.timeoutInMilliseconds), result);
			this.submittedTaskCount.incrementAndGet();
			return forkJoinTask;
		} catch (RejectedExecutionException e) {
			this.rejectedExecutionHandler.rejectedExecution();
			throw e;
		}
	}

	@Nonnull
	@Override
	public Future<?> submit(@Nonnull Runnable task) {
		try {
			final Future<?> forkJoinTask = this.executorService.submit(registerTask(task, this.timeoutInMilliseconds));
			this.submittedTaskCount.incrementAndGet();
			return forkJoinTask;
		} catch (RejectedExecutionException e) {
			this.rejectedExecutionHandler.rejectedExecution();
			throw e;
		}
	}

	@Nonnull
	@Override
	public <T> List<Future<T>> invokeAll(@Nonnull Collection<? extends Callable<T>> tasks) {
		try {
			final List<Callable<T>> tasksToSubmit = tasks.stream().map(it -> this.registerTask(it, this.timeoutInMilliseconds)).toList();
			final List<Future<T>> futures = this.executorService.invokeAll(tasksToSubmit);
			this.submittedTaskCount.addAndGet(futures.size());
			return futures;
		} catch (RejectedExecutionException e) {
			this.rejectedExecutionHandler.rejectedExecution();
			throw e;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RejectedExecutionException("Thread was interrupted while waiting for tasks to complete.", e);
		}
	}

	@Nonnull
	@Override
	public <T> List<Future<T>> invokeAll(@Nonnull Collection<? extends Callable<T>> tasks, long timeout, @Nonnull TimeUnit unit) {
		try {
			final long theTimeout = Math.min(this.timeoutInMilliseconds, unit.toMillis(timeout));
			final List<Callable<T>> tasksToSubmit = tasks.stream().map(it -> this.registerTask(it, theTimeout)).toList();
			final List<Future<T>> futures = this.executorService.invokeAll(tasksToSubmit);
			this.submittedTaskCount.addAndGet(futures.size());
			return futures;
		} catch (RejectedExecutionException e) {
			this.rejectedExecutionHandler.rejectedExecution();
			throw e;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RejectedExecutionException("Thread was interrupted while waiting for tasks to complete.", e);
		}
	}

	@Nonnull
	@Override
	public <T> T invokeAny(@Nonnull Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException {
		try {
			final List<Callable<T>> tasksToSubmit = tasks.stream().map(it -> this.registerTask(it, this.timeoutInMilliseconds)).toList();
			final T result = this.executorService.invokeAny(tasksToSubmit);
			this.submittedTaskCount.incrementAndGet();
			return result;
		} catch (RejectedExecutionException e) {
			this.rejectedExecutionHandler.rejectedExecution();
			throw e;
		}
	}

	@Override
	public <T> T invokeAny(@Nonnull Collection<? extends Callable<T>> tasks, long timeout, @Nonnull TimeUnit unit) throws InterruptedException, ExecutionException {
		try {
			final long theTimeout = Math.min(this.timeoutInMilliseconds, unit.toMillis(timeout));
			final List<Callable<T>> tasksToSubmit = tasks.stream().map(it -> this.registerTask(it, theTimeout)).toList();
			final T result = this.executorService.invokeAny(tasksToSubmit);
			this.submittedTaskCount.incrementAndGet();
			return result;
		} catch (RejectedExecutionException e) {
			this.rejectedExecutionHandler.rejectedExecution();
			throw e;
		}
	}

	/**
	 * Emits statistics of the ForkJoinPool associated with the request executor.
	 */
	public void emitPoolStatistics(@Nonnull BiFunction<ForkJoinPool, Long, Event> eventFactory) {
		if (this.executorService instanceof ForkJoinPool fjp) {
			// emit statistics of the pool
			final long currentStealCount = fjp.getStealCount();
			eventFactory.apply(fjp, currentStealCount - this.executorSteals).commit();
			this.executorSteals = currentStealCount;
		} else {
			// emit no statistics if the pool is not a ForkJoinPool
		}
	}

	/**
	 * Register a task with a particular timeout.
	 *
	 * @param runnable              the task to run
	 * @param timeoutInMilliseconds the timeout in milliseconds
	 * @return wrapped runnable into an observable task
	 */
	@Nonnull
	private Runnable registerTask(@Nonnull Runnable runnable, long timeoutInMilliseconds) {
		return addTaskToQueue(runnable instanceof ObservableRunnable or ? or : new ObservableRunnable(runnable, timeoutInMilliseconds));
	}

	/**
	 * Register a task with a particular timeout.
	 *
	 * @param callable              the task to run
	 * @param timeoutInMilliseconds the timeout in milliseconds
	 * @param <V>                   the type of the result
	 * @return wrapped callable into an observable task
	 */
	@Nonnull
	private <V> Callable<V> registerTask(@Nonnull Callable<V> callable, long timeoutInMilliseconds) {
		return addTaskToQueue(callable instanceof ObservableCallable<V> oc ? oc : new ObservableCallable<>(callable, timeoutInMilliseconds));
	}

	/**
	 * Wraps the task into an observable task and adds it to the queue. Returns the same type as the input argument
	 * to allow for fluent chaining.
	 *
	 * @param task the task to add
	 * @param <T>  the type of the task
	 * @return the task that was added and wrapped
	 */
	@Nonnull
	private <T extends ObservableTask> T addTaskToQueue(@Nonnull T task) {
		if (task.canTimeOut()) {
			final WeakReference<ObservableTask> taskRef = new WeakReference<>(task);
			try {
				// add the task to the queue
				this.queue.add(taskRef);
			} catch (IllegalStateException e) {
				// this means the queue is full, so we need to remove some tasks
				this.cancelTimedOutTasks();
				// and try adding the task again
				try {
					this.queue.add(taskRef);
				} catch (IllegalStateException exceptionAgain) {
					// and this should never happen since queue was cleared of finished and timed out tasks and its size
					// is double the configured size
					this.rejectedExecutionHandler.rejectedExecution();
					throw exceptionAgain;
				}
			}
		}
		return task;
	}

	/**
	 * Iterates over all tasks in {@link #queue} in a batch manner and cancels those that are timed out. Tasks that are
	 * still waiting or running and not timed out are added to the tail of the queue again.
	 */
	private void cancelTimedOutTasks() {
		int timedOutTasks = 0;
		if (this.bufferLock.tryLock()) {
			try {
				// go through the entire queue, but only once
				final int queueSize = this.queue.size();
				for (int i = 0; i < queueSize; ) {
					// initialize threshold for entire batch only once
					final long threshold = System.currentTimeMillis() - this.timeoutInMilliseconds;
					// effectively withdraw first block of tasks from the queue
					i += this.queue.drainTo(this.buffer, BUFFER_CAPACITY);
					// now go through all of them
					final Iterator<WeakReference<ObservableTask>> it = this.buffer.iterator();
					while (it.hasNext()) {
						final WeakReference<ObservableTask> taskRef = it.next();
						final ObservableTask task = taskRef.get();
						if (task == null) {
							// if task is already garbage collected, remove it from the queue
							it.remove();
						} else if (task.isFinished()) {
							// if task is finished, remove it from the queue
							it.remove();
						} else {
							// if task is running / waiting longer than the threshold, cancel it and remove it from the queue
							if (task.isTimedOut(threshold)) {
								timedOutTasks++;
								log.info("Cancelling timed out task: {}", task);
								task.cancel();
								it.remove();
							} else {
								// if task is not finished and not timed out, leave it in the buffer
							}
						}
					}
					// add the remaining tasks back to the queue in an effective way
					for (WeakReference<ObservableTask> task : this.buffer) {
						try {
							this.queue.add(task);
						} catch (IllegalStateException e) {
							// queue is full, cancel the task
							ofNullable(task.get())
								.ifPresent(ObservableTask::cancel);
						}
					}
					// clear the buffer for the next iteration
					this.buffer.clear();
				}
			} finally {
				this.bufferLock.unlock();
			}
		} else {
			// someone else is currently purging the queue
			// we need to wait until he's done and then the queue should have enough free room
			while (this.bufferLock.isLocked()) {
				Thread.onSpinWait();
			}
		}
		// emit aggregate event
		new BackgroundTaskTimedOutEvent(this.name, timedOutTasks).commit();
	}

	/**
	 * Interface that allows to check if a task is finished and if it is timed out.
	 * It also allows to cancel the task.
	 */
	private interface ObservableTask {

		/**
		 * Check if the task is finished.
		 *
		 * @return true if the task is finished, false otherwise
		 */
		boolean isFinished();

		/**
		 * Check if the task is timed out.
		 *
		 * @param now the current time in milliseconds
		 * @return true if the task is timed out, false otherwise
		 */
		boolean isTimedOut(long now);

		/**
		 * Determines whether the task has the capability to time out.
		 *
		 * @return true if the task can time out, false otherwise
		 */
		boolean canTimeOut();

		/**
		 * Cancels the task.
		 */
		void cancel();

	}

	/**
	 * Wrapper around a {@link Runnable} that implements the {@link ObservableTask} interface.
	 */
	static class ObservableRunnable implements Runnable, ObservableTask {
		/**
		 * Name / description of the task.
		 */
		private final String name;
		/**
		 * Delegate runnable that is being wrapped.
		 */
		private final Runnable delegate;
		/**
		 * Time when the task is considered timed out.
		 */
		private final long timedOutAt;
		/**
		 * Context object that is set when the task is running.
		 */
		private final TracingContext.CapturedContext capturedContext = TracingContext.captureContext();
		/**
		 * Future that is completed when the task is finished.
		 */
		private final CompletableFuture<Void> future = new CompletableFuture<>();

		public ObservableRunnable(@Nonnull Runnable delegate, long timeoutInMilliseconds) {
			final StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
			// pick first name that doesn't contain Observable in the class name
			String name = "Unknown";
			for (StackTraceElement element : stackTrace) {
				if (element.getClassName().contains("io.evitadb") && !element.getClassName().contains("Observable")) {
					name = element.toString();
					break;
				}
			}
			this.name = name;
			this.delegate = delegate;
			long calculatedTimeout;
			try {
				calculatedTimeout = Math.addExact(System.currentTimeMillis(), timeoutInMilliseconds);
			} catch (ArithmeticException e) {
				calculatedTimeout = Long.MAX_VALUE;
			}
			this.timedOutAt = calculatedTimeout;
		}

		public ObservableRunnable(@Nonnull String name, @Nonnull Runnable delegate, long timeoutInMilliseconds) {
			this.name = name;
			this.delegate = delegate;
			long calculatedTimeout;
			try {
				calculatedTimeout = Math.addExact(System.currentTimeMillis(), timeoutInMilliseconds);
			} catch (ArithmeticException e) {
				calculatedTimeout = Long.MAX_VALUE;
			}
			this.timedOutAt = calculatedTimeout;
		}

		@Override
		public boolean isFinished() {
			return this.future.isDone();
		}

		@Override
		public boolean isTimedOut(long now) {
			return this.timedOutAt < now;
		}

		@Override
		public boolean canTimeOut() {
			return this.timedOutAt < Long.MAX_VALUE;
		}

		@Override
		public void cancel() {
			this.future.cancel(true);
		}

		@Override
		public void run() {
			TracingContext.executeWithClientContext(
				this.capturedContext,
				() -> {
					try {
						this.delegate.run();
						this.future.complete(null);
						return null;
					} catch (Exception e) {
						MDC.clear();
						this.future.completeExceptionally(e);
						ObservableThreadExecutor.log.error("Uncaught exception in task.", e);
						throw new ObservableExecutionException(e);
					}
				}
			);
		}

		@Override
		public String toString() {
			return this.name;
		}
	}

	/**
	 * Wrapper around a {@link Callable} that implements the {@link ObservableTask} interface.
	 *
	 * @param <V> the type of the result
	 */
	static class ObservableCallable<V> implements Callable<V>, ObservableTask {
		/**
		 * Name / description of the task.
		 */
		private final String name;
		/**
		 * Delegate callable that is being wrapped.
		 */
		private final Callable<V> delegate;
		/**
		 * Time when the task is considered timed out.
		 */
		private final long timedOutAt;
		/**
		 * Context object that is set when the task is running.
		 */
		private final TracingContext.CapturedContext capturedContext = TracingContext.captureContext();
		/**
		 * Future that is completed when the task is finished.
		 */
		private final CompletableFuture<V> future = new CompletableFuture<>();

		public ObservableCallable(@Nonnull Callable<V> delegate, long timeoutInMilliseconds) {
			final StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
			// pick first name that doesn't contain Observable in the class name
			String name = "Unknown";
			for (StackTraceElement element : stackTrace) {
				if (element.getClassName().contains("io.evitadb") && !element.getClassName().contains("Observable")) {
					name = element.toString();
					break;
				}
			}
			this.name = name;
			this.delegate = delegate;
			long calculatedTimeout;
			try {
				calculatedTimeout = Math.addExact(System.currentTimeMillis(), timeoutInMilliseconds);
			} catch (ArithmeticException e) {
				calculatedTimeout = Long.MAX_VALUE;
			}
			this.timedOutAt = calculatedTimeout;
		}

		public ObservableCallable(@Nonnull String name, @Nonnull Callable<V> delegate, long timeoutInMilliseconds) {
			this.name = name;
			this.delegate = delegate;
			long calculatedTimeout;
			try {
				calculatedTimeout = Math.addExact(System.currentTimeMillis(), timeoutInMilliseconds);
			} catch (ArithmeticException e) {
				calculatedTimeout = Long.MAX_VALUE;
			}
			this.timedOutAt = calculatedTimeout;
		}

		@Override
		public boolean isFinished() {
			return this.future.isDone();
		}

		@Override
		public boolean isTimedOut(long now) {
			return this.timedOutAt < now;
		}

		@Override
		public boolean canTimeOut() {
			return this.timedOutAt < Long.MAX_VALUE;
		}

		@Override
		public void cancel() {
			this.future.cancel(true);
		}

		@Override
		public V call() throws Exception {
			try {
				return TracingContext.executeWithClientContext(
					this.capturedContext,
					() -> {
						try {
							final V result = this.delegate.call();
							this.future.complete(result);
							return result;
						} catch (Exception e) {
							MDC.clear();
							this.future.completeExceptionally(e);
							ObservableThreadExecutor.log.error("Uncaught exception in task.", e);
							throw e instanceof RuntimeException re ?
								re : new ObservableExecutionException(e);
						}
					}
				);
			} catch (ObservableExecutionException e) {
				throw e.getDelegate();
			}
		}

		@Override
		public String toString() {
			return this.name;
		}
	}

	/**
	 * Custom type of the thread with a name, priority and exception logging handler.
	 */
	private static class EvitaWorkerThread extends ForkJoinWorkerThread {
		/**
		 * Counter monitoring the number of threads this factory created.
		 */
		private static final AtomicInteger THREAD_COUNTER = new AtomicInteger();

		protected EvitaWorkerThread(@Nonnull ForkJoinPool pool, @Nonnull String name, int priority) {
			super(pool);
			setDaemon(true);
			setName("Evita-" + name + "-" + THREAD_COUNTER.incrementAndGet());
			setPriority(priority);
			setUncaughtExceptionHandler(LoggingUncaughtExceptionHandler.INSTANCE);
		}
	}

	/**
	 * Custom rejection handler that logs the rejection and increments the counter.
	 */
	private static class LoggingUncaughtExceptionHandler implements UncaughtExceptionHandler {
		public static final LoggingUncaughtExceptionHandler INSTANCE = new LoggingUncaughtExceptionHandler();

		@Override
		public void uncaughtException(Thread t, Throwable e) {
			ObservableThreadExecutor.log.error("Uncaught exception in thread {}", t.getName(), e);
		}
	}

	/**
	 * A runtime exception that wraps another throwable. This exception is typically used to rethrow
	 * a checked exception as an unchecked exception in scenarios where observables or asynchronous
	 * processing are involved.
	 *
	 * Instances of this exception effectively act as wrappers for underlying cause exceptions,
	 * enabling propagation of those exceptions through APIs that do not declare checked exceptions.
	 */
	private static class ObservableExecutionException extends RuntimeException {
		@Serial private static final long serialVersionUID = -7044403627268312520L;
		@Getter private final Exception delegate;

		public ObservableExecutionException(@Nonnull Exception cause) {
			super(cause);
			this.delegate = cause;
		}
	}

}
