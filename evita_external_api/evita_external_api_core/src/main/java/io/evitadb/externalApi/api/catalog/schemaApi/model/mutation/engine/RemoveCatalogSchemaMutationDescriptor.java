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

package io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.engine;

import io.evitadb.externalApi.api.model.ObjectDescriptor;

import java.util.List;

/**
 * Descriptor interface that defines the structure for catalog schema removal mutations
 * in external API representations. This descriptor provides property definitions for
 * mutations that delete existing catalog schemas, enabling catalog removal operations
 * through the external API.
 *
 * @author Lukáš Hornych, 2023
 */
public interface RemoveCatalogSchemaMutationDescriptor extends EngineMutationDescriptor {

	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("RemoveCatalogSchemaMutation")
		.description("""
			Mutation is responsible for removing an existing catalog schema - or more precisely the catalog
			instance itself.
			""")
		.staticProperties(List.of(
			MUTATION_TYPE,
			CATALOG_NAME
		))
		.build();
	ObjectDescriptor THIS_INPUT = ObjectDescriptor.from(THIS)
		.name("RemoveCatalogSchemaMutationInput")
		.staticProperties(List.of(
			CATALOG_NAME
		))
		.build();
}
