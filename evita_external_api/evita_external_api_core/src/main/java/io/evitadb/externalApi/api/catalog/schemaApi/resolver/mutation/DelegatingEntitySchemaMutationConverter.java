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

package io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation;

import io.evitadb.api.requestResponse.schema.mutation.EntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.associatedData.CreateAssociatedDataSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.associatedData.ModifyAssociatedDataSchemaDeprecationNoticeMutation;
import io.evitadb.api.requestResponse.schema.mutation.associatedData.ModifyAssociatedDataSchemaDescriptionMutation;
import io.evitadb.api.requestResponse.schema.mutation.associatedData.ModifyAssociatedDataSchemaNameMutation;
import io.evitadb.api.requestResponse.schema.mutation.associatedData.ModifyAssociatedDataSchemaTypeMutation;
import io.evitadb.api.requestResponse.schema.mutation.associatedData.RemoveAssociatedDataSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.associatedData.SetAssociatedDataSchemaLocalizedMutation;
import io.evitadb.api.requestResponse.schema.mutation.associatedData.SetAssociatedDataSchemaNullableMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.*;
import io.evitadb.api.requestResponse.schema.mutation.catalog.CreateEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.RemoveEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.entity.AllowCurrencyInEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.entity.AllowEvolutionModeInEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.entity.AllowLocaleInEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.entity.DisallowCurrencyInEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.entity.DisallowEvolutionModeInEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.entity.DisallowLocaleInEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.entity.ModifyEntitySchemaDeprecationNoticeMutation;
import io.evitadb.api.requestResponse.schema.mutation.entity.ModifyEntitySchemaDescriptionMutation;
import io.evitadb.api.requestResponse.schema.mutation.entity.SetEntitySchemaWithGeneratedPrimaryKeyMutation;
import io.evitadb.api.requestResponse.schema.mutation.entity.SetEntitySchemaWithHierarchyMutation;
import io.evitadb.api.requestResponse.schema.mutation.entity.SetEntitySchemaWithPriceMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.*;
import io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound.*;
import io.evitadb.externalApi.api.catalog.resolver.mutation.DelegatingMutationConverter;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationObjectMapper;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.associatedData.CreateAssociatedDataSchemaMutationConverter;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.associatedData.ModifyAssociatedDataSchemaDeprecationNoticeMutationConverter;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.associatedData.ModifyAssociatedDataSchemaDescriptionMutationConverter;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.associatedData.ModifyAssociatedDataSchemaNameMutationConverter;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.associatedData.ModifyAssociatedDataSchemaTypeMutationConverter;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.associatedData.RemoveAssociatedDataSchemaMutationConverter;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.associatedData.SetAssociatedDataSchemaLocalizedMutationConverter;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.associatedData.SetAssociatedDataSchemaNullableMutationConverter;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.attribute.*;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.catalog.CreateEntitySchemaMutationConverter;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.catalog.ModifyEntitySchemaMutationConverter;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.catalog.RemoveEntitySchemaMutationConverter;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.entity.*;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.reference.*;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.sortableAttributeCompound.CreateSortableAttributeCompoundSchemaMutationConverter;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.sortableAttributeCompound.ModifySortableAttributeCompoundSchemaDeprecationNoticeMutationConverter;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.sortableAttributeCompound.ModifySortableAttributeCompoundSchemaDescriptionMutationConverter;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.sortableAttributeCompound.ModifySortableAttributeCompoundSchemaNameMutationConverter;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.sortableAttributeCompound.RemoveSortableAttributeCompoundSchemaMutationConverter;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.sortableAttributeCompound.SetSortableAttributeCompoundIndexedMutationConverter;
import lombok.AccessLevel;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.util.Map;

