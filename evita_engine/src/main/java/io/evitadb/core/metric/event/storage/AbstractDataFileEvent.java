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

import io.evitadb.api.observability.annotation.ExportMetricLabel;
import jdk.jfr.Description;
import jdk.jfr.Label;
import lombok.Getter;

import javax.annotation.Nonnull;

/**
 * Abstract ancestor for events that are related to a specific OffsetIndex file.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Getter
abstract class AbstractDataFileEvent extends AbstractStorageEvent {
	/**
	 * The type of the file that was flushed.
	 */
	@Label("File type")
	@Description("The type of the file that was flushed. One of: CATALOG, ENTITY_COLLECTION, WAL, or BOOTSTRAP")
	@ExportMetricLabel
	final String fileType;
	/**
	 * The logical name of the file that was flushed.
	 */
	@Label("Logical file name")
	@Description("The logical name of the file that was flushed. Identifies the file more precisely.")
	@ExportMetricLabel
	final String name;

	public AbstractDataFileEvent(@Nonnull String catalogName, @Nonnull FileType fileType, @Nonnull String name) {
		super(catalogName);
		this.fileType = fileType.name();
		this.name = name;
	}

	protected AbstractDataFileEvent(@Nonnull FileType fileType, @Nonnull String name) {
		super(null);
		this.fileType = fileType.name();
		this.name = name;
	}

}
