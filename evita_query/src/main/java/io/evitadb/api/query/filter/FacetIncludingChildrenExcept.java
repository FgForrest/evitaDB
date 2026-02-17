/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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
import io.evitadb.api.query.FacetConstraint;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.Child;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.api.query.require.FacetSummary;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;

/**
 * The `includingChildrenExcept` constraint modifies the behavior of its parent {@link FacetHaving} constraint to automatically include child
 * entities in hierarchical facet filtering, **excluding** specific children that match the provided filtering constraint. By default,
 * `facetHaving` only matches entities with a **direct reference** to the specified faceted entity. When `includingChildrenExcept` is present,
 * the query matches entities that reference the faceted entity **or any of its descendants**, except those descendants that satisfy the
 * exclusion constraint.
 *
 * This constraint can only be used within {@link FacetHaving} and only applies to hierarchical references (references to entity types with a
 * hierarchical structure). Using `includingChildrenExcept` with non-hierarchical references results in a query error.
 *
 * This constraint is a {@link FacetConstraint} and {@link HierarchyReferenceSpecificationFilterConstraint}, marking it as a facet-specific
 * hierarchy modifier.
 *
 * ## Basic Usage (Exclude Matching Children)
 *
 * The `includingChildrenExcept` constraint accepts a single filtering constraint that defines which children to **exclude** from the
 * hierarchical propagation:
 *
 * ```
 * query(
 *     collection("Product"),
 *     filterBy(
 *         facetHaving(
 *             "categories",
 *             entityHaving(
 *                 attributeEquals("code", "accessories")
 *             ),
 *             includingChildrenExcept(
 *                 attributeEquals("visible", "INVISIBLE")
 *             )
 *         )
 *     ),
 *     require(
 *         entityFetch(attributeContent("code"))
 *     )
 * )
 * ```
 *
 * This query matches all products that reference the category "accessories" **or any of its visible subcategories**. Subcategories with
 * `visible` attribute set to "INVISIBLE" are excluded from the match, even though they are descendants in the hierarchy. The
 * {@link io.evitadb.api.query.require.FacetSummary} will exclude references to invisible children when computing facet statistics.
 *
 * ## Exclusion Logic
 *
 * The exclusion constraint applies to **child entities** in the hierarchy, not the parent entity matched by the `facetHaving` constraint.
 * Consider a hierarchy:
 *
 * - Accessories (code: "accessories")
 *   - Phone Accessories (visible: "VISIBLE")
 *   - Discontinued Accessories (visible: "INVISIBLE")
 *     - Legacy Chargers (visible: "VISIBLE")
 *
 * With `includingChildrenExcept(attributeEquals("visible", "INVISIBLE"))`:
 *
 * - "Accessories" is included (matches `facetHaving` parent constraint).
 * - "Phone Accessories" is included (child, not excluded).
 * - "Discontinued Accessories" is **excluded** (matches exclusion constraint).
 * - "Legacy Chargers" is **excluded** (parent "Discontinued Accessories" is excluded, so descendants are also excluded).
 *
 * ## Hierarchical Facet Navigation
 *
 * The `includingChildrenExcept` constraint is useful for hierarchical facet navigation when certain branches of the category tree should be
 * hidden from users (e.g., inactive categories, draft categories, or categories with visibility restrictions). By excluding these branches,
 * you ensure that facet statistics and product matches reflect only the visible portion of the hierarchy.
 *
 * ## Comparison with FacetIncludingChildren
 *
 * {@link FacetIncludingChildren} offers two modes:
 *
 * - **Include all children**: `includingChildren()` — includes all descendants.
 * - **Include filtered children**: `includingChildrenHaving(constraint)` — includes only children that **match** the constraint.
 *
 * `includingChildrenExcept` provides the inverse logic:
 *
 * - **Include all except filtered children**: `includingChildrenExcept(constraint)` — includes all children **except** those that match the
 *   constraint.
 *
 * Use `includingChildrenHaving` when you have a whitelist of valid children. Use `includingChildrenExcept` when you have a blacklist of
 * invalid children.
 *
 * ## Facet Summary Integration
 *
 * When used with {@link io.evitadb.api.query.require.FacetSummary}, `includingChildrenExcept` ensures that facet statistics exclude products
 * that reference excluded child categories. This prevents facet counts from inflating due to references to hidden or inactive categories.
 *
 * ## Relationship to Other Constraints
 *
 * - {@link FacetHaving}: The parent container; `includingChildrenExcept` modifies its hierarchical matching behavior.
 * - {@link FacetIncludingChildren}: Similar constraint that includes only matching children (whitelist approach) instead of excluding them.
 * - {@link io.evitadb.api.query.require.FacetSummary}: Computes facet statistics; respects `includingChildrenExcept` when calculating counts.
 *
 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/references#including-children-except)
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@ConstraintDefinition(
	name = "includingChildrenExcept",
	shortDescription = "The constraint automatically selects all children except those matching " +
		"specified sub-constraints of the hierarchical entities matched by `facetHaving` container.",
	userDocsLink = "/documentation/query/filtering/references#including-children-except",
	supportedIn = ConstraintDomain.FACET
)
public class FacetIncludingChildrenExcept extends AbstractFilterConstraintContainer
	implements FacetConstraint<FilterConstraint>, HierarchyReferenceSpecificationFilterConstraint {
	@Serial private static final long serialVersionUID = 3828147822588237136L;
	private static final String CONSTRAINT_NAME = "includingChildrenExcept";

	public FacetIncludingChildrenExcept() {
		// because this query can be used only within some other facet query, it would be
		// unnecessary to duplicate the facet prefix
		super(CONSTRAINT_NAME);
	}

	@Creator
	public FacetIncludingChildrenExcept(@Nonnull @Child(domain = ConstraintDomain.ENTITY) FilterConstraint child) {
		super(CONSTRAINT_NAME, child);
	}

	/**
	 * Returns the single child filter constraint, or `null` if none is present.
	 */
	@Nullable
	public FilterConstraint getChild() {
		final FilterConstraint[] children = getChildren();
		return children.length == 0 ? null : children[0];
	}

	@Override
	public boolean isApplicable() {
		return true;
	}

	@Override
	public boolean isNecessary() {
		return true;
	}

	@Nonnull
	@Override
	public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		throw new UnsupportedOperationException("FacetIncludingChildrenExcept filtering constraint has no arguments!");
	}

	@Nonnull
	@Override
	public FilterConstraint getCopyWithNewChildren(
		@Nonnull FilterConstraint[] children,
		@Nonnull Constraint<?>[] additionalChildren
	) {
		Assert.isPremiseValid(
			ArrayUtils.isEmpty(additionalChildren),
			"FacetIncludingChildrenExcept cannot have additional children."
		);
		Assert.isPremiseValid(
			children.length <= 1,
			"FacetIncludingChildrenExcept can have only one child."
		);
		return children.length == 0 ? new FacetIncludingChildrenExcept() : new FacetIncludingChildrenExcept(children[0]);
	}
}