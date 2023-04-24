/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.externalApi.api.system.model;

import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import java.util.List;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;

/**
 * Descriptor of {@link io.evitadb.api.CatalogContract}.
 *
 * Note: this descriptor has static structure.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public interface CorruptedCatalogDescriptor {

    PropertyDescriptor NAME = PropertyDescriptor.builder()
        .name("name")
        .description("""
            Name of the catalog. Name must be unique across all catalogs inside same evitaDB instance.
            """)
        .type(nonNull(String.class))
        .build();
    PropertyDescriptor CATALOG_STORAGE_PATH = PropertyDescriptor.builder()
        .name("catalogStoragePath")
        .description("""
            Path to original catalog.
            """)
        .type(nonNull(String.class))
        .build();
    PropertyDescriptor CAUSE = PropertyDescriptor.builder()
        .name("cause")
        .description("""
            Cause of catalog corruption.
            """)
        .type(nonNull(String.class))
        .build();
    PropertyDescriptor CORRUPTED = PropertyDescriptor.builder()
        .name("corrupted")
        .description("""
			Whether this catalog is corrupted or can be freely used.
			""")
        .type(nonNull(Boolean.class))
        .build();

    ObjectDescriptor THIS = ObjectDescriptor.builder()
        .name("CorruptedCatalog")
        .description("""
            Catalog instance that cannot be loaded into a memory due an error.
            The original exception and catalog path are accessible via. `catalogStoragePath` and `cause` properties.
            """)
        .staticFields(List.of(NAME, CATALOG_STORAGE_PATH, CAUSE, CORRUPTED))
        .build();
}
