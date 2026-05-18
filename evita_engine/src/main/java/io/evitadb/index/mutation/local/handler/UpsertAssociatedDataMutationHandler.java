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

import io.evitadb.api.requestResponse.data.mutation.associatedData.UpsertAssociatedDataMutation;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.mutation.local.EntityIndexLocalMutationExecutor;

import javax.annotation.Nonnull;

/**
 * Handler for `UpsertAssociatedDataMutation`. Associated data is not directly indexed; the only
 * indexing side-effect is to re-evaluate facet expressions that reference the mutated associated
 * data key — and even that is deferred so the expression evaluation observes post-write storage
 * state.
 */
public final class UpsertAssociatedDataMutationHandler implements LocalMutationHandler<UpsertAssociatedDataMutation> {

	public static final UpsertAssociatedDataMutationHandler INSTANCE = new UpsertAssociatedDataMutationHandler();

	private UpsertAssociatedDataMutationHandler() {
		// singleton
	}

	@Nonnull
	@Override
	public Class<UpsertAssociatedDataMutation> handledType() {
		return UpsertAssociatedDataMutation.class;
	}

	@Override
	public void apply(
		@Nonnull UpsertAssociatedDataMutation mutation,
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull GlobalEntityIndex globalIndex
	) {
		AssociatedDataMutationFanOut.apply(mutation.getAssociatedDataKey().associatedDataName(), executor, globalIndex);
	}

}
