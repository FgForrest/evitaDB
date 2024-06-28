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

package io.evitadb.api.task;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.PrintWriter;
import java.io.Serializable;
import java.io.StringWriter;
import java.time.OffsetDateTime;
import java.util.UUID;

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
	@Nonnull OffsetDateTime issued,
	@Nullable OffsetDateTime started,
	@Nullable OffsetDateTime finished,
	int progress,
	@Nonnull S settings,
	@Nullable T result,
	@Nullable String exceptionWithStackTrace
) implements Serializable {

	/**
	 * Returns the shortened state of the task.
	 *
	 * @return The state of the task.
	 */
	@Nonnull
	public State state() {
		if (exceptionWithStackTrace != null) {
			return State.FAILED;
		} else if (finished != null) {
			return State.FINISHED;
		} else if (started != null) {
			return State.RUNNING;
		} else {
			return State.QUEUED;
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
				this.issued,
				this.started,
				this.finished,
				progress,
				this.settings,
				this.result,
				this.exceptionWithStackTrace
			);
		} else {
			return this;
		}
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
			this.issued,
			OffsetDateTime.now(),
			null,
			0,
			this.settings,
			this.result,
			this.exceptionWithStackTrace
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
			this.issued,
			this.started,
			OffsetDateTime.now(),
			100,
			this.settings,
			result,
			null
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

		return new TaskStatus<>(
			this.taskType,
			this.taskName,
			this.taskId,
			this.catalogName,
			this.issued,
			this.started,
			OffsetDateTime.now(),
			100,
			this.settings,
			null,
			exception.getClass().getName() + ": " + exception.getMessage() + "\n" + sw
		);
	}

	/**
	 * State aggregates the possible states of a task into a simple enumeration.
	 */
	public enum State {
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

}
