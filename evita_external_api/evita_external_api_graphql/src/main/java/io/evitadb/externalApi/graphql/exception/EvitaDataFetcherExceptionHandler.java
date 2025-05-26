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

package io.evitadb.externalApi.graphql.exception;

import graphql.GraphQLContext;
import graphql.execution.DataFetcherExceptionHandler;
import graphql.execution.DataFetcherExceptionHandlerParameters;
import graphql.execution.DataFetcherExceptionHandlerResult;
import io.evitadb.api.observability.trace.TracingBlockReference;
import io.evitadb.exception.EvitaError;
import io.evitadb.externalApi.graphql.api.catalog.GraphQLContextKey;
import io.evitadb.externalApi.graphql.metric.event.request.ExecutedEvent;
import io.evitadb.externalApi.graphql.metric.event.request.ExecutedEvent.ResponseStatus;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Handles all exceptions occurred during query executions. Internal errors of GraphQL are logged and exceptions
 * from external libraries are treated like internal errors.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@Slf4j
public class EvitaDataFetcherExceptionHandler implements DataFetcherExceptionHandler {

	@Override
	public CompletableFuture<DataFetcherExceptionHandlerResult> handleException(DataFetcherExceptionHandlerParameters handlerParameters) {
		final Throwable exception = unwrap(handlerParameters.getException());

		final EvitaError evitaError;
		// wrap any exception occurred inside some external code which was not handled before
		if (exception instanceof EvitaError) {
			evitaError = (EvitaError) exception;
		} else {
			evitaError = new GraphQLInternalError(
				"Unexpected internal evitaDB GraphQL API error occurred: " + exception.getMessage(),
				"Unexpected internal evitaDB GraphQL API error occurred.",
				exception
			);
		}

		// log any GraphQL internal errors that Evita cannot handle because they are outside of Evita execution
		if (evitaError instanceof final GraphQLInternalError graphQLInternalError) {
			log.error(
				"Internal evitaDB GraphQL API error occurred in {}: {}",
				graphQLInternalError.getErrorCode(),
				graphQLInternalError.getPrivateMessage(),
				graphQLInternalError
			);
		}

		final GraphQLContext graphQlContext = handlerParameters.getDataFetchingEnvironment().getGraphQlContext();

		final TracingBlockReference operationBlockReference = graphQlContext.get(GraphQLContextKey.OPERATION_TRACING_BLOCK);
		operationBlockReference.setError((Throwable) evitaError);

		final UUID sourceQueryRecordingId = graphQlContext.get(GraphQLContextKey.TRAFFIC_SOURCE_QUERY_RECORDING_ID);
		if (sourceQueryRecordingId != null) {
			List<EvitaError> exceptions = graphQlContext.get(GraphQLContextKey.TRAFFIC_SOURCE_QUERY_RECORDING_EXCEPTIONS);
			if (exceptions == null) {
				exceptions = new LinkedList<>();
				graphQlContext.put(GraphQLContextKey.TRAFFIC_SOURCE_QUERY_RECORDING_EXCEPTIONS, exceptions);
			}
			exceptions.add(evitaError);
		}

		final EvitaGraphQLError error = new EvitaGraphQLError(
			evitaError.getPublicMessage(),
			handlerParameters.getSourceLocation(),
			handlerParameters.getPath().toList(),
			Map.of(
				"errorCode", evitaError.getErrorCode()
			)
		);

		final ExecutedEvent requestExecutedEvent = graphQlContext.get(GraphQLContextKey.METRIC_EXECUTED_EVENT);
		if (requestExecutedEvent != null) {
			requestExecutedEvent.provideResponseStatus(ResponseStatus.ERROR);
		}

		return CompletableFuture.completedFuture(DataFetcherExceptionHandlerResult.newResult()
			.error(error)
			.build());
	}

	/**
	 * Called to unwrap an exception to a more suitable cause if required.
	 *
	 * @param exception the exception to unwrap
	 *
	 * @return the suitable exception
	 */
	protected Throwable unwrap(Throwable exception) {
		if (exception.getCause() != null && exception instanceof CompletionException) {
			return exception.getCause();
		}
		return exception;
	}
}
