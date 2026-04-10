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

import static io.evitadb.api.query.QueryConstraints.attributeGreaterThan;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link AttributeGreaterThan} verifying construction, applicability, property accessors,
 * cloning, visitor support, string representation, and equality contract.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("AttributeGreaterThan constraint")
class AttributeGreaterThanTest {

	@Nested
	@DisplayName("Construction")
	class ConstructionTest {

		@Test
		@DisplayName("should fail to use invalid data type")
		void shouldFailToUseInvalidDataType() {
			assertThrows(UnsupportedDataTypeException.class, () -> attributeGreaterThan("abc", new MockObject()));
		}

		@Test
		@DisplayName("should automatically convert data type")
		void shouldAutomaticallyConvertDataType() {
			assertEquals(new BigDecimal("1.0"), attributeGreaterThan("abc", 1f).getAttributeValue());
		}

		@Test
		@DisplayName("should create via factory method with correct properties")
		void shouldCreateViaFactoryClassWorkAsExpected() {
			final AttributeGreaterThan constraint = attributeGreaterThan("abc", "def");

			assertEquals("abc", constraint.getAttributeName());
			assertEquals("def", constraint.getAttributeValue());
		}
	}

	@Nested
	@DisplayName("Applicability")
	class ApplicabilityTest {

		@Test
		@DisplayName("should recognize applicable and non-applicable instances")
		void shouldRecognizeApplicability() {
			assertFalse(new AttributeGreaterThan("abc", null).isApplicable());
			assertFalse(new AttributeGreaterThan(null, "abc").isApplicable());
			assertFalse(new AttributeGreaterThan(null, null).isApplicable());
			assertTrue(attributeGreaterThan("abc", "def").isApplicable());
		}
	}

	@Nested
	@DisplayName("Property accessors")
	class PropertyAccessorsTest {

		@Test
		@DisplayName("should return attribute name")
		void shouldReturnAttributeName() {
			final AttributeGreaterThan constraint = attributeGreaterThan("testAttr", "val");

			assertEquals("testAttr", constraint.getAttributeName());
		}

		@Test
		@DisplayName("should return attribute value")
		void shouldReturnAttributeValue() {
			final AttributeGreaterThan constraint = attributeGreaterThan("abc", 42);

			assertEquals(42, (int) constraint.getAttributeValue());
		}
	}

	@Nested
	@DisplayName("Cloning")
	class CloningTest {

		@Test
		@DisplayName("should produce equal but not same instance via cloneWithArguments")
		void shouldCloneWithArguments() {
			final AttributeGreaterThan original = attributeGreaterThan("abc", "def");
			final FilterConstraint clone = original.cloneWithArguments(new Serializable[]{"abc", "def"});

			assertEquals(original, clone);
			assertNotSame(original, clone);
			assertInstanceOf(AttributeGreaterThan.class, clone);
		}
	}

	@Nested
	@DisplayName("Visitor support")
	class VisitorSupportTest {

		@Test
		@DisplayName("should accept visitor and call visit method")
		void shouldAcceptVisitor() {
			final AttributeGreaterThan constraint = attributeGreaterThan("abc", "def");
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
			final AttributeGreaterThan constraint = attributeGreaterThan("abc", "def");

			assertEquals(FilterConstraint.class, constraint.getType());
		}
	}

	@Nested
	@DisplayName("String representation")
	class ToStringTest {

		@Test
		@DisplayName("should produce expected toString format")
		void shouldToStringReturnExpectedFormat() {
			final AttributeGreaterThan constraint = attributeGreaterThan("abc", "def");

			assertEquals("attributeGreaterThan('abc','def')", constraint.toString());
		}
	}

	@Nested
	@DisplayName("Equality and hashCode")
	class EqualityTest {

		@Test
		@DisplayName("should conform to equals and hashCode contract")
		void shouldConformToEqualsAndHashContract() {
			assertNotSame(attributeGreaterThan("abc", "def"), attributeGreaterThan("abc", "def"));
			assertEquals(attributeGreaterThan("abc", "def"), attributeGreaterThan("abc", "def"));
			assertNotEquals(attributeGreaterThan("abc", "def"), attributeGreaterThan("abc", "defe"));
			assertNotEquals(attributeGreaterThan("abc", "def"), new AttributeGreaterThan("abc", null));
			assertNotEquals(attributeGreaterThan("abc", "def"), new AttributeGreaterThan(null, "abc"));
			assertEquals(
				attributeGreaterThan("abc", "def").hashCode(),
				attributeGreaterThan("abc", "def").hashCode()
			);
			assertNotEquals(
				attributeGreaterThan("abc", "def").hashCode(),
				attributeGreaterThan("abc", "defe").hashCode()
			);
			assertNotEquals(
				attributeGreaterThan("abc", "def").hashCode(),
				new AttributeGreaterThan("abc", null).hashCode()
			);
			assertNotEquals(
				attributeGreaterThan("abc", "def").hashCode(),
				new AttributeGreaterThan(null, "abc").hashCode()
			);
		}
	}
}
