/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.core.metric.event.system;


import io.evitadb.api.configuration.metric.MetricType;
import io.evitadb.api.observability.annotation.ExportMetric;
import jdk.jfr.Description;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.Period;
import lombok.Getter;

import javax.annotation.Nonnull;

/**
 * Event that regularly monitors in-memory ring buffer statistics.
 *
 * It exposes counters and gauges to observe utilization and traffic flowing through the buffer.
 */
@Name(AbstractSystemEvent.PACKAGE_NAME + ".RingBufferStatistics")
@Description("Event that regularly monitors in-memory ring buffer statistics.")
@Label("Ring buffer statistics")
@Period("1m")
@Getter
public class RingBufferStatisticsEvent extends AbstractSystemCatalogEvent {

    /**
     * Type of the ring buffer instance producing these metrics (e.g., ChangeCapture, Conflict).
     */
    @Label("Ring buffer type")
    @Name("ringBufferType")
    @Description("Type of the ring buffer instance producing these metrics (e.g., ChangeCapture, Conflict).")
    @Nonnull
    private final String ringBufferType;

    /**
     * Total number of items accepted into the buffer since creation.
     */
    @Label("Accepted items")
    @Description("Total number of items accepted into the buffer since creation.")
    @ExportMetric(metricType = MetricType.COUNTER)
    private final long itemsAccepted;

    /**
     * Total number of items copied out of the buffer via copy operations since creation.
     */
    @Label("Copied items")
    @Description("Total number of items copied out of the buffer via copy operations since creation.")
    @ExportMetric(metricType = MetricType.COUNTER)
    private final long itemsCopied;

    /**
     * Total number of items scanned via forEach operations since creation.
     */
    @Label("Scanned items")
    @Description("Total number of items scanned via forEach operations since creation.")
    @ExportMetric(metricType = MetricType.COUNTER)
    private final long itemsScanned;

    /**
     * Current number of items present in the buffer.
     */
    @Label("Items present")
    @Description("Current number of items present in the buffer.")
    @ExportMetric(metricType = MetricType.GAUGE)
    private final int itemsPresent;

    /**
     * Current number of items available to be scanned/copied respecting the effective end watermark.
     */
    @Label("Items available")
    @Description("Current number of items available to be scanned/copied respecting the effective end watermark.")
    @ExportMetric(metricType = MetricType.GAUGE)
    private final int itemsAvailable;

	public RingBufferStatisticsEvent(
		@Nonnull String catalogName,
		@Nonnull String ringBufferType,
		long itemsAccepted,
		long itemsCopied,
		long itemsScanned,
		int itemsPresent,
		int itemsAvailable
	) {
		super(catalogName);
		this.ringBufferType = ringBufferType;
		this.itemsAccepted = itemsAccepted;
		this.itemsCopied = itemsCopied;
		this.itemsScanned = itemsScanned;
		this.itemsPresent = itemsPresent;
		this.itemsAvailable = itemsAvailable;
	}
}
