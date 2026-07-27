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
 * The `hierarchyWithin` constraint restricts query results to entities that are part of a hierarchy tree starting from
 * one or more specified root nodes. This is the primary filtering constraint for hierarchical queries in evitaDB,
 * enabling efficient subtree queries over tree-structured entity collections like product categories, organizational
 * charts, or geographic regions.
 *
 * **Syntax**
 *
 * ```evitaql
 * hierarchyWithin(
 *     referenceName?: String,
 *     ofParent: FilterConstraint!,
 *     with?: HierarchySpecificationFilterConstraint+
 * )
 * ```
 *
 * **Arguments**
 *
 * - `referenceName` (optional): name of the reference schema representing the relationship to the hierarchical entity
 *   type. Omit this argument (or use the `hierarchyWithinSelf` variant) when querying hierarchical entities directly.
 *   When present, this argument identifies which reference to follow when querying non-hierarchical entities that
 *   reference hierarchical ones. The reference name is used instead of the target entity type because entities may
 *   target the same hierarchical entity type through multiple semantically different references (e.g., `primaryCategory`
 *   vs. `additionalCategories`).
 * - `ofParent` (required): filter constraint identifying one or more hierarchy nodes that act as root nodes for the
 *   query. This can be any filter constraint (typically `entityPrimaryKeyInSet` or `attributeEquals`). To specify
 *   multiple root nodes, wrap them in {@link And} or {@link Or} containers. The selected root nodes and all their
 *   direct and transitive descendants are included in the result (subject to refinement by specification constraints).
 * - `with` (optional): zero or more {@link HierarchySpecificationFilterConstraint} instances that refine the hierarchy
 *   query behavior:
 *   - {@link HierarchyDirectRelation}: limits results to immediate children only
 *   - {@link HierarchyHaving}: requires each node in traversal path to satisfy filter
 *   - {@link HierarchyAnyHaving}: requires at least one node in subtree to satisfy filter
 *   - {@link HierarchyExcluding}: excludes subtrees whose root matches filter
 *   - {@link HierarchyExcludingRoot}: excludes the specified parent node from results (children remain)
 *
 * **Query Modes**
 *
 * The constraint operates in two modes depending on whether the queried entity collection is hierarchical:
 *
 * 1. **Self-hierarchical mode** (when `referenceName` is omitted): filters the hierarchical entities themselves. For
 *    example, querying `Category` entities with `hierarchyWithinSelf(attributeEquals("code", "accessories"))` returns
 *    the Accessories category and all its descendant categories.
 *
 * 2. **Reference-hierarchical mode** (when `referenceName` is provided): filters non-hierarchical entities that
 *    reference hierarchical ones. For example, querying `Product` entities with
 *    `hierarchyWithin("categories", attributeEquals("code", "accessories"))` returns all products assigned to the
 *    Accessories category or any of its descendant categories.
 *
 * **Default Behavior**
 *
 * Without specification constraints, `hierarchyWithin`:
 * - Returns the specified parent node(s) identified by `ofParent`
 * - Returns all direct children of the parent node(s)
 * - Returns all transitive descendants (children of children, recursively to leaf nodes)
 * - Excludes orphaned nodes (nodes with parent references pointing to non-existent entities)
 *
 * **Deduplication**
 *
 * In reference-hierarchical mode, when a product is assigned to multiple categories within the same visible subtree,
 * it appears only once in the result (unlike SQL-style joins which would produce duplicate rows). For example, if
 * product X is assigned to both "Smartphones" and "Tablets" under the "Electronics" category tree, querying products
 * within "Electronics" returns product X exactly once.
 *
 * **Multiple Root Nodes**
 *
 * The `ofParent` constraint can identify multiple root nodes using logical containers:
 *
 * ```java
 * hierarchyWithin(
 *     "categories",
 *     or(
 *         attributeEquals("code", "electronics"),
 *         attributeEquals("code", "accessories")
 *     )
 * )
 * ```
 *
 * This returns entities within either the Electronics subtree OR the Accessories subtree (or both).
 *
 * **Constraint Uniqueness**
 *
 * Only one `hierarchyWithin` or `hierarchyWithinRoot` constraint is allowed per query. Using multiple hierarchy filter
 * constraints results in a validation error.
 *
 * **Suffix Support**
 *
 * This constraint implements {@link ConstraintContainerWithSuffix}, providing a `Self` suffix variant when
 * `referenceName` is omitted. The constraint is serialized as `hierarchyWithinSelf(...)` in EvitaQL when targeting
 * self-hierarchical entities, and as `hierarchyWithin("reference", ...)` when targeting referenced hierarchical
 * entities.
 *
 * **Examples**
 *
 * ```java
 * // Self-hierarchical: return Accessories category and all its subcategories
 * query(
 *     collection("Category"),
 *     filterBy(
 *         hierarchyWithinSelf(
 *             attributeEquals("code", "accessories")
 *         )
 *     ),
 *     require(
 *         entityFetch(attributeContent("code"))
 *     )
 * )
 *
 * // Reference-hierarchical: return products in Accessories category tree
 * query(
 *     collection("Product"),
 *     filterBy(
 *         hierarchyWithin(
 *             "categories",
 *             attributeEquals("code", "accessories")
 *         )
 *     ),
 *     require(
 *         entityFetch(attributeContent("code"))
 *     )
 * )
 *
 * // Direct children only (no transitive descendants)
 * query(
 *     collection("Product"),
 *     filterBy(
 *         hierarchyWithin(
 *             "categories",
 *             attributeEquals("code", "accessories"),
 *             directRelation()
 *         )
 *     )
 * )
 *
 * // Exclude wireless headphones subtree
 * query(
 *     collection("Product"),
 *     filterBy(
 *         hierarchyWithin(
 *             "categories",
 *             attributeEquals("code", "accessories"),
 *             excluding(attributeEquals("code", "wireless-headphones"))
 *         )
 *     )
 * )
 *
 * // Include only valid categories (having constraint)
 * query(
 *     collection("Product"),
 *     filterBy(
 *         hierarchyWithin(
 *             "categories",
 *             attributeEquals("code", "accessories"),
 *             having(attributeInRange("validity", ZonedDateTime.now()))
 *         )
 *     )
 * )
 *
 * // Exclude parent node itself but include children
 * query(
 *     collection("Product"),
 *     filterBy(
 *         hierarchyWithin(
 *             "categories",
 *             attributeEquals("code", "accessories"),
 *             excludingRoot()
 *         )
 *     )
 * )
 *
 * // Multiple root nodes with OR
 * query(
 *     collection("Product"),
 *     filterBy(
 *         hierarchyWithin(
 *             "categories",
 *             or(
 *                 attributeEquals("code", "electronics"),
 *                 attributeEquals("code", "accessories")
 *             )
 *         )
 *     )
 * )
 * ```
 *
 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/hierarchy#hierarchy-within)
 *
 * @see HierarchyWithinRoot variant for entire hierarchy tree without specifying root nodes
 * @see HierarchyFilterConstraint parent interface defining hierarchy filter contract
 * @see HierarchySpecificationFilterConstraint refinement constraints for modifying behavior
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "within",
	shortDescription = "The constraint checks if an entity is placed inside the defined hierarchy tree " +
		"(or has reference to any hierarchical entity in the tree).",
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
	 * Returns filtering constraints that return entities that have at least one child satisfying the filter
	 * in order for the hierarchy tree to be included in the hierarchy query.
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
	 * Returns true if withinHierarchy should not return entities directly related to the {@link #getParentFilter()} entity.
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
	public FilterConstraint getCopyWithNewChildren(
		@Nonnull FilterConstraint[] children,
		@Nonnull Constraint<?>[] additionalChildren
	) {
		return new HierarchyWithin(getArguments(), children, additionalChildren);
	}

	@Nonnull
	@Override
	public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new HierarchyWithin(newArguments, getChildren(), getAdditionalChildren());
	}

}
