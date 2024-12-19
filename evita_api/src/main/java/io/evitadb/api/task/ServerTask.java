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
import java.util.function.Predicate;

/**
 * Extension of the {@link Task} interface that is used on the server side. It provides methods to execute and fail
 * the task (this cannot be done on client).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public interface ServerTask<S, T> extends Task<S, T> {

	/**
	 * Executes the task and returns the result.
	 * @return The result of the task.
	 */
	@Nullable
	T execute();

	/**
	 * Terminates the task using passed exception.
	 *
	 * @param exception The exception that caused the task to be cancelled.
	 */
	void fail(@Nonnull Exception exception);

	/**
	 * Transitions the task to the QUEUED state (issued date time is assigned).
	 */
	void transitionToIssued();

	/**
	 * Evaluates the specified predicate against this server task.
	 *
	 * @param taskPredicate The predicate to be evaluated against this server task.
	 * @return True if the predicate evaluates to true for this task, false otherwise.
	 */
	boolean matches(@Nonnull Predicate<ServerTask<?,?>> taskPredicate);

}
