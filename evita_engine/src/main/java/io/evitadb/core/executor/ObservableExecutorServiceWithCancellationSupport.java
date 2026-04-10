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


import javax.annotation.Nonnull;
import java.util.concurrent.Callable;

/**
 * This interface extends {@link ObservableExecutorService} and marks a service that creates tasks that can be cancelled
 * with thread interruption. It provides methods to create tasks that support cancellation, allowing for better resource
 * management and responsiveness in scenarios where tasks may need to be stopped before completion.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@SuppressWarnings("InterfaceWithOnlyOneDirectInheritor")
public interface ObservableExecutorServiceWithCancellationSupport extends ObservableExecutorService {

	/**
	 * Creates a task with the given name and lambda function to be executed.
	 *
	 * @param name the name of the task
	 * @param lambda the task to be executed
	 * @return a CancellableRunnable representing the task
	 */
	@Nonnull
	CancellableRunnable createTask(@Nonnull String name, @Nonnull Runnable lambda);

	/**
	 * Creates a task to be executed from the given lambda.
	 *
	 * @param lambda the task to be executed
	 * @return a CancellableRunnable representing the task
	 */
	@Nonnull
	CancellableRunnable createTask(@Nonnull Runnable lambda);

	/**
	 * Creates a task with the given name and lambda function to be executed.
	 *
	 * @param name the name of the task
	 * @param lambda the task to be executed
	 * @param <V> the result type of method call
	 * @return a CancellableCallable representing the task
	 */
	@Nonnull
	<V> CancellableCallable<V> createTask(@Nonnull String name, @Nonnull Callable<V> lambda);

	/**
	 * Creates a task to be executed from the given lambda.
	 *
	 * @param lambda the task to be executed
	 * @param <V> the result type of method call
	 * @return a CancellableCallable representing the task
	 */
	@Nonnull
	<V> CancellableCallable<V> createTask(@Nonnull Callable<V> lambda);

}
