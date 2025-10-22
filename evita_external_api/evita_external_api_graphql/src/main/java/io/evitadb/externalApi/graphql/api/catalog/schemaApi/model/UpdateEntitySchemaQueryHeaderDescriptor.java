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

package io.evitadb.externalApi.graphql.api.catalog.schemaApi.model;

import io.evitadb.externalApi.api.catalog.schemaApi.model.CatalogSchemaApiRootDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.EntitySchemaMutationInputAggregateDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import static io.evitadb.externalApi.api.model.TypePropertyDataTypeDescriptor.nonNullListRef;

/**
 * Descriptor for header arguments of {@link CatalogSchemaApiRootDescriptor#UPDATE_CATALOG_SCHEMA}
 * query.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public interface UpdateEntitySchemaQueryHeaderDescriptor {

	PropertyDescriptor MUTATIONS = PropertyDescriptor.builder()
		.name("mutations")
		.description("""
			Individual mutations to apply to entity schema.
			""")
		.type(nonNullListRef(EntitySchemaMutationInputAggregateDescriptor.THIS_INPUT))
		.build();
}
