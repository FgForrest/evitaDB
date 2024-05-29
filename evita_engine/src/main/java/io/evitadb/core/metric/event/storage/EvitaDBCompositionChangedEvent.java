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

package io.evitadb.core.metric.event.storage;

import io.evitadb.api.configuration.metric.MetricType;
import io.evitadb.api.observability.annotation.EventGroup;
import io.evitadb.api.observability.annotation.ExportMetric;
import io.evitadb.core.metric.event.CustomMetricsExecutionEvent;
import jdk.jfr.Description;
import jdk.jfr.Label;
import jdk.jfr.Name;
import lombok.Getter;

/**
 * Event that is fired when a new catalog version is flushed.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@EventGroup(AbstractStorageEvent.PACKAGE_NAME)
@Name(AbstractStorageEvent.PACKAGE_NAME + ".EvitaDBCompositionChanged")
@Description("Event that is fired when evitaDB composition changes.")
@Label("Evita composition changed")
@Getter
public class EvitaDBCompositionChangedEvent extends CustomMetricsExecutionEvent {
	@Label("Catalog count")
	@ExportMetric(metricType = MetricType.GAUGE)
	private final int catalogs;

	@Label("Corrupted catalog count")
	@ExportMetric(metricType = MetricType.GAUGE)
	private final int corruptedCatalogs;

	public EvitaDBCompositionChangedEvent(
		int catalogs,
		int corruptedCatalogs
	) {
		this.catalogs = catalogs;
		this.corruptedCatalogs = corruptedCatalogs;
	}

}
