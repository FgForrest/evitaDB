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

package io.evitadb.externalApi.observability.trace;

import io.evitadb.api.trace.TracingContext;
import io.evitadb.externalApi.utils.ExternalApiTracingContext;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.HeaderMap;
import io.undertow.util.HttpString;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Implementation of {@link ExternalApiTracingContext} for JSON APIs (REST, GraphQL).
 *
 * @author Tomáš Pozler, FG Forrest a.s. (c) 2024
 */
public class JsonApiTracingContext implements ExternalApiTracingContext<HttpServerExchange> {
	private static final String CLIENT_ID_HEADER = "X-EvitaDB-ClientID";
	/**
	 * Getter for extracting information from Undertow {@link HeaderMap}.
	 */
	@Nonnull
	private static final TextMapGetter<HeaderMap> CONTEXT_GETTER =
		new TextMapGetter<>() {
			@Override
			public Iterable<String> keys(HeaderMap headers) {
				return headers.getHeaderNames()
					.stream()
					.map(HttpString::toString)
					.collect(Collectors.toList());
			}

			@Override
			public String get(@Nullable HeaderMap headers, @Nonnull String s) {
				assert headers != null;
				return headers.getFirst(s);
			}
		};
	private final TracingContext tracingContext;

	public JsonApiTracingContext(@Nonnull TracingContext tracingContext) {
		this.tracingContext = tracingContext;
	}

	/**
	 * Executes the given lambda within a tracing block.
	 *
	 * @param protocolName The name of the protocol.
	 * @param exchange     The HTTP server exchange.
	 * @param attributes   Optional attributes to pass into the lambda.
	 * @param lambda       The lambda to execute.
	 */
	@Override
	public void executeWithinBlock(
		@Nonnull String protocolName,
		@Nonnull HttpServerExchange exchange,
		@Nullable Map<String, Object> attributes,
		@Nonnull Runnable lambda
	) {
		executeWithinBlock(protocolName, exchange, attributes, () -> {
			lambda.run();
			return null;
		});
	}

	/**
	 * Executes the given lambda within a tracing block.
	 *
	 * @param protocolName The name of the protocol.
	 * @param exchange     The HTTP server exchange.
	 * @param attributes   Optional attributes to pass into the lambda.
	 * @param lambda       The lambda to execute.
	 * @return The value returned by the lambda.
	 * @param <T> The type of the value returned by the lambda.
	 */
	@Override
	public <T> T executeWithinBlock(
		@Nonnull String protocolName,
		@Nonnull HttpServerExchange exchange,
		@Nullable Map<String, Object> attributes,
		@Nonnull Supplier<T> lambda
	) {
		if (!OpenTelemetryTracerSetup.isTracingEnabled()) {
			return lambda.get();
		}
		try (Scope ignored = extractContextFromHeaders(protocolName, exchange).makeCurrent()) {
			return tracingContext.executeWithinBlock(
				protocolName,
				attributes,
				lambda
			);
		}
	}

	/**
	 * Method for extracting information from the context received via OpenTelemetry's Context Propagation mechanism. Besides
	 * the extracted traceId, the clientId is also extracted and injected into the received context.
	 */
	@Nonnull
	private Context extractContextFromHeaders(@Nonnull String protocolName, @Nonnull HttpServerExchange exchange) {
		final HeaderMap headers = exchange.getRequestHeaders();
		final Context context = OpenTelemetryTracerSetup.getOpenTelemetry()
			.getPropagators()
			.getTextMapPropagator()
			.extract(Context.current(), headers, CONTEXT_GETTER);
		final String clientId = convertClientId(
			protocolName,
			headers.getFirst(CLIENT_ID_HEADER)
		);
		return context.with(OpenTelemetryTracerSetup.CONTEXT_KEY, clientId);
	}
}
