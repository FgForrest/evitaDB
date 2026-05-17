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

import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.mutation.local.EntityIndexLocalMutationExecutor;

import javax.annotation.Nonnull;

/**
 * Handler for `UpsertAttributeMutation`. The entity-side orchestration (pre-mutation capture,
 * global update, unique fan-out across reduced indexes, deferred facet re-evaluation) is shared
 * with `Remove`/`ApplyDelta` and lives in `AttributeMutationFanOut`. Differentiation between the
 * three concrete attribute mutations happens inside `EntityIndexLocalMutationExecutor#updateAttribute`,
 * which switches on the mutation type to call the matching `AttributeIndexMutator` operation.
 */
public final class UpsertAttributeMutationHandler implements LocalMutationHandler<UpsertAttributeMutation> {

	/**
	 * Class-init singleton — zero per-call allocation.
	 */
	public static final UpsertAttributeMutationHandler INSTANCE = new UpsertAttributeMutationHandler();

	private UpsertAttributeMutationHandler() {
		// singleton — instantiated once at class-init from the public field above
	}

	@Nonnull
	@Override
	public Class<UpsertAttributeMutation> handledType() {
		return UpsertAttributeMutation.class;
	}

	@Override
	public void apply(
		@Nonnull UpsertAttributeMutation mutation,
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull GlobalEntityIndex globalIndex
	) {
		AttributeMutationFanOut.apply(mutation, executor, globalIndex);
	}

}
