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

package io.evitadb.externalApi.api.catalog.dataApi.model.mutation.entity;

import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import java.util.List;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;
import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nullable;

/**
 * Descriptor representing {@link io.evitadb.api.requestResponse.data.mutation.entity.SetHierarchicalPlacementMutation}.
 *
 * Note: this descriptor has static structure.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public interface SetHierarchicalPlacementMutationDescriptor {

	PropertyDescriptor PARENT_PRIMARY_KEY = PropertyDescriptor.builder()
		.name("parentPrimaryKey")
		.description("""
			Optional new primary key of parent entity. If null, this entity is at the root of hierarchy.
			""")
		.type(nullable(Integer.class))
		.build();
	PropertyDescriptor ORDER_AMONG_SIBLINGS = PropertyDescriptor.builder()
		.name("orderAmongSiblings")
		.description("""
			Represents order of this entity among other entities under the same parent. It's recommended to be unique, but
			it isn't enforced so it could behave like reversed priority where lower number is better (i.e. Integer.MIN is
			the first entity under the parent, Integer.MAX is the last entity under the same parent).
			""")
		.type(nonNull(Integer.class))
		.build();


	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("SetHierarchicalPlacementMutation")
		.description("""
			This mutation allows to set `hierarchicalPlacement` in the `entity`.
			""")
		.staticFields(List.of(PARENT_PRIMARY_KEY, ORDER_AMONG_SIBLINGS))
		.build();
}
