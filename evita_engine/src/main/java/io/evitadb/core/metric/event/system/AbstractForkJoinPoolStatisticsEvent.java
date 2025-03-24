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
import lombok.Getter;

import java.util.concurrent.ForkJoinPool;

/**
 * Abstract super class for all events that are related to {@link ForkJoinPool} statistics.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Getter
public abstract class AbstractForkJoinPoolStatisticsEvent extends AbstractSystemEvent {

	@Label("Tasks stolen")
	@Description(
		"Estimate of the total number of tasks stolen from one thread's work queue by another. The reported value " +
			"underestimates the actual total number of steals when the pool is not quiescent"
	)
	@ExportMetric(metricType = MetricType.COUNTER)
	final long steals;

	@Label("Tasks queued")
	@Description("An estimate of the total number of tasks currently held in queues by worker threads")
	@ExportMetric(metricType = MetricType.GAUGE)
	final long queued;

	@Label("Workers active")
	@Description("An estimate of the number of threads that are currently stealing or executing tasks")
	@ExportMetric(metricType = MetricType.GAUGE)
	final int active;

	@Label("Workers running")
	@Description("An estimate of the number of worker threads that are not blocked waiting to join tasks or for other managed" +
		" synchronization threads")
	@ExportMetric(metricType = MetricType.GAUGE)
	final int running;

	protected AbstractForkJoinPoolStatisticsEvent(long steals, long queued, int active, int running) {
		this.steals = steals;
		this.queued = queued;
		this.active = active;
		this.running = running;
	}
}
