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

import io.evitadb.api.requestResponse.extraResult.Hierarchy;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import java.util.List;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;
import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nullable;

/**
 * Represents {@link Hierarchy} in conjunction with {@link io.evitadb.api.query.require.HierarchyOfSelf} and {@link io.evitadb.api.query.require.HierarchyOfReference}.
 *
 * Note: this descriptor is meant be template for generated specific DTOs base on internal data. Fields in this
 * descriptor are supposed to be dynamically registered to target generated DTO.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
// TODO LHO consider moving this to GQL as this is no longer universal to REST and create custom new one for REST aswell
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

	/**
	 * Common ancestor for partial hierarchies. Should not be used directly.
	 */
	interface HierarchyOfDescriptor {

		PropertyDescriptor FROM_ROOT = PropertyDescriptor.builder()
			.name("fromRoot")
			// TOBEDONE JNO: fromRoot docs
			.description("""
                Note: for multiple different hierarchies beginning from the root, the use of field alias is encouraged here.
				""")
			// type is expected to be a list of `LevelInfo` objects relevant to specified reference
			.build();
		PropertyDescriptor FROM_NODE = PropertyDescriptor.builder()
			.name("fromNode")
			// TOBEDONE JNO: fromNode docs
			.description("""
                Note: for multiple different hierarchies beginning from a node, the use of field alias is encouraged here.
				""")
			// type is expected to be a list of `LevelInfo` objects relevant to specified reference
			.build();
		PropertyDescriptor CHILDREN = PropertyDescriptor.builder()
			.name("children")
			// TOBEDONE JNO: children docs
			.description("""
                Note: for multiple different children hierarchies, the use of field alias is encouraged here.
				""")
			// type is expected to be a list of `LevelInfo` objects relevant to specified reference
			.build();
		PropertyDescriptor PARENTS = PropertyDescriptor.builder()
			.name("parents")
			// TOBEDONE JNO: parents docs
			.description("""
                Note: for multiple different parents hierarchies, the use of field alias is encouraged here.
				""")
			// type is expected to be a list of `LevelInfo` objects relevant to specified reference
			.build();
		PropertyDescriptor SIBLINGS = PropertyDescriptor.builder()
			.name("siblings")
			// TOBEDONE JNO: siblings docs
			.description("""
                Note: for multiple different siblings hierarchies, the use of field alias is encouraged here.
				""")
			// type is expected to be a list of `LevelInfo` objects relevant to specified reference
			.build();
	}

	/**
	 * Represents {@link Hierarchy#getSelfStatistics()}
	 *
	 * Note: this descriptor is meant be template for generated specific DTOs base on internal data. Fields in this
	 * descriptor are supposed to be dynamically registered to target generated DTO.
	 */
	interface HierarchyOfSelfDescriptor extends HierarchyOfDescriptor {

		ObjectDescriptor THIS = ObjectDescriptor.builder()
			.name("*Hierarchy")
			.description("""
				This DTO contains hierarchical structures of hierarchical entities of same type is the queried one. It copies
				hierarchical structure of those entities and contains their identification or full body as well as information on
				cardinality of referencing entities.
				""")
			.build();
	}
	/**
	 * Represents {@link Hierarchy#getStatistics(String, String)}.
	 *
	 * Note: this descriptor is meant be template for generated specific DTOs base on internal data. Fields in this
	 * descriptor are supposed to be dynamically registered to target generated DTO.
	 */
	interface HierarchyOfReferenceDescriptor extends HierarchyOfDescriptor {

		ObjectDescriptor THIS = ObjectDescriptor.builder()
			.name("*Hierarchy")
			.description("""
				This DTO contains hierarchical structures of entities referenced by the entities required by the query. It copies
				hierarchical structure of those entities and contains their identification or full body as well as information on
				cardinality of referencing entities.
				""")
			.build();
	}

	/**
	 * Represents {@link Hierarchy.LevelInfo}.
	 *
	 * Note: this descriptor is meant be template for generated specific DTOs base on internal data. Fields in this
	 * descriptor are supposed to be dynamically registered to target generated DTO.
	 */
	interface LevelInfoDescriptor {

		PropertyDescriptor PARENT_ID = PropertyDescriptor.builder()
			.name("parentId")
			.description("""
				ID of parent hierarchical entity if this entity is not root entity.
				""")
			.type(nullable(Integer.class))
			.build();
		PropertyDescriptor ENTITY = PropertyDescriptor.builder()
			.name("entity")
			.description("""
				Hierarchical entity at position in tree represented by this object.
				""")
			// type is expected to be a `Entity` object
			.build();
		PropertyDescriptor QUERIED_ENTITY_COUNT = PropertyDescriptor.builder()
			.name("queriedEntityCount")
			.description("""
				Contains the number of queried entities that refer directly to this {@link #entity} or to any of its children
				entities.
				""")
			.type(nullable(Integer.class))
			.build();
		PropertyDescriptor CHILDREN_COUNT = PropertyDescriptor.builder()
			.name("childrenCount")
			.description("""
				Contains number of hierarchical entities that are referring to this `entity` as its parent.
				The count will respect behaviour settings and will not
				count empty children in case `REMOVE_EMPTY` is
				used for computation.
				""")
			.type(nullable(Integer.class))
			.build();
		PropertyDescriptor HAS_CHILDREN = PropertyDescriptor.builder()
			.name("hasChildren")
			.description("""
                Whether this hierarchical entity has any child entities.
				""")
			.type(nonNull(Boolean.class))
			.build();

		ObjectDescriptor THIS = ObjectDescriptor.builder()
			.name("*LevelInfo")
			.description("""
				This DTO represents single hierarchical entity in the hierarchy tree. It contains identification of the entity,
				the cardinality of queried entities that refer to it and information about children level.
				""")
			.staticFields(List.of(PARENT_ID, QUERIED_ENTITY_COUNT, CHILDREN_COUNT, HAS_CHILDREN))
			.build();
	}
}
