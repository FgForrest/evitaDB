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
import io.evitadb.externalApi.api.catalog.schemaApi.model.ScopedReferenceIndexedComponentsDescriptor;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import static io.evitadb.externalApi.api.model.TypePropertyDataTypeDescriptor.nonNullListRef;
import static io.evitadb.externalApi.api.model.TypePropertyDataTypeDescriptor.nullableListRef;

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
			Contains information about scopes and index types for this reference that should be created and maintained
			allowing to filter by `reference_{reference name}_having` filtering constraints and sorted by
			`reference_{reference name}_property` constraints. Index is also required when reference is `faceted` -
			but it has to be indexed in the same scope as faceted.
			
			Do not mark reference as indexed unless you know that you'll need to filter/sort entities by this reference.
			Each indexed reference occupies (memory/disk) space in the form of index. When reference is not indexed,
			the entity cannot be looked up by reference attributes or relation existence itself, but the data is loaded
			alongside other references and is available by calling entity reference methods.
			
			The index type determines the level of indexing optimization applied to improve query performance when
			filtering by reference constraints. The index type affects both memory/disk usage and query performance.
			Maintaining partitioned indexes provides better query performance at the cost of increased storage
			requirements and maintenance overhead.
			
			Returns array of scopes and their corresponding reference index types in which this reference is indexed.
			""")
		.type(nonNullListRef(ScopedReferenceIndexTypeDescriptor.THIS))
		.build();
	PropertyDescriptor INDEXED_IN_SCOPES_INPUT = PropertyDescriptor.from(INDEXED_IN_SCOPES)
		.type(nonNullListRef(ScopedReferenceIndexTypeDescriptor.THIS_INPUT))
		.build();

	PropertyDescriptor INDEXED_COMPONENTS_IN_SCOPES = PropertyDescriptor.builder()
		.name("indexedComponentsInScopes")
		.description("""
			Contains information about which parts of the reference relationship are indexed per scope.
			Controls whether the referenced entity itself, the referenced group entity, or both
			are maintained in the index for filtering and querying operations.

			When not specified, the default indexed components are determined automatically based on
			the reference schema configuration (e.g. whether a group type is defined).
			""")
		.type(nullableListRef(ScopedReferenceIndexedComponentsDescriptor.THIS))
		.build();
	PropertyDescriptor INDEXED_COMPONENTS_IN_SCOPES_INPUT = PropertyDescriptor.from(INDEXED_COMPONENTS_IN_SCOPES)
		.type(nullableListRef(ScopedReferenceIndexedComponentsDescriptor.THIS_INPUT))
		.build();

	ObjectDescriptor THIS = ObjectDescriptor.implementing(THIS_INTERFACE)
		.representedClass(SetReferenceSchemaIndexedMutation.class)
		.description("""
			Mutation is responsible for setting value to a `ReferenceSchema.indexed` in `EntitySchema`.
			Mutation can be used for altering also the existing `ReferenceSchema` alone.
			""")
		.staticProperty(NAME)
		.staticProperty(INDEXED_IN_SCOPES)
		.staticProperty(INDEXED_COMPONENTS_IN_SCOPES)
		.build();
	ObjectDescriptor THIS_INPUT = ObjectDescriptor.from(THIS, INPUT_OBJECT_PROPERTIES_FILTER)
		.name("SetReferenceSchemaIndexedMutationInput")
		.staticProperty(INDEXED_IN_SCOPES_INPUT)
		.staticProperty(INDEXED_COMPONENTS_IN_SCOPES_INPUT)
		.build();
}
