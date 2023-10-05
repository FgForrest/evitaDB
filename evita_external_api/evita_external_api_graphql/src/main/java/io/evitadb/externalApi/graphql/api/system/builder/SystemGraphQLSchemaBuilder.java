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

package io.evitadb.externalApi.graphql.api.system.builder;

import graphql.schema.*;
import io.evitadb.api.CatalogContract;
import io.evitadb.core.Catalog;
import io.evitadb.core.CorruptedCatalog;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.api.catalog.schemaApi.model.AttributeElementDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.NameVariantsDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.EntitySchemaMutationAggregateDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.LocalCatalogSchemaMutationAggregateDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.TopLevelCatalogSchemaMutationAggregateDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.associatedData.CreateAssociatedDataSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.associatedData.ModifyAssociatedDataSchemaDeprecationNoticeMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.associatedData.ModifyAssociatedDataSchemaDescriptionMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.associatedData.ModifyAssociatedDataSchemaNameMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.associatedData.ModifyAssociatedDataSchemaTypeMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.associatedData.RemoveAssociatedDataSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.associatedData.SetAssociatedDataSchemaLocalizedMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.associatedData.SetAssociatedDataSchemaNullableMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.attribute.*;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.catalog.*;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.entity.*;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.reference.*;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.sortableAttributeCompound.CreateSortableAttributeCompoundSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.sortableAttributeCompound.ModifySortableAttributeCompoundSchemaDeprecationNoticeMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.sortableAttributeCompound.ModifySortableAttributeCompoundSchemaDescriptionMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.sortableAttributeCompound.ModifySortableAttributeCompoundSchemaNameMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.sortableAttributeCompound.ReferenceSortableAttributeCompoundSchemaMutationAggregateDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.sortableAttributeCompound.RemoveSortableAttributeCompoundSchemaMutationDescriptor;
import io.evitadb.externalApi.api.system.model.CatalogDescriptor;
import io.evitadb.externalApi.api.system.model.CatalogUnionDescriptor;
import io.evitadb.externalApi.api.system.model.CorruptedCatalogDescriptor;
import io.evitadb.externalApi.api.system.model.cdc.ChangeSystemCaptureDescriptor;
import io.evitadb.externalApi.graphql.api.builder.BuiltFieldDescriptor;
import io.evitadb.externalApi.graphql.api.builder.FinalGraphQLSchemaBuilder;
import io.evitadb.externalApi.graphql.api.builder.GraphQLSchemaBuildingContext;
import io.evitadb.externalApi.graphql.api.catalog.schemaApi.resolver.dataFetcher.NameVariantDataFetcher;
import io.evitadb.externalApi.graphql.api.dataType.GraphQLScalars;
import io.evitadb.externalApi.graphql.api.resolver.dataFetcher.ReadDataFetcher;
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
import io.evitadb.externalApi.graphql.api.system.resolver.subscriptionDataFetcher.OnSystemChangeDataFetcher;
import io.evitadb.externalApi.graphql.api.system.resolver.mutatingDataFetcher.CreateCatalogMutatingDataFetcher;
import io.evitadb.externalApi.graphql.api.system.resolver.mutatingDataFetcher.DeleteCatalogIfExistsMutatingDataFetcher;
import io.evitadb.externalApi.graphql.api.system.resolver.mutatingDataFetcher.RenameCatalogMutatingDataFetcher;
import io.evitadb.externalApi.graphql.api.system.resolver.mutatingDataFetcher.ReplaceCatalogMutatingDataFetcher;
import io.evitadb.externalApi.graphql.api.system.resolver.mutatingDataFetcher.SwitchCatalogToAliveStateMutatingDataFetcher;
import io.evitadb.externalApi.graphql.configuration.GraphQLConfig;
import io.evitadb.utils.NamingConvention;

import javax.annotation.Nonnull;

