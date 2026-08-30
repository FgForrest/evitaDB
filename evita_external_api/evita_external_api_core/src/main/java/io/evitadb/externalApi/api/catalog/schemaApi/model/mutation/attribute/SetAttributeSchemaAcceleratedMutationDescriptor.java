/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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


package io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.attribute;

import io.evitadb.api.requestResponse.schema.mutation.attribute.SetAttributeSchemaAcceleratedMutation;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ScopedAttributeFilterAcceleratorsDescriptor;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import static io.evitadb.externalApi.api.model.TypePropertyDataTypeDescriptor.nullableListRef;

/**
 * Descriptor representing {@link SetAttributeSchemaAcceleratedMutation}.
 *
 * Note: this descriptor has static structure.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public interface SetAttributeSchemaAcceleratedMutationDescriptor extends AttributeSchemaMutationDescriptor {

	PropertyDescriptor ACCELERATORS_IN_SCOPES = PropertyDescriptor.builder()
		.name("acceleratorsInScopes")
		.description("""
			Optional accelerations the attribute's filter index should maintain, per scope. Each accelerator costs
			additional memory and additional write-path work, so none of them is implied by making the attribute
			filterable or unique - only the ones listed here are maintained.

			This is a full statement of the accelerator axis: a scope not named here ends up with no acceleration. The
			property is optional, and omitting it withdraws every accelerator the attribute declared. A scope may only
			be named when the attribute has a filter index there - i.e. it is filterable or unique in that scope.
			""")
		.type(nullableListRef(ScopedAttributeFilterAcceleratorsDescriptor.THIS))
		.build();
	PropertyDescriptor ACCELERATORS_IN_SCOPES_INPUT = PropertyDescriptor.from(ACCELERATORS_IN_SCOPES)
		.type(nullableListRef(ScopedAttributeFilterAcceleratorsDescriptor.THIS_INPUT))
		.build();

	ObjectDescriptor THIS = ObjectDescriptor.implementing(THIS_INTERFACE)
		.representedClass(SetAttributeSchemaAcceleratedMutation.class)
		.description("""
			Mutation is responsible for setting the optional filter accelerators of an `AttributeSchema`
			in `EntitySchema`.
			Mutation can be used for altering also the existing `AttributeSchema` or
			`GlobalAttributeSchema` alone.
			""")
		.staticProperty(NAME)
		.staticProperty(ACCELERATORS_IN_SCOPES)
		.build();
	ObjectDescriptor THIS_INPUT = ObjectDescriptor.from(THIS, INPUT_OBJECT_PROPERTIES_FILTER)
		.name("SetAttributeSchemaAcceleratedMutationInput")
		.staticProperty(ACCELERATORS_IN_SCOPES_INPUT)
		.build();
}
