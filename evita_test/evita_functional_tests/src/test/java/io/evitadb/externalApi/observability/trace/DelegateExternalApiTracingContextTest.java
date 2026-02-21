/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025-2026
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

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestHeaders;
import io.evitadb.api.observability.trace.DefaultTracingContext;
import io.evitadb.api.observability.trace.TracingContext;
import io.evitadb.api.observability.trace.TracingContextProvider;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.grpc.Metadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;

/**
 * Tests for {@link DelegateExternalApiTracingContext}
 * verifying routing behavior based on context object type.
 * Uses {@link MockedStatic} for
 * {@link TracingContextProvider} and
 * {@link OpenTelemetryTracerSetup} to isolate tests from
 * actual OpenTelemetry configuration.
 *
 * @author evitaDB
 */
@DisplayName(
	"DelegateExternalApiTracingContext - context type routing"
)
class DelegateExternalApiTracingContextTest {

	@Test
	@DisplayName(
		"delegates to JSON context for HttpRequest"
	)
	void shouldDelegateToJsonApiContextForHttpRequest() {
		try (
			MockedStatic<TracingContextProvider> tcp =
				mockStatic(TracingContextProvider.class);
			MockedStatic<OpenTelemetryTracerSetup> otel =
				mockStatic(OpenTelemetryTracerSetup.class)
		) {
			final TracingContext noopCtx =
				DefaultTracingContext.INSTANCE;
			tcp.when(TracingContextProvider::getContext)
				.thenReturn(noopCtx);
			otel.when(
				OpenTelemetryTracerSetup::isTracingEnabled
			).thenReturn(false);

			final DelegateExternalApiTracingContext delegate =
				new DelegateExternalApiTracingContext();

			final HttpRequest httpRequest = HttpRequest.of(
				RequestHeaders.of(
					HttpMethod.POST, "/graphql"
				)
			);
			final AtomicBoolean called =
				new AtomicBoolean(false);

			delegate.executeWithinBlock(
				"GraphQL",
				httpRequest,
				() -> called.set(true)
			);

			assertTrue(
				called.get(),
				"Lambda should have been called via JSON ctx"
			);
		}
	}

	@Test
	@DisplayName(
		"delegates to gRPC context for Metadata"
	)
	void shouldDelegateToGrpcContextForMetadata() {
		try (
			MockedStatic<TracingContextProvider> tcp =
				mockStatic(TracingContextProvider.class);
			MockedStatic<OpenTelemetryTracerSetup> otel =
				mockStatic(OpenTelemetryTracerSetup.class)
		) {
			final TracingContext noopCtx =
				DefaultTracingContext.INSTANCE;
			tcp.when(TracingContextProvider::getContext)
				.thenReturn(noopCtx);
			otel.when(
				OpenTelemetryTracerSetup::isTracingEnabled
			).thenReturn(false);

			final DelegateExternalApiTracingContext delegate =
				new DelegateExternalApiTracingContext();

			final Metadata metadata = new Metadata();
			final AtomicBoolean called =
				new AtomicBoolean(false);

			delegate.executeWithinBlock(
				"gRPC",
				metadata,
				() -> called.set(true)
			);

			assertTrue(
				called.get(),
				"Lambda should have been called via gRPC ctx"
			);
		}
	}

	@Test
	@DisplayName(
		"throws for unknown context type"
	)
	void shouldThrowForUnknownContextType() {
		try (
			MockedStatic<TracingContextProvider> tcp =
				mockStatic(TracingContextProvider.class);
			MockedStatic<OpenTelemetryTracerSetup> otel =
				mockStatic(OpenTelemetryTracerSetup.class)
		) {
			final TracingContext noopCtx =
				DefaultTracingContext.INSTANCE;
			tcp.when(TracingContextProvider::getContext)
				.thenReturn(noopCtx);
			otel.when(
				OpenTelemetryTracerSetup::isTracingEnabled
			).thenReturn(false);

			final DelegateExternalApiTracingContext delegate =
				new DelegateExternalApiTracingContext();

			assertThrows(
				EvitaInvalidUsageException.class,
				() -> delegate.executeWithinBlock(
					"unknown",
					"invalid-context-type",
					() -> {}
				)
			);
		}
	}

	@Test
	@DisplayName(
		"delegates async to JSON for HttpRequest"
	)
	void shouldDelegateAsyncToJsonApiForHttpRequest()
		throws ExecutionException, InterruptedException {

		try (
			MockedStatic<TracingContextProvider> tcp =
				mockStatic(TracingContextProvider.class);
			MockedStatic<OpenTelemetryTracerSetup> otel =
				mockStatic(OpenTelemetryTracerSetup.class)
		) {
			final TracingContext noopCtx =
				DefaultTracingContext.INSTANCE;
			tcp.when(TracingContextProvider::getContext)
				.thenReturn(noopCtx);
			otel.when(
				OpenTelemetryTracerSetup::isTracingEnabled
			).thenReturn(false);

			final DelegateExternalApiTracingContext delegate =
				new DelegateExternalApiTracingContext();

			final HttpRequest httpRequest = HttpRequest.of(
				RequestHeaders.of(
					HttpMethod.POST, "/rest"
				)
			);

			final CompletableFuture<String> result =
				delegate.executeWithinBlockAsync(
					"REST",
					httpRequest,
					() -> CompletableFuture
						.completedFuture("async-value")
				);

			assertEquals("async-value", result.get());
		}
	}

	@Test
	@DisplayName(
		"throws for unknown type in async variant"
	)
	void shouldThrowForUnknownContextTypeInAsync() {
		try (
			MockedStatic<TracingContextProvider> tcp =
				mockStatic(TracingContextProvider.class);
			MockedStatic<OpenTelemetryTracerSetup> otel =
				mockStatic(OpenTelemetryTracerSetup.class)
		) {
			final TracingContext noopCtx =
				DefaultTracingContext.INSTANCE;
			tcp.when(TracingContextProvider::getContext)
				.thenReturn(noopCtx);
			otel.when(
				OpenTelemetryTracerSetup::isTracingEnabled
			).thenReturn(false);

			final DelegateExternalApiTracingContext delegate =
				new DelegateExternalApiTracingContext();

			assertThrows(
				EvitaInvalidUsageException.class,
				() -> delegate.executeWithinBlockAsync(
					"unknown",
					"invalid-type",
					() -> CompletableFuture
						.completedFuture("never")
				)
			);
		}
	}
}
