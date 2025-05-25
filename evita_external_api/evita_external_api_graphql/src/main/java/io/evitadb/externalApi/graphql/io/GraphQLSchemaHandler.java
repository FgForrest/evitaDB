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

package io.evitadb.externalApi.graphql.io;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponseWriter;
import graphql.GraphQL;
import graphql.schema.GraphQLSchema;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.exception.ExternalApiInternalError;
import io.evitadb.externalApi.exception.ExternalApiInvalidUsageException;
import io.evitadb.externalApi.graphql.exception.GraphQLInternalError;
import io.evitadb.externalApi.graphql.exception.GraphQLInvalidUsageException;
import io.evitadb.externalApi.graphql.utils.GraphQLSchemaPrinter;
import io.evitadb.externalApi.http.EndpointHandler;
import io.evitadb.externalApi.http.EndpointResponse;
import io.evitadb.externalApi.http.SuccessEndpointResponse;
import io.evitadb.utils.Assert;
import io.netty.channel.EventLoop;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.utils.CollectionUtils.createLinkedHashSet;

/**
 * HTTP request handler for returning {@link graphql.schema.GraphQLSchema} as a DSL string using passed
 * configured instance of {@link GraphQL}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. 2023
 */
@Slf4j
public class GraphQLSchemaHandler extends EndpointHandler<GraphQLSchemaEndpointExecutionContext> {

    @Nonnull
    private final Evita evita;
    @Nonnull
    private final AtomicReference<GraphQL> graphQL;

    public GraphQLSchemaHandler(
        @Nonnull Evita evita,
        @Nonnull AtomicReference<GraphQL> graphQL
    ) {
        this.evita = evita;
        this.graphQL = graphQL;
    }

    @Nonnull
    @Override
    protected GraphQLSchemaEndpointExecutionContext createExecutionContext(@Nonnull HttpRequest httpRequest) {
        return new GraphQLSchemaEndpointExecutionContext(httpRequest, this.evita);
    }

    @Override
    @Nonnull
    protected CompletableFuture<EndpointResponse> doHandleRequest(@Nonnull GraphQLSchemaEndpointExecutionContext executionContext) {
        return executionContext.executeAsyncInRequestThreadPool(
            () -> new SuccessEndpointResponse(this.graphQL.get().getGraphQLSchema())
        );
    }

    @Nonnull
    @Override
    protected <T extends ExternalApiInternalError> T createInternalError(@Nonnull String message) {
        //noinspection unchecked
        return (T) new GraphQLInternalError(message);
    }

    @Nonnull
    @Override
    protected <T extends ExternalApiInternalError> T createInternalError(@Nonnull String message, @Nonnull Throwable cause) {
        //noinspection unchecked
        return (T) new GraphQLInternalError(message, cause);
    }

    @Nonnull
    @Override
    protected <T extends ExternalApiInvalidUsageException> T createInvalidUsageException(@Nonnull String message) {
        //noinspection unchecked
        return (T) new GraphQLInvalidUsageException(message);
    }

    @Nonnull
    @Override
    public Set<HttpMethod> getSupportedHttpMethods() {
        return Set.of(HttpMethod.GET);
    }

    @Nonnull
    @Override
    public LinkedHashSet<String> getSupportedResponseContentTypes() {
        final LinkedHashSet<String> mediaTypes = createLinkedHashSet(1);
        mediaTypes.add(GraphQLMimeTypes.APPLICATION_GRAPHQL);
        return mediaTypes;
    }

    @Override
    protected void writeResponse(@Nonnull GraphQLSchemaEndpointExecutionContext executionContext, @Nonnull HttpResponseWriter responseWriter, @Nonnull Object response, @Nonnull EventLoop eventExecutors) {
        Assert.isPremiseValid(
            response instanceof GraphQLSchema,
            () -> new GraphQLInternalError("Expected response to be instance of GraphQLSchema, but was `" + response.getClass().getName() + "`.")
        );
        final String printedSchema = GraphQLSchemaPrinter.print((GraphQLSchema) response);
	    responseWriter.write(HttpData.ofUtf8(printedSchema));
    }
}
