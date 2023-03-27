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

package io.evitadb.externalApi.system.configuration;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.evitadb.externalApi.configuration.AbstractApiConfiguration;
import io.evitadb.externalApi.configuration.ApiWithOriginControl;
import io.evitadb.externalApi.configuration.ApiWithSpecificPrefix;
import io.evitadb.utils.Assert;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * System API specific configuration.
 *
 * @author Tomáš Pozler, 2023
 */
public class SystemConfig extends AbstractApiConfiguration implements ApiWithSpecificPrefix, ApiWithOriginControl {
	/**
	 * Port on which will server be run and on which will channel be opened.
	 */
	public static final int DEFAULT_SYSTEM_PORT = 5557;
	private static final String BASE_SYSTEM_PATH = "system";
	private static final Pattern ORIGIN_PATTERN = Pattern.compile("([a-z]+)://([\\w.]+)(:(\\d+))?");

	/**
	 * Controls the prefix System API will react on.
	 * Default value is `system`.
	 */
	@Getter private final String prefix;
	@Getter private final String[] allowedOrigins;

	public SystemConfig() {
		super(true, "0.0.0.0:" + DEFAULT_SYSTEM_PORT, true);
		this.prefix = BASE_SYSTEM_PATH;
		this.allowedOrigins = null;
	}

	public SystemConfig(@Nonnull String host) {
		super(true, host, true);
		this.prefix = BASE_SYSTEM_PATH;
		this.allowedOrigins = null;
	}

	@JsonCreator
	public SystemConfig(@Nullable @JsonProperty("enabled") Boolean enabled,
						@Nonnull @JsonProperty("host") String host,
						@Nullable @JsonProperty("prefix") String prefix,
						@Nullable @JsonProperty("allowedOrigins") String allowedOrigins) {
		super(enabled, host, true);
		this.prefix = Optional.ofNullable(prefix).orElse(BASE_SYSTEM_PATH);
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
}
