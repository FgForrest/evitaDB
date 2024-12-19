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

package io.evitadb.externalApi.rest.api.catalog.dataApi.model.header;

import io.evitadb.externalApi.api.model.PropertyDescriptor;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nullable;

/**
 * Ancestor for endpoint header arguments for endpoints that return unknown entities.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public interface UnknownEntityEndpointHeaderDescriptor extends FetchEntityEndpointHeaderDescriptor, ScopeAwareEndpointHeaderDescriptor {

	PropertyDescriptor FILTER_JOIN = PropertyDescriptor.builder()
		.name("filterJoin")
		.description("""
			Defines how the filtering arguments are joined together into a single filter. By default, `AND` is used.
			""")
		.type(nullable(QueryHeaderFilterArgumentsJoinType.class))
		.defaultValue(QueryHeaderFilterArgumentsJoinType.AND)
		.build();
}
