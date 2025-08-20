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

package io.evitadb.externalApi.rest.api.system;

import io.evitadb.core.Evita;
import io.evitadb.externalApi.api.catalog.schemaApi.model.NameVariantsDescriptor;
import io.evitadb.externalApi.api.system.model.CatalogDescriptor;
import io.evitadb.externalApi.api.system.model.CatalogUnionDescriptor;
import io.evitadb.externalApi.api.system.model.UnusableCatalogDescriptor;
import io.evitadb.externalApi.configuration.HeaderOptions;
import io.evitadb.externalApi.rest.api.Rest;
import io.evitadb.externalApi.rest.api.builder.FinalRestBuilder;
import io.evitadb.externalApi.rest.api.model.ErrorDescriptor;
import io.evitadb.externalApi.rest.api.openApi.OpenApiObjectUnionType;
import io.evitadb.externalApi.rest.api.openApi.OpenApiUnion;
import io.evitadb.externalApi.rest.api.system.builder.SystemEndpointBuilder;
import io.evitadb.externalApi.rest.api.system.builder.SystemRestBuildingContext;
import io.evitadb.externalApi.rest.api.system.model.CreateCatalogRequestDescriptor;
import io.evitadb.externalApi.rest.api.system.model.LivenessDescriptor;
import io.evitadb.externalApi.rest.api.system.model.UpdateCatalogRequestDescriptor;
import io.evitadb.externalApi.rest.configuration.RestOptions;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;

import static io.evitadb.externalApi.rest.api.openApi.OpenApiTypeReference.typeRefTo;

/**
 * Creates OpenAPI specification for evitaDB management.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@Slf4j
public class SystemRestBuilder extends FinalRestBuilder<SystemRestBuildingContext> {

	@Nonnull private final SystemEndpointBuilder endpointBuilder;

	/**
	 * Creates new builder.
	 */
	public SystemRestBuilder(
		@Nonnull RestOptions restConfig,
		@Nonnull HeaderOptions headerOptions,
		@Nonnull Evita evita
	) {
		super(new SystemRestBuildingContext(restConfig, headerOptions, evita));
		this.endpointBuilder = new SystemEndpointBuilder(this.operationPathParameterBuilderTransformer);
	}

	/**
	 * Builds OpenAPI specification for evitaDB management.
	 *
	 * @return OpenAPI specification
	 */
	@Nonnull
	public Rest build() {
		buildCommonTypes();
		buildEndpoints();

		return this.buildingContext.buildRest();
	}

	private void buildCommonTypes() {
		this.buildingContext.registerType(ErrorDescriptor.THIS.to(this.objectBuilderTransformer).build());
		this.buildingContext.registerType(LivenessDescriptor.THIS.to(this.objectBuilderTransformer).build());
		this.buildingContext.registerType(NameVariantsDescriptor.THIS.to(this.objectBuilderTransformer).build());
		this.buildingContext.registerType(CatalogDescriptor.THIS.to(this.objectBuilderTransformer).build());
		this.buildingContext.registerType(buildCatalogUnion());
		this.buildingContext.registerType(UnusableCatalogDescriptor.THIS.to(this.objectBuilderTransformer).build());
		this.buildingContext.registerType(CreateCatalogRequestDescriptor.THIS.to(this.objectBuilderTransformer).build());
		this.buildingContext.registerType(UpdateCatalogRequestDescriptor.THIS.to(this.objectBuilderTransformer).build());
	}

	private void buildEndpoints() {
		this.buildingContext.registerEndpoint(this.endpointBuilder.buildOpenApiSpecificationEndpoint());
		this.buildingContext.registerEndpoint(this.endpointBuilder.buildLivenessEndpoint());
		this.buildingContext.registerEndpoint(this.endpointBuilder.buildGetCatalogEndpoint());
		this.buildingContext.registerEndpoint(this.endpointBuilder.buildListCatalogsEndpoint());
		this.buildingContext.registerEndpoint(this.endpointBuilder.buildCreateCatalogEndpoint());
		this.buildingContext.registerEndpoint(this.endpointBuilder.buildUpdateCatalogEndpoint());
		this.buildingContext.registerEndpoint(this.endpointBuilder.buildDeleteCatalogEndpoint());
	}

	@Nonnull
	private OpenApiUnion buildCatalogUnion() {
		return CatalogUnionDescriptor.THIS
			.to(this.unionBuilderTransformer)
			.type(OpenApiObjectUnionType.ONE_OF)
			.object(typeRefTo(CatalogDescriptor.THIS.name()))
			.object(typeRefTo(UnusableCatalogDescriptor.THIS.name()))
			.build();
	}
}
