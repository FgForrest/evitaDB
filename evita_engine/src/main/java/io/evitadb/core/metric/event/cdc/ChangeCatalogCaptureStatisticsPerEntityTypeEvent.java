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
import io.evitadb.api.observability.annotation.ExportMetricLabel;
import jdk.jfr.Description;
import jdk.jfr.Label;
import jdk.jfr.Name;
import lombok.Getter;

import javax.annotation.Nonnull;

/**
 * Event that is fired when the system captures entity type statistics of change data capture (CDC).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Name(AbstractChangeCaptureEvent.PACKAGE_NAME + ".ChangeCatalogCaptureStatisticsPerEntityType")
@Description("Event that is fired in regular intervals capturing base statistics of CDC per entity type.")
@Label("CDC catalog statistics per entity type")
@Getter
public class ChangeCatalogCaptureStatisticsPerEntityTypeEvent extends AbstractChangeCatalogCaptureEvent {
	@ExportMetricLabel
	@Label("Entity type")
	@Description("The name of the related entity type (collection).")
	private final String entityType;

	@Label("Published events")
	@Description("The number of events published to all subscribers.")
	@ExportMetric(metricType = MetricType.COUNTER)
	private final long eventsPublishedTotal;

	public ChangeCatalogCaptureStatisticsPerEntityTypeEvent(
		@Nonnull String catalogName,
		@Nonnull String entityType,
		long eventsPublishedTotal
	) {
		super(catalogName);
		this.entityType = entityType;
		this.eventsPublishedTotal = eventsPublishedTotal;
	}

}
