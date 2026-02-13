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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.model.extraResult;

import io.evitadb.externalApi.api.model.PropertyDescriptor;

/**
 * Common ancestor for partial hierarchies. Should not be used directly.
 */
public interface HierarchyOfDescriptor {

	PropertyDescriptor FROM_ROOT = PropertyDescriptor.builder()
		.name("fromRoot")
		.description("""
			Computes the hierarchy tree starting from the "virtual" invisible top root of
			the hierarchy, regardless of the potential use of the `hierarchyWithin` constraint
			in the filtering part of the query. The scope of the calculated information can be
			controlled by the `stopAt` argument. Note: for multiple different hierarchies
			beginning from the root, the use of field alias is encouraged here.
			""")
		// type is expected to be a list of `LevelInfo` objects relevant to specified reference
		.build();
	PropertyDescriptor FROM_NODE = PropertyDescriptor.builder()
		.name("fromNode")
		.description("""
			Computes the hierarchy tree starting from the pivot node of the hierarchy,
			that is identified by the `node` argument. The `fromNode` calculates the result
			regardless of the potential use of the `hierarchyWithin` constraint in the filtering
			part of the query. Note: for multiple different hierarchies beginning from a node,
			the use of field alias is encouraged here.
			""")
		// type is expected to be a list of `LevelInfo` objects relevant to specified reference
		.build();
	PropertyDescriptor CHILDREN = PropertyDescriptor.builder()
		.name("children")
		.description("""
			Computes the hierarchy tree starting at the same hierarchy node that is targeted
			by the filtering part of the same query using the `hierarchyWithin` or
			`hierarchyWithinRoot` constraints. The scope of the calculated information can be
			controlled by the `stopAt` argument. Note: for multiple different children
			hierarchies, the use of field alias is encouraged here.
			""")
		// type is expected to be a list of `LevelInfo` objects relevant to specified reference
		.build();
	PropertyDescriptor PARENTS = PropertyDescriptor.builder()
		.name("parents")
		.description("""
			Computes the hierarchy tree starting at the same hierarchy node that is targeted
			by the filtering part of the same query using the `hierarchyWithin` constraint
			towards the root of the hierarchy. The scope of the calculated information can be
			controlled by the `stopAt` argument. Note: for multiple different parents
			hierarchies, the use of field alias is encouraged here.
			""")
		// type is expected to be a list of `LevelInfo` objects relevant to specified reference
		.build();
	PropertyDescriptor SIBLINGS = PropertyDescriptor.builder()
		.name("siblings")
		.description("""
			Lists all sibling nodes to the node that is requested by `hierarchyWithin`
			constraint. Siblings will produce a flat list of siblings unless the `stopAt`
			argument is used. Note: for multiple different siblings hierarchies, the use of
			field alias is encouraged here.
			""")
		// type is expected to be a list of `LevelInfo` objects relevant to specified reference
		.build();
}
