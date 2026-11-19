/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.api.query;

import io.evitadb.dataType.ChainableType;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.dataType.exception.UnsupportedDataTypeException;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Objects;

/**
 * Shared argument validation and normalization at query construction boundaries.
 *
 * The `QueryConstraints` factories apply these checks before they decide to omit a constraint, so a null or blank
 * name throws even when the value next to it is undefined. Names are set by the application and must be present;
 * values may be undefined, and null items of a value list are dropped.
 */
public final class ConstraintArguments {

	/**
	 * Prevents instantiation of this utility class.
	 */
	private ConstraintArguments() {
	}

	/**
	 * Requires an application-defined scalar name.
	 *
	 * @param name supplied name
	 * @param argumentName parameter name for the diagnostic
	 * @return the validated name
	 * @throws io.evitadb.exception.EvitaInvalidUsageException when the name is null or blank
	 */
	@Nonnull
	public static String requireName(@Nullable String name, @Nonnull String argumentName) {
		Assert.notNull(name, "Argument `" + argumentName + "` must not be null.");
		Assert.isTrue(!name.isBlank(), "Argument `" + argumentName + "` must not be blank.");
		return name;
	}

	/**
	 * Drops undefined values while retaining the array type and distinguishing a missing list from an empty one.
	 *
	 * @param values values supplied by the caller
	 * @param <T> value type
	 * @return the original array, a filtered copy, or null for a missing list
	 */
	@Nullable
	public static <T> T[] withoutNullValues(@Nullable T[] values) {
		if (values == null) {
			return null;
		}
		for (final T value : values) {
			if (value == null) {
				return Arrays.stream(values).filter(Objects::nonNull)
					.toArray(size -> Arrays.copyOf(values, size));
			}
		}
		return values;
	}

	/**
	 * Validates one attribute comparison value or one item of an attribute value list. Null represents an
	 * undefined value and is handled by the calling factory.
	 *
	 * @param attributeName name of the filtered or ordered attribute
	 * @param value the supplied value
	 * @throws EvitaInvalidUsageException when the value is an array or a chain predecessor
	 */
	public static void requireScalarAttributeValue(@Nonnull String attributeName, @Nullable Serializable value) {
		if (value == null) {
			return;
		}
		if (value.getClass().isArray()) {
			throw new EvitaInvalidUsageException(
				"Attribute `" + attributeName + "` cannot use `" + value.getClass().getTypeName() + "` as one value. " +
					"Pass scalar values, or pass the array by itself to an attribute value-list constraint."
			);
		}
		if (value instanceof ChainableType) {
			throw new EvitaInvalidUsageException(
				"Attribute `" + attributeName + "` cannot use `" + value.getClass().getTypeName() + "` as a value. " +
					"Pass a scalar attribute value, or use `attributeNatural` to order by a chain."
			);
		}
	}

	/**
	 * Expands a sole array item by one level and validates the resulting scalar items before dropping nulls.
	 * Checking before dropping nulls preserves the distinction between one array and an array among other items.
	 *
	 * @param attributeName name of the filtered or ordered attribute
	 * @param values supplied list of values, or null if the list is missing
	 * @return a flat list of defined values, or null if the list is missing
	 * @throws EvitaInvalidUsageException when a list item is an array, a chain predecessor or not serializable
	 */
	@Nullable
	public static Serializable[] normalizeAttributeValues(
		@Nonnull String attributeName, @Nullable Serializable[] values
	) {
		if (values == null) {
			return null;
		}
		final Serializable[] items;
		if (values.length == 1 && values[0] != null && values[0].getClass().isArray()) {
			items = toAttributeValueArray(attributeName, values[0]);
		} else {
			items = values;
		}
		for (final Serializable item : items) {
			requireScalarAttributeValue(attributeName, item);
		}
		return withoutNullValues(items);
	}

	/**
	 * Reuses an array of serializable items or reads its elements reflectively, boxing primitive elements.
	 *
	 * @param attributeName name of the filtered or ordered attribute
	 * @param array the sole array supplied as the value list
	 * @return the elements in their original order
	 * @throws EvitaInvalidUsageException when an element is not serializable
	 */
	@Nonnull
	private static Serializable[] toAttributeValueArray(@Nonnull String attributeName, @Nonnull Object array) {
		if (array instanceof Serializable[] values) {
			return values;
		}
		final Serializable[] values = new Serializable[Array.getLength(array)];
		for (int i = 0; i < values.length; i++) {
			final Object item = Array.get(array, i);
			if (item != null && !(item instanceof Serializable)) {
				throw new EvitaInvalidUsageException(
					"Attribute `" + attributeName + "` cannot use `" + item.getClass().getTypeName() +
						"` as a value. Pass an array of supported scalar values."
				);
			}
			values[i] = (Serializable) item;
		}
		return values;
	}

	/**
	 * Converts a histogram bound without losing the precision of its decimal representation. Floating-point
	 * numbers use the same normalization and non-finite value checks as other numeric query arguments.
	 *
	 * @param value numeric bound, or null for an open bound
	 * @return the decimal bound, or null for an open bound
	 * @throws UnsupportedDataTypeException for a non-finite float or double
	 */
	@Nullable
	public static BigDecimal toHistogramBound(@Nullable Number value) {
		if (value == null) {
			return null;
		}
		if (value instanceof Float || value instanceof Double) {
			return (BigDecimal) EvitaDataTypes.toSupportedType(value);
		}
		return value instanceof BigDecimal decimal ? decimal : new BigDecimal(value.toString());
	}

	/**
	 * Validates names and enum settings without changing the meaning of a missing list. A missing or empty list
	 * passes; a null item, or a blank `String` item, is refused.
	 *
	 * @param items names or enum settings supplied by the caller
	 * @param argumentName parameter name for the diagnostic
	 * @throws io.evitadb.exception.EvitaInvalidUsageException when an item is null or a blank name
	 */
	public static void requireNonNullItems(@Nullable Object[] items, @Nonnull String argumentName) {
		if (items != null) {
			for (final Object item : items) {
				Assert.notNull(item, "Argument `" + argumentName + "` must not contain null items.");
				Assert.isTrue(!(item instanceof String name) || !name.isBlank(),
					"Argument `" + argumentName + "` must not contain blank names.");
			}
		}
	}

	/**
	 * Drops undefined price-list values, including blank strings.
	 *
	 * @param values supplied price lists
	 * @return normalized price lists, or null when no list was supplied
	 */
	@Nullable
	public static String[] withoutUndefinedPriceLists(@Nullable String[] values) {
		return values == null ? null : Arrays.stream(values)
			.filter(value -> value != null && !value.isBlank()).toArray(String[]::new);
	}

}
