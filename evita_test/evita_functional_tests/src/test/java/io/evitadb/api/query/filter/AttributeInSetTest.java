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

import static io.evitadb.api.query.QueryConstraints.attributeInSet;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link AttributeInSet} verifying construction, applicability, property accessors,
 * cloning, visitor support, string representation, and equality contract.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("AttributeInSet constraint")
class AttributeInSetTest {

	@Nested
	@DisplayName("Construction")
	class ConstructionTest {

		@Test
		@DisplayName("should create via factory method with values")
		void shouldCreateViaFactoryClassWorkAsExpected() {
			final AttributeInSet constraint = attributeInSet("refs", 1, 5);

			assertArrayEquals(new Comparable<?>[]{1, 5}, constraint.getAttributeValues());
		}

		@Test
		@DisplayName("should filter out null values in array")
		void shouldCreateViaFactoryClassWorkAsExpectedNullInArray() {
			final AttributeInSet constraint = attributeInSet("refs", 1, null, 5);

			assertArrayEquals(new Comparable<?>[]{1, 5}, constraint.getAttributeValues());
		}

		@Test
		@DisplayName("should return null for null variable input")
		void shouldCreateViaFactoryClassWorkAsExpectedForNullVariable() {
			final Integer nullInteger = null;
			final AttributeInSet constraint = attributeInSet("refs", nullInteger);

			assertNull(constraint);
		}

		@Test
		@DisplayName("should handle empty array input")
		void shouldCreateViaFactoryClassWorkAsExpectedNullValueInArray() {
			final AttributeInSet constraint = attributeInSet("refs", new Integer[0]);

			assertArrayEquals(new Comparable<?>[0], constraint.getAttributeValues());
		}
	}

	@Nested
	@DisplayName("Applicability")
	class ApplicabilityTest {

		@Test
		@DisplayName("should recognize applicable and non-applicable instances")
		void shouldRecognizeApplicability() {
			assertFalse(new AttributeInSet(null).isApplicable());
			assertTrue(new AttributeInSet("refs").isApplicable());
			assertTrue(attributeInSet("refs", 1).isApplicable());
			assertTrue(attributeInSet("refs", 1, 2).isApplicable());
		}
	}

	@Nested
	@DisplayName("Property accessors")
	class PropertyAccessorsTest {

		@Test
		@DisplayName("should return attribute name")
		void shouldReturnAttributeName() {
			final AttributeInSet constraint = attributeInSet("myAttribute", 1, 2, 3);

			assertEquals("myAttribute", constraint.getAttributeName());
		}

		@Test
		@DisplayName("should return attribute values")
		void shouldReturnAttributeValues() {
			final AttributeInSet constraint = attributeInSet("refs", 10, 20, 30);

			assertArrayEquals(new Comparable<?>[]{10, 20, 30}, constraint.getAttributeValues());
		}
	}

	@Nested
	@DisplayName("Cloning")
	class CloningTest {

		@Test
		@DisplayName("should produce equal but not same instance via cloneWithArguments")
		void shouldCloneWithArguments() {
			final AttributeInSet original = attributeInSet("refs", 1, 5);
			final FilterConstraint clone = original.cloneWithArguments(new Serializable[]{"refs", 1, 5});

			assertEquals(original, clone);
			assertNotSame(original, clone);
			assertInstanceOf(AttributeInSet.class, clone);
		}
	}

	@Nested
	@DisplayName("Visitor support")
	class VisitorSupportTest {

		@Test
		@DisplayName("should accept visitor and call visit method")
		void shouldAcceptVisitor() {
			final AttributeInSet constraint = attributeInSet("refs", 1, 5);
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
			final AttributeInSet constraint = attributeInSet("refs", 1);

			assertEquals(FilterConstraint.class, constraint.getType());
		}
	}

	@Nested
	@DisplayName("String representation")
	class ToStringTest {

		@Test
		@DisplayName("should produce expected toString format")
		void shouldToStringReturnExpectedFormat() {
			final AttributeInSet constraint = attributeInSet("refs", 1, 5);

			assertEquals("attributeInSet('refs',1,5)", constraint.toString());
		}
	}

	@Nested
	@DisplayName("Equality and hashCode")
	class EqualityTest {

		@Test
		@DisplayName("should conform to equals and hashCode contract")
		void shouldConformToEqualsAndHashContract() {
			assertNotSame(attributeInSet("refs", 1, 5), attributeInSet("refs", 1, 5));
			assertEquals(attributeInSet("refs", 1, 5), attributeInSet("refs", 1, 5));
			assertNotEquals(attributeInSet("refs", 1, 5), attributeInSet("refs", 1, 6));
			assertNotEquals(attributeInSet("refs", 1, 5), attributeInSet("refs", 1));
			assertNotEquals(attributeInSet("refs", 1, 5), attributeInSet("def", 1, 5));
			assertEquals(attributeInSet("refs", 1, 5).hashCode(), attributeInSet("refs", 1, 5).hashCode());
			assertNotEquals(attributeInSet("refs", 1, 5).hashCode(), attributeInSet("refs", 1, 6).hashCode());
			assertNotEquals(attributeInSet("refs", 1, 5).hashCode(), attributeInSet("refs", 1).hashCode());
		}
	}
}
