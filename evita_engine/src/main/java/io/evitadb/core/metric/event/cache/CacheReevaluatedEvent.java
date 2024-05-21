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
import io.evitadb.core.metric.annotation.ExportDurationMetric;
import io.evitadb.core.metric.annotation.ExportInvocationMetric;
import io.evitadb.core.metric.annotation.ExportMetric;
import jdk.jfr.Description;
import jdk.jfr.Label;
import jdk.jfr.Name;
import lombok.Getter;
import lombok.Setter;

import javax.annotation.Nonnull;

/**
 * Event that is fired when a transaction is started.
 */
@Name(AbstractCacheEvent.PACKAGE_NAME + ".CacheReevaluated")
@Description("Event that is fired when a cache is reevaluated.")
@ExportInvocationMetric(value = "cacheEvaluationTotal", label = "Cache evaluation total")
@ExportDurationMetric(value = "cacheEvaluationDurationMilliseconds", label = "Cache evaluation duration in ms")
@Label("Cache reevaluated")
@Getter @Setter
public class CacheReevaluatedEvent extends AbstractCacheStatisticsRelatedEvent {

	@Label("Number of adepts to evaluate")
	@ExportMetric(metricType = MetricType.GAUGE)
	private int adeptsTotal;

	@Label("Number of evaluated adepts and records")
	@ExportMetric(metricType = MetricType.GAUGE)
	private int evaluatedItemsTotal;

	@Label("Adepts that were promoted")
	@ExportMetric(metricType = MetricType.GAUGE)
	private int promotedAdeptsTotal;

	@Label("Cached records that survived the evaluation")
	@ExportMetric(metricType = MetricType.GAUGE)
	private int survivingRecordsTotal;

	@Label("Cached records that may be evicted in the future")
	@ExportMetric(metricType = MetricType.GAUGE)
	private int cooldownRecordsTotal;

	@Label("Cached records that were evicted")
	@ExportMetric(metricType = MetricType.GAUGE)
	private int evictedRecordsTotal;

	@Label("Estimated size of the cache after the evaluation in Bytes")
	@ExportMetric(metricType = MetricType.GAUGE)
	private long occupiedSizeBytes;

	public CacheReevaluatedEvent() {
		this.begin();
	}

	@Nonnull
	public CacheReevaluatedEvent finish() {
		this.end();
		return this;
	}
}
