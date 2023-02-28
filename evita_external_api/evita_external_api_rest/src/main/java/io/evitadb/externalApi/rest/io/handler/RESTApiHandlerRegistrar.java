/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.externalApi.rest.io.handler;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import io.evitadb.externalApi.api.catalog.dataApi.model.CatalogDataApiRootDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.CatalogSchemaApiRootDescriptor;
import io.evitadb.externalApi.rest.api.catalog.builder.CatalogSchemaBuildingContext;
import io.evitadb.externalApi.rest.api.catalog.builder.OpenApiEntitySchemaBuildingContext;
import io.evitadb.externalApi.rest.api.catalog.builder.UrlPathCreator;
import io.evitadb.externalApi.rest.io.serializer.BigDecimalSerializer;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.undertow.server.RoutingHandler;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.util.function.Supplier;

/**
 * Registers API handlers into {@link RoutingHandler} for REST requests processing.
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
public class RESTApiHandlerRegistrar {
	@Getter
	@Nonnull
	private final RoutingHandler routingHandler;

	/**
	 * This instance of object mapper is shared by all REST handlers registered via RoutingHandler.
	 */
	@Getter
	@Nonnull
	private final ObjectMapper objectMapper;

	private final CatalogSchemaBuildingContext context;

	public RESTApiHandlerRegistrar(@Nonnull CatalogSchemaBuildingContext context) {
		this.context = context;
		routingHandler = new RoutingHandler();
		objectMapper = new ObjectMapper();
		objectMapper.setSerializationInclusion(Include.NON_NULL);

		SimpleModule module = new SimpleModule();
		module.addSerializer(new BigDecimalSerializer());
		objectMapper.registerModule(module);
	}

	/**
	 * Register handler for single entity. In this case same handler is registered on two path because of
	 * usage of non-required path variable.
	 *
	 * @param localizedUrl <code>true</code> when locale is part of URL path (and thus is required)
	 */
	public void registerSingleEntityHandler(@Nonnull OpenApiEntitySchemaBuildingContext schemaBuildingContext,
	                                        boolean localizedUrl,
	                                        @Nonnull PathItem pathItem) {
		final String urlPathToEntity = UrlPathCreator.createUrlPathToEntity(schemaBuildingContext, CatalogDataApiRootDescriptor.ENTITY_GET, localizedUrl);
		final String urlPathToEntityWithVariable = urlPathToEntity + UrlPathCreator.URL_PATH_SEPARATOR + UrlPathCreator.URL_PRIMARY_KEY_PATH_VARIABLE;
		final EntityHandler handler = new EntityHandler(RESTApiContext.builder()
			.objectMapper(objectMapper)
			.catalog(context.getCatalog())
			.entityType(schemaBuildingContext.getSchema().getName())
			.evita(context.getEvita())
			.localized(localizedUrl)
			.pathItem(pathItem)
			.build());

		routingHandler
			.get(urlPathToEntityWithVariable, handler)
			.get(urlPathToEntity, handler);
	}

	/**
	 * Register handler for entity list.
	 *
	 * @param localizedUrl <code>true</code> when locale is part of URL path (and thus is required)
	 */
	public void registerEntityListHandler(@Nonnull OpenApiEntitySchemaBuildingContext schemaBuildingContext,
	                                      boolean localizedUrl,
	                                      @Nonnull PathItem pathItem) {
		final String pathToEntityList = UrlPathCreator.createUrlPathToEntity(schemaBuildingContext, CatalogDataApiRootDescriptor.ENTITY_LIST, localizedUrl);
		final EntityListHandler handler = new EntityListHandler(RESTApiContext.builder()
			.objectMapper(objectMapper)
			.catalog(context.getCatalog())
			.entityType(schemaBuildingContext.getSchema().getName())
			.evita(context.getEvita())
			.localized(localizedUrl)
			.pathItem(pathItem)
			.build());
		routingHandler.post(pathToEntityList, handler);
	}

	/**
	 * Register handler for entity query.
	 *
	 * @param localizedUrl <code>true</code> when locale is part of URL path (and thus is required)
	 */
	public void registerEntityQueryHandler(@Nonnull OpenApiEntitySchemaBuildingContext schemaBuildingContext,
	                                       boolean localizedUrl,
	                                       @Nonnull PathItem pathItem) {
		final String pathToEntityList = UrlPathCreator.createUrlPathToEntity(schemaBuildingContext, CatalogDataApiRootDescriptor.ENTITY_QUERY, localizedUrl);
		final EntityListHandler handler = new EntityQueryHandler(RESTApiContext.builder()
			.objectMapper(objectMapper)
			.catalog(context.getCatalog())
			.entityType(schemaBuildingContext.getSchema().getName())
			.evita(context.getEvita())
			.localized(localizedUrl)
			.pathItem(pathItem)
			.build());
		routingHandler.post(pathToEntityList, handler);
	}

	/**
	 * Register handler for unknown entity list.
	 *
	 * @param localizedUrl <code>true</code> when locale is part of URL path (and thus is required)
	 */
	public void registerUnknownEntityListHandler(boolean localizedUrl, @Nonnull PathItem pathItem) {
		final String urlPathToUnknownEntityList = UrlPathCreator.createUrlPathToUnknownEntityList(localizedUrl);
		routingHandler
			.get(urlPathToUnknownEntityList, new UnknownEntityListHandler(RESTApiContext.builder()
				.objectMapper(objectMapper)
				.pathItem(pathItem)
				.catalog(context.getCatalog())
				.evita(context.getEvita())
				.localized(localizedUrl)
				.build()));
	}

	/**
	 * Register handler for unknown entity.
	 *
	 * @param localizedUrl <code>true</code> when locale is part of URL path (and thus is required)
	 */
	public void registerUnknownEntityHandler(boolean localizedUrl, @Nonnull PathItem pathItem) {
		final String unknownEntityPath = UrlPathCreator.createUrlPathToUnknownEntity(localizedUrl);
		routingHandler
			.get(unknownEntityPath, new UnknownEntityHandler(
				RESTApiContext.builder()
					.objectMapper(objectMapper)
					.pathItem(pathItem)
					.catalog(context.getCatalog())
					.evita(context.getEvita())
					.localized(localizedUrl)
					.build()));
	}

	/**
	 * Register handler for list of collections
	 */
	public void registerCollectionsHandler(@Nonnull String collectionsSchemaName, @Nonnull PathItem pathItem) {
		routingHandler
			.get(UrlPathCreator.URL_PATH_SEPARATOR + collectionsSchemaName, new CollectionsHandler(
				RESTApiContext.builder()
					.objectMapper(objectMapper)
					.pathItem(pathItem)
					.catalog(context.getCatalog())
					.evita(context.getEvita())
					.build()));
	}

	/**
	 * Register handler for entity upsert.
	 */
	public void registerEntityUpsertHandler(@Nonnull OpenApiEntitySchemaBuildingContext schemaBuildingContext,
	                                        boolean withPrimaryKeyInUrl,
	                                        @Nonnull PathItem pathItem) {
		final String pathToEntity = UrlPathCreator.createUrlPathToEntityMutation(schemaBuildingContext, withPrimaryKeyInUrl);
		final EntityUpsertHandler handler = new EntityUpsertHandler(RESTApiContext.builder()
			.objectMapper(objectMapper)
			.catalog(context.getCatalog())
			.entityType(schemaBuildingContext.getSchema().getName())
			.evita(context.getEvita())
			.pathItem(pathItem)
			.build(),
			withPrimaryKeyInUrl);
		if (withPrimaryKeyInUrl) {
			routingHandler.put(pathToEntity, handler);
		} else {
			routingHandler.post(pathToEntity, handler);
		}
	}

	public void registerEntityDeleteHandler(@Nonnull OpenApiEntitySchemaBuildingContext schemaBuildingContext,
	                                        @Nonnull PathItem pathItem) {
		final String pathToEntity = UrlPathCreator.createUrlPathToEntityMutation(schemaBuildingContext, true);
		final EntityDeleteHandler handler = new EntityDeleteHandler(RESTApiContext.builder()
			.objectMapper(objectMapper)
			.catalog(context.getCatalog())
			.entityType(schemaBuildingContext.getSchema().getName())
			.evita(context.getEvita())
			.pathItem(pathItem)
			.build());
		routingHandler.delete(pathToEntity, handler);
	}

	public void registerEntityListDeleteHandler(@Nonnull OpenApiEntitySchemaBuildingContext schemaBuildingContext,
	                                            @Nonnull PathItem pathItem) {
		final String pathToEntity = UrlPathCreator.createUrlPathToEntityMutation(schemaBuildingContext, false);
		final EntityListDeleteHandler handler = new EntityListDeleteHandler(RESTApiContext.builder()
			.objectMapper(objectMapper)
			.catalog(context.getCatalog())
			.entityType(schemaBuildingContext.getSchema().getName())
			.evita(context.getEvita())
			.pathItem(pathItem)
			.build());
		routingHandler.delete(pathToEntity, handler);
	}

	/**
	 * Register handler for entity schema.
	 */
	public void registerEntitySchemaHandler(@Nonnull OpenApiEntitySchemaBuildingContext schemaBuildingContext,
	                                        @Nonnull PathItem pathItem) {
		final String urlPathToEntity = UrlPathCreator.createUrlPathToEntitySchema(schemaBuildingContext, CatalogSchemaApiRootDescriptor.GET_ENTITY_SCHEMA);
		final EntitySchemaHandler handler = new EntitySchemaHandler(RESTApiContext.builder()
			.objectMapper(objectMapper)
			.catalog(context.getCatalog())
			.entityType(schemaBuildingContext.getSchema().getName())
			.evita(context.getEvita())
			.pathItem(pathItem)
			.build());

		routingHandler.get(urlPathToEntity, handler);
	}

	/**
	 * Register handler for OpenAPI schema
	 */
	public void registerOpenApiSchemaHandler(@Nonnull Supplier<OpenAPI> openApiSupplier, @Nonnull PathItem pathItem) {
		routingHandler
			.get("", new OpenApiSchemaHandler(
				RESTApiContext.builder()
					.objectMapper(objectMapper)
					.openApi(openApiSupplier)
					.pathItem(pathItem)
					.build()
			));
	}
}
