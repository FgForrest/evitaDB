/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.performance.externalApi.grpc.artificial;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientFactoryBuilder;
import com.linecorp.armeria.client.grpc.GrpcClientBuilder;
import com.linecorp.armeria.client.grpc.GrpcClients;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import io.evitadb.externalApi.grpc.certificate.ClientCertificateManager;
import io.evitadb.externalApi.grpc.configuration.GrpcOptions;
import io.evitadb.externalApi.grpc.generated.EvitaServiceGrpc;
import io.evitadb.externalApi.grpc.generated.EvitaSessionServiceGrpc;
import io.evitadb.externalApi.grpc.generated.EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub;
import io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest;
import io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse;
import io.evitadb.externalApi.system.configuration.SystemOptions;
import io.evitadb.performance.artificial.AbstractArtificialBenchmarkState;

import java.util.function.Supplier;

/**
 * Base state class for all artificial based benchmarks.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public abstract class GrpcArtificialBenchmarkState extends AbstractArtificialBenchmarkState<EvitaSessionServiceBlockingStub> {

	private static final String HOST = "localhost";
	private static final int PORT = GrpcOptions.DEFAULT_GRPC_PORT;

	private ClientCertificateManager clientCertificateManager;

	public void setUp() {
		this.clientCertificateManager = new ClientCertificateManager.Builder()
			.useGeneratedCertificate(true, HOST, SystemOptions.DEFAULT_SYSTEM_PORT)
			.build();
	}

	/**
	 * Returns an existing session unique for the thread or creates new one.
	 */
	@Override
	public EvitaSessionServiceBlockingStub getSession() {
		return getSession(() -> {
			final ClientFactoryBuilder clientFactoryBuilder = this.clientCertificateManager
				.buildClientSslContext(
					null,
					ClientFactory.builder()
						.useHttp1Pipelining(true)
						.idleTimeoutMillis(10000, true)
						.maxNumRequestsPerConnection(1000)
						.maxNumEventLoopsPerEndpoint(10)
				);


			final ClientFactory clientFactory = clientFactoryBuilder.build();

			final GrpcClientBuilder clientBuilder = GrpcClients.builder("https://" + HOST + ":" + PORT + "/")
				.factory(clientFactory)
				.serializationFormat(GrpcSerializationFormats.PROTO)
				.responseTimeoutMillis(10000);

			final EvitaServiceGrpc.EvitaServiceBlockingStub evitaClient = clientBuilder.build(EvitaServiceGrpc.EvitaServiceBlockingStub.class);
			final GrpcEvitaSessionResponse response = evitaClient.createReadOnlySession(GrpcEvitaSessionRequest.newBuilder()
				.setCatalogName(TEST_CATALOG)
				.build());

			final GrpcClientBuilder clientBuilderWithJmhInterceptor = GrpcClients.builder("https://" + HOST + ":" + PORT + "/")
				.factory(clientFactory)
				.serializationFormat(GrpcSerializationFormats.PROTO)
				.responseTimeoutMillis(10000)
				.intercept(new JmhClientSessionInterceptor(response.getSessionId()));

			return clientBuilderWithJmhInterceptor.build(EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub.class);
		});
	}

	/**
	 * Returns an existing session unique for the thread or creates new one.
	 */
	@Override
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
