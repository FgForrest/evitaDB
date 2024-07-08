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

package io.evitadb.driver;

import io.evitadb.api.task.Task;
import io.evitadb.api.task.TaskStatus;
import io.evitadb.api.task.TaskStatus.State;
import io.evitadb.driver.exception.TaskFailedException;

import javax.annotation.Nonnull;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
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
	private final Consumer<UUID> cancellationLambda;

	public ClientTask(
		@Nonnull TaskStatus<S, T> status,
		@Nonnull Supplier<Consumer<UUID>> cancellationLambdaFactory
	) {
		this.status = new AtomicReference<>(status);
		this.result = new ClientTaskCompletableFuture<>();
		if (status.state() == State.FINISHED) {
			this.result.complete(status.result());
			this.cancellationLambda = null;
		} else if (status.state() == State.FAILED) {
			this.result.completeExceptionally(
				new TaskFailedException(status.publicExceptionMessage())
			);
			this.cancellationLambda = null;
		} else {
			this.cancellationLambda = cancellationLambdaFactory.get();
		}
	}

	public ClientTask(
		@Nonnull TaskStatus<S, T> status
	) {
		this(status, () -> null);
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
			this.result.cancel(true);
			this.cancellationLambda.accept(this.status.get().taskId());
			return true;
		}
	}

	public void discard() {
		this.result.cancel(true);
	}

	public boolean isCompleted() {
		return this.result.isDone() || this.result.isCancelled() || this.status.get().finished() != null;
	}

	public void updateStatus(@Nonnull TaskStatus<?, ?> status) {
		//noinspection unchecked
		final TaskStatus<S, T> theStatus = (TaskStatus<S, T>) status;
		this.status.set(theStatus);
		if (theStatus.state() == State.FINISHED) {
			System.out.println("Finishing " + status.taskId());
			this.result.complete(theStatus.result());
		} else if (theStatus.state() == State.FAILED) {
			System.out.println("Failing " + status.taskId());
			this.result.completeExceptionally(
				new TaskFailedException(theStatus.publicExceptionMessage())
			);
		}
	}

	/**
	 * This class is used to keep {@link ClientTask} alive as long as someone keeps a reference to the future. Task
	 * must not be ever garbage collected while the future is still referenced. That's why this inner class is not
	 * static.
	 * @param <X>
	 */
	@SuppressWarnings("InnerClassMayBeStatic")
	private class ClientTaskCompletableFuture<X> extends CompletableFuture<X> {

	}

}
