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
import io.evitadb.externalApi.api.system.model.CatalogUnionDescriptor;
import io.evitadb.externalApi.api.system.model.cdc.ChangeSystemCaptureDescriptor;
import io.evitadb.externalApi.api.system.model.progress.EngineMutationProgressChangeDescriptor;
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

	EndpointDescriptor CREATE_NEW_CATALOG = EndpointDescriptor.builder()
		.operation("createNewCatalog")
		.description("""
			Creates a new catalog of the given name if it doesn't exist yet, and returns it. An existing catalog of that
			name is returned as it is. A new catalog is created in the `WARMING_UP` state and must be switched to the
			`ALIVE` state by `makeCatalogAlive` after all bulk indexing operations are finished.

			The call waits until the operation finishes. Returns the resulting catalog after the operation has finished.
			""")
		.type(nonNullRef(CatalogUnionDescriptor.THIS))
		.build();
	EndpointDescriptor ACTIVATE_CATALOG = EndpointDescriptor.builder()
		.operation("activateCatalog")
		.description("""
			Activates an inactive catalog by loading it from disk into memory. The catalog returns to the state it had
			when it was deactivated - `ALIVE`, or `WARMING_UP` when it was deactivated during warm-up.

			The call waits until the operation finishes. Returns the resulting catalog after the operation has finished.
			""")
		.type(nonNullRef(CatalogUnionDescriptor.THIS))
		.build();
	EndpointDescriptor ACTIVATE_CATALOG_WITH_PROGRESS = EndpointDescriptor.builder()
		.operation("activateCatalogWithProgress")
		.description("""
			Activates an inactive catalog by loading it from disk into memory. The catalog returns to the state it had
			when it was deactivated - `ALIVE`, or `WARMING_UP` when it was deactivated during warm-up.

			The call returns as soon as the operation is accepted, with a handle whose `progressId` can be used to
			subscribe to the progress and the outcome of the operation by `onEngineMutationProgressChange`. Checks
			that precede the acceptance (e.g. that the catalog exists) still fail the call itself.
			""")
		.type(nonNullRef(EngineMutationProgressHandleDescriptor.THIS))
		.build();
	EndpointDescriptor DEACTIVATE_CATALOG = EndpointDescriptor.builder()
		.operation("deactivateCatalog")
		.description("""
			Deactivates a catalog by unloading it from memory. The catalog becomes `INACTIVE`, its data remains on disk
			and it can be activated again by `activateCatalog`.

			The call waits until the operation finishes. Returns the resulting catalog after the operation has finished.
			""")
		.type(nonNullRef(CatalogUnionDescriptor.THIS))
		.build();
	EndpointDescriptor DEACTIVATE_CATALOG_WITH_PROGRESS = EndpointDescriptor.builder()
		.operation("deactivateCatalogWithProgress")
		.description("""
			Deactivates a catalog by unloading it from memory. The catalog becomes `INACTIVE`, its data remains on disk
			and it can be activated again by `activateCatalog`.

			The call returns as soon as the operation is accepted, with a handle whose `progressId` can be used to
			subscribe to the progress and the outcome of the operation by `onEngineMutationProgressChange`. Checks
			that precede the acceptance (e.g. that the catalog exists) still fail the call itself.
			""")
		.type(nonNullRef(EngineMutationProgressHandleDescriptor.THIS))
		.build();
	EndpointDescriptor DELETE_CATALOG_IF_EXISTS = EndpointDescriptor.builder()
		.operation("deleteCatalogIfExists")
		.description("""
			Deletes the catalog of the given name along with its contents on disk, if it exists.

			The call waits until the operation finishes. Returns `true` when the catalog existed and was deleted,
			`false` when no such catalog exists.
			""")
		.type(nonNull(Boolean.class))
		.build();
	EndpointDescriptor DELETE_CATALOG_IF_EXISTS_WITH_PROGRESS = EndpointDescriptor.builder()
		.operation("deleteCatalogIfExistsWithProgress")
		.description("""
			Deletes the catalog of the given name along with its contents on disk, if it exists.

			The call returns as soon as the operation is accepted, with a handle whose `progressId` can be used to
			subscribe to the progress and the outcome of the operation by `onEngineMutationProgressChange`. Checks
			that precede the acceptance (e.g. that the catalog exists) still fail the call itself. Returns `null` when
			no catalog of the given name exists.
			""")
		.type(nullableRef(EngineMutationProgressHandleDescriptor.THIS))
		.build();
	EndpointDescriptor DUPLICATE_CATALOG = EndpointDescriptor.builder()
		.operation("duplicateCatalog")
		.description("""
			Duplicates an existing catalog into a new catalog of name `newName`, copying all its data and schema. The
			source catalog must be `ALIVE` or `WARMING_UP` and no catalog of name `newName` may exist. The duplicate is
			created `INACTIVE`.

			The call waits until the operation finishes. Returns the resulting catalog after the operation has finished.
			""")
		.type(nonNullRef(CatalogUnionDescriptor.THIS))
		.build();
	EndpointDescriptor DUPLICATE_CATALOG_WITH_PROGRESS = EndpointDescriptor.builder()
		.operation("duplicateCatalogWithProgress")
		.description("""
			Duplicates an existing catalog into a new catalog of name `newName`, copying all its data and schema. The
			source catalog must be `ALIVE` or `WARMING_UP` and no catalog of name `newName` may exist. The duplicate is
			created `INACTIVE`.

			The call returns as soon as the operation is accepted, with a handle whose `progressId` can be used to
			subscribe to the progress and the outcome of the operation by `onEngineMutationProgressChange`. Checks
			that precede the acceptance (e.g. that the catalog exists) still fail the call itself.
			""")
		.type(nonNullRef(EngineMutationProgressHandleDescriptor.THIS))
		.build();
	EndpointDescriptor MAKE_CATALOG_ALIVE = EndpointDescriptor.builder()
		.operation("makeCatalogAlive")
		.description("""
			Switches a catalog from the `WARMING_UP` state to the `ALIVE` state, in which it supports transactions. The
			operation waits briefly for open sessions of the catalog to finish.

			The call waits until the operation finishes. Returns the resulting catalog after the operation has finished.
			""")
		.type(nonNullRef(CatalogUnionDescriptor.THIS))
		.build();
	EndpointDescriptor MAKE_CATALOG_ALIVE_WITH_PROGRESS = EndpointDescriptor.builder()
		.operation("makeCatalogAliveWithProgress")
		.description("""
			Switches a catalog from the `WARMING_UP` state to the `ALIVE` state, in which it supports transactions. The
			operation waits briefly for open sessions of the catalog to finish.

			The call returns as soon as the operation is accepted, with a handle whose `progressId` can be used to
			subscribe to the progress and the outcome of the operation by `onEngineMutationProgressChange`. Checks
			that precede the acceptance (e.g. that the catalog exists) still fail the call itself.
			""")
		.type(nonNullRef(EngineMutationProgressHandleDescriptor.THIS))
		.build();
	EndpointDescriptor MAKE_CATALOG_IMMUTABLE = EndpointDescriptor.builder()
		.operation("makeCatalogImmutable")
		.description("""
			Makes a catalog read-only: it will reject all data modifications and schema changes until it is made
			mutable again by `makeCatalogMutable`.

			The call waits until the operation finishes. Returns the resulting catalog after the operation has finished.
			""")
		.type(nonNullRef(CatalogUnionDescriptor.THIS))
		.build();
	EndpointDescriptor MAKE_CATALOG_IMMUTABLE_WITH_PROGRESS = EndpointDescriptor.builder()
		.operation("makeCatalogImmutableWithProgress")
		.description("""
			Makes a catalog read-only: it will reject all data modifications and schema changes until it is made
			mutable again by `makeCatalogMutable`.

			The call returns as soon as the operation is accepted, with a handle whose `progressId` can be used to
			subscribe to the progress and the outcome of the operation by `onEngineMutationProgressChange`. Checks
			that precede the acceptance (e.g. that the catalog exists) still fail the call itself.
			""")
		.type(nonNullRef(EngineMutationProgressHandleDescriptor.THIS))
		.build();
	EndpointDescriptor MAKE_CATALOG_MUTABLE = EndpointDescriptor.builder()
		.operation("makeCatalogMutable")
		.description("""
			Makes a read-only catalog writable again, so it accepts data modifications and schema changes.

			The call waits until the operation finishes. Returns the resulting catalog after the operation has finished.
			""")
		.type(nonNullRef(CatalogUnionDescriptor.THIS))
		.build();
	EndpointDescriptor MAKE_CATALOG_MUTABLE_WITH_PROGRESS = EndpointDescriptor.builder()
		.operation("makeCatalogMutableWithProgress")
		.description("""
			Makes a read-only catalog writable again, so it accepts data modifications and schema changes.

			The call returns as soon as the operation is accepted, with a handle whose `progressId` can be used to
			subscribe to the progress and the outcome of the operation by `onEngineMutationProgressChange`. Checks
			that precede the acceptance (e.g. that the catalog exists) still fail the call itself.
			""")
		.type(nonNullRef(EngineMutationProgressHandleDescriptor.THIS))
		.build();
	EndpointDescriptor RENAME_CATALOG = EndpointDescriptor.builder()
		.operation("renameCatalog")
		.description("""
			Renames an existing catalog to `newName`. The `newName` must not clash with any existing catalog name - to
			take the name of an existing catalog use `replaceCatalog` instead. The operation waits briefly for open
			sessions and commits in progress of the catalog and is refused when they do not finish in time.

			When the operation fails, the catalog keeps its original name and `newName` is not created. The catalog
			must be loaded in memory (not `INACTIVE`).

			The call waits until the operation finishes. Returns the resulting catalog after the operation has finished.
			""")
		.type(nonNullRef(CatalogUnionDescriptor.THIS))
		.build();
	EndpointDescriptor RENAME_CATALOG_WITH_PROGRESS = EndpointDescriptor.builder()
		.operation("renameCatalogWithProgress")
		.description("""
			Renames an existing catalog to `newName`. The `newName` must not clash with any existing catalog name - to
			take the name of an existing catalog use `replaceCatalog` instead. The operation waits briefly for open
			sessions and commits in progress of the catalog and is refused when they do not finish in time.

			When the operation fails, the catalog keeps its original name and `newName` is not created. The catalog
			must be loaded in memory (not `INACTIVE`).

			The call returns as soon as the operation is accepted, with a handle whose `progressId` can be used to
			subscribe to the progress and the outcome of the operation by `onEngineMutationProgressChange`. Checks
			that precede the acceptance (e.g. that the catalog exists) still fail the call itself.
			""")
		.type(nonNullRef(EngineMutationProgressHandleDescriptor.THIS))
		.build();
	EndpointDescriptor REPLACE_CATALOG = EndpointDescriptor.builder()
		.operation("replaceCatalog")
		.description("""
			Replaces the catalog `nameToBeReplaced` with the catalog `nameToBeReplacedWith`: after the operation the
			catalog `nameToBeReplacedWith` is known under the name `nameToBeReplaced`, and the original contents of
			`nameToBeReplaced` are dropped. The operation waits briefly for open sessions and commits in progress of
			both catalogs and is refused when they do not finish in time.

			When the operation fails, both catalogs stay as they were. The catalogs must be loaded in memory (not
			`INACTIVE`).

			The call waits until the operation finishes. Returns the resulting catalog after the operation has finished.
			""")
		.type(nonNullRef(CatalogUnionDescriptor.THIS))
		.build();
	EndpointDescriptor REPLACE_CATALOG_WITH_PROGRESS = EndpointDescriptor.builder()
		.operation("replaceCatalogWithProgress")
		.description("""
			Replaces the catalog `nameToBeReplaced` with the catalog `nameToBeReplacedWith`: after the operation the
			catalog `nameToBeReplacedWith` is known under the name `nameToBeReplaced`, and the original contents of
			`nameToBeReplaced` are dropped. The operation waits briefly for open sessions and commits in progress of
			both catalogs and is refused when they do not finish in time.

			When the operation fails, both catalogs stay as they were. The catalogs must be loaded in memory (not
			`INACTIVE`).

			The call returns as soon as the operation is accepted, with a handle whose `progressId` can be used to
			subscribe to the progress and the outcome of the operation by `onEngineMutationProgressChange`. Checks
			that precede the acceptance (e.g. that the catalog exists) still fail the call itself.
			""")
		.type(nonNullRef(EngineMutationProgressHandleDescriptor.THIS))
		.build();

	EndpointDescriptor ON_ENGINE_MUTATION_PROGRESS_CHANGE = EndpointDescriptor.builder()
		.operation("onEngineMutationProgressChange")
		.description("""
			Subscribes client to the progress of an engine operation started by one of the `...WithProgress`
			mutations. The first message carries the state of the operation at the moment of subscription, the
			following ones its changes. A successful operation ends the stream with a message with `finished` set to
			`true`, a failed one with an error. Subscribing after the operation has finished delivers its outcome as
			long as it is retained by the server; an unknown or expired id ends the stream with an error.
			""")
		.type(nonNullRef(EngineMutationProgressChangeDescriptor.THIS))
		.build();

	EndpointDescriptor ON_SYSTEM_CHANGE = EndpointDescriptor.builder()
		.operation("onSystemChange")
		.description("""
            Subscribes client to a stream of change system captures that match the request.
            """)
		.type(nonNullRef(ChangeSystemCaptureDescriptor.THIS))
		.build();
    EndpointDescriptor ON_SYSTEM_CHANGE_UNTYPED = EndpointDescriptor.builder()
        .operation("onSystemChangeUntyped")
        .description("""
            Subscribes client to a stream of change system captures that match the request.
            
            Same as `onSystemChange` but the mutation is sent as a generic JSON object instead of the interface type. Useful when
            the client needs to somehow handle all of the mutation implementations, but doesn't want to specify the exact
            fields for each implementation.
            """)
        .type(nonNullRef(ChangeSystemCaptureDescriptor.THIS_GENERIC))
        .build();
	EndpointDescriptor ON_CATALOG_CHANGE = EndpointDescriptor.builder()
		.operation("onCatalogChange")
		.description("""
            Subscribes client to a stream of change catalog captures that match the request.
            """)
		.type(nonNullRef(ChangeCatalogCaptureDescriptor.THIS))
		.build();
	EndpointDescriptor ON_CATALOG_CHANGE_UNTYPED = EndpointDescriptor.builder()
		.operation("onCatalogChangeUntyped")
		.description("""
            Subscribes client to a stream of change catalog captures that match the request.
            
            Same as `onCatalogChange` but the mutation is sent as a generic JSON object instead of the interface type. Useful when
            the client needs to somehow handle all of the mutation implementations, but doesn't want to specify the exact
            fields for each implementation.
            """)
		.type(nonNullRef(ChangeCatalogCaptureDescriptor.THIS_GENERIC))
		.build();
}
