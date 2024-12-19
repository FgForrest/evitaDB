/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

package io.evitadb.api.requestResponse.schema.mutation;


import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Function;

/**
 * Internal interface providing support for conditional mutation creation based on the difference between two versions.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public interface CreateMutation {

	/**
	 * Compares values retrieved from the created version and the existing version using the provided property retriever.
	 * If the values are different, it creates a mutation using the mutation creator function. Works for both simple objects
	 * and arrays.
	 *
	 * @param mutationType the type of the mutation to create (to avoid ambiguity)
	 * @param createdVersion the newly created version from which properties are retrieved
	 * @param existingVersion the current version from which properties are retrieved
	 * @param propertyRetriever a function to retrieve properties for comparison from the versions
	 * @param mutationCreator a function to create a mutation if there is a difference between the properties
	 * @return a mutation if the properties are different, null if they are the same
	 */
	@Nullable
	default <T, S, V> V makeMutationIfDifferent(
		@Nonnull Class<S> mutationType,
		@Nonnull S createdVersion,
		@Nonnull S existingVersion,
		@Nonnull Function<S, T> propertyRetriever,
		@Nonnull Function<T, V> mutationCreator
	) {
		final T newValue = propertyRetriever.apply(createdVersion);
		final T existingValue = propertyRetriever.apply(existingVersion);
		if (newValue instanceof Object[] && existingValue instanceof Object[]) {
			return Arrays.equals((Object[]) newValue, (Object[]) existingValue) ?  null : mutationCreator.apply(newValue);
		} else {
			return Objects.equals(existingValue, newValue) ? null : mutationCreator.apply(newValue);
		}
	}

}
