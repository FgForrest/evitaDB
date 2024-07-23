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

package io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.reference;

import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.attribute.ReferenceAttributeSchemaMutationAggregateDescriptor;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import java.util.List;

import static io.evitadb.externalApi.api.model.ObjectPropertyDataTypeDescriptor.nonNullRef;

/**
 * Descriptor representing {@link io.evitadb.api.requestResponse.schema.mutation.reference.ModifyReferenceSortableAttributeCompoundSchemaMutation}.
 *
 * Note: this descriptor has static structure.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public interface ModifyReferenceSortableAttributeCompoundSchemaMutationDescriptor extends ReferenceSchemaMutationDescriptor {

	PropertyDescriptor SORTABLE_ATTRIBUTE_COMPOUND_SCHEMA_MUTATION = PropertyDescriptor.builder()
		.name("sortableAttributeCompoundSchemaMutation")
		.description("""
            Nested sortable attribute compound schema mutation that mutates reference sortable attribute compounds of targeted reference.
			""")
		.type(nonNullRef(ReferenceAttributeSchemaMutationAggregateDescriptor.THIS))
		.build();

	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("ModifyReferenceSortableAttributeCompoundSchemaMutation")
		.description("""
			Mutation is a holder for a single `SortableAttributeCompoundSchema` that affect any
			of `ReferenceSchema.sortableAttributeCompound` in the `EntitySchema`.
			""")
		.staticFields(List.of(NAME, SORTABLE_ATTRIBUTE_COMPOUND_SCHEMA_MUTATION))
		.build();
}
