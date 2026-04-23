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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.api.query.QueryConstraints.attributeIs;
import static io.evitadb.api.query.QueryConstraints.attributeIsNotNull;
import static io.evitadb.api.query.QueryConstraints.attributeIsNull;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link AttributeIs} verifying construction, applicability, property accessors,
 * cloning, visitor support, string representation, and equality contract.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("AttributeIs constraint")
class AttributeIsTest {

	@Nested
	@DisplayName("Construction")
	class ConstructionTest {

		@Test
		@DisplayName("should create via factory method with NOT_NULL special value")
		void shouldCreateViaFactoryClassWorkAsExpected() {
			final AttributeIs constraint = attributeIs("married", AttributeSpecialValue.NOT_NULL);

			assertEquals("married", constraint.getAttributeName());
			assertEquals(AttributeSpecialValue.NOT_NULL, constraint.getAttributeSpecialValue());
		}

		@Test
		@DisplayName("should create via attributeIsNull convenience method")
		void shouldCreateViaIsNullFactory() {
			final AttributeIs constraint = attributeIsNull("married");

			assertEquals("married", constraint.getAttributeName());
			assertEquals(AttributeSpecialValue.NULL, constraint.getAttributeSpecialValue());
		}

		@Test
		@DisplayName("should create via attributeIsNotNull convenience method")
		void shouldCreateViaIsNotNullFactory() {
			final AttributeIs constraint = attributeIsNotNull("married");

			assertEquals("married", constraint.getAttributeName());
			assertEquals(AttributeSpecialValue.NOT_NULL, constraint.getAttributeSpecialValue());
		}
	}

	@Nested
	@DisplayName("Applicability")
	class ApplicabilityTest {

		@Test
		@DisplayName("should be applicable with valid attribute name and special value")
		void shouldRecognizeApplicability() {
			assertTrue(attributeIsNull("married").isApplicable());
			assertTrue(attributeIsNotNull("married").isApplicable());
		}

		@Test
		@DisplayName("should not be applicable when attribute name is null")
		void shouldNotBeApplicableWhenNameIsNull() {
			assertFalse(new AttributeIs(null, AttributeSpecialValue.NULL).isApplicable());
		}

		@Test
		@DisplayName("should not be applicable when special value is null")
		void shouldNotBeApplicableWhenSpecialValueIsNull() {
			assertFalse(new AttributeIs("married", null).isApplicable());
		}

		@Test
		@DisplayName("should not be applicable when both arguments are null")
		void shouldNotBeApplicableWhenBothNull() {
			assertFalse(new AttributeIs(null, null).isApplicable());
		}
	}

	@Nested
	@DisplayName("Property accessors")
	class PropertyAccessorsTest {

		@Test
		@DisplayName("should return attribute name")
		void shouldReturnAttributeName() {
			final AttributeIs constraint = attributeIsNull("visible");

			assertEquals("visible", constraint.getAttributeName());
		}

		@Test
		@DisplayName("should return attribute special value")
		void shouldReturnAttributeSpecialValue() {
			final AttributeIs constraint = attributeIs("visible", AttributeSpecialValue.NOT_NULL);

			assertEquals(AttributeSpecialValue.NOT_NULL, constraint.getAttributeSpecialValue());
		}
	}

	@Nested
	@DisplayName("Cloning")
	class CloningTest {

		@Test
		@DisplayName("should produce equal but not same instance via cloneWithArguments")
		void shouldCloneWithArguments() {
			final AttributeIs original = attributeIsNull("married");
			final FilterConstraint clone = original.cloneWithArguments(
				new Serializable[]{"married", AttributeSpecialValue.NULL}
			);

			assertEquals(original, clone);
			assertNotSame(original, clone);
			assertInstanceOf(AttributeIs.class, clone);
		}
	}

	@Nested
	@DisplayName("Visitor support")
	class VisitorSupportTest {

		@Test
		@DisplayName("should accept visitor and call visit method")
		void shouldAcceptVisitor() {
			final AttributeIs constraint = attributeIsNull("married");
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
			final AttributeIs constraint = attributeIsNull("married");

			assertEquals(FilterConstraint.class, constraint.getType());
		}
	}

	@Nested
	@DisplayName("String representation")
	class ToStringTest {

		@Test
		@DisplayName("should produce expected toString format for NULL")
		void shouldToStringReturnExpectedFormatForNull() {
			final AttributeIs constraint = attributeIsNull("married");

			assertEquals("attributeIs('married',NULL)", constraint.toString());
		}

		@Test
		@DisplayName("should produce expected toString format for NOT_NULL")
		void shouldToStringReturnExpectedFormatForNotNull() {
			final AttributeIs constraint = attributeIsNotNull("married");

			assertEquals("attributeIs('married',NOT_NULL)", constraint.toString());
		}
	}

	@Nested
	@DisplayName("Equality and hashCode")
	class EqualityTest {

		@Test
		@DisplayName("should conform to equals and hashCode contract")
		void shouldConformToEqualsAndHashContract() {
			assertNotSame(attributeIsNull("married"), attributeIsNull("married"));
			assertEquals(attributeIsNull("married"), attributeIsNull("married"));
			assertEquals(attributeIs("married", AttributeSpecialValue.NULL), attributeIsNull("married"));
			assertNotEquals(attributeIsNull("married"), attributeIsNull("single"));
			assertNotEquals(attributeIsNull("married"), attributeIsNull(null));
			assertNotEquals(attributeIsNull("married"), attributeIsNotNull("married"));
			assertEquals(attributeIsNull("married").hashCode(), attributeIsNull("married").hashCode());
			assertEquals(
				attributeIs("married", AttributeSpecialValue.NULL).hashCode(),
				attributeIsNull("married").hashCode()
			);
			assertNotEquals(attributeIsNull("married").hashCode(), attributeIsNull("single").hashCode());
			assertNotEquals(
				attributeIsNull("married").hashCode(),
				new AttributeIs(null, AttributeSpecialValue.NULL).hashCode()
			);
			assertNotEquals(attributeIsNull("married").hashCode(), attributeIsNotNull("married").hashCode());
		}
	}
}
