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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ParseContext} verifying argument handling,
 * parse mode management, and data type validation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("ParseContext")
class ParseContextTest {

	/**
	 * Type that is not supported by EvitaDataTypes,
	 * used to verify argument data type validation.
	 */
	private static class UnsupportedType {}

	/**
	 * Enum used for testing enum argument support.
	 */
	enum TestEnum { ALPHA, BETA }

	@Nested
	@DisplayName("Positional arguments")
	class PositionalArguments {

		@Test
		@DisplayName("should return positional arguments in order")
		void shouldReturnPositionalArgumentsInOrder() {
			final ParseContext context = new ParseContext("name", 1);

			assertEquals("name", context.getNextPositionalArgument());
			assertEquals(1, (Integer) context.getNextPositionalArgument());
		}

		@Test
		@DisplayName("should accept List constructor for positional arguments")
		void shouldAcceptListConstructorForPositionalArgs() {
			final ParseContext context = new ParseContext(List.of("hello", 42));

			assertEquals("hello", context.getNextPositionalArgument());
			assertEquals(42, (Integer) context.getNextPositionalArgument());
		}

		@Test
		@DisplayName("should return positional arguments alongside named arguments")
		void shouldReturnPositionalWithNamedArguments() {
			final ParseContext context = new ParseContext(Map.of("name", "code"), "name", 1);

			assertEquals("name", context.getNextPositionalArgument());
			assertEquals(1, (Integer) context.getNextPositionalArgument());
		}

		@Test
		@DisplayName("should accept List constructor with named args")
		void shouldAcceptListConstructorWithNamedArgs() {
			final ParseContext context = new ParseContext(Map.of("key", "val"), List.of("a", "b"));

			assertEquals("a", context.getNextPositionalArgument());
			assertEquals("b", context.getNextPositionalArgument());
		}

		@Test
		@DisplayName("should throw when no positional args provided")
		void shouldThrowWhenNoPositionalArgsProvided() {
			final ParseContext context = new ParseContext();

			assertThrows(EvitaInvalidUsageException.class, context::getNextPositionalArgument);
		}

		@Test
		@DisplayName("should throw when positional args exhausted")
		void shouldThrowWhenPositionalArgsExhausted() {
			final ParseContext context = new ParseContext("code");

			context.getNextPositionalArgument();

			assertThrows(EvitaInvalidUsageException.class, context::getNextPositionalArgument);
		}

		@Test
		@DisplayName("should throw for unsupported positional arg type")
		void shouldThrowForUnsupportedPositionalArgType() {
			final ParseContext context = new ParseContext(new UnsupportedType());

			assertThrows(EvitaInvalidUsageException.class, context::getNextPositionalArgument);
		}
	}

	@Nested
	@DisplayName("Named arguments")
	class NamedArguments {

		@Test
		@DisplayName("should return named arguments by name")
		void shouldReturnNamedArgumentsByName() {
			final ParseContext context = new ParseContext(Map.of("name", "code", "otherName", "validity"));

			assertEquals("validity", context.getNamedArgument("otherName"));
			assertEquals("code", context.getNamedArgument("name"));
		}

		@Test
		@DisplayName("should return named args when both types present")
		void shouldReturnNamedArgsWhenBothTypesPresent() {
			final ParseContext context = new ParseContext(
				Map.of("name", "code", "otherName", "validity"),
				"name", 1
			);

			assertEquals("validity", context.getNamedArgument("otherName"));
			assertEquals("code", context.getNamedArgument("name"));
		}

		@Test
		@DisplayName("should throw when no named args provided")
		void shouldThrowWhenNoNamedArgsProvided() {
			final ParseContext context = new ParseContext();

			assertThrows(EvitaInvalidUsageException.class, () -> context.getNamedArgument("name"));
		}

		@Test
		@DisplayName("should throw when named arg not found")
		void shouldThrowWhenNamedArgNotFound() {
			final ParseContext context = new ParseContext(Map.of("otherName", "validity"));

			assertThrows(EvitaInvalidUsageException.class, () -> context.getNamedArgument("name"));
		}

		@Test
		@DisplayName("should throw for unsupported named arg type")
		void shouldThrowForUnsupportedNamedArgType() {
			final ParseContext context = new ParseContext(Map.of("name", new UnsupportedType()));

			assertThrows(EvitaInvalidUsageException.class, () -> context.getNamedArgument("name"));
		}
	}

	@Nested
	@DisplayName("Parse mode handling")
	class ParseModeHandling {

		@Test
		@DisplayName("should default to SAFE mode")
		void shouldDefaultToSafeMode() {
			final ParseContext context = new ParseContext();

			assertEquals(ParseMode.SAFE, context.getMode());
		}

		@Test
		@DisplayName("should allow setting and getting mode")
		void shouldAllowSettingAndGettingMode() {
			final ParseContext context = new ParseContext();

			context.setMode(ParseMode.UNSAFE);

			assertEquals(ParseMode.UNSAFE, context.getMode());
		}
	}

	@Nested
	@DisplayName("Data type validation")
	class DataTypeValidation {

		@Test
		@DisplayName("should accept enum as positional argument")
		void shouldAcceptEnumAsPositionalArgument() {
			final ParseContext context = new ParseContext(TestEnum.ALPHA);

			final TestEnum result = context.getNextPositionalArgument();

			assertEquals(TestEnum.ALPHA, result);
		}

		@Test
		@DisplayName("should accept iterable as positional argument")
		void shouldAcceptIterableAsPositionalArgument() {
			final ParseContext context = new ParseContext((Object) List.of("a", "b"));

			final List<String> result = context.getNextPositionalArgument();

			assertEquals(List.of("a", "b"), result);
		}
	}
}
