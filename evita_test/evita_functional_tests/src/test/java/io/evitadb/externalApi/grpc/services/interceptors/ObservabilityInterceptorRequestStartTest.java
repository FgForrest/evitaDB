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
import io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogRequest;
import io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse;
import io.evitadb.utils.CollectionUtils;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.ServerCall.Listener;
import io.grpc.ServerCallHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import javax.annotation.Nonnull;
import java.util.Map;

import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.GRPC;
import static io.evitadb.test.TestTags.OBSERVABILITY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@link ObservabilityInterceptor} records the start of the request being served for **every** shape of
 * gRPC method, not merely the ones whose work happens inside a listener callback.
 *
 * This deliberately exercises the interceptor rather than a hand-picked service method, because the claim under test
 * is a quantifier: *every* gRPC call must carry the request start. The case that motivated it is client-streaming -
 * grpc-java invokes the service method inside `startCall` and only afterwards constructs the listener, so a
 * listener-only seam misses the entire body of a method such as `EvitaManagementService.restoreCatalog`. The fixture
 * therefore installs that method's own descriptor and asserts its shape, so the class cannot quietly end up testing
 * a method of a different shape.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("gRPC observability interceptor must record the request start for every method shape")
@Tag(GRPC)
@Tag(EXTERNAL_API)
@Tag(OBSERVABILITY)
class ObservabilityInterceptorRequestStartTest {

	/**
	 * The points at which grpc-java passes control to a service implementation, named once so that the handler that
	 * records them and the assertions that read them cannot drift apart.
	 */
	private static final String START_CALL = "startCall";
	private static final String ON_MESSAGE = "onMessage";
	private static final String ON_HALF_CLOSE = "onHalfClose";
	private static final String ON_CANCEL = "onCancel";
	private static final String ON_COMPLETE = "onComplete";
	private static final String ON_READY = "onReady";

	/**
	 * What the MDC held at each point the interceptor passed control downstream.
	 *
	 * An entry is written even when nothing was recorded, so its **presence** distinguishes "the downstream handler
	 * ran and saw no request start" from "the downstream handler never ran".
	 */
	private final Map<String, String> observed = CollectionUtils.createHashMap(8);

	/**
	 * The intercepted call, together with what Armeria recorded as the start of the request that produced it.
	 *
	 * @param listener     the listener the interceptor handed back
	 * @param requestStart the request start Armeria recorded, as the interceptor would render it
	 */
	private record InterceptedCall(
		@Nonnull Listener<GrpcRestoreCatalogRequest> listener,
		@Nonnull String requestStart
	) {
	}

	@BeforeEach
	void setUp() {
		// sibling classes share the surefire worker thread, so a request start one of them left behind would
		// satisfy the "records the request start" assertions without this interceptor doing anything
		TracingContext.clearContext();
	}

	@AfterEach
	void tearDown() {
		TracingContext.clearContext();
	}

	/**
	 * Records the request start currently visible in the MDC under the given seam.
	 *
	 * @param seam the seam the downstream handler was entered through
	 */
	private void captureRequestStart(@Nonnull String seam) {
		this.observed.put(seam, MDC.get(TracingContext.MDC_REQUEST_START_PROPERTY));
	}

	/**
	 * Builds a downstream handler that records what the MDC held at every point grpc-java passes control to a
	 * service implementation.
	 *
	 * @return a handler recording into {@link #observed}
	 */
	@Nonnull
	private ServerCallHandler<GrpcRestoreCatalogRequest, GrpcRestoreCatalogResponse> recordingHandler() {
		return (call, headers) -> {
			// this is where a client-streaming service method body actually runs
			captureRequestStart(START_CALL);
			return new Listener<>() {
				@Override
				public void onMessage(GrpcRestoreCatalogRequest message) {
					captureRequestStart(ON_MESSAGE);
				}

				@Override
				public void onHalfClose() {
					captureRequestStart(ON_HALF_CLOSE);
				}

				@Override
				public void onCancel() {
					captureRequestStart(ON_CANCEL);
				}

				@Override
				public void onComplete() {
					captureRequestStart(ON_COMPLETE);
				}

				@Override
				public void onReady() {
					captureRequestStart(ON_READY);
				}
			};
		};
	}

	/**
	 * The descriptor the fixture installs - the client-streaming restore, whose body runs inside `startCall`.
	 *
	 * @return the descriptor of `EvitaManagementService.restoreCatalog`
	 */
	@Nonnull
	private static MethodDescriptor<GrpcRestoreCatalogRequest, GrpcRestoreCatalogResponse> clientStreamingMethod() {
		final MethodDescriptor<GrpcRestoreCatalogRequest, GrpcRestoreCatalogResponse> method =
			EvitaManagementServiceGrpc.getRestoreCatalogMethod();
		assertEquals(
			MethodType.CLIENT_STREAMING, method.getType(),
			"the fixture must exercise a client-streaming method - that is the shape a listener-only seam misses"
		);
		return method;
	}

