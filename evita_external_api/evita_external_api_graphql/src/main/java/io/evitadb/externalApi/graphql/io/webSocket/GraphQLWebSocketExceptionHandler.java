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

package io.evitadb.externalApi.graphql.io.webSocket;

import io.evitadb.exception.EvitaError;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.exception.ExternalApiInternalError;
import io.evitadb.externalApi.graphql.GraphQLProvider;
import io.evitadb.externalApi.graphql.exception.EvitaGraphQLError;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;

/**
 * Exception handler for GraphQL WebSocket connections. This class provides centralized
 * exception handling for WebSocket-based GraphQL operations, ensuring proper error
 * responses and connection management when exceptions occur during WebSocket communication.
 * It handles both GraphQL-specific errors and general WebSocket protocol errors.
 *
 * @author Lukáš Hornych, 2023
 */
@Slf4j
public class GraphQLWebSocketExceptionHandler {

	@Nonnull
	public GraphQLWebSocketCloseMessage toCloseMessage(@Nonnull Throwable exception) {
		final EvitaError evitaError = convertUnknownExceptionIntoEvitaError(exception);
		if (evitaError instanceof EvitaInvalidUsageException) {
			return GraphQLWebSocketCloseMessage.invalidMessage(constructPublicMessage(evitaError));
		} else {
			return GraphQLWebSocketCloseMessage.internalServerError();
		}
	}

	@Nonnull
	public EvitaGraphQLError toGraphQLError(@Nonnull Throwable exception) {
		final EvitaError evitaError = convertUnknownExceptionIntoEvitaError(exception);
		if (evitaError instanceof EvitaInvalidUsageException) {
			return new EvitaGraphQLError(constructPublicMessage(evitaError));
		} else {
			return new EvitaGraphQLError("Internal server error occurred.");
		}
	}

	@Nonnull
	private EvitaError convertUnknownExceptionIntoEvitaError(@Nonnull Throwable exception) {
		final EvitaError evitaError;
		if (exception instanceof EvitaError) {
			evitaError = (EvitaError) exception;
		} else {
			// wrap any exception occurred inside some external code which was not handled before
			evitaError = new ExternalApiInternalError(
				"Unexpected internal Evita " + GraphQLProvider.CODE + " WebSocket error occurred: " + exception.getMessage(),
				"Unexpected internal Evita " + GraphQLProvider.CODE + " WebSocket error occurred.",
				exception
			);
		}

		if (evitaError instanceof final ExternalApiInternalError externalApiInternalError) {
			// log any API internal errors that Evita cannot handle because they are outside of Evita execution
			log.error(
				"Internal Evita " + GraphQLProvider.CODE + " WebSocket API error occurred in {}: {}",
				externalApiInternalError.getErrorCode(),
				externalApiInternalError.getPrivateMessage(),
				externalApiInternalError
			);
		}

		return evitaError;
	}

	@Nonnull
	private static String constructPublicMessage(@Nonnull EvitaError evitaError) {
		return evitaError.getErrorCode() + ":" + evitaError.getPublicMessage();
	}
}
