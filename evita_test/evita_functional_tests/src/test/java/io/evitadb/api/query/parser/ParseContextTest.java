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

import io.evitadb.api.query.parser.exception.EvitaSyntaxException;
import io.evitadb.exception.EvitaInvalidUsageException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.*;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.QUERY;

/**
 * Tests for {@link ParseContext} verifying argument handling,
 * parse mode management, and data type validation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("ParseContext")
@Tag(CONTRACT)
@Tag(QUERY)
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

		/**
		 * The data type check reads the items before the visitor does, so an iterable that can be read only once would
		 * reach the visitor empty and silently turn into an empty list.
		 */
		@Test
		@DisplayName("should refuse an iterable that is not a collection")
		void shouldRefuseIterableThatIsNotCollection() {
			final Iterator<Integer> once = new ArrayList<>(List.of(7)).iterator();
			// serializable, so that only the iterable check can refuse it
			final Iterable<Integer> readOnce = (Iterable<Integer> & Serializable) () -> once;

			final EvitaInvalidUsageException positional = assertThrows(
				EvitaInvalidUsageException.class, () -> new ParseContext((Object) readOnce).getNextPositionalArgument()
			);
			final ParseContext namedContext = new ParseContext(Map.of("ids", readOnce));
			final EvitaInvalidUsageException named = assertThrows(
				EvitaInvalidUsageException.class, () -> namedContext.getNamedArgument("ids")
			);

			assertTrue(positional.getMessage().contains("Bind a list or an array"), positional.getMessage());
			assertTrue(named.getMessage().contains("Bind a list or an array"), named.getMessage());
		}

		/**
		 * A collection view such as the values of a map is not serializable itself, but its items are what the query
		 * needs, so it is passed on as a list.
		 */
		@Test
		@DisplayName("should accept a collection that is not serializable as a list")
		void shouldAcceptCollectionThatIsNotSerializable() {
			final Map<String, Integer> ids = new HashMap<>(Map.of("first", 1));

			final List<Integer> positional = new ParseContext((Object) ids.values()).getNextPositionalArgument();
			final List<Integer> named = new ParseContext(Map.of("ids", ids.values())).getNamedArgument("ids");

			assertEquals(List.of(1), positional);
			assertEquals(List.of(1), named);
		}

		/**
		 * The refusal names the type that cannot be bound, whether it is the scalar itself, the declared component of
		 * an array or an item of a collection.
		 */
		@Test
		@DisplayName("should name the unsupported scalar, array component and collection item type")
		void shouldNameUnsupportedType() {
			final String typeName = UnsupportedType.class.getTypeName();

			final EvitaInvalidUsageException scalar = assertThrows(
				EvitaInvalidUsageException.class,
				() -> new ParseContext(new UnsupportedType()).getNextPositionalArgument()
			);
			// empty, so that only the component type check can refuse it
			final EvitaInvalidUsageException component = assertThrows(
				EvitaInvalidUsageException.class,
				() -> new ParseContext((Object) new UnsupportedType[0]).getNextPositionalArgument()
			);
			final ParseContext namedContext = new ParseContext(Map.of("items", List.of(new UnsupportedType())));
			final EvitaInvalidUsageException item = assertThrows(
				EvitaInvalidUsageException.class, () -> namedContext.getNamedArgument("items")
			);

			assertTrue(scalar.getMessage().contains(typeName), scalar.getMessage());
			assertTrue(component.getMessage().contains(typeName), component.getMessage());
			assertTrue(item.getMessage().contains(typeName), item.getMessage());
		}
	}

	@Nested
	@DisplayName("Unused positional arguments")
	class UnusedPositionalArguments {

		@Test
		@DisplayName("should accept a context without positional arguments or with all of them used")
		void shouldAcceptWhenNoPositionalArgumentIsLeft() {
			final ParseContext withoutArguments = new ParseContext();
			final ParseContext withoutPositionalArguments = new ParseContext(Map.of("unused", "value"));
			final ParseContext withEmptyPositionalArguments = new ParseContext(List.of());
			final ParseContext allUsed = new ParseContext("a", 1);
			allUsed.getNextPositionalArgument();
			allUsed.getNextPositionalArgument();

			assertDoesNotThrow(withoutArguments::assertAllPositionalArgumentsUsed);
			assertDoesNotThrow(withoutPositionalArguments::assertAllPositionalArgumentsUsed);
			assertDoesNotThrow(withEmptyPositionalArguments::assertAllPositionalArgumentsUsed);
			assertDoesNotThrow(allUsed::assertAllPositionalArgumentsUsed);
		}

		@Test
		@DisplayName("should refuse positional arguments left over, naming how many were passed and used")
		void shouldRefuseLeftOverPositionalArguments() {
			final ParseContext context = new ParseContext("a", 1, 2);
			context.getNextPositionalArgument();
			context.getNextPositionalArgument();

			final EvitaSyntaxException exception = assertThrows(
				EvitaSyntaxException.class, context::assertAllPositionalArgumentsUsed
			);

			assertTrue(exception.getMessage().contains("3 positional arguments"), exception.getMessage());
			assertTrue(exception.getMessage().contains("only 2 positional parameters"), exception.getMessage());
		}
	}
}
