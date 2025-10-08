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

package io.evitadb.api.query.filter;

import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.Classifier;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.ConstraintSupportedValues;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.api.query.descriptor.annotation.Value;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;

/**
 * This `lessThanEquals` is query that compares value of the attribute with name passed in first argument with the value passed
 * in the second argument. First argument must be {@link String}, second argument may be any of {@link Comparable} type.
 * Type of the attribute value and second argument must be convertible one to another otherwise `lessThanEquals` function
 * returns false.
 *
 * Function returns true if value in a filterable attribute of such a name is lesser than value in second argument or
 * equal.
 *
 * Function currently doesn't support attribute arrays and when attribute is of array type. Query returns error when this
 * query is used in combination with array type attribute. This may however change in the future.
 *
 * Example:
 *
 * <pre>
 * lessThanEquals("age", 20)
 * </pre>
 *
 * <p><a href="https://evitadb.io/documentation/query/filtering/comparable#attribute-less-than-equals">Visit detailed user documentation</a></p>
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "lessThanEquals",
	shortDescription = "Compares value of the attribute with passed value and checks if the value of that attribute is less than or equals to the passed value.",
	userDocsLink = "/documentation/query/filtering/comparable#attribute-less-than-equals",
	supportedIn = { ConstraintDomain.ENTITY, ConstraintDomain.REFERENCE, ConstraintDomain.INLINE_REFERENCE },
	supportedValues = @ConstraintSupportedValues(allTypesSupported = true, arraysSupported = true)
)
public class AttributeLessThanEquals extends AbstractAttributeFilterComparisonConstraintLeaf implements FilterConstraint {
	@Serial private static final long serialVersionUID = -6991102136613476099L;

	private AttributeLessThanEquals(Serializable... arguments) {
		super(arguments);
	}

	@Creator
	public <T extends Serializable> AttributeLessThanEquals(@Nonnull @Classifier String attributeName,
	                                                                        @Nonnull @Value(requiresPlainType = true) T attributeValue) {
		super(attributeName, attributeValue);
	}

	/**
	 * Returns value that must be less than or equals attribute value.
	 */
	@Nonnull
	public <T extends Serializable> T getAttributeValue() {
		//noinspection unchecked
		return (T) getArguments()[1];
	}

	@Override
	public boolean isApplicable() {
		return isArgumentsNonNull() && getArguments().length == 2;
	}

	@Nonnull
	@Override
	public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new AttributeLessThanEquals(newArguments);
	}
}
