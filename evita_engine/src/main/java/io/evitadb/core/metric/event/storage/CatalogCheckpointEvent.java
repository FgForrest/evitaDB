/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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
 * Event fired when a catalog checkpoint completes - that is, when the data files written since the previous
 * checkpoint have been forced to the physical device and the bootstrap record pointing at them has been written.
 *
 * Emitted at the point the device acknowledges, not at the end of the trunk round that queued the work, because the
 * whole purpose of the checkpoint interval is that those two moments are no longer the same.
 *
 * Read the two gauges together - they answer different questions:
 *
 * - **Cadence** says whether checkpoints are happening as often as configured. Sustained values far above
 *   the configured interval mean checkpointing cannot keep up with the write rate.
 * - **Fence depth** says how far behind durability is running, and therefore bounds both how much write-ahead log
 *   must be retained and how much of it a restart has to replay. On a quiet catalog it should show roughly one
 *   interval and then stay flat.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Name(AbstractStorageEvent.PACKAGE_NAME + ".CatalogCheckpoint")
@Description("Event that is fired when the catalog data files are made durable and a bootstrap record is written.")
@Label("Catalog checkpointed")
@ExportInvocationMetric(label = "Catalog checkpoints.")
@Getter
public class CatalogCheckpointEvent extends AbstractStorageEvent {

	@Label("Device force duration in milliseconds")
	@Description("The time spent forcing the data files to the physical device. This is the cost the checkpoint interval exists to amortise - it is paid once per checkpoint instead of once per trunk round.")
	@ExportMetric(metricType = MetricType.GAUGE)
	private long forceDurationMilliseconds;

	@Label("Checkpoint cadence in milliseconds")
	@Description("The time elapsed since the previous completed checkpoint. Compare against the configured checkpoint interval - sustained higher values mean checkpointing is not keeping up with the write rate.")
	@ExportMetric(metricType = MetricType.GAUGE)
	private long cadenceMilliseconds;

	@Label("Fence depth in milliseconds")
	@Description("How long the oldest change covered by this checkpoint waited to become durable. Bounds both the write-ahead log retention and the amount of replay a restart has to perform. Zero when the round checkpointed without deferring.")
	@ExportMetric(metricType = MetricType.GAUGE)
	private long fenceDepthMilliseconds;

	@Label("Number of files forced")
	@Description("The number of data files that were forced to the physical device by this checkpoint.")
	@ExportMetric(metricType = MetricType.GAUGE)
	private int filesForced;

	public CatalogCheckpointEvent(@Nonnull String catalogName) {
		super(catalogName);
		this.begin();
	}

	/**
	 * Finish the event.
	 *
	 * @param cadenceMilliseconds       time elapsed since the previous completed checkpoint
	 * @param fenceDepthMilliseconds    how long the oldest change covered by this checkpoint waited for the device
	 * @param filesForced               number of data files forced to the device
	 * @param forceDurationMilliseconds time spent forcing those files
	 * @return this event
	 */
	@Nonnull
	public CatalogCheckpointEvent finish(
		long cadenceMilliseconds,
		long fenceDepthMilliseconds,
		int filesForced,
		long forceDurationMilliseconds
	) {
		this.cadenceMilliseconds = cadenceMilliseconds;
		this.fenceDepthMilliseconds = fenceDepthMilliseconds;
		this.filesForced = filesForced;
		this.forceDurationMilliseconds = forceDurationMilliseconds;
		this.end();
		return this;
	}

}
