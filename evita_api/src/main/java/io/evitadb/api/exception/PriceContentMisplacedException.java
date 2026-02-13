/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.api.exception;

import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.exception.EvitaInvalidUsageException;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Exception thrown when a `priceContent` requirement is used outside of a valid entity fetch container.
 *
 * In evitaDB queries, price data can only be fetched when operating within an entity context. The `priceContent`
 * requirement must be nested inside either {@link EntityFetch} or `entityGroupFetch` containers. Attempting to
 * fetch prices at the wrong query level (e.g., at the root level or within non-entity contexts) violates this
 * constraint and triggers this exception.
 *
 * **Valid Usage:**
 * ```
 * query(
 *   require(
 *     entityFetch(
 *       priceContent(PriceContentMode.RESPECTING_FILTER)
 *     )
 *   )
 * )
 * ```
 *
 * **Invalid Usage:**
 * ```
 * query(
 *   require(
 *     priceContent(PriceContentMode.RESPECTING_FILTER)  // INVALID: not inside entityFetch
 *   )
 * )
 * ```
 *
 * **Usage Context:**
 * - {@link io.evitadb.core.query.extraResult.translator.reference.PriceContentTranslator}: validates that
 *   `priceContent` requirements are properly nested during query planning
 * - Thrown during query translation when constraint hierarchy is validated
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class PriceContentMisplacedException extends EvitaInvalidUsageException {

	@Serial private static final long serialVersionUID = -4235850574041141230L;

	/**
	 * Creates a new exception indicating that `priceContent` was used outside of a valid container.
	 *
	 * @param constraintChain the chain of constraints from root to the misplaced `priceContent`, used to
	 *                        provide context in the error message showing where the requirement was found
	 */
	public PriceContentMisplacedException(@Nonnull Stream<RequireConstraint> constraintChain) {
		super(
			"The `priceContent` needs to be wrapped inside `entityFetch` or `entityGroupFetch` container: " +
				constraintChain
					.map(it -> "`" + it.toString() + "`")
					.collect(Collectors.joining(" → "))
		);
	}

}
