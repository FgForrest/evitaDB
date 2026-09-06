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
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.exception.GenericEvitaInternalError;

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
 * - `isCombinableWith(T)` — returns `true` when two instances of the same implementation type describe the *same
 *   thing*, as that implementation defines sameness. The kinds that may legitimately occur several times in one
 *   fetch container define it through a **key**: a {@link ReferenceContent} accepts only a requirement carrying the
 *   same *(instance name, set of reference names)*, an {@link AccompanyingPriceContent} only one naming the same
 *   accompanying price. Overlap is explicitly **not** enough — a `referenceContent("a")` overlaps
 *   `referenceContentAll()` and the two are deliberately not combinable, because they are resolved through
 *   different lookups
 * - `combineWith(T)` — produces a new instance covering both requirements; must not mutate either operand
 *   (immutability contract). It may also **refuse**: two requirements sharing a key but disagreeing on something
 *   that has no meaningful union raise an {@link EvitaInvalidUsageException} instead of resolving the disagreement
 *   silently
 * - `isFullyContainedWithin(T)` — returns `true` when everything the receiver asks for is already covered by
 *   `anotherRequirement`, meaning the receiver is redundant and can be discarded. This is a superset relation and
 *   is therefore wider than combinability
 *
 * The static factory method `combineRequirements(T, T)` provides null-safe combination: it returns the non-null
 * operand when only one is present, or delegates to `combineWith` when both are non-null.
 *
 * The protocol has two consumers with deliberately different appetites, and the difference is the reason
 * `forPrefetch()` exists:
 *
 * - {@link FetchRequirementCollector} / {@link DefaultPrefetchRequirementCollector} accumulates during query
 *   planning what must be **loaded at least**. It unions requirements, drops the ones already contained within
 *   another, and admits every requirement through {@link #forPrefetch()} so that no output restriction ever reaches
 *   it — a superset is exactly what a prefetch wants, and widening it can never make an answer wrong
 * - {@link EntityFetchRequire#combineDuplicateRequirements(EntityContentRequire[])} folds the duplicate
 *   requirements the client wrote side by side into what he must receive **exactly**. It deliberately skips the
 *   containment step, because the body the client receives has to preserve the specific-over-default precedence,
 *   and it refuses a pair that disagrees rather than widening it
 *
 * All implementations must be immutable and thread-safe.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface EntityContentRequire extends RequireConstraint {
	EntityContentRequire[] EMPTY_ARRAY = new EntityContentRequire[0];

	/**
	 * Combines two EntityContentRequire requirements into one combined requirement.
	 * If one of the requirements is null, the non-null requirement is returned.
	 * If both are null, null is returned. If both are non-null, they are combined.
	 *
	 * @param <T> the type of the requirement which extends EntityContentRequire
	 * @param a the first EntityContentRequire requirement, can be null
	 * @param b the second EntityContentRequire requirement, can be null
	 * @return the combined EntityContentRequire requirement, or null if both are null
	 * @throws EvitaInvalidUsageException when both requirements are present and cannot be reconciled
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
	 * Determines whether this requirement and `anotherRequirement` describe the same thing and can therefore be folded
	 * into a single requirement by {@link #combineWith(EntityContentRequire)}.
	 *
	 * Combinability is an equivalence on *what the requirement addresses*, not a subset test. The implementations that
	 * may legitimately appear several times in one fetch container compare their key and nothing else — see
	 * {@link ReferenceContent#isCombinableWith(EntityContentRequire)} for the *(instance name, set of reference
	 * names)* key and {@link AccompanyingPriceContent#isCombinableWith(EntityContentRequire)} for the accompanying
	 * price name. A requirement that merely *covers* another one is not combinable with it; that wider relation is
	 * {@link #isFullyContainedWithin(EntityContentRequire)}.
	 *
	 * @param anotherRequirement another requirement to be combined with
	 * @param <T> type of the requirement to be combined with
	 * @return true if both requirements address the same thing and can be combined, false otherwise
	 */
	<T extends EntityContentRequire> boolean isCombinableWith(@Nonnull T anotherRequirement);

	/**
	 * Combines two requirements of the same type (that needs to be compatible with "this" type) into one by merging
	 * the arguments of both of them. The caller **must** have verified
	 * {@link #isCombinableWith(EntityContentRequire)} first — every implementation treats a pair it would never have
	 * accepted as a programming error.
	 *
	 * A pair sharing the key may still turn out irreconcilable, and the merge is then refused instead of being
	 * resolved silently: two `referenceContent` requirements for one reference disagreeing about the `filterBy` or
	 * the chunking constraint — the case where only one of them carries it included, since a restriction dropped
	 * that way would silently return references the client asked to exclude — two `referenceContent` requirements
	 * carrying two different `orderBy` constraints, two `hierarchyContent` requirements bounding the parent chain
	 * differently, or two `accompanyingPriceContent` requirements calculating one price from different price list
	 * sequences.
	 *
	 * @param anotherRequirement another requirement to be combined with
	 * @param <T> type of the requirement to be combined with
	 * @return a new combined requirement
	 * @throws EvitaInvalidUsageException when both requirements address the same thing but cannot be reconciled
	 * @throws GenericEvitaInternalError when `anotherRequirement` is of another type, or when the caller skipped the
	 *                                   {@link #isCombinableWith(EntityContentRequire)} check
	 */
	@Nonnull
	<T extends EntityContentRequire> T combineWith(@Nonnull T anotherRequirement);

	/**
	 * Returns this requirement as it matters for **prefetching** — stripped of everything that merely projects the
	 * loaded data into the response and therefore says nothing about what has to be loaded to answer the query.
	 *
	 * A prefetch requirement is a lower bound: it says "load at least this". Widening it can never make an answer
	 * wrong, because the response is re-derived from the client's own `EvitaRequest` afterwards. The default
	 * implementation hands the receiver back unchanged, which is correct for every requirement whose whole content
	 * *is* a description of what to load; {@link ReferenceContent#forPrefetch()} overrides it to drop its `filterBy`,
	 * `orderBy` and chunking constraints, and {@link HierarchyContent#forPrefetch()} to drop its `stopAt` bound.
	 *
	 * Applied by {@link DefaultPrefetchRequirementCollector} to every requirement entering the prefetch union, and by
	 * nobody else — the fold that shapes the body the client receives must see the requirement as he wrote it.
	 *
	 * @return this requirement without its output restrictions, or this very instance when it carries none
	 */
	@Nonnull
	default EntityContentRequire forPrefetch() {
		return this;
	}

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
