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

import io.evitadb.externalApi.observability.configuration.ObservabilityOptions;
import io.evitadb.externalApi.observability.configuration.TracingConfig;
import io.evitadb.externalApi.utils.ExternalApiTracingContext;
import io.evitadb.utils.Assert;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.ContextKey;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.Duration;

/**
 * This class is responsible for setting up the OpenTelemetry instance, the tracer and a propagator context.
 * It is used by the {@link JsonApiTracingContext} and {@link GrpcTracingContext} to create a tracing context.
 * Traces are exported to the configured endpoint that is set in the {@link ObservabilityOptions}. This config should be
 * set before working with the OpenTelemetry via {@link #setTracingConfig(TracingConfig)} (ObservabilityOptions)}.
 *
 * @author Tomáš Pozler, FG Forrest a.s. (c) 2024
 */
public class OpenTelemetryTracerSetup {
	/**
	 * The context key that is used to store the client ID in the context. It is necessary to have a shared instance because
	 * of internal checks in the OpenTelemetry library uses `==` operation that compares instances and not values.
	 */
	public static final ContextKey<String> CONTEXT_KEY = ContextKey.named(ExternalApiTracingContext.CLIENT_ID_CONTEXT_KEY_NAME);
	/**
	 * Observability API tracing config.
	 */
	private static TracingConfig TRACING_CONFIG;
	/**
	 * Reusable instance of the OpenTelemetry.
	 */
	private static OpenTelemetry OPEN_TELEMETRY;
	/**
	 * Reusable instance of the OpenTelemetry's Tracer.
	 */
	private static Tracer TRACER;

	/**
	 * Sets the {@link ObservabilityOptions} that is used to configure the OpenTelemetry. Should be set before working with
	 * the OpenTelemetry.
	 */
	public static void setTracingConfig(@Nonnull TracingConfig tracingConfig) {
		TRACING_CONFIG = tracingConfig;
		if (isTracingEnabled(tracingConfig)) {
			OPEN_TELEMETRY = initializeOpenTelemetry(tracingConfig);
		}
	}

	/**
	 * Initializes the OpenTelemetry instance with the configured exporter and propagator.
	 */
	@Nonnull
	private static OpenTelemetry initializeOpenTelemetry(@Nonnull TracingConfig tracingConfig) {
		final Resource resource = Resource.getDefault().toBuilder()
			.put(AttributeKey.stringKey("service.name"), tracingConfig.serviceName())
			.build();

		final SdkTracerProvider sdkTracerProvider = SdkTracerProvider.builder()
			.addSpanProcessor(getSpanProcessor(tracingConfig))
			.setResource(resource)
			.build();

		return OpenTelemetrySdk.builder()
			.setTracerProvider(sdkTracerProvider)
			.setPropagators(
				ContextPropagators.create(TextMapPropagator.composite(W3CTraceContextPropagator.getInstance(), W3CBaggagePropagator.getInstance()))
			)
			.buildAndRegisterGlobal();
	}

	/**
	 * Creates a span processor based on the configured protocol with a specified endpoint.
	 */
	@Nonnull
	private static SpanProcessor getSpanProcessor(@Nonnull TracingConfig tracingConfig) {
		Assert.isTrue(tracingConfig.endpoint() != null, "Tracing endpoint must be set.");
		if (TracingConfig.SPAN_HTTP_PROTOCOL.equalsIgnoreCase(tracingConfig.protocol())) {
			return BatchSpanProcessor.builder(
				OtlpHttpSpanExporter.builder()
					.setEndpoint(tracingConfig.endpoint())
					.setTimeout(Duration.ofSeconds(10))
					.build()
			).build();
		} else {
			return BatchSpanProcessor.builder(
				OtlpGrpcSpanExporter.builder()
					.setEndpoint(tracingConfig.endpoint())
					.setTimeout(Duration.ofSeconds(10))
					.build()
			).build();
		}
	}

	/**
	 * Checks if tracing is enabled.
	 * @return true if tracing is enabled, false otherwise
	 */
	public static boolean isTracingEnabled() {
		return isTracingEnabled(TRACING_CONFIG);
	}

	/**
	 * Gets and caches an instance of the OpenTelemetry's Tracer.
	 */
	@Nonnull
	public static Tracer getTracer() {
		if (TRACER == null) {
			TRACER = OPEN_TELEMETRY.getTracer(TRACING_CONFIG.serviceName());
		}
		return TRACER;
	}

	/**
	 * Gets and caches an instance of the OpenTelemetry.
	 */
	@Nonnull
	public static OpenTelemetry getOpenTelemetry() {
		return OPEN_TELEMETRY;
	}

	/**
	 * Checks if tracing is enabled.
	 *
	 * @param tracingConfig the tracing configuration
	 * @return true if tracing is enabled, false otherwise
	 */
	private static boolean isTracingEnabled(@Nullable TracingConfig tracingConfig) {
		if (tracingConfig == null) {
			return false;
		}
		final String endpoint = tracingConfig.endpoint();
		if (endpoint == null) {
			return false;
		}
		return !endpoint.trim().isEmpty();
	}
}
