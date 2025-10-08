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

import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.dataType.ContainerType;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import java.util.List;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nullable;

/**
 * Static descriptor of {@link io.evitadb.api.requestResponse.cdc.SchemaSite}
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2025
 */
public interface SchemaSiteDescriptor {

	PropertyDescriptor ENTITY_TYPE = PropertyDescriptor.builder()
		.name("entityType")
		.description("The intercepted entity type")
		.type(nullable(String.class))
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
		.type(nullable(ContainerType[].class))
		.build();

	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("SchemaSite")
		.description("Record describing the location and form of the CDC event in the evitaDB that should be captured.")
		.staticFields(List.of(
			ENTITY_TYPE,
			OPERATION,
			CONTAINER_TYPE
		))
		.build();
}
