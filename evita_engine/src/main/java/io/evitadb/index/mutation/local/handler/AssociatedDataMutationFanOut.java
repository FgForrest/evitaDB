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

import io.evitadb.api.requestResponse.data.mutation.associatedData.AssociatedDataMutation;
import io.evitadb.core.catalog.CatalogExpressionTriggerRegistry;
import io.evitadb.dataType.Scope;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.IndexType;
import io.evitadb.index.mutation.local.EntityIndexLocalMutationExecutor;
import io.evitadb.index.mutation.local.EntityIndexLocalMutationExecutor.Target;
import io.evitadb.index.mutation.local.FulltextIndexMutator;
import io.evitadb.index.mutation.local.ReferenceIndexMutator;

import javax.annotation.Nonnull;

/**
 * Shared fan-out for the two concrete associated-data mutations. Searchable associated data updates the
 * fulltext indexes in the global index. Before storage changes, the fan-out withdraws histogram values of
 * references whose condition reads the mutated key, then registers a post-write facet re-evaluation.
 */
final class AssociatedDataMutationFanOut {

	/**
	 * Prevents instantiation of the shared associated-data orchestration.
	 */
	private AssociatedDataMutationFanOut() {
		// no instances
	}

	/**
	 * Updates fulltext indexes, withdraws reference histogram values whose condition reads the named associated
	 * data, and defers facet expression re-evaluation for triggers that read it.
	 *
	 * @param mutation    the associated data mutation
	 * @param executor    the active executor
	 * @param globalIndex pre-resolved global index for the active scope
	 */
	static void apply(
		@Nonnull AssociatedDataMutation mutation,
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull GlobalEntityIndex globalIndex
	) {
		// the fulltext indexes live in the global index only - the value it replaces is read before the storage
		// executor applies the same mutation
		FulltextIndexMutator.executeAssociatedDataMutation(executor, globalIndex, mutation);
		final String mutatedDataName = mutation.getAssociatedDataKey().associatedDataName();
		withdrawHistogramValuesOfReferencesReading(mutatedDataName, executor);
		// defer re-evaluation to after storage write so expression reads updated associated data
		if (executor.hasFacetExpressionTriggers()) {
			final int entityPK = executor.getPrimaryKeyToIndex(IndexType.ENTITY_INDEX, Target.EXISTING);
			executor.deferExpressionReEvaluation(
				() -> ReferenceIndexMutator.reEvaluateFacetExpressionsInAllIndexes(
					globalIndex, executor, entityPK,
					trigger -> trigger.getLocalAssociatedData().contains(mutatedDataName)
				)
			);
		}
	}

	/**
	 * Withdraws, before the associated data changes in storage, the histogram values of every stored reference whose
	 * histogram condition reads it, and schedules their final add. When no histogram condition of the entity type
	 * reads the associated data in the current scope, it costs the registry lookup alone - at most three map probes -
	 * and reads no stored reference.
	 *
	 * @param mutatedDataName the associated data name from the mutation
	 * @param executor        the active executor
	 */
	private static void withdrawHistogramValuesOfReferencesReading(
		@Nonnull String mutatedDataName,
		@Nonnull EntityIndexLocalMutationExecutor executor
	) {
		final CatalogExpressionTriggerRegistry registry = executor.getCatalogExpressionTriggerRegistry();
		// No guard for an entity removed entirely: its removal emits the associated-data removals after the reference
		// removals, and each mutation reaches storage before the next one, so this walk meets only dropped references.
		if (registry != null) {
			final Scope scope = executor.getScope();
			OwnerInputHistogramWithdrawal.withdraw(
				executor,
				registry.getLocalHistogramReferencesForAssociatedData(executor.getEntityType(), scope, mutatedDataName),
				scope
			);
		}
	}

}
