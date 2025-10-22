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

import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import java.util.List;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;
import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nullable;

/**
 * Descriptor representing {@link io.evitadb.api.requestResponse.schema.mutation.reference.ModifyReferenceSchemaRelatedEntityGroupMutation}.
 *
 * Note: this descriptor has static structure.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public interface ModifyReferenceSchemaRelatedEntityGroupMutationDescriptor extends ReferenceSchemaMutationDescriptor {

	PropertyDescriptor REFERENCED_GROUP_TYPE = PropertyDescriptor.builder()
		.name("referencedGroupType")
		.description("""
			Reference to `EntitySchema.name` of the referenced group entity. Might be also any `String`
			that identifies type some external resource not maintained by Evita.
			""")
		.type(nullable(String.class))
		.build();
	PropertyDescriptor REFERENCED_GROUP_TYPE_MANAGED = PropertyDescriptor.builder()
		.name("referencedGroupTypeManaged")
		.description("""
			Whether `referencedGroupType` refers to any existing `EntitySchema.name` that is
			maintained by Evita.
			""")
		.type(nonNull(Boolean.class))
		.build();

	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("ModifyReferenceSchemaRelatedEntityGroupMutation")
		.description("""
			Mutation is responsible for setting value to a `ReferenceSchema.referencedGroupType`
			in `EntitySchema`.
			""")
		.staticProperties(List.of(MUTATION_TYPE, NAME, REFERENCED_GROUP_TYPE, REFERENCED_GROUP_TYPE_MANAGED))
		.build();
	ObjectDescriptor THIS_INPUT = ObjectDescriptor.from(THIS)
		.name("ModifyReferenceSchemaRelatedEntityGroupMutationInput")
		.staticProperties(List.of(NAME, REFERENCED_GROUP_TYPE, REFERENCED_GROUP_TYPE_MANAGED))
		.build();
}
