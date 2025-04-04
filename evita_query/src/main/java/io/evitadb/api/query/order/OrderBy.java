/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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
import io.evitadb.api.query.GenericConstraint;
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;

/**
 * This `orderBy` is container for ordering. It is mandatory container when any ordering is to be used.
 * evitaDB requires a previously prepared sort index to be able to sort entities. This fact makes sorting much faster
 * than ad-hoc sorting by attribute value. Also, the sorting mechanism of evitaDB is somewhat different from what you
 * might be used to. If you sort entities by two attributes in an orderBy clause of the query, evitaDB sorts them first
 * by the first attribute (if present) and then by the second (but only those where the first attribute is missing).
 * If two entities have the same value of the first attribute, they are not sorted by the second attribute, but by the
 * primary key (in ascending order). If we want to use fast "pre-sorted" indexes, there is no other way to do it,
 * because the secondary order would not be known until a query time.
 *
 * This default sorting behavior by multiple attributes is not always desirable, so evitaDB allows you to define
 * a sortable attribute compound, which is a virtual attribute composed of the values of several other attributes.
 * evitaDB also allows you to specify the order of the "pre-sorting" behavior (ascending/descending) for each of these
 * attributes, and also the behavior for NULL values (first/last) if the attribute is completely missing in the entity.
 * The sortable attribute compound is then used in the orderBy clause of the query instead of specifying the multiple
 * individual attributes to achieve the expected sorting behavior while maintaining the speed of the "pre-sorted"
 * indexes.
 *
 * Example:
 *
 * <pre>
 * orderBy(
 *     ascending("code"),
 *     ascending("create"),
 *     priceDescending()
 * )
 * </pre>
 *
 * <p><a href="https://evitadb.io/documentation/query/basics#order-by">Visit detailed user documentation</a></p>
 *
 * @author Jan Novotný, FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "orderBy",
	shortDescription = "The container encapsulates inner order constraints into one main constraint that is required by the query.",
	userDocsLink = "/documentation/query/basics#order-by",
	supportedIn = { ConstraintDomain.GENERIC, ConstraintDomain.ENTITY, ConstraintDomain.INLINE_REFERENCE, ConstraintDomain.SEGMENT }
)
public class OrderBy extends AbstractOrderConstraintContainer implements GenericConstraint<OrderConstraint> {
	@Serial private static final long serialVersionUID = 6352220342769661652L;

	@Creator
	public OrderBy(@Nonnull OrderConstraint... children) {
		super(children);
	}

	@Nullable
	public OrderConstraint getChild() {
		final OrderConstraint[] children = getChildren();
		Assert.isPremiseValid(
			children.length <= 1,
			"OrderBy ordering query has more than one child!"
		);
		return children.length == 1 ? children[0] : null;
	}

	@Nonnull
	@Override
	public OrderConstraint getCopyWithNewChildren(@Nonnull OrderConstraint[] children, @Nonnull Constraint<?>[] additionalChildren) {
		Assert.isPremiseValid(
			additionalChildren.length == 0,
			"OrderBy ordering query allows no additional children!"
		);
		return new OrderBy(children);
	}

	@Override
	public boolean isNecessary() {
		return isApplicable();
	}

	@Nonnull
	@Override
	public OrderConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		throw new UnsupportedOperationException("OrderBy ordering query has no arguments!");
	}
}
