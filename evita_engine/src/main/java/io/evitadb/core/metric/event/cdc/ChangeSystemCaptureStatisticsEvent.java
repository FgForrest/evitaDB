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

package io.evitadb.core.metric.event.cdc;

import io.evitadb.api.configuration.metric.MetricType;
import io.evitadb.api.observability.annotation.ExportMetric;
import jdk.jfr.Description;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.Period;
import lombok.Getter;

/**
 * Event that is fired when the system captures base statistics of change data capture (CDC).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Name(ChangeSystemCaptureStatisticsEvent.EVENT_NAME)
@Description("Event that is fired in regular intervals capturing base statistics of CDC.")
@Label("Overall CDC - system statistics")
@Period("1m")
@Getter
public class ChangeSystemCaptureStatisticsEvent extends AbstractChangeCaptureEvent {
	public static final String EVENT_NAME = AbstractChangeCaptureEvent.PACKAGE_NAME + ".ChangeSystemCaptureStatistics";

	@Label("Subscriber count")
	@Description("The number of subscribers active in the system.")
	@ExportMetric(metricType = MetricType.GAUGE)
	private final int subscribers;

	@Label("Lagging subscribers")
	@Description("The number of subscribers fetching the WAL records.")
	@ExportMetric(metricType = MetricType.GAUGE)
	private final int laggingSubscribers;

	@Label("Published events")
	@Description("The number of events published to all subscribers.")
	@ExportMetric(metricType = MetricType.COUNTER)
	private final long eventsPublishedTotal;

	public ChangeSystemCaptureStatisticsEvent(
		int subscribers,
		int laggingSubscribers,
		long eventsPublishedTotal
	) {
		super();
		this.subscribers = subscribers;
		this.laggingSubscribers = laggingSubscribers;
		this.eventsPublishedTotal = eventsPublishedTotal;
	}

}
