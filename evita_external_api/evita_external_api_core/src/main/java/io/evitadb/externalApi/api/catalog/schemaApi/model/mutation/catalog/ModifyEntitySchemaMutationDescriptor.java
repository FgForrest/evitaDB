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

package io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.catalog;

import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.EntitySchemaMutationAggregateDescriptor;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;
import io.evitadb.externalApi.api.model.mutation.MutationDescriptor;

import java.util.List;

import static io.evitadb.externalApi.api.model.ObjectPropertyDataTypeDescriptor.nonNullListRef;
import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;

/**
 * Descriptor representing {@link io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation}.
 *
 * Note: this descriptor has static structure.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public interface ModifyEntitySchemaMutationDescriptor extends MutationDescriptor {

	PropertyDescriptor ENTITY_TYPE = PropertyDescriptor.builder()
		.name("entityType")
		.description("""
            Entity type of entity schema that will be affected by passed mutations.
			""")
		.type(nonNull(String.class))
		.build();
	// todo lho input version
	PropertyDescriptor SCHEMA_MUTATIONS = PropertyDescriptor.builder()
		.name("schemaMutations")
		.description("""
            Collection of mutations that should be applied on current version of the schema.
			""")
		.type(nonNullListRef(EntitySchemaMutationAggregateDescriptor.THIS))
		.build();

	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("ModifyEntitySchemaMutation")
		.description("""
			Mutation is a holder for a set of `EntitySchemaMutation` that affect a single entity schema within
			the `CatalogSchema`.
			""")
		.staticFields(List.of(MUTATION_TYPE, ENTITY_TYPE, SCHEMA_MUTATIONS))
		.build();
}
