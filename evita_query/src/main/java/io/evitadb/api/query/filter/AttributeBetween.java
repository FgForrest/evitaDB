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
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;

/**
 * Filters entities where a named attribute value falls within a specified closed interval, defined by inclusive lower
 * and upper bounds. This is the primary constraint for efficient range-based filtering, optimized for both single-value
 * and array-typed attributes, with specialized handling for Range-typed attributes (NumberRange, DateTimeRange, etc.).
 *
 * The constraint performs type-safe comparison via {@link io.evitadb.dataType.EvitaDataTypes} conversion. The attribute
 * value and both boundary values must be convertible to a common comparable type, or the constraint evaluates to false.
 * String comparisons follow alphabetical ordering (locale-specific collation for localized attributes). Boolean values
 * are treated as numeric (true=1, false=0). Both boundaries are **inclusive** - the constraint matches values that are
 * exactly equal to either boundary.
 *
 * **EvitaQL syntax:**
 *
 * ```
 * attributeBetween(attributeName:string!, lowerBound:comparable!, upperBound:comparable!)
 * ```
 *
 * **Constraint classification:**
 *
 * - Implements {@link io.evitadb.api.query.FilterConstraint} - usable in filterBy clauses
 * - Implements {@link io.evitadb.api.query.AttributeConstraint} - operates on named attributes
 * - Supported in: {@link io.evitadb.api.query.descriptor.ConstraintDomain#ENTITY}, {@link io.evitadb.api.query.descriptor.ConstraintDomain#REFERENCE},
 *   {@link io.evitadb.api.query.descriptor.ConstraintDomain#INLINE_REFERENCE}
 *
 * **Array attribute handling:**
 *
 * When the attribute is array-typed, the constraint matches if **any** element in the array falls within the specified
 * range. For example, given `amount=[1, 9]`, all of these constraints match:
 *
 * ```
 * attributeBetween("amount", 0, 50)    // Both 1 and 9 are in [0,50]
 * attributeBetween("amount", 0, 5)     // 1 is in [0,5]
 * attributeBetween("amount", 8, 10)    // 9 is in [8,10]
 * ```
 *
 * **Range-typed attribute handling (overlap semantics):**
 *
 * For Range-typed attributes (NumberRange, DateTimeRange, BigDecimalNumberRange, etc.), `between` performs **overlap
 * detection** rather than simple containment. The constraint matches if the query range and **any** attribute range
 * share at least one point in common.
 *
 * Given `validity=[[2,5], [8,10]]` (two NumberRange values), these constraints match:
 *
 * ```
 * attributeBetween("validity", 0, 3)    // Overlaps [2,5]
 * attributeBetween("validity", 0, 100)  // Overlaps both ranges
 * attributeBetween("validity", 9, 10)   // Overlaps [8,10]
 * ```
 *
 * These constraints do **not** match (no overlap):
 *
 * ```
 * attributeBetween("validity", 11, 15)  // Gap after [8,10]
 * attributeBetween("validity", 0, 1)    // Gap before [2,5]
 * attributeBetween("validity", 6, 7)    // Falls in gap between ranges
 * ```
 *
 * **Common use cases:**
 *
 * - Date range queries: `attributeBetween("publishDate", "2024-01-01", "2024-12-31")`
 * - Age group filtering: `attributeBetween("age", 25, 35)`
 * - Rating ranges: `attributeBetween("rating", 3.5, 5.0)`
 * - Temporal validity checks: `attributeBetween("validityPeriod", startDate, endDate)` (overlap semantics)
 *
 * **Advantages over separate inequalities:**
 *
 * While logically equivalent to:
 *
 * ```
 * and(
 *     attributeGreaterThanEquals("price", 50.00),
 *     attributeLessThanEquals("price", 100.00)
 * )
 * ```
 *
 * The `between` constraint offers:
 * - More concise and readable syntax
 * - Better query optimization opportunities (single index scan instead of two)
 * - Specialized handling for Range types (overlap vs. containment)
 *
 * **Null boundary handling:**
 *
 * At least one boundary must be non-null for the constraint to be applicable. A null boundary effectively removes that
 * side of the constraint (open-ended range), though explicit use of {@link AttributeGreaterThanEquals} or
 * {@link AttributeLessThanEquals} is clearer for such cases.
 *
 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/comparable#attribute-between)
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "between",
	shortDescription = "Compares value of the attribute with passed value and checks if the value of that attribute is within the passed range (both ends are inclusive).",
	userDocsLink = "/documentation/query/filtering/comparable#attribute-between",
	supportedIn = { ConstraintDomain.ENTITY, ConstraintDomain.REFERENCE, ConstraintDomain.INLINE_REFERENCE },
	supportedValues = @ConstraintSupportedValues(allTypesSupported = true, arraysSupported = true)
)
public class AttributeBetween extends AbstractAttributeFilterConstraintLeaf implements FilterConstraint {
	@Serial private static final long serialVersionUID = 4684374310853295964L;

	private AttributeBetween(@Nonnull Serializable... arguments) {
		super(arguments);
	}

	@Creator
	public <T extends Serializable> AttributeBetween(@Nonnull @Classifier String attributeName,
	                                                                 @Nullable @Value(requiresPlainType = true) T from,
	                                                                 @Nullable @Value(requiresPlainType = true) T to) {
		super(attributeName, from, to);
	}

	/**
	 * Returns lower bound of attribute value (inclusive).
	 */
	@Nullable
	public <T extends Serializable> T getFrom() {
		//noinspection unchecked
		return (T) getArguments()[1];
	}

	/**
	 * Returns upper bound of attribute value (inclusive).
	 */
	@Nullable
	public <T extends Serializable> T getTo() {
		//noinspection unchecked
		return (T) getArguments()[2];
	}

	@Override
	public boolean isApplicable() {
		//noinspection ConstantValue
		return getArguments().length == 3 && getAttributeName() != null && (getFrom() != null || getTo() != null);
	}

	@Nonnull
	@Override
	public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new AttributeBetween(newArguments);
	}
}
