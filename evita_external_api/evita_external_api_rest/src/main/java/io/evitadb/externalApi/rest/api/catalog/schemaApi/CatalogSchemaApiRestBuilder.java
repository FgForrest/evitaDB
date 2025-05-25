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

package io.evitadb.externalApi.rest.api.catalog.schemaApi;

import io.evitadb.externalApi.api.catalog.schemaApi.model.NameVariantsDescriptor;
import io.evitadb.externalApi.rest.api.builder.PartialRestBuilder;
import io.evitadb.externalApi.rest.api.catalog.builder.CatalogRestBuildingContext;
import io.evitadb.externalApi.rest.api.catalog.schemaApi.builder.CatalogSchemaObjectBuilder;
import io.evitadb.externalApi.rest.api.catalog.schemaApi.builder.EntitySchemaObjectBuilder;
import io.evitadb.externalApi.rest.api.catalog.schemaApi.builder.SchemaApiEndpointBuilder;

import javax.annotation.Nonnull;

/**
 * Builds schema API part of catalog's REST API. Building of whole REST API is handled by {@link io.evitadb.externalApi.rest.api.catalog.CatalogRestBuilder}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class CatalogSchemaApiRestBuilder extends PartialRestBuilder<CatalogRestBuildingContext> {

	@Nonnull private final SchemaApiEndpointBuilder endpointBuilder;
	@Nonnull private final EntitySchemaObjectBuilder entitySchemaObjectBuilder;
	@Nonnull private final CatalogSchemaObjectBuilder catalogSchemaObjectBuilder;

	public CatalogSchemaApiRestBuilder(@Nonnull CatalogRestBuildingContext buildingContext) {
		super(buildingContext);

		this.endpointBuilder = new SchemaApiEndpointBuilder();
		this.entitySchemaObjectBuilder = new EntitySchemaObjectBuilder(
			buildingContext,
			this.objectBuilderTransformer,
			this.propertyBuilderTransformer
		);
		this.catalogSchemaObjectBuilder = new CatalogSchemaObjectBuilder(
			buildingContext,
			this.objectBuilderTransformer,
			this.propertyBuilderTransformer
		);
	}

	@Override
	public void build() {
		buildCommonTypes();
		buildEndpoints();
	}

	private void buildCommonTypes() {
		this.buildingContext.registerType(NameVariantsDescriptor.THIS.to(this.objectBuilderTransformer).build());

		this.entitySchemaObjectBuilder.buildCommonTypes();
		this.catalogSchemaObjectBuilder.buildCommonTypes();
	}

	private void buildEndpoints() {
		this.buildingContext.getEntitySchemas().forEach(entitySchema -> {
			this.entitySchemaObjectBuilder.build(entitySchema);
			this.buildingContext.registerEndpoint(this.endpointBuilder.buildGetEntitySchemaEndpoint(this.buildingContext.getSchema(), entitySchema));
			this.buildingContext.registerEndpoint(this.endpointBuilder.buildUpdateEntitySchemaEndpoint(this.buildingContext.getSchema(), entitySchema));
		});

		this.catalogSchemaObjectBuilder.build();
		this.buildingContext.registerEndpoint(this.endpointBuilder.buildGetCatalogSchemaEndpoint(this.buildingContext.getSchema()));
		this.buildingContext.registerEndpoint(this.endpointBuilder.buildUpdateCatalogSchemaEndpoint(this.buildingContext.getSchema()));
	}
}
