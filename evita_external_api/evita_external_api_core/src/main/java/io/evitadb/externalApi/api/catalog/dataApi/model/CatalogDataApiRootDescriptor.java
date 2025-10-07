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

package io.evitadb.externalApi.api.catalog.dataApi.model;

import io.evitadb.externalApi.api.catalog.model.CatalogRootDescriptor;
import io.evitadb.externalApi.api.catalog.model.cdc.ChangeCatalogCaptureDescriptor;
import io.evitadb.externalApi.api.model.EndpointDescriptor;
import io.evitadb.externalApi.api.model.ObjectDescriptor;

import static io.evitadb.externalApi.api.model.ObjectPropertyDataTypeDescriptor.nonNullRef;
import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;

/**
 * Descriptor of root of data API for schema-based external APIs.
 *
 * Note: this descriptor is meant be template for generated specific DTOs base on internal data. Endpoints in this
 * descriptor are supposed to be dynamically registered to target generated DTO.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public interface CatalogDataApiRootDescriptor extends CatalogRootDescriptor {

    ObjectDescriptor LOCALE_ENUM = ObjectDescriptor.builder()
        .name("CatalogLocale")
        .description("""
            Enum of all available locales in a catalog.
            """)
        .build();
    ObjectDescriptor CURRENCY_ENUM = ObjectDescriptor.builder()
        .name("CatalogCurrency")
        .description("""
            Enum of all available currencies in a catalog.
            """)
        .build();

    EndpointDescriptor COLLECTIONS = EndpointDescriptor.builder()
        .operation("collections")
        .urlPathItem("collections")
        .description("""
            Returns all present collection entity types in this catalog.
            """)
        .type(nonNull(String[].class))
        .build();

    EndpointDescriptor GET_UNKNOWN_ENTITY = EndpointDescriptor.builder()
        .operation("get")
        .urlPathItem("get")
        .classifier("entity")
        .description("""
            Finds and returns single entity from unspecified collection by shared arguments between collections.
            """)
        // type can be entity of any collection
        .build();

    EndpointDescriptor LIST_UNKNOWN_ENTITY = EndpointDescriptor.builder()
        .operation("list")
        .urlPathItem("list")
        .classifier("entity")
        .description("""
            Finds and returns list of entities from unspecified collections by shared arguments between collections.
            """)
        // type can be list of entities of any collection
        .build();

    EndpointDescriptor GET_ENTITY = EndpointDescriptor.builder()
        .operation("get")
        .urlPathItem("get")
        .description("""
            Finds and returns single entity from `%s` collection by simplified collection-specific arguments.
            """)
        // type is expected to be a collection-specific `Entity` object
        .build();

    EndpointDescriptor LIST_ENTITY = EndpointDescriptor.builder()
        .operation("list")
        .urlPathItem("list")
        .description("""
            Finds and returns list of entities from `%s` collection by complex query.
            """)
        // type is expected to be a collection of collection-specific `Entity` objects
        .build();

    EndpointDescriptor QUERY_ENTITY = EndpointDescriptor.builder()
        .operation("query")
        .urlPathItem("query")
        .description("""
            Finds and returns entities and extra results from `%s` collection by complex query.
            """)
        // type is expected to be a collection-specific `Response` object
        .build();

    EndpointDescriptor COUNT_COLLECTION = EndpointDescriptor.builder()
        .operation("count")
        .urlPathItem("count")
        .description("""
            Returns number of all entities stored in `%s` collection.
            """)
        .type(nonNull(Integer.class))
        .build();

    EndpointDescriptor UPSERT_ENTITY = EndpointDescriptor.builder()
        .operation("upsert")
        .urlPathItem("")
        .description("""
            Updates existing or inserts new entity to `%s` collection.
            """)
        // type is expected to be a specific collection-specific `Entity` object
        .build();

    EndpointDescriptor DELETE_ENTITY = EndpointDescriptor.builder()
        .operation("delete")
        .urlPathItem("")
        .description("""
            Deletes existing entities from `%s` collection that conforms with passed query and returns deletion info.
            """)
        // type is expected to be a collection-specific list of `Entity` objects
        .build();
}
