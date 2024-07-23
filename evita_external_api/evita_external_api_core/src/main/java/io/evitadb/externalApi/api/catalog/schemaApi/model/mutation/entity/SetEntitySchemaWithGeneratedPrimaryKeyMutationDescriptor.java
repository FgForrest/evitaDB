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

package io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.entity;

import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import java.util.List;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;

/**
 * Descriptor representing {@link io.evitadb.api.requestResponse.schema.mutation.entity.SetEntitySchemaWithGeneratedPrimaryKeyMutation}.
 *
 * Note: this descriptor has static structure.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public interface SetEntitySchemaWithGeneratedPrimaryKeyMutationDescriptor {

	PropertyDescriptor WITH_GENERATED_PRIMARY_KEY = PropertyDescriptor.builder()
		.name("withGeneratedPrimaryKey")
		.description("""
			Whether primary keys of entities of this type will not be provided by the external systems and Evita
			is responsible for generating unique primary keys for the entity on insertion.
			
			Generated key is guaranteed to be unique, but may not represent continuous ascending series. Generated key
			will be always greater than zero.
			""")
		.type(nonNull(Boolean.class))
		.build();

	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("SetEntitySchemaWithGeneratedPrimaryKeyMutation")
		.description("""
			Mutation is responsible for setting a `EntitySchema.withGeneratedPrimaryKey`
			in `EntitySchema`.
			""")
		.staticFields(List.of(WITH_GENERATED_PRIMARY_KEY))
		.build();
}
