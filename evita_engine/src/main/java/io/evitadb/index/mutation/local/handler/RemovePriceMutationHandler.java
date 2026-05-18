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

import io.evitadb.api.requestResponse.data.mutation.price.RemovePriceMutation;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.mutation.local.EntityIndexLocalMutationExecutor;

import javax.annotation.Nonnull;

/**
 * Handler for `RemovePriceMutation`. Mirrors `UpsertPriceMutationHandler` through the shared
 * `PriceMutationFanOut`; removals always touch reduced indexes before the super index.
 */
public final class RemovePriceMutationHandler implements LocalMutationHandler<RemovePriceMutation> {

	public static final RemovePriceMutationHandler INSTANCE = new RemovePriceMutationHandler();

	private RemovePriceMutationHandler() {
		// singleton
	}

	@Nonnull
	@Override
	public Class<RemovePriceMutation> handledType() {
		return RemovePriceMutation.class;
	}

	@Override
	public void apply(
		@Nonnull RemovePriceMutation mutation,
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull GlobalEntityIndex globalIndex
	) {
		PriceMutationFanOut.apply(mutation, executor, globalIndex);
	}

}
