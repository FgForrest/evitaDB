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

package io.evitadb.externalApi.graphql.metric.event.instance;

import io.evitadb.api.observability.annotation.EventGroup;
import io.evitadb.core.metric.event.CustomMetricsExecutionEvent;
import jdk.jfr.Category;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Common event ancestor for events regarding GraphQL instances.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2024
 */
@EventGroup(value = AbstractGraphQLInstanceEvent.PACKAGE_NAME, description = "evitaDB events relating to GraphQL API.")
@Category({"evitaDB", "API", "GraphQL", "Instance", "Schema"})
@RequiredArgsConstructor
@Getter
public class AbstractGraphQLInstanceEvent extends CustomMetricsExecutionEvent {

	protected static final String PACKAGE_NAME = "io.evitadb.externalApi.graphql.instance";
}
