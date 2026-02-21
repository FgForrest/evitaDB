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
import io.evitadb.api.observability.trace.TracingBlockReference;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
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
		final ClientMetadata metadata =
			extractClientMetadata(context.headers());

		if (!OpenTelemetryTracerSetup.isTracingEnabled()) {
			return TracingContext.executeWithClientContext(
				metadata.clientIpAddress(),
				metadata.clientUri(),
				metadata.labels(),
				lambda
			);
		}
		try (Scope ignored = extractContextFromHeaders(protocolName, context).makeCurrent()) {
			return TracingContext.executeWithClientContext(
				metadata.clientIpAddress(),
				metadata.clientUri(),
				metadata.labels(),
				() -> this.tracingContext.executeWithinBlock(
					protocolName,
					lambda
				)
			);
		}
	}

	/**
	 * Async variant of
	 * {@link #executeWithinBlock(String, HttpRequest, Supplier)}.
	 * Opens the tracing scope and client context, calls the async
	 * supplier (which returns a `CompletableFuture`), detaches the
	 * scope immediately on the calling thread, then ends the span
	 * when the future completes.
	 *
	 * @param protocolName the protocol name for tracing
	 *                     (e.g. "GraphQL")
	 * @param context      the HTTP request containing tracing headers
	 * @param asyncLambda  supplier that starts async work and returns
	 *                     a future
	 * @param <T>          the result type of the async operation
	 * @return a future that completes with tracing properly ended
	 */
	@Nonnull
	@Override
	public <T> CompletableFuture<T> executeWithinBlockAsync(
		@Nonnull String protocolName,
		@Nonnull HttpRequest context,
		@Nonnull Supplier<CompletableFuture<T>> asyncLambda
	) {
		final ClientMetadata metadata =
			extractClientMetadata(context.headers());

		if (!OpenTelemetryTracerSetup.isTracingEnabled()) {
			// no tracing -- executeWithClientContext sets/clears MDC synchronously
			return TracingContext.executeWithClientContext(
				metadata.clientIpAddress(),
				metadata.clientUri(),
				metadata.labels(),
				asyncLambda
			);
		}

		// All scope/thread-local operations happen synchronously on the calling thread.
		// Only span.end() is deferred to when the async future completes.
		try (Scope ignored = extractContextFromHeaders(protocolName, context).makeCurrent()) {
			return TracingContext.executeWithClientContext(
				metadata.clientIpAddress(),
				metadata.clientUri(),
				metadata.labels(),
				() -> {
					// Create span and open scope (this thread)
					final TracingBlockReference block = this.tracingContext.createAndActivateBlock(protocolName);
					final CompletableFuture<T> future;
					try {
						// Call async supplier -- starts async work, returns future
						future = Objects.requireNonNull(
							asyncLambda.get(),
							"asyncLambda must return a"
								+ " non-null CompletableFuture"
						);
					} catch (Throwable t) {
						// sync error during setup -- full cleanup
						block.setError(t);
						block.close();
						throw t;
					}

					// Detach span scope immediately (same thread as creation)
					block.detachScope();

					// Chain span end for async completion (any thread -- thread-safe)
					return future.handle((result, error) -> {
						if (error != null) {
							block.setError(error);
						}
						block.end();
						if (error != null) {
							if (error instanceof RuntimeException re) {
								throw re;
							}
							throw new CompletionException(error);
						}
						return result;
					});
				}
			);
		}
	}

	/**
	 * Client metadata extracted from HTTP request headers for
	 * tracing and MDC.
	 *
	 * @param clientIpAddress the client IP from X-Forwarded-For
	 *                        header
	 * @param clientUri       the client URI from configured
	 *                        forwarded-uri headers
	 * @param labels          client-provided labels from configured
	 *                        forwarded-for headers
	 */
	private record ClientMetadata(
		@Nullable String clientIpAddress,
		@Nullable String clientUri,
		@Nonnull Label[] labels
	) {}

	/**
	 * Extracts client metadata (IP address, URI, and labels) from
	 * the given request headers using the configured header options.
	 *
	 * @param headers the request headers to extract metadata from
	 * @return the extracted client metadata
	 */
	@Nonnull
	private ClientMetadata extractClientMetadata(
		@Nonnull RequestHeaders headers
	) {
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
					return new Label(
						header.substring(0, index),
						header.substring(index + 1)
					);
				}
			})
			.filter(Objects::nonNull)
			.toArray(Label[]::new);
		return new ClientMetadata(
			clientIpAddress, clientUri, labels
		);
	}

	/**
	 * Method for extracting information from the context received
	 * via OpenTelemetry's Context Propagation mechanism. Besides the
	 * extracted traceId, the clientId is also extracted and injected
	 * into the received context.
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
