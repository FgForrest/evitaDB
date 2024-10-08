/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

package io.evitadb.externalApi.http;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.server.DecoratingHttpServiceFunction;
import com.linecorp.armeria.server.DecoratingRpcServiceFunction;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.RpcService;
import com.linecorp.armeria.server.ServiceRequestContext;
import io.evitadb.externalApi.configuration.AbstractApiConfiguration;
import io.evitadb.externalApi.configuration.HostDefinition;
import io.evitadb.externalApi.configuration.TlsMode;
import io.evitadb.externalApi.exception.InvalidPortException;
import io.evitadb.externalApi.exception.InvalidSchemeException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * This HTTP service verifies input scheme against allowed {@link TlsMode} in configuration and returns appropriate
 * error response if the scheme is not allowed. It also checks if the port is allowed for the service.
 * Otherwise, it delegates the request to the decorated service.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class HttpServiceSecurityDecorator implements DecoratingHttpServiceFunction, DecoratingRpcServiceFunction {
	public static final String SCHEME_HTTPS = "https";
	public static final String SCHEME_HTTP = "http";
	private final String[] hosts;
	private final String[] schemes;
	private final int[] ports;
	private final int peak;

	public HttpServiceSecurityDecorator(@Nonnull AbstractApiConfiguration... configurations) {
		final int hostsConfigs = Arrays.stream(configurations)
			.mapToInt(config -> config.getHost().length)
			.sum();
		this.hosts = new String[hostsConfigs];
		this.schemes = new String[hostsConfigs];
		this.ports = new int[hostsConfigs];
		this.peak = prepareConfigurations(configurations);
	}

	@Nonnull
	@Override
	public HttpResponse serve(@Nonnull HttpService delegate, @Nonnull ServiceRequestContext ctx, @Nonnull HttpRequest req) throws Exception {
		final URI uri = ctx.uri();
		final String scheme = uri.getScheme();
		final InetSocketAddress address = ctx.localAddress();
		final int port = address.getPort();
		boolean hostAndPortMatching = false;
		for (int i = 0; i < peak; i++) {
			if (port == ports[i] && (hosts[i] == null || address.getAddress().getHostAddress().equals(hosts[i]))) {
				if (schemes[i] == null || scheme.equals(schemes[i])) {
					return delegate.serve(ctx, req);
				} else {
					hostAndPortMatching = true;
				}
			}
		}
		if (hostAndPortMatching) {
			return HttpResponse.of(HttpStatus.FORBIDDEN, MediaType.PLAIN_TEXT, getSchemeErrorMessage(scheme));
		} else {
			return HttpResponse.of(HttpStatus.NOT_FOUND, MediaType.PLAIN_TEXT, "Service not available.");
		}
	}

	@Nonnull
	@Override
	public RpcResponse serve(@Nonnull RpcService delegate, @Nonnull ServiceRequestContext ctx, @Nonnull RpcRequest req) throws Exception {
		final URI uri = ctx.uri();
		final String scheme = uri.getScheme();
		final InetSocketAddress address = ctx.localAddress();
		final int port = address.getPort();
		boolean portMatching = false;
		for (int i = 0; i < peak; i++) {
			if (port == ports[i] && (hosts[i] == null || address.getAddress().getHostAddress().equals(hosts[i]))) {
				if (schemes[i] == null || scheme.equals(schemes[i])) {
					return delegate.serve(ctx, req);
				} else {
					portMatching = true;
				}
			}
		}
		if (portMatching) {
			return RpcResponse.ofFailure(new InvalidSchemeException(getSchemeErrorMessage(scheme)));
		} else {
			return RpcResponse.ofFailure(new InvalidPortException("Service not available."));
		}
	}

	/**
	 * Prepares security configurations based on the provided API configurations.
	 *
	 * @param configurations array of {@link AbstractApiConfiguration} objects containing the security setup
	 *                       for different API endpoints.
	 * @return the peak index of the configurations
	 */
	private int prepareConfigurations(@Nonnull AbstractApiConfiguration... configurations) {
		final ArrayList<HostTriple> hostTriples = new ArrayList<>(32);
		int index = 0;
		for (final AbstractApiConfiguration config : configurations) {
			final HostDefinition[] hosts = config.getHost();
			for (final HostDefinition host : hosts) {
				final TlsMode tlsMode = config.getTlsMode();
				final String scheme;
				if (tlsMode == TlsMode.FORCE_TLS) {
					scheme = SCHEME_HTTPS;
				} else if (tlsMode == TlsMode.FORCE_NO_TLS) {
					scheme = SCHEME_HTTP;
				} else {
					scheme = null;
				}
				final int port = host.port();
				final String hostname = host.localhost() ? null : host.host().getHostAddress();
				final HostTriple hostTriple = new HostTriple(hostname, port, scheme);

				boolean newHost = true;
				for (int i = 0; i < hostTriples.size(); i++) {
					final HostTriple existingTriple = hostTriples.get(i);
					if (existingTriple.isSuperiorOrEqual(hostTriple)) {
						newHost = false;
					} else if (hostTriple.isSuperiorOrEqual(existingTriple)) {
						hostTriples.set(i, hostTriple);
						this.hosts[i] = hostTriple.host();
						this.ports[i] = hostTriple.port();
						this.schemes[i] = hostTriple.scheme();
						newHost = false;
					}
				}

				if (newHost) {
					hostTriples.add(hostTriple);
					this.hosts[index] = hostTriple.host();
					this.ports[index] = hostTriple.port();
					this.schemes[index] = hostTriple.scheme();
					index++;
				}
			}
		}
		return index;
	}

	/**
	 * Generates a specific error message based on the provided scheme.
	 *
	 * @param scheme the URI scheme to check (e.g., "http", "https").
	 * @return an error message indicating whether the scheme requires TLS or not.
	 */
	@Nonnull
	private static String getSchemeErrorMessage(@Nullable String scheme) {
        if (SCHEME_HTTPS.equals(scheme)) {
			return "This endpoint requires TLS.";
		}
		return "This endpoint does not support TLS.";
	}

	/**
	 * Represents a host, port, and scheme combination.
	 *
	 * @param host the hostname
	 * @param port the port number
	 * @param scheme the URI scheme (e.g., "http", "https")
	 */
	public record HostTriple(
		@Nullable String host,
		int port,
		@Nullable String scheme
	) {

		/**
		 * Checks if the current host triple is superior or equal to another host triple.
		 * The current host triple is superior or equal if the port is the same and the host and scheme are equal or null.
		 *
		 * @param another the other host triple to compare
		 * @return true if the current host triple is superior or equal to the other host triple, false otherwise
		 */
		public boolean isSuperiorOrEqual(@Nonnull HostTriple another) {
			if (port == another.port) {
				if (host == null || host.equals(another.host)) {
					if (scheme == null || scheme.equals(another.scheme)) {
						return true;
					}
					return false;
				} else {
					return false;
				}
			} else {
				return false;
			}
		}
	}

}
