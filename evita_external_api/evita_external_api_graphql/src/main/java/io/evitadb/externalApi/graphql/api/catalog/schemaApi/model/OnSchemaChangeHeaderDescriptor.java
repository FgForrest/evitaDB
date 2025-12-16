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

package io.evitadb.externalApi.graphql.api.catalog.schemaApi.model;

import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.dataType.ClassifierType;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nullable;

/**
 * Ancestor for header arguments of subscription fields that provide streams of schema changes.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2025
 */
public interface OnSchemaChangeHeaderDescriptor {

	PropertyDescriptor SINCE_VERSION = PropertyDescriptor.builder()
		.name("sinceVersion")
		.description("""
			Specifies the initial capture point (catalog version) for the CDC stream, if not specified
			it is assumed to begin at the most recent / oldest available version.
			""")
		.type(nullable(Long.class))
		.build();
	PropertyDescriptor SINCE_INDEX = PropertyDescriptor.builder()
		.name("sinceIndex")
		.description("""
			Specifies the initial capture point for the CDC stream, it is optional and can be used
			to specify continuation point within an enclosing block of events.
			""")
		.type(nullable(Integer.class))
		.build();
	PropertyDescriptor OPERATION = PropertyDescriptor.builder()
		.name("operation")
		.description("""
			The intercepted type of operation.
			""")
		.type(nullable(Operation[].class))
		.build();
	PropertyDescriptor CONTAINER_TYPE = PropertyDescriptor.builder()
		.name("containerType")
		.description("""
			The intercepted container type of the entity data.
			""")
		.type(nullable(ClassifierType[].class))
		.build();
	PropertyDescriptor CONTAINER_NAME = PropertyDescriptor.builder()
		.name("containerName")
		.description("""
			The intercepted name of the container (e.g. `attribute`, `reference`, `associatedData`).
			""")
		.type(nullable(String[].class))
		.build();
}
