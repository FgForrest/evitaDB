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

package io.evitadb.core.scheduling;

import io.evitadb.core.metric.event.system.BackgroundTaskFinishedEvent;
import io.evitadb.core.metric.event.system.BackgroundTaskStartedEvent;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;

/**
 * Represents a task that is executed in the background. This is a thin wrapper around {@link Runnable} that emits
 * observability events before and after the task is executed.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@RequiredArgsConstructor
public class BackgroundTask implements Runnable {
	/**
	 * The name of the catalog that the task belongs to (may be NULL if the task is not bound to any particular catalog).
	 */
	private final String catalogName;
	/**
	 * The name of the task.
	 */
	@Getter
	private final String taskName;
	/**
	 * The actual logic wrapped in a lambda that is executed by the task.
	 */
	private final Runnable runnable;

	public BackgroundTask(@Nonnull String taskName, @Nonnull Runnable runnable) {
		this.catalogName = null;
		this.taskName = taskName;
		this.runnable = runnable;
	}

	@Override
	public final void run() {
		// emit the start event
		new BackgroundTaskStartedEvent(catalogName, taskName).commit();

		// prepare the finish event
		final BackgroundTaskFinishedEvent finishedEvent = new BackgroundTaskFinishedEvent(catalogName, taskName);
		try {
			runnable.run();
		} finally {
			// emit the finish event
			finishedEvent.finish().commit();
		}
	}

}
