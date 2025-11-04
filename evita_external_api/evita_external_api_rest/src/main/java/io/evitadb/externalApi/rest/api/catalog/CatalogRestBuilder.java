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
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.EntityRemoveMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.EntityUpsertMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.LocalMutationUnionDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.associatedData.RemoveAssociatedDataMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.associatedData.UpsertAssociatedDataMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.attribute.ApplyDeltaAttributeMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.attribute.AttributeMutationUnionDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.attribute.AttributeMutationInputAggregateDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.attribute.RemoveAttributeMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.attribute.UpsertAttributeMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.entity.RemoveParentMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.entity.SetEntityScopeMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.entity.SetParentMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.price.RemovePriceMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.price.SetPriceInnerRecordHandlingMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.price.UpsertPriceMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.reference.InsertReferenceMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.reference.ReferenceAttributeMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.reference.RemoveReferenceGroupMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.reference.RemoveReferenceMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.reference.SetReferenceGroupMutationDescriptor;
import io.evitadb.externalApi.api.catalog.model.cdc.ChangeCatalogCaptureDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.attribute.ReferenceAttributeSchemaMutationInputAggregateDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.LocalEntitySchemaMutationInputAggregateDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.LocalCatalogSchemaMutationInputAggregateDescriptor;
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
import io.evitadb.externalApi.api.model.mutation.MutationDescriptor;
import io.evitadb.externalApi.api.transaction.model.mutation.TransactionMutationDescriptor;
import io.evitadb.externalApi.configuration.HeaderOptions;
import io.evitadb.externalApi.rest.api.Rest;
import io.evitadb.externalApi.rest.api.builder.FinalRestBuilder;
import io.evitadb.externalApi.rest.api.catalog.builder.CatalogEndpointBuilder;
import io.evitadb.externalApi.rest.api.catalog.builder.CatalogRestBuildingContext;
import io.evitadb.externalApi.rest.api.catalog.cdcApi.CatalogCdcApiRestBuilder;
import io.evitadb.externalApi.rest.api.catalog.dataApi.CatalogDataApiRestBuilder;
import io.evitadb.externalApi.rest.api.catalog.schemaApi.CatalogSchemaApiRestBuilder;
import io.evitadb.externalApi.rest.api.model.ErrorDescriptor;
import io.evitadb.externalApi.rest.configuration.RestOptions;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;

