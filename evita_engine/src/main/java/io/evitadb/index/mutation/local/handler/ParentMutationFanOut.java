/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.index.mutation.local.handler;

import io.evitadb.api.requestResponse.data.mutation.parent.ParentMutation;
import io.evitadb.core.catalog.CatalogExpressionTriggerRegistry;
import io.evitadb.core.expression.trigger.FacetExpressionTrigger;
import io.evitadb.dataType.Scope;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.IndexType;
import io.evitadb.index.mutation.local.EntityIndexLocalMutationExecutor;
import io.evitadb.index.mutation.local.EntityIndexLocalMutationExecutor.Target;
import io.evitadb.index.mutation.local.ReferenceIndexMutator;

import javax.annotation.Nonnull;

/**
 * Shared orchestration for the two parent-mutation handlers.
 *
 * Before the parent changes, it withdraws the histogram values of the references whose histogram condition reads the
 * parent, so the final add of the run evaluates the condition against the new parent. The withdrawal is skipped while
 * the entity is being removed entirely.
 *
 * It then delegates the actual hierarchy placement update to `executor.updateHierarchyPlacement`, which switches on
 * the concrete parent mutation type, and defers facet re-evaluation for any trigger that consumes parent state.
 */
final class ParentMutationFanOut {

	/**
	 * Prevents instantiation of the shared parent orchestration.
	 */
	private ParentMutationFanOut() {
		// no instances
	}

	/**
	 * Withdraws the histogram values of the references whose condition reads the parent, updates the hierarchy
	 * placement of the entity and defers the re-evaluation of the facet conditions reading the parent.
	 *
	 * @param mutation    the parent mutation being applied
	 * @param executor    the active executor
	 * @param globalIndex pre-resolved global index for the active scope
	 */
	static void apply(
		@Nonnull ParentMutation mutation,
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull GlobalEntityIndex globalIndex
	) {
		withdrawHistogramValuesOfReferencesReadingParent(executor);
		executor.updateHierarchyPlacement(mutation, globalIndex);
		// defer re-evaluation to after storage write so expression reads updated parent
		if (executor.hasFacetExpressionTriggers()) {
			final int entityPK = executor.getPrimaryKeyToIndex(IndexType.ENTITY_INDEX, Target.EXISTING);
			executor.deferExpressionReEvaluation(
				() -> ReferenceIndexMutator.reEvaluateFacetExpressionsInAllIndexes(
					globalIndex, executor, entityPK,
					FacetExpressionTrigger::usesParent
				)
			);
		}
	}

	/**
	 * Withdraws, before the parent changes in storage, the histogram values of every stored reference whose histogram
	 * condition reads the parent, and schedules their final add. When no histogram condition of the entity type reads
	 * the parent in the current scope, it costs the registry lookup alone - at most two map probes - and reads no
	 * stored reference.
	 *
	 * @param executor the active executor
	 */
	private static void withdrawHistogramValuesOfReferencesReadingParent(
		@Nonnull EntityIndexLocalMutationExecutor executor
	) {
		final CatalogExpressionTriggerRegistry registry = executor.getCatalogExpressionTriggerRegistry();
		// Entity removal drops the parent before the references, and each reference removal of an entity removed
		// entirely takes out whatever the owner holds without evaluating a condition - after asserting that no
		// reference awaits a final add. A withdrawal here would schedule one even for a false condition.
		if (registry == null || executor.getContainerAccessor().isEntityRemovedEntirely()) {
			return;
		}
		final Scope scope = executor.getScope();
		OwnerInputHistogramWithdrawal.withdraw(
			executor, registry.getLocalHistogramReferencesForParent(executor.getEntityType(), scope), scope
		);
	}

}
