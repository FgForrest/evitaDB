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
 * Tests for {@link TraversalMode} verifying enum values, their ordinals, valueOf behavior,
 * serialization round-trip, and the presence of the {@link SupportedEnum} annotation.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("TraversalMode enum")
class TraversalModeTest {

	@Nested
	@DisplayName("Enum values")
	class EnumValuesTest {

		@Test
		@DisplayName("should have exactly two values")
		void shouldHaveExactlyTwoValues() {
			final TraversalMode[] values = TraversalMode.values();

			assertEquals(2, values.length);
		}

		@Test
		@DisplayName("should contain DEPTH_FIRST value at ordinal 0")
		void shouldContainDepthFirstValue() {
			final TraversalMode depthFirst = TraversalMode.DEPTH_FIRST;

			assertEquals("DEPTH_FIRST", depthFirst.name());
			assertEquals(0, depthFirst.ordinal());
		}

		@Test
		@DisplayName("should contain BREADTH_FIRST value at ordinal 1")
		void shouldContainBreadthFirstValue() {
			final TraversalMode breadthFirst = TraversalMode.BREADTH_FIRST;

			assertEquals("BREADTH_FIRST", breadthFirst.name());
			assertEquals(1, breadthFirst.ordinal());
		}
	}

	@Nested
	@DisplayName("valueOf resolution")
	class ValueOfTest {

		@Test
		@DisplayName("should resolve DEPTH_FIRST by name")
		void shouldResolveDepthFirstByName() {
			final TraversalMode result = TraversalMode.valueOf("DEPTH_FIRST");

			assertEquals(TraversalMode.DEPTH_FIRST, result);
		}

		@Test
		@DisplayName("should resolve BREADTH_FIRST by name")
		void shouldResolveBreadthFirstByName() {
			final TraversalMode result = TraversalMode.valueOf("BREADTH_FIRST");

			assertEquals(TraversalMode.BREADTH_FIRST, result);
		}

		@Test
		@DisplayName("should throw for invalid name")
		void shouldThrowForInvalidName() {
			assertThrows(IllegalArgumentException.class, () -> TraversalMode.valueOf("INVALID"));
		}

		@Test
		@DisplayName("should throw for wrong case")
		void shouldThrowForWrongCase() {
			assertThrows(
				IllegalArgumentException.class,
				() -> TraversalMode.valueOf("depth_first")
			);
			assertThrows(
				IllegalArgumentException.class,
				() -> TraversalMode.valueOf("breadth_first")
			);
		}
	}

	@Nested
	@DisplayName("Serialization")
	class SerializationTest {

		@Test
		@DisplayName("should survive serialization round-trip for DEPTH_FIRST")
		void shouldSurviveSerializationRoundTripForDepthFirst() throws Exception {
			final TraversalMode deserialized = serializeAndDeserialize(TraversalMode.DEPTH_FIRST);

			assertSame(TraversalMode.DEPTH_FIRST, deserialized);
		}

		@Test
		@DisplayName("should survive serialization round-trip for BREADTH_FIRST")
		void shouldSurviveSerializationRoundTripForBreadthFirst() throws Exception {
			final TraversalMode deserialized = serializeAndDeserialize(TraversalMode.BREADTH_FIRST);

			assertSame(TraversalMode.BREADTH_FIRST, deserialized);
		}

		/**
		 * Serializes and deserializes the given enum constant via Java object serialization.
		 */
		private TraversalMode serializeAndDeserialize(TraversalMode value) throws Exception {
			final ByteArrayOutputStream baos = new ByteArrayOutputStream(256);
			try (final ObjectOutputStream oos = new ObjectOutputStream(baos)) {
				oos.writeObject(value);
			}
			try (final ObjectInputStream ois = new ObjectInputStream(
				new ByteArrayInputStream(baos.toByteArray())
			)) {
				return (TraversalMode) ois.readObject();
			}
		}
	}

	@Nested
	@DisplayName("Annotation")
	class AnnotationTest {

		@Test
		@DisplayName("should be annotated with @SupportedEnum")
		void shouldBeAnnotatedWithSupportedEnum() {
			final boolean hasAnnotation = TraversalMode.class.isAnnotationPresent(SupportedEnum.class);

			assertTrue(hasAnnotation);
		}
	}

	@Nested
	@DisplayName("Type hierarchy")
	class TypeHierarchyTest {

		@Test
		@DisplayName("should implement Serializable")
		void shouldImplementSerializable() {
			assertTrue(Serializable.class.isAssignableFrom(TraversalMode.class));
		}
	}
}
