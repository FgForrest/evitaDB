/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.externalApi.api.system.model.cdc;

import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.TopLevelCatalogSchemaMutationAggregateDescriptor;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;
import io.evitadb.externalApi.api.model.cdc.ChangeCaptureDescriptor;

import java.util.List;

import static io.evitadb.externalApi.api.model.ObjectPropertyDataTypeDescriptor.nonNullListRef;
import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;

/**
 * TODO lho docs
 *
 * @author Lukáš Hornych, 2023
 */
public interface ChangeSystemCaptureDescriptor extends ChangeCaptureDescriptor {

	PropertyDescriptor CATALOG = PropertyDescriptor.builder()
		.name("catalog")
		.description("""
			Name of catalog in question.
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
            Body of the operation, i.e. what happened.
			""")
		// type is expected to be list of top level catalog schema mutations, but the representation vary across APIs
		.build();

	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("ChangeSystemCapture")
		.staticFields(List.of(INDEX, CATALOG, OPERATION))
		.build();
}
