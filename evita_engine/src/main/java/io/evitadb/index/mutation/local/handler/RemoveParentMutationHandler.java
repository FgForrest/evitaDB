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

import io.evitadb.api.requestResponse.data.mutation.parent.RemoveParentMutation;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.mutation.local.EntityIndexLocalMutationExecutor;

import javax.annotation.Nonnull;

/**
 * Handler for `RemoveParentMutation`. Mirrors `SetParentMutationHandler` — both share the same
 * orchestration in `ParentMutationFanOut`, dispatching to `updateHierarchyPlacement` which in turn
 * resolves the concrete parent operation.
 */
public final class RemoveParentMutationHandler implements LocalMutationHandler<RemoveParentMutation> {

	public static final RemoveParentMutationHandler INSTANCE = new RemoveParentMutationHandler();

	private RemoveParentMutationHandler() {
		// singleton
	}

	@Nonnull
	@Override
	public Class<RemoveParentMutation> handledType() {
		return RemoveParentMutation.class;
	}

	@Override
	public void apply(
		@Nonnull RemoveParentMutation mutation,
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull GlobalEntityIndex globalIndex
	) {
		ParentMutationFanOut.apply(mutation, executor, globalIndex);
	}

}
