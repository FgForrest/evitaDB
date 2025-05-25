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

package io.evitadb.api.task;

import io.evitadb.exception.EvitaError;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.PrintWriter;
import java.io.Serializable;
import java.io.StringWriter;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.UUID;
import java.util.concurrent.CancellationException;

/**
 * This record provides the status of a task, including telemetry information and access to its progress, settings,
 * and result.
 *
 * @param taskType                The name of the task (short class name).
 * @param taskName                The human readable name of the task.
 * @param taskId                  The unique identifier of the task.
 * @param catalogName             The name of the catalog that the task belongs to (may be NULL if the task is not bound to any particular catalog).
 * @param issued                  The time when the task was issued.
 * @param started                 The time when the task was started.
 * @param finished                The time when the task was finished.
 * @param progress                The progress of the task (0-100).
 * @param settings                The settings of the task.
 * @param result                  The result of the task.
 * @param exceptionWithStackTrace The exception with stack trace if the task failed.
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public record TaskStatus<S, T>(
	@Nonnull String taskType,
	@Nonnull String taskName,
	@Nonnull UUID taskId,
	@Nullable String catalogName,
	@Nonnull OffsetDateTime created,
	@Nullable OffsetDateTime issued,
	@Nullable OffsetDateTime started,
	@Nullable OffsetDateTime finished,
	int progress,
	@Nonnull S settings,
	@Nullable T result,
	@Nullable String publicExceptionMessage,
	@Nullable String exceptionWithStackTrace,
	@Nonnull EnumSet<TaskTrait> traits
	) implements Serializable {

	/**
	 * Returns the shortened state of the task.
	 *
	 * @return The state of the task.
	 */
	@Nonnull
	public TaskSimplifiedState simplifiedState() {
		if (this.exceptionWithStackTrace != null || this.publicExceptionMessage != null) {
			return TaskSimplifiedState.FAILED;
		} else if (this.finished != null) {
			return TaskSimplifiedState.FINISHED;
		} else if (this.started != null) {
			return TaskSimplifiedState.RUNNING;
		} else if (this.issued != null) {
			return TaskSimplifiedState.QUEUED;
		} else {
			return TaskSimplifiedState.WAITING_FOR_PRECONDITION;
		}
	}

	/**
	 * Returns new instance of {@link TaskStatus} with updated progress.
	 *
	 * @param progress The new progress of the task.
	 * @return The new instance of {@link TaskStatus} with updated progress.
	 */
	@Nonnull
	public TaskStatus<S, T> updateProgress(int progress) {
		if (progress != this.progress) {
			return new TaskStatus<>(
				this.taskType,
				this.taskName,
				this.taskId,
				this.catalogName,
				this.created,
				this.issued,
				this.started,
				this.finished,
				progress,
				this.settings,
				this.result,
				this.publicExceptionMessage,
				this.exceptionWithStackTrace,
				this.traits
			);
		} else {
			return this;
		}
	}

	/**
	 * Updates the name of the task and returns a new instance of {@link TaskStatus}
	 * with the updated task name, if the new name is different from the current name.
	 *
	 * @param taskName The new name for the task.
	 * @param traits  The traits of the task.
	 * @return The new instance of {@link TaskStatus} with the updated task name.
	 */
	@Nonnull
	public TaskStatus<S, T> updateTaskNameAndTraits(@Nonnull String taskName, @Nonnull TaskTrait... traits) {
		if (!taskName.equals(this.taskName) || !ArrayUtils.equals(traits, this.traits)) {
			return new TaskStatus<>(
				this.taskType,
				taskName,
				this.taskId,
				this.catalogName,
				this.created,
				this.issued,
				this.started,
				this.finished,
				this.progress,
				this.settings,
				this.result,
				this.publicExceptionMessage,
				this.exceptionWithStackTrace,
				ArrayUtils.isEmpty(traits) ?
					EnumSet.noneOf(TaskTrait.class) : EnumSet.of(traits[0], traits)
			);
		} else {
			return this;
		}
	}

	/**
	 * Returns new instance of {@link TaskStatus} with updated issue time.
	 *
	 * @return The new instance of {@link TaskStatus} with updated issue time.
	 */
	@Nonnull
	public TaskStatus<S, T> transitionToIssued() {
		Assert.isTrue(this.issued == null, "Task is already issued.");
		return new TaskStatus<>(
			this.taskType,
			this.taskName,
			this.taskId,
			this.catalogName,
			this.created,
			OffsetDateTime.now(),
			null,
			null,
			0,
			this.settings,
			this.result,
			this.publicExceptionMessage,
			this.exceptionWithStackTrace,
			this.traits
		);
	}

	/**
	 * Returns new instance of {@link TaskStatus} with updated started time and progress.
	 *
	 * @return The new instance of {@link TaskStatus} with updated started time and progress.
	 */
	@Nonnull
	public TaskStatus<S, T> transitionToStarted() {
		return new TaskStatus<>(
			this.taskType,
			this.taskName,
			this.taskId,
			this.catalogName,
			this.created,
			this.issued,
			OffsetDateTime.now(),
			null,
			0,
			this.settings,
			this.result,
			this.publicExceptionMessage,
			this.exceptionWithStackTrace,
			this.traits
		);
	}

	/**
	 * Returns new instance of {@link TaskStatus} with updated finished time and result.
	 *
	 * @param result   The result of the task.
	 * @return The new instance of {@link TaskStatus} with updated finished time and result.
	 */
	@Nonnull
	public TaskStatus<S, T> transitionToFinished(@Nullable T result) {
		return new TaskStatus<>(
			this.taskType,
			this.taskName,
			this.taskId,
			this.catalogName,
			this.created,
			this.issued,
			this.started,
			OffsetDateTime.now(),
			100,
			this.settings,
			result,
			null,
			null,
			this.traits
		);
	}

	/**
	 * Returns new instance of {@link TaskStatus} with updated exception.
	 *
	 * @param exception The exception that caused the task to fail.
	 * @return The new instance of {@link TaskStatus} with updated exception.
	 */
	@Nonnull
	public TaskStatus<S, T> transitionToFailed(@Nonnull Throwable exception) {
		// copy the stack trace
		final StringWriter sw = new StringWriter(512);
		exception.printStackTrace(new PrintWriter(sw));

		final String publicException;
		if (exception instanceof EvitaError evitaError) {
			publicException = evitaError.getPublicMessage();
		} else if (exception instanceof CancellationException) {
			publicException = "Task was cancelled.";
		} else {
			publicException = "Task failed for unknown reasons.";
		}

		return new TaskStatus<>(
			this.taskType,
			this.taskName,
			this.taskId,
			this.catalogName,
			this.created,
			this.issued,
			this.started,
			OffsetDateTime.now(),
			100,
			this.settings,
			null,
			publicException,
			exception.getClass().getName() + ": " + exception.getMessage() + "\n" + sw,
			this.traits
		);
	}

	/**
	 * State aggregates the possible states of a task into a simple enumeration.
	 */
	public enum TaskSimplifiedState {
		/**
		 * Task is waiting in for precondition to be fulfilled.
		 */
		WAITING_FOR_PRECONDITION,
		/**
		 * Task is waiting in the queue to be executed.
		 */
		QUEUED,
		/**
		 * Task is currently running.
		 */
		RUNNING,
		/**
		 * Task has finished successfully.
		 */
		FINISHED,
		/**
		 * Task has failed.
		 */
		FAILED
	}

	/**
	 * Enum describes traits of a {@link ServerTask} task.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
	 */
	public enum TaskTrait {

		/**
		 * Task can be manually started by the user.
		 */
		CAN_BE_STARTED,
		/**
		 * Task can be manually cancelled by the user.
		 */
		CAN_BE_CANCELLED,
		/**
		 * Task needs to be manually stopped by the user (otherwise it will run indefinitely).
		 */
		NEEDS_TO_BE_STOPPED

	}

}
