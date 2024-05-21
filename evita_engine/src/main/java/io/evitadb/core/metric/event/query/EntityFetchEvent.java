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
 * Event that is fired when an evitaDB entity is fetched.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Name(AbstractQueryEvent.PACKAGE_NAME + ".EntityFetch")
@Description("Event that is fired when an entity is fetched directly.")
@Label("Entity fetched")
@ExportInvocationMetric(value = "fetch", label = "Entity fetched")
@ExportDurationMetric(value = "fetchMillisecondsTotal", label = "Entity fetch duration in milliseconds")
@Getter
public class EntityFetchEvent extends AbstractQueryEvent {
	@Label("Entity type")
	@ExportMetricLabel
	private final String entityType;

	@Label("Records fetched total")
	@ExportMetric(metricType = MetricType.COUNTER)
	private int recordsFetchedTotal;

	@Label("Fetched size in bytes")
	@ExportMetric(metricType = MetricType.HISTOGRAM)
	private int fetchedSizeBytes;

	/**
	 * Creation timestamp.
	 */
	private final long created;

	public EntityFetchEvent(
		@Nonnull String catalogName,
		@Nullable String entityType
	) {
		super(catalogName);
		this.entityType = entityType;
		this.begin();
		this.created = System.currentTimeMillis();
	}

	/**
	 * Method should be called when the query is finished.
	 * @return this
	 */
	@Nonnull
	public EntityFetchEvent finish(
		int recordsFetchedTotal,
		int fetchedSizeBytes
	) {
		this.end();
		this.recordsFetchedTotal = recordsFetchedTotal;
		this.fetchedSizeBytes = fetchedSizeBytes;
		return this;
	}

}
