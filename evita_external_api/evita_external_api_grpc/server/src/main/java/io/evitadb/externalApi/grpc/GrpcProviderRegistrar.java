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

import com.linecorp.armeria.common.ContentTooLargeException;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.grpc.GrpcExceptionHandlerFunction;
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
import io.evitadb.utils.ExceptionUtils;
import io.grpc.Status;
import io.grpc.protobuf.services.ProtoReflectionService;

import javax.annotation.Nonnull;

/**
 * Registers gRPC API provider.
 *
 * @author Tomáš Pozler, 2022
 */
public class GrpcProviderRegistrar implements ExternalApiProviderRegistrar<GrpcOptions> {

	/**
	 * Turns Armeria's "request body too large" rejection into a status a client can act on.
	 *
	 * A client-streaming upload travels as a **single** HTTP request, so `maxRequestLength` - which
	 * evitaDB wires to `api.maxEntitySizeInBytes` - bounds the whole upload rather than one message.
	 * `RestoreCatalog` is the RPC that runs into it, and Armeria's default mapping is a bare
	 * `RESOURCE_EXHAUSTED` with no description, indistinguishable from any other resource failure - the
	 * client cannot tell that it hit a configured limit, let alone which one or what to do instead.
	 *
	 * Returning `null` for anything else hands the exception back to Armeria's default handler.
	 */
	private static final GrpcExceptionHandlerFunction OVERSIZED_REQUEST_HANDLER =
		(ctx, status, cause, metadata) -> {
			final ContentTooLargeException tooLarge = cause == null ?
				null : ExceptionUtils.findInCauseChain(cause, ContentTooLargeException.class);
			return tooLarge == null ?
				null :
				Status.RESOURCE_EXHAUSTED
					.withDescription(
						"Request body exceeds the server limit of " + tooLarge.maxContentLength() +
							" B (`api.maxEntitySizeInBytes`). A client-streaming upload is one request, so " +
							"the limit applies to the whole upload - send a large catalog backup through " +
							"`RestoreCatalogUnary` instead, where every chunk is a request of its own."
					)
					.withCause(tooLarge);
		};

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
			.addService(new EvitaManagementService(
				evita, externalApiServer, apiOptions.headers(), grpcAPIConfig.getStreamingRequestTimeoutInMillis()
			))
			.addService(new EvitaSessionService(
				evita, apiOptions.headers(), grpcAPIConfig.getStreamingRequestTimeoutInMillis()
			))
			.addService(new EvitaTrafficRecordingService(
				evita, apiOptions.headers(), grpcAPIConfig.getStreamingRequestTimeoutInMillis()
			))
			.addService(ProtoReflectionService.newInstance())
			.intercept(new ServerSessionInterceptor(evita))
			.intercept(new GlobalExceptionHandlerInterceptor())
			.intercept(new ObservabilityInterceptor())
			.supportedSerializationFormats(GrpcSerializationFormats.values())
			.enableHttpJsonTranscoding(true)
			.enableUnframedRequests(true)
			.useClientTimeoutHeader(true)
			.exceptionHandler(OVERSIZED_REQUEST_HANDLER.orElse(GrpcExceptionHandlerFunction.of()));

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
