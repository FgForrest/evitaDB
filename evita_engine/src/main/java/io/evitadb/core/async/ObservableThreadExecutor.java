/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

package io.evitadb.core.async;

import io.evitadb.api.configuration.ThreadPoolOptions;
import io.evitadb.core.metric.event.system.BackgroundTaskTimedOutEvent;
import io.evitadb.exception.EvitaInvalidUsageException;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.lang.Thread.UncaughtExceptionHandler;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * This class implementation of {@link ExecutorService} that allows to process asynchronous tasks in a safe and limited
 * manner. It is based on the Java ForkJoinPool and provides additional features like task timeout and task queue
 * monitoring.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Slf4j
public class ObservableThreadExecutor implements ObservableExecutorService {
	/**
	 * Name used in log messages and events.
	 */
	private final String name;
	/**
	 * Java based fork join pool that does the heavy lifting.
	 */
	private final ForkJoinPool forkJoinPool;
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
	private final ArrayBlockingQueue<ObservableTask> queue;

	public ObservableThreadExecutor(
		@Nonnull String name,
		@Nonnull ThreadPoolOptions options,
		@Nonnull Scheduler scheduler,
		long timeoutInMilliseconds
	) {
		this.name = name;
		final int processorsCount = Runtime.getRuntime().availableProcessors();
		this.forkJoinPool = new ForkJoinPool(
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
			this.forkJoinPool.execute(registerTask(command, this.timeoutInMilliseconds));
			this.submittedTaskCount.incrementAndGet();
		} catch (RejectedExecutionException e) {
			this.rejectedExecutionHandler.rejectedExecution();
		}
	}

	@Nonnull
	@Override
	public <T> ForkJoinTask<T> submit(@Nonnull Callable<T> task) {
		try {
			final ForkJoinTask<T> result = this.forkJoinPool.submit(registerTask(task, this.timeoutInMilliseconds));
			this.submittedTaskCount.incrementAndGet();
			return result;
		} catch (RejectedExecutionException e) {
			this.rejectedExecutionHandler.rejectedExecution();
			throw e;
		}
	}

	@Nonnull
	@Override
	public <T> ForkJoinTask<T> submit(@Nonnull Runnable task, T result) {
		try {
			final ForkJoinTask<T> forkJoinTask = this.forkJoinPool.submit(registerTask(task, this.timeoutInMilliseconds), result);
			this.submittedTaskCount.incrementAndGet();
			return forkJoinTask;
		} catch (RejectedExecutionException e) {
			this.rejectedExecutionHandler.rejectedExecution();
			throw e;
		}
	}

	@Nonnull
	@Override
	public ForkJoinTask<?> submit(@Nonnull Runnable task) {
		try {
			final ForkJoinTask<?> forkJoinTask = this.forkJoinPool.submit(registerTask(task, this.timeoutInMilliseconds));
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
			final List<Future<T>> futures = this.forkJoinPool.invokeAll(tasksToSubmit);
			this.submittedTaskCount.addAndGet(futures.size());
			return futures;
		} catch (RejectedExecutionException e) {
			this.rejectedExecutionHandler.rejectedExecution();
			throw e;
		}
	}

	@Nonnull
	@Override
	public <T> List<Future<T>> invokeAll(@Nonnull Collection<? extends Callable<T>> tasks, long timeout, @Nonnull TimeUnit unit) {
		try {
			final long theTimeout = Math.min(this.timeoutInMilliseconds, unit.toMillis(timeout));
			final List<Callable<T>> tasksToSubmit = tasks.stream().map(it -> this.registerTask(it, theTimeout)).toList();
			final List<Future<T>> futures = this.forkJoinPool.invokeAll(tasksToSubmit);
			this.submittedTaskCount.addAndGet(futures.size());
			return futures;
		} catch (RejectedExecutionException e) {
			this.rejectedExecutionHandler.rejectedExecution();
			throw e;
		}
	}

