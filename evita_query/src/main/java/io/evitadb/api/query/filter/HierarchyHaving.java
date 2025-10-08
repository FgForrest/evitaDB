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
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.Child;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;

/**
 * The constraint `having` is a constraint that can only be used within {@link HierarchyWithin} or
 * {@link HierarchyWithinRoot} parent constraints. It simply makes no sense anywhere else because it changes the default
 * behavior of those constraints. Hierarchy constraints return all hierarchy children of the parent node or entities
 * that are transitively or directly related to them, and the parent node itself.
 *
 * The `having` constraint allows you to set a constraint that must be fulfilled by each hierarchical entity in while
 * traversing through the hierarchical tree from top to down to be accepted by the filter. This constraint is especially
 * useful if you want to conditionally display certain parts of the tree. Imagine you have a category Christmas Sale
 * that should only be available during a certain period of the year, or a category B2B Partners that should only be
 * accessible to a certain role of users. All of these scenarios can take advantage of the having constraint (but there
 * are other approaches to solving the above use cases).
 *
 * The constraint accepts the following arguments:
 *
 * - one or more mandatory constraints that must be satisfied by all returned hierarchy nodes and that mark the visible
 *   part of the tree, the implicit relation between constraints is logical conjunction (boolean AND)
 *
 * When the hierarchy constraint targets the hierarchy entity, the children that don't satisfy the inner constraints
 * (and their children, whether they satisfy them or not) are excluded from the result.
 *
 * For demonstration purposes, let's list all categories within the Accessories category, but only those that are valid
 * at 01:00 AM on October 1, 2023.
 *
 * <pre>
 * query(
 *     collection('Category'),
 *     filterBy(
 *         hierarchyWithinSelf(
 *             attributeEquals('code', 'accessories'),
 *             having(
 *                 or(
 *                     attributeIsNull('validity'),
 *                     attributeInRange('validity', 2023-10-01T01:00:00-01:00)
 *                 )
 *             )
 *         )
 *     ),
 *     require(
 *         entityFetch(
 *             attributeContent('code')
 *         )
 *     )
 * )
 * </pre>
 *
 * Because the category Christmas electronics has its validity set to be valid only between December 1st and December
 * 24th, it will be omitted from the result. If it had subcategories, they would also be omitted (even if they had no
 * validity restrictions).
 *
 * If the hierarchy constraint targets a non-hierarchical entity that references the hierarchical one (typical example
 * is a product assigned to a category), the having constraint is evaluated against the hierarchical entity (category),
 * but affects the queried non-hierarchical entities (products). It excludes all products referencing categories that
 * don't satisfy the having inner constraints.
 *
 * Let's use again our example with Christmas electronics that is valid only between 1st and 24th December. To list all
 * products available at 01:00 AM on October 1, 2023, issue a following query:
 *
 * <pre>
 * query(
 *     collection("Product"),
 *     filterBy(
 *         hierarchyWithin(
 *             "categories",
 *             attributeEquals("code", "accessories"),
 *             having(
 *                 or(
 *                     attributeIsNull("validity"),
 *                     attributeInRange("validity", 2023-10-01T01:00:00-01:00)
 *                 )
 *             )
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
 * You can see that Christmas products like Retlux Blue Christmas lightning, Retlux Warm white Christmas lightning or
 * Emos Candlestick are not present in the listing.
 *
 * <strong>The lookup stops at the first node that doesn't satisfy the constraint!</strong>
 *
 * The hierarchical query traverses from the root nodes to the leaf nodes. For each of the nodes, the engine checks
 * whether the having constraint is still valid, and if not, it excludes that hierarchy node and all of its child nodes
 * (entire subtree).
 *
 * <strong>What if the product is linked to two categories - one that meets the constraint and one that does not?</strong>
 *
 * In the situation where the single product, let's say Garmin Vivosmart 5, is in both the excluded category Christmas
 * Electronics and the included category Smartwatches, it will remain in the query result because there is at least one
 * product reference that is part of the visible part of the tree.
 *
 * <p><a href="https://evitadb.io/documentation/query/filtering/hierarchy#having">Visit detailed user documentation</a></p>
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@ConstraintDefinition(
	name = "having",
	shortDescription = "The constraint narrows the hierarchy within a parent constraint to include only the subtrees that satisfy the inner filter constraint.",
	userDocsLink = "/documentation/query/filtering/hierarchy#having",
	supportedIn = ConstraintDomain.HIERARCHY
)
public class HierarchyHaving extends AbstractFilterConstraintContainer implements HierarchySpecificationFilterConstraint {
	@Serial private static final long serialVersionUID = -6950287451642746676L;
	private static final String CONSTRAINT_NAME = "having";

	@Creator
	public HierarchyHaving(@Nonnull @Child(domain = ConstraintDomain.ENTITY) FilterConstraint... filtering) {
		super(CONSTRAINT_NAME, NO_ARGS, filtering);
	}

	/**
	 * Returns filtering constraints that return entities whose trees should be excluded from {@link HierarchyWithin}
	 * query.
	 */
	@Nonnull
	public FilterConstraint[] getFiltering() {
		return getChildren();
	}

	@Override
	public boolean isNecessary() {
		return getChildren().length > 0;
	}

	@Override
	public boolean isApplicable() {
		return getChildren().length > 0;
	}

	@Nonnull
	@Override
	public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return this;
	}

	@Nonnull
	@Override
	public FilterConstraint getCopyWithNewChildren(@Nonnull FilterConstraint[] children, @Nonnull Constraint<?>[] additionalChildren) {
		Assert.isTrue(
			ArrayUtils.isEmpty(additionalChildren),
			"Constraint HierarchyHaving doesn't accept other than filtering constraints!"
		);
		return new HierarchyHaving(children);
	}
}
