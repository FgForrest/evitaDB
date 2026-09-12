
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
import java.util.function.IntSupplier;

/**
 * Event that is fired when an evitaDB entity is enriched.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Name(AbstractQueryEvent.PACKAGE_NAME + ".EntityEnrich")
@Description("Event fired when an entity is directly enriched.")
@Label("Entity enriched")
@ExportInvocationMetric(label = "Entity enriched")
@ExportDurationMetric(label = "Entity enrichment duration in milliseconds")
@Getter
public class EntityEnrichEvent extends AbstractQueryEvent {
	@Label("Entity type")
	@Description("The name of the related entity type (collection).")
	@ExportMetricLabel
	private final String entityType;

	/**
	 * Populated by {@link #finish} **only when the event is going to be written** - resolving it walks the entity's
	 * reference graph and is not worth doing for an event nobody reads. A reader that inspects the object returned
	 * by `finish` rather than the recorded event therefore sees `0` here whenever `shouldCommit()` was false, which
	 * means "not measured" and not "nothing was enriched".
	 */
	@Label("Records enriched total")
	@Description("The total number of records that were enriched.")
	@ExportMetric(metricType = MetricType.COUNTER)
	private int records;

	/**
	 * Measured under the same condition as {@link #records} - see there.
	 */
	@Label("Enrichment size in bytes")
	@Description("The size in Bytes of the additional fetched and enriched data.")
	@HistogramSettings(unit = "bytes", factor = 3)
	@ExportMetric(metricType = MetricType.HISTOGRAM)
	private int sizeBytes;

	/**
	 * Creation timestamp.
	 */
	private final long created;

	public EntityEnrichEvent(
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
	public EntityEnrichEvent finish(
		@Nonnull IntSupplier recordsFetchedTotal,
		@Nonnull IntSupplier fetchedSizeBytes
	) {
		this.end();
		// resolving these two aggregates walks the reference graph of the entity, so they are asked for only when
		// this event is going to be written - `shouldCommit` is false whenever neither a JFR recording nor the
		// metric exporter is subscribed to it
		if (shouldCommit()) {
			this.records = recordsFetchedTotal.getAsInt();
			this.sizeBytes = fetchedSizeBytes.getAsInt();
		}
		return this;
	}

}
