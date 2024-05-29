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

package io.evitadb.externalApi.rest.api.catalog.dataApi.model;

import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

/**
 * Descriptor for entities fetching request bodies.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public interface FetchEntityRequestDescriptor {

	PropertyDescriptor FILTER_BY = PropertyDescriptor.builder()
		.name("filterBy")
		.description("""
			Constraints used to filter returned entities.
			""")
		// type is expected to be constraint container
		.build();
	PropertyDescriptor ORDER_BY = PropertyDescriptor.builder()
		.name("orderBy")
		.description("""
			Constraints used to order returned entities.
			""")
		// type is expected to be constraint container
		.build();
	PropertyDescriptor REQUIRE = PropertyDescriptor.builder()
		.name("require")
		.description("""
			Constraints used to specify what data the result will contain.
			""")
		// type is expected to be constraint container
		.build();

	ObjectDescriptor THIS_LIST = ObjectDescriptor.builder()
		.name("*ListRequestBody")
		.description("""
			Request body for listing entities.
			""")
		.build();

	ObjectDescriptor THIS_QUERY = ObjectDescriptor.builder()
		.name("*QueryRequestBody")
		.description("""
			Request body for querying entities.
			""")
		.build();

	ObjectDescriptor THIS_DELETE = ObjectDescriptor.builder()
		.name("*DeleteRequestBody")
		.description("""
			Request body for deleting entities.
			""")
		.build();
}
