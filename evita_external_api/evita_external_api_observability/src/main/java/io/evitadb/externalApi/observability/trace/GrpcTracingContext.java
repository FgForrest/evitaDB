/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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
import io.evitadb.api.observability.trace.TracingContextProvider;
import io.evitadb.api.query.head.Label;
import io.evitadb.externalApi.configuration.HeaderOptions;
import io.evitadb.externalApi.utils.ExternalApiTracingContext;
import io.grpc.Metadata;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static java.util.Optional.ofNullable;

/**
 * Implementation of {@link ExternalApiTracingContext} for gRPC API.
 *
 * Besides opening the trace span, this context publishes the caller's client context - IP address, addressed URI,
 * labels and the start of the request being served - into the MDC for **every** entry point this context offers,
 * mirroring what {@link JsonApiTracingContext} does for the JSON APIs. Why it has to be every one of them, and not
 * only the ones evitaDB's own services happen to call, is explained on the private {@code withClientContext} helper
 * they all route through.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class GrpcTracingContext implements ExternalApiTracingContext<Metadata> {
	private static final String CLIENT_ID_HEADER = "clientId";

	/**
	 * Keys of the headers the client context is read from. Initialized with no header names - a context nobody has
	 * configured reads none - and replaced wholesale once {@link #configureHeaders(HeaderOptions)} runs.
	 *
	 * Declared `volatile` because the thread that builds the server writes it while gRPC request threads read it;
	 * without the happens-before edge an early request could observe the empty initial value and publish no client
	 * URI or labels at all.
	 */
	private volatile ClientHeaderKeys headerKeys = ClientHeaderKeys.NONE;

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

	/**
	 * {@inheritDoc}
	 *
	 * The configured names are resolved into gRPC metadata keys here, once, rather than on every call. Resolving a
	 * key validates the name, and the names come from free-form operator configuration - so a name gRPC cannot
	 * represent has to fail while the server is being built, naming the offending header, instead of failing every
	 * request that arrives afterwards.
	 *
	 * @throws IllegalArgumentException when a configured header name is not a valid gRPC metadata key
	 */
	@Override
	public void configureHeaders(@Nonnull HeaderOptions headerOptions) {
		this.headerKeys = ClientHeaderKeys.from(headerOptions);
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
	 * **Every entry point above routes through here.** Which one a caller lands on depends only on whether its lambda
	 * returns a value and whether it completes synchronously - distinctions that say nothing about whether client
	 * metadata is wanted. Instrumenting a subset would leave the paths that happen to use the others silently
	 * without context; evitaDB's own gRPC services reach this class exclusively through the {@link Runnable}
	 * variant.
	 *
	 * This runs on every gRPC call, including the default deployment with tracing switched off, so the extraction
	 * underneath it allocates only what it actually publishes.
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
	 * Keys of the headers the client context is read from, resolved from the configured header names.
	 *
	 * Keys are held rather than names because building one validates the name, lower-cases it and copies it into a
	 * byte array - grpc-java documents its keys as meant to be built once and reused, and holding them here is what
	 * keeps that work off a path that runs on every call.
	 *
	 * @param forwardedFor keys of the headers carrying the caller's IP address
	 * @param forwardedUri keys of the headers carrying the URI the caller addressed
	 * @param label        keys of the headers carrying client labels
	 */
	private record ClientHeaderKeys(
		@Nonnull List<Metadata.Key<String>> forwardedFor,
		@Nonnull List<Metadata.Key<String>> forwardedUri,
		@Nonnull List<Metadata.Key<String>> label
	) {

		/**
		 * The keys of a context that has not been configured yet - no client header is read at all.
		 */
		static final ClientHeaderKeys NONE = new ClientHeaderKeys(List.of(), List.of(), List.of());

		/**
		 * Resolves every header name the operator configured into a gRPC metadata key.
		 *
		 * Nothing is assigned until all three groups resolve, so a configuration carrying an unusable name leaves
		 * the context exactly as it was rather than half-applied.
		 *
		 * @param headerOptions the configured header names
		 * @return the resolved keys
		 * @throws IllegalArgumentException when a configured header name is not a valid gRPC metadata key
		 */
		@Nonnull
		static ClientHeaderKeys from(@Nonnull HeaderOptions headerOptions) {
			return new ClientHeaderKeys(
				resolve(headerOptions.forwardedFor()),
				resolve(headerOptions.forwardedUri()),
				resolve(headerOptions.label())
			);
		}

		/**
		 * Resolves the given header names into gRPC metadata keys, failing on the first name gRPC cannot represent.
		 *
		 * @param headerNames the configured header names
		 * @return the resolved keys, in the configured order
		 * @throws IllegalArgumentException when a header name is not a valid gRPC metadata key
		 */
		@Nonnull
		private static List<Metadata.Key<String>> resolve(@Nonnull List<String> headerNames) {
			final List<Metadata.Key<String>> keys = new ArrayList<>(headerNames.size());
			for (int i = 0; i < headerNames.size(); i++) {
				final String headerName = headerNames.get(i);
				try {
					keys.add(Metadata.Key.of(headerName, Metadata.ASCII_STRING_MARSHALLER));
				} catch (IllegalArgumentException ex) {
					throw new IllegalArgumentException(
						"Configured header name `" + headerName + "` cannot be used by the gRPC API: "
							+ ex.getMessage(),
						ex
					);
				}
			}
			return List.copyOf(keys);
		}
	}

	/**
	 * Extracts client metadata (IP address, URI and labels) from the given gRPC metadata using the configured header
	 * options - the same headers, read the same way, as `JsonApiTracingContext` reads from an HTTP request. gRPC
	 * requests reach evitaDB through the same Armeria pipeline, so the forwarding headers the tracing decorator
	 * writes are present here too.
	 *
	 * @param metadata the gRPC call metadata to extract from
	 * @return the extracted client metadata
	 */
	@Nonnull
	private ClientMetadata extractClientMetadata(@Nonnull Metadata metadata) {
		final ClientHeaderKeys keys = this.headerKeys;
		return new ClientMetadata(
			firstHeaderValue(metadata, keys.forwardedFor()),
			firstHeaderValue(metadata, keys.forwardedUri()),
			extractLabels(metadata, keys.label())
		);
	}

	/**
	 * Returns the value of the first of the given headers the call actually carries.
	 *
	 * A repeated header answers with its last value - that is what the single-valued getter does, here and on the
	 * JSON side, and it is deliberately different from how labels below are read.
	 *
	 * @param metadata the gRPC call metadata to read from
	 * @param keys     the keys to try, in the configured order
	 * @return the first value found, or null when the call carries none of these headers
	 */
	@Nullable
	private static String firstHeaderValue(@Nonnull Metadata metadata, @Nonnull List<Metadata.Key<String>> keys) {
		for (int i = 0; i < keys.size(); i++) {
			final String value = metadata.get(keys.get(i));
			if (value != null) {
				return value;
			}
		}
		return null;
	}

	/**
	 * Reads every client label the call carries.
	 *
	 * A label header legitimately repeats - each occurrence carries one `name=value` pair, exactly as on the JSON
	 * side - so every value of every configured label header is read rather than only the last one. The pair itself is
	 * parsed by {@link ClientMetadata#parseLabel(String)}, which both API surfaces share, and a value it cannot make a
	 * label out of is dropped.
	 *
	 * @param metadata the gRPC call metadata to read from
	 * @param keys     the keys of the configured label headers
	 * @return the labels the call carries, or an empty array when it carries none
	 */
	@Nonnull
	private static Label[] extractLabels(@Nonnull Metadata metadata, @Nonnull List<Metadata.Key<String>> keys) {
		List<Label> labels = null;
		for (int i = 0; i < keys.size(); i++) {
			final Iterable<String> values = metadata.getAll(keys.get(i));
			if (values == null) {
				continue;
			}
			for (final String header : values) {
				final Label label = ClientMetadata.parseLabel(header);
				if (label == null) {
					continue;
				}
				if (labels == null) {
					labels = new ArrayList<>(4);
				}
				labels.add(label);
			}
		}
		return labels == null ? Label.EMPTY_ARRAY : labels.toArray(Label.EMPTY_ARRAY);
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
