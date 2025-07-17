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

import io.evitadb.api.requestResponse.schema.mutation.engine.MakeCatalogAliveMutation;
import io.evitadb.externalApi.api.model.ObjectDescriptor;

/**
 * Descriptor for {@link MakeCatalogAliveMutation}
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public interface MakeCatalogAliveMutationDescriptor extends TopLevelCatalogSchemaMutationDescriptor {

	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("MakeCatalogAliveMutation")
		.description("""
			Mutation that transitions a catalog to the "live" state, making it transactional.
			When a catalog goes live, it becomes fully operational and can participate in transactions.
			This is a one-way operation that changes the catalog's operational state.
			""")
		.staticField(CATALOG_NAME)
		.build();
}