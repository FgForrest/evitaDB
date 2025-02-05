/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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
import io.evitadb.api.task.InternallyScheduledTask;
import io.evitadb.api.task.ServerTask;
import io.evitadb.api.task.Task;
import io.evitadb.api.task.TaskStatus;
import io.evitadb.api.task.TaskStatus.TaskSimplifiedState;
import io.evitadb.dataType.PaginatedList;
import io.evitadb.dataType.array.CompositeObjectArray;
import io.evitadb.utils.ArrayUtils;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Scheduler spins up a new {@link ScheduledThreadPoolExecutor} that regularly executes Evita maintenance jobs such as
 * cache invalidation of file system cleaning.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@Slf4j
public class Scheduler implements ObservableExecutorService, ScheduledExecutorService {
	private static final int FINISHED_TASKS_KEEP_INTERVAL_MILLIS = 300_000; // 5 minutes
	private static final int WAITING_TASKS_KEEP_INTERVAL_MILLIS = 600_000; // 10 minutes
	private static final int BUFFER_CAPACITY = 512;
	/**
	 * Buffer used for purging finished tasks.
	 */
	private final ArrayList<ServerTask<?, ?>> buffer = new ArrayList<>(BUFFER_CAPACITY);
	/**
	 * Lock synchronizing access to the buffer and purge operation.
	 */
	private final ReentrantLock bufferLock = new ReentrantLock();
	/**
	 * Java based scheduled executor service.
	 */
	private final ScheduledThreadPoolExecutor executorService;
	/**
	 * Counter monitoring the number of tasks submitted to the executor service.
	 */
	private final AtomicLong submittedTaskCount = new AtomicLong();
	/**
	 * Counter monitoring the number of tasks rejected by the executor service.
	 */
	private final AtomicLong rejectedTaskCount = new AtomicLong();
	/**
	 * Queue that holds the tasks that are currently being executed or waiting to be executed. It could also contain
	 * already finished tasks that are subject to be removed.
	 */
	private final ArrayBlockingQueue<ServerTask<?, ?>> queue;
	/**
	 * Maximum number of tasks that can be stored in the queue.
	 */
	private final int queueCapacity;
	/**
	 * Rejected execution handler that is called when the queue is full and a new task cannot be added.
	 */
	private final EvitaRejectingExecutorHandler rejectingExecutorHandler;

	/**
	 * Creates a predicate to evaluate {@link TaskStatus} objects based on the specified task types and simplified states.
	 *
	 * @param taskType an array of task type strings to filter by; can be null or empty to ignore task type filtering
	 * @param stateSet a set of {@link TaskSimplifiedState} enums to filter by; cannot be null, but can be empty to ignore state filtering
	 * @return a {@link Predicate} that filters {@link TaskStatus} objects based on the provided task types and simplified states;
	 * returns null if neither taskType nor stateSet contain filtering criteria
	 */
	@Nullable
	private static Predicate<TaskStatus<?, ?>> getTaskStatusPredicate(
		@Nullable String[] taskType,
		@Nonnull EnumSet<TaskSimplifiedState> stateSet
	) {
		final Predicate<TaskStatus<?, ?>> typePredicate = ArrayUtils.isEmpty(taskType) ?
			null :
			status -> Arrays.stream(taskType)
				.anyMatch(it -> Arrays.stream(status.taskType().split(","))
					.map(String::trim)
					.anyMatch(tt -> tt.equals(it))
				);
		final Predicate<TaskStatus<?, ?>> statePredicate = stateSet.isEmpty() ? null : status -> stateSet.contains(status.simplifiedState());
		return statePredicate == null ? typePredicate : (typePredicate == null ? statePredicate : typePredicate.and(statePredicate));
	}

