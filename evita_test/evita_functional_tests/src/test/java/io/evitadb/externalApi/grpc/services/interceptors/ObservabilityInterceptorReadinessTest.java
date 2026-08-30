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

import io.evitadb.externalApi.grpc.generated.EvitaManagementServiceGrpc;
import io.evitadb.externalApi.grpc.generated.GrpcFetchFileRequest;
import io.evitadb.externalApi.grpc.generated.GrpcFetchFileResponse;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCall.Listener;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.GRPC;
import static io.evitadb.test.TestTags.OBSERVABILITY;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the transport-readiness signal that {@link ObservabilityInterceptor} sits on top of.
 *
 * The interceptor wraps every {@link ServerCall} in its own decorator before handing it to the
 * service implementation. {@link ServerCall#isReady()} is **not** abstract - `io.grpc.ServerCall`
 * ships a default implementation that unconditionally returns `true`. A decorator that forgets to
 * delegate it therefore does not merely lose an optimisation: it reports "the transport can accept
 * more data" forever, which silently defeats any flow-control loop written against
 * `ServerCallStreamObserver.isReady()` in a streaming service method (most notably
 * `EvitaManagementService.fetchFile`, which streams whole backup/export files).
 *
 * The failure mode is invisible on a fast consumer and only shows up as unbounded outbound
 * buffering - and eventually a heap blow-up - when the consumer is slower than the producer, so it
 * needs a unit-level guard rather than an end-to-end one. The complementary end-to-end evidence
 * lives in `LongRunningGrpcFetchFileBackpressureTest` in the long-running module.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("gRPC observability interceptor must preserve the transport readiness signal")
@Tag(GRPC)
@Tag(EXTERNAL_API)
@Tag(OBSERVABILITY)
class ObservabilityInterceptorReadinessTest {

	/**
	 * Runs the interceptor against a delegate call and returns the decorated call the interceptor
	 * handed down to the service implementation.
	 *
	 * @param delegate the call the interceptor is asked to decorate
	 * @return the decorated call as seen by the service implementation
	 */
	@Nonnull
	private static ServerCall<GrpcFetchFileRequest, GrpcFetchFileResponse> intercept(
		@Nonnull ServerCall<GrpcFetchFileRequest, GrpcFetchFileResponse> delegate
	) {
		final AtomicReference<ServerCall<GrpcFetchFileRequest, GrpcFetchFileResponse>> decorated =
			new AtomicReference<>();
		final ServerCallHandler<GrpcFetchFileRequest, GrpcFetchFileResponse> handler =
			(call, headers) -> {
				decorated.set(call);
				return new Listener<>() {
				};
			};
		new ObservabilityInterceptor().interceptCall(delegate, new Metadata(), handler);
		final ServerCall<GrpcFetchFileRequest, GrpcFetchFileResponse> result = decorated.get();
		assertNotNull(result, "Interceptor did not start the downstream call.");
		return result;
	}

	@Test
	@DisplayName("isReady() of the decorated call reflects a transport that cannot accept more data")
	void shouldPropagateNotReadyTransportStateToServiceImplementation() {
		final MockServerCall delegate = new MockServerCall(false);
		assertFalse(
			intercept(delegate).isReady(),
			"ObservabilityInterceptor reported the transport as ready while the underlying call is not - " +
				"any isReady()-driven flow control in a streaming service method is silently disabled."
		);
	}

	@Test
	@DisplayName("isReady() of the decorated call reflects a transport that can accept more data")
	void shouldPropagateReadyTransportStateToServiceImplementation() {
		final MockServerCall delegate = new MockServerCall(true);
		assertTrue(
			intercept(delegate).isReady(),
			"ObservabilityInterceptor reported the transport as not ready while the underlying call is."
		);
	}

	/**
	 * Minimal {@link ServerCall} stub whose only interesting property is the readiness flag it was
	 * constructed with - everything else is a no-op, because the interceptor is not expected to
	 * touch it during `interceptCall`.
	 */
	private static class MockServerCall extends ServerCall<GrpcFetchFileRequest, GrpcFetchFileResponse> {
		private final boolean ready;

		MockServerCall(boolean ready) {
			this.ready = ready;
		}

		@Override
		public void request(int numMessages) {
		}

		@Override
		public void sendHeaders(Metadata headers) {
		}

		@Override
		public void sendMessage(GrpcFetchFileResponse message) {
		}

		@Override
		public boolean isReady() {
			return this.ready;
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
