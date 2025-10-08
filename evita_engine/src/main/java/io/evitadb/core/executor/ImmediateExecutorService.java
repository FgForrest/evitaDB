/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Implementation of {@link ExecutorService} that executes tasks immediately in the calling thread.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class ImmediateExecutorService implements ExecutorService {
	private boolean shutdown = false;

	/**
	 * A Future implementation that represents a task that has already completed.
	 */
	private static class CompletedFuture<T> implements Future<T> {
		private final T result;
		private final ExecutionException exception;

		/**
		 * Creates a completed future with a result.
		 */
		CompletedFuture(T result) {
			this.result = result;
			this.exception = null;
		}

		/**
		 * Creates a completed future without a result.
		 */
		CompletedFuture() {
			this.result = null;
			this.exception = null;
		}

		/**
		 * Creates a completed future with an exception.
		 */
		CompletedFuture(ExecutionException exception) {
			this.result = null;
			this.exception = exception;
		}

		@Override
		public boolean cancel(boolean mayInterruptIfRunning) {
			return false; // Cannot cancel a completed task
		}

		@Override
		public boolean isCancelled() {
			return false; // Never cancelled
		}

		@Override
		public boolean isDone() {
			return true; // Always done
		}

		@Nullable
		@Override
		public T get() throws ExecutionException {
			if (this.exception != null) {
				throw this.exception;
			}
			return this.result;
		}

		@Nullable
		@Override
		public T get(long timeout, @Nonnull TimeUnit unit) throws ExecutionException {
			return get(); // No need to wait, already completed
		}
	}

	@Override
	public void execute(@Nonnull Runnable command) {
		command.run();
	}

	@Override
	public <T> T invokeAny(@Nonnull Collection<? extends Callable<T>> tasks, long timeout, @Nonnull TimeUnit unit)
			throws ExecutionException {
		return invokeAny(tasks);
	}

	@Nonnull
	@Override
	public <T> T invokeAny(@Nonnull Collection<? extends Callable<T>> tasks)
			throws ExecutionException {
		if (tasks.isEmpty()) {
			throw new IllegalArgumentException("Task collection is empty");
		}

		ExecutionException lastException = null;

		for (Callable<T> task : tasks) {
			try {
				return task.call();
			} catch (Exception e) {
				lastException = new ExecutionException(e);
			}
		}

		throw lastException;
	}

	@Nonnull
	@Override
	public <T> List<Future<T>> invokeAll(@Nonnull Collection<? extends Callable<T>> tasks, long timeout, @Nonnull TimeUnit unit) {
		return invokeAll(tasks);
	}

	@Nonnull
	@Override
	public <T> List<Future<T>> invokeAll(@Nonnull Collection<? extends Callable<T>> tasks) {
		final List<Future<T>> futures = new ArrayList<>(tasks.size());

		for (Callable<T> task : tasks) {
			try {
				T result = task.call();
				futures.add(new CompletedFuture<>(result));
			} catch (Exception e) {
				futures.add(new CompletedFuture<>(new ExecutionException(e)));
			}
		}

		return futures;
	}

	@Nonnull
	@Override
	public Future<?> submit(@Nonnull Runnable task) {
		task.run();
		return new CompletedFuture<>();
	}

	@Nonnull
	@Override
	public <T> Future<T> submit(@Nonnull Runnable task, T result) {
		task.run();
		return new CompletedFuture<>(result);
	}

	@Nonnull
	@Override
	public <T> Future<T> submit(@Nonnull Callable<T> task) {
		try {
			T result = task.call();
			return new CompletedFuture<>(result);
		} catch (Exception e) {
			return new CompletedFuture<>(new ExecutionException(e));
		}
	}

	@Override
	public boolean awaitTermination(long timeout, @Nonnull TimeUnit unit) {
		return true; // Always terminated since tasks are executed immediately
	}

	@Override
	public boolean isTerminated() {
		return this.shutdown; // Terminated if shutdown has been called
	}

	@Override
	public boolean isShutdown() {
		return this.shutdown;
	}

	@Nonnull
	@Override
	public List<Runnable> shutdownNow() {
		this.shutdown = true;
		return List.of(); // No pending tasks to return
	}

	@Override
	public void shutdown() {
		this.shutdown = true;
	}

}
