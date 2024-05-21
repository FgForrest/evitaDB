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

package io.evitadb.core.scheduling;

import io.evitadb.scheduling.Scheduler;

import javax.annotation.Nonnull;
import java.time.OffsetDateTime;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * Represents a task that is executed asynchronously after a specified delay. The task is guarded to be scheduled only
 * once at a time or not at all. Task is scheduled by {@link #schedule()} method with constant delay. The task is paused
 * when the {@link #task} is finished and returns negative value. The task is re-scheduled when the {@link #task} returns
 * positive value. The task is re-scheduled with shorter delay when the {@link #task} returns positive value.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class DelayedAsyncTask {
	/**
	 * The scheduler that is used to schedule the task.
	 */
	private final Scheduler scheduler;
	/**
	 * The delay after which the task is executed.
	 */
	private final long delay;
	/**
	 * The time unit of the delay.
	 */
	private final TimeUnit delayUnits;
	/**
	 * The task that is executed asynchronously after the specified delay and returns negative value when it should be
	 * paused or positive value when it should be re-scheduled (with shortened delay).
	 */
	private final BackgroundTask task;
	/**
	 * The next planned cache cut time - if there is scheduled action planned in the current scheduled executor service,
	 * the time is stored here to avoid scheduling the same action multiple times.
	 */
	private final AtomicReference<OffsetDateTime> nextPlannedExecution = new AtomicReference<>(OffsetDateTime.MIN);

	public DelayedAsyncTask(
		@Nonnull String catalogName,
		@Nonnull String taskName,
		@Nonnull Scheduler scheduler,
		@Nonnull LongSupplier runnable,
		long delay,
		@Nonnull TimeUnit delayUnits
	) {
		this.scheduler = scheduler;
		this.delay = delay;
		this.delayUnits = delayUnits;
		this.task = new BackgroundTask(
			catalogName, taskName,
			() -> {
				final long planWithShorterDelay = runnable.getAsLong();
				if (planWithShorterDelay > -1L) {
					scheduleWithDelayShorterBy(planWithShorterDelay);
				} else {
					pause();
				}
			}
		);
	}

	/**
	 * Schedules the execution of the task if it has not been scheduled already.
	 * It calculates the next planned execution time based on the specified delay and delay units.
	 * The task is scheduled using the scheduler's schedule method.
	 */
	public void schedule() {
		final OffsetDateTime now = OffsetDateTime.now();
		final OffsetDateTime nextTick = now.plus(this.delay, this.delayUnits.toChronoUnit());
		if (this.nextPlannedExecution.compareAndExchange(OffsetDateTime.MIN, nextTick) == OffsetDateTime.MIN) {
			final long computedDelay = Math.max(
				nextTick.toInstant().toEpochMilli() - now.toInstant().toEpochMilli(),
				0
			);
			this.scheduler.schedule(
				this.task,
				computedDelay,
				TimeUnit.MILLISECONDS
			);
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
			offsetDateTime -> offsetDateTime.plus(Math.min(this.delay - shorterBy, 1L), this.delayUnits.toChronoUnit())
		);
		// re-plan the scheduled cut to the moment when the next entry should be cut down
		final OffsetDateTime now = OffsetDateTime.now();
		final long computedDelay = Math.max(
			nextTick.toInstant().toEpochMilli() - now.toInstant().toEpochMilli(),
			0
		);
		this.scheduler.schedule(
			this.task,
			computedDelay,
			TimeUnit.MILLISECONDS
		);
	}
}
