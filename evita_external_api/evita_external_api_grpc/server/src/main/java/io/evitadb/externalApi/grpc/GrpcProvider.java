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

import io.evitadb.externalApi.configuration.HostDefinition;
import io.evitadb.externalApi.grpc.configuration.GrpcConfig;
import io.evitadb.externalApi.grpc.exception.GrpcServerStartFailedException;
import io.evitadb.externalApi.http.ExternalApiProvider;
import io.grpc.ConnectivityState;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.io.IOException;

/**
 * Descriptor of external API provider that provides gRPC API.
 *
 * @author Tomáš Pozler, 2022
 * @see GrpcProviderRegistrar
 */
@RequiredArgsConstructor
public class GrpcProvider implements ExternalApiProvider<GrpcConfig> {

	public static final String CODE = "gRPC";

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
				final ManagedChannelBuilder<?> builder = ManagedChannelBuilder.forAddress(hostDefinition.hostName(), hostDefinition.port());
				if (!configuration.isTlsEnabled()) {
					builder.usePlaintext();
				}
				ManagedChannel examinedChannel = null;
				try {
					examinedChannel = builder.build();
					if (isReady(examinedChannel)) {
						return true;
					}
				} catch (Exception e) {
					// ignore the exception and continue with next host
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
	private boolean isReady(@Nonnull ManagedChannel channel) {
		ConnectivityState state;

		final long start = System.currentTimeMillis();
		do {
			state = channel.getState(true);
			if (state == ConnectivityState.READY || state == ConnectivityState.IDLE) {
				this.channel = channel;
				return true;
			}
		} while (state == ConnectivityState.CONNECTING && System.currentTimeMillis() - start < 1000);

		return false;
	}

}
