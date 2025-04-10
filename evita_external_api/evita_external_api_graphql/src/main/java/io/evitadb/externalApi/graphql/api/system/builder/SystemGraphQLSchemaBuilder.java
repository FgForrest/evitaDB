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

package io.evitadb.externalApi.graphql.api.system.builder;

import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLUnionType;
import graphql.schema.PropertyDataFetcher;
import graphql.schema.TypeResolver;
import io.evitadb.api.CatalogContract;
import io.evitadb.core.Catalog;
import io.evitadb.core.CorruptedCatalog;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.api.catalog.schemaApi.model.NameVariantsDescriptor;
import io.evitadb.externalApi.api.system.model.CatalogDescriptor;
import io.evitadb.externalApi.api.system.model.CatalogUnionDescriptor;
import io.evitadb.externalApi.api.system.model.CorruptedCatalogDescriptor;
import io.evitadb.externalApi.graphql.api.builder.BuiltFieldDescriptor;
import io.evitadb.externalApi.graphql.api.builder.FinalGraphQLSchemaBuilder;
import io.evitadb.externalApi.graphql.api.builder.GraphQLSchemaBuildingContext;
import io.evitadb.externalApi.graphql.api.catalog.schemaApi.resolver.dataFetcher.NameVariantDataFetcher;
import io.evitadb.externalApi.graphql.api.resolver.dataFetcher.AsyncDataFetcher;
import io.evitadb.externalApi.graphql.api.system.model.CatalogQueryHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.system.model.CreateCatalogMutationHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.system.model.DeleteCatalogIfExistsMutationHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.system.model.RenameCatalogMutationHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.system.model.ReplaceCatalogMutationHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.system.model.SwitchCatalogToAliveStateMutationHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.system.model.SystemRootDescriptor;
import io.evitadb.externalApi.graphql.api.system.resolver.dataFetcher.CatalogDataFetcher;
import io.evitadb.externalApi.graphql.api.system.resolver.dataFetcher.CatalogsDataFetcher;
import io.evitadb.externalApi.graphql.api.system.resolver.dataFetcher.LivenessDataFetcher;
import io.evitadb.externalApi.graphql.api.system.resolver.mutatingDataFetcher.CreateCatalogMutatingDataFetcher;
import io.evitadb.externalApi.graphql.api.system.resolver.mutatingDataFetcher.DeleteCatalogIfExistsMutatingDataFetcher;
import io.evitadb.externalApi.graphql.api.system.resolver.mutatingDataFetcher.RenameCatalogMutatingDataFetcher;
import io.evitadb.externalApi.graphql.api.system.resolver.mutatingDataFetcher.ReplaceCatalogMutatingDataFetcher;
import io.evitadb.externalApi.graphql.api.system.resolver.mutatingDataFetcher.SwitchCatalogToAliveStateMutatingDataFetcher;
import io.evitadb.externalApi.graphql.configuration.GraphQLOptions;
import io.evitadb.utils.NamingConvention;

import javax.annotation.Nonnull;
import java.util.Map;

