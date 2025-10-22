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

package io.evitadb.externalApi.graphql.api.catalog.schemaApi.builder;

import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLObjectType;
import io.evitadb.externalApi.api.catalog.model.cdc.ChangeCatalogCaptureDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.NameVariantsDescriptor;
import io.evitadb.externalApi.api.model.mutation.MutationDescriptor;
import io.evitadb.externalApi.graphql.api.builder.PartialGraphQLSchemaBuilder;
import io.evitadb.externalApi.graphql.api.catalog.builder.CatalogGraphQLSchemaBuildingContext;
import io.evitadb.externalApi.graphql.api.catalog.schemaApi.resolver.dataFetcher.MutationDtoTypeResolver;
import io.evitadb.externalApi.graphql.api.catalog.schemaApi.resolver.dataFetcher.NameVariantDataFetcher;
import io.evitadb.externalApi.graphql.api.catalog.schemaApi.resolver.subscribingDataFetcher.ChangeCatalogSchemaCaptureBodyDataFetcher;
import io.evitadb.externalApi.graphql.api.dataType.GraphQLScalars;
import io.evitadb.utils.NamingConvention;

import javax.annotation.Nonnull;

import static graphql.schema.GraphQLNonNull.nonNull;

/**
 * Implementation of {@link PartialGraphQLSchemaBuilder} for building common types and fields used in both {@link CatalogSchemaSchemaBuilder}
 * and {@link EntitySchemaSchemaBuilder}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class CommonEvitaSchemaSchemaBuilder extends PartialGraphQLSchemaBuilder<CatalogGraphQLSchemaBuildingContext> {

	private static final NameVariantDataFetcher CAMEL_CASE_VARIANT_DATA_FETCHER = new NameVariantDataFetcher(NamingConvention.CAMEL_CASE);
	private static final NameVariantDataFetcher PASCAL_CASE_VARIANT_DATA_FETCHER = new NameVariantDataFetcher(NamingConvention.PASCAL_CASE);
	private static final NameVariantDataFetcher SNAKE_CASE_VARIANT_DATA_FETCHER = new NameVariantDataFetcher(NamingConvention.SNAKE_CASE);
	private static final NameVariantDataFetcher UPPER_SNAKE_CASE_VARIANT_DATA_FETCHER = new NameVariantDataFetcher(NamingConvention.UPPER_SNAKE_CASE);
	private static final NameVariantDataFetcher KEBAB_CASE_VARIANT_DATA_FETCHER = new NameVariantDataFetcher(NamingConvention.KEBAB_CASE);

	public CommonEvitaSchemaSchemaBuilder(@Nonnull CatalogGraphQLSchemaBuildingContext catalogGraphQLSchemaBuildingContext) {
		super(catalogGraphQLSchemaBuildingContext);
	}

	@Override
	public void build() {
		final GraphQLEnumType scalarEnum = buildScalarEnum();
		this.buildingContext.registerType(scalarEnum);
		this.buildingContext.registerType(buildAssociatedDataScalarEnum(scalarEnum));
		this.buildingContext.registerType(buildNameVariantsObject());

		buildMutationInterface();

		this.buildingContext.registerType(buildChangeCatalogCaptureObject());
	}

	private void buildMutationInterface() {
		final GraphQLInterfaceType mutationInterface = MutationDescriptor.THIS_INTERFACE.to(this.interfaceBuilderTransformer).build();
		this.buildingContext.registerType(mutationInterface);
		this.buildingContext.addMappingTypeResolver(mutationInterface, new MutationDtoTypeResolver(120));
	}

	@Nonnull
	private GraphQLObjectType buildNameVariantsObject() {
		this.buildingContext.registerDataFetcher(
			NameVariantsDescriptor.THIS,
			NameVariantsDescriptor.CAMEL_CASE,
			CAMEL_CASE_VARIANT_DATA_FETCHER
		);
		this.buildingContext.registerDataFetcher(
			NameVariantsDescriptor.THIS,
			NameVariantsDescriptor.PASCAL_CASE,
			PASCAL_CASE_VARIANT_DATA_FETCHER
		);
		this.buildingContext.registerDataFetcher(
			NameVariantsDescriptor.THIS,
			NameVariantsDescriptor.SNAKE_CASE,
			SNAKE_CASE_VARIANT_DATA_FETCHER
		);
		this.buildingContext.registerDataFetcher(
			NameVariantsDescriptor.THIS,
			NameVariantsDescriptor.UPPER_SNAKE_CASE,
			UPPER_SNAKE_CASE_VARIANT_DATA_FETCHER
		);
		this.buildingContext.registerDataFetcher(
			NameVariantsDescriptor.THIS,
			NameVariantsDescriptor.KEBAB_CASE,
			KEBAB_CASE_VARIANT_DATA_FETCHER
		);

		return NameVariantsDescriptor.THIS
			.to(this.objectBuilderTransformer)
			.build();
	}

	@Nonnull
	private GraphQLObjectType buildChangeCatalogCaptureObject() {
		this.buildingContext.registerDataFetcher(
			ChangeCatalogCaptureDescriptor.THIS,
			ChangeCatalogCaptureDescriptor.BODY,
			new ChangeCatalogSchemaCaptureBodyDataFetcher()
		);

		return ChangeCatalogCaptureDescriptor.THIS
			.to(this.objectBuilderTransformer)
			.field(ChangeCatalogCaptureDescriptor.BODY.to(this.fieldBuilderTransformer).type(nonNull(GraphQLScalars.OBJECT)))
			.build();
	}
}
