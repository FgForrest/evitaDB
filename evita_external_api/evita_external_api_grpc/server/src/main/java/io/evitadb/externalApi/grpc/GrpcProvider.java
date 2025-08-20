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

package io.evitadb.externalApi.grpc;

import com.google.protobuf.Empty;
import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.grpc.GrpcClients;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.docs.DocService;
import com.linecorp.armeria.server.docs.DocServiceFilter;
import io.evitadb.externalApi.configuration.ApiOptions;
import io.evitadb.externalApi.configuration.HostDefinition;
import io.evitadb.externalApi.configuration.TlsMode;
import io.evitadb.externalApi.event.ReadinessEvent;
import io.evitadb.externalApi.event.ReadinessEvent.Prospective;
import io.evitadb.externalApi.event.ReadinessEvent.Result;
import io.evitadb.externalApi.grpc.configuration.GrpcOptions;
import io.evitadb.externalApi.grpc.generated.EvitaServiceGrpc.EvitaServiceBlockingStub;
import io.evitadb.externalApi.http.ExternalApiProvider;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;

/**
 * Descriptor of external API provider that provides gRPC API.
 *
 * @author Tomáš Pozler, 2022
 * @see GrpcProviderRegistrar
 */
@Slf4j
public class GrpcProvider implements ExternalApiProvider<GrpcOptions> {

	public static final String CODE = "gRPC";

	@Nonnull
	@Getter
	private final GrpcOptions configuration;

	@Nonnull
	@Getter
	private final HttpService apiHandler;
	/**
	 * Timeout taken from {@link ApiOptions#requestTimeoutInMillis()} that will be used in {@link #checkReachable(String)}
	 * method.
	 */
	private final long requestTimeout;
	/**
	 * Contains url that was at least once found reachable.
	 */
	private String reachableUrl;
	/**
	 * Builder for gRPC client factory.
	 */
	private final ClientFactory clientFactory;

	public GrpcProvider(@Nonnull GrpcOptions configuration, @Nonnull HttpService apiHandler, long requestTimeout, long idleTimeout) {
		this.configuration = configuration;
		this.apiHandler = apiHandler;
		this.requestTimeout = requestTimeout;
		this.clientFactory = ClientFactory.builder()
			// 1 second timeout for connection establishment
			.connectTimeoutMillis(requestTimeout)
			// 1 second timeout for idle connections
			.idleTimeoutMillis(idleTimeout)
			.tlsNoVerify()
			.build();
	}

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
		if (this.configuration.isExposeDocsService()) {
			final DocService docService = DocService.builder()
				.exclude(DocServiceFilter.ofServiceName("grpc.reflection.v1alpha.ServerReflection"))
				.build();

			return new HttpServiceDefinition[]{
				new HttpServiceDefinition(this.apiHandler, PathHandlingMode.FIXED_PATH_HANDLING),
				new HttpServiceDefinition("grpc/doc", docService, PathHandlingMode.FIXED_PATH_HANDLING)
			};
		} else {
			return new HttpServiceDefinition[]{
				new HttpServiceDefinition(this.apiHandler, PathHandlingMode.FIXED_PATH_HANDLING)
			};
		}
	}

	@Override
	public boolean isReady() {
		if (this.reachableUrl != null) {
			if (checkReachable(this.reachableUrl)) {
				return true;
			}
		}

		for (HostDefinition hostDefinition : this.configuration.getHost()) {
			final String uriScheme = this.configuration.getTlsMode() != TlsMode.FORCE_NO_TLS ? "https" : "http";

			final String uri = uriScheme + "://" + hostDefinition.hostAddressWithPort() + "/";
			if (!uri.equals(this.reachableUrl) && checkReachable(uri)) {
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
		final ReadinessEvent readinessEvent = new ReadinessEvent(CODE, Prospective.CLIENT);
		try {
			final EvitaServiceBlockingStub evitaService = GrpcClients.builder(uri)
				.factory(this.clientFactory)
				.responseTimeoutMillis(this.requestTimeout)
				.writeTimeoutMillis(this.requestTimeout)
				.build(EvitaServiceBlockingStub.class);
			if (evitaService.isReady(Empty.newBuilder().build()).getReady()) {
				this.reachableUrl = uri;
				readinessEvent.finish(Result.READY);
				return true;
			} else {
				readinessEvent.finish(Result.ERROR);
				log.error("gRPC API is not ready at: {}", uri);
				return false;
			}
		} catch (StatusRuntimeException e) {
			if (e.getStatus().getCode() == Status.Code.DEADLINE_EXCEEDED) {
				readinessEvent.finish(Result.TIMEOUT);
				log.error("Timeout while checking readiness of gRPC API at: {}", uri);
			} else {
				readinessEvent.finish(Result.ERROR);
				log.error("Error while checking readiness of gRPC API: {}", e.getMessage());
			}
			return false;
		} catch (Exception e) {
			readinessEvent.finish(Result.ERROR);
			log.error("Error while checking readiness of gRPC API: {}", e.getMessage());
			return false;
		}
	}

}
