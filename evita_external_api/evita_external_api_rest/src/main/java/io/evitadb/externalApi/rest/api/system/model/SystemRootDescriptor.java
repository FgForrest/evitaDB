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

import io.evitadb.externalApi.api.model.EndpointDescriptor;
import io.evitadb.externalApi.api.system.model.CatalogDescriptor;

import static io.evitadb.externalApi.api.model.ObjectPropertyDataTypeDescriptor.nonNullRef;
import static io.evitadb.externalApi.api.model.ObjectPropertyDataTypeDescriptor.nullableListRef;
import static io.evitadb.externalApi.api.model.ObjectPropertyDataTypeDescriptor.nullableRef;
import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;

/**
 * Descriptor of root of management API for schema-based external APIs.
 *
 * Note: this descriptor is meant be template for generated specific DTOs base on internal data. Endpoints in this
 * descriptor are supposed to be dynamically registered to target generated DTO.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public interface SystemRootDescriptor {

    EndpointDescriptor LIVENESS = EndpointDescriptor.builder()
        .operation("liveness")
        .urlPathItem("liveness")
        .description("""
            Returns `true` when the API is ready to take requests.
            """)
        .type(nonNullRef(LivenessDescriptor.THIS))
        .build();

    EndpointDescriptor GET_CATALOG = EndpointDescriptor.builder()
        .operation("getCatalog")
        .urlPathItem("catalogs")
        .description("""
            Returns single catalog by its name.
            """)
        .type(nullableRef(CatalogDescriptor.THIS))
        .build();
    EndpointDescriptor LIST_CATALOGS = EndpointDescriptor.builder()
        .operation("listCatalogs")
        .urlPathItem("catalogs")
        .description("""
            Returns all catalogs known to evitaDB.
            """)
        .type(nullableListRef(CatalogDescriptor.THIS))
        .build();

    EndpointDescriptor CREATE_CATALOG = EndpointDescriptor.builder()
        .operation("createCatalog")
        .urlPathItem("catalogs")
        .description("""
            Creates new catalog of particular name if it doesn't exist. New empty catalog is returned.
            """)
        .type(nonNullRef(CatalogDescriptor.THIS))
        .build();
    EndpointDescriptor UPDATE_CATALOG = EndpointDescriptor.builder()
        .operation("updateCatalog")
        .urlPathItem("catalogs")
        .description("""
            Updates part of data of the specified catalog.
            """)
        .type(nonNullRef(CatalogDescriptor.THIS))
        .build();
    EndpointDescriptor DELETE_CATALOG = EndpointDescriptor.builder()
        .operation("deleteCatalog")
        .urlPathItem("catalogs")
        .description("""
            Deletes catalog with name along with its contents on disk.
            """)
        .type(nonNull(Boolean.class))
        .build();
    EndpointDescriptor SYSTEM_CHANGE_CAPTURE = EndpointDescriptor.builder()
        .operation("registerSystemChangeCapture")
        .urlPathItem("change-captures")
        .description("""
            Opens WebSocket connection to system change capture stream.
            """)
        // todo lho type???
        .type(nonNull(Boolean.class))
        .build();
}
