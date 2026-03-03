/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025-2026
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

package io.evitadb.index.mutation.index;

import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.index.AbstractReducedEntityIndex;

import javax.annotation.Nonnull;

/**
 * A functional callback that applies a single index mutation step to a pair of
 * {@link AbstractReducedEntityIndex} instances in the context of a specific {@link ReferenceSchemaContract}.
 *
 * ## Purpose and role
 *
 * Instances of this interface are passed to the traversal helpers on {@link ReferenceIndexMutator}
 * (`executeWithReferenceIndexes`, `executeWithGroupReferenceIndexes`, `executeWithAllReferenceIndexes`). Those
 * helpers iterate every currently-active reference stored on an entity and call `accept` once for each
 * relevant {@link AbstractReducedEntityIndex} — either a `REFERENCED_ENTITY` index or a `REFERENCED_GROUP_ENTITY`
 * index, depending on which traversal helper is used.
 *
 * ## The two-index contract
 *
 * Every invocation receives two index references, `indexForRemoval` and `indexForUpsert`, which serve
 * distinct roles in the update cycle:
 *
 * - **`indexForRemoval`** — the `AbstractReducedEntityIndex` that holds existing data for the reference. Old
 *   values, facets, prices, and attributes must be removed from this index before the new values are
 *   written.
 * - **`indexForUpsert`** — the `AbstractReducedEntityIndex` that will receive the new or updated data.
 *
 * In the common case (no representative-key change), both parameters point to the **same** index
 * instance; the caller simply passes `indexToUse` twice. The parameters are kept separate to support the
 * rarer scenario where a reference's {@link io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey}
 * changes between the stored and current states (e.g. when a reference with
 * `ZERO_OR_MORE` cardinality shifts its representative entity). In that situation the callee must read
 * stale data from the old `formerReferenceIndex` (`indexForRemoval`) and write fresh data into the new
 * `newReferenceIndex` (`indexForUpsert`), which are genuinely different objects. The `attributeUpdate`
 * helper in {@link ReferenceIndexMutator} demonstrates the canonical pattern where the distinction
 * matters: it routes `Target.EXISTING` primary-key look-ups to `indexForRemoval` and `Target.NEW`
 * look-ups to `indexForUpsert`.
 *
 * ## Ordering guarantees expected by callers
 *
 * {@link EntityIndexLocalMutationExecutor} relies on a strict remove-before-insert order for
 * reduced indexes relative to the global index:
 *
 * - For **removals**: reduced indexes are updated *before* the global index, because reduced price
 *   indexes look up authoritative data in the global super-index.
 * - For **upserts**: the global index is updated *first*, and then the reduced indexes follow.
 *
 * Implementations must not reverse this ordering.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 * @see ReferenceIndexMutator#executeWithReferenceIndexes
 * @see ReferenceIndexMutator#executeWithGroupReferenceIndexes
 * @see ReferenceIndexMutator#executeWithAllReferenceIndexes
 */
@FunctionalInterface
public interface ReferenceIndexConsumer {

	/**
	 * Applies the mutation operation to the given pair of reduced entity indexes for the specified reference.
	 *
	 * Implementations must remove stale data from `indexForRemoval` and write new data to `indexForUpsert`.
	 * When both parameters are the same index instance (the typical case), a single index is updated in place.
	 * When they differ (representative-key migration), old data is cleaned from the former index and new data
	 * is inserted into the replacement index.
	 *
	 * The `referenceSchema` is provided so the implementation can inspect schema-controlled behaviours
	 * (faceting, attribute indexing configuration, etc.) without needing to re-fetch the schema from the executor.
	 *
	 * @param referenceSchema the schema describing the reference being processed; never `null`
	 * @param indexForRemoval the reduced entity index from which obsolete data should be removed; never `null`
	 * @param indexForUpsert  the reduced entity index to which new or updated data should be written; never `null`;
	 *                        may be the same object as `indexForRemoval`
	 */
	void accept(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull AbstractReducedEntityIndex indexForRemoval,
		@Nonnull AbstractReducedEntityIndex indexForUpsert
	);

}
