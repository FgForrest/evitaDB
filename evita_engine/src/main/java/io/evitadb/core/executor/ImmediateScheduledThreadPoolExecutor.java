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


import io.evitadb.api.task.InfiniteTask;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Delegate;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Delayed;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * A test utility implementation of {@link ScheduledThreadPoolExecutor} that executes tasks immediately
 * when the delay is 0 or negative, instead of scheduling them. For tasks with a positive delay,
 * it uses the parent class's implementation.
 *
 * This class is particularly useful for testing asynchronous code without introducing actual delays,
 * making tests run faster and more deterministically. It's designed to be used in test environments
 * where immediate execution of scheduled tasks is preferred.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class ImmediateScheduledThreadPoolExecutor extends ScheduledThreadPoolExecutor {
	private boolean shutdown = false;

	public ImmediateScheduledThreadPoolExecutor() {
		super(4);
	}

	@Nonnull
	@Override
	public ScheduledFuture<?> schedule(@Nonnull Runnable command, long delay, @Nonnull TimeUnit unit) {
		if (delay > 0) {
			return super.schedule(command, delay, unit);
		} else {
			command.run();
			return new TestScheduledFuture<>(CompletableFuture.completedFuture(null));
		}
	}

	@Nonnull
	@Override
	public <V> ScheduledFuture<V> schedule(@Nonnull Callable<V> callable, long delay, @Nonnull TimeUnit unit) {
		if (delay > 0 || callable instanceof InfiniteTask<?, ?>) {
			return super.schedule(callable, delay, unit);
		} else {
			try {
				final V result = callable.call();
				return new TestScheduledFuture<>(CompletableFuture.completedFuture(result));
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		}
	}

	@Override
	public void execute(@Nonnull Runnable command) {
		command.run();
	}

	@Nonnull
	@Override
	public Future<?> submit(@Nonnull Runnable task) {
		task.run();
		return CompletableFuture.completedFuture(null);
	}

	@Nonnull
	@Override
	public <T> Future<T> submit(@Nonnull Runnable task, T result) {
		task.run();
		return CompletableFuture.completedFuture(result);
	}

	@Nonnull
	@Override
	public <T> Future<T> submit(@Nonnull Callable<T> task) {
		final T result;
		try {
			if (task instanceof InfiniteTask<?,?>) {
				return super.submit(task);
			} else {
				result = task.call();
				return CompletableFuture.completedFuture(result);
			}
		} catch (Exception e) {
			return CompletableFuture.failedFuture(e);
		}
	}

	@Override
	public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
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
		super.shutdownNow();
		return List.of(); // No pending tasks to return
	}

	@Override
	public void shutdown() {
		this.shutdown = true;
		super.shutdown();
	}

	@RequiredArgsConstructor
	@EqualsAndHashCode
	private static class TestScheduledFuture<T> implements ScheduledFuture<T> {
		@Delegate
		private final CompletableFuture<T> future;

		@Override
		public long getDelay(@Nonnull TimeUnit delay) {
			return Long.MIN_VALUE;
		}

		@Override
		public int compareTo(@Nonnull Delayed o) {
			throw new UnsupportedOperationException();
		}

	}
}