	public Scheduler(@Nonnull ThreadPoolOptions options) {
		this.rejectingExecutorHandler = new EvitaRejectingExecutorHandler("service", this.rejectedTaskCount::incrementAndGet);
		final ScheduledThreadPoolExecutor theExecutor = new ScheduledThreadPoolExecutor(
			options.maxThreadCount(),
			new EvitaThreadFactory(options.threadPriority()),
			this.rejectingExecutorHandler
		);
		theExecutor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
		theExecutor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
		this.executorService = theExecutor;
		// create queue with double the size of the configured queue size to have some breathing room
		this.queueCapacity = options.queueSize();
		this.queue = new ArrayBlockingQueue<>(queueCapacity << 1);
		// schedule automatic purging task
		new DelayedAsyncTask(
			null,
			"Scheduler queue purging task",
			this,
			this::purgeFinishedAndLongWaitingTasks,
			1, TimeUnit.MINUTES
		).schedule();
	}

	/**
	 * This constructor is used only in tests.
	 *
	 * @param executorService to be used for scheduling tasks
	 */
	public Scheduler(@Nonnull ScheduledThreadPoolExecutor executorService) {
		this.executorService = executorService;
		this.queue = new ArrayBlockingQueue<>(64);
		this.queueCapacity = 64;
		this.rejectingExecutorHandler = null;
	}

	/**
	 * Returns the internal executor service used by the scheduler. This method is intended for observability purposes
	 * and should not be used for task scheduling.
	 *
	 * @return the internal executor service
	 */
	@Nonnull
	public ScheduledThreadPoolExecutor getExecutorServiceInternal() {
		return executorService;
	}

	@Nonnull
	@Override
	public ScheduledFuture<?> schedule(@Nonnull Runnable lambda, long delay, @Nonnull TimeUnit delayUnits) {
		if (!this.executorService.isShutdown()) {
			return this.executorService.schedule(lambda, delay, delayUnits);
		} else {
			throw new RejectedExecutionException("Scheduler is already shut down.");
		}
	}

	@Nonnull
	@Override
	public <V> ScheduledFuture<V> schedule(@Nonnull Callable<V> callable, long delay, @Nonnull TimeUnit unit) {
		if (!this.executorService.isShutdown()) {
			return this.executorService.schedule(callable, delay, unit);
		} else {
			throw new RejectedExecutionException("Scheduler is already shut down.");
		}
	}

	@Nonnull
	@Override
	public ScheduledFuture<?> scheduleAtFixedRate(@Nonnull Runnable command, long initialDelay, long period, @Nonnull TimeUnit unit) {
		if (!this.executorService.isShutdown()) {
			final ScheduledFuture<?> scheduledFuture = this.executorService.scheduleAtFixedRate(
				command,
				initialDelay,
				period,
				unit
			);
			this.submittedTaskCount.incrementAndGet();
			return scheduledFuture;
		} else {
			throw new RejectedExecutionException("Scheduler is already shut down.");
		}
	}

	@Nonnull
	@Override
	public ScheduledFuture<?> scheduleWithFixedDelay(@Nonnull Runnable command, long initialDelay, long delay, @Nonnull TimeUnit unit) {
		if (!this.executorService.isShutdown()) {
			final ScheduledFuture<?> scheduledFuture = this.executorService.scheduleWithFixedDelay(
				command,
				initialDelay,
				delay,
				unit
			);
			this.submittedTaskCount.incrementAndGet();
			return scheduledFuture;
		} else {
			throw new RejectedExecutionException("Scheduler is already shut down.");
		}
	}

