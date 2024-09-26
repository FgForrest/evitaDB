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
import com.linecorp.armeria.client.grpc.GrpcClients;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.docs.DocService;
import com.linecorp.armeria.server.docs.DocServiceFilter;
import io.evitadb.externalApi.configuration.HostDefinition;
import io.evitadb.externalApi.configuration.TlsMode;
import io.evitadb.externalApi.grpc.configuration.GrpcConfig;
import io.evitadb.externalApi.grpc.generated.EvitaServiceGrpc.EvitaServiceBlockingStub;
import io.evitadb.externalApi.http.ExternalApiProvider;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;

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
	@Getter
	private final GrpcConfig configuration;

	@Nonnull
	@Getter
	private final HttpService apiHandler;
	/**
	 * Contains url that was at least once found reachable.
	 */
	private String reachableUrl;
	/**
	 * Builder for gRPC client factory.
	 */
	private final ClientFactory clientFactory = ClientFactory.builder()
		.useHttp1Pipelining(true)
		// 1 second timeout for connection establishment
		.connectTimeoutMillis(1000)
		// 1 second timeout for idle connections
		.idleTimeoutMillis(1000)
		.tlsNoVerify()
		.build();

	@Override
	public void beforeStop() {
		this.clientFactory.close();
	}

	@Nonnull
	@Override
	public String getCode() {
		return CODE;
	}

	@Nonnull
	@Override
	public HttpServiceDefinition[] getHttpServiceDefinitions() {
		if (configuration.isExposeDocsService()) {
			final DocService docService = DocService.builder()
				.exclude(DocServiceFilter.ofServiceName("grpc.reflection.v1alpha.ServerReflection"))
				.build();

			return new HttpServiceDefinition[]{
				new HttpServiceDefinition(apiHandler, PathHandlingMode.FIXED_PATH_HANDLING),
				new HttpServiceDefinition("grpc/doc", docService, PathHandlingMode.FIXED_PATH_HANDLING)
			};
		} else {
			return new HttpServiceDefinition[]{
				new HttpServiceDefinition(apiHandler, PathHandlingMode.FIXED_PATH_HANDLING)
			};
		}
	}

	@Override
	public boolean isReady() {
		if (reachableUrl != null) {
			if (checkReachable(reachableUrl)) {
				return true;
			}
		}

		for (HostDefinition hostDefinition : this.configuration.getHost()) {
			final String uriScheme = configuration.getTlsMode() != TlsMode.FORCE_NO_TLS ? "https" : "http";

			final String uri = uriScheme + "://" + hostDefinition.hostAddressWithPort() + "/";
			if (!uri.equals(reachableUrl) && checkReachable(uri)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Check if the given URI is reachable via gRPC client.
	 * @param uri URI to check
	 * @return true if the URI is reachable, false otherwise
	 */
	public boolean checkReachable(@Nonnull String uri) {
		try {
			final EvitaServiceBlockingStub evitaService = GrpcClients.builder(uri)
				.factory(this.clientFactory)
				.responseTimeoutMillis(100)
				.build(EvitaServiceBlockingStub.class);
			if (evitaService.isReady(Empty.newBuilder().build()).getReady()) {
				this.reachableUrl = uri;
				return true;
			} else {
				log.error("gRPC API is not ready at: {}", uri);
				return false;
			}

		} catch (Exception e) {
			log.error("Error while checking readiness of gRPC API: {}", e.getMessage());
			return false;
		}
	}

}
