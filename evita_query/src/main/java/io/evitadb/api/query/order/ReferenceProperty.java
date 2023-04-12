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
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.ReferenceConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.Child;
import io.evitadb.api.query.descriptor.annotation.Classifier;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;

/**
 * This `referenceAttribute` container is ordering that sorts returned entities by reference attributes. Ordering is
 * specified by inner constraints. Price related orderings cannot be used here, because references don't posses of prices.
 *
 * Example:
 *
 * ```
 * referenceAttribute(
 * 'CATEGORY',
 * ascending('categoryPriority')
 * )
 * ```
 *
 * or
 *
 * ```
 * referenceAttribute(
 * 'CATEGORY',
 * ascending('categoryPriority'),
 * descending('stockPriority')
 * )
 * ```
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "property",
	shortDescription = "The constraint sorts returned entities or references by attribute specified on its reference in natural order.",
	supportedIn = { ConstraintDomain.ENTITY }
)
public class ReferenceProperty extends AbstractOrderConstraintContainer implements ReferenceConstraint<OrderConstraint> {
	@Serial private static final long serialVersionUID = -8564699361608364992L;

	private ReferenceProperty(Serializable[] arguments, OrderConstraint... children) {
		super(arguments, children);
	}

	@Creator
	public ReferenceProperty(@Nonnull @Classifier String referenceName,
	                         @Nonnull @Child OrderConstraint... children) {
		super(referenceName, children);
	}

	/**
	 * Returns reference name of the facet that should be used for applying for ordering according to children constraints.
	 */
	@Nonnull
	public String getReferenceName() {
		return (String) getArguments()[0];
	}

	@Nonnull
	@Override
	public OrderConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new ReferenceProperty(newArguments, getChildren());
	}

	@Override
	public boolean isNecessary() {
		return getChildren().length >= 1;
	}

	@Nonnull
	@Override
	public OrderConstraint getCopyWithNewChildren(@Nonnull Constraint<?>[] children, @Nonnull Constraint<?>[] additionalChildren) {
		final OrderConstraint[] orderChildren = Arrays.stream(children)
				.map(c -> (OrderConstraint) c)
				.toArray(OrderConstraint[]::new);
		return new ReferenceProperty(getReferenceName(), orderChildren);
	}
}
