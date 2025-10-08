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

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nullable;

/**
 * Descriptor representing {@link io.evitadb.api.requestResponse.schema.mutation.associatedData.ModifyAssociatedDataSchemaDeprecationNoticeMutation}.
 *
 * Note: this descriptor has static structure.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public interface ModifyAssociatedDataSchemaDeprecationNoticeMutationDescriptor extends AssociatedDataSchemaMutationDescriptor {

	PropertyDescriptor DEPRECATION_NOTICE = PropertyDescriptor.builder()
		.name("deprecationNotice")
		.description("""
			Deprecation notice contains information about planned removal of this associated data from the model / client API.
			This allows to plan and evolve the schema allowing clients to adapt early to planned breaking changes.
			""")
		.type(nullable(String.class))
		.build();

	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("ModifyAssociatedDataSchemaDeprecationNoticeMutation")
		.description("""
			Mutation is responsible for setting value to a `AssociatedDataSchemaContract.deprecationNotice`
			in `EntitySchema`.
			Mutation can be used for altering also the existing `AssociatedDataSchema` alone.
			""")
		.staticFields(List.of(MUTATION_TYPE, NAME, DEPRECATION_NOTICE))
		.build();
}
