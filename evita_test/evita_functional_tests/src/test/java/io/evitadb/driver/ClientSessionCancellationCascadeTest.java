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

package io.evitadb.driver;

import com.linecorp.armeria.client.grpc.GrpcClientBuilder;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.driver.config.ClientConnectionOptions;
import io.evitadb.driver.config.ClientTimeoutOptions;
import io.evitadb.driver.config.ClientTlsOptions;
import io.evitadb.driver.config.EvitaClientConfiguration;
import io.evitadb.driver.exception.TransportException;
import io.evitadb.externalApi.configuration.ApiOptions;
import io.evitadb.externalApi.configuration.HostDefinition;
import io.evitadb.externalApi.grpc.GrpcProvider;
import io.evitadb.externalApi.system.SystemProvider;
import io.evitadb.server.EvitaServer;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.TestConstants;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.EvitaParameterResolver;
import io.evitadb.utils.CertificateUtils;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.annotation.Nullable;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.entityFetch;
import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyInSet;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.require;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.DRIVER;
import static io.evitadb.test.TestTags.GRPC;
import static io.evitadb.test.TestTags.SESSION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the driver's handling of the client-session cancellation cascade: when a session call dies at the
 * transport level (a dropped / cancelled connection), the driver must treat the session as lost locally and
 * must NOT issue a remote close that would race the orphaned server-side invocation and trigger a
 * `ConcurrentSessionAccessException`.
 *
 * The transport failure is injected deterministically with a client-side {@link ClientInterceptor} that fails
 * a chosen unary call with {@link Status#CANCELLED} — simulating a mid-call connection drop — without touching
 * the wire, and counts any further RPC the driver attempts on the dead session.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Client session cancellation cascade")
@Tag(DRIVER)
@Tag(GRPC)
@Tag(SESSION)
@ExtendWith(EvitaParameterResolver.class)
class ClientSessionCancellationCascadeTest implements TestConstants, EvitaTestSupport {
	private static final String DATA_SET_CANCELLATION_CASCADE = "clientSessionCancellationCascade";

	/**
	 * Builds a minimal catalog with two products reachable by primary key and keeps the setup client open for the
	 * lifetime of the dataset. The catalog stays in the WARM_UP state — queries by primary key work there and the
	 * read path needs no transaction, keeping the reproduction focused on the transport-failure handling.
	 */
	@DataSet(value = DATA_SET_CANCELLATION_CASCADE, openWebApi = {GrpcProvider.CODE, SystemProvider.CODE}, readOnly = false, destroyAfterClass = true)
	static EvitaClient initDataSet(EvitaServer evitaServer) {
		final EvitaClient setupClient = new EvitaClient(
			clientConfiguration(evitaServer, ClientConnectionOptions.DEFAULT_PING_INTERVAL_MILLIS)
		);
		setupClient.defineCatalog(TEST_CATALOG);
		setupClient.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.createNewEntity(Entities.PRODUCT, 1).upsertVia(session);
				session.createNewEntity(Entities.PRODUCT, 2).upsertVia(session);
			}
		);
		return setupClient;
	}

	@Test
	@UseDataSet(DATA_SET_CANCELLATION_CASCADE)
	@DisplayName("should treat a mid-call transport failure as session loss and skip the remote close")
	void shouldTreatMidCallTransportFailureAsSessionLoss(EvitaServer evitaServer) {
		final FaultInjectingInterceptor faultInjector = new FaultInjectingInterceptor("QueryOne");
		final AtomicReference<EvitaClientSession> capturedSession = new AtomicReference<>();

		try (final EvitaClient client = new EvitaClient(
			clientConfiguration(evitaServer, ClientConnectionOptions.DEFAULT_PING_INTERVAL_MILLIS),
			(Consumer<GrpcClientBuilder>) builder -> builder.intercept(faultInjector)
		)) {
			// (a) the transport failure is surfaced as a dedicated TransportException, not the CANCELLED-mapped
			// GenericEvitaInternalError, and the original transport cause is preserved
			final TransportException transportException = assertThrows(
				TransportException.class,
				() -> client.queryCatalog(
					TEST_CATALOG,
					session -> {
						capturedSession.set((EvitaClientSession) session);
						faultInjector.arm();
						return session.queryOne(
							query(
								collection(Entities.PRODUCT),
								filterBy(entityPrimaryKeyInSet(1)),
								require(entityFetch())
							),
							SealedEntity.class
						);
					}
				)
			);
			assertNotNull(transportException.getCause(), "the original transport exception must be preserved as the cause");

			// (b) the session is marked inactive locally, without a server round-trip
			assertFalse(
				capturedSession.get().isActive(),
				"the session must be marked inactive locally after a transport failure"
			);
		}

		// the fault must actually have been injected — otherwise the test proves nothing
		assertTrue(faultInjector.wasFaultTriggered(), "the simulated transport failure was never injected");
		// (c) no further RPC for that session after the fault — in particular no remote close, the source of the
		// ConcurrentSessionAccessException cascade
		assertEquals(
			0,
			faultInjector.remoteCloseCallsAfterFault(),
			"the driver must not send a remote close (nor any further RPC) on a transport-failed session"
		);
	}

	/**
	 * Builds an {@link EvitaClientConfiguration} pointing at the running server's gRPC endpoint, with the client
	 * keep-alive ping pinned to the requested interval.
	 */
	private static EvitaClientConfiguration clientConfiguration(EvitaServer evitaServer, int pingIntervalMillis) {
		final ApiOptions apiOptions = evitaServer.getExternalApiServer().getApiOptions();
		final HostDefinition grpcHost = apiOptions.getEndpointConfiguration(GrpcProvider.CODE).getHost()[0];
		final HostDefinition systemHost = apiOptions.getEndpointConfiguration(SystemProvider.CODE).getHost()[0];

		final String serverCertificates = apiOptions.certificate().getFolderPath().toString();
		final int lastDash = serverCertificates.lastIndexOf('-');
		final Path clientCertificates = Path.of(serverCertificates.substring(0, lastDash) + "-client");

		return EvitaClientConfiguration
			.builder()
			.host(grpcHost.hostAddress())
			.port(grpcHost.port())
			.systemApiPort(systemHost.port())
			.pingIntervalMillis(pingIntervalMillis)
			.tls(
				ClientTlsOptions.builder()
					.mtlsEnabled(false)
					.certificateFolderPath(clientCertificates)
					.certificateFileName(Path.of(CertificateUtils.getGeneratedClientCertificateFileName()))
					.certificateKeyFileName(Path.of(CertificateUtils.getGeneratedClientCertificatePrivateKeyFileName()))
					.build()
			)
			.timeouts(
				ClientTimeoutOptions.builder()
					.timeout(10, TimeUnit.MINUTES)
					.build()
			)
			.build();
	}

	/**
	 * gRPC {@link ClientInterceptor} that, once {@link #arm() armed}, fails the next call whose method name contains
	 * {@link #targetMethodSubstring} with {@link Status#CANCELLED} — simulating a mid-call connection drop without
	 * touching the wire. After the fault it counts any close-family RPC the driver attempts, which would betray a
	 * remote close on a session that should have been terminated locally.
	 */
	private static final class FaultInjectingInterceptor implements ClientInterceptor {
		private final String targetMethodSubstring;
		private final AtomicBoolean armed = new AtomicBoolean(false);
		private final AtomicBoolean faultTriggered = new AtomicBoolean(false);
		private final AtomicInteger remoteCloseCallsAfterFault = new AtomicInteger(0);

		FaultInjectingInterceptor(String targetMethodSubstring) {
			this.targetMethodSubstring = targetMethodSubstring;
		}

		void arm() {
			this.armed.set(true);
		}

		boolean wasFaultTriggered() {
			return this.faultTriggered.get();
		}

		int remoteCloseCallsAfterFault() {
			return this.remoteCloseCallsAfterFault.get();
		}

		@Override
		public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
			MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next
		) {
			final String methodName = method.getFullMethodName();
			// after a fault has been injected, any close-family RPC means the driver went remote on a dead session
			if (this.faultTriggered.get() && methodName.contains("Close")) {
				this.remoteCloseCallsAfterFault.incrementAndGet();
			}
			if (methodName.contains(this.targetMethodSubstring) && this.armed.compareAndSet(true, false)) {
				this.faultTriggered.set(true);
				return new ClientCall<>() {
					@Override
					public void start(Listener<RespT> responseListener, Metadata headers) {
						responseListener.onClose(
							Status.CANCELLED.withDescription("simulated mid-call connection drop"), new Metadata()
						);
					}

					@Override
					public void request(int numMessages) {
					}

					@Override
					public void cancel(@Nullable String message, @Nullable Throwable cause) {
					}

					@Override
					public void halfClose() {
					}

					@Override
					public void sendMessage(ReqT message) {
					}
				};
			}
			return next.newCall(method, callOptions);
		}
	}
}
