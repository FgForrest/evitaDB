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

import io.evitadb.api.requestResponse.data.mutation.price.PriceMutation;
import io.evitadb.api.requestResponse.data.mutation.price.RemovePriceMutation;
import io.evitadb.api.requestResponse.data.mutation.price.UpsertPriceMutation;
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.mutation.local.EntityIndexLocalMutationExecutor;
import io.evitadb.index.mutation.local.ReferenceIndexConsumer;
import io.evitadb.index.mutation.local.ReferenceIndexMutator;
import io.evitadb.utils.Functions;

import javax.annotation.Nonnull;

/**
 * Shared orchestration for the two indexed price-mutation handlers (`Upsert`, `Remove`). Carries
 * two ordering invariants:
 *
 * - For removals (and upserts of non-indexed prices), reduced indexes update first because they
 *   consult the super (global) index.
 * - For upserts of indexed prices, the global/super index updates first because reduced indexes
 *   rely on information in the super index.
 *
 * Uses `fanOutUniquePerIndex` because price leaves on `PriceListAndCurrencyPriceRefIndex` are
 * set-semantic — a duplicate add is a no-op, the first remove drains the bucket, and a
 * per-reference fan-out across N siblings sharing a single `ReducedGroupEntityIndex` would either
 * no-op or throw on the second invocation.
 */
final class PriceMutationFanOut {

	private PriceMutationFanOut() {
		// no instances
	}

	static void apply(
		@Nonnull PriceMutation mutation,
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull GlobalEntityIndex globalIndex
	) {
		if (mutation instanceof RemovePriceMutation
			|| (mutation instanceof UpsertPriceMutation upsert && !upsert.isIndexed())) {
			// removal must first occur on the reduced indexes, because they consult the super index
			final ReferenceIndexConsumer priceRemovalConsumer =
				(referenceSchema, indexForRemoval, indexForUpsert) -> executor.updatePriceIndex(
					referenceSchema, mutation, indexForRemoval, indexForUpsert
				);
			executor.fanOutUniquePerIndex(
				ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING,
				priceRemovalConsumer, Functions.alwaysTrue(), true,
				ReferenceIndexMutator.IterationPath.BOTH
			);
			executor.updatePriceIndex(null, mutation, globalIndex, globalIndex);
		} else {
			// upsert must first occur on super index, because reduced indexes rely on information in super index
			executor.updatePriceIndex(null, mutation, globalIndex, globalIndex);
			final ReferenceIndexConsumer priceUpsertConsumer =
				(referenceSchema, indexForRemoval, indexForUpsert) -> executor.updatePriceIndex(
					referenceSchema, mutation, indexForRemoval, indexForUpsert
				);
			executor.fanOutUniquePerIndex(
				ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING,
				priceUpsertConsumer, Functions.alwaysTrue(), true,
				ReferenceIndexMutator.IterationPath.BOTH
			);
		}
	}

}
