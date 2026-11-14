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

import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Objects;

/**
 * Shared null validation and normalization at query construction boundaries.
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
