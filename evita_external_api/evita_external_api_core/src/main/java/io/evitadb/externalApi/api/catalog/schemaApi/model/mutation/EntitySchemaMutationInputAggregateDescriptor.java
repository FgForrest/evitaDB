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
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.sortableAttributeCompound.SetSortableAttributeCompoundIndexedMutationDescriptor;
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
public interface EntitySchemaMutationInputAggregateDescriptor {

	/**
	 * Entity schema mutations
	 */

	PropertyDescriptor ALLOW_CURRENCY_IN_ENTITY_SCHEMA_MUTATION = PropertyDescriptor.nullableFromObject(
		"allowCurrencyInEntitySchemaMutation",
		AllowCurrencyInEntitySchemaMutationDescriptor.THIS_INPUT
	);
	PropertyDescriptor ALLOW_EVOLUTION_MODE_IN_ENTITY_SCHEMA_MUTATION = PropertyDescriptor.nullableFromObject(
		"allowEvolutionModeInEntitySchemaMutation",
		AllowEvolutionModeInEntitySchemaMutationDescriptor.THIS_INPUT
	);
	PropertyDescriptor ALLOW_LOCALE_IN_ENTITY_SCHEMA_MUTATION = PropertyDescriptor.nullableFromObject(
		"allowLocaleInEntitySchemaMutation",
		AllowLocaleInEntitySchemaMutationDescriptor.THIS_INPUT
	);
	PropertyDescriptor DISALLOW_CURRENCY_IN_ENTITY_SCHEMA_MUTATION = PropertyDescriptor.nullableFromObject(
		"disallowCurrencyInEntitySchemaMutation",
		DisallowCurrencyInEntitySchemaMutationDescriptor.THIS_INPUT
	);
	PropertyDescriptor DISALLOW_EVOLUTION_MODE_IN_ENTITY_SCHEMA_MUTATION = PropertyDescriptor.nullableFromObject(
		"disallowEvolutionModeInEntitySchemaMutation",
		DisallowEvolutionModeInEntitySchemaMutationDescriptor.THIS_INPUT
	);
	PropertyDescriptor DISALLOW_LOCALE_IN_ENTITY_SCHEMA_MUTATION = PropertyDescriptor.nullableFromObject(
		"disallowLocaleInEntitySchemaMutation",
		DisallowLocaleInEntitySchemaMutationDescriptor.THIS_INPUT
	);
	PropertyDescriptor MODIFY_ENTITY_SCHEMA_DEPRECATION_NOTICE_MUTATION = PropertyDescriptor.nullableFromObject(
		"modifyEntitySchemaDeprecationNoticeMutation",
		ModifyEntitySchemaDeprecationNoticeMutationDescriptor.THIS_INPUT
	);
	PropertyDescriptor MODIFY_ENTITY_SCHEMA_DESCRIPTION_MUTATION = PropertyDescriptor.nullableFromObject(
		"modifyEntitySchemaDescriptionMutation",
		ModifyEntitySchemaDescriptionMutationDescriptor.THIS_INPUT
	);
	PropertyDescriptor MODIFY_ENTITY_SCHEMA_NAME_MUTATION = PropertyDescriptor.nullableFromObject(
		"modifyEntitySchemaNameMutation",
		ModifyEntitySchemaNameMutationDescriptor.THIS_INPUT
	);
	PropertyDescriptor REMOVE_ENTITY_SCHEMA_MUTATION = PropertyDescriptor.nullableFromObject(
		"removeEntitySchemaMutation",
		RemoveEntitySchemaMutationDescriptor.THIS_INPUT
	);
	PropertyDescriptor SET_ENTITY_SCHEMA_WITH_GENERATED_PRIMARY_KEY_MUTATION = PropertyDescriptor.nullableFromObject(
		"setEntitySchemaWithGeneratedPrimaryKeyMutation",
		SetEntitySchemaWithGeneratedPrimaryKeyMutationDescriptor.THIS_INPUT
	);
	PropertyDescriptor SET_ENTITY_SCHEMA_WITH_HIERARCHY_MUTATION = PropertyDescriptor.nullableFromObject(
		"setEntitySchemaWithHierarchyMutation",
		SetEntitySchemaWithHierarchyMutationDescriptor.THIS_INPUT
	);
	PropertyDescriptor SET_ENTITY_SCHEMA_WITH_PRICE_MUTATION = PropertyDescriptor.nullableFromObject(
		"setEntitySchemaWithPriceMutation",
		SetEntitySchemaWithPriceMutationDescriptor.THIS_INPUT
	);

