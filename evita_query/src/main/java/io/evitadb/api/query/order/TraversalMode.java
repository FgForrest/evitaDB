/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.api.query.order;


import io.evitadb.dataType.SupportedEnum;

/**
 * This enum defines the two modes of traversing a hierarchy when using the {@link TraverseByEntityProperty} ordering
 * constraint.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@SupportedEnum
public enum TraversalMode {

	/**
	 * The depth-first traversal mode traverses the hierarchy in a depth-first manner, meaning it will
	 * explore as far as possible along each branch before backtracking.
	 */
	DEPTH_FIRST,
	/**
	 * The breadth-first traversal mode traverses the hierarchy in a breadth-first manner, meaning it will
	 * explore all the nodes at the present depth level before moving on to the nodes at the next depth level.
	 */
	BREADTH_FIRST

}
