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
 * Thrown when an `attributeContent` query constraint is placed outside the required container constraints
 * in a query's require section.
 *
 * The `attributeContent` constraint must be nested inside one of these containers: `entityFetch`,
 * `entityGroupFetch`, or `referenceContent`. This nesting indicates which entities or references should
 * have their attributes fetched. Placing it outside these containers makes it unclear which data the
 * attribute request applies to.
 *
 * **When this is thrown:**
 * - During query processing when validating the require constraint hierarchy
 * - When `attributeContent` appears at the root level or in other non-supported containers
 * - Thrown by `AttributeContentTranslator` during query translation
 *
 * **Correct usage:**
 * ```
 * query(
 *   require(
 *     entityFetch(
 *       attributeContent('name', 'code')
 *     ),
 *     referenceContent('brand',
 *       attributeContent('priority')
 *     )
 *   )
 * )
 * ```
 *
 * **Incorrect usage:**
 * ```
 * query(
 *   require(
 *     attributeContent('name')  // Error: not inside entityFetch/referenceContent
 *   )
 * )
 * ```
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class AttributeContentMisplacedException extends EvitaInvalidUsageException {

	@Serial private static final long serialVersionUID = -2317895116414988617L;

	/**
	 * Creates exception showing the actual constraint hierarchy that caused the violation.
	 */
	public AttributeContentMisplacedException(@Nonnull Stream<RequireConstraint> constraintChain) {
		super(
			"The `attributeContent` needs to be wrapped inside `entityFetch`, `entityGroupFetch` or `referenceContent` " +
				"container: " +
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
