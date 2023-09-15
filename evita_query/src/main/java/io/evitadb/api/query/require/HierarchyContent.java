/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
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
 * This `hierarchyContent` requirement changes default behaviour of the query engine returning only entity primary keys in the result.
 * When this requirement is used result contains [entity bodies](entity_model.md) along with hierarchyContent with to entities
 * or external objects specified in one or more arguments of this requirement.
 *
 * Example:
 *
 * ```
 * hierarchyContent()
 * hierarchyContent('stocks', entityBody())
 * hierarchyContent('stocks', stopAt(distance(4)), entityBody())
 * ```
 *
 * If you need to fetch hierarchy for referenced entities - you need to wrap the `hierarchyContent` inside
 * the `referenceContent` requirement as follows:
 *
 * ```
 * referenceContent('categories', hierarchyContent())
 * ```
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "content",
	shortDescription = "The constraint triggers fetching parent hierarchy entity parent chain and its bodies into returned main entities.",
	supportedIn = ConstraintDomain.ENTITY
)
public class HierarchyContent extends AbstractRequireConstraintContainer implements HierarchyConstraint<RequireConstraint>, SeparateEntityContentRequireContainer, EntityContentRequire {
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

	@Nonnull
	@SuppressWarnings("unchecked")
	@Override
	public <T extends EntityContentRequire> T combineWith(@Nonnull T anotherRequirement) {
		Assert.isTrue(anotherRequirement instanceof HierarchyContent, "Only HierarchyContent requirement can be combined with this one!");
		return (T) new HierarchyContent(
			Arrays.stream(
				new RequireConstraint[]{
					getStopAt().orElse(null),
					getEntityFetch()
						.map(it ->
							((HierarchyContent) anotherRequirement).getEntityFetch()
								.map(it::combineWith)
								.orElse(it)
						)
						.orElse(null)
				}).filter(Objects::nonNull).toArray(RequireConstraint[]::new)
		);
	}

	@Nonnull
	@Override
	public RequireConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		Assert.isTrue(ArrayUtils.isEmpty(newArguments), "Additional children are not supported for HierarchyContent!");
		return this;
	}
}
