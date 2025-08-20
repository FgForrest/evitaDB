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

package io.evitadb.driver;

import io.evitadb.api.EvitaManagementContract;
import io.evitadb.api.exception.InstanceTerminatedException;
import io.evitadb.api.task.Task;
import io.evitadb.api.task.TaskStatus;
import io.evitadb.api.task.TaskStatus.TaskSimplifiedState;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.io.Closeable;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.util.Optional.ofNullable;

/**
 * This class is responsible for tracking the status of client tasks and updating them in the background. Each returned
 * task status received on the client that is not yet finished needs to be tracked and updated by this service, because
 * it contains {@link CompletableFuture} that might be trapped in the client's pipeline that would be waiting indefinitely
 * if the future is not completed. If the futures are not used at all, they would be garbage collected and might be
 * evicted from the tracke
 * d queue prematurely. Also the tasks are evicted from the queue when they are finished.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Slf4j
public class ClientTaskTracker implements Closeable {
	/**
	 * Flag indicating whether the tracker is closed.
	 */
	private final AtomicBoolean closed = new AtomicBoolean(false);
	/**
	 * The client that is used to refresh the task statuses and cancelling them.
	 */
	private final EvitaManagementContract evitaManagement;
	/**
	 * The queue of tasks that are being tracked.
	 */
	private final BlockingQueue<WeakReference<ClientTask<?, ?>>> tasks;
	/**
	 * Internal scheduler that schedules refresh of the task statuses.
	 */
	private final ScheduledExecutorService scheduler;
	/**
	 * The reference to the refreshing lambda allowing to cancel it when there is no more tasks to track.
	 */
	private final AtomicReference<ScheduledFuture<?>> refreshTaskStatusFuture = new AtomicReference<>();
	/**
	 * The lock that is used to synchronize the refreshing of the task statuses.
	 */
	private final ReentrantLock refreshTaskLock = new ReentrantLock(true);
	/**
	 * The interval in milliseconds in which the task statuses are refreshed.
	 */
	private final int refreshIntervalMillis;

	public ClientTaskTracker(@Nonnull EvitaManagementContract evitaManagement, int clientTaskLimit, int refreshIntervalMillis) {
		this.evitaManagement = evitaManagement;
		this.scheduler = Executors.newSingleThreadScheduledExecutor();
		this.tasks = new ArrayBlockingQueue<>(clientTaskLimit);
		this.refreshIntervalMillis = refreshIntervalMillis;
	}

	/**
	 * Creates a new client task. If the task is not yet completed (finished or failed), it is added to the queue of
	 * tracked tasks and its status is updated in the background, so that the {@link Task#getFutureResult()} is completed
	 * when the task is finished.
	 *
	 * @param taskStatus the status of the task to be tracked
	 * @return the client task that is tracking the status of the task
	 * @param <S> the type of the settings of the task
	 * @param <T> the type of the result of the task
	 */
	@Nonnull
	public <S, T> ClientTask<S, T> createTask(@Nonnull TaskStatus<S, T> taskStatus) {
		assertActive();
		final TaskSimplifiedState taskState = taskStatus.simplifiedState();
		if (
			taskState == TaskSimplifiedState.WAITING_FOR_PRECONDITION ||
			taskState == TaskSimplifiedState.QUEUED ||
			taskState == TaskSimplifiedState.RUNNING
		) {
			// we need to add the task to the queue and track its status - unless it's already GCed
			final ClientTask<S, T> taskToTrack = new ClientTask<>(
				taskStatus,
				() -> this.evitaManagement::cancelTask,
				() -> this.evitaManagement::getTaskStatus
			);
			final boolean added = this.tasks.offer(new WeakReference<>(taskToTrack));
			if (!added) {
				purgeFinishedTasks();
				if (!this.tasks.offer(new WeakReference<>(taskToTrack))) {
					throw new RejectedExecutionException(
						"Tracked client task limit reached, cannot track more tasks."
					);
				}
			}

			// init refresh future if not already
			if (this.refreshTaskStatusFuture.get() == null) {
				this.refreshTaskLock.lock();
				try {
					if (this.refreshTaskStatusFuture.get() == null) {
						this.refreshTaskStatusFuture.set(
							this.scheduler.scheduleWithFixedDelay(
								this::refreshTaskStatus,
								0, this.refreshIntervalMillis, TimeUnit.MILLISECONDS
							)
						);
					}
				} finally {
					this.refreshTaskLock.unlock();
				}
			}
			return taskToTrack;
		} else {
			return new ClientTask<>(taskStatus);
		}
	}

	/**
	 * Closes the tracker and cancels all the tasks that are being tracked (only on the client side, the server side
	 * tasks will remain running / queued).
	 */
	@Override
	public void close() {
		if (this.closed.compareAndSet(false, true)) {
			this.scheduler.shutdownNow();
			this.tasks.stream().map(Reference::get)
				.filter(Objects::nonNull)
				.forEach(ClientTask::discard);
		}
	}

	/**
	 * Method refreshes the status of tracked tasks. It is called periodically by the scheduler.
	 */
	private void refreshTaskStatus() {
		try {
			// we don't expect there will be many simultaneous tasks on the client side
			final UUID[] taskIds = this.tasks.stream()
				.map(Reference::get)
				.filter(Objects::nonNull)
				.map(ClientTask::getStatus)
				.map(TaskStatus::taskId)
				.distinct()
				.toArray(UUID[]::new);
			final Map<UUID, TaskStatus<?, ?>> statusIndex = this.evitaManagement.getTaskStatuses(taskIds)
				.stream()
				.collect(
					Collectors.toMap(
						TaskStatus::taskId,
						Function.identity()
					)
				);
			// update the status of all tracked tasks
			for (WeakReference<ClientTask<?, ?>> task : this.tasks) {
				final ClientTask<?, ?> clientTask = task.get();
				if (clientTask != null) {
					final TaskStatus<?, ?> status = statusIndex.get(clientTask.getStatus().taskId());
					if (status != null) {
						clientTask.updateStatus(status);
					}
				}
			}
			// remove finished tasks from the queue
			purgeFinishedTasks();
		} catch (Exception e) {
			log.error("Failed to refresh task statuses.", e);
		}
	}

	/**
	 * Method goes through all the tasks in the queue and removes:
	 *
	 * - those that have empty {@link WeakReference} - which means there is no one waiting for their future result
	 * - those that are finished - which means they are not needed to be tracked anymore
	 *
	 * All still active tasks are added back to the queue.
	 */
	private void purgeFinishedTasks() {
		// go through the entire queue, but only once
		final int bufferSize = Math.min(this.tasks.size(), 512);
		final ArrayList<WeakReference<ClientTask<?, ?>>> buffer = new ArrayList<>(bufferSize);
		final int queueSize = this.tasks.size();
		for (int i = 0; i < queueSize; ) {
			// effectively withdraw first block of tasks from the queue
			i += this.tasks.drainTo(buffer, bufferSize);
			// now go through all of them
			// if task is finished, remove it from the queue
			buffer.removeIf(task -> ofNullable(task.get()).map(ClientTask::isCompleted).orElse(true));
			// add the remaining tasks back to the queue in an effective way
			this.tasks.addAll(buffer);
			// clear the buffer for the next iteration
			buffer.clear();
		}
		// if there is no other task to monitor
		if (this.tasks.isEmpty()) {
			// cancel the refresh task status future
			this.refreshTaskLock.lock();
			try {
				final ScheduledFuture<?> scheduledFuture = this.refreshTaskStatusFuture.get();
				if (this.tasks.isEmpty() && scheduledFuture != null) {
					scheduledFuture.cancel(true);
					this.refreshTaskStatusFuture.set(null);
				}
			} finally {
				this.refreshTaskLock.unlock();
			}
		}
	}

	/**
	 * Asserts that the tracker is active. If it is closed, an exception is thrown.
	 */
	private void assertActive() {
		if (this.closed.get()) {
			throw new InstanceTerminatedException("client task tracker");
		}
	}

}
