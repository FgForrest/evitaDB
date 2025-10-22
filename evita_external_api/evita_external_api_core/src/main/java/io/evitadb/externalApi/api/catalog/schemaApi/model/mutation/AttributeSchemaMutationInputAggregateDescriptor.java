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

import io.evitadb.api.requestResponse.schema.mutation.AttributeSchemaMutation;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.attribute.*;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import java.util.List;

/**
 * Descriptor of aggregate object containing all implementations of {@link AttributeSchemaMutation}
 * for schema-based external APIs.
 *
 * Note: this descriptor has static structure.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public interface AttributeSchemaMutationInputAggregateDescriptor {

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

	ObjectDescriptor THIS_INPUT = ObjectDescriptor.builder()
		.name("ReferenceAttributeSchemaMutationInputAggregate")
		.description("""
            Contains all possible attribute schema mutations.
            """)
		.staticProperties(List.of(
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
			SET_ATTRIBUTE_SCHEMA_SORTABLE_MUTATION,
			SET_ATTRIBUTE_SCHEMA_UNIQUE_MUTATION
		))
		.build();

}
