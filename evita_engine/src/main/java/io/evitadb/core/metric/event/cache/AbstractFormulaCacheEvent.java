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

package io.evitadb.core.metric.event.cache;

import io.evitadb.api.observability.annotation.EventGroup;
import io.evitadb.core.metric.event.CustomMetricsExecutionEvent;
import jdk.jfr.Category;

/**
 * Common event group for engine-owned formula result cache statistics and confirmed mismatches.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@EventGroup(
	value = AbstractFormulaCacheEvent.PACKAGE_NAME,
	name = "evitaDB - Formula cache",
	description = "Formula result cache statistics and confirmed verification mismatches."
)
@Category({"evitaDB", "Formula cache"})
abstract class AbstractFormulaCacheEvent extends CustomMetricsExecutionEvent {
	/** Stable group shared by cache events and their exported metric names. */
	protected static final String PACKAGE_NAME = "io.evitadb.cache";
}
