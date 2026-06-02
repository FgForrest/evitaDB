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

package io.evitadb.core.executor;

import io.evitadb.api.configuration.ThreadPoolOptions;
import io.evitadb.api.task.InfiniteTask;
import io.evitadb.api.task.InternallyScheduledTask;
import io.evitadb.api.task.ServerTask;
import io.evitadb.api.task.Task;
import io.evitadb.api.task.TaskStatus;
import io.evitadb.api.task.TaskStatus.TaskSimplifiedState;
import io.evitadb.core.metric.event.system.ScheduledExecutorStatisticsEvent;
import io.evitadb.dataType.PaginatedList;
import io.evitadb.dataType.array.CompositeObjectArray;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.IOUtils;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Delegate;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
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
	private final LongAdder submittedTaskCount = new LongAdder();
	/**
	 * Counter monitoring the number of tasks rejected by the executor service.
	 */
	private final LongAdder rejectedTaskCount = new LongAdder();
	/**
	 * Flag indicating whether the scheduler is in the process of shutting down.
	 */
	private final AtomicBoolean shutdownInProgress = new AtomicBoolean(false);
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
	 * Physical capacity of the {@link #queue} - i.e. the actual number of slots the backing
	 * {@link ArrayBlockingQueue} was allocated with. The purge logic reasons against this value so that the
	 * "keep roughly one third of the queue empty" breathing-room invariant matches the real allocation.
	 */
	private final int physicalQueueCapacity;
	/**
	 * Rejected execution handler that is called when the queue is full and a new task cannot be added.
	 */
	private final EvitaRejectingExecutorHandler rejectingExecutorHandler;
	/**
	 * Last observed completed task count of the scheduler.
	 */
	private long schedulerCompletedTasks;
	/**
	 * Task that periodically purges finished tasks from the queue.
	 */
	private final DelayedAsyncTask purgingTask;

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
		this.rejectingExecutorHandler = new EvitaRejectingExecutorHandler("service", this.rejectedTaskCount::increment);
		// note: a ScheduledThreadPoolExecutor uses an unbounded DelayedWorkQueue, so this handler is effectively only
		// triggered for tasks submitted after shutdown - back-pressure for the bounded task registry is enforced
		// separately in #addTaskToQueue, which invokes the same handler on overflow
		final ScheduledThreadPoolExecutor theExecutor = new ScheduledThreadPoolExecutor(
			options.maxThreadCount(),
			new EvitaThreadFactory(options.threadPriority()),
			this.rejectingExecutorHandler
		);
		theExecutor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
		theExecutor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
		theExecutor.setRemoveOnCancelPolicy(true);
		this.executorService = theExecutor;
		// create queue with double the size of the configured queue size to have some breathing room
		this.queueCapacity = options.queueSize();
		this.physicalQueueCapacity = this.queueCapacity << 1;
		this.queue = new ArrayBlockingQueue<>(this.physicalQueueCapacity);
		// schedule automatic purging task
		this.purgingTask = new DelayedAsyncTask(
			null,
			"Scheduler queue purging task",
			this,
			this::purgeFinishedAndLongWaitingTasks,
			1, TimeUnit.MINUTES
		);
		this.purgingTask.schedule();
	}

	/**
	 * This constructor is used only in tests.
	 *
	 * @param executorService to be used for scheduling tasks
	 */
	public Scheduler(@Nonnull ScheduledThreadPoolExecutor executorService) {
		this.executorService = executorService;
		this.queueCapacity = 64;
		this.physicalQueueCapacity = 64;
		this.queue = new ArrayBlockingQueue<>(this.physicalQueueCapacity);
		this.rejectingExecutorHandler = null;
		this.purgingTask = null;
	}

	@Nonnull
	@Override
	public ScheduledFuture<?> schedule(@Nonnull Runnable lambda, long delay, @Nonnull TimeUnit delayUnits) {
		if (!this.executorService.isShutdown()) {
			final ScheduledFuture<?> scheduledFuture = this.executorService.schedule(lambda, delay, delayUnits);
			this.submittedTaskCount.increment();
			return scheduledFuture;
		} else if (!this.shutdownInProgress.get()) {
			throw new RejectedExecutionException("Scheduler is already shut down.");
		} else {
			return NonScheduledFuture.instance();
		}
	}

	@Nonnull
	@Override
	public <V> ScheduledFuture<V> schedule(@Nonnull Callable<V> callable, long delay, @Nonnull TimeUnit unit) {
		if (!this.executorService.isShutdown()) {
			final ScheduledFuture<V> scheduledFuture = this.executorService.schedule(callable, delay, unit);
			this.submittedTaskCount.increment();
			return scheduledFuture;
		} else if (!this.shutdownInProgress.get()) {
			throw new RejectedExecutionException("Scheduler is already shut down.");
		} else {
			return NonScheduledFuture.instance();
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
			this.submittedTaskCount.increment();
			return scheduledFuture;
		} else if (!this.shutdownInProgress.get()) {
			throw new RejectedExecutionException("Scheduler is already shut down.");
		} else {
			return NonScheduledFuture.instance();
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
			this.submittedTaskCount.increment();
			return scheduledFuture;
		} else if (!this.shutdownInProgress.get()) {
			throw new RejectedExecutionException("Scheduler is already shut down.");
		} else {
			return NonScheduledFuture.instance();
		}
	}

	/**
	 * Method schedules immediate execution of `runnable`. If there is no free thread left in the pool, the runnable
	 * will be executed "as soon as possible". This is a fire-and-forget submission - no future is handed back to the
	 * caller, therefore any exception thrown while the runnable executes is logged on the worker thread rather than
	 * propagated to the caller.
	 *
	 * @param runnable the runnable task to be executed
	 * @throws NullPointerException       if the runnable parameter is null
	 * @throws RejectedExecutionException if the scheduler is already shut down (and shutdown is not merely in progress)
	 */
	@Override
	public void execute(@Nonnull Runnable runnable) {
		if (!this.executorService.isShutdown()) {
			// ScheduledThreadPoolExecutor wraps every task (even via execute()) in a ScheduledFutureTask that
			// captures thrown exceptions into the future. Since this fire-and-forget path hands no future back to
			// the caller, wrap the runnable to log any otherwise-swallowed exception instead of losing it silently.
			this.executorService.execute(() -> {
				try {
					runnable.run();
				} catch (Throwable t) {
					log.error("Uncaught error during execution of a task submitted via execute().", t);
				}
			});
			this.submittedTaskCount.increment();
		} else if (!this.shutdownInProgress.get()) {
			throw new RejectedExecutionException("Scheduler is already shut down.");
		}
	}

	@Override
	public long getSubmittedTaskCount() {
		return this.submittedTaskCount.sum();
	}

	@Override
	public long getRejectedTaskCount() {
		return this.rejectedTaskCount.sum();
	}

	@Override
	public void shutdown() {
		if (this.purgingTask != null) {
			IOUtils.closeQuietly(this.purgingTask::close);
		}
		// cancel all tasks in the queue
		for (ServerTask<?, ?> serverTask : this.queue) {
			if (serverTask instanceof InfiniteTask<?,?> it) {
				it.stop();
			} else {
				serverTask.cancel();
			}
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
		return this.shutdownInProgress.get() || this.executorService.isShutdown();
	}

	@Override
	public boolean isTerminated() {
		return this.executorService.isTerminated();
	}

	@Override
	public boolean awaitTermination(long timeout, @Nonnull TimeUnit unit) throws InterruptedException {
		return this.executorService.awaitTermination(timeout, unit);
	}

	@Nonnull
	@Override
	public <T> Future<T> submit(@Nonnull Callable<T> task) {
		if (!this.executorService.isShutdown()) {
			if (task instanceof ServerTask<?, ?> st) {
				st.transitionToIssued();
			}
			final Future<T> future = this.executorService.submit(task);
			this.submittedTaskCount.increment();
			return future;
		} else if (!this.shutdownInProgress.get()) {
			throw new RejectedExecutionException("Scheduler is already shut down.");
		} else {
			return NonScheduledFuture.instance();
		}
	}

	@Nonnull
	@Override
	public <T> Future<T> submit(@Nonnull Runnable task, T result) {
		if (!this.executorService.isShutdown()) {
			if (task instanceof ServerTask<?, ?> st) {
				st.transitionToIssued();
			}
			final Future<T> future = this.executorService.submit(task, result);
			this.submittedTaskCount.increment();
			return future;
		} else if (!this.shutdownInProgress.get()) {
			throw new RejectedExecutionException("Scheduler is already shut down.");
		} else {
			return NonScheduledFuture.instance();
		}
	}

	@Nonnull
	@Override
	public Future<?> submit(@Nonnull Runnable task) {
		if (!this.executorService.isShutdown()) {
			if (task instanceof ServerTask<?, ?> st) {
				st.transitionToIssued();
			}
			final Future<?> future = this.executorService.submit(task);
			this.submittedTaskCount.increment();
			return future;
		} else if (!this.shutdownInProgress.get()) {
			throw new RejectedExecutionException("Scheduler is already shut down.");
		} else {
			return NonScheduledFuture.instance();
		}
	}

	@Nonnull
	@Override
	public <T> List<Future<T>> invokeAll(@Nonnull Collection<? extends Callable<T>> tasks) throws InterruptedException {
		if (!this.executorService.isShutdown()) {
			for (Callable<T> task : tasks) {
				if (task instanceof ServerTask<?, ?> st) {
					st.transitionToIssued();
				}
			}
			final List<Future<T>> futures = this.executorService.invokeAll(tasks);
			this.submittedTaskCount.add(futures.size());
			return futures;
		} else if (!this.shutdownInProgress.get()) {
			throw new RejectedExecutionException("Scheduler is already shut down.");
		} else {
			return List.of(NonScheduledFuture.instance());
		}
	}

	@Nonnull
	@Override
	public <T> List<Future<T>> invokeAll(@Nonnull Collection<? extends Callable<T>> tasks, long timeout, @Nonnull TimeUnit unit) throws InterruptedException {
		if (!this.executorService.isShutdown()) {
			for (Callable<T> task : tasks) {
				if (task instanceof ServerTask<?, ?> st) {
					st.transitionToIssued();
				}
			}
			final List<Future<T>> futures = this.executorService.invokeAll(tasks, timeout, unit);
			this.submittedTaskCount.add(futures.size());
			return futures;
		} else if (!this.shutdownInProgress.get()) {
			throw new RejectedExecutionException("Scheduler is already shut down.");
		} else {
			return List.of(NonScheduledFuture.instance());
		}
	}

	@Nonnull
	@Override
	public <T> T invokeAny(@Nonnull Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException {
		if (!this.executorService.isShutdown()) {
			for (Callable<T> task : tasks) {
				if (task instanceof ServerTask<?, ?> st) {
					st.transitionToIssued();
				}
			}
			final T result = this.executorService.invokeAny(tasks);
			this.submittedTaskCount.increment();
			return result;
		} else {
			// unlike the other shut-down branches this one cannot return a graceful sentinel: the method contract is
			// @Nonnull and there is no result to hand back, so a RejectedExecutionException is thrown even while the
			// shutdown is merely in progress
			throw new RejectedExecutionException("Scheduler is already shut down.");
		}
	}

	@Nullable
	@Override
	public <T> T invokeAny(@Nonnull Collection<? extends Callable<T>> tasks, long timeout, @Nonnull TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
		if (!this.executorService.isShutdown()) {
			for (Callable<T> task : tasks) {
				if (task instanceof ServerTask<?, ?> st) {
					st.transitionToIssued();
				}
			}
			final T result = this.executorService.invokeAny(tasks, timeout, unit);
			this.submittedTaskCount.increment();
			return result;
		} else if (!this.shutdownInProgress.get()) {
			throw new RejectedExecutionException("Scheduler is already shut down.");
		} else {
			return null;
		}
	}

	@Nonnull
	public <T> CompletableFuture<T> submit(@Nonnull ServerTask<?, T> task) {
		if (!this.executorService.isShutdown()) {
			addTaskToQueue(task);
			return submitTaskInQueue(task);
		} else if (!this.shutdownInProgress.get()) {
			throw new RejectedExecutionException("Scheduler is already shut down.");
		} else {
			return NonScheduledFuture.<T>instance().getFuture();
		}
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
		if (!this.executorService.isShutdown()) {
			this.addTaskToQueue(task);
		} else if (!this.shutdownInProgress.get()) {
			throw new RejectedExecutionException("Scheduler is already shut down.");
		}
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
		if (!this.executorService.isShutdown()) {
			this.queue.stream().filter(task -> task.matches(taskPredicate)).findFirst()
				.ifPresent(this::submitTaskInQueue);
		} else if (!this.shutdownInProgress.get()) {
			throw new RejectedExecutionException("Scheduler is already shut down.");
		}
	}

	/**
	 * Emits statistics of the ThreadPool associated with the scheduler.
	 */
	public void emitScheduledForkJoinPoolStatistics() {
		try {
			final long currentlyCompleted = this.executorService.getCompletedTaskCount();
			new ScheduledExecutorStatisticsEvent(
				currentlyCompleted - this.schedulerCompletedTasks,
				this.executorService.getActiveCount(),
				this.executorService.getQueue().size(),
				this.executorService.getQueue().remainingCapacity(),
				this.executorService.getPoolSize(),
				this.executorService.getCorePoolSize(),
				this.executorService.getMaximumPoolSize()
			).commit();
			this.schedulerCompletedTasks = currentlyCompleted;
		} catch (Throwable t) {
			log.error("Emitting observability events failed!", t);
		}
	}

	/**
	 * Method might be called before the scheduler is shut down to stop accepting new scheduled tasks.
	 */
	public void prepareForBeingShutdown() {
		this.shutdownInProgress.set(true);
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
		} else if (task instanceof Callable<?>) {
			//noinspection unchecked
			this.executorService.submit((Callable<T>)task);
		} else {
			this.executorService.submit(task::execute);
		}
		this.submittedTaskCount.increment();
		return task.getFutureResult();
	}

	/**
	 * Adds the task to the tracking {@link #queue}, returning the same instance to allow for fluent chaining. The
	 * task is first added via a non-blocking {@link ArrayBlockingQueue#offer(Object) offer}; if the queue is full,
	 * {@link #purgeFinishedAndLongWaitingTasks()} is triggered to reclaim space and the offer is retried. Should the
	 * queue still be full afterwards, the task is marked as failed, the rejecting handler (when present) is notified
	 * and an {@link IllegalStateException} is thrown.
	 *
	 * @param task the task to add
	 * @param <T>  the type of the task
	 * @return the task that was added
	 * @throws IllegalStateException if the queue remains full even after a purge attempt
	 */
	@Nonnull
	private <T extends ServerTask<?, ?>> T addTaskToQueue(@Nonnull T task) {
		final boolean added;
		// hold the buffer lock around the whole add so that registry writes never interleave with a concurrent
		// purge: this prevents the purge's drain/refill window from racing an offer, which could otherwise overflow
		// the re-add and silently drop live tasks. The purge re-enters this lock reentrantly on the full-queue path.
		this.bufferLock.lock();
		try {
			// try to add the task to the queue without resorting to exceptions for control flow
			if (this.queue.offer(task)) {
				return task;
			}
			// the queue is full, so we need to remove some tasks and try again
			this.purgeFinishedAndLongWaitingTasks();
			added = this.queue.offer(task);
		} finally {
			this.bufferLock.unlock();
		}
		if (!added) {
			// this should never happen since the queue was cleared of finished and timed out tasks and its
			// physical size is double the configured size
			final IllegalStateException exception = new IllegalStateException(
				"Scheduler queue is full and no task could be purged to make room."
			);
			// mark the task as failed first so it is reported as failed regardless of the handler presence
			task.fail(exception);
			// the rejecting handler (when present) emits an event and rethrows a RejectedExecutionException
			if (this.rejectingExecutorHandler != null) {
				this.rejectingExecutorHandler.rejectedExecution();
			}
			throw exception;
		}
		return task;
	}

	/**
	 * Iterates over all tasks in {@link #queue} in a batch manner and prunes it according to the following policy:
	 *
	 * - tasks still waiting for a precondition longer than {@link #WAITING_TASKS_KEEP_INTERVAL_MILLIS} are dropped,
	 * - finished or failed tasks are removed, but those whose completion falls within the defense period are
	 *   re-queued up to a fill threshold that keeps roughly one third of {@link #physicalQueueCapacity} empty as
	 *   breathing room for newly submitted tasks,
	 * - all remaining waiting or running tasks are added back to the tail of the queue.
	 *
	 * The method is guarded by {@link #bufferLock}; a concurrent caller that cannot acquire the lock blocks until the
	 * in-progress purge finishes rather than busy-spinning.
	 *
	 * @return always {@code 0L}, signalling the scheduling framework to re-plan the purge at its standard interval
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
				// now add the tasks that are still in defense period back to the queue, but keep at least 1/3 of the
				// physical queue capacity empty as breathing room for newly submitted tasks
				final int requiredEmptyBlock = Math.max(1, this.physicalQueueCapacity / 3);
				final int maxFill = this.physicalQueueCapacity - requiredEmptyBlock;
				if (finishedTaskInDefensePeriod != null) {
					//noinspection rawtypes
					final Iterator<Task> it = finishedTaskInDefensePeriod.iterator();
					// re-add defense-period tasks until either the fill threshold is reached or we run out of tasks
					while (this.queue.size() < maxFill && it.hasNext()) {
						this.queue.offer((ServerTask<?, ?>) it.next());
					}
				}
			} finally {
				this.bufferLock.unlock();
			}
		} else {
			// someone else is currently purging the queue - block until they are done (at which point the queue
			// should have enough free room) instead of busy-spinning on the lock state
			this.bufferLock.lock();
			this.bufferLock.unlock();
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
			final Thread thread = new Thread(this.group, runnable, "Evita-service-" + THREAD_COUNTER.incrementAndGet());
			if (this.priority > 0 && thread.getPriority() != this.priority) {
				thread.setPriority(this.priority);
			}
			return thread;
		}
	}

	/**
	 * Return value for all scheduled tasks issued after the scheduler is shut down.
	 * @param <T>
	 */
	@RequiredArgsConstructor
	@EqualsAndHashCode
	private static class NonScheduledFuture<T> implements ScheduledFuture<T> {
		public static final NonScheduledFuture<?> INSTANCE = new NonScheduledFuture<>();
		private final IllegalStateException exception = new IllegalStateException("Scheduler is being shut down!");
		@Delegate @Getter private final CompletableFuture<T> future = CompletableFuture.failedFuture(this.exception);

		@Nonnull
		public static <T> NonScheduledFuture<T> instance() {
			//noinspection unchecked
			return (NonScheduledFuture<T>) INSTANCE;
		}


		@Override
		public long getDelay(@Nonnull TimeUnit delay) {
			return Long.MIN_VALUE;
		}

		@Override
		public int compareTo(@Nonnull Delayed o) {
			throw new UnsupportedOperationException();
		}

	}

}
