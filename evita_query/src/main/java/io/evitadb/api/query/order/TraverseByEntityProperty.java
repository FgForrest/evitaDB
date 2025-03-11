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
 * The `traverseByEntityProperty` ordering constraint can only be used within the {@link ReferenceProperty} ordering
 * constraint, which targets a reference of cardinality 1:N. It allows ordering rules to be defined for traversing
 * multiple references before the {@link ReferenceProperty}  ordering constraint is applied. The behaviour is different
 * for hierarchical and non-hierarchical entities.
 *
 * Consider the following example where we want to list products in the *Accessories* category ordered by the `orderInCategory`
 * attribute on the reference to the category, but the products could either directly reference the *Accessories* category
 * or one of its child categories. The order will first list products directly related to the *Accessories* category in
 * a particular order, then it will start listing products in the child categories in depth-first order. To specify which
 * order to use when traversing the child categories, we can use the `traverseByEntityProperty` ordering constraint:
 *
 * Example:
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
 * </pre>
 *
 * ## Behaviour of zero or one to many references ordering
 *
 * The situation is more complicated when the reference is one-to-many. What is the expected result of a query that
 * involves ordering by a property on a reference attribute? Is it wise to allow such ordering query in this case?
 *
 * We decided to allow it and bind it with the following rules:
 *
 * ### Non-hierarchical entity
 *
 * If the referenced entity is **non-hierarchical**, and the returned entity references multiple entities, only
 * the first reference according to `traverseByEntityProperty` ordering, while also having the order property set,
 * will be used for ordering.
 *
 * ### Hierarchical entity
 *
 * If the referenced entity is **hierarchical** and the returned entity references multiple entities, the reference used
 * for ordering is the one that contains the order property and is the closest hierarchy node to the root of the filtered
 * hierarchy node.
 *
 * It sounds complicated, but it's really quite simple. If you list products of a certain category and at the same time
 * order them by a property "priority" set on the reference to the category, the first products will be those directly
 * related to the category, ordered by "priority", followed by the products of the first child category, and so on,
 * maintaining the depth-first order of the category tree. The order of the child categories is determined by the
 * `traverseByEntityProperty` ordering constraint.
 *
 * <p><a href="https://evitadb.io/documentation/query/ordering/reference#traverse-by-entity-property">Visit detailed user documentation</a></p>
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "property",
	shortDescription = "The constraint defines order of the 1:N references traversal before the ordering is applied.",
	userDocsLink = "/documentation/query/ordering/reference#traverse-by-entity-property",
	supportedIn = { ConstraintDomain.REFERENCE }
)
public class TraverseByEntityProperty extends AbstractOrderConstraintContainer implements ReferenceConstraint<OrderConstraint> {
	@Serial private static final long serialVersionUID = -4940847050046564050L;

	@Creator
	public TraverseByEntityProperty(@Nonnull @Child OrderConstraint... children) {
		super(children);
	}

	@Nonnull
	@Override
	public OrderConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		throw new UnsupportedOperationException(
			"TraverseByEntityProperty does not support cloning with new arguments!"
		);
	}

	@Override
	public boolean isNecessary() {
		return getChildren().length >= 1;
	}

	@Nonnull
	@Override
	public OrderConstraint getCopyWithNewChildren(@Nonnull OrderConstraint[] children, @Nonnull Constraint<?>[] additionalChildren) {
		Assert.isTrue(
			additionalChildren.length == 0,
			"TraverseByEntityProperty does not support additional children!"
		);
		return new TraverseByEntityProperty(children);
	}
}