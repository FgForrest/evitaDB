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

package io.evitadb.externalApi.grpc.configuration;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.evitadb.externalApi.configuration.AbstractApiConfiguration;
import io.evitadb.externalApi.configuration.ApiConfigurationWithMutualTls;
import io.evitadb.externalApi.configuration.ApiWithOriginControl;
import io.evitadb.externalApi.configuration.MtlsConfiguration;
import io.evitadb.utils.Assert;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.Optional.ofNullable;

/**
 * gRPC API specific configuration.
 * Currently, we're not able to set prefix for gRPC API and share port with other APIs.
 * By configuring {@link GrpcConfig#mtlsConfiguration}, additional security can be added to the gRPC API by enabling
 * mTLS and allowing only specific and verified client to connect.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class GrpcConfig extends AbstractApiConfiguration implements ApiConfigurationWithMutualTls, ApiWithOriginControl {
	private static final Pattern ORIGIN_PATTERN = Pattern.compile("([a-z]+)://([\\w.]+)(:(\\d+))?");

	private static final String BASE_GRPC_PATH = "";
	/**
	 * Port on which will server be run and on which will channel be opened.
	 */
	public static final int DEFAULT_GRPC_PORT = 5555;
	/**
	 * Allows to expose the Armeria specific docs service on the gRPC API.
	 */
	@Getter private final boolean exposeDocsService;

	@Getter private final String[] allowedOrigins;
	/**
	 * Wrapper that contains a part of configuration file that is related to mTLS settings.
	 */
	@Getter
	private final MtlsConfiguration mtlsConfiguration;
	/**
	 * Controls the prefix gRPC API will react on.
	 * Default value is empty string - gRPC currently doesn't support running on any prefix.
	 * This is unfortunately limitation of original implementation - see <a href="https://github.com/grpc/grpc-java/issues/9671">related issue</a>.
	 */
	@Getter private final String prefix;

	public GrpcConfig() {
		super(true, ":" + DEFAULT_GRPC_PORT);
		this.exposeDocsService = false;
		this.mtlsConfiguration = new MtlsConfiguration(false, List.of());
		this.prefix = BASE_GRPC_PATH;
		this.allowedOrigins = null;
	}

	public GrpcConfig(@Nonnull String host) {
		super(true, host);
		this.exposeDocsService = false;
		this.mtlsConfiguration = new MtlsConfiguration(false, List.of());
		this.prefix = BASE_GRPC_PATH;
		this.allowedOrigins = null;
	}

	@JsonCreator
	public GrpcConfig(@Nullable @JsonProperty("enabled") Boolean enabled,
	                  @Nonnull @JsonProperty("host") String host,
	                  @Nullable @JsonProperty("exposedHost") String exposedHost,
					  @Nullable @JsonProperty("tlsMode") String tlsMode,
					  @Nullable @JsonProperty("exposeDocsService") Boolean exposeDocsService,
	                  @Nullable @JsonProperty("prefix") String prefix,
	                  @Nullable @JsonProperty("allowedOrigins") String allowedOrigins,
	                  @Nonnull @JsonProperty("mTLS") MtlsConfiguration mtlsConfiguration) {
		super(enabled, host, exposedHost, tlsMode);
		this.exposeDocsService = ofNullable(exposeDocsService).orElse(false);
		this.mtlsConfiguration = mtlsConfiguration;
		this.prefix = ofNullable(prefix).orElse(BASE_GRPC_PATH);
		if (allowedOrigins == null) {
			this.allowedOrigins = null;
		} else {
			this.allowedOrigins = Arrays.stream(allowedOrigins.split(","))
				.peek(origin -> {
					final Matcher matcher = ORIGIN_PATTERN.matcher(origin);
					Assert.isTrue(matcher.matches(), "Invalid origin definition: " + origin);
				})
				.toArray(String[]::new);
		}
	}

	@Override
	public boolean isMtlsEnabled() {
		return mtlsConfiguration.enabled();
	}
}
