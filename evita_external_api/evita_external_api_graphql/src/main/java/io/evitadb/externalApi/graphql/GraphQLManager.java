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

package io.evitadb.externalApi.graphql;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.server.HttpService;
import graphql.GraphQL;
import graphql.schema.GraphQLSchema;
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
import io.evitadb.externalApi.graphql.api.catalog.CatalogGraphQLBuilder;
import io.evitadb.externalApi.graphql.api.catalog.SystemGraphQLRefreshingObserver;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.CatalogDataApiGraphQLSchemaBuilder;
import io.evitadb.externalApi.graphql.api.catalog.schemaApi.CatalogSchemaApiGraphQLSchemaBuilder;
import io.evitadb.externalApi.graphql.api.system.SystemGraphQLBuilder;
import io.evitadb.externalApi.graphql.api.system.builder.SystemGraphQLSchemaBuilder;
import io.evitadb.externalApi.graphql.configuration.GraphQLOptions;
import io.evitadb.externalApi.graphql.exception.GraphQLInternalError;
import io.evitadb.externalApi.graphql.io.GraphQLInstanceType;
import io.evitadb.externalApi.graphql.io.GraphQLRouter;
import io.evitadb.externalApi.graphql.metric.event.instance.BuiltEvent;
import io.evitadb.externalApi.graphql.metric.event.instance.BuiltEvent.BuildType;
import io.evitadb.externalApi.graphql.utils.GraphQLSchemaPrinter;
import io.evitadb.utils.Assert;
import io.evitadb.utils.ExceptionUtils;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
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
 * Manages the whole GraphQL API (its endpoints, lifecycle, etc).
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@Slf4j
public class GraphQLManager {

	/**
	 * Common object mapper for endpoints
	 */
	@Nonnull private final ObjectMapper objectMapper = new ObjectMapper();

	/**
	 * Provides access to Evita private API
	 */
	@Nonnull private final Evita evita;

	/**
	 * Configuration settings for GraphQL queries and operations.
	 * This variable holds an instance of {@link GraphQLOptions} to set up and control
	 * various aspects of the GraphQL API.
	 */
	@Nonnull private final GraphQLOptions graphQLConfig;

	/**
	 * GraphQL specific endpoint router.
	 */
	@Nonnull private final GraphQLRouter graphQLRouter;

	/**
	 * Already registered catalogs (corresponds to existing endpoints as well)
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

	/**
	 * Statistics for system GraphQL build.
	 * This is used to emit observability events only once.
	 */
	@Nullable private SystemBuildStatistics systemBuildStatistics;

	/**
	 * Statistics for each catalog build.
	 * The key is the catalog name. Concurrent, because live system change capture callbacks add and remove catalogs
	 * on the capture executor while {@link #emitObservabilityEvents()} iterates them on the thread initializing the
	 * web API server, for example when an embedded engine is still loading catalogs. Mutations committed before the
	 * subscription do not overlap: they are replayed synchronously on the subscribing thread.
	 */
	@Nonnull private final Map<String, CatalogBuildStatistics> catalogBuildStatistics = createConcurrentHashMap(20);

