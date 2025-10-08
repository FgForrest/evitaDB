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

package io.evitadb.externalApi.graphql.io.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServiceRequestContext;
import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.GraphQLException;
import graphql.execution.InputMapDefinesTooManyFieldsException;
import graphql.execution.NonNullableValueCoercedAsNullException;
import graphql.execution.UnknownOperationException;
import graphql.schema.CoercingParseValueException;
import graphql.schema.CoercingSerializeException;
import io.evitadb.api.observability.trace.TracingBlockReference;
import io.evitadb.core.Evita;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.configuration.HeaderOptions;
import io.evitadb.externalApi.exception.ExternalApiInternalError;
import io.evitadb.externalApi.exception.ExternalApiInvalidUsageException;
import io.evitadb.externalApi.exception.HttpExchangeException;
import io.evitadb.externalApi.graphql.api.catalog.GraphQLContextKey;
import io.evitadb.externalApi.graphql.exception.GraphQLInternalError;
import io.evitadb.externalApi.graphql.exception.GraphQLInvalidUsageException;
import io.evitadb.externalApi.graphql.io.GraphQLEndpointExecutionContext;
import io.evitadb.externalApi.graphql.io.GraphQLInstanceType;
import io.evitadb.externalApi.graphql.io.GraphQLMimeTypes;
import io.evitadb.externalApi.graphql.io.GraphQLRequest;
import io.evitadb.externalApi.graphql.io.GraphQLResponse;
import io.evitadb.externalApi.graphql.metric.event.request.ExecutedEvent;
import io.evitadb.externalApi.http.EndpointHandler;
import io.evitadb.externalApi.http.EndpointResponse;
import io.evitadb.externalApi.http.MimeTypes;
import io.evitadb.externalApi.http.SuccessEndpointResponse;
import io.evitadb.externalApi.trace.ExternalApiTracingContextProvider;
import io.evitadb.externalApi.utils.ExternalApiTracingContext;
import io.netty.channel.EventLoop;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.utils.CollectionUtils.createLinkedHashSet;

/**
 * HTTP request handler for processing {@link GraphQLRequest}s and returning {@link GraphQLResponse}s using passed
 * configured instance of {@link GraphQL}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. 2022
 */
@Slf4j
public class GraphQLWebHandler extends EndpointHandler<GraphQLEndpointExecutionContext> {

	/**
	 * Set of GraphQL exceptions that are caused by invalid user input and thus shouldn't return server error.
	 */
	private static final Set<Class<? extends GraphQLException>> GRAPHQL_USER_ERRORS = Set.of(
		CoercingSerializeException.class,
		CoercingParseValueException.class,
		NonNullableValueCoercedAsNullException.class,
		InputMapDefinesTooManyFieldsException.class,
		UnknownOperationException.class
	);

	@Nonnull
	private final Evita evita;
	@Nonnull
	private final ObjectMapper objectMapper;
	@Nonnull
	private final ExternalApiTracingContext<Object> tracingContext;
	@Nonnull
	private final GraphQLInstanceType instanceType;
	@Nonnull
	private final AtomicReference<GraphQL> graphQL;

	public GraphQLWebHandler(
		@Nonnull Evita evita,
		@Nonnull HeaderOptions headers,
		@Nonnull ObjectMapper objectMapper,
		@Nonnull GraphQLInstanceType instanceType,
		@Nonnull AtomicReference<GraphQL> graphQL
	) {
		this.evita = evita;
		this.objectMapper = objectMapper;
		this.tracingContext = ExternalApiTracingContextProvider.getContext(headers);
		this.instanceType = instanceType;
		this.graphQL = graphQL;
	}

	@Nonnull
	@Override
	public HttpResponse serve(@Nonnull ServiceRequestContext ctx, @Nonnull HttpRequest req) {
		return instrumentRequest(ctx, req);
	}

	@Nonnull
	@Override
	public Set<HttpMethod> getSupportedHttpMethods() {
		return Set.of(HttpMethod.POST);
	}

	@Nonnull
	@Override
	public Set<String> getSupportedRequestContentTypes() {
		return Set.of(MimeTypes.APPLICATION_JSON);
	}

	@Nonnull
	@Override
	public LinkedHashSet<String> getSupportedResponseContentTypes() {
		final LinkedHashSet<String> mediaTypes = createLinkedHashSet(2);
		mediaTypes.add(GraphQLMimeTypes.APPLICATION_GRAPHQL_RESPONSE_JSON);
		mediaTypes.add(MimeTypes.APPLICATION_JSON);
		return mediaTypes;
	}

	@Nonnull
	@Override
	protected GraphQLEndpointExecutionContext createExecutionContext(@Nonnull HttpRequest httpRequest) {
		return new GraphQLEndpointExecutionContext(
			httpRequest,
			this.evita,
			new ExecutedEvent(this.instanceType)
		);
	}

