/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.externalApi.rest.api.catalog.builder;

import io.evitadb.externalApi.rest.api.dataType.DataTypesConverter;
import io.evitadb.externalApi.rest.api.model.RestRootDescriptor;
import io.evitadb.externalApi.rest.api.openApi.OpenApiCatalogEndpoint;
import io.evitadb.externalApi.rest.api.resolver.endpoint.OpenApiSpecificationHandler;
import io.swagger.v3.oas.models.PathItem.HttpMethod;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;

import static io.evitadb.externalApi.rest.api.openApi.OpenApiCatalogEndpoint.newCatalogEndpoint;
import static io.evitadb.externalApi.rest.api.openApi.OpenApiNonNull.nonNull;

/**
 * Creates OpenAPI {@link io.evitadb.externalApi.rest.api.openApi.OpenApiEndpoint} for each generic catalog endpoint.
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
public class CatalogEndpointBuilder {

	@Nonnull
	public OpenApiCatalogEndpoint buildOpenApiSpecificationEndpoint(@Nonnull CatalogRestBuildingContext buildingContext) {
		return newCatalogEndpoint(buildingContext.getSchema())
			.path(p -> p
				.staticItem(RestRootDescriptor.OPEN_API_SPECIFICATION.urlPathItem()))
			.method(HttpMethod.GET)
			.operationId(RestRootDescriptor.OPEN_API_SPECIFICATION.operation())
			.description(RestRootDescriptor.OPEN_API_SPECIFICATION.description())
			.successResponse(nonNull(DataTypesConverter.getOpenApiScalar(String.class)))
			.handler(OpenApiSpecificationHandler::new)
			.build();
	}
}