import static io.evitadb.externalApi.rest.api.openApi.OpenApiTypeReference.typeRefTo;

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
		new CatalogCdcApiRestBuilder(this.buildingContext).build();

		return this.buildingContext.buildRest();
	}

	private void buildCommonTypes() {
		this.buildingContext.registerType(buildScalarEnum());
		this.buildingContext.registerType(ErrorDescriptor.THIS.to(this.objectBuilderTransformer).build());
		this.buildingContext.registerType(ChangeCatalogCaptureDescriptor.THIS.to(this.objectBuilderTransformer).build());
		buildMutationInterface();
		buildInputMutations();
		buildOutputMutations();
	}

	private void buildEndpoints() {
		this.buildingContext.registerEndpoint(this.endpointBuilder.buildOpenApiSpecificationEndpoint(this.buildingContext));
	}

	private void buildMutationInterface() {
		this.buildingContext.registerType(
			MutationDescriptor.THIS_INTERFACE.to(this.interfaceBuilderTransformer)
				.discriminator(MutationDescriptor.MUTATION_TYPE)
				.implementingTypes(
					// infrastructure mutations

					typeRefTo(TransactionMutationDescriptor.THIS.name()),

					// schema mutations

					// catalog schema mutations
					typeRefTo(ModifyCatalogSchemaMutationDescriptor.THIS.name()),
					typeRefTo(ModifyEntitySchemaMutationDescriptor.THIS.name()),
					typeRefTo(ModifyCatalogSchemaDescriptionMutationDescriptor.THIS.name()),
					typeRefTo(AllowEvolutionModeInCatalogSchemaMutationDescriptor.THIS.name()),
					typeRefTo(DisallowEvolutionModeInCatalogSchemaMutationDescriptor.THIS.name()),

					// global attribute schema mutations
					typeRefTo(CreateGlobalAttributeSchemaMutationDescriptor.THIS.name()),
					typeRefTo(SetAttributeSchemaGloballyUniqueMutationDescriptor.THIS.name()),

					// entity schema mutations
					typeRefTo(AllowCurrencyInEntitySchemaMutationDescriptor.THIS.name()),
					typeRefTo(AllowEvolutionModeInEntitySchemaMutationDescriptor.THIS.name()),
					typeRefTo(AllowLocaleInEntitySchemaMutationDescriptor.THIS.name()),
					typeRefTo(CreateEntitySchemaMutationDescriptor.THIS.name()),
					typeRefTo(DisallowCurrencyInEntitySchemaMutationDescriptor.THIS.name()),
					typeRefTo(DisallowEvolutionModeInEntitySchemaMutationDescriptor.THIS.name()),
					typeRefTo(DisallowLocaleInEntitySchemaMutationDescriptor.THIS.name()),
					typeRefTo(ModifyEntitySchemaDeprecationNoticeMutationDescriptor.THIS.name()),
					typeRefTo(ModifyEntitySchemaDescriptionMutationDescriptor.THIS.name()),
					typeRefTo(ModifyEntitySchemaNameMutationDescriptor.THIS.name()),
					typeRefTo(RemoveEntitySchemaMutationDescriptor.THIS.name()),
					typeRefTo(SetEntitySchemaWithGeneratedPrimaryKeyMutationDescriptor.THIS.name()),
					typeRefTo(SetEntitySchemaWithHierarchyMutationDescriptor.THIS.name()),
					typeRefTo(SetEntitySchemaWithPriceMutationDescriptor.THIS.name()),

					// associated data schema mutations
					typeRefTo(CreateAssociatedDataSchemaMutationDescriptor.THIS.name()),
					typeRefTo(ModifyAssociatedDataSchemaDeprecationNoticeMutationDescriptor.THIS.name()),
					typeRefTo(ModifyAssociatedDataSchemaDescriptionMutationDescriptor.THIS.name()),
					typeRefTo(ModifyAssociatedDataSchemaNameMutationDescriptor.THIS.name()),
					typeRefTo(ModifyAssociatedDataSchemaTypeMutationDescriptor.THIS.name()),
					typeRefTo(RemoveAssociatedDataSchemaMutationDescriptor.THIS.name()),
					typeRefTo(SetAssociatedDataSchemaLocalizedMutationDescriptor.THIS.name()),
					typeRefTo(SetAssociatedDataSchemaNullableMutationDescriptor.THIS.name()),

					// attribute schema mutations
					typeRefTo(CreateAttributeSchemaMutationDescriptor.THIS.name()),
					typeRefTo(ModifyAttributeSchemaDefaultValueMutationDescriptor.THIS.name()),
					typeRefTo(ModifyAttributeSchemaDeprecationNoticeMutationDescriptor.THIS.name()),
					typeRefTo(ModifyAttributeSchemaDescriptionMutationDescriptor.THIS.name()),
					typeRefTo(ModifyAttributeSchemaNameMutationDescriptor.THIS.name()),
					typeRefTo(ModifyAttributeSchemaTypeMutationDescriptor.THIS.name()),
					typeRefTo(RemoveAttributeSchemaMutationDescriptor.THIS.name()),
					typeRefTo(SetAttributeSchemaFilterableMutationDescriptor.THIS.name()),
					typeRefTo(SetAttributeSchemaLocalizedMutationDescriptor.THIS.name()),
					typeRefTo(SetAttributeSchemaNullableMutationDescriptor.THIS.name()),
					typeRefTo(SetAttributeSchemaRepresentativeMutationDescriptor.THIS.name()),
					typeRefTo(SetAttributeSchemaSortableMutationDescriptor.THIS.name()),
					typeRefTo(SetAttributeSchemaUniqueMutationDescriptor.THIS.name()),
					typeRefTo(UseGlobalAttributeSchemaMutationDescriptor.THIS.name()),

					// sortable attribute compound schema mutations
					typeRefTo(CreateSortableAttributeCompoundSchemaMutationDescriptor.THIS.name()),
					typeRefTo(ModifySortableAttributeCompoundSchemaDeprecationNoticeMutationDescriptor.THIS.name()),
					typeRefTo(ModifySortableAttributeCompoundSchemaDescriptionMutationDescriptor.THIS.name()),
					typeRefTo(ModifySortableAttributeCompoundSchemaNameMutationDescriptor.THIS.name()),
					typeRefTo(SetSortableAttributeCompoundIndexedMutationDescriptor.THIS.name()),
					typeRefTo(RemoveSortableAttributeCompoundSchemaMutationDescriptor.THIS.name()),

					// reference schema mutations
					typeRefTo(CreateReferenceSchemaMutationDescriptor.THIS.name()),
					typeRefTo(CreateReflectedReferenceSchemaMutationDescriptor.THIS.name()),
					typeRefTo(ModifyReferenceAttributeSchemaMutationDescriptor.THIS.name()),
					typeRefTo(ModifyReferenceSchemaCardinalityMutationDescriptor.THIS.name()),
					typeRefTo(ModifyReferenceSchemaDeprecationNoticeMutationDescriptor.THIS.name()),
					typeRefTo(ModifyReferenceSchemaDescriptionMutationDescriptor.THIS.name()),
					typeRefTo(ModifyReferenceSchemaNameMutationDescriptor.THIS.name()),
					typeRefTo(ModifyReferenceSchemaRelatedEntityGroupMutationDescriptor.THIS.name()),
					typeRefTo(ModifyReferenceSchemaRelatedEntityMutationDescriptor.THIS.name()),
					typeRefTo(ModifyReflectedReferenceAttributeInheritanceSchemaMutationDescriptor.THIS.name()),
					typeRefTo(RemoveReferenceSchemaMutationDescriptor.THIS.name()),
					typeRefTo(SetReferenceSchemaFacetedMutationDescriptor.THIS.name()),
					typeRefTo(SetReferenceSchemaIndexedMutationDescriptor.THIS.name()),

					// data mutations

					typeRefTo(SetEntityScopeMutationDescriptor.THIS.name()),
					typeRefTo(RemoveAssociatedDataMutationDescriptor.THIS.name()),
					typeRefTo(UpsertAssociatedDataMutationDescriptor.THIS.name()),
					typeRefTo(ApplyDeltaAttributeMutationDescriptor.THIS.name()),
					typeRefTo(RemoveAttributeMutationDescriptor.THIS.name()),
					typeRefTo(UpsertAttributeMutationDescriptor.THIS.name()),
					typeRefTo(SetParentMutationDescriptor.THIS.name()),
					typeRefTo(SetPriceInnerRecordHandlingMutationDescriptor.THIS.name()),
					typeRefTo(RemovePriceMutationDescriptor.THIS.name()),
					typeRefTo(UpsertPriceMutationDescriptor.THIS.name()),
					typeRefTo(InsertReferenceMutationDescriptor.THIS.name()),
					typeRefTo(RemoveReferenceMutationDescriptor.THIS.name()),
					typeRefTo(SetReferenceGroupMutationDescriptor.THIS.name()),
					typeRefTo(RemoveReferenceGroupMutationDescriptor.THIS.name()),
					typeRefTo(ReferenceAttributeMutationDescriptor.THIS.name()),
					typeRefTo(EntityUpsertMutationDescriptor.THIS.name()),
					typeRefTo(EntityRemoveMutationDescriptor.THIS.name())
				)
				.build()
		);
	}

	private void buildInputMutations() {
		registerMutations(
			SetEntityScopeMutationDescriptor.THIS_INPUT,
			RemoveAssociatedDataMutationDescriptor.THIS_INPUT,
			UpsertAssociatedDataMutationDescriptor.THIS_INPUT,
			ApplyDeltaAttributeMutationDescriptor.THIS_INPUT,
			RemoveAttributeMutationDescriptor.THIS_INPUT,
			UpsertAttributeMutationDescriptor.THIS_INPUT,
			SetParentMutationDescriptor.THIS_INPUT,
			SetPriceInnerRecordHandlingMutationDescriptor.THIS_INPUT,
			RemovePriceMutationDescriptor.THIS_INPUT,
			UpsertPriceMutationDescriptor.THIS_INPUT,
			InsertReferenceMutationDescriptor.THIS_INPUT,
			RemoveReferenceMutationDescriptor.THIS_INPUT,
			SetReferenceGroupMutationDescriptor.THIS_INPUT,
			RemoveReferenceGroupMutationDescriptor.THIS_INPUT,
			ReferenceAttributeMutationDescriptor.THIS_INPUT,
			AttributeMutationInputAggregateDescriptor.THIS_INPUT
		);

		registerMutations(
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
			SetAttributeSchemaUniqueMutationDescriptor.THIS_INPUT,
			UseGlobalAttributeSchemaMutationDescriptor.THIS_INPUT,
			ReferenceAttributeSchemaMutationInputAggregateDescriptor.THIS_INPUT,

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
		registerMutations(
			RemoveAssociatedDataMutationDescriptor.THIS,
			UpsertAssociatedDataMutationDescriptor.THIS,
			ApplyDeltaAttributeMutationDescriptor.THIS,
			RemoveAttributeMutationDescriptor.THIS,
			UpsertAttributeMutationDescriptor.THIS,
			RemoveParentMutationDescriptor.THIS,
			SetParentMutationDescriptor.THIS,
			SetEntityScopeMutationDescriptor.THIS,
			SetPriceInnerRecordHandlingMutationDescriptor.THIS,
			RemovePriceMutationDescriptor.THIS,
			UpsertPriceMutationDescriptor.THIS,
			InsertReferenceMutationDescriptor.THIS,
			RemoveReferenceMutationDescriptor.THIS,
			SetReferenceGroupMutationDescriptor.THIS,
			RemoveReferenceGroupMutationDescriptor.THIS,
			ReferenceAttributeMutationDescriptor.THIS,
			EntityUpsertMutationDescriptor.THIS,
			EntityRemoveMutationDescriptor.THIS
		);

		this.buildingContext.registerType(AttributeMutationUnionDescriptor.THIS.to(this.unionBuilderTransformer).build());
		this.buildingContext.registerType(LocalMutationUnionDescriptor.THIS.to(this.unionBuilderTransformer).build());

		registerMutations(
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
			SetAttributeSchemaUniqueMutationDescriptor.THIS,
			UseGlobalAttributeSchemaMutationDescriptor.THIS,

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

		this.buildingContext.registerType(
			LocalEntitySchemaMutationUnionDescriptor.THIS.to(this.unionBuilderTransformer).build());
		this.buildingContext.registerType(ReferenceAttributeSchemaMutationUnionDescriptor.THIS.to(this.unionBuilderTransformer).build());
		this.buildingContext.registerType(
			ReferenceSortableAttributeCompoundSchemaMutationUnionDescriptor.THIS.to(this.unionBuilderTransformer).build());
	}

}
