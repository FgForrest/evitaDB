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
 * The `hierarchyWithinRoot` constraint restricts query results to entities that are part of the entire hierarchy tree,
 * treating all top-level nodes as children of an invisible "virtual" root parent. This is the complement to
 * {@link HierarchyWithin}, differing only in that it does not require specifying explicit root nodes.
 *
 * **Syntax**
 *
 * ```evitaql
 * hierarchyWithinRoot(
 *     referenceName?: String,
 *     with?: HierarchySpecificationFilterConstraint+
 * )
 * ```
 *
 * **Arguments**
 *
 * - `referenceName` (optional): name of the reference schema representing the relationship to the hierarchical entity
 *   type. Omit this argument (or use the `hierarchyWithinRootSelf` variant) when querying hierarchical entities
 *   directly. When present, this argument identifies which reference to follow when querying non-hierarchical entities
 *   that reference hierarchical ones.
 * - `with` (optional): zero or more {@link HierarchySpecificationFilterConstraint} instances that refine the hierarchy
 *   query behavior:
 *   - {@link HierarchyDirectRelation}: limits results to top-level nodes only (children of the virtual root)
 *   - {@link HierarchyHaving}: requires each node in traversal path to satisfy filter
 *   - {@link HierarchyAnyHaving}: requires at least one node in subtree to satisfy filter
 *   - {@link HierarchyExcluding}: excludes subtrees whose root matches filter
 *
 * Note: {@link HierarchyExcludingRoot} is not supported for `hierarchyWithinRoot` because there is no actual parent
 * node to exclude (the "virtual" root has no corresponding entity).
 *
 * **Virtual Root Concept**
 *
 * evitaDB supports hierarchies with multiple top-level nodes (entities whose parent field is null). The
 * `hierarchyWithinRoot` constraint conceptualizes these top-level nodes as children of an invisible "virtual" root
 * parent that sits above all actual hierarchy nodes. This virtual root provides a unified entry point for queries that
 * need to traverse the entire hierarchy tree regardless of how many actual root nodes exist.
 *
 * **Difference from HierarchyWithin**
 *
 * | Aspect | HierarchyWithin | HierarchyWithinRoot |
 * |--------|-----------------|---------------------|
 * | Root node specification | Required (`ofParent` argument) | Implicit (virtual root) |
 * | Returns | Specified root(s) + descendants | All top-level nodes + descendants |
 * | Typical use case | Query a specific subtree | Query entire hierarchy |
 * | ExcludingRoot support | Yes | No (no actual root to exclude) |
 * | Multiple roots | Must explicitly list with OR | Automatically includes all |
 *
 * **Default Behavior**
 *
 * Without specification constraints, `hierarchyWithinRoot`:
 * - Returns all top-level hierarchy nodes (nodes with no parent)
 * - Returns all direct and transitive descendants of those top-level nodes
 * - Excludes orphaned nodes (nodes with parent references pointing to non-existent entities)
 *
 * **Query Modes**
 *
 * Like {@link HierarchyWithin}, this constraint operates in two modes:
 *
 * 1. **Self-hierarchical mode** (when `referenceName` is omitted): returns all hierarchical entities in the collection
 *    except orphans. For example, querying `Category` entities with `hierarchyWithinRootSelf()` returns the entire
 *    category tree.
 *
 * 2. **Reference-hierarchical mode** (when `referenceName` is provided): returns all non-hierarchical entities that
 *    reference any entity in the hierarchical tree. For example, querying `Product` entities with
 *    `hierarchyWithinRoot("categories")` returns all products assigned to any valid category (excluding products
 *    assigned only to orphaned categories).
 *
 * **Orphan Exclusion**
 *
 * Orphaned hierarchy nodes (nodes whose parent field references a non-existent entity) never satisfy any hierarchy
 * query, including `hierarchyWithinRoot`. In self-hierarchical mode, orphaned categories are excluded from results. In
 * reference-hierarchical mode, products assigned exclusively to orphaned categories are excluded (but products assigned
 * to both orphaned and valid categories remain in the result).
 *
 * **Deduplication**
 *
 * In reference-hierarchical mode, entities referencing multiple nodes in the hierarchy tree appear only once in the
 * result, even if they reference multiple top-level subtrees.
 *
 * **DirectRelation Behavior**
 *
 * When combined with {@link HierarchyDirectRelation}, the constraint returns only top-level hierarchy nodes (or
 * entities directly referencing top-level nodes):
 *
 * ```java
 * // Returns only top-level categories (those with parent = null)
 * query(
 *     collection("Category"),
 *     filterBy(
 *         hierarchyWithinRootSelf(directRelation())
 *     )
 * )
 * ```
 *
 * In reference-hierarchical mode, `directRelation` is meaningless for `hierarchyWithinRoot` because no entity can be
 * directly assigned to the virtual root (entities can only be assigned to actual hierarchy nodes).
 *
 * **Constraint Uniqueness**
 *
 * Only one `hierarchyWithin` or `hierarchyWithinRoot` constraint is allowed per query. Using multiple hierarchy filter
 * constraints results in a validation error.
 *
 * **Suffix Support**
 *
 * This constraint implements {@link ConstraintContainerWithSuffix}, providing a `Self` suffix variant when
 * `referenceName` is omitted. The constraint is serialized as `hierarchyWithinRootSelf()` in EvitaQL when targeting
 * self-hierarchical entities, and as `hierarchyWithinRoot("reference")` when targeting referenced hierarchical
 * entities.
 *
 * **Examples**
 *
 * ```java
 * // Self-hierarchical: return all categories in the tree
 * query(
 *     collection("Category"),
 *     filterBy(
 *         hierarchyWithinRootSelf()
 *     ),
 *     require(
 *         entityFetch(attributeContent("code"))
 *     )
 * )
 *
 * // Reference-hierarchical: return all products assigned to any category
 * query(
 *     collection("Product"),
 *     filterBy(
 *         hierarchyWithinRoot("categories")
 *     ),
 *     require(
 *         entityFetch(attributeContent("code"))
 *     )
 * )
 *
 * // Top-level categories only (direct children of virtual root)
 * query(
 *     collection("Category"),
 *     filterBy(
 *         hierarchyWithinRootSelf(directRelation())
 *     )
 * )
 *
 * // Exclude clearance subtree from entire tree
 * query(
 *     collection("Product"),
 *     filterBy(
 *         hierarchyWithinRoot(
 *             "categories",
 *             excluding(attributeEquals("clearance", true))
 *         )
 *     )
 * )
 *
 * // Include only valid categories throughout entire tree
 * query(
 *     collection("Product"),
 *     filterBy(
 *         hierarchyWithinRoot(
 *             "categories",
 *             having(attributeInRange("validity", ZonedDateTime.now()))
 *         )
 *     )
 * )
 *
 * // Products in trees containing at least one featured category
 * query(
 *     collection("Product"),
 *     filterBy(
 *         hierarchyWithinRoot(
 *             "categories",
 *             anyHaving(attributeEquals("featured", true))
 *         )
 *     )
 * )
 * ```
 *
 * **Use Cases**
 *
 * Common scenarios for `hierarchyWithinRoot`:
 * - **Faceted navigation starting point**: return all products in the catalog, allowing users to drill down via facets
 * - **Breadcrumb generation**: fetch the entire category tree for building navigation menus
 * - **Data validation**: identify orphaned hierarchy nodes by comparing results with and without the constraint
 * - **Global filtering**: apply filters (validity, status) across the entire hierarchy tree
 *
 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/hierarchy#hierarchy-within-root)
 *
 * @see HierarchyWithin variant with explicit root node specification
 * @see HierarchyFilterConstraint parent interface defining hierarchy filter contract
 * @see HierarchySpecificationFilterConstraint refinement constraints for modifying behavior
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "withinRoot",
	shortDescription = "The constraint checks if an entity is placed inside the defined hierarchy tree " +
		"starting at the root of the tree (or has reference to any hierarchical entity in the tree).",
	userDocsLink = "/documentation/query/filtering/hierarchy#hierarchy-within-root",
	supportedIn = ConstraintDomain.ENTITY
)
public class HierarchyWithinRoot extends AbstractFilterConstraintContainer
	implements HierarchyFilterConstraint, SeparateEntityScopeContainer, ConstraintContainerWithSuffix {
	@Serial private static final long serialVersionUID = -4396541048481960654L;
	private static final String SUFFIX = "self";

	private HierarchyWithinRoot(
		@Nonnull Serializable[] argument,
		@Nonnull FilterConstraint[] fineGrainedConstraints,
		@Nonnull Constraint<?>... additionalChildren
	) {
		super(argument, fineGrainedConstraints, additionalChildren);
		final Optional<String> referenceName = getReferenceName();
		for (final FilterConstraint filterConstraint : fineGrainedConstraints) {
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
	public FilterConstraint getCopyWithNewChildren(
		@Nonnull FilterConstraint[] children,
		@Nonnull Constraint<?>[] additionalChildren
	) {
		return new HierarchyWithinRoot(getArguments(), children, additionalChildren);
	}

	@Nonnull
	@Override
	public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new HierarchyWithinRoot(newArguments, getChildren(), getAdditionalChildren());
	}

}
