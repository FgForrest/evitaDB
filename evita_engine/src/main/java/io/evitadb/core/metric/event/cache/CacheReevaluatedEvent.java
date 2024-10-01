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
import io.evitadb.api.observability.annotation.ExportDurationMetric;
import io.evitadb.api.observability.annotation.ExportInvocationMetric;
import io.evitadb.api.observability.annotation.ExportMetric;
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
@ExportInvocationMetric(label = "Cache evaluation total")
@ExportDurationMetric(label = "Cache evaluation duration in ms")
@Label("Cache reevaluated")
@Getter @Setter
public class CacheReevaluatedEvent extends AbstractCacheStatisticsRelatedEvent {

	@Label("Number of adepts to evaluate")
	@Description("The number of adepts added to the evaluation for promotion to the cache.")
	@ExportMetric(metricType = MetricType.GAUGE)
	private int adepts;

	@Label("Number of evaluated adepts and records")
	@Description("The number of adepts and already cached records that were evaluated in the cache reevaluation.")
	@ExportMetric(metricType = MetricType.GAUGE)
	private int evaluatedItems;

	@Label("Adepts that were promoted")
	@Description("The number of adepts that were newly promoted to the cache.")
	@ExportMetric(metricType = MetricType.GAUGE)
	private int promotedAdepts;

	@Label("Cached records that survived the evaluation")
	@Description("The number of records that were already cached and survived the evaluation.")
	@ExportMetric(metricType = MetricType.GAUGE)
	private int survivingRecords;

	@Label("Cached records that may be evicted in the future")
	@Description("The number of records that have already been cached but have not been hit in the last period and may be evicted in the future if they cool down enough.")
	@ExportMetric(metricType = MetricType.GAUGE)
	private int cooldownRecords;

	@Label("Cached records that were evicted")
	@Description("The number of records that were evicted from the cache.")
	@ExportMetric(metricType = MetricType.GAUGE)
	private int evictedRecords;

	@Label("Estimated size of the cache after the evaluation in Bytes")
	@Description("The estimated size of the cache after the evaluation in Bytes.")
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
