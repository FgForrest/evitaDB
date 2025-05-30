/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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
import io.evitadb.api.observability.annotation.ExportInvocationMetric;
import io.evitadb.api.observability.annotation.ExportMetric;
import jdk.jfr.Description;
import jdk.jfr.Label;
import jdk.jfr.Name;
import lombok.Getter;

import javax.annotation.Nonnull;

/**
 * Event that is fired when an ObservableOutput buffer count is changed.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Name(AbstractStorageEvent.PACKAGE_NAME + ".ObservableOutputChange")
@Description("Event that is fired when an ObservableOutput buffer count changes.")
@Label("ObservableOutput buffers")
@ExportInvocationMetric(label = "ObservableOutput buffer count changes.")
@Getter
public class ObservableOutputChangeEvent extends AbstractStorageEvent {
	@Label("Number of opened output buffers")
	@Description("The number of open buffers used to write data to OffsetIndexes.")
	@ExportMetric(metricType = MetricType.GAUGE)
	private final int openedBuffers;

	@Label("Memory occupied by opened output buffers in Bytes")
	@Description("The amount of memory in bytes occupied by open OffsetIndex output buffers.")
	@ExportMetric(metricType = MetricType.GAUGE)
	private final long occupiedMemoryBytes;

	public ObservableOutputChangeEvent(int openedBuffers, long occupiedMemoryBytes) {
		super(null);
		this.openedBuffers = openedBuffers;
		this.occupiedMemoryBytes = occupiedMemoryBytes;
	}

	public ObservableOutputChangeEvent(@Nonnull String catalogName, int openedBuffers, long occupiedMemoryBytes) {
		super(catalogName);
		this.openedBuffers = openedBuffers;
		this.occupiedMemoryBytes = occupiedMemoryBytes;
	}
}
