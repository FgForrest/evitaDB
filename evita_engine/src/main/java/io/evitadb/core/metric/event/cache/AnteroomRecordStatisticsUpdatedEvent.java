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

package io.evitadb.core.metric.event.cache;

import io.evitadb.api.configuration.metric.MetricType;
import io.evitadb.api.observability.annotation.ExportMetric;
import jdk.jfr.Description;
import jdk.jfr.Label;
import jdk.jfr.Name;
import lombok.Getter;

/**
 * Event that is fired in regular intervals to update statistics about records waiting in anteroom.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Name(AbstractCacheEvent.PACKAGE_NAME + ".AnteroomRecordStatisticsUpdated")
@Description("Event that is fired periodically to update statistics about records waiting in the anteroom.")
@Label("Anteroom statistics updated")
@Getter
public class AnteroomRecordStatisticsUpdatedEvent extends AbstractCacheEvent {

	@Label("Number of records waiting in anteroom")
	@Description("The number of cacheable but not yet cached records that are collecting usage statistics to evaluate for becoming cached.")
	@ExportMetric(metricType = MetricType.GAUGE)
	private final int records;

	public AnteroomRecordStatisticsUpdatedEvent(int records) {
		this.records = records;
	}
}
