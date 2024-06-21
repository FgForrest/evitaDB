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

package io.evitadb.externalApi.graphql.io;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.AggregationOptions;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.stream.SubscriptionOption;
import graphql.GraphQL;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.SchemaPrinter;
import graphql.schema.idl.SchemaPrinter.Options;
import io.evitadb.externalApi.exception.ExternalApiInternalError;
import io.evitadb.externalApi.exception.ExternalApiInvalidUsageException;
import io.evitadb.externalApi.graphql.exception.GraphQLInternalError;
import io.evitadb.externalApi.graphql.exception.GraphQLInvalidUsageException;
import io.evitadb.externalApi.graphql.io.GraphQLSchemaHandler.GraphQLEndpointExchange;
import io.evitadb.externalApi.http.AbstractHttpService;
import io.evitadb.externalApi.http.EndpointRequest;
import io.evitadb.externalApi.http.EndpointResponse;
import io.evitadb.externalApi.http.SuccessEndpointResponse;
import io.evitadb.utils.Assert;
import io.netty.util.concurrent.EventExecutor;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Subscriber;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.OutputStream;
import java.io.PrintWriter;
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
public class GraphQLSchemaHandler extends AbstractHttpService<GraphQLEndpointExchange> {

    private static final Set<String> IMPLICIT_DIRECTIVES = Set.of("deprecated", "skip", "include", "specifiedBy");

    @Nonnull
    private final SchemaPrinter schemaPrinter;
    @Nonnull
    private final AtomicReference<GraphQL> graphQL;

    public GraphQLSchemaHandler(@Nonnull AtomicReference<GraphQL> graphQL) {
        this.schemaPrinter = new SchemaPrinter(Options.defaultOptions()
            .includeDirectives(directive -> !IMPLICIT_DIRECTIVES.contains(directive)));
        this.graphQL = graphQL;
    }

    @Nonnull
    @Override
    protected GraphQLEndpointExchange createEndpointExchange(@Nonnull HttpRequest httpRequest,
                                                                     @Nonnull String httpMethod,
                                                                     @Nullable String requestBodyMediaType,
                                                                     @Nullable String preferredResponseMediaType) {
        return new GraphQLEndpointExchange(httpRequest, httpMethod, requestBodyMediaType, preferredResponseMediaType);
    }

    @Override
    @Nonnull
    protected EndpointResponse doHandleRequest(@Nonnull GraphQLEndpointExchange exchange) {
        return new SuccessEndpointResponse(graphQL.get().getGraphQLSchema());
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
    public Set<String> getSupportedHttpMethods() {
        return Set.of(HttpMethod.GET.name());
    }

    @Nonnull
    @Override
    public LinkedHashSet<String> getSupportedResponseContentTypes() {
        final LinkedHashSet<String> mediaTypes = createLinkedHashSet(1);
        mediaTypes.add(GraphQLMimeTypes.APPLICATION_GRAPHQL);
        return mediaTypes;
    }

    @Override
    protected void writeResponse(@Nonnull GraphQLEndpointExchange exchange, @Nonnull HttpResponseWriter responseWriter, @Nonnull Object response) {
        Assert.isPremiseValid(
            response instanceof GraphQLSchema,
            () -> new GraphQLInternalError("Expected response to be instance of GraphQLSchema, but was `" + response.getClass().getName() + "`.")
        );

        final String schema = schemaPrinter.print((GraphQLSchema) response);
        responseWriter.write(HttpData.ofUtf8(schema));
    }

    protected record GraphQLEndpointExchange(@Nonnull HttpRequest httpRequest,
                                             @Nonnull String httpMethod,
                                             @Nullable String requestBodyContentType,
                                             @Nullable String preferredResponseContentType) implements EndpointRequest {

    }
}
