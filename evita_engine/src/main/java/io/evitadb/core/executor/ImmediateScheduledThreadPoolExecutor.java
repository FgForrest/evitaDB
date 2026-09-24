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
		// A task with a positive delay is handed to the real ScheduledThreadPoolExecutor below, which spawns worker
		// threads that park on the delayed queue. ScheduledThreadPoolExecutor's DEFAULT policy keeps those tasks
		// scheduled across `shutdown()`, so the pool stays alive until each delay elapses and its threads - which are
		// GC roots - go on retaining whatever the owning Evita instance reaches, in this suite roughly 2 MB of pooled
		// Kryo output buffers per instance. Nothing scheduled through a test executor has any business outliving the
		// shutdown that discards it, so both policies are turned off and `shutdown()` really does terminate.
		setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
		setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
		// the third of the three policies the production pool sets, so this stand-in does not quietly differ from
		// what it stands in for. It costs footprint, not retention: a cancelled task releases what it captured
		// either way, because the JDK nulls a cancelled task's callable, so the entry left behind is an empty
		// husk. Without this policy that husk sits in the delayed queue until its delay elapses - up to five
		// minutes for an output keeper's cut task - lengthening every scan of the queue, and the executor-taking
		// `Scheduler` constructor creates no periodic purge to sweep it up. It buys nothing at engine close,
		// where both shutdown policies above already clear the queue outright; it is for tasks cancelled while
		// the engine is still alive, such as a catalog closing or a keeper going idle.
		setRemoveOnCancelPolicy(true);
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
		// this used to return `true` unconditionally, which is only correct for the zero-delay tasks this class runs
		// inline. A positive-delay task goes to the superclass and keeps real threads alive, and claiming termination
		// here made `Evita#shutdownScheduler` skip its `shutdownNow()` fallback - so the escape hatch could never
		// fire and the pool was never forced down. Report what actually happened instead.
		return super.awaitTermination(timeout, unit);
	}

	@Override
	public boolean isTerminated() {
		return super.isTerminated();
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
