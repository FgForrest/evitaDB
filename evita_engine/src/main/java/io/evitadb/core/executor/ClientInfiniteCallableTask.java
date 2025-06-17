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

import io.evitadb.api.task.InfiniteTask;
import io.evitadb.api.task.InternallyScheduledTask;
import io.evitadb.api.task.TaskStatus;
import io.evitadb.api.task.TaskStatus.TaskTrait;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.util.concurrent.Callable;
import java.util.function.Function;

/**
 * Represents a task that is executed in the background. This is a thin wrapper around {@link Callable} that emits
 * observability events before and after the task is executed.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Slf4j
@InternallyScheduledTask
public abstract class ClientInfiniteCallableTask<S, T> extends ClientCallableTask<S, T> implements InfiniteTask<S, T> {

	public ClientInfiniteCallableTask(@Nonnull String catalogName, @Nonnull String taskType, @Nonnull String taskName, @Nonnull S settings, @Nonnull Callable<T> callable, @Nonnull TaskTrait... traits) {
		super(catalogName, taskType, taskName, settings, callable, traits);
	}

	public ClientInfiniteCallableTask(@Nonnull String taskType, @Nonnull String taskName, @Nonnull S settings, @Nonnull Callable<T> callable, @Nonnull TaskTrait... traits) {
		super(taskType, taskName, settings, callable, traits);
	}

	public ClientInfiniteCallableTask(@Nonnull String catalogName, @Nonnull String taskType, @Nonnull String taskName, @Nonnull S settings, @Nonnull Function<ClientCallableTask<S, T>, T> callable, @Nonnull TaskTrait... traits) {
		super(catalogName, taskType, taskName, settings, callable, traits);
	}

	public ClientInfiniteCallableTask(@Nonnull String taskType, @Nonnull String taskName, @Nonnull S settings, @Nonnull Function<ClientCallableTask<S, T>, T> callable, @Nonnull TaskTrait... traits) {
		super(taskType, taskName, settings, callable, traits);
	}

	public ClientInfiniteCallableTask(@Nonnull String catalogName, @Nonnull String taskType, @Nonnull String taskName, @Nonnull S settings, @Nonnull Callable<T> callable, @Nonnull Function<Throwable, T> exceptionHandler, @Nonnull TaskTrait... traits) {
		super(catalogName, taskType, taskName, settings, callable, exceptionHandler, traits);
	}

	public ClientInfiniteCallableTask(@Nonnull String taskType, @Nonnull String taskName, @Nonnull S settings, @Nonnull Callable<T> callable, @Nonnull Function<Throwable, T> exceptionHandler, @Nonnull TaskTrait... traits) {
		super(taskType, taskName, settings, callable, exceptionHandler, traits);
	}

	public ClientInfiniteCallableTask(@Nonnull String catalogName, @Nonnull String taskType, @Nonnull String taskName, @Nonnull S settings, @Nonnull Function<ClientCallableTask<S, T>, T> callable, @Nonnull Function<Throwable, T> exceptionHandler, @Nonnull TaskTrait... traits) {
		super(catalogName, taskType, taskName, settings, callable, exceptionHandler, traits);
	}

	public ClientInfiniteCallableTask(@Nonnull String taskType, @Nonnull String taskName, @Nonnull S settings, @Nonnull Function<ClientCallableTask<S, T>, T> callable, @Nonnull Function<Throwable, T> exceptionHandler, @Nonnull TaskTrait... traits) {
		super(taskType, taskName, settings, callable, exceptionHandler, traits);
	}

	@Override
	public final void stop() {
		final TaskStatus<S, T> theStatus = getStatus();
		try {
			if (!this.future.isDone()) {
				this.stopInternal();
			}
		} catch (Throwable e) {
			log.error("Task failed: {}", theStatus.taskName(), e);
			this.status.updateAndGet(
				currentStatus -> currentStatus.transitionToFailed(e)
			);
			if (this.exceptionHandler != null) {
				try {
					final T defaultResult = this.exceptionHandler.apply(e);
					this.future.complete(defaultResult);
				} catch (Throwable e2) {
					this.future.completeExceptionally(e2);
					throw e2;
				}
			} else {
				this.future.completeExceptionally(e);
				throw e;
			}
		}
	}

	/**
	 * Stops the running task.
	 */
	protected abstract void stopInternal();

}
