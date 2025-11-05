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

package io.evitadb.externalApi.rest.api.system;

import io.evitadb.core.Evita;
import io.evitadb.dataType.ComplexDataObject;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.EntityRemoveMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.EntityUpsertMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.LocalMutationUnionDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.associatedData.RemoveAssociatedDataMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.associatedData.UpsertAssociatedDataMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.attribute.ApplyDeltaAttributeMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.attribute.AttributeMutationUnionDescriptor;
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
import io.evitadb.externalApi.api.catalog.schemaApi.model.AttributeElementDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.NameVariantsDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.LocalCatalogSchemaMutationUnionDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.LocalEntitySchemaMutationUnionDescriptor;
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
import io.evitadb.externalApi.api.system.model.CatalogDescriptor;
import io.evitadb.externalApi.api.system.model.CatalogUnionDescriptor;
import io.evitadb.externalApi.api.system.model.UnusableCatalogDescriptor;
import io.evitadb.externalApi.api.system.model.cdc.ChangeSystemCaptureDescriptor;
import io.evitadb.externalApi.api.transaction.model.mutation.TransactionMutationDescriptor;
import io.evitadb.externalApi.configuration.HeaderOptions;
import io.evitadb.externalApi.dataType.DataTypeSerializer;
import io.evitadb.externalApi.rest.api.Rest;
import io.evitadb.externalApi.rest.api.builder.FinalRestBuilder;
import io.evitadb.externalApi.rest.api.model.ErrorDescriptor;
import io.evitadb.externalApi.rest.api.openApi.OpenApiEnum;
import io.evitadb.externalApi.rest.api.openApi.OpenApiUnion;
import io.evitadb.externalApi.rest.api.system.builder.SystemEndpointBuilder;
import io.evitadb.externalApi.rest.api.system.builder.SystemRestBuildingContext;
import io.evitadb.externalApi.rest.api.system.model.ChangeSystemCaptureRequestDescriptor;
import io.evitadb.externalApi.rest.api.system.model.CreateCatalogRequestDescriptor;
import io.evitadb.externalApi.rest.api.system.model.LivenessDescriptor;
import io.evitadb.externalApi.rest.api.system.model.UpdateCatalogRequestDescriptor;
import io.evitadb.externalApi.rest.configuration.RestOptions;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;

import static io.evitadb.externalApi.api.catalog.model.CatalogRootDescriptor.ASSOCIATED_DATA_SCALAR_ENUM;
import static io.evitadb.externalApi.rest.api.openApi.OpenApiEnum.newEnum;
import static io.evitadb.externalApi.rest.api.openApi.OpenApiTypeReference.typeRefTo;

