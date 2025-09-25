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
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;

/**
 * Descriptor interface that defines the structure for catalog schema name modification mutations
 * in external API representations. This descriptor provides property definitions for
 * mutations that rename existing catalog schemas, allowing changes to the catalog name
 * through the external API.
 *
 * @author Lukáš Hornych, 2023
 */
public interface ModifyCatalogSchemaNameMutationDescriptor extends EngineMutationDescriptor {

	PropertyDescriptor NEW_CATALOG_NAME = PropertyDescriptor.builder()
		.name("newCatalogName")
		.description("""
			New name of the catalog schema the mutation is targeting.
			""")
		.type(nonNull(String.class))
		.build();
	PropertyDescriptor OVERWRITE_TARGET = PropertyDescriptor.builder()
		.name("overwriteTarget")
		.description("""
			Whether to overwrite catalog with same name as the `newCatalogName` if found.
			""")
		.type(nonNull(Boolean.class))
		.build();

	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("ModifyCatalogSchemaNameMutation")
		.description("""
			Mutation is responsible for renaming an existing catalog schema.
			""")
		.staticField(CATALOG_NAME)
		.staticField(NEW_CATALOG_NAME)
		.staticField(OVERWRITE_TARGET)
		.build();
}
