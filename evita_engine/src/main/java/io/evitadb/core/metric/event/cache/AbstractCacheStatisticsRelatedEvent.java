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
	@Description("The number of cache hits - i.e., the number of times the cache was able to return a value without having to execute calculation.")
	@ExportMetric(metricType = MetricType.COUNTER)
	long cacheHits;

	@Label("Cache misses count")
	@Description("The number of cache misses - i.e., the number of times the cache was not able to return a value and had to execute calculation.")
	@ExportMetric(metricType = MetricType.COUNTER)
	long cacheMisses;

	@Label("Cache enrichments count")
	@Description("The number of cache enrichments - i.e., the number of times the cache needed to enrich the objects contents before returning a cached value.")
	@ExportMetric(metricType = MetricType.COUNTER)
	long cacheEnrichments;

	@Label("Cache record initializations count")
	@Description("The number of cache record initializations - i.e., how many adepts introduced to the cache were actually hit for the first time.")
	@ExportMetric(metricType = MetricType.COUNTER)
	long cacheInitializations;

}
