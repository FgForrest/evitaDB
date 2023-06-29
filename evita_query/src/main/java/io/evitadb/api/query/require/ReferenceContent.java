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
import io.evitadb.api.query.ConstraintWithSuffix;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.ReferenceConstraint;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.AdditionalChild;
import io.evitadb.api.query.descriptor.annotation.AliasForParameter;
import io.evitadb.api.query.descriptor.annotation.Child;
import io.evitadb.api.query.descriptor.annotation.Classifier;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import static java.util.Optional.empty;
import static java.util.Optional.of;

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
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "content",
	shortDescription = "The constraint triggers fetching referenced entity bodies into returned main entities.",
	supportedIn = ConstraintDomain.ENTITY
)
public class ReferenceContent extends AbstractRequireConstraintContainer
	implements ReferenceConstraint<RequireConstraint>, SeparateEntityContentRequireContainer, EntityContentRequire, ConstraintWithSuffix {
	@Serial private static final long serialVersionUID = 3374240925555151814L;
	private static final String SUFFIX = "all";
	public static final ReferenceContent ALL_REFERENCES = new ReferenceContent();

	private ReferenceContent(@Nonnull String[] referenceName,
	                         @Nonnull RequireConstraint[] requirements,
	                         @Nonnull Constraint<?>[] additionalChildren) {
		super(referenceName, requirements, additionalChildren);
	}

	@Creator(suffix = SUFFIX)
	public ReferenceContent() {
		super();
	}

	public ReferenceContent(@Nonnull String... referenceName) {
		super(referenceName);
	}

	public ReferenceContent(@Nonnull String[] referenceNames,
							@Nullable EntityFetch entityRequirement,
	                        @Nullable EntityGroupFetch groupEntityRequirement) {
		super(referenceNames, entityRequirement, groupEntityRequirement);
	}

	@Creator
	public ReferenceContent(@Nonnull @Classifier String referenceName,
							@Nullable @AdditionalChild FilterBy filterBy,
	                        @Nullable @AdditionalChild OrderBy orderBy,
							@Nullable @Child AttributeContent attributeContent,
	                        @Nullable @Child EntityFetch entityFetch,
	                        @Nullable @Child EntityGroupFetch entityGroupFetch) {
		super(new String[] { referenceName }, new RequireConstraint[] {attributeContent, entityFetch, entityGroupFetch}, filterBy, orderBy);
	}

	public ReferenceContent(@Nullable EntityFetch entityRequirement, @Nullable EntityGroupFetch groupEntityRequirement) {
		super(entityRequirement, groupEntityRequirement);
	}

	/**
	 * Returns name of reference which should be loaded along with entity.
	 * Note: this can be used only if there is single reference name. Otherwise {@link #getReferenceNames()} should be used.
	 */
	@Nonnull
	public String getReferenceName() {
		final String[] referenceNames = getReferenceNames();
		Assert.isTrue(
			referenceNames.length == 1,
			"There are multiple reference names, cannot return single name."
		);
		return referenceNames[0];
	}

	/**
	 * Returns names of references which should be loaded along with entity.
	 */
	@Nonnull
	public String[] getReferenceNames() {
		return Arrays.stream(getArguments())
			.map(String.class::cast)
			.toArray(String[]::new);
	}

	/**
	 * Returns attribute content requirement for reference attributes.
	 */
	// TODO JNO: use this method to support attribute specification in references
	@Nonnull
	public Optional<AttributeContent> getAttributeContent() {
		return Arrays.stream(getChildren())
			.filter(it -> AttributeContent.class.isAssignableFrom(it.getClass()))
			.map(it -> (AttributeContent) it)
			.findFirst();
	}

	/**
	 * Returns requirements for entities.
	 */
	@AliasForParameter("entityFetch")
	@Nonnull
	public Optional<EntityFetch> getEntityRequirement() {
		return Arrays.stream(getChildren())
			.filter(it -> EntityFetch.class.isAssignableFrom(it.getClass()))
			.map(it -> (EntityFetch) it)
			.findFirst();
	}

	/**
	 * Returns requirements for group entities.
	 */
	@AliasForParameter("entityGroupFetch")
	@Nonnull
	public Optional<EntityGroupFetch> getGroupEntityRequirement() {
		return Arrays.stream(getChildren())
			.filter(it -> EntityGroupFetch.class.isAssignableFrom(it.getClass()))
			.map(it -> (EntityGroupFetch) it)
			.findFirst();
	}

	/**
	 * Returns filter to filter list of returning references.
	 */
	@Nonnull
	public Optional<FilterBy> getFilterBy() {
		return getAdditionalChild(FilterBy.class);
	}

	/**
	 * Returns sorting to order list of returning references.
	 */
	@Nonnull
	public Optional<OrderBy> getOrderBy() {
		return getAdditionalChild(OrderBy.class);
	}

	/**
	 * Returns TRUE if all available references were requested to load.
	 */
	public boolean isAllRequested() {
		return ArrayUtils.isEmpty(getArguments());
	}

	@Nonnull
	@Override
	public Optional<String> getSuffixIfApplied() {
		return isAllRequested() ? of(SUFFIX) : empty();
	}

	@Override
	public boolean isApplicable() {
		return true;
	}

	@Nonnull
	@SuppressWarnings("unchecked")
	@Override
	public <T extends EntityContentRequire> T combineWith(@Nonnull T anotherRequirement) {
		Assert.isTrue(anotherRequirement instanceof ReferenceContent, "Only References requirement can be combined with this one!");
		if (isAllRequested()) {
			return (T) this;
		} else if (((ReferenceContent) anotherRequirement).isAllRequested()) {
			return anotherRequirement;
		} else {
			final EntityFetch combinedEntityRequirement = combineRequirements(getEntityRequirement().orElse(null), ((ReferenceContent) anotherRequirement).getEntityRequirement().orElse(null));
			final EntityGroupFetch combinedGroupEntityRequirement = combineRequirements(getGroupEntityRequirement().orElse(null), ((ReferenceContent) anotherRequirement).getGroupEntityRequirement().orElse(null));
			final String[] arguments = Stream.concat(
					Arrays.stream(getArguments()).map(String.class::cast),
					Arrays.stream(anotherRequirement.getArguments()).map(String.class::cast)
				)
				.distinct()
				.toArray(String[]::new);
			return (T) new ReferenceContent(
				arguments,
				Arrays.stream(
					new RequireConstraint[] {
						combinedEntityRequirement,
						combinedGroupEntityRequirement
					}
				).filter(Objects::nonNull).toArray(RequireConstraint[]::new),
				Arrays.stream(
					new Constraint<?>[] {
						getFilterBy().orElse(null),
						getOrderBy().orElse(null)
					}
				).filter(Objects::nonNull).toArray(Constraint[]::new)
			);
		}
	}

	@Nullable
	private EntityFetch combineRequirements(@Nullable EntityFetch a, @Nullable EntityFetch b) {
		if (a == null) {
			return b;
		} else if (b == null) {
			return a;
		} else {
			return a.combineWith(b);
		}
	}

	@Nullable
	private EntityGroupFetch combineRequirements(@Nullable EntityGroupFetch a, @Nullable EntityGroupFetch b) {
		if (a == null) {
			return b;
		} else if (b == null) {
			return a;
		} else {
			return a.combineWith(b);
		}
	}

	@Nonnull
	@Override
	public RequireConstraint getCopyWithNewChildren(@Nonnull RequireConstraint[] children, @Nonnull Constraint<?>[] additionalChildren) {
		if (additionalChildren.length > 2 || (additionalChildren.length == 2 && !FilterConstraint.class.isAssignableFrom(additionalChildren[0].getType()) && !OrderConstraint.class.isAssignableFrom(additionalChildren[1].getType()))) {
			throw new IllegalArgumentException("Expected single or no additional filter and order child query.");
		}
		return new ReferenceContent(getReferenceNames(), children, additionalChildren);
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
