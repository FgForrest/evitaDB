/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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
import static graphql.execution.instrumentation.SimpleInstrumentationContext.whenDispatched;

/**
 * Traces every GraphQL operation in a span of its own, nested in the span of the request that executes it. The span
 * is recorded only when tracing is switched on and the thread starting the operation carries a parent tracing
 * context.
 *
 * The operation's {@link TracingBlockReference}, if the tracing context provides one, is stored in the GraphQL context
 * under {@link GraphQLContextKey#OPERATION_TRACING_BLOCK}. Its thread-bound scope is released as soon as the operation
 * is dispatched, but the span stays open: whoever executes the operation must close the stored block once the
 * execution completes, on whichever thread that happens - as
 * {@link io.evitadb.externalApi.graphql.io.web.GraphQLWebHandler} does.
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

        final TracingBlockReference blockReference = this.tracingContext.createAndActivateBlockIfParentContextAvailable(
            "GraphQL " + operation.name().toLowerCase() + " - " + operationName,
            new SpanAttribute("operation", operation.name()),
            new SpanAttribute("operationName", operationName)
        );
        if (blockReference == null) {
            return noOp();
        }
        executionContext.getGraphQLContext().put(GraphQLContextKey.OPERATION_TRACING_BLOCK, blockReference);

        // The block's scope is bound to this thread, but the operation usually completes on another one - the thread
        // finishing its last asynchronous data fetcher. graphql-java reports the dispatch on this very thread, right
        // after the execution strategy has started the operation. Every data fetcher the strategy invoked while
        // starting - for a query, those of all non-deferred root fields - was called on this thread, so each
        // AsyncDataFetcher among them has already captured the span as its parent, and the scope is released there.
        // Closed on another thread, the scope would not be restored here - OpenTelemetry's default thread-local
        // storage ignores a close on a thread where the scope's context is not current - and this thread would keep
        // the span current for every request it serves next. The span itself is ended by GraphQLWebHandler when the
        // operation completes.
        return whenDispatched(blockReference::detachScope);
    }
}
