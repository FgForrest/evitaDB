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

package io.evitadb.externalApi.rest.api.catalog.cdcApi;

import io.evitadb.externalApi.rest.api.builder.PartialRestBuilder;
import io.evitadb.externalApi.rest.api.catalog.builder.CatalogRestBuildingContext;
import io.evitadb.externalApi.rest.api.catalog.cdcApi.builder.CdcApiEndpointBuilder;

import javax.annotation.Nonnull;

/**
 * TODO lho docs
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2025
 */
public class CatalogCdcApiRestBuilder extends PartialRestBuilder<CatalogRestBuildingContext> {

	@Nonnull private final CdcApiEndpointBuilder endpointBuilder;

	public CatalogCdcApiRestBuilder(@Nonnull CatalogRestBuildingContext buildingContext) {
		super(buildingContext);
		this.endpointBuilder = new CdcApiEndpointBuilder(buildingContext);
	}

	@Override
	public void build() {
		// todo lho define mutation types for the CDC even if not used directly?
		buildEndpoints();
	}

	private void buildEndpoints() {
		this.buildingContext.registerEndpoint(this.endpointBuilder.buildChangeCatalogCaptureEndpoint());
	}
}
