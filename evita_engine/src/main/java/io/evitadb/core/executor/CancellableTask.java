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

import javax.annotation.Nonnull;
import java.util.concurrent.CompletionStage;

/**
 * Interface for tasks that can be cancelled with thread interruption. Designed to wire
 * Armeria request cancellation to executor tasks, so that when a client disconnects or
 * a request times out, the in-flight task is interrupted and stops executing.
 *
 * @param <V> the type of the result produced by this task
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public interface CancellableTask<V> {

	/**
	 * Cancels the task, interrupting the executing thread if the task is currently running.
	 */
	void cancel();

	/**
	 * Returns true if the task has finished execution (completed, failed, or was cancelled).
	 */
	boolean isFinished();

	/**
	 * Returns the {@link CompletionStage} that completes when the task finishes.
	 */
	@Nonnull
	CompletionStage<V> completionStage();

}
