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
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.associatedData.RemoveAssociatedDataMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.associatedData.UpsertAssociatedDataMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.attribute.ApplyDeltaAttributeMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.attribute.RemoveAttributeMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.attribute.UpsertAttributeMutationDescriptor;
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
import io.evitadb.externalApi.api.catalog.schemaApi.model.AttributeElementDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.NameVariantsDescriptor;
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
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.entity.*;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.reference.*;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.sortableAttributeCompound.CreateSortableAttributeCompoundSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.sortableAttributeCompound.ModifySortableAttributeCompoundSchemaDeprecationNoticeMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.sortableAttributeCompound.ModifySortableAttributeCompoundSchemaDescriptionMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.sortableAttributeCompound.ModifySortableAttributeCompoundSchemaNameMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.sortableAttributeCompound.RemoveSortableAttributeCompoundSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.sortableAttributeCompound.SetSortableAttributeCompoundIndexedMutationDescriptor;
import io.evitadb.externalApi.api.system.model.CatalogDescriptor;
import io.evitadb.externalApi.api.system.model.CatalogUnionDescriptor;
import io.evitadb.externalApi.api.system.model.UnusableCatalogDescriptor;
import io.evitadb.externalApi.configuration.HeaderOptions;
import io.evitadb.externalApi.rest.api.Rest;
import io.evitadb.externalApi.rest.api.builder.FinalRestBuilder;
import io.evitadb.externalApi.rest.api.model.ErrorDescriptor;
import io.evitadb.externalApi.rest.api.openApi.OpenApiObjectUnionType;
import io.evitadb.externalApi.rest.api.openApi.OpenApiUnion;
import io.evitadb.externalApi.rest.api.system.builder.SystemEndpointBuilder;
import io.evitadb.externalApi.rest.api.system.builder.SystemRestBuildingContext;
import io.evitadb.externalApi.rest.api.system.model.CreateCatalogRequestDescriptor;
import io.evitadb.externalApi.rest.api.system.model.LivenessDescriptor;
import io.evitadb.externalApi.rest.api.system.model.UpdateCatalogRequestDescriptor;
import io.evitadb.externalApi.rest.configuration.RestOptions;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;

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
		this.buildingContext.registerType(ErrorDescriptor.THIS.to(this.objectBuilderTransformer).build());
		this.buildingContext.registerType(LivenessDescriptor.THIS.to(this.objectBuilderTransformer).build());
		this.buildingContext.registerType(NameVariantsDescriptor.THIS.to(this.objectBuilderTransformer).build());
		this.buildingContext.registerType(CatalogDescriptor.THIS.to(this.objectBuilderTransformer).build());
		this.buildingContext.registerType(buildCatalogUnion());
		this.buildingContext.registerType(UnusableCatalogDescriptor.THIS.to(this.objectBuilderTransformer).build());
		this.buildingContext.registerType(CreateCatalogRequestDescriptor.THIS.to(this.objectBuilderTransformer).build());
		this.buildingContext.registerType(UpdateCatalogRequestDescriptor.THIS.to(this.objectBuilderTransformer).build());

		// todo lho union instead of interface?
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
			.object(typeRefTo(CatalogDescriptor.THIS.name()))
			.object(typeRefTo(UnusableCatalogDescriptor.THIS.name()))
			.build();
	}

	private void buildOutputMutations() {
		registerMutations(
			// schema mutations

			// entity schema mutations
			AllowCurrencyInEntitySchemaMutationDescriptor.THIS,
			AllowEvolutionModeInEntitySchemaMutationDescriptor.THIS,
			AllowLocaleInEntitySchemaMutationDescriptor.THIS,
			CreateEntitySchemaMutationDescriptor.THIS,
			DisallowCurrencyInEntitySchemaMutationDescriptor.THIS,
			DisallowEvolutionModeInEntitySchemaMutationDescriptor.THIS,
			DisallowLocaleInEntitySchemaMutationDescriptor.THIS,
			ModifyEntitySchemaDeprecationNoticeMutationDescriptor.THIS,
			ModifyEntitySchemaDescriptionMutationDescriptor.THIS,
			ModifyEntitySchemaNameMutationDescriptor.THIS,
			RemoveEntitySchemaMutationDescriptor.THIS,
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
			AttributeElementDescriptor.THIS_INPUT,
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
			ModifyReflectedReferenceAttributeInheritanceSchemaMutationDescriptor.THIS,
			RemoveReferenceSchemaMutationDescriptor.THIS,
			SetReferenceSchemaFacetedMutationDescriptor.THIS,
			SetReferenceSchemaIndexedMutationDescriptor.THIS,

			// catalog schema mutations
			ModifyEntitySchemaMutationDescriptor.THIS,
			ModifyCatalogSchemaDescriptionMutationDescriptor.THIS,
			AllowEvolutionModeInCatalogSchemaMutationDescriptor.THIS,
			DisallowEvolutionModeInCatalogSchemaMutationDescriptor.THIS,

			// global attribute schema mutations
			CreateGlobalAttributeSchemaMutationDescriptor.THIS,
			SetAttributeSchemaGloballyUniqueMutationDescriptor.THIS,

			// data mutations

			SetEntityScopeMutationDescriptor.THIS,
			RemoveAssociatedDataMutationDescriptor.THIS,
			UpsertAssociatedDataMutationDescriptor.THIS,
			ApplyDeltaAttributeMutationDescriptor.THIS,
			RemoveAttributeMutationDescriptor.THIS,
			UpsertAttributeMutationDescriptor.THIS,
			SetParentMutationDescriptor.THIS,
			SetPriceInnerRecordHandlingMutationDescriptor.THIS,
			RemovePriceMutationDescriptor.THIS,
			UpsertPriceMutationDescriptor.THIS,
			InsertReferenceMutationDescriptor.THIS,
			RemoveReferenceMutationDescriptor.THIS,
			SetReferenceGroupMutationDescriptor.THIS,
			RemoveReferenceGroupMutationDescriptor.THIS,
			ReferenceAttributeMutationDescriptor.THIS
		);

		// todo lho unions?
	}
}
