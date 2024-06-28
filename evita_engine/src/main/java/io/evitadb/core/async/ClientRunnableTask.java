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

import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Represents a task that is executed in the background. This is a thin wrapper around {@link Runnable} that emits
 * observability events before and after the task is executed.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Slf4j
public class ClientRunnableTask<S> extends AbstractClientTask<S, Void> implements Runnable {
	/**
	 * The actual logic wrapped in a lambda that is executed by the task.
	 */
	private final Consumer<ClientRunnableTask<S>> runnableWithProgress;

	/**
	 * Transforms {@link Consumer<Throwable>} to {@link Function<Throwable, Void>} that can be used as an exception handler.
	 * @param exceptionHandler The exception handler to wrap.
	 * @return The wrapped exception handler.
	 */
	private static @Nonnull Function<Throwable, Void> wrapExceptionHandler(@Nonnull Consumer<Throwable> exceptionHandler) {
		return e -> {
			exceptionHandler.accept(e);
			return null;
		};
	}

	public ClientRunnableTask(@Nonnull String catalogName, @Nonnull String taskName, @Nullable S settings, @Nonnull Runnable runnable) {
		super(catalogName, taskName, settings);
		this.runnableWithProgress = task -> runnable.run();
	}

	public ClientRunnableTask(@Nonnull String taskName, @Nullable S settings, @Nonnull Runnable runnable) {
		super(taskName, settings);
		this.runnableWithProgress = task -> runnable.run();
	}

	public ClientRunnableTask(@Nonnull String catalogName, @Nonnull String taskName, @Nullable S settings, @Nonnull Runnable runnable, @Nonnull Consumer<Throwable> exceptionHandler) {
		super(catalogName, taskName, settings, wrapExceptionHandler(exceptionHandler));
		this.runnableWithProgress = task -> runnable.run();
	}

	public ClientRunnableTask(@Nonnull String taskName, @Nullable S settings, @Nonnull Runnable runnable, @Nonnull Consumer<Throwable> exceptionHandler) {
		super(taskName, settings, wrapExceptionHandler(exceptionHandler));
		this.runnableWithProgress = task -> runnable.run();
	}

	public ClientRunnableTask(@Nonnull String catalogName, @Nonnull String taskName, @Nullable S settings, @Nonnull Consumer<ClientRunnableTask<S>> runnableWithProgress) {
		super(catalogName, taskName, settings);
		this.runnableWithProgress = runnableWithProgress;
	}

	public ClientRunnableTask(@Nonnull String taskName, @Nullable S settings, @Nonnull Consumer<ClientRunnableTask<S>> runnableWithProgress) {
		super(taskName, settings);
		this.runnableWithProgress = runnableWithProgress;
	}

	public ClientRunnableTask(@Nonnull String catalogName, @Nonnull String taskName, @Nullable S settings, @Nonnull Consumer<ClientRunnableTask<S>> runnableWithProgress, @Nonnull Consumer<Throwable> exceptionHandler) {
		super(catalogName, taskName, settings, wrapExceptionHandler(exceptionHandler));
		this.runnableWithProgress = runnableWithProgress;
	}

	public ClientRunnableTask(@Nonnull String taskName, @Nullable S settings, @Nonnull Consumer<ClientRunnableTask<S>> runnableWithProgress, @Nonnull Consumer<Throwable> exceptionHandler) {
		super(taskName, settings, wrapExceptionHandler(exceptionHandler));
		this.runnableWithProgress = runnableWithProgress;
	}

	@Override
	public final void run() {
		super.execute();
	}

	@Override
	protected Void executeInternal() {
		this.runnableWithProgress.accept(this);
		return null;
	}
}
