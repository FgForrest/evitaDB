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

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.common.grpc.protocol.GrpcHeaderNames;
import com.linecorp.armeria.server.cors.CorsService;
import com.linecorp.armeria.server.cors.CorsServiceBuilder;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.server.grpc.GrpcServiceBuilder;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.configuration.ApiOptions;
import io.evitadb.externalApi.grpc.configuration.GrpcOptions;
import io.evitadb.externalApi.grpc.services.EvitaManagementService;
import io.evitadb.externalApi.grpc.services.EvitaService;
import io.evitadb.externalApi.grpc.services.EvitaSessionService;
import io.evitadb.externalApi.grpc.services.EvitaTrafficRecordingService;
import io.evitadb.externalApi.grpc.services.interceptors.GlobalExceptionHandlerInterceptor;
import io.evitadb.externalApi.grpc.services.interceptors.ObservabilityInterceptor;
import io.evitadb.externalApi.grpc.services.interceptors.ServerSessionInterceptor;
import io.evitadb.externalApi.http.ExternalApiProvider;
import io.evitadb.externalApi.http.ExternalApiProviderRegistrar;
import io.evitadb.externalApi.http.ExternalApiServer;
import io.grpc.protobuf.services.ProtoReflectionService;

import javax.annotation.Nonnull;

/**
 * Registers gRPC API provider.
 *
 * @author Tomáš Pozler, 2022
 */
public class GrpcProviderRegistrar implements ExternalApiProviderRegistrar<GrpcOptions> {

	@Nonnull
	@Override
	public String getExternalApiCode() {
		return GrpcProvider.CODE;
	}

	@Nonnull
	@Override
	public Class<GrpcOptions> getConfigurationClass() {
		return GrpcOptions.class;
	}

	@Nonnull
	@Override
	public ExternalApiProvider<GrpcOptions> register(
		@Nonnull Evita evita,
		@Nonnull ExternalApiServer externalApiServer,
		@Nonnull ApiOptions apiOptions,
		@Nonnull GrpcOptions grpcAPIConfig
	) {
		final GrpcServiceBuilder grpcServiceBuilder = GrpcService.builder()
			.addService(new EvitaService(evita, apiOptions.headers()))
			.addService(new EvitaManagementService(evita, externalApiServer, apiOptions.headers()))
			.addService(new EvitaSessionService(evita, apiOptions.headers()))
			.addService(new EvitaTrafficRecordingService(evita, apiOptions.headers()))
			.addService(ProtoReflectionService.newInstance())
			.intercept(new ServerSessionInterceptor(evita))
			.intercept(new GlobalExceptionHandlerInterceptor())
			.intercept(new ObservabilityInterceptor())
			.supportedSerializationFormats(GrpcSerializationFormats.values())
			.enableHttpJsonTranscoding(true)
			.enableUnframedRequests(true)
			.useClientTimeoutHeader(true);

		final CorsServiceBuilder corsBuilder = CorsService.builderForAnyOrigin()
			.allowRequestMethods(HttpMethod.POST) // Allow POST method.
			// Allow all request headers to ensure proper Metadata to HTTP headers conversion in gRPC-Web.
			// Because of the variability of such headers, it is not wise to list them all and maintain it.
			.allowAllRequestHeaders(true)
			// Expose trailers of the HTTP response to the client.
			.exposeHeaders(GrpcHeaderNames.GRPC_STATUS,
				GrpcHeaderNames.GRPC_MESSAGE,
				GrpcHeaderNames.ARMERIA_GRPC_THROWABLEPROTO_BIN);

		final GrpcService grpcService = grpcServiceBuilder.build();

		return new GrpcProvider(
			grpcAPIConfig,
			corsBuilder.build(grpcService),
			apiOptions.requestTimeoutInMillis(),
			apiOptions.idleTimeoutInMillis()
		);
	}
}
