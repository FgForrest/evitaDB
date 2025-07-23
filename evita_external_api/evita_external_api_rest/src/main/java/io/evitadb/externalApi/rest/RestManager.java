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

package io.evitadb.externalApi.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.server.HttpService;
import io.evitadb.api.CatalogContract;
import io.evitadb.api.requestResponse.cdc.ChangeCaptureContent;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCaptureRequest;
import io.evitadb.core.Evita;
import io.evitadb.core.UnusableCatalog;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.externalApi.configuration.HeaderOptions;
import io.evitadb.externalApi.http.PathNormalizingHandler;
import io.evitadb.externalApi.rest.api.Rest;
import io.evitadb.externalApi.rest.api.catalog.CatalogRestBuilder;
import io.evitadb.externalApi.rest.api.catalog.SystemRestRefreshingObserver;
import io.evitadb.externalApi.rest.api.openApi.OpenApiWriter;
import io.evitadb.externalApi.rest.api.system.SystemRestBuilder;
import io.evitadb.externalApi.rest.configuration.RestOptions;
import io.evitadb.externalApi.rest.exception.RestInternalError;
import io.evitadb.externalApi.rest.io.RestInstanceType;
import io.evitadb.externalApi.rest.io.RestRouter;
import io.evitadb.externalApi.rest.metric.event.instance.BuiltEvent;
import io.evitadb.externalApi.rest.metric.event.instance.BuiltEvent.BuildType;
import io.evitadb.utils.Assert;
import io.swagger.v3.oas.models.OpenAPI;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
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
	 * Instance of the Evita object used by the RestManager to manage and interact with the EvitaDB system.
	 * It plays a central role in facilitating operations such as registering, unregistering,
	 * and refreshing catalog-specific REST endpoints.
	 */
	@Nonnull private final Evita evita;

	/**
	 * Header options for REST API.
	 */
	@Nonnull private final HeaderOptions headerOptions;

	/**
	 * REST specific options.
	 */
	@Nonnull private final RestOptions restOptions;

	/**
	 * REST specific endpoint router.
	 */
	private final RestRouter restRouter;
	/**
	 * All registered catalogs
	 */
	@Nonnull private final Set<String> registeredCatalogs = createHashSet(20);

	/**
	 * Completable future that is completed when all initial catalogs are registered.
	 */
	private final CompletableFuture<Void> fullyInitialized;

	@Nullable private SystemBuildStatistics systemBuildStatistics;
	@Nonnull private final Map<String, CatalogBuildStatistics> catalogBuildStatistics = createHashMap(20);

	public RestManager(@Nonnull Evita evita, @Nonnull HeaderOptions headerOptions, @Nonnull RestOptions restOptions) {
		this.evita = evita;
		this.headerOptions = headerOptions;
		this.restOptions = restOptions;

		final ObjectMapper objectMapper = new ObjectMapper();
		this.restRouter = new RestRouter(objectMapper, headerOptions, restOptions);

		// listen to any evita catalog changes
		evita.registerSystemChangeCapture(new ChangeSystemCaptureRequest(null, null, ChangeCaptureContent.BODY))
			.subscribe(new SystemRestRefreshingObserver(this));

		// register initial endpoints
		registerSystemApi();

		// register initial catalogs when they are loaded
		this.fullyInitialized = CompletableFuture.allOf(
			Arrays.stream(this.evita.getInitialLoadCatalogFutures())
			      .map(theFuture -> theFuture.thenAccept(catalog -> registerCatalog(catalog.getName())))
			      .toArray(CompletableFuture[]::new)
		).whenComplete(
			(__, throwable) -> {
				if (throwable != null) {
					log.error("Failed to register initial catalogs for GraphQL API.", throwable);
				} else {
					log.info("REST API initialized with {} registered catalogs.", this.registeredCatalogs.size());
				}
			}
		);
	}

	/**
	 * Determines whether the current {@code io.evitadb.externalApi.rest.RestManager} instance has been fully initialized.
	 *
	 * @return {@code true} if the initialization process is complete, otherwise {@code false}.
	 */
	public boolean isFullyInitialized() {
		return this.fullyInitialized.isDone();
	}

	@Nonnull
	public HttpService getRestRouter() {
		return new PathNormalizingHandler(this.restRouter);
	}

	/**
	 * Builds and registers system API to manage evitaDB
	 */
	private void registerSystemApi() {
		final long instanceBuildStartTime = System.currentTimeMillis();

		final SystemRestBuilder systemRestBuilder = new SystemRestBuilder(
			this.restOptions,
			this.headerOptions,
			this.evita
		);
		final long schemaBuildStartTime = System.currentTimeMillis();
		final Rest api = systemRestBuilder.build();
		final long schemaBuildDuration = System.currentTimeMillis() - schemaBuildStartTime;

		this.restRouter.registerSystemApi(api);
		final long instanceBuildDuration = System.currentTimeMillis() - instanceBuildStartTime;

		// build metrics
		this.systemBuildStatistics = SystemBuildStatistics.createNew(
			instanceBuildDuration,
			schemaBuildDuration,
			countOpenApiSchemaLines(api.openApi()),
			api.openApi().getPaths().size()
		);
	}

	/**
	 * Register REST endpoints for new catalog.
	 */
	public boolean registerCatalog(@Nonnull String catalogName) {
		final CatalogContract catalog = this.evita.getCatalogInstanceOrThrowException(catalogName);
		if (catalog instanceof UnusableCatalog) {
			log.warn("Catalog `" + catalogName + "` is unusable (" + catalog.getCatalogState() + "). Skipping...");
			return false;
		}
		Assert.isPremiseValid(
			!this.registeredCatalogs.contains(catalogName),
			() -> new RestInternalError("Catalog `" + catalogName + "` has been already registered.")
		);

		try {
			final long instanceBuildStartTime = System.currentTimeMillis();

			final CatalogRestBuilder catalogRestBuilder = new CatalogRestBuilder(this.restOptions, this.headerOptions, this.evita, catalog);
			final long schemaBuildStartTime = System.currentTimeMillis();
			final Rest api = catalogRestBuilder.build();
			final long schemaBuildDuration = System.currentTimeMillis() - schemaBuildStartTime;

			this.registeredCatalogs.add(catalogName);
			this.restRouter.registerCatalogApi(catalogName, api);
			final long instanceBuildDuration = System.currentTimeMillis() - instanceBuildStartTime;

			// build metrics
			Assert.isPremiseValid(
				!this.catalogBuildStatistics.containsKey(catalogName),
				() -> new RestInternalError("There are already build statistics present for catalog `" + catalogName + "`.")
			);
			final CatalogBuildStatistics buildStatistics = CatalogBuildStatistics.createNew(
				instanceBuildDuration,
				schemaBuildDuration,
				countOpenApiSchemaLines(api.openApi()),
				api.openApi().getPaths().size()
			);
			this.catalogBuildStatistics.put(catalogName, buildStatistics);
			return true;
		} catch (EvitaInternalError ex) {
			// log and skip the catalog entirely
			log.error("Catalog `" + catalogName + "` is corrupted and will not accessible by REST API.", ex);

			// cleanup corrupted paths
			this.restRouter.unregisterCatalogApi(catalogName);
			this.catalogBuildStatistics.remove(catalogName);
			return false;
		}
	}

	/**
	 * Unregister all REST endpoints of catalog.
	 */
	public void unregisterCatalog(@Nonnull String catalogName) {
		final boolean catalogRegistered = this.registeredCatalogs.remove(catalogName);
		if (catalogRegistered) {
			this.restRouter.unregisterCatalogApi(catalogName);
			this.catalogBuildStatistics.remove(catalogName);
		}
	}

	/**
	 * Update REST endpoints and OpenAPI schema of catalog.
	 */
	public boolean refreshCatalog(@Nonnull String catalogName) {
		if (!this.registeredCatalogs.contains(catalogName)) {
			// there may be case where initial registration failed and catalog is not registered at all
			// for example, when catalog was corrupted and is replaced with new fresh one
			log.info("Could not refresh existing catalog `{}`. Registering new one instead...", catalogName);
			return registerCatalog(catalogName);
		}

		final long instanceBuildStartTime = System.currentTimeMillis();

		final CatalogContract catalog = this.evita.getCatalogInstanceOrThrowException(catalogName);
		final CatalogRestBuilder catalogRestBuilder = new CatalogRestBuilder(this.restOptions, this.headerOptions, this.evita, catalog);
		final long schemaBuildStartTime = System.currentTimeMillis();
		final Rest newApi = catalogRestBuilder.build();
		final long schemaBuildDuration = System.currentTimeMillis() - schemaBuildStartTime;

		this.restRouter.unregisterCatalogApi(catalogName);
		this.restRouter.registerCatalogApi(catalogName, newApi);
		final long instanceBuildDuration = System.currentTimeMillis() - instanceBuildStartTime;

		// build metrics
		final CatalogBuildStatistics buildStatistics = this.catalogBuildStatistics.get(catalogName);
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

		return true;
	}

	/**
	 * Allows emitting start events when observability facilities are already initialized.
	 * If we didn't postpone this initialization, events would become lost.
	 */
	public void emitObservabilityEvents() {
		Assert.isPremiseValid(
			this.systemBuildStatistics != null,
			() -> new RestInternalError("No build statistics for system API found.")
		);
		if (!this.systemBuildStatistics.reported().get()) {
			new BuiltEvent(
				RestInstanceType.SYSTEM,
				BuildType.NEW,
				this.systemBuildStatistics.instanceBuildDuration(),
				this.systemBuildStatistics.schemaBuildDuration(),
				this.systemBuildStatistics.schemaDslLines(),
				this.systemBuildStatistics.registeredEndpoints()
			).commit();

			this.systemBuildStatistics.markAsReported();
		}

		this.catalogBuildStatistics.keySet().forEach(this::emitObservabilityEvents);
	}

	/**
	 * Allows emitting start events when observability facilities are already initialized.
	 * If we didn't postpone this initialization, events would become lost.
	 */
	public void emitObservabilityEvents(@Nonnull String catalogName) {
		final CatalogBuildStatistics buildStatistics = this.catalogBuildStatistics.get(catalogName);
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