	/**
	 * Creates this manager, subscribes to catalog changes and publishes the system API.
	 *
	 * @param evita engine serving the API
	 * @param headers HTTP header configuration
	 * @param graphQLConfig endpoint configuration
	 */
	public GraphQLManager(@Nonnull Evita evita, @Nonnull HeaderOptions headers, @Nonnull GraphQLOptions graphQLConfig) {
		this.evita = evita;
		this.graphQLConfig = graphQLConfig;

		this.graphQLRouter = new GraphQLRouter(this.objectMapper, evita, headers);

		// listen to any evita catalog changes — explicitly opt in to BOTH areas so the GraphQL
		// router reacts both to engine mutations (durable, WAL-replicated) AND host
		// events (e.g. boot-time auto-upgrade replacing an UnusableCatalog
		// placeholder with a real Catalog). Note: `criteria == null` would default to ENGINE
		// only; passing both criteria entries is required to receive `HostSystemEvent`s.
		evita.registerSystemChangeCapture(
			ChangeSystemCaptureRequest.builder()
				.sinceVersion(this.evita.getEngineState().startVersion() + 1)
				.content(ChangeCaptureContent.BODY)
				.criteria(
					ChangeSystemCaptureCriteria.builder().engineArea().build(),
					ChangeSystemCaptureCriteria.builder().hostArea().build()
				)
				.build()
		).subscribe(new SystemGraphQLRefreshingObserver(this));

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
						log.info("GraphQL API initial catalog loading complete; one or more catalogs are pending storage-protocol upgrade.");
					} else {
						log.error("GraphQL API initial catalog loading failed.", throwable);
					}
				} else {
					log.info("GraphQL API initial catalog loading complete.");
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
			log.error("GraphQL catalog routing subscription failed. Active catalogs left unpublished: {}",
				unpublishedCatalogs, failure);
		}
	}

	/**
	 * Returns whether the given catalog already has GraphQL endpoints registered with this manager.
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
	public HttpService getGraphQLRouter() {
		return this.graphQLRouter;
	}

	/**
	 * Initializes system GraphQL endpoint for managing Evita.
	 */
	private void registerSystemApi() {
		final long instanceBuildStartTime = System.currentTimeMillis();

		final long schemaBuildStartTime = System.currentTimeMillis();
		final GraphQLSchema schema = new SystemGraphQLSchemaBuilder(this.graphQLConfig, this.evita).build();
		final long schemaBuildDuration = System.currentTimeMillis() - schemaBuildStartTime;

		this.graphQLRouter.registerSystemApi(new SystemGraphQLBuilder(schema).build(this.graphQLConfig));
		final long instanceBuildDuration = System.currentTimeMillis() - instanceBuildStartTime;

		// build metrics
		this.systemBuildStatistics = SystemBuildStatistics.createNew(
			instanceBuildDuration,
			schemaBuildDuration,
			countGraphQLSchemaLines(schema)
		);
	}

	/**
	 * Builds and publishes the GraphQL endpoints for a catalog. An unusable catalog is skipped and stays owed. A failed
	 * build marks the catalog's publication failed, which settles its routing until a later registration or refresh
	 * succeeds: an internal error is logged and the partial registration cleaned up, any other failure propagates.
	 * A {@link CatalogGoingLiveException} propagates without the mark and leaves the catalog owed; the host event that
	 * announces the live catalog triggers its publication.
	 *
	 * @param catalogName name of the catalog to publish
	 * @return true when published, false when skipped or publication failed with an internal error
	 * @throws GraphQLInternalError when the catalog is already registered
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
			() -> new GraphQLInternalError("Catalog `" + catalogName + "` has been already registered.")
		);

		try {
			// build data API instance
			final long dataApiInstanceBuildStartTime = System.currentTimeMillis();
			final long dataApiSchemaBuildStartTime = System.currentTimeMillis();
			final GraphQLSchema dataApiSchema = new CatalogDataApiGraphQLSchemaBuilder(this.graphQLConfig, this.evita, catalog).build();
			final long dataApiSchemaBuildDuration = System.currentTimeMillis() - dataApiSchemaBuildStartTime;

			final GraphQL dataApi = new CatalogGraphQLBuilder(
				this.evita,
				catalog,
				dataApiSchema,
				this.objectMapper
			).build(this.graphQLConfig);

			final long dataApiInstanceBuildDuration = System.currentTimeMillis() - dataApiInstanceBuildStartTime;

			// build schema API instance
			final long schemaApiInstanceBuildStartTime = System.currentTimeMillis();
			final long schemaApiSchemaBuildStartTime = System.currentTimeMillis();
			final GraphQLSchema schemaApiSchema = new CatalogSchemaApiGraphQLSchemaBuilder(this.graphQLConfig, this.evita, catalog).build();
			final long schemaApiSchemaBuildDuration = System.currentTimeMillis() - schemaApiSchemaBuildStartTime;

			final GraphQL schemaApi = new CatalogGraphQLBuilder(
				this.evita,
				catalog,
				schemaApiSchema,
				this.objectMapper
			).build(this.graphQLConfig);

			this.graphQLRouter.registerCatalogApi(catalogName, dataApi, schemaApi);
			final long schemaApiInstanceBuildDuration = System.currentTimeMillis() - schemaApiInstanceBuildStartTime;

			this.registeredCatalogs.add(catalogName);

			// build metrics
			final CatalogBuildStatistics schemaBuildStatistics = CatalogBuildStatistics.createNew(
				dataApiInstanceBuildDuration,
				dataApiSchemaBuildDuration,
				countGraphQLSchemaLines(dataApiSchema),
				schemaApiInstanceBuildDuration,
				schemaApiSchemaBuildDuration,
				countGraphQLSchemaLines(schemaApiSchema)
			);
			Assert.isPremiseValid(
				!this.catalogBuildStatistics.containsKey(catalogName),
				() -> new GraphQLInternalError("No build statistics found for catalog `" + catalogName + "`")
			);
			this.catalogBuildStatistics.put(catalogName, schemaBuildStatistics);
			this.failedCatalogs.remove(catalogName);
			return true;
		} catch (CatalogGoingLiveException ex) {
			throw ex;
		} catch (EvitaInternalError ex) {
			this.failedCatalogs.add(catalogName);
			// log and skip the catalog entirely
			log.error("Catalog `" + catalogName + "` is corrupted and will not accessible by GraphQL API.", ex);

			// cleanup corrupted paths and registration bookkeeping
			this.registeredCatalogs.remove(catalogName);
			this.graphQLRouter.unregisterCatalogApis(catalogName);
			this.catalogBuildStatistics.remove(catalogName);
			return false;
		} catch (Throwable ex) {
			this.failedCatalogs.add(catalogName);
			throw ex;
		}
	}

	/**
	 * Rebuilds the DATA and SCHEMA {@link GraphQL} instances of a registered catalog and swaps them in behind its
	 * existing endpoints. Both instances are built before either is swapped, so a build failure propagates and leaves
	 * the previous instances serving. Any failure other than a {@link CatalogGoingLiveException} also marks the
	 * catalog's publication failed, which settles its routing even if the caller then withdraws the endpoints; a
	 * successful rebuild clears the mark. A catalog that is not registered yet is registered by
	 * {@link #registerCatalog(String)} instead.
	 *
	 * @param catalogName name of the catalog to refresh or register
	 * @return true when the catalog's APIs are published afterwards, false only when the fallback registration skipped
	 *         the catalog or failed with an internal error
	 */
	public boolean refreshCatalog(@Nonnull String catalogName) {
		final boolean catalogRegistered = this.registeredCatalogs.contains(catalogName);
		if (!catalogRegistered) {
			// there may be case where initial registration failed and catalog is not registered at all
			// for example, when catalog was corrupted and is replaced with new fresh one
			log.info("Could not refresh existing catalog `{}`. Registering new one instead...", catalogName);
			return registerCatalog(catalogName);
		}

		try {
			this.rebuildCatalogApis(catalogName);
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
	 * Rebuilds the DATA and SCHEMA {@link GraphQL} instances of a registered catalog, swaps them in behind its
	 * existing endpoints and refreshes its build statistics.
	 *
	 * @param catalogName name of the registered catalog
	 */
	private void rebuildCatalogApis(@Nonnull String catalogName) {
		final CatalogContract catalog = this.evita.getCatalogInstanceOrThrowException(catalogName);

		// rebuild data API instance
		final long dataApiInstanceBuildStartTime = System.currentTimeMillis();
		final long dataApiSchemaBuildStartTime = System.currentTimeMillis();
		final GraphQLSchema dataApiSchema = new CatalogDataApiGraphQLSchemaBuilder(this.graphQLConfig, this.evita, catalog).build();
		final long dataApiSchemaBuildDuration = System.currentTimeMillis() - dataApiSchemaBuildStartTime;

		final GraphQL newDataApi = new CatalogGraphQLBuilder(
			this.evita,
			catalog,
			dataApiSchema,
			this.objectMapper
		).build(this.graphQLConfig);

		final long dataApiInstanceBuildDuration = System.currentTimeMillis() - dataApiInstanceBuildStartTime;

		// rebuild schema API instance
		final long schemaApiInstanceBuildStartTime = System.currentTimeMillis();
		final long schemaApiSchemaBuildStartTime = System.currentTimeMillis();
		final GraphQLSchema schemaApiSchema = new CatalogSchemaApiGraphQLSchemaBuilder(this.graphQLConfig, this.evita, catalog).build();
		final long schemaApiSchemaBuildDuration = System.currentTimeMillis() - schemaApiSchemaBuildStartTime;

		final GraphQL newSchemaApi = new CatalogGraphQLBuilder(
			this.evita,
			catalog,
			schemaApiSchema,
			this.objectMapper
		).build(this.graphQLConfig);

		final long schemaApiInstanceBuildDuration = System.currentTimeMillis() - schemaApiInstanceBuildStartTime;

		// Publish both completed instances through the references retained by HTTP and WebSocket handlers.
		this.graphQLRouter.refreshCatalogApi(catalogName, GraphQLInstanceType.DATA, newDataApi);
		this.graphQLRouter.refreshCatalogApi(catalogName, GraphQLInstanceType.SCHEMA, newSchemaApi);

		// build metrics
		final CatalogBuildStatistics buildStatistics = this.catalogBuildStatistics.get(catalogName);
		Assert.isPremiseValid(
			buildStatistics != null,
			() -> new GraphQLInternalError("No build statistics found for catalog `" + catalogName + "`")
		);
		buildStatistics.refresh(
			dataApiInstanceBuildDuration,
			dataApiSchemaBuildDuration,
			countGraphQLSchemaLines(dataApiSchema),
			schemaApiInstanceBuildDuration,
			schemaApiSchemaBuildDuration,
			countGraphQLSchemaLines(schemaApiSchema)
		);
	}

	/**
	 * Deletes endpoint and its {@link GraphQL} instance for this already registered catalog.
	 */
	public boolean unregisterCatalog(@Nonnull String catalogName) {
		final boolean catalogRegistered = this.registeredCatalogs.remove(catalogName);
		if (catalogRegistered) {
			this.graphQLRouter.unregisterCatalogApis(catalogName);
			this.catalogBuildStatistics.remove(catalogName);
			return true;
		} else {
			return false;
		}
	}

	/**
	 * Allows emitting start events when observability facilities are already initialized.
	 * If we didn't postpone this initialization, events would become lost.
	 */
	public void emitObservabilityEvents() {
		Assert.isPremiseValid(
			this.systemBuildStatistics != null,
			() -> new GraphQLInternalError("No build statistics for system API found.")
		);
		if (!this.systemBuildStatistics.reported().get()) {
			new BuiltEvent(
				GraphQLInstanceType.SYSTEM,
				BuildType.NEW,
				this.systemBuildStatistics.instanceBuildDuration(),
				this.systemBuildStatistics.schemaBuildDuration(),
				this.systemBuildStatistics.schemaDslLines()
			).commit();

			this.systemBuildStatistics.markAsReported();
		}

		// iterate the entries rather than looking each key up: a catalog may be removed concurrently, see the field
		for (final Map.Entry<String, CatalogBuildStatistics> entry : this.catalogBuildStatistics.entrySet()) {
			emitCatalogBuiltEvents(entry.getKey(), entry.getValue());
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
			() -> new GraphQLInternalError("No build statistics found for catalog `" + catalogName + "`")
		);
		emitCatalogBuiltEvents(catalogName, buildStatistics);
	}

	/**
	 * Emits the build events of a catalog's data and schema GraphQL APIs from its build statistics.
	 *
	 * @param catalogName     name of the catalog the statistics belong to
	 * @param buildStatistics statistics of the catalog's latest build
	 */
	private static void emitCatalogBuiltEvents(
		@Nonnull String catalogName,
		@Nonnull CatalogBuildStatistics buildStatistics
	) {
		final BuildType buildType = buildStatistics.buildCount().get() == 1 ? BuildType.NEW : BuildType.REFRESH;
		new BuiltEvent(
			catalogName,
			GraphQLInstanceType.DATA,
			buildType,
			buildStatistics.dataApiInstanceBuildDuration().get(),
			buildStatistics.dataApiSchemaBuildDuration().get(),
			buildStatistics.dataApiSchemaDslLines().get()
		).commit();
		new BuiltEvent(
			catalogName,
			GraphQLInstanceType.SCHEMA,
			buildType,
			buildStatistics.schemaApiInstanceBuildDuration().get(),
			buildStatistics.schemaApiSchemaBuildDuration().get(),
			buildStatistics.schemaApiSchemaDslLines().get()
		).commit();
	}

	/**
	 * Counts lines of printed GraphQL schema in DSL.
	 */
	private static long countGraphQLSchemaLines(@Nonnull GraphQLSchema schema) {
		return GraphQLSchemaPrinter.print(schema).lines().count();
	}

	private record SystemBuildStatistics(@Nonnull AtomicBoolean reported,
										 long instanceBuildDuration,
	                                     long schemaBuildDuration,
	                                     long schemaDslLines) {

		public static SystemBuildStatistics createNew(long instanceBuildDuration,
		                                              long schemaBuildDuration,
		                                              long schemaDslLines) {
			return new SystemBuildStatistics(
				new AtomicBoolean(false),
				instanceBuildDuration,
				schemaBuildDuration,
				schemaDslLines
			);
		}

		public void markAsReported() {
			this.reported.set(true);
		}
	}

	private record CatalogBuildStatistics(@Nonnull AtomicInteger buildCount,
										  @Nonnull AtomicLong dataApiInstanceBuildDuration,
	                                      @Nonnull AtomicLong dataApiSchemaBuildDuration,
	                                      @Nonnull AtomicLong dataApiSchemaDslLines,
										  @Nonnull AtomicLong schemaApiInstanceBuildDuration,
	                                      @Nonnull AtomicLong schemaApiSchemaBuildDuration,
	                                      @Nonnull AtomicLong schemaApiSchemaDslLines) {

		public static CatalogBuildStatistics createNew(long dataApiInstanceBuildDuration,
		                                               long dataApiSchemaBuildDuration,
		                                               long dataApiSchemaDslLines,
		                                               long schemaApiInstanceBuildDuration,
		                                               long schemaApiSchemaBuildDuration,
		                                               long schemaApiSchemaDslLines) {
			return new CatalogBuildStatistics(
				new AtomicInteger(1),
				new AtomicLong(dataApiInstanceBuildDuration),
				new AtomicLong(dataApiSchemaBuildDuration),
				new AtomicLong(dataApiSchemaDslLines),
				new AtomicLong(schemaApiInstanceBuildDuration),
				new AtomicLong(schemaApiSchemaBuildDuration),
				new AtomicLong(schemaApiSchemaDslLines)
			);
		}

		public void refresh(long dataApiInstanceBuildDuration,
		                    long dataApiSchemaBuildDuration,
		                    long dataApiSchemaDslLines,
		                    long schemaApiInstanceBuildDuration,
		                    long schemaApiSchemaBuildDuration,
		                    long schemaApiSchemaDslLines) {
			this.buildCount.incrementAndGet();
			this.dataApiInstanceBuildDuration.set(dataApiInstanceBuildDuration);
			this.dataApiSchemaBuildDuration.set(dataApiSchemaBuildDuration);
			this.dataApiSchemaDslLines.set(dataApiSchemaDslLines);
			this.schemaApiInstanceBuildDuration.set(schemaApiInstanceBuildDuration);
			this.schemaApiSchemaBuildDuration.set(schemaApiSchemaBuildDuration);
			this.schemaApiSchemaDslLines.set(schemaApiSchemaDslLines);
		}
	}

}
