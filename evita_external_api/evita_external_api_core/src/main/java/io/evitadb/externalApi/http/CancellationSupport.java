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

package io.evitadb.externalApi.http;

import com.linecorp.armeria.server.ServiceRequestContext;
import io.evitadb.core.executor.CancellableRunnable;
import io.evitadb.core.executor.CancellableTask;
import io.evitadb.core.executor.ObservableExecutorServiceWithCancellationSupport;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Utility for wiring Armeria request cancellation to executor tasks. When a client disconnects
 * or a request times out, Armeria fires the {@link ServiceRequestContext#whenRequestCancelling()}
 * callback. This utility hooks that callback to cancel the associated executor task, interrupting
 * the worker thread so that existing {@code InterruptionTransformer} checkpoints can stop the query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public final class CancellationSupport {

	private CancellationSupport() {
		// utility class
	}

	/**
	 * Wires Armeria request cancellation to the given task. If the request is cancelled
	 * (client disconnect, timeout), the task will be cancelled with thread interruption.
	 *
	 * @param ctx  the Armeria service request context, may be null (no-op if null)
	 * @param task the cancellable task to wire cancellation to
	 */
	public static void wireCancellation(@Nullable ServiceRequestContext ctx, @Nonnull CancellableTask<?> task) {
		if (ctx != null) {
			ctx.whenRequestCancelling().thenAccept(cause -> {
				if (!task.isFinished()) {
					task.cancel();
				}
			});
		}
	}

	/**
	 * Wires Armeria request cancellation to the given task and ensures that when the internal task
	 * future completes (including cancellation), the external result future is also completed.
	 * This prevents the result future from hanging indefinitely when a task is cancelled before
	 * it starts running.
	 *
	 * @param ctx    the Armeria service request context, may be null (cancellation wiring is skipped if null)
	 * @param task   the cancellable task to wire cancellation to
	 * @param result the external result future to propagate completion to
	 */
	public static void wireCancellation(
		@Nullable ServiceRequestContext ctx,
		@Nonnull CancellableTask<?> task,
		@Nonnull CompletableFuture<?> result
	) {
		final AtomicReference<Throwable> cancellationCause = new AtomicReference<>();
		if (ctx != null) {
			ctx.whenRequestCancelling().thenAccept(cause -> {
				cancellationCause.set(cause);
				if (!task.isFinished()) {
					task.cancel();
				}
			});
		}
		task.completionStage().whenComplete((v, ex) -> {
			if (!result.isDone()) {
				final Throwable cause = cancellationCause.get();
				if (cause != null) {
					result.completeExceptionally(cause);
				} else {
					result.cancel(false);
				}
			}
		});
	}

	/**
	 * Creates a task from the given supplier, wires Armeria cancellation, ensures the result future
	 * is completed on cancellation, and submits the task for execution.
	 *
	 * @param ctx      the Armeria service request context
	 * @param executor the executor to create and submit the task on
	 * @param supplier the supplier to execute asynchronously
	 * @param <T>      the type of the result
	 * @return a future that completes with the supplier's result, or is cancelled if the task is cancelled
	 */
	@Nonnull
	public static <T> CompletableFuture<T> submitWithCancellation(
		@Nonnull ServiceRequestContext ctx,
		@Nonnull ObservableExecutorServiceWithCancellationSupport executor,
		@Nonnull Supplier<T> supplier
	) {
		final CompletableFuture<T> result = new CompletableFuture<>();
		final CancellableRunnable task = executor.createTask(() -> {
			try {
				result.complete(supplier.get());
			} catch (Throwable t) {
				result.completeExceptionally(t);
			}
		});
		wireCancellation(ctx, task, result);
		executor.execute(task);
		return result;
	}

}
