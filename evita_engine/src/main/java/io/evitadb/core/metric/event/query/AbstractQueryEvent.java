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

package io.evitadb.core.metric.event.query;

import io.evitadb.api.observability.annotation.EventGroup;
import io.evitadb.core.metric.event.CatalogRelatedEvent;
import io.evitadb.core.metric.event.CustomMetricsExecutionEvent;
import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Label;
import jdk.jfr.Name;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * This event is base class for all query related events.
 */
@EventGroup(
	value = AbstractQueryEvent.PACKAGE_NAME,
	name = "evitaDB - Query",
	description = "evitaDB events related to query processing."
)
@Category({"evitaDB", "Query"})
@RequiredArgsConstructor
@Getter
abstract class AbstractQueryEvent extends CustomMetricsExecutionEvent implements CatalogRelatedEvent {
	protected static final String PACKAGE_NAME = "io.evitadb.query";

	/**
	 * The name of the catalog the transaction relates to.
	 */
	@Label("Catalog")
	@Name("catalogName")
	@Description("The name of the catalog to which this event/metric is associated.")
	final String catalogName;

}
