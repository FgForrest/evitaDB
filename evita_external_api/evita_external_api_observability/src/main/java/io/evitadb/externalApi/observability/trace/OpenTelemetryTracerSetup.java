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

import io.evitadb.externalApi.observability.configuration.ObservabilityConfig;
import io.evitadb.externalApi.utils.ExternalApiTracingContext;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.ContextKey;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.semconv.ResourceAttributes;

import javax.annotation.Nonnull;
import java.time.Duration;

/**
 * This class is responsible for setting up the OpenTelemetry instance, the tracer and a propagator context.
 * It is used by the {@link JsonApiClientContext} and {@link GrpcClientContext} to create a tracing context.
 * Traces are exported to the configured endpoint that is set in the {@link ObservabilityConfig}. This config should be
 * set before working with the OpenTelemetry via {@link #setOpenTelemetryConfig(ObservabilityConfig)}.
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
	 * Name of the service that is used in the tracing context.
	 */
	private static final String SERVICE_NAME = "evitaDB";
	/**
	 * Observability API config.
	 */
	private static ObservabilityConfig OBSERVABILITY_CONFIG;
	/**
	 * Reusable instance of the OpenTelemetry.
	 */
	private static OpenTelemetry OPEN_TELEMETRY;
	/**
	 * Reusable instance of the OpenTelemetry's Tracer.
	 */
	private static Tracer TRACER;

	/**
	 * Sets the {@link ObservabilityConfig} that is used to configure the OpenTelemetry. Should be set before working with
	 * the OpenTelemetry.
	 */
	public static void setOpenTelemetryConfig(@Nonnull ObservabilityConfig observabilityConfig) {
		OBSERVABILITY_CONFIG = observabilityConfig;
	}

	/**
	 * Initializes the OpenTelemetry instance with the configured exporter and propagator.
	 */
	@Nonnull
	private static OpenTelemetry initializeOpenTelemetry() {
		final Resource resource = Resource.getDefault().toBuilder().put(ResourceAttributes.SERVICE_NAME, SERVICE_NAME).build();

		final SdkTracerProvider sdkTracerProvider = SdkTracerProvider.builder()
			.addSpanProcessor(BatchSpanProcessor.builder(
					OtlpHttpSpanExporter.builder()
						.setEndpoint(OBSERVABILITY_CONFIG.getTracingEndpoint())
						.setTimeout(Duration.ofSeconds(10))
						.build()
				).build()
			)
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
	 * Gets and caches an instance of the OpenTelemetry's Tracer.
	 */
	@Nonnull
	public static Tracer getTracer() {
		if (OPEN_TELEMETRY == null) {
			OPEN_TELEMETRY = initializeOpenTelemetry();
		}
		if (TRACER == null) {
			TRACER = OPEN_TELEMETRY.getTracer(SERVICE_NAME);
		}
		return TRACER;
	}

	/**
	 * Gets and caches an instance of the OpenTelemetry.
	 */
	@Nonnull
	public static OpenTelemetry getOpenTelemetry() {
		if (OPEN_TELEMETRY == null) {
			OPEN_TELEMETRY = initializeOpenTelemetry();
		}
		return OPEN_TELEMETRY;
	}
}
