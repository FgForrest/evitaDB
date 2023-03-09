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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import io.evitadb.api.CatalogContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.core.Evita;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.externalApi.rest.api.dto.Rest;
import io.evitadb.externalApi.rest.api.openApi.OpenApiComplexType;
import io.evitadb.externalApi.rest.api.openApi.OpenApiEndpoint;
import io.evitadb.externalApi.rest.api.openApi.OpenApiEnum;
import io.evitadb.externalApi.rest.api.openApi.OpenApiObject;
import io.evitadb.externalApi.rest.api.openApi.OpenApiTypeReference;
import io.evitadb.externalApi.rest.exception.OpenApiBuildingError;
import io.evitadb.externalApi.rest.io.serializer.BigDecimalSerializer;
import io.evitadb.utils.Assert;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.PathItem.HttpMethod;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.SpecVersion;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static io.evitadb.utils.CollectionUtils.createHashMap;
import static io.evitadb.utils.CollectionUtils.createHashSet;

/**
 * This context contains objects which are used (and shared) during building OpenAPI schema.
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
public class CatalogRestBuildingContext {

	@Getter @Nonnull private final Evita evita;
	@Getter @Nonnull private final CatalogContract catalog;
	@Getter @Nonnull private final Set<EntitySchemaContract> entitySchemas;

	/**
	 * This instance of object mapper is shared by all REST handlers registered via RoutingHandler.
	 */
	@Getter
	@Nonnull
	private final ObjectMapper objectMapper;

	@Nonnull private final Map<String, OpenApiComplexType> registeredTypes = createHashMap(100);
	/**
	 * Gathered all entity objects for all collections (non-localized).
	 */
	@Nonnull @Getter private final List<OpenApiTypeReference> entityObjects;
	/**
	 * Gathered all entity objects for all collections (localized).
	 */
	@Nonnull @Getter private final List<OpenApiTypeReference> localizedEntityObjects;
	private final Map<Path, Map<HttpMethod, OpenApiEndpoint<?>>> registeredEndpoints = createHashMap(100);
	/**
	 * Holds all globally registered custom enums that will be inserted into GraphQL schema.
	 */
	@Nonnull
	private final Set<String> registeredCustomEnums = createHashSet(32);

	public CatalogRestBuildingContext(@Nonnull Evita evita, @Nonnull CatalogContract catalog) {
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
		this.objectMapper = setupObjectMapper();
		entityObjects = new ArrayList<>(this.entitySchemas.size());
		localizedEntityObjects = new ArrayList<>(this.entitySchemas.size());
	}

	@Nonnull
	private static ObjectMapper setupObjectMapper() {
		final ObjectMapper objectMapper = new ObjectMapper();
		objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);

		final SimpleModule module = new SimpleModule();
		module.addSerializer(new BigDecimalSerializer());
		objectMapper.registerModule(module);

		return objectMapper;
	}

	@Nonnull
	public CatalogSchemaContract getSchema() {
		return catalog.getSchema();
	}

	@Nonnull
	public OpenApiTypeReference registerEntityObject(@Nonnull OpenApiObject entityObject) {
		final OpenApiTypeReference ref = registerType(entityObject);
		entityObjects.add(ref);
		return ref;
	}

	@Nonnull
	public OpenApiTypeReference registerLocalizedEntityObject(@Nonnull OpenApiObject entityObject) {
		final OpenApiTypeReference ref = registerType(entityObject);
		localizedEntityObjects.add(ref);
		return ref;
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
			() -> new OpenApiBuildingError("Object with name `" + type.getName() + "` is already registered.")
		);
		registeredTypes.put(type.getName(), type);

		return OpenApiTypeReference.typeRefTo(type);
	}

	@Nonnull
	public Optional<OpenApiTypeReference> getRegisteredType(@Nonnull String name) {
		return Optional.ofNullable(registeredTypes.get(name))
			.map(OpenApiTypeReference::typeRefTo);
	}

	public void registerEndpoint(@Nonnull OpenApiEndpoint<?> endpoint) {
		Assert.isPremiseValid(
			!registeredEndpoints.containsKey(endpoint.getPath()) ||
				!registeredEndpoints.get(endpoint.getPath()).containsKey(endpoint.getMethod()),
			() -> new OpenApiBuildingError("There is already registered `" + endpoint.getMethod() + "` endpoint at `" + endpoint.getPath() + "`.")
		);

		registeredEndpoints.computeIfAbsent(
			endpoint.getPath(),
			path -> createHashMap(HttpMethod.values().length)
		)
			.put(endpoint.getMethod(), endpoint);
	}

	@Nonnull
	public Rest buildRest() {
		final OpenAPI openApi = buildOpenApi();
		final List<Rest.Endpoint> endpoints = buildEndpoints(openApi);

		return new Rest(openApi, endpoints);
	}

	@Nonnull
	private OpenAPI buildOpenApi() {
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
		registeredEndpoints.forEach((path, endpoints) -> {
			final PathItem pathItem = new PathItem();
			endpoints.forEach((method, endpoint) -> {
				pathItem.operation(method, endpoint.toOperation());
			});
			paths.addPathItem(path.toString(), pathItem);
		});
		openApi.setPaths(paths);

		validateReferences(openApi);
		return openApi;
	}

	@Nonnull
	private List<Rest.Endpoint> buildEndpoints(@Nonnull OpenAPI openApi) {
		final List<Rest.Endpoint> builtEndpoints = new LinkedList<>();
		registeredEndpoints.forEach((path, endpoints) ->
			endpoints.forEach((method, endpoint) ->
				builtEndpoints.add(new Rest.Endpoint(
					path.subpath(1, path.getNameCount()), // strip "/rest" prefix
					endpoint.getMethod(),
					endpoint.toHandler(objectMapper, evita, openApi)
				))
			)
		);
		return builtEndpoints;
	}

	private void validateReferences(OpenAPI openApi) {
		final OpenApiReferenceValidator referenceValidator = new OpenApiReferenceValidator(openApi);
		final Set<String> missingSchemas = referenceValidator.validateSchemaReferences();

		if (!missingSchemas.isEmpty()) {
			final StringBuilder errorMessageBuilder = new StringBuilder("Found missing schema in OpenAPI for catalog `" + getCatalog().getName() + "`:\n");
			missingSchemas.forEach(it -> errorMessageBuilder.append("- `").append(it).append("`\n"));
			throw new OpenApiBuildingError(errorMessageBuilder.toString());
		}
	}
}
