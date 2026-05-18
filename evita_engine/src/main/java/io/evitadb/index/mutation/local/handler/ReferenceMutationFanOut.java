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

import io.evitadb.api.requestResponse.data.mutation.reference.InsertReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceMutation;
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.index.AbstractReducedEntityIndex;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.IndexType;
import io.evitadb.index.mutation.local.EntityIndexLocalMutationExecutor;
import io.evitadb.index.mutation.local.EntityIndexLocalMutationExecutor.Target;
import io.evitadb.index.mutation.local.ReferenceIndexConsumer;
import io.evitadb.index.mutation.local.ReferenceIndexMutator;

import javax.annotation.Nonnull;
import java.util.function.Predicate;

/**
 * Shared orchestration for the five concrete reference-mutation handlers. Uses per-reference
 * fan-out (`fanOutPerReference` with `IterationPath.BOTH`) because the consumer is keyed by the
 * individual reference key — facet add/remove must fire once per reference, even when N sibling
 * references resolve to the same shared `ReducedGroupEntityIndex`. The unique-per-index variant
 * would lose facet entries for the siblings folded into the dedup.
 *
 * The type-specific work lives inside `executor.updateReferences` (cross-reference paths) and
 * `executor.updateReferencesInReferenceIndex` (this-reference paths); both still dispatch on the
 * concrete mutation type because they are shared helpers, not per-mutation entry points.
 */
final class ReferenceMutationFanOut {

	private ReferenceMutationFanOut() {
		// no instances
	}

	static void apply(
		@Nonnull ReferenceMutation<?> mutation,
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull GlobalEntityIndex globalIndex
	) {
		final ReferenceKey referenceKey = mutation.getReferenceKey();
		final ReferenceSchemaContract referenceSchema =
			executor.getEntitySchema().getReferenceOrThrowException(referenceKey.referenceName());
		if (!referenceSchema.isIndexedInScope(executor.getScope())) {
			return;
		}
		executor.updateReferences(mutation, globalIndex);
		final ReferenceIndexConsumer crossRefConsumer =
			(theReferenceSchema, indexForRemoval, indexForUpsert) -> executor.updateReferencesInReferenceIndex(
				mutation, theReferenceSchema,
				(AbstractReducedEntityIndex) indexForRemoval,
				(AbstractReducedEntityIndex) indexForUpsert
			);
		// avoid indexing the referenced index that got updated by updateReferences method
		final Predicate<io.evitadb.api.requestResponse.data.ReferenceContract> crossRefPredicate =
			referenceContract -> !referenceKey.equalsInGeneral(referenceContract.getReferenceKey());
		final boolean presenceExpected = !(mutation instanceof InsertReferenceMutation);
		// per-reference: facet add/remove is keyed by the individual reference key, so when N
		// references share a single shared RGEI, each reference must still process its own facet
		// bookkeeping for its own reference key (the consumer is a no-op for ReferenceAttributeMutation,
		// and the other branches use the iterating reference's data, not entity-level state).
		executor.fanOutPerReference(
			ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING,
			crossRefConsumer, crossRefPredicate, presenceExpected,
			ReferenceIndexMutator.IterationPath.BOTH
		);
		// defer re-evaluation to after storage write so expression reads updated reference attributes
		if (executor.hasFacetExpressionTriggers() && mutation instanceof ReferenceAttributeMutation ram) {
			final String mutatedAttrName = ram.getAttributeKey().attributeName();
			final int entityPK = executor.getPrimaryKeyToIndex(IndexType.ENTITY_INDEX, Target.EXISTING);
			final String mutatedRefName = referenceKey.referenceName();
			executor.deferExpressionReEvaluation(
				() -> ReferenceIndexMutator.reEvaluateFacetExpressionsInAllIndexes(
					globalIndex, executor, entityPK,
					trigger -> mutatedRefName.equals(trigger.getReferenceName())
						&& trigger.getLocalReferenceAttributes().contains(mutatedAttrName)
				)
			);
		}
		// defer histogram re-evaluation for reference attribute changes
		if (mutation instanceof ReferenceAttributeMutation ram2) {
			executor.deferHistogramReEvaluationForReferenceAttribute(
				referenceKey, ram2.getAttributeKey().attributeName(), executor.getScope()
			);
		}
	}

}
