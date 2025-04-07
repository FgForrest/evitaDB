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

import io.evitadb.api.observability.trace.TracingContext;
import io.evitadb.api.observability.trace.TracingContext.SpanAttribute;
import io.evitadb.externalApi.configuration.HeaderOptions;
import io.evitadb.externalApi.utils.ExternalApiTracingContext;
import io.grpc.Metadata;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.function.Supplier;

import static java.util.Optional.ofNullable;

/**
 * Implementation of {@link ExternalApiTracingContext} for gRPC API.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class GrpcTracingContext implements ExternalApiTracingContext<Metadata> {
	private static final String CLIENT_ID_HEADER = "clientId";

	private final TracingContext tracingContext;

	public GrpcTracingContext(@Nonnull TracingContext tracingContext) {
		this.tracingContext = tracingContext;
	}

	@Override
	public void configureHeaders(@Nonnull HeaderOptions headerOptions) {
		// do nothing
	}

	@Override
	public void executeWithinBlock(
		@Nonnull String protocolName,
		@Nonnull Metadata context,
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
		@Nonnull Metadata context,
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
		@Nonnull Metadata context,
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
		@Nonnull Metadata context,
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
	public void executeWithinBlock(@Nonnull String protocolName, @Nonnull Metadata context, @Nonnull Runnable runnable) {
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
	public <T> T executeWithinBlock(@Nonnull String protocolName, @Nonnull Metadata context, @Nonnull Supplier<T> lambda) {
		if (!OpenTelemetryTracerSetup.isTracingEnabled()) {
			return lambda.get();
		}
		try (Scope ignored = extractContextFromHeaders(protocolName, context).makeCurrent()) {
			return this.tracingContext.executeWithinBlock(
				protocolName,
				lambda
			);
		}
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
