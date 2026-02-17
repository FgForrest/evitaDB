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

package io.evitadb.api.query.filter;

import io.evitadb.dataType.SupportedEnum;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link AttributeSpecialValue} verifying enum values, their ordinals, valueOf behavior,
 * and the presence of the {@link SupportedEnum} annotation.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("AttributeSpecialValue enum")
class AttributeSpecialValueTest {

	@Nested
	@DisplayName("Enum values")
	class EnumValuesTest {

		@Test
		@DisplayName("should have exactly two values")
		void shouldHaveExactlyTwoValues() {
			final AttributeSpecialValue[] values = AttributeSpecialValue.values();

			assertEquals(2, values.length);
		}

		@Test
		@DisplayName("should contain NULL value")
		void shouldContainNullValue() {
			final AttributeSpecialValue nullValue = AttributeSpecialValue.NULL;

			assertEquals("NULL", nullValue.name());
			assertEquals(0, nullValue.ordinal());
		}

		@Test
		@DisplayName("should contain NOT_NULL value")
		void shouldContainNotNullValue() {
			final AttributeSpecialValue notNullValue = AttributeSpecialValue.NOT_NULL;

			assertEquals("NOT_NULL", notNullValue.name());
			assertEquals(1, notNullValue.ordinal());
		}
	}

	@Nested
	@DisplayName("valueOf resolution")
	class ValueOfTest {

		@Test
		@DisplayName("should resolve NULL by name")
		void shouldResolveNullByName() {
			final AttributeSpecialValue result = AttributeSpecialValue.valueOf("NULL");

			assertEquals(AttributeSpecialValue.NULL, result);
		}

		@Test
		@DisplayName("should resolve NOT_NULL by name")
		void shouldResolveNotNullByName() {
			final AttributeSpecialValue result = AttributeSpecialValue.valueOf("NOT_NULL");

			assertEquals(AttributeSpecialValue.NOT_NULL, result);
		}

		@Test
		@DisplayName("should throw for invalid name")
		void shouldThrowForInvalidName() {
			assertThrows(IllegalArgumentException.class, () -> AttributeSpecialValue.valueOf("INVALID"));
		}

		@Test
		@DisplayName("should throw for wrong case")
		void shouldThrowForWrongCase() {
			assertThrows(IllegalArgumentException.class, () -> AttributeSpecialValue.valueOf("null"));
			assertThrows(IllegalArgumentException.class, () -> AttributeSpecialValue.valueOf("not_null"));
		}
	}

	@Nested
	@DisplayName("Annotation")
	class AnnotationTest {

		@Test
		@DisplayName("should be annotated with @SupportedEnum")
		void shouldBeAnnotatedWithSupportedEnum() {
			final boolean hasAnnotation = AttributeSpecialValue.class.isAnnotationPresent(SupportedEnum.class);

			assertTrue(hasAnnotation);
		}
	}
}
