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

package io.evitadb.externalApi.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import io.evitadb.api.CatalogContract;
import io.evitadb.api.requestResponse.cdc.CaptureContent;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCaptureRequest;
import io.evitadb.core.CorruptedCatalog;
import io.evitadb.core.Evita;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.externalApi.http.HttpServiceTlsCheckingDecorator;
import io.evitadb.externalApi.http.PathNormalizingHandler;
import io.evitadb.externalApi.rest.api.Rest;
import io.evitadb.externalApi.rest.api.catalog.CatalogRestBuilder;
import io.evitadb.externalApi.rest.api.catalog.SystemRestRefreshingObserver;
import io.evitadb.externalApi.rest.api.openApi.OpenApiWriter;
import io.evitadb.externalApi.rest.api.system.SystemRestBuilder;
import io.evitadb.externalApi.rest.configuration.RestConfig;
import io.evitadb.externalApi.rest.exception.RestInternalError;
import io.evitadb.externalApi.rest.io.RestInstanceType;
import io.evitadb.externalApi.rest.io.RestRouter;
import io.evitadb.externalApi.rest.metric.event.instance.BuiltEvent;
import io.evitadb.externalApi.rest.metric.event.instance.BuiltEvent.BuildType;
import io.evitadb.function.TriFunction;
import io.evitadb.utils.Assert;
import io.evitadb.utils.StringUtils;
import io.swagger.v3.oas.models.OpenAPI;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static io.evitadb.utils.CollectionUtils.createHashMap;
import static io.evitadb.utils.CollectionUtils.createHashSet;

