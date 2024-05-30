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

package io.evitadb.core.metric.event.system;

import io.evitadb.api.observability.annotation.ExportInvocationMetric;
import jdk.jfr.Description;
import jdk.jfr.Label;
import jdk.jfr.Name;

import javax.annotation.Nonnull;

/**
 * Event that is fired when a background task is finished.
 */
@Name(AbstractSystemEvent.PACKAGE_NAME + ".BackgroundTaskFinished")
@Description("Event that is fired when a background task is finished.")
@ExportInvocationMetric(label = "Background tasks finished")
@Label("Background task finished")
public class BackgroundTaskFinishedEvent extends AbstractBackgroundTaskEvent {

	public BackgroundTaskFinishedEvent(@Nonnull String catalogName, @Nonnull String taskName) {
		super(catalogName, taskName);
		this.begin();
	}

	/**
	 * Finish the event.
	 *
	 * @return the event
	 */
	@Nonnull
	public BackgroundTaskFinishedEvent finish() {
		this.end();
		return this;
	}

}
