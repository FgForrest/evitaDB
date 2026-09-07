/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.Child;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.exception.GenericEvitaInternalError;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;
import java.util.stream.Stream;

/**
 * The `entityFetch` requirement triggers loading the full entity body from storage. Without this requirement, query
 * results contain only entity primary keys. This operation may require a disk access unless the entity is already
 * present in the database cache — frequently accessed entities have a higher probability of remaining cached.
 *
 * `entityFetch` acts as a container for one or more {@link EntityContentRequire} sub-requirements that specify
 * which data containers to load. An empty `entityFetch()` (no sub-requirements) loads only the entity body
 * (locale, scope, and schema reference), but no attributes, associated data, prices, or references.
 *
 * ## Performance consideration
 *
 * Only fetch data you actually need. Each sub-requirement causes additional data to be loaded from disk or cache.
 * Fetching unnecessary data increases both I/O cost and network transfer size.
 *
 * ## Supported content requirements
 *
 * - {@link AttributeContent} — entity or reference attributes
 * - {@link AssociatedDataContent} — unstructured associated data
 * - {@link PriceContent} — price information in various modes
 * - {@link AccompanyingPriceContent} — additional prices alongside the selling price
 * - {@link HierarchyContent} — parent hierarchy chain
 * - {@link ReferenceContent} — references to other entities (with optional nested entity/group fetching)
 * - {@link DataInLocales} — localized data in specific or all locales
 *
 * ## Usage context
 *
 * `entityFetch` is valid in the top-level `require` clause, inside {@link ReferenceContent} (to load referenced entity
 * bodies), and inside {@link HierarchyContent} (to load bodies of parent hierarchy nodes).
 *
 * When multiple `entityFetch` requirements are combined (e.g., from different API layers), their sub-requirements are
 * merged by {@link EntityFetchRequire#combineDuplicateRequirements(EntityContentRequire[])}, so the result fetches
 * the union of the requested data — unless two of the merged sub-requirements contradict each other, in which case
 * the merge is refused with an {@link EvitaInvalidUsageException} rather than resolved silently.
 *
 * Example — fetching selected attributes of a Brand entity:
 *
 * ```
 * query(
 *     collection("Brand"),
 *     filterBy(
 *         entityPrimaryKeyInSet(64703),
 *         entityLocaleEquals("en")
 *     ),
 *     require(
 *         entityFetch(
 *             attributeContent("code", "name")
 *         )
 *     )
 * )
 * ```
 *
 * ## Two content requirements of the same kind in one entityFetch
 *
 * Several content requirements of one kind placed in a single `entityFetch` are not an error and none of them is
 * dropped — they are folded into the one requirement the query is executed with, following the same "the superset
 * wins" rule the individual requirements use among themselves:
 *
 * ```
 * entityFetch(
 *     attributeContent("code"),
 *     attributeContent("name")
 * )
 * ```
 *
 * fetches `code` **and** `name`, exactly as `attributeContent("code", "name")` would.
 *
 * Two kinds may legitimately occur several times in one container, and they fold **per key** rather than into one
 * requirement altogether: a {@link ReferenceContent} is keyed by the references it names (an aliased instance by its
 * instance name as well), an {@link AccompanyingPriceContent} by the name of the price it calculates. So
 * `referenceContent("brand")` beside `referenceContent("categories")` stays two requirements, while two
 * `referenceContent("brand")` requirements become one. A name-specific requirement is never folded into a
 * `referenceContentAll…()` written beside it — the specific one wins the lookup for the reference it names and the
 * default one remains the fallback for every other reference.
 *
 * Two siblings that cannot be reconciled are refused with an {@link EvitaInvalidUsageException} instead of one of
 * them silently winning: two `referenceContent` requirements for one reference carrying different `filterBy`,
 * `orderBy` or chunking constraints, two `hierarchyContent` requirements bounding the parent chain differently, or
 * two `accompanyingPriceContent` requirements calculating one price from different price list sequences.
 * {@link EntityFetchRequire#combineDuplicateRequirements()} defines the fold; `EvitaRequest#getEntityRequirement()`
 * is where it is applied to the query the client sent, once per request.
 *
 * The fold is **shallow** — it reconciles the direct children of the container it is called on. An `entityFetch`
 * nested inside a {@link ReferenceContent} is reduced when the request for the referenced entity is derived, not by
 * the outer call, so each fetch scope is reduced by the request that executes it.
 *
 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#entity-fetch)
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@ConstraintDefinition(
	name = "fetch",
	shortDescription = "The constraint triggers loading full entity bodies instead of just primary key references; its children control which parts of the entity are fetched.",
	userDocsLink = "/documentation/query/requirements/fetching#entity-fetch",
	supportedIn = {ConstraintDomain.GENERIC, ConstraintDomain.REFERENCE, ConstraintDomain.INLINE_REFERENCE, ConstraintDomain.HIERARCHY, ConstraintDomain.FACET}
)
public class EntityFetch extends AbstractRequireConstraintContainer implements EntityFetchRequire {
	@Serial private static final long serialVersionUID = -781235795350040285L;

	/**
	 * Memoized children re-typed as content requirements. This constraint is immutable, so the cast array can only
	 * ever have one value, and {@link #getRequirements()} is the most frequently asked question about it - the
	 * duplicate fold, the containment check, the prefetch collector and `EvitaRequest` all go through it.
	 *
	 * The array is shared with the caller exactly as {@link #getChildren()} shares its own, and is `volatile`
	 * because a racy publication of an array is not covered by the final-field guarantee. It is `transient`
	 * because it is derived state that a deserialized instance recomputes on demand.
	 */
	private transient volatile EntityContentRequire[] memoizedRequirements;

	protected EntityFetch(RequireConstraint[] requireConstraints) {
		super(requireConstraints);
	}

	public EntityFetch() {
		super();
	}

	@Creator
	public EntityFetch(@Nonnull @Child(uniqueChildren = true) EntityContentRequire... requirements) {
		super(requirements);
	}

	@Override
	public boolean isApplicable() {
		return true;
	}

	@Nonnull
	@Override
	public EntityContentRequire[] getRequirements() {
		EntityContentRequire[] memoized = this.memoizedRequirements;
		if (memoized == null) {
			memoized = Arrays.stream(getChildren())
				.map(EntityContentRequire.class::cast)
				.toArray(EntityContentRequire[]::new);
			this.memoizedRequirements = memoized;
		}
		return memoized;
	}

	@Override
	public <T extends EntityFetchRequire> boolean isFullyContainedWithin(@Nonnull T anotherRequirement) {
		if (anotherRequirement instanceof EntityFetch anotherEntityFetch) {
			return Arrays.stream(getRequirements())
				.allMatch(requirement -> Arrays.stream(anotherEntityFetch.getRequirements()).anyMatch(requirement::isFullyContainedWithin));
		}
		return false;
	}

	/**
	 * Merges this fetch with another one into a single fetch requesting the union of both bodies. The merge is the
	 * very same keyed fold that reduces duplicate requirements written side by side
	 * ({@link EntityFetchRequire#combineDuplicateRequirements(EntityContentRequire[])}), applied to the concatenation
	 * of both requirement lists - so a united body follows exactly the precedence a body written once would.
	 *
	 * Containment is deliberately **not** consulted here: a `referenceContent("brand")` is contained within a
	 * `referenceContentAllWithAttributes()`, yet the two are resolved through different lookups (the reference-name
	 * specific requirement wins over the default one), and dropping the specific one would silently widen the body
	 * fetched for `brand`. The prefetch union computed by {@link DefaultPrefetchRequirementCollector} does drop
	 * contained requirements - that one deliberately asks for a superset.
	 *
	 * @param anotherRequirement another fetch to be merged in, NULL yields this very instance
	 * @param <T> type of the requirement to be combined with
	 * @return a new fetch covering both this one and `anotherRequirement`
	 * @throws EvitaInvalidUsageException when two requirements of one kind contradict each other
	 * @throws GenericEvitaInternalError when `anotherRequirement` is not an `entityFetch`
	 */
	@Nonnull
	@Override
	public <T extends EntityFetchRequire> T combineWith(@Nullable T anotherRequirement) {
		if (anotherRequirement == null) {
			//noinspection unchecked
			return (T) this;
		}

		if (anotherRequirement instanceof EntityFetch anotherEntityFetch) {
			final EntityContentRequire[] combinedContentRequirements =
				EntityFetchRequire.combineDuplicateRequirements(
					Stream.concat(
							Arrays.stream(getRequirements()),
							Arrays.stream(anotherEntityFetch.getRequirements())
						)
						.toArray(EntityContentRequire[]::new)
				);

			//noinspection unchecked
			return (T) new EntityFetch(combinedContentRequirements);
		} else {
			throw new GenericEvitaInternalError(
				"Only entity fetch requirement can be combined with this one - but got: " + anotherRequirement.getClass(),
				"Only entity fetch requirement can be combined with this one!"
			);
		}
	}

	@Nonnull
	@Override
	public <T extends EntityFetchRequire> T combineDuplicateRequirements() {
		final EntityContentRequire[] requirements = getRequirements();
		final EntityContentRequire[] reduced = EntityFetchRequire.combineDuplicateRequirements(requirements);
		//noinspection unchecked
		return reduced == requirements ?
			(T) this :
			(T) getCopyWithNewChildren(reduced, getAdditionalChildren());
	}

	@Nonnull
	@Override
	public RequireConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new EntityFetch(getChildren());
	}

	@Nonnull
	@Override
	public RequireConstraint getCopyWithNewChildren(
		@Nonnull RequireConstraint[] children,
		@Nonnull Constraint<?>[] additionalChildren
	) {
		return new EntityFetch(children);
	}

}