	/**
	 * Runs the interceptor inside a live server request context and drives the resulting listener through every
	 * callback Armeria invokes with that context pushed.
	 *
	 * Its principal effect is on {@link #observed}: every seam the downstream handler is entered through leaves the
	 * request start it saw in that map, which is what the assertions read. `onReady` is deliberately **not** driven
	 * here - Armeria invokes it without pushing the context, so the test that covers it drives it outside the push.
	 *
	 * @return the intercepted call and the request start Armeria recorded for it
	 */
	@Nonnull
	private InterceptedCall interceptAndDriveCallbacks() {
		final MethodDescriptor<GrpcRestoreCatalogRequest, GrpcRestoreCatalogResponse> method =
			clientStreamingMethod();
		final ServiceRequestContext ctx = ServiceRequestContext.builder(
			HttpRequest.of(HttpMethod.POST, "/" + method.getFullMethodName())
		).build();

		try (SafeCloseable ignored = ctx.push()) {
			final Listener<GrpcRestoreCatalogRequest> listener = new ObservabilityInterceptor().interceptCall(
				new MockServerCall<>(method), new Metadata(), recordingHandler()
			);
			assertNotNull(listener, "the interceptor did not start the downstream call");

			listener.onMessage(GrpcRestoreCatalogRequest.getDefaultInstance());
			listener.onHalfClose();
			listener.onComplete();
			listener.onCancel();

			return new InterceptedCall(listener, Long.toString(ctx.log().partial().requestStartTimeMillis()));
		}
	}

	@Nested
	@DisplayName("inside a served request")
	class InsideARequest {

		@Test
		@DisplayName("records the request start inside startCall and inside every pushed listener callback")
		void shouldRecordRequestStartAtEverySeam() {
			final String expected = interceptAndDriveCallbacks().requestStart();

			assertEquals(
				expected, ObservabilityInterceptorRequestStartTest.this.observed.get(START_CALL),
				"startCall - client-streaming method bodies run here"
			);
			assertEquals(expected, ObservabilityInterceptorRequestStartTest.this.observed.get(ON_MESSAGE));
			assertEquals(expected, ObservabilityInterceptorRequestStartTest.this.observed.get(ON_HALF_CLOSE));
			assertEquals(expected, ObservabilityInterceptorRequestStartTest.this.observed.get(ON_COMPLETE));
			assertEquals(expected, ObservabilityInterceptorRequestStartTest.this.observed.get(ON_CANCEL));
		}

		@Test
		@DisplayName("leaves the MDC clean once the call is over")
		void shouldNotLeakRequestStartOntoThePooledThread() {
			interceptAndDriveCallbacks();

			assertTrue(
				ObservabilityInterceptorRequestStartTest.this.observed.containsKey(START_CALL),
				"the interceptor never invoked the downstream handler, so nothing could have leaked"
			);
			assertNull(
				MDC.get(TracingContext.MDC_REQUEST_START_PROPERTY),
				"the request start outlived the call and would be attributed to the next request this thread serves"
			);
		}

		@Test
		@DisplayName("records the request start in the one callback invoked without the request context pushed")
		void shouldRecordRequestStartInOnReady() {
			final InterceptedCall call = interceptAndDriveCallbacks();

			// nothing may be left in the MDC here, or this case would pass on a leak rather than on the listener
			// having kept the request start it saw when the call arrived
			assertNull(
				MDC.get(TracingContext.MDC_REQUEST_START_PROPERTY),
				"the earlier callbacks leaked the request start onto this thread"
			);

			// driven outside the pushed context, which is how Armeria invokes it - the request start is therefore
			// not readable from Armeria at this point and has to come from the intercepted call
			call.listener().onReady();

			assertTrue(
				ObservabilityInterceptorRequestStartTest.this.observed.containsKey(ON_READY),
				"the listener never delegated onReady downstream"
			);
			assertEquals(
				call.requestStart(), ObservabilityInterceptorRequestStartTest.this.observed.get(ON_READY),
				"onReady - a server-streaming producer driven from here logs without a duration otherwise"
			);
		}
	}

	@Nested
	@DisplayName("outside any served request")
	class OutsideARequest {

		@Test
		@DisplayName("records nothing when no request context is current")
		void shouldRecordNothingOutsideARequest() {
			final Listener<GrpcRestoreCatalogRequest> listener = new ObservabilityInterceptor().interceptCall(
				new MockServerCall<>(clientStreamingMethod()), new Metadata(), recordingHandler()
			);

			assertNotNull(listener, "the interceptor did not start the downstream call");
			// an absent key and a key mapped to null are indistinguishable through `get`, so the presence of the
			// entry is what separates "the handler ran and saw nothing" from "the handler never ran at all"
			assertTrue(
				ObservabilityInterceptorRequestStartTest.this.observed.containsKey(START_CALL),
				"the interceptor never invoked the downstream handler"
			);
			assertNull(ObservabilityInterceptorRequestStartTest.this.observed.get(START_CALL));
		}
	}
}
