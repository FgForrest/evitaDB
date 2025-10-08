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

package io.evitadb.api.query.filter;

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.ConstraintContainerWithSuffix;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.AliasForParameter;
import io.evitadb.api.query.descriptor.annotation.Child;
import io.evitadb.api.query.descriptor.annotation.Classifier;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Optional;

import static java.util.Optional.empty;
import static java.util.Optional.of;
import static java.util.Optional.ofNullable;

/**
 * The constraint `hierarchyWithinRoot` allows you to restrict the search to only those entities that are part of
 * the entire hierarchy tree. In e-commerce systems the typical representative of a hierarchical entity is a category.
 *
 * The single difference to {@link HierarchyWithin} constraint is that it doesn't accept a root node specification.
 * Because evitaDB accepts multiple root nodes in your entity hierarchy, it may be helpful to imagine there is
 * an invisible "virtual" top root above all the top nodes (whose parent property remains NULL) you have in your entity
 * hierarchy and this virtual top root is targeted by this constraint.
 *
 - The constraint accepts following arguments:
 *
 * - optional name of the queried entity reference schema that represents the relationship to the hierarchical entity
 *   type, your entity may target different hierarchical entities in different reference types, or it may target
 *   the same hierarchical entity through multiple semantically different references, and that is why the reference name
 *   is used instead of the target entity type.
 * - optional constraints allow you to narrow the scope of the hierarchy; none or all of the constraints may be present:
 *
 *      - {@link HierarchyDirectRelation}
 *      - {@link HierarchyHaving}
 *      - {@link HierarchyAnyHaving}
 *      - {@link HierarchyExcluding}
 *
 * The `hierarchyWithinRoot`, which targets the Category collection itself, returns all categories except those that
 * would point to non-existent parent nodes, such hierarchy nodes are called orphans and do not satisfy any hierarchy
 * query.
 *
 * <pre>
 * query(
 *     collection("Category"),
 *     filterBy(
 *         hierarchyWithinRootSelf()
 *     ),
 *     require(
 *         entityFetch(
 *             attributeContent("code")
 *         )
 *     )
 * )
 * </pre>
 *
 * The `hierarchyWithinRoot` constraint can also be used for entities that directly reference a hierarchical entity
 * type. The most common use case from the e-commerce world is a product that is assigned to one or more categories.
 *
 * <pre>
 * query(
 *     collection("Product"),
 *     filterBy(
 *         hierarchyWithinRoot("categories")
 *     ),
 *     require(
 *         entityFetch(
 *             attributeContent("code")
 *         )
 *     )
 * )
 * </pre>
 *
 * Products assigned to only one orphan category will be missing from the result. Products assigned to two or more
 * categories will only appear once in the response (contrary to what you might expect if you have experience with SQL).
 *
 * <p><a href="https://evitadb.io/documentation/query/filtering/hierarchy#hierarchy-within-root">Visit detailed user documentation</a></p>
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "withinRoot",
	shortDescription = "The constraint if entity is placed inside the defined hierarchy tree starting at the root of the tree (or has reference to any hierarchical entity in the tree).",
	userDocsLink = "/documentation/query/filtering/hierarchy#hierarchy-within-root",
	supportedIn = ConstraintDomain.ENTITY
)
public class HierarchyWithinRoot extends AbstractFilterConstraintContainer
	implements HierarchyFilterConstraint, SeparateEntityScopeContainer, ConstraintContainerWithSuffix {
	@Serial private static final long serialVersionUID = -4396541048481960654L;
	private static final String SUFFIX = "self";

	private HierarchyWithinRoot(@Nonnull Serializable[] argument, @Nonnull FilterConstraint[] fineGrainedConstraints, @Nonnull Constraint<?>... additionalChildren) {
		super(argument, fineGrainedConstraints, additionalChildren);
		final Optional<String> referenceName = getReferenceName();
		for (FilterConstraint filterConstraint : fineGrainedConstraints) {
			Assert.isTrue(
				filterConstraint instanceof HierarchyExcluding ||
						filterConstraint instanceof HierarchyHaving ||
						filterConstraint instanceof HierarchyAnyHaving ||
					(filterConstraint instanceof HierarchyDirectRelation && referenceName.isEmpty()),
				() -> "Constraint hierarchyWithinRoot accepts only " +
					(referenceName.isEmpty() ? "Excluding, Having, AnyHaving, or DirectRelation when it targets same entity type" :
						"Excluding when it targets different entity type") + " as inner query!"
			);
		}
		Assert.isPremiseValid(
			ArrayUtils.isEmpty(additionalChildren),
			() -> "Constraint hierarchyWithinRoot accepts only " +
				(referenceName.isEmpty() ? "Excluding, Having, or DirectRelation when it targets same entity type" :
					"Excluding when it targets different entity type") + " as inner query!"
		);
	}

	@Creator(suffix = SUFFIX, silentImplicitClassifier = true)
	public HierarchyWithinRoot(
		@Nonnull @Child(uniqueChildren = true) HierarchySpecificationFilterConstraint... with
	) {
		this(NO_ARGS, with);
	}

	@Creator
	public HierarchyWithinRoot(
		@Nonnull @Classifier String referenceName,
		@Nonnull @Child(uniqueChildren = true) HierarchySpecificationFilterConstraint... with
	) {
		this(new Serializable[]{referenceName}, with);
	}

	@Nonnull
	@Override
	public Optional<String> getReferenceName() {
		final Serializable[] arguments = getArguments();
		final Serializable firstArgument = arguments.length > 0 ? arguments[0] : null;
		return firstArgument instanceof Integer ? empty() : ofNullable((String) firstArgument);
	}

	@Nonnull
	@Override
	public Optional<String> getSuffixIfApplied() {
		return getReferenceName()
			.map(it -> Optional.<String>empty())
			.orElseGet(() -> of(SUFFIX));
	}

	/**
	 * Returns true if withinHierarchy should return only entities directly related to the root entity.
	 */
	@Override
	public boolean isDirectRelation() {
		return Arrays.stream(getChildren())
			.anyMatch(HierarchyDirectRelation.class::isInstance);
	}

	/**
	 * Returns filtering constraints that return entities whose trees should be included from hierarchy query.
	 */
	@Override
	@Nonnull
	public FilterConstraint[] getHavingChildrenFilter() {
		return Arrays.stream(getChildren())
			.filter(HierarchyHaving.class::isInstance)
			.map(it -> ((HierarchyHaving) it).getFiltering())
			.findFirst()
			.orElseGet(() -> new FilterConstraint[0]);
	}

	/**
	 * Returns filtering constraints that return entities whose have at least one children satisfying the filter in order
	 * the hierarchy tree should be included in the hierarchy query.
	 */
	@Override
	@Nonnull
	public FilterConstraint[] getHavingAnyChildFilter() {
		return Arrays.stream(getChildren())
			.filter(HierarchyAnyHaving.class::isInstance)
			.map(it -> ((HierarchyAnyHaving) it).getFiltering())
			.findFirst()
			.orElseGet(() -> new FilterConstraint[0]);
	}

	/**
	 * Returns filtering constraints that return entities whose trees should be excluded from hierarchy query.
	 */
	@Override
	@Nonnull
	public FilterConstraint[] getExcludedChildrenFilter() {
		return Arrays.stream(getChildren())
			.filter(HierarchyExcluding.class::isInstance)
			.map(it -> ((HierarchyExcluding) it).getFiltering())
			.findFirst()
			.orElseGet(() -> new FilterConstraint[0]);
	}

	/**
	 * Returns all specification constraints passed in `with` parameter.
	 */
	@AliasForParameter("with")
	@Nonnull
	public HierarchySpecificationFilterConstraint[] getHierarchySpecificationConstraints() {
		return Arrays.stream(getChildren())
			.filter(HierarchySpecificationFilterConstraint.class::isInstance)
			.map(HierarchySpecificationFilterConstraint.class::cast)
			.toArray(HierarchySpecificationFilterConstraint[]::new);
	}

	@Override
	public boolean isNecessary() {
		return true;
	}

	@Override
	public boolean isApplicable() {
		return true;
	}

	@Nonnull
	@Override
	public FilterConstraint getCopyWithNewChildren(@Nonnull FilterConstraint[] children, @Nonnull Constraint<?>[] additionalChildren) {
		return new HierarchyWithinRoot(getArguments(), children, additionalChildren);
	}

	@Nonnull
	@Override
	public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new HierarchyWithinRoot(newArguments, getChildren(), getAdditionalChildren());
	}

}
