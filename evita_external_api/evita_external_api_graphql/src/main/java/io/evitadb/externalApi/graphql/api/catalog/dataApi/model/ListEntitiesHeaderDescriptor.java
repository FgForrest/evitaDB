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
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.externalApi.graphql.api.catalog.dataApi.model;

import io.evitadb.externalApi.api.catalog.dataApi.model.CatalogDataApiRootDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nullable;

/**
 * Descriptor for header arguments of {@link CatalogDataApiRootDescriptor#LIST_ENTITY}
 * query.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public interface ListEntitiesHeaderDescriptor {

	PropertyDescriptor FILTER_BY = PropertyDescriptor.builder()
		.name("filterBy")
		.description("""
			Complex filter query to filter result entities by.
			""")
		// type is expected to be tree of filter constraints
		.build();
	PropertyDescriptor ORDER_BY = PropertyDescriptor.builder()
		.name("orderBy")
		.description("""
			Complex order query to order result entities by.
			""")
		// type is expected to be tree of order constraints
		.build();
	PropertyDescriptor OFFSET = PropertyDescriptor.builder()
		.name("offset")
		.description("""
			Argument for adjusting default offset of returned results. It is shortcut for full paging.
			""")
		.type(nullable(Integer.class))
		.build();
	PropertyDescriptor LIMIT = PropertyDescriptor.builder()
		.name("limit")
		.description("""
			Argument for adjusting default number of maximum results. It is shortcut for full paging.
			""")
		.type(nullable(Integer.class))
		.build();
}
