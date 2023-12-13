/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
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
import graphql.execution.instrumentation.InstrumentationState;
import graphql.execution.instrumentation.SimplePerformantInstrumentation;
import graphql.execution.instrumentation.parameters.InstrumentationExecutionParameters;
import graphql.language.OperationDefinition;
import io.evitadb.api.CatalogContract;
import io.evitadb.api.CatalogState;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.graphql.exception.GraphQLInternalError;
import io.evitadb.externalApi.graphql.exception.GraphQLSchemaBuildingError;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.NotThreadSafe;
import java.util.concurrent.CompletableFuture;

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

    @Nonnull
    @Override
    public ExecutionContext instrumentExecutionContext(@Nonnull ExecutionContext executionContext,
                                                       @Nonnull InstrumentationExecutionParameters parameters,
                                                       @Nonnull InstrumentationState state) {
        final OperationDefinition.Operation operation = executionContext.getOperationDefinition().getOperation();

        final EvitaSessionContract evitaSession;
        if (operation == OperationDefinition.Operation.QUERY) {
            evitaSession = evita.createReadOnlySession(catalogName);
        } else if (operation == OperationDefinition.Operation.MUTATION) {
            evitaSession = evita.createReadWriteSession(catalogName);
            final CatalogContract catalog = evita.getCatalogInstance(catalogName)
                .orElseThrow(() -> new GraphQLInternalError("Catalog `" + catalogName + "` could not be found."));
            if (catalog.supportsTransaction()) {
                evitaSession.openTransaction();
            }
        } else {
            throw new GraphQLInternalError("Operation `" + operation + "` is currently not supported by evitaDB GraphQL API.");
        }
        executionContext.getGraphQLContext().put(GraphQLContextKey.EVITA_SESSION, evitaSession);

        return executionContext;
    }

    @Nonnull
    @Override
    public CompletableFuture<ExecutionResult> instrumentExecutionResult(@Nonnull ExecutionResult executionResult,
                                                                        @Nonnull InstrumentationExecutionParameters parameters,
                                                                        @Nonnull InstrumentationState state) {
        final EvitaSessionContract evitaSession = parameters.getGraphQLContext().get(GraphQLContextKey.EVITA_SESSION);
        if (evitaSession != null) {
            // there may not be any session if there was some error in GraphQL query parsing before the session creation
            // or the catalog is in WARMING_UP state
            if (evitaSession.isTransactionOpen()) {
                evitaSession.closeTransaction();
            }
            evitaSession.close();
        }

        return CompletableFuture.completedFuture(executionResult);
    }
}
