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

package io.evitadb.externalApi.graphql.api;

import graphql.ExecutionInput;
import graphql.execution.instrumentation.InstrumentationState;
import graphql.execution.instrumentation.SimplePerformantInstrumentation;
import graphql.execution.instrumentation.parameters.InstrumentationExecutionParameters;
import graphql.validation.QueryComplexityLimits;
import io.evitadb.externalApi.graphql.configuration.GraphQLOptions;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;

/**
 * Applies the query complexity limits configured in {@link GraphQLOptions#getMaxQueryDepth()} and
 * {@link GraphQLOptions#getMaxQueryFieldsCount()} to every request of a GraphQL instance.
 *
 * graphql-java enforces {@link QueryComplexityLimits} while it validates a document and reads them from the
 * request's GraphQL context under {@link QueryComplexityLimits#KEY}. The limits are put there in
 * {@link #instrumentExecutionInput}, which graphql-java calls before parsing and validation, so a single place
 * covers requests arriving over HTTP as well as over WebSocket.
 *
 * Because the limits are fixed for the whole lifetime of a GraphQL instance, a document validated once is valid for
 * every later request of the same instance, and {@link GraphQLDocumentCache} keyed by the query text alone stays
 * correct.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
@ThreadSafe
public class QueryComplexityLimitsInstrumentation extends SimplePerformantInstrumentation {

	/**
	 * Limits applied to every request.
	 */
	@Nonnull private final QueryComplexityLimits limits;

	/**
	 * Creates the instrumentation with the limits taken from the passed configuration.
	 *
	 * @param config GraphQL API configuration carrying the limits
	 */
	public QueryComplexityLimitsInstrumentation(@Nonnull GraphQLOptions config) {
		this.limits = QueryComplexityLimits.newLimits()
			.maxDepth(config.getMaxQueryDepth())
			.maxFieldsCount(config.getMaxQueryFieldsCount())
			.build();
	}

	@Nonnull
	@Override
	public ExecutionInput instrumentExecutionInput(
		@Nonnull ExecutionInput executionInput,
		@Nonnull InstrumentationExecutionParameters parameters,
		@Nonnull InstrumentationState state
	) {
		executionInput.getGraphQLContext().put(QueryComplexityLimits.KEY, this.limits);
		return executionInput;
	}
}
