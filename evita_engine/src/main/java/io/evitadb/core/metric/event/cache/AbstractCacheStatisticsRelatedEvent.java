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
import io.evitadb.core.metric.annotation.ExportMetric;
import jdk.jfr.Label;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * This event is base class for all cache events that update cache statistics.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@AllArgsConstructor
@NoArgsConstructor
@Getter @Setter
abstract class AbstractCacheStatisticsRelatedEvent extends AbstractCacheEvent {

	@Label("Cache hits count")
	@ExportMetric(metricType = MetricType.COUNTER)
	long cacheHitsTotal;

	@Label("Cache misses count")
	@ExportMetric(metricType = MetricType.COUNTER)
	long cacheMissesTotal;

	@Label("Cache enrichments count")
	@ExportMetric(metricType = MetricType.COUNTER)
	long cacheEnrichmentsTotal;

	@Label("Cache record initializations count")
	@ExportMetric(metricType = MetricType.COUNTER)
	long cacheInitializationsTotal;

}
