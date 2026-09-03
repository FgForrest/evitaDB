/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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
import io.evitadb.api.query.ConstraintWithDefaults;
import io.evitadb.api.query.HierarchyConstraint;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

import static java.util.Optional.empty;
import static java.util.Optional.of;

/**
 * The `hierarchyContent` requirement fetches the hierarchical placement of the entity — specifically, the chain of
 * ancestor entities from the immediate parent up to the root of the hierarchy tree. It must be placed inside an
 * {@link EntityFetch} constraint and is only applicable to entities that are part of a hierarchical structure
 * (i.e., entities whose schema has the hierarchy flag enabled).
 *
 * ## Default behavior
 *
 * Without any nested constraints, `hierarchyContent()` returns the complete parent chain as a list of parent primary
 * keys all the way up to the hierarchy root. No additional entity body data is loaded for the parent nodes.
 *
 * ## Limiting traversal depth with stopAt
 *
 * A nested {@link HierarchyStopAt} constraint restricts how far up the tree the traversal goes. This is useful when
 * only the immediate parent or grandparent is needed:
 *
 * ```
 * entityFetch(
 *     hierarchyContent(
 *         stopAt(distance(1))
 *     )
 * )
 * ```
 *
 * ## Loading parent entity bodies
 *
 * A nested {@link EntityFetch} causes the engine to load the full body of each parent node in the chain, not just
 * its primary key. Any content requirements valid inside `entityFetch` (attributes, associated data, prices, etc.)
 * can be used to specify what data to include for each parent:
 *
 * ```
 * entityFetch(
 *     hierarchyContent(
 *         stopAt(distance(2)),
 *         entityFetch(
 *             attributeContent("code", "name")
 *         )
 *     )
 * )
 * ```
 *
 * ## Controlling what happens above an unmaterializable ancestor
 *
 * An ancestor that the hierarchy index still holds may nevertheless fail to yield the body that was asked for -
 * it may hold no data in the locale the query filters by, it may have been deleted, or the parent primary key may
 * point at an entity that was never created. An optional leading {@link HierarchyParentsBehaviour} argument decides
 * what the traversal does there:
 *
 * ```
 * entityFetch(
 *     hierarchyContent(
 *         COMPLETE,
 *         entityFetch(
 *             attributeContent("code", "name")
 *         )
 *     )
 * )
 * ```
 *
 * {@link HierarchyParentsBehaviour#MATCHING} - the default - cuts the chain below that ancestor, so every returned
 * element carries the requested body. {@link HierarchyParentsBehaviour#COMPLETE} returns that ancestor as a bodyless
 * pointer and keeps walking above it, so a body may follow a pointer. Because the rule is written in terms of the
 * *requested* body, a `hierarchyContent()` with no inner {@link EntityFetch} requests nothing that could fail and
 * both modes return the full primary-key chain.
 *
 * ## Two hierarchyContent requirements in one entityFetch
 *
 * Several `hierarchyContent` requirements placed in a single `entityFetch` are not an error - they are combined
 * into the one requirement the query is executed with, following the same "the superset wins" rule the other
 * content requirements use:
 *
 * - **the bound is dropped unless both sides carry it.** An absent {@link HierarchyStopAt} means "the whole chain",
 *   which is the superset of any bound, so a caller asking for the whole chain is never truncated by a bound the
 *   other side asked for. Two *different* bounds are refused with {@link EvitaInvalidUsageException}.
 * - **the behaviour is taken from whichever side asks for ancestor bodies.** A requirement with no inner
 *   {@link EntityFetch} can have no ancestor body fail to materialize, so it expresses no preference and combines
 *   with either behaviour. Two sides that *both* ask for bodies under *different* behaviours are refused with
 *   {@link EvitaInvalidUsageException} - neither {@link HierarchyParentsBehaviour#MATCHING} nor
 *   {@link HierarchyParentsBehaviour#COMPLETE} is a safe substitute for the other, so the disagreement has to
 *   surface rather than be silently resolved.
 *
 * `entityFetchAllContent()` already emits a bare `hierarchyContent()`, so anything added next to it combines with
 * that bare form. `entityFetchAllContentAnd(hierarchyContent(stopAt(distance(1))))` therefore fetches the **whole**
 * ancestor chain and not one level of it - exactly as `attributeContentAll()` swallows an `attributeContent("code")`
 * written beside it. Ask for a bounded chain with a plain `entityFetch` rather than with the fetch-all shorthand.
 *
 * ## Relationship to HierarchyOfSelf / HierarchyParents
 *
 * `hierarchyContent` differs from the `parents` output requirement (available via `hierarchyOfSelf`):
 * - `hierarchyContent` is lightweight and returns ancestor placement directly on the entity object.
 * - `parents` (via `hierarchyOfSelf` / `hierarchyOfReference`) is more powerful — it provides sibling counts,
 *   statistics, and full subtree navigation, but requires a separate extra-result computation pass.
 *
 * Use `hierarchyContent` when you need simple breadcrumb-style ancestor data. Use `hierarchyOfSelf` / `hierarchyParents`
 * when you need hierarchy statistics or sibling information.
 *
 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#hierarchy-content)
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "content",
	shortDescription = "The constraint triggers fetching the entity's parent hierarchy chain (ancestor entities up to the root) into the returned entities.",
	userDocsLink = "/documentation/query/requirements/fetching#hierarchy-content",
	supportedIn = ConstraintDomain.ENTITY
)
public class HierarchyContent extends AbstractRequireConstraintContainer
	implements ConstraintWithDefaults<RequireConstraint>, HierarchyConstraint<RequireConstraint>,
	SeparateEntityContentRequireContainer, EntityContentRequire {
	@Serial private static final long serialVersionUID = -6406509157596655207L;
	/**
	 * The behaviour applied when the constraint carries no explicit one. It is stored in the argument array of every
	 * instance all the same, so that two constraints differing only in an omitted default still compare equal, and
	 * it is hidden again from {@link #toString()} by {@link #getArgumentsExcludingDefaults()}.
	 */
	public static final HierarchyParentsBehaviour DEFAULT_PARENTS_BEHAVIOUR = HierarchyParentsBehaviour.MATCHING;

	/**
	 * The single funnel every public constructor delegates to. It is what guarantees that the behaviour always sits in
	 * the argument array - including when it was omitted - so that equality never depends on how an instance was built.
	 *
	 * @param parentsBehaviour the behaviour to store, null means {@link #DEFAULT_PARENTS_BEHAVIOUR}
	 * @param requirements the already-filtered child constraints
	 */
	private HierarchyContent(
		@Nullable HierarchyParentsBehaviour parentsBehaviour,
		@Nonnull RequireConstraint[] requirements
	) {
		super(
			new Serializable[]{parentsBehaviour == null ? DEFAULT_PARENTS_BEHAVIOUR : parentsBehaviour},
			requirements
		);
	}

	/**
	 * Requests the whole chain of parent primary keys under {@link #DEFAULT_PARENTS_BEHAVIOUR}. No ancestor bodies are
	 * loaded, so the behaviour is inert.
	 */
	public HierarchyContent() {
		this(null, NO_CHILDREN);
	}

	/**
	 * Requests the chain of parent primary keys as far up as `stopAt` allows, under
	 * {@link #DEFAULT_PARENTS_BEHAVIOUR}. No ancestor bodies are loaded, so the behaviour is inert.
	 *
	 * @param stopAt the condition limiting how far up the traversal goes
	 */
	public HierarchyContent(@Nonnull HierarchyStopAt stopAt) {
		this(null, stopAt, null);
	}

	/**
	 * Requests the whole chain of ancestors together with the bodies described by `entityFetch`, under
	 * {@link #DEFAULT_PARENTS_BEHAVIOUR}.
	 *
	 * @param entityFetch the content requirements applied to each ancestor
	 */
	public HierarchyContent(@Nonnull EntityFetch entityFetch) {
		this(null, null, entityFetch);
	}

	/**
	 * Requests the chain of ancestors as far up as `stopAt` allows, together with the bodies described by
	 * `entityFetch`, under {@link #DEFAULT_PARENTS_BEHAVIOUR}.
	 *
	 * @param stopAt the condition limiting how far up the traversal goes, may be null
	 * @param entityFetch the content requirements applied to each ancestor, may be null
	 */
	public HierarchyContent(@Nullable HierarchyStopAt stopAt, @Nullable EntityFetch entityFetch) {
		this(null, stopAt, entityFetch);
	}

	/**
	 * Requests the whole chain of parent primary keys under the given behaviour. No ancestor bodies are loaded, so the
	 * behaviour is inert - this form exists so that a caller can state it before adding an `entityFetch` later.
	 *
	 * @param parentsBehaviour the behaviour applied to an unmaterializable ancestor, null means the default
	 */
	public HierarchyContent(@Nullable HierarchyParentsBehaviour parentsBehaviour) {
		this(parentsBehaviour, NO_CHILDREN);
	}

	/**
	 * Requests the chain of parent primary keys as far up as `stopAt` allows, under the given behaviour. No ancestor
	 * bodies are loaded, so the behaviour is inert.
	 *
	 * @param parentsBehaviour the behaviour applied to an unmaterializable ancestor, null means the default
	 * @param stopAt the condition limiting how far up the traversal goes, may be null
	 */
	public HierarchyContent(
		@Nullable HierarchyParentsBehaviour parentsBehaviour,
		@Nullable HierarchyStopAt stopAt
	) {
		this(parentsBehaviour, stopAt, null);
	}

	/**
	 * Requests the whole chain of ancestors together with the bodies described by `entityFetch`, under the given
	 * behaviour.
	 *
	 * @param parentsBehaviour the behaviour applied to an ancestor whose requested body cannot be materialized,
	 *                         null means the default
	 * @param entityFetch the content requirements applied to each ancestor, may be null
	 */
	public HierarchyContent(
		@Nullable HierarchyParentsBehaviour parentsBehaviour,
		@Nullable EntityFetch entityFetch
	) {
		this(parentsBehaviour, null, entityFetch);
	}

	/**
	 * Requests the chain of ancestors as far up as `stopAt` allows, together with the bodies described by
	 * `entityFetch`, under the given behaviour.
	 *
	 * @param parentsBehaviour the behaviour applied to an ancestor whose requested body cannot be materialized,
	 *                         null means the default
	 * @param stopAt the condition limiting how far up the traversal goes, may be null
	 * @param entityFetch the content requirements applied to each ancestor, may be null
	 */
	@Creator(silentImplicitClassifier = true)
	public HierarchyContent(
		@Nullable HierarchyParentsBehaviour parentsBehaviour,
		@Nullable HierarchyStopAt stopAt,
		@Nullable EntityFetch entityFetch
	) {
		this(
			parentsBehaviour,
			Arrays.stream(new RequireConstraint[]{stopAt, entityFetch})
				.filter(Objects::nonNull)
				.toArray(RequireConstraint[]::new)
		);
	}

	/**
	 * Returns the behaviour applied to an ancestor whose requested body cannot be materialized. When the constraint
	 * was created without an explicit value, {@link #DEFAULT_PARENTS_BEHAVIOUR} is returned.
	 *
	 * The behaviour has no effect at all unless {@link #getEntityFetch()} is present - with no body requested there is
	 * nothing that could fail to materialize, and the full chain of parent primary keys is returned either way.
	 *
	 * The single argument slot is guaranteed: every instance is built through the sole private constructor, which
	 * always writes exactly one behaviour into it, and an enum is a supported data type that
	 * `BaseConstraint#convertArgumentsIfNeeded` passes through untouched.
	 *
	 * @return the parents behaviour, never null
	 */
	@Nonnull
	public HierarchyParentsBehaviour getParentsBehaviour() {
		return (HierarchyParentsBehaviour) getArguments()[0];
	}

	/**
	 * Returns the condition that limits the top-down hierarchy traversal.
	 */
	@Nonnull
	public Optional<HierarchyStopAt> getStopAt() {
		for (RequireConstraint constraint : getChildren()) {
			if (constraint instanceof HierarchyStopAt hierarchyStopAt) {
				return of(hierarchyStopAt);
			}
		}
		return empty();
	}

	/**
	 * Returns content requirements for hierarchy entities.
	 */
	@Nonnull
	public Optional<EntityFetch> getEntityFetch() {
		for (RequireConstraint constraint : getChildren()) {
			if (constraint instanceof EntityFetch entityFetch) {
				return of(entityFetch);
			}
		}
		return empty();
	}

	@Override
	public boolean isApplicable() {
		return true;
	}

	@Nonnull
	@Override
	public RequireConstraint getCopyWithNewChildren(@Nonnull RequireConstraint[] children, @Nonnull Constraint<?>[] additionalChildren) {
		Assert.isTrue(ArrayUtils.isEmpty(additionalChildren), "Additional children are not supported for HierarchyContent!");
		return new HierarchyContent(getParentsBehaviour(), children);
	}

	@Override
	public <T extends EntityContentRequire> boolean isCombinableWith(@Nonnull T anotherRequirement) {
		return anotherRequirement instanceof HierarchyContent;
	}

	@Override
	public <T extends EntityContentRequire> boolean isFullyContainedWithin(@Nonnull T anotherRequirement) {
		if (anotherRequirement instanceof HierarchyContent anotherHierarchyContent) {
			if (getStopAt().isPresent() || anotherHierarchyContent.getStopAt().isPresent()) {
				return false;
			}
			if (conflictsInParentsBehaviour(anotherHierarchyContent)) {
				return false;
			}
			if (getEntityFetch().isEmpty()) {
				// this side reports every parent primary key and nothing else. A container that requests ancestor
				// bodies under MATCHING cuts the chain below an ancestor whose body does not materialize, so it
				// returns fewer keys than this side does; COMPLETE keeps every one of them and only adds bodies.
				return anotherHierarchyContent.getEntityFetch().isEmpty() ||
					anotherHierarchyContent.getParentsBehaviour() == HierarchyParentsBehaviour.COMPLETE;
			}
			return anotherHierarchyContent.getEntityFetch()
				.map(anotherEntityFetch -> getEntityFetch().get().isFullyContainedWithin(anotherEntityFetch))
				.orElse(false);
		}
		return false;
	}

	@Nonnull
	@SuppressWarnings("unchecked")
	@Override
	public <T extends EntityContentRequire> T combineWith(@Nonnull T anotherRequirement) {
		if (anotherRequirement instanceof HierarchyContent anotherHierarchyContent) {
			final Optional<HierarchyStopAt> thisStopAt = getStopAt();
			final Optional<HierarchyStopAt> thatStopAt = anotherHierarchyContent.getStopAt();
			if (thisStopAt.isPresent() && thatStopAt.isPresent() && !thisStopAt.equals(thatStopAt)) {
				throw new EvitaInvalidUsageException(
					"Cannot combine multiple hierarchy content requirements with stop constraint: " + this + " and " + anotherRequirement,
					"Cannot combine multiple hierarchy content requirements with stop constraint."
				);
			}
			if (conflictsInParentsBehaviour(anotherHierarchyContent)) {
				throw new EvitaInvalidUsageException(
					"Cannot combine multiple hierarchy content requirements with different parents behaviour: " +
						this + " and " + anotherRequirement,
					"Cannot combine multiple hierarchy content requirements with different parents behaviour."
				);
			}
			// the side that asks for no ancestor bodies contributes no behaviour, so the other one's survives intact;
			// when neither asks, neither states a preference and the combination lands on the default rather than on
			// whichever operand happened to be written second
			final HierarchyParentsBehaviour combinedBehaviour;
			if (requestsAncestorBodies()) {
				combinedBehaviour = getParentsBehaviour();
			} else if (anotherHierarchyContent.requestsAncestorBodies()) {
				combinedBehaviour = anotherHierarchyContent.getParentsBehaviour();
			} else {
				combinedBehaviour = DEFAULT_PARENTS_BEHAVIOUR;
			}
			return (T) new HierarchyContent(
				combinedBehaviour,
				Arrays.stream(
					new RequireConstraint[]{
						// an absent bound is the superset: a caller that asked for the whole chain must not be
						// truncated by a bound the other caller asked for, so a bound survives only when both sides
						// carry it - and the check above has already established that the two then agree
						thisStopAt.isPresent() && thatStopAt.isPresent() ? thisStopAt.get() : null,
						EntityFetchRequire.combineRequirements(
							getEntityFetch().orElse(null),
							anotherHierarchyContent.getEntityFetch().orElse(null)
						)
					})
					.filter(Objects::nonNull)
					.toArray(RequireConstraint[]::new)
			);
		} else {
			throw new GenericEvitaInternalError(
				"Only hierarchy requirement can be combined with this one - but got: " + anotherRequirement.getClass(),
				"Only hierarchy requirement can be combined with this one!"
			);
		}
	}

	@Nonnull
	@Override
	public RequireConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new HierarchyContent(parseParentsBehaviour(newArguments), getChildren());
	}

	@Nonnull
	@Override
	public Serializable[] getArgumentsExcludingDefaults() {
		return Arrays.stream(getArguments())
			.filter(it -> !isArgumentImplicit(it))
			.toArray(Serializable[]::new);
	}

	@Override
	public boolean isArgumentImplicit(@Nonnull Serializable serializable) {
		return serializable == DEFAULT_PARENTS_BEHAVIOUR;
	}

	/**
	 * Returns TRUE when this constraint asks for the bodies of the ancestors, i.e. when it carries an inner
	 * {@link EntityFetch}. Only then can an ancestor body fail to materialize, and only then does
	 * {@link #getParentsBehaviour()} decide anything.
	 *
	 * @return TRUE when ancestor bodies are requested
	 */
	private boolean requestsAncestorBodies() {
		return getEntityFetch().isPresent();
	}

	/**
	 * Returns TRUE when this constraint and `anotherHierarchyContent` cannot agree on a single parents behaviour.
	 *
	 * A side that requests no ancestor bodies is skipped rather than reconciled: its behaviour is inert by
	 * definition, so it expresses no preference to conflict with. This is a deliberate departure from
	 * {@link ReferenceContent#combineWith(EntityContentRequire)}, which resolves a disagreement by downgrading to
	 * the stricter {@link ManagedReferencesBehaviour#EXISTING}. There the two values order -
	 * {@link ManagedReferencesBehaviour#EXISTING} returns a subset of what {@link ManagedReferencesBehaviour#ANY}
	 * returns, so the stricter one satisfies both callers. Here they do not:
	 * {@link HierarchyParentsBehaviour#COMPLETE} and {@link HierarchyParentsBehaviour#MATCHING} each return ancestors
	 * the other omits, so neither is a safe substitute for the other and a disagreement has to surface as an error
	 * instead of being silently resolved. Skipping the body-less side is what keeps `entityFetchAll()`, which emits
	 * a bare `hierarchyContent()` carrying the default, combinable with an explicit
	 * `hierarchyContent(COMPLETE, entityFetch(...))`.
	 *
	 * Two inert sides therefore never conflict, whatever they carry - and because neither states a preference,
	 * {@link #combineWith(EntityContentRequire)} resolves them to {@link #DEFAULT_PARENTS_BEHAVIOUR} rather than to
	 * either operand, so that combining is not sensitive to the order the two were written in.
	 *
	 * @param anotherHierarchyContent the constraint to reconcile this one with
	 * @return TRUE when both sides request ancestor bodies and their behaviours differ
	 */
	private boolean conflictsInParentsBehaviour(@Nonnull HierarchyContent anotherHierarchyContent) {
		return requestsAncestorBodies() &&
			anotherHierarchyContent.requestsAncestorBodies() &&
			getParentsBehaviour() != anotherHierarchyContent.getParentsBehaviour();
	}

	/**
	 * Extracts the parents behaviour from a freshly supplied argument array. An empty array means the caller stated
	 * no behaviour and asks for {@link #DEFAULT_PARENTS_BEHAVIOUR}; anything other than a single
	 * {@link HierarchyParentsBehaviour} is refused as invalid usage.
	 *
	 * @param newArguments the argument array to interpret
	 * @return the behaviour the arguments express, never null
	 * @throws EvitaInvalidUsageException when the arguments are not a single behaviour
	 */
	@Nonnull
	private static HierarchyParentsBehaviour parseParentsBehaviour(@Nonnull Serializable[] newArguments) {
		if (ArrayUtils.isEmpty(newArguments)) {
			return DEFAULT_PARENTS_BEHAVIOUR;
		}
		Assert.isTrue(
			newArguments.length == 1 && newArguments[0] instanceof HierarchyParentsBehaviour,
			"HierarchyContent accepts a single HierarchyParentsBehaviour argument, but got: " +
				Arrays.toString(newArguments)
		);
		return (HierarchyParentsBehaviour) newArguments[0];
	}
}
