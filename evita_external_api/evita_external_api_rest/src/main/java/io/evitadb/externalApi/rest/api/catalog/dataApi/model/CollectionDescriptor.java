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

package io.evitadb.externalApi.rest.api.catalog.dataApi.model;


import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import java.util.List;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;
import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nullable;

/**
 * Descriptor of object returned when requesting basic information about existing collections (entities).
 *
 * @author Martin Veska, FG Forrest a.s. (c) 2022
 */
public interface CollectionDescriptor {

    PropertyDescriptor ENTITY_TYPE = PropertyDescriptor.builder()
        .name("entityType")
        .description("""
            Entity type name.
            """)
        .type(nonNull(String.class))
        .build();
    PropertyDescriptor COUNT = PropertyDescriptor.builder()
        .name("count")
        .description("""
            Count of all entities within single collection
            """)
        .type(nullable(Integer.class))
        .build();

    ObjectDescriptor THIS = ObjectDescriptor.builder()
        .name("Collection")
        .description("""
            Contains info about single catalog's collection.
            """)
        .staticProperties(List.of(ENTITY_TYPE, COUNT))
        .build();
}