	@Override
	@Nonnull
	protected CompletableFuture<EndpointResponse> doHandleRequest(@Nonnull GraphQLEndpointExecutionContext executionContext) {
		return parseRequestBody(executionContext, GraphQLRequest.class)
			.thenApply(graphQLRequest -> {
				executionContext.requestExecutedEvent().finishInputDeserialization();
				final GraphQLResponse<?> graphQLResponse = this.tracingContext.executeWithinBlock(
					"GraphQL",
					executionContext.httpRequest(),
					() -> executeRequest(executionContext, graphQLRequest)
				);
				return new SuccessEndpointResponse(graphQLResponse);
			});
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
	protected <T> CompletableFuture<T> parseRequestBody(@Nonnull GraphQLEndpointExecutionContext executionContext, @Nonnull Class<T> dataClass) {
		try {
			return readRawRequestBody(executionContext).thenApply(body -> {
				try {
					return this.objectMapper.readValue(body, dataClass);
				} catch (IOException e) {
					if (e.getCause() instanceof EvitaInternalError internalError) {
						throw internalError;
					} else if (e.getCause() instanceof EvitaInvalidUsageException invalidUsageException) {
						throw invalidUsageException;
					}
					throw new HttpExchangeException(HttpStatus.UNSUPPORTED_MEDIA_TYPE.code(), "Invalid request body format. Expected JSON object.");
				}
			});
		} catch (EvitaInternalError | EvitaInvalidUsageException e) {
			throw new HttpExchangeException(HttpStatus.UNSUPPORTED_MEDIA_TYPE.code(), "Invalid request body format. Expected JSON object.");
		}
	}

	@Override
	protected void writeResponse(@Nonnull GraphQLEndpointExecutionContext executionContext, @Nonnull HttpResponseWriter responseWriter, @Nonnull Object response, @Nonnull EventLoop eventExecutors) {
		try {
			responseWriter.write(HttpData.copyOf(this.objectMapper.writeValueAsBytes(response)));
		} catch (IOException e) {
			throw new GraphQLInternalError(
				"Could not serialize GraphQL API response to JSON: " + e.getMessage(),
				"Could not provide GraphQL API response.",
				e
			);
		} finally {
			executionContext.requestExecutedEvent().finishResultSerialization();
		}
	}

	/**
	 * Process every request with tracing context, so we can classify it in evitaDB.
	 */
	@Nonnull
	private HttpResponse instrumentRequest(@Nonnull ServiceRequestContext ctx, @Nonnull HttpRequest req) {
		return Objects.requireNonNull(
			this.tracingContext.executeWithinBlock(
				"GraphQL",
				req,
				() -> super.serve(ctx, req)
			)
		);
	}

	@Nonnull
	private GraphQLResponse<?> executeRequest(@Nonnull GraphQLEndpointExecutionContext executionContext,
	                                          @Nonnull GraphQLRequest graphQLRequest) {
		try {
			final ExecutionInput executionInput = graphQLRequest.toExecutionInput(executionContext);
			final ExecutionResult result = this.graphQL.get()
				.executeAsync(executionInput)
				.join();

			// trying to close potential tracing block (created by OperationTracingInstrumentation) in the original thread
			final TracingBlockReference blockReference = executionInput.getGraphQLContext().get(GraphQLContextKey.OPERATION_TRACING_BLOCK);
			if (blockReference != null) {
				blockReference.close();
			}

			return GraphQLResponse.fromExecutionResult(result);
		} catch (CompletionException e) {
			final Throwable cause = e.getCause();
			if (cause instanceof TimeoutException) {
				throw new HttpExchangeException(HttpStatus.GATEWAY_TIMEOUT.code(), "Could not complete GraphQL request. Process timed out.");
			} else if (GRAPHQL_USER_ERRORS.contains(cause.getClass())) {
				throw new GraphQLInvalidUsageException("Invalid GraphQL API request: " + cause.getMessage());
			} else if (cause instanceof GraphQLException graphQLException) {
				throw new GraphQLInternalError(
					"Internal GraphQL API error: " + graphQLException.getMessage(),
					"Internal GraphQL API error.",
					graphQLException
				);
			} else if (cause instanceof RuntimeException) {
				// borrowed from graphql.GraphQL.execute(graphql.ExecutionInput)
				throw (RuntimeException) cause;
			} else {
				throw e;
			}
		} catch (RuntimeException e) {
			// if there is something weird going on, at least wrap it into our own exception
			throw new GraphQLInternalError(
				"Internal GraphQL API error: " + e.getMessage(),
				"Internal GraphQL API error.",
				e
			);
		}
	}

}
