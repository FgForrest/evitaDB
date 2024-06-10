/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.core.scheduling;

import io.evitadb.api.configuration.ThreadPoolOptions;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Scheduler spins up a new {@link ScheduledThreadPoolExecutor} that regularly executes Evita maintenance jobs such as
 * cache invalidation of file system cleaning.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@Slf4j
public class Scheduler implements ObservableExecutorService {
	/**
	 * Java based scheduled executor service.
	 */
	private final ScheduledExecutorService executorService;
	/**
	 * Counter monitoring the number of tasks submitted to the executor service.
	 */
	private final AtomicLong submittedTaskCount = new AtomicLong();
	/**
	 * Counter monitoring the number of tasks rejected by the executor service.
	 */
	private final AtomicLong rejectedTaskCount = new AtomicLong();

	public Scheduler(@Nonnull ThreadPoolOptions options) {
		final ScheduledThreadPoolExecutor theExecutor = new ScheduledThreadPoolExecutor(
			options.maxThreadCount(),
			new EvitaThreadFactory(options.threadPriority()),
			new EvitaRejectingExecutorHandler("service", this.rejectedTaskCount::incrementAndGet)
		);
		theExecutor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
		theExecutor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
		this.executorService = theExecutor;
	}

	/**
	 * This constructor is used only in tests.
	 * @param executorService to be used for scheduling tasks
	 */
	public Scheduler(@Nonnull ScheduledExecutorService executorService) {
		this.executorService = executorService;
	}

	/**
	 * Method schedules execution of `runnable` after `initialDelay` with frequency of `period`.
	 *
	 * @param runnable    the task to be executed
	 * @param initialDelay the initial delay before the first execution
	 * @param period       the period between subsequent executions
	 * @param timeUnit     the time unit of the initialDelay and period parameters
	 *
	 * @throws NullPointerException       if the runnable or timeUnit parameter is null
	 * @throws RejectedExecutionException if the task cannot be scheduled for execution
	 */
	public void scheduleAtFixedRate(@Nonnull Runnable runnable, int initialDelay, int period, @Nonnull TimeUnit timeUnit) {
		if (!this.executorService.isShutdown()) {
			this.executorService.scheduleAtFixedRate(
				runnable,
				initialDelay,
				period,
				timeUnit
			);
			this.submittedTaskCount.incrementAndGet();
		}
	}

	/**
	 * Schedules the execution of a {@link Runnable} task after a specified delay.
	 *
	 * @param lambda The task to be executed.
	 * @param delay The amount of time to delay the execution.
	 * @param delayUnits The time unit of the delay parameter.
	 * @throws NullPointerException if the lambda or delayUnits parameter is null.
	 * @throws RejectedExecutionException if the task cannot be scheduled for execution.
	 */
	public void schedule(@Nonnull Runnable lambda, long delay, @Nonnull TimeUnit delayUnits) {
		if (!this.executorService.isShutdown()) {
			this.executorService.schedule(lambda, delay, delayUnits);
		}
	}

	/**
	 * Method schedules immediate execution of `runnable`. If there is no free thread left in the pool, the runnable
	 * will be executed "as soon as possible".
	 *
	 * @param runnable the runnable task to be executed
	 * @throws NullPointerException if the runnable parameter is null
	 * @throws RejectedExecutionException if the task cannot be submitted for execution
	 */
	@Override
	public void execute(@Nonnull Runnable runnable) {
		if (!this.executorService.isShutdown()) {
			this.executorService.submit(runnable);
			this.submittedTaskCount.incrementAndGet();
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
	public void shutdown() {
		System.out.println("Shutting down scheduler");
		executorService.shutdown();
	}

	@Nonnull
	@Override
	public List<Runnable> shutdownNow() {
		return executorService.shutdownNow();
	}

	@Override
	public boolean isShutdown() {
		return executorService.isShutdown();
	}

	@Override
	public boolean isTerminated() {
		return executorService.isTerminated();
	}

	@Override
	public boolean awaitTermination(long timeout, @Nonnull TimeUnit unit) throws InterruptedException {
		return executorService.awaitTermination(timeout, unit);
	}

	@Nonnull
	@Override
	public <T> Future<T> submit(@Nonnull Callable<T> task) {
		final Future<T> future = executorService.submit(task);
		this.submittedTaskCount.incrementAndGet();
		return future;
	}

	@Nonnull
	@Override
	public <T> Future<T> submit(@Nonnull Runnable task, T result) {
		final Future<T> future = executorService.submit(task, result);
		this.submittedTaskCount.incrementAndGet();
		return future;
	}

	@Nonnull
	@Override
	public Future<?> submit(@Nonnull Runnable task) {
		final Future<?> future = executorService.submit(task);
		this.submittedTaskCount.incrementAndGet();
		return future;
	}

	@Nonnull
	@Override
	public <T> List<Future<T>> invokeAll(@Nonnull Collection<? extends Callable<T>> tasks) throws InterruptedException {
		final List<Future<T>> futures = executorService.invokeAll(tasks);
		this.submittedTaskCount.addAndGet(futures.size());
		return futures;
	}

	@Nonnull
	@Override
	public <T> List<Future<T>> invokeAll(@Nonnull Collection<? extends Callable<T>> tasks, long timeout, @Nonnull TimeUnit unit) throws InterruptedException {
		final List<Future<T>> futures = executorService.invokeAll(tasks, timeout, unit);
		this.submittedTaskCount.addAndGet(futures.size());
		return futures;
	}

	@Nonnull
	@Override
	public <T> T invokeAny(@Nonnull Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException {
		final T result = executorService.invokeAny(tasks);
		this.submittedTaskCount.incrementAndGet();
		return result;
	}

	@Override
	public <T> T invokeAny(@Nonnull Collection<? extends Callable<T>> tasks, long timeout, @Nonnull TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
		final T result = executorService.invokeAny(tasks, timeout, unit);
		this.submittedTaskCount.incrementAndGet();
		return result;
	}

	/**
	 * Custom thread factory to manage thread priority and naming.
	 */
	private static class EvitaThreadFactory implements ThreadFactory {
		/**
		 * Counter monitoring the number of threads this factory created.
		 */
		private static final AtomicInteger THREAD_COUNTER = new AtomicInteger();
		/**
		 * Home group for the new threads.
		 */
		private final ThreadGroup group;
		/**
		 * Priority for threads that are created by this factory.
		 * Initialized from {@link ThreadPoolOptions#threadPriority()}.
		 */
		private final int priority;

		public EvitaThreadFactory(int priority) {
			this.group = Thread.currentThread().getThreadGroup();
			this.priority = priority;
		}

		@Override
		public Thread newThread(@Nonnull Runnable runnable) {
			final Thread thread = new Thread(group, runnable, "Evita-service-" + THREAD_COUNTER.incrementAndGet());
			if (priority > 0 && thread.getPriority() != priority) {
				thread.setPriority(priority);
			}
			return thread;
		}
	}

}
