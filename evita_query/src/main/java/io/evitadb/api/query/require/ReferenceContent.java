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
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.ReferenceConstraint;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.ConstraintChildrenParamDef;
import io.evitadb.api.query.descriptor.annotation.ConstraintClassifierParamDef;
import io.evitadb.api.query.descriptor.annotation.ConstraintCreatorDef;
import io.evitadb.api.query.descriptor.annotation.ConstraintDef;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;
import java.util.stream.Stream;

/**
 * This `references` requirement changes default behaviour of the query engine returning only entity primary keys in the result.
 * When this requirement is used result contains [entity bodies](entity_model.md) along with references with to entities
 * or external objects specified in one or more arguments of this requirement.
 *
 * Example:
 *
 * ```
 * references()
 * references(CATEGORY)
 * references(CATEGORY, 'stocks', entityBody())
 * references(CATEGORY, filterBy(attributeEquals('code', 10)), entityBody())
 * ```
 *
 * @author Jan Novotn?? (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDef(
	name = "content",
	shortDescription = "The constraint triggers fetching referenced entity bodies into returned main entities.",
	supportedIn = ConstraintDomain.ENTITY
)
public class ReferenceContent extends AbstractRequireConstraintContainer implements ReferenceConstraint<RequireConstraint>, SeparateEntityContentRequireContainer, CombinableEntityContentRequire {
	@Serial private static final long serialVersionUID = 3374240925555151814L;
	public static final ReferenceContent ALL_REFERENCES = new ReferenceContent();

	private ReferenceContent(@Nonnull String[] referencedEntityType,
	                         @Nonnull RequireConstraint[] requirements,
	                         @Nonnull Constraint<?>[] additionalChildren) {
		super(referencedEntityType, requirements, additionalChildren);
	}

	@ConstraintCreatorDef(suffix = "all")
	public ReferenceContent() {
		super();
	}

	public ReferenceContent(@Nonnull String referencedEntityType) {
		super(new String[] { referencedEntityType });
	}

	public ReferenceContent(@Nonnull String... referencedEntityType) {
		super(referencedEntityType);
	}

	public ReferenceContent(@Nonnull String referencedEntityType,
	                        @Nonnull EntityFetch entityRequirement) {
		super(new String[] { referencedEntityType }, entityRequirement);
	}

	public ReferenceContent(@Nonnull String referencedEntityType,
	                        @Nonnull EntityGroupFetch groupEntityRequirement) {
		super(new String[] { referencedEntityType }, groupEntityRequirement);
	}

	public ReferenceContent(@Nonnull String referencedEntityType,
	                        @Nullable EntityFetch entityRequirement,
	                        @Nullable EntityGroupFetch groupEntityRequirement) {
		super(new String[] { referencedEntityType }, entityRequirement, groupEntityRequirement);
	}

	public ReferenceContent(@Nonnull String[] referencedEntityTypes,
	                        @Nonnull EntityFetch entityRequirement) {
		super(referencedEntityTypes, entityRequirement);
	}

	public ReferenceContent(@Nonnull String[] referencedEntityTypes,
	                        @Nonnull EntityGroupFetch groupEntityRequirement) {
		super(referencedEntityTypes, groupEntityRequirement);
	}

	public ReferenceContent(@Nonnull String[] referencedEntityTypes,
							@Nullable EntityFetch entityRequirement,
	                        @Nullable EntityGroupFetch groupEntityRequirement) {
		super(referencedEntityTypes, entityRequirement, groupEntityRequirement);
	}

	public ReferenceContent(@Nonnull String referencedEntityType,
	                        @Nonnull FilterBy filterBy,
	                        @Nonnull EntityFetch entityRequirement) {
		super(new String[] { referencedEntityType }, new RequireConstraint[] {entityRequirement}, filterBy);
	}

	public ReferenceContent(@Nonnull String referencedEntityType,
	                        @Nonnull FilterBy filterBy,
	                        @Nonnull EntityGroupFetch groupEntityRequirement) {
		super(new String[] { referencedEntityType }, new RequireConstraint[] {groupEntityRequirement}, filterBy);
	}

	public ReferenceContent(@Nonnull String referencedEntityType,
	                        @Nonnull FilterBy filterBy,
							@Nullable EntityFetch entityRequirement,
	                        @Nullable EntityGroupFetch groupEntityRequirement) {
		super(new String[] { referencedEntityType }, new RequireConstraint[] {entityRequirement, groupEntityRequirement}, filterBy);
	}

	public ReferenceContent(@Nonnull String referencedEntityType,
	                        @Nonnull FilterBy filterBy) {
		super(new String[] { referencedEntityType }, new RequireConstraint[0], filterBy);
	}

	public ReferenceContent(@Nonnull String referencedEntityType,
	                        @Nonnull OrderBy orderBy,
	                        @Nonnull EntityFetch entityRequirement) {
		super(new String[] { referencedEntityType }, new RequireConstraint[] {entityRequirement}, orderBy);
	}

	public ReferenceContent(@Nonnull String referencedEntityType,
	                        @Nonnull OrderBy orderBy,
	                        @Nonnull EntityGroupFetch groupEntityRequirement) {
		super(new String[] { referencedEntityType }, new RequireConstraint[] {groupEntityRequirement}, orderBy);
	}

	public ReferenceContent(@Nonnull String referencedEntityType,
	                        @Nonnull OrderBy orderBy,
	                        @Nullable EntityFetch entityRequirement,
	                        @Nullable EntityGroupFetch groupEntityRequirement) {
		super(new String[] { referencedEntityType }, new RequireConstraint[] {entityRequirement, groupEntityRequirement}, orderBy);
	}

	public ReferenceContent(@Nonnull String referencedEntityType,
	                        @Nonnull OrderBy orderBy) {
		super(new String[] { referencedEntityType }, new RequireConstraint[0], orderBy);
	}

	public ReferenceContent(@Nonnull String referencedEntityType,
							@Nonnull FilterBy filterBy,
	                        @Nonnull OrderBy orderBy,
	                        @Nonnull EntityFetch entityRequirement) {
		super(new String[] { referencedEntityType }, new RequireConstraint[] {entityRequirement}, filterBy, orderBy);
	}

	public ReferenceContent(@Nonnull String referencedEntityType,
							@Nonnull FilterBy filterBy,
	                        @Nonnull OrderBy orderBy,
	                        @Nonnull EntityGroupFetch groupEntityRequirement) {
		super(new String[] { referencedEntityType }, new RequireConstraint[] {groupEntityRequirement}, filterBy, orderBy);
	}

	public ReferenceContent(@Nonnull String referencedEntityType,
							@Nonnull FilterBy filterBy,
	                        @Nonnull OrderBy orderBy,
	                        @Nullable EntityFetch entityRequirement,
	                        @Nullable EntityGroupFetch groupEntityRequirement) {
		super(new String[] { referencedEntityType }, new RequireConstraint[] {entityRequirement, groupEntityRequirement}, filterBy, orderBy);
	}

	public ReferenceContent(@Nonnull String referencedEntityType,
							@Nonnull FilterBy filterBy,
	                        @Nonnull OrderBy orderBy) {
		super(new String[] { referencedEntityType }, new RequireConstraint[0], filterBy, orderBy);
	}

	public ReferenceContent(@Nonnull EntityFetch entityRequirement) {
		super(entityRequirement);
	}

	public ReferenceContent(@Nonnull EntityGroupFetch groupEntityRequirement) {
		super(groupEntityRequirement);
	}

	public ReferenceContent(@Nullable EntityFetch entityRequirement, @Nullable EntityGroupFetch groupEntityRequirement) {
		super(entityRequirement, groupEntityRequirement);
	}

	@ConstraintCreatorDef
	public ReferenceContent(@Nonnull @ConstraintClassifierParamDef String referencedEntityType,
	                        @Nonnull @ConstraintChildrenParamDef(uniqueChildren = true) EntityRequire... requirements) {
		super(new String[] { referencedEntityType }, requirements);
	}

	/**
	 * Returns names of entity types or external entities which references should be loaded along with entity.
	 */
	@Nonnull
	public String[] getReferencedEntityTypes() {
		return Arrays.stream(getArguments())
			.map(String.class::cast)
			.toArray(String[]::new);
	}

	/**
	 * Returns requirements for entities.
	 */
	@Nullable
	public EntityFetch getEntityRequirement() {
		final int childrenLength = getChildren().length;
		if (childrenLength == 2) {
			return (EntityFetch) getChildren()[0];
		} else if (childrenLength == 1) {
			if (getChildren()[0] instanceof final EntityFetch facetEntityRequirement) {
				return facetEntityRequirement;
			} else {
				return null;
			}
		} else {
			return null;
		}
	}

	/**
	 * Returns requirements for group entities.
	 */
	@Nullable
	public EntityGroupFetch getGroupEntityRequirement() {
		final int childrenLength = getChildren().length;
		if (childrenLength == 2) {
			return (EntityGroupFetch) getChildren()[1];
		} else if (childrenLength == 1) {
			if (getChildren()[0] instanceof final EntityGroupFetch groupEntityRequirement) {
				return groupEntityRequirement;
			} else {
				return null;
			}
		} else {
			return null;
		}
	}

	/**
	 * Returns filter to filter list of returning references.
	 */
	@Nullable
	public FilterBy getFilterBy() {
		return getAdditionalChild(FilterBy.class);
	}

	/**
	 * Returns sorting to order list of returning references.
	 */
	@Nullable
	public OrderBy getOrderBy() {
		return getAdditionalChild(OrderBy.class);
	}

	@Override
	public boolean isNecessary() {
		return true;
	}

	@Override
	public boolean isApplicable() {
		return true;
	}

	/**
	 * Returns TRUE if all available references were requested to load.
	 */
	public boolean isAllRequested() {
		return ArrayUtils.isEmpty(getArguments());
	}

	@Nonnull
	@SuppressWarnings("unchecked")
	@Override
	public <T extends CombinableEntityContentRequire> T combineWith(@Nonnull T anotherRequirement) {
		Assert.isTrue(anotherRequirement instanceof ReferenceContent, "Only References requirement can be combined with this one!");
		if (isAllRequested()) {
			return (T) this;
		} else if (((ReferenceContent) anotherRequirement).isAllRequested()) {
			return anotherRequirement;
		} else {
			return (T) new ReferenceContent(
				Stream.concat(
						Arrays.stream(getArguments()).map(String.class::cast),
						Arrays.stream(anotherRequirement.getArguments()).map(String.class::cast)
					)
					.distinct()
					.toArray(String[]::new)
			);
		}
	}

	@Nonnull
	@Override
	public RequireConstraint getCopyWithNewChildren(@Nonnull Constraint<?>[] children, @Nonnull Constraint<?>[] additionalChildren) {
		if (additionalChildren.length > 1 || (additionalChildren.length == 1 && !FilterConstraint.class.isAssignableFrom(additionalChildren[0].getType()))) {
			throw new IllegalArgumentException("Expected single or no additional filter child query.");
		}
		final RequireConstraint[] requireChildren = Arrays.stream(children)
			.map(c -> (RequireConstraint) c)
			.toArray(RequireConstraint[]::new);
		return new ReferenceContent(getReferencedEntityTypes(), requireChildren, additionalChildren);
	}

	@Nonnull
	@Override
	public RequireConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new ReferenceContent(
			Arrays.stream(newArguments)
				.map(String.class::cast)
				.toArray(String[]::new),
			getChildren(),
			getAdditionalChildren()
		);
	}
}
