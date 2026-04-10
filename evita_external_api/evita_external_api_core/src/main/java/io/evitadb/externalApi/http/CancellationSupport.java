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
 * ## Two submission modes
 *
 * This class provides two submission methods that differ in how the supplied work relates to
 * the returned result future and, consequently, how cancellation is wired:
 *
 * **{@link #submitWithCancellation} — synchronous supplier (`Supplier<T>`)**
 *
 * The supplier runs entirely inside the executor task. When the task body finishes,
 * the result future is already completed (either with a value or an exception).
 * Therefore task completion and result completion are tightly coupled, and it is safe
 * to use the task's {@code completionStage} as a fallback to cancel the result future
 * when the task finishes but the result is still pending (which can only happen when
 * the task was cancelled before it had a chance to run).
 *
 * **{@link #submitAsyncWithCancellation} — async supplier (`Supplier<CompletableFuture<T>>`)**
 *
 * The supplier performs synchronous setup (tracing context, query parsing, etc.)
 * inside the executor task and then returns a {@link CompletableFuture} representing
 * work that continues asynchronously (e.g., {@code GraphQL.executeAsync()}). The executor
 * task body finishes as soon as the supplier returns — **before** the inner future
 * completes. Task completion and result completion are therefore **decoupled**: the task
 * completing normally while the result is still pending is the expected happy path, not
 * an error. Wiring the task's {@code completionStage} to cancel the result would falsely
 * abort in-progress async operations. Instead, request cancellation is wired independently
 * to both the task (to interrupt setup work) and the result future (to propagate
 * cancellation to the caller).
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
	 * Submits a **synchronous** supplier for execution in the given thread pool with Armeria
	 * request cancellation support.
	 *
	 * The supplier runs entirely inside the executor task — when the task body finishes,
	 * the result future is guaranteed to be completed (with a value or exception). This tight
	 * coupling between task lifecycle and result lifecycle allows us to use the task's
	 * {@code completionStage} as a safety net: if the task completes but the result is still
	 * pending (which only happens when the task was cancelled before it started running),
	 * the result future is cancelled as well.
	 *
	 * ### Cancellation behavior
	 *
	 * - **Request cancelled before task starts**: task is cancelled, result future is cancelled
	 *   via the {@code completionStage} fallback.
	 * - **Request cancelled while task runs**: task is cancelled (worker thread interrupted),
	 *   supplier throws, result future completes exceptionally.
	 * - **Normal completion**: supplier returns a value, result future completes with it.
	 *
	 * Use {@link #submitAsyncWithCancellation} instead when the supplier returns a
	 * {@link CompletableFuture} and you want to release the thread pool thread immediately
	 * after the async work is kicked off.
	 *
	 * @param ctx      the Armeria service request context
	 * @param executor the executor to create and submit the task on
	 * @param supplier the supplier whose return value becomes the result; runs synchronously
	 *                 inside the executor task
	 * @param <T>      the type of the result
	 * @return a future that completes with the supplier's result, or is cancelled if the task
	 *         is cancelled before it runs
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

	/**
	 * Submits an **asynchronous** supplier for execution in the given thread pool with Armeria
	 * request cancellation support.
	 *
	 * Unlike {@link #submitWithCancellation}, the supplier here returns a {@link CompletableFuture}
	 * rather than a direct value. The executor task only runs the supplier's synchronous setup
	 * phase (e.g., tracing context initialization, query parsing) and then returns immediately,
	 * releasing the thread pool thread. The actual work continues asynchronously — the result
	 * future completes when the inner future returned by the supplier completes.
	 *
	 * Because of this, the executor task's lifecycle is **decoupled** from the result future's
	 * lifecycle: the task finishes (and its {@code completionStage} fires) while the result is
	 * still legitimately pending. This is the normal happy path. We therefore must **not** wire
	 * the task's {@code completionStage} to cancel the result (as {@link #submitWithCancellation}
	 * does), because that would falsely abort in-flight async operations.
	 *
	 * ### Cancellation behavior
	 *
	 * - **Request cancelled before task starts**: task is cancelled, request cancellation callback
	 *   completes the result future exceptionally.
	 * - **Request cancelled during synchronous setup**: task is cancelled (worker thread interrupted),
	 *   supplier throws, result future completes exceptionally.
	 * - **Request cancelled during async execution**: request cancellation callback completes
	 *   the result future exceptionally (the inner future may continue but its result is ignored).
	 * - **Normal completion**: inner future completes, result future completes with its value.
	 *
	 * @param ctx           the Armeria service request context
	 * @param executor      the executor to create and submit the task on
	 * @param asyncSupplier the supplier that performs synchronous setup and returns an async future;
	 *                      its synchronous part runs in the executor task, the returned future
	 *                      completes independently
	 * @param <T>           the type of the result
	 * @return a future that completes when the inner async future completes, or exceptionally
	 *         if the request is cancelled at any stage
	 */
	@Nonnull
	public static <T> CompletableFuture<T> submitAsyncWithCancellation(
		@Nonnull ServiceRequestContext ctx,
		@Nonnull ObservableExecutorServiceWithCancellationSupport executor,
		@Nonnull Supplier<CompletableFuture<T>> asyncSupplier
	) {
		final CompletableFuture<T> result = new CompletableFuture<>();
		final CancellableRunnable task = executor.createTask(() -> {
			try {
				asyncSupplier.get().whenComplete((value, error) -> {
					if (error != null) {
						result.completeExceptionally(error);
					} else {
						result.complete(value);
					}
				});
			} catch (Throwable t) {
				result.completeExceptionally(t);
			}
		});
		// Wire request cancellation to cancel the executor task (interrupt worker thread).
		// Unlike submitWithCancellation, we must NOT use wireCancellation(ctx, task, result)
		// because the task body only starts async work — the task's completionStage fires
		// before the async operation finishes, which would falsely cancel the result future.
		wireCancellation(ctx, task);
		// Wire request cancellation directly to the result future, so it completes
		// even if the task was cancelled before the async supplier ran
		ctx.whenRequestCancelling().thenAccept(cause -> {
			if (!result.isDone()) {
				result.completeExceptionally(cause);
			}
		});
		executor.execute(task);
		return result;
	}

}
