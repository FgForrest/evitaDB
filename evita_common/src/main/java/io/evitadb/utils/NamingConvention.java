/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.utils;

import io.evitadb.exception.EvitaInternalError;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * String cases that are supported to convert strings to.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public enum NamingConvention {

	/**
	 * Camel case.
	 *
	 * @see <a href="https://en.wikipedia.org/wiki/Camel_case">Camel case</a>
	 */
	CAMEL_CASE,
	/**
	 * Pascal case.
	 *
	 * @see <a href="https://www.theserverside.com/definition/Pascal-case">Pascal case</a>
	 */
	PASCAL_CASE,
	/**
	 * Snake case.
	 *
	 * @see <a href="https://en.wikipedia.org/wiki/Snake_case">Snake case</a>
	 */
	SNAKE_CASE,
	/**
	 * Capitalized snake case.
	 *
	 * @see <a href="https://en.wikipedia.org/wiki/Snake_case">Snake case</a>
	 */
	UPPER_SNAKE_CASE,
	/**
	 * Kebab case.
	 *
	 * @see <a href="https://en.wikipedia.org/wiki/Letter_case#Kebab_case">Kebab case</a>
	 */
	KEBAB_CASE;

	private static final NamingConvention[] ALL_CONVENTIONS = NamingConvention.values();

	/**
	 * Method generates all name variants in all supported naming conventions.
	 *
	 * @param name to translate
	 * @return index of translated names indexed by name convention
	 */
	public static Map<NamingConvention, String> generate(@Nonnull String name) {
		return Collections.unmodifiableMap(
			Arrays.stream(ALL_CONVENTIONS)
				.collect(
					Collectors.toMap(
						Function.identity(),
						it -> StringUtils.toSpecificCase(name, it),
						(s, s2) -> { throw new EvitaInternalError("Should not ever occur!"); },
						LinkedHashMap::new
					)
				)
		);
	}
}
