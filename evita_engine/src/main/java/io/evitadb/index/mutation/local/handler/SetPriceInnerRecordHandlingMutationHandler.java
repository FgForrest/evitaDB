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

import io.evitadb.api.requestResponse.data.mutation.price.SetPriceInnerRecordHandlingMutation;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.mutation.local.EntityIndexLocalMutationExecutor;

import javax.annotation.Nonnull;

/**
 * Handler for `SetPriceInnerRecordHandlingMutation`. Distinct from `PriceMutation` — it does not
 * extend `PriceMutation`, so the legacy dispatcher had a dedicated `else if` branch before the
 * `PriceMutation` test. Updating the handling strategy reindexes every stored price (remove with
 * the old strategy on reduced indexes, then on the super index; then re-insert with the new
 * strategy in the same order). All fan-outs are unique-per-index because the price leaves are
 * set-semantic.
 */
public final class SetPriceInnerRecordHandlingMutationHandler
	implements LocalMutationHandler<SetPriceInnerRecordHandlingMutation> {

	public static final SetPriceInnerRecordHandlingMutationHandler INSTANCE =
		new SetPriceInnerRecordHandlingMutationHandler();

	private SetPriceInnerRecordHandlingMutationHandler() {
		// singleton
	}

	@Nonnull
	@Override
	public Class<SetPriceInnerRecordHandlingMutation> handledType() {
		return SetPriceInnerRecordHandlingMutation.class;
	}

	@Override
	public void apply(
		@Nonnull SetPriceInnerRecordHandlingMutation mutation,
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull GlobalEntityIndex globalIndex
	) {
		executor.updatePriceHandlingForEntity(mutation, globalIndex);
	}

}
