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
 * This `inRange` is query that compares value of the attribute with name passed in first argument with the date
 * and time passed in the second argument. First argument must be {@link String}, second argument must be
 * {@link OffsetDateTime} type. If second argument is not passed - current date and time (now) is used.
 * Type of the attribute value must implement {@link Range} interface.
 *
 * Function returns true if second argument is greater than or equal to range start (from), and is lesser than
 * or equal to range end (to).
 *
 * Example:
 *
 * <pre>
 * inRange("valid", 2020-07-30T20:37:50+00:00)
 * inRange("age", 18)
 * </pre>
 *
 * Function supports attribute arrays and when attribute is of array type `inRange` returns true if any of attribute
 * values has range, that envelopes the passed value the value in the query. If we have the attribute `age` with value
 * `[[18, 25],[60,65]]` all these constraints will match:
 *
 * <pre>
 * inRange("age", 18)
 * inRange("age", 24)
 * inRange("age", 63)
 * </pre>
 *
 * <p><a href="https://evitadb.io/documentation/query/filtering/range#attribute-in-range">Visit detailed user documentation</a></p>
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
public class AttributeInRange extends AbstractAttributeFilterConstraintLeaf implements ConstraintWithSuffix, FilterConstraint {
	private static final String SUFFIX_NOW = "now";
	@Serial private static final long serialVersionUID = -6018832750772234247L;

	private AttributeInRange(Serializable... arguments) {
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
