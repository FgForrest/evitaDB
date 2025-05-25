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

import graphql.ExecutionResult;
import graphql.execution.instrumentation.InstrumentationContext;
import graphql.execution.instrumentation.InstrumentationState;
import graphql.execution.instrumentation.SimplePerformantInstrumentation;
import graphql.execution.instrumentation.parameters.InstrumentationExecuteOperationParameters;
import graphql.execution.instrumentation.parameters.InstrumentationExecutionParameters;
import graphql.execution.instrumentation.parameters.InstrumentationValidationParameters;
import graphql.language.Document;
import graphql.language.OperationDefinition;
import graphql.validation.ValidationError;
import io.evitadb.externalApi.graphql.api.catalog.GraphQLContextKey;
import io.evitadb.externalApi.graphql.metric.event.request.ExecutedEvent.ResponseStatus;
import io.evitadb.externalApi.graphql.utils.GraphQLOperationNameResolver;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Instrumentation to provide data for {@link ExecutedEvent} that should be present in the GraphQL context.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2024
 */
@RequiredArgsConstructor
public class RequestMetricInstrumentation extends SimplePerformantInstrumentation {

	@Nonnull
	private final String catalogName;

	@Override
	@Nullable
	public InstrumentationContext<ExecutionResult> beginExecution(InstrumentationExecutionParameters parameters,
	                                                              InstrumentationState state) {
		final ExecutedEvent requestExecutedEvent = parameters.getGraphQLContext().get(GraphQLContextKey.METRIC_EXECUTED_EVENT);
		if (requestExecutedEvent != null) {
			requestExecutedEvent.provideCatalogName(this.catalogName);
		}
		return super.beginExecution(parameters, state);
	}

	@Override
	@Nullable
	public InstrumentationContext<Document> beginParse(InstrumentationExecutionParameters parameters,
	                                                   InstrumentationState state) {
		final ExecutedEvent requestExecutedEvent = parameters.getGraphQLContext().get(GraphQLContextKey.METRIC_EXECUTED_EVENT);
		if (requestExecutedEvent != null) {
			requestExecutedEvent.finishPreparation();
		}
		return super.beginParse(parameters, state);
	}

	@Override
	@Nullable
	public InstrumentationContext<List<ValidationError>> beginValidation(InstrumentationValidationParameters parameters,
	                                                                     InstrumentationState state) {
		final ExecutedEvent requestExecutedEvent = parameters.getGraphQLContext().get(GraphQLContextKey.METRIC_EXECUTED_EVENT);
		if (requestExecutedEvent != null) {
			requestExecutedEvent.finishParse();
		}
		return super.beginValidation(parameters, state);
	}

	@Override
	@Nullable
	public InstrumentationContext<ExecutionResult> beginExecuteOperation(InstrumentationExecuteOperationParameters parameters,
	                                                                     InstrumentationState state) {
		final ExecutedEvent requestExecutedEvent = parameters.getExecutionContext().getGraphQLContext().get(GraphQLContextKey.METRIC_EXECUTED_EVENT);
		if (requestExecutedEvent != null) {
			requestExecutedEvent.finishValidation();

			final OperationDefinition operationDefinition = parameters.getExecutionContext().getOperationDefinition();
			requestExecutedEvent.provideOperationType(operationDefinition.getOperation());
			requestExecutedEvent.provideOperationName(GraphQLOperationNameResolver.resolve(operationDefinition));
			requestExecutedEvent.provideRootFieldsProcessed(operationDefinition.getSelectionSet().getSelections().size());
		}
		return super.beginExecuteOperation(parameters, state);
	}

	@Override
	@Nonnull
	public CompletableFuture<ExecutionResult> instrumentExecutionResult(ExecutionResult executionResult,
	                                                                    InstrumentationExecutionParameters parameters,
	                                                                    InstrumentationState state) {
		final ExecutedEvent requestExecutedEvent = parameters.getGraphQLContext().get(GraphQLContextKey.METRIC_EXECUTED_EVENT);
		if (requestExecutedEvent != null) {
			requestExecutedEvent.finishOperationExecution();
			if (executionResult.getErrors() != null &&
				!executionResult.getErrors().isEmpty() &&
				ResponseStatus.OK.name().equals(requestExecutedEvent.getResponseStatus())) {
				requestExecutedEvent.provideResponseStatus(ResponseStatus.ERROR);
			}
		}
		return super.instrumentExecutionResult(executionResult, parameters, state);
	}
}
