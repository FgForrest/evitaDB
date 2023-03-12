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

package io.evitadb.externalApi.rest.api.catalog.schemaApi.model;

import io.evitadb.externalApi.api.catalog.model.CatalogRootDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.EntitySchemaDescriptor;
import io.evitadb.externalApi.api.model.EndpointDescriptor;

import static io.evitadb.externalApi.api.model.ObjectPropertyDataTypeDescriptor.nonNullRef;

/**
 * Descriptor of root of schema API for REST API.
 *
 * Note: this descriptor is meant be template for generated specific DTOs base on internal data. Endpoints in this
 * descriptor are supposed to be dynamically registered to target generated DTO.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public interface CatalogSchemaApiRootDescriptor extends CatalogRootDescriptor {

    EndpointDescriptor GET_CATALOG_SCHEMA = EndpointDescriptor.builder()
        .operation("schema")
        .classifier("catalog")
        .description("""
            Returns evitaDB's internal schema for whole catalog.
            Can be used for altering catalog schema.
            """)
        // type is expected to be specific `CatalogSchema` object
        .build();
    EndpointDescriptor UPDATE_CATALOG_SCHEMA = EndpointDescriptor.builder()
        .operation("schema")
        .classifier("catalog")
        .description("""
            Updates existing evitaDB's internal schema for whole catalog.
            """)
        // type is expected to be specific `CatalogSchema` object
        .build();
    EndpointDescriptor DELETE_CATALOG_SCHEMA = EndpointDescriptor.builder()
        .operation("schema")
        .classifier("catalog")
        .description("""
            Delete existing evitaDB's internal schema for whole catalog.
            """)
        .build();

    EndpointDescriptor GET_ENTITY_SCHEMA = EndpointDescriptor.builder()
        .operation("schema")
        .description("""
            Returns evitaDB's internal schema for entities from `%s` collection.
            Can be used for altering schema of entities and their data.
            """)
        // type is expected to be a collection-specific `EntitySchema` object
        .build();
    EndpointDescriptor UPDATE_ENTITY_SCHEMA = EndpointDescriptor.builder()
        .operation("schema")
        .description("""
            Updates existing evitaDB's internal schema for entities from `%s` collection.
            """)
        // type is expected to be a collection-specific `EntitySchema` object
        .build();
    EndpointDescriptor DELETE_ENTITY_SCHEMA = EndpointDescriptor.builder()
        .operation("schema")
        .description("""
            Delete existing evitaDB's internal schema for entities from `%s` collection.
            """)
        .build();
    EndpointDescriptor CREATE_ENTITY_SCHEMA = EndpointDescriptor.builder()
        .operation("schema")
        .description("""
            Creates new evitaDB collection with new internal schema.
            """)
        .type(nonNullRef(EntitySchemaDescriptor.THIS_GENERIC))
        .build();
}
