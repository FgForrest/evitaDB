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

import io.evitadb.externalApi.api.system.model.CatalogUnionDescriptor;
import io.evitadb.externalApi.lab.api.model.CatalogsHeaderDescriptor;
import io.evitadb.externalApi.lab.api.model.QueryEntitiesRequestBodyDescriptor;
import io.evitadb.externalApi.lab.api.openApi.OpenApiLabApiDataEndpoint;
import io.evitadb.externalApi.lab.api.resolver.endpoint.ListCatalogsHandler;
import io.evitadb.externalApi.lab.api.resolver.endpoint.QueryEntitiesHandler;
import io.evitadb.externalApi.rest.api.model.PropertyDescriptorToOpenApiOperationPathParameterTransformer;
import io.swagger.v3.oas.models.PathItem.HttpMethod;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;

import static io.evitadb.externalApi.lab.api.openApi.OpenApiLabApiDataEndpoint.newLabDataEndpoint;
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
	public OpenApiLabApiDataEndpoint buildListCatalogsEndpoint() {
		return newLabDataEndpoint()
			.path(p -> p
				.staticItem("catalogs"))
			.method(HttpMethod.GET)
			.operationId("getCatalogs")
			.description("Returns all known catalogs.")
			.successResponse(nonNull(arrayOf(typeRefTo(CatalogUnionDescriptor.THIS.name()))))
			.handler(ListCatalogsHandler::new)
			.build();
	}

	@Nonnull
	public OpenApiLabApiDataEndpoint buildQueryEntitiesEndpoint() {
		return newLabDataEndpoint()
			.path(p -> p
				.staticItem("catalogs")
				.paramItem(CatalogsHeaderDescriptor.NAME.to(operationPathParameterBuilderTransformer))
				.staticItem("collections")
				.staticItem("query"))
			.method(HttpMethod.POST)
			.operationId("queryEntities")
			.description("Returns all entities from collection.")
			.requestBody(typeRefTo(QueryEntitiesRequestBodyDescriptor.THIS.name()))
			.successResponse(nonNull(typeRefTo("FullResponse")))
			.handler(QueryEntitiesHandler::new)
			.build();
	}
}
