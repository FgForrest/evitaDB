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

import io.evitadb.core.metric.event.system.BackgroundTaskFinishedEvent;
import io.evitadb.core.metric.event.system.BackgroundTaskStartedEvent;
import io.evitadb.cron.CronSchedule;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.Assert;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Closeable;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * Represents a task that is executed asynchronously after a specified delay. The task is guarded to be scheduled only
 * once at a time or not at all. Task is scheduled by {@link #schedule()} method with constant delay. The task is paused
 * when the {@link #lambda} is finished and returns negative value. The task is re-scheduled when the {@link #lambda}
 * returns zero or a positive value. In the latter case, the task is re-scheduled with a shorter delay.
 *
 * ## Thread-Safety
 *
 * This class is thread-safe. All scheduling operations are protected by {@link #schedulingLock} to ensure atomic
 * state transitions. The class uses atomic variables for simple boolean/reference updates and explicit locking
 * for complex operations involving multiple state changes.
 *
 * ## Return Value Semantics
 *
 * The `LongSupplier` runnable controls task behavior through its return value:
 *
 * - **Negative value (< 0)**: Task pauses - no automatic rescheduling occurs. However, if another thread called
 * `schedule()` or `scheduleImmediately()` while the task was running (setting the `reSchedule` flag),
 * the task will be rescheduled despite the pause intent.
 * - **Zero (0)**: Task is rescheduled with the same delay as originally configured.
 * - **Positive value (> 0)**: Task is rescheduled with delay shortened by this amount. The actual delay
 * will be `max(delay - returnValue, 1)` in the configured time unit.
 *
 * ## Lifecycle States
 *
 * - **Created**: Task is constructed but not yet scheduled
 * - **Scheduled**: Task has a pending execution in the scheduler
 * - **Running**: Task is currently executing
 * - **Paused**: Task has finished and returned negative value
 * - **Closed**: Task cannot be scheduled anymore; pending executions are cancelled
 *
 * ## Exception Handling
 *
	 * If the task lambda throws a `RuntimeException`, the exception is logged. The `running` flag
	 * is properly reset in the finally block, allowing the task to be rescheduled later. The task automatically reschedules
	 * to a standard delay after an exception.
 *
 * ## Close Semantics
 *
 * The `close()` method:
 * - Cancels any pending scheduled execution (does not interrupt running tasks)
 * - Releases the lambda reference to allow garbage collection
 * - Prevents any future scheduling attempts (throws `GenericEvitaInternalError`)
 * - Is idempotent - calling it multiple times has no additional effect
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@ThreadSafe
@Slf4j
public class ScheduledTask implements Closeable {
	/**
	 * The default minimal gap between two scheduling moments.
	 */
	private static final long DEFAULT_MINIMAL_SCHEDULING_GAP = 1000L;
	/**
	 * The scheduler that is used to schedule the task.
	 */
	private final Scheduler scheduler;
	/**
	 * The delay after which the task is executed.
	 */
	private final long delay;
	/**
	 * The minimal gap between two scheduling moments.
	 */
	private final long minimalSchedulingGap;
	/**
	 * The time unit of the delay.
	 */
	private final TimeUnit delayUnits;
	/**
	 * Name of the catalog that the task belongs to (may be NULL if the task is not bound to any particular catalog).
	 */
	@Getter
	private final String catalogName;
	/**
	 * The name of the task.
	 */
	@Getter
	private final String taskName;
	/**
	 * The task that is executed asynchronously after the specified delay and returns negative value when it should be
	 * paused or zero / positive value when it should be re-scheduled (with shortened delay).
	 */
	private final AtomicReference<Runnable> lambda;
	/**
	 * The next planned cache cut time - if there is scheduled action planned in the current scheduled executor service,
	 * the time is stored here to avoid scheduling the same action multiple times.
	 */
	private final AtomicReference<OffsetDateTime> nextPlannedExecution = new AtomicReference<>(OffsetDateTime.MIN);
	/**
	 * The time of the last finished execution.
	 */
	private final AtomicReference<OffsetDateTime> lastFinishedExecution = new AtomicReference<>(OffsetDateTime.MIN);
	/**
	 * The flag indicating whether the task is currently running.
	 */
	private final AtomicBoolean running = new AtomicBoolean();
	/**
	 * The flag indicating whether the task should be re-scheduled after it finishes run.
	 */
	private final AtomicBoolean reSchedule = new AtomicBoolean();
	/**
	 * The future representing the scheduled task.
	 */
	private final AtomicReference<ScheduledFuture<?>> scheduledFuture = new AtomicReference<>();
	/**
	 * The flag indicating whether the task has been closed. Closed task cannot be scheduled anymore.
	 */
	private final AtomicBoolean closed = new AtomicBoolean();
	/**
	 * Lock for scheduling to ensure thread-safety.
	 */
	private final ReentrantLock schedulingLock = new ReentrantLock();

	public ScheduledTask(
		@Nullable String catalogName,
		@Nonnull String taskName,
		@Nonnull Scheduler scheduler,
		@Nonnull Runnable runnable,
		@Nonnull CronSchedule cronSchedule
	) {
		this(
			catalogName, taskName, scheduler, runnable, cronSchedule, DEFAULT_MINIMAL_SCHEDULING_GAP
		);
	}

	public ScheduledTask(
		@Nullable String catalogName,
		@Nonnull String taskName,
		@Nonnull Scheduler scheduler,
		@Nonnull Runnable runnable,
		@Nonnull CronSchedule cronSchedule,
		long minimalSchedulingGap
	) {
		this(
			catalogName,
			taskName,
			scheduler,
			() -> {
				runnable.run();
				// we need to calculate shortened delay against Long.MAX_VALUE from now
				// (now)|-----------|(nextRun)----------------------|(Long.MAX_VALUE)
				//      |-----------|-- we need to calculate this --|(Long.MAX_VALUE)
				final OffsetDateTime now = OffsetDateTime.now();
				final OffsetDateTime nextRun = cronSchedule.calculateNext(now);
				return Math.negateExact(
					nextRun.toInstant().toEpochMilli() - now.toInstant().toEpochMilli() - Long.MAX_VALUE
				);
			},
			Long.MAX_VALUE,
			TimeUnit.MILLISECONDS,
			minimalSchedulingGap
		);
		schedule(
			cronSchedule.calculateNext(OffsetDateTime.now())
		);
	}

	public ScheduledTask(
		@Nullable String catalogName,
		@Nonnull String taskName,
		@Nonnull Scheduler scheduler,
		@Nonnull LongSupplier runnable,
		long delay,
		@Nonnull TimeUnit delayUnits
	) {
		this(
			catalogName, taskName, scheduler, runnable, delay, delayUnits, DEFAULT_MINIMAL_SCHEDULING_GAP
		);
	}

	public ScheduledTask(
		@Nullable String catalogName,
		@Nonnull String taskName,
		@Nonnull Scheduler scheduler,
		@Nonnull LongSupplier runnable,
		long delay,
		@Nonnull TimeUnit delayUnits,
		long minimalSchedulingGap
	) {
		Assert.notNull(scheduler, "Scheduler must not be null");
		Assert.notNull(taskName, "Task name must not be null");
		Assert.notNull(runnable, "Runnable must not be null");
		Assert.notNull(delayUnits, "Delay units must not be null");
		Assert.isTrue(
			delay >= 0,
			"Delay must be positive or Long.MAX_VALUE for manual tasks"
		);
		Assert.isTrue(
			minimalSchedulingGap >= 0,
			"Minimal scheduling gap must be positive"
		);

		this.scheduler = scheduler;
		this.delay = delay;
		this.delayUnits = delayUnits;
		this.minimalSchedulingGap = minimalSchedulingGap;
		this.catalogName = catalogName;
		this.taskName = taskName;
		this.lambda = new AtomicReference<>(() -> runTask(runnable));
	}

	/**
	 * Schedules the execution of the task immediately if it has not been scheduled already.
	 * The task is scheduled using the scheduler's schedule method.
	 */
	public void scheduleImmediately() {
		schedule(OffsetDateTime.now());
	}

	/**
	 * Schedules the execution of the task at the specified time if it has not been scheduled already.
	 * If the task is currently running, it flags the task to be re-scheduled after it finishes.
	 * The actual execution might be delayed to respect the minimal scheduling gap.
	 */
	public void schedule(@Nonnull OffsetDateTime scheduledTime) {
		assertNotClosed();
		this.schedulingLock.lock();
		try {
			if (this.nextPlannedExecution.compareAndExchange(OffsetDateTime.MIN, scheduledTime) == OffsetDateTime.MIN) {
				scheduleTask(computeMinimalSchedulingGap(scheduledTime.toInstant().toEpochMilli()));
			} else if (this.running.get()) {
				// if this task is currently running, we need to schedule it again after it finishes
				this.reSchedule.set(true);
			}
		} finally {
			this.schedulingLock.unlock();
		}
	}

	/**
	 * Schedules the execution of the task if it has not been scheduled already.
	 * It calculates the next planned execution time based on the specified delay and delay units.
	 * The task is scheduled using the scheduler's schedule method.
	 */
	public void schedule() {
		assertNotClosed();

		this.schedulingLock.lock();
		try {
			if (this.delay == Long.MAX_VALUE) {
				// the task is manual task and will never be scheduled
				return;
			}

			final OffsetDateTime now = OffsetDateTime.now();
			final OffsetDateTime nextTick = now.plus(this.delay, this.delayUnits.toChronoUnit());
			if (this.nextPlannedExecution.compareAndExchange(OffsetDateTime.MIN, nextTick) == OffsetDateTime.MIN) {
				final long nowMillis = now.toInstant().toEpochMilli();
				final long computedDelay = Math.max(
					nextTick.toInstant().toEpochMilli() - nowMillis,
					computeMinimalSchedulingGap(nowMillis)
				);
				scheduleTask(computedDelay);
			} else if (this.running.get()) {
				// if this task is currently running, we need to schedule it again after it finishes
				this.reSchedule.set(true);
			}
		} finally {
			this.schedulingLock.unlock();
		}
	}

	@Override
	public void close() throws IOException {
		if (this.closed.compareAndSet(false, true)) {
			this.schedulingLock.lock();
			try {
				final ScheduledFuture<?> future = this.scheduledFuture.getAndSet(null);
				if (future != null && !future.isDone()) {
					future.cancel(false);
				}
				// release the lambda to allow garbage collection
				this.lambda.set(null);
				this.nextPlannedExecution.set(OffsetDateTime.MIN);
			} finally {
				this.schedulingLock.unlock();
			}
		}
	}

	/**
	 * Ensures that the task is not closed before proceeding with its execution or scheduling.
	 * If the task is already marked as closed, this method throws a GenericEvitaInternalError
	 * to indicate that the operation cannot be performed on a closed task.
	 *
	 * @throws GenericEvitaInternalError if the task is marked as closed
	 */
	private void assertNotClosed() {
		if (this.closed.get()) {
			throw new GenericEvitaInternalError("Cannot schedule task `" + this.taskName + "` that has been closed.");
		}
	}

	/**
	 * Schedules the task for execution after the specified computed delay if the task is not already closed.
	 * The task is scheduled using the scheduler's schedule method with the computed delay and time unit
	 * specified in milliseconds.
	 *
	 * This method checks both the closed state and lambda availability before scheduling. The lambda
	 * may be null if `close()` was called concurrently.
	 *
	 * @param computedDelay the delay time in milliseconds after which the task should be executed
	 */
	private void scheduleTask(long computedDelay) {
		if (!this.closed.get()) {
			final Runnable task = this.lambda.get();
			if (task != null) {
				this.scheduledFuture.set(
					this.scheduler.schedule(
						task,
						computedDelay,
						TimeUnit.MILLISECONDS
					)
				);
			}
		}
	}

	/**
	 * Computes the minimal scheduling gap required based on the last finished execution time and the current time in milliseconds.
	 *
	 * @param nowMillis the current time in milliseconds
	 * @return the computed minimal scheduling gap in milliseconds; returns 0 if the last finished execution time is at its minimum value
	 */
	private long computeMinimalSchedulingGap(long nowMillis) {
		final OffsetDateTime lastFinishedExecutionTime = this.lastFinishedExecution.get();
		if (lastFinishedExecutionTime.equals(OffsetDateTime.MIN)) {
			return 0;
		} else {
			return Math.max(
				0, this.minimalSchedulingGap - (nowMillis - lastFinishedExecutionTime.toInstant()
					.toEpochMilli())
			);
		}
	}

	/**
	 * Handles post-execution scheduling based on the task's return value.
	 * This method is called after the task completes and determines whether to:
	 *
	 * - Re-schedule with a shortened delay (if returnValue >= 0)
	 * - Pause and check for deferred rescheduling (if returnValue < 0)
	 *
	 * Thread-safety: This method acquires the scheduling lock to ensure atomic
	 * state transitions between pause/schedule operations.
	 *
	 * @param planWithShorterDelay the value returned by the task lambda:
	 *                             - negative: pause (no automatic rescheduling)
	 *                             - 0: reschedule with the same delay
	 *                             - positive: reschedule with delay shortened by this amount
	 */
	private void handlePostExecution(long planWithShorterDelay) {
		this.schedulingLock.lock();
		try {
			if (this.closed.get()) {
				// don't reschedule if closed
				return;
			}
			if (planWithShorterDelay > -1L) {
				scheduleWithDelayShorterByLocked(planWithShorterDelay);
			} else {
				pauseLocked();
				if (this.reSchedule.compareAndSet(true, false)) {
					// someone called schedule()/scheduleImmediately() while we were running
					scheduleLocked();
				}
			}
		} finally {
			this.schedulingLock.unlock();
		}
	}

	/**
	 * Internal version of pause that assumes lock is already held.
	 * Sets the next planned execution time to the minimum value, indicating the task is paused.
	 */
	private void pauseLocked() {
		this.nextPlannedExecution.set(OffsetDateTime.MIN);
	}

	/**
	 * Internal version of scheduleWithDelayShorterBy that assumes lock is already held.
	 * Updates the next planned execution time by shortening the delay by the specified amount
	 * and schedules the task accordingly.
	 *
	 * @param shorterBy the amount to shorten the delay by
	 */
	private void scheduleWithDelayShorterByLocked(long shorterBy) {
		final OffsetDateTime nextTick = this.nextPlannedExecution.updateAndGet(
			offsetDateTime -> offsetDateTime.plus(
				Math.max(this.delay - shorterBy, 1L),
				this.delayUnits.toChronoUnit()
			)
		);
		// re-plan the scheduled cut to the moment when the next entry should be cut down
		final OffsetDateTime now = OffsetDateTime.now();
		final long nowMillis = now.toInstant().toEpochMilli();
		final long computedDelay = Math.max(
			nextTick.toInstant().toEpochMilli() - nowMillis,
			computeMinimalSchedulingGap(nowMillis)
		);
		scheduleTask(computedDelay);
	}

	/**
	 * Internal version of schedule core logic that assumes lock is already held.
	 * Schedules the task with the configured delay if not already scheduled.
	 */
	private void scheduleLocked() {
		if (this.delay == Long.MAX_VALUE) {
			// the task is manual task and will never be scheduled
			return;
		}
		final OffsetDateTime now = OffsetDateTime.now();
		final OffsetDateTime nextTick = now.plus(this.delay, this.delayUnits.toChronoUnit());
		if (this.nextPlannedExecution.compareAndExchange(OffsetDateTime.MIN, nextTick) == OffsetDateTime.MIN) {
			final long nowMillis = now.toInstant().toEpochMilli();
			final long computedDelay = Math.max(
				nextTick.toInstant().toEpochMilli() - nowMillis,
				computeMinimalSchedulingGap(nowMillis)
			);
			scheduleTask(computedDelay);
		}
	}

	/**
	 * Executes the task and handles post-execution scheduling.
	 * The running flag is set to true during execution and reset in the finally block.
	 * Post-execution scheduling is delegated to {@link #handlePostExecution(long)} which
	 * properly synchronizes state transitions.
	 *
	 * If the task throws an exception, the task is automatically rescheduled to a standard delay.
	 *
	 * @param runnable the task to execute
	 */
	private void runTask(@Nonnull LongSupplier runnable) {
		long planWithShorterDelay = 0L;
		final BackgroundTaskFinishedEvent finishEvent = new BackgroundTaskFinishedEvent(
			this.catalogName, this.taskName
		);
		try {
			Assert.isPremiseValid(
				this.running.compareAndSet(false, true),
				"Task is already running."
			);
			new BackgroundTaskStartedEvent(this.catalogName, this.taskName).commit();
			planWithShorterDelay = runnable.getAsLong();
		} catch (RuntimeException ex) {
			log.error("Error while running task: {}", this.taskName, ex);
		} finally {
			this.lastFinishedExecution.set(OffsetDateTime.now());
			finishEvent.commit();
			Assert.isPremiseValid(
				this.running.compareAndSet(true, false),
				"Task is not running."
			);
			// delegate post-execution handling to synchronized method
			handlePostExecution(planWithShorterDelay);
		}
	}
}
