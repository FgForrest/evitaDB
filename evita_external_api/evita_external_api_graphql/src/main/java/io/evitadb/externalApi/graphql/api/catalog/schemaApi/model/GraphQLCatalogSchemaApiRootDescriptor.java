/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.externalApi.graphql.api.catalog.schemaApi.model;

import io.evitadb.externalApi.api.catalog.model.cdc.ChangeCatalogCaptureDescriptor;
import io.evitadb.externalApi.api.model.EndpointDescriptor;

import static io.evitadb.externalApi.api.model.TypePropertyDataTypeDescriptor.nonNullRef;

/**
 * Extension of {@link io.evitadb.externalApi.api.catalog.schemaApi.model.CatalogSchemaApiRootDescriptor} for GraphQL API.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2025
 */
public interface GraphQLCatalogSchemaApiRootDescriptor {

	EndpointDescriptor ON_CATALOG_SCHEMA_CHANGE = EndpointDescriptor.builder()
		.operation("onSchemaChange")
		.description("""
            Subscribes client to a stream of catalog schema changes which are sent over as individual capture events.
            """)
		.type(nonNullRef(ChangeCatalogCaptureDescriptor.THIS))
		.build();

	EndpointDescriptor ON_COLLECTION_SCHEMA_CHANGE = EndpointDescriptor.builder()
		.operation("on*SchemaChange")
		.description("""
            Subscribes client to a stream of specific collection schema changes which are sent over as individual capture events.
            """)
		.type(nonNullRef(ChangeCatalogCaptureDescriptor.THIS))
		.build();
}
