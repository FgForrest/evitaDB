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
import io.evitadb.api.requestResponse.cdc.ChangeCaptureContent;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCaptureRequest;
import io.evitadb.core.Evita;
import io.evitadb.core.UnusableCatalog;
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
import io.evitadb.externalApi.http.PathNormalizingHandler;
import io.evitadb.utils.Assert;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
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
	@Nonnull private final Set<String> registeredCatalogs = createHashSet(20);

	/**
	 * Completable future that is completed when all initial catalogs are registered.
	 */
	private final CompletableFuture<Void> fullyInitialized;

	/**
	 * Statistics for system GraphQL build.
	 * This is used to emit observability events only once.
	 */
	@Nullable private SystemBuildStatistics systemBuildStatistics;

	/**
	 * Statistics for each catalog build.
	 * The key is the catalog name.
	 */
	@Nonnull private final Map<String, CatalogBuildStatistics> catalogBuildStatistics = createHashMap(20);

	public GraphQLManager(@Nonnull Evita evita, @Nonnull HeaderOptions headers, @Nonnull GraphQLOptions graphQLConfig) {
		this.evita = evita;
		this.graphQLConfig = graphQLConfig;

		this.graphQLRouter = new GraphQLRouter(this.objectMapper, evita, headers);

		// listen to any evita catalog changes
		evita.registerSystemChangeCapture(new ChangeSystemCaptureRequest(
			this.evita.getEngineState().startVersion() + 1, // we need all changes since the evitaDB start before the GQL API was initialized to accept changes
			null,
			ChangeCaptureContent.BODY
		))
			.subscribe(new SystemGraphQLRefreshingObserver(this));

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
					log.info("GraphQL API initialized with {} registered catalogs.", this.registeredCatalogs.size());
				}
			}
		);
	}

	/**
	 * Determines whether the current {@code GraphQLManager} instance has been fully initialized.
	 *
	 * @return {@code true} if the initialization process is complete, otherwise {@code false}.
	 */
	public boolean isFullyInitialized() {
		return this.fullyInitialized.isDone();
	}

	@Nonnull
	public HttpService getGraphQLRouter() {
		return this.graphQLRouter.decorate(PathNormalizingHandler::new);
	}

	/**
	 * Initializes system GraphQL endpoint for managing Evita.
	 */
	private void registerSystemApi() {
		final long instanceBuildStartTime = System.currentTimeMillis();

		final long schemaBuildStartTime = System.currentTimeMillis();
		final GraphQLSchema schema = new SystemGraphQLSchemaBuilder(this.graphQLConfig, this.evita).build();
		final long schemaBuildDuration = System.currentTimeMillis() - schemaBuildStartTime;

		this.graphQLRouter.registerSystemApi(new SystemGraphQLBuilder(this.evita, schema).build(this.graphQLConfig));
		final long instanceBuildDuration = System.currentTimeMillis() - instanceBuildStartTime;

		// build metrics
		this.systemBuildStatistics = SystemBuildStatistics.createNew(
			instanceBuildDuration,
			schemaBuildDuration,
			countGraphQLSchemaLines(schema)
		);
	}

	/**
	 * Registers new Evita catalog to API. It creates new endpoint and {@link GraphQL} instance for it.
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

			this.graphQLRouter.registerCatalogApi(catalogName, GraphQLInstanceType.DATA, dataApi);
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

			this.graphQLRouter.registerCatalogApi(catalogName, GraphQLInstanceType.SCHEMA, schemaApi);
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
			return true;
		} catch (EvitaInternalError ex) {
			// log and skip the catalog entirely
			log.error("Catalog `" + catalogName + "` is corrupted and will not accessible by GraphQL API.", ex);

			// cleanup corrupted paths
			this.graphQLRouter.unregisterCatalogApis(catalogName);
			this.catalogBuildStatistics.remove(catalogName);
			return false;
		}
	}

	/**
	 * Refreshes already registered catalog endpoint and its {@link GraphQL} instance.
	 */
	public boolean refreshCatalog(@Nonnull String catalogName) {
		final boolean catalogRegistered = this.registeredCatalogs.contains(catalogName);
		if (!catalogRegistered) {
			// there may be case where initial registration failed and catalog is not registered at all
			// for example, when catalog was corrupted and is replaced with new fresh one
			log.info("Could not refresh existing catalog `{}`. Registering new one instead...", catalogName);
			return registerCatalog(catalogName);
		}

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

		this.graphQLRouter.refreshCatalogApi(catalogName, GraphQLInstanceType.DATA, newDataApi);
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

		this.graphQLRouter.refreshCatalogApi(catalogName, GraphQLInstanceType.SCHEMA, newSchemaApi);
		final long schemaApiInstanceBuildDuration = System.currentTimeMillis() - schemaApiInstanceBuildStartTime;

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

		return true;
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
			() -> new GraphQLInternalError("No build statistics found for catalog `" + catalogName + "`")
		);

		final BuildType buildType = buildStatistics.buildCount().get() == 1 ? BuildType.NEW : BuildType.REFRESH;
		new BuiltEvent(
			catalogName,
			GraphQLInstanceType.DATA,
			buildType,
			buildStatistics.dataApiSchemaBuildDuration().get(),
			buildStatistics.dataApiSchemaBuildDuration().get(),
			buildStatistics.dataApiSchemaDslLines().get()
		).commit();
		new BuiltEvent(
			catalogName,
			GraphQLInstanceType.SCHEMA,
			buildType,
			buildStatistics.schemaApiSchemaBuildDuration().get(),
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
