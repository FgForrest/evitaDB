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

import io.evitadb.api.requestResponse.data.mutation.reference.RemoveReferenceMutation;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.mutation.local.EntityIndexLocalMutationExecutor;

import javax.annotation.Nonnull;

/**
 * Handler for `RemoveReferenceMutation`. Routes through `ReferenceMutationFanOut`; the synchronous
 * histogram cleanup, `referenceRemovalGlobal` call, and per-component cleanup all live inside
 * `executor.updateReferences`. Unlike other mutations, this one does not defer facet expression
 * re-evaluation — the facet entry is removed synchronously.
 */
public final class RemoveReferenceMutationHandler implements LocalMutationHandler<RemoveReferenceMutation> {

	public static final RemoveReferenceMutationHandler INSTANCE = new RemoveReferenceMutationHandler();

	private RemoveReferenceMutationHandler() {
		// singleton
	}

	@Nonnull
	@Override
	public Class<RemoveReferenceMutation> handledType() {
		return RemoveReferenceMutation.class;
	}

	@Override
	public void apply(
		@Nonnull RemoveReferenceMutation mutation,
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull GlobalEntityIndex globalIndex
	) {
		ReferenceMutationFanOut.apply(mutation, executor, globalIndex);
	}

}
