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

package io.evitadb.api.task;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.concurrent.CompletableFuture;

/**
 * Interface for different tasks that can be executed in the background service. It represents an instance of single
 * task execution with particular settings executed by the service thread pool with lower priority on the background.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public interface Task<S, T> {

	/**
	 * Returns actual status of the task, telemetry information and access to its progress, settings and result.
	 * @return The status of the task.
	 */
	@Nonnull
	TaskStatus<S, T> getStatus();

	/**
	 * Returns the future result of the task. The future is completed when the task is finished.
	 * @return The future result of the task.
	 */
	@Nonnull
	CompletableFuture<T> getFutureResult();

	/**
	 * Executes the task and returns the result.
	 * @return The result of the task.
	 */
	@Nullable
	T execute();

	/**
	 * Cancels the task.
	 */
	void cancel();

	/**
	 * Terminates the task using passed exception.
	 *
	 * @param exception The exception that caused the task to be cancelled.
	 */
	void fail(@Nonnull Exception exception);

}
