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
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.ReferenceConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.Child;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;

/**
 * The `pickFirstByEntityProperty` ordering constraint can only be used within the {@link ReferenceProperty} ordering
 * constraint and makes sense only if the reference has cardinality 1:N. It allows to define which of the multiple
 * references will be picked and examined for a property that will be used for ordering.
 *
 * Consider the following example where we want to list products with a reference to "main" stock and sort them by
 * the `quantityOnStock` attribute on the reference to the stock. The products may have multiple references to different
 * stocks, but we want to use only the one that is referenced by the `main` reference. This query will return products
 * for this situation:
 *
 * Example:
 *
 * ```evitaql
 * query(
 *     collection("Product"),
 *     filterBy(
 *         referenceHaving(
 *             "stocks",
 *             attributeEquals("code", "main")
 *         )
 *     ),
 *     orderBy(
 *          referenceProperty(
 *              "stocks",
 *              pickFirstByEntityProperty(
 *                  attributeSetExact("code", "main")
 *              ),
 *              attributeNatural("quantityOnStock", DESC)
 *          )
 *     ),
 *     require(
 *         entityFetch(
 *             attributeContent("code"),
 *             referenceContentWithAttributes(
 *                "stocks",
 *                attributeContent("quantityOnStock")
 *             )
 *         )
 *     )
 * )
 * ```
 *
 * Constraint `pickFirstByEntityProperty` accepts ordering constraints as its arguments, orders the entity references by
 * them and picks the first reference whose attribute `quantityOnStock` will be used for ordering the main entity. In
 * this case it uses the explicit ordering where the `main` reference is ordered first and the other references are
 * ordered by their primary key in ascending order. Since only the first reference matters, the other entity references
 * are ignored.
 *
 * This constraint implements {@link ReferenceOrderingSpecification} and is therefore **mutually exclusive** with
 * {@link TraverseByEntityProperty} — at most one may appear as a direct child of {@link ReferenceProperty}.
 * If neither is present, {@link ReferenceProperty} applies a built-in default that mimics
 * `pickFirstByEntityProperty(primaryKeyNatural(ASC))` for non-hierarchical references. Use this constraint
 * explicitly only when you need a different pick order (e.g. to prefer a named reference over others).
 *
 * For hierarchical references where the ordering should follow the entity tree structure, use
 * {@link TraverseByEntityProperty} instead.
 *
 * @see ReferenceProperty
 * @see ReferenceOrderingSpecification
 * @see TraverseByEntityProperty
 *
 * [Visit detailed user documentation](https://evitadb.io/documentation/query/ordering/reference#pick-first-by-entity-property)
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "pickFirstByEntityProperty",
	shortDescription = "For one-to-many references, picks the first reference according to a specified ordering and uses it as the sort key.",
	userDocsLink = "/documentation/query/ordering/reference#pick-first-by-entity-property",
	supportedIn = { ConstraintDomain.REFERENCE }
)
public class PickFirstByEntityProperty extends AbstractOrderConstraintContainer
	implements ReferenceConstraint<OrderConstraint>,
	ReferenceOrderingSpecification
{
	@Serial
	private static final long serialVersionUID = 6947885672916582291L;

	@Creator
	public PickFirstByEntityProperty(@Nonnull @Child(domain = ConstraintDomain.ENTITY) OrderConstraint... orderBy) {
		super(orderBy);
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
	public OrderConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		throw new UnsupportedOperationException(
			"PickFirstByEntityProperty does not support cloning with new arguments!"
		);
	}

	@Override
	public boolean isNecessary() {
		return getChildren().length >= 1;
	}

	@Nonnull
	@Override
	public OrderConstraint getCopyWithNewChildren(
		@Nonnull OrderConstraint[] children,
		@Nonnull Constraint<?>[] additionalChildren
	) {
		Assert.isTrue(
			additionalChildren.length == 0,
			"PickFirstByEntityProperty does not support additional children!"
		);
		return new PickFirstByEntityProperty(children);
	}
}