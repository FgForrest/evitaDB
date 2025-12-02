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

package io.evitadb.core.transaction.conflict;

import javax.annotation.Nonnull;

/**
 * Strategy for resolving conflicts produced by commutative mutations.
 *
 * Implementations keep an internal accumulator that represents the effect of all delta mutations
 * seen so far for a single logical conflict key. The crucial property is that the accumulation
 * operation is commutative (and ideally associative) so the final result does not depend on the
 * order in which concurrent deltas are processed.
 *
 * This resolver is created and used by the transaction conflict detection to aggregate deltas from
 * previously committed transactions and the current one. Implementations may lazily initialize the
 * accumulator from the current persisted state (for example, the stored attribute value) on the
 * first access.
 *
 * @param <T> type of the accumulated value and delta. Implementations should prefer immutable types
 *            for safety.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public interface CommutativeConflictResolver<T> {

	/**
	 * Returns the currently accumulated value.
	 *
	 * Implementations may compute the value lazily on the first call (e.g. by combining the persisted
	 * base value with the accumulated delta). Subsequent calls should return the same value until
	 * {@link #accumulate(Object)} is invoked again.
	 *
	 * @return non-null accumulated value reflecting all deltas processed so far
	 */
    @Nonnull
    T accumulatedValue();

	/**
	 * Incorporates the passed delta into the internal accumulator.
	 *
	 * The accumulation must be commutative with respect to other deltas of the same type so that the
	 * final result is independent of processing order. Implementations should avoid unnecessary
	 * allocations in performance-critical paths.
	 *
	 * @param deltaValue non-null delta to be accumulated
	 */
    void accumulate(@Nonnull T deltaValue);

}
