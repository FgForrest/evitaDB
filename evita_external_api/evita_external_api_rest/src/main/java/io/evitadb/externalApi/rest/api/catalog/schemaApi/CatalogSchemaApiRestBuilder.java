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

package io.evitadb.externalApi.rest.api.catalog.schemaApi;

import io.evitadb.externalApi.api.catalog.schemaApi.model.NameVariantsDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.AttributeSchemaMutationInputAggregateDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.EntitySchemaMutationInputAggregateDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.LocalCatalogSchemaMutationInputAggregateDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.SortableAttributeCompoundSchemaMutationInputAggregateDescriptor;
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
import io.evitadb.externalApi.rest.api.builder.PartialRestBuilder;
import io.evitadb.externalApi.rest.api.catalog.builder.CatalogRestBuildingContext;
import io.evitadb.externalApi.rest.api.catalog.schemaApi.builder.CatalogSchemaObjectBuilder;
import io.evitadb.externalApi.rest.api.catalog.schemaApi.builder.EntitySchemaObjectBuilder;
import io.evitadb.externalApi.rest.api.catalog.schemaApi.builder.SchemaApiEndpointBuilder;
import io.evitadb.externalApi.rest.api.catalog.schemaApi.model.UpdateCatalogSchemaRequestDescriptor;
import io.evitadb.externalApi.rest.api.catalog.schemaApi.model.UpdateEntitySchemaRequestDescriptor;

import javax.annotation.Nonnull;

/**
 * Builds schema API part of catalog's REST API. Building of whole REST API is handled by {@link io.evitadb.externalApi.rest.api.catalog.CatalogRestBuilder}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class CatalogSchemaApiRestBuilder extends PartialRestBuilder<CatalogRestBuildingContext> {

	@Nonnull private final SchemaApiEndpointBuilder endpointBuilder;
	@Nonnull private final EntitySchemaObjectBuilder entitySchemaObjectBuilder;
	@Nonnull private final CatalogSchemaObjectBuilder catalogSchemaObjectBuilder;

	public CatalogSchemaApiRestBuilder(@Nonnull CatalogRestBuildingContext buildingContext) {
		super(buildingContext);

		this.endpointBuilder = new SchemaApiEndpointBuilder();
		this.entitySchemaObjectBuilder = new EntitySchemaObjectBuilder(
			buildingContext,
			this.objectBuilderTransformer,
			this.propertyBuilderTransformer
		);
		this.catalogSchemaObjectBuilder = new CatalogSchemaObjectBuilder(
			buildingContext,
			this.objectBuilderTransformer,
			this.propertyBuilderTransformer
		);
	}

	@Override
	public void build() {
		buildCommonTypes();
		buildEndpoints();
	}

	private void buildCommonTypes() {
		this.buildingContext.registerType(NameVariantsDescriptor.THIS.to(this.objectBuilderTransformer).build());
		this.buildingContext.registerType(UpdateEntitySchemaRequestDescriptor.THIS.to(this.objectBuilderTransformer).build());
		this.buildingContext.registerType(UpdateCatalogSchemaRequestDescriptor.THIS.to(this.objectBuilderTransformer).build());

		buildInputMutations();
		buildOutputMutations();

		this.entitySchemaObjectBuilder.buildCommonTypes();
	}

	private void buildEndpoints() {
		this.buildingContext.getEntitySchemas().forEach(entitySchema -> {
			this.entitySchemaObjectBuilder.build(entitySchema);
			this.buildingContext.registerEndpoint(this.endpointBuilder.buildGetEntitySchemaEndpoint(this.buildingContext.getSchema(), entitySchema));
			this.buildingContext.registerEndpoint(this.endpointBuilder.buildUpdateEntitySchemaEndpoint(this.buildingContext.getSchema(), entitySchema));
		});

		this.catalogSchemaObjectBuilder.build();
		this.buildingContext.registerEndpoint(this.endpointBuilder.buildGetCatalogSchemaEndpoint(this.buildingContext.getSchema()));
		this.buildingContext.registerEndpoint(this.endpointBuilder.buildUpdateCatalogSchemaEndpoint(this.buildingContext.getSchema()));
	}

	private void buildInputMutations() {
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
			AttributeSchemaMutationInputAggregateDescriptor.THIS_INPUT,

			// sortable attribute compound schema mutations
			CreateSortableAttributeCompoundSchemaMutationDescriptor.THIS_INPUT,
			ModifySortableAttributeCompoundSchemaDeprecationNoticeMutationDescriptor.THIS_INPUT,
			ModifySortableAttributeCompoundSchemaDescriptionMutationDescriptor.THIS_INPUT,
			ModifySortableAttributeCompoundSchemaNameMutationDescriptor.THIS_INPUT,
			SetSortableAttributeCompoundIndexedMutationDescriptor.THIS_INPUT,
			RemoveSortableAttributeCompoundSchemaMutationDescriptor.THIS_INPUT,
			SortableAttributeCompoundSchemaMutationInputAggregateDescriptor.THIS_INPUT,

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
			ModifyReflectedReferenceAttributeInheritanceSchemaMutationDescriptor.THIS_INPUT,
			RemoveReferenceSchemaMutationDescriptor.THIS_INPUT,
			SetReferenceSchemaFacetedMutationDescriptor.THIS_INPUT,
			SetReferenceSchemaIndexedMutationDescriptor.THIS_INPUT,

			EntitySchemaMutationInputAggregateDescriptor.THIS_INPUT,

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
			// catalog schema mutations
			ModifyEntitySchemaMutationDescriptor.THIS,
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
			ModifyReflectedReferenceAttributeInheritanceSchemaMutationDescriptor.THIS,
			RemoveReferenceSchemaMutationDescriptor.THIS,
			SetReferenceSchemaFacetedMutationDescriptor.THIS,
			SetReferenceSchemaIndexedMutationDescriptor.THIS
		);

		// todo lho union?
	}
}
