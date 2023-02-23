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

package io.evitadb.externalApi.grpc.testUtils;

import io.evitadb.driver.certificate.ClientCertificateManager;
import io.grpc.ClientInterceptor;
import io.grpc.ManagedChannel;
import io.grpc.netty.NettyChannelBuilder;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;

/**
 * Class used in tests for creating and storing instance of {@link ManagedChannel} upon which will gRPC stubs be created.
 *
 * @author Tomáš Pozler, 2022
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class TestChannelCreator {
	/**
	 * Channel used in gRPC tests.
	 */
	private static ManagedChannel channel;

	/**
	 * Builds (first call) or gets the channel instance.
	 *
	 * @param interceptor instance of {@link ClientInterceptor} for passing metadata containing session information
	 * @param port        where gRPC service listens on
	 * @param <T>         implementation of {@link ClientInterceptor}
	 */
	public static <T extends ClientInterceptor> ManagedChannel getChannel(@Nonnull T interceptor, int port) {
		if (channel == null) {
			channel = NettyChannelBuilder.forAddress("localhost", port)
				.sslContext(new ClientCertificateManager.Builder().build().buildClientSslContext())
				.intercept(interceptor)
				.build();
		}
		return channel;
	}
}
