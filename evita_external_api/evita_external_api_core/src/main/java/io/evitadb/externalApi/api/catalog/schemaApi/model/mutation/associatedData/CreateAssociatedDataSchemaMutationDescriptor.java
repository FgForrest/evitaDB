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

package io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.associatedData;

import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import java.util.List;

import static io.evitadb.externalApi.api.catalog.model.CatalogRootDescriptor.SCALAR_ENUM;
import static io.evitadb.externalApi.api.model.TypePropertyDataTypeDescriptor.nonNullRef;
import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nullable;

/**
 * Descriptor representing {@link io.evitadb.api.requestResponse.schema.mutation.associatedData.CreateAssociatedDataSchemaMutation}.
 *
 * Note: this descriptor has static structure.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public interface CreateAssociatedDataSchemaMutationDescriptor extends AssociatedDataSchemaMutationDescriptor {

	PropertyDescriptor DESCRIPTION = PropertyDescriptor.builder()
		.name("description")
		.description("""
			Contains description of the model is optional but helps authors of the schema / client API to better
			explain the original purpose of the model to the consumers.
			""")
		.type(nullable(String.class))
		.build();
	PropertyDescriptor DEPRECATION_NOTICE = PropertyDescriptor.builder()
		.name("deprecationNotice")
		.description("""
			Deprecation notice contains information about planned removal of this associated data from the model / client API.
			This allows to plan and evolve the schema allowing clients to adapt early to planned breaking changes.
			""")
		.type(nullable(String.class))
		.build();
	PropertyDescriptor TYPE = PropertyDescriptor.builder()
		.name("type")
		.description("""
			Contains the data type of the entity. Must be one of supported types or may
			represent complex type - which is JSON object that can be automatically converted
			to the set of basic types.
			""")
		.type(nonNullRef(SCALAR_ENUM))
		.build();
	PropertyDescriptor LOCALIZED = PropertyDescriptor.builder()
		.name("localized")
		.description("""
			Localized associated data has to be ALWAYS used in connection with specific `locale`. In other
			words - it cannot be stored unless associated locale is also provided.
			""")
		.type(nullable(Boolean.class))
		.build();
	PropertyDescriptor NULLABLE = PropertyDescriptor.builder()
		.name("nullable")
		.description("""
			When associated data is nullable, its values may be missing in the entities. Otherwise, the system will enforce
			non-null checks upon upserting of the entity.
			""")
		.type(nullable(Boolean.class))
		.build();

	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("CreateAssociatedDataSchemaMutation")
		.description("""
			Mutation is responsible for setting up a new `AssociatedDataSchema` in the `EntitySchema`.
			Mutation can be used for altering also the existing `AssociatedDataSchema` alone.
			""")
		.staticProperties(List.of(MUTATION_TYPE, NAME, DESCRIPTION, DEPRECATION_NOTICE, TYPE, LOCALIZED, NULLABLE))
		.build();
	ObjectDescriptor THIS_INPUT = ObjectDescriptor.from(THIS)
		.name("CreateAssociatedDataSchemaMutationInput")
		.staticProperties(List.of(NAME, DESCRIPTION, DEPRECATION_NOTICE, TYPE, LOCALIZED, NULLABLE))
		.build();
}
