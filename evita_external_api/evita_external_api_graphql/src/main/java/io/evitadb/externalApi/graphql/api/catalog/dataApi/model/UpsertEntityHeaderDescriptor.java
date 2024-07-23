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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.model;

import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.externalApi.api.catalog.dataApi.model.CatalogDataApiRootDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;

/**
 * Descriptor for header arguments of {@link CatalogDataApiRootDescriptor#UPSERT_ENTITY}
 * mutation.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public interface UpsertEntityHeaderDescriptor {

	PropertyDescriptor PRIMARY_KEY = PropertyDescriptor.builder()
		.name("primaryKey")
		.description("""
			Identification of upserted entity. If null or entity with passed primary key doesn't exist, new one is created.
			""")
		// type is expected to be an integer
		.build();
	PropertyDescriptor ENTITY_EXISTENCE = PropertyDescriptor.builder()
		.name("entityExistence")
		.description("""
			Controls behaviour of the upsert operation.
			""")
		.type(nonNull(EntityExistence.class))
		.build();
	PropertyDescriptor MUTATIONS = PropertyDescriptor.builder()
		.name("mutations")
		.description("""
			Individual mutations to apply to entity selected by primary key parameter.
			""")
		// type is expected to be a `LocalMutationAggregate` object
		.build();
	PropertyDescriptor REQUIRE = PropertyDescriptor.builder()
		.name("require")
		.description("""
			Limited require query to specify content of mutated entity
			""")
		// type is expected to be tree of require constraints
		.build();
}
