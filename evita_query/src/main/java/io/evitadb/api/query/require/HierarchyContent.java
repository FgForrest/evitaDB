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
 * The `hierarchyContent` requirement allows you to access the information about the hierarchical placement of
 * the entity.
 *
 * If no additional constraints are specified, entity will contain a full chain of parent primary keys up to the root
 * of a hierarchy tree. You can limit the size of the chain by using a stopAt constraint - for example, if you're only
 * interested in a direct parent of each entity returned, you can use a stopAt(distance(1)) constraint. The result is
 * similar to using a parents constraint, but is limited in that it doesn't provide information about statistics and
 * the ability to list siblings of the entity parents. On the other hand, it's easier to use - since the hierarchy
 * placement is directly available in the retrieved entity object.
 *
 * If you provide a nested entityFetch constraint, the hierarchy information will contain the bodies of the parent
 * entities in the required width. The attributeContent inside the entityFetch allows you to access the attributes
 * of the parent entities, etc.
 *
 * Example:
 *
 * <pre>
 * entityFetch(
 *    hierarchyContent()
 * )
 * </pre>
 *
 * <p><a href="https://evitadb.io/documentation/query/requirements/fetching#hierarchy-content">Visit detailed user documentation</a></p>
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "content",
	shortDescription = "The constraint triggers fetching parent hierarchy entity parent chain and its bodies into returned main entities.",
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
		return this;
	}
}
