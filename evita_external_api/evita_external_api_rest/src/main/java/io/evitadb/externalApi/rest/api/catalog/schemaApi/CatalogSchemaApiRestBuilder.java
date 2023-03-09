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

package io.evitadb.externalApi.rest.api.catalog.schemaApi;

import io.evitadb.externalApi.rest.api.builder.PartialRestBuilder;
import io.evitadb.externalApi.rest.api.catalog.builder.CatalogRestBuildingContext;

import javax.annotation.Nonnull;

/**
 * Builds schema API part of catalog's REST API. Building of whole REST API is handled by {@link io.evitadb.externalApi.rest.api.catalog.CatalogRestBuilder}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class CatalogSchemaApiRestBuilder extends PartialRestBuilder<CatalogRestBuildingContext> {

	public CatalogSchemaApiRestBuilder(@Nonnull CatalogRestBuildingContext context) {
		super(context);
	}

	@Override
	public void build() {
		// todo lho implement
	}

	private void buildCommonTypes() {
//		buildingContext.registerType(SchemaNameVariantsDescriptor.THIS.to(objectBuilderTransformer).build());
//		buildingContext.registerType(AttributeSchemaDescriptor.THIS.to(objectBuilderTransformer).build());
//		buildingContext.registerType(GlobalAttributeSchemaDescriptor.THIS.to(objectBuilderTransformer).build());
//		buildingContext.registerType(AssociatedDataSchemaDescriptor.THIS.to(objectBuilderTransformer).build());
	}
}
