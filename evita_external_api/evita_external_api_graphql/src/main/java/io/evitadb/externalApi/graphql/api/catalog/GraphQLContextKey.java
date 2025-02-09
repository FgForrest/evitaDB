/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.externalApi.graphql.api.catalog;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;

/**
 * List of possible keys (for possible values) for GraphQL query execution context.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@Getter
@RequiredArgsConstructor
public enum GraphQLContextKey {

    EVITA_SESSION("evitaSession"),
    OPERATION_TRACING_BLOCK("operationTracingBlock"),
    METRIC_EXECUTED_EVENT("metricExecutedEvent"),
    TRAFFIC_SOURCE_QUERY_RECORDING_ID("trafficSourceQueryRecordingId"),
    TRAFFIC_SOURCE_QUERY_RECORDING_EXCEPTIONS("trafficSourceQueryRecordingExceptions");

    @Nonnull
    private final String key;
}
