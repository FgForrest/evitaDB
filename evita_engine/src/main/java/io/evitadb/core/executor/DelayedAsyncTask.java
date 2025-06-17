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

import io.evitadb.core.metric.event.system.BackgroundTaskFinishedEvent;
import io.evitadb.core.metric.event.system.BackgroundTaskStartedEvent;
import io.evitadb.utils.Assert;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.OffsetDateTime;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * Represents a task that is executed asynchronously after a specified delay. The task is guarded to be scheduled only
 * once at a time or not at all. Task is scheduled by {@link #schedule()} method with constant delay. The task is paused
 * when the {@link #lambda} is finished and returns negative value. The task is re-scheduled when the {@link #lambda} returns
 * positive value. The task is re-scheduled with shorter delay when the {@link #lambda} returns positive value.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Slf4j
public class DelayedAsyncTask {
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
	 * paused or positive value when it should be re-scheduled (with shortened delay).
	 */
	private final Runnable lambda;
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

	public DelayedAsyncTask(
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

	public DelayedAsyncTask(
		@Nullable String catalogName,
		@Nonnull String taskName,
		@Nonnull Scheduler scheduler,
		@Nonnull LongSupplier runnable,
		long delay,
		@Nonnull TimeUnit delayUnits,
		long minimalSchedulingGap
	) {
		this.scheduler = scheduler;
		this.delay = delay;
		this.delayUnits = delayUnits;
		this.minimalSchedulingGap = minimalSchedulingGap;
		this.catalogName = catalogName;
		this.taskName = taskName;
		this.lambda = () -> runTask(runnable);
	}

	/**
	 * Schedules the execution of the task immediately if it has not been scheduled already.
	 * The task is scheduled using the scheduler's schedule method.
	 */
	public void scheduleImmediately() {
		final OffsetDateTime now = OffsetDateTime.now();
		if (this.nextPlannedExecution.compareAndExchange(OffsetDateTime.MIN, now) == OffsetDateTime.MIN) {
			this.scheduler.schedule(
				this.lambda,
				computeMinimalSchedulingGap(now.toInstant().toEpochMilli()),
				TimeUnit.MILLISECONDS
			);
		} else if (this.running.get()) {
			// if this task is currently running, we need to schedule it again after it finishes
			this.reSchedule.set(true);
		}
	}

	/**
	 * Schedules the execution of the task if it has not been scheduled already.
	 * It calculates the next planned execution time based on the specified delay and delay units.
	 * The task is scheduled using the scheduler's schedule method.
	 */
	public void schedule() {
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
			this.scheduler.schedule(
				this.lambda,
				computedDelay,
				TimeUnit.MILLISECONDS
			);
		} else if (this.running.get()) {
			// if this task is currently running, we need to schedule it again after it finishes
			this.reSchedule.set(true);
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
			return Math.max(0, this.minimalSchedulingGap - (nowMillis - lastFinishedExecutionTime.toInstant().toEpochMilli()));
		}
	}

	/**
	 * Pauses the execution of the task by setting the next planned execution time to the minimum value.
	 */
	private void pause() {
		this.nextPlannedExecution.set(OffsetDateTime.MIN);
	}

	/**
	 * Updates the next planned execution time by shortening the delay by the specified amount.
	 * It re-plans the scheduled task to the new execution time using the scheduler's schedule method.
	 *
	 * @param shorterBy The amount to shorten the delay by.
	 */
	private void scheduleWithDelayShorterBy(long shorterBy) {
		final OffsetDateTime nextTick = this.nextPlannedExecution.updateAndGet(
			offsetDateTime -> offsetDateTime.plus(Math.max(this.delay - shorterBy, 1L), this.delayUnits.toChronoUnit())
		);
		// re-plan the scheduled cut to the moment when the next entry should be cut down
		final OffsetDateTime now = OffsetDateTime.now();
		final long nowMillis = now.toInstant().toEpochMilli();
		final long computedDelay = Math.max(
			nextTick.toInstant().toEpochMilli() - nowMillis,
			computeMinimalSchedulingGap(nowMillis)
		);
		this.scheduler.schedule(
			this.lambda,
			computedDelay,
			TimeUnit.MILLISECONDS
		);
	}

	/**
	 * Executes the task and sets the running flag to false when the task is finished.
	 */
	private void runTask(@Nonnull LongSupplier runnable) {
		final long planWithShorterDelay;
		final BackgroundTaskFinishedEvent finishEvent = new BackgroundTaskFinishedEvent(this.catalogName, this.taskName);
		try {
			Assert.isPremiseValid(
				this.running.compareAndSet(false, true),
				"Task is already running."
			);
			new BackgroundTaskStartedEvent(this.catalogName, this.taskName).commit();
			planWithShorterDelay = runnable.getAsLong();
			this.lastFinishedExecution.set(OffsetDateTime.now());
		} catch (RuntimeException ex) {
			log.error("Error while running task: {}", this.taskName, ex);
			throw ex;
		} finally {
			finishEvent.commit();
			Assert.isPremiseValid(
				this.running.compareAndSet(true, false),
				"Task is not running."
			);
		}
		if (planWithShorterDelay > -1L) {
			scheduleWithDelayShorterBy(planWithShorterDelay);
		} else {
			pause();
			if (this.reSchedule.compareAndSet(true, false)) {
				// reschedule the task if it was requested during the run
				this.schedule();
			}
		}
	}
}
