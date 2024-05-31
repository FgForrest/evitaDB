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

package io.evitadb.index.hierarchy.predicate;

import io.evitadb.api.requestResponse.extraResult.Hierarchy.LevelInfo;

import javax.annotation.Nonnull;
import java.util.function.Predicate;

/**
 * The predicate controls the scope of the computed hierarchy statistics tree. In other words it defines the scope for
 * which the {@link LevelInfo} objects will be generated. It does not define the visibility scope, however.
 * The visibility scope is driven by {@link HierarchyFilteringPredicate}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public interface HierarchyTraversalPredicate {

	HierarchyTraversalPredicate NEVER_STOP_PREDICATE = (hierarchyNodeId, level, distance) -> true;
	HierarchyTraversalPredicate ONLY_DIRECT_DESCENDANTS = (hierarchyNodeId, level, distance) -> distance <= 1;
	HierarchyTraversalPredicate ONLY_SELF = (hierarchyNodeId, level, distance) -> distance < 1;

	/**
	 * Method should return true or false in case the predicate matches the traversed node or its position in the tree.
	 *
	 * @param hierarchyNodeId the primary key of the visited hierarchy node
	 * @param level           the depth level of the visited hierarchy node
	 * @param distance        the distance from the top node the visitor started to traversing
	 * @return true if the node is accepted
	 */
	boolean test(int hierarchyNodeId, int level, int distance);

	/**
	 * The copy of the {@link Predicate#and(Predicate)} that combines two {@link HierarchyTraversalPredicate} producing
	 * another one in conjunction scope.
	 */
	@Nonnull
	default HierarchyTraversalPredicate and(@Nonnull HierarchyTraversalPredicate other) {
		return (hierarchyNodeId, level, distance) ->
			test(hierarchyNodeId, level, distance) && other.test(hierarchyNodeId, level, distance);
	}

	/**
	 * The interface allows to declare that {@link HierarchyTraversalPredicate} wants to execute hierarchy traversal
	 * in its own "scope".
	 */
	interface SelfTraversingPredicate extends HierarchyTraversalPredicate {

		/**
		 * Analogous method to {@link HierarchyTraversalPredicate#test(int, int, int)} that accepts traversal lambda
		 * which can be optionally invoked by the implementation in its own scope.
		 */
		void traverse(int hierarchyNodeId, int level, int distance, @Nonnull Runnable traverser);

	}

}
