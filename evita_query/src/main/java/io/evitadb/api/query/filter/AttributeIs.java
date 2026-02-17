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

import io.evitadb.api.query.AttributeConstraint;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.ConstraintNullabilitySupport;
import io.evitadb.api.query.descriptor.annotation.Classifier;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.ConstraintSupportedValues;
import io.evitadb.api.query.descriptor.annotation.Creator;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;

/**
 * Filters entities based on the presence or absence of a named attribute value, testing for special nullability states
 * that cannot be expressed via standard {@link Comparable} operations. This constraint is essential for filtering by
 * attribute existence, handling optional attributes, and distinguishing between explicitly-set null values and missing
 * attributes.
 *
 * The constraint accepts an {@link AttributeSpecialValue} enum constant specifying the desired nullability state:
 * {@link AttributeSpecialValue#NULL} or {@link AttributeSpecialValue#NOT_NULL}. Both explicit and implicit null states
 * are treated identically - an attribute that was never set is considered null.
 *
 * **EvitaQL syntax:**
 *
 * ```
 * attributeIs(attributeName:string!, NULL|NOT_NULL)
 * ```
 *
 * For convenience, shorthand factory methods are provided (via QueryConstraints):
 *
 * ```
 * attributeIsNull("attributeName")       // Equivalent to attributeIs("attributeName", NULL)
 * attributeIsNotNull("attributeName")    // Equivalent to attributeIs("attributeName", NOT_NULL)
 * ```
 *
 * **Constraint classification:**
 *
 * - Implements {@link FilterConstraint} - usable in filterBy clauses
 * - Implements {@link AttributeConstraint} - operates on named attributes
 * - Supported in: {@link ConstraintDomain#ENTITY}, {@link ConstraintDomain#REFERENCE},
 *   {@link ConstraintDomain#INLINE_REFERENCE}
 *
 * **Array attribute handling:**
 *
 * For array-typed attributes, the constraint tests whether the entire attribute (the array itself) is null or not null,
 * **not** the individual elements within the array. An empty array `[]` is considered NOT_NULL (the array exists but is
 * empty), while a missing array attribute is NULL.
 *
 * **Common use cases:**
 *
 * - Filtering incomplete records: `attributeIsNull("description")` (products missing descriptions)
 * - Requiring mandatory data: `attributeIsNotNull("description")` (only products with description set)
 * - Optional feature detection: `attributeIsNotNull("discount")` (products with active discounts)
 * - Data quality checks: `attributeIsNull("externalId")` (entities not yet synchronized)
 * - Conditional logic: `attributeIsNotNull("customField")` (entities with custom data populated)
 *
 * **Nullability semantics:**
 *
 * - **NULL**: Matches entities where the attribute is not set (implicitly null) or explicitly set to null
 * - **NOT_NULL**: Matches entities where the attribute has any non-null value (including empty strings, zero, false,
 *   empty arrays, etc.)
 *
 * **Difference from other constraints:**
 *
 * Unlike value-based constraints ({@link AttributeEquals}, {@link AttributeInSet}, etc.), `attributeIs` does not
 * compare attribute values - it only tests for existence/absence. This makes it the only constraint capable of
 * detecting missing attributes.
 *
 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/comparable#attribute-is)
 *
 * @see AttributeSpecialValue
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "is",
	shortDescription = "The constraint checks if the value of the attribute is null or not null, based on the passed special value (NULL or NOT_NULL).",
	userDocsLink = "/documentation/query/filtering/comparable#attribute-is",
	supportedIn = { ConstraintDomain.ENTITY, ConstraintDomain.REFERENCE, ConstraintDomain.INLINE_REFERENCE },
	supportedValues = @ConstraintSupportedValues(
		allTypesSupported = true,
		arraysSupported = true,
		nullability = ConstraintNullabilitySupport.ONLY_NULLABLE
	)
)
public class AttributeIs extends AbstractAttributeFilterConstraintLeaf implements FilterConstraint {
	@Serial private static final long serialVersionUID = 6615086027607982158L;

	private AttributeIs(@Nonnull Serializable... arguments) {
		super(arguments);
	}

	@Creator
	public AttributeIs(@Nonnull @Classifier String attributeName,
	                   @Nonnull AttributeSpecialValue attributeSpecialValue) {
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
