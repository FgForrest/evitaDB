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
import graphql.schema.GraphQLObjectType;
import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.externalApi.api.catalog.model.cdc.ChangeCatalogCaptureDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.AttributeElementDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.NameVariantsDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ScopedAttributeUniquenessTypeDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ScopedGlobalAttributeUniquenessTypeDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ScopedReferenceIndexTypeDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.attribute.ReferenceAttributeSchemaMutationInputAggregateDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.LocalEntitySchemaMutationInputAggregateDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.LocalCatalogSchemaMutationInputAggregateDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.LocalCatalogSchemaMutationUnionDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.LocalEntitySchemaMutationUnionDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.sortableAttributeCompound.ReferenceSortableAttributeCompoundSchemaMutationInputAggregateDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.associatedData.CreateAssociatedDataSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.associatedData.ModifyAssociatedDataSchemaDeprecationNoticeMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.associatedData.ModifyAssociatedDataSchemaDescriptionMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.associatedData.ModifyAssociatedDataSchemaNameMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.associatedData.ModifyAssociatedDataSchemaTypeMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.associatedData.RemoveAssociatedDataSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.associatedData.SetAssociatedDataSchemaLocalizedMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.associatedData.SetAssociatedDataSchemaNullableMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.attribute.*;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.catalog.AllowEvolutionModeInCatalogSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.catalog.CreateEntitySchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.catalog.DisallowEvolutionModeInCatalogSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.catalog.ModifyCatalogSchemaDescriptionMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.catalog.ModifyEntitySchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.catalog.ModifyEntitySchemaNameMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.catalog.RemoveEntitySchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.engine.CreateCatalogSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.engine.ModifyCatalogSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.entity.*;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.reference.*;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.sortableAttributeCompound.CreateSortableAttributeCompoundSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.sortableAttributeCompound.ModifySortableAttributeCompoundSchemaDeprecationNoticeMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.sortableAttributeCompound.ModifySortableAttributeCompoundSchemaDescriptionMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.sortableAttributeCompound.ModifySortableAttributeCompoundSchemaNameMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.sortableAttributeCompound.ReferenceSortableAttributeCompoundSchemaMutationUnionDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.sortableAttributeCompound.RemoveSortableAttributeCompoundSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.sortableAttributeCompound.SetSortableAttributeCompoundIndexedMutationDescriptor;
import io.evitadb.externalApi.graphql.api.builder.PartialGraphQLSchemaBuilder;
import io.evitadb.externalApi.graphql.api.catalog.builder.CatalogGraphQLSchemaBuildingContext;
import io.evitadb.externalApi.graphql.api.catalog.schemaApi.resolver.dataFetcher.NameVariantDataFetcher;
import io.evitadb.externalApi.graphql.api.catalog.schemaApi.resolver.subscribingDataFetcher.ChangeCatalogSchemaCaptureUntypedBodyDataFetcher;
import io.evitadb.utils.NamingConvention;

import javax.annotation.Nonnull;