/**
 * Creates REST endpoints for particular Evita catalogs according generated OpenAPI schema.
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
@Slf4j
public class RestManager {

	/**
	 * Common object mapper for endpoints
	 */
	@Nonnull private final ObjectMapper objectMapper = new ObjectMapper();

	@Nonnull private final Evita evita;
	@Nullable private final String exposedOn;
	@Nonnull private final RestConfig restConfig;

	/**
	 * REST specific endpoint router.
	 */
	private final RestRouter restRouter;
	/**
	 * All registered catalogs
	 */
	@Nonnull private final Set<String> registeredCatalogs = createHashSet(20);

	@Nullable private SystemBuildStatistics systemBuildStatistics;
	@Nonnull private final Map<String, CatalogBuildStatistics> catalogBuildStatistics = createHashMap(20);

	@Nonnull private final TriFunction<ServiceRequestContext, HttpRequest, HttpService, HttpResponse> apiHandlerPortSslValidatingFunction;

	public RestManager(@Nonnull Evita evita, @Nullable String exposedOn, @Nonnull RestConfig restConfig, @Nonnull TriFunction<ServiceRequestContext, HttpRequest, HttpService, HttpResponse> apiHandlerPortSslValidatingFunction) {
		this.evita = evita;
		this.exposedOn = exposedOn;
		this.restConfig = restConfig;
		this.restRouter = new RestRouter(objectMapper, restConfig);
		this.apiHandlerPortSslValidatingFunction = apiHandlerPortSslValidatingFunction;

		final long buildingStartTime = System.currentTimeMillis();

		// listen to any evita catalog changes
		evita.registerSystemChangeCapture(new ChangeSystemCaptureRequest(CaptureContent.HEADER))
			.subscribe(new SystemRestRefreshingObserver(this));

		// register initial endpoints
		registerSystemApi();
		this.evita.getCatalogs().forEach(catalog -> registerCatalog(catalog.getName()));

		log.info("Built REST API in " + StringUtils.formatPreciseNano(System.currentTimeMillis() - buildingStartTime));
	}

	@Nonnull
	public HttpService getRestRouter() {
		return new HttpServiceTlsCheckingDecorator(
			new PathNormalizingHandler(restRouter), apiHandlerPortSslValidatingFunction
		);
	}

	/**
	 * Builds and registers system API to manage evitaDB
	 */
	private void registerSystemApi() {
		final long instanceBuildStartTime = System.currentTimeMillis();

		final SystemRestBuilder systemRestBuilder = new SystemRestBuilder(exposedOn, restConfig, evita);
		final long schemaBuildStartTime = System.currentTimeMillis();
		final Rest api = systemRestBuilder.build();
		final long schemaBuildDuration = System.currentTimeMillis() - schemaBuildStartTime;

		restRouter.registerSystemApi(api);
		final long instanceBuildDuration = System.currentTimeMillis() - instanceBuildStartTime;

		// build metrics
		systemBuildStatistics = SystemBuildStatistics.createNew(
			instanceBuildDuration,
			schemaBuildDuration,
			countOpenApiSchemaLines(api.openApi()),
			api.openApi().getPaths().size()
		);
	}

	/**
	 * Register REST endpoints for new catalog.
	 */
	public void registerCatalog(@Nonnull String catalogName) {
		final CatalogContract catalog = evita.getCatalogInstanceOrThrowException(catalogName);
		if (catalog instanceof CorruptedCatalog) {
			log.warn("Catalog `" + catalogName + "` is corrupted. Skipping...");
			return;
		}
		Assert.isPremiseValid(
			!registeredCatalogs.contains(catalogName),
			() -> new RestInternalError("Catalog `" + catalogName + "` has been already registered.")
		);

		try {
			final long instanceBuildStartTime = System.currentTimeMillis();

			// todo jno: uncomment this after the catalog captures are implemented
			//		catalog.registerChangeCatalogCapture(
			//			new ChangeCatalogCaptureRequest(
			//				CaptureArea.SCHEMA, new SchemaSite(Operation.values()), CaptureContent.HEADER,
			//				catalog.getLastCommittedTransactionId()
			//			)
			//		).subscribe(new CatalogRestRefreshingObserver(this));

			final CatalogRestBuilder catalogRestBuilder = new CatalogRestBuilder(exposedOn, restConfig, evita, catalog);
			final long schemaBuildStartTime = System.currentTimeMillis();
			final Rest api = catalogRestBuilder.build();
			final long schemaBuildDuration = System.currentTimeMillis() - schemaBuildStartTime;

			registeredCatalogs.add(catalogName);
			restRouter.registerCatalogApi(catalogName, api);
			final long instanceBuildDuration = System.currentTimeMillis() - instanceBuildStartTime;

			// build metrics
			Assert.isPremiseValid(
				!catalogBuildStatistics.containsKey(catalogName),
				() -> new RestInternalError("There are already build statistics present for catalog `" + catalogName + "`.")
			);
			final CatalogBuildStatistics buildStatistics = CatalogBuildStatistics.createNew(
				instanceBuildDuration,
				schemaBuildDuration,
				countOpenApiSchemaLines(api.openApi()),
				api.openApi().getPaths().size()
			);
			catalogBuildStatistics.put(catalogName, buildStatistics);
		} catch (EvitaInternalError ex) {
			// log and skip the catalog entirely
			log.error("Catalog `" + catalogName + "` is corrupted and will not accessible by REST API.", ex);

			// cleanup corrupted paths
			restRouter.unregisterCatalogApi(catalogName);
			catalogBuildStatistics.remove(catalogName);
		}
	}

	/**
	 * Unregister all REST endpoints of catalog.
	 */
	public void unregisterCatalog(@Nonnull String catalogName) {
		final boolean catalogRegistered = registeredCatalogs.remove(catalogName);
		if (catalogRegistered) {
			restRouter.unregisterCatalogApi(catalogName);
			catalogBuildStatistics.remove(catalogName);
		}
	}

	/**
	 * Update REST endpoints and OpenAPI schema of catalog.
	 */
	public void refreshCatalog(@Nonnull String catalogName) {
		if (!registeredCatalogs.contains(catalogName)) {
			// there may be case where initial registration failed and catalog is not registered at all
			// for example, when catalog was corrupted and is replaced with new fresh one
			log.info("Could not refresh existing catalog `{}`. Registering new one instead...", catalogName);
			registerCatalog(catalogName);
			return;
		}

		final long instanceBuildStartTime = System.currentTimeMillis();

		final CatalogContract catalog = evita.getCatalogInstanceOrThrowException(catalogName);
		final CatalogRestBuilder catalogRestBuilder = new CatalogRestBuilder(exposedOn, restConfig, evita, catalog);
		final long schemaBuildStartTime = System.currentTimeMillis();
		final Rest newApi = catalogRestBuilder.build();
		final long schemaBuildDuration = System.currentTimeMillis() - schemaBuildStartTime;

		restRouter.unregisterCatalogApi(catalogName);
		restRouter.registerCatalogApi(catalogName, newApi);
		final long instanceBuildDuration = System.currentTimeMillis() - instanceBuildStartTime;

		// build metrics
		final CatalogBuildStatistics buildStatistics = catalogBuildStatistics.get(catalogName);
		Assert.isPremiseValid(
			buildStatistics != null,
			() -> new RestInternalError("No build statistics found for catalog `" + catalogName + "`.")
		);
		buildStatistics.refresh(
			instanceBuildDuration,
			schemaBuildDuration,
			countOpenApiSchemaLines(newApi.openApi()),
			newApi.openApi().getPaths().size()
		);
	}

	/**
	 * Allows emitting start events when observability facilities are already initialized.
	 * If we didn't postpone this initialization, events would become lost.
	 */
	public void emitObservabilityEvents() {
		Assert.isPremiseValid(
			systemBuildStatistics != null,
			() -> new RestInternalError("No build statistics for system API found.")
		);
		if (!systemBuildStatistics.reported().get()) {
			new BuiltEvent(
				RestInstanceType.SYSTEM,
				BuildType.NEW,
				systemBuildStatistics.instanceBuildDuration(),
				systemBuildStatistics.schemaBuildDuration(),
				systemBuildStatistics.schemaDslLines(),
				systemBuildStatistics.registeredEndpoints()
			).commit();

			systemBuildStatistics.markAsReported();
		}

		catalogBuildStatistics.keySet().forEach(this::emitObservabilityEvents);
	}

	/**
	 * Allows emitting start events when observability facilities are already initialized.
	 * If we didn't postpone this initialization, events would become lost.
	 */
	public void emitObservabilityEvents(@Nonnull String catalogName) {
		final CatalogBuildStatistics buildStatistics = catalogBuildStatistics.get(catalogName);
		Assert.isPremiseValid(
			buildStatistics != null,
			() -> new RestInternalError("No build statistics found for catalog `" + catalogName + "`.")
		);

		new BuiltEvent(
			catalogName,
			RestInstanceType.CATALOG,
			buildStatistics.buildCount().get() == 1 ? BuildType.NEW : BuildType.REFRESH,
			buildStatistics.instanceBuildDuration().get(),
			buildStatistics.schemaBuildDuration().get(),
			buildStatistics.schemaDslLines().get(),
			buildStatistics.registeredEndpoints().get()
		).commit();
	}

	/**
	 * Counts lines of printed OpenAPI schema in DSL.
	 */
	private static long countOpenApiSchemaLines(@Nonnull OpenAPI schema) {
		final ByteArrayOutputStream out = new ByteArrayOutputStream();
		try {
			OpenApiWriter.toYaml(schema, out);
		} catch (IOException e) {
			throw new RestInternalError("Failed to count OpenAPI DSL lines.");
		}
		return out.toString().lines().count();
	}

	private record SystemBuildStatistics(@Nonnull AtomicBoolean reported,
	                                     long instanceBuildDuration,
	                                     long schemaBuildDuration,
	                                     long schemaDslLines,
	                                     long registeredEndpoints) {

		public static SystemBuildStatistics createNew(long instanceBuildDuration,
		                                              long schemaBuildDuration,
		                                              long schemaDslLines,
		                                              long registeredEndpoints) {
			return new SystemBuildStatistics(
				new AtomicBoolean(false),
				instanceBuildDuration,
				schemaBuildDuration,
				schemaDslLines,
				registeredEndpoints
			);
		}

		public void markAsReported() {
			this.reported.set(true);
		}
	}

	private record CatalogBuildStatistics(@Nonnull AtomicInteger buildCount,
										  @Nonnull AtomicLong instanceBuildDuration,
	                                      @Nonnull AtomicLong schemaBuildDuration,
	                                      @Nonnull AtomicLong schemaDslLines,
	                                      @Nonnull AtomicLong registeredEndpoints) {

		public static CatalogBuildStatistics createNew(long instanceBuildDuration,
		                                               long schemaBuildDuration,
		                                               long schemaDslLines,
		                                               long registeredEndpoints) {
			return new CatalogBuildStatistics(
				new AtomicInteger(1),
				new AtomicLong(instanceBuildDuration),
				new AtomicLong(schemaBuildDuration),
				new AtomicLong(schemaDslLines),
				new AtomicLong(registeredEndpoints)
			);
		}

		public void refresh(long instanceBuildDuration,
		                    long schemaBuildDuration,
		                    long schemaDslLines,
		                    long registeredEndpoints) {
			this.buildCount.incrementAndGet();
			this.instanceBuildDuration.set(instanceBuildDuration);
			this.schemaBuildDuration.set(schemaBuildDuration);
			this.schemaDslLines.set(schemaDslLines);
			this.registeredEndpoints.set(registeredEndpoints);
		}
	}
}