	/**
	 * Associated data schema mutations
	 */

	PropertyDescriptor CREATE_ASSOCIATED_DATA_SCHEMA_MUTATION = PropertyDescriptor.nullableFromObject(
		"createAssociatedDataSchemaMutation",
		CreateAssociatedDataSchemaMutationDescriptor.THIS_INPUT
	);
	PropertyDescriptor MODIFY_ASSOCIATED_DATA_SCHEMA_DEPRECATION_NOTICE_MUTATION = PropertyDescriptor.nullableFromObject(
		"modifyAssociatedDataSchemaDeprecationNoticeMutation",
		ModifyAssociatedDataSchemaDeprecationNoticeMutationDescriptor.THIS_INPUT
	);
	PropertyDescriptor MODIFY_ASSOCIATED_DATA_SCHEMA_DESCRIPTION_MUTATION = PropertyDescriptor.nullableFromObject(
		"modifyAssociatedDataSchemaDescriptionMutation",
		ModifyAssociatedDataSchemaDescriptionMutationDescriptor.THIS_INPUT
	);
	PropertyDescriptor MODIFY_ASSOCIATED_DATA_SCHEMA_NAME_MUTATION = PropertyDescriptor.nullableFromObject(
		"modifyAssociatedDataSchemaNameMutation",
		ModifyAssociatedDataSchemaNameMutationDescriptor.THIS_INPUT
	);
	PropertyDescriptor MODIFY_ASSOCIATED_DATA_SCHEMA_TYPE_MUTATION = PropertyDescriptor.nullableFromObject(
		"modifyAssociatedDataSchemaTypeMutation",
		ModifyAssociatedDataSchemaTypeMutationDescriptor.THIS_INPUT
	);
	PropertyDescriptor REMOVE_ASSOCIATED_DATA_SCHEMA_MUTATION = PropertyDescriptor.nullableFromObject(
		"removeAssociatedDataSchemaMutation",
		RemoveAssociatedDataSchemaMutationDescriptor.THIS_INPUT
	);
	PropertyDescriptor SET_ASSOCIATED_DATA_SCHEMA_LOCALIZED_MUTATION = PropertyDescriptor.nullableFromObject(
		"setAssociatedDataSchemaLocalizedMutation",
		SetAssociatedDataSchemaLocalizedMutationDescriptor.THIS_INPUT
	);
	PropertyDescriptor SET_ASSOCIATED_DATA_SCHEMA_NULLABLE_MUTATION = PropertyDescriptor.nullableFromObject(
		"setAssociatedDataSchemaNullableMutation",
		SetAssociatedDataSchemaNullableMutationDescriptor.THIS_INPUT
	);

	/**
	 * Attribute schema mutations
	 */

