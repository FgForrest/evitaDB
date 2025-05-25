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

package io.evitadb.externalApi.rest.api.builder;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.configuration.AbstractApiOptions;
import io.evitadb.externalApi.configuration.HeaderOptions;
import io.evitadb.externalApi.rest.api.Rest;
import io.evitadb.externalApi.rest.api.Rest.Endpoint;
import io.evitadb.externalApi.rest.api.openApi.OpenApiComplexType;
import io.evitadb.externalApi.rest.api.openApi.OpenApiEndpoint;
import io.evitadb.externalApi.rest.api.openApi.OpenApiEnum;
import io.evitadb.externalApi.rest.api.openApi.OpenApiReferenceValidator;
import io.evitadb.externalApi.rest.api.openApi.OpenApiTypeReference;
import io.evitadb.externalApi.rest.exception.OpenApiBuildingError;
import io.evitadb.externalApi.rest.exception.RestInternalError;
import io.evitadb.externalApi.serialization.BigDecimalSerializer;
import io.evitadb.externalApi.utils.UriPath;
import io.evitadb.utils.Assert;
import io.evitadb.utils.VersionUtils;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.PathItem.HttpMethod;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.SpecVersion;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * Generic context object for building REST API (OpenAPI specs and endpoint handlers)
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public abstract class RestBuildingContext {

	@Nonnull protected final AbstractApiOptions restConfig;
	@Nonnull @Getter private final Evita evita;
	@Nonnull private final HeaderOptions headers;

	/**
	 * This instance of object mapper is shared by all REST handlers registered via RoutingHandler.
	 */
	@Getter
	@Nonnull
	private final ObjectMapper objectMapper;

	@Nonnull private final Map<String, OpenApiComplexType> registeredTypes = createHashMap(100);
	private final Map<UriPath, Map<HttpMethod, OpenApiEndpoint<?>>> registeredEndpoints = createHashMap(100);
	/**
	 * Holds all globally registered custom enums that will be inserted into GraphQL schema.
	 */
	@Nonnull
	private final Map<String, Class<? extends Enum<?>>> registeredCustomEnums = createHashMap(32);

	protected RestBuildingContext(
		@Nonnull AbstractApiOptions restConfig,
		@Nonnull HeaderOptions headerOptions,
		@Nonnull Evita evita
	) {
		this.restConfig = restConfig;
		this.headers = headerOptions;
		this.evita = evita;
		this.objectMapper = setupObjectMapper();
	}

	@Nonnull
	protected ObjectMapper setupObjectMapper() {
		final ObjectMapper objectMapper = new ObjectMapper();

		final SimpleModule module = new SimpleModule();
		module.addSerializer(new BigDecimalSerializer());
		objectMapper.registerModule(module);
		objectMapper.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
		objectMapper.setConfig(objectMapper.getSerializationConfig().with(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY));

		return objectMapper;
	}

	/**
	 * Builds OpenAPI servers section defining base paths for API endpoints.
	 */
	@Nonnull
	protected abstract List<Server> buildOpenApiServers();

	/**
	 * @return public title of OpenAPI used in info.
	 */
	@Nonnull
	protected abstract String getOpenApiTitle();

	/**
	 * Registers new custom enum if there is not enum with same name.
	 */
	public void registerCustomEnumIfAbsent(@Nonnull OpenApiEnum customEnum) {
		if (this.registeredCustomEnums.containsKey(customEnum.getName())) {
			return;
		}
		this.registeredCustomEnums.put(customEnum.getName(), customEnum.getEnumTemplate());
		registerType(customEnum);
	}

	@Nonnull
	public OpenApiTypeReference registerType(@Nonnull OpenApiComplexType type) {
		Assert.isPremiseValid(
			!this.registeredTypes.containsKey(type.getName()),
			() -> new OpenApiBuildingError("Object with name `" + type.getName() + "` is already registered.")
		);
		this.registeredTypes.put(type.getName(), type);

		return OpenApiTypeReference.typeRefTo(type);
	}

	@Nonnull
	public Optional<OpenApiTypeReference> getRegisteredType(@Nonnull String name) {
		return Optional.ofNullable(this.registeredTypes.get(name))
			.map(OpenApiTypeReference::typeRefTo);
	}

	public void registerEndpoint(@Nonnull OpenApiEndpoint<?> endpoint) {
		Assert.isPremiseValid(
			!this.registeredEndpoints.containsKey(endpoint.getPath()) ||
				!this.registeredEndpoints.get(endpoint.getPath()).containsKey(endpoint.getMethod()),
			() -> new OpenApiBuildingError("There is already registered `" + endpoint.getMethod() + "` endpoint at `" + endpoint.getPath() + "`.")
		);

		this.registeredEndpoints.computeIfAbsent(
				endpoint.getPath(),
				path -> createHashMap(HttpMethod.values().length)
			)
			.put(endpoint.getMethod(), endpoint);
	}


	@Nonnull
	public Rest buildRest() {
		final OpenAPI openApi = buildOpenApi();
		final Map<String, Class<? extends Enum<?>>> enumMapping = buildEnumMapping();
		final List<Endpoint> endpoints = buildEndpoints(openApi, enumMapping);

		return new Rest(openApi, endpoints);
	}

	@Nonnull
	private OpenAPI buildOpenApi() {
		final OpenAPI openApi = new OpenAPI(SpecVersion.V31)
			.servers(buildOpenApiServers());

		final Info info = new Info();
		info.setTitle(getOpenApiTitle());
		info.setContact(new Contact().email("novotny@fg.cz").url("https://www.fg.cz"));
		info.setVersion(VersionUtils.readVersion());
		info.setLicense(new License().name("Business Source License 1.1").url("https://github.com/FgForrest/evitaDB/blob/master/LICENSE"));
		openApi.info(info);

		final Components components = new Components();
		this.registeredTypes.forEach((name, object) -> components.addSchemas(name, object.toSchema()));
		openApi.setComponents(components);

		final Paths paths = new Paths();
		this.registeredEndpoints.forEach((path, endpoints) -> {
			final PathItem pathItem = new PathItem();
			endpoints.forEach((method, endpoint) -> pathItem.operation(method, endpoint.toOperation()));
			paths.addPathItem("/" + path.toString(), pathItem);
		});
		openApi.setPaths(paths);

		validateReferences(openApi);
		return openApi;
	}

	@Nonnull
	private Map<String, Class<? extends Enum<?>>> buildEnumMapping() {
		final Map<String, Class<? extends Enum<?>>> enumMapping = this.registeredCustomEnums.entrySet()
			.stream()
			.filter(it -> it.getValue() != null) // we want mappings only for enums that have Java enum associated with it
			.collect(Collectors.toMap(
				Entry::getKey,
				Entry::getValue,
				(o, o2) -> { throw new RestInternalError("Enum items cannot merge."); },
				LinkedHashMap::new
			));

		return Collections.unmodifiableMap(enumMapping);
	}

	@Nonnull
	private List<Rest.Endpoint> buildEndpoints(@Nonnull OpenAPI openApi, @Nonnull Map<String, Class<? extends Enum<?>>> enumMapping) {
		final List<Rest.Endpoint> builtEndpoints = new LinkedList<>();
		this.registeredEndpoints.forEach((path, endpoints) ->
			endpoints.forEach((method, endpoint) ->
				builtEndpoints.add(new Rest.Endpoint(
					path,
					com.linecorp.armeria.common.HttpMethod.valueOf(endpoint.getMethod().name()),
					endpoint.toHandler(this.objectMapper, this.evita, this.headers, openApi, enumMapping)
				))
			)
		);
		return Collections.unmodifiableList(builtEndpoints);
	}

	private static void validateReferences(OpenAPI openApi) {
		final OpenApiReferenceValidator referenceValidator = new OpenApiReferenceValidator(openApi);
		final Set<String> missingSchemas = referenceValidator.validateSchemaReferences();

		if (!missingSchemas.isEmpty()) {
			final StringBuilder errorMessageBuilder = new StringBuilder("Found missing schema in OpenAPI :`\n");
			missingSchemas.forEach(it -> errorMessageBuilder.append("- `").append(it).append("`\n"));
			throw new OpenApiBuildingError(errorMessageBuilder.toString());
		}
	}
}
