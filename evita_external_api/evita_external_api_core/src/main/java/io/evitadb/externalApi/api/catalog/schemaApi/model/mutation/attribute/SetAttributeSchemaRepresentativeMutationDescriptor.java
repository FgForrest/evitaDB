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

package io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.attribute;

import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import java.util.List;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;

/**
 * Descriptor representing {@link io.evitadb.api.requestResponse.schema.mutation.attribute.SetAttributeSchemaRepresentativeMutation}.
 *
 * Note: this descriptor has static structure.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public interface SetAttributeSchemaRepresentativeMutationDescriptor extends AttributeSchemaMutationDescriptor {

	PropertyDescriptor REPRESENTATIVE = PropertyDescriptor.builder()
		.name("representative")
		.description("""
            If an attribute is flagged as representative, it should be used in developer tools along with the entity's
	        primary key to describe the entity or reference to that entity. The flag is completely optional and doesn't
	        affect the core functionality of the database in any way. However, if it's used correctly, it can be very
	        helpful to developers in quickly finding their way around the data. There should be very few representative
	        attributes in the entity type, and the unique ones are usually the best to choose.			                                             	 
			""")
		.type(nonNull(Boolean.class))
		.build();

	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("SetAttributeSchemaRepresentativeMutation")
		.description("""
			Mutation is responsible for setting value to a `AttributeSchema.representative`
			in `EntitySchema`.
			Mutation can be used for altering also the existing `AttributeSchema` or
			`GlobalAttributeSchema` alone.
			""")
		.staticProperties(List.of(MUTATION_TYPE, NAME, REPRESENTATIVE))
		.build();
	ObjectDescriptor THIS_INPUT = ObjectDescriptor.from(THIS)
		.name("SetAttributeSchemaRepresentativeMutationInput")
		.staticProperties(List.of(NAME, REPRESENTATIVE))
		.build();
}
