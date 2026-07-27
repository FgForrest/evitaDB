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

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;

import static java.util.Optional.ofNullable;

/**
 * Specialized {@link NumberRange} for {@link Byte}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public final class ByteNumberRange extends NumberRange<Byte> {
	@Serial private static final long serialVersionUID = -7031165388993390172L;

	/**
	 * Method creates new ByteRange instance.
	 */
	@Nonnull
	public static ByteNumberRange between(@Nonnull Byte from, @Nonnull Byte to) {
		return new ByteNumberRange(from, to);
	}

	/**
	 * Method creates new ByteRange instance when only lower range bound is available.
	 */
	@Nonnull
	public static ByteNumberRange from(@Nonnull Byte from) {
		return new ByteNumberRange(from, null);
	}

	/**
	 * Method creates new ByteRange instance when only upper range bound is available.
	 */
	@Nonnull
	public static ByteNumberRange to(@Nonnull Byte to) {
		return new ByteNumberRange(null, to);
	}

	/**
	 * Parses string to {@link NumberRange} or throws an exception. String must conform to the format produced
	 * by {@link NumberRange#toString()} method. Parsed Number range always uses {@link Byte} for numbers.
	 */
	@Nonnull
	public static ByteNumberRange fromString(@Nonnull String string) throws DataTypeParseException {
		return Range.parseRange(
			string, ByteNumberRange::parseByte,
			ByteNumberRange::to, ByteNumberRange::from, ByteNumberRange::between
		);
	}

	@Nonnull
	private static Byte parseByte(@Nonnull String toBeNumber) {
		try {
			return Byte.parseByte(toBeNumber);
		} catch (NumberFormatException ex) {
			throw new DataTypeParseException("String " + toBeNumber + " is not a byte number!");
		}
	}

	/**
	 * This method is for internal purposes only. It could be used for reconstruction of original Entity from different
	 * package than current, but still internal code of the Evita ecosystems.
	 *
	 * Do not use this method from in the client code!
	 */
	@Nonnull
	public static ByteNumberRange _internalBuild(@Nullable Byte from, @Nullable Byte to, @Nullable Integer retainedDecimalPlaces, long fromToCompare, long toToCompare) {
		return new ByteNumberRange(from, to, retainedDecimalPlaces, fromToCompare, toToCompare);
	}

	private ByteNumberRange(@Nullable Byte from, @Nullable Byte to, @Nullable Integer retainedDecimalPlaces, long fromToCompare, long toToCompare) {
		super(from, to, retainedDecimalPlaces, fromToCompare, toToCompare);
	}

	private ByteNumberRange(@Nullable Byte from, @Nullable Byte to) {
		super(
			from, to, null,
			ofNullable(from).map(Byte::longValue).orElse(Long.MIN_VALUE),
			ofNullable(to).map(Byte::longValue).orElse(Long.MAX_VALUE)
		);
		assertEitherBoundaryNotNull(from, to);
		assertFromLesserThanTo(from, to);
		assertNotFloatingPointType(from, "from");
		assertNotFloatingPointType(to, "to");
	}

	@Nonnull
	@Override
	public Range<Byte> cloneWithDifferentBounds(@Nullable Byte from, @Nullable Byte to) {
		return new ByteNumberRange(from, to);
	}

	@Override
	protected long toComparableLong(@Nullable Byte valueToCheck, long defaultValue) {
		return ofNullable(valueToCheck).map(Byte::longValue).orElse(defaultValue);
	}

	@Override
	protected Class<Byte> getSupportedType() {
		return Byte.class;
	}
}