	/**
	 * Method schedules immediate execution of `runnable`. If there is no free thread left in the pool, the runnable
	 * will be executed "as soon as possible".
	 *
	 * @param runnable the runnable task to be executed
	 * @throws NullPointerException       if the runnable parameter is null
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
		// cancel all tasks in the queue
		for (ServerTask<?, ?> serverTask : this.queue) {
			serverTask.cancel();
		}
		this.executorService.shutdown();
	}

	@Nonnull
	@Override
	public List<Runnable> shutdownNow() {
		// cancel all tasks in the queue
		for (ServerTask<?, ?> serverTask : this.queue) {
			serverTask.cancel();
		}
		return this.executorService.shutdownNow();
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
		if (task instanceof ServerTask<?, ?> st) {
			st.transitionToIssued();
		}
		final Future<T> future = executorService.submit(task);
		this.submittedTaskCount.incrementAndGet();
		return future;
	}

	@Nonnull
	@Override
	public <T> Future<T> submit(@Nonnull Runnable task, T result) {
		if (task instanceof ServerTask<?, ?> st) {
			st.transitionToIssued();
		}
		final Future<T> future = executorService.submit(task, result);
		this.submittedTaskCount.incrementAndGet();
		return future;
	}

	@Nonnull
	@Override
	public Future<?> submit(@Nonnull Runnable task) {
		if (task instanceof ServerTask<?, ?> st) {
			st.transitionToIssued();
		}
		final Future<?> future = executorService.submit(task);
		this.submittedTaskCount.incrementAndGet();
		return future;
	}

	@Nonnull
	@Override
	public <T> List<Future<T>> invokeAll(@Nonnull Collection<? extends Callable<T>> tasks) throws InterruptedException {
		for (Callable<T> task : tasks) {
			if (task instanceof ServerTask<?, ?> st) {
				st.transitionToIssued();
			}
		}
		final List<Future<T>> futures = executorService.invokeAll(tasks);
		this.submittedTaskCount.addAndGet(futures.size());
		return futures;
	}

	@Nonnull
	@Override
	public <T> List<Future<T>> invokeAll(@Nonnull Collection<? extends Callable<T>> tasks, long timeout, @Nonnull TimeUnit unit) throws InterruptedException {
		for (Callable<T> task : tasks) {
			if (task instanceof ServerTask<?, ?> st) {
				st.transitionToIssued();
			}
		}
		final List<Future<T>> futures = executorService.invokeAll(tasks, timeout, unit);
		this.submittedTaskCount.addAndGet(futures.size());
		return futures;
	}

	@Nonnull
	@Override
	public <T> T invokeAny(@Nonnull Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException {
		for (Callable<T> task : tasks) {
			if (task instanceof ServerTask<?, ?> st) {
				st.transitionToIssued();
			}
		}
		final T result = executorService.invokeAny(tasks);
		this.submittedTaskCount.incrementAndGet();
		return result;
	}

	@Override
	public <T> T invokeAny(@Nonnull Collection<? extends Callable<T>> tasks, long timeout, @Nonnull TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
		for (Callable<T> task : tasks) {
			if (task instanceof AbstractServerTask<?, ?> ast) {
				ast.transitionToIssued();
			}
		}
		final T result = executorService.invokeAny(tasks, timeout, unit);
		this.submittedTaskCount.incrementAndGet();
		return result;
	}

	@Nonnull
	public <T> CompletableFuture<T> submit(@Nonnull ServerTask<?, T> task) {
		addTaskToQueue(task);
		return submitTaskInQueue(task);
	}

	/**
	 * Returns a paginated list of all tasks that are currently in the queue - either waiting to be executed or
	 * currently running, or recently finished.
	 *
	 * @param page     the page number (starting from 1)
	 * @param pageSize the size of the page
	 * @param taskType allows limiting result statuses to those of a particular type
	 * @param states   allows limiting result statuses to those of a particular simplified state
	 * @return the paginated list of tasks
	 */
	@Nonnull
	public PaginatedList<TaskStatus<?, ?>> listTaskStatuses(
		int page,
		int pageSize,
		@Nullable String[] taskType,
		@Nonnull TaskSimplifiedState... states
	) {

		final EnumSet<TaskSimplifiedState> stateSet = EnumSet.noneOf(TaskSimplifiedState.class);
		Collections.addAll(stateSet, states);

		final Predicate<TaskStatus<?, ?>> finalPredicate = getTaskStatusPredicate(taskType, stateSet);

		final Collection<ServerTask<?, ?>> tasks = finalPredicate == null ?
			this.queue : this.queue.stream().filter(it -> finalPredicate.test(it.getStatus())).toList();
		return new PaginatedList<>(
			page, pageSize, tasks.size(),
			tasks.stream()
				.sorted((o1, o2) -> o2.getStatus().created().compareTo(o1.getStatus().created()))
				.skip(PaginatedList.getFirstItemNumberForPage(page, pageSize))
				.limit(pageSize)
				.map(Task::getStatus)
				.collect(Collectors.toCollection(ArrayList::new))
		);
	}

