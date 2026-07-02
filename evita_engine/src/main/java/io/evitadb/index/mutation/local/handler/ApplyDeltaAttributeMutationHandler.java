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

import io.evitadb.api.requestResponse.data.mutation.attribute.ApplyDeltaAttributeMutation;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.mutation.local.EntityIndexLocalMutationExecutor;

import javax.annotation.Nonnull;

/**
 * Handler for `ApplyDeltaAttributeMutation`. Delegates to the shared `AttributeMutationFanOut`
 * orchestration. The generic numeric payload (`T extends Number`) is irrelevant to the registry
 * key — only the raw class is consulted at dispatch time.
 */
public final class ApplyDeltaAttributeMutationHandler
	implements LocalMutationHandler<ApplyDeltaAttributeMutation<?>> {

	public static final ApplyDeltaAttributeMutationHandler INSTANCE = new ApplyDeltaAttributeMutationHandler();

	private ApplyDeltaAttributeMutationHandler() {
		// singleton
	}

	@Nonnull
	@Override
	@SuppressWarnings({"unchecked", "rawtypes"})
	public Class<ApplyDeltaAttributeMutation<?>> handledType() {
		// the raw class literal lives at the runtime layer where the generic numeric payload has
		// been erased — only the raw class is consulted as the registry key
		return (Class<ApplyDeltaAttributeMutation<?>>) (Class) ApplyDeltaAttributeMutation.class;
	}

	@Override
	public void apply(
		@Nonnull ApplyDeltaAttributeMutation<?> mutation,
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull GlobalEntityIndex globalIndex
	) {
		AttributeMutationFanOut.apply(mutation, executor, globalIndex);
	}

}
