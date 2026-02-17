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

import io.evitadb.api.query.RequireConstraint;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Marker interface for all require constraints that specify *what data* to load for each entity body. Implementations
 * control which portions of an entity are materialised from storage and included in the query response.
 *
 * Every `EntityContentRequire` represents one data dimension of an entity. Concrete examples:
 * - {@link AttributeContent} — named (or all) entity/reference attributes
 * - {@link AssociatedDataContent} — complex associated-data blobs
 * - {@link PriceContent} — price records with optional price-list selection
 * - {@link HierarchyContent} — hierarchical parent chain for hierarchical entities
 * - {@link ReferenceContent} — references to other entities, optionally with their own nested fetch
 * - {@link DataInLocales} — locales for which localized values should be materialised
 *
 * Beyond the marker role, the interface provides a **merging protocol** that lets the query planner combine
 * or deduplicate requirements collected from multiple sources (the explicit `require` clause, implicit ordering
 * translators, implicit filtering translators, etc.):
 *
 * - `isCombinableWith(T)` — returns `true` when two instances of the same implementation type carry overlapping
 *   or complementary specifications and can be collapsed into a single instance
 * - `combineWith(T)` — produces a new instance that is the logical union of both requirements; must not mutate
 *   either operand (immutability contract)
 * - `isFullyContainedWithin(T)` — returns `true` when the receiver's specification is a strict subset of
 *   `anotherRequirement`, meaning the receiver is redundant and can be discarded
 *
 * The static factory method `combineRequirements(T, T)` provides null-safe combination: it returns the non-null
 * operand when only one is present, or delegates to `combineWith` when both are non-null.
 *
 * Requirements are accumulated by {@link FetchRequirementCollector} / {@link DefaultPrefetchRequirementCollector}
 * during query planning, and the {@link EntityContentRequireCombiningCollector} allows the same merging logic to be
 * applied to Java streams of requirements.
 *
 * All implementations must be immutable and thread-safe.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface EntityContentRequire extends RequireConstraint {
	EntityContentRequire[] EMPTY_ARRAY = new EntityContentRequire[0];

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
	static <T extends EntityContentRequire> T combineRequirements(@Nullable T a, @Nullable T b) {
		if (a == null) {
			return b;
		} else if (b == null) {
			return a;
		} else {
			return a.combineWith(b);
		}
	}

	/**
	 * Determines whether this requirement can be combined with another requirement of the same type and logically
	 * combinable.
	 *
	 * @param anotherRequirement another requirement to be combined with
	 * @param <T> type of the requirement to be combined with
	 * @return true if the requirements can be combined, false otherwise
	 */
	<T extends EntityContentRequire> boolean isCombinableWith(@Nonnull T anotherRequirement);

	/**
	 * Combines two requirements of the same type (that needs to be compatible with "this" type) into one by merging
	 * the arguments of both of them.
	 *
	 * @param anotherRequirement another requirement to be combined with
	 * @param <T> type of the requirement to be combined with
	 * @return a new combined requirement
	 */
	@Nonnull
	<T extends EntityContentRequire> T combineWith(@Nonnull T anotherRequirement);

	/**
	 * Determines if the current requirement is fully contained within the provided requirement. Contained means that
	 * this requirement is not necessary because it will be fully satisfied by the provided `anotherRequirement`.
	 *
	 * @param anotherRequirement another requirement to be checked for containment
	 * @param <T> the type of the requirement which extends EntityContentRequire
	 * @return true if the current requirement is fully contained within the provided requirement, false otherwise
	 */
	<T extends EntityContentRequire> boolean isFullyContainedWithin(@Nonnull T anotherRequirement);

}
