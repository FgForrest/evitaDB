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

import io.evitadb.api.task.TaskStatus.TaskTrait;
import io.evitadb.exception.GenericEvitaInternalError;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.concurrent.Callable;
import java.util.function.Function;

/**
 * Represents a task that is executed in the background. This is a thin wrapper around {@link Callable} that emits
 * observability events before and after the task is executed.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Slf4j
public class ClientCallableTask<S, T> extends AbstractServerTask<S, T> implements Callable<T> {
	/**
	 * The actual logic wrapped in a lambda that is executed by the task with progress tracking.
	 */
	private final Function<ClientCallableTask<S, T>, T> callableWithProgress;

	/**
	 * Wraps the given {@link Callable} and logs any exceptions that occur during its execution.
	 *
	 * @param catalogName The name of the catalog that the task belongs to (may be NULL if the task is not bound to any particular catalog).
	 * @param taskName    The name of the task.
	 * @param callable    The callable to wrap.
	 * @param <V>         The type of the result.
	 * @return The result of the callable.
	 */
	private static <V> V wrapCallable(@Nullable String catalogName, @Nonnull String taskName, @Nonnull Callable<V> callable) {
		try {
			return callable.call();
		} catch (Exception e) {
			if (catalogName == null) {
				log.error("Task `{}` execution failed", taskName, e);
				throw new GenericEvitaInternalError(
					"Task `" + taskName + "` execution failed.",
					"Task execution failed.", e
				);
			} else {
				log.error("Task `{}` in catalog `{}` execution failed", taskName, catalogName, e);
				throw new GenericEvitaInternalError(
					"Task `" + taskName + "` in catalog `" + catalogName + "` execution failed.",
					"Task execution failed.", e
				);
			}
		}
	}

	public ClientCallableTask(@Nonnull String catalogName, @Nonnull String taskType, @Nonnull String taskName, @Nonnull S settings, @Nonnull Callable<T> callable, @Nonnull TaskTrait... traits) {
		super(catalogName, taskType, taskName, settings, traits);
		this.callableWithProgress = intConsumer -> wrapCallable(catalogName, taskName, callable);
	}

	public ClientCallableTask(@Nonnull String taskType, @Nonnull String taskName, @Nonnull S settings, @Nonnull Callable<T> callable, @Nonnull TaskTrait... traits) {
		super(taskType, taskName, settings, traits);
		this.callableWithProgress = intConsumer -> wrapCallable(null, taskName, callable);
	}

	public ClientCallableTask(@Nonnull String catalogName, @Nonnull String taskType, @Nonnull String taskName, @Nonnull S settings, @Nonnull Function<ClientCallableTask<S, T>, T> callable, @Nonnull TaskTrait... traits) {
		super(catalogName, taskType, taskName, settings, traits);
		this.callableWithProgress = callable;
	}

	public ClientCallableTask(@Nonnull String taskType, @Nonnull String taskName, @Nonnull S settings, @Nonnull Function<ClientCallableTask<S, T>, T> callable, @Nonnull TaskTrait... traits) {
		super(taskType, taskName, settings, traits);
		this.callableWithProgress = callable;
	}

	public ClientCallableTask(@Nonnull String catalogName, @Nonnull String taskType, @Nonnull String taskName, @Nonnull S settings, @Nonnull Callable<T> callable, @Nonnull Function<Throwable, T> exceptionHandler, @Nonnull TaskTrait... traits) {
		super(catalogName, taskType, taskName, settings, exceptionHandler, traits);
		this.callableWithProgress = intConsumer -> wrapCallable(catalogName, taskName, callable);
	}

	public ClientCallableTask(@Nonnull String taskType, @Nonnull String taskName, @Nonnull S settings, @Nonnull Callable<T> callable, @Nonnull Function<Throwable, T> exceptionHandler, @Nonnull TaskTrait... traits) {
		super(taskType, taskName, settings, exceptionHandler, traits);
		this.callableWithProgress = intConsumer -> wrapCallable(null, taskName, callable);
	}

	public ClientCallableTask(@Nonnull String catalogName, @Nonnull String taskType, @Nonnull String taskName, @Nonnull S settings, @Nonnull Function<ClientCallableTask<S, T>, T> callable, @Nonnull Function<Throwable, T> exceptionHandler, @Nonnull TaskTrait... traits) {
		super(catalogName, taskType, taskName, settings, exceptionHandler, traits);
		this.callableWithProgress = callable;
	}

	public ClientCallableTask(@Nonnull String taskType, @Nonnull String taskName, @Nonnull S settings, @Nonnull Function<ClientCallableTask<S, T>, T> callable, @Nonnull Function<Throwable, T> exceptionHandler, @Nonnull TaskTrait... traits) {
		super(taskType, taskName, settings, exceptionHandler, traits);
		this.callableWithProgress = callable;
	}

	@Nullable
	@Override
	public T call() throws Exception {
		return super.execute();
	}

	@Nonnull
	@Override
	protected T executeInternal() {
		return this.callableWithProgress.apply(this);
	}

}
