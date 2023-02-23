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

package io.evitadb.externalApi.rest.api.catalog.builder;

import io.evitadb.api.CatalogContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.core.Evita;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.externalApi.rest.exception.OpenApiSchemaBuildingError;
import io.evitadb.externalApi.rest.io.handler.RESTApiHandlerRegistrar;
import io.evitadb.utils.Assert;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.SpecVersion;
import io.swagger.v3.oas.models.media.Schema;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.util.Optional;
import java.util.Set;

import static io.evitadb.externalApi.rest.api.catalog.builder.SchemaCreator.createReferenceSchema;
import static io.evitadb.utils.CollectionUtils.createHashSet;

/**
 * This context contains objects which are used (and shared) during building OpenAPI schema.
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
public class CatalogSchemaBuildingContext {

	@Getter
	@Nonnull
	private final Evita evita;
	@Getter
	@Nonnull
	private final CatalogContract catalog;
	@Getter
	@Nonnull
	private final Set<EntitySchemaContract> entitySchemas;
	@Getter
	@Nonnull
	private final OpenAPI openAPI;

	/**
	 * Routing handler is used to register REST handlers for each mapping created in OpenAPI schema.
	 */
	@Getter
	@Nonnull
	private final RESTApiHandlerRegistrar restApiHandlerRegistrar;

	public CatalogSchemaBuildingContext(@Nonnull Evita evita, @Nonnull CatalogContract catalog) {
		this.evita = evita;
		this.catalog = catalog;
		this.entitySchemas = evita.queryCatalog(catalog.getName(), session -> {
			final Set<String> collections = session.getAllEntityTypes();
			final Set<EntitySchemaContract> schemas = createHashSet(collections.size());
			collections.forEach(
				c -> schemas.add(
					session.getEntitySchema(c)
						.orElseThrow(() -> new EvitaInternalError("Schema for `" + c + "` entity type unexpectedly not found!"))
				)
			);
			return schemas;
		});

		openAPI = prepareOpenApi();
		restApiHandlerRegistrar = new RESTApiHandlerRegistrar(this);
	}

	@Nonnull
	public CatalogSchemaContract getSchema() {
		return catalog.getSchema();
	}

	@Nonnull
	public Schema<Object> registerType(@Nonnull Schema<Object> type) {
		Assert.isPremiseValid(
			type.getName() != null,
			() -> {
				return new OpenApiSchemaBuildingError("Can't add schema without name into Components.");
			}
		);
		Assert.isPremiseValid(
			openAPI.getComponents().getSchemas() == null ||
				openAPI.getComponents().getSchemas().get(type.getName()) == null,
			() -> {
				return new OpenApiSchemaBuildingError("Schema already exists in Components list. Name: " + type.getName());
			}
		);
		openAPI.getComponents().addSchemas(type.getName(), type);

		return createReferenceSchema(type);
	}

	@SuppressWarnings("unchecked")
	@Nonnull
	public Optional<Schema<Object>> getRegisteredType(@Nonnull String name) {
		return Optional.ofNullable((Schema<Object>) openAPI.getComponents().getSchemas().get(name));
	}

	public void registerPath(@Nonnull String urlPath, @Nonnull PathItem pathItem) {
		openAPI.getPaths().addPathItem(urlPath, pathItem);
	}

	private static OpenAPI prepareOpenApi() {
		final OpenAPI newOpenApi = new OpenAPI(SpecVersion.V31);
		newOpenApi.setComponents(new Components());
		newOpenApi.setPaths(new Paths());
		return newOpenApi;
	}
}
