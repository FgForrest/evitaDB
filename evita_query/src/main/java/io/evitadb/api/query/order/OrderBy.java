/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
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
import io.evitadb.api.query.descriptor.annotation.ConstraintChildrenParamDef;
import io.evitadb.api.query.descriptor.annotation.ConstraintCreatorDef;
import io.evitadb.api.query.descriptor.annotation.ConstraintDef;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;

/**
 * This `orderBy` is container for ordering. It is mandatory container when any ordering is to be used. Ordering
 * process is as follows:
 *
 * - first ordering evaluated, entities missing requested attribute value are excluded to intermediate bucket
 * - next ordering is evaluated using entities present in an intermediate bucket, entities missing requested attribute
 *   are excluded to new intermediate bucket
 * - second step is repeated until all orderings are processed
 * - content of the last intermediate bucket is appended to the result ordered by the primary key in ascending order
 *
 * Entities with same (equal) values must not be subject to secondary ordering rules and may be sorted randomly within
 * the scope of entities with the same value (this is subject to change, currently this behaviour differs from the one
 * used by relational databases - but might be more performant).
 *
 * Example:
 *
 * ```
 * orderBy(
 *     ascending('code'),
 *     ascending('create'),
 *     priceDescending()
 * )
 * ```
 *
 * @author Jan Novotný, FG Forrest a.s. (c) 2021
 */
@ConstraintDef(
	name = "orderBy",
	shortDescription = "The container encapsulates inner order constraints into one main constraint that is required by the query."
)
public class OrderBy extends AbstractOrderConstraintContainer implements GenericConstraint<OrderConstraint> {
	@Serial private static final long serialVersionUID = 6352220342769661652L;

	@ConstraintCreatorDef
	public OrderBy(@Nonnull @ConstraintChildrenParamDef OrderConstraint... children) {
		super(children);
	}

	@Nullable
	public OrderConstraint getChild() {
		return getChildrenCount() == 0 ? null : getChildren()[0];
	}

	@Nonnull
	@Override
	public OrderConstraint getCopyWithNewChildren(@Nonnull Constraint<?>[] children, @Nonnull Constraint<?>[] additionalChildren) {
		final OrderConstraint[] orderChildren = Arrays.stream(children)
				.map(c -> (OrderConstraint) c)
				.toArray(OrderConstraint[]::new);
		return new OrderBy(orderChildren);
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
