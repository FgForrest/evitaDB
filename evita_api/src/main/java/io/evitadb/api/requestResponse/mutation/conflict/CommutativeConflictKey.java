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

package io.evitadb.api.requestResponse.mutation.conflict;

import io.evitadb.api.exception.ConflictingCatalogCommutativeMutationException;

import javax.annotation.Nonnull;

/**
 * Cumulative conflict key is used to identify mutations that can be applied in any order without causing conflicts.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public interface CommutativeConflictKey<T> extends ConflictKey {

    /**
     * Retrieves the delta value associated with this commutative conflict key.
     * The delta value represents a single mutable unit that can be applied to compute an updated state.
     *
     * @return the non-null delta value associated with this commutative conflict key
     */
    @Nonnull
    T deltaValue();

    /**
     * Aggregates multiple partial values into a single accumulated value.
     *
     * @param one the first value to aggregate; must not be null
     * @param two the second value to aggregate; must not be null
     * @return the accumulated value
     */
    @Nonnull
    T aggregate(
        @Nonnull T one,
        @Nonnull T two
    );

    /**
     * Indicates whether the accumulated value is constrained to a specific range.
     *
     * @return true if the accumulated value is constrained to a specific range, false otherwise
     */
    boolean isConstrainedToRange();

    /**
     * Ensures that the provided accumulated value is within the allowed range for this commutative conflict key.
     * If the value falls outside the permissible range, a {@link ConflictingCatalogCommutativeMutationException} is thrown.
     *
     * @param accumulatedValue the accumulated value to validate; must not be null
     * @throws ConflictingCatalogCommutativeMutationException if the accumulated value is not within the allowed range
     */
    void assertInAllowedRange(
        @Nonnull String catalogName,
        long catalogVersion,
        @Nonnull T accumulatedValue
    )
        throws ConflictingCatalogCommutativeMutationException;
}
