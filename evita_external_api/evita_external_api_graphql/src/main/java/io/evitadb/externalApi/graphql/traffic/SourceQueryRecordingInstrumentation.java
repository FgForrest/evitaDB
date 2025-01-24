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

package io.evitadb.externalApi.graphql.traffic;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.execution.ExecutionContext;
import graphql.execution.instrumentation.InstrumentationContext;
import graphql.execution.instrumentation.InstrumentationState;
import graphql.execution.instrumentation.SimpleInstrumentationContext;
import graphql.execution.instrumentation.SimplePerformantInstrumentation;
import graphql.execution.instrumentation.parameters.InstrumentationExecuteOperationParameters;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.TrafficRecordingOptions;
import io.evitadb.core.EvitaInternalSessionContract;
import io.evitadb.externalApi.graphql.api.catalog.GraphQLContextKey;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;

import static graphql.execution.instrumentation.SimpleInstrumentationContext.noOp;

/**
 * Manages context for source query tracking for data fetchers.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2024
 */
@RequiredArgsConstructor
@Slf4j
public class SourceQueryRecordingInstrumentation extends SimplePerformantInstrumentation {

	private static final String SOURCE_QUERY_TYPE = "GraphQL";

	@Nonnull private final ObjectMapper objectMapper;
	@Nonnull private final TrafficRecordingOptions trafficRecordingOptions;

	@Override
	@Nullable
	public InstrumentationContext<ExecutionResult> beginExecuteOperation(InstrumentationExecuteOperationParameters parameters,
	                                                                     InstrumentationState state) {
		if (!trafficRecordingOptions.sourceQueryTrackingEnabled()) {
			return noOp();
		}

		final ExecutionContext executionContext = parameters.getExecutionContext();
		final EvitaSessionContract evitaSession = executionContext.getGraphQLContext().get(GraphQLContextKey.EVITA_SESSION);
		if (!(evitaSession instanceof EvitaInternalSessionContract evitaInternalSession)) {
			log.error("Source query tracking is enabled but Evita session is not of internal type. Cannot record source query. Aborting.");
			return noOp();
		}

		final SourceQueryDto sourceQuery = SourceQueryDto.from(executionContext.getExecutionInput());
		final String serializedSourceQuery;
		try {
			serializedSourceQuery = objectMapper.writeValueAsString(sourceQuery);
		} catch (JsonProcessingException e) {
			log.error("Cannot serialize source query for traffic recording. Aborting.", e);
			return noOp();
		}

		/* TODO LHO - insert finishedWithError if parsing failed */
		final UUID recordingId = evitaInternalSession.recordSourceQuery(
			serializedSourceQuery,
			SOURCE_QUERY_TYPE,
			null
		);
		executionContext.getGraphQLContext().put(GraphQLContextKey.TRAFFIC_SOURCE_QUERY_RECORDING_ID, recordingId);

		return new SimpleInstrumentationContext<>() {
			@Override
			public void onCompleted(ExecutionResult result, Throwable t) {
				/* TODO LHO - insert finishedWithError if any of the queries failed with error */
				evitaInternalSession.finalizeSourceQuery(recordingId, null);
				executionContext.getGraphQLContext().delete(GraphQLContextKey.TRAFFIC_SOURCE_QUERY_RECORDING_ID);
			}
		};
	}

	private record SourceQueryDto(@Nonnull String query, @Nonnull Map<String, Object> variables, @Nonnull Map<String, Object> extensions) {

		public static SourceQueryDto from(@Nonnull ExecutionInput executionInput) {
			return new SourceQueryDto(
				executionInput.getQuery(),
				executionInput.getVariables(),
				executionInput.getExtensions()
			);
		}
	}

}
