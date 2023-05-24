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

import io.evitadb.api.query.EntityConstraint;
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.ConstraintSupportedValues;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.api.query.descriptor.annotation.Value;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;

/**
 * TODO JNO - document me
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
/* TODO LHO - review */
@ConstraintDefinition(
	name = "setInFilter",
	shortDescription = "The constraint sorts returned entities by ordering of the values specified `attributeInSet` in filter sharing the same attribute name.",
	supportedIn = { ConstraintDomain.ENTITY },
	supportedValues = @ConstraintSupportedValues(allTypesSupported = true)
)
public class AttributeSetInFilter extends AbstractOrderConstraintLeaf implements EntityConstraint<OrderConstraint> {
	@Serial private static final long serialVersionUID = -8627803791652731430L;

	private AttributeSetInFilter(Serializable... arguments) {
		super(arguments);
	}

	@Creator()
	public AttributeSetInFilter(@Nonnull @Value String attributeName) {
		super(attributeName);
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
