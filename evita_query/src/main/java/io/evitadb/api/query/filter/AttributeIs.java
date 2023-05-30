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

package io.evitadb.api.query.filter;

import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.Classifier;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.ConstraintSupportedValues;
import io.evitadb.api.query.descriptor.annotation.Value;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;

/**
 * This `attributeIs` is query that checks attribute for "special" value or constant that cannot be compared through
 * {@link Comparable} of attribute with name passed in first argument.
 * First argument must be {@link String}. Second is one of the {@link AttributeSpecialValue special values}:
 *
 * - NULL
 * - NOT_NULL
 *
 * Function returns true if attribute has (explicitly or implicitly) passed special value.
 *
 * Example:
 *
 * ```
 * attributeIs('visible', NULL)
 * ```
 *
 * Function supports attribute arrays in the same way as plain values.
 *
 * @see AttributeSpecialValue
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "is",
	shortDescription = "The constraint if value of the attribute is same as passed special value.",
	supportedIn = { ConstraintDomain.ENTITY, ConstraintDomain.REFERENCE },
	supportedValues = @ConstraintSupportedValues(allTypesSupported = true, arraysSupported = true)
)
public class AttributeIs extends AbstractAttributeFilterConstraintLeaf implements IndexUsingConstraint {
	@Serial private static final long serialVersionUID = 6615086027607982158L;

	private AttributeIs(Serializable... arguments) {
		super(arguments);
	}

	@Creator
	public AttributeIs(@Nonnull @Classifier String attributeName,
	                   @Nonnull @Value AttributeSpecialValue attributeSpecialValue) {
		super(attributeName, attributeSpecialValue);
	}

	/**
	 * Returns attribute special value that attribute must have (explicitly or implicitly).
	 */
	@Nonnull
	public AttributeSpecialValue getAttributeSpecialValue() {
		return (AttributeSpecialValue) getArguments()[1];
	}

	@Override
	public boolean isApplicable() {
		return isArgumentsNonNull() && getArguments().length == 2;
	}

	@Nonnull
	@Override
	public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new AttributeIs(newArguments);
	}
}
