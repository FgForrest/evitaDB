/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.externalApi.api.catalog.schemaApi.model.mutation;

import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.associatedData.CreateAssociatedDataSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.associatedData.ModifyAssociatedDataSchemaDeprecationNoticeMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.associatedData.ModifyAssociatedDataSchemaDescriptionMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.associatedData.ModifyAssociatedDataSchemaNameMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.associatedData.ModifyAssociatedDataSchemaTypeMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.associatedData.RemoveAssociatedDataSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.associatedData.SetAssociatedDataSchemaLocalizedMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.associatedData.SetAssociatedDataSchemaNullableMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.attribute.*;
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
import io.evitadb.externalApi.api.model.UnionDescriptor;
import io.evitadb.externalApi.api.model.mutation.MutationDescriptor;

/**
 * TODO lho docs
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2025
 */
public interface LocalEntitySchemaMutationUnionDescriptor {

	UnionDescriptor THIS = UnionDescriptor.builder()
		.name("LocalEntitySchemaMutationUnion")
		.description("Lists all possible types of schema mutations for entity schema modification.")
		.discriminator(MutationDescriptor.MUTATION_TYPE)

		.type(AllowCurrencyInEntitySchemaMutationDescriptor.THIS)
		.type(AllowEvolutionModeInEntitySchemaMutationDescriptor.THIS)
		.type(AllowLocaleInEntitySchemaMutationDescriptor.THIS)
		.type(DisallowCurrencyInEntitySchemaMutationDescriptor.THIS)
		.type(DisallowEvolutionModeInEntitySchemaMutationDescriptor.THIS)
		.type(DisallowLocaleInEntitySchemaMutationDescriptor.THIS)
		.type(ModifyEntitySchemaDeprecationNoticeMutationDescriptor.THIS)
		.type(ModifyEntitySchemaDescriptionMutationDescriptor.THIS)
		.type(ModifyEntitySchemaNameMutationDescriptor.THIS)
		.type(SetEntitySchemaWithGeneratedPrimaryKeyMutationDescriptor.THIS)
		.type(SetEntitySchemaWithHierarchyMutationDescriptor.THIS)
		.type(SetEntitySchemaWithPriceMutationDescriptor.THIS)

		.type(CreateAssociatedDataSchemaMutationDescriptor.THIS)
		.type(ModifyAssociatedDataSchemaDeprecationNoticeMutationDescriptor.THIS)
		.type(ModifyAssociatedDataSchemaDescriptionMutationDescriptor.THIS)
		.type(ModifyAssociatedDataSchemaNameMutationDescriptor.THIS)
		.type(ModifyAssociatedDataSchemaTypeMutationDescriptor.THIS)
		.type(RemoveAssociatedDataSchemaMutationDescriptor.THIS)
		.type(SetAssociatedDataSchemaLocalizedMutationDescriptor.THIS)
		.type(SetAssociatedDataSchemaNullableMutationDescriptor.THIS)

		.type(CreateAttributeSchemaMutationDescriptor.THIS)
		.type(ModifyAttributeSchemaDefaultValueMutationDescriptor.THIS)
		.type(ModifyAttributeSchemaDeprecationNoticeMutationDescriptor.THIS)
		.type(ModifyAttributeSchemaDescriptionMutationDescriptor.THIS)
		.type(ModifyAttributeSchemaNameMutationDescriptor.THIS)
		.type(ModifyAttributeSchemaTypeMutationDescriptor.THIS)
		.type(RemoveAttributeSchemaMutationDescriptor.THIS)
		.type(SetAttributeSchemaFilterableMutationDescriptor.THIS)
		.type(SetAttributeSchemaLocalizedMutationDescriptor.THIS)
		.type(SetAttributeSchemaNullableMutationDescriptor.THIS)
		.type(SetAttributeSchemaRepresentativeMutationDescriptor.THIS)
		.type(SetAttributeSchemaSortableMutationDescriptor.THIS)
		.type(SetAttributeSchemaUniqueMutationDescriptor.THIS)
		.type(UseGlobalAttributeSchemaMutationDescriptor.THIS)

		.type(CreateSortableAttributeCompoundSchemaMutationDescriptor.THIS)
		.type(ModifySortableAttributeCompoundSchemaDeprecationNoticeMutationDescriptor.THIS)
		.type(ModifySortableAttributeCompoundSchemaDescriptionMutationDescriptor.THIS)
		.type(ModifySortableAttributeCompoundSchemaNameMutationDescriptor.THIS)
		.type(SetSortableAttributeCompoundIndexedMutationDescriptor.THIS)
		.type(RemoveSortableAttributeCompoundSchemaMutationDescriptor.THIS)

		.type(CreateReferenceSchemaMutationDescriptor.THIS)
		.type(CreateReflectedReferenceSchemaMutationDescriptor.THIS)
		.type(ModifyReferenceAttributeSchemaMutationDescriptor.THIS)
		.type(ModifyReferenceSchemaCardinalityMutationDescriptor.THIS)
		.type(ModifyReferenceSchemaDeprecationNoticeMutationDescriptor.THIS)
		.type(ModifyReferenceSchemaDescriptionMutationDescriptor.THIS)
		.type(ModifyReferenceSchemaNameMutationDescriptor.THIS)
		.type(ModifyReferenceSchemaRelatedEntityGroupMutationDescriptor.THIS)
		.type(ModifyReferenceSchemaRelatedEntityMutationDescriptor.THIS)
		.type(ModifyReferenceSortableAttributeCompoundSchemaMutationDescriptor.THIS)
		.type(ModifyReflectedReferenceAttributeInheritanceSchemaMutationDescriptor.THIS)
		.type(RemoveReferenceSchemaMutationDescriptor.THIS)
		.type(SetReferenceSchemaFacetedMutationDescriptor.THIS)
		.type(SetReferenceSchemaIndexedMutationDescriptor.THIS)

		.build();
}
