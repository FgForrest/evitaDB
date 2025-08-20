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

package io.evitadb.externalApi.graphql.api.catalog.model;

import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;
import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nullable;

/**
 * Subscription header arguments of registering subscription for listening {@link io.evitadb.api.requestResponse.schema.EntitySchemaContract}
 * and {@link io.evitadb.api.requestResponse.schema.CatalogSchemaContract} changes.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public interface OnChangeHeaderDescriptor {

	PropertyDescriptor OPERATION = PropertyDescriptor
		.builder()
		.name("operation")
		.description("""
			             The intercepted type of operation
			             """)
		.type(nullable(Operation[].class))
		.build();
	PropertyDescriptor SINCE_VERSION = PropertyDescriptor
		.builder()
		.name("sinceVersion")
		.description("""
			             Specifies the initial capture point for the CDC stream, it must always provide a last
			             known transaction id from the client point of view.
			             """)
		.type(nonNull(Long.class))
		.build();
	PropertyDescriptor SINCE_INDEX = PropertyDescriptor
		.builder()
		.name("sinceIndex")
		.description("""
			             Specifies the initial capture point within the transaction for the CDC stream. The index zero
			             means that the stream will start from the first event in the transaction (the transaction mutation).
			             Greater than zero index means that the stream will start from the event with the given index.
			             """)
		.type(nonNull(Integer.class))
		.build();
	PropertyDescriptor CONTAINER_NAME = PropertyDescriptor
		.builder()
		.name("containerName")
		.description("""
			             Specifies zero or more container names that should be captured. E.g. name of the entity type,
			             attribute, reference or associated data container (depending on the container type property).
			             """)
		.type(nonNull(String[].class))
		.build();
}
