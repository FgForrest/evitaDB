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
 * Descriptor representing {@link io.evitadb.api.requestResponse.schema.mutation.attribute.SetAttributeSchemaNullableMutation}.
 *
 * Note: this descriptor has static structure.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public interface SetAttributeSchemaNullableMutationDescriptor extends AttributeSchemaMutationDescriptor {

	PropertyDescriptor NULLABLE = PropertyDescriptor.builder()
		.name("nullable")
		.description("""
			When attribute is nullable, its values may be missing in the entities. Otherwise, the system will enforce
			non-null checks upon upserting of the entity.
			""")
		.type(nonNull(Boolean.class))
		.build();

	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("SetAttributeSchemaNullableMutation")
		.description("""
			Mutation is responsible for setting value to a `AttributeSchema.nullable`
			in `EntitySchema`.
			Mutation can be used for altering also the existing `AttributeSchema` or
			`GlobalAttributeSchema` alone.
			""")
		.staticProperties(List.of(MUTATION_TYPE, NAME, NULLABLE))
		.build();
	ObjectDescriptor THIS_INPUT = ObjectDescriptor.from(THIS)
		.name("SetAttributeSchemaNullableMutationInput")
		.staticProperties(List.of(NAME, NULLABLE))
		.build();
}
