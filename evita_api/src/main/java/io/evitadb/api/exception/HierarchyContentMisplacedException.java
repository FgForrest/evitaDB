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
 * Exception thrown when `hierarchyContent` requirement is used outside of its required container constraints.
 *
 * The `hierarchyContent` requirement fetches parent entity references for hierarchical entities. However, this
 * requirement must be properly nested within an entity fetching container to define which entities should have
 * their hierarchy content loaded.
 *
 * **Required containers:**
 *
 * - **{@link EntityFetch}**: When fetching hierarchy content for main query entities
 * - **`entityGroupFetch`**: When fetching hierarchy content for facet group entities
 *
 * **Why this constraint exists:**
 *
 * The `hierarchyContent` requirement specifies how to traverse and load parent entities in the hierarchy tree.
 * Without a containing `entityFetch` or `entityGroupFetch`, evitaDB cannot determine which entities should have
 * their hierarchy loaded, making the requirement ambiguous and unexecutable.
 *
 * **Invalid usage example:**
 *
 * ```
 * query(
 *   collection('Category'),
 *   require(
 *     hierarchyContent()  // ERROR: not inside entityFetch
 *   )
 * )
 * ```
 *
 * **Valid usage example:**
 *
 * ```
 * query(
 *   collection('Category'),
 *   require(
 *     entityFetch(
 *       hierarchyContent()  // OK: properly nested
 *     )
 *   )
 * )
 * ```
 *
 * **Resolution**: Wrap the `hierarchyContent()` requirement inside an `entityFetch()` or `entityGroupFetch()`
 * container constraint.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class HierarchyContentMisplacedException extends EvitaInvalidUsageException {

	@Serial private static final long serialVersionUID = -4235850574041141230L;

	/**
	 * Creates exception showing the constraint chain where the misplacement occurred.
	 *
	 * @param constraintChain the sequence of requirement constraints leading to the misplaced hierarchyContent
	 */
	public HierarchyContentMisplacedException(@Nonnull Stream<RequireConstraint> constraintChain) {
		super(
			"The `hierarchyContent` needs to be wrapped inside `entityFetch` or `entityGroupFetch` container: `" +
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
