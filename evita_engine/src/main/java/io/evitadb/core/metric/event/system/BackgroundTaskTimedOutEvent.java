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

import io.evitadb.api.configuration.metric.MetricType;
import io.evitadb.api.observability.annotation.ExportMetric;
import io.evitadb.api.observability.annotation.ExportMetricLabel;
import jdk.jfr.Description;
import jdk.jfr.Label;
import jdk.jfr.Name;
import lombok.Getter;

import javax.annotation.Nonnull;

/**
 * Event that is fired when a background task was timed out and was canceled.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Name(AbstractSystemEvent.PACKAGE_NAME + ".BackgroundTaskTimedOut")
@Description("Event that is raised when a background task has timed out and has been canceled.")
@Label("Background task timed out")
@Getter
public class BackgroundTaskTimedOutEvent extends AbstractSystemEvent {

	/**
	 * Number of timed out tasks.
	 */
	@Label("Timed out tasks")
	@Description("Number of timed out and canceled tasks.")
	@ExportMetric(metricType = MetricType.COUNTER)
	private final int timedOutTasks;

	/**
	 * The name of the background task.
	 */
	@Label("Task name")
	@Description("Name of the background task.")
	@ExportMetricLabel
	final String taskName;

	public BackgroundTaskTimedOutEvent(@Nonnull String taskName, int timedOutTasks) {
		this.taskName = taskName;
		this.timedOutTasks = timedOutTasks;
	}

}
