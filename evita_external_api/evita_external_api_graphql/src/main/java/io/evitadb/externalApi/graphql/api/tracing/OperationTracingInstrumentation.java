/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.externalApi.graphql.api.tracing;

import graphql.ExecutionResult;
import graphql.execution.ExecutionContext;
import graphql.execution.instrumentation.InstrumentationContext;
import graphql.execution.instrumentation.InstrumentationState;
import graphql.execution.instrumentation.SimplePerformantInstrumentation;
import graphql.execution.instrumentation.parameters.InstrumentationExecuteOperationParameters;
import graphql.language.OperationDefinition;
import io.evitadb.api.observability.trace.TracingBlockReference;
import io.evitadb.api.observability.trace.TracingContext;
import io.evitadb.api.observability.trace.TracingContext.SpanAttribute;
import io.evitadb.api.observability.trace.TracingContextProvider;
import io.evitadb.externalApi.graphql.api.catalog.GraphQLContextKey;
import io.evitadb.externalApi.graphql.utils.GraphQLOperationNameResolver;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.NotThreadSafe;

import static graphql.execution.instrumentation.SimpleInstrumentationContext.noOp;

/**
 * Handles tracing the GraphQL operations.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2024
 */
@NotThreadSafe
public class OperationTracingInstrumentation extends SimplePerformantInstrumentation {

    @Nonnull
    private final TracingContext tracingContext;

    public OperationTracingInstrumentation() {
        this.tracingContext = TracingContextProvider.getContext();
    }

    @Nonnull
    @Override
    public InstrumentationContext<ExecutionResult> beginExecuteOperation(InstrumentationExecuteOperationParameters parameters,
                                                                         InstrumentationState state) {
        final ExecutionContext executionContext = parameters.getExecutionContext();
        final OperationDefinition.Operation operation = executionContext.getOperationDefinition().getOperation();
        final String operationName = GraphQLOperationNameResolver.resolve(executionContext.getOperationDefinition());

        // this block is closed in GraphQLHandler because instrumentation doesn't provide way of executing code
        // in same thread as this callback (if parallel query execution is used), which is needed by the tracing tooling
        final TracingBlockReference blockReference = this.tracingContext.createAndActivateBlockIfParentContextAvailable(
            "GraphQL " + operation.name().toLowerCase() + " - " + operationName,
            new SpanAttribute("operation", operation.name()),
            new SpanAttribute("operationName", operationName)
        );
        if (blockReference != null) {
            executionContext.getGraphQLContext().put(GraphQLContextKey.OPERATION_TRACING_BLOCK, blockReference);
        }

        return noOp();
    }
}
