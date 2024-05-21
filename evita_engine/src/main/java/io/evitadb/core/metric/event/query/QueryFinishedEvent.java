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

package io.evitadb.core.metric.event.query;

import io.evitadb.api.configuration.metric.MetricType;
import io.evitadb.core.metric.annotation.ExportDurationMetric;
import io.evitadb.core.metric.annotation.ExportInvocationMetric;
import io.evitadb.core.metric.annotation.ExportMetric;
import io.evitadb.core.metric.annotation.ExportMetricLabel;
import jdk.jfr.Description;
import jdk.jfr.Label;
import jdk.jfr.Name;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Event that is fired when an evitaDB query is finished.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Name(AbstractQueryEvent.PACKAGE_NAME + ".QueryFinished")
@Description("Event that is fired when a query is finished.")
@Label("Catalog finished")
@ExportInvocationMetric(value = "queryFinished", label = "Query finished")
@ExportDurationMetric(value = "queryDurationMillisecondsTotal", label = "Query duration in milliseconds")
@Getter
public class QueryFinishedEvent extends AbstractQueryEvent {
	@Label("Entity type")
	@ExportMetricLabel
	private final String entityType;

	@Label("Query planning duration in milliseconds")
	@ExportMetric(metricType = MetricType.HISTOGRAM)
	private long planDurationMilliseconds;

	@Label("Query execution duration in milliseconds")
	@ExportMetric(metricType = MetricType.HISTOGRAM)
	private long executionDurationMilliseconds;

	@Label("Prefetched vs. non-prefetched query")
	@ExportMetric(metricType = MetricType.HISTOGRAM)
	private int prefetchInfo;

	@Label("Records scanned total")
	@ExportMetric(metricType = MetricType.HISTOGRAM)
	private int recordsScannedTotal;

	@Label("Records returned total")
	@ExportMetric(metricType = MetricType.HISTOGRAM)
	private int recordsReturnedTotal;

	@Label("Records found total")
	@ExportMetric(metricType = MetricType.HISTOGRAM)
	private int recordsFoundTotal;

	@Label("Records fetched total")
	@ExportMetric(metricType = MetricType.HISTOGRAM)
	private int recordsFetchedTotal;

	@Label("Fetched size in bytes")
	@ExportMetric(metricType = MetricType.HISTOGRAM)
	private int fetchedSizeBytes;

	@Label("Estimated complexity info")
	@ExportMetric(metricType = MetricType.HISTOGRAM)
	private long estimatedComplexityInfo;

	@Label("Complexity info")
	@ExportMetric(metricType = MetricType.HISTOGRAM)
	private long complexityInfo;

	/**
	 * Creation timestamp.
	 */
	private final long created;

	public QueryFinishedEvent(
		@Nonnull String catalogName,
		@Nullable String entityType
	) {
		super(catalogName);
		this.entityType = entityType;
		this.begin();
		this.created = System.currentTimeMillis();
	}

	/**
	 * Method should be called when the query is started to be executed.
	 * @return this
	 */
	@Nonnull
	public QueryFinishedEvent startExecuting() {
		this.planDurationMilliseconds = System.currentTimeMillis() - this.created;
		return this;
	}

	/**
	 * Method should be called when the query is finished.
	 * @return this
	 */
	@Nonnull
	public QueryFinishedEvent finish(
		int prefetchInfo,
		int recordsScannedTotal,
		int recordsReturnedTotal,
		int recordsFoundTotal,
		int recordsFetchedTotal,
		int fetchedSizeBytes,
		long estimatedComplexityInfo,
		long complexityInfo
	) {
		this.end();
		this.executionDurationMilliseconds = System.currentTimeMillis() - this.created;
		this.prefetchInfo = prefetchInfo;
		this.recordsScannedTotal = recordsScannedTotal;
		this.recordsReturnedTotal = recordsReturnedTotal;
		this.recordsFoundTotal = recordsFoundTotal;
		this.recordsFetchedTotal = recordsFetchedTotal;
		this.fetchedSizeBytes = fetchedSizeBytes;
		this.estimatedComplexityInfo = estimatedComplexityInfo;
		this.complexityInfo = complexityInfo;
		return this;
	}

}
