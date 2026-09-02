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

package io.evitadb.api.exception;

import io.evitadb.exception.EvitaInvalidUsageException;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.util.UUID;

/**
 * Exception marking a background task that waited for its precondition longer than the scheduler is willing to
 * keep it, and was therefore removed from the task queue.
 *
 * The task never ran. It is the terminal state of a task whose precondition never arrived - most visibly a
 * chunked catalog-restore upload that stopped sending chunks partway through, leaving the restoration task
 * parked in the queue with nothing left to complete it.
 *
 * The scheduler completes the task's future with this exception rather than discarding the task silently, so
 * that anything joining that future observes the timeout instead of waiting forever.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class TaskTimedOutException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = -4986213977905927613L;
	/**
	 * The UUID of the task that timed out, useful for client-side debugging.
	 */
	@Getter private final UUID taskId;

	/**
	 * Creates a new exception indicating that the task waited for its precondition for too long.
	 *
	 * @param taskId the UUID of the task that timed out
	 */
	public TaskTimedOutException(@Nonnull UUID taskId) {
		super(
			"Task " + taskId + " waited for its precondition for too long and was removed from the queue.",
			"Task waited for its precondition for too long and was removed from the queue."
		);
		this.taskId = taskId;
	}
}
