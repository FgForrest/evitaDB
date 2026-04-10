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

import static io.evitadb.api.query.QueryConstraints.attributeEquals;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link AttributeEquals} verifying construction, applicability, property accessors,
 * cloning, visitor support, string representation, and equality contract.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("AttributeEquals constraint")
class AttributeEqualsTest {

	@Nested
	@DisplayName("Construction")
	class ConstructionTest {

		@Test
		@DisplayName("should fail to use invalid data type")
		void shouldFailToUseInvalidDataType() {
			assertThrows(UnsupportedDataTypeException.class, () -> attributeEquals("abc", new MockObject()));
		}

		@Test
		@DisplayName("should automatically convert data type")
		void shouldAutomaticallyConvertDataType() {
			assertEquals(new BigDecimal("1.0"), attributeEquals("abc", 1f).getAttributeValue());
		}

		@Test
		@DisplayName("should create via factory method with correct properties")
		void shouldCreateViaFactoryClassWorkAsExpected() {
			final AttributeEquals eq = attributeEquals("abc", "def");

			assertEquals("abc", eq.getAttributeName());
			assertEquals("def", eq.getAttributeValue());
		}
	}

	@Nested
	@DisplayName("Applicability")
	class ApplicabilityTest {

		@Test
		@DisplayName("should recognize applicable and non-applicable instances")
		void shouldRecognizeApplicability() {
			assertFalse(new AttributeEquals("abc", null).isApplicable());
			assertFalse(new AttributeEquals(null, "abc").isApplicable());
			assertFalse(new AttributeEquals(null, null).isApplicable());
			assertTrue(attributeEquals("abc", "def").isApplicable());
		}
	}

	@Nested
	@DisplayName("Property accessors")
	class PropertyAccessorsTest {

		@Test
		@DisplayName("should return attribute name")
		void shouldReturnAttributeName() {
			final AttributeEquals eq = attributeEquals("testAttr", "val");

			assertEquals("testAttr", eq.getAttributeName());
		}

		@Test
		@DisplayName("should return attribute value")
		void shouldReturnAttributeValue() {
			final AttributeEquals eq = attributeEquals("abc", 42);

			assertEquals(42, (int) eq.getAttributeValue());
		}
	}

	@Nested
	@DisplayName("Cloning")
	class CloningTest {

		@Test
		@DisplayName("should produce equal but not same instance via cloneWithArguments")
		void shouldCloneWithArguments() {
			final AttributeEquals original = attributeEquals("abc", "def");
			final FilterConstraint clone = original.cloneWithArguments(new Serializable[]{"abc", "def"});

			assertEquals(original, clone);
			assertNotSame(original, clone);
			assertInstanceOf(AttributeEquals.class, clone);
		}
	}

	@Nested
	@DisplayName("Visitor support")
	class VisitorSupportTest {

		@Test
		@DisplayName("should accept visitor and call visit method")
		void shouldAcceptVisitor() {
			final AttributeEquals eq = attributeEquals("abc", "def");
			final AtomicReference<Constraint<?>> visited = new AtomicReference<>();
			eq.accept(new ConstraintVisitor() {
				@Override
				public void visit(@Nonnull Constraint<?> constraint) {
					visited.set(constraint);
				}
			});

			assertSame(eq, visited.get());
		}

		@Test
		@DisplayName("should return FilterConstraint class as type")
		void shouldReturnCorrectType() {
			final AttributeEquals eq = attributeEquals("abc", "def");

			assertEquals(FilterConstraint.class, eq.getType());
		}
	}

	@Nested
	@DisplayName("String representation")
	class ToStringTest {

		@Test
		@DisplayName("should produce expected toString format")
		void shouldToStringReturnExpectedFormat() {
			final AttributeEquals eq = attributeEquals("abc", "def");

			assertEquals("attributeEquals('abc','def')", eq.toString());
		}
	}

	@Nested
	@DisplayName("Equality and hashCode")
	class EqualityTest {

		@Test
		@DisplayName("should conform to equals and hashCode contract")
		void shouldConformToEqualsAndHashContract() {
			assertNotSame(attributeEquals("abc", "def"), attributeEquals("abc", "def"));
			assertEquals(attributeEquals("abc", "def"), attributeEquals("abc", "def"));
			assertNotEquals(attributeEquals("abc", "def"), attributeEquals("abc", "defe"));
			assertNotEquals(attributeEquals("abc", "def"), new AttributeEquals("abc", null));
			assertNotEquals(attributeEquals("abc", "def"), new AttributeEquals(null, "abc"));
			assertEquals(attributeEquals("abc", "def").hashCode(), attributeEquals("abc", "def").hashCode());
			assertNotEquals(attributeEquals("abc", "def").hashCode(), attributeEquals("abc", "defe").hashCode());
			assertNotEquals(
				attributeEquals("abc", "def").hashCode(),
				new AttributeEquals("abc", null).hashCode()
			);
			assertNotEquals(
				attributeEquals("abc", "def").hashCode(),
				new AttributeEquals(null, "abc").hashCode()
			);
		}
	}
}
