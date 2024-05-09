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

import io.evitadb.core.Evita;
import io.evitadb.exception.GenericEvitaInternalError;
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
import io.evitadb.utils.CertificateUtils;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.ServerCredentials;
import io.grpc.TlsServerCredentials;
import io.grpc.netty.NettyServerBuilder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.security.cert.CertificateFactory;

/**
 * Builder class for {@link Server} and {@link ManagedChannel} instances.
 *
 * @author Tomáš Pozler, 2022
 */
@Slf4j
public class GrpcServer {
	/**
	 * The server instance.
	 */
	@Getter
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
	 * @param evita                   instance on which will services be operating
	 * @param apiOptions              API options from configuration file for getting certificate settings
	 * @param config                  gRPC configuration from configuration file
	 */
	private void setUpServer(@Nonnull Evita evita, @Nonnull ApiOptions apiOptions, @Nonnull GrpcConfig config) {
		final HostDefinition[] hosts = config.getHost();
		final CertificatePath certificatePath = ServerCertificateManager.getCertificatePath(apiOptions.certificate());
		if (certificatePath.certificate() == null || certificatePath.privateKey() == null) {
			throw new GenericEvitaInternalError("Certificate path is not set.");
		}
		final ServerCredentials tlsServerCredentials;
		try {
			final TlsServerCredentials.Builder tlsServerCredentialsBuilder = TlsServerCredentials.newBuilder();
			tlsServerCredentialsBuilder.keyManager(new File(certificatePath.certificate()), new File(certificatePath.privateKey()), certificatePath.privateKeyPassword());
			final MtlsConfiguration mtlsConfiguration = config.getMtlsConfiguration();
			if (mtlsConfiguration != null && Boolean.TRUE.equals(mtlsConfiguration.enabled())) {
				if (apiOptions.certificate().generateAndUseSelfSigned()) {
					tlsServerCredentialsBuilder.trustManager(
						apiOptions.certificate().getFolderPath()
							.resolve(CertificateUtils.getGeneratedRootCaCertificateFileName())
							.toFile()
					);
				}
				tlsServerCredentialsBuilder.clientAuth(TlsServerCredentials.ClientAuth.REQUIRE);
				final CertificateFactory cf = CertificateFactory.getInstance("X.509");
				for (String clientCert : mtlsConfiguration.allowedClientCertificatePaths()) {
					tlsServerCredentialsBuilder.trustManager(new FileInputStream(clientCert));
					try (InputStream in = new FileInputStream(clientCert)) {
						log.info("Whitelisted client's certificate fingerprint: {}", CertificateUtils.getCertificateFingerprint(cf.generateCertificate(in)));
					}
				}
			} else {
				tlsServerCredentialsBuilder.clientAuth(TlsServerCredentials.ClientAuth.OPTIONAL);
			}
			tlsServerCredentials = tlsServerCredentialsBuilder.build();
		} catch (Exception e) {
			throw new GenericEvitaInternalError(
				"Failed to create gRPC server credentials with provided certificate and private key: " + e.getMessage(),
				"Failed to create gRPC server credentials with provided certificate and private key.",
				e
			);
		}

		final NettyServerBuilder serverBuilder = NettyServerBuilder.forAddress(new InetSocketAddress(hosts[0].host(), hosts[0].port()), tlsServerCredentials)
			.executor(evita.getExecutor())
			.addService(new EvitaService(evita))
			.addService(new EvitaSessionService(evita))
			.intercept(new ServerSessionInterceptor(evita))
			.intercept(new GlobalExceptionHandlerInterceptor());
		if (apiOptions.accessLog()) {
			serverBuilder.intercept(new AccessLogInterceptor());
		}
		server = serverBuilder.build();
	}
}
