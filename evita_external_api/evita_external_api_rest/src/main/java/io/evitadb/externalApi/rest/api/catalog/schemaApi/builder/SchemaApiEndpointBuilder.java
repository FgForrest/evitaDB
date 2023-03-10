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

package io.evitadb.externalApi.rest.api.catalog.schemaApi.builder;

import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.externalApi.api.catalog.schemaApi.model.CatalogSchemaApiRootDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.EntitySchemaDescriptor;
import io.evitadb.externalApi.rest.api.catalog.schemaApi.resolver.endpoint.GetEntitySchemaHandler;
import io.evitadb.externalApi.rest.api.openApi.OpenApiCollectionEndpoint;
import io.swagger.v3.oas.models.PathItem.HttpMethod;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;

import static io.evitadb.externalApi.rest.api.openApi.OpenApiCollectionEndpoint.newCollectionEndpoint;
import static io.evitadb.externalApi.rest.api.openApi.OpenApiNonNull.nonNull;
import static io.evitadb.externalApi.rest.api.openApi.OpenApiTypeReference.typeRefTo;

/**
 * Creates OpenAPI {@link io.evitadb.externalApi.rest.api.openApi.OpenApiEndpoint} for each schema API endpoint.
 * for requests processing.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@RequiredArgsConstructor
public class SchemaApiEndpointBuilder {

	/**
	 * Default {@link CatalogSchemaApiRootDescriptor#GET_ENTITY_SCHEMA} operation name cannot be used due to
	 * limitations of REST API to URLs, therefore this one is used instead
	 */
	private static final String GET_ENTITY_SCHEMA_OPERATION_NAME = "schema";
	/**
	 * Default {@link CatalogSchemaApiRootDescriptor#GET_CATALOG_SCHEMA} operation name cannot be used due to
	 * limitations of REST API to URLs, therefore this one is used instead
	 */
	private static final String GET_CATALOG_SCHEMA_OPERATION_NAME = "schema";

	@Nonnull
	public OpenApiCollectionEndpoint buildGetEntitySchemaEndpoint(@Nonnull CatalogSchemaContract catalogSchema,
	                                                              @Nonnull EntitySchemaContract entitySchema) {

		return newCollectionEndpoint(catalogSchema, entitySchema)
			.path(p -> p
				.staticItem(GET_ENTITY_SCHEMA_OPERATION_NAME))
			.method(HttpMethod.GET)
			.description(CatalogSchemaApiRootDescriptor.GET_ENTITY_SCHEMA.description(entitySchema.getName()))
			.deprecationNotice(entitySchema.getDeprecationNotice())
			.successResponse(nonNull(typeRefTo(EntitySchemaDescriptor.THIS_SPECIFIC.name(entitySchema))))
			.handler(GetEntitySchemaHandler::new)
			.build();
	}

//	@Nonnull
//	public OpenApiCatalogEndpoint buildGetCatalogSchemaEndpoint(@Nonnull CatalogSchemaContract catalogSchema) {
//		return newCatalogEndpoint(catalogSchema)
//			.path(p -> p
//				.staticItem(GET_CATALOG_SCHEMA_OPERATION_NAME))
//			.method(HttpMethod.GET)
//			.description(CatalogSchemaApiRootDescriptor.GET_CATALOG_SCHEMA.description())
//			.successResponse(nonNull(typeRefTo(CatalogSchemaDescriptor.THIS.name())))
//			.handler(GetCatalogSchemaHandler::new)
//			.build();
//	}
}
