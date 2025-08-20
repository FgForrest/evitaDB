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

package io.evitadb.dataType;

import io.evitadb.dataType.exception.DataTypeParseException;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;

import static java.util.Optional.ofNullable;

/**
 * Specialized {@link NumberRange} for {@link Short}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public final class ShortNumberRange extends NumberRange<Short> {
	@Serial private static final long serialVersionUID = -7031165388993390172L;

	/**
	 * Method creates new LongRange instance.
	 */
	@Nonnull
	public static ShortNumberRange between(@Nonnull Short from, @Nonnull Short to) {
		return new ShortNumberRange(from, to);
	}

	/**
	 * Method creates new LongRange instance when only lower range bound is available.
	 */
	@Nonnull
	public static ShortNumberRange from(@Nonnull Short from) {
		return new ShortNumberRange(from, null);
	}

	/**
	 * Method creates new LongRange instance when only upper range bound is available.
	 */
	@Nonnull
	public static ShortNumberRange to(@Nonnull Short to) {
		return new ShortNumberRange(null, to);
	}

	/**
	 * This method is for internal purposes only. It could be used for reconstruction of original Entity from different
	 * package than current, but still internal code of the Evita ecosystems.
	 *
	 * Do not use this method from in the client code!
	 */
	@Nonnull
	public static ShortNumberRange _internalBuild(@Nullable Short from, @Nullable Short to, @Nullable Integer retainedDecimalPlaces, long fromToCompare, long toToCompare) {
		return new ShortNumberRange(from, to, retainedDecimalPlaces, fromToCompare, toToCompare);
	}

	/**
	 * Parses string to {@link NumberRange} or throws an exception. String must conform to the format produced
	 * by {@link NumberRange#toString()} method. Parsed Number range always uses {@link Long} for numbers.
	 */
	@Nonnull
	public static ShortNumberRange fromString(@Nonnull String string) throws DataTypeParseException {
		Assert.isTrue(
			string.startsWith(OPEN_CHAR) && string.endsWith(CLOSE_CHAR),
			() -> new DataTypeParseException("NumberRange must start with " + OPEN_CHAR + " and end with " + CLOSE_CHAR + "!")
		);
		final int delimiter = string.indexOf(INTERVAL_JOIN, 1);
		Assert.isTrue(
			delimiter > -1,
			() -> new DataTypeParseException("NumberRange must contain " + INTERVAL_JOIN + " to separate from and to dates!")
		);
		final Short from = delimiter == 1 ? null : parseShort(string.substring(1, delimiter));
		final Short to = delimiter == string.length() - 2 ? null : parseShort(string.substring(delimiter + 1, string.length() - 1));
		if (from == null && to != null) {
			return to(to);
		} else if (from != null && to == null) {
			return from(from);
		} else if (from != null) {
			return between(from, to);
		} else {
			throw new DataTypeParseException("Range has no sense with both limits open to infinity!");
		}
	}

	@Nonnull
	private static Short parseShort(@Nonnull String toBeNumber) {
		try {
			return Short.parseShort(toBeNumber);
		} catch (NumberFormatException ex) {
			throw new DataTypeParseException("String " + toBeNumber + " is not a short number!");
		}
	}

	private ShortNumberRange(@Nullable Short from, @Nullable Short to, @Nullable Integer retainedDecimalPlaces, long fromToCompare, long toToCompare) {
		super(from, to, retainedDecimalPlaces, fromToCompare, toToCompare);
	}

	private ShortNumberRange(@Nullable Short from, @Nullable Short to) {
		super(
			from, to, null,
			ofNullable(from).map(Short::longValue).orElse(Long.MIN_VALUE),
			ofNullable(to).map(Short::longValue).orElse(Long.MAX_VALUE)
		);
		assertEitherBoundaryNotNull(from, to);
		assertFromLesserThanTo(from, to);
		assertNotFloatingPointType(from, "from");
		assertNotFloatingPointType(to, "to");
	}

	@Nonnull
	@Override
	public Range<Short> cloneWithDifferentBounds(@Nullable Short from, @Nullable Short to) {
		return new ShortNumberRange(from, to);
	}

	@Override
	protected long toComparableLong(@Nullable Short valueToCheck, long defaultValue) {
		return ofNullable(valueToCheck).map(Short::longValue).orElse(defaultValue);
	}

	@Override
	protected Class<Short> getSupportedType() {
		return Short.class;
	}
}
