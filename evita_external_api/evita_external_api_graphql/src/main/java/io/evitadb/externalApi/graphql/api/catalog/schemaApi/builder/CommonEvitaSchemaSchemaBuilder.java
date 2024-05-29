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
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
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
import io.evitadb.externalApi.api.catalog.schemaApi.model.NameVariantsDescriptor;
import io.evitadb.externalApi.graphql.api.builder.PartialGraphQLSchemaBuilder;
import io.evitadb.externalApi.graphql.api.catalog.builder.CatalogGraphQLSchemaBuildingContext;
import io.evitadb.externalApi.graphql.api.catalog.schemaApi.resolver.dataFetcher.NameVariantDataFetcher;
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
		buildingContext.registerType(buildNameVariantsObject());
	}

	@Nonnull
	private GraphQLObjectType buildNameVariantsObject() {
		buildingContext.registerDataFetcher(
			NameVariantsDescriptor.THIS,
			NameVariantsDescriptor.CAMEL_CASE,
			new NameVariantDataFetcher(NamingConvention.CAMEL_CASE)
		);
		buildingContext.registerDataFetcher(
			NameVariantsDescriptor.THIS,
			NameVariantsDescriptor.PASCAL_CASE,
			new NameVariantDataFetcher(NamingConvention.PASCAL_CASE)
		);
		buildingContext.registerDataFetcher(
			NameVariantsDescriptor.THIS,
			NameVariantsDescriptor.SNAKE_CASE,
			new NameVariantDataFetcher(NamingConvention.SNAKE_CASE)
		);
		buildingContext.registerDataFetcher(
			NameVariantsDescriptor.THIS,
			NameVariantsDescriptor.UPPER_SNAKE_CASE,
			new NameVariantDataFetcher(NamingConvention.UPPER_SNAKE_CASE)
		);
		buildingContext.registerDataFetcher(
			NameVariantsDescriptor.THIS,
			NameVariantsDescriptor.KEBAB_CASE,
			new NameVariantDataFetcher(NamingConvention.KEBAB_CASE)
		);

		return NameVariantsDescriptor.THIS
			.to(objectBuilderTransformer)
			.build();
	}
}
