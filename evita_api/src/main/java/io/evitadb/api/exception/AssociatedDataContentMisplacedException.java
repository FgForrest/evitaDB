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
 * Thrown when an `associatedDataContent` query constraint is placed outside the required container
 * constraints in a query's require section.
 *
 * The `associatedDataContent` constraint must be nested inside either `entityFetch` or `entityGroupFetch`
 * to indicate which entities (main entities or group entities in references) should have their associated
 * data fetched. Placing it outside these containers makes it unclear which entities the associated data
 * request applies to.
 *
 * **When this is thrown:**
 * - During query processing when validating the require constraint hierarchy
 * - When `associatedDataContent` appears at the root level or in other non-entity-fetch containers
 * - Thrown by `AssociatedDataContentTranslator` during query translation
 *
 * **Correct usage:**
 * ```
 * query(
 *   require(
 *     entityFetch(
 *       associatedDataContent('description', 'specifications')
 *     )
 *   )
 * )
 * ```
 *
 * **Incorrect usage:**
 * ```
 * query(
 *   require(
 *     associatedDataContent('description')  // Error: not inside entityFetch
 *   )
 * )
 * ```
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class AssociatedDataContentMisplacedException extends EvitaInvalidUsageException {

	@Serial private static final long serialVersionUID = 3364888804230783679L;

	/**
	 * Creates exception showing the actual constraint hierarchy that caused the violation.
	 */
	public AssociatedDataContentMisplacedException(@Nonnull Stream<RequireConstraint> constraintChain) {
		super(
			"The `associatedDataContent` needs to be wrapped inside `entityFetch` or `entityGroupFetch` container: `" +
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
