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
public interface LocalCatalogSchemaMutationAggregateDescriptor {

	/**
	 * Catalog schema mutations
	 */

	PropertyDescriptor MODIFY_CATALOG_SCHEMA_DESCRIPTION_MUTATION = PropertyDescriptor.nullableFromObject(ModifyCatalogSchemaDescriptionMutationDescriptor.THIS);

	PropertyDescriptor ALLOW_EVOLUTION_MODE_IN_CATALOG_SCHEMA_MUTATION = PropertyDescriptor.nullableFromObject(AllowEvolutionModeInCatalogSchemaMutationDescriptor.THIS);

	PropertyDescriptor DISALLOW_EVOLUTION_MODE_IN_CATALOG_SCHEMA_MUTATION = PropertyDescriptor.nullableFromObject(DisallowEvolutionModeInCatalogSchemaMutationDescriptor.THIS);

	/*
	    Global attribute schema mutations
	 */

	PropertyDescriptor CREATE_GLOBAL_ATTRIBUTE_SCHEMA_MUTATION = PropertyDescriptor.nullableFromObject(CreateGlobalAttributeSchemaMutationDescriptor.THIS);
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
	PropertyDescriptor SET_ATTRIBUTE_SCHEMA_GLOBALLY_UNIQUE_MUTATION = PropertyDescriptor.nullableFromObject(SetAttributeSchemaGloballyUniqueMutationDescriptor.THIS);

	/*
		Entity mutation
	 */

	PropertyDescriptor CREATE_ENTITY_SCHEMA_MUTATION = PropertyDescriptor.nullableFromObject(CreateEntitySchemaMutationDescriptor.THIS);
	PropertyDescriptor MODIFY_ENTITY_SCHEMA_MUTATION = PropertyDescriptor.nullableFromObject(ModifyEntitySchemaMutationDescriptor.THIS);
	PropertyDescriptor MODIFY_ENTITY_SCHEMA_NAME_MUTATION = PropertyDescriptor.nullableFromObject(ModifyEntitySchemaNameMutationDescriptor.THIS);
	PropertyDescriptor REMOVE_ENTITY_SCHEMA_MUTATION = PropertyDescriptor.nullableFromObject(RemoveEntitySchemaMutationDescriptor.THIS);

	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("LocalCatalogSchemaMutationAggregate")
		.description("""
            Contains all possible catalog schema mutations.
            """)
		.staticFields(List.of(
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
