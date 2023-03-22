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

package io.evitadb.externalApi.grpc.configuration;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.evitadb.externalApi.configuration.AbstractApiConfiguration;
import io.evitadb.externalApi.configuration.MtlsConfiguration;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;

/**
 * gRPC API specific configuration.
 * Currently, we're not able to set prefix for gRPC API and share port with other APIs.
 * By configuring {@link GrpcConfig#mtlsConfiguration}, additional security can be added to the gRPC API by enabling
 * mTLS and allowing only specific and verified client to connect.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class GrpcConfig extends AbstractApiConfiguration {
	/**
	 * Port on which will server be run and on which will channel be opened.
	 */
	public static final int DEFAULT_GRPC_PORT = 5556;
	/*
	 * Wrapper that contains a part of configuration file that is related to mTLS settings.
	 */
	@Getter
	private final MtlsConfiguration mtlsConfiguration;

	public GrpcConfig() {
		super(true, LOCALHOST + ":" + DEFAULT_GRPC_PORT);
		mtlsConfiguration = new MtlsConfiguration(false, List.of());
	}

	public GrpcConfig(@Nonnull String host) {
		super(true, host);
		mtlsConfiguration = new MtlsConfiguration(false, List.of());
	}

	@JsonCreator
	public GrpcConfig(@Nullable @JsonProperty("enabled") Boolean enabled,
	                  @Nonnull @JsonProperty("host") String host,
	                  @Nonnull @JsonProperty("mTLS") MtlsConfiguration mtlsConfiguration) {
		super(enabled, host);
		this.mtlsConfiguration = mtlsConfiguration;
	}
}
