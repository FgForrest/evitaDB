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

package io.evitadb.externalApi.rest.metric.event.request;

import io.evitadb.api.observability.annotation.EventGroup;
import io.evitadb.api.observability.annotation.ExportMetricLabel;
import io.evitadb.core.metric.event.CustomMetricsExecutionEvent;
import io.evitadb.externalApi.rest.io.RestInstanceType;
import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Label;
import lombok.Getter;

import javax.annotation.Nonnull;

/**
 * Common ancestor for REST API request processing events.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2024
 */
@EventGroup(
	value = AbstractRestRequestEvent.PACKAGE_NAME,
	name = "evitaDB - REST Request",
	description = "evitaDB events related to REST request processing."
)
@Category({"evitaDB", "ExternalAPI", "REST", "Request"})
@Getter
public class AbstractRestRequestEvent extends CustomMetricsExecutionEvent {
	protected static final String PACKAGE_NAME = "io.evitadb.externalApi.rest.request";

	/**
	 * Type of used REST instance.
	 */
	@Label("REST instance type")
	@Description("Domain of the REST API used in connection with this event/metric: SYSTEM, or CATALOG")
	@ExportMetricLabel
	@Nonnull
	private final String restInstanceType;

	protected AbstractRestRequestEvent(@Nonnull RestInstanceType restInstanceType) {
		this.restInstanceType = restInstanceType.name();
	}
}
