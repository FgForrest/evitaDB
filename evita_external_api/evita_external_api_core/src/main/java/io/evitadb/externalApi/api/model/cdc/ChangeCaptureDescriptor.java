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
 * Descriptor interface that defines common property descriptors for {@link io.evitadb.api.requestResponse.cdc.ChangeCapture}
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2025
 */
public interface ChangeCaptureDescriptor {

	PropertyDescriptor VERSION = PropertyDescriptor.builder()
		.name("version")
		.description("""
			Returns the target version of the data source to which this mutation advances it.
			""")
		.type(nonNull(Long.class))
		.build();
	PropertyDescriptor INDEX = PropertyDescriptor.builder()
		.name("index")
		.description("""
            Returns the index of the event within the enclosed version. If the operation is part of the multi-step process,
            the index starts with 0 and increments with each such operation. Next capture with `version` + 1 always
            starts with index 0.
            
            This index allows client to build on the previously interrupted CDC stream even in the middle of the transaction.
            This is beneficial in case of very large transactions that still needs to be fully transferred to the client, but
            could be done so in multiple separate chunks.
            
            Combination of `version` and this index precisely identifies the position of a single operation in
            the CDC stream.
			""")
		.type(nonNull(Integer.class))
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
	        Optional body of the operation when it is requested initial request. Carries information about what exactly
			happened.
			""")
		// type is expected to be list of mutations, but the representation varies across APIs
		.build();
}
