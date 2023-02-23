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

package io.evitadb.externalApi.api.catalog.dataApi.model.extraResult;

import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import java.util.List;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;

/**
 * Represents {@link io.evitadb.api.requestResponse.extraResult.HierarchyStatistics}.
 *
 * Note: this descriptor is meant be template for generated specific DTOs base on internal data. Fields in this
 * descriptor are supposed to be dynamically registered to target generated DTO.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public interface HierarchyStatisticsDescriptor {

	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("*HierarchyStatistics")
		.description("""
			This DTO contains hierarchical structure of entities referenced by the entities required by the query. It copies
			hierarchical structure of those entities and contains their identification or full body as well as information on
			cardinality of referencing entities.
			""")
		.build();

	PropertyDescriptor SELF = PropertyDescriptor.builder()
		.name("self")
		.description("""
			Computes statistics for same entity collection as queried.
			""")
		// type is expected be a parents of entity object
		.build();

	/**
	 * Represents {@link io.evitadb.api.requestResponse.extraResult.HierarchyStatistics.LevelInfo}.
	 *
	 * Note: this descriptor is meant be template for generated specific DTOs base on internal data. Fields in this
	 * descriptor are supposed to be dynamically registered to target generated DTO.
	 */
	interface HierarchyStatisticsLevelInfoDescriptor {

		PropertyDescriptor ENTITY = PropertyDescriptor.builder()
			.name("entity")
			.description("""
				Entity in tree.
				""")
			// type is expected to be a `Entity` object
			.build();
		PropertyDescriptor CARDINALITY = PropertyDescriptor.builder()
			.name("cardinality")
			.description("""
				Contains the number of queried entities that refer directly to this `entity` or to any of its children
				entities.
				""")
			.type(nonNull(Integer.class))
			.build();
		PropertyDescriptor CHILDREN_STATISTICS = PropertyDescriptor.builder()
			.name("childrenStatistics")
			.description("""
				Contains statistics of the entities that are subordinate (children) of this `entity`.
				""")
			// type is expected to be a collection of `LevelInfo` objects
			.build();

		ObjectDescriptor THIS = ObjectDescriptor.builder()
			.name("*HierarchyStatisticsLevelInfo")
			.description("""
				This DTO represents single hierarchical entity in the statistics tree. It contains identification of the entity,
				the cardinality of queried entities that refer to it and information about children level.
				""")
			.staticFields(List.of(CARDINALITY))
			.build();
	}
}