/**
 * Creates OpenAPI specification for evitaDB management.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@Slf4j
public class SystemRestBuilder extends FinalRestBuilder<SystemRestBuildingContext> {

	@Nonnull private final SystemEndpointBuilder endpointBuilder;

	/**
	 * Creates new builder.
	 */
	public SystemRestBuilder(
		@Nonnull RestOptions restConfig,
		@Nonnull HeaderOptions headerOptions,
		@Nonnull Evita evita
	) {
		super(new SystemRestBuildingContext(restConfig, headerOptions, evita));
		this.endpointBuilder = new SystemEndpointBuilder(this.operationPathParameterBuilderTransformer);
	}

	/**
	 * Builds OpenAPI specification for evitaDB management.
	 *
	 * @return OpenAPI specification
	 */
	@Nonnull
	public Rest build() {
		buildCommonTypes();
		buildEndpoints();

		return this.buildingContext.buildRest();
	}

	private void buildCommonTypes() {
		this.buildingContext.registerType(buildScalarEnum());
		this.buildingContext.registerType(buildAssociatedDataScalarEnum());

		this.buildingContext.registerType(ErrorDescriptor.THIS.to(this.objectBuilderTransformer).build());
		this.buildingContext.registerType(LivenessDescriptor.THIS.to(this.objectBuilderTransformer).build());
		this.buildingContext.registerType(NameVariantsDescriptor.THIS.to(this.objectBuilderTransformer).build());
		this.buildingContext.registerType(CatalogDescriptor.THIS.to(this.objectBuilderTransformer).build());
		this.buildingContext.registerType(buildCatalogUnion());
		this.buildingContext.registerType(UnusableCatalogDescriptor.THIS.to(this.objectBuilderTransformer).build());
		this.buildingContext.registerType(CreateCatalogRequestDescriptor.THIS.to(this.objectBuilderTransformer).build());
		this.buildingContext.registerType(UpdateCatalogRequestDescriptor.THIS.to(this.objectBuilderTransformer).build());
		this.buildingContext.registerType(AttributeElementDescriptor.THIS_INPUT.to(this.objectBuilderTransformer).build());

		// these objects are not used by the endpoints directly, but are used within the WebSocket protocol for CDC streams,
		// which we currently cannot specify in the OpenAPI specification. So we at least provide the object documention
		// for client developers.
		this.buildingContext.registerType(ChangeSystemCaptureRequestDescriptor.THIS.to(this.objectBuilderTransformer).build());
		this.buildingContext.registerType(ChangeSystemCaptureDescriptor.THIS.to(this.objectBuilderTransformer).build());
		buildMutationInterface();
		buildOutputMutations();
	}

	private void buildEndpoints() {
		this.buildingContext.registerEndpoint(this.endpointBuilder.buildOpenApiSpecificationEndpoint());
		this.buildingContext.registerEndpoint(this.endpointBuilder.buildLivenessEndpoint());
		this.buildingContext.registerEndpoint(this.endpointBuilder.buildGetCatalogEndpoint());
		this.buildingContext.registerEndpoint(this.endpointBuilder.buildListCatalogsEndpoint());
		this.buildingContext.registerEndpoint(this.endpointBuilder.buildCreateCatalogEndpoint());
		this.buildingContext.registerEndpoint(this.endpointBuilder.buildUpdateCatalogEndpoint());
		this.buildingContext.registerEndpoint(this.endpointBuilder.buildDeleteCatalogEndpoint());
		this.buildingContext.registerEndpoint(this.endpointBuilder.buildChangeSystemCaptureEndpoint());
	}

	@Nonnull
	private OpenApiUnion buildCatalogUnion() {
		return CatalogUnionDescriptor.THIS
			.to(this.unionBuilderTransformer)
			.type(typeRefTo(CatalogDescriptor.THIS.name()))
			.type(typeRefTo(UnusableCatalogDescriptor.THIS.name()))
			.build();
	}

	@Nonnull
	private static OpenApiEnum buildAssociatedDataScalarEnum() {
		return newEnum(buildScalarEnum())
			.name(ASSOCIATED_DATA_SCALAR_ENUM.name())
			.description(ASSOCIATED_DATA_SCALAR_ENUM.description())
			.item(DataTypeSerializer.serialize(ComplexDataObject.class))
			.build();
	}

	private void buildOutputMutations() {
		registerMutations(
			// infrastructure mutations

			TransactionMutationDescriptor.THIS,

			// schema mutations

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
			SetReferenceSchemaIndexedMutationDescriptor.THIS,

			// catalog schema mutations
			CreateCatalogSchemaMutationDescriptor.THIS,
			CreateEntitySchemaMutationDescriptor.THIS,
			ModifyEntitySchemaMutationDescriptor.THIS,
			RemoveEntitySchemaMutationDescriptor.THIS,
			ModifyCatalogSchemaDescriptionMutationDescriptor.THIS,
			AllowEvolutionModeInCatalogSchemaMutationDescriptor.THIS,
			DisallowEvolutionModeInCatalogSchemaMutationDescriptor.THIS,

			// global attribute schema mutations
			CreateGlobalAttributeSchemaMutationDescriptor.THIS,
			SetAttributeSchemaGloballyUniqueMutationDescriptor.THIS,

			// data mutations

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

		this.buildingContext.registerType(LocalEntitySchemaMutationUnionDescriptor.THIS.to(this.unionBuilderTransformer).build());
		this.buildingContext.registerType(ReferenceAttributeSchemaMutationUnionDescriptor.THIS.to(this.unionBuilderTransformer).build());
		this.buildingContext.registerType(ReferenceSortableAttributeCompoundSchemaMutationUnionDescriptor.THIS.to(this.unionBuilderTransformer).build());
		this.buildingContext.registerType(LocalCatalogSchemaMutationUnionDescriptor.THIS.to(this.unionBuilderTransformer).build());

		this.buildingContext.registerType(AttributeMutationUnionDescriptor.THIS.to(this.unionBuilderTransformer).build());
		this.buildingContext.registerType(LocalMutationUnionDescriptor.THIS.to(this.unionBuilderTransformer).build());
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
}
