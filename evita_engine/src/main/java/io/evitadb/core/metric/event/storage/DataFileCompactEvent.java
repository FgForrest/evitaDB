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

import io.evitadb.api.observability.annotation.ExportDurationMetric;
import io.evitadb.api.observability.annotation.ExportInvocationMetric;
import jdk.jfr.Description;
import jdk.jfr.Label;
import jdk.jfr.Name;
import lombok.Getter;

import javax.annotation.Nonnull;

/**
 * Event that is fired when an OffsetIndex file is compacted.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Name(AbstractStorageEvent.PACKAGE_NAME + ".DataFileCompact")
@Description("Event that is fired when an OffsetIndex file is compacted.")
@Label("OffsetIndex compaction")
@ExportDurationMetric(label = "Duration of OffsetIndex compaction.")
@ExportInvocationMetric(label = "OffsetIndex compaction.")
@Getter
public class DataFileCompactEvent extends AbstractDataFileEvent {

	public DataFileCompactEvent(
		@Nonnull String catalogName,
		@Nonnull FileType fileType,
		@Nonnull String name
	) {
		super(catalogName, fileType, name);
		this.begin();
	}

	public DataFileCompactEvent(
		@Nonnull FileType fileType,
		@Nonnull String name
	) {
		super(fileType, name);
		this.begin();
	}

	/**
	 * Finish the event.
	 * @return this event
	 */
	@Nonnull
	public DataFileCompactEvent finish() {
		this.end();
		return this;
	}
}
