/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.externalApi.rest.api.catalog.dataApi.model;

import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;

/**
 * This is helper descriptor for aggregate of implementations of data chunk for OpenAPI.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public interface DataChunkUnionDescriptor {

	PropertyDescriptor DISCRIMINATOR = PropertyDescriptor.builder()
		.name("type")
		.description("""
			Contains information about type of returned page object
			""")
		.type(nonNull(String.class))
		.build();


	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("*DataChunk")
		.description("""
			Returns either `page` or `strip` of records according to pagination rules in input query.
			""")
		.build();
}
