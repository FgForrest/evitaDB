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

package io.evitadb.externalApi.api.catalog.schemaApi.model;

import io.evitadb.externalApi.api.catalog.model.CatalogRootDescriptor;
import io.evitadb.externalApi.api.catalog.model.cdc.ChangeCatalogCaptureDescriptor;
import io.evitadb.externalApi.api.model.EndpointDescriptor;

import static io.evitadb.externalApi.api.model.ObjectPropertyDataTypeDescriptor.nonNullRef;

/**
 * Descriptor of root of schema API for schema-based APIs.
 *
 * Note: this descriptor is meant be template for generated specific DTOs base on internal data. Endpoints in this
 * descriptor are supposed to be dynamically registered to target generated DTO.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public interface CatalogSchemaApiRootDescriptor extends CatalogRootDescriptor {

    EndpointDescriptor GET_CATALOG_SCHEMA = EndpointDescriptor.builder()
        .operation("get*schema")
        .urlPathItem("schema")
        .classifier("catalog")
        .description("""
            Returns evitaDB's internal schema for whole catalog.
            Can be used for altering catalog schema.
            """)
        .type(nonNullRef(CatalogSchemaDescriptor.THIS))
        .build();
    EndpointDescriptor UPDATE_CATALOG_SCHEMA = EndpointDescriptor.builder()
        .operation("update*schema")
        .urlPathItem("schema")
        .classifier("catalog")
        .description("""
            Updates evitaDB's internal schema for whole catalog.
            """)
        .type(nonNullRef(CatalogSchemaDescriptor.THIS))
        .build();
    EndpointDescriptor ON_CATALOG_SCHEMA_CHANGE = EndpointDescriptor.builder()
        .operation("onSchemaChange")
        .urlPathItem("schema")
        .classifier("catalog") // todo lho this cannot be present in final operation name, should it be here?
        .description("""
            Subscribes client to a stream of catalog schema changes which are sent over as individual capture events.
            """)
        .type(nonNullRef(ChangeCatalogCaptureDescriptor.THIS))
        .build();

    EndpointDescriptor GET_ENTITY_SCHEMA = EndpointDescriptor.builder()
        .operation("get*schema")
        .urlPathItem("schema")
        .description("""
            Returns evitaDB's internal schema for entities from `%s` collection.
            Can be used for altering schema of entities and their data.
            """)
        // type is expected to be a collection-specific `EntitySchema` object
        .build();
    EndpointDescriptor UPDATE_ENTITY_SCHEMA = EndpointDescriptor.builder()
        .operation("update*schema")
        .urlPathItem("schema")
        .description("""
            Updates evitaDB's internal schema for entities from `%s` collection.
            """)
        // type is expected to be a collection-specific `EntitySchema` object
        .build();
    EndpointDescriptor ON_COLLECTION_SCHEMA_CHANGE = EndpointDescriptor.builder()
        .operation("on*SchemaChange")
        .urlPathItem("schema")
        .description("""
            Subscribes client to a stream of specific collection schema changes which are sent over as individual capture events.
            """)
        .type(nonNullRef(ChangeCatalogCaptureDescriptor.THIS))
        .build();
}
