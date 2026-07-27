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

package io.evitadb.externalApi.grpc.interceptor;

import io.evitadb.externalApi.grpc.services.interceptors.ServerSessionInterceptor;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.io.InputStream;

import static io.evitadb.test.TestTags.GRPC;
import static io.evitadb.test.TestTags.SESSION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies which gRPC endpoints {@link ServerSessionInterceptor} lets through without an active
 * session.
 *
 * The interceptor is default-deny, so an endpoint that never reads the session is unreachable
 * unless it was exempted. Getting that wrong fails at call time with
 * {@link Status#UNAUTHENTICATED}, which the client reports as a terminated session - a message that
 * points at session lifecycle rather than at the missing exemption. These tests pin the matching
 * itself rather than that downstream symptom.
 *
 * No session header is supplied by any test, which is what makes the exemption decision the only
 * thing under test.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(GRPC)
@Tag(SESSION)
@DisplayName("gRPC session interceptor - should")
class ServerSessionInterceptorTest {
	private static final String GENERATED_PACKAGE = "io.evitadb.externalApi.grpc.generated.";

	@Test
	@DisplayName("let a wildcarded management endpoint through without a session")
	void shouldNotRequireSessionForManagementEndpoint() {
		assertPassesWithoutSession(GENERATED_PACKAGE + "EvitaManagementService/GetEngineSettings");
	}

	@Test
	@DisplayName("let a management endpoint that did not exist when the rule was written through")
	void shouldNotRequireSessionForFutureManagementEndpoint() {
		// the point of the wildcard - a method added later must not start out unreachable
		assertPassesWithoutSession(GENERATED_PACKAGE + "EvitaManagementService/SomeLaterAddedMethod");
	}

	@Test
	@DisplayName("let the progress-reporting catalog endpoints through without a session")
	void shouldNotRequireSessionForProgressEndpoints() {
		// these three operate directly on the Evita instance, yet used to demand a session while
		// their non-progress siblings did not
		assertPassesWithoutSession(GENERATED_PACKAGE + "EvitaService/RenameCatalogWithProgress");
		assertPassesWithoutSession(GENERATED_PACKAGE + "EvitaService/ReplaceCatalogWithProgress");
		assertPassesWithoutSession(GENERATED_PACKAGE + "EvitaService/GetProgress");
	}

	@Test
	@DisplayName("let an individually registered session endpoint through without a session")
	void shouldNotRequireSessionForExactlyRegisteredEndpoint() {
		// the session may already be closed by the time these arrive
		assertPassesWithoutSession(GENERATED_PACKAGE + "EvitaSessionService/Close");
	}

	@Test
	@DisplayName("reject a session-scoped endpoint that has no session")
	void shouldRequireSessionForSessionScopedEndpoint() {
		// this one receives the session and would work on null without the guard
		assertRejectedWithoutSession(GENERATED_PACKAGE + "EvitaSessionService/Query");
	}

	@Test
	@DisplayName("reject traffic recording endpoints that have no session")
	void shouldRequireSessionForTrafficRecordingEndpoints() {
		// traffic recording is scoped by the session, so it must stay behind the guard
		assertRejectedWithoutSession(
			GENERATED_PACKAGE + "GrpcEvitaTrafficRecordingService/StartTrafficRecording"
		);
	}

	@Test
	@DisplayName("reject an endpoint of a service that was never exempted")
	void shouldRequireSessionForUnknownService() {
		assertRejectedWithoutSession(GENERATED_PACKAGE + "SomeOtherService/DoSomething");
	}

	/**
	 * Runs the interceptor against the given method and asserts the call reached the handler.
	 *
	 * @param fullMethodName fully qualified `<service>/<method>` name, must not be null
	 */
	private static void assertPassesWithoutSession(@Nonnull String fullMethodName) {
		final RecordingServerCall serverCall = new RecordingServerCall(fullMethodName);
		final RecordingHandler handler = new RecordingHandler();

		interceptor().interceptCall(serverCall, new Metadata(), handler);

		assertTrue(handler.invoked, "Call to `" + fullMethodName + "` did not reach the handler!");
		assertNull(
			serverCall.closedStatus,
			"Call to `" + fullMethodName + "` was closed with " + serverCall.closedStatus + "!"
		);
	}

	/**
	 * Runs the interceptor against the given method and asserts the call was refused as
	 * unauthenticated before reaching the handler.
	 *
	 * @param fullMethodName fully qualified `<service>/<method>` name, must not be null
	 */
	private static void assertRejectedWithoutSession(@Nonnull String fullMethodName) {
		final RecordingServerCall serverCall = new RecordingServerCall(fullMethodName);
		final RecordingHandler handler = new RecordingHandler();

		interceptor().interceptCall(serverCall, new Metadata(), handler);

		assertEquals(
			Status.Code.UNAUTHENTICATED,
			serverCall.closedStatus == null ? null : serverCall.closedStatus.getCode(),
			"Call to `" + fullMethodName + "` was not refused!"
		);
		assertTrue(!handler.invoked, "Call to `" + fullMethodName + "` reached the handler!");
	}

	/**
	 * Creates the tested interceptor. The engine reference stays null on purpose - no test supplies
	 * a session id, and the interceptor only resolves a session when one was sent, so the engine is
	 * never touched.
	 *
	 * @return interceptor under test
	 */
	@Nonnull
	private static ServerSessionInterceptor interceptor() {
		return new ServerSessionInterceptor(null);
	}

	/**
	 * Builds a descriptor carrying nothing but the method name the interceptor matches on.
	 *
	 * @param fullMethodName fully qualified `<service>/<method>` name, must not be null
	 * @return descriptor for the named method
	 */
	@Nonnull
	private static MethodDescriptor<byte[], byte[]> methodDescriptor(@Nonnull String fullMethodName) {
		return MethodDescriptor.<byte[], byte[]>newBuilder()
			.setType(MethodType.UNARY)
			.setFullMethodName(fullMethodName)
			.setRequestMarshaller(PassThroughMarshaller.INSTANCE)
			.setResponseMarshaller(PassThroughMarshaller.INSTANCE)
			.build();
	}

	/**
	 * Marshaller that carries the payload unchanged - the tests never send one.
	 */
	private static class PassThroughMarshaller implements MethodDescriptor.Marshaller<byte[]> {
		private static final PassThroughMarshaller INSTANCE = new PassThroughMarshaller();

		@Override
		public InputStream stream(byte[] value) {
			return new ByteArrayInputStream(value);
		}

		@Override
		public byte[] parse(InputStream stream) {
			return new byte[0];
		}
	}

	/**
	 * Server call that records the status it was closed with, if any.
	 */
	private static class RecordingServerCall extends ServerCall<byte[], byte[]> {
		private final MethodDescriptor<byte[], byte[]> methodDescriptor;
		@Nullable private Status closedStatus;

		RecordingServerCall(@Nonnull String fullMethodName) {
			this.methodDescriptor = methodDescriptor(fullMethodName);
		}

		@Override
		public void request(int numMessages) {
			// no payload is exchanged in these tests
		}

		@Override
		public void sendHeaders(Metadata headers) {
			// headers are irrelevant to the exemption decision
		}

		@Override
		public void sendMessage(byte[] message) {
			// no payload is exchanged in these tests
		}

		@Override
		public void close(Status status, Metadata trailers) {
			this.closedStatus = status;
		}

		@Override
		public boolean isCancelled() {
			return false;
		}

		@Override
		public MethodDescriptor<byte[], byte[]> getMethodDescriptor() {
			return this.methodDescriptor;
		}
	}

	/**
	 * Call handler that records whether the interceptor let the call through.
	 */
	private static class RecordingHandler implements ServerCallHandler<byte[], byte[]> {
		private boolean invoked;

		@Override
		public ServerCall.Listener<byte[]> startCall(ServerCall<byte[], byte[]> call, Metadata headers) {
			this.invoked = true;
			return new ServerCall.Listener<>() {
			};
		}
	}

}
