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
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.externalApi.utils;

import io.evitadb.api.observability.trace.TracingContext;
import io.evitadb.api.observability.trace.TracingContext.SpanAttribute;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Base tracing context interface for external APIs. Its implementations should bridge trace requests from APIs to the
 * evitaDB core to allow adding additional inner traces.
 *
 * @param <C> type of the context, should be either {@link com.linecorp.armeria.common.RequestHeaders} for JSON APIs (REST, GraphQL) or
 *            gRPC Metadata type.
 * @see TracingContext
 */
public interface ExternalApiTracingContext<C> {
	/**
	 * Format of the client ID used by the server.
	 */
	String SERVER_CLIENT_ID_FORMAT = "%s|%s";
	/**
	 * Default client ID used when the client does not send any.
	 */
	String DEFAULT_CLIENT_ID = "unknown";

	/**
	 * Name of the ContextKey used by tracing library.
	 */
	String CLIENT_ID_CONTEXT_KEY_NAME = "client_id";

	/**
	 * Regex identifying characters that are not allowed inside client ID.
	 */
	Pattern ID_FORBIDDEN_CHARACTERS = Pattern.compile("[^a-zA-Z0-9\\-_.]");

	/**
	 * Sanitizes client-sent ID.
	 */
	@Nullable
	private static String sanitizeId(@Nullable String idFromClient) {
		if (idFromClient == null) {
			return null;
		}
		return ID_FORBIDDEN_CHARACTERS.matcher(idFromClient)
			.replaceAll("-");
	}

	/**
	 * Sets the passed task name and attributes to the trace BEFORE the lambda is executed. Within the method,
	 * the lambda with passed logic will be traced and properly executed.
	 */
	void executeWithinBlock(@Nonnull String protocolName, @Nonnull C context, @Nonnull Runnable runnable, @Nullable SpanAttribute... attributes);

	/**
	 * Sets the passed task name and attributes to the trace BEFORE the lambda is executed. Within the method,
	 * the lambda with passed logic will be traced and properly executed.
	 */
	<T> T executeWithinBlock(@Nonnull String protocolName, @Nonnull C context, @Nonnull Supplier<T> lambda, @Nullable SpanAttribute... attributes);

	/**
	 * Sets the passed task name and attributes to the trace AFTER the lambda is executed. Within the method,
	 * the lambda with passed logic will be traced and properly executed. After the method successfully finishes,
	 * the attributes will be set to the trace. The attributes may take advantage of the data computed in the lambda
	 * itself.
	 */
	void executeWithinBlock(@Nonnull String protocolName, @Nonnull C context, @Nonnull Runnable runnable, @Nullable Supplier<SpanAttribute[]> attributes);

	/**
	 * Sets the passed task name and attributes to the trace AFTER the lambda is executed. Within the method,
	 * the lambda with passed logic will be traced and properly executed. After the method successfully finishes,
	 * the attributes will be set to the trace. The attributes may take advantage of the data computed in the lambda
	 * itself.
	 */
	<T> T executeWithinBlock(@Nonnull String protocolName, @Nonnull C context, @Nonnull Supplier<T> lambda, @Nullable Supplier<SpanAttribute[]> attributes);

	/**
	 * Executes the given lambda within the tracing block. It requires the client ID to be provided by the client and his
	 * network address as well for the proper client identification formatting. Passed context is used as a decider for
	 * the target implementation to use, whether the request originates from a JSON based API or a gRPC one.
	 */
	void executeWithinBlock(@Nonnull String protocolName, @Nonnull C context, @Nonnull Runnable runnable);

	/**
	 * Executes the given lambda within the tracing block. It requires the client ID to be provided by the client and his
	 * network address as well for the proper client identification formatting. Passed context is used as a decider for
	 * the target implementation to use, whether the request originates from a JSON based API or a gRPC one.
	 */
	@Nullable
	<T> T executeWithinBlock(@Nonnull String protocolName, @Nonnull C context, @Nonnull Supplier<T> lambda);

	/**
	 * Converts client-sent ID to internal client ID.
	 *
	 * @param clientIdFromClient client-sent client ID
	 * @return more detailed client ID for internal use
	 */
	@Nonnull
	default String convertClientId(@Nonnull String protocolName, @Nullable String clientIdFromClient) {
		return String.format(
			SERVER_CLIENT_ID_FORMAT,
			protocolName,
			Optional.ofNullable(clientIdFromClient).map(ExternalApiTracingContext::sanitizeId).orElse(DEFAULT_CLIENT_ID)
		);
	}
}
