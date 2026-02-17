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
 * Filters entities where a named attribute value strictly exceeds a specified threshold value, establishing an
 * exclusive lower bound for range-based queries. This constraint is fundamental for numeric and ordered data filtering,
 * commonly used in conjunction with {@link AttributeLessThan} or {@link AttributeLessThanEquals} to define open or
 * half-open intervals.
 *
 * The constraint performs type-safe comparison via {@link io.evitadb.dataType.EvitaDataTypes} conversion. Both the
 * attribute value and threshold must be convertible to a common comparable type, or the constraint evaluates to false.
 * String comparisons follow alphabetical ordering (locale-specific collation for localized attributes). Range types
 * compare left boundary first, then right boundary. Boolean values are treated as numeric (true=1, false=0).
 *
 * **EvitaQL syntax:**
 *
 * ```
 * attributeGreaterThan(attributeName:string!, value:comparable!)
 * ```
 *
 * **Constraint classification:**
 *
 * - Implements {@link FilterConstraint} - usable in filterBy clauses
 * - Implements {@link io.evitadb.api.query.AttributeConstraint} - operates on named attributes
 * - Supported in: {@link ConstraintDomain#ENTITY}, {@link ConstraintDomain#REFERENCE},
 *   {@link ConstraintDomain#INLINE_REFERENCE}
 *
 * **Array attribute limitations:**
 *
 * When the attribute is array-typed, the constraint matches if **any** element in the array is greater than
 * the threshold value.
 *
 * **Common use cases:**
 *
 * - Minimum threshold filtering: `attributeGreaterThan("price", 100.00)`
 * - Date range queries: `attributeGreaterThan("publishedAfter", "2024-01-01")`
 * - Age restrictions: `attributeGreaterThan("age", 17)`
 * - Stock availability: `attributeGreaterThan("quantity", 0)`
 * - Score filtering: `attributeGreaterThan("rating", 4.5)`
 *
 * **Combining with other constraints:**
 *
 * Commonly paired with upper-bound constraints to form ranges:
 *
 * ```
 * and(
 *     attributeGreaterThan("price", 50.00),
 *     attributeLessThanEquals("price", 100.00)
 * )
 * ```
 *
 * For closed intervals (inclusive on both ends), use {@link AttributeBetween} for better readability and performance.
 *
 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/comparable#attribute-greater-than)
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "greaterThan",
	shortDescription = "Compares value of the attribute with passed value and checks if the value of that attribute is greater than the passed value.",
	userDocsLink = "/documentation/query/filtering/comparable#attribute-greater-than",
	supportedIn = {ConstraintDomain.ENTITY, ConstraintDomain.REFERENCE, ConstraintDomain.INLINE_REFERENCE},
	supportedValues = @ConstraintSupportedValues(allTypesSupported = true, arraysSupported = true)
)
public class AttributeGreaterThan extends AbstractAttributeFilterComparisonConstraintLeaf implements FilterConstraint {
	@Serial private static final long serialVersionUID = -4468753216715311483L;

	private AttributeGreaterThan(@Nonnull Serializable... arguments) {
		super(arguments);
	}

	@Creator
	public <T extends Serializable> AttributeGreaterThan(
		@Nonnull @Classifier String attributeName,
		@Nonnull @Value(requiresPlainType = true) T attributeValue
	) {
		super(attributeName, attributeValue);
	}

	/**
	 * Returns the threshold value that the attribute value must be greater than.
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
		return new AttributeGreaterThan(newArguments);
	}
}
