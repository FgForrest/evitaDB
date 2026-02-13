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
import java.util.Arrays;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Exception thrown when a query contains a `referenceContent` requirement outside of an
 * {@link EntityFetch} or `entityGroupFetch` container, violating the structural rules of
 * evitaDB's query language.
 *
 * In evitaDB queries, reference data can only be fetched within the context of entity fetching.
 * The `referenceContent` constraint must be nested inside an `entityFetch` or `entityGroupFetch`
 * constraint to establish the entity scope for reference loading.
 *
 * **Valid query structure:**
 * ```
 * query(
 * entityFetch(
 * referenceContent('brand', 'category')
 * )
 * )
 * ```
 *
 * **Invalid query structure (triggers this exception):**
 * ```
 * query(
 * referenceContent('brand', 'category')
 * )
 * ```
 *
 * This validation ensures that reference fetching is always associated with the correct entity
 * context and prevents ambiguous or invalid query structures. The exception message includes the
 * full constraint chain to help identify where the misplacement occurred.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class ReferenceContentMisplacedException extends EvitaInvalidUsageException {

	@Serial private static final long serialVersionUID = -8568958465353763831L;

	/**
	 * Constructs a new exception indicating that `referenceContent` was used outside of an
	 * entity fetch container.
	 *
	 * @param constraintChain the stream of require constraints leading to the misplaced
	 *                        `referenceContent`, used to construct a descriptive error message showing the
	 *                        constraint hierarchy
	 */
	public ReferenceContentMisplacedException(@Nonnull Stream<RequireConstraint> constraintChain) {
		super(
			"The `referenceContent` needs to be wrapped inside `entityFetch` or `entityGroupFetch` container: `" +
				constraintChain
					.map(
						it -> "`" + it.getName() + "(" +
							Arrays.stream(it.getArguments())
								.map(String::valueOf)
								.collect(Collectors.joining(", ")) +
							")`")
					.collect(Collectors.joining(" → "))
		);
	}

}
