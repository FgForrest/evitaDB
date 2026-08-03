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

package io.evitadb.externalApi.grpc;

import com.google.protobuf.Empty;
import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.grpc.GrpcClients;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.docs.DocService;
import com.linecorp.armeria.server.docs.DocServiceFilter;
import io.evitadb.externalApi.configuration.ApiOptions;
import io.evitadb.externalApi.configuration.HostDefinition;
import io.evitadb.externalApi.configuration.TlsMode;
import io.evitadb.externalApi.event.ReadinessEvent;
import io.evitadb.externalApi.event.ReadinessEvent.Prospective;
import io.evitadb.externalApi.event.ReadinessEvent.Result;
import io.evitadb.externalApi.grpc.configuration.GrpcOptions;
import io.evitadb.externalApi.grpc.generated.EvitaServiceGrpc.EvitaServiceBlockingStub;
import io.evitadb.externalApi.http.ExternalApiProvider;
import io.evitadb.externalApi.http.ReadinessDiscoveryStallTracker;
import io.evitadb.utils.CollectionUtils;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Descriptor of external API provider that provides gRPC API.
 *
 * @author Tomáš Pozler, 2022
 * @see GrpcProviderRegistrar
 */
@Slf4j
public class GrpcProvider implements ExternalApiProvider<GrpcOptions> {

	public static final String CODE = "gRPC";

	@Nonnull
	@Getter
	private final GrpcOptions configuration;

	@Nonnull
	@Getter
	private final HttpService apiHandler;
	/**
	 * Timeout taken from {@link ApiOptions#requestTimeoutInMillis()} that will be used in
	 * {@link #checkReachable(String, Consumer)} method.
	 */
	private final long requestTimeout;
	/**
	 * Contains url that was at least once found reachable.
	 */
	private String reachableUrl;
	/**
	 * Builder for gRPC client factory.
	 */
	private final ClientFactory clientFactory;
	/**
	 * Tracks how long the readiness discovery phase (see {@link #reachableUrl}) has been running, so a server that
	 * never becomes reachable on any candidate URL is reported once instead of on every single probe.
	 */
	private final ReadinessDiscoveryStallTracker stallTracker = new ReadinessDiscoveryStallTracker();

	public GrpcProvider(@Nonnull GrpcOptions configuration, @Nonnull HttpService apiHandler, long requestTimeout, long idleTimeout) {
		this.configuration = configuration;
		this.apiHandler = apiHandler;
		this.requestTimeout = requestTimeout;
		this.clientFactory = ClientFactory.builder()
			// 1 second timeout for connection establishment
			.connectTimeoutMillis(requestTimeout)
			// 1 second timeout for idle connections
			.idleTimeoutMillis(idleTimeout)
			.tlsNoVerify()
			.build();
	}

	@Override
	public void beforeStop() {
		this.clientFactory.close();
	}

	@Nonnull
	@Override
	public String getCode() {
		return CODE;
	}

	@Nonnull
	@Override
	public HttpServiceDefinition[] getHttpServiceDefinitions() {
		if (this.configuration.isExposeDocsService()) {
			final DocService docService = DocService.builder()
				.exclude(DocServiceFilter.ofServiceName("grpc.reflection.v1alpha.ServerReflection"))
				.build();

			return new HttpServiceDefinition[]{
				new HttpServiceDefinition(this.apiHandler, PathHandlingMode.FIXED_PATH_HANDLING),
				new HttpServiceDefinition("grpc/doc", docService, PathHandlingMode.FIXED_PATH_HANDLING)
			};
		} else {
			return new HttpServiceDefinition[]{
				new HttpServiceDefinition(this.apiHandler, PathHandlingMode.FIXED_PATH_HANDLING)
			};
		}
	}

	@Override
	public boolean isReady() {
		if (this.reachableUrl == null) {
			// discovery phase: individual host failures are expected until the reachable one is found (e.g. the
			// server socket isn't bound yet), so they're only worth a DEBUG line here
			final HostDefinition[] hosts = this.configuration.getHost();
			final Map<String, String> failures = CollectionUtils.createLinkedHashMap(hosts.length);
			for (HostDefinition hostDefinition : hosts) {
				final String uri = toUri(hostDefinition);
				if (checkReachable(uri, message -> {
					failures.put(uri, message);
					log.debug("Error while checking readiness of gRPC API: {}", message);
				})) {
					return true;
				}
			}
			if (this.stallTracker.shouldWarnAboutStall()) {
				log.warn(
					"gRPC API has not become reachable on any of the {} configured URL(s) for over {}s " +
						"(this can be normal while the server is still starting up): {}",
					hosts.length, ReadinessDiscoveryStallTracker.GRACE_PERIOD.toSeconds(), failures
				);
			}
			return false;
		} else {
			// steady state: this URL was reachable before, so a failure now is a genuine regression; fall back to
			// the other configured hosts in case the service moved rather than went down
			final Consumer<String> failureLogger =
				message -> log.error("Error while checking readiness of gRPC API: {}", message);
			if (checkReachable(this.reachableUrl, failureLogger)) {
				return true;
			}
			for (HostDefinition hostDefinition : this.configuration.getHost()) {
				final String uri = toUri(hostDefinition);
				if (!uri.equals(this.reachableUrl) && checkReachable(uri, failureLogger)) {
					return true;
				}
			}
			return false;
		}
	}

	/**
	 * Assembles the gRPC endpoint URI for the given host, honoring the configured TLS mode.
	 */
	@Nonnull
	private String toUri(@Nonnull HostDefinition hostDefinition) {
		final String uriScheme = this.configuration.getTlsMode() != TlsMode.FORCE_NO_TLS ? "https" : "http";
		return uriScheme + "://" + hostDefinition.hostAddressWithPort() + "/";
	}

	/**
	 * Check if the given URI is reachable via gRPC client, reporting any failure message via {@code failureLogger}.
	 * @param uri URI to check
	 * @param failureLogger callback receiving the failure message, if the URI turns out unreachable
	 * @return true if the URI is reachable, false otherwise
	 */
	public boolean checkReachable(@Nonnull String uri, @Nonnull Consumer<String> failureLogger) {
		final ReadinessEvent readinessEvent = new ReadinessEvent(CODE, Prospective.CLIENT);
		try {
			final EvitaServiceBlockingStub evitaService = GrpcClients.builder(uri)
				.factory(this.clientFactory)
				.responseTimeoutMillis(this.requestTimeout)
				.writeTimeoutMillis(this.requestTimeout)
				.build(EvitaServiceBlockingStub.class);
			if (evitaService.isReady(Empty.newBuilder().build()).getReady()) {
				this.reachableUrl = uri;
				readinessEvent.finish(Result.READY);
				return true;
			} else {
				readinessEvent.finish(Result.ERROR);
				failureLogger.accept("gRPC API is not ready at: " + uri);
				return false;
			}
		} catch (StatusRuntimeException e) {
			if (e.getStatus().getCode() == Status.Code.DEADLINE_EXCEEDED) {
				readinessEvent.finish(Result.TIMEOUT);
				failureLogger.accept("Timeout while checking readiness of gRPC API at: " + uri);
			} else {
				readinessEvent.finish(Result.ERROR);
				failureLogger.accept("Error while checking readiness of gRPC API: " + e.getMessage());
			}
			return false;
		} catch (Exception e) {
			readinessEvent.finish(Result.ERROR);
			failureLogger.accept("Error while checking readiness of gRPC API: " + e.getMessage());
			return false;
		}
	}

}
