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
import io.evitadb.api.query.ConstraintWithSuffix;
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
import java.util.Optional;

/**
 * The `includingChildren` constraint modifies the behavior of its parent {@link FacetHaving} constraint to automatically include child
 * entities in hierarchical facet filtering. By default, `facetHaving` only matches entities that have a **direct reference** to the specified
 * faceted entity. When `includingChildren` is present, the query also matches entities that reference **any descendant** of the faceted entity
 * in the hierarchy tree.
 *
 * This constraint can only be used within {@link FacetHaving} and only applies to hierarchical references (references to entity types that
 * have a hierarchical structure). Using `includingChildren` with non-hierarchical references results in a query error.
 *
 * This constraint is a {@link FacetConstraint} and {@link HierarchyReferenceSpecificationFilterConstraint}, marking it as a facet-specific
 * hierarchy modifier. It implements {@link ConstraintWithSuffix}, allowing two syntactic variants: `includingChildren()`
 * and `includingChildrenHaving(...)`.
 *
 * ## Basic Usage (Include All Children)
 *
 * Without additional filtering, `includingChildren` propagates the facet match to all descendants:
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
 *             includingChildren()
 *         )
 *     ),
 *     require(
 *         entityFetch(attributeContent("code"))
 *     )
 * )
 * ```
 *
 * This query matches all products that reference the category with code "accessories" **or any of its subcategories** (e.g., "accessories" →
 * "phone-accessories" → "phone-cases"). The {@link FacetSummary} will include references to any descendant
 * category when computing facet statistics and impact predictions.
 *
 * ## Filtered Child Inclusion (includingChildrenHaving)
 *
 * You can restrict which children are included by using the `Having` suffix variant and providing a filtering constraint. Only child entities
 * that satisfy the constraint will be included:
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
 *             includingChildrenHaving(
 *                 or(
 *                     attributeInRangeNow("validity"),
 *                     attributeIs("validity", NULL)
 *                 )
 *             )
 *         )
 *     ),
 *     require(
 *         entityFetch(attributeContent("code"))
 *     )
 * )
 * ```
 *
 * This query includes only children of "accessories" that have a `validity` date range covering the current date or no validity constraint at
 * all. Children that fail the constraint are excluded from the facet match, even though they are descendants in the hierarchy.
 *
 * ## Hierarchical Facet Navigation
 *
 * The `includingChildren` constraint is critical for hierarchical facet navigation in e-commerce systems. Consider a category tree like:
 *
 * - Electronics
 *   - Computers
 *     - Laptops
 *     - Desktops
 *   - Phones
 *     - Smartphones
 *     - Feature Phones
 *
 * Without `includingChildren`, selecting "Computers" as a facet only matches products **directly tagged** with "Computers", not products
 * tagged with "Laptops" or "Desktops". With `includingChildren`, selecting "Computers" also matches products tagged with any subcategory,
 * providing a more intuitive user experience.
 *
 * ## Facet Summary Integration
 *
 * When used with {@link FacetSummary}, `includingChildren` ensures that facet statistics reflect the hierarchical
 * propagation. For example, if the user selects the "accessories" category facet, the facet summary will show counts for other facets based
 * on products that match "accessories" or any of its subcategories.
 *
 * ## Suffix Support (ConstraintWithSuffix)
 *
 * This constraint implements {@link ConstraintWithSuffix} to support two syntactic variants:
 *
 * - **No suffix**: `includingChildren()` — includes all children.
 * - **"Having" suffix**: `includingChildrenHaving(filterConstraint)` — includes only children that match `filterConstraint`.
 *
 * The suffix is dynamically appended to the constraint name based on whether a child constraint is present.
 *
 * ## Relationship to Other Constraints
 *
 * - {@link FacetHaving}: The parent container; `includingChildren` modifies its hierarchical matching behavior.
 * - {@link FacetIncludingChildrenExcept}: Similar constraint that excludes specific children instead of filtering them.
 * - {@link FacetSummary}: Computes facet statistics; respects `includingChildren` when calculating counts.
 *
 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/references#including-children-having)
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@ConstraintDefinition(
	name = "includingChildren",
	shortDescription = "The constraint automatically selects all children (or their subset satisfying " +
		"additional constraints) of the hierarchical entities matched by `facetHaving` container.",
	userDocsLink = "/documentation/query/filtering/references#including-children-having",
	supportedIn = ConstraintDomain.FACET
)
public class FacetIncludingChildren extends AbstractFilterConstraintContainer
	implements ConstraintWithSuffix, FacetConstraint<FilterConstraint>,
	HierarchyReferenceSpecificationFilterConstraint {
	@Serial private static final long serialVersionUID = -7258410742839628308L;
	private static final String SUFFIX_HAVING = "having";
	private static final String CONSTRAINT_NAME = "includingChildren";

	@Creator
	public FacetIncludingChildren() {
		// because this query can be used only within some other facet query, it would be
		// unnecessary to duplicate the facet prefix
		super(CONSTRAINT_NAME);
	}

	@Creator(suffix = SUFFIX_HAVING)
	public FacetIncludingChildren(@Nonnull @Child(domain = ConstraintDomain.ENTITY) FilterConstraint child) {
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
	public Optional<String> getSuffixIfApplied() {
		return ArrayUtils.isEmpty(getChildren()) ?
			Optional.empty() : Optional.of(SUFFIX_HAVING);
	}

	@Nonnull
	@Override
	public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		throw new UnsupportedOperationException("FacetIncludingChildren filtering constraint has no arguments!");
	}

	@Nonnull
	@Override
	public FilterConstraint getCopyWithNewChildren(
		@Nonnull FilterConstraint[] children,
		@Nonnull Constraint<?>[] additionalChildren
	) {
		Assert.isPremiseValid(
			ArrayUtils.isEmpty(additionalChildren),
			"FacetIncludingChildren cannot have additional children."
		);
		Assert.isPremiseValid(
			children.length <= 1,
			"FacetIncludingChildren can have only one child."
		);
		return children.length == 0 ? new FacetIncludingChildren() : new FacetIncludingChildren(children[0]);
	}
}