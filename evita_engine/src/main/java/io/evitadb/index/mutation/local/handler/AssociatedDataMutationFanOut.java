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

import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.IndexType;
import io.evitadb.index.mutation.local.EntityIndexLocalMutationExecutor;
import io.evitadb.index.mutation.local.EntityIndexLocalMutationExecutor.Target;
import io.evitadb.index.mutation.local.ReferenceIndexMutator;

import javax.annotation.Nonnull;

/**
 * Shared deferred-facet-only fan-out for the two concrete associated-data mutations. Associated
 * data is opaque to all reduced indexes, so the only side-effect is to register a post-write
 * facet re-evaluation when a trigger references the mutated key.
 */
final class AssociatedDataMutationFanOut {

	private AssociatedDataMutationFanOut() {
		// no instances
	}

	/**
	 * Defers facet expression re-evaluation for triggers that read the named associated data.
	 *
	 * @param mutatedDataName the associated data name from the mutation
	 * @param executor        the active executor
	 * @param globalIndex     pre-resolved global index for the active scope
	 */
	static void apply(
		@Nonnull String mutatedDataName,
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull GlobalEntityIndex globalIndex
	) {
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

}
