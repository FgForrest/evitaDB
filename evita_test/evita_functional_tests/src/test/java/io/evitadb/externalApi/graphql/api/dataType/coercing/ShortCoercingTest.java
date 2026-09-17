/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.externalApi.graphql.api.dataType.coercing;

import graphql.language.IntValue;
import graphql.language.StringValue;
import graphql.schema.CoercingParseLiteralException;
import graphql.schema.CoercingParseValueException;
import graphql.schema.CoercingSerializeException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static io.evitadb.test.TestTags.DATA_TYPE;
import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.GRAPHQL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies conversions performed by {@link ShortCoercing} between the engine-side {@link Short} and the client-side
 * GraphQL `Int`. The serialization direction is the one reachable from any query returning a `Short` attribute, so
 * boundary values of the whole short domain are asserted explicitly - the conversion widens a signed 16-bit value
 * into a 32-bit one and must not truncate or re-interpret the sign.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("ShortCoercing")
@Tag(GRAPHQL)
@Tag(EXTERNAL_API)
@Tag(DATA_TYPE)
class ShortCoercingTest {

	private final ShortCoercing coercing = new ShortCoercing();

	@Nested
	@DisplayName("serialize")
	class Serialize {

		@Test
		@DisplayName("converts a short data fetcher result into an integer")
		void shouldSerializeShortWhenResultIsShort() {
			final Integer serialized = ShortCoercingTest.this.coercing.serialize((short) 4200);
			assertInstanceOf(Integer.class, serialized);
			assertEquals(Integer.valueOf(4200), serialized);
		}

		@Test
		@DisplayName("preserves the whole short domain including its sign")
		void shouldSerializeBoundaryValuesWhenResultIsShort() {
			assertEquals(Integer.valueOf(Short.MIN_VALUE), ShortCoercingTest.this.coercing.serialize(Short.MIN_VALUE));
			assertEquals(Integer.valueOf(Short.MAX_VALUE), ShortCoercingTest.this.coercing.serialize(Short.MAX_VALUE));
			assertEquals(Integer.valueOf(0), ShortCoercingTest.this.coercing.serialize((short) 0));
			assertEquals(Integer.valueOf(-1), ShortCoercingTest.this.coercing.serialize((short) -1));
		}

		@Test
		@DisplayName("rejects a data fetcher result that is not a short")
		void shouldThrowExceptionWhenResultIsNotShort() {
			assertThrows(CoercingSerializeException.class, () -> ShortCoercingTest.this.coercing.serialize("42"));
			assertThrows(CoercingSerializeException.class, () -> ShortCoercingTest.this.coercing.serialize(42));
			assertThrows(CoercingSerializeException.class, () -> ShortCoercingTest.this.coercing.serialize((byte) 42));
		}
	}

	@Nested
	@DisplayName("parseValue")
	class ParseValue {

		@Test
		@DisplayName("converts an integer input into a short")
		void shouldParseIntegerInputWhenInputFitsIntoShort() {
			assertEquals(Short.valueOf((short) 4200), ShortCoercingTest.this.coercing.parseValue(4200));
			assertEquals(Short.valueOf((short) -4200), ShortCoercingTest.this.coercing.parseValue(-4200));
		}

		@Test
		@DisplayName("accepts both ends of the short domain")
		void shouldParseBoundaryValuesWhenInputFitsIntoShort() {
			assertEquals(
				Short.valueOf(Short.MIN_VALUE),
				ShortCoercingTest.this.coercing.parseValue((int) Short.MIN_VALUE)
			);
			assertEquals(
				Short.valueOf(Short.MAX_VALUE),
				ShortCoercingTest.this.coercing.parseValue((int) Short.MAX_VALUE)
			);
		}

		@Test
		@DisplayName("rejects an integer that overflows the short domain")
		void shouldThrowExceptionWhenInputOverflowsShort() {
			assertThrows(CoercingParseValueException.class, () -> ShortCoercingTest.this.coercing.parseValue(Short.MAX_VALUE + 1));
			assertThrows(CoercingParseValueException.class, () -> ShortCoercingTest.this.coercing.parseValue(Short.MIN_VALUE - 1));
		}

		@Test
		@DisplayName("rejects an input that is not an integer")
		void shouldThrowExceptionWhenInputIsNotInteger() {
			assertThrows(CoercingParseValueException.class, () -> ShortCoercingTest.this.coercing.parseValue("42"));
			assertThrows(CoercingParseValueException.class, () -> ShortCoercingTest.this.coercing.parseValue((short) 42));
		}
	}

	@Nested
	@DisplayName("parseLiteral")
	class ParseLiteral {

		@Test
		@DisplayName("converts an int literal into a short")
		void shouldParseIntLiteralWhenLiteralFitsIntoShort() {
			assertEquals(Short.valueOf((short) 4200), ShortCoercingTest.this.coercing.parseLiteral(IntValue.of(4200)));
			assertEquals(Short.valueOf((short) -4200), ShortCoercingTest.this.coercing.parseLiteral(IntValue.of(-4200)));
		}

		@Test
		@DisplayName("accepts int literals at both ends of the short domain")
		void shouldParseBoundaryLiteralsWhenLiteralFitsIntoShort() {
			assertEquals(
				Short.valueOf(Short.MIN_VALUE),
				ShortCoercingTest.this.coercing.parseLiteral(IntValue.of(Short.MIN_VALUE))
			);
			assertEquals(
				Short.valueOf(Short.MAX_VALUE),
				ShortCoercingTest.this.coercing.parseLiteral(IntValue.of(Short.MAX_VALUE))
			);
		}

		@Test
		@DisplayName("rejects an int literal that overflows the short domain")
		void shouldThrowExceptionWhenLiteralOverflowsShort() {
			assertThrows(
				CoercingParseLiteralException.class,
				() -> ShortCoercingTest.this.coercing.parseLiteral(IntValue.of(Short.MAX_VALUE + 1))
			);
			assertThrows(
				CoercingParseLiteralException.class,
				() -> ShortCoercingTest.this.coercing.parseLiteral(IntValue.of(Short.MIN_VALUE - 1))
			);
		}

		@Test
		@DisplayName("rejects a literal that is not an int literal")
		void shouldThrowExceptionWhenLiteralIsNotIntValue() {
			// a non-int literal is reported through CoercingParseValueException, which is the established behaviour
			// of this coercing - the assertion pins it so that a future change to the exception type is deliberate
			assertThrows(
				CoercingParseValueException.class,
				() -> ShortCoercingTest.this.coercing.parseLiteral(StringValue.of("42"))
			);
		}
	}
}
