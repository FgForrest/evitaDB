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

package io.evitadb.core.metric.event.system;

import io.evitadb.api.observability.annotation.ExportMetricLabel;
import jdk.jfr.Description;
import jdk.jfr.Label;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * This event is base class for all background task related events.
 */
@Getter
abstract class AbstractBackgroundTaskEvent extends AbstractSystemCatalogEvent {

	/**
	 * The name of the background task.
	 */
	@Label("Task name")
	@Description("Name of the background task.")
	@ExportMetricLabel
	final String taskName;

	public AbstractBackgroundTaskEvent(@Nullable String catalogName, @Nonnull String taskName) {
		super(catalogName);
		this.taskName = taskName;
	}
}
