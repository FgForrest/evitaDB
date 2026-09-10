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
 * Verifies conversions performed by {@link ByteCoercing} between the engine-side {@link Byte} and the client-side
 * GraphQL `Int`. The serialization direction is the one reachable from any query returning a `Byte` attribute, so
 * boundary values of the whole byte domain are asserted explicitly - the conversion widens a signed 8-bit value
 * into a 32-bit one and must not truncate or re-interpret the sign.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("ByteCoercing")
@Tag(GRAPHQL)
@Tag(EXTERNAL_API)
@Tag(DATA_TYPE)
class ByteCoercingTest {

	private final ByteCoercing coercing = new ByteCoercing();

	@Nested
	@DisplayName("serialize")
	class Serialize {

		@Test
		@DisplayName("converts a byte data fetcher result into an integer")
		void shouldSerializeByteWhenResultIsByte() {
			final Integer serialized = ByteCoercingTest.this.coercing.serialize((byte) 42);
			assertInstanceOf(Integer.class, serialized);
			assertEquals(Integer.valueOf(42), serialized);
		}

		@Test
		@DisplayName("preserves the whole byte domain including its sign")
		void shouldSerializeBoundaryValuesWhenResultIsByte() {
			assertEquals(Integer.valueOf(Byte.MIN_VALUE), ByteCoercingTest.this.coercing.serialize(Byte.MIN_VALUE));
			assertEquals(Integer.valueOf(Byte.MAX_VALUE), ByteCoercingTest.this.coercing.serialize(Byte.MAX_VALUE));
			assertEquals(Integer.valueOf(0), ByteCoercingTest.this.coercing.serialize((byte) 0));
			assertEquals(Integer.valueOf(-1), ByteCoercingTest.this.coercing.serialize((byte) -1));
		}

		@Test
		@DisplayName("rejects a data fetcher result that is not a byte")
		void shouldThrowExceptionWhenResultIsNotByte() {
			assertThrows(CoercingSerializeException.class, () -> ByteCoercingTest.this.coercing.serialize("42"));
			assertThrows(CoercingSerializeException.class, () -> ByteCoercingTest.this.coercing.serialize(42));
			assertThrows(CoercingSerializeException.class, () -> ByteCoercingTest.this.coercing.serialize((short) 42));
		}
	}

	@Nested
	@DisplayName("parseValue")
	class ParseValue {

		@Test
		@DisplayName("converts an integer input into a byte")
		void shouldParseIntegerInputWhenInputFitsIntoByte() {
			assertEquals(Byte.valueOf((byte) 42), ByteCoercingTest.this.coercing.parseValue(42));
			assertEquals(Byte.valueOf((byte) -42), ByteCoercingTest.this.coercing.parseValue(-42));
		}

		@Test
		@DisplayName("accepts both ends of the byte domain")
		void shouldParseBoundaryValuesWhenInputFitsIntoByte() {
			assertEquals(Byte.valueOf(Byte.MIN_VALUE), ByteCoercingTest.this.coercing.parseValue((int) Byte.MIN_VALUE));
			assertEquals(Byte.valueOf(Byte.MAX_VALUE), ByteCoercingTest.this.coercing.parseValue((int) Byte.MAX_VALUE));
		}

		@Test
		@DisplayName("rejects an integer that overflows the byte domain")
		void shouldThrowExceptionWhenInputOverflowsByte() {
			assertThrows(CoercingParseValueException.class, () -> ByteCoercingTest.this.coercing.parseValue(Byte.MAX_VALUE + 1));
			assertThrows(CoercingParseValueException.class, () -> ByteCoercingTest.this.coercing.parseValue(Byte.MIN_VALUE - 1));
		}

		@Test
		@DisplayName("rejects an input that is not an integer")
		void shouldThrowExceptionWhenInputIsNotInteger() {
			assertThrows(CoercingParseValueException.class, () -> ByteCoercingTest.this.coercing.parseValue("42"));
			assertThrows(CoercingParseValueException.class, () -> ByteCoercingTest.this.coercing.parseValue((byte) 42));
		}
	}

	@Nested
	@DisplayName("parseLiteral")
	class ParseLiteral {

		@Test
		@DisplayName("converts an int literal into a byte")
		void shouldParseIntLiteralWhenLiteralFitsIntoByte() {
			assertEquals(Byte.valueOf((byte) 42), ByteCoercingTest.this.coercing.parseLiteral(IntValue.of(42)));
			assertEquals(Byte.valueOf((byte) -42), ByteCoercingTest.this.coercing.parseLiteral(IntValue.of(-42)));
		}

		@Test
		@DisplayName("accepts int literals at both ends of the byte domain")
		void shouldParseBoundaryLiteralsWhenLiteralFitsIntoByte() {
			assertEquals(
				Byte.valueOf(Byte.MIN_VALUE),
				ByteCoercingTest.this.coercing.parseLiteral(IntValue.of(Byte.MIN_VALUE))
			);
			assertEquals(
				Byte.valueOf(Byte.MAX_VALUE),
				ByteCoercingTest.this.coercing.parseLiteral(IntValue.of(Byte.MAX_VALUE))
			);
		}

		@Test
		@DisplayName("rejects an int literal that overflows the byte domain")
		void shouldThrowExceptionWhenLiteralOverflowsByte() {
			assertThrows(
				CoercingParseLiteralException.class,
				() -> ByteCoercingTest.this.coercing.parseLiteral(IntValue.of(Byte.MAX_VALUE + 1))
			);
			assertThrows(
				CoercingParseLiteralException.class,
				() -> ByteCoercingTest.this.coercing.parseLiteral(IntValue.of(Byte.MIN_VALUE - 1))
			);
		}

		@Test
		@DisplayName("rejects a literal that is not an int literal")
		void shouldThrowExceptionWhenLiteralIsNotIntValue() {
			// a non-int literal is reported through CoercingParseValueException, which is the established behaviour
			// of this coercing - the assertion pins it so that a future change to the exception type is deliberate
			assertThrows(
				CoercingParseValueException.class,
				() -> ByteCoercingTest.this.coercing.parseLiteral(StringValue.of("42"))
			);
		}
	}
}
