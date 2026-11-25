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
import io.evitadb.api.CatalogState;
import io.evitadb.api.exception.CatalogGoingLiveException;
import io.evitadb.api.exception.CatalogRequiresUpgradeException;
import io.evitadb.api.requestResponse.cdc.ChangeCaptureContent;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCaptureCriteria;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCaptureRequest;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.UnusableCatalog;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.externalApi.configuration.HeaderOptions;
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
import io.evitadb.utils.ExceptionUtils;
import io.swagger.v3.oas.models.OpenAPI;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static io.evitadb.utils.CollectionUtils.createConcurrentHashMap;

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
	@Nonnull private final Set<String> registeredCatalogs = Collections.newSetFromMap(createConcurrentHashMap(20));

	/**
	 * Catalogs whose latest attempted publication failed. A successful registration or refresh removes the catalog.
	 */
	@Nonnull private final Set<String> failedCatalogs = Collections.newSetFromMap(createConcurrentHashMap(20));

	/**
	 * Catalogs this manager owes no publication for, because they were already active when it subscribed to catalog
	 * events and their announcement may have preceded the subscription.
	 */
	@Nonnull private final Set<String> catalogsNotOwed = Collections.newSetFromMap(createConcurrentHashMap(20));

	/**
	 * Whether the system change capture subscription has terminated.
	 */
	private volatile boolean catalogRoutingSubscriptionAbandoned;

	/**
	 * Tracks completion of the initial engine catalog loads for logging.
	 */
	private final CompletableFuture<Void> fullyInitialized;

	@Nullable private SystemBuildStatistics systemBuildStatistics;
	/**
	 * Build statistics of each registered catalog, keyed by catalog name. Concurrent, because live system change
	 * capture callbacks add and remove catalogs on the capture executor while {@link #emitObservabilityEvents()}
	 * iterates them on the thread initializing the web API server, for example when an embedded engine is still
	 * loading catalogs. Mutations committed before the subscription do not overlap: they are replayed synchronously
	 * on the subscribing thread.
	 */
	@Nonnull private final Map<String, CatalogBuildStatistics> catalogBuildStatistics = createConcurrentHashMap(20);

	/**
	 * Creates this manager, subscribes to catalog changes and publishes the system API.
	 *
	 * @param evita engine serving the API
	 * @param headerOptions HTTP header configuration
	 * @param restOptions endpoint configuration
	 */
	public RestManager(@Nonnull Evita evita, @Nonnull HeaderOptions headerOptions, @Nonnull RestOptions restOptions) {
		this.evita = evita;
		this.headerOptions = headerOptions;
		this.restOptions = restOptions;

		final ObjectMapper objectMapper = new ObjectMapper();
		this.restRouter = new RestRouter(objectMapper, headerOptions);

		// listen to any evita catalog changes — explicitly opt into BOTH areas:
		// `ENGINE` carries durable WAL-replicated engine mutations (catalog schema create /
		// rename / etc.), `HOST` carries host-local `HostSystemEvent`s that
		// announce when a catalog's local reference settles into a stable state on this
		// host (required to recover from boot-time auto-upgrade and other transient
		// state transitions where the engine mutation alone does not announce settlement).
		evita.registerSystemChangeCapture(
			ChangeSystemCaptureRequest.builder()
				.sinceVersion(this.evita.getEngineState().startVersion() + 1)
				.content(ChangeCaptureContent.BODY)
				.criteria(
					ChangeSystemCaptureCriteria.builder().engineArea().build(),
					ChangeSystemCaptureCriteria.builder().hostArea().build()
				)
				.build()
		)
			.subscribe(new SystemRestRefreshingObserver(this));

		// Already active catalogs can have published their live-tail event before this subscription was installed.
		for (final CatalogContract catalog : this.evita.getCatalogs()) {
			if (catalog.getCatalogState().isActive()) {
				this.catalogsNotOwed.add(catalog.getName());
			}
		}

		// register initial endpoints
		registerSystemApi();

		// Track initial catalog loading. Catalog registration is driven by the system CDC
		// stream (HostSystemEvent.CatalogInstalledIntoLiveView).
		this.fullyInitialized = CompletableFuture.allOf(
			this.evita.getInitialLoadCatalogFutures()
		).whenComplete(
			(__, throwable) -> {
				if (throwable != null) {
					// boot-time auto-upgrade completes the first-attempt future exceptionally by
					// design; the retried load will register the catalog asynchronously
					final Throwable cause = ExceptionUtils.unwrapCompletionWrappers(throwable);
					if (cause instanceof CatalogRequiresUpgradeException) {
						log.info("REST API initial catalog loading complete; one or more catalogs are pending storage-protocol upgrade.");
					} else {
						log.error("REST API initial catalog loading failed.", throwable);
					}
				} else {
					log.info("REST API initial catalog loading complete.");
				}
			}
		);
	}

	/**
	 * Reports whether this manager has settled the catalog's routing obligation.
	 *
	 * @param catalogName name whose routing is being checked
	 * @return true if the route is registered, publication failed, this manager owes no publication, or the catalog
	 *         event subscription has terminated
	 */
	public boolean isCatalogRoutingSettled(@Nonnull String catalogName) {
		return this.catalogRoutingSubscriptionAbandoned || this.catalogsNotOwed.contains(catalogName) ||
			this.failedCatalogs.contains(catalogName) || this.registeredCatalogs.contains(catalogName);
	}

	/**
	 * Records termination of the catalog routing subscription.
	 *
	 * @param failure error that terminated the subscription, or null for normal completion
	 */
	public void catalogRoutingSubscriptionTerminated(@Nullable Throwable failure) {
		this.catalogRoutingSubscriptionAbandoned = true;
		if (failure != null) {
			final List<String> unpublishedCatalogs = this.evita.getCatalogs().stream()
				.filter(catalog -> catalog.getCatalogState().isActive())
				.map(CatalogContract::getName)
				.filter(name -> !this.registeredCatalogs.contains(name))
				.sorted()
				.toList();
			log.error("REST catalog routing subscription failed. Active catalogs REST has not activated: {}",
				unpublishedCatalogs, failure);
		}
	}

	/**
	 * Returns whether the given catalog already has REST endpoints registered with this manager.
	 * Used by the system CDC observer to decide between a first-time `registerCatalog` and a
	 * subsequent `refreshCatalog` when handling a `CatalogInstalledIntoLiveView` host event.
	 */
	public boolean isCatalogRegistered(@Nonnull String catalogName) {
		return this.registeredCatalogs.contains(catalogName);
	}

	/**
	 * Returns the current engine state under a catalog name, which may be ahead of a queued host event.
	 * The system observer uses this to preserve endpoints of a later active catalog incarnation.
	 *
	 * @param catalogName name whose current binding is inspected
	 * @return current catalog state, or empty when the name no longer exists
	 */
	@Nonnull
	public Optional<CatalogState> getCatalogState(@Nonnull String catalogName) {
		return this.evita.getCatalogState(catalogName);
	}

	@Nonnull
	public HttpService getRestRouter() {
		return this.restRouter;
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
	 * Builds and publishes the REST endpoints for a catalog. An unusable catalog is skipped and stays owed. A failed
	 * build marks the catalog's publication failed, which settles its routing until a later registration or refresh
	 * succeeds: an internal error is logged and the partial registration cleaned up, any other failure propagates.
	 * A {@link CatalogGoingLiveException} propagates without the mark and leaves the catalog owed; the host event that
	 * announces the live catalog triggers its publication.
	 *
	 * @param catalogName name of the catalog to publish
	 * @return true when published, false when skipped or publication failed with an internal error
	 * @throws RestInternalError when the catalog is already registered
	 * @throws CatalogGoingLiveException when access is interrupted by the catalog going live
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

			this.restRouter.registerCatalogApi(catalogName, api);
			this.registeredCatalogs.add(catalogName);
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
			this.failedCatalogs.remove(catalogName);
			return true;
		} catch (CatalogGoingLiveException ex) {
			throw ex;
		} catch (EvitaInternalError ex) {
			this.failedCatalogs.add(catalogName);
			// log and skip the catalog entirely
			log.error("Catalog `" + catalogName + "` is corrupted and will not accessible by REST API.", ex);

			// cleanup corrupted paths and registration bookkeeping
			this.registeredCatalogs.remove(catalogName);
			this.restRouter.unregisterCatalogApi(catalogName);
			this.catalogBuildStatistics.remove(catalogName);
			return false;
		} catch (Throwable ex) {
			this.failedCatalogs.add(catalogName);
			throw ex;
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
	 * Update REST endpoints and OpenAPI schema of catalog. The new endpoints replace the old ones in one step once they
	 * are built, so a build failure propagates and leaves the previous endpoints serving. Any failure other than a
	 * {@link CatalogGoingLiveException} also marks the catalog's publication failed, which settles its routing even if
	 * the caller then withdraws the endpoints; a successful rebuild clears the mark. A catalog that is not registered
	 * yet is registered by {@link #registerCatalog(String)} instead.
	 *
	 * @param catalogName name of the catalog to refresh or register
	 * @return true when the catalog's endpoints are published afterwards, false only when the fallback registration
	 *         skipped the catalog or failed with an internal error
	 */
	public boolean refreshCatalog(@Nonnull String catalogName) {
		if (!this.registeredCatalogs.contains(catalogName)) {
			// there may be case where initial registration failed and catalog is not registered at all
			// for example, when catalog was corrupted and is replaced with new fresh one
			log.info("Could not refresh existing catalog `{}`. Registering new one instead...", catalogName);
			return registerCatalog(catalogName);
		}

		try {
			this.rebuildCatalogApi(catalogName);
			this.failedCatalogs.remove(catalogName);
			return true;
		} catch (CatalogGoingLiveException ex) {
			throw ex;
		} catch (Throwable ex) {
			this.failedCatalogs.add(catalogName);
			throw ex;
		}
	}

	/**
	 * Rebuilds the REST API of a registered catalog, swaps it in behind its existing endpoints and refreshes its
	 * build statistics.
	 *
	 * @param catalogName name of the registered catalog
	 */
	private void rebuildCatalogApi(@Nonnull String catalogName) {
		final long instanceBuildStartTime = System.currentTimeMillis();

		final CatalogContract catalog = this.evita.getCatalogInstanceOrThrowException(catalogName);
		final CatalogRestBuilder catalogRestBuilder = new CatalogRestBuilder(this.restOptions, this.headerOptions, this.evita, catalog);
		final long schemaBuildStartTime = System.currentTimeMillis();
		final Rest newApi = catalogRestBuilder.build();
		final long schemaBuildDuration = System.currentTimeMillis() - schemaBuildStartTime;

		this.restRouter.refreshCatalogApi(catalogName, newApi);
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

		// iterate the entries rather than looking each key up: a catalog may be removed concurrently, see the field
		for (final Map.Entry<String, CatalogBuildStatistics> entry : this.catalogBuildStatistics.entrySet()) {
			emitCatalogBuiltEvent(entry.getKey(), entry.getValue());
		}
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
		emitCatalogBuiltEvent(catalogName, buildStatistics);
	}

	/**
	 * Emits the build event of a catalog's REST API from its build statistics.
	 *
	 * @param catalogName     name of the catalog the statistics belong to
	 * @param buildStatistics statistics of the catalog's latest build
	 */
	private static void emitCatalogBuiltEvent(
		@Nonnull String catalogName,
		@Nonnull CatalogBuildStatistics buildStatistics
	) {
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
