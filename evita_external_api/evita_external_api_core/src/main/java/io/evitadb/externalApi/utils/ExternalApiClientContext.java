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

package io.evitadb.externalApi.utils;

import io.evitadb.api.ClientContext;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.net.SocketAddress;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Bridge between client-send IDs and internal evitaDB client context for external APIs.
 * This exists because we want to sanitize client inputs and log more detailed info about clients.
 * This bridge takes client-sent IDs, transforms them and delegates them to the internal {@link ClientContext}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@RequiredArgsConstructor
public abstract class ExternalApiClientContext  {

	private static final String SERVER_CLIENT_ID_FORMAT = "%s|%s|%s";
	private static final String DEFAULT_CLIENT_ID = "unknown";

	private static final Pattern ID_FORBIDDEN_CHARACTERS = Pattern.compile("[^a-zA-Z0-9\\-_.]");

	/**
	 * Client context of the underlying evitaDB server to which all calls are delegated.
	 */
	@Nonnull private final ClientContext internalClientContext;

	/**
	 * Method executes the `lambda` function within the scope of client-defined context.
	 *
	 * @param clientAddress network address of the client
	 * @param clientId      string that will be constant per a connected client,
	 *                      example values: Next.JS Middleware, evitaDB console etc.
	 * @param requestId     a randomized token - preferably UUID that will connect all queries and mutations issued by the client
	 *                      in a single unit of work, that is controlled by the client (not the server); there might be different
	 *                      request ids within single evita session and also there might be same request id among multiple different
	 *                      evita sessions
	 * @param lambda        function to be executed
	 */
	public void executeWithClientAndRequestId(@Nonnull SocketAddress clientAddress,
	                                          @Nullable String clientId,
	                                          @Nullable String requestId,
	                                          @Nonnull Runnable lambda) {
		internalClientContext.executeWithClientAndRequestId(
			convertClientId(clientAddress, clientId),
			sanitizeId(requestId),
			lambda
		);
	}

	/**
	 * Method executes the `lambda` function within the scope of client-defined context and returns its result.
	 *
	 * @param clientAddress network address of the client
	 * @param clientId      string that will be constant per a connected client,
	 *                      example values: Next.JS Middleware, evitaDB console etc.
	 * @param requestId     a randomized token - preferably UUID that will connect all queries and mutations issued by the client
	 *                      in a single unit of work, that is controlled by the client (not the server); there might be different
	 *                      request ids within single evita session and also there might be same request id among multiple different
	 *                      evita sessions
	 * @param lambda        function to be executed
	 * @return result of the lambda function
	 */
	public <T> T executeWithClientAndRequestId(@Nonnull SocketAddress clientAddress,
	                                           @Nullable String clientId,
	                                           @Nullable String requestId,
	                                           @Nonnull Supplier<T> lambda) {
		return internalClientContext.executeWithClientAndRequestId(
			convertClientId(clientAddress, clientId),
			sanitizeId(requestId),
			lambda
		);
	}

	/**
	 * Returns short name (abbreviation) of API implementing this external API client context. Used to distinguish
	 * between multiple external APIs.
	 */
	@Nonnull
	protected abstract String getProtocol();

	/**
	 * Sanitizes client-sent ID.
	 */
	@Nullable
	private String sanitizeId(@Nullable String idFromClient) {
		if (idFromClient == null) {
			return null;
		}
		return ID_FORBIDDEN_CHARACTERS.matcher(idFromClient)
			.replaceAll("-");
	}

	/**
	 * Converts client-sent ID to internal client ID.
	 *
	 * @param clientAddress network address of the client
	 * @param clientIdFromClient client-sent client ID
	 * @return more detailed client ID for internal use
	 */
	@Nonnull
	private String convertClientId(@Nonnull SocketAddress clientAddress, @Nullable String clientIdFromClient) {
		return String.format(
			SERVER_CLIENT_ID_FORMAT,
			getProtocol(),
			clientAddress,
			Optional.ofNullable(clientIdFromClient).map(this::sanitizeId).orElse(DEFAULT_CLIENT_ID)
		);
	}
}
