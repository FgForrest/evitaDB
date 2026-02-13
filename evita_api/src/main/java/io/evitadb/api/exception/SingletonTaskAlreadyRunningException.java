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

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Exception thrown when attempting to start a singleton task that is already running. Certain operations
 * in evitaDB are designed to run as singleton tasks per server instance to prevent resource conflicts,
 * data corruption, or duplicate processing.
 *
 * **Singleton tasks include:**
 *
 * - JFR (Java Flight Recorder) recording - started via {@link io.evitadb.externalApi.observability.ObservabilityManager}
 * - Traffic recording - started via {@link io.evitadb.api.EvitaSessionContract#startTrafficRecording(io.evitadb.core.Evita, java.nio.file.Path, java.time.OffsetDateTime, java.time.OffsetDateTime, int)}
 *
 * The client should either wait for the existing task to complete, cancel it explicitly if permitted,
 * or retrieve the status of the running task instead of attempting to start a new one.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class SingletonTaskAlreadyRunningException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = -213346139859613431L;

	/**
	 * Creates a new exception indicating that the specified singleton task is already running.
	 *
	 * @param taskName the name of the task that is already running
	 */
	public SingletonTaskAlreadyRunningException(@Nonnull String taskName) {
		super(
			"Task " + taskName + " is already running. Only one instance of the task can run at a time.",
			"Please wait until the task is finished or cancel the running task."
		);
	}
}
