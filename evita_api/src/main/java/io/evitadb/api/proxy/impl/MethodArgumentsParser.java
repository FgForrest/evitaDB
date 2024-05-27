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
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.api.proxy.impl;

import javax.annotation.Nonnull;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Predicate;

import static java.util.Optional.empty;
import static java.util.Optional.of;

/**
 * The MethodArgumentsParser class provides a static method for parsing method arguments and returning the parsed
 * arguments as an optional {@link ParsedArguments} object.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class MethodArgumentsParser {

	/**
	 * Parses the arguments of a method to identify the position of the value parameter and locale parameter (if any).
	 *
	 * @param method The method to parse the arguments for.
	 * @param supportedTypePredicate The predicate to determine if a parameter type is supported.
	 * @return An Optional containing the parsed arguments or empty if the method arguments doesn't meet the expectations
	 */
	@Nonnull
	public static Optional<ParsedArguments> parseArguments(
		@Nonnull Method method, @Nonnull Predicate<Class<?>> supportedTypePredicate
		) {
		final int valueParameterPosition;
		final OptionalInt localeParameterPosition;
		// We only want to handle methods with exactly one parameter, or two parameters of which one is Locale
		final Class<?>[] parameterTypes = method.getParameterTypes();
		if (method.getParameterCount() == 1) {
			if (supportedTypePredicate.test(parameterTypes[0]) && !Locale.class.isAssignableFrom(parameterTypes[0])) {
				valueParameterPosition = 0;
				localeParameterPosition = OptionalInt.empty();
			} else if (Locale.class.isAssignableFrom(parameterTypes[0])) {
				localeParameterPosition = OptionalInt.of(0);
				valueParameterPosition = -1;
			} else {
				localeParameterPosition = OptionalInt.empty();
				valueParameterPosition = -1;
			}
		} else if (method.getParameterCount() == 2 &&
			Arrays.stream(parameterTypes)
				.allMatch(it -> Locale.class.isAssignableFrom(it) || supportedTypePredicate.test(it))
		) {
			int lp = -1;
			for (int i = 0; i < parameterTypes.length; i++) {
				if (Locale.class.isAssignableFrom(parameterTypes[i])) {
					lp = i;
					break;
				}
			}
			if (lp == -1) {
				return empty();
			} else {
				localeParameterPosition = OptionalInt.of(lp);
				valueParameterPosition = lp == 0 ? 1 : 0;
			}
		} else {
			localeParameterPosition = OptionalInt.empty();
			valueParameterPosition = -1;
		}
		return of(new ParsedArguments(valueParameterPosition, localeParameterPosition));
	}

	/**
	 * Represents parsed arguments of the method.
	 *
	 * @param valueParameterPosition index of the value parameter among method parameters
	 * @param localeParameterPosition index of the {@link Locale} parameter among method parameters (if present)
	 */
	 public record ParsedArguments(
		int valueParameterPosition,
		@Nonnull OptionalInt localeParameterPosition
	) {
	}

}
