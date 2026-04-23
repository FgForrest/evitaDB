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

import io.evitadb.api.query.Constraint;
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
	implements HierarchyConstraint<RequireConstraint>, SeparateEntityContentRequireContainer, EntityContentRequire {
	@Serial private static final long serialVersionUID = -6406509157596655207L;

	private HierarchyContent(@Nonnull RequireConstraint[] requirements) {
		super(NO_ARGS, requirements);
	}

	public HierarchyContent() {
		super();
	}

	public HierarchyContent(@Nonnull HierarchyStopAt stopAt) {
		super(stopAt);
	}

	public HierarchyContent(@Nonnull EntityFetch entityFetch) {
		super(entityFetch);
	}

	@Creator(silentImplicitClassifier = true)
	public HierarchyContent(@Nullable HierarchyStopAt stopAt, @Nullable EntityFetch entityFetch) {
		super(
			NO_ARGS,
			Arrays.stream(new RequireConstraint[]{stopAt, entityFetch}).filter(Objects::nonNull).toArray(RequireConstraint[]::new)
		);
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
		return new HierarchyContent(children);
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
			return getEntityFetch().isEmpty() ||
				anotherHierarchyContent.getEntityFetch()
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
			return (T) new HierarchyContent(
				Arrays.stream(
					new RequireConstraint[]{
						thisStopAt.or(() -> thatStopAt).orElse(null),
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
		Assert.isTrue(ArrayUtils.isEmpty(newArguments), "Additional children are not supported for HierarchyContent!");
		return new HierarchyContent(getChildren());
	}
}
