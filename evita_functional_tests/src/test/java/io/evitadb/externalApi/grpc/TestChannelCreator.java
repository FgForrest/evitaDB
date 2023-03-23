/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
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

package io.evitadb.externalApi.grpc;

import io.evitadb.driver.certificate.ClientCertificateManager.Builder;
import io.evitadb.externalApi.configuration.AbstractApiConfiguration;
import io.evitadb.externalApi.configuration.ApiOptions;
import io.evitadb.externalApi.configuration.CertificateSettings;
import io.evitadb.externalApi.grpc.interceptor.ClientSessionInterceptor;
import io.evitadb.externalApi.http.ExternalApiServer;
import io.evitadb.externalApi.system.SystemProvider;
import io.evitadb.utils.Assert;
import io.grpc.ClientInterceptor;
import io.grpc.ManagedChannel;
import io.grpc.netty.NettyChannelBuilder;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import java.nio.file.Path;

/**
 * Class used in tests for creating and storing instance of {@link ManagedChannel} upon which will gRPC stubs be created.
 *
 * @author Tomáš Pozler, 2022
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class TestChannelCreator {

	/**
	 * Builds (first call) or gets the channel instance.
	 *
	 * @param interceptor       instance of {@link ClientInterceptor} for passing metadata containing session information
	 * @param externalApiServer where gRPC service listens on
	 */
	public static ManagedChannel getChannel(@Nonnull ClientSessionInterceptor interceptor, @Nonnull ExternalApiServer externalApiServer) {
		final ApiOptions apiOptions = externalApiServer.getApiOptions();
		final int grpcPort = apiOptions.getEndpointConfiguration(GrpcProvider.CODE).getHost()[0].port();
		final CertificateSettings certificate = apiOptions.certificate();
		final Builder builder = new Builder()
			.certificateClientFolderPath(Path.of(externalApiServer.getApiOptions().certificate().folderPath()));
		if (certificate.generateAndUseSelfSigned()) {
			final AbstractApiConfiguration systemEndpoint = apiOptions.getEndpointConfiguration(SystemProvider.CODE);
			Assert.notNull(systemEndpoint, "System endpoint is not enabled!");
			builder.useGeneratedCertificate(true, systemEndpoint.getHost()[0].hostName(), systemEndpoint.getHost()[0].port());
		}
		return NettyChannelBuilder.forAddress("localhost", grpcPort)
			.sslContext(
				builder
					.build()
					.buildClientSslContext()
			)
			.intercept(interceptor)
			.build();
	}

}
