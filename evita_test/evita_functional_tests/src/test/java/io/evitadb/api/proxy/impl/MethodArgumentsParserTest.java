/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

import io.evitadb.api.proxy.impl.MethodArgumentsParser.ParsedArguments;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link MethodArgumentsParser} which parses method signatures to identify
 * value and locale parameter positions.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("MethodArgumentsParser")
class MethodArgumentsParserTest {

	/** Predicate that accepts String and Integer types. */
	private static final Predicate<Class<?>> SUPPORTED_TYPES =
		type -> String.class.isAssignableFrom(type)
			|| Integer.class.isAssignableFrom(type)
			|| int.class.isAssignableFrom(type);

	/**
	 * Test interface with various method signatures for reflection-based testing.
	 */
	@SuppressWarnings("unused")
	private interface TestMethods {
		void singleStringParam(String value);
		void singleLocaleParam(Locale locale);
		void singleUnsupportedParam(Double value);
		void stringAndLocale(String value, Locale locale);
		void localeAndString(Locale locale, String value);
		void twoStrings(String a, String b);
		void noParams();
		void threeParams(String a, Locale locale, String b);
		void singleIntParam(int value);
		void intAndLocale(int value, Locale locale);
	}

	private static Method getMethod(String name, Class<?>... paramTypes) {
		try {
			return TestMethods.class.getDeclaredMethod(name, paramTypes);
		} catch (NoSuchMethodException e) {
			throw new RuntimeException(e);
		}
	}

	@Nested
	@DisplayName("Single parameter methods")
	class SingleParameterMethods {

		@Test
		@DisplayName("should parse single supported value parameter")
		void shouldParseSingleSupportedValueParameter() {
			final Method method = getMethod("singleStringParam", String.class);
			final Optional<ParsedArguments> result =
				MethodArgumentsParser.parseArguments(method, SUPPORTED_TYPES);

			assertTrue(result.isPresent());
			assertEquals(0, result.get().valueParameterPosition());
			assertFalse(result.get().localeParameterPosition().isPresent());
		}

		@Test
		@DisplayName("should parse single Locale parameter")
		void shouldParseSingleLocaleParameter() {
			final Method method = getMethod("singleLocaleParam", Locale.class);
			final Optional<ParsedArguments> result =
				MethodArgumentsParser.parseArguments(method, SUPPORTED_TYPES);

			assertTrue(result.isPresent());
			assertEquals(-1, result.get().valueParameterPosition());
			assertTrue(result.get().localeParameterPosition().isPresent());
			assertEquals(0, result.get().localeParameterPosition().getAsInt());
		}

		@Test
		@DisplayName("should return result with -1 value position for unsupported type")
		void shouldReturnResultWithNegativeValuePositionForUnsupportedType() {
			final Method method = getMethod(
				"singleUnsupportedParam", Double.class
			);
			final Optional<ParsedArguments> result =
				MethodArgumentsParser.parseArguments(method, SUPPORTED_TYPES);

			assertTrue(result.isPresent());
			assertEquals(-1, result.get().valueParameterPosition());
			assertFalse(result.get().localeParameterPosition().isPresent());
		}

		@Test
		@DisplayName("should parse single int parameter")
		void shouldParseSingleIntParameter() {
			final Method method = getMethod("singleIntParam", int.class);
			final Optional<ParsedArguments> result =
				MethodArgumentsParser.parseArguments(method, SUPPORTED_TYPES);

			assertTrue(result.isPresent());
			assertEquals(0, result.get().valueParameterPosition());
			assertFalse(result.get().localeParameterPosition().isPresent());
		}
	}

	@Nested
	@DisplayName("Two parameter methods")
	class TwoParameterMethods {

		@Test
		@DisplayName("should parse value followed by Locale")
		void shouldParseValueFollowedByLocale() {
			final Method method = getMethod(
				"stringAndLocale", String.class, Locale.class
			);
			final Optional<ParsedArguments> result =
				MethodArgumentsParser.parseArguments(method, SUPPORTED_TYPES);

			assertTrue(result.isPresent());
			assertEquals(0, result.get().valueParameterPosition());
			assertTrue(result.get().localeParameterPosition().isPresent());
			assertEquals(1, result.get().localeParameterPosition().getAsInt());
		}

		@Test
		@DisplayName("should parse Locale followed by value")
		void shouldParseLocaleFollowedByValue() {
			final Method method = getMethod(
				"localeAndString", Locale.class, String.class
			);
			final Optional<ParsedArguments> result =
				MethodArgumentsParser.parseArguments(method, SUPPORTED_TYPES);

			assertTrue(result.isPresent());
			assertEquals(1, result.get().valueParameterPosition());
			assertTrue(result.get().localeParameterPosition().isPresent());
			assertEquals(0, result.get().localeParameterPosition().getAsInt());
		}

		@Test
		@DisplayName("should return empty for two value params without Locale")
		void shouldReturnEmptyForTwoValueParamsWithoutLocale() {
			final Method method = getMethod(
				"twoStrings", String.class, String.class
			);
			final Optional<ParsedArguments> result =
				MethodArgumentsParser.parseArguments(method, SUPPORTED_TYPES);

			assertFalse(result.isPresent());
		}

		@Test
		@DisplayName("should parse int followed by Locale")
		void shouldParseIntFollowedByLocale() {
			final Method method = getMethod(
				"intAndLocale", int.class, Locale.class
			);
			final Optional<ParsedArguments> result =
				MethodArgumentsParser.parseArguments(method, SUPPORTED_TYPES);

			assertTrue(result.isPresent());
			assertEquals(0, result.get().valueParameterPosition());
			assertTrue(result.get().localeParameterPosition().isPresent());
			assertEquals(1, result.get().localeParameterPosition().getAsInt());
		}
	}

	@Nested
	@DisplayName("Edge cases")
	class EdgeCases {

		@Test
		@DisplayName("should return result with -1 for no parameters")
		void shouldReturnResultWithNegativeForNoParameters() {
			final Method method = getMethod("noParams");
			final Optional<ParsedArguments> result =
				MethodArgumentsParser.parseArguments(method, SUPPORTED_TYPES);

			assertTrue(result.isPresent());
			assertEquals(-1, result.get().valueParameterPosition());
			assertFalse(result.get().localeParameterPosition().isPresent());
		}

		@Test
		@DisplayName("should return result with -1 for three or more parameters")
		void shouldReturnResultWithNegativeForThreeOrMoreParameters() {
			final Method method = getMethod(
				"threeParams", String.class, Locale.class, String.class
			);
			final Optional<ParsedArguments> result =
				MethodArgumentsParser.parseArguments(method, SUPPORTED_TYPES);

			assertTrue(result.isPresent());
			assertEquals(-1, result.get().valueParameterPosition());
			assertFalse(result.get().localeParameterPosition().isPresent());
		}

		@Test
		@DisplayName("ParsedArguments record should have correct accessors")
		void parsedArgumentsRecordShouldHaveCorrectAccessors() {
			final ParsedArguments args = new ParsedArguments(
				2, OptionalInt.of(1)
			);

			assertEquals(2, args.valueParameterPosition());
			assertTrue(args.localeParameterPosition().isPresent());
			assertEquals(1, args.localeParameterPosition().getAsInt());
		}
	}
}
