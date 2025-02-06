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
 * Event that regularly monitors traffic recorder statistics
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
	 * The name of the catalog the transaction relates to.
	 */
	@Label("Catalog")
	@Name("catalogName")
	@Description("The name of the catalog to which this event/metric is associated.")
	final String catalogName;

	/**
	 * Counter of missed records due to memory shortage or sampling.
	 */
	@Label("Missed records")
	@Name("missedRecords")
	@Description("Counter of missed records due to memory shortage or sampling.")
	@ExportMetric(metricType = MetricType.COUNTER)
	private final long missedRecords;

	/**
	 * Counter of dropped sessions due to memory shortage.
	 */
	@Label("Dropped sessions")
	@Name("droppedSessions")
	@Description("Counter of dropped sessions due to memory shortage.")
	@ExportMetric(metricType = MetricType.COUNTER)
	private final long droppedSessions;
	/**
	 * Counter of created sessions.
	 */
	@Label("Created sessions")
	@Name("createdSessions")
	@Description("Created sessions.")
	@ExportMetric(metricType = MetricType.COUNTER)
	private final long createdSessions;

	/**
	 * Counter of finished sessions.
	 */
	@Label("Finished sessions")
	@Name("finishedSessions")
	@Description("Recorded sessions.")
	@ExportMetric(metricType = MetricType.COUNTER)
	private final long finishedSessions;

}
