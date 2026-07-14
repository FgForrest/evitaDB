/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.api.requestResponse.mutation;

import io.evitadb.api.requestResponse.cdc.ChangeCaptureBody;
import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictGenerationContext;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictKey;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.util.stream.Stream;

/**
 * This interface denotes all mutation operations that can be cast on Evita data objects.
 *
 * Mutations must be designed as immutable and thread safe.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Immutable
@ThreadSafe
public sealed interface Mutation
	extends MutationContract, ChangeCaptureBody
	permits EngineMutation, CatalogBoundMutation {

	/**
	 * Returns operation classification.
	 */
	@Nonnull
	Operation operation();

	/**
	 * Collects all conflict keys that this mutation may produce when applied to the data.
	 *
	 * The effective conflict resolution is carried by the {@code context}: implementations decide which
	 * keys to emit by asking its {@code shouldEmit*} predicates and {@link ConflictGenerationContext#coarsePolicy()},
	 * never by inspecting a raw policy set.
	 *
	 * @param context context that both scopes the generation (catalog / entity) and decides, through its
	 *                {@code shouldEmit*} predicates, which conflict keys must be produced
	 * @return stream providing conflict keys
	 */
	@Nonnull
	Stream<ConflictKey> collectConflictKeys(
		@Nonnull ConflictGenerationContext context
	);

}
