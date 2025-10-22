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
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.attribute.*;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.catalog.AllowEvolutionModeInCatalogSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.catalog.CreateEntitySchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.catalog.DisallowEvolutionModeInCatalogSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.catalog.ModifyCatalogSchemaDescriptionMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.catalog.ModifyEntitySchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.catalog.ModifyEntitySchemaNameMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.catalog.RemoveEntitySchemaMutationDescriptor;
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
public interface LocalCatalogSchemaMutationInputAggregateDescriptor {

	/**
	 * Catalog schema mutations
	 */

	PropertyDescriptor MODIFY_CATALOG_SCHEMA_DESCRIPTION_MUTATION = PropertyDescriptor.nullableFromObject(
		"modifyCatalogSchemaDescriptionMutation",
		ModifyCatalogSchemaDescriptionMutationDescriptor.THIS_INPUT
	);

	PropertyDescriptor ALLOW_EVOLUTION_MODE_IN_CATALOG_SCHEMA_MUTATION = PropertyDescriptor.nullableFromObject(
		"allowEvolutionModeInCatalogSchemaMutation",
		AllowEvolutionModeInCatalogSchemaMutationDescriptor.THIS_INPUT
	);

	PropertyDescriptor DISALLOW_EVOLUTION_MODE_IN_CATALOG_SCHEMA_MUTATION = PropertyDescriptor.nullableFromObject(
		"disallowEvolutionModeInCatalogSchemaMutation",
		DisallowEvolutionModeInCatalogSchemaMutationDescriptor.THIS_INPUT
	);

	/*
	    Global attribute schema mutations
	 */

	PropertyDescriptor CREATE_GLOBAL_ATTRIBUTE_SCHEMA_MUTATION = PropertyDescriptor.nullableFromObject(
		"createGlobalAttributeSchemaMutation",
		CreateGlobalAttributeSchemaMutationDescriptor.THIS_INPUT
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
	PropertyDescriptor SET_ATTRIBUTE_SCHEMA_GLOBALLY_UNIQUE_MUTATION = PropertyDescriptor.nullableFromObject(
		"setAttributeSchemaGloballyUniqueMutation",
		SetAttributeSchemaGloballyUniqueMutationDescriptor.THIS_INPUT
	);

	/*
		Entity mutation
	 */

	PropertyDescriptor CREATE_ENTITY_SCHEMA_MUTATION = PropertyDescriptor.nullableFromObject(
		"createEntitySchemaMutation",
		CreateEntitySchemaMutationDescriptor.THIS_INPUT
	);
	PropertyDescriptor MODIFY_ENTITY_SCHEMA_MUTATION = PropertyDescriptor.nullableFromObject(
		"modifyEntitySchemaMutation",
		ModifyEntitySchemaMutationDescriptor.THIS_INPUT
	);
	PropertyDescriptor MODIFY_ENTITY_SCHEMA_NAME_MUTATION = PropertyDescriptor.nullableFromObject(
		"modifyEntitySchemaNameMutation",
		ModifyEntitySchemaNameMutationDescriptor.THIS_INPUT
	);
	PropertyDescriptor REMOVE_ENTITY_SCHEMA_MUTATION = PropertyDescriptor.nullableFromObject(
		"removeEntitySchemaMutation",
		RemoveEntitySchemaMutationDescriptor.THIS_INPUT
	);

	ObjectDescriptor THIS_INPUT = ObjectDescriptor.builder()
		.name("LocalCatalogSchemaMutationInputAggregate")
		.description("""
            Contains all possible catalog schema mutations.
            """)
		.staticProperties(List.of(
			MODIFY_CATALOG_SCHEMA_DESCRIPTION_MUTATION,
			ALLOW_EVOLUTION_MODE_IN_CATALOG_SCHEMA_MUTATION,
			DISALLOW_EVOLUTION_MODE_IN_CATALOG_SCHEMA_MUTATION,

			CREATE_GLOBAL_ATTRIBUTE_SCHEMA_MUTATION,
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
			SET_ATTRIBUTE_SCHEMA_UNIQUE_MUTATION,
			SET_ATTRIBUTE_SCHEMA_GLOBALLY_UNIQUE_MUTATION,

			CREATE_ENTITY_SCHEMA_MUTATION,
			MODIFY_ENTITY_SCHEMA_MUTATION,
			MODIFY_ENTITY_SCHEMA_NAME_MUTATION,
			REMOVE_ENTITY_SCHEMA_MUTATION
		))
		.build();
}
