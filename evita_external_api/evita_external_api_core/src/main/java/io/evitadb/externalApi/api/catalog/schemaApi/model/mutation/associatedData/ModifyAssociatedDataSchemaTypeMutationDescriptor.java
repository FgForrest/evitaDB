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

package io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.associatedData;

import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import java.util.List;

import static io.evitadb.externalApi.api.catalog.model.CatalogRootDescriptor.SCALAR_ENUM;
import static io.evitadb.externalApi.api.model.ObjectPropertyDataTypeDescriptor.nonNullRef;

/**
 * Descriptor representing {@link io.evitadb.api.requestResponse.schema.mutation.associatedData.ModifyAssociatedDataSchemaTypeMutation}.
 *
 * Note: this descriptor has static structure.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public interface ModifyAssociatedDataSchemaTypeMutationDescriptor extends AssociatedDataSchemaMutationDescriptor {

	PropertyDescriptor TYPE = PropertyDescriptor.builder()
		.name("type")
		.description("""
			Contains the data type of the entity. Must be one of supported types or may
			represent complex type - which is JSON object that can be automatically converted
			to the set of basic types.
			""")
		.type(nonNullRef(SCALAR_ENUM))
		.build();

	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("ModifyAssociatedDataSchemaTypeMutation")
		.description("""
			Mutation is responsible for setting value to a `AssociatedDataSchema.type`
			in `EntitySchema`.
			Mutation can be used for altering also the existing `AssociatedDataSchema` alone.
			""")
		.staticFields(List.of(NAME, TYPE))
		.build();
}
