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

package io.evitadb.core.async;

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
public class BackgroundCallableTask<V> extends AbstractBackgroundTask<V> implements Callable<V> {
	/**
	 * The actual logic wrapped in a lambda that is executed by the task with progress tracking.
	 */
	private final Function<BackgroundCallableTask<V>, V> callableWithProgress;

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

	public BackgroundCallableTask(@Nonnull String catalogName, @Nonnull String taskName, @Nonnull Callable<V> callable) {
		super(catalogName, taskName);
		this.callableWithProgress = intConsumer -> wrapCallable(catalogName, getTaskName(), callable);
	}

	public BackgroundCallableTask(@Nonnull String taskName, @Nonnull Callable<V> callable) {
		super(taskName);
		this.callableWithProgress = intConsumer -> wrapCallable(null, taskName, callable);
	}

	public BackgroundCallableTask(@Nonnull String catalogName, @Nonnull String taskName, @Nonnull Function<BackgroundCallableTask<V>, V> callable) {
		super(catalogName, taskName);
		this.callableWithProgress = callable;
	}

	public BackgroundCallableTask(@Nonnull String taskName, @Nonnull Function<BackgroundCallableTask<V>, V> callable) {
		super(taskName);
		this.callableWithProgress = callable;
	}

	public BackgroundCallableTask(@Nonnull String catalogName, @Nonnull String taskName, @Nonnull Callable<V> callable, @Nonnull Function<Throwable, V> exceptionHandler) {
		super(catalogName, taskName, exceptionHandler);
		this.callableWithProgress = intConsumer -> wrapCallable(catalogName, getTaskName(), callable);
	}

	public BackgroundCallableTask(@Nonnull String taskName, @Nonnull Callable<V> callable, @Nonnull Function<Throwable, V> exceptionHandler) {
		super(taskName, exceptionHandler);
		this.callableWithProgress = intConsumer -> wrapCallable(null, taskName, callable);
	}

	public BackgroundCallableTask(@Nonnull String catalogName, @Nonnull String taskName, @Nonnull Function<BackgroundCallableTask<V>, V> callable, @Nonnull Function<Throwable, V> exceptionHandler) {
		super(catalogName, taskName, exceptionHandler);
		this.callableWithProgress = callable;
	}

	public BackgroundCallableTask(@Nonnull String taskName, @Nonnull Function<BackgroundCallableTask<V>, V> callable, @Nonnull Function<Throwable, V> exceptionHandler) {
		super(taskName, exceptionHandler);
		this.callableWithProgress = callable;
	}

	@Override
	public V call() throws Exception {
		return super.execute();
	}

	@Override
	protected V executeInternal() {
		return this.callableWithProgress.apply(this);
	}
}
