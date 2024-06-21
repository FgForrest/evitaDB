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

import io.evitadb.core.Evita;
import io.evitadb.externalApi.configuration.ApiOptions;
import io.evitadb.externalApi.grpc.configuration.GrpcConfig;
import io.evitadb.externalApi.http.ExternalApiProvider;
import io.evitadb.externalApi.http.ExternalApiProviderRegistrar;

import io.evitadb.externalApi.http.ExternalApiServer;
import javax.annotation.Nonnull;

/**
 * Registers gRPC API provider.
 *
 * @author Tomáš Pozler, 2022
 */
public class GrpcProviderRegistrar implements ExternalApiProviderRegistrar<GrpcConfig> {

	@Nonnull
	@Override
	public String getExternalApiCode() {
		return GrpcProvider.CODE;
	}

	@Nonnull
	@Override
	public Class<GrpcConfig> getConfigurationClass() {
		return GrpcConfig.class;
	}

	@Nonnull
	@Override
	public ExternalApiProvider<GrpcConfig> register(@Nonnull Evita evita, @Nonnull ExternalApiServer externalApiServer, @Nonnull ApiOptions apiOptions, @Nonnull GrpcConfig grpcAPIConfig) {
		final GrpcManager grpcManager = new GrpcManager(evita, apiOptions, grpcAPIConfig);
		return new GrpcProvider(grpcAPIConfig, grpcManager.getGrpcRouter());
	}
}
