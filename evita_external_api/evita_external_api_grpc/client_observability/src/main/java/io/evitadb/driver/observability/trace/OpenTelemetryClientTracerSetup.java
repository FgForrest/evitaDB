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

package io.evitadb.driver.observability.trace;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.semconv.ResourceAttributes;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.Duration;

/**
 * This class is responsible for setting up the OpenTelemetry instance, the tracer and a propagator context.
 * Traces are exported to the configured endpoint URL that is set via constructor. It has to be set before using
 * this tracer.
 *
 * @author Tomáš Pozler, FG Forrest a.s. (c) 2024
 */
public class OpenTelemetryClientTracerSetup {
	private static final String SERVICE_NAME = "evitaDB-Java-Client";

	private static String TRACING_ENDPOINT_URL;
	private static String TRACING_ENDPOINT_PROTOCOL;
	private static OpenTelemetry OPEN_TELEMETRY;
	private static final String SPAN_HTTP_PROTOCOL = "HTTP";
	private static final String SPAN_GRPC_PROTOCOL = "GRPC";

	public static void setTracingEndpointUrlAndProtocol(@Nonnull String tracingEndpointUrl, @Nullable String protocol) {
		TRACING_ENDPOINT_URL = tracingEndpointUrl;
		TRACING_ENDPOINT_PROTOCOL = protocol;
	}

	/**
	 * Initializes the OpenTelemetry instance, the tracer, and the propagator context.
	 *
	 * @return the initialized OpenTelemetry instance
	 */
	@Nonnull
	private static OpenTelemetry initializeOpenTelemetry() {
		final Resource resource = Resource.getDefault().toBuilder().put(ResourceAttributes.SERVICE_NAME, SERVICE_NAME).build();

		final SdkTracerProvider sdkTracerProvider = SdkTracerProvider.builder()
			.addSpanProcessor(getSpanProcessor())
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
	private static SpanProcessor getSpanProcessor() {
		if (SPAN_HTTP_PROTOCOL.equalsIgnoreCase(TRACING_ENDPOINT_PROTOCOL)) {
			return BatchSpanProcessor.builder(
				OtlpHttpSpanExporter.builder()
					.setEndpoint(TRACING_ENDPOINT_URL)
					.setTimeout(Duration.ofSeconds(10))
					.build()
			).build();
		}
		return BatchSpanProcessor.builder(
			OtlpGrpcSpanExporter.builder()
				.setEndpoint(TRACING_ENDPOINT_URL)
				.setTimeout(Duration.ofSeconds(10))
				.build()
		).build();
	}

	/**
	 * Retrieves the instance of OpenTelemetry. If the instance is not initialized, it will be
	 * initialized before returning.
	 *
	 * @return the OpenTelemetry instance
	 */
	@Nonnull
	public static OpenTelemetry getOpenTelemetry() {
		if (OPEN_TELEMETRY == null) {
			OPEN_TELEMETRY = initializeOpenTelemetry();
		}
		return OPEN_TELEMETRY;
	}
}
