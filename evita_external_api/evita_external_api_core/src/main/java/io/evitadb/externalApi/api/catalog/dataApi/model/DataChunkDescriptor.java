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

package io.evitadb.externalApi.api.catalog.dataApi.model;

import io.evitadb.dataType.DataChunk;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;

/**
 * Represents {@link DataChunk}
 *
 * Note: this descriptor is meant be template for generated specific entity DTOs base on internal data. Fields in this
 * descriptor are supposed to be dynamically registered to target generated entity DTO.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public interface DataChunkDescriptor {

	PropertyDescriptor DATA = PropertyDescriptor.builder()
		.name("data")
		.description("""
			Actual found sorted page/strip of records.
			""")
		// type is expected to be a collection of `Entity` objects
		.build();
	PropertyDescriptor TOTAL_RECORD_COUNT = PropertyDescriptor.builder()
		.name("totalRecordCount")
		.description("""
			Returns total number of records that are possible to fetch by paginating entire result stream.
			""")
		.type(nonNull(Integer.class))
		.build();
	PropertyDescriptor FIRST = PropertyDescriptor.builder()
		.name("first")
		.description("""
			Returns true if current page/strip is the first page/strip in the result set.
			""")
		.type(nonNull(Boolean.class))
		.build();
	PropertyDescriptor LAST = PropertyDescriptor.builder()
		.name("last")
		.description("""
			Returns true if current page/strip is the last page/strip in the result set.
			""")
		.type(nonNull(Boolean.class))
		.build();
	PropertyDescriptor HAS_PREVIOUS = PropertyDescriptor.builder()
		.name("hasPrevious")
		.description("""
			Returns true if there is previous page/strip available.
			""")
		.type(nonNull(Boolean.class))
		.build();
	PropertyDescriptor HAS_NEXT = PropertyDescriptor.builder()
		.name("hasNext")
		.description("""
			Returns true if there is next page/strip available.
			""")
		.type(nonNull(Boolean.class))
		.build();
	PropertyDescriptor SINGLE_PAGE = PropertyDescriptor.builder()
		.name("singlePage")
		.description("""
			Returns true if there is only single page/strip available (i.e. total record count < record count on one page).
			""")
		.type(nonNull(Boolean.class))
		.build();
	PropertyDescriptor EMPTY = PropertyDescriptor.builder()
		.name("empty")
		.description("""
			Returns true if there are no data available.
			""")
		.type(nonNull(Boolean.class))
		.build();
}
