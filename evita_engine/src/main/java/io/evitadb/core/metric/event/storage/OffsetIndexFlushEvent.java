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
import io.evitadb.api.observability.annotation.ExportDurationMetric;
import io.evitadb.api.observability.annotation.ExportInvocationMetric;
import io.evitadb.api.observability.annotation.ExportMetric;
import jdk.jfr.Description;
import jdk.jfr.Label;
import jdk.jfr.Name;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.OffsetDateTime;

/**
 * Event that is fired when an OffsetIndex file is flushed.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Name(AbstractStorageEvent.PACKAGE_NAME + ".OffsetIndexFlush")
@Description("Event that is fired when an OffsetIndex file is flushed.")
@Label("OffsetIndex flushed to disk")
@ExportDurationMetric(label = "Duration of OffsetIndex flush to disk.")
@ExportInvocationMetric(label = "OffsetIndex flushes to disk.")
@Getter
public class OffsetIndexFlushEvent extends AbstractDataFileEvent {

	@Label("Number of active records")
	@Description("The number of active (accessible) records in the OffsetIndex.")
	@ExportMetric(metricType = MetricType.GAUGE)
	private int activeRecords;

	@Label("Estimated memory size in Bytes")
	@Description("The estimated size in Bytes of the OffsetIndex in memory.")
	@ExportMetric(metricType = MetricType.GAUGE)
	private long estimatedMemorySizeBytes;

	@Label("Biggest record Bytes")
	@Description("The size in Bytes of the biggest record in the OffsetIndex.")
	@ExportMetric(metricType = MetricType.GAUGE)
	private long maxRecordSize;

	@Label("Disk size in Bytes")
	@Description("The size in Bytes of the OffsetIndex on disk.")
	@ExportMetric(metricType = MetricType.GAUGE)
	private long diskSizeBytes;

	@Label("Active part of disk size in Bytes")
	@Description("The size in Bytes of the active part of the OffsetIndex on disk.")
	@ExportMetric(metricType = MetricType.GAUGE)
	private long activeDiskSizeBytes;

	@Label("Oldest record kept in memory timestamp in seconds")
	@Description("The timestamp in seconds of the oldest volatile record kept in memory. Volatile records are records that are not yet flushed to disk.")
	@ExportMetric(metricType = MetricType.GAUGE)
	private long oldestRecordTimestampSeconds;

	public OffsetIndexFlushEvent(
		@Nonnull String catalogName,
		@Nonnull FileType fileType,
		@Nonnull String name
	) {
		super(catalogName, fileType, name);
		this.begin();
	}

	/**
	 * Finish the event.
	 * @return this event
	 */
	@Nonnull
	public OffsetIndexFlushEvent finish(
		int activeRecordsTotal,
		long estimatedMemorySizeBytes,
		long maxRecordSize,
		long diskSizeBytes,
		long activeDiskSizeBytes,
		@Nullable OffsetDateTime oldestRecordTimestamp
	) {
		this.activeRecords = activeRecordsTotal;
		this.estimatedMemorySizeBytes = estimatedMemorySizeBytes;
		this.maxRecordSize = maxRecordSize;
		this.diskSizeBytes = diskSizeBytes;
		this.activeDiskSizeBytes = activeDiskSizeBytes;
		this.oldestRecordTimestampSeconds = oldestRecordTimestamp != null ? oldestRecordTimestamp.toEpochSecond() : 0;
		this.end();
		return this;
	}
}
