/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.api.query.parser;

import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.utils.Assert;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;
import java.util.regex.Pattern;

/**
 * {@link Enum} wrapper for parsing enums from EvitaQL string because during parsing such enum there is no way to know to
 * which enum the value belongs to so the actual value is wrapped and resolved to actual enum later.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2021
 */
@EqualsAndHashCode(cacheStrategy = EqualsAndHashCode.CacheStrategy.LAZY)
public class EnumWrapper implements Serializable, Comparable<EnumWrapper> {

	@Serial private static final long serialVersionUID = -7936191516934712950L;
	/**
	 * Supported format of inner value.
	 */
	private static final Pattern VALUE_FORMAT = Pattern.compile("[A-Z]+(_[A-Z]+)*");

	@Getter
	private final String value;

	private EnumWrapper(String value) {
		this.value = value;
	}

	/**
	 * Converts string value into enum wrapper
	 */
	public static EnumWrapper fromString(@Nonnull String value) {
		Assert.isTrue(VALUE_FORMAT.matcher(value).matches(), "Value of enum is in unsupported format. Use only capital letters and underscores.");
		return new EnumWrapper(value);
	}

	/**
	 * Converts value of this wrapper to actual {@link Enum}
	 *
	 * @param targetEnum enum class to convert to
	 * @return enum instance corresponding to wrapper value
	 */
	@Nonnull
	public <E extends Enum<E>> E toEnum(@Nonnull Class<E> targetEnum) {
		return Arrays.stream(targetEnum.getEnumConstants())
			.filter(enumValue -> enumValue.name().equals(this.value))
			.findFirst()
			.orElseThrow(() -> new EvitaInvalidUsageException("Unknown value `" + this.value + "` for enum `" + targetEnum.getSimpleName() + "`."));
	}

	/**
	 * Checks whether value of this enum wrapper can be safely mapped to the target enum class.
	 */
	public boolean canBeMappedTo(@Nonnull Class<? extends Enum<?>> targetEnum) {
		return Arrays.stream(targetEnum.getEnumConstants())
			.anyMatch(enumValue -> enumValue.name().equals(this.value));
	}

	@Override
	public int compareTo(EnumWrapper o) {
		return this.value.compareTo(o.getValue());
	}

	@Override
	public String toString() {
		return this.value;
	}
}
