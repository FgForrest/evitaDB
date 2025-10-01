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

import io.evitadb.api.requestResponse.schema.mutation.engine.DuplicateCatalogMutation;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;

/**
 * Descriptor for {@link DuplicateCatalogMutation}
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public interface DuplicateCatalogMutationDescriptor extends EngineMutationDescriptor {

	PropertyDescriptor NEW_CATALOG_NAME = PropertyDescriptor.builder()
		.name("newCatalogName")
		.description("""
			Name of the new catalog to create with duplicated contents.
			""")
		.type(nonNull(String.class))
		.build();

	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("DuplicateCatalogMutation")
		.description("""
			Mutation that duplicates a catalog with a new name, copying all contents from the source catalog.
			This mutation creates a new catalog with the specified name containing all the data and schema
			from the source catalog. The source catalog must exist and be in a valid state for duplication.
			""")
		.staticField(MUTATION_TYPE)
		.staticField(CATALOG_NAME)
		.staticField(NEW_CATALOG_NAME)
		.build();
}