/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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

package io.evitadb.externalApi.observability.trace;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestHeaders;
import io.evitadb.api.observability.trace.TracingContext;
import io.evitadb.api.observability.trace.TracingContext.SpanAttribute;
import io.evitadb.api.query.head.Label;
import io.evitadb.externalApi.configuration.HeaderOptions;
import io.evitadb.externalApi.utils.ExternalApiTracingContext;
import io.netty.util.AsciiString;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import lombok.Setter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static java.util.Optional.ofNullable;

/**
 * Implementation of {@link ExternalApiTracingContext} for JSON APIs (REST, GraphQL).
 *
 * @author Tomáš Pozler, FG Forrest a.s. (c) 2024
 */
public class JsonApiTracingContext implements ExternalApiTracingContext<HttpRequest> {

	/**
	 * Getter for extracting information from Armeria's {@link RequestHeaders}.
	 */
	@Nonnull
	private static final TextMapGetter<RequestHeaders> CONTEXT_GETTER =
		new TextMapGetter<>() {
			@Override
			public Iterable<String> keys(RequestHeaders headers) {
				return headers.names()
					.stream()
					.map(AsciiString::toString)
					.collect(Collectors.toList());
			}

			@Override
			public String get(@Nullable RequestHeaders headers, @Nonnull String s) {
				return ofNullable(headers)
					.map(it -> it.get(s))
					.orElse(null);
			}
		};

	/**
	 * Header configuration. Initialized with default settings that gets overwritten once the context is prepared.
	 */
	@Setter private HeaderOptions headerOptions = HeaderOptions.builder().build();
	/**
	 * Tracing context for handling the actual tracing logic.
	 */
	private final TracingContext tracingContext;

	public JsonApiTracingContext(
		@Nonnull TracingContext tracingContext
	) {
		this.tracingContext = tracingContext;
	}

	@Override
	public void configureHeaders(@Nonnull HeaderOptions headerOptions) {
		this.headerOptions = headerOptions;
	}

	@Override
	public void executeWithinBlock(
		@Nonnull String protocolName,
		@Nonnull HttpRequest context,
		@Nonnull Runnable runnable,
		@Nullable SpanAttribute... attributes
	) {
		if (!OpenTelemetryTracerSetup.isTracingEnabled()) {
			runnable.run();
			return;
		}
		try (Scope ignored = extractContextFromHeaders(protocolName, context).makeCurrent()) {
			this.tracingContext.executeWithinBlock(
				protocolName,
				runnable,
				attributes
			);
		}
	}

	@Override
	public <T> T executeWithinBlock(
		@Nonnull String protocolName,
		@Nonnull HttpRequest context,
		@Nonnull Supplier<T> lambda,
		@Nullable SpanAttribute... attributes
	) {
		if (!OpenTelemetryTracerSetup.isTracingEnabled()) {
			return lambda.get();
		}
		try (Scope ignored = extractContextFromHeaders(protocolName, context).makeCurrent()) {
			return this.tracingContext.executeWithinBlock(
				protocolName,
				lambda,
				attributes
			);
		}
	}

	@Override
	public void executeWithinBlock(
		@Nonnull String protocolName,
		@Nonnull HttpRequest context,
		@Nonnull Runnable runnable,
		@Nullable Supplier<SpanAttribute[]> attributes
	) {
		if (!OpenTelemetryTracerSetup.isTracingEnabled()) {
			runnable.run();
			return;
		}
		try (Scope ignored = extractContextFromHeaders(protocolName, context).makeCurrent()) {
			this.tracingContext.executeWithinBlock(
				protocolName,
				runnable,
				attributes
			);
		}
	}

	@Override
	public <T> T executeWithinBlock(
		@Nonnull String protocolName,
		@Nonnull HttpRequest context,
		@Nonnull Supplier<T> lambda,
		@Nullable Supplier<SpanAttribute[]> attributes
	) {
		if (!OpenTelemetryTracerSetup.isTracingEnabled()) {
			return lambda.get();
		}
		try (Scope ignored = extractContextFromHeaders(protocolName, context).makeCurrent()) {
			return this.tracingContext.executeWithinBlock(
				protocolName,
				lambda,
				attributes
			);
		}
	}

	@Override
	public void executeWithinBlock(@Nonnull String protocolName, @Nonnull HttpRequest context, @Nonnull Runnable runnable) {
		if (!OpenTelemetryTracerSetup.isTracingEnabled()) {
			runnable.run();
			return;
		}
		try (Scope ignored = extractContextFromHeaders(protocolName, context).makeCurrent()) {
			this.tracingContext.executeWithinBlock(
				protocolName,
				runnable
			);
		}
	}

	@Nullable
	@Override
	public <T> T executeWithinBlock(
		@Nonnull String protocolName,
		@Nonnull HttpRequest context,
		@Nonnull Supplier<T> lambda
	) {
		final RequestHeaders headers = context.headers();
		final String clientIpAddress = headers.get(HttpHeaderNames.X_FORWARDED_FOR);
		final String clientUri = this.headerOptions.forwardedUri()
			.stream()
			.map(headers::get)
			.filter(Objects::nonNull)
			.findFirst()
			.orElse(null);
		final Label[] labels = this.headerOptions.forwardedFor()
			.stream()
			.flatMap(name -> headers.getAll(name).stream())
			.map(header -> {
				final int index = header.indexOf('=');
				if (index < 0) {
					return null;
				} else {
					return new Label(header.substring(0, index), header.substring(index + 1));
				}
			})
			.filter(Objects::nonNull)
			.toArray(Label[]::new);

		if (!OpenTelemetryTracerSetup.isTracingEnabled()) {
			return TracingContext.executeWithClientContext(
				clientIpAddress, clientUri, labels,
				lambda
			);
		}
		try (Scope ignored = extractContextFromHeaders(protocolName, context).makeCurrent()) {
			return TracingContext.executeWithClientContext(
				clientIpAddress, clientUri, labels,
				() -> this.tracingContext.executeWithinBlock(
					protocolName,
					lambda
				)
			);
		}
	}

	/**
	 * Method for extracting information from the context received via OpenTelemetry's Context Propagation mechanism. Besides
	 * the extracted traceId, the clientId is also extracted and injected into the received context.
	 */
	@Nonnull
	private Context extractContextFromHeaders(@Nonnull String protocolName, @Nonnull HttpRequest exchange) {
		final RequestHeaders headers = exchange.headers();
		final Context context = OpenTelemetryTracerSetup.getOpenTelemetry()
			.getPropagators()
			.getTextMapPropagator()
			.extract(Context.current(), headers, CONTEXT_GETTER);
		final String clientId = convertClientId(
			protocolName,
			this.headerOptions.clientId()
				.stream()
				.map(headers::get)
				.filter(Objects::nonNull)
				.findFirst()
				.orElse(null)
		);
		return context.with(OpenTelemetryTracerSetup.CONTEXT_KEY, clientId);
	}
}
