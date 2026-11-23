/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.externalApi.configuration;

import io.evitadb.externalApi.exception.ExternalApiInternalError;
import io.evitadb.utils.Assert;
import io.evitadb.utils.NetworkUtils;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static java.util.Optional.ofNullable;

/**
 * This DTO contains basic configuration settings for different API endpoints.
 * By configuring {@link AbstractApiOptions#mtlsConfiguration}, additional security can be added to the API in
 * question by enabling mTLS and allowing only specific and verified client to connect.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public abstract class AbstractApiOptions {
	/**
	 * Default API port.
	 */
	public static final int DEFAULT_PORT = 5555;
	/**
	 * Pattern for host / port parsing.
	 */
	private static final Pattern HOST_PATTERN = Pattern.compile("([\\w.:]*):(\\d+)");
	/**
	 * Defines the port API endpoint will listen on.
	 */
	@Getter private final boolean enabled;
	/**
	 * Defines whether the API should keep the connection alive (default is true). When set to false, the connection
	 * will be closed after each request from the server side.
	 */
	@Getter private final boolean keepAlive;
	/**
	 * Defines hosts the API will listen on.
	 */
	@Getter private final HostDefinition[] host;
	/**
	 * Defines external host the API will be exposed on when database is running in a container.
	 */
	@Getter private final String exposeOn;
	/**
	 * Defines whether the API accepts encrypted connections, unencrypted connections, or both. A missing value
	 * resolves to the default of the concrete endpoint options class; any other value must be the exact,
	 * case-sensitive name of a {@link TlsMode} constant, or options construction fails - see
	 * {@link TlsMode#getByName(String, TlsMode, String)}.
	 */
	@Getter private final TlsMode tlsMode;

	/**
	 * Wrapper that contains a part of configuration file that is related to mTLS settings.
	 */
	@Getter private final MtlsConfiguration mtlsConfiguration;

	/**
	 * Parses host definition into {@link HostDefinition} object.
	 *
	 * @param host host definition in format `hostname:port`
	 * @return parsed host definition
	 */
	@Nonnull
	private static HostDefinition[] parseHost(@Nonnull String host) {
		final Matcher matcher = HOST_PATTERN.matcher(host);
		Assert.isTrue(matcher.matches(), "Invalid host definition: " + host);
		final String parsedHost = matcher.group(1);
		final int port = Integer.parseInt(matcher.group(2));
		if (parsedHost.isEmpty()) {
			return new HostDefinition[] {
				new HostDefinition(NetworkUtils.getByName("0.0.0.0"), true, port)
			};
		} else {
			return new HostDefinition[] {
				new HostDefinition(NetworkUtils.getByName(parsedHost), false, port)
			};
		}
	}

	/**
	 * Creates enabled endpoint options on the default port with the endpoint's documented TLS mode.
	 *
	 * @param defaultTlsMode documented TLS default of the endpoint
	 */
	protected AbstractApiOptions(@Nonnull TlsMode defaultTlsMode) {
		this(true, ":" + DEFAULT_PORT, null, null, null, null, defaultTlsMode);
	}

	/**
	 * Creates options for a host using the endpoint's documented TLS default.
	 *
	 * @param enabled enables the particular API
	 * @param host comma-separated host definitions in the format `hostname:port`, read as described at
	 *             {@link #AbstractApiOptions(Boolean, String, String, String, Boolean, MtlsConfiguration, TlsMode)}
	 * @param defaultTlsMode documented TLS default of the endpoint
	 */
	protected AbstractApiOptions(
		@Nullable Boolean enabled, @Nonnull String host, @Nonnull TlsMode defaultTlsMode
	) {
		this(enabled, host, null, null, null, null, defaultTlsMode);
	}

	/**
	 * Creates endpoint options, resolving a missing TLS mode to the supplied endpoint default. Each endpoint passes its
	 * own default, so a `tlsMode` left `null` - nothing set on the endpoint and nothing inherited from the shared
	 * endpoint defaults - resolves per endpoint instead of to a single value for all of them.
	 *
	 * @param enabled enables the particular API
	 * @param host comma-separated host definitions in the format `hostname:port`; an empty hostname (`:5555`) stands
	 *             for all network interfaces, any other hostname or IP address for the address it resolves to, for
	 *             example `localhost:5555,168.12.45.44:5555`
	 * @param exposeOn external address advertised to clients
	 * @param tlsMode exact TLS mode name, or null to use the endpoint default
	 * @param keepAlive whether connections should be kept alive
	 * @param mtlsConfiguration mutual TLS settings
	 * @param defaultTlsMode documented TLS default of the endpoint
	 * @throws io.evitadb.exception.EvitaInvalidUsageException when `tlsMode` is neither null nor the exact name
	 *         of a {@link TlsMode} constant, or a host definition does not match `hostname:port`
	 */
	protected AbstractApiOptions(
		@Nullable Boolean enabled,
		@Nonnull String host,
		@Nullable String exposeOn,
		@Nullable String tlsMode,
		@Nullable Boolean keepAlive,
		@Nullable MtlsConfiguration mtlsConfiguration,
		@Nonnull TlsMode defaultTlsMode
	) {
		this.enabled = ofNullable(enabled).orElse(true);
		this.tlsMode = TlsMode.getByName(tlsMode, defaultTlsMode, getClass().getSimpleName());
		this.host = Arrays.stream(host.split(","))
			.map(AbstractApiOptions::parseHost)
			.flatMap(Arrays::stream)
			.toArray(HostDefinition[]::new);
		this.exposeOn = exposeOn;
		this.keepAlive = ofNullable(keepAlive).orElse(true);
		this.mtlsConfiguration = ofNullable(mtlsConfiguration).orElse(new MtlsConfiguration(false, List.of()));
	}

	/**
	 * Returns base url for the API.
	 */
	@Nonnull
	public String[] getBaseUrls() {
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
			.map(it -> it + (this instanceof ApiWithSpecificPrefix withSpecificPrefix ? "/" + withSpecificPrefix.getPrefix() + "/" : "/"))
			.toArray(String[]::new);
	}

	/**
	 * Returns URL on which the API is exposed on based on config.
	 */
	@Nonnull
	public String getResolvedExposeOnUrl() {
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

	/**
	 * Returns true if particular API has mutual TLS enabled.
	 * @return true if mutual TLS is enabled
	 */
	public boolean isMtlsEnabled() {
		return this.mtlsConfiguration != null && Boolean.TRUE.equals(this.mtlsConfiguration.enabled());
	}
}
