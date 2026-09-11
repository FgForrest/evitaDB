/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.externalApi.grpc.services.interceptors;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.server.ServiceRequestContext;
import io.evitadb.api.observability.trace.TracingContext;
import io.evitadb.externalApi.grpc.generated.EvitaManagementServiceGrpc;
import io.evitadb.externalApi.grpc.generated.GrpcFetchFileRequest;
import io.evitadb.externalApi.grpc.generated.GrpcFetchFileResponse;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCall.Listener;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.GRPC;
import static io.evitadb.test.TestTags.OBSERVABILITY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Verifies that {@link ObservabilityInterceptor} records the start of the request being served for **every** shape of
 * gRPC method, not merely the ones whose work happens inside a listener callback.
 *
 * This deliberately exercises the interceptor rather than a hand-picked service method, because the claim under test
 * is a quantifier: *every* gRPC call must carry the request start. The case that motivated it is client-streaming -
 * grpc-java invokes the service method inside `startCall` and only afterwards constructs the listener, so a
 * listener-only seam misses the entire body of a method such as `EvitaManagementService.restoreCatalog`.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("gRPC observability interceptor must record the request start for every method shape")
@Tag(GRPC)
@Tag(EXTERNAL_API)
@Tag(OBSERVABILITY)
class ObservabilityInterceptorRequestStartTest {

	/**
	 * What the MDC held at each point the interceptor passed control downstream.
	 */
	private final Map<String, String> observed = new HashMap<>();

	@AfterEach
	void tearDown() {
		TracingContext.clearContext();
	}

	/**
	 * Records the request start currently visible in the MDC under the given label.
	 *
	 * @param where the call-site label
	 */
	private void record(@Nonnull String where) {
		this.observed.put(where, MDC.get(TracingContext.MDC_REQUEST_START_PROPERTY));
	}

	/**
	 * Runs the interceptor inside a live server request context and drives the resulting listener through every
	 * callback Armeria invokes with that context pushed.
	 *
	 * @return the request start Armeria recorded for the request, as a string
	 */
	@Nonnull
	private String interceptAndDriveCallbacks() {
		final ServiceRequestContext ctx = ServiceRequestContext.builder(
			HttpRequest.of(HttpMethod.POST, "/io.evitadb.EvitaManagementService/RestoreCatalog")
		).build();

		final ServerCallHandler<GrpcFetchFileRequest, GrpcFetchFileResponse> handler =
			(call, headers) -> {
				// this is where a client-streaming service method body actually runs
				record("startCall");
				return new Listener<>() {
					@Override
					public void onMessage(GrpcFetchFileRequest message) {
						record("onMessage");
					}

					@Override
					public void onHalfClose() {
						record("onHalfClose");
					}

					@Override
					public void onCancel() {
						record("onCancel");
					}

					@Override
					public void onComplete() {
						record("onComplete");
					}
				};
			};

		try (SafeCloseable ignored = ctx.push()) {
			final Listener<GrpcFetchFileRequest> listener =
				new ObservabilityInterceptor().interceptCall(new MockServerCall(), new Metadata(), handler);
			assertNotNull(listener);

			listener.onMessage(GrpcFetchFileRequest.getDefaultInstance());
			listener.onHalfClose();
			listener.onComplete();
			listener.onCancel();

			return Long.toString(ctx.log().partial().requestStartTimeMillis());
		}
	}

	@Test
	@DisplayName("records the request start inside startCall and inside every pushed listener callback")
	void shouldRecordRequestStartAtEverySeam() {
		final String expected = interceptAndDriveCallbacks();

		assertEquals(expected, this.observed.get("startCall"), "startCall - client-streaming method bodies run here");
		assertEquals(expected, this.observed.get("onMessage"));
		assertEquals(expected, this.observed.get("onHalfClose"));
		assertEquals(expected, this.observed.get("onComplete"));
		assertEquals(expected, this.observed.get("onCancel"));
	}

	@Test
	@DisplayName("leaves the MDC clean once the call is over")
	void shouldNotLeakRequestStartOntoThePooledThread() {
		interceptAndDriveCallbacks();

		assertNull(
			MDC.get(TracingContext.MDC_REQUEST_START_PROPERTY),
			"the request start outlived the call and would be attributed to the next request this thread serves"
		);
	}

	@Test
	@DisplayName("records nothing when no request context is current")
	void shouldRecordNothingOutsideARequest() {
		final ServerCallHandler<GrpcFetchFileRequest, GrpcFetchFileResponse> handler =
			(call, headers) -> {
				record("startCall");
				return new Listener<>() {
				};
			};

		new ObservabilityInterceptor().interceptCall(new MockServerCall(), new Metadata(), handler);

		assertNull(this.observed.get("startCall"));
	}

	/**
	 * Minimal {@link ServerCall} stub - the interceptor only asks it for its method descriptor during
	 * `interceptCall`.
	 */
	private static class MockServerCall extends ServerCall<GrpcFetchFileRequest, GrpcFetchFileResponse> {

		@Override
		public void request(int numMessages) {
		}

		@Override
		public void sendHeaders(@Nullable Metadata headers) {
		}

		@Override
		public void sendMessage(GrpcFetchFileResponse message) {
		}

		@Override
		public void close(Status status, Metadata trailers) {
		}

		@Override
		public boolean isCancelled() {
			return false;
		}

		@Override
		public MethodDescriptor<GrpcFetchFileRequest, GrpcFetchFileResponse> getMethodDescriptor() {
			return EvitaManagementServiceGrpc.getFetchFileMethod();
		}
	}
}
