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

import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.catalog.CreateCatalogSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.catalog.DuplicateCatalogMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.catalog.MakeCatalogAliveMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.catalog.ModifyCatalogSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.catalog.ModifyCatalogSchemaNameMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.catalog.RemoveCatalogSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.catalog.RestoreCatalogSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.catalog.SetCatalogMutabilityMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.catalog.SetCatalogStateMutationDescriptor;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import java.util.List;

/**
 * Descriptor interface that defines the structure for aggregated top-level catalog schema mutations
 * in external API representations. This descriptor provides property definitions for various types
 * of catalog schema mutations that can be performed at the top level, including creation, modification,
 * and removal operations.
 *
 * @author Lukáš Hornych, 2023
 */
public interface TopLevelCatalogSchemaMutationAggregateDescriptor {

	PropertyDescriptor CREATE_CATALOG_SCHEMA_MUTATION = PropertyDescriptor.nullableFromObject(CreateCatalogSchemaMutationDescriptor.THIS);
	PropertyDescriptor RESTORE_CATALOG_SCHEMA_MUTATION = PropertyDescriptor.nullableFromObject(RestoreCatalogSchemaMutationDescriptor.THIS);
	PropertyDescriptor DUPLICATE_CATALOG_MUTATION = PropertyDescriptor.nullableFromObject(DuplicateCatalogMutationDescriptor.THIS);
	PropertyDescriptor MAKE_CATALOG_ALIVE_MUTATION = PropertyDescriptor.nullableFromObject(MakeCatalogAliveMutationDescriptor.THIS);
	PropertyDescriptor SET_CATALOG_MUTABILITY_MUTATION = PropertyDescriptor.nullableFromObject(SetCatalogMutabilityMutationDescriptor.THIS);
	PropertyDescriptor SET_CATALOG_STATE_MUTATION = PropertyDescriptor.nullableFromObject(SetCatalogStateMutationDescriptor.THIS);
	PropertyDescriptor MODIFY_CATALOG_SCHEMA_MUTATION = PropertyDescriptor.nullableFromObject(ModifyCatalogSchemaMutationDescriptor.THIS);
	PropertyDescriptor MODIFY_CATALOG_SCHEMA_NAME_MUTATION = PropertyDescriptor.nullableFromObject(ModifyCatalogSchemaNameMutationDescriptor.THIS);
	PropertyDescriptor REMOVE_CATALOG_SCHEMA_MUTATION = PropertyDescriptor.nullableFromObject(RemoveCatalogSchemaMutationDescriptor.THIS);

	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("TopLevelCatalogSchemaMutationAggregate")
		.description("""
            Contains all possible top level catalog schema mutations.
            """)
		.staticFields(List.of(
			CREATE_CATALOG_SCHEMA_MUTATION,
			RESTORE_CATALOG_SCHEMA_MUTATION,
			DUPLICATE_CATALOG_MUTATION,
			MAKE_CATALOG_ALIVE_MUTATION,
			SET_CATALOG_MUTABILITY_MUTATION,
			SET_CATALOG_STATE_MUTATION,
			MODIFY_CATALOG_SCHEMA_MUTATION,
			MODIFY_CATALOG_SCHEMA_NAME_MUTATION,
			REMOVE_CATALOG_SCHEMA_MUTATION
		))
		.build();
}
