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
import io.evitadb.externalApi.api.system.model.CatalogUnionDescriptor;
import io.evitadb.externalApi.api.system.model.progress.EngineMutationProgressHandleDescriptor;

import static io.evitadb.externalApi.api.model.TypePropertyDataTypeDescriptor.nonNullRef;
import static io.evitadb.externalApi.api.model.TypePropertyDataTypeDescriptor.nullableListRef;
import static io.evitadb.externalApi.api.model.TypePropertyDataTypeDescriptor.nullableRef;
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
			Creates a new catalog of the given name if it doesn't exist yet, and returns it. An existing catalog of that
			name is returned as it is. A new catalog is created in the `WARMING_UP` state.
			""")
		.type(nonNullRef(CatalogUnionDescriptor.THIS))
		.build();
	EndpointDescriptor UPDATE_CATALOG = EndpointDescriptor.builder()
		.operation("updateCatalog")
		.urlPathItem("catalogs")
		.description("""
			Changes the catalog `{name}`. The request body must contain exactly one of the properties:

			- `name`: renames the catalog to the new name, which must not be taken (to take the name of an existing
			  catalog, use the `replace` action); the catalog must be loaded in memory (not `INACTIVE`),
			- `state`: `ALIVE` activates an inactive catalog, or switches a catalog in the `WARMING_UP` state to the
			  `ALIVE` state; `INACTIVE` deactivates the catalog,
			- `readOnly`: `true` makes the catalog read-only, `false` writable again.

			The call waits until the operation finishes and returns the resulting catalog. Activating a catalog that was
			deactivated during warm-up returns it in the `WARMING_UP` state - send `ALIVE` once more to make it alive.
			""")
		.type(nonNullRef(CatalogUnionDescriptor.THIS))
		.build();
	EndpointDescriptor UPDATE_CATALOG_ASYNC = EndpointDescriptor.builder()
		.operation("updateCatalogAsync")
		.urlPathItem("catalogs-async")
		.description("""
			Changes the catalog `{name}`. The request body must contain exactly one of the properties:

			- `name`: renames the catalog to the new name, which must not be taken (to take the name of an existing
			  catalog, use the `replace` action); the catalog must be loaded in memory (not `INACTIVE`),
			- `state`: `ALIVE` activates an inactive catalog, or switches a catalog in the `WARMING_UP` state to the
			  `ALIVE` state; `INACTIVE` deactivates the catalog,
			- `readOnly`: `true` makes the catalog read-only, `false` writable again.

			The call returns `202 Accepted` as soon as the operation is accepted, with a handle whose `progressId`
			can be used to observe the progress and the outcome of the operation through the WebSocket endpoint
			`/catalogs-async/progress`. Checks that precede the acceptance (e.g. that the catalog exists) still fail
			the call itself.
			""")
		.type(nonNullRef(EngineMutationProgressHandleDescriptor.THIS))
		.build();
	EndpointDescriptor DELETE_CATALOG = EndpointDescriptor.builder()
		.operation("deleteCatalog")
		.urlPathItem("catalogs")
		.description("""
			Deletes the catalog `{name}` along with its contents on disk.

			The call waits until the catalog is deleted.
			""")
		.type(nonNull(Boolean.class))
		.build();
	EndpointDescriptor DELETE_CATALOG_ASYNC = EndpointDescriptor.builder()
		.operation("deleteCatalogAsync")
		.urlPathItem("catalogs-async")
		.description("""
			Deletes the catalog `{name}` along with its contents on disk.

			The call returns `202 Accepted` as soon as the operation is accepted, with a handle whose `progressId`
			can be used to observe the progress and the outcome of the operation through the WebSocket endpoint
			`/catalogs-async/progress`. Checks that precede the acceptance (e.g. that the catalog exists) still fail
			the call itself.
			""")
		.type(nonNullRef(EngineMutationProgressHandleDescriptor.THIS))
		.build();
	EndpointDescriptor DUPLICATE_CATALOG = EndpointDescriptor.builder()
		.operation("duplicateCatalog")
		.urlPathItem("catalogs")
		.description("""
			Duplicates the catalog `{name}` into a new catalog `{newName}`, copying all its data and schema. The source
			catalog must be `ALIVE` or `WARMING_UP` and no catalog `{newName}` may exist. The duplicate is created
			`INACTIVE`.

			The call waits until the operation finishes and returns the new catalog.
			""")
		.type(nonNullRef(CatalogUnionDescriptor.THIS))
		.build();
	EndpointDescriptor DUPLICATE_CATALOG_ASYNC = EndpointDescriptor.builder()
		.operation("duplicateCatalogAsync")
		.urlPathItem("catalogs-async")
		.description("""
			Duplicates the catalog `{name}` into a new catalog `{newName}`, copying all its data and schema. The source
			catalog must be `ALIVE` or `WARMING_UP` and no catalog `{newName}` may exist. The duplicate is created
			`INACTIVE`.

			The call returns `202 Accepted` as soon as the operation is accepted, with a handle whose `progressId`
			can be used to observe the progress and the outcome of the operation through the WebSocket endpoint
			`/catalogs-async/progress`. Checks that precede the acceptance (e.g. that the catalog exists) still fail
			the call itself.
			""")
		.type(nonNullRef(EngineMutationProgressHandleDescriptor.THIS))
		.build();
	EndpointDescriptor REPLACE_CATALOG = EndpointDescriptor.builder()
		.operation("replaceCatalog")
		.urlPathItem("catalogs")
		.description("""
			Replaces the catalog `{newName}` with the catalog `{name}`: after the operation the catalog `{name}` is
			known under the name `{newName}`, and the original contents of `{newName}` are dropped. The operation waits
			briefly for open sessions and commits in progress and is refused when they do not finish in time. When the
			operation fails, both catalogs stay as they were. The catalogs must be loaded in memory (not `INACTIVE`).

			The call waits until the operation finishes and returns the catalog under its new name `{newName}`.
			""")
		.type(nonNullRef(CatalogUnionDescriptor.THIS))
		.build();
	EndpointDescriptor REPLACE_CATALOG_ASYNC = EndpointDescriptor.builder()
		.operation("replaceCatalogAsync")
		.urlPathItem("catalogs-async")
		.description("""
			Replaces the catalog `{newName}` with the catalog `{name}`: after the operation the catalog `{name}` is
			known under the name `{newName}`, and the original contents of `{newName}` are dropped. The operation waits
			briefly for open sessions and commits in progress and is refused when they do not finish in time. When the
			operation fails, both catalogs stay as they were. The catalogs must be loaded in memory (not `INACTIVE`).

			The call returns `202 Accepted` as soon as the operation is accepted, with a handle whose `progressId`
			can be used to observe the progress and the outcome of the operation through the WebSocket endpoint
			`/catalogs-async/progress`. Checks that precede the acceptance (e.g. that the catalog exists) still fail
			the call itself.
			""")
		.type(nonNullRef(EngineMutationProgressHandleDescriptor.THIS))
		.build();
	EndpointDescriptor ENGINE_MUTATION_PROGRESS = EndpointDescriptor.builder()
		.operation("registerEngineMutationProgress")
		.urlPathItem("catalogs-async")
		.description("""
			Opens WebSocket connection to the progress stream of an engine operation started by one of the asynchronous
			(`catalogs-async`) endpoints. The subscription payload carries the `progressId` of the operation. The first
			message carries the state of the operation at the moment of subscription, the following ones its changes;
			a successful operation ends the stream with a message with `finished` set to `true`, a failed one with an
			error. An unknown or expired id ends the stream with an error.
			""")
		.build();
    EndpointDescriptor CHANGE_SYSTEM_CAPTURE = EndpointDescriptor.builder()
        .operation("registerSystemChangeCapture")
        .urlPathItem("change-captures")
        .description("""
            Opens WebSocket connection to system change capture stream.
            """)
        .build();
}
