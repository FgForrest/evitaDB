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

import static io.evitadb.api.query.QueryConstraints.attributeStartsWith;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link AttributeStartsWith} verifying construction, applicability, property accessors,
 * cloning, visitor support, string representation, and equality contract.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("AttributeStartsWith constraint")
class AttributeStartsWithTest {

	@Nested
	@DisplayName("Construction")
	class ConstructionTest {

		@Test
		@DisplayName("should create via factory method with correct properties")
		void shouldCreateViaFactoryClassWorkAsExpected() {
			final AttributeStartsWith constraint = attributeStartsWith("abc", "def");

			assertEquals("abc", constraint.getAttributeName());
			assertEquals("def", constraint.getTextToSearch());
		}
	}

	@Nested
	@DisplayName("Applicability")
	class ApplicabilityTest {

		@Test
		@DisplayName("should recognize applicable and non-applicable instances")
		void shouldRecognizeApplicability() {
			assertFalse(new AttributeStartsWith("abc", null).isApplicable());
			assertFalse(new AttributeStartsWith(null, "abc").isApplicable());
			assertFalse(new AttributeStartsWith(null, null).isApplicable());
			assertTrue(attributeStartsWith("abc", "def").isApplicable());
		}
	}

	@Nested
	@DisplayName("Property accessors")
	class PropertyAccessorsTest {

		@Test
		@DisplayName("should return attribute name")
		void shouldReturnAttributeName() {
			final AttributeStartsWith constraint = attributeStartsWith("myAttr", "search");

			assertEquals("myAttr", constraint.getAttributeName());
		}

		@Test
		@DisplayName("should return text to search")
		void shouldReturnTextToSearch() {
			final AttributeStartsWith constraint = attributeStartsWith("abc", "searchText");

			assertEquals("searchText", constraint.getTextToSearch());
		}
	}

	@Nested
	@DisplayName("Cloning")
	class CloningTest {

		@Test
		@DisplayName("should produce equal but not same instance via cloneWithArguments")
		void shouldCloneWithArguments() {
			final AttributeStartsWith original = attributeStartsWith("abc", "def");
			final FilterConstraint clone = original.cloneWithArguments(new Serializable[]{"abc", "def"});

			assertEquals(original, clone);
			assertNotSame(original, clone);
			assertInstanceOf(AttributeStartsWith.class, clone);
		}
	}

	@Nested
	@DisplayName("Visitor support")
	class VisitorSupportTest {

		@Test
		@DisplayName("should accept visitor and call visit method")
		void shouldAcceptVisitor() {
			final AttributeStartsWith constraint = attributeStartsWith("abc", "def");
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
			final AttributeStartsWith constraint = attributeStartsWith("abc", "def");

			assertEquals(FilterConstraint.class, constraint.getType());
		}
	}

	@Nested
	@DisplayName("String representation")
	class ToStringTest {

		@Test
		@DisplayName("should produce expected toString format")
		void shouldToStringReturnExpectedFormat() {
			final AttributeStartsWith constraint = attributeStartsWith("abc", "def");

			assertEquals("attributeStartsWith('abc','def')", constraint.toString());
		}
	}

	@Nested
	@DisplayName("Equality and hashCode")
	class EqualityTest {

		@Test
		@DisplayName("should conform to equals and hashCode contract")
		void shouldConformToEqualsAndHashContract() {
			assertNotSame(attributeStartsWith("abc", "def"), attributeStartsWith("abc", "def"));
			assertEquals(attributeStartsWith("abc", "def"), attributeStartsWith("abc", "def"));
			assertNotEquals(attributeStartsWith("abc", "def"), attributeStartsWith("abc", "defe"));
			assertNotEquals(attributeStartsWith("abc", "def"), new AttributeStartsWith("abc", null));
			assertNotEquals(attributeStartsWith("abc", "def"), new AttributeStartsWith(null, "abc"));
			assertEquals(
				attributeStartsWith("abc", "def").hashCode(),
				attributeStartsWith("abc", "def").hashCode()
			);
			assertNotEquals(
				attributeStartsWith("abc", "def").hashCode(),
				attributeStartsWith("abc", "defe").hashCode()
			);
			assertNotEquals(
				attributeStartsWith("abc", "def").hashCode(),
				new AttributeStartsWith("abc", null).hashCode()
			);
			assertNotEquals(
				attributeStartsWith("abc", "def").hashCode(),
				new AttributeStartsWith(null, "abc").hashCode()
			);
		}
	}
}
