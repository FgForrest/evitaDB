/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.externalApi.rest.api.catalog.cdcApi.builder;

import io.evitadb.externalApi.rest.api.catalog.builder.CatalogRestBuildingContext;
import io.evitadb.externalApi.rest.api.catalog.cdcApi.model.CatalogCdcApiRootDescriptor;
import io.evitadb.externalApi.rest.api.catalog.cdcApi.resolver.endpoint.ChangeCatalogCaptureStreamHandler;
import io.evitadb.externalApi.rest.api.openApi.OpenApiCatalogEndpoint;
import io.evitadb.externalApi.rest.api.openApi.OpenApiScalar;
import io.swagger.v3.oas.models.PathItem.HttpMethod;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;

import static io.evitadb.externalApi.rest.api.openApi.OpenApiCatalogEndpoint.newCatalogEndpoint;
import static io.evitadb.externalApi.rest.api.openApi.OpenApiNonNull.nonNull;

/**
 * Creates OpenAPI {@link io.evitadb.externalApi.rest.api.openApi.OpenApiEndpoint} for each CDC API endpoint.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2025
 */
@RequiredArgsConstructor
public class CdcApiEndpointBuilder {

	@Nonnull private final CatalogRestBuildingContext buildingContext;

	@Nonnull
	public OpenApiCatalogEndpoint buildChangeCatalogCaptureEndpoint() {
		return newCatalogEndpoint(this.buildingContext.getSchema())
			.path(p -> p
				.staticItem(CatalogCdcApiRootDescriptor.CHANGE_CATALOG_CAPTURE.urlPathItem()))
			.method(HttpMethod.GET)
			.operationId(CatalogCdcApiRootDescriptor.CHANGE_CATALOG_CAPTURE.operation())
			.description(CatalogCdcApiRootDescriptor.CHANGE_CATALOG_CAPTURE.description())
			.successResponse(nonNull(OpenApiScalar.scalarFrom(Boolean.class))) // todo lho not needed
			.handler(ChangeCatalogCaptureStreamHandler::new)
			.build();
	}
}
