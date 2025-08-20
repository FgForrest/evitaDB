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

package io.evitadb.externalApi.rest.api.catalog;

import io.evitadb.api.CatalogContract;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.configuration.HeaderOptions;
import io.evitadb.externalApi.rest.api.Rest;
import io.evitadb.externalApi.rest.api.builder.FinalRestBuilder;
import io.evitadb.externalApi.rest.api.catalog.builder.CatalogEndpointBuilder;
import io.evitadb.externalApi.rest.api.catalog.builder.CatalogRestBuildingContext;
import io.evitadb.externalApi.rest.api.catalog.dataApi.CatalogDataApiRestBuilder;
import io.evitadb.externalApi.rest.api.catalog.schemaApi.CatalogSchemaApiRestBuilder;
import io.evitadb.externalApi.rest.api.model.ErrorDescriptor;
import io.evitadb.externalApi.rest.configuration.RestOptions;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;

/**
 * Creates OpenAPI specification for Evita's catalog.
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
@Slf4j
public class CatalogRestBuilder extends FinalRestBuilder<CatalogRestBuildingContext> {

	@Nonnull private final CatalogEndpointBuilder endpointBuilder;

	/**
	 * Creates new builder.
	 */
	public CatalogRestBuilder(
		@Nonnull RestOptions restConfig,
		@Nonnull HeaderOptions headerOptions,
		@Nonnull Evita evita,
		@Nonnull CatalogContract catalog
	) {
		super(new CatalogRestBuildingContext(restConfig, headerOptions, evita, catalog));
		this.endpointBuilder = new CatalogEndpointBuilder();
	}

	/**
	 * Builds OpenAPI specification for provided catalog.
	 *
	 * @return OpenAPI specification
	 */
	@Nonnull
	public Rest build() {
		buildCommonTypes();
		buildEndpoints();

		new CatalogDataApiRestBuilder(this.buildingContext).build();
		new CatalogSchemaApiRestBuilder(this.buildingContext).build();

		return this.buildingContext.buildRest();
	}

	private void buildCommonTypes() {
		this.buildingContext.registerType(buildScalarEnum());
		this.buildingContext.registerType(ErrorDescriptor.THIS.to(this.objectBuilderTransformer).build());
	}

	private void buildEndpoints() {
		this.buildingContext.registerEndpoint(this.endpointBuilder.buildOpenApiSpecificationEndpoint(this.buildingContext));
	}
}
