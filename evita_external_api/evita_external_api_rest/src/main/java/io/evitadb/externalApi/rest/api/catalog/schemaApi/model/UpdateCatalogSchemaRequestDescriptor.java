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

package io.evitadb.externalApi.rest.api.catalog.schemaApi.model;

import io.evitadb.externalApi.api.catalog.schemaApi.model.CatalogSchemaApiRootDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.LocalCatalogSchemaMutationAggregateDescriptor;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import java.util.List;

import static io.evitadb.externalApi.api.model.ObjectPropertyDataTypeDescriptor.nonNullListRef;

/**
 * Descriptor for request body of {@link CatalogSchemaApiRootDescriptor#UPDATE_ENTITY_SCHEMA} endpoint.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public interface UpdateCatalogSchemaRequestDescriptor {

	PropertyDescriptor MUTATIONS = PropertyDescriptor.builder()
		.name("mutations")
		.description("""
			Individual mutations to apply to entity schema to create new one or update existing.
			""")
		.type(nonNullListRef(LocalCatalogSchemaMutationAggregateDescriptor.THIS))
		.build();

	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("UpdateCatalogSchemaRequest")
		.description("""
			Updates existing entity schema with passed mutations and returns the updated entity schema.
			""")
		.staticFields(List.of(MUTATIONS))
		.build();
}
