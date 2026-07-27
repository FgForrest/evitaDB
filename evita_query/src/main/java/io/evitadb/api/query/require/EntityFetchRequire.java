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

package io.evitadb.api.query.require;

import io.evitadb.api.query.EntityConstraint;
import io.evitadb.api.query.RequireConstraint;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Marker interface for require constraints that *trigger loading of an entity body* and define which content should
 * be included in that body. Implementations represent the top-level "fetch" containers, rather than individual
 * content specifiers.
 *
 * There are two concrete implementations in the standard require API:
 * - {@link EntityFetch} — defines the body richness for *primary queried entities* (and referenced entities when
 *   used inside {@link ReferenceContent})
 * - {@link EntityGroupFetch} — defines the body richness for *reference group entities* inside {@link ReferenceContent}
 *   / facet contexts
 *
 * An `EntityFetchRequire` is also a {@link SeparateEntityContentRequireContainer}, which signals to the query
 * planner that the {@link EntityContentRequire} children nested inside it define an *isolated fetch scope*. This
 * prevents the children from being merged with `EntityContentRequire` constraints that belong to a different
 * entity context.
 *
 * The interface defines:
 * - `getRequirements()` — returns the flat list of {@link EntityContentRequire} children that together specify
 *   which data dimensions (attributes, prices, references, …) should be loaded
 * - `combineWith(T)` — merges two compatible fetch requirements into one by taking the union of their
 *   nested content requirements; null-safe via the static `combineRequirements(T, T)` factory
 * - `isFullyContainedWithin(T)` — allows the query planner to determine whether one fetch requirement is
 *   already covered by another, enabling deduplication
 *
 * All implementations must be immutable.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public interface EntityFetchRequire extends EntityConstraint<RequireConstraint>, SeparateEntityContentRequireContainer {

	/**
	 * Combines two EntityFetchRequire requirements into one combined requirement.
	 * If one of the requirements is null, the non-null requirement is returned.
	 * If both are null, null is returned. If both are non-null, they are combined.
	 *
	 * @param <T> the type of the requirement which extends EntityFetchRequire
	 * @param a the first EntityFetchRequire requirement, can be null
	 * @param b the second EntityFetchRequire requirement, can be null
	 * @return the combined EntityFetchRequire requirement, or null if both are null
	 */
	@Nullable
	static <T extends EntityFetchRequire> T combineRequirements(@Nullable T a, @Nullable T b) {
		if (a == null) {
			return b;
		} else if (b == null) {
			return a;
		} else {
			return a.combineWith(b);
		}
	}

	/**
	 * Returns all requirements that need to be satisfied for this requirement to be fulfilled.
	 *
	 * @return array of requirements
	 */
	@Nonnull
	EntityContentRequire[] getRequirements();

	/**
	 * Determines if the current requirement is fully contained within the provided requirement. Contained means that
	 * this requirement is not necessary because it will be fully satisfied by the provided `anotherRequirement`.
	 *
	 * @param anotherRequirement another requirement to be checked for containment
	 * @param <T> the type of the requirement which extends EntityFetchRequire
	 * @return true if the current requirement is fully contained within the provided requirement, false otherwise
	 */
	<T extends EntityFetchRequire> boolean isFullyContainedWithin(@Nonnull T anotherRequirement);

	/**
	 * Combines two requirements of the same type (that needs to be compatible with "this" type) into one by merging
	 * the arguments of both of them.
	 *
	 * @param anotherRequirement another requirement to be combined with
	 * @param <T> type of the requirement to be combined with
	 * @return a new combined requirement
	 */
	@Nonnull
	<T extends EntityFetchRequire> T combineWith(@Nullable T anotherRequirement);

}
