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
import io.evitadb.externalApi.rest.api.dto.OpenApiComplexType;
import io.evitadb.externalApi.rest.api.dto.OpenApiEnum;
import io.evitadb.externalApi.rest.api.dto.OpenApiTypeReference;
import io.evitadb.externalApi.rest.exception.OpenApiSchemaBuildingError;
import io.evitadb.externalApi.rest.io.handler.RESTApiHandlerRegistrar;
import io.evitadb.utils.Assert;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.SpecVersion;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.utils.CollectionUtils.createHashMap;
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
	@Nonnull
	private final Map<String, OpenApiComplexType> registeredTypes = createHashMap(100);
	private final Map<String, PathItem> registeredPaths = createHashMap(100);
	/**
	 * Holds all globally registered custom enums that will be inserted into GraphQL schema.
	 */
	@Nonnull
	private final Set<String> registeredCustomEnums = createHashSet(32);
	/**
	 * Reference to OpenAPI instance when built.
	 */
	@Nonnull
	@Getter
	private final AtomicReference<OpenAPI> openApi = new AtomicReference<>();

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

		restApiHandlerRegistrar = new RESTApiHandlerRegistrar(this);
	}

	@Nonnull
	public CatalogSchemaContract getSchema() {
		return catalog.getSchema();
	}

	/**
	 * Registers new custom enum if there is not enum with same name.
	 */
	public void registerCustomEnumIfAbsent(@Nonnull OpenApiEnum customEnum) {
		if (registeredCustomEnums.contains(customEnum.getName())) {
			return;
		}
		registeredCustomEnums.add(customEnum.getName());
		registerType(customEnum);
	}

	@Nonnull
	public OpenApiTypeReference registerType(@Nonnull OpenApiComplexType type) {
		Assert.isPremiseValid(
			!registeredTypes.containsKey(type.getName()),
			() -> new OpenApiSchemaBuildingError("Object with name `" + type.getName() + "` is already registered.")
		);
		registeredTypes.put(type.getName(), type);

		return OpenApiTypeReference.typeRefTo(type);
	}

	@Nonnull
	public Optional<OpenApiTypeReference> getRegisteredType(@Nonnull String name) {
		return Optional.ofNullable(registeredTypes.get(name))
			.map(OpenApiTypeReference::typeRefTo);
	}

	public void registerPath(@Nonnull String urlPath, @Nonnull PathItem pathItem) {
		Assert.isPremiseValid(
			!registeredPaths.containsKey(urlPath),
			() -> new OpenApiSchemaBuildingError("There is already registered path `" + urlPath + "`.")
		);
		registeredPaths.put(urlPath, pathItem);
	}

	@Nonnull
	public OpenAPI buildOpenApi() {
		final OpenAPI openApi = new OpenAPI(SpecVersion.V31);

		final Info info = new Info();
		info.setTitle("Web services for catalog `" + getCatalog().getName() + "`.");
		info.setContact(new Contact().email("novotny@fg.cz").url("https://www.fg.cz"));
		info.setVersion("1.0.0-oas3"); // todo lho take version of evita?
		openApi.info(info);

		final Components components = new Components();
		registeredTypes.forEach((name, object) -> components.addSchemas(name, object.toSchema()));
		openApi.setComponents(components);

		final Paths paths = new Paths();
		registeredPaths.forEach(paths::addPathItem);
		openApi.setPaths(paths);

		validateReferences(openApi);
		this.openApi.set(openApi);
		return openApi;
	}

	private void validateReferences(OpenAPI openApi) {
		final OpenApiSchemaReferenceValidator referenceValidator = new OpenApiSchemaReferenceValidator(openApi);
		final Set<String> missingSchemas = referenceValidator.validateSchemaReferences();

		if (!missingSchemas.isEmpty()) {
			final StringBuilder errorMessageBuilder = new StringBuilder("Found missing schema in OpenAPI for catalog `" + getCatalog().getName() + "`:\n");
			missingSchemas.forEach(it -> errorMessageBuilder.append("- `").append(it).append("`\n"));
			throw new OpenApiSchemaBuildingError(errorMessageBuilder.toString());
		}
	}
}