	PropertyDescriptor CREATE_ATTRIBUTE_SCHEMA_MUTATION = PropertyDescriptor.nullableFromObject(
		"createAttributeSchemaMutation",
		CreateAttributeSchemaMutationDescriptor.THIS_INPUT
	);
	PropertyDescriptor MODIFY_ATTRIBUTE_SCHEMA_DEFAULT_VALUE_MUTATION = PropertyDescriptor.nullableFromObject(
		"modifyAttributeSchemaDefaultValueMutation",
		ModifyAttributeSchemaDefaultValueMutationDescriptor.THIS_INPUT
	);
	PropertyDescriptor MODIFY_ATTRIBUTE_SCHEMA_DEPRECATION_NOTICE_MUTATION = PropertyDescriptor.nullableFromObject(
		"modifyAttributeSchemaDeprecationNoticeMutation",
		ModifyAttributeSchemaDeprecationNoticeMutationDescriptor.THIS_INPUT
	);
	PropertyDescriptor MODIFY_ATTRIBUTE_SCHEMA_DESCRIPTION_MUTATION = PropertyDescriptor.nullableFromObject(
		"modifyAttributeSchemaDescriptionMutation",
		ModifyAttributeSchemaDescriptionMutationDescriptor.THIS_INPUT
	);
	PropertyDescriptor MODIFY_ATTRIBUTE_SCHEMA_NAME_MUTATION = PropertyDescriptor.nullableFromObject(
		"modifyAttributeSchemaNameMutation",
		ModifyAttributeSchemaNameMutationDescriptor.THIS_INPUT
	);
	PropertyDescriptor MODIFY_ATTRIBUTE_SCHEMA_TYPE_MUTATION = PropertyDescriptor.nullableFromObject(
		"modifyAttributeSchemaTypeMutation",
		ModifyAttributeSchemaTypeMutationDescriptor.THIS_INPUT
	);
	PropertyDescriptor REMOVE_ATTRIBUTE_SCHEMA_MUTATION = PropertyDescriptor.nullableFromObject(
		"removeAttributeSchemaMutation",
		RemoveAttributeSchemaMutationDescriptor.THIS_INPUT
	);
	PropertyDescriptor SET_ATTRIBUTE_SCHEMA_FILTERABLE_MUTATION = PropertyDescriptor.nullableFromObject(
		"setAttributeSchemaFilterableMutation",
		SetAttributeSchemaFilterableMutationDescriptor.THIS_INPUT
	);
	PropertyDescriptor SET_ATTRIBUTE_SCHEMA_LOCALIZED_MUTATION = PropertyDescriptor.nullableFromObject(
		"setAttributeSchemaLocalizedMutation",
		SetAttributeSchemaLocalizedMutationDescriptor.THIS_INPUT
	);
	PropertyDescriptor SET_ATTRIBUTE_SCHEMA_NULLABLE_MUTATION = PropertyDescriptor.nullableFromObject(
		"setAttributeSchemaNullableMutation",
		SetAttributeSchemaNullableMutationDescriptor.THIS_INPUT
	);
	PropertyDescriptor SET_ATTRIBUTE_SCHEMA_REPRESENTATIVE_MUTATION = PropertyDescriptor.nullableFromObject(
		"setAttributeSchemaRepresentativeMutation",
		SetAttributeSchemaRepresentativeMutationDescriptor.THIS_INPUT
	);
	PropertyDescriptor SET_ATTRIBUTE_SCHEMA_SORTABLE_MUTATION = PropertyDescriptor.nullableFromObject(
		"setAttributeSchemaSortableMutation",
		SetAttributeSchemaSortableMutationDescriptor.THIS_INPUT
	);
	PropertyDescriptor SET_ATTRIBUTE_SCHEMA_UNIQUE_MUTATION = PropertyDescriptor.nullableFromObject(
		"setAttributeSchemaUniqueMutation",
		SetAttributeSchemaUniqueMutationDescriptor.THIS_INPUT
	);
	PropertyDescriptor USE_GLOBAL_ATTRIBUTE_SCHEMA_MUTATION = PropertyDescriptor.nullableFromObject(
		"useGlobalAttributeSchemaMutation",
		UseGlobalAttributeSchemaMutationDescriptor.THIS_INPUT
	);

	/**
	 * Sortable attribute compound schema mutations
	 */

