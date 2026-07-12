/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025-2026
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

package io.evitadb.test.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.DATA_TYPE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the reflective field access contract of {@link ReflectionUtils}: setting and reading
 * private, inherited and non-static final instance fields regardless of accessibility modifiers,
 * and the failure modes for unknown fields and null values.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Tag(CONTRACT)
@Tag(DATA_TYPE)
@DisplayName("ReflectionUtils reflective field access")
class ReflectionUtilsTest {

	@Nested
	@Tag(CONTRACT)
	@Tag(DATA_TYPE)
	@DisplayName("setFieldValue")
	class SetFieldValue {

		@Test
		@DisplayName("sets a private instance field declared on the concrete class")
		void shouldSetPrivateInstanceField() {
			final Child instance = new Child("childValue");

			ReflectionUtils.setFieldValue(instance, "childField", "updated");

			assertEquals("updated", instance.getChildField());
		}

		@Test
		@DisplayName("sets a private field inherited from a superclass")
		void shouldSetInheritedField() {
			final Child instance = new Child("childValue");

			ReflectionUtils.setFieldValue(instance, "parentField", "updatedParent");

			assertEquals("updatedParent", instance.getParentField());
		}

		@Test
		@DisplayName("sets a non-static final instance field")
		void shouldSetNonStaticFinalInstanceField() {
			// the final field is assigned in the constructor so it is not a compile-time constant,
			// otherwise the getter would observe an inlined value rather than the reflective change
			final FinalFieldHolder instance = new FinalFieldHolder("initial");

			ReflectionUtils.setFieldValue(instance, "finalField", "reflectivelyChanged");

			assertEquals("reflectivelyChanged", instance.getFinalField());
		}

		@Test
		@DisplayName("throws IllegalArgumentException when the field is unknown")
		void shouldThrowWhenSettingUnknownField() {
			final Child instance = new Child("childValue");

			final IllegalArgumentException ex = assertThrows(
				IllegalArgumentException.class,
				() -> ReflectionUtils.setFieldValue(instance, "missingField", "value")
			);
			assertTrue(ex.getMessage().contains("missingField"));
			assertTrue(ex.getMessage().contains("not found in class hierarchy"));
		}
	}

	@Nested
	@Tag(CONTRACT)
	@Tag(DATA_TYPE)
	@DisplayName("getFieldValue")
	class GetFieldValue {

		@Test
		@DisplayName("reads a private instance field declared on the concrete class")
		void shouldReadPrivateInstanceField() {
			final Child instance = new Child("childValue");

			final String value = ReflectionUtils.getFieldValue(instance, "childField");

			assertEquals("childValue", value);
		}

		@Test
		@DisplayName("reads a private field inherited from a superclass")
		void shouldReadInheritedField() {
			final Child instance = new Child("childValue");

			final String value = ReflectionUtils.getFieldValue(instance, "parentField");

			assertEquals("parentInitial", value);
		}

		@Test
		@DisplayName("returns null when the field value is null")
		void shouldReturnNullWhenFieldValueIsNull() {
			final Child instance = new Child("childValue");

			final String value = ReflectionUtils.getFieldValue(instance, "nullableField");

			assertNull(value);
		}

		@Test
		@DisplayName("throws IllegalArgumentException when the field is unknown")
		void shouldThrowWhenGettingUnknownField() {
			final Child instance = new Child("childValue");

			final IllegalArgumentException ex = assertThrows(
				IllegalArgumentException.class,
				() -> ReflectionUtils.getFieldValue(instance, "missingField")
			);
			assertTrue(ex.getMessage().contains("missingField"));
			assertTrue(ex.getMessage().contains("not found in class hierarchy"));
		}
	}

	@Nested
	@Tag(CONTRACT)
	@Tag(DATA_TYPE)
	@DisplayName("getNonnullFieldValue")
	class GetNonnullFieldValue {

		@Test
		@DisplayName("returns the value when the field is present and non-null")
		void shouldReturnValueWhenFieldIsPresent() {
			final Child instance = new Child("childValue");

			final String value = ReflectionUtils.getNonnullFieldValue(instance, "childField");

			assertSame("childValue", value);
		}

		@Test
		@DisplayName("throws NullPointerException when the field value is null")
		void shouldThrowNullPointerExceptionWhenFieldValueIsNull() {
			final Child instance = new Child("childValue");

			final NullPointerException ex = assertThrows(
				NullPointerException.class,
				() -> ReflectionUtils.getNonnullFieldValue(instance, "nullableField")
			);
			assertTrue(ex.getMessage().contains("nullableField"));
			assertTrue(ex.getMessage().contains("cannot be null"));
		}
	}

	/**
	 * Superclass fixture exposing a private field and a permanently null field, used to exercise the
	 * class-hierarchy traversal of {@link ReflectionUtils}.
	 */
	private static class Parent {
		private String parentField = "parentInitial";
		@Nullable
		private final String nullableField = null;

		@Nonnull
		String getParentField() {
			return this.parentField;
		}
	}

	/**
	 * Concrete fixture declaring its own private field on top of the inherited {@link Parent} fields.
	 */
	private static class Child extends Parent {
		private String childField;

		Child(@Nonnull String childField) {
			this.childField = childField;
		}

		@Nonnull
		String getChildField() {
			return this.childField;
		}
	}

	/**
	 * Fixture holding a non-static final field assigned via the constructor, so the value is not a
	 * compile-time constant and the getter reflects reflective mutations.
	 */
	private static class FinalFieldHolder {
		private final String finalField;

		FinalFieldHolder(@Nonnull String finalField) {
			this.finalField = finalField;
		}

		@Nonnull
		String getFinalField() {
			return this.finalField;
		}
	}
}