import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * Implementation of {@link DelegatingMutationConverter} for converting implementations of {@link EntitySchemaMutation}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class DelegatingEntitySchemaMutationConverter extends
	DelegatingMutationConverter<EntitySchemaMutation, SchemaMutationConverter<EntitySchemaMutation>> {

	@Nonnull
	@Getter(AccessLevel.PROTECTED)
	private final Map<Class<? extends EntitySchemaMutation>, SchemaMutationConverter<EntitySchemaMutation>> converters = createHashMap(55);

	public DelegatingEntitySchemaMutationConverter(@Nonnull MutationObjectMapper objectMapper,
	                                              @Nonnull MutationResolvingExceptionFactory exceptionFactory) {
		super(objectMapper, exceptionFactory);

		// entity schema mutations
		registerConverter(AllowCurrencyInEntitySchemaMutation.class, new AllowCurrencyInEntitySchemaMutationConverter(objectMapper, exceptionFactory));
		registerConverter(AllowEvolutionModeInEntitySchemaMutation.class, new AllowEvolutionModeInEntitySchemaMutationConverter(objectMapper, exceptionFactory));
		registerConverter(AllowLocaleInEntitySchemaMutation.class, new AllowLocaleInEntitySchemaMutationConverter(objectMapper, exceptionFactory));
		registerConverter(DisallowCurrencyInEntitySchemaMutation.class, new DisallowCurrencyInEntitySchemaMutationConverter(objectMapper, exceptionFactory));
		registerConverter(DisallowEvolutionModeInEntitySchemaMutation.class, new DisallowEvolutionModeInEntitySchemaMutationConverter(objectMapper, exceptionFactory));
		registerConverter(DisallowLocaleInEntitySchemaMutation.class, new DisallowLocaleInEntitySchemaMutationConverter(objectMapper, exceptionFactory));
		registerConverter(ModifyEntitySchemaDeprecationNoticeMutation.class, new ModifyEntitySchemaDeprecationNoticeMutationConverter(objectMapper, exceptionFactory));
		registerConverter(ModifyEntitySchemaDescriptionMutation.class, new ModifyEntitySchemaDescriptionMutationConverter(objectMapper, exceptionFactory));
		registerConverter(SetEntitySchemaWithGeneratedPrimaryKeyMutation.class, new SetEntitySchemaWithGeneratedPrimaryKeyMutationConverter(objectMapper, exceptionFactory));
		registerConverter(SetEntitySchemaWithHierarchyMutation.class, new SetEntitySchemaWithHierarchyMutationConverter(objectMapper, exceptionFactory));
		registerConverter(SetEntitySchemaWithPriceMutation.class, new SetEntitySchemaWithPriceMutationConverter(objectMapper, exceptionFactory));

		// associated data schema mutations
		registerConverter(CreateAssociatedDataSchemaMutation.class, new CreateAssociatedDataSchemaMutationConverter(objectMapper, exceptionFactory));
		registerConverter(ModifyAssociatedDataSchemaDeprecationNoticeMutation.class, new ModifyAssociatedDataSchemaDeprecationNoticeMutationConverter(objectMapper, exceptionFactory));
		registerConverter(ModifyAssociatedDataSchemaDescriptionMutation.class, new ModifyAssociatedDataSchemaDescriptionMutationConverter(objectMapper, exceptionFactory));
		registerConverter(ModifyAssociatedDataSchemaNameMutation.class, new ModifyAssociatedDataSchemaNameMutationConverter(objectMapper, exceptionFactory));
		registerConverter(ModifyAssociatedDataSchemaTypeMutation.class, new ModifyAssociatedDataSchemaTypeMutationConverter(objectMapper, exceptionFactory));
		registerConverter(RemoveAssociatedDataSchemaMutation.class, new RemoveAssociatedDataSchemaMutationConverter(objectMapper, exceptionFactory));
		registerConverter(SetAssociatedDataSchemaLocalizedMutation.class, new SetAssociatedDataSchemaLocalizedMutationConverter(objectMapper, exceptionFactory));
		registerConverter(SetAssociatedDataSchemaNullableMutation.class, new SetAssociatedDataSchemaNullableMutationConverter(objectMapper, exceptionFactory));

		// attribute schema mutations
		registerConverter(CreateAttributeSchemaMutation.class, new CreateAttributeSchemaMutationConverter(objectMapper, exceptionFactory));
		registerConverter(ModifyAttributeSchemaDefaultValueMutation.class, new ModifyAttributeSchemaDefaultValueMutationConverter(objectMapper, exceptionFactory));
		registerConverter(ModifyAttributeSchemaDeprecationNoticeMutation.class, new ModifyAttributeSchemaDeprecationNoticeMutationConverter(objectMapper, exceptionFactory));
		registerConverter(ModifyAttributeSchemaDescriptionMutation.class, new ModifyAttributeSchemaDescriptionMutationConverter(objectMapper, exceptionFactory));
		registerConverter(ModifyAttributeSchemaNameMutation.class, new ModifyAttributeSchemaNameMutationConverter(objectMapper, exceptionFactory));
		registerConverter(ModifyAttributeSchemaTypeMutation.class, new ModifyAttributeSchemaTypeMutationConverter(objectMapper, exceptionFactory));
		registerConverter(RemoveAttributeSchemaMutation.class, new RemoveAttributeSchemaMutationConverter(objectMapper, exceptionFactory));
		registerConverter(SetAttributeSchemaFilterableMutation.class, new SetAttributeSchemaFilterableMutationConverter(objectMapper, exceptionFactory));
		registerConverter(SetAttributeSchemaLocalizedMutation.class, new SetAttributeSchemaLocalizedMutationConverter(objectMapper, exceptionFactory));
		registerConverter(SetAttributeSchemaNullableMutation.class, new SetAttributeSchemaNullableMutationConverter(objectMapper, exceptionFactory));
		registerConverter(SetAttributeSchemaRepresentativeMutation.class, new SetAttributeSchemaRepresentativeMutationConverter(objectMapper, exceptionFactory));
		registerConverter(SetAttributeSchemaSortableMutation.class, new SetAttributeSchemaSortableMutationConverter(objectMapper, exceptionFactory));
		registerConverter(SetAttributeSchemaUniqueMutation.class, new SetAttributeSchemaUniqueMutationConverter(objectMapper, exceptionFactory));
		registerConverter(UseGlobalAttributeSchemaMutation.class, new UseGlobalAttributeSchemaMutationConverter(objectMapper, exceptionFactory));

		// sortable attribute compounds schema mutations
		registerConverter(CreateSortableAttributeCompoundSchemaMutation.class, new CreateSortableAttributeCompoundSchemaMutationConverter(objectMapper, exceptionFactory));
		registerConverter(ModifySortableAttributeCompoundSchemaDeprecationNoticeMutation.class, new ModifySortableAttributeCompoundSchemaDeprecationNoticeMutationConverter(objectMapper, exceptionFactory));
		registerConverter(ModifySortableAttributeCompoundSchemaDescriptionMutation.class, new ModifySortableAttributeCompoundSchemaDescriptionMutationConverter(objectMapper, exceptionFactory));
		registerConverter(ModifySortableAttributeCompoundSchemaNameMutation.class, new ModifySortableAttributeCompoundSchemaNameMutationConverter(objectMapper, exceptionFactory));
		registerConverter(SetSortableAttributeCompoundSchemaIndexedMutation.class, new SetSortableAttributeCompoundIndexedMutationConverter(objectMapper, exceptionFactory));
		registerConverter(RemoveSortableAttributeCompoundSchemaMutation.class, new RemoveSortableAttributeCompoundSchemaMutationConverter(objectMapper, exceptionFactory));

		// reference schema mutations
		registerConverter(CreateReferenceSchemaMutation.class, new CreateReferenceSchemaMutationConverter(objectMapper, exceptionFactory));
		registerConverter(CreateReflectedReferenceSchemaMutation.class, new CreateReflectedReferenceSchemaMutationConverter(objectMapper, exceptionFactory));
		registerConverter(ModifyReferenceAttributeSchemaMutation.class, new ModifyReferenceAttributeSchemaMutationConverter(objectMapper, exceptionFactory));
		registerConverter(ModifyReferenceSchemaCardinalityMutation.class, new ModifyReferenceSchemaCardinalityMutationConverter(objectMapper, exceptionFactory));
		registerConverter(ModifyReferenceSchemaDeprecationNoticeMutation.class, new ModifyReferenceSchemaDeprecationNoticeMutationConverter(objectMapper, exceptionFactory));
		registerConverter(ModifyReferenceSchemaDescriptionMutation.class, new ModifyReferenceSchemaDescriptionMutationConverter(objectMapper, exceptionFactory));
		registerConverter(ModifyReferenceSchemaNameMutation.class, new ModifyReferenceSchemaNameMutationConverter(objectMapper, exceptionFactory));
		registerConverter(ModifyReferenceSchemaRelatedEntityGroupMutation.class, new ModifyReferenceSchemaRelatedEntityGroupMutationConverter(objectMapper, exceptionFactory));
		registerConverter(ModifyReferenceSchemaRelatedEntityMutation.class, new ModifyReferenceSchemaRelatedEntityMutationConverter(objectMapper, exceptionFactory));
		registerConverter(ModifyReflectedReferenceAttributeInheritanceSchemaMutation.class, new ModifyReflectedReferenceAttributeInheritanceSchemaMutationConverter(objectMapper, exceptionFactory));
		registerConverter(RemoveReferenceSchemaMutation.class, new RemoveReferenceSchemaMutationConverter(objectMapper, exceptionFactory));
		registerConverter(SetReferenceSchemaFacetedMutation.class, new SetReferenceSchemaFacetedMutationConverter(objectMapper, exceptionFactory));
		registerConverter(SetReferenceSchemaIndexedMutation.class, new SetReferenceSchemaIndexedMutationConverter(objectMapper, exceptionFactory));
	}

	@Nonnull
	@Override
	protected String getAncestorMutationName() {
		return EntitySchemaMutation.class.getSimpleName();
	}
}
