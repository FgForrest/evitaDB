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
import jdk.jfr.Label;
import jdk.jfr.Name;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Common ancestor for REST API request processing events.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2024
 */
@EventGroup(value = AbstractRestRequestEvent.PACKAGE_NAME, description = "evitaDB events relating to REST request processing.")
@Category({"evitaDB", "ExternalAPI", "REST", "Request"})
@Getter
public class AbstractRestRequestEvent extends CustomMetricsExecutionEvent {

	protected static final String PACKAGE_NAME = "io.evitadb.externalApi.rest.request";

	/**
	 * Type of used GQL instance.
	 */
	@Label("Instance type")
	@Name("instanceType")
	@ExportMetricLabel
	@Nullable
	final String instanceType;

	protected AbstractRestRequestEvent(@Nonnull RestInstanceType instanceType) {
		this.instanceType = instanceType.name();
	}
}
