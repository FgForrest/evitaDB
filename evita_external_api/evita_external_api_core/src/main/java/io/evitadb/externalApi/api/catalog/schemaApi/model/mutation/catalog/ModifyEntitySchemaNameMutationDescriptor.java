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

package io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.catalog;

import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;
import io.evitadb.externalApi.api.model.mutation.MutationDescriptor;

import java.util.List;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;

/**
 * Descriptor representing {@link io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaNameMutation}.
 *
 * Note: this descriptor has static structure.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public interface ModifyEntitySchemaNameMutationDescriptor extends MutationDescriptor {

	PropertyDescriptor NAME = PropertyDescriptor.builder()
		.name("name")
		.description("""
			Name of the entity schema the mutation is targeting.
			""")
		.type(nonNull(String.class))
		.build();
	PropertyDescriptor NEW_NAME = PropertyDescriptor.builder()
		.name("newName")
		.description("""
			New name of the entity schema the mutation is targeting.
			""")
		.type(nonNull(String.class))
		.build();
	PropertyDescriptor OVERWRITE_TARGET = PropertyDescriptor.builder()
		.name("overwriteTarget")
		.description("""
			Whether to overwrite entity collection with same name as the `newName` if found.
			""")
		.type(nonNull(Boolean.class))
		.build();

	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("ModifyEntitySchemaNameMutation")
		.description("""
			Mutation is responsible for renaming an existing `EntitySchema`.
			""")
		.staticFields(List.of(MUTATION_TYPE, NAME, NEW_NAME, OVERWRITE_TARGET))
		.build();
}
