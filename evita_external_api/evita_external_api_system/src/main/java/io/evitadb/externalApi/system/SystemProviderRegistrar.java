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

package io.evitadb.externalApi.system;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseBuilder;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.file.HttpFile;
import io.evitadb.api.observability.HealthProblem;
import io.evitadb.api.observability.ReadinessState;
import io.evitadb.api.requestResponse.system.SystemStatus;
import io.evitadb.core.Evita;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.externalApi.api.system.ProbesProvider;
import io.evitadb.externalApi.api.system.ProbesProvider.ApiState;
import io.evitadb.externalApi.api.system.ProbesProvider.CatalogRoutingSettlement;
import io.evitadb.externalApi.api.system.ProbesProvider.Readiness;
import io.evitadb.externalApi.configuration.ApiOptions;
import io.evitadb.externalApi.configuration.CertificateOptions;
import io.evitadb.externalApi.configuration.CertificatePath;
import io.evitadb.externalApi.event.ReadinessEvent;
import io.evitadb.externalApi.event.ReadinessEvent.Prospective;
import io.evitadb.externalApi.event.ReadinessEvent.Result;
import io.evitadb.externalApi.http.CorsService;
import io.evitadb.externalApi.http.ExternalApiProvider;
import io.evitadb.externalApi.http.ExternalApiProviderRegistrar;
import io.evitadb.externalApi.http.ExternalApiServer;
import io.evitadb.externalApi.http.routing.EndpointRoutingService;
import io.evitadb.externalApi.system.configuration.SystemOptions;
import io.evitadb.utils.CertificateUtils;
import io.evitadb.utils.StringUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.File;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Registers System API provider to provide system API to clients.
 *
 * @author Tomáš Pozler, 2023
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class SystemProviderRegistrar implements ExternalApiProviderRegistrar<SystemOptions> {
	public static final String ENDPOINT_SERVER_NAME = "server-name";
	public static final String ENDPOINT_SYSTEM_STATUS = "status";
	public static final String ENDPOINT_SYSTEM_LIVENESS = "liveness";
	public static final String ENDPOINT_SYSTEM_READINESS = "readiness";

	/**
	 * Prints API readiness and, during catalog startup, the number of pending catalogs.
	 *
	 * @param builder   http response builder
	 * @param readiness the readiness of the APIs
	 * @param status overall readiness including startup catalog routing
	 * @param catalogsPending count to include during catalog startup, or null when catalog routing has settled
	 * @return JSON readiness response
	 */
	private static HttpResponse printApiStatus(
		@Nonnull HttpResponseBuilder builder,
		@Nonnull Readiness readiness,
		@Nonnull ReadinessState status,
		@Nullable Integer catalogsPending
	) {
		return builder.content(MediaType.JSON, "{\n" +
			"\t\"status\": \"" + status.name() + "\",\n" +
			(catalogsPending == null ? "" : "\t\"catalogsPending\": " + catalogsPending + ",\n") +
			"\t\"apis\": {\n" +
			Arrays.stream(readiness.apiStates())
				.sorted(Comparator.comparing(ApiState::apiCode))
				.map(entry -> "\t\t\"" + entry.apiCode() + "\": \"" + (entry.isReady() ? "ready" : "not ready") + "\"")
				.collect(Collectors.joining(",\n")) + "\n" +
			"\t}\n" +
			"}").build();
	}

	/**
	 * Renders the unavailable response (should not happen).
	 */
	private static HttpResponse renderUnavailable() {
		return HttpResponse.of(HttpStatus.SERVICE_UNAVAILABLE, MediaType.JSON, "{\"status\": \"" + ReadinessState.SHUTDOWN.name() + "\"}");
	}

	/**
	 * Renders the status of the evitaDB server as a JSON string.
	 *
	 * @param evita             the evitaDB server
	 * @param externalApiServer the external API server
	 * @param apiOptions        the common settings shared among all the API endpoints
	 * @param enabledEndPoints  the enabled API endpoints
	 */
	private static CompletableFuture<HttpResponse> renderStatus(
		@Nonnull Evita evita,
		@Nonnull ExternalApiServer externalApiServer,
		@Nonnull ApiOptions apiOptions,
		@Nonnull String[] enabledEndPoints) {
		return evita.executeAsyncInRequestThreadPool(
			() -> {
				if (evita.isActive()) {
					final HttpResponseBuilder builder = HttpResponse.builder();
					builder.status(HttpStatus.OK);
					final Set<HealthProblem> healthProblems = externalApiServer.getProbeProviders()
						.stream()
						.flatMap(it -> it.getHealthProblems(evita, externalApiServer, enabledEndPoints).stream())
						.collect(Collectors.toSet());
					final SystemStatus systemStatus = evita.management().getSystemStatus();
					return builder.content(
						MediaType.JSON,
						String.format("""
								{
								   "serverName": "%s",
								   "version": "%s",
								   "startedAt": "%s",
								   "uptime": %d,
								   "uptimeForHuman": "%s",
								   "engineVersion": %s,
								   "introducedAt": "%s",
								   "catalogsCorrupted": %d,
								   "catalogsActive": %d,
								   "catalogsInactive": %d,
								   "healthProblems": [%s],
								   "apis": [
								%s
								   ]
								}""",
							evita.getConfiguration().name(),
							systemStatus.version(),
							DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(systemStatus.startedAt()),
							systemStatus.uptime().toSeconds(),
							StringUtils.formatDuration(systemStatus.uptime()),
				            systemStatus.engineVersion(),
		                    DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(systemStatus.introducedAt()),
							systemStatus.catalogsCorrupted(),
							systemStatus.catalogsActive(),
							systemStatus.catalogsInactive(),
							healthProblems.stream()
								.sorted()
								.map(it -> "\"" + it.name() + "\"")
								.collect(Collectors.joining(", ")),
							apiOptions.endpoints()
								.entrySet()
								.stream()
								.filter(entry -> Arrays.stream(enabledEndPoints).anyMatch(it -> it.equals(entry.getKey())))
								.sorted(Map.Entry.comparingByKey())
								.map(
									entry -> "      {\n         \"" + entry.getKey() + "\": " +
										"[\n" + Arrays.stream(entry.getValue().getBaseUrls())
										.map(it -> "            \"" + it + "\"")
										.collect(Collectors.joining(",\n")) +
										"\n         ]" +
										"\n      }"
								)
								.collect(Collectors.joining(",\n"))
						)
					).build();
				} else {
					return renderUnavailable();
				}
			}).toCompletableFuture();
	}

	/**
	 * Renders the readiness response. It answers HTTP 200 only when the probe reports `READY` and startup catalog
	 * routing has settled. Until routing settles, `READY` is reported as `STARTING` and the response carries the number
	 * of pending catalogs as `catalogsPending`. Every other state answers HTTP 503.
	 *
	 * @param evita             the evitaDB server
	 * @param externalApiServer the external API server
	 * @param enabledEndPoints  the enabled API endpoints
	 * @return asynchronous HTTP readiness response
	 */
	private static CompletableFuture<HttpResponse> renderReadinessResponse(
		@Nonnull Evita evita,
		@Nonnull ExternalApiServer externalApiServer,
		@Nonnull String[] enabledEndPoints
	) {
		return evita.executeAsyncInRequestThreadPool(
			() -> {
				if (!evita.isActive()) {
					return renderUnavailable();
				}
				final HttpResponseBuilder builder = HttpResponse.builder();
				final Optional<ProbesProvider> probe = externalApiServer.getProbeProviders().stream().findFirst();
				if (probe.isEmpty()) {
					return builder.status(HttpStatus.SERVICE_UNAVAILABLE)
						.content(MediaType.JSON, "{\"status\": \"" + ReadinessState.UNKNOWN.name() + "\"}").build();
				}

				final ProbesProvider provider = probe.get();
				final Readiness readiness = provider.getReadiness(evita, externalApiServer, enabledEndPoints);
				final CatalogRoutingSettlement outcome = provider.getCatalogRoutingSettlement(evita, externalApiServer);
				final boolean settled = outcome.settled();
				final ReadinessState status =
					CatalogRoutingSettlement.resolveReadinessState(readiness.state(), settled);
				builder.status(status == ReadinessState.READY ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE);
				return printApiStatus(builder, readiness, status, settled ? null : outcome.catalogsPending());
			}).toCompletableFuture();
	}

	/**
	 * Renders the liveness response.
	 *
	 * @param evita             the evitaDB server
	 * @param externalApiServer the external API server
	 * @param enabledEndPoints  the enabled API endpoints
	 */
	private static CompletableFuture<HttpResponse> renderLivenessResponse(
		@Nonnull Evita evita,
		@Nonnull ExternalApiServer externalApiServer,
		@Nonnull String[] enabledEndPoints
	) {
		return evita.executeAsyncInRequestThreadPool(
			() -> {
				if (evita.isActive()) {
					final Set<HealthProblem> healthProblems = externalApiServer.getProbeProviders()
						.stream()
						.flatMap(it -> it.getHealthProblems(evita, externalApiServer, enabledEndPoints).stream())
						.collect(Collectors.toSet());

					if (healthProblems.isEmpty()) {
						return HttpResponse.of(HttpStatus.OK, MediaType.JSON, "{\"status\": \"healthy\"}");
					} else {
						return HttpResponse.of(
							HttpStatus.SERVICE_UNAVAILABLE,
							MediaType.JSON,
							"{\"status\": \"unhealthy\", \"problems\": [" +
								healthProblems.stream()
									.sorted()
									.map(it -> "\"" + it.name() + "\"")
									.collect(Collectors.joining(", ")) +
								"]}"
						);
					}
				} else {
					return renderUnavailable();
				}
			}).toCompletableFuture();
	}

	@Nonnull
	@Override
	public String getExternalApiCode() {
		return SystemProvider.CODE;
	}

	@Nonnull
	@Override
	public Class<SystemOptions> getConfigurationClass() {
		return SystemOptions.class;
	}

	/**
	 * Registers a certificate file at a fixed public download route, independently of its configured filename.
	 *
	 * @param router   builder receiving the download route
	 * @param fileName fixed public download name
	 * @param file     certificate file read on each request
	 */
	private static void registerCertificateDownload(
		@Nonnull EndpointRoutingService.Builder router, @Nonnull String fileName, @Nonnull File file
	) {
		router.add(
			HttpMethod.GET, "/" + fileName,
			createCorsWrapper((ctx, req) -> {
				ctx.addAdditionalResponseHeader(
					HttpHeaderNames.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\""
				);
				return HttpFile.of(file).asService().serve(ctx, req);
			})
		);
	}

	@Nonnull
	@Override
	public ExternalApiProvider<SystemOptions> register(
		@Nonnull Evita evita,
		@Nonnull ExternalApiServer externalApiServer,
		@Nonnull ApiOptions apiOptions,
		@Nonnull SystemOptions systemConfig
	) {
		final EndpointRoutingService.Builder router = new EndpointRoutingService.Builder();
		router.add(
			HttpMethod.GET,
			"/" + ENDPOINT_SERVER_NAME,
			createCorsWrapper(
				(ctx, req) -> {
					new ReadinessEvent(SystemProvider.CODE, Prospective.SERVER).finish(Result.READY);
					return HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT, evita.getConfiguration().name());
				}
			)
		);

		final String[] enabledEndPoints = apiOptions.getEnabledApiEndpoints();
		router.add(
			HttpMethod.GET,
			"/" + ENDPOINT_SYSTEM_STATUS,
			createCorsWrapper(
				(ctx, req) -> HttpResponse.of(
					renderStatus(
						evita,
						externalApiServer,
						apiOptions,
						enabledEndPoints
					)
				)
			)
		);

		router.add(
			HttpMethod.GET,
			"/" + ENDPOINT_SYSTEM_LIVENESS,
			createCorsWrapper(
				(ctx, req) -> HttpResponse.of(renderLivenessResponse(evita, externalApiServer, enabledEndPoints))
			)
		);

		router.add(
			HttpMethod.GET,
			"/" + ENDPOINT_SYSTEM_READINESS,
			createCorsWrapper(
				(ctx, req) -> HttpResponse.of(renderReadinessResponse(evita, externalApiServer, enabledEndPoints))
			)
		);

		final CertificateOptions certificateSettings = apiOptions.certificate();
		final boolean atLeastOnEndpointRequiresTls = apiOptions.atLeastOneEndpointRequiresTls();
		if (atLeastOnEndpointRequiresTls) {
			final File serverCertificate;
			if (certificateSettings.generateAndUseSelfSigned()) {
				serverCertificate = certificateSettings.getFolderPath()
					.resolve(CertificateUtils.getGeneratedServerCertificateFileName()).toFile();
			} else {
				final CertificatePath certificatePath = certificateSettings.custom();
				if (certificatePath == null ||
					certificatePath.certificate() == null || certificatePath.privateKey() == null) {
					throw new GenericEvitaInternalError(
						"Certificate path is not properly set in the configuration file and " +
							"`generateAndUseSelfSigned` is set to false."
					);
				}
				serverCertificate = new File(certificatePath.certificate());
			}
			registerCertificateDownload(
				router, CertificateUtils.getGeneratedServerCertificateFileName(), serverCertificate
			);
			if (certificateSettings.generateAndUseSelfSigned()) {
				final File folder = serverCertificate.getParentFile();
				final String clientCertificate = CertificateUtils.getGeneratedClientCertificateFileName();
				final String clientKey = CertificateUtils.getGeneratedClientCertificatePrivateKeyFileName();
				registerCertificateDownload(router, clientCertificate, new File(folder, clientCertificate));
				registerCertificateDownload(router, clientKey, new File(folder, clientKey));
			}
		}

		final boolean atLeastOnEndpointRequiresMtls = apiOptions.atLeastOnEndpointRequiresMtls();
		final LinkedHashMap<String, String[]> endpoints = new LinkedHashMap<>(16);
		endpoints.put(
			SystemProvider.SERVER_NAME_URL,
			Arrays.stream(systemConfig.getBaseUrls())
				.map(it -> it + ENDPOINT_SERVER_NAME)
				.toArray(String[]::new)
		);
		if (atLeastOnEndpointRequiresTls) {
			endpoints.put(
				SystemProvider.SERVER_CERTIFICATE_URL,
				Arrays.stream(systemConfig.getBaseUrls())
					.map(it -> it + CertificateUtils.getGeneratedServerCertificateFileName())
					.toArray(String[]::new)
			);
		}
		if (certificateSettings.generateAndUseSelfSigned() && atLeastOnEndpointRequiresMtls) {
			endpoints.put(
				SystemProvider.CLIENT_CERTIFICATE_URL,
				Arrays.stream(systemConfig.getBaseUrls())
					.map(it -> it + CertificateUtils.getGeneratedClientCertificateFileName())
					.toArray(String[]::new)
			);
			endpoints.put(
				SystemProvider.CLIENT_PRIVATE_KEY_URL,
				Arrays.stream(systemConfig.getBaseUrls())
					.map(it -> it + CertificateUtils.getGeneratedClientCertificatePrivateKeyFileName())
					.toArray(String[]::new)
			);
		}
		return new SystemProvider(systemConfig, router.build(), endpoints, apiOptions.requestTimeoutInMillis());
	}

	@Nonnull
	private static HttpService createCorsWrapper(@Nonnull HttpService delegate) {
		return CorsService.filter(
			delegate,
			Set.of(HttpMethod.GET),
			Set.of()
		);
	}
}
