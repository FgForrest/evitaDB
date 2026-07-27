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

import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceAttributeMutation;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.mutation.local.EntityIndexLocalMutationExecutor;

import javax.annotation.Nonnull;

/**
 * Handler for `ReferenceAttributeMutation`. Triggers attribute reindexing on the entity-component
 * (type + reduced) and group-component (type + reduced) indexes via `executor.updateReferences`
 * and the per-reference fan-out, then defers histogram re-evaluation for any reference-attribute
 * trigger that depends on the mutated attribute name.
 */
public final class ReferenceAttributeMutationHandler implements LocalMutationHandler<ReferenceAttributeMutation> {

	public static final ReferenceAttributeMutationHandler INSTANCE = new ReferenceAttributeMutationHandler();

	private ReferenceAttributeMutationHandler() {
		// singleton
	}

	@Nonnull
	@Override
	public Class<ReferenceAttributeMutation> handledType() {
		return ReferenceAttributeMutation.class;
	}

	@Override
	public void apply(
		@Nonnull ReferenceAttributeMutation mutation,
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull GlobalEntityIndex globalIndex
	) {
		ReferenceMutationFanOut.apply(mutation, executor, globalIndex);
	}

}
