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

import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.mutation.local.EntityIndexLocalMutationExecutor;

import javax.annotation.Nonnull;

/**
 * Self-describing apply operation for a single concrete `LocalMutation` subtype.
 *
 * Each leaf mutation type (`UpsertAttributeMutation`, `RemovePriceMutation`,
 * `InsertReferenceMutation`, ...) is paired with exactly one handler singleton registered in
 * `LocalMutationHandlerRegistry`. The handler states its fan-out needs (per-reference vs
 * unique-per-index, which `IterationPath`) at the call site, so adding a new mutation type touches
 * exactly one new file rather than scattered edits across the executor.
 *
 * Handlers are stateless class-init singletons (`public static final INSTANCE = ...`). Zero
 * per-call allocation; the registry lookup is a single hashmap get keyed by `mutation.getClass()`.
 *
 * @param <M> the concrete mutation type this handler accepts
 */
public interface LocalMutationHandler<M extends LocalMutation<?, ?>> {

	/**
	 * Returns the concrete mutation class this handler dispatches for. Used as the registry key.
	 * The registry requires exact-class matches — abstract supertypes (e.g. `AttributeMutation`,
	 * `ReferenceMutation`) must not be returned here.
	 *
	 * @return the concrete `LocalMutation` subclass handled by this singleton
	 */
	@Nonnull
	Class<M> handledType();

	/**
	 * Applies the mutation to all relevant indexes via the supplied executor. The handler owns the
	 * orchestration of global-index updates and any fan-out to reduced indexes (via
	 * `executor.fanOutPerReference` / `executor.fanOutUniquePerIndex`).
	 *
	 * @param mutation    the concrete mutation to apply
	 * @param executor    the active executor providing entity state, index access, deferral hooks,
	 *                    and fan-out wrappers
	 * @param globalIndex the global entity index for the active scope (pre-resolved by the
	 *                    executor's dispatcher)
	 */
	void apply(
		@Nonnull M mutation,
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull GlobalEntityIndex globalIndex
	);

}
