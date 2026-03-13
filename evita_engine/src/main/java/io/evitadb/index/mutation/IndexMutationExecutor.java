/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.index.mutation;

import javax.annotation.Nonnull;

/**
 * Stateless strategy interface for executing a concrete {@link IndexMutation}. Each implementation handles exactly
 * one mutation type and performs the full processing pipeline:
 *
 * 1. Resolves affected owner entity PKs from the collection's own indexes (`ReferencedTypeEntityIndex` ->
 *    `ReducedGroupEntityIndex` / `ReducedEntityIndex`)
 * 2. Gets the pre-translated {@link io.evitadb.api.query.filter.FilterBy FilterBy} from the trigger, parameterizes
 *    it with the mutated entity PK, and evaluates it against current indexes to determine which affected entities
 *    currently satisfy the expression
 * 3. Compares the query result with current facet state and performs the actual index modifications (add/remove
 *    facet) for affected entities
 *
 * Executor instances are stateless singletons — all collection-specific state is received via the
 * {@link IndexMutationTarget} parameter. This means the {@link IndexMutationExecutorRegistry} and all its executors
 * can be a static singleton, avoiding reinstantiation when `EntityCollection` creates transactional copies.
 *
 * Registered in {@link IndexMutationExecutorRegistry} keyed by the concrete mutation class. The target
 * `EntityCollection` dispatches to the executor — no switch/case or orchestration logic in the collection.
 *
 * @param <M> the concrete {@link IndexMutation} subtype this executor handles
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public interface IndexMutationExecutor<M extends IndexMutation> {

	/**
	 * Executes the mutation against the given target collection. Resolves affected PKs, evaluates the expression
	 * via FilterBy query, and performs index operations. The executor is stateless — all collection context comes
	 * from the `target` parameter.
	 *
	 * @param mutation the concrete mutation to execute
	 * @param target   limited view of the target `EntityCollection`
	 */
	void execute(@Nonnull M mutation, @Nonnull IndexMutationTarget target);

}
