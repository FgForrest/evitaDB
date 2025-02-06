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
import io.evitadb.api.observability.annotation.ExportDurationMetric;
import io.evitadb.api.observability.annotation.ExportInvocationMetric;
import io.evitadb.api.observability.annotation.ExportMetric;
import io.evitadb.api.observability.annotation.ExportMetricLabel;
import io.evitadb.api.observability.annotation.HistogramSettings;
import jdk.jfr.Description;
import jdk.jfr.Label;
import jdk.jfr.Name;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Event that is fired when an evitaDB query is finished.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Name(AbstractQueryEvent.PACKAGE_NAME + ".Finished")
@Description("Event that is fired when a query is finished.")
@Label("Query finished")
@HistogramSettings(factor = 1.65)
@ExportInvocationMetric(label = "Query finished")
@ExportDurationMetric(label = "Query duration in milliseconds")
@Getter
public class FinishedEvent extends AbstractQueryEvent {
	@Label("Entity type")
	@Description("The name of the related entity type (collection).")
	@ExportMetricLabel
	private final String entityType;

	@Label("Labels")
	@Description("Zero or more client labels associated with the query. Labels are delimited by comma, label consists of name and value separated by equals sign.")
	@ExportMetricLabel
	private final String labels;

	@Label("Query planning duration in milliseconds")
	@Description("The time it took to build all the query execution plan variants.")
	@ExportMetric(metricType = MetricType.HISTOGRAM)
	@HistogramSettings(factor = 1.9)
	private long planDurationMilliseconds;

	@Label("Query execution duration in milliseconds")
	@Description("The time it took to execute the selected execution plan for the query.")
	@ExportMetric(metricType = MetricType.HISTOGRAM)
	@HistogramSettings(factor = 1.9)
	private long executionDurationMilliseconds;

	@Label("Prefetched vs. non-prefetched query")
	@Description("Whether or not the query used a prefetch plan. Prefetch plan optimistically fetches queried entities in advance and executes directly on them (without accessing the indexes).")
	@ExportMetricLabel
	private String prefetched;

	@Label("Records scanned total")
	@Description("The total number of records scanned (included in the calculation).")
	@HistogramSettings(unit = "records", factor = 4)
	@ExportMetric(metricType = MetricType.HISTOGRAM)
	private int scanned;

	@Label("Records returned total")
	@Description("The total number of records returned (included in the result).")
	@HistogramSettings(unit = "records", factor = 1.9)
	@ExportMetric(metricType = MetricType.HISTOGRAM)
	private int returned;

	@Label("Records found total")
	@Description("The total number of records found (matching the query).")
	@HistogramSettings(unit = "records", factor = 2.5)
	@ExportMetric(metricType = MetricType.HISTOGRAM)
	private int found;

	@Label("Records fetched total")
	@Description("The total number of records fetched from the data storage (excluding records found in the cache).")
	@HistogramSettings(unit = "records", factor = 1.9)
	@ExportMetric(metricType = MetricType.HISTOGRAM)
	private int fetched;

	@Label("Fetched size in bytes")
	@Description("The total size of the fetched data in Bytes.")
	@HistogramSettings(unit = "bytes", factor = 3)
	@ExportMetric(metricType = MetricType.HISTOGRAM)
	private int fetchedSizeBytes;

	@Label("Estimated complexity info")
	@Description("The estimated complexity of the query.")
	@HistogramSettings(unit = "complexity", factor = 22)
	@ExportMetric(metricName = "estimated", metricType = MetricType.HISTOGRAM)
	private long estimatedComplexity;

	@Label("Filter complexity")
	@Description("The real complexity of the query.")
	@HistogramSettings(unit = "complexity", factor = 22)
	@ExportMetric(metricName = "real", metricType = MetricType.HISTOGRAM)
	private long realComplexity;

	/**
	 * Creation timestamp.
	 */
	private final long created;
	private long planned;

	public FinishedEvent(
		@Nonnull String catalogName,
		@Nullable String entityType,
		@Nullable io.evitadb.api.query.head.Label[] labels
	) {
		super(catalogName);
		this.entityType = entityType;
		this.begin();
		this.created = System.currentTimeMillis();
		this.labels = labels == null ?
			"" :
			Arrays.stream(labels)
				.map(label -> label.getLabelName() + "=" + label.getLabelValue())
				.collect(Collectors.joining(","));
	}

	/**
	 * Method should be called when the query is started to be executed.
	 * @return this
	 */
	@Nonnull
	public FinishedEvent startExecuting() {
		final long now = System.currentTimeMillis();
		this.planDurationMilliseconds = now - this.created;
		this.planned = now;
		return this;
	}

	/**
	 * Method should be called when the query is finished.
	 * @return this
	 */
	@Nonnull
	public FinishedEvent finish(
		boolean prefetchInfo,
		int recordsScannedTotal,
		int recordsReturnedTotal,
		int recordsFoundTotal,
		int recordsFetchedTotal,
		int fetchedSizeBytes,
		long estimatedComplexityInfo,
		long complexityInfo
	) {
		this.end();
		this.executionDurationMilliseconds = System.currentTimeMillis() - this.planned;
		this.prefetched = prefetchInfo ? "yes" : "no";
		this.scanned = recordsScannedTotal;
		this.returned = recordsReturnedTotal;
		this.found = recordsFoundTotal;
		this.fetched = recordsFetchedTotal;
		this.fetchedSizeBytes = fetchedSizeBytes;
		this.estimatedComplexity = estimatedComplexityInfo;
		this.realComplexity = complexityInfo;
		return this;
	}

}
