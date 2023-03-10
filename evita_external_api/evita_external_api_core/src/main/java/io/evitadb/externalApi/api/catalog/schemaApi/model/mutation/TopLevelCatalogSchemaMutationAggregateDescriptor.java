/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.externalApi.api.catalog.schemaApi.model.mutation;

import io.evitadb.api.requestResponse.schema.mutation.TopLevelCatalogSchemaMutation;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.catalog.CreateCatalogSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.catalog.ModifyCatalogSchemaNameMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.catalog.RemoveCatalogSchemaMutationDescriptor;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import java.util.List;

/**
 * Descriptor of aggregate object containing all implementations of {@link TopLevelCatalogSchemaMutation}
 * for schema-based external APIs.
 *
 * Note: this descriptor has static structure.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public interface TopLevelCatalogSchemaMutationAggregateDescriptor {

	PropertyDescriptor CREATE_CATALOG_SCHEMA_MUTATION = PropertyDescriptor.nullableFromObject(CreateCatalogSchemaMutationDescriptor.THIS);
	PropertyDescriptor MODIFY_CATALOG_SCHEMA_NAME_MUTATION = PropertyDescriptor.nullableFromObject(ModifyCatalogSchemaNameMutationDescriptor.THIS);
	PropertyDescriptor REMOVE_CATALOG_SCHEMA_MUTATION = PropertyDescriptor.nullableFromObject(RemoveCatalogSchemaMutationDescriptor.THIS);

	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("TopLevelCatalogSchemaMutationAggregate")
		.description("""
            Contains all possible top level catalog schema mutations.
            """)
		.staticFields(List.of(
			CREATE_CATALOG_SCHEMA_MUTATION,
			MODIFY_CATALOG_SCHEMA_NAME_MUTATION,
			REMOVE_CATALOG_SCHEMA_MUTATION
		))
		.build();
}
