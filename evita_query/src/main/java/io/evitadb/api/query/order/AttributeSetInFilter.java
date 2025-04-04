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

import io.evitadb.api.query.AttributeConstraint;
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.Classifier;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.ConstraintSupportedValues;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.api.query.filter.AttributeInSet;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;

/**
 * The constraint allows to sort output entities by attribute values in the exact order that was used for filtering
 * them. The constraint requires presence of exactly one {@link AttributeInSet} constraint in filter part of the query
 * that relates to the attribute with the same name as is used in the first argument of this constraint.
 * It uses {@link AttributeInSet#getAttributeValues()} array for sorting the output of the query.
 *
 * Example usage:
 *
 * <pre>
 * query(
 *    filterBy(
 *       attributeInSet("code", "t-shirt", "sweater", "pants")
 *    ),
 *    orderBy(
 *       attributeSetInFilter()
 *    )
 * )
 * </pre>
 *
 * The example will return the selected entities (if present) in the exact order of their attribute `code` that was used
 * for array filtering them. The ordering constraint is particularly useful when you have sorted set of attribute values
 * from an external system which needs to be maintained (for example, it represents a relevancy of those entities).
 *
 * <p><a href="https://evitadb.io/documentation/query/ordering/constant#exact-entity-attribute-value-order-used-in-filter">Visit detailed user documentation</a></p>
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "setInFilter",
	shortDescription = "The constraint sorts returned entities by ordering of the values specified `attributeInSet` in filter sharing the same attribute name.",
	userDocsLink = "/documentation/query/ordering/constant#exact-entity-attribute-value-order-used-in-filter",
	supportedIn = { ConstraintDomain.ENTITY },
	supportedValues = @ConstraintSupportedValues(allTypesSupported = true)
)
public class AttributeSetInFilter extends AbstractOrderConstraintLeaf implements AttributeConstraint<OrderConstraint> {
	@Serial private static final long serialVersionUID = -8627803791652731430L;

	private AttributeSetInFilter(Serializable... arguments) {
		super(arguments);
	}

	@Creator()
	public AttributeSetInFilter(@Nonnull @Classifier String attributeName) {
		super(new Serializable[] { attributeName });
	}

	/**
	 * Returns name of the attribute the values relate to.
	 */
	@Nonnull
	public String getAttributeName() {
		return String.valueOf(getArguments()[0]);
	}

	@Nonnull
	@Override
	public OrderConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		Assert.isTrue(
			newArguments.length == 1 && newArguments[0] instanceof String,
			"Expected exactly one argument of String type in constraint AttributeSetInFilter."
		);
		return new AttributeSetInFilter(newArguments);
	}
}
