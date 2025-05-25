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
import graphql.GraphQLContext;
import graphql.execution.ExecutionContext;
import graphql.execution.instrumentation.InstrumentationContext;
import graphql.execution.instrumentation.InstrumentationState;
import graphql.execution.instrumentation.SimpleInstrumentationContext;
import graphql.execution.instrumentation.SimplePerformantInstrumentation;
import graphql.execution.instrumentation.parameters.InstrumentationExecuteOperationParameters;
import graphql.execution.instrumentation.parameters.InstrumentationExecutionParameters;
import graphql.execution.instrumentation.parameters.InstrumentationValidationParameters;
import graphql.language.Document;
import graphql.validation.ValidationError;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.TrafficRecordingOptions;
import io.evitadb.core.EvitaInternalSessionContract;
import io.evitadb.exception.EvitaError;
import io.evitadb.externalApi.graphql.api.catalog.GraphQLContextKey;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
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
	@Nonnull private final ObjectMapper objectMapper;
	@Nonnull private final TrafficRecordingOptions trafficRecordingOptions;

	@Nullable
	@Override
	public InstrumentationContext<Document> beginParse(InstrumentationExecutionParameters parameters, InstrumentationState state) {
		return logFailedSourceQuery(parameters);
	}

	@Nullable
	@Override
	public InstrumentationContext<List<ValidationError>> beginValidation(InstrumentationValidationParameters parameters, InstrumentationState state) {
		return logFailedSourceQuery(parameters);
	}

	@Nonnull
	private <T> InstrumentationContext<T> logFailedSourceQuery(InstrumentationExecutionParameters parameters) {
		final EvitaInternalSessionContract internalSession = getInternalSession(parameters.getGraphQLContext());
		if (internalSession == null) {
			return noOp();
		}

		return new SimpleInstrumentationContext<>() {
			@Override
			public void onCompleted(T result, Throwable t) {
				if (t != null) {
					final String serializedSourceQuery = serializeSourceQuery(parameters.getExecutionInput());
					if (serializedSourceQuery == null) {
						return;
					}

					internalSession.recordSourceQuery(
						serializedSourceQuery,
						GraphQLQueryLabels.GRAPHQL_SOURCE_TYPE_VALUE,
						t.getMessage()
					);
				}
			}
		};
	}

	@Override
	@Nullable
	public InstrumentationContext<ExecutionResult> beginExecuteOperation(InstrumentationExecuteOperationParameters parameters,
	                                                                     InstrumentationState state) {
		final ExecutionContext executionContext = parameters.getExecutionContext();
		final GraphQLContext graphQLContext = executionContext.getGraphQLContext();
		final EvitaInternalSessionContract internalSession = getInternalSession(graphQLContext);
		if (internalSession == null) {
			return noOp();
		}

		final String serializedSourceQuery = serializeSourceQuery(executionContext.getExecutionInput());
		if (serializedSourceQuery == null) {
			return noOp();
		}

		final UUID recordingId = internalSession.recordSourceQuery(
			serializedSourceQuery,
			GraphQLQueryLabels.GRAPHQL_SOURCE_TYPE_VALUE,
			null
		);
		graphQLContext.put(GraphQLContextKey.TRAFFIC_SOURCE_QUERY_RECORDING_ID, recordingId);

		return new SimpleInstrumentationContext<>() {
			@Override
			public void onCompleted(ExecutionResult result, Throwable t) {
				final StringBuilder combinedErrorMessage = new StringBuilder();
				if (t != null) {
					combinedErrorMessage.append(t.getMessage());
				}
				final List<EvitaError> exceptionExceptions = graphQLContext.get(GraphQLContextKey.TRAFFIC_SOURCE_QUERY_RECORDING_EXCEPTIONS);
				if (exceptionExceptions != null && !exceptionExceptions.isEmpty()) {
					exceptionExceptions.forEach(exception ->
						combinedErrorMessage.append("; ").append(exception.getPublicMessage()));
				}

				internalSession.finalizeSourceQuery(
					recordingId,
					!combinedErrorMessage.isEmpty() ? combinedErrorMessage.toString() : null
				);
				graphQLContext.delete(GraphQLContextKey.TRAFFIC_SOURCE_QUERY_RECORDING_ID);
			}
		};
	}

	@Nullable
	private EvitaInternalSessionContract getInternalSession(@Nonnull GraphQLContext graphQLContext) {
		if (!this.trafficRecordingOptions.sourceQueryTrackingEnabled()) {
			return null;
		}

		final EvitaSessionContract evitaSession = graphQLContext.get(GraphQLContextKey.EVITA_SESSION);
		if (evitaSession == null) {
			return null;
		}
		if (!(evitaSession instanceof EvitaInternalSessionContract evitaInternalSession)) {
			log.error("Source query tracking is enabled but Evita session is not of internal type. Cannot record source query. Aborting.");
			return null;
		}
		return evitaInternalSession;
	}

	@Nullable
	private String serializeSourceQuery(@Nonnull ExecutionInput executionInput) {
		final SourceQueryDto sourceQuery = SourceQueryDto.from(executionInput);
		try {
			return this.objectMapper.writeValueAsString(sourceQuery);
		} catch (JsonProcessingException e) {
			log.error("Cannot serialize source query for traffic recording. Aborting.", e);
			return null;
		}
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
