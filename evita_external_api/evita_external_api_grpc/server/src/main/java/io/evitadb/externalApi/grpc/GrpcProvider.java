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
import io.evitadb.externalApi.certificate.ServerCertificateManager;
import io.evitadb.externalApi.configuration.ApiOptions;
import io.evitadb.externalApi.configuration.CertificatePath;
import io.evitadb.externalApi.configuration.CertificateSettings;
import io.evitadb.externalApi.configuration.HostDefinition;
import io.evitadb.externalApi.grpc.certificate.ClientCertificateManager;
import io.evitadb.externalApi.grpc.configuration.GrpcConfig;
import io.evitadb.externalApi.grpc.exception.GrpcServerStartFailedException;
import io.evitadb.externalApi.grpc.generated.EvitaServiceGrpc;
import io.evitadb.externalApi.grpc.generated.EvitaServiceGrpc.EvitaServiceFutureStub;
import io.evitadb.externalApi.grpc.generated.GrpcEvitaServerStatusResponse;
import io.evitadb.externalApi.http.ExternalApiProvider;
import io.evitadb.utils.CertificateUtils;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.netty.NettyChannelBuilder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
	private final ApiOptions apiOptions;

	@Nonnull
	@Getter
	private final GrpcConfig configuration;

	@Getter
	private final Server server;

	/**
	 * Contains channel that was successfully used to check if gRPC server is ready.
	 */
	private ManagedChannel channel;

	@Nonnull
	@Override
	public String getCode() {
		return CODE;
	}

	@Override
	public boolean isManagedByUndertow() {
		return false;
	}

	@Override
	public void afterStart() {
		try {
			server.start();
		} catch (IOException e) {
			throw new GrpcServerStartFailedException(
				"Failed to start gRPC server due to: " + e.getMessage(),
				"Failed to start gRPC server.",
				e
			);
		}
	}

	@Override
	public void beforeStop() {
		if (this.channel != null && !this.channel.isShutdown()) {
			this.channel.shutdown();
			this.channel = null;
		}
		this.server.shutdown();
	}

	@Override
	public boolean isReady() {
		if (this.channel == null) {
			for (HostDefinition hostDefinition : this.configuration.getHost()) {
				NettyChannelBuilder nettyChannelBuilder = NettyChannelBuilder.forAddress(hostDefinition.hostName(), hostDefinition.port());
				final CertificateSettings certificateSettings = apiOptions.certificate();
				if (!certificateSettings.generateAndUseSelfSigned() && configuration.isMtlsEnabled()) {
					log.error(
						"Cannot check readiness of the gRPC API on the server side if mTLS is enabled currently." +
						"The client private key is missing."
					);
					return false;
				}
				final Optional<CertificatePath> certificatePaths = ServerCertificateManager.getCertificatePath(certificateSettings);
				if (configuration.isTlsEnabled() && certificatePaths.isPresent()) {
					final CertificatePath paths = certificatePaths.get();
					final Path folderPath = certificateSettings.getFolderPath().toAbsolutePath().normalize();
					final ClientCertificateManager clientCertificateManager = new ClientCertificateManager.Builder()
						.usingTrustedRootCaCertificate(certificateSettings.generateAndUseSelfSigned())
						// this will prevent attempt to download certificates (we use certificates directly from the server)
						.dontUseGeneratedCertificate()
						.mtls(configuration.isMtlsEnabled())
						.rootCaCertificateFilePath(Path.of(paths.certificate()))
						.certificateClientFolderPath(folderPath)
						.clientCertificateFilePath(folderPath.resolve(CertificateUtils.getGeneratedClientCertificateFileName()))
						.clientPrivateKeyFilePath(folderPath.resolve(CertificateUtils.getGeneratedClientCertificatePrivateKeyFileName()))
						.build();
					nettyChannelBuilder.sslContext(clientCertificateManager.buildClientSslContext(null));
				} else {
					nettyChannelBuilder.usePlaintext();
				}
				final ManagedChannel examinedChannel = nettyChannelBuilder.build();
				try {
					if (isReady(examinedChannel)) {
						return true;
					}
				} finally {
					if (examinedChannel != null && !examinedChannel.isShutdown()) {
						examinedChannel.shutdown();
					}
				}
			}
		} else {
			if (isReady(this.channel)) {
				return true;
			} else {
				this.channel = null;
			}
		}
		return false;
	}

	/**
	 * Returns true if the channel is ready or idle.
	 * @param channel channel to check
	 * @return true if the channel is ready or idle
	 */
	private static boolean isReady(@Nonnull ManagedChannel channel) {
		final EvitaServiceFutureStub evitaService = EvitaServiceGrpc.newFutureStub(channel);
		try {
			final GrpcEvitaServerStatusResponse response = evitaService.serverStatus(Empty.newBuilder().build())
				.get(1, TimeUnit.SECONDS);
			return response.isInitialized();
		} catch (InterruptedException | ExecutionException | TimeoutException e) {
			return false;
		}
	}

}
