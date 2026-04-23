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

package io.evitadb.api.query.require;

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.AdditionalChild;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Optional;

/**
 * Triggers computation of hierarchy data structures from the entity collection that is itself hierarchical — that is,
 * the queried entity type forms the tree being navigated. This is in contrast to {@link HierarchyOfReference}, which
 * computes hierarchy data from a _referenced_ hierarchical entity.
 *
 * Use this constraint when the queried entity type is hierarchical (e.g., a Category entity querying its own
 * category tree), and you need to render navigation menus, breadcrumb trails, or expandable tree components for the
 * hierarchy that the query result belongs to.
 *
 * `hierarchyOfSelf` can be combined with {@link HierarchyOfReference} in the same query when the queried entity is
 * hierarchical and also holds references to other hierarchical entities, though such composite scenarios are rare.
 *
 * **Required sub-constraints (at least one must be present):**
 *
 * - {@link HierarchyFromRoot}: computes the full tree from the virtual root, regardless of any `hierarchyWithin`
 *   filter; useful for rendering full mega-menus
 * - {@link HierarchyFromNode}: computes a subtree starting from a specific pivot node identified dynamically;
 *   useful for rendering secondary side-menus anchored to a fixed category
 * - {@link HierarchyChildren}: computes direct subcategories of the currently filtered node; suitable for
 *   breadcrumb-adjacent sub-navigation
 * - {@link HierarchyParents}: traverses the ancestor axis from the current node to the root; used for breadcrumbs
 * - {@link HierarchySiblings}: lists all sibling nodes at the same level as the current node
 *
 * **Optional arguments:**
 *
 * - an `OrderBy` constraint (as an additional child) controlling the sort order of hierarchy `LevelInfo` elements
 *   within the computed result; if omitted, the natural order of the hierarchical entity is used
 *
 * The constraint is not applicable (i.e., `isApplicable()` returns `false`) if no sub-constraint is provided.
 *
 * **Example — compute a full category mega-menu alongside products in a category:**
 *
 * ```evitaql
 * query(
 *     collection("Category"),
 *     filterBy(
 *         hierarchyWithinRoot("categories")
 *     ),
 *     require(
 *         hierarchyOfSelf(
 *             orderBy(attributeNatural("order")),
 *             fromRoot(
 *                 "megaMenu",
 *                 entityFetch(attributeContent("code", "name")),
 *                 stopAt(level(2)),
 *                 statistics(CHILDREN_COUNT, QUERIED_ENTITY_COUNT)
 *             )
 *         )
 *     )
 * )
 * ```
 *
 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/hierarchy#hierarchy-of-self)
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "ofSelf",
	shortDescription = "The constraint triggers computation of hierarchy data structures (tree, statistics, parent chain) for the queried hierarchical entity collection itself.",
	userDocsLink = "/documentation/query/requirements/hierarchy#hierarchy-of-self"
)
public class HierarchyOfSelf extends AbstractRequireConstraintContainer
	implements RootHierarchyConstraint, SeparateEntityContentRequireContainer, ExtraResultRequireConstraint {

	@Serial private static final long serialVersionUID = -4394552939743167661L;

	private HierarchyOfSelf(
		@Nonnull RequireConstraint[] children,
		@Nonnull Constraint<?>... additionalChildren
	) {
		super(children, additionalChildren);
		for (RequireConstraint child : children) {
			Assert.isTrue(
				child instanceof HierarchyRequireConstraint || child instanceof EntityFetch,
				"Constraint HierarchyOfSelf accepts only HierarchyRequireConstraint, EntityFetch or OrderBy as inner constraints!"
			);
		}
		for (Constraint<?> child : additionalChildren) {
			Assert.isTrue(
				child instanceof OrderBy,
				"Constraint HierarchyOfSelf accepts only HierarchyRequireConstraint, EntityFetch or OrderBy as inner constraints!"
			);
		}
	}

	public HierarchyOfSelf(
		HierarchyRequireConstraint... requirements
	) {
		super(new Serializable[0], requirements);
	}

	@Creator(silentImplicitClassifier = true)
	public HierarchyOfSelf(
		@Nullable @AdditionalChild(domain = ConstraintDomain.ENTITY) OrderBy orderBy,
		@Nonnull HierarchyRequireConstraint... requirements
	) {
		super(new Serializable[0], requirements, orderBy);
	}

	/**
	 * Returns requirement constraints for the loaded entities.
	 */
	@Nonnull
	public HierarchyRequireConstraint[] getRequirements() {
		return Arrays.stream(getChildren())
			.map(HierarchyRequireConstraint.class::cast)
			.toArray(HierarchyRequireConstraint[]::new);
	}

	/**
	 * Returns filtering constraints that return entities whose trees should be excluded from hierarchy query.
	 */
	@Nonnull
	public Optional<OrderBy> getOrderBy() {
		return Arrays.stream(getAdditionalChildren())
			.filter(OrderBy.class::isInstance)
			.map(OrderBy.class::cast)
			.findFirst();
	}

	@Override
	public boolean isApplicable() {
		return getChildrenCount() > 0;
	}

	@Nonnull
	@Override
	public RequireConstraint getCopyWithNewChildren(
		@Nonnull RequireConstraint[] children,
		@Nonnull Constraint<?>[] additionalChildren
	) {
		return new HierarchyOfSelf(children, additionalChildren);
	}

	@Nonnull
	@Override
	public RequireConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		Assert.isTrue(ArrayUtils.isEmpty(newArguments), "HierarchyOfSelf container accepts no arguments!");
		return new HierarchyOfSelf(getChildren(), getAdditionalChildren());
	}

}
