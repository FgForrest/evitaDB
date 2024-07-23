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

package io.evitadb.externalApi.api.catalog.dataApi.model.extraResult;

import io.evitadb.api.requestResponse.extraResult.Hierarchy;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

/**
 * Represents {@link Hierarchy} in conjunction with {@link io.evitadb.api.query.require.HierarchyOfSelf} and {@link io.evitadb.api.query.require.HierarchyOfReference}.
 *
 * Note: this descriptor is meant be template for generated specific DTOs base on internal data. Fields in this
 * descriptor are supposed to be dynamically registered to target generated DTO.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public interface HierarchyDescriptor {

	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("*Hierarchy")
		.description("""
			This DTO contains hierarchical structures of self hierarchical as well as entities referenced by the entities 
			required by the query. It copies hierarchical structure of those entities and contains their identification
			or full body as well as information on cardinality of referencing entities.
			""")
		.build();

	PropertyDescriptor SELF = PropertyDescriptor.builder()
		.name("self")
		.description("""
			Computes statistics for same entity collection as queried.
			""")
		// type is expected be a `HierarchyOfSelf` object
		.build();
	PropertyDescriptor REFERENCE = PropertyDescriptor.builder()
		.name("*") // this descriptor is meant be used only as template with data
		.description("""
			Computes statistics for referenced entity collection `%s` as queried.
			""")
		// type is expected be a `HierarchyOfReference` object
		.build();

}
