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

import io.evitadb.api.requestResponse.schema.mutation.engine.SetCatalogStateMutation;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;

/**
 * Descriptor for {@link SetCatalogStateMutation}
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public interface SetCatalogStateMutationDescriptor extends TopLevelCatalogSchemaMutationDescriptor {

	PropertyDescriptor ACTIVE = PropertyDescriptor.builder()
		.name("active")
		.description("""
			Whether the catalog should be active or not. The active state determines the operational status of the catalog.
			""")
		.type(nonNull(Boolean.class))
		.build();

	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("SetCatalogStateMutation")
		.description("""
			Mutation that sets the active state of a catalog.
			This mutation allows controlling whether a particular catalog should be active or not.
			The active state determines the operational status of the catalog.
			""")
		.staticField(CATALOG_NAME)
		.staticField(ACTIVE)
		.build();
}