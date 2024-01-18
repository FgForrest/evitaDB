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

package io.evitadb.externalApi.trace;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.context.propagation.TextMapSetter;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.semconv.ResourceAttributes;
import io.undertow.util.HeaderMap;

import javax.annotation.Nonnull;
import java.net.HttpURLConnection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class OpenTelemetryTracerSetup {
	private static final OpenTelemetry OPEN_TELEMETRY = initializeOpenTelemetry();
	private static Tracer TRACER;
	private static final String SERVICE_NAME = "evitaDB";
	private static final TextMapGetter<HeaderMap> CONTEXT_GETTER =
		new TextMapGetter<>() {
			@Override
			public String get(HeaderMap headers, String s) {
				assert headers != null;
				return headers.get(s).toString();
			}

			@Override
			public Iterable<String> keys(HeaderMap headers) {
				List<String> keys = new ArrayList<>(8);
				headers.getHeaderNames().forEach((k) -> keys.add(k.toString()));
				return keys;
			}
		};
	private static OpenTelemetry initializeOpenTelemetry() {
		Resource resource = Resource.getDefault().toBuilder().put(ResourceAttributes.SERVICE_NAME, SERVICE_NAME).build();

		SdkTracerProvider sdkTracerProvider = SdkTracerProvider.builder()
			.addSpanProcessor(BatchSpanProcessor.builder(OtlpHttpSpanExporter.builder()
				.setEndpoint("http://localhost:4318/v1/traces")
				.setTimeout(Duration.ofSeconds(10))
				.build()).build()
			)
			.setResource(resource)
			.build();

		return OpenTelemetrySdk.builder()
			.setTracerProvider(sdkTracerProvider)
			.setPropagators(ContextPropagators.create(TextMapPropagator.composite(W3CTraceContextPropagator.getInstance(), W3CBaggagePropagator.getInstance())))
			.buildAndRegisterGlobal();
	}

	public static Tracer getTracer() {
		if (TRACER == null) {
			TRACER = OPEN_TELEMETRY.getTracer(SERVICE_NAME);
		}
		return TRACER;
	}

	@Nonnull
	public static Context extractContextFromHeaders(@Nonnull HeaderMap headers) {
		return OPEN_TELEMETRY.getPropagators().getTextMapPropagator().extract(Context.current(), headers, CONTEXT_GETTER);
	}
}
