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

package io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.reference;

import io.evitadb.api.requestResponse.schema.mutation.reference.SetReferenceSchemaIndexedMutation;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ScopedReferenceIndexTypeDescriptor;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import java.util.List;

import static io.evitadb.externalApi.api.model.ObjectPropertyDataTypeDescriptor.nonNullListRef;

/**
 * Descriptor representing {@link SetReferenceSchemaIndexedMutation}.
 *
 * Note: this descriptor has static structure.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public interface SetReferenceSchemaIndexedMutationDescriptor extends ReferenceSchemaMutationDescriptor {

	PropertyDescriptor INDEXED_IN_SCOPES = PropertyDescriptor.builder()
		.name("indexedInScopes")
		.description("""
			Whether the index for this reference should be created and maintained allowing to filter by
			`reference_{reference name}_having` filtering constraints. Index is also required when reference is
			`faceted`.
			
			Do not mark reference as faceted unless you know that you'll need to filter / sort entities by this reference.
			Each indexed reference occupies (memory/disk) space in the form of index. When reference is not indexed, 
			the entity cannot be looked up by reference attributes or relation existence itself, but the data is loaded 
			alongside other references if requested.
			
			This array defines in which scopes the reference will be indexed. It will not be indexed in not-specified scopes.
			""")
		.type(nonNullListRef(ScopedReferenceIndexTypeDescriptor.THIS_INPUT))
		.build();

	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("SetReferenceSchemaIndexedMutation")
		.description("""
			Mutation is responsible for setting value to a `ReferenceSchema.indexed` in `EntitySchema`.
			Mutation can be used for altering also the existing `ReferenceSchema` alone.
			""")
		.staticFields(List.of(NAME, INDEXED_IN_SCOPES))
		.build();
}
