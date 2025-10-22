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

package io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.sortableAttributeCompound;

import io.evitadb.api.requestResponse.schema.mutation.reference.SetReferenceSchemaIndexedMutation;
import io.evitadb.dataType.Scope;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import java.util.List;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nullable;

/**
 * Descriptor representing {@link SetReferenceSchemaIndexedMutation}.
 *
 * Note: this descriptor has static structure.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public interface SetSortableAttributeCompoundIndexedMutationDescriptor extends SortableAttributeCompoundSchemaMutationDescriptor {

	PropertyDescriptor INDEXED_IN_SCOPES = PropertyDescriptor.builder()
		.name("indexedInScopes")
		.description("""
			When attribute sortable compound is indexed, it is possible to sort entities by this calculated attribute compound.
			This property specifies set of all scopes this attribute compound is indexed in.
			""")
		.type(nullable(Scope[].class))
		.build();

	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("SetSortableAttributeCompoundIndexedMutation")
		.description("""
			Mutation is responsible for setting set of scopes for indexing value in a `SortableAttributeCompoundSchema` in `EntitySchema`.
			""")
		.staticProperties(List.of(MUTATION_TYPE, NAME, INDEXED_IN_SCOPES))
		.build();
	ObjectDescriptor THIS_INPUT = ObjectDescriptor.from(THIS)
		.name("SetSortableAttributeCompoundIndexedMutationInput")
		.staticProperties(List.of(NAME, INDEXED_IN_SCOPES))
		.build();
}