	PropertyDescriptor CREATE_SORTABLE_ATTRIBUTE_COMPOUND_SCHEMA_MUTATION = PropertyDescriptor.nullableFromObject(
		"createSortableAttributeCompoundSchemaMutation",
		CreateSortableAttributeCompoundSchemaMutationDescriptor.THIS_INPUT
	);
	PropertyDescriptor MODIFY_SORTABLE_ATTRIBUTE_COMPOUND_SCHEMA_DEPRECATION_NOTICE_MUTATION = PropertyDescriptor.nullableFromObject(
		"modifySortableAttributeCompoundSchemaDeprecationNoticeMutation",
		ModifySortableAttributeCompoundSchemaDeprecationNoticeMutationDescriptor.THIS_INPUT
	);
	PropertyDescriptor MODIFY_SORTABLE_ATTRIBUTE_COMPOUND_SCHEMA_DESCRIPTION_MUTATION = PropertyDescriptor.nullableFromObject(
		"modifySortableAttributeCompoundSchemaDescriptionMutation",
		ModifySortableAttributeCompoundSchemaDescriptionMutationDescriptor.THIS_INPUT
	);
	PropertyDescriptor MODIFY_SORTABLE_ATTRIBUTE_COMPOUND_SCHEMA_NAME_MUTATION = PropertyDescriptor.nullableFromObject(
		"modifySortableAttributeCompoundSchemaNameMutation",
		ModifySortableAttributeCompoundSchemaNameMutationDescriptor.THIS_INPUT
	);
	PropertyDescriptor SET_SORTABLE_ATTRIBUTE_COMPOUND_SCHEMA_INDEXED_MUTATION = PropertyDescriptor.nullableFromObject(
		"setSortableAttributeCompoundIndexedMutation",
		SetSortableAttributeCompoundIndexedMutationDescriptor.THIS_INPUT
	);
	PropertyDescriptor REMOVE_SORTABLE_ATTRIBUTE_COMPOUND_SCHEMA_MUTATION = PropertyDescriptor.nullableFromObject(
		"removeSortableAttributeCompoundSchemaMutation",
		RemoveSortableAttributeCompoundSchemaMutationDescriptor.THIS_INPUT
	);

	/**
	 * Reference schema mutations
	 */

	PropertyDescriptor CREATE_REFERENCE_SCHEMA_MUTATION = PropertyDescriptor.nullableFromObject(
		"createReferenceSchemaMutation",
		CreateReferenceSchemaMutationDescriptor.THIS_INPUT
	);
	PropertyDescriptor CREATE_REFLECTED_REFERENCE_SCHEMA_MUTATION = PropertyDescriptor.nullableFromObject(
		"createReflectedReferenceSchemaMutation",
		CreateReflectedReferenceSchemaMutationDescriptor.THIS_INPUT
	);
	PropertyDescriptor MODIFY_REFERENCE_ATTRIBUTE_SCHEMA_MUTATION = PropertyDescriptor.nullableFromObject(
		"modifyReferenceAttributeSchemaMutation",
		ModifyReferenceAttributeSchemaMutationDescriptor.THIS_INPUT
	);
	PropertyDescriptor MODIFY_REFERENCE_SCHEMA_CARDINALITY_MUTATION = PropertyDescriptor.nullableFromObject(
		"modifyReferenceSchemaCardinalityMutation",
		ModifyReferenceSchemaCardinalityMutationDescriptor.THIS_INPUT
	);
	PropertyDescriptor MODIFY_REFERENCE_SCHEMA_DEPRECATION_NOTICE_MUTATION = PropertyDescriptor.nullableFromObject(
		"modifyReferenceSchemaDeprecationNoticeMutation",
		ModifyReferenceSchemaDeprecationNoticeMutationDescriptor.THIS_INPUT
	);
	PropertyDescriptor MODIFY_REFERENCE_SCHEMA_DESCRIPTION_MUTATION = PropertyDescriptor.nullableFromObject(
		"modifyReferenceSchemaDescriptionMutation",
		ModifyReferenceSchemaDescriptionMutationDescriptor.THIS_INPUT
	);
	PropertyDescriptor MODIFY_REFERENCE_SCHEMA_NAME_MUTATION = PropertyDescriptor.nullableFromObject(
		"modifyReferenceSchemaNameMutation",
		ModifyReferenceSchemaNameMutationDescriptor.THIS_INPUT
	);
	PropertyDescriptor MODIFY_REFERENCE_SCHEMA_RELATED_ENTITY_GROUP_MUTATION = PropertyDescriptor.nullableFromObject(
		"modifyReferenceSchemaRelatedEntityGroupMutation",
		ModifyReferenceSchemaRelatedEntityGroupMutationDescriptor.THIS_INPUT
	);
	PropertyDescriptor MODIFY_REFERENCE_SCHEMA_RELATED_ENTITY_MUTATION = PropertyDescriptor.nullableFromObject(
		"modifyReferenceSchemaRelatedEntityMutation",
		ModifyReferenceSchemaRelatedEntityMutationDescriptor.THIS_INPUT
	);
	PropertyDescriptor MODIFY_REFERENCE_SORTABLE_ATTRIBUTE_COMPOUND_SCHEMA_MUTATION = PropertyDescriptor.nullableFromObject(
		"modifyReferenceSortableAttributeCompoundSchemaMutation",
		ModifyReferenceSortableAttributeCompoundSchemaMutationDescriptor.THIS_INPUT
	);
	PropertyDescriptor MODIFY_REFERENCE_SCHEMA_ATTRIBUTE_INHERITANCE_MUTATION = PropertyDescriptor.nullableFromObject(
		"modifyReflectedReferenceAttributeInheritanceSchemaMutation",
		ModifyReflectedReferenceAttributeInheritanceSchemaMutationDescriptor.THIS_INPUT
	);
	PropertyDescriptor REMOVE_REFERENCE_SCHEMA_MUTATION = PropertyDescriptor.nullableFromObject(
		"removeReferenceSchemaMutation",
		RemoveReferenceSchemaMutationDescriptor.THIS_INPUT
	);
	PropertyDescriptor SET_REFERENCE_SCHEMA_FACETED_MUTATION = PropertyDescriptor.nullableFromObject(
		"setReferenceSchemaFacetedMutation",
		SetReferenceSchemaFacetedMutationDescriptor.THIS_INPUT
	);
	PropertyDescriptor SET_REFERENCE_SCHEMA_INDEXED_MUTATION = PropertyDescriptor.nullableFromObject(
		"setReferenceSchemaIndexedMutation",
		SetReferenceSchemaIndexedMutationDescriptor.THIS_INPUT
	);

