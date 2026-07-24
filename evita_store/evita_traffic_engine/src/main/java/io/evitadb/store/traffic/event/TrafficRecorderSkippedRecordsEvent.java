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

package io.evitadb.store.traffic.event;


import io.evitadb.api.configuration.metric.MetricType;
import io.evitadb.api.observability.annotation.EventGroup;
import io.evitadb.api.observability.annotation.ExportMetric;
import io.evitadb.api.observability.annotation.ExportMetricLabel;
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
 * Event that reports how many traffic records and whole sessions the recorder did NOT persist, broken
 * down by the {@code reason} dimension ({@link TrafficRecorderMissReason}). It is emitted once per
 * reason per memory-buffer flush cycle so a single `reason` label distinguishes benign sampling from
 * genuine memory/disk pressure or serialization errors.
 *
 * The two COUNTER fields carry the DELTA since the previous emission for that reason (not the
 * cumulative total), because the metric pipeline increments the Prometheus counter by the emitted
 * value on every commit; emitting the cumulative value would over-count.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Name(PACKAGE_NAME + ".SkippedRecords")
@Description("Event that reports traffic records and sessions skipped or dropped, broken down by reason.")
@Label("Traffic recorder skipped records")
@EventGroup(
	value = PACKAGE_NAME,
	name = "evitaDB - Traffic Recorder",
	description = "evitaDB events related to traffic recording."
)
@Category({"evitaDB", "Query"})
@RequiredArgsConstructor
@Getter
public class TrafficRecorderSkippedRecordsEvent extends CustomMetricsExecutionEvent implements CatalogRelatedEvent {

	/**
	 * The name of the catalog the traffic recorder relates to.
	 */
	@Label("Catalog")
	@Name("catalogName")
	@Description("The name of the catalog to which this event/metric is associated.")
	final String catalogName;

	/**
	 * The reason the records/sessions were not persisted - exported as the `reason` metric dimension.
	 */
	@Label("Reason")
	@Name("reason")
	@Description("Why the records/sessions were not persisted (e.g. SAMPLING, MEMORY_SHORTAGE, DISK_SHORTAGE, IO_ERROR, SERIALIZATION_ERROR).")
	@ExportMetricLabel
	private final String reason;

	/**
	 * Number of traffic records not persisted for this reason since the previous emission.
	 */
	@Label("Missed records")
	@Name("missedRecords")
	@Description("Number of traffic records not persisted for this reason since the previous emission.")
	@ExportMetric(metricType = MetricType.COUNTER)
	private final long missedRecords;

	/**
	 * Number of whole sessions dropped for this reason since the previous emission.
	 */
	@Label("Dropped sessions")
	@Name("droppedSessions")
	@Description("Number of whole sessions dropped for this reason since the previous emission.")
	@ExportMetric(metricType = MetricType.COUNTER)
	private final long droppedSessions;

}
