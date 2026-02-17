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

package io.evitadb.api.query.order;

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.ConstraintWithDefaults;
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.ReferenceConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.Child;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;

/**
 * The `traverseByEntityProperty` ordering constraint can only be used within the {@link ReferenceProperty} ordering
 * constraint. It changes the behaviour of the ordering rules in a way that first the result is ordered by the
 * referenced entity property and within the same referenced entity the main entity is ordered by the reference
 * property. This constraint is particularly useful when the reference is one-to-many and the referenced entity
 * is hierarchical.
 *
 * Consider the following example where we want to list products in the *Accessories* category ordered by the
 * `orderInCategory` attribute on the reference to the category, but the products could either directly reference
 * the *Accessories* category or one of its child categories. The order will first list products directly related
 * to the *Accessories* category in a particular order, then it will start listing products in the child
 * categories in depth-first order. To specify which
 * order to use when traversing the child categories, we can use the `traverseByEntityProperty` ordering constraint:
 *
 * Example:
 *
 * ```evitaql
 * query(
 *     collection("Product"),
 *     filterBy(
 *         hierarchyWithin(
 *             "categories",
 *             attributeEquals("code", "accessories")
 *         )
 *     ),
 *      orderBy(
 *          referenceProperty(
 *              "categories",
 *              traverseByEntityProperty(
 *                  attributeNatural("order", ASC)
 *              ),
 *              attributeNatural("orderInCategory", ASC)
 *          )
 *      ),
 *      require(
 *          entityFetch(
 *              attributeContent("code"),
 *              referenceContentWithAttributes(
 *                 "categories",
 *                 attributeContent("orderInCategory")
 *              )
 *          )
 *      )
 * )
 * ```
 *
 * You can also change the depth-first traversal to breadth-first traversal by declaring optional first argument as
 * follows:
 *
 * ```evitaql
 * referenceProperty(
 *     "categories",
 *     traverseByEntityProperty(
 *         BREADTH_FIRST, attributeNatural("order", ASC)
 *     ),
 *     attributeNatural("orderInCategory", ASC)
 * )
 * ```
 *
 * This constraint implements {@link ReferenceOrderingSpecification} and is therefore **mutually exclusive** with
 * {@link PickFirstByEntityProperty} — at most one may appear as a direct child of {@link ReferenceProperty}.
 * If neither is present, {@link ReferenceProperty} applies a built-in default that already mimics
 * `traverseByEntityProperty(DEPTH_FIRST, primaryKeyNatural(ASC))` for hierarchical references. Use this constraint
 * explicitly only when you need a different traversal mode or a different node-ordering criterion.
 *
 * For non-hierarchical references where you want to pick a specific reference as the sort key, use
 * {@link PickFirstByEntityProperty} instead.
 *
 * @see ReferenceProperty
 * @see ReferenceOrderingSpecification
 * @see PickFirstByEntityProperty
 * @see TraversalMode
 *
 * [Visit detailed user documentation](https://evitadb.io/documentation/query/ordering/reference#traverse-by-entity-property)
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "traverseByEntityProperty",
	shortDescription = "For hierarchical 1:N references, traverses the referenced entity tree and orders within each node by the reference property.",
	userDocsLink = "/documentation/query/ordering/reference#traverse-by-entity-property",
	supportedIn = { ConstraintDomain.REFERENCE }
)
public class TraverseByEntityProperty extends AbstractOrderConstraintContainer
	implements ConstraintWithDefaults<OrderConstraint>,
	ReferenceConstraint<OrderConstraint>,
	ReferenceOrderingSpecification
{
	@Serial
	private static final long serialVersionUID = -4940847050046564050L;

	private TraverseByEntityProperty(@Nonnull Serializable[] arguments, @Nonnull OrderConstraint[] children) {
		super(arguments, children);
	}

	@Creator
	public TraverseByEntityProperty(
		@Nullable TraversalMode traversalMode,
		@Nonnull @Child(domain = ConstraintDomain.ENTITY) OrderConstraint... orderBy
	) {
		super(new Serializable[] { traversalMode == null ? TraversalMode.DEPTH_FIRST : traversalMode }, orderBy);
	}

	/**
	 * Returns the {@link TraversalMode} that defines how 1:N references are traversed before the ordering
	 * is applied.
	 *
	 * @return the {@link TraversalMode} that defines how 1:N references are traversed before the ordering
	 * is applied.
	 * @see TraversalMode
	 */
	@Nonnull
	public TraversalMode getTraversalMode() {
		return (TraversalMode) getArguments()[0];
	}

	/**
	 * Returns the array of {@link OrderConstraint} elements used to define ordering constraints.
	 *
	 * @return an array of {@link OrderConstraint} elements.
	 */
	@Nonnull
	public OrderConstraint[] getOrderBy() {
		return super.getChildren();
	}

	@Nonnull
	@Override
	public Serializable[] getArgumentsExcludingDefaults() {
		return Arrays.stream(getArguments())
			.filter(it -> it != TraversalMode.DEPTH_FIRST)
			.toArray(Serializable[]::new);
	}

	@Override
	public boolean isArgumentImplicit(@Nonnull Serializable serializable) {
		return serializable == TraversalMode.DEPTH_FIRST;
	}

	@Nonnull
	@Override
	public OrderConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new TraverseByEntityProperty(newArguments, getChildren());
	}

	@Override
	public boolean isNecessary() {
		return getArguments().length == 1 && getChildren().length >= 1;
	}

	@Nonnull
	@Override
	public OrderConstraint getCopyWithNewChildren(
		@Nonnull OrderConstraint[] children,
		@Nonnull Constraint<?>[] additionalChildren
	) {
		Assert.isTrue(
			additionalChildren.length == 0,
			"TraverseByEntityProperty does not support additional children!"
		);
		return new TraverseByEntityProperty(getArguments(), children);
	}
}