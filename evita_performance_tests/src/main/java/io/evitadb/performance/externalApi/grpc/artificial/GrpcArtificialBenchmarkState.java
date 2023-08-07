/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
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

package io.evitadb.performance.externalApi.grpc.artificial;

import io.evitadb.driver.certificate.ClientCertificateManager;
import io.evitadb.externalApi.configuration.AbstractApiConfiguration;
import io.evitadb.externalApi.grpc.configuration.GrpcConfig;
import io.evitadb.externalApi.grpc.generated.EvitaServiceGrpc;
import io.evitadb.externalApi.grpc.generated.EvitaSessionServiceGrpc;
import io.evitadb.externalApi.grpc.generated.EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub;
import io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest;
import io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse;
import io.evitadb.externalApi.system.configuration.SystemConfig;
import io.evitadb.performance.artificial.AbstractArtificialBenchmarkState;
import io.grpc.ManagedChannel;
import io.grpc.netty.NettyChannelBuilder;
import io.netty.handler.ssl.SslContext;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Setup;

import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Base state class for all artificial based benchmarks.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public abstract class GrpcArtificialBenchmarkState extends AbstractArtificialBenchmarkState<EvitaSessionServiceBlockingStub> {

	private static final String HOST = AbstractApiConfiguration.LOCALHOST;
	private static final int PORT = GrpcConfig.DEFAULT_GRPC_PORT;

	private final ClientCertificateManager clientCertificateManager = new ClientCertificateManager.Builder().build();
	private SslContext sslContext;

	@Setup(Level.Trial)
	public void setUp() {
		clientCertificateManager.getCertificatesFromServer(HOST, SystemConfig.DEFAULT_SYSTEM_PORT);
		sslContext = clientCertificateManager.buildClientSslContext();
	}

	/**
	 * Returns an existing session unique for the thread or creates new one.
	 */
	public EvitaSessionServiceBlockingStub getSession() {
		return getSession(() -> {
			final ManagedChannel grpcEvitaChannel = NettyChannelBuilder.forAddress(HOST, PORT)
				.sslContext(sslContext)
				.build();

			final EvitaServiceGrpc.EvitaServiceBlockingStub evitaClient = EvitaServiceGrpc.newBlockingStub(grpcEvitaChannel);
			final GrpcEvitaSessionResponse response = evitaClient.createReadOnlySession(GrpcEvitaSessionRequest.newBuilder()
				.setCatalogName(TEST_CATALOG)
				.build());
			grpcEvitaChannel.shutdown();

			final ManagedChannel channel = NettyChannelBuilder.forAddress(HOST, PORT)
				.sslContext(sslContext)
				.intercept(new JmhClientSessionInterceptor(response.getSessionId()))
				.executor(Executors.newCachedThreadPool())
				.defaultLoadBalancingPolicy("round_robin")
				.build();

			return EvitaSessionServiceGrpc.newBlockingStub(channel);
		});
	}

	/**
	 * Returns an existing session unique for the thread or creates new one.
	 */
	public EvitaSessionServiceBlockingStub getSession(Supplier<EvitaSessionServiceBlockingStub> creatorFct) {
		final EvitaSessionServiceBlockingStub session = this.session.get();
		if (session == null) {
			final EvitaSessionServiceBlockingStub createdSession = creatorFct.get();
			this.session.set(createdSession);
			return createdSession;
		} else {
			return session;
		}
	}
}