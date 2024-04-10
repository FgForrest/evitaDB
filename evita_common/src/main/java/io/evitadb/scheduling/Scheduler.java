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
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.scheduling;

import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Scheduler spins up a new {@link ScheduledThreadPoolExecutor} that regularly executes Evita maintenance jobs such as
 * cache invalidation of file system cleaning.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@Slf4j
public class Scheduler implements Executor {
	/**
	 * Java based scheduled executor service.
	 */
	private final ScheduledExecutorService executorService;

	public Scheduler(@Nonnull ScheduledExecutorService executor) {
		this.executorService = executor;
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
			this.executorService.scheduleAtFixedRate(runnable, initialDelay, period, timeUnit);
		}
	}

	/**
	 * Method schedules immediate execution of `runnable`. If there is no free thread left in the pool, the runnable
	 * 	 * will be executed "as soon as possible".
	 *
	 * @param runnable the runnable task to be executed
	 * @throws NullPointerException if the runnable parameter is null
	 * @throws RejectedExecutionException if the task cannot be submitted for execution
	 */
	@Override
	public void execute(@Nonnull Runnable runnable) {
		if (!this.executorService.isShutdown()) {
			this.executorService.submit(runnable);
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
}
