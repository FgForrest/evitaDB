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

package io.evitadb.externalApi.api.catalog.dataApi.model;

import io.evitadb.dataType.PaginatedList;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import java.util.List;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;

/**
 * Ancestor for {@link PaginatedList} objects.
 *
 * Note: this descriptor is meant be template for generated specific entity DTOs base on internal data. Fields in this
 * descriptor are supposed to be dynamically registered to target generated entity DTO.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public interface PaginatedListDescriptor extends DataChunkDescriptor {

	PropertyDescriptor PAGE_SIZE = PropertyDescriptor.builder()
		.name("pageSize")
		.description("""
			Returns number of records per single page.
			""")
		.type(nonNull(Integer.class))
		.build();
	PropertyDescriptor PAGE_NUMBER = PropertyDescriptor.builder()
		.name("pageNumber")
		.description("""
			Returns current page number (indexed from 1).
			""")
		.type(nonNull(Integer.class))
		.build();
	PropertyDescriptor LAST_PAGE_NUMBER = PropertyDescriptor.builder()
		.name("lastPageNumber")
		.description("""
			Returns number of the last page that can be accessed with current number of records.
			Returns -1 when offset/limit was used for creating paginated list.
			""")
		.type(nonNull(Integer.class))
		.build();
	PropertyDescriptor FIRST_PAGE_ITEM_NUMBER = PropertyDescriptor.builder()
		.name("firstPageItemNumber")
		.description("""
			Returns offset of the first record of current page with current pageSize.
			""")
		.type(nonNull(Integer.class))
		.build();
	PropertyDescriptor LAST_PAGE_ITEM_NUMBER = PropertyDescriptor.builder()
		.name("lastPageItemNumber")
		.description("""
			Returns offset of the last record of current page with current pageSize.
			""")
		.type(nonNull(Integer.class))
		.build();

	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("*PaginatedList")
		.description("""
			Page of records according to pagination rules in input query.
			""")
		.staticProperties(List.of(
			PAGE_SIZE,
			PAGE_NUMBER,
			LAST_PAGE_NUMBER,
			FIRST_PAGE_ITEM_NUMBER,
			LAST_PAGE_ITEM_NUMBER,
			TOTAL_RECORD_COUNT,
			FIRST,
			LAST,
			HAS_PREVIOUS,
			HAS_NEXT,
			SINGLE_PAGE,
			EMPTY
		))
		.build();
}
