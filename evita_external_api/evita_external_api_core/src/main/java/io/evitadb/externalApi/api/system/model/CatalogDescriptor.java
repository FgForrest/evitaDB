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

package io.evitadb.externalApi.api.system.model;

import io.evitadb.api.CatalogState;
import io.evitadb.externalApi.api.catalog.schemaApi.model.NameVariantsDescriptor;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import java.util.List;
import java.util.UUID;

import static io.evitadb.externalApi.api.model.TypePropertyDataTypeDescriptor.nonNullRef;
import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;

/**
 * Descriptor of {@link io.evitadb.api.CatalogContract}.
 *
 * Note: this descriptor has static structure.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public interface CatalogDescriptor {

	PropertyDescriptor CATALOG_ID = PropertyDescriptor.builder()
		.name("catalogId")
		.description("""
			Returns unique catalog id that doesn't change with catalog schema changes - such as renaming.
			The id is assigned to the catalog when it is created and never changes.
			""")
		.type(nonNull(UUID.class))
		.build();
    PropertyDescriptor NAME = PropertyDescriptor.builder()
        .name("name")
        .description("""
            Name of the catalog. Name must be unique across all catalogs inside same evitaDB instance.
            """)
        .type(nonNull(String.class))
        .build();
    PropertyDescriptor NAME_VARIANTS = PropertyDescriptor.builder()
        .name("nameVariants")
        .description("""
            Map contains the `name` variants in different naming conventions. The name
			is guaranteed to be unique among other references in same convention. These names are used to quickly
			translate to / from names used in different protocols. Each API protocol prefers names in different naming
			conventions.
            """)
        .type(nonNullRef(NameVariantsDescriptor.THIS))
        .build();
    PropertyDescriptor VERSION = PropertyDescriptor.builder()
        .name("version")
        .description("""
            Catalog header version that is incremented with each update. Version is not stored on the disk,
            it serves only to distinguish whether there is any change made in the header and whether it needs to be persisted
            on disk.
            """)
        .type(nonNull(Long.class))
        .build();
    PropertyDescriptor CATALOG_STATE = PropertyDescriptor.builder()
        .name("catalogState")
        .description("""
            State of this catalog instance.
            """)
        .type(nonNull(CatalogState.class))
        .build();
    PropertyDescriptor SUPPORTS_TRANSACTION = PropertyDescriptor.builder()
        .name("supportsTransaction")
        .description("""
            Returns true if catalog supports transaction.
            """)
        .type(nonNull(Boolean.class))
        .build();
    PropertyDescriptor ENTITY_TYPES = PropertyDescriptor.builder()
        .name("entityTypes")
        .description("""
            Set of all maintained entity collections - i.e. entity types.
            """)
        .type(nonNull(String[].class))
        .build();
	PropertyDescriptor UNUSABLE = PropertyDescriptor
		.builder()
		.name("unusable")
		.description("""
             Whether this catalog is unusable or can be freely used.
             """)
		.type(nonNull(Boolean.class))
		.build();

    ObjectDescriptor THIS = ObjectDescriptor.builder()
        .name("Catalog")
        .description("""
            Catalog is a fragment of evitaDB database that can be compared to a schema of relational database. Catalog allows
            handling multiple isolated data collections inside single evitaDB instance. Catalogs in evitaDB are isolated one from
            another and share no single thing.
           
            Catalog is an abstraction for "database" in the sense of relational databases. Catalog contains all entities and data
            connected with single client. In the e-commerce world catalog means "single e-shop" although it may not be the truth
            in every case. Catalog manages set of entity collection uniquely identified by their name.
            """)
        .staticProperties(List.of(CATALOG_ID, NAME, NAME_VARIANTS, VERSION, CATALOG_STATE, SUPPORTS_TRANSACTION, ENTITY_TYPES,
                              UNUSABLE
        ))
        .build();
}
