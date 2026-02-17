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
import io.evitadb.api.query.GenericConstraint;
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.Child;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.api.query.require.ReferenceContent;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;

/**
 * The `orderGroupBy` is a container for ordering constraints applied to facet group entities within
 * {@link ReferenceContent} requirement. While `orderBy` controls the order of referenced entities themselves,
 * `orderGroupBy` controls the order of the groups under which those references are aggregated.
 *
 * This is useful when you want to control how reference groups (e.g., Parameter groups containing ParameterValue
 * references) are sorted in the output, independently of how individual references within each group are sorted.
 *
 * Example:
 *
 * ```evitaql
 * query(
 *     collection("Product"),
 *     filterBy(
 *         attributeEquals("code", "garmin-vivoactive-4"),
 *         entityLocaleEquals("en")
 *     ),
 *     require(
 *         entityFetch(
 *             attributeContent("code"),
 *             referenceContent(
 *                 "parameterValues",
 *                 orderBy(
 *                     entityGroupProperty(
 *                         attributeNatural("name", ASC)
 *                     ),
 *                     entityProperty(
 *                         attributeNatural("name", ASC)
 *                     )
 *                 ),
 *                 entityFetch(
 *                     attributeContent("name")
 *                 ),
 *                 entityGroupFetch(
 *                     attributeContent("name")
 *                 )
 *             )
 *         )
 *     )
 * )
 * ```
 *
 * [Visit detailed user documentation](https://evitadb.io/documentation/query/basics#order-by)
 *
 * @author Jan Novotný, FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "orderGroupBy",
	shortDescription = "Container for ordering constraints that control the sort order of reference groups within reference content.",
	userDocsLink = "/documentation/query/basics#order-by",
	supportedIn = { ConstraintDomain.REFERENCE, ConstraintDomain.INLINE_REFERENCE }
)
public class OrderGroupBy extends AbstractOrderConstraintContainer implements GenericConstraint<OrderConstraint> {
	@Serial private static final long serialVersionUID = -309362096093370813L;

	@Creator
	public OrderGroupBy(@Nonnull @Child OrderConstraint... children) {
		super(children);
	}

	@Nullable
	public OrderConstraint getChild() {
		return getChildrenCount() == 0 ? null : getChildren()[0];
	}

	@Nonnull
	@Override
	public OrderConstraint getCopyWithNewChildren(
		@Nonnull OrderConstraint[] children,
		@Nonnull Constraint<?>[] additionalChildren
	) {
		return new OrderGroupBy(children);
	}

	@Override
	public boolean isNecessary() {
		return isApplicable();
	}

	@Nonnull
	@Override
	public OrderConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		throw new UnsupportedOperationException("OrderGroupBy ordering query has no arguments!");
	}
}
