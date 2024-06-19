/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

import io.evitadb.core.query.QueryExecutionContext;

import javax.annotation.Nonnull;

/**
 * Shared interface for making initialization of the predicates working with the formulas.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
sealed interface ExecutionContextRequiringPredicate permits HierarchyFilteringPredicate, HierarchyTraversalPredicate {

	/**
	 * Method needs to be called before the first call of the test method of the predicate.
	 * Implementations must be prepared that the method might be called multiple times and ensure only
	 * first initialization will be performed.
	 *
	 * @param executionContext the context for the query execution
	 */
	default void initializeIfNotAlreadyInitialized(@Nonnull QueryExecutionContext executionContext) {
		// do nothing by default
	}

}
