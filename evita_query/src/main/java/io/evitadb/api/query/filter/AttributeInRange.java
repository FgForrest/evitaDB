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

import io.evitadb.api.query.ConstraintWithSuffix;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.AliasForParameter;
import io.evitadb.api.query.descriptor.annotation.Classifier;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.ConstraintSupportedValues;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.api.query.descriptor.annotation.Value;
import io.evitadb.dataType.ByteNumberRange;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.dataType.LongNumberRange;
import io.evitadb.dataType.Range;
import io.evitadb.dataType.ShortNumberRange;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Filters entities by testing whether a specified value falls within the boundaries of a range-type attribute.
 *
 * This constraint verifies that a given comparable value (a timestamp or number) is contained within an attribute's range boundaries,
 * where both the start (from) and end (to) points are inclusive. It is designed specifically for temporal validity checks and numeric
 * range matching, commonly used in e-commerce scenarios like product availability windows, promotion validity periods, age restrictions,
 * or quantity thresholds.
 *
 * ## Syntax
 *
 * ```
 * attributeInRange(attributeName:string!, value:comparable!)
 * attributeInRangeNow(attributeName:string!)
 * ```
 *
 * The `attributeInRangeNow` variant uses the current system date and time as the comparison value, making it convenient for filtering
 * entities valid at the moment of query execution without explicitly passing a timestamp.
 *
 * ## Type Requirements
 *
 * The target attribute must be a {@link Range} subtype, specifically one of:
 * - {@link DateTimeRange}: For temporal validity (e.g., promotion periods, product availability windows)
 * - {@link ByteNumberRange}, {@link ShortNumberRange}, {@link IntegerNumberRange}, {@link LongNumberRange}: For numeric ranges
 *
 * The comparison value must be compatible with the range type:
 * - {@link OffsetDateTime} for {@link DateTimeRange} attributes
 * - {@link Number} (byte, short, int, long) for numeric range attributes
 *
 * The attribute must be defined as filterable in the entity schema. Non-range attributes or incompatible value types will cause query
 * execution to fail.
 *
 * ## Matching Behavior
 *
 * The constraint returns true if and only if:
 * ```
 * attribute.from <= value <= attribute.to
 * ```
 *
 * Both boundaries are **inclusive**. If the range has unbounded start or end (null values), those boundaries are considered to extend
 * infinitely in that direction.
 *
 * Examples with a `DateTimeRange` attribute:
 * - Range `[2024-01-01, 2024-12-31]` matches value `2024-06-15` (within bounds)
 * - Range `[2024-01-01, 2024-12-31]` matches value `2024-01-01` (inclusive start)
 * - Range `[2024-01-01, 2024-12-31]` matches value `2024-12-31` (inclusive end)
 * - Range `[2024-01-01, 2024-12-31]` does NOT match value `2023-12-31` (before range)
 * - Range `[2024-01-01, null]` matches any value >= `2024-01-01` (unbounded end)
 *
 * ## Array Support
 *
 * When the attribute is an array of ranges, the constraint returns true if the comparison value falls within ANY of the ranges in the
 * array. This uses existential quantification semantics: at least one range must contain the value.
 *
 * For example, given an attribute `eligibleAges` with value `[ByteNumberRange[18, 25], ByteNumberRange[60, 65]]`:
 *
 * ```
 * attributeInRange("eligibleAges", 18)  // matches (18 is in [18, 25])
 * attributeInRange("eligibleAges", 24)  // matches (24 is in [18, 25])
 * attributeInRange("eligibleAges", 63)  // matches (63 is in [60, 65])
 * attributeInRange("eligibleAges", 30)  // does not match (not in any range)
 * ```
 *
 * ## Usage Patterns
 *
 * Common use cases include:
 * - **Temporal validity**: Filter products/promotions valid at a specific date and time
 * - **Current validity**: Use `attributeInRangeNow` to find currently valid entities
 * - **Age restrictions**: Match products eligible for specific age ranges
 * - **Quantity thresholds**: Filter by quantity brackets or tier pricing ranges
 * - **Combining with NULL checks**: Use `or(attributeInRange(...), attributeIs(..., NULL))` to also include entities without range
 *   attributes
 *
 * Example query filtering products with promotions valid right now:
 *
 * ```
 * query(
 *     collection("Product"),
 *     filterBy(
 *         attributeInRangeNow("promotionValidity")
 *     )
 * )
 * ```
 *
 * Example query filtering products valid at a specific date or without validity constraints:
 *
 * ```
 * query(
 *     collection("Product"),
 *     filterBy(
 *         or(
 *             attributeInRange("validity", OffsetDateTime.parse("2024-06-15T00:00:00Z")),
 *             attributeIs("validity", NULL)
 *         )
 *     )
 * )
 * ```
 *
 * ## Related Constraints
 *
 * - {@link AttributeEquals}: Exact value matching
 * - {@link AttributeBetween}: Check if a scalar attribute value falls within a specified range (inverse semantics)
 * - {@link AttributeIs}: Check for NULL or NOT_NULL attribute values
 *
 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/range#attribute-in-range)
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "inRange",
	shortDescription = "Compares value of the attribute with passed value and checks if the range value of that " +
		"attribute contains the passed value within its limits (both ends are inclusive). " +
		"The constraint can be used only for Range data type values.",
	userDocsLink = "/documentation/query/filtering/range#attribute-in-range",
	supportedIn = {ConstraintDomain.ENTITY, ConstraintDomain.REFERENCE, ConstraintDomain.INLINE_REFERENCE},
	supportedValues = @ConstraintSupportedValues(
		supportedTypes = {
			DateTimeRange.class,
			ByteNumberRange.class,
			ShortNumberRange.class,
			IntegerNumberRange.class,
			LongNumberRange.class
		},
		arraysSupported = true
	)
)
public class AttributeInRange extends AbstractAttributeFilterConstraintLeaf
	implements ConstraintWithSuffix, FilterConstraint {
	private static final String SUFFIX_NOW = "now";
	@Serial private static final long serialVersionUID = -6018832750772234247L;

	private AttributeInRange(@Nonnull Serializable... arguments) {
		super(arguments);
	}

	@Creator
	private <T extends Serializable> AttributeInRange(
		@Nonnull @Classifier String attributeName,
		@Nonnull @Value(requiresPlainType = true) T value
	) {
		super(attributeName, value);
		Assert.isTrue(
			value instanceof Number || value instanceof OffsetDateTime,
			"Value in query`" + this + "` has unsupported data type. Supported are `OffsetDateTime` and `Number`."
		);
	}

	@Creator(suffix = SUFFIX_NOW)
	public AttributeInRange(@Nonnull @Classifier String attributeName) {
		super(attributeName);
	}

	public AttributeInRange(@Nonnull String attributeName, @Nonnull OffsetDateTime theMoment) {
		super(attributeName, theMoment);
	}

	public AttributeInRange(@Nonnull String attributeName, @Nonnull Number theValue) {
		super(attributeName, theValue);
	}

	/**
	 * Returns {@link Serializable} argument that should be verified whether is within the range (inclusive) of attribute validity.
	 */
	@AliasForParameter("value")
	@Nullable
	public Serializable getUnknownArgument() {
		final boolean argsReady = getArguments().length == 2;
		if (argsReady) {
			return getArguments()[1];
		} else {
			return null;
		}
	}

	/**
	 * Returns {@link OffsetDateTime} that should be verified whether is within the range (inclusive) of attribute validity.
	 */
	@Nullable
	public OffsetDateTime getTheMoment() {
		final boolean argsReady = getArguments().length == 2;
		if (argsReady) {
			return getArguments()[1] instanceof OffsetDateTime ? (OffsetDateTime) getArguments()[1] : null;
		} else {
			return null;
		}
	}

	/**
	 * Returns {@link Number} that should be verified whether is within the range (inclusive) of attribute validity.
	 */
	@Nullable
	public Number getTheValue() {
		final boolean argsReady = getArguments().length == 2;
		if (argsReady) {
			return getArguments()[1] instanceof Number ? (Number) getArguments()[1] : null;
		} else {
			return null;
		}
	}

	@Override
	public boolean isApplicable() {
		final int length = getArguments().length;
		// "now" variant has 1 arg (attribute name), explicit value variant has 2 args (name + value)
		return isArgumentsNonNull() && (length == 1 || length == 2);
	}

	@Nonnull
	@Override
	public Optional<String> getSuffixIfApplied() {
		return getArguments().length == 1 ? Optional.of(SUFFIX_NOW) : Optional.empty();
	}

	@Nonnull
	@Override
	public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new AttributeInRange(newArguments);
	}
}
