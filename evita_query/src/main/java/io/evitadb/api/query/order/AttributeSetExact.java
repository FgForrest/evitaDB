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
import io.evitadb.utils.ArrayUtils;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;

/**
 * The constraint allows output entities to be sorted by attribute values in the exact order specified in the 2nd through
 * Nth arguments of this constraint.
 *
 * Example usage:
 *
 * <pre>
 * query(
 *    filterBy(
 *       attributeEqualsTrue("shortcut")
 *    ),
 *    orderBy(
 *       attributeSetExact("code", "t-shirt", "sweater", "pants")
 *    )
 * )
 * </pre>
 *
 * The example will return the selected entities (if present) in the exact order of their `code` attributes that is
 * stated in the second to Nth argument of this ordering constraint. If there are entities, that have not the attribute
 * `code` , then they will be present at the end of the output in ascending order of their primary keys (or they will be
 * sorted by additional ordering constraint in the chain).
 *
 * <p><a href="https://evitadb.io/documentation/query/ordering/constant#exact-entity-attribute-value-order">Visit detailed user documentation</a></p>
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "setExact",
	shortDescription = "The constraint sorts returned entities by ordering of the values specified in arguments matching the entity attribute of specified name.",
	userDocsLink = "/documentation/query/ordering/constant#exact-entity-attribute-value-order",
	supportedIn = { ConstraintDomain.ENTITY, ConstraintDomain.REFERENCE, ConstraintDomain.INLINE_REFERENCE },
	supportedValues = @ConstraintSupportedValues(allTypesSupported = true)
)
public class AttributeSetExact extends AbstractOrderConstraintLeaf implements AttributeConstraint<OrderConstraint> {
	@Serial private static final long serialVersionUID = -8627803791652731430L;

	private AttributeSetExact(Serializable... arguments) {
		super(arguments);
	}

	@Creator()
	public AttributeSetExact(
		@Nonnull @Classifier String attributeName,
		@Nonnull Serializable... attributeValues
	) {
		super(
			ArrayUtils.mergeArrays(
				new Serializable[] { attributeName },
				attributeValues
			)
		);
	}

	/**
	 * Returns name of the attribute the values relate to.
	 */
	@Nonnull
	public String getAttributeName() {
		return String.valueOf(getArguments()[0]);
	}

	/**
	 * Returns attribute values to sort along.
	 */
	@Nonnull
	public Serializable[] getAttributeValues() {
		final Serializable[] args = getArguments();
		return Arrays.copyOfRange(args, 1, args.length);
	}

	@Override
	public boolean isApplicable() {
		return isArgumentsNonNull() && getArguments().length > 1;
	}

	@Nonnull
	@Override
	public OrderConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new AttributeSetExact(newArguments);
	}
}
