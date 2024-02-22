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
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.externalApi.grpc.utils;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.cors.CorsService;
import com.linecorp.armeria.server.cors.CorsServiceBuilder;
import com.linecorp.armeria.common.grpc.protocol.GrpcHeaderNames;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.server.grpc.GrpcServiceBuilder;
import io.evitadb.core.Evita;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.externalApi.certificate.ServerCertificateManager;
import io.evitadb.externalApi.configuration.ApiOptions;
import io.evitadb.externalApi.configuration.CertificatePath;
import io.evitadb.externalApi.configuration.HostDefinition;
import io.evitadb.externalApi.configuration.MtlsConfiguration;
import io.evitadb.externalApi.grpc.configuration.GrpcConfig;
import io.evitadb.externalApi.grpc.services.EvitaService;
import io.evitadb.externalApi.grpc.services.EvitaSessionService;
import io.evitadb.externalApi.grpc.services.interceptors.AccessLogInterceptor;
import io.evitadb.externalApi.grpc.services.interceptors.GlobalExceptionHandlerInterceptor;
import io.evitadb.externalApi.grpc.services.interceptors.ServerSessionInterceptor;
import io.evitadb.externalApi.trace.ExternalApiTracingContextProvider;
import io.evitadb.utils.CertificateUtils;
import io.grpc.ServerInterceptor;
import io.grpc.protobuf.services.ProtoReflectionService;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContextBuilder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.net.ssl.SSLException;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.cert.CertificateFactory;

/**
 * Builder class for {@link Server} instance.
 *
 * @author Tomáš Pozler, 2022
 */
@Getter
@Slf4j
public class GrpcServer {
	/**
	 * The server instance.
	 */
	private Server server;

	/**
	 * Builds the server instance with default server port.
	 *
	 * @param evita instance on which will services be operating
	 */
	public GrpcServer(@Nonnull Evita evita, @Nonnull ApiOptions apiOptions, @Nonnull GrpcConfig config) {
		setUpServer(evita, apiOptions, config);
	}

	/**
	 * Builds the server instance which will operate on a set-up server port. If configured, from provided {@link ApiOptions}
	 * and {@link GrpcConfig} the TLS/mTLS settings will be used.
	 *
	 * @param evita      instance on which will services be operating
	 * @param apiOptions API options from configuration file for getting certificate settings
	 * @param config     gRPC configuration from configuration file
	 */
	private void setUpServer(@Nonnull Evita evita, @Nonnull ApiOptions apiOptions, @Nonnull GrpcConfig config) {
		final HostDefinition[] hosts = config.getHost();
		final CertificatePath certificatePath = ServerCertificateManager.getCertificatePath(apiOptions.certificate());
		if (certificatePath.certificate() == null || certificatePath.privateKey() == null) {
			throw new EvitaInternalError("Certificate path is not set.");
		}
		final ServerBuilder serverBuilder = Server.builder()
			.https(hosts[0].port());

		final SslContextBuilder tlsServerCredentialsBuilder;
		try {
			tlsServerCredentialsBuilder = SslContextBuilder.forServer(new File(certificatePath.certificate()), new File(certificatePath.privateKey()), certificatePath.privateKeyPassword());
			final MtlsConfiguration mtlsConfiguration = config.getMtlsConfiguration();
			if (mtlsConfiguration != null && Boolean.TRUE.equals(mtlsConfiguration.enabled())) {
				if (apiOptions.certificate().generateAndUseSelfSigned()) {
					tlsServerCredentialsBuilder.trustManager(
						apiOptions.certificate().getFolderPath()
							.resolve(CertificateUtils.getGeneratedRootCaCertificateFileName())
							.toFile()
					);
				}
				tlsServerCredentialsBuilder.clientAuth(ClientAuth.REQUIRE);
				final CertificateFactory cf = CertificateFactory.getInstance("X.509");
				for (String clientCert : mtlsConfiguration.allowedClientCertificatePaths()) {
					tlsServerCredentialsBuilder.trustManager(new FileInputStream(clientCert));
					try (InputStream in = new FileInputStream(clientCert)) {
						log.info("Whitelisted client's certificate fingerprint: {}", CertificateUtils.getCertificateFingerprint(cf.generateCertificate(in)));
					}
				}
			} else {
				tlsServerCredentialsBuilder.clientAuth(ClientAuth.OPTIONAL);
			}
		} catch (Exception e) {
			throw new EvitaInternalError(
				"Failed to create gRPC server credentials with provided certificate and private key: " + e.getMessage(),
				"Failed to create gRPC server credentials with provided certificate and private key.",
				e
			);
		}

		GrpcServiceBuilder grpcServiceBuilder = GrpcService.builder()
			.addService(new EvitaService(evita))
			.addService(new EvitaSessionService())
			.addService(ProtoReflectionService.newInstance())
			.intercept(new ServerSessionInterceptor(evita))
			.intercept(new GlobalExceptionHandlerInterceptor())
			.supportedSerializationFormats(GrpcSerializationFormats.values())
			.enableUnframedRequests(true);

		if (apiOptions.accessLog()) {
			grpcServiceBuilder = grpcServiceBuilder.intercept(new AccessLogInterceptor());
		}
		final ServerInterceptor serverInterceptor = ExternalApiTracingContextProvider.getContext().getServerInterceptor(ServerInterceptor.class);
		if (serverInterceptor != null) {
			grpcServiceBuilder = grpcServiceBuilder.intercept(serverInterceptor);
		}

		final GrpcService grpcService = grpcServiceBuilder.build();

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

		serverBuilder.tlsCustomizer(t -> {
				try {
					tlsServerCredentialsBuilder.build();
				} catch (SSLException e) {
					throw new RuntimeException(e);
				}
			})
			.blockingTaskExecutor(evita.getExecutor(), true)
			.service(grpcService, corsBuilder.newDecorator());

		server = serverBuilder.build();
	}
}