import java.util.Map;

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
		this.buildingContext.registerType(
			ScopedAttributeUniquenessTypeDescriptor.THIS.to(this.objectBuilderTransformer).build());
		this.buildingContext.registerType(ScopedAttributeUniquenessTypeDescriptor.THIS_INPUT.to(
			this.inputObjectBuilderTransformer).build());
		this.buildingContext.registerType(ScopedGlobalAttributeUniquenessTypeDescriptor.THIS.to(
			this.objectBuilderTransformer).build());
		this.buildingContext.registerType(ScopedGlobalAttributeUniquenessTypeDescriptor.THIS_INPUT.to(
			this.inputObjectBuilderTransformer).build());
		this.buildingContext.registerType(ScopedReferenceIndexTypeDescriptor.THIS.to(this.objectBuilderTransformer).build());
		this.buildingContext.registerType(ScopedReferenceIndexTypeDescriptor.THIS_INPUT.to(
			this.inputObjectBuilderTransformer).build());
		this.buildingContext.registerType(AttributeElementDescriptor.THIS.to(this.objectBuilderTransformer).build());
		this.buildingContext.registerType(AttributeElementDescriptor.THIS_INPUT.to(this.inputObjectBuilderTransformer).build());

		buildMutationInterface();
		buildInputMutations();
		buildOutputMutations();

		this.buildingContext.registerType(ChangeCatalogCaptureDescriptor.THIS.to(this.objectBuilderTransformer).build());
		this.buildingContext.registerType(buildGenericChangeCatalogCaptureObject());
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
	private GraphQLObjectType buildGenericChangeCatalogCaptureObject() {
		this.buildingContext.registerDataFetcher(
			ChangeCatalogCaptureDescriptor.THIS_GENERIC,
			ChangeCatalogCaptureDescriptor.BODY_UNTYPED,
			new ChangeCatalogSchemaCaptureUntypedBodyDataFetcher()
		);

		return ChangeCatalogCaptureDescriptor.THIS_GENERIC
			.to(this.objectBuilderTransformer)
			.build();
	}

	private void buildInputMutations() {
		registerInputMutations(
			// entity schema mutations
			AllowCurrencyInEntitySchemaMutationDescriptor.THIS_INPUT,
			AllowEvolutionModeInEntitySchemaMutationDescriptor.THIS_INPUT,
			AllowLocaleInEntitySchemaMutationDescriptor.THIS_INPUT,
			CreateEntitySchemaMutationDescriptor.THIS_INPUT,
			DisallowCurrencyInEntitySchemaMutationDescriptor.THIS_INPUT,
			DisallowEvolutionModeInEntitySchemaMutationDescriptor.THIS_INPUT,
			DisallowLocaleInEntitySchemaMutationDescriptor.THIS_INPUT,
			ModifyEntitySchemaDeprecationNoticeMutationDescriptor.THIS_INPUT,
			ModifyEntitySchemaDescriptionMutationDescriptor.THIS_INPUT,
			ModifyEntitySchemaNameMutationDescriptor.THIS_INPUT,
			RemoveEntitySchemaMutationDescriptor.THIS_INPUT,
			SetEntitySchemaWithGeneratedPrimaryKeyMutationDescriptor.THIS_INPUT,
			SetEntitySchemaWithHierarchyMutationDescriptor.THIS_INPUT,
			SetEntitySchemaWithPriceMutationDescriptor.THIS_INPUT,

			// associated data schema mutations
			CreateAssociatedDataSchemaMutationDescriptor.THIS_INPUT,
			ModifyAssociatedDataSchemaDeprecationNoticeMutationDescriptor.THIS_INPUT,
			ModifyAssociatedDataSchemaDescriptionMutationDescriptor.THIS_INPUT,
			ModifyAssociatedDataSchemaNameMutationDescriptor.THIS_INPUT,
			ModifyAssociatedDataSchemaTypeMutationDescriptor.THIS_INPUT,
			RemoveAssociatedDataSchemaMutationDescriptor.THIS_INPUT,
			SetAssociatedDataSchemaLocalizedMutationDescriptor.THIS_INPUT,
			SetAssociatedDataSchemaNullableMutationDescriptor.THIS_INPUT,

			// attribute schema mutations
			CreateAttributeSchemaMutationDescriptor.THIS_INPUT,
			ModifyAttributeSchemaDefaultValueMutationDescriptor.THIS_INPUT,
			ModifyAttributeSchemaDeprecationNoticeMutationDescriptor.THIS_INPUT,
			ModifyAttributeSchemaDescriptionMutationDescriptor.THIS_INPUT,
			ModifyAttributeSchemaNameMutationDescriptor.THIS_INPUT,
			ModifyAttributeSchemaTypeMutationDescriptor.THIS_INPUT,
			RemoveAttributeSchemaMutationDescriptor.THIS_INPUT,
			SetAttributeSchemaFilterableMutationDescriptor.THIS_INPUT,
			SetAttributeSchemaLocalizedMutationDescriptor.THIS_INPUT,
			SetAttributeSchemaNullableMutationDescriptor.THIS_INPUT,
			SetAttributeSchemaRepresentativeMutationDescriptor.THIS_INPUT,
			SetAttributeSchemaSortableMutationDescriptor.THIS_INPUT,
			UseGlobalAttributeSchemaMutationDescriptor.THIS_INPUT,
			ReferenceAttributeSchemaMutationInputAggregateDescriptor.THIS_INPUT,
			SetAttributeSchemaUniqueMutationDescriptor.THIS_INPUT,

			// sortable attribute compound schema mutations
			CreateSortableAttributeCompoundSchemaMutationDescriptor.THIS_INPUT,
			ModifySortableAttributeCompoundSchemaDeprecationNoticeMutationDescriptor.THIS_INPUT,
			ModifySortableAttributeCompoundSchemaDescriptionMutationDescriptor.THIS_INPUT,
			ModifySortableAttributeCompoundSchemaNameMutationDescriptor.THIS_INPUT,
			SetSortableAttributeCompoundIndexedMutationDescriptor.THIS_INPUT,
			RemoveSortableAttributeCompoundSchemaMutationDescriptor.THIS_INPUT,
			ReferenceSortableAttributeCompoundSchemaMutationInputAggregateDescriptor.THIS_INPUT,

			// reference schema mutations
			CreateReferenceSchemaMutationDescriptor.THIS_INPUT,
			CreateReflectedReferenceSchemaMutationDescriptor.THIS_INPUT,
			ModifyReferenceAttributeSchemaMutationDescriptor.THIS_INPUT,
			ModifyReferenceSchemaCardinalityMutationDescriptor.THIS_INPUT,
			ModifyReferenceSchemaDeprecationNoticeMutationDescriptor.THIS_INPUT,
			ModifyReferenceSchemaDescriptionMutationDescriptor.THIS_INPUT,
			ModifyReferenceSchemaNameMutationDescriptor.THIS_INPUT,
			ModifyReferenceSchemaRelatedEntityGroupMutationDescriptor.THIS_INPUT,
			ModifyReferenceSchemaRelatedEntityMutationDescriptor.THIS_INPUT,
			ModifyReferenceSortableAttributeCompoundSchemaMutationDescriptor.THIS_INPUT,
			ModifyReflectedReferenceAttributeInheritanceSchemaMutationDescriptor.THIS_INPUT,
			RemoveReferenceSchemaMutationDescriptor.THIS_INPUT,
			SetReferenceSchemaFacetedMutationDescriptor.THIS_INPUT,
			SetReferenceSchemaIndexedMutationDescriptor.THIS_INPUT,

			LocalEntitySchemaMutationInputAggregateDescriptor.THIS_INPUT,

			// catalog schema mutations
			ModifyEntitySchemaMutationDescriptor.THIS_INPUT,
			ModifyCatalogSchemaDescriptionMutationDescriptor.THIS_INPUT,
			AllowEvolutionModeInCatalogSchemaMutationDescriptor.THIS_INPUT,
			DisallowEvolutionModeInCatalogSchemaMutationDescriptor.THIS_INPUT,

			// global attribute schema mutations
			CreateGlobalAttributeSchemaMutationDescriptor.THIS_INPUT,
			SetAttributeSchemaGloballyUniqueMutationDescriptor.THIS_INPUT,

			LocalCatalogSchemaMutationInputAggregateDescriptor.THIS_INPUT
		);
	}

	private void buildOutputMutations() {
		final Map<Class<? extends Mutation>, GraphQLObjectType> registeredOutputMutations = registerOutputMutations(
			// catalog schema mutations
			CreateCatalogSchemaMutationDescriptor.THIS,
			ModifyCatalogSchemaMutationDescriptor.THIS,
			CreateEntitySchemaMutationDescriptor.THIS,
			ModifyEntitySchemaMutationDescriptor.THIS,
			RemoveEntitySchemaMutationDescriptor.THIS,
			ModifyCatalogSchemaDescriptionMutationDescriptor.THIS,
			AllowEvolutionModeInCatalogSchemaMutationDescriptor.THIS,
			DisallowEvolutionModeInCatalogSchemaMutationDescriptor.THIS,

			// global attribute schema mutations
			CreateGlobalAttributeSchemaMutationDescriptor.THIS,
			SetAttributeSchemaGloballyUniqueMutationDescriptor.THIS,

			// entity schema mutations
			AllowCurrencyInEntitySchemaMutationDescriptor.THIS,
			AllowEvolutionModeInEntitySchemaMutationDescriptor.THIS,
			AllowLocaleInEntitySchemaMutationDescriptor.THIS,
			DisallowCurrencyInEntitySchemaMutationDescriptor.THIS,
			DisallowEvolutionModeInEntitySchemaMutationDescriptor.THIS,
			DisallowLocaleInEntitySchemaMutationDescriptor.THIS,
			ModifyEntitySchemaDeprecationNoticeMutationDescriptor.THIS,
			ModifyEntitySchemaDescriptionMutationDescriptor.THIS,
			ModifyEntitySchemaNameMutationDescriptor.THIS,
			SetEntitySchemaWithGeneratedPrimaryKeyMutationDescriptor.THIS,
			SetEntitySchemaWithHierarchyMutationDescriptor.THIS,
			SetEntitySchemaWithPriceMutationDescriptor.THIS,

			// associated data schema mutations
			CreateAssociatedDataSchemaMutationDescriptor.THIS,
			ModifyAssociatedDataSchemaDeprecationNoticeMutationDescriptor.THIS,
			ModifyAssociatedDataSchemaDescriptionMutationDescriptor.THIS,
			ModifyAssociatedDataSchemaNameMutationDescriptor.THIS,
			ModifyAssociatedDataSchemaTypeMutationDescriptor.THIS,
			RemoveAssociatedDataSchemaMutationDescriptor.THIS,
			SetAssociatedDataSchemaLocalizedMutationDescriptor.THIS,
			SetAssociatedDataSchemaNullableMutationDescriptor.THIS,

			// attribute schema mutations
			CreateAttributeSchemaMutationDescriptor.THIS,
			ModifyAttributeSchemaDefaultValueMutationDescriptor.THIS,
			ModifyAttributeSchemaDeprecationNoticeMutationDescriptor.THIS,
			ModifyAttributeSchemaDescriptionMutationDescriptor.THIS,
			ModifyAttributeSchemaNameMutationDescriptor.THIS,
			ModifyAttributeSchemaTypeMutationDescriptor.THIS,
			RemoveAttributeSchemaMutationDescriptor.THIS,
			SetAttributeSchemaFilterableMutationDescriptor.THIS,
			SetAttributeSchemaLocalizedMutationDescriptor.THIS,
			SetAttributeSchemaNullableMutationDescriptor.THIS,
			SetAttributeSchemaRepresentativeMutationDescriptor.THIS,
			SetAttributeSchemaSortableMutationDescriptor.THIS,
			UseGlobalAttributeSchemaMutationDescriptor.THIS,
			SetAttributeSchemaUniqueMutationDescriptor.THIS,

			// sortable attribute compound schema mutations
			CreateSortableAttributeCompoundSchemaMutationDescriptor.THIS,
			ModifySortableAttributeCompoundSchemaDeprecationNoticeMutationDescriptor.THIS,
			ModifySortableAttributeCompoundSchemaDescriptionMutationDescriptor.THIS,
			ModifySortableAttributeCompoundSchemaNameMutationDescriptor.THIS,
			SetSortableAttributeCompoundIndexedMutationDescriptor.THIS,
			RemoveSortableAttributeCompoundSchemaMutationDescriptor.THIS,

			// reference schema mutations
			CreateReferenceSchemaMutationDescriptor.THIS,
			CreateReflectedReferenceSchemaMutationDescriptor.THIS,
			ModifyReferenceAttributeSchemaMutationDescriptor.THIS,
			ModifyReferenceSchemaCardinalityMutationDescriptor.THIS,
			ModifyReferenceSchemaDeprecationNoticeMutationDescriptor.THIS,
			ModifyReferenceSchemaDescriptionMutationDescriptor.THIS,
			ModifyReferenceSchemaNameMutationDescriptor.THIS,
			ModifyReferenceSchemaRelatedEntityGroupMutationDescriptor.THIS,
			ModifyReferenceSchemaRelatedEntityMutationDescriptor.THIS,
			ModifyReferenceSortableAttributeCompoundSchemaMutationDescriptor.THIS,
			ModifyReflectedReferenceAttributeInheritanceSchemaMutationDescriptor.THIS,
			RemoveReferenceSchemaMutationDescriptor.THIS,
			SetReferenceSchemaFacetedMutationDescriptor.THIS,
			SetReferenceSchemaIndexedMutationDescriptor.THIS
		);

		registerMutationUnion(LocalCatalogSchemaMutationUnionDescriptor.THIS, registeredOutputMutations);
		registerMutationUnion(LocalEntitySchemaMutationUnionDescriptor.THIS, registeredOutputMutations);
		registerMutationUnion(ReferenceAttributeSchemaMutationUnionDescriptor.THIS, registeredOutputMutations);
		registerMutationUnion(ReferenceSortableAttributeCompoundSchemaMutationUnionDescriptor.THIS, registeredOutputMutations);
	}
}
