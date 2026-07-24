/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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

package io.evitadb.store.traffic.event;


import io.evitadb.api.configuration.metric.MetricType;
import io.evitadb.api.observability.annotation.EventGroup;
import io.evitadb.api.observability.annotation.ExportMetric;
import io.evitadb.core.metric.event.CatalogRelatedEvent;
import io.evitadb.core.metric.event.CustomMetricsExecutionEvent;
import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Label;
import jdk.jfr.Name;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import static io.evitadb.store.traffic.event.TrafficRecorderStatisticsEvent.PACKAGE_NAME;

/**
 * Event that regularly monitors traffic recorder throughput and memory/churn state. It is emitted once
 * per memory-buffer flush cycle and carries a snapshot of the recorder's health.
 *
 * The COUNTER fields carry the DELTA since the previous emission (not the cumulative total), because
 * the metric pipeline increments the Prometheus counter by the emitted value on every commit; emitting
 * the cumulative value would over-count. The GAUGE fields carry the instantaneous value at flush time.
 *
 * Per-reason skip/drop breakdown lives in the sibling {@link TrafficRecorderSkippedRecordsEvent}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Name(PACKAGE_NAME + ".Statistics")
@Description("Event that regularly monitors traffic recorder statistics.")
@Label("Traffic recorder statistics")
@EventGroup(
	value = PACKAGE_NAME,
	name = "evitaDB - Traffic Recorder",
	description = "evitaDB events related to traffic recording."
)
@Category({"evitaDB", "Query"})
@RequiredArgsConstructor
@Getter
public class TrafficRecorderStatisticsEvent extends CustomMetricsExecutionEvent implements CatalogRelatedEvent {
	public static final String PACKAGE_NAME = "io.evitadb.store.traffic";

	/**
	 * The name of the catalog the traffic recorder relates to.
	 */
	@Label("Catalog")
	@Name("catalogName")
	@Description("The name of the catalog to which this event/metric is associated.")
	final String catalogName;

	/**
	 * Number of sessions successfully admitted for recording since the previous emission.
	 */
	@Label("Created sessions")
	@Name("createdSessions")
	@Description("Number of sessions admitted for recording since the previous emission.")
	@ExportMetric(metricType = MetricType.COUNTER)
	private final long createdSessions;

	/**
	 * Number of sessions closed cleanly and queued to disk since the previous emission.
	 */
	@Label("Finished sessions")
	@Name("finishedSessions")
	@Description("Number of sessions closed cleanly and queued to disk since the previous emission.")
	@ExportMetric(metricType = MetricType.COUNTER)
	private final long finishedSessions;

	/**
	 * Number of traffic records successfully captured since the previous emission (ingest throughput).
	 */
	@Label("Recorded records")
	@Name("recordedRecords")
	@Description("Number of traffic records successfully captured since the previous emission.")
	@ExportMetric(metricType = MetricType.COUNTER)
	private final long recordedRecords;

	/**
	 * Number of off-heap memory blocks allocated since the previous emission (off-heap churn rate).
	 */
	@Label("Memory blocks allocated")
	@Name("blocksAllocated")
	@Description("Number of off-heap memory blocks allocated since the previous emission.")
	@ExportMetric(metricType = MetricType.COUNTER)
	private final long blocksAllocated;

	/**
	 * Number of bytes appended to the disk ring buffer since the previous emission (disk write throughput).
	 */
	@Label("Disk bytes appended")
	@Name("diskBytesAppended")
	@Description("Number of bytes appended to the disk ring buffer since the previous emission.")
	@ExportMetric(metricType = MetricType.COUNTER)
	private final long diskBytesAppended;

	/**
	 * Number of off-heap memory blocks currently in use - the primary memory-pressure signal (compare
	 * with {@link #totalMemoryBlocks}; approaching it foreshadows memory-shortage drops).
	 */
	@Label("Used memory blocks")
	@Name("usedMemoryBlocks")
	@Description("Number of off-heap memory blocks currently in use (primary memory-pressure signal).")
	@ExportMetric(metricType = MetricType.GAUGE)
	private final long usedMemoryBlocks;

	/**
	 * Total number of off-heap memory blocks available to the recorder (denominator for the utilization ratio).
	 */
	@Label("Total memory blocks")
	@Name("totalMemoryBlocks")
	@Description("Total number of off-heap memory blocks available to the recorder.")
	@ExportMetric(metricType = MetricType.GAUGE)
	private final long totalMemoryBlocks;

	/**
	 * Number of live in-flight sessions currently holding off-heap blocks.
	 */
	@Label("Active sessions")
	@Name("activeSessions")
	@Description("Number of live in-flight sessions currently holding off-heap blocks.")
	@ExportMetric(metricType = MetricType.GAUGE)
	private final long activeSessions;

	/**
	 * Number of closed sessions waiting to be drained to disk (backlog - indicates whether the flush
	 * task keeps up with the close rate).
	 */
	@Label("Finalized sessions backlog")
	@Name("finalizedSessionsBacklog")
	@Description("Number of closed sessions waiting to be drained to disk (flush backlog).")
	@ExportMetric(metricType = MetricType.GAUGE)
	private final long finalizedSessionsBacklog;

	/**
	 * Number of bytes currently occupied by resident sessions in the disk ring buffer.
	 */
	@Label("Disk buffer used bytes")
	@Name("diskBufferUsedBytes")
	@Description("Number of bytes currently occupied by resident sessions in the disk ring buffer.")
	@ExportMetric(metricType = MetricType.GAUGE)
	private final long diskBufferUsedBytes;

	/**
	 * Number of sessions currently resident in the disk ring buffer.
	 */
	@Label("Disk resident sessions")
	@Name("diskResidentSessions")
	@Description("Number of sessions currently resident in the disk ring buffer.")
	@ExportMetric(metricType = MetricType.GAUGE)
	private final long diskResidentSessions;

}