/**
 * Implementation of {@link FinalGraphQLSchemaBuilder} for building evitaDB management manipulation schema.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class SystemGraphQLSchemaBuilder extends FinalGraphQLSchemaBuilder<GraphQLSchemaBuildingContext> {

	@Nonnull
	private final Evita evita;

	public SystemGraphQLSchemaBuilder(@Nonnull GraphQLConfig config, @Nonnull Evita evita) {
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

		// entity schema mutations
		buildingContext.registerType(AllowCurrencyInEntitySchemaMutationDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(AllowEvolutionModeInEntitySchemaMutationDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(AllowLocaleInEntitySchemaMutationDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(CreateEntitySchemaMutationDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(DisallowCurrencyInEntitySchemaMutationDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(DisallowEvolutionModeInEntitySchemaMutationDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(DisallowLocaleInEntitySchemaMutationDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(ModifyEntitySchemaDeprecationNoticeMutationDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(ModifyEntitySchemaDescriptionMutationDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(ModifyEntitySchemaNameMutationDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(RemoveEntitySchemaMutationDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(SetEntitySchemaWithGeneratedPrimaryKeyMutationDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(SetEntitySchemaWithHierarchyMutationDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(SetEntitySchemaWithPriceMutationDescriptor.THIS.to(objectBuilderTransformer).build());

		// associated data schema mutations
		buildingContext.registerType(CreateAssociatedDataSchemaMutationDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(ModifyAssociatedDataSchemaDeprecationNoticeMutationDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(ModifyAssociatedDataSchemaDescriptionMutationDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(ModifyAssociatedDataSchemaNameMutationDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(ModifyAssociatedDataSchemaTypeMutationDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(RemoveAssociatedDataSchemaMutationDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(SetAssociatedDataSchemaLocalizedMutationDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(SetAssociatedDataSchemaNullableMutationDescriptor.THIS.to(objectBuilderTransformer).build());

		// attribute schema mutations
		buildingContext.registerType(CreateAttributeSchemaMutationDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(ModifyAttributeSchemaDefaultValueMutationDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(ModifyAttributeSchemaDeprecationNoticeMutationDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(ModifyAttributeSchemaDescriptionMutationDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(ModifyAttributeSchemaNameMutationDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(ModifyAttributeSchemaTypeMutationDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(RemoveAttributeSchemaMutationDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(SetAttributeSchemaFilterableMutationDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(SetAttributeSchemaLocalizedMutationDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(SetAttributeSchemaNullableMutationDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(SetAttributeSchemaSortableMutationDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(SetAttributeSchemaUniqueMutationDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(UseGlobalAttributeSchemaMutationDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(ReferenceAttributeSchemaMutationAggregateDescriptor.THIS.to(objectBuilderTransformer).build());

		// attribute schema mutations
		buildingContext.registerType(AttributeElementDescriptor.THIS_INPUT.to(objectBuilderTransformer).build());
		buildingContext.registerType(CreateSortableAttributeCompoundSchemaMutationDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(ModifySortableAttributeCompoundSchemaDeprecationNoticeMutationDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(ModifySortableAttributeCompoundSchemaDescriptionMutationDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(ModifySortableAttributeCompoundSchemaNameMutationDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(RemoveSortableAttributeCompoundSchemaMutationDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(ReferenceSortableAttributeCompoundSchemaMutationAggregateDescriptor.THIS.to(objectBuilderTransformer).build());

		// reference schema mutations
		buildingContext.registerType(CreateReferenceSchemaMutationDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(ModifyReferenceAttributeSchemaMutationDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(ModifyReferenceSchemaCardinalityMutationDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(ModifyReferenceSchemaDeprecationNoticeMutationDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(ModifyReferenceSchemaDescriptionMutationDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(ModifyReferenceSchemaNameMutationDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(ModifyReferenceSchemaRelatedEntityGroupMutationDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(ModifyReferenceSchemaRelatedEntityMutationDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(RemoveReferenceSchemaMutationDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(SetReferenceSchemaFacetedMutationDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(SetReferenceSchemaIndexedMutationDescriptor.THIS.to(objectBuilderTransformer).build());

		buildingContext.registerType(EntitySchemaMutationAggregateDescriptor.THIS.to(objectBuilderTransformer).build());

		// catalog schema mutations
		buildingContext.registerType(ModifyEntitySchemaMutationDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(ModifyCatalogSchemaDescriptionMutationDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(AllowEvolutionModeInCatalogSchemaMutationDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(DisallowEvolutionModeInCatalogSchemaMutationDescriptor.THIS.to(objectBuilderTransformer).build());

		// global attribute schema mutations
		buildingContext.registerType(CreateGlobalAttributeSchemaMutationDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(SetAttributeSchemaGloballyUniqueMutationDescriptor.THIS.to(objectBuilderTransformer).build());

		// other mutation objects should be already created by EntitySchemaSchemaBuilder
		buildingContext.registerType(LocalCatalogSchemaMutationAggregateDescriptor.THIS.to(objectBuilderTransformer).build());

		// top level schema mutations
		buildingContext.registerType(CreateEntitySchemaMutationDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(ModifyCatalogSchemaMutationDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(ModifyCatalogSchemaNameMutationDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(RemoveCatalogSchemaMutationDescriptor.THIS.to(objectBuilderTransformer).build());

		buildingContext.registerType(TopLevelCatalogSchemaMutationAggregateDescriptor.THIS.to(objectBuilderTransformer).build());

		buildingContext.registerType(ChangeSystemCaptureDescriptor.THIS.to(objectBuilderTransformer).build());

		buildingContext.registerQueryField(buildLivenessField());
		buildingContext.registerQueryField(buildCatalogField());
		buildingContext.registerQueryField(buildCatalogsField());

		buildingContext.registerMutationField(buildCreateCatalogField());
		buildingContext.registerMutationField(buildSwitchCatalogToAliveStateField());
		buildingContext.registerMutationField(buildRenameCatalogField());
		buildingContext.registerMutationField(buildReplaceCatalogField());
		buildingContext.registerMutationField(buildDeleteCatalogIfExistsField());

		buildingContext.registerSubscriptionField(buildOnSystemChangeField());

		return buildingContext.buildGraphQLSchema();
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

	@Nonnull
	private GraphQLObjectType buildCatalogObject() {
		buildingContext.registerDataFetcher(
			CatalogDescriptor.THIS,
			CatalogDescriptor.NAME_VARIANTS,
			PropertyDataFetcher.fetching(it -> ((Catalog) it).getSchema().getNameVariants())
		);
		buildingContext.registerDataFetcher(
			CatalogDescriptor.THIS,
			CatalogDescriptor.SUPPORTS_TRANSACTION,
			PropertyDataFetcher.fetching(CatalogContract::supportsTransaction)
		);
		buildingContext.registerDataFetcher(
			CatalogDescriptor.THIS,
			CatalogDescriptor.CORRUPTED,
			PropertyDataFetcher.fetching(it -> false)
		);

		return CatalogDescriptor.THIS.to(objectBuilderTransformer).build();
	}

	@Nonnull
	private GraphQLObjectType buildCorruptedCatalogObject() {
		buildingContext.registerDataFetcher(
			CorruptedCatalogDescriptor.THIS,
			CorruptedCatalogDescriptor.CATALOG_STORAGE_PATH,
			PropertyDataFetcher.fetching(it -> ((CorruptedCatalog) it).getCatalogStoragePath().toString())
		);
		buildingContext.registerDataFetcher(
			CorruptedCatalogDescriptor.THIS,
			CorruptedCatalogDescriptor.CAUSE,
			PropertyDataFetcher.fetching(it -> ((CorruptedCatalog) it).getCause().toString())
		);
		buildingContext.registerDataFetcher(
			CorruptedCatalogDescriptor.THIS,
			CorruptedCatalogDescriptor.CORRUPTED,
			PropertyDataFetcher.fetching(it -> true)
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
			new LivenessDataFetcher()
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
			new ReadDataFetcher(new CatalogDataFetcher(evita), buildingContext.getEvitaExecutor().orElse(null))
		);
	}

	@Nonnull
	private BuiltFieldDescriptor buildCatalogsField() {
		return new BuiltFieldDescriptor(
			SystemRootDescriptor.CATALOGS.to(staticEndpointBuilderTransformer).build(),
			new ReadDataFetcher(new CatalogsDataFetcher(evita), buildingContext.getEvitaExecutor().orElse(null))
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
			new CreateCatalogMutatingDataFetcher(evita)
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
			new SwitchCatalogToAliveStateMutatingDataFetcher(evita)
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
			new RenameCatalogMutatingDataFetcher(evita)
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
			new ReplaceCatalogMutatingDataFetcher(evita)
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
			new DeleteCatalogIfExistsMutatingDataFetcher(evita)
		);
	}

	@Nonnull
	private BuiltFieldDescriptor buildOnSystemChangeField() {
		final GraphQLFieldDefinition onSystemChangeField = SystemRootDescriptor.ON_SYSTEM_CHANGE
			.to(staticEndpointBuilderTransformer)
			.build();

		return new BuiltFieldDescriptor(
			onSystemChangeField,
			new OnSystemChangeDataFetcher(evita)
		);
	}
}
