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

import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.util.Map;

/**
 * Static singleton registry mapping concrete {@link IndexMutation} types to their stateless
 * {@link IndexMutationExecutor} implementations. Both the registry and its executors hold no instance state — all
 * collection-specific context is passed via {@link IndexMutationTarget} at dispatch time.
 *
 * This design avoids reinstantiation when `EntityCollection` creates transactional copies (which happens on every
 * committed transaction). Adding a new mutation type requires only: a new mutation record, a new stateless executor
 * class, and one entry in the map below.
 *
 * The registry is never a field on `EntityCollection` — it is accessed as
 * `IndexMutationExecutorRegistry.INSTANCE` at dispatch time.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class IndexMutationExecutorRegistry {

	/**
	 * Singleton instance holding the immutable executor map. The `ReevaluateFacetExpressionMutation` entry will be
	 * added when `ReevaluateFacetExpressionExecutor` is implemented in WBS-07.
	 */
	public static final IndexMutationExecutorRegistry INSTANCE = new IndexMutationExecutorRegistry(
		Map.of(
			ReevaluateFacetExpressionMutation.class, new ReevaluateFacetExpressionExecutor()
		)
	);

	/**
	 * Immutable map from concrete {@link IndexMutation} class to the stateless executor that handles it.
	 */
	private final Map<Class<? extends IndexMutation>, IndexMutationExecutor<?>> executors;

	/**
	 * Creates a new registry with the given executor map. The map is defensively copied to ensure immutability.
	 *
	 * @param executors mapping from concrete mutation class to the executor handling that mutation type
	 */
	IndexMutationExecutorRegistry(
		@Nonnull Map<Class<? extends IndexMutation>, IndexMutationExecutor<?>> executors
	) {
		this.executors = Map.copyOf(executors);
	}

	/**
	 * Looks up the executor for the given mutation type and executes it against the target collection.
	 * Throws if no executor is registered for the mutation's concrete class — fail-fast to prevent silently
	 * dropped mutations.
	 *
	 * The unchecked cast from `IndexMutationExecutor<?>` to `IndexMutationExecutor<M>` is type-safe because
	 * the registry enforces that each key's class matches its value's generic parameter at registration time.
	 *
	 * @param mutation the concrete mutation to dispatch
	 * @param target   limited view of the target `EntityCollection`
	 * @param <M>      the concrete mutation type
	 */
	@SuppressWarnings("unchecked")
	public <M extends IndexMutation> void dispatch(
		@Nonnull M mutation,
		@Nonnull IndexMutationTarget target
	) {
		final IndexMutationExecutor<M> executor = (IndexMutationExecutor<M>) this.executors.get(mutation.getClass());
		Assert.notNull(
			executor,
			() -> "No executor registered for mutation type `" + mutation.getClass().getName() + "`."
		);
		executor.execute(mutation, target);
	}

}
