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

package io.evitadb.externalApi.api.model.cdc;

import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;

/**
 * Descriptor interface that defines common property descriptors for Change Data Capture (CDC) events.
 * This interface serves as a base for all CDC event descriptors and provides fundamental properties
 * that are shared across different types of CDC events in the evitaDB system.
 *
 * @author Lukáš Hornych, 2023
 */
public interface ChangeCaptureDescriptor {

	// todo lho: feel free to reimplement this... this is the way how we could track if the subscriber received all events
	PropertyDescriptor INDEX = PropertyDescriptor.builder()
		.name("index")
		.description("""
			An index of the event in the ordered CDC log.
			""")
		.type(nonNull(Long.class))
		.build();
	PropertyDescriptor CATALOG = PropertyDescriptor.builder()
		.name("catalog")
		.description("""
			Name of the catalog where the operation was performed.
			""")
		.type(nonNull(String.class))
		.build();
	PropertyDescriptor OPERATION = PropertyDescriptor.builder()
		.name("operation")
		.description("""
			Operation that was performed.
			""")
		.type(nonNull(Operation.class))
		.build();
	PropertyDescriptor BODY = PropertyDescriptor.builder()
		.name("body")
		.description("""
	        Body of the operation. Carries information about what exactly happened.
			""")
		// type is expected to be list of mutations, but the representation varies across APIs
		.build();
}
