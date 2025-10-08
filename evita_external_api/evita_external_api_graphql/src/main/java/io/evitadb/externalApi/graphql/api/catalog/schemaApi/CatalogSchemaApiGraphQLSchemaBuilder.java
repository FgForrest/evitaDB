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

package io.evitadb.externalApi.graphql.api.catalog.schemaApi;

import graphql.schema.GraphQLSchema;
import io.evitadb.api.CatalogContract;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.graphql.api.builder.FinalGraphQLSchemaBuilder;
import io.evitadb.externalApi.graphql.api.builder.GraphQLSchemaBuilder;
import io.evitadb.externalApi.graphql.api.catalog.builder.CatalogGraphQLSchemaBuildingContext;
import io.evitadb.externalApi.graphql.api.catalog.schemaApi.builder.CatalogSchemaSchemaBuilder;
import io.evitadb.externalApi.graphql.api.catalog.schemaApi.builder.CommonEvitaSchemaSchemaBuilder;
import io.evitadb.externalApi.graphql.api.catalog.schemaApi.builder.EntitySchemaSchemaBuilder;
import io.evitadb.externalApi.graphql.configuration.GraphQLOptions;

import javax.annotation.Nonnull;

/**
 * Builds {@link GraphQLSchema} from entire Evita's {@link CatalogSchema}.
 * Actual building of field is delegated to implementations of {@link GraphQLSchemaBuilder}s categorized by
 * what type of data they target.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class CatalogSchemaApiGraphQLSchemaBuilder extends FinalGraphQLSchemaBuilder<CatalogGraphQLSchemaBuildingContext> {

	public CatalogSchemaApiGraphQLSchemaBuilder(@Nonnull GraphQLOptions config, @Nonnull Evita evita, @Nonnull CatalogContract catalog) {
		super(new CatalogGraphQLSchemaBuildingContext(config, evita, catalog));
	}

	/**
	 * Build ready-to-use schema based on data from constructor.
	 */
	@Override
	@Nonnull
	public GraphQLSchema build() {
		// internal evita schema
		new CommonEvitaSchemaSchemaBuilder(this.buildingContext).build();
		new EntitySchemaSchemaBuilder(this.buildingContext).build();
		new CatalogSchemaSchemaBuilder(this.buildingContext).build();

		return this.buildingContext.buildGraphQLSchema();
	}
}