/**
 * Implementation of {@link FinalGraphQLSchemaBuilder} for building evitaDB management manipulation schema.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class SystemGraphQLSchemaBuilder extends FinalGraphQLSchemaBuilder<GraphQLSchemaBuildingContext> {

	private static final NameVariantDataFetcher CAMEL_CASE_VARIANT_DATA_FETCHER = new NameVariantDataFetcher(NamingConvention.CAMEL_CASE);
	private static final NameVariantDataFetcher PASCAL_CASE_VARIANT_DATA_FETCHER = new NameVariantDataFetcher(NamingConvention.PASCAL_CASE);
	private static final NameVariantDataFetcher SNAKE_CASE_VARIANT_DATA_FETCHER = new NameVariantDataFetcher(NamingConvention.SNAKE_CASE);
	private static final NameVariantDataFetcher UPPER_SNAKE_CASE_VARIANT_DATA_FETCHER = new NameVariantDataFetcher(NamingConvention.UPPER_SNAKE_CASE);
	private static final NameVariantDataFetcher KEBAB_CASE_VARIANT_DATA_FETCHER = new NameVariantDataFetcher(NamingConvention.KEBAB_CASE);

	private static final PropertyDataFetcher<Map<NamingConvention, String>> CATALOG_NAME_VARIANTS_DATA_FETCHER = PropertyDataFetcher.fetching(it -> ((Catalog) it).getSchema().getNameVariants());
	private static final PropertyDataFetcher<Boolean> CATALOG_SUPPORTS_TRANSACTION_DATA_FETCHER = PropertyDataFetcher.fetching(CatalogContract::supportsTransaction);
	private static final PropertyDataFetcher<Boolean> CATALOG_CORRUPTED_DATA_FETCHER = PropertyDataFetcher.fetching(it -> false);

	private static final PropertyDataFetcher<String> CORRUPTED_CATALOG_STORAGE_PATH_DATA_FETCHER = PropertyDataFetcher.fetching(it -> ((CorruptedCatalog) it).getCatalogStoragePath().toString());
	private static final PropertyDataFetcher<String> CORRUPTED_CATALOG_CAUSE_DATA_FETCHER = PropertyDataFetcher.fetching(it -> ((CorruptedCatalog) it).getCause().toString());
	private static final PropertyDataFetcher<Boolean> CORRUPTED_CATALOG_CORRUPTED_DATA_FETCHER = PropertyDataFetcher.fetching(it -> true);

	@Nonnull
	private final Evita evita;

	public SystemGraphQLSchemaBuilder(@Nonnull GraphQLOptions config, @Nonnull Evita evita) {
		super(new GraphQLSchemaBuildingContext(config, evita));
		this.evita = evita;
	}

	@Override
    @Nonnull
	public GraphQLSchema build() {
		buildingContext.registerType(buildNameVariantsObject());

		final GraphQLObjectType catalogObject = buildCatalogObject();
		buildingContext.registerType(catalogObject);
		final GraphQLObjectType corruptedCatalogObject = buildCorruptedCatalogObject();
		buildingContext.registerType(corruptedCatalogObject);
		buildingContext.registerType(buildCatalogUnion(catalogObject, corruptedCatalogObject));

		buildingContext.registerQueryField(buildLivenessField());
		buildingContext.registerQueryField(buildCatalogField());
		buildingContext.registerQueryField(buildCatalogsField());

		buildingContext.registerMutationField(buildCreateCatalogField());
		buildingContext.registerMutationField(buildSwitchCatalogToAliveStateField());
		buildingContext.registerMutationField(buildRenameCatalogField());
		buildingContext.registerMutationField(buildReplaceCatalogField());
		buildingContext.registerMutationField(buildDeleteCatalogIfExistsField());

		return buildingContext.buildGraphQLSchema();
	}


	@Nonnull
	private GraphQLObjectType buildNameVariantsObject() {
		buildingContext.registerDataFetcher(
			NameVariantsDescriptor.THIS,
			NameVariantsDescriptor.CAMEL_CASE,
			CAMEL_CASE_VARIANT_DATA_FETCHER
		);
		buildingContext.registerDataFetcher(
			NameVariantsDescriptor.THIS,
			NameVariantsDescriptor.PASCAL_CASE,
			PASCAL_CASE_VARIANT_DATA_FETCHER
		);
		buildingContext.registerDataFetcher(
			NameVariantsDescriptor.THIS,
			NameVariantsDescriptor.SNAKE_CASE,
			SNAKE_CASE_VARIANT_DATA_FETCHER
		);
		buildingContext.registerDataFetcher(
			NameVariantsDescriptor.THIS,
			NameVariantsDescriptor.UPPER_SNAKE_CASE,
			UPPER_SNAKE_CASE_VARIANT_DATA_FETCHER
		);
		buildingContext.registerDataFetcher(
			NameVariantsDescriptor.THIS,
			NameVariantsDescriptor.KEBAB_CASE,
			KEBAB_CASE_VARIANT_DATA_FETCHER
		);

		return NameVariantsDescriptor.THIS
			.to(objectBuilderTransformer)
			.build();
	}

	@Nonnull
	private GraphQLObjectType buildCatalogObject() {
		buildingContext.registerDataFetcher(
			CatalogDescriptor.THIS,
			CatalogDescriptor.NAME_VARIANTS,
			CATALOG_NAME_VARIANTS_DATA_FETCHER
		);
		buildingContext.registerDataFetcher(
			CatalogDescriptor.THIS,
			CatalogDescriptor.SUPPORTS_TRANSACTION,
			CATALOG_SUPPORTS_TRANSACTION_DATA_FETCHER
		);
		buildingContext.registerDataFetcher(
			CatalogDescriptor.THIS,
			CatalogDescriptor.CORRUPTED,
			CATALOG_CORRUPTED_DATA_FETCHER
		);

		return CatalogDescriptor.THIS.to(objectBuilderTransformer).build();
	}

	@Nonnull
	private GraphQLObjectType buildCorruptedCatalogObject() {
		buildingContext.registerDataFetcher(
			CorruptedCatalogDescriptor.THIS,
			CorruptedCatalogDescriptor.CATALOG_STORAGE_PATH,
			CORRUPTED_CATALOG_STORAGE_PATH_DATA_FETCHER
		);
		buildingContext.registerDataFetcher(
			CorruptedCatalogDescriptor.THIS,
			CorruptedCatalogDescriptor.CAUSE,
			CORRUPTED_CATALOG_CAUSE_DATA_FETCHER
		);
		buildingContext.registerDataFetcher(
			CorruptedCatalogDescriptor.THIS,
			CorruptedCatalogDescriptor.CORRUPTED,
			CORRUPTED_CATALOG_CORRUPTED_DATA_FETCHER
		);

		return CorruptedCatalogDescriptor.THIS.to(objectBuilderTransformer).build();
	}

	@Nonnull
	private GraphQLUnionType buildCatalogUnion(@Nonnull GraphQLObjectType catalogObject,
	                                           @Nonnull GraphQLObjectType corruptedCatalogObject) {
		final GraphQLUnionType catalogUnion = CatalogUnionDescriptor.THIS
			.to(unionBuilderTransformer)
			.possibleTypes(catalogObject)
			.possibleType(corruptedCatalogObject)
			.build();

		final TypeResolver catalogUnionResolver = env -> {
			if (env.getObject() instanceof CorruptedCatalog) {
				return corruptedCatalogObject;
			} else {
				return catalogObject;
			}
		};
		buildingContext.registerTypeResolver(catalogUnion, catalogUnionResolver);

		return catalogUnion;
	}

	@Nonnull
	private BuiltFieldDescriptor buildLivenessField() {
		return new BuiltFieldDescriptor(
			SystemRootDescriptor.LIVENESS.to(staticEndpointBuilderTransformer).build(),
			new AsyncDataFetcher(
				LivenessDataFetcher.getInstance(),
				buildingContext.getConfig(),
				buildingContext.getTracingContext(),
				buildingContext.getEvita()
			)
		);
	}

	@Nonnull
	private BuiltFieldDescriptor buildCatalogField() {
		final GraphQLFieldDefinition catalogField = SystemRootDescriptor.CATALOG
			.to(staticEndpointBuilderTransformer)
			.argument(CatalogQueryHeaderDescriptor.NAME.to(argumentBuilderTransformer))
			.build();

		return new BuiltFieldDescriptor(
			catalogField,
			new AsyncDataFetcher(
				new CatalogDataFetcher(evita),
				buildingContext.getConfig(),
				buildingContext.getTracingContext(),
				buildingContext.getEvita()
			)
		);
	}

	@Nonnull
	private BuiltFieldDescriptor buildCatalogsField() {
		return new BuiltFieldDescriptor(
			SystemRootDescriptor.CATALOGS.to(staticEndpointBuilderTransformer).build(),
			new AsyncDataFetcher(
				new CatalogsDataFetcher(evita),
				buildingContext.getConfig(),
				buildingContext.getTracingContext(),
				buildingContext.getEvita()
			)
		);
	}

	@Nonnull
	private BuiltFieldDescriptor buildCreateCatalogField() {
		final GraphQLFieldDefinition createCatalogField = SystemRootDescriptor.CREATE_CATALOG
			.to(staticEndpointBuilderTransformer)
			.argument(CreateCatalogMutationHeaderDescriptor.NAME.to(argumentBuilderTransformer))
			.build();

		return new BuiltFieldDescriptor(
			createCatalogField,
			new AsyncDataFetcher(
				new CreateCatalogMutatingDataFetcher(evita),
				buildingContext.getConfig(),
				buildingContext.getTracingContext(),
				buildingContext.getEvita()
			)
		);
	}

	@Nonnull
	private BuiltFieldDescriptor buildSwitchCatalogToAliveStateField() {
		final GraphQLFieldDefinition switchCatalogToAliveStateField = SystemRootDescriptor.SWITCH_CATALOG_TO_ALIVE_STATE
			.to(staticEndpointBuilderTransformer)
			.argument(SwitchCatalogToAliveStateMutationHeaderDescriptor.NAME.to(argumentBuilderTransformer))
			.build();

		return new BuiltFieldDescriptor(
			switchCatalogToAliveStateField,
			new AsyncDataFetcher(
				new SwitchCatalogToAliveStateMutatingDataFetcher(evita),
				buildingContext.getConfig(),
				buildingContext.getTracingContext(),
				buildingContext.getEvita()
			)
		);
	}

	@Nonnull
	private BuiltFieldDescriptor buildRenameCatalogField() {
		final GraphQLFieldDefinition renameCatalogField = SystemRootDescriptor.RENAME_CATALOG
			.to(staticEndpointBuilderTransformer)
			.argument(RenameCatalogMutationHeaderDescriptor.NAME.to(argumentBuilderTransformer))
			.argument(RenameCatalogMutationHeaderDescriptor.NEW_NAME.to(argumentBuilderTransformer))
			.build();

		return new BuiltFieldDescriptor(
			renameCatalogField,
			new AsyncDataFetcher(
				new RenameCatalogMutatingDataFetcher(evita),
				buildingContext.getConfig(),
				buildingContext.getTracingContext(),
				buildingContext.getEvita()
			)
		);
	}

	@Nonnull
	private BuiltFieldDescriptor buildReplaceCatalogField() {
		final GraphQLFieldDefinition replaceCatalogField = SystemRootDescriptor.REPLACE_CATALOG
			.to(staticEndpointBuilderTransformer)
			.argument(ReplaceCatalogMutationHeaderDescriptor.NAME_TO_BE_REPLACED.to(argumentBuilderTransformer))
			.argument(ReplaceCatalogMutationHeaderDescriptor.NAME_TO_BE_REPLACED_WITH.to(argumentBuilderTransformer))
			.build();

		return new BuiltFieldDescriptor(
			replaceCatalogField,
			new AsyncDataFetcher(
				new ReplaceCatalogMutatingDataFetcher(evita),
				buildingContext.getConfig(),
				buildingContext.getTracingContext(),
				buildingContext.getEvita()
			)
		);
	}

	@Nonnull
	private BuiltFieldDescriptor buildDeleteCatalogIfExistsField() {
		final GraphQLFieldDefinition deleteCatalogIfExistsCatalogField = SystemRootDescriptor.DELETE_CATALOG_IF_EXISTS
			.to(staticEndpointBuilderTransformer)
			.argument(DeleteCatalogIfExistsMutationHeaderDescriptor.NAME.to(argumentBuilderTransformer))
			.build();

		return new BuiltFieldDescriptor(
			deleteCatalogIfExistsCatalogField,
			new AsyncDataFetcher(
				new DeleteCatalogIfExistsMutatingDataFetcher(evita),
				buildingContext.getConfig(),
				buildingContext.getTracingContext(),
				buildingContext.getEvita()
			)
		);
	}
}