	ObjectDescriptor THIS_INPUT = ObjectDescriptor.builder()
		.name("EntitySchemaMutationInputAggregate")
		.description("""
            Contains all possible entity schema mutations.
            """)
		.staticProperties(List.of(
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
			SET_SORTABLE_ATTRIBUTE_COMPOUND_SCHEMA_INDEXED_MUTATION,
			REMOVE_SORTABLE_ATTRIBUTE_COMPOUND_SCHEMA_MUTATION,

			CREATE_REFERENCE_SCHEMA_MUTATION,
			CREATE_REFLECTED_REFERENCE_SCHEMA_MUTATION,
			MODIFY_REFERENCE_ATTRIBUTE_SCHEMA_MUTATION,
			MODIFY_REFERENCE_SCHEMA_CARDINALITY_MUTATION,
			MODIFY_REFERENCE_SCHEMA_DEPRECATION_NOTICE_MUTATION,
			MODIFY_REFERENCE_SCHEMA_DESCRIPTION_MUTATION,
			MODIFY_REFERENCE_SCHEMA_NAME_MUTATION,
			MODIFY_REFERENCE_SCHEMA_RELATED_ENTITY_GROUP_MUTATION,
			MODIFY_REFERENCE_SCHEMA_RELATED_ENTITY_MUTATION,
			REMOVE_REFERENCE_SCHEMA_MUTATION,
			MODIFY_REFERENCE_SORTABLE_ATTRIBUTE_COMPOUND_SCHEMA_MUTATION,
			MODIFY_REFERENCE_SCHEMA_ATTRIBUTE_INHERITANCE_MUTATION,
			SET_REFERENCE_SCHEMA_FACETED_MUTATION,
			SET_REFERENCE_SCHEMA_INDEXED_MUTATION
		))
		.build();
}
