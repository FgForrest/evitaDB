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
import io.evitadb.core.cache.model.CacheRecordType;
import io.evitadb.core.metric.annotation.ExportMetric;
import io.evitadb.core.metric.annotation.ExportMetricLabel;
import jdk.jfr.Description;
import jdk.jfr.Label;
import jdk.jfr.Name;
import lombok.Getter;

import javax.annotation.Nonnull;

/**
 * Event that is fired when cache contents are updated.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Name(AbstractCacheEvent.PACKAGE_NAME + ".CacheStatisticsPerTypeUpdated")
@Description("Event that is fired when cache contents are updated.")
@Label("Cache type statistics updated")
@Getter
public class CacheStatisticsPerTypeUpdatedEvent extends AbstractCacheEvent {
	@Label("Type of record.")
	@ExportMetricLabel("Record type")
	private final String type;

	@Label("Number of records of a particular type in cache.")
	@ExportMetric(metricType = MetricType.GAUGE)
	private final int records;

	@Label("Number of records of a particular type in cache.")
	@ExportMetric(metricType = MetricType.GAUGE)
	private final long recordsSizeBytes;

	@Label("Number of records of a particular type in cache.")
	@ExportMetric(metricType = MetricType.GAUGE)
	private final long averageComplexity;

	public CacheStatisticsPerTypeUpdatedEvent(
		@Nonnull CacheRecordType type,
		int records,
		long recordsSizeBytes,
		long averageComplexity
	) {
		this.type = type.name();
		this.records = records;
		this.recordsSizeBytes = recordsSizeBytes;
		this.averageComplexity = averageComplexity;
	}

}
