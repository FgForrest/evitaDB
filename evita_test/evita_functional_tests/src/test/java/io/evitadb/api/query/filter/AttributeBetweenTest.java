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

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.ConstraintVisitor;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.dataType.exception.UnsupportedDataTypeException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.api.query.QueryConstraints.attributeBetween;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link AttributeBetween} verifying construction, applicability, property accessors,
 * cloning, visitor support, string representation, and equality contract.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("AttributeBetween constraint")
class AttributeBetweenTest {

	@Nested
	@DisplayName("Construction")
	class ConstructionTest {

		@Test
		@DisplayName("should fail to use invalid data type")
		void shouldFailToUseInvalidDataType() {
			assertThrows(
				UnsupportedDataTypeException.class,
				() -> attributeBetween("abc", new MockObject(), new MockObject())
			);
		}

		@Test
		@DisplayName("should automatically convert data type")
		void shouldAutomaticallyConvertDataType() {
			assertEquals(new BigDecimal("1.0"), attributeBetween("abc", 1f, 2f).getFrom());
			assertEquals(new BigDecimal("2.0"), attributeBetween("abc", 1f, 2f).getTo());
		}

		@Test
		@DisplayName("should create via factory method with correct properties")
		void shouldCreateViaFactoryClassWorkAsExpected() {
			final AttributeBetween constraint = attributeBetween("abc", 1, 2);

			assertEquals("abc", constraint.getAttributeName());
			assertEquals(Integer.valueOf(1), constraint.getFrom());
			assertEquals(Integer.valueOf(2), constraint.getTo());
		}
	}

	@Nested
	@DisplayName("Applicability")
	class ApplicabilityTest {

		@Test
		@DisplayName("should recognize applicable and non-applicable instances")
		void shouldRecognizeApplicability() {
			assertNull(attributeBetween("abc", null, null));
			assertFalse(new AttributeBetween("abc", null, null).isApplicable());
			assertFalse(new AttributeBetween(null, "abc", "abc").isApplicable());
			assertFalse(new AttributeBetween(null, null, null).isApplicable());
			assertTrue(attributeBetween("abc", "def", "def").isApplicable());
			assertTrue(attributeBetween("abc", "def", null).isApplicable());
			assertTrue(attributeBetween("abc", null, "def").isApplicable());
		}
	}

	@Nested
	@DisplayName("Property accessors")
	class PropertyAccessorsTest {

		@Test
		@DisplayName("should return attribute name")
		void shouldReturnAttributeName() {
			final AttributeBetween constraint = attributeBetween("testAttr", 1, 10);

			assertEquals("testAttr", constraint.getAttributeName());
		}

		@Test
		@DisplayName("should return from value")
		void shouldReturnFromValue() {
			final AttributeBetween constraint = attributeBetween("abc", 5, 10);

			assertEquals(5, (int) constraint.getFrom());
		}

		@Test
		@DisplayName("should return to value")
		void shouldReturnToValue() {
			final AttributeBetween constraint = attributeBetween("abc", 5, 10);

			assertEquals(10, (int) constraint.getTo());
		}

		@Test
		@DisplayName("should return null from when from is null")
		void shouldReturnNullFrom() {
			final AttributeBetween constraint = attributeBetween("abc", null, "def");

			assertNull(constraint.getFrom());
		}

		@Test
		@DisplayName("should return null to when to is null")
		void shouldReturnNullTo() {
			final AttributeBetween constraint = attributeBetween("abc", "def", null);

			assertNull(constraint.getTo());
		}
	}

	@Nested
	@DisplayName("Cloning")
	class CloningTest {

		@Test
		@DisplayName("should produce equal but not same instance via cloneWithArguments")
		void shouldCloneWithArguments() {
			final AttributeBetween original = attributeBetween("abc", "def", "ghi");
			final FilterConstraint clone = original.cloneWithArguments(new Serializable[]{"abc", "def", "ghi"});

			assertEquals(original, clone);
			assertNotSame(original, clone);
			assertInstanceOf(AttributeBetween.class, clone);
		}
	}

	@Nested
	@DisplayName("Visitor support")
	class VisitorSupportTest {

		@Test
		@DisplayName("should accept visitor and call visit method")
		void shouldAcceptVisitor() {
			final AttributeBetween constraint = attributeBetween("abc", 1, 10);
			final AtomicReference<Constraint<?>> visited = new AtomicReference<>();
			constraint.accept(new ConstraintVisitor() {
				@Override
				public void visit(@Nonnull Constraint<?> c) {
					visited.set(c);
				}
			});

			assertSame(constraint, visited.get());
		}

		@Test
		@DisplayName("should return FilterConstraint class as type")
		void shouldReturnCorrectType() {
			final AttributeBetween constraint = attributeBetween("abc", 1, 10);

			assertEquals(FilterConstraint.class, constraint.getType());
		}
	}

	@Nested
	@DisplayName("String representation")
	class ToStringTest {

		@Test
		@DisplayName("should produce expected toString format with both bounds")
		void shouldToStringReturnExpectedFormat() {
			assertEquals("attributeBetween('abc','def','def')", attributeBetween("abc", "def", "def").toString());
		}

		@Test
		@DisplayName("should produce expected toString format when from is null")
		void shouldToStringReturnExpectedFormatWhenFromIsNull() {
			assertEquals("attributeBetween('abc',<NULL>,'def')", attributeBetween("abc", null, "def").toString());
		}

		@Test
		@DisplayName("should produce expected toString format when to is null")
		void shouldToStringReturnExpectedFormatWhenToIsNull() {
			assertEquals("attributeBetween('abc','def',<NULL>)", attributeBetween("abc", "def", null).toString());
		}
	}

	@Nested
	@DisplayName("Equality and hashCode")
	class EqualityTest {

		@Test
		@DisplayName("should conform to equals and hashCode contract")
		void shouldConformToEqualsAndHashContract() {
			assertNotSame(attributeBetween("abc", "def", "def"), attributeBetween("abc", "def", "def"));
			assertEquals(attributeBetween("abc", "def", "def"), attributeBetween("abc", "def", "def"));
			assertNotEquals(attributeBetween("abc", "def", "def"), attributeBetween("abc", "defe", "defe"));
			assertNotEquals(attributeBetween("abc", "def", "def"), attributeBetween("abc", null, null));
			assertNotEquals(attributeBetween("abc", "def", "def"), attributeBetween(null, "abc", "abc"));
			assertEquals(attributeBetween("abc", null, "def"), attributeBetween("abc", null, "def"));
			assertEquals(
				attributeBetween("abc", "def", "def").hashCode(),
				attributeBetween("abc", "def", "def").hashCode()
			);
			assertNotEquals(
				attributeBetween("abc", "def", "def").hashCode(),
				attributeBetween("abc", "defe", "defe").hashCode()
			);
			assertNotEquals(
				attributeBetween("abc", "def", "def").hashCode(),
				new AttributeBetween("abc", null, null).hashCode()
			);
			assertNotEquals(
				attributeBetween("abc", "def", "def").hashCode(),
				new AttributeBetween(null, "abc", "abc").hashCode()
			);
			assertEquals(
				attributeBetween("abc", null, "def").hashCode(),
				attributeBetween("abc", null, "def").hashCode()
			);
		}
	}
}
