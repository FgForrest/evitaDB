/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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
import jdk.jfr.Description;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.Period;

/**
 * Event related to request thread pool statistics.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Name(AbstractSystemEvent.PACKAGE_NAME + ".ScheduledExecutor")
@Description("Event that is fired on regular intervals to track scheduled executor statistics.")
@Label("Scheduled executor statistics")
@Period("1m")
public class ScheduledExecutorStatisticsEvent extends AbstractSystemEvent {

	@Label("Tasks completed")
	@Description("The approximate total number of tasks that have completed execution")
	@ExportMetric(metricType = MetricType.COUNTER)
	final long completed;

	@Label("Tasks active")
	@Description("The approximate number of threads that are actively executing tasks")
	@ExportMetric(metricType = MetricType.GAUGE)
	final int active;

	@Label("Tasks queued")
	@Description("The approximate number of queued tasks that are waiting to be executed")
	@ExportMetric(metricType = MetricType.GAUGE)
	final int queued;

	@Label("Queue remaining")
	@Description("The number of additional elements that this queue can ideally accept without blocking")
	@ExportMetric(metricType = MetricType.GAUGE)
	final int queueRemaining;

	@Label("Current worker count")
	@Description("The current number of threads in the pool")
	@ExportMetric(metricType = MetricType.GAUGE)
	final int poolSize;

	@Label("Minimal worker count")
	@Description("The core number of threads for the pool")
	@ExportMetric(metricType = MetricType.GAUGE)
	final int poolCore;

	@Label("Max worker count")
	@Description("The maximum allowed number of threads in the pool")
	@ExportMetric(metricType = MetricType.GAUGE)
	final int poolMax;

	public ScheduledExecutorStatisticsEvent(long completed, int active, int queued, int queueRemaining, int poolSize, int poolCore, int poolMax) {
		this.completed = completed;
		this.active = active;
		this.queued = queued;
		this.queueRemaining = queueRemaining;
		this.poolSize = poolSize;
		this.poolCore = poolCore;
		this.poolMax = poolMax;
	}
}
