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

package io.evitadb.externalApi.lab.configuration;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.evitadb.externalApi.configuration.AbstractApiConfiguration;
import io.evitadb.externalApi.configuration.ApiWithOriginControl;
import io.evitadb.externalApi.configuration.ApiWithSpecificPrefix;
import io.evitadb.externalApi.configuration.HostDefinition;
import io.evitadb.externalApi.configuration.TlsMode;
import io.evitadb.externalApi.exception.ExternalApiInternalError;
import io.evitadb.utils.Assert;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static java.util.Optional.ofNullable;

/**
 * Configuration for lab API and GUI.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class LabConfig extends AbstractApiConfiguration implements ApiWithSpecificPrefix, ApiWithOriginControl {
	private static final String BASE_LAB_PATH = "lab";
	private static final Pattern ORIGIN_PATTERN = Pattern.compile("([a-z]+)://([\\w.]+)(:(\\d+))?");

	/**
	 * Controls the prefix lab will react on.
	 * Default value is `gql`.
	 */
	@Getter private final String prefix;
	@Getter private final String[] allowedOrigins;
	@Getter private final GuiConfig gui;

	public LabConfig() {
		super();
		this.prefix = BASE_LAB_PATH;
		this.allowedOrigins = null;
		this.gui = new GuiConfig();
	}

	public LabConfig(@Nonnull String host) {
		super(true, host);
		this.prefix = BASE_LAB_PATH;
		this.allowedOrigins = null;
		this.gui = new GuiConfig();
	}

	@JsonCreator
	public LabConfig(
		@Nullable @JsonProperty("enabled") Boolean enabled,
		@Nonnull @JsonProperty("host") String host,
		@Nullable @JsonProperty("exposeOn") String exposeOn,
		@Nullable @JsonProperty("tlsMode") String tlsMode,
		@Nullable @JsonProperty("prefix") String prefix,
		@Nullable @JsonProperty("allowedOrigins") String allowedOrigins,
		@Nullable @JsonProperty("gui") GuiConfig gui
	) {
		super(enabled, host, exposeOn, tlsMode);
		this.prefix = ofNullable(prefix).orElse(BASE_LAB_PATH);
		if (allowedOrigins == null) {
			this.allowedOrigins = null;
		} else {
			this.allowedOrigins = Arrays.stream(allowedOrigins.split(","))
				.peek(origin -> {
					final Matcher matcher = ORIGIN_PATTERN.matcher(origin);
					Assert.isTrue(matcher.matches(), "Invalid origin definition: " + origin);
				})
				.toArray(String[]::new);
			Assert.isTrue(this.allowedOrigins.length > 0, "At least one allowed origin must be specified.");
		}
		this.gui = ofNullable(gui).orElse(new GuiConfig());
	}

	/**
	 * Returns base url without uri through which all other APIs can be accessed.
	 */
	@Nonnull
	public String getBaseApiAccessUrl() {
		return Stream.concat(
				Arrays.stream(getHost())
					.map(HostDefinition::port)
					.distinct()
					.flatMap(
						port -> ofNullable(getExposeOn())
							.map(it -> it.contains(":") ? it : it + ":" + port)
							.stream()
					),
				Arrays.stream(getHost())
					.map(HostDefinition::hostAddressWithPort)
			)
			.map(it -> it.contains("://") ? it : (getTlsMode() == TlsMode.FORCE_NO_TLS ? "http://" : "https://") + it)
			.findFirst()
			.orElseThrow(() -> new ExternalApiInternalError("No API access URL found."));
	}
}
