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

package io.evitadb.externalApi.observability.trace;

import com.linecorp.armeria.common.HttpHeaderNames;
import io.evitadb.api.observability.trace.TracingContext;
import io.evitadb.api.observability.trace.TracingContext.SpanAttribute;
import io.evitadb.api.observability.trace.TracingContextProvider;
import io.evitadb.api.query.head.Label;
import io.evitadb.externalApi.configuration.HeaderOptions;
import io.evitadb.externalApi.utils.ExternalApiTracingContext;
import io.grpc.Metadata;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import lombok.Setter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static java.util.Optional.ofNullable;

/**
 * Implementation of {@link ExternalApiTracingContext} for gRPC API.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class GrpcTracingContext implements ExternalApiTracingContext<Metadata> {
	private static final String CLIENT_ID_HEADER = "clientId";

	/**
	 * Header configuration. Initialized with default settings that gets overwritten once the context is prepared.
	 */
	@Setter private HeaderOptions headerOptions = HeaderOptions.builder().build();

	private final TracingContext tracingContext;

	/**
	 * No-arg constructor required by {@link java.util.ServiceLoader}.
	 * Loads the {@link TracingContext} via {@link TracingContextProvider}.
	 */
	public GrpcTracingContext() {
		this.tracingContext = TracingContextProvider.getContext();
	}

	/**
	 * Constructor accepting an explicit {@link TracingContext} — useful for testing.
	 */
	GrpcTracingContext(@Nonnull TracingContext tracingContext) {
		this.tracingContext = tracingContext;
	}

	@Nonnull
	@Override
	public Class<Metadata> contextType() {
		return Metadata.class;
	}

	@Override
	public void configureHeaders(@Nonnull HeaderOptions headerOptions) {
		this.headerOptions = headerOptions;
	}

	@Override
	public void executeWithinBlock(
		@Nonnull String protocolName,
		@Nonnull Metadata context,
		@Nonnull Runnable runnable,
		@Nullable SpanAttribute... attributes
	) {
		withClientContext(context, () -> {
			if (OpenTelemetryTracerSetup.isTracingEnabled()) {
				try (Scope ignored = extractContextFromHeaders(protocolName, context).makeCurrent()) {
					this.tracingContext.executeWithinBlock(protocolName, runnable, attributes);
				}
			} else {
				runnable.run();
			}
			return null;
		});
	}

	@Override
	public <T> T executeWithinBlock(
		@Nonnull String protocolName,
		@Nonnull Metadata context,
		@Nonnull Supplier<T> lambda,
		@Nullable SpanAttribute... attributes
	) {
		return withClientContext(context, () -> {
			if (!OpenTelemetryTracerSetup.isTracingEnabled()) {
				return lambda.get();
			}
			try (Scope ignored = extractContextFromHeaders(protocolName, context).makeCurrent()) {
				return this.tracingContext.executeWithinBlock(protocolName, lambda, attributes);
			}
		});
	}

	@Override
	public void executeWithinBlock(
		@Nonnull String protocolName,
		@Nonnull Metadata context,
		@Nonnull Runnable runnable,
		@Nullable Supplier<SpanAttribute[]> attributes
	) {
		withClientContext(context, () -> {
			if (OpenTelemetryTracerSetup.isTracingEnabled()) {
				try (Scope ignored = extractContextFromHeaders(protocolName, context).makeCurrent()) {
					this.tracingContext.executeWithinBlock(protocolName, runnable, attributes);
				}
			} else {
				runnable.run();
			}
			return null;
		});
	}

	@Override
	public <T> T executeWithinBlock(
		@Nonnull String protocolName,
		@Nonnull Metadata context,
		@Nonnull Supplier<T> lambda,
		@Nullable Supplier<SpanAttribute[]> attributes
	) {
		return withClientContext(context, () -> {
			if (!OpenTelemetryTracerSetup.isTracingEnabled()) {
				return lambda.get();
			}
			try (Scope ignored = extractContextFromHeaders(protocolName, context).makeCurrent()) {
				return this.tracingContext.executeWithinBlock(protocolName, lambda, attributes);
			}
		});
	}

	@Override
	public void executeWithinBlock(@Nonnull String protocolName, @Nonnull Metadata context, @Nonnull Runnable runnable) {
		withClientContext(context, () -> {
			if (OpenTelemetryTracerSetup.isTracingEnabled()) {
				try (Scope ignored = extractContextFromHeaders(protocolName, context).makeCurrent()) {
					this.tracingContext.executeWithinBlock(protocolName, runnable);
				}
			} else {
				runnable.run();
			}
			return null;
		});
	}

	@Nullable
	@Override
	public <T> T executeWithinBlock(@Nonnull String protocolName, @Nonnull Metadata context, @Nonnull Supplier<T> lambda) {
		return withClientContext(context, () -> {
			if (!OpenTelemetryTracerSetup.isTracingEnabled()) {
				return lambda.get();
			}
			try (Scope ignored = extractContextFromHeaders(protocolName, context).makeCurrent()) {
				return this.tracingContext.executeWithinBlock(protocolName, lambda);
			}
		});
	}

	/**
	 * Runs {@code lambda} with the client metadata of the gRPC call published to the MDC, exactly as the JSON APIs
	 * publish theirs, so that log lines and traffic recordings made underneath it can report who called and what
	 * they called.
	 *
	 * **Every overload above routes through here.** Which one a caller lands on depends only on whether its lambda
	 * returns a value and whether it passes span attributes - distinctions that say nothing about whether client
	 * metadata is wanted. Instrumenting a subset would leave the paths that happen to use the other overloads
	 * silently without context; evitaDB's own gRPC services reach this class exclusively through the
	 * {@link Runnable} variants.
	 *
	 * @param metadata the gRPC call metadata carrying the client headers
	 * @param lambda   the work to run with the client context published
	 * @param <T>      the result type
	 * @return whatever {@code lambda} returns
	 */
	private <T> T withClientContext(@Nonnull Metadata metadata, @Nonnull Supplier<T> lambda) {
		final ClientMetadata clientMetadata = extractClientMetadata(metadata);
		return TracingContext.executeWithClientContext(
			ExternalApiTracingContext.currentRequestStart(),
			clientMetadata.clientIpAddress(),
			clientMetadata.clientUri(),
			clientMetadata.labels(),
			lambda
		);
	}

	/**
	 * Client metadata carried by the headers of an incoming gRPC call.
	 *
	 * @param clientIpAddress the client IP from the `X-Forwarded-For` header
	 * @param clientUri       the client URI from the configured forwarded-uri headers
	 * @param labels          client-provided labels from the configured label headers
	 */
	private record ClientMetadata(
		@Nullable String clientIpAddress,
		@Nullable String clientUri,
		@Nonnull Label[] labels
	) {}

	/**
	 * Extracts client metadata (IP address, URI and labels) from the given gRPC metadata using the configured header
	 * options - the same headers, read the same way, as `JsonApiTracingContext` reads from an HTTP request. gRPC
	 * requests reach evitaDB through the same Armeria pipeline, so the `X-Forwarded-For` header the tracing decorator
	 * writes is present here too.
	 *
	 * @param metadata the gRPC call metadata to extract from
	 * @return the extracted client metadata
	 */
	@Nonnull
	private ClientMetadata extractClientMetadata(@Nonnull Metadata metadata) {
		final String clientIpAddress = CONTEXT_GETTER.get(metadata, HttpHeaderNames.X_FORWARDED_FOR.toString());
		final String clientUri = this.headerOptions.forwardedUri()
			.stream()
			.map(headerName -> CONTEXT_GETTER.get(metadata, headerName))
			.filter(Objects::nonNull)
			.findFirst()
			.orElse(null);
		final Label[] labels = this.headerOptions.label()
			.stream()
			// a label header may legitimately repeat - every occurrence carries one label, exactly as on the JSON
			// side, so the single-valued getter used for the IP and URI is not enough here
			.flatMap(headerName -> {
				final Iterable<String> values = metadata.getAll(
					Metadata.Key.of(headerName, Metadata.ASCII_STRING_MARSHALLER)
				);
				return values == null ?
					Stream.<String>empty() : StreamSupport.stream(values.spliterator(), false);
			})
			.map(header -> {
				final int index = header.indexOf('=');
				return index < 0 ?
					null : new Label(header.substring(0, index), header.substring(index + 1));
			})
			.filter(Objects::nonNull)
			.toArray(Label[]::new);
		return new ClientMetadata(clientIpAddress, clientUri, labels);
	}

	/**
	 * Method for extracting information from the context received via OpenTelemetry's Context Propagation mechanism. Besides
	 * the extracted traceId, the clientId is also extracted and injected into the received context.
	 */
	@Nonnull
	private Context extractContextFromHeaders(@Nonnull String protocolName, @Nonnull Metadata metadata) {
		final Context context = OpenTelemetryTracerSetup.getOpenTelemetry()
			.getPropagators()
			.getTextMapPropagator()
			.extract(Context.current(), metadata, CONTEXT_GETTER);
		final String clientId = convertClientId(protocolName, CONTEXT_GETTER.get(metadata, CLIENT_ID_HEADER));
		return context.with(OpenTelemetryTracerSetup.CONTEXT_KEY, clientId);
	}

	/**
	 * Getter for extracting information from gRPC Metadata.
	 */
	@Nonnull
	private static final TextMapGetter<Metadata> CONTEXT_GETTER =
		new TextMapGetter<>() {
			@Override
			public String get(@Nullable Metadata metadata, @Nonnull String s) {
				final Metadata.Key<String> clientMetadata = Metadata.Key.of(s, Metadata.ASCII_STRING_MARSHALLER);
				return ofNullable(metadata)
					.map(it -> it.get(clientMetadata))
					.orElse(null);
			}

			@Override
			public Iterable<String> keys(Metadata metadata) {
				return metadata.keys();
			}
		};
}
