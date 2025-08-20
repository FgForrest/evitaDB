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

package io.evitadb.externalApi.api.catalog.schemaApi.model;

import io.evitadb.api.requestResponse.schema.dto.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedReferenceIndexType;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import java.util.List;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;

/**
 * Descriptor representing scope-specific index type of reference.
 * It is used to represent both input ({@link ScopedReferenceIndexType}) in mutations and output in schemas.
 *
 * Note: this descriptor has static structure.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public interface ScopedReferenceIndexTypeDescriptor extends ScopedDataDescriptor {

	PropertyDescriptor INDEX_TYPE = PropertyDescriptor.builder()
		.name("indexType")
		.description("""
			Determines the indexing level for the reference. The index type controls how the reference
			can be used in filtering and querying operations.
			
			- NONE: No indexing, reference cannot be used for filtering
			- FOR_FILTERING: Basic indexing for filtering operations
			- FOR_FILTERING_AND_PARTITIONING: Advanced indexing for both filtering and partitioning operations
			""")
		.type(nonNull(ReferenceIndexType.class))
		.build();

	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("ScopedReferenceIndexType")
		.description("""
			Represents combination of reference index type and entity scope it should be applied to.
			""")
		.staticFields(List.of(SCOPE, INDEX_TYPE))
		.build();

	ObjectDescriptor THIS_INPUT = ObjectDescriptor.builder()
		.name("InputScopedReferenceIndexType")
		.description("""
			Represents combination of reference index type and entity scope it should be applied to.
			""")
		.staticFields(List.of(SCOPE, INDEX_TYPE))
		.build();
}