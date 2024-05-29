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
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.externalApi.grpc;

import com.linecorp.armeria.server.Server;
import io.evitadb.externalApi.grpc.configuration.GrpcConfig;
import io.evitadb.externalApi.grpc.exception.GrpcServerStartFailedException;
import io.evitadb.externalApi.http.ExternalApiProvider;
import io.evitadb.utils.NetworkUtils;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.function.Predicate;

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
	 * Contains url that was at least once found reachable.
	 */
	private String reachableUrl;

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
			server.closeOnJvmShutdown();
			server.start().join();
		} catch (Exception e) {
			throw new GrpcServerStartFailedException(
				"Failed to start gRPC server due to: " + e.getMessage(),
				"Failed to start gRPC server.",
				e
			);
		}
	}

	@Override
	public void beforeStop() {
		server.stop();
	}

	@Override
	public boolean isReady() {
		final Predicate<String> isReady = url -> {
			final int responseCode = NetworkUtils.getHttpStatusCode(url, "GET", "application/grpc")
				.orElse(-1);
			// we are interested in 405 Method Not Allowed which signals gRPC server is running
			return responseCode == 405;
		};
		final String[] baseUrls = this.configuration.getBaseUrls(configuration.getExposedHost());
		if (this.reachableUrl == null) {
			for (String baseUrl : baseUrls) {
				if (isReady.test(baseUrl)) {
					this.reachableUrl = baseUrl;
					return true;
				}
			}
			return false;
		} else {
			return isReady.test(this.reachableUrl);
		}
	}

}
