/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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


import io.evitadb.api.EvitaContract;
import io.evitadb.api.exception.InvalidMutationException;
import io.evitadb.api.requestResponse.cdc.ChangeCaptureContent;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCapture;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictKey;
import io.evitadb.api.requestResponse.progress.Progress;

import javax.annotation.Nonnull;
import java.util.stream.Stream;

/**
 * This mutation represents a top-level mutation executed on the entire evitaDB instance and not just on a
 * single catalog schema instance.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public non-sealed interface EngineMutation<T> extends Mutation {

	/**
	 * Returns the type of the result of the {@link Progress} future.
	 * @return the type of the result of the {@link Progress} future
	 */
	@Nonnull
	Class<T> getProgressResultType();

	/**
	 * Returns a {@link ConflictKey} associated with this mutation that identifies potential conflicts
	 * in the evitaDB system. The conflict key is used to ensure proper handling of concurrent mutations.
	 *
	 * @return the conflict key representing the unique identifier for potential conflicts
	 */
	@Nonnull
	Stream<ConflictKey> getConflictKeys();

	/**
	 * Transforms mutation to the stream of change system capture item matching the input predicate.
	 *
	 * @param predicate the predicate to be used for filtering the mutation items if any are present
	 * @param content   the requested content of the capture
	 * @return the change system capture item
	 */
	@Nonnull
	default Stream<ChangeSystemCapture> toChangeSystemCapture(
		@Nonnull MutationPredicate predicate,
		@Nonnull ChangeCaptureContent content
	) {
		final MutationPredicateContext context = predicate.getContext();
		prepareContext(context);
		if (predicate.test(this)) {
			return Stream.of(
				ChangeSystemCapture.systemCapture(
					predicate.getContext(),
					operation(),
					content == ChangeCaptureContent.BODY ? this : null
				)
			);
		} else {
			return Stream.empty();
		}
	}

	/**
	 * Prepares the context for mutation processing by advancing its internal state,
	 * such as updating the index or other tracking properties, based on the stream direction.
	 *
	 * @param context the {@link MutationPredicateContext} whose state needs to be advanced
	 */
	default void prepareContext(@Nonnull MutationPredicateContext context) {
		context.advance();
	}

	/**
	 * Verifies whether the mutation is applicable to the evita instance.
	 *
	 * @param evita the evita instance to verify applicability against
	 * @throws InvalidMutationException if the mutation is not applicable
	 */
	void verifyApplicability(@Nonnull EvitaContract evita)
		throws InvalidMutationException;

}
