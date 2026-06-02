/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025-2026
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

import javax.annotation.Nonnull;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Abstract super class for all events that track {@link ThreadPoolExecutor} statistics. The request, transaction
 * and (scheduled) service pools all back onto a {@link ThreadPoolExecutor}, so they share this common field set
 * and emission contract.
 *
 * The `completed` field carries the number of tasks completed *since the previous observation* (a per-tick
 * delta), not the cumulative total: the metric pipeline turns a {@link MetricType#COUNTER} field into a
 * Prometheus `Counter.inc(value)` on every recorded event, so feeding it the running total each tick would
 * make the counter grow quadratically with uptime. The emitting executor is responsible for computing the
 * delta against the previously observed completed-task count. All other fields are point-in-time gauges.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Getter
public abstract class AbstractThreadPoolStatisticsEvent extends AbstractSystemEvent {

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

	@Label("Largest worker count")
	@Description("The largest number of threads that have ever simultaneously been in the pool")
	@ExportMetric(metricType = MetricType.GAUGE)
	final int largestPoolSize;

	protected AbstractThreadPoolStatisticsEvent(
		long completed,
		int active,
		int queued,
		int queueRemaining,
		int poolSize,
		int poolCore,
		int poolMax,
		int largestPoolSize
	) {
		this.completed = completed;
		this.active = active;
		this.queued = queued;
		this.queueRemaining = queueRemaining;
		this.poolSize = poolSize;
		this.poolCore = poolCore;
		this.poolMax = poolMax;
		this.largestPoolSize = largestPoolSize;
	}

	/**
	 * Builds the concrete event subtype from a snapshot of {@link ThreadPoolExecutor} statistics. Each concrete
	 * event exposes a matching constructor (e.g. {@code RequestThreadPoolStatisticsEvent::new}), so the emitting
	 * executor owns the metric extraction and the `completed` delta computation while the call site only chooses
	 * which event type to emit.
	 */
	@FunctionalInterface
	public interface Factory {

		/**
		 * Creates the concrete statistics event from the supplied pool snapshot.
		 *
		 * @param completed       number of tasks completed since the previous observation (a delta, not the total)
		 * @param active          approximate number of worker threads currently executing tasks
		 * @param queued          number of tasks currently waiting in the backlog
		 * @param queueRemaining  number of additional tasks the backlog can accept before rejecting/blocking
		 * @param poolSize        current number of worker threads
		 * @param poolCore        configured core worker-thread count
		 * @param poolMax         configured maximum worker-thread count
		 * @param largestPoolSize largest worker-thread count ever observed simultaneously
		 * @return the concrete event, ready to be committed
		 */
		@Nonnull
		AbstractThreadPoolStatisticsEvent create(
			long completed,
			int active,
			int queued,
			int queueRemaining,
			int poolSize,
			int poolCore,
			int poolMax,
			int largestPoolSize
		);

	}

}
