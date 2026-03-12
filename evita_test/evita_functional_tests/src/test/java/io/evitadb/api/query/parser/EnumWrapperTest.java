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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link EnumWrapper} verifying parsing, conversion,
 * comparison, and equality behavior.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("EnumWrapper")
class EnumWrapperTest {

	/**
	 * Enum used only for testing wrapper to enum conversion.
	 */
	enum DummyEnum {
		VALUE_A, VALUE_B, VALUE_C
	}

	@Nested
	@DisplayName("Parsing")
	class Parsing {

		@Test
		@DisplayName("should parse single-word enum value")
		void shouldParseSingleWordEnumValue() {
			final EnumWrapper wrapper = EnumWrapper.fromString("PRODUCT");

			assertEquals("PRODUCT", wrapper.getValue());
		}

		@Test
		@DisplayName("should parse multi-word enum value")
		void shouldParseMultiWordEnumValue() {
			final EnumWrapper wrapper = EnumWrapper.fromString("WITH_TAX");

			assertEquals("WITH_TAX", wrapper.getValue());
		}

		@Test
		@DisplayName("should parse long multi-word enum value")
		void shouldParseLongMultiWordEnumValue() {
			final EnumWrapper wrapper = EnumWrapper.fromString("PRODUCT_WITH_ATTRIBUTES");

			assertEquals("PRODUCT_WITH_ATTRIBUTES", wrapper.getValue());
		}

		@Test
		@DisplayName("should reject lowercase enum value")
		void shouldRejectLowercaseEnumValue() {
			assertThrows(IllegalArgumentException.class, () -> EnumWrapper.fromString("product"));
		}

		@Test
		@DisplayName("should reject camelCase enum value")
		void shouldRejectCamelCaseEnumValue() {
			assertThrows(IllegalArgumentException.class, () -> EnumWrapper.fromString("withTax"));
		}

		@Test
		@DisplayName("should reject hyphenated enum value")
		void shouldRejectHyphenatedEnumValue() {
			assertThrows(IllegalArgumentException.class, () -> EnumWrapper.fromString("WITH-TAX"));
		}

		@Test
		@DisplayName("should reject trailing hyphen")
		void shouldRejectTrailingHyphen() {
			assertThrows(IllegalArgumentException.class, () -> EnumWrapper.fromString("product-"));
		}

		@Test
		@DisplayName("should reject leading underscore")
		void shouldRejectLeadingUnderscore() {
			assertThrows(IllegalArgumentException.class, () -> EnumWrapper.fromString("_product"));
		}

		@Test
		@DisplayName("should reject pure numeric value")
		void shouldRejectPureNumericValue() {
			assertThrows(IllegalArgumentException.class, () -> EnumWrapper.fromString("100"));
		}

		@Test
		@DisplayName("should accept enum values containing digits")
		void shouldAcceptEnumValuesContainingDigits() {
			final EnumWrapper http2 = EnumWrapper.fromString("HTTP_2");
			assertEquals("HTTP_2", http2.getValue());

			final EnumWrapper v1 = EnumWrapper.fromString("V1");
			assertEquals("V1", v1.getValue());

			final EnumWrapper aes256 = EnumWrapper.fromString("AES_256");
			assertEquals("AES_256", aes256.getValue());
		}
	}

	@Nested
	@DisplayName("Enum conversion")
	class EnumConversion {

		@Test
		@DisplayName("should convert wrapper to matching enum")
		void shouldConvertWrapperToMatchingEnum() {
			final DummyEnum result = EnumWrapper.fromString("VALUE_B").toEnum(DummyEnum.class);

			assertEquals(DummyEnum.VALUE_B, result);
		}

		@Test
		@DisplayName("should throw for non-matching enum value")
		void shouldThrowForNonMatchingEnumValue() {
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> EnumWrapper.fromString("VALUE_Z").toEnum(DummyEnum.class)
			);
		}
	}

	@Nested
	@DisplayName("Comparison")
	class Comparison {

		@Test
		@DisplayName("should order alphabetically before")
		void shouldOrderAlphabeticallyBefore() {
			final int result = EnumWrapper.fromString("APRODUCT").compareTo(EnumWrapper.fromString("BPRODUCT"));

			assertTrue(result < 0);
		}

		@Test
		@DisplayName("should order alphabetically after")
		void shouldOrderAlphabeticallyAfter() {
			final int result = EnumWrapper.fromString("BPRODUCT").compareTo(EnumWrapper.fromString("APRODUCT"));

			assertTrue(result > 0);
		}

		@Test
		@DisplayName("should compare equal values as zero")
		void shouldCompareEqualValuesAsZero() {
			final int result = EnumWrapper.fromString("PRODUCT").compareTo(EnumWrapper.fromString("PRODUCT"));

			assertEquals(0, result);
		}
	}

	@Nested
	@DisplayName("Equality and hashing")
	class EqualityAndHashing {

		@Test
		@DisplayName("should be equal for same value")
		void shouldBeEqualForSameValue() {
			final EnumWrapper a = EnumWrapper.fromString("VALUE_A");
			final EnumWrapper b = EnumWrapper.fromString("VALUE_A");

			assertEquals(a, b);
			assertEquals(a.hashCode(), b.hashCode());
		}

		@Test
		@DisplayName("should not be equal for different values")
		void shouldNotBeEqualForDifferentValues() {
			final EnumWrapper a = EnumWrapper.fromString("VALUE_A");
			final EnumWrapper b = EnumWrapper.fromString("VALUE_B");

			assertNotEquals(a, b);
		}
	}

	@Nested
	@DisplayName("canBeMappedTo()")
	class CanBeMappedTo {

		@Test
		@DisplayName("should return true for matching enum")
		void shouldReturnTrueForMatchingEnum() {
			final EnumWrapper wrapper = EnumWrapper.fromString("VALUE_A");

			assertTrue(wrapper.canBeMappedTo(DummyEnum.class));
		}

		@Test
		@DisplayName("should return false for non-matching enum")
		void shouldReturnFalseForNonMatchingEnum() {
			final EnumWrapper wrapper = EnumWrapper.fromString("NONEXISTENT");

			assertFalse(wrapper.canBeMappedTo(DummyEnum.class));
		}
	}

	@Test
	@DisplayName("toString() should return value string")
	void toStringShouldReturnValueString() {
		final EnumWrapper wrapper = EnumWrapper.fromString("WITH_TAX");

		assertEquals("WITH_TAX", wrapper.toString());
	}
}
