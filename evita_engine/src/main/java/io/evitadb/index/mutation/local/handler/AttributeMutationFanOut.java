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

import io.evitadb.api.requestResponse.data.Droppable;
import io.evitadb.api.requestResponse.data.mutation.attribute.AttributeMutation;
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.catalog.CatalogExpressionTriggerRegistry;
import io.evitadb.function.QuadriConsumer;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.IndexType;
import io.evitadb.index.mutation.local.EntityIndexLocalMutationExecutor;
import io.evitadb.index.mutation.local.EntityIndexLocalMutationExecutor.Target;
import io.evitadb.index.mutation.local.EntitySchemaAttributeAndCompoundSchemaProvider;
import io.evitadb.index.mutation.local.ReferenceIndexConsumer;
import io.evitadb.index.mutation.local.ReferenceIndexMutator;
import io.evitadb.index.mutation.local.dataAccess.ExistingAttributeValueSupplier;

import javax.annotation.Nonnull;

/**
 * Shared entity-side fan-out used by all three concrete attribute-mutation handlers
 * (`Upsert`, `Remove`, `ApplyDelta`). The three handlers differ only in the inner branch of
 * `updateAttribute` — every step before and after the inner call (pre-mutation capture, global
 * update, unique fan-out across reduced indexes, deferred facet re-evaluation) is identical, so
 * it lives here exactly once. Keeping this orchestration in a single place preserves byte-for-byte
 * equivalence with the legacy `applyAttributeMutation` and lets the handlers be thin shells whose
 * only responsibility is naming the concrete mutation class.
 */
final class AttributeMutationFanOut {

	private AttributeMutationFanOut() {
		// no instances
	}

	/**
	 * Applies the supplied attribute mutation to the global index and fans out to all reduced
	 * indexes via `forEachUniqueReferenceIndex` — see `EntityIndexLocalMutationExecutor`'s
	 * legacy `applyAttributeMutation` for the invariants this method preserves.
	 */
	static void apply(
		@Nonnull AttributeMutation mutation,
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull GlobalEntityIndex globalIndex
	) {
		final ExistingAttributeValueSupplier entityAttributeValueSupplier =
			executor.getStoragePartExistingDataFactory().getNormalizedEntityAttributeValueSupplier();
		final QuadriConsumer<Boolean, EntityIndex, EntityIndex, ReferenceSchemaContract> applicator =
			(updateGlobalIndex, indexForRemoval, indexForUpsert, theReferenceSchema) -> executor.updateAttribute(
				theReferenceSchema,
				mutation,
				new EntitySchemaAttributeAndCompoundSchemaProvider(executor.getEntitySchema()),
				entityAttributeValueSupplier,
				indexForRemoval,
				indexForUpsert,
				updateGlobalIndex,
				true
			);
		final CatalogExpressionTriggerRegistry triggerRegistry = executor.getCatalogExpressionTriggerRegistry();
		// capture pre-mutation value and defer histogram re-evaluation for entity attribute changes —
		// capture must happen before mutation; re-evaluation is deferred so placement is irrelevant
		if (triggerRegistry != null) {
			final String mutatedAttrName = mutation.getAttributeKey().attributeName();
			// capture pre-mutation value only when a cross-entity trigger depends on this specific
			// attribute — avoids map allocation and supplier call for attributes that no trigger references
			if (triggerRegistry.hasEntityAttributeTrigger(executor.getEntityType(), mutatedAttrName)) {
				executor.captureOldEntityAttributeValue(mutation.getAttributeKey(), entityAttributeValueSupplier);
			}
			final int entityPK = executor.getPrimaryKeyToIndex(IndexType.ENTITY_INDEX, Target.EXISTING);
			executor.deferExpressionReEvaluation(
				() -> ReferenceIndexMutator.reEvaluateHistogramExpressionsInAllIndexes(
					globalIndex, executor, entityPK,
					executor.getStoragePartExistingDataFactory(),
					trigger -> trigger.getLocalEntityAttributes().contains(mutatedAttrName)
				)
			);
		}
		//noinspection DataFlowIssue
		applicator.accept(true, globalIndex, globalIndex, null);
		// Entity-level attribute mutations fan out to every reference reduced index. When multiple
		// references on the same entity resolve to the same shared `ReducedGroupEntityIndex` (shared
		// group + representative attribute values), the entity-level bookkeeping — indexed once per
		// (entity, RGEI) pair by `ReferenceIndexMutator#indexAllEntityLevelAttributes` — would
		// otherwise be decremented N times by N sibling references and underflow the
		// `AttributeCardinalityIndex` counter. `forEachUniqueReferenceIndex` folds the N sibling-
		// reference invocations into one per unique reduced index, matching the one-shot insert/remove
		// gating performed by `ReducedGroupEntityIndex#insertPrimaryKeyIfMissing(int, int)`.
		final ReferenceIndexConsumer attrConsumer =
			(theReferenceSchema, indexForRemoval, indexForUpsert) -> applicator.accept(
				false, indexForRemoval, indexForUpsert, theReferenceSchema
			);
		executor.fanOutUniquePerIndex(
			ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING,
			attrConsumer, Droppable::exists, true,
			ReferenceIndexMutator.IterationPath.BOTH
		);
		// defer re-evaluation to after storage write so expression reads updated attribute values
		if (executor.hasFacetExpressionTriggers()) {
			final String mutatedAttrName = mutation.getAttributeKey().attributeName();
			final int entityPK = executor.getPrimaryKeyToIndex(IndexType.ENTITY_INDEX, Target.EXISTING);
			executor.deferExpressionReEvaluation(
				() -> ReferenceIndexMutator.reEvaluateFacetExpressionsInAllIndexes(
					globalIndex, executor, entityPK,
					trigger -> trigger.getLocalEntityAttributes().contains(mutatedAttrName)
				)
			);
		}
	}

}
