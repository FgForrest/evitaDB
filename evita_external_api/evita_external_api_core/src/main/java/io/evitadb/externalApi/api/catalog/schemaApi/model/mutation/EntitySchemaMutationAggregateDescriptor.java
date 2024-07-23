/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

import io.evitadb.api.requestResponse.schema.mutation.EntitySchemaMutation;
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
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import java.util.List;

/**
 * Descriptor of aggregate object containing all implementations of {@link EntitySchemaMutation}
 * for schema-based external APIs.
 *
 * Note: this descriptor has static structure.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public interface EntitySchemaMutationAggregateDescriptor {

	/**
	 * Entity schema mutations
	 */

	PropertyDescriptor ALLOW_CURRENCY_IN_ENTITY_SCHEMA_MUTATION = PropertyDescriptor.nullableFromObject(AllowCurrencyInEntitySchemaMutationDescriptor.THIS);
	PropertyDescriptor ALLOW_EVOLUTION_MODE_IN_ENTITY_SCHEMA_MUTATION = PropertyDescriptor.nullableFromObject(AllowEvolutionModeInEntitySchemaMutationDescriptor.THIS);
	PropertyDescriptor ALLOW_LOCALE_IN_ENTITY_SCHEMA_MUTATION = PropertyDescriptor.nullableFromObject(AllowLocaleInEntitySchemaMutationDescriptor.THIS);
	PropertyDescriptor DISALLOW_CURRENCY_IN_ENTITY_SCHEMA_MUTATION = PropertyDescriptor.nullableFromObject(DisallowCurrencyInEntitySchemaMutationDescriptor.THIS);
	PropertyDescriptor DISALLOW_EVOLUTION_MODE_IN_ENTITY_SCHEMA_MUTATION = PropertyDescriptor.nullableFromObject(DisallowEvolutionModeInEntitySchemaMutationDescriptor.THIS);
	PropertyDescriptor DISALLOW_LOCALE_IN_ENTITY_SCHEMA_MUTATION = PropertyDescriptor.nullableFromObject(DisallowLocaleInEntitySchemaMutationDescriptor.THIS);
	PropertyDescriptor MODIFY_ENTITY_SCHEMA_DEPRECATION_NOTICE_MUTATION = PropertyDescriptor.nullableFromObject(ModifyEntitySchemaDeprecationNoticeMutationDescriptor.THIS);
	PropertyDescriptor MODIFY_ENTITY_SCHEMA_DESCRIPTION_MUTATION = PropertyDescriptor.nullableFromObject(ModifyEntitySchemaDescriptionMutationDescriptor.THIS);
	PropertyDescriptor MODIFY_ENTITY_SCHEMA_NAME_MUTATION = PropertyDescriptor.nullableFromObject(ModifyEntitySchemaNameMutationDescriptor.THIS);
	PropertyDescriptor REMOVE_ENTITY_SCHEMA_MUTATION = PropertyDescriptor.nullableFromObject(RemoveEntitySchemaMutationDescriptor.THIS);
	PropertyDescriptor SET_ENTITY_SCHEMA_WITH_GENERATED_PRIMARY_KEY_MUTATION = PropertyDescriptor.nullableFromObject(SetEntitySchemaWithGeneratedPrimaryKeyMutationDescriptor.THIS);
	PropertyDescriptor SET_ENTITY_SCHEMA_WITH_HIERARCHY_MUTATION = PropertyDescriptor.nullableFromObject(SetEntitySchemaWithHierarchyMutationDescriptor.THIS);
	PropertyDescriptor SET_ENTITY_SCHEMA_WITH_PRICE_MUTATION = PropertyDescriptor.nullableFromObject(SetEntitySchemaWithPriceMutationDescriptor.THIS);

	/**
	 * Associated data schema mutations
	 */

	PropertyDescriptor CREATE_ASSOCIATED_DATA_SCHEMA_MUTATION = PropertyDescriptor.nullableFromObject(CreateAssociatedDataSchemaMutationDescriptor.THIS);
	PropertyDescriptor MODIFY_ASSOCIATED_DATA_SCHEMA_DEPRECATION_NOTICE_MUTATION = PropertyDescriptor.nullableFromObject(ModifyAssociatedDataSchemaDeprecationNoticeMutationDescriptor.THIS);
	PropertyDescriptor MODIFY_ASSOCIATED_DATA_SCHEMA_DESCRIPTION_MUTATION = PropertyDescriptor.nullableFromObject(ModifyAssociatedDataSchemaDescriptionMutationDescriptor.THIS);
	PropertyDescriptor MODIFY_ASSOCIATED_DATA_SCHEMA_NAME_MUTATION = PropertyDescriptor.nullableFromObject(ModifyAssociatedDataSchemaNameMutationDescriptor.THIS);
	PropertyDescriptor MODIFY_ASSOCIATED_DATA_SCHEMA_TYPE_MUTATION = PropertyDescriptor.nullableFromObject(ModifyAssociatedDataSchemaTypeMutationDescriptor.THIS);
	PropertyDescriptor REMOVE_ASSOCIATED_DATA_SCHEMA_MUTATION = PropertyDescriptor.nullableFromObject(RemoveAssociatedDataSchemaMutationDescriptor.THIS);
	PropertyDescriptor SET_ASSOCIATED_DATA_SCHEMA_LOCALIZED_MUTATION = PropertyDescriptor.nullableFromObject(SetAssociatedDataSchemaLocalizedMutationDescriptor.THIS);
	PropertyDescriptor SET_ASSOCIATED_DATA_SCHEMA_NULLABLE_MUTATION = PropertyDescriptor.nullableFromObject(SetAssociatedDataSchemaNullableMutationDescriptor.THIS);

	/**
	 * Attribute schema mutations
	 */

	PropertyDescriptor CREATE_ATTRIBUTE_SCHEMA_MUTATION = PropertyDescriptor.nullableFromObject(CreateAttributeSchemaMutationDescriptor.THIS);
	PropertyDescriptor MODIFY_ATTRIBUTE_SCHEMA_DEFAULT_VALUE_MUTATION = PropertyDescriptor.nullableFromObject(ModifyAttributeSchemaDefaultValueMutationDescriptor.THIS);
	PropertyDescriptor MODIFY_ATTRIBUTE_SCHEMA_DEPRECATION_NOTICE_MUTATION = PropertyDescriptor.nullableFromObject(ModifyAttributeSchemaDeprecationNoticeMutationDescriptor.THIS);
	PropertyDescriptor MODIFY_ATTRIBUTE_SCHEMA_DESCRIPTION_MUTATION = PropertyDescriptor.nullableFromObject(ModifyAttributeSchemaDescriptionMutationDescriptor.THIS);
	PropertyDescriptor MODIFY_ATTRIBUTE_SCHEMA_NAME_MUTATION = PropertyDescriptor.nullableFromObject(ModifyAttributeSchemaNameMutationDescriptor.THIS);
	PropertyDescriptor MODIFY_ATTRIBUTE_SCHEMA_TYPE_MUTATION = PropertyDescriptor.nullableFromObject(ModifyAttributeSchemaTypeMutationDescriptor.THIS);
	PropertyDescriptor REMOVE_ATTRIBUTE_SCHEMA_MUTATION = PropertyDescriptor.nullableFromObject(RemoveAttributeSchemaMutationDescriptor.THIS);
	PropertyDescriptor SET_ATTRIBUTE_SCHEMA_FILTERABLE_MUTATION = PropertyDescriptor.nullableFromObject(SetAttributeSchemaFilterableMutationDescriptor.THIS);
	PropertyDescriptor SET_ATTRIBUTE_SCHEMA_LOCALIZED_MUTATION = PropertyDescriptor.nullableFromObject(SetAttributeSchemaLocalizedMutationDescriptor.THIS);
	PropertyDescriptor SET_ATTRIBUTE_SCHEMA_NULLABLE_MUTATION = PropertyDescriptor.nullableFromObject(SetAttributeSchemaNullableMutationDescriptor.THIS);
	PropertyDescriptor SET_ATTRIBUTE_SCHEMA_REPRESENTATIVE_MUTATION = PropertyDescriptor.nullableFromObject(SetAttributeSchemaRepresentativeMutationDescriptor.THIS);
	PropertyDescriptor SET_ATTRIBUTE_SCHEMA_SORTABLE_MUTATION = PropertyDescriptor.nullableFromObject(SetAttributeSchemaSortableMutationDescriptor.THIS);
	PropertyDescriptor SET_ATTRIBUTE_SCHEMA_UNIQUE_MUTATION = PropertyDescriptor.nullableFromObject(SetAttributeSchemaUniqueMutationDescriptor.THIS);
	PropertyDescriptor USE_GLOBAL_ATTRIBUTE_SCHEMA_MUTATION = PropertyDescriptor.nullableFromObject(UseGlobalAttributeSchemaMutationDescriptor.THIS);

	/**
	 * Sortable attribute compound schema mutations
	 */

	PropertyDescriptor CREATE_SORTABLE_ATTRIBUTE_COMPOUND_SCHEMA_MUTATION = PropertyDescriptor.nullableFromObject(CreateSortableAttributeCompoundSchemaMutationDescriptor.THIS);
	PropertyDescriptor MODIFY_SORTABLE_ATTRIBUTE_COMPOUND_SCHEMA_DEPRECATION_NOTICE_MUTATION = PropertyDescriptor.nullableFromObject(ModifySortableAttributeCompoundSchemaDeprecationNoticeMutationDescriptor.THIS);
	PropertyDescriptor MODIFY_SORTABLE_ATTRIBUTE_COMPOUND_SCHEMA_DESCRIPTION_MUTATION = PropertyDescriptor.nullableFromObject(ModifySortableAttributeCompoundSchemaDescriptionMutationDescriptor.THIS);
	PropertyDescriptor MODIFY_SORTABLE_ATTRIBUTE_COMPOUND_SCHEMA_NAME_MUTATION = PropertyDescriptor.nullableFromObject(ModifySortableAttributeCompoundSchemaNameMutationDescriptor.THIS);
	PropertyDescriptor REMOVE_SORTABLE_ATTRIBUTE_COMPOUND_SCHEMA_MUTATION = PropertyDescriptor.nullableFromObject(RemoveSortableAttributeCompoundSchemaMutationDescriptor.THIS);

	/**
	 * Reference schema mutations
	 */

	PropertyDescriptor CREATE_REFERENCE_SCHEMA_MUTATION = PropertyDescriptor.nullableFromObject(CreateReferenceSchemaMutationDescriptor.THIS);
	PropertyDescriptor MODIFY_REFERENCE_ATTRIBUTE_SCHEMA_MUTATION = PropertyDescriptor.nullableFromObject(ModifyReferenceAttributeSchemaMutationDescriptor.THIS);
	PropertyDescriptor MODIFY_REFERENCE_SCHEMA_CARDINALITY_MUTATION = PropertyDescriptor.nullableFromObject(ModifyReferenceSchemaCardinalityMutationDescriptor.THIS);
	PropertyDescriptor MODIFY_REFERENCE_SCHEMA_DEPRECATION_NOTICE_MUTATION = PropertyDescriptor.nullableFromObject(ModifyReferenceSchemaDeprecationNoticeMutationDescriptor.THIS);
	PropertyDescriptor MODIFY_REFERENCE_SCHEMA_DESCRIPTION_MUTATION = PropertyDescriptor.nullableFromObject(ModifyReferenceSchemaDescriptionMutationDescriptor.THIS);
	PropertyDescriptor MODIFY_REFERENCE_SCHEMA_NAME_MUTATION = PropertyDescriptor.nullableFromObject(ModifyReferenceSchemaNameMutationDescriptor.THIS);
	PropertyDescriptor MODIFY_REFERENCE_SCHEMA_RELATED_ENTITY_GROUP_MUTATION = PropertyDescriptor.nullableFromObject(ModifyReferenceSchemaRelatedEntityGroupMutationDescriptor.THIS);
	PropertyDescriptor MODIFY_REFERENCE_SCHEMA_RELATED_ENTITY_MUTATION = PropertyDescriptor.nullableFromObject(ModifyReferenceSchemaRelatedEntityMutationDescriptor.THIS);
	PropertyDescriptor REMOVE_REFERENCE_SCHEMA_MUTATION = PropertyDescriptor.nullableFromObject(RemoveReferenceSchemaMutationDescriptor.THIS);
	PropertyDescriptor SET_REFERENCE_SCHEMA_FACETED_MUTATION = PropertyDescriptor.nullableFromObject(SetReferenceSchemaFacetedMutationDescriptor.THIS);
	PropertyDescriptor SET_REFERENCE_SCHEMA_INDEXED_MUTATION = PropertyDescriptor.nullableFromObject(SetReferenceSchemaIndexedMutationDescriptor.THIS);

	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("EntitySchemaMutationAggregate")
		.description("""
            Contains all possible entity schema mutations.
            """)
		.staticFields(List.of(
			ALLOW_CURRENCY_IN_ENTITY_SCHEMA_MUTATION,
			ALLOW_EVOLUTION_MODE_IN_ENTITY_SCHEMA_MUTATION,
			ALLOW_LOCALE_IN_ENTITY_SCHEMA_MUTATION,
			DISALLOW_CURRENCY_IN_ENTITY_SCHEMA_MUTATION,
			DISALLOW_EVOLUTION_MODE_IN_ENTITY_SCHEMA_MUTATION,
			DISALLOW_LOCALE_IN_ENTITY_SCHEMA_MUTATION,
			MODIFY_ENTITY_SCHEMA_DEPRECATION_NOTICE_MUTATION,
			MODIFY_ENTITY_SCHEMA_DESCRIPTION_MUTATION,
			MODIFY_ENTITY_SCHEMA_NAME_MUTATION,
			REMOVE_ENTITY_SCHEMA_MUTATION,
			SET_ENTITY_SCHEMA_WITH_GENERATED_PRIMARY_KEY_MUTATION,
			SET_ENTITY_SCHEMA_WITH_HIERARCHY_MUTATION,
			SET_ENTITY_SCHEMA_WITH_PRICE_MUTATION,

			CREATE_ASSOCIATED_DATA_SCHEMA_MUTATION,
			MODIFY_ASSOCIATED_DATA_SCHEMA_DEPRECATION_NOTICE_MUTATION,
			MODIFY_ASSOCIATED_DATA_SCHEMA_DESCRIPTION_MUTATION,
			MODIFY_ASSOCIATED_DATA_SCHEMA_NAME_MUTATION,
			MODIFY_ASSOCIATED_DATA_SCHEMA_TYPE_MUTATION,
			REMOVE_ASSOCIATED_DATA_SCHEMA_MUTATION,
			SET_ASSOCIATED_DATA_SCHEMA_LOCALIZED_MUTATION,
			SET_ASSOCIATED_DATA_SCHEMA_NULLABLE_MUTATION,

			CREATE_ATTRIBUTE_SCHEMA_MUTATION,
			MODIFY_ATTRIBUTE_SCHEMA_DEFAULT_VALUE_MUTATION,
			MODIFY_ATTRIBUTE_SCHEMA_DEPRECATION_NOTICE_MUTATION,
			MODIFY_ATTRIBUTE_SCHEMA_DESCRIPTION_MUTATION,
			MODIFY_ATTRIBUTE_SCHEMA_NAME_MUTATION,
			MODIFY_ATTRIBUTE_SCHEMA_TYPE_MUTATION,
			REMOVE_ATTRIBUTE_SCHEMA_MUTATION,
			SET_ATTRIBUTE_SCHEMA_FILTERABLE_MUTATION,
			SET_ATTRIBUTE_SCHEMA_LOCALIZED_MUTATION,
			SET_ATTRIBUTE_SCHEMA_NULLABLE_MUTATION,
			SET_ATTRIBUTE_SCHEMA_REPRESENTATIVE_MUTATION,
			SET_ATTRIBUTE_SCHEMA_SORTABLE_MUTATION,
			SET_ATTRIBUTE_SCHEMA_UNIQUE_MUTATION,
			USE_GLOBAL_ATTRIBUTE_SCHEMA_MUTATION,

			CREATE_SORTABLE_ATTRIBUTE_COMPOUND_SCHEMA_MUTATION,
			MODIFY_SORTABLE_ATTRIBUTE_COMPOUND_SCHEMA_DEPRECATION_NOTICE_MUTATION,
			MODIFY_SORTABLE_ATTRIBUTE_COMPOUND_SCHEMA_DESCRIPTION_MUTATION,
			MODIFY_SORTABLE_ATTRIBUTE_COMPOUND_SCHEMA_NAME_MUTATION,
			REMOVE_SORTABLE_ATTRIBUTE_COMPOUND_SCHEMA_MUTATION,

			CREATE_REFERENCE_SCHEMA_MUTATION,
			MODIFY_REFERENCE_ATTRIBUTE_SCHEMA_MUTATION,
			MODIFY_REFERENCE_SCHEMA_CARDINALITY_MUTATION,
			MODIFY_REFERENCE_SCHEMA_DEPRECATION_NOTICE_MUTATION,
			MODIFY_REFERENCE_SCHEMA_DESCRIPTION_MUTATION,
			MODIFY_REFERENCE_SCHEMA_NAME_MUTATION,
			MODIFY_REFERENCE_SCHEMA_RELATED_ENTITY_GROUP_MUTATION,
			MODIFY_REFERENCE_SCHEMA_RELATED_ENTITY_MUTATION,
			REMOVE_REFERENCE_SCHEMA_MUTATION,
			SET_REFERENCE_SCHEMA_FACETED_MUTATION,
			SET_REFERENCE_SCHEMA_INDEXED_MUTATION
		))
		.build();
}
