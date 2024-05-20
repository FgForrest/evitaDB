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
import io.evitadb.core.metric.annotation.ExportMetric;
import jdk.jfr.Description;
import jdk.jfr.Label;
import jdk.jfr.Name;
import lombok.Getter;

import javax.annotation.Nonnull;

/**
 * Event that is fired only once when catalog is created or loaded and signalizes the maximal threshold for
 * simultaneously opened read-only handles.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Name(AbstractStorageEvent.PACKAGE_NAME + ".ReadOnlyHandleLimitSetEvent")
@Description("Event that is fired when a read-only handle limit is set.")
@Label("Read-only handle limit set")
@Getter
public class ReadOnlyHandleLimitSetEvent extends AbstractDataFileEvent {
	@Label("Maximal count of opened read-only handles")
	@ExportMetric(metricType = MetricType.GAUGE)
	private final int readOnlyHandlesLimit;

	public ReadOnlyHandleLimitSetEvent(@Nonnull String catalogName, @Nonnull FileType fileType, @Nonnull String name, int readOnlyHandlesLimit) {
		super(catalogName, fileType, name);
		this.readOnlyHandlesLimit = readOnlyHandlesLimit;
	}

}
