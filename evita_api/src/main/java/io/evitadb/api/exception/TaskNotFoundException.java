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

package io.evitadb.api.exception;

import io.evitadb.exception.EvitaInvalidUsageException;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.util.UUID;

/**
 * Exception thrown when attempting to access, query, or cancel a background task that cannot be found
 * by its unique identifier. evitaDB uses background tasks for long-running operations such as:
 *
 * - Traffic recording
 * - JFR (Java Flight Recorder) recording
 * - Catalog restoration from backups
 * - Data export operations
 *
 * **This exception can occur when:**
 *
 * - The task ID is invalid or mistyped
 * - The task has already completed and been removed from the task registry
 * - The task was never created (incorrect UUID)
 * - The server was restarted and task state was not persisted
 *
 * Clients should verify the task ID and check the list of available tasks before attempting to
 * access a specific task.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class TaskNotFoundException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = 6188715534953413955L;
	/**
	 * The UUID of the task that could not be found, useful for client-side debugging.
	 */
	@Getter private final UUID taskId;

	/**
	 * Creates a new exception indicating that the task with the specified ID was not found.
	 *
	 * @param taskId the UUID of the task that could not be located
	 */
	public TaskNotFoundException(@Nonnull UUID taskId) {
		super(
			"Task not found: " + taskId,
			"Task not found."
		);
		this.taskId = taskId;
	}
}
