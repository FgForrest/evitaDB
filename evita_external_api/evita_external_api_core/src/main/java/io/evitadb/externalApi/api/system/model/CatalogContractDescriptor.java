/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.externalApi.api.system.model;

import io.evitadb.api.CatalogState;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;

/**
 * Descriptor of {@link io.evitadb.api.CatalogContract}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2025
 */
public interface CatalogContractDescriptor {
	PropertyDescriptor NAME = PropertyDescriptor.builder()
		.name("name")
		.description("""
            Name of the catalog. Name must be unique across all catalogs inside same evitaDB instance.
            """)
		.type(nonNull(String.class))
		.build();
	PropertyDescriptor CATALOG_STATE = PropertyDescriptor.builder()
		.name("catalogState")
		.description("""
            State of this catalog instance.
            """)
		.type(nonNull(CatalogState.class))
		.build();
	PropertyDescriptor UNUSABLE = PropertyDescriptor.builder()
		.name("unusable")
		.description("""
			Whether this catalog is in unusable state or can be freely used.
			""")
		.type(nonNull(Boolean.class))
		.build();
}
