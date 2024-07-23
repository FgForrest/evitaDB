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
 * Common ancestor for partial hierarchies. Should not be used directly.
 */
public interface HierarchyOfDescriptor {

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