	/**
	 * Returns the tasks of the given task type.
	 *
	 * @param taskType the type of the task
	 * @param <T>      the type of the task
	 * @return the list of matching tasks
	 */
	@Nonnull
	public <T extends ServerTask<?, ?>> Collection<T> getTasks(@Nonnull Class<T> taskType) {
		return this.queue
			.stream()
			.filter(taskType::isInstance)
			.map(taskType::cast)
			.collect(Collectors.toCollection(ArrayList::new));
	}

	/**
	 * Returns job statuses for the requested job ids. If the job with the specified jobId is not found, it is not
	 * included in the returned collection.
	 *
	 * @param jobId jobId of the job
	 * @return collection of job statuses
	 */
	public Collection<TaskStatus<?, ?>> getTaskStatuses(@Nonnull UUID... jobId) {
		final HashSet<UUID> uuids = new HashSet<>(Arrays.asList(jobId));
		return this.queue
			.stream()
			.filter(it -> uuids.contains(it.getStatus().taskId()))
			.map(it -> (TaskStatus<?, ?>) it.getStatus())
			.collect(Collectors.toCollection(ArrayList::new));
	}

	/**
	 * Returns job status for the specified jobId or empty if the job is not found.
	 *
	 * @param jobId jobId of the job
	 * @return job status
	 */
	@Nonnull
	public Optional<TaskStatus<?, ?>> getTaskStatus(@Nonnull UUID jobId) {
		return this.queue.stream()
			.filter(it -> it.getStatus().taskId().equals(jobId))
			.findFirst()
			.map(Task::getStatus);
	}

	/**
	 * Cancels the job with the specified jobId. If the job is waiting in the queue, it will be removed from the queue.
	 * If the job is already running, it must support cancelling to be interrupted and canceled.
	 *
	 * @param jobId jobId of the job
	 * @return true if the job was found and cancellation triggered, false if the job was not found
	 */
	public boolean cancelTask(@Nonnull UUID jobId) {
		return this.queue.stream()
			.filter(it -> it.getStatus().taskId().equals(jobId))
			.findFirst()
			.map(Task::cancel)
			.orElse(false);
	}

	/**
	 * Registers a task to be kept in the waiting queue until it can be executed.
	 *
	 * @param task The task to be registered and added to the waiting queue.
	 */
	public void registerWaitingTask(@Nonnull ServerTask<?, ?> task) {
		this.addTaskToQueue(task);
	}

	/**
	 * Retrieves a task from the waiting queue based on the provided registration identifier.
	 *
	 * @param taskPredicate predicate to filter the task
	 * @return An {@link Optional} containing the {@link ServerTask} if found, otherwise an empty {@link Optional}.
	 */
	public Optional<ServerTask<?, ?>> findTask(@Nonnull Predicate<ServerTask<?, ?>> taskPredicate) {
		return this.queue.stream().filter(task -> task.matches(taskPredicate)).findFirst();
	}

	/**
	 * Submits a task from the waiting queue based on the provided registration identifier.
	 *
	 * @param taskPredicate predicate to filter the task
	 */
	public void submitWaitingTask(@Nonnull Predicate<ServerTask<?, ?>> taskPredicate) {
		this.queue.stream().filter(task -> task.matches(taskPredicate)).findFirst()
			.ifPresent(this::submitTaskInQueue);
	}

