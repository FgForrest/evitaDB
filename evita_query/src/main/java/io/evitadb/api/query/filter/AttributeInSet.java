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

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;

/**
 * This `inSet` is query that compares value of the attribute with name passed in first argument with all the values passed
 * in the second, third and additional arguments. First argument must be {@link String}, additional arguments may be any
 * of {@link Comparable} type.
 *
 * Type of the attribute value and additional arguments must be convertible one to another otherwise `in` function
 * skips value comparison and ultimately returns false.
 *
 * Function returns true if attribute value is equal to at least one of additional values.
 *
 * Example:
 *
 * <pre>
 * inSet("level", 1, 2, 3)
 * </pre>
 *
 * Function supports attribute arrays and when attribute is of array type `inSet` returns true if any of attribute values
 * equals the value in the query. If we have the attribute `code` with value `["A","B","C"]` all these constraints will
 * match:
 *
 * <pre>
 * inSet("code","A","D")
 * inSet("code","A", "B")
 * </pre>
 *
 * <p><a href="https://evitadb.io/documentation/query/filtering/comparable#attribute-in-set">Visit detailed user documentation</a></p>
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "inSet",
	shortDescription = "Compares value of the attribute with passed value and checks if the value of that attribute " +
		"equals to at least one of the passed values. " +
		"The constraint is equivalent to the multiple `equals` constraints combined with logical OR.",
	userDocsLink = "/documentation/query/filtering/comparable#attribute-in-set",
	supportedIn = {ConstraintDomain.ENTITY, ConstraintDomain.REFERENCE, ConstraintDomain.INLINE_REFERENCE},
	supportedValues = @ConstraintSupportedValues(allTypesSupported = true, arraysSupported = true)
)
public class AttributeInSet extends AbstractAttributeFilterConstraintLeaf implements FilterConstraint {
	@Serial private static final long serialVersionUID = 500395477991778874L;

	private AttributeInSet(Serializable... arguments) {
		super(arguments);
	}

	@Creator
	public <T extends Serializable> AttributeInSet(
		@Nonnull @Classifier String attributeName,
		@Nonnull T... attributeValues
	) {
		super(concat(attributeName, attributeValues));
	}

	/**
	 * Returns set of {@link Serializable} values that attribute value must be part of.
	 */
	public Serializable[] getAttributeValues() {
		return Arrays.stream(getArguments())
			.skip(1)
			.toArray(Serializable[]::new);
	}

	@Override
	public boolean isApplicable() {
		return isArgumentsNonNull() && getArguments().length >= 1;
	}

	@Nonnull
	@Override
	public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new AttributeInSet(newArguments);
	}
}
