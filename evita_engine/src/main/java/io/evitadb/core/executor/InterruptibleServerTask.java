/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

import io.evitadb.api.task.ServerTask;

import javax.annotation.Nonnull;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

/**
 * Implemented by {@link ServerTask}s whose cancellation must be able to interrupt the worker thread that is currently
 * running them.
 *
 * ## Why the executor's handle is needed
 *
 * A task's own {@link ServerTask#getFutureResult()} is a {@link CompletableFuture}, and
 * {@link CompletableFuture#cancel(boolean)} documents that `mayInterruptIfRunning` **has no effect** — that
 * implementation does not use interrupts at all. Cancelling through it therefore marks the result cancelled while the
 * worker thread keeps running to completion, burning CPU nobody is waiting for.
 *
 * The only handle that can interrupt the worker is the {@link Future} returned by the executor's `submit(...)`, which
 * is backed by a `FutureTask`. {@link Scheduler#submitTaskInQueue} used to discard it. It now hands it here instead,
 * so {@link ServerTask#cancel()} can interrupt for real.
 *
 * A `FutureTask` is preferred over tracking the executing thread and interrupting it directly: its cancel state machine
 * only delivers the interrupt while the task is genuinely running, so a cancel racing with completion cannot poison the
 * next task that the pooled thread picks up. That distinction matters now that the `@Interruptible` checkpoints
 * actually poll the flag.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
interface InterruptibleServerTask {

	/**
	 * Attaches the executor handle that {@link ServerTask#cancel()} uses to interrupt the running worker thread.
	 *
	 * Called by {@link Scheduler#submitTaskInQueue} right after submission — which means the task may already be
	 * running, or even finished, by the time this arrives. Implementations must therefore accept the handle at any
	 * point in the lifecycle.
	 *
	 * ## The handshake implementations must honour
	 *
	 * A cancel arriving in the window between `submit(...)` and this call cannot rely on the `QUEUED` guard in the
	 * task's `execute()` to stop the body: once the worker has read the status and transitioned the task to STARTED,
	 * that guard is already behind it and nothing else stops the work. Losing the cancel there would leave the task
	 * running to completion with a cancelled result future — the outcome this interface exists to prevent.
	 *
	 * The two sides therefore touch the same two volatile locations in **opposite** order:
	 *
	 * - `cancel()` cancels the result future first, then reads the handle,
	 * - this method publishes the handle first, then re-reads the result future and cancels the handle when it finds
	 *   the task already cancelled.
	 *
	 * At least one side is then guaranteed to observe the other, so a cancel racing the attachment is never lost.
	 * Implementations must keep both fields volatile and must not reorder these two steps.
	 *
	 * @param handle the executor's handle for the submitted task
	 */
	void attachExecutionHandle(@Nonnull Future<?> handle);

}
