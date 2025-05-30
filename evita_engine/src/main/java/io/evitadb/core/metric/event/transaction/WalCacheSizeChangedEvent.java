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

package io.evitadb.core.metric.event.transaction;

import io.evitadb.api.configuration.metric.MetricType;
import io.evitadb.api.observability.annotation.ExportMetric;
import jdk.jfr.Description;
import jdk.jfr.Label;
import jdk.jfr.Name;
import lombok.Getter;

import javax.annotation.Nonnull;

/**
 * Event that is fired when a shared WAL location cache size is changed.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Name(AbstractTransactionEvent.PACKAGE_NAME + ".WalCacheSizeChanged")
@Description("Event fired when the cache size of a shared WAL location is changed.")
@Label("WAL cache size changed")
@Getter
public class WalCacheSizeChangedEvent extends AbstractTransactionEvent {
	@Label("Total cached locations in WAL file")
	@Description("The total number of cached locations (used for fast mutation lookups) in the shared WAL file.")
	@ExportMetric(metricType = MetricType.GAUGE)
	private final int locationsCached;

	public WalCacheSizeChangedEvent(int locationsCached) {
		super(null);
		this.locationsCached = locationsCached;
	}

	public WalCacheSizeChangedEvent(@Nonnull String catalogName, int locationsCached) {
		super(catalogName);
		this.locationsCached = locationsCached;
	}
}
