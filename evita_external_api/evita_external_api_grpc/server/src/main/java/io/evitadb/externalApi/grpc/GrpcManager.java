/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.cors.CorsService;
import com.linecorp.armeria.server.cors.CorsServiceBuilder;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.server.grpc.GrpcServiceBuilder;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.configuration.ApiOptions;
import io.evitadb.externalApi.grpc.configuration.GrpcConfig;
import io.evitadb.externalApi.grpc.services.EvitaService;
import io.evitadb.externalApi.grpc.services.EvitaSessionService;
import io.evitadb.externalApi.grpc.services.interceptors.GlobalExceptionHandlerInterceptor;
import io.evitadb.externalApi.grpc.services.interceptors.ObservabilityInterceptor;
import io.evitadb.externalApi.grpc.services.interceptors.ServerSessionInterceptor;
import io.evitadb.externalApi.http.PathNormalizingHandler;
import io.evitadb.externalApi.utils.RoutingHandlerService;
import io.grpc.protobuf.services.ProtoReflectionService;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;

@Slf4j
public class GrpcManager {
	@Nonnull private final Evita evita;
	@Nonnull private final ApiOptions apiOptions;
	@Nonnull private final GrpcConfig grpcConfig;

	@Nonnull private final RoutingHandlerService grpcRouter = new RoutingHandlerService();

	public GrpcManager(@Nonnull Evita evita, @Nonnull ApiOptions apiOptions, @Nonnull GrpcConfig grpcConfig) {
		this.evita = evita;
		this.apiOptions = apiOptions;
		this.grpcConfig = grpcConfig;
	}

	@Nonnull
	public HttpService getGrpcRouter() {
		return new PathNormalizingHandler(grpcRouter);
	}

	private void registerGrpcApi() {
		final GrpcServiceBuilder grpcServiceBuilder = GrpcService.builder()
			.addService(new EvitaService(evita))
			.addService(new EvitaSessionService(evita))
			.addService(ProtoReflectionService.newInstance())
			.intercept(new ServerSessionInterceptor(evita))
			.intercept(new GlobalExceptionHandlerInterceptor())
			.intercept(new ObservabilityInterceptor(apiOptions.accessLog()))
			.supportedSerializationFormats(GrpcSerializationFormats.values())
			.enableUnframedRequests(true);

		final CorsServiceBuilder corsBuilder =
			CorsService.builderForAnyOrigin()
				.allowRequestMethods(HttpMethod.POST) // Allow POST method.
				// Allow Content-type and X-GRPC-WEB headers.
				.allowAllRequestHeaders(true)
				/*.allowAllRequestHeaders(HttpHeaderNames.CONTENT_TYPE,
						HttpHeaderNames.of("X-GRPC-WEB"), "X-User-Agent")*/
				// Expose trailers of the HTTP response to the client.
				.exposeHeaders(GrpcHeaderNames.GRPC_STATUS,
					GrpcHeaderNames.GRPC_MESSAGE,
					GrpcHeaderNames.ARMERIA_GRPC_THROWABLEPROTO_BIN);

		final GrpcService grpcService = grpcServiceBuilder.build();

		// Register gRPC API
		grpcRouter.add(grpcService, corsBuilder.newDecorator());
	}
}
