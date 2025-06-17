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

import io.evitadb.core.metric.event.system.BackgroundTaskRejectedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Custom rejecting executor that logs the problem when the queue gets full.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@Slf4j
@RequiredArgsConstructor
public class EvitaRejectingExecutorHandler implements RejectedExecutionHandler {
	/**
	 * Name used in log messages and events.
	 */
	private final String name;
	/**
	 * Lambda to be executed when the queue is full.
	 */
	private final Runnable onReject;

	/**
	 * Handle the rejection.
	 */
	public void rejectedExecution() {
		handleRejection();
	}

	/**
	 * Method that may be invoked by a {@link ThreadPoolExecutor} when a task cannot be accepted.
	 * @param command the runnable task requested to be executed
	 * @param executor the executor attempting to execute this task
	 */
	@Override
	public void rejectedExecution(Runnable command, ThreadPoolExecutor executor) {
		handleRejection();
	}

	/**
	 * Handle the rejection.
	 */
	private void handleRejection() {
		new BackgroundTaskRejectedEvent(this.name).commit();
		this.onReject.run();
		log.error("Evita executor queue full. Please add more threads to the `" + this.name + "` pool.");
		throw new RejectedExecutionException("Evita executor queue full. Please add more threads to the `" + this.name + "` pool.");
	}

}
