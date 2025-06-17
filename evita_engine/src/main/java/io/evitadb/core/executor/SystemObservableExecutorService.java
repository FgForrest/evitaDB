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


import io.evitadb.core.executor.ObservableThreadExecutor.ObservableCallable;
import io.evitadb.core.executor.ObservableThreadExecutor.ObservableRunnable;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Implementation of {@link ObservableExecutorService} that wraps another executor service and adds system-level
 * observability to all submitted tasks. This implementation decorates all submitted tasks with {@link ObservableCallable}
 * or {@link ObservableRunnable} to track their execution.
 *
 * The class maintains a name identifier for the executor service and delegates all execution operations to the underlying
 * executor service while wrapping tasks with observable counterparts. This allows for monitoring and tracking of task
 * execution within the system context.
 *
 * All submitted tasks will never be canceled due to timeout.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@RequiredArgsConstructor
public class SystemObservableExecutorService implements ObservableExecutorService {
	private final String name;
	private final ObservableExecutorService delegate;

	@Override
	public long getSubmittedTaskCount() {
		return this.delegate.getSubmittedTaskCount();
	}

	@Override
	public long getRejectedTaskCount() {
		return this.delegate.getRejectedTaskCount();
	}

	@Override
	public void shutdown() {
		this.delegate.shutdown();
	}

	@Nonnull
	@Override
	public List<Runnable> shutdownNow() {
		return this.delegate.shutdownNow();
	}

	@Override
	public boolean isShutdown() {
		return this.delegate.isShutdown();
	}

	@Override
	public boolean isTerminated() {
		return this.delegate.isTerminated();
	}

	@Override
	public boolean awaitTermination(long timeout, @Nonnull TimeUnit unit) throws InterruptedException {
		return this.delegate.awaitTermination( timeout, unit);
	}

	@Nonnull
	@Override
	public <T> Future<T> submit(@Nonnull Callable<T> task) {
		return this.delegate.submit(new ObservableCallable<>(this.name, task, Long.MAX_VALUE));
	}

	@Nonnull
	@Override
	public <T> Future<T> submit(@Nonnull Runnable task, T result) {
		return this.delegate.submit(new ObservableRunnable(this.name, task, Long.MAX_VALUE), result);
	}

	@Nonnull
	@Override
	public Future<?> submit(@Nonnull Runnable task) {
		return this.delegate.submit(new ObservableRunnable(this.name, task, Long.MAX_VALUE));
	}

	@Nonnull
	@Override
	public <T> List<Future<T>> invokeAll(@Nonnull Collection<? extends Callable<T>> tasks) throws InterruptedException {
		return this.delegate.invokeAll(
			tasks.stream()
				.map(task -> new ObservableCallable<>(this.name, task, Long.MAX_VALUE))
				.toList()
		);
	}

	@Nonnull
	@Override
	public <T> List<Future<T>> invokeAll(@Nonnull Collection<? extends Callable<T>> tasks, long timeout, @Nonnull TimeUnit unit) throws InterruptedException {
		return this.delegate.invokeAll(
			tasks.stream()
				.map(task -> new ObservableCallable<>(this.name, task, unit.toNanos(timeout)))
				.toList()
		);
	}

	@Nonnull
	@Override
	public <T> T invokeAny(@Nonnull Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException {
		return this.delegate.invokeAny(
			tasks.stream()
				.map(task -> new ObservableCallable<>(this.name, task, Long.MAX_VALUE))
				.toList()
		);
	}

	@Override
	public <T> T invokeAny(@Nonnull Collection<? extends Callable<T>> tasks, long timeout, @Nonnull TimeUnit unit) throws InterruptedException, ExecutionException {
		return this.delegate.invokeAny(
			tasks.stream()
				.map(task -> new ObservableCallable<>(this.name, task, unit.toNanos(timeout)))
				.toList()
		);
	}

	@Override
	public void execute(@Nonnull Runnable command) {
		this.delegate.execute(new ObservableRunnable(this.name, command, Long.MAX_VALUE));
	}

}
