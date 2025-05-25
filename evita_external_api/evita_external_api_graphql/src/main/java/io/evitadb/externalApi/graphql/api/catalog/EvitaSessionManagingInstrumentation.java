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

package io.evitadb.externalApi.graphql.api.catalog;

import graphql.ExecutionResult;
import graphql.execution.ExecutionContext;
import graphql.execution.instrumentation.Instrumentation;
import graphql.execution.instrumentation.InstrumentationContext;
import graphql.execution.instrumentation.InstrumentationState;
import graphql.execution.instrumentation.SimplePerformantInstrumentation;
import graphql.execution.instrumentation.parameters.InstrumentationExecuteOperationParameters;
import graphql.execution.instrumentation.parameters.InstrumentationExecutionParameters;
import graphql.language.OperationDefinition;
import graphql.language.OperationDefinition.Operation;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.exception.RollbackException;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.graphql.exception.GraphQLInternalError;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.util.concurrent.CompletableFuture;

import static graphql.execution.instrumentation.SimpleInstrumentationContext.noOp;

/**
 * GraphQL {@link Instrumentation} which is responsible for managing lifecycle of {@link EvitaSessionContract} during
 * query execution.
 *
 * It creates correct type of session at the beginning of execution depending on input type (query/mutation).
 * After execution, it closes the session.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@NotThreadSafe
@RequiredArgsConstructor
public class EvitaSessionManagingInstrumentation extends SimplePerformantInstrumentation {

    @Nonnull
    private final Evita evita;
    @Nonnull
    private final String catalogName;

    @Override
    @Nullable
    public InstrumentationContext<ExecutionResult> beginExecuteOperation(InstrumentationExecuteOperationParameters parameters,
                                                                         InstrumentationState state) {
        final ExecutionContext executionContext = parameters.getExecutionContext();
        final Operation operation = executionContext.getOperationDefinition().getOperation();

        final EvitaSessionContract evitaSession;
        if (operation == OperationDefinition.Operation.QUERY || operation == OperationDefinition.Operation.SUBSCRIPTION) {
            evitaSession = this.evita.createReadOnlySession(this.catalogName);
        } else if (operation == OperationDefinition.Operation.MUTATION) {
            evitaSession = this.evita.createReadWriteSession(this.catalogName);
        } else {
            throw new GraphQLInternalError("Operation `" + operation + "` is currently not supported by evitaDB GraphQL API.");
        }
        executionContext.getGraphQLContext().put(GraphQLContextKey.EVITA_SESSION, evitaSession);

        return noOp();
    }

    @Nonnull
    @Override
    public CompletableFuture<ExecutionResult> instrumentExecutionResult(ExecutionResult executionResult,
                                                                        InstrumentationExecutionParameters parameters,
                                                                        InstrumentationState state) {
        final EvitaSessionContract evitaSession = parameters.getGraphQLContext().get(GraphQLContextKey.EVITA_SESSION);
        if (evitaSession != null) {
            try {
                evitaSession.close();
                parameters.getGraphQLContext().delete(GraphQLContextKey.EVITA_SESSION);
            } catch (RollbackException ex) {
                // we can ignore the rollback exception here,
                // because the exception has been already handled by exception handler
            }
        }

        return CompletableFuture.completedFuture(executionResult);
    }
}
