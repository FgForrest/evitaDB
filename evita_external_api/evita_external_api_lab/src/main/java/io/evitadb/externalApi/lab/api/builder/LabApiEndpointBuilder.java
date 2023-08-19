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

package io.evitadb.externalApi.lab.api.builder;

import io.evitadb.externalApi.api.catalog.schemaApi.model.CatalogSchemaDescriptor;
import io.evitadb.externalApi.api.system.model.CatalogUnionDescriptor;
import io.evitadb.externalApi.lab.api.model.CatalogsHeaderDescriptor;
import io.evitadb.externalApi.lab.api.model.GenericResponseDescriptor;
import io.evitadb.externalApi.lab.api.model.QueryEntitiesRequestBodyDescriptor;
import io.evitadb.externalApi.lab.api.openApi.OpenApiLabApiEndpoint;
import io.evitadb.externalApi.lab.api.resolver.endpoint.GetCatalogSchemaHandler;
import io.evitadb.externalApi.lab.api.resolver.endpoint.ListCatalogsHandler;
import io.evitadb.externalApi.lab.api.resolver.endpoint.QueryEntitiesHandler;
import io.evitadb.externalApi.rest.api.dataType.DataTypesConverter;
import io.evitadb.externalApi.rest.api.model.PropertyDescriptorToOpenApiOperationPathParameterTransformer;
import io.evitadb.externalApi.rest.api.model.RestRootDescriptor;
import io.evitadb.externalApi.rest.api.resolver.endpoint.OpenApiSpecificationHandler;
import io.swagger.v3.oas.models.PathItem.HttpMethod;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;

import static io.evitadb.externalApi.lab.api.openApi.OpenApiLabApiEndpoint.DATA_API_URL_PREFIX;
import static io.evitadb.externalApi.lab.api.openApi.OpenApiLabApiEndpoint.SCHEMA_API_URL_PREFIX;
import static io.evitadb.externalApi.lab.api.openApi.OpenApiLabApiEndpoint.newLabApiEndpoint;
import static io.evitadb.externalApi.rest.api.openApi.OpenApiArray.arrayOf;
import static io.evitadb.externalApi.rest.api.openApi.OpenApiNonNull.nonNull;
import static io.evitadb.externalApi.rest.api.openApi.OpenApiTypeReference.typeRefTo;

/**
 * Creates OpenAPI {@link io.evitadb.externalApi.rest.api.openApi.OpenApiEndpoint} for each lab API endpoint.
 * for requests processing.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@RequiredArgsConstructor
public class LabApiEndpointBuilder {

	@Nonnull private final PropertyDescriptorToOpenApiOperationPathParameterTransformer operationPathParameterBuilderTransformer;

	@Nonnull
	public OpenApiLabApiEndpoint buildOpenApiSpecificationEndpoint() {
		return newLabApiEndpoint()
			.method(HttpMethod.GET)
			.operationId(RestRootDescriptor.OPEN_API_SPECIFICATION.operation())
			.description(RestRootDescriptor.OPEN_API_SPECIFICATION.description())
			.successResponse(nonNull(DataTypesConverter.getOpenApiScalar(String.class)))
			.handler(OpenApiSpecificationHandler::new)
			.build();
	}

	@Nonnull
	public OpenApiLabApiEndpoint buildGetCatalogSchemaEndpoint() {
		return newLabApiEndpoint()
			.path(p -> p
				.staticItem(SCHEMA_API_URL_PREFIX)
				.staticItem("catalogs")
				.paramItem(CatalogsHeaderDescriptor.NAME.to(operationPathParameterBuilderTransformer)))
			.method(HttpMethod.GET)
			.operationId("getCatalogSchema")
			.description("Returns catalog schema.")
			.successResponse(nonNull(typeRefTo(CatalogSchemaDescriptor.THIS.name())))
			.handler(GetCatalogSchemaHandler::new)
			.build();
	}

	@Nonnull
	public OpenApiLabApiEndpoint buildListCatalogsEndpoint() {
		return newLabApiEndpoint()
			.path(p -> p
				.staticItem(DATA_API_URL_PREFIX)
				.staticItem("catalogs"))
			.method(HttpMethod.GET)
			.operationId("getCatalogs")
			.description("Returns all known catalogs.")
			.successResponse(nonNull(arrayOf(typeRefTo(CatalogUnionDescriptor.THIS.name()))))
			.handler(ListCatalogsHandler::new)
			.build();
	}

	@Nonnull
	public OpenApiLabApiEndpoint buildQueryEntitiesEndpoint() {
		return newLabApiEndpoint()
			.path(p -> p
				.staticItem(DATA_API_URL_PREFIX)
				.staticItem("catalogs")
				.paramItem(CatalogsHeaderDescriptor.NAME.to(operationPathParameterBuilderTransformer))
				.staticItem("collections")
				.staticItem("query"))
			.method(HttpMethod.POST)
			.operationId("queryEntities")
			.description("Returns all entities from collection.")
			.requestBody(typeRefTo(QueryEntitiesRequestBodyDescriptor.THIS.name()))
			.successResponse(nonNull(typeRefTo(GenericResponseDescriptor.THIS.name())))
			.handler(QueryEntitiesHandler::new)
			.build();
	}
}
