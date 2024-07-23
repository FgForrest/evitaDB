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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.model.extraResult;

import io.evitadb.externalApi.api.model.PropertyDescriptor;

/**
 * Descriptor of header arguments of facets for {@link io.evitadb.api.query.require.FacetSummaryOfReference} for specific reference.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public interface FacetStatisticsHeaderDescriptor {

	PropertyDescriptor FILTER_BY = PropertyDescriptor.builder()
		.name("filterBy")
		// TOBEDONE JNO: proper docs for filterBy
		.description("""
			Filters returned facets by defined constraints.
			""")
		// type is expected to be a  `filterBy` container
		.build();
	PropertyDescriptor ORDER_BY = PropertyDescriptor.builder()
		.name("orderBy")
		// TOBEDONE JNO: proper docs for orderBy
		.description("""
			Sorts returned facets by defined constraints.
			""")
		// type is expected to be a `orderBy` container
		.build();
}
