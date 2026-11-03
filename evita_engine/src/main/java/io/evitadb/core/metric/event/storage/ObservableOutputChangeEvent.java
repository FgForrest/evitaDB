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

/**
 * Event reporting changes in the JVM-wide set of borrowed and idle frame buffers after allocation or dropping.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Name(AbstractStorageEvent.PACKAGE_NAME + ".ObservableOutputChange")
@Description("Event reporting frame buffer allocations and drops across all engines and catalogs.")
@Label("Shared frame buffers")
@ExportInvocationMetric(label = "Shared frame buffer allocations and drops.")
@Getter
public class ObservableOutputChangeEvent extends AbstractStorageEvent {
	/**
	 * Number of arrays owned by the pool, including borrowed and idle buffers.
	 */
	@Label("Number of borrowed and idle frame buffers")
	@Description("The total number of borrowed and idle arrays in the JVM-wide frame buffer pool.")
	@ExportMetric(metricType = MetricType.GAUGE)
	private final int openedBuffers;

	/**
	 * Total size in bytes of all borrowed and idle arrays owned by the pool.
	 */
	@Label("Memory occupied by borrowed and idle frame buffers in bytes")
	@Description("The total size in bytes of borrowed and idle arrays in the JVM-wide frame buffer pool.")
	@ExportMetric(metricType = MetricType.GAUGE)
	private final long occupiedMemoryBytes;

	/**
	 * Captures the total number and size of borrowed and idle buffers across the JVM after allocating or dropping one.
	 *
	 * @param openedBuffers total number of borrowed and idle arrays
	 * @param occupiedMemoryBytes total size in bytes of borrowed and idle arrays
	 */
	public ObservableOutputChangeEvent(int openedBuffers, long occupiedMemoryBytes) {
		super(null);
		this.openedBuffers = openedBuffers;
		this.occupiedMemoryBytes = occupiedMemoryBytes;
	}

}
