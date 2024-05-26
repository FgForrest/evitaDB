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

package io.evitadb.core.metric.event.storage;

import io.evitadb.api.configuration.metric.MetricType;
import io.evitadb.api.observability.annotation.ExportMetric;
import jdk.jfr.Description;
import jdk.jfr.Label;
import jdk.jfr.Name;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.OffsetDateTime;

/**
 * Event that is fired when a new catalog version is flushed.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Name(AbstractStorageEvent.PACKAGE_NAME + ".CatalogFlush")
@Description("Event that is fired when a new catalog version is flushed.")
@Label("Catalog flushed")
@Getter
public class CatalogFlushEvent extends AbstractStorageEvent {
	@Label("Entity collection count")
	@ExportMetric(metricType = MetricType.GAUGE)
	private final int entityCollections;

	@Label("Total occupied disk space in Bytes")
	@ExportMetric(metricType = MetricType.GAUGE)
	private final long occupiedDiskSpaceBytes;

	@Label("Timestamp of the oldest catalog version available in seconds")
	@ExportMetric(metricType = MetricType.GAUGE)
	private final long oldestCatalogVersionTimestampSeconds;

	public CatalogFlushEvent(
		@Nonnull String catalogName,
		int entityCollections,
		long occupiedDiskSpaceBytes,
		@Nullable OffsetDateTime oldestCatalogVersionTimestampSeconds) {
		super(catalogName);
		this.entityCollections = entityCollections;
		this.occupiedDiskSpaceBytes = occupiedDiskSpaceBytes;
		this.oldestCatalogVersionTimestampSeconds = oldestCatalogVersionTimestampSeconds == null ?
			0L : oldestCatalogVersionTimestampSeconds.toEpochSecond();
	}
}
