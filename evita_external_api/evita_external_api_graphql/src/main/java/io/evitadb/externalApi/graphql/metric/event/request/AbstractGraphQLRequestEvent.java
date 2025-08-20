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

package io.evitadb.externalApi.graphql.metric.event.request;

import io.evitadb.api.observability.annotation.EventGroup;
import io.evitadb.api.observability.annotation.ExportMetricLabel;
import io.evitadb.core.metric.event.CustomMetricsExecutionEvent;
import io.evitadb.externalApi.graphql.io.GraphQLInstanceType;
import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Label;
import lombok.Getter;

import javax.annotation.Nullable;

/**
 * Ancestor for GraphQL request processing events.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2024
 */
@EventGroup(
	value = AbstractGraphQLRequestEvent.PACKAGE_NAME,
	name = "evitaDB - GraphQL Request",
	description = "evitaDB events related to GraphQL request processing."
)
@Category({"evitaDB", "ExternalAPI", "GraphQL", "Request"})
@Getter
public class AbstractGraphQLRequestEvent extends CustomMetricsExecutionEvent {
	protected static final String PACKAGE_NAME = "io.evitadb.externalApi.graphql.request";

	/**
	 * Type of used GQL instance.
	 */
	@Label("GraphQL instance type")
	@Description("Domain of the GraphQL API used in connection with this event/metric: SYSTEM, SCHEMA, or DATA")
	@ExportMetricLabel
	@Nullable
	final String graphQLInstanceType;

	protected AbstractGraphQLRequestEvent(@Nullable GraphQLInstanceType graphQLInstanceType) {
		this.graphQLInstanceType = graphQLInstanceType == null ? null : graphQLInstanceType.toString();
	}
}
