/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.externalApi.api.catalog.dataApi.model.mutation;

import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;
import io.evitadb.externalApi.api.model.mutation.MutationDescriptor;

import java.util.List;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;
import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nullable;

/**
 * TODO lho docs
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2025
 */
public interface EntityRemoveMutationDescriptor extends MutationDescriptor {

	PropertyDescriptor ENTITY_PRIMARY_KEY = PropertyDescriptor.builder()
		.name("entityPrimaryKey")
		.description("""
			The existing entity primary key allowing identification of the entity to modify.
			""")
		.type(nullable(Integer.class))
		.build();
	PropertyDescriptor ENTITY_TYPE = PropertyDescriptor.builder()
		.name("entityType")
		.description("""
            The name of entity schema of this entity. 
			""")
		.type(nonNull(String.class))
		.build();

	// todo lho register in api
	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("EntityRemoveMutation")
		.description("""
			EntityRemoveMutation represents a terminal mutation when existing entity is removed in the evitaDB. The entity is
			and all its internal data are marked as TRUE for dropped, stored to the storage file and
			removed from the mem-table.
			""")
		.staticFields(List.of(
		    MUTATION_TYPE,
		    ENTITY_PRIMARY_KEY,
		    ENTITY_TYPE
		))
		.build();
}
