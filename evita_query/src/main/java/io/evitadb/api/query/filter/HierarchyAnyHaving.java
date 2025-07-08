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
 * The constraint `anyHaving` is a constraint that can only be used within {@link HierarchyWithin} or
 * {@link HierarchyWithinRoot} parent constraints. It simply makes no sense anywhere else because it changes the default
 * behavior of those constraints. Hierarchy constraints return all hierarchy children of the parent node or entities
 * that are transitively or directly related to them, and the parent node itself.
 *
 * The `anyHaving` constraint allows you to set a constraint that must be fulfilled by at least one nested (child)
 * hierarchical entity to be accepted by the filter. Imagine you want to have a category tree, and you want to verify
 * if certain categories anywhere in the tree contain directly or transitively via their subcategories at least one
 * valid product. This situation can be solved by using the `anyHaving` constraint in your query.
 *
 * The constraint accepts following arguments:
 *
 * - one or more mandatory constraints that must be satisfied by at least one child node of the examined hierarchy node
 *   or directly by that examined hierarchy node, the implicit relation between constraints is logical conjunction
 *   (boolean AND)
 *
 * When the hierarchy constraint targets the hierarchy entity, the children having no child satisfying the inner
 * constraints are excluded from the result.
 *
 * As an example, let's write the query for the above-defined situation.
 *
 * <pre>
 * query(
 *     collection("Category"),
 *     filterBy(
 *         hierarchyWithinSelf(
 *             entityPrimaryKeyIn(1, 2, 3),
 *             having(
 *                 attributeEquals("status", "ACTIVE")
 *             ),
 *             anyHaving(
 *                 referenceHaving(
 *                     entityHaving(
 *                         attributeEquals("status", "ACTIVE")
 *                     )
 *                 )
 *             ),
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
 * The query returns only a subset of categories with primary key 1 or 2 or 3, which either directly of any of their
 * sub-categories labeled as "ACTIVE" or have at least one product that is labeled as "ACTIVE".
 *
 * If the hierarchy constraint targets a non-hierarchical entity that references the hierarchical one (typical example
 * is a product assigned to a category), the having constraint is evaluated against the hierarchical entity (category),
 * but affects the queried non-hierarchical entities (products). It excludes all products referencing categories that
 * don't satisfy the `anyHaving` inner constraints.
 *
 * Let's say that some categories in the tree are labeled as "favorites" and you want to list all products that relate
 * to those categories or any of their parent categories, but only within root category with `code="accessories"`.
 * You can use the `anyHaving` constraint to achieve that:
 *
 * <pre>
 * query(
 *     collection("Product"),
 *     filterBy(
 *         hierarchyWithin(
 *             "categories",
 *             attributeEquals("code", "accessories"),
 *             having(
 *                 attributeEquals("status", "ACTIVE")
 *             ),
 *             anyHaving(
 *                 referenceHaving(
 *                     entityHaving(
 *                         attributeEquals("status", "ACTIVE")
 *                         attributeEquals("favorite", true)
 *                     )
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
 * The query will still consider only categories and products that are labeled as "ACTIVE", but it will also include
 * only those products that relate to the categories that are either labeled "favorite" or are parent of such a category.
 *
 * <p><a href="https://evitadb.io/documentation/query/filtering/hierarchy#anyHaving">Visit detailed user documentation</a></p>
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@ConstraintDefinition(
	name = "anyHaving",
	shortDescription = "The constraint narrows the hierarchy within a parent constraint to include only the subtrees whose at least one member satisfies the inner filter constraint.",
	userDocsLink = "/documentation/query/filtering/hierarchy#anyHaving",
	supportedIn = ConstraintDomain.HIERARCHY
)
public class HierarchyAnyHaving extends AbstractFilterConstraintContainer implements HierarchySpecificationFilterConstraint {
	@Serial private static final long serialVersionUID = -7926794636918674168L;
	private static final String CONSTRAINT_NAME = "anyHaving";

	@Creator
	public HierarchyAnyHaving(@Nonnull @Child(domain = ConstraintDomain.ENTITY) FilterConstraint... filtering) {
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
			"Constraint HierarchyAnyHaving doesn't accept other than filtering constraints!"
		);
		return new HierarchyAnyHaving(children);
	}
}