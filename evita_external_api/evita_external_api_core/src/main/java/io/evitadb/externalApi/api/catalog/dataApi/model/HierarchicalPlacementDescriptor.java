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

package io.evitadb.externalApi.api.catalog.dataApi.model;

import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import java.util.List;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;
import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nullable;

/**
 * Represents {@link io.evitadb.api.requestResponse.data.structure.HierarchicalPlacement}
 *
 * Note: this descriptor has static structure.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public interface HierarchicalPlacementDescriptor {

	PropertyDescriptor PARENT_PRIMARY_KEY = PropertyDescriptor.builder()
		.name("parentPrimaryKey")
		.description("""
			Reference to primary key of the parent entity.
			Null parent primary key means, that the entity is root entity with no parent (there may be multiple root entities).
			""")
		.type(nullable(Integer.class))
		.build();
	PropertyDescriptor ORDER_AMONG_SIBLINGS = PropertyDescriptor.builder()
		.name("orderAmongSiblings")
		.description("""
			Represents order of this entity among other entities under the same parent. It's recommended to be unique, but
			it isn't enforced so it could behave like reversed priority where lower number is better.
			""")
		.type(nonNull(Integer.class))
		.build();


	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("HierarchicalPlacement")
		.description("""
			Entities may be organized in hierarchical fashion. That means that entity may refer to single parent entity and may be
			referred by multiple child entities. Hierarchy is always composed of entities of same type.
			Each entity must be part of at most single hierarchy (tree).
			Hierarchy can limit returned entities by using filtering constraints `hierarchy_{hierarchy name}_within`. It's also used for
			computation of extra data - such as `hierarchyOfSelf`.
			""")
		.staticFields(List.of(PARENT_PRIMARY_KEY, ORDER_AMONG_SIBLINGS))
		.build();
}
