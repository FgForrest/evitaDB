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

package io.evitadb.externalApi.rest.api.system.model;

import io.evitadb.api.CatalogState;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import java.util.List;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nullable;

/**
 * Descriptor of request body of {@link SystemRootDescriptor#UPDATE_CATALOG}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public interface UpdateCatalogRequestDescriptor {

	PropertyDescriptor NAME = PropertyDescriptor.builder()
		.name("name")
		.description("""
			New name of the catalog. The name must not be taken by another catalog - to take the name of an existing
			catalog use the `replace` action instead.
			""")
		.type(nullable(String.class))
		.build();
	PropertyDescriptor STATE = PropertyDescriptor.builder()
		.name("state")
		.description("""
			Requested state of the catalog. Only `ALIVE` and `INACTIVE` are accepted:

			- `ALIVE` activates an `INACTIVE` catalog (loads it into memory), or switches a catalog in the
			  `WARMING_UP` state to the `ALIVE` state. An activated catalog returns to the state it was deactivated in,
			  so a catalog deactivated during warm-up comes back `WARMING_UP` and needs `ALIVE` once more.
			- `INACTIVE` deactivates the catalog (unloads it from memory, its data remain on disk).
			""")
		.type(nullable(CatalogState.class))
		.build();
	PropertyDescriptor READ_ONLY = PropertyDescriptor.builder()
		.name("readOnly")
		.description("""
			`true` makes the catalog read-only (it rejects all data and schema changes), `false` makes it writable
			again.
			""")
		.type(nullable(Boolean.class))
		.build();

	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("UpdateCatalogRequest")
		.description("""
			Requests a change of a catalog. Exactly one of the properties must be set - each request performs exactly
			one engine operation.
			""")
		.staticProperties(List.of(NAME, STATE, READ_ONLY))
		.build();
}
