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

package io.evitadb.api.query.order;

import io.evitadb.dataType.SupportedEnum;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link OrderDirection} verifying enum values, their ordinals, valueOf behavior,
 * serialization round-trip, and the presence of the {@link SupportedEnum} annotation.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("OrderDirection enum")
class OrderDirectionTest {

	@Nested
	@DisplayName("Enum values")
	class EnumValuesTest {

		@Test
		@DisplayName("should have exactly two values")
		void shouldHaveExactlyTwoValues() {
			final OrderDirection[] values = OrderDirection.values();

			assertEquals(2, values.length);
		}

		@Test
		@DisplayName("should contain ASC value at ordinal 0")
		void shouldContainAscValue() {
			final OrderDirection asc = OrderDirection.ASC;

			assertEquals("ASC", asc.name());
			assertEquals(0, asc.ordinal());
		}

		@Test
		@DisplayName("should contain DESC value at ordinal 1")
		void shouldContainDescValue() {
			final OrderDirection desc = OrderDirection.DESC;

			assertEquals("DESC", desc.name());
			assertEquals(1, desc.ordinal());
		}
	}

	@Nested
	@DisplayName("valueOf resolution")
	class ValueOfTest {

		@Test
		@DisplayName("should resolve ASC by name")
		void shouldResolveAscByName() {
			final OrderDirection result = OrderDirection.valueOf("ASC");

			assertEquals(OrderDirection.ASC, result);
		}

		@Test
		@DisplayName("should resolve DESC by name")
		void shouldResolveDescByName() {
			final OrderDirection result = OrderDirection.valueOf("DESC");

			assertEquals(OrderDirection.DESC, result);
		}

		@Test
		@DisplayName("should throw for invalid name")
		void shouldThrowForInvalidName() {
			assertThrows(IllegalArgumentException.class, () -> OrderDirection.valueOf("INVALID"));
		}

		@Test
		@DisplayName("should throw for wrong case")
		void shouldThrowForWrongCase() {
			assertThrows(IllegalArgumentException.class, () -> OrderDirection.valueOf("asc"));
			assertThrows(IllegalArgumentException.class, () -> OrderDirection.valueOf("desc"));
		}
	}

	@Nested
	@DisplayName("Serialization")
	class SerializationTest {

		@Test
		@DisplayName("should survive serialization round-trip for ASC")
		void shouldSurviveSerializationRoundTripForAsc() throws Exception {
			final OrderDirection deserialized = serializeAndDeserialize(OrderDirection.ASC);

			assertSame(OrderDirection.ASC, deserialized);
		}

		@Test
		@DisplayName("should survive serialization round-trip for DESC")
		void shouldSurviveSerializationRoundTripForDesc() throws Exception {
			final OrderDirection deserialized = serializeAndDeserialize(OrderDirection.DESC);

			assertSame(OrderDirection.DESC, deserialized);
		}

		/**
		 * Serializes and deserializes the given enum constant via Java object serialization.
		 */
		private OrderDirection serializeAndDeserialize(OrderDirection value) throws Exception {
			final ByteArrayOutputStream baos = new ByteArrayOutputStream(256);
			try (final ObjectOutputStream oos = new ObjectOutputStream(baos)) {
				oos.writeObject(value);
			}
			try (final ObjectInputStream ois = new ObjectInputStream(
				new ByteArrayInputStream(baos.toByteArray())
			)) {
				return (OrderDirection) ois.readObject();
			}
		}
	}

	@Nested
	@DisplayName("Annotation")
	class AnnotationTest {

		@Test
		@DisplayName("should be annotated with @SupportedEnum")
		void shouldBeAnnotatedWithSupportedEnum() {
			final boolean hasAnnotation = OrderDirection.class.isAnnotationPresent(SupportedEnum.class);

			assertTrue(hasAnnotation);
		}
	}

	@Nested
	@DisplayName("Type hierarchy")
	class TypeHierarchyTest {

		@Test
		@DisplayName("should implement Serializable")
		void shouldImplementSerializable() {
			assertTrue(Serializable.class.isAssignableFrom(OrderDirection.class));
		}
	}
}
