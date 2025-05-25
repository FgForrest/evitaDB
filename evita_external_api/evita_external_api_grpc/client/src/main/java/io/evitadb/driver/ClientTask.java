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

package io.evitadb.driver;

import io.evitadb.api.task.Task;
import io.evitadb.api.task.TaskStatus;
import io.evitadb.api.task.TaskStatus.TaskSimplifiedState;
import io.evitadb.driver.exception.TaskFailedException;

import javax.annotation.Nonnull;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Client task mimics the server task counterpart and provides the same interface for the client side. It is used to
 * track the status of the task and to cancel it if needed.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class ClientTask<S, T> implements Task<S, T> {
	private final AtomicReference<TaskStatus<S, T>> status;
	private final ClientTaskCompletableFuture<T> result;
	private final Function<UUID, Boolean> cancellationLambda;
	private final Function<UUID, Optional<TaskStatus<?, ?>>> stateUpdater;

	/**
	 * Creates a TaskFailedException representing the failure of a task based on its status.
	 *
	 * @param status The status of the task, which may contain the reason for failure or additional context.
	 * @return A new instance of TaskFailedException containing an appropriate failure message derived from the task status.
	 */
	@Nonnull
	private static TaskFailedException createTaskFailedException(@Nonnull TaskStatus<?, ?> status) {
		return new TaskFailedException(
			status.publicExceptionMessage() == null ?
				"Task " + status.taskName() + " failed without reason." :
				status.publicExceptionMessage()
		);
	}

	public ClientTask(
		@Nonnull TaskStatus<S, T> status,
		@Nonnull Supplier<Function<UUID, Boolean>> cancellationLambdaFactory,
		@Nonnull Supplier<Function<UUID, Optional<TaskStatus<?, ?>>>> stateUpdater
	) {
		this.status = new AtomicReference<>(status);
		this.result = new ClientTaskCompletableFuture<>();
		if (status.simplifiedState() == TaskSimplifiedState.FINISHED) {
			this.result.complete(status.result());
			this.cancellationLambda = null;
			this.stateUpdater = null;
		} else if (status.simplifiedState() == TaskSimplifiedState.FAILED) {
			this.result.completeExceptionally(
				new TaskFailedException(
					status.publicExceptionMessage() == null ?
						"Task " + status.taskName() + " failed without reason." :
						status.publicExceptionMessage()
				)
			);
			this.cancellationLambda = null;
			this.stateUpdater = null;
		} else {
			this.cancellationLambda = cancellationLambdaFactory.get();
			this.stateUpdater = stateUpdater.get();
		}
	}

	public ClientTask(
		@Nonnull TaskStatus<S, T> status
	) {
		this(status, () -> null, () -> null);
	}

	@Nonnull
	@Override
	public TaskStatus<S, T> getStatus() {
		return this.status.get();
	}

	@Nonnull
	@Override
	public CompletableFuture<T> getFutureResult() {
		return this.result;
	}

	@Override
	public boolean cancel() {
		if (this.result.isDone() || this.result.isCancelled() || this.cancellationLambda == null) {
			return false;
		} else {
			final boolean cancelled = this.result.cancel(true);
			if (cancelled) {
				refreshStatus();
			}
			return cancelled;
		}
	}

	/**
	 * Discards task locally (the server task is not cancelled).
	 */
	public void discard() {
		this.result.cancel(true);
	}

	/**
	 * Returns true if the task is finished or cancelled.
	 *
	 * @return True if the task is finished or cancelled.
	 */
	public boolean isCompleted() {
		return this.result.isDone() || this.result.isCancelled() || this.status.get().finished() != null;
	}

	/**
	 * Updates internal status of the task according to new external state.
	 *
	 * @param status The new status of the task.
	 */
	public void updateStatus(@Nonnull TaskStatus<?, ?> status) {
		//noinspection unchecked
		final TaskStatus<S, T> theStatus = (TaskStatus<S, T>) status;
		this.status.set(theStatus);
		if (theStatus.simplifiedState() == TaskSimplifiedState.FINISHED) {
			this.result.complete(theStatus.result());
		} else if (theStatus.simplifiedState() == TaskSimplifiedState.FAILED) {
			this.result.completeExceptionally(createTaskFailedException(status));
		}
	}

	/**
	 * Refreshes the status of the task internally.
	 */
	private void refreshStatus() {
		this.stateUpdater.apply(this.status.get().taskId())
			.ifPresentOrElse(
				this::updateStatus,
				() -> this.updateStatus(this.status.get().transitionToFailed(new CancellationException("Task was canceled.")))
			);
	}

	/**
	 * This class is used to keep {@link ClientTask} alive as long as someone keeps a reference to the future. Task
	 * must not be ever garbage collected while the future is still referenced. That's why this inner class is not
	 * static.
	 */
	@SuppressWarnings("InnerClassMayBeStatic")
	private class ClientTaskCompletableFuture<X> extends CompletableFuture<X> {

		@Override
		public boolean cancel(boolean mayInterruptIfRunning) {
			if (ClientTask.this.result.isDone() || ClientTask.this.result.isCancelled() || ClientTask.this.cancellationLambda == null) {
				return false;
			} else {
				final Boolean canceledOnServer = ClientTask.this.cancellationLambda.apply(ClientTask.this.status.get().taskId());
				super.cancel(mayInterruptIfRunning);
				if (canceledOnServer) {
					ClientTask.this.refreshStatus();
				}
				return canceledOnServer;
			}
		}
	}

}
