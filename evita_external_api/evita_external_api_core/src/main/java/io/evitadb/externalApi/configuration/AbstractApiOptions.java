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
	 * By enabling this internal flag, the API will be forced to use unencrypted HTTP protocol. The only purpose of this
	 * flag is to allow accessing the system API, from where the client obtains to access all of evita's APIs. All of
	 * evita's APIs are always secured at least by TLS encryption, in gRPC is also an option to use mTLS.
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

	protected AbstractApiOptions() {
		this.enabled = true;
		this.tlsMode = TlsMode.RELAXED;
		this.exposeOn = null;
		this.host = new HostDefinition[]{
			new HostDefinition(NetworkUtils.getByName("0.0.0.0"), true, DEFAULT_PORT)
		};
		this.keepAlive = true;
		this.mtlsConfiguration = new MtlsConfiguration(false, List.of());
	}

	/**
	 * @param enabled enables the particular API
	 * @param host    defines the hostname and port the endpoints will listen on, use constant `localhost` for loopback
	 *                (IPv4) host. Multiple values can be delimited by comma. Example: `localhost:5555,168.12.45.44:5555`
	 */
	protected AbstractApiOptions(@Nullable Boolean enabled, @Nonnull String host) {
		this(enabled, host, null, null, null, null);
	}

	/**
	 * @param enabled    enables the particular API
	 * @param host       defines the hostname and port the endpoints will listen on, use constant `localhost` for loopback
	 *                   (IPv4) host. Multiple values can be delimited by comma. Example: `localhost:5555,168.12.45.44:5555`
	 * @param tlsMode    allows the API to run with TLS encryption
	 */
	protected AbstractApiOptions(
		@Nullable Boolean enabled,
		@Nonnull String host,
		@Nullable String exposeOn,
		@Nullable String tlsMode,
		@Nullable Boolean keepAlive,
		@Nullable MtlsConfiguration mtlsConfiguration
	) {
		this.enabled = ofNullable(enabled).orElse(true);
		this.tlsMode = TlsMode.getByName(tlsMode);
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
