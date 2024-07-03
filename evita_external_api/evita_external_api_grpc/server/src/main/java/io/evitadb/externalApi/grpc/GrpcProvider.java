/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.externalApi.grpc;

import com.google.protobuf.Empty;
import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientFactoryBuilder;
import com.linecorp.armeria.client.grpc.GrpcClientBuilder;
import com.linecorp.armeria.client.grpc.GrpcClients;
import io.evitadb.externalApi.configuration.ApiOptions;
import com.linecorp.armeria.server.HttpService;
import io.evitadb.externalApi.configuration.HostDefinition;
import io.evitadb.externalApi.configuration.MtlsConfiguration;
import io.evitadb.externalApi.configuration.TlsMode;
import io.evitadb.externalApi.grpc.configuration.GrpcConfig;
import io.evitadb.externalApi.grpc.generated.EvitaServiceGrpc.EvitaServiceBlockingStub;
import io.evitadb.externalApi.http.ExternalApiProvider;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Descriptor of external API provider that provides gRPC API.
 *
 * @author Tomáš Pozler, 2022
 * @see GrpcProviderRegistrar
 */
@Slf4j
@RequiredArgsConstructor
public class GrpcProvider implements ExternalApiProvider<GrpcConfig> {

	public static final String CODE = "gRPC";

	@Nonnull
	private final String serverName;

	@Nonnull
	private final ApiOptions apiOptions;

	@Nonnull
	@Getter
	private final GrpcConfig configuration;

	@Nonnull
	@Getter
	private final HttpService apiHandler;

	/**
	 * Contains url that was at least once found reachable.
	 */
	private String reachableUrl;

	@Nonnull
	@Override
	public String getCode() {
		return CODE;
	}

	@Nullable
	@Override
	public MtlsConfiguration mtlsConfiguration() {
		return this.configuration.getMtlsConfiguration();
	}

	@Override
	public boolean isDocsServiceEnabled() {
		return configuration.isExposeDocsService();
	}

	private GrpcClientBuilder grpcClientBuilder;

	private final ClientFactoryBuilder clientFactoryBuilder = ClientFactory.builder()
		.useHttp1Pipelining(true)
		.idleTimeoutMillis(10000, true)
		.tlsNoVerify()
		.maxNumRequestsPerConnection(1000)
		.maxNumEventLoopsPerEndpoint(10);

	@Override
	public boolean isReady() {
		for (HostDefinition hostDefinition : this.configuration.getHost()) {
			final String uriScheme = configuration.getTlsMode() != TlsMode.FORCE_NO_TLS ? "https" : "http";

			int attempts = 5;
			while (attempts-- > 0) {
				try {
					final EvitaServiceBlockingStub evitaService = GrpcClients.builder(uriScheme + "://" + hostDefinition.hostWithPort() + "/")
						.factory(clientFactoryBuilder.build())
						.responseTimeoutMillis(3000)
						.build(EvitaServiceBlockingStub.class);
					return evitaService.serverStatus(Empty.newBuilder().build()).getUptime() > 0;
				} catch (Exception e) {
					log.error("Error while checking if gRPC server is ready", e);
				}
			}
		}
		return false;
	}
}
