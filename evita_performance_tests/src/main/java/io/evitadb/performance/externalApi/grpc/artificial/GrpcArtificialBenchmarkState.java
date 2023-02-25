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

import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Base state class for all artificial based benchmarks.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public abstract class GrpcArtificialBenchmarkState extends AbstractArtificialBenchmarkState<EvitaSessionServiceBlockingStub> {

	/**
	 * Returns an existing session unique for the thread or creates new one.
	 */
	public EvitaSessionServiceBlockingStub getSession() {
		final String host = AbstractApiConfiguration.LOCALHOST;
		final int port = GrpcConfig.DEFAULT_GRPC_PORT;
		final ClientCertificateManager clientCertificateManager = new ClientCertificateManager.Builder().build();
		clientCertificateManager.getCertificatesFromServer(host, SystemConfig.DEFAULT_SYSTEM_PORT);
		return getSession(() -> {
			final SslContext sslContext = clientCertificateManager.buildClientSslContext();
			final ManagedChannel grpcEvitaChannel = NettyChannelBuilder.forAddress(host, port)
				.sslContext(sslContext)
				.build();

			final EvitaServiceGrpc.EvitaServiceBlockingStub evitaClient = EvitaServiceGrpc.newBlockingStub(grpcEvitaChannel);
			final GrpcEvitaSessionResponse response = evitaClient.createReadOnlySession(GrpcEvitaSessionRequest.newBuilder()
				.setCatalogName(TEST_CATALOG)
				.build());
			grpcEvitaChannel.shutdown();

			final ManagedChannel channel = NettyChannelBuilder.forAddress(host, port)
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