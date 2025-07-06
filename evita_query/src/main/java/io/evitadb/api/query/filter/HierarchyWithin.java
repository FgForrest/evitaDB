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
import io.evitadb.exception.EvitaInvalidUsageException;
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
 * The constraint `hierarchyWithin` allows you to restrict the search to only those entities that are part of
 * the hierarchy tree starting with the root node identified by the first argument of this constraint. In e-commerce
 * systems the typical representative of a hierarchical entity is a category, which will be used in all of our examples.
 *
 * The constraint accepts following arguments:
 *
 * - optional name of the queried entity reference schema that represents the relationship to the hierarchical entity
 *   type, your entity may target different hierarchical entities in different reference types, or it may target
 *   the same hierarchical entity through multiple semantically different references, and that is why the reference name
 *   is used instead of the target entity type.
 * - a single mandatory filter constraint that identifies one or more hierarchy nodes that act as hierarchy roots;
 *   multiple constraints must be enclosed in AND / OR containers
 * - optional constraints allow you to narrow the scope of the hierarchy; none or all of the constraints may be present:
 *
 *      - {@link HierarchyDirectRelation}
 *      - {@link HierarchyHaving}
 *      - {@link HierarchyAnyHaving}
 *      - {@link HierarchyExcluding}
 *      - {@link HierarchyExcludingRoot}
 *
 * The most straightforward usage is filtering the hierarchical entities themselves.
 *
 * <pre>
 * query(
 *     collection("Category"),
 *     filterBy(
 *         hierarchyWithinSelf(
 *             attributeEquals("code", "accessories")
 *         )
 *     ),
 *     require(
 *         entityFetch(
 *             attributeContent("code")
 *         )
 *     )
 * )
 * </pre>
 *
 * The `hierarchyWithin` constraint can also be used for entities that directly reference a hierarchical entity type.
 * The most common use case from the e-commerce world is a product that is assigned to one or more categories.
 *
 * <pre>
 * query(
 *     collection("Product"),
 *     filterBy(
 *         hierarchyWithin(
 *             "categories",
 *             attributeEquals("code", "accessories")
 *         )
 *     ),
 *     require(
 *         entityFetch(
 *             attributeContent("code")
 *         )
 *     )
 * )
 * </pre>
 *
 * Products assigned to two or more subcategories of Accessories category will only appear once in the response
 * (contrary to what you might expect if you have experience with SQL).
 *
 * <p><a href="https://evitadb.io/documentation/query/filtering/hierarchy#hierarchy-within">Visit detailed user documentation</a></p>
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "within",
	shortDescription = "The constraint if entity is placed inside the defined hierarchy tree (or has reference to any hierarchical entity in the tree).",
	userDocsLink = "/documentation/query/filtering/hierarchy#hierarchy-within",
	supportedIn = ConstraintDomain.ENTITY
)
public class HierarchyWithin extends AbstractFilterConstraintContainer
	implements HierarchyFilterConstraint, SeparateEntityScopeContainer, ConstraintContainerWithSuffix {
	@Serial private static final long serialVersionUID = 5346689836560255185L;
	private static final String SUFFIX = "self";

	private HierarchyWithin(
		@Nonnull Serializable[] argument,
		@Nonnull FilterConstraint[] fineGrainedConstraints,
		@Nonnull Constraint<?>... additionalChildren
	) {
		super(argument, fineGrainedConstraints);
		Assert.isPremiseValid(
			ArrayUtils.isEmpty(additionalChildren),
			"Constraint hierarchyWithin accepts only filtering inner constraints!"
		);
	}

	@Creator(suffix = SUFFIX, silentImplicitClassifier = true)
	public HierarchyWithin(
		@Nonnull @Child(domain = ConstraintDomain.ENTITY) FilterConstraint ofParent,
		@Nonnull @Child(uniqueChildren = true) HierarchySpecificationFilterConstraint... with
	) {
		this(
			NO_ARGS,
			ArrayUtils.mergeArrays(
				new FilterConstraint[] {ofParent},
				with
			)
		);
	}

	@Creator
	public HierarchyWithin(
		@Nonnull @Classifier String referenceName,
		@Nonnull @Child(domain = ConstraintDomain.ENTITY) FilterConstraint ofParent,
		@Nonnull @Child(uniqueChildren = true) HierarchySpecificationFilterConstraint... with
	) {
		this(
			new Serializable[]{referenceName},
			ArrayUtils.mergeArrays(
				new FilterConstraint[] {ofParent},
				with
			)
		);
	}

	@Override
	@Nonnull
	public Optional<String> getReferenceName() {
		return getArguments().length == 0 ? empty() : ofNullable((String) getArguments()[0]);
	}

	@Nonnull
	@Override
	public Optional<String> getSuffixIfApplied() {
		return getReferenceName()
			.map(it -> Optional.<String>empty())
			.orElseGet(() -> of(SUFFIX));
	}

	/**
	 * Returns true if withinHierarchy should return only entities directly related to the {@link #getParentFilter()} entity.
	 */
	@Override
	public boolean isDirectRelation() {
		return Arrays.stream(getChildren())
			.anyMatch(HierarchyDirectRelation.class::isInstance);
	}

	/**
	 * Returns filtering constraints that return entities whose trees should be included in hierarchy query.
	 */
	@AliasForParameter("ofParent")
	@Nonnull
	public FilterConstraint getParentFilter() {
		return Arrays.stream(getChildren())
			.filter(it -> !(it instanceof HierarchySpecificationFilterConstraint))
			.findFirst()
			.orElseThrow(() -> new EvitaInvalidUsageException("No filtering was specified for the HierarchyWithin constraint!"));
	}

	/**
	 * Returns filtering constraints that return entities whose trees should be included in the hierarchy query.
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
	 * Returns true if withinHierarchy should not return entities directly related to the {@link #getParentFilter()}} entity.
	 */
	public boolean isExcludingRoot() {
		return Arrays.stream(getChildren())
			.anyMatch(HierarchyExcludingRoot.class::isInstance);
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
		return super.isNecessary() || isApplicable();
	}

	@Override
	public boolean isApplicable() {
		return getChildren().length > 0;
	}

	@Nonnull
	@Override
	public FilterConstraint getCopyWithNewChildren(@Nonnull FilterConstraint[] children, @Nonnull Constraint<?>[] additionalChildren) {
		return new HierarchyWithin(getArguments(), children, additionalChildren);
	}

	@Nonnull
	@Override
	public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new HierarchyWithin(newArguments, getChildren(), getExcludedChildrenFilter());
	}

}
