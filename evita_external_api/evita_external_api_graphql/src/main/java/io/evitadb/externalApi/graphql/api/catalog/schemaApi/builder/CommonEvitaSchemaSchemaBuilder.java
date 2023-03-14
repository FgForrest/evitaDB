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

package io.evitadb.externalApi.graphql.api.catalog.schemaApi.builder;

import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLObjectType;
import io.evitadb.externalApi.api.catalog.schemaApi.model.SchemaNameVariantsDescriptor;
import io.evitadb.externalApi.graphql.api.builder.PartialGraphQLSchemaBuilder;
import io.evitadb.externalApi.graphql.api.catalog.builder.CatalogGraphQLSchemaBuildingContext;
import io.evitadb.externalApi.graphql.api.catalog.schemaApi.resolver.dataFetcher.SchemaNameVariantDataFetcher;
import io.evitadb.utils.NamingConvention;

import javax.annotation.Nonnull;

/**
 * Implementation of {@link PartialGraphQLSchemaBuilder} for building common types and fields used in both {@link CatalogSchemaSchemaBuilder}
 * and {@link EntitySchemaSchemaBuilder}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class CommonEvitaSchemaSchemaBuilder extends PartialGraphQLSchemaBuilder<CatalogGraphQLSchemaBuildingContext> {

	public CommonEvitaSchemaSchemaBuilder(@Nonnull CatalogGraphQLSchemaBuildingContext catalogGraphQLSchemaBuildingContext) {
		super(catalogGraphQLSchemaBuildingContext);
	}

	@Override
	public void build() {
		final GraphQLEnumType scalarEnum = buildScalarEnum();
		buildingContext.registerType(scalarEnum);
		buildingContext.registerType(buildAssociatedDataScalarEnum(scalarEnum));
		buildingContext.registerType(buildSchemaNameVariantsObject());
	}

	@Nonnull
	private GraphQLObjectType buildSchemaNameVariantsObject() {
		buildingContext.registerDataFetcher(
			SchemaNameVariantsDescriptor.THIS,
			SchemaNameVariantsDescriptor.CAMEL_CASE,
			new SchemaNameVariantDataFetcher(NamingConvention.CAMEL_CASE)
		);
		buildingContext.registerDataFetcher(
			SchemaNameVariantsDescriptor.THIS,
			SchemaNameVariantsDescriptor.PASCAL_CASE,
			new SchemaNameVariantDataFetcher(NamingConvention.PASCAL_CASE)
		);
		buildingContext.registerDataFetcher(
			SchemaNameVariantsDescriptor.THIS,
			SchemaNameVariantsDescriptor.SNAKE_CASE,
			new SchemaNameVariantDataFetcher(NamingConvention.SNAKE_CASE)
		);
		buildingContext.registerDataFetcher(
			SchemaNameVariantsDescriptor.THIS,
			SchemaNameVariantsDescriptor.UPPER_SNAKE_CASE,
			new SchemaNameVariantDataFetcher(NamingConvention.UPPER_SNAKE_CASE)
		);
		buildingContext.registerDataFetcher(
			SchemaNameVariantsDescriptor.THIS,
			SchemaNameVariantsDescriptor.KEBAB_CASE,
			new SchemaNameVariantDataFetcher(NamingConvention.KEBAB_CASE)
		);

		return SchemaNameVariantsDescriptor.THIS
			.to(objectBuilderTransformer)
			.build();
	}
}