	@Nonnull
	@Override
	public <T> T invokeAny(@Nonnull Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException {
		try {
			final List<Callable<T>> tasksToSubmit = tasks.stream().map(it -> this.registerTask(it, this.timeoutInMilliseconds)).toList();
			final T result = this.forkJoinPool.invokeAny(tasksToSubmit);
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
			final T result = this.forkJoinPool.invokeAny(tasksToSubmit);
			this.submittedTaskCount.incrementAndGet();
			return result;
		} catch (RejectedExecutionException e) {
			this.rejectedExecutionHandler.rejectedExecution();
			throw e;
		}
	}

	@Override
	public void shutdown() {
		this.forkJoinPool.shutdown();
	}

	@Nonnull
	@Override
	public List<Runnable> shutdownNow() {
		return this.forkJoinPool.shutdownNow();
	}

	@Override
	public boolean isShutdown() {
		return this.forkJoinPool.isShutdown();
	}

	@Override
	public boolean isTerminated() {
		return this.forkJoinPool.isTerminated();
	}

	@Override
	public boolean awaitTermination(long timeout, @Nonnull TimeUnit unit) throws InterruptedException {
		return this.forkJoinPool.awaitTermination(timeout, unit);
	}

	/*
	 * PRIVATE METHODS
	 */

	/**
	 * Register a task with a particular timeout.
	 * @param runnable the task to run
	 * @param timeoutInMilliseconds the timeout in milliseconds
	 * @return wrapped runnable into an observable task
	 */
	@Nonnull
	private Runnable registerTask(@Nonnull Runnable runnable, long timeoutInMilliseconds) {
		return addTaskToQueue(new ObservableRunnable(runnable, timeoutInMilliseconds));
	}

	/**
	 * Register a task with a particular timeout.
	 * @param callable the task to run
	 * @param timeoutInMilliseconds the timeout in milliseconds
	 * @return wrapped callable into an observable task
	 * @param <V> the type of the result
	 */
	@Nonnull
	private <V> Callable<V> registerTask(@Nonnull Callable<V> callable, long timeoutInMilliseconds) {
		return addTaskToQueue(new ObservableCallable<>(callable, timeoutInMilliseconds));
	}

	/**
	 * Wraps the task into an observable task and adds it to the queue. Returns the same type as the input argument
	 * to allow for fluent chaining.
	 *
	 * @param task the task to add
	 * @return the task that was added and wrapped
	 * @param <T> the type of the task
	 */
	@Nonnull
	private <T extends ObservableTask> T addTaskToQueue(@Nonnull T task) {
		try {
			// add the task to the queue
			this.queue.add(task);
		} catch (IllegalStateException e) {
			// this means the queue is full, so we need to remove some tasks
			this.cancelTimedOutTasks();
			// and try adding the task again
			try {
				this.queue.add(task);
			} catch (IllegalStateException exceptionAgain) {
				// and this should never happen since queue was cleared of finished and timed out tasks and its size
				// is double the configured size
				this.rejectedExecutionHandler.rejectedExecution();
				throw exceptionAgain;
			}
		}
		return task;
	}

	/**
	 * Iterates over all tasks in {@link #queue} in a batch manner and cancels those that are timed out. Tasks that are
	 * still waiting or running and not timed out are added to the tail of the queue again.
	 */
	private void cancelTimedOutTasks() {
		// go through the entire queue, but only once
		final int bufferSize = 512;
		final ArrayList<ObservableTask> buffer = new ArrayList<>(bufferSize);
		final int queueSize = this.queue.size();
		int timedOutTasks = 0;
		for (int i = 0; i < queueSize;) {
			// initialize threshold for entire batch only once
			final long threshold = System.currentTimeMillis() - this.timeoutInMilliseconds;
			// effectively withdraw first block of tasks from the queue
			i += this.queue.drainTo(buffer, bufferSize);
			// now go through all of them
			final Iterator<ObservableTask> it = buffer.iterator();
			while (it.hasNext()) {
				final ObservableTask task = it.next();
				if (task.isFinished()) {
					// if task is finished, remove it from the queue
					it.remove();
				} else {
					// if task is running / waiting longer than the threshold, cancel it and remove it from the queue
					if (task.isTimedOut(threshold)) {
						timedOutTasks++;
						task.cancel();
						it.remove();
					} else {
						// if task is not finished and not timed out, leave it in the buffer
					}
				}
			}
			// add the remaining tasks back to the queue in an effective way
			this.queue.addAll(buffer);
			// clear the buffer for the next iteration
			buffer.clear();
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
		 * @return true if the task is finished, false otherwise
		 */
		boolean isFinished();

		/**
		 * Check if the task is timed out.
		 * @param now the current time in milliseconds
		 * @return true if the task is timed out, false otherwise
		 */
		boolean isTimedOut(long now);

		/**
		 * Cancels the task.
		 */
		void cancel();

	}

	/**
	 * Wrapper around a {@link Runnable} that implements the {@link ObservableTask} interface.
	 */
	private static class ObservableRunnable implements Runnable, ObservableTask {
		/**
		 * Delegate runnable that is being wrapped.
		 */
		private final Runnable delegate;
		/**
		 * Time when the task is considered timed out.
		 */
		private final long timedOutAt;
		/**
		 * Future that is completed when the task is finished.
		 */
		private final CompletableFuture<Void> future = new CompletableFuture<>();

		public ObservableRunnable(@Nonnull Runnable delegate, long timeoutInMilliseconds) {
			this.delegate = delegate;
			this.timedOutAt = System.currentTimeMillis() + timeoutInMilliseconds;
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
		public void cancel() {
			this.future.cancel(true);
		}

		@Override
		public void run() {
			try {
				this.delegate.run();
				this.future.complete(null);
			} catch (Throwable e) {
				this.future.completeExceptionally(e);
				ObservableThreadExecutor.log.error("Uncaught exception in task.", e);
				throw e;
			}
		}
	}

	/**
	 * Wrapper around a {@link Callable} that implements the {@link ObservableTask} interface.
	 * @param <V> the type of the result
	 */
	private static class ObservableCallable<V> implements Callable<V>, ObservableTask {
		/**
		 * Delegate callable that is being wrapped.
		 */
		private final Callable<V> delegate;
		/**
		 * Time when the task is considered timed out.
		 */
		private final long timedOutAt;
		/**
		 * Future that is completed when the task is finished.
		 */
		private final CompletableFuture<V> future = new CompletableFuture<>();

		public ObservableCallable(@Nonnull Callable<V> delegate, long timeout) {
			this.delegate = delegate;
			this.timedOutAt = System.currentTimeMillis() + timeout;
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
		public void cancel() {
			this.future.cancel(true);
		}

		@Override
		public V call() throws Exception {
			try {
				final V result = this.delegate.call();
				this.future.complete(result);
				return result;
			} catch (Throwable e) {
				this.future.completeExceptionally(e);
				ObservableThreadExecutor.log.error("Uncaught exception in task.", e);
				throw e;
			}
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
}
