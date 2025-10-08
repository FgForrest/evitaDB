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

package io.evitadb.externalApi.graphql.api.system.model;

import io.evitadb.externalApi.api.catalog.model.cdc.ChangeCatalogCaptureDescriptor;
import io.evitadb.externalApi.api.model.EndpointDescriptor;
import io.evitadb.externalApi.api.system.model.CatalogDescriptor;
import io.evitadb.externalApi.api.system.model.CatalogUnionDescriptor;
import io.evitadb.externalApi.api.system.model.cdc.ChangeSystemCaptureDescriptor;

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
        .description("""
            Returns `true` when the API is ready to take requests.
            """)
        .type(nonNull(Boolean.class))
        .build();

    EndpointDescriptor CATALOG = EndpointDescriptor.builder()
        .operation("catalog")
        .description("""
            Returns single catalog by its name.
            """)
        .type(nullableRef(CatalogUnionDescriptor.THIS))
        .build();
    EndpointDescriptor CATALOGS = EndpointDescriptor.builder()
        .operation("catalogs")
        .description("""
            Returns all catalogs known to evitaDB.
            """)
        .type(nullableListRef(CatalogUnionDescriptor.THIS))
        .build();

    EndpointDescriptor CREATE_CATALOG = EndpointDescriptor.builder()
        .operation("createCatalog")
        .description("""
            Creates new catalog of particular name if it doesn't exist. New empty catalog is returned. The catalog
            is created in `WARMING_UP` state and must be switched to `ALIVE` state by calling `switchCatalogToAliveState`
            after all bulk index operations are finished.
            """)
        .type(nonNullRef(CatalogDescriptor.THIS))
        .build();
    EndpointDescriptor RENAME_CATALOG = EndpointDescriptor.builder()
        .operation("renameCatalog")
        .description("""
            Renames existing catalog to a new name. The `newName` must not clash with any existing catalog name,
            otherwise exception is thrown. If you need to rename catalog to a name of existing catalog use
            the `replaceCatalog` mutation instead.
            
            In case exception occurs the original catalog (`name`) is guaranteed to be untouched,
            and the `newName` will not be present.
            """)
        .type(nonNullRef(CatalogDescriptor.THIS))
        .build();
    EndpointDescriptor SWITCH_CATALOG_TO_ALIVE_STATE = EndpointDescriptor.builder()
        .operation("switchCatalogToAliveState")
        .description("""
            Switches catalog to the `ALIVE` state so that next request is operating in the new catalog state.
            
            Catalog's state is switched only when the state transition successfully occurs and this is signalized
            by return value.
            """)
        .type(nonNull(Boolean.class))
        .build();
    EndpointDescriptor REPLACE_CATALOG = EndpointDescriptor.builder()
        .operation("replaceCatalog")
        .description("""
            Replaces existing catalog of particular with the contents of the another catalog. When this method is
            successfully finished, the catalog `nameToBeReplacedWith` will be known under the name of the
            `nameToBeReplaced` and the original contents of the `nameToBeReplaced` will be purged entirely.
            
            In case exception occurs, the original catalog (`nameToBeReplaced`) is guaranteed to be untouched, the
            state of `nameToBeReplacedWith` is however unknown and should be treated as damaged.
            """)
        .type(nonNullRef(CatalogDescriptor.THIS))
        .build();
    EndpointDescriptor DELETE_CATALOG_IF_EXISTS = EndpointDescriptor.builder()
        .operation("deleteCatalogIfExists")
        .description("""
            Deletes catalog with name along with its contents on disk.
            """)
        .type(nonNull(Boolean.class))
        .build();

    EndpointDescriptor ON_SYSTEM_CHANGE = EndpointDescriptor.builder()
        .operation("onSystemChange")
        .description("""
            Subscribes client to a stream of change system captures that match the request.
            """)
        .type(nonNullRef(ChangeSystemCaptureDescriptor.THIS))
        .build();
	EndpointDescriptor ON_CATALOG_CHANGE = EndpointDescriptor.builder()
		.operation("onCatalogChange")
		.description("""
            Subscribes client to a stream of change catalog captures that match the request.
            """)
		.type(nonNullRef(ChangeCatalogCaptureDescriptor.THIS))
		.build();
}