	/**
	 * Submits a given server task to the internal queue for execution.
	 *
	 * @param task The server task to be submitted. Must not be null.
	 * @return A CompletableFuture representing the result of the submitted task.
	 */
	private <T> @Nonnull CompletableFuture<T> submitTaskInQueue(@Nonnull ServerTask<?, T> task) {
		task.transitionToIssued();
		if (task.getClass().isAnnotationPresent(InternallyScheduledTask.class)) {
			// if the task is internally scheduled, we can execute it immediately
			task.execute();
		} else {
			this.executorService.submit(task::execute);
		}
		this.submittedTaskCount.incrementAndGet();
		return task.getFutureResult();
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
	private <T extends ServerTask<?, ?>> T addTaskToQueue(@Nonnull T task) {
		try {
			// add the task to the queue
			this.queue.add(task);
		} catch (IllegalStateException e) {
			// this means the queue is full, so we need to remove some tasks
			this.purgeFinishedAndLongWaitingTasks();
			// and try adding the task again
			try {
				this.queue.add(task);
			} catch (IllegalStateException exceptionAgain) {
				// and this should never happen since queue was cleared of finished and timed out tasks and its size
				// is double the configured size
				if (this.rejectingExecutorHandler != null) {
					this.rejectingExecutorHandler.rejectedExecution();
				}
				task.fail(exceptionAgain);
				throw exceptionAgain;
			}
		}
		return task;
	}

	/**
	 * Iterates over all tasks in {@link #queue} in a batch manner and removes all finished tasks. Tasks that are
	 * still waiting or running are added to the tail of the queue again.
	 */
	private long purgeFinishedAndLongWaitingTasks() {
		if (this.bufferLock.tryLock()) {
			try {
				// go through the entire queue, but only once
				final int queueSize = this.queue.size();
				//noinspection rawtypes
				CompositeObjectArray<Task> finishedTaskInDefensePeriod = null;
				final OffsetDateTime waitingThreshold = OffsetDateTime.now().minus(FINISHED_TASKS_KEEP_INTERVAL_MILLIS, ChronoUnit.MILLIS);
				final OffsetDateTime threshold = OffsetDateTime.now().minus(WAITING_TASKS_KEEP_INTERVAL_MILLIS, ChronoUnit.MILLIS);
				final int batches = queueSize / BUFFER_CAPACITY + 1;
				for (int i = 0; i < batches; i++) {
					// effectively withdraw first block of tasks from the queue
					this.queue.drainTo(this.buffer, BUFFER_CAPACITY);
					// now go through all of them
					final Iterator<ServerTask<?, ?>> it = this.buffer.iterator();
					while (it.hasNext()) {
						final Task<?, ?> task = it.next();
						final TaskStatus<?, ?> status = task.getStatus();
						final TaskSimplifiedState taskState = status.simplifiedState();
						if (taskState == TaskSimplifiedState.WAITING_FOR_PRECONDITION && status.created().isBefore(waitingThreshold)) {
							// if task is waiting for precondition and its issued time is older than the threshold, remove it
							log.info("Task {} is waiting for precondition for too long, removing it from the queue.", status.taskId());
							it.remove();
						} else if (taskState == TaskSimplifiedState.FINISHED || taskState == TaskSimplifiedState.FAILED) {
							it.remove();
							// if its defense period hasn't perished add it to list, that might end up in the queue again
							if (status.finished() != null && status.finished().isAfter(threshold)) {
								if (finishedTaskInDefensePeriod == null) {
									finishedTaskInDefensePeriod = new CompositeObjectArray<>(Task.class);
								}
								finishedTaskInDefensePeriod.add(task);
							}
						}
					}
					// add the remaining tasks back to the queue in an effective way
					this.queue.addAll(this.buffer);
					// clear the buffer for the next iteration
					this.buffer.clear();
				}
				// now add the tasks that are still in defense period back to the queue, but keep at least 1/3 of the queue empty
				final int requiredEmptyBlock = Math.min(1, this.queueCapacity / 3);
				final int remainingCapacity = this.queueCapacity - this.queue.size();
				if (remainingCapacity > requiredEmptyBlock && finishedTaskInDefensePeriod != null) {
					//noinspection rawtypes
					final Iterator<Task> it = finishedTaskInDefensePeriod.iterator();
					final int currentCapacity = this.queue.size();
					for (int i = currentCapacity; i < this.queueCapacity - requiredEmptyBlock && i < remainingCapacity && it.hasNext(); i++) {
						this.queue.add((ServerTask<?, ?>) it.next());
					}
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
		// plan to next standard time
		return 0L;
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
