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

import io.evitadb.api.requestResponse.schema.mutation.SortableAttributeCompoundSchemaMutation;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.sortableAttributeCompound.ModifySortableAttributeCompoundSchemaDeprecationNoticeMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.sortableAttributeCompound.ModifySortableAttributeCompoundSchemaDescriptionMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.sortableAttributeCompound.ModifySortableAttributeCompoundSchemaNameMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.sortableAttributeCompound.RemoveSortableAttributeCompoundSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.sortableAttributeCompound.SetSortableAttributeCompoundIndexedMutationDescriptor;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import java.util.List;

/**
 * Descriptor of aggregate object containing all implementations of {@link SortableAttributeCompoundSchemaMutation}
 * for schema-based external APIs.
 *
 * Note: this descriptor has static structure.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public interface SortableAttributeCompoundSchemaMutationAggregateDescriptor {

	PropertyDescriptor CREATE_SORTABLE_ATTRIBUTE_COMPOUND_SCHEMA_MUTATION = PropertyDescriptor.nullableFromObject(
		RemoveSortableAttributeCompoundSchemaMutationDescriptor.THIS);
	PropertyDescriptor MODIFY_SORTABLE_ATTRIBUTE_COMPOUND_SCHEMA_DEPRECATION_NOTICE_MUTATION = PropertyDescriptor.nullableFromObject(
		ModifySortableAttributeCompoundSchemaDeprecationNoticeMutationDescriptor.THIS);
	PropertyDescriptor MODIFY_SORTABLE_ATTRIBUTE_COMPOUND_SCHEMA_DESCRIPTION_MUTATION = PropertyDescriptor.nullableFromObject(
		ModifySortableAttributeCompoundSchemaDescriptionMutationDescriptor.THIS);
	PropertyDescriptor MODIFY_SORTABLE_ATTRIBUTE_COMPOUND_SCHEMA_NAME_MUTATION = PropertyDescriptor.nullableFromObject(
		ModifySortableAttributeCompoundSchemaNameMutationDescriptor.THIS);
	PropertyDescriptor SET_SORTABLE_ATTRIBUTE_COMPOUND_SCHEMA_INDEXED_MUTATION = PropertyDescriptor.nullableFromObject(
		SetSortableAttributeCompoundIndexedMutationDescriptor.THIS);
	PropertyDescriptor REMOVE_SORTABLE_ATTRIBUTE_COMPOUND_SCHEMA_MUTATION = PropertyDescriptor.nullableFromObject(RemoveSortableAttributeCompoundSchemaMutationDescriptor.THIS);

	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("ReferenceSortableAttributeCompoundSchemaMutationAggregate")
		.description("""
            Contains all possible sortable attribute compound schema mutations.
            """)
		.staticFields(List.of(
			CREATE_SORTABLE_ATTRIBUTE_COMPOUND_SCHEMA_MUTATION,
			MODIFY_SORTABLE_ATTRIBUTE_COMPOUND_SCHEMA_DEPRECATION_NOTICE_MUTATION,
			MODIFY_SORTABLE_ATTRIBUTE_COMPOUND_SCHEMA_DESCRIPTION_MUTATION,
			MODIFY_SORTABLE_ATTRIBUTE_COMPOUND_SCHEMA_NAME_MUTATION,
			SET_SORTABLE_ATTRIBUTE_COMPOUND_SCHEMA_INDEXED_MUTATION,
			REMOVE_SORTABLE_ATTRIBUTE_COMPOUND_SCHEMA_MUTATION
		))
		.build();

}
