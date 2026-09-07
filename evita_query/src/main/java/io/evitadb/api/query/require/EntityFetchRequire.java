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
import io.evitadb.exception.EvitaInvalidUsageException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;

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
 * - `combineDuplicateRequirements()` — folds duplicate content requirements of the same kind that the client wrote
 *   side by side into a single requirement each, so that the request is described by at most one requirement per
 *   kind; conflicting siblings are refused with an {@link EvitaInvalidUsageException}
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
	 * @throws EvitaInvalidUsageException when both are non-null and hold two requirements of one kind that
	 *                                    contradict each other - the combination is delegated to
	 *                                    {@link #combineWith(EntityFetchRequire)}, which refuses such a pair
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
	 * Folds duplicate content requirements of the same kind in the passed array into a single requirement each and
	 * returns the reduced array.
	 *
	 * The fold walks the array in encounter order and, for every requirement, looks for an already-kept requirement
	 * of exactly the same class that accepts it via {@link EntityContentRequire#isCombinableWith(EntityContentRequire)}.
	 * When such a sibling is found, the kept requirement is replaced by the result of
	 * {@link EntityContentRequire#combineWith(EntityContentRequire)}; otherwise the requirement is appended. The
	 * position of a combined requirement is therefore the position of its first appearance.
	 *
	 * Every kind of {@link EntityContentRequire} takes part - the kinds that may legitimately occur several times in
	 * one fetch container say so through their own key: a {@link ReferenceContent} is keyed by the references it
	 * names, an {@link AccompanyingPriceContent} by the name of the price it calculates. Requirements with different
	 * keys are not combinable and all of them survive the fold.
	 *
	 * Containment ({@link EntityContentRequire#isFullyContainedWithin(EntityContentRequire)}) is deliberately **not**
	 * consulted. A `referenceContent("category")` is contained within a `referenceContentAll()`, yet the two are
	 * resolved through different lookups - the reference-name specific requirement wins over the default one - and
	 * collapsing the specific one into the default would silently widen the body fetched for `category`.
	 *
	 * Two siblings of the same kind that cannot be reconciled (two `referenceContent` requirements for the same
	 * reference disagreeing about the `filterBy` or the chunking constraint - whether the two differ or only one side
	 * carries it at all - or carrying two different `orderBy` constraints, two `hierarchyContent` requirements with
	 * different `stopAt` constraints, two `accompanyingPriceContent` requirements for one price name with different
	 * price lists, ...) make `combineWith` throw an {@link EvitaInvalidUsageException} - that is the intended way for
	 * such a conflict to surface, and this method lets it propagate.
	 *
	 * This fold says **return exactly this**, and refusing is what that costs: the requirements it reduces are the
	 * ones the client wrote, and picking one of two contradicting intents on his behalf would silently change what
	 * comes back. Its counterpart {@link DefaultPrefetchRequirementCollector} says **load at least this** and may
	 * therefore widen freely - it merges requirements coming from unrelated sources (the client's `entityFetch` and
	 * the ones the query planner invents), drops the requirements contained within another one, and admits every
	 * requirement through {@link EntityContentRequire#forPrefetch()}, which strips the output projections a prefetch
	 * has no opinion about. That widened set is never what the client receives; the response is re-derived from his
	 * own `EvitaRequest`.
	 *
	 * The three rules this fold implements, and every other site that implements them, are in
	 * `documentation/developer/query/constraint-resolution.md`.
	 *
	 * @param requirements requirements to reduce, never null
	 * @return the very same array instance when there was nothing to combine, a new shorter array otherwise
	 * @throws EvitaInvalidUsageException when two siblings of the same kind contradict each other
	 */
	@Nonnull
	static EntityContentRequire[] combineDuplicateRequirements(@Nonnull EntityContentRequire[] requirements) {
		if (requirements.length < 2 || !containsRepeatedRequirementKind(requirements)) {
			return requirements;
		}
		final EntityContentRequire[] reduced = new EntityContentRequire[requirements.length];
		int reducedLength = 0;
		for (final EntityContentRequire requirement : requirements) {
			int combineWithIndex = -1;
			for (int i = 0; i < reducedLength; i++) {
				final EntityContentRequire kept = reduced[i];
				if (kept.getClass().equals(requirement.getClass()) && kept.isCombinableWith(requirement)) {
					combineWithIndex = i;
					break;
				}
			}
			if (combineWithIndex >= 0) {
				reduced[combineWithIndex] = reduced[combineWithIndex].combineWith(requirement);
			} else {
				reduced[reducedLength++] = requirement;
			}
		}
		return reducedLength == requirements.length ? requirements : Arrays.copyOf(reduced, reducedLength);
	}

	/**
	 * Returns TRUE when at least two requirements of the very same class occur in the passed array. This cheap
	 * pre-check keeps the (overwhelmingly common) duplicate-free case allocation free and guarantees that the
	 * original array instance is handed back untouched.
	 *
	 * @param requirements requirements to examine, never null
	 * @return TRUE when the array holds at least two requirements of the same class
	 */
	private static boolean containsRepeatedRequirementKind(@Nonnull EntityContentRequire[] requirements) {
		for (int i = 0; i < requirements.length - 1; i++) {
			final EntityContentRequire examined = requirements[i];
			for (int j = i + 1; j < requirements.length; j++) {
				if (examined.getClass().equals(requirements[j].getClass())) {
					return true;
				}
			}
		}
		return false;
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
	 * @throws EvitaInvalidUsageException when the two carry requirements of one kind that say contradictory things
	 *                                    about a single target - the merge describes what is returned, so it refuses
	 *                                    such a pair instead of letting one of them silently win
	 */
	@Nonnull
	<T extends EntityFetchRequire> T combineWith(@Nullable T anotherRequirement);

	/**
	 * Reduces this fetch requirement so that it holds at most one content requirement of each kind (and, for
	 * {@link ReferenceContent}, of each reference key), folding duplicate siblings into one via
	 * {@link #combineDuplicateRequirements(EntityContentRequire[])}.
	 *
	 * Callers use the reduced instance as the single description of what the request fetches, which is what makes
	 * the single-result lookups over a fetch container (`QueryUtils.findConstraint`) well defined - after the
	 * reduction there can be at most one requirement of a kind to find.
	 *
	 * The identity of the receiver is preserved when there was nothing to combine, so a caller may compare the result
	 * with `==` to learn whether the query contained duplicates at all.
	 *
	 * @param <T> the static type of this requirement, which is also the type of the result
	 * @return this very instance when no two requirements were combined, a new reduced instance otherwise
	 * @throws EvitaInvalidUsageException when two siblings of the same kind contradict each other
	 */
	@Nonnull
	<T extends EntityFetchRequire> T combineDuplicateRequirements();

}
