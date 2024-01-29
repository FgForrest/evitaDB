/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

import org.slf4j.MDC;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.net.SocketAddress;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Base tracing context interface for external APIs. Its implementations should bridge trace requests from APIs to the
 * evitaDB core to allow adding additional inner traces.
 *
 * @see io.evitadb.api.trace.TracingContext
 *
 * @param <C> type of the context, should be either {@link io.undertow.util.HeaderMap} for JSON APIs (REST, GraphQL) or
 * gRPC Metadata type.
 */
public interface ExternalApiTracingContext<C> {
	/**
	 * Format of the client ID used by the server.
	 */
	String SERVER_CLIENT_ID_FORMAT = "%s|%s|%s";
	/**
	 * Default client ID used when the client does not send any.
	 */
	String DEFAULT_CLIENT_ID = "unknown";
	/**
	 * Name of property representing the client identifier in the {@link MDC}.
	 */
	String MDC_CLIENT_ID_PROPERTY = "clientId";
	/**
	 * Name of property representing the trace identifier in the {@link MDC}.
	 */
	String MDC_TRACE_ID_PROPERTY = "traceId";

	/**
	 * Name of the ContextKey used by tracing library.
	 */
	String CLIENT_ID_CONTEXT_KEY_NAME = "client_id";

	/**
	 * Regex identifying characters that are not allowed inside client ID.
	 */
	Pattern ID_FORBIDDEN_CHARACTERS = Pattern.compile("[^a-zA-Z0-9\\-_.]");

	/**
	 * Executes the given lambda within the tracing block. It requires the client ID to be provided by the client and his
	 * network address as well for the proper client identification formatting. Passed context is used as a decider for
	 * the target implementation to use, whether the request originates from a JSON based API or a gRPC one.
	 */
	default void executeWithinBlock(@Nonnull String protocolName, @Nonnull SocketAddress sourceAddress, @Nonnull C context, @Nullable Map<String, Object> attributes, @Nonnull Runnable runnable) {
	}

	/**
	 * Executes the given lambda within the tracing block. It requires the client ID to be provided by the client and his
	 * network address as well for the proper client identification formatting. Passed context is used as a decider for
	 * the target implementation to use, whether the request originates from a JSON based API or a gRPC one.
	 */
	@Nullable
	default <T> T executeWithinBlock(@Nonnull String protocolName, @Nonnull SocketAddress sourceAddress, @Nonnull C context, @Nullable Map<String, Object> attributes, @Nonnull Supplier<T> lambda) {
		return null;
	}

	/**
	 * Executes the given lambda within the tracing block. It requires the client ID to be provided by the client and his
	 * network address as well for the proper client identification formatting. Passed context is used as a decider for
	 * the target implementation to use, whether the request originates from a JSON based API or a gRPC one.
	 */
	default void executeWithinBlock(@Nonnull String protocolName, @Nonnull SocketAddress sourceAddress, @Nonnull C context, @Nonnull Runnable runnable) {
		executeWithinBlock(protocolName, sourceAddress, context, null, runnable);
	}

	/**
	 * Executes the given lambda within the tracing block. It requires the client ID to be provided by the client and his
	 * network address as well for the proper client identification formatting. Passed context is used as a decider for
	 * the target implementation to use, whether the request originates from a JSON based API or a gRPC one.
	 */
	@Nullable
	default <T> T executeWithinBlock(@Nonnull String protocolName, @Nonnull SocketAddress sourceAddress, @Nonnull C context, @Nonnull Supplier<T> lambda) {
		return executeWithinBlock(protocolName, sourceAddress, context, null, lambda);
	}

	/**
	 * Returns server interceptor for the given type. Expected usage is from gRPC Server.
	 */
	@Nullable
	default <T> T getServerInterceptor(Class<T> type) {
		return null;
	}

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
	 * @param clientAddress      network address of the client
	 * @param clientIdFromClient client-sent client ID
	 * @return more detailed client ID for internal use
	 */
	@Nonnull
	default String convertClientId(@Nonnull String protocolName, @Nonnull SocketAddress clientAddress, @Nullable String clientIdFromClient) {
		return String.format(
			SERVER_CLIENT_ID_FORMAT,
			protocolName,
			clientAddress,
			Optional.ofNullable(clientIdFromClient).map(this::sanitizeId).orElse(DEFAULT_CLIENT_ID)
		);
	}

	/**
	 * Initializes MDC with the given client ID and trace ID. It is used for logging purposes.
	 */
	static void initMdc(@Nonnull String clientId, @Nonnull String traceId) {
		MDC.remove(MDC_CLIENT_ID_PROPERTY);
		MDC.put(MDC_CLIENT_ID_PROPERTY, clientId);

		MDC.remove(MDC_TRACE_ID_PROPERTY);
		MDC.put(MDC_TRACE_ID_PROPERTY, traceId);
	}
}
