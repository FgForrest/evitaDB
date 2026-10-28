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
import io.evitadb.api.query.ConstraintContainer;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.exception.GenericEvitaInternalError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;

import static io.evitadb.api.query.QueryConstraints.attributeEquals;
import static io.evitadb.api.query.QueryConstraints.entityHaving;
import static org.junit.jupiter.api.Assertions.*;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.FILTER;

/**
 * Tests for {@link EntityHaving} verifying construction, applicability, necessity, property accessors,
 * child management, cloning, visitor support, string representation, and equality contract.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("EntityHaving constraint")
@Tag(CONTRACT)
@Tag(FILTER)
class EntityHavingTest {

	@Nested
	@DisplayName("Construction")
	class ConstructionTest {

		@Test
		@DisplayName("should create with child constraint via factory")
		void shouldCreateWithChildConstraintViaFactory() {
			final ConstraintContainer<FilterConstraint> constraint = entityHaving(
				attributeEquals("abc", "def")
			);

			assertNotNull(constraint);
			assertEquals(1, constraint.getChildrenCount());
			assertEquals("abc", ((AttributeEquals) constraint.getChildren()[0]).getAttributeName());
			assertEquals("def", ((AttributeEquals) constraint.getChildren()[0]).getAttributeValue());
		}
	}

	@Nested
	@DisplayName("Property accessors")
	class PropertyAccessorTest {

		@Test
		@DisplayName("should return child constraint")
		@SuppressWarnings("deprecation")
		void shouldReturnChildConstraint() {
			final EntityHaving constraint = entityHaving(attributeEquals("code", "apple"));

			final FilterConstraint child = constraint.getChild();
			assertNotNull(child);
			assertInstanceOf(AttributeEquals.class, child);
			assertEquals("code", ((AttributeEquals) child).getAttributeName());
		}

		@Test
		@DisplayName("should return null child when empty")
		@SuppressWarnings("deprecation")
		void shouldReturnNullChildWhenEmpty() {
			final EntityHaving empty = (EntityHaving) new EntityHaving(attributeEquals("x", "y"))
				.getCopyWithNewChildren(new FilterConstraint[0], new Constraint<?>[0]);

			assertNull(empty.getChild());
		}
	}

	@Nested
	@DisplayName("Applicability and necessity")
	class ApplicabilityTest {

		@Test
		@DisplayName("should be applicable with child")
		void shouldBeApplicableWithChild() {
			assertTrue(new EntityHaving(attributeEquals("abc", "def")).isApplicable());
		}

		@Test
		@DisplayName("should not be applicable without children")
		void shouldNotBeApplicableWithoutChildren() {
			final EntityHaving empty = (EntityHaving) new EntityHaving(attributeEquals("abc", "def"))
				.getCopyWithNewChildren(new FilterConstraint[0], new Constraint<?>[0]);

			assertFalse(empty.isApplicable());
		}

		@Test
		@DisplayName("should be necessary with child")
		void shouldBeNecessaryWithChild() {
			assertTrue(new EntityHaving(attributeEquals("abc", "def")).isNecessary());
		}

		@Test
		@DisplayName("should not be necessary without children")
		void shouldNotBeNecessaryWithoutChildren() {
			final EntityHaving empty = (EntityHaving) new EntityHaving(attributeEquals("abc", "def"))
				.getCopyWithNewChildren(new FilterConstraint[0], new Constraint<?>[0]);

			assertFalse(empty.isNecessary());
		}
	}

	@Nested
	@DisplayName("Copy with new children")
	class CopyWithNewChildrenTest {

		@Test
		@DisplayName("should create copy with single child")
		void shouldCreateCopyWithSingleChild() {
			final EntityHaving original = entityHaving(attributeEquals("x", "y"));
			final FilterConstraint newChild = attributeEquals("code", "test");

			final FilterConstraint copy = original.getCopyWithNewChildren(
				new FilterConstraint[]{newChild}, new Constraint<?>[0]
			);

			assertInstanceOf(EntityHaving.class, copy);
			assertEquals(1, ((EntityHaving) copy).getChildrenCount());
		}

		@Test
		@DisplayName("should create empty copy with no children")
		void shouldCreateEmptyCopyWithNoChildren() {
			final EntityHaving original = entityHaving(attributeEquals("x", "y"));

			final FilterConstraint copy = original.getCopyWithNewChildren(
				new FilterConstraint[0], new Constraint<?>[0]
			);

			assertInstanceOf(EntityHaving.class, copy);
			assertEquals(0, ((EntityHaving) copy).getChildrenCount());
		}

		@Test
		@DisplayName("should reject non-empty additional children")
		void shouldRejectNonEmptyAdditionalChildren() {
			final EntityHaving original = entityHaving(attributeEquals("x", "y"));

			assertThrows(GenericEvitaInternalError.class, () ->
				original.getCopyWithNewChildren(
					new FilterConstraint[]{attributeEquals("a", "b")},
					new Constraint<?>[]{attributeEquals("c", "d")}
				)
			);
		}
	}

	@Nested
	@DisplayName("Clone with arguments")
	class CloneWithArgumentsTest {

		@Test
		@DisplayName("should throw UnsupportedOperationException")
		void shouldThrowUnsupportedOperationException() {
			final EntityHaving constraint = entityHaving(attributeEquals("x", "y"));

			assertThrows(UnsupportedOperationException.class, () ->
				constraint.cloneWithArguments(new Serializable[]{"test"})
			);
		}
	}

	@Nested
	@DisplayName("Type and visitor")
	class TypeAndVisitorTest {

		@Test
		@DisplayName("should return FilterConstraint type")
		void shouldReturnFilterConstraintType() {
			assertEquals(
				FilterConstraint.class,
				entityHaving(attributeEquals("x", "y")).getType()
			);
		}

		@Test
		@DisplayName("should accept visitor")
		void shouldAcceptVisitor() {
			final EntityHaving constraint = entityHaving(attributeEquals("x", "y"));
			final AtomicReference<Constraint<?>> visited = new AtomicReference<>();

			constraint.accept(c -> visited.set(c));

			assertSame(constraint, visited.get());
		}
	}

	@Nested
	@DisplayName("String representation")
	class ToStringTest {

		@Test
		@DisplayName("should format with child constraint")
		void shouldFormatWithChildConstraint() {
			final ConstraintContainer<FilterConstraint> constraint = entityHaving(
				attributeEquals("abc", '\'')
			);

			assertNotNull(constraint);
			assertEquals("entityHaving(attributeEquals('abc','\\''))", constraint.toString());
		}
	}

	@Nested
	@DisplayName("Equals and hashCode")
	class EqualsAndHashCodeTest {

		@Test
		@DisplayName("should conform to equals and hashCode contract")
		void shouldConformToEqualsAndHashContract() {
			assertNotSame(createEntityHaving("def"), createEntityHaving("def"));
			assertEquals(createEntityHaving("def"), createEntityHaving("def"));
			assertNotEquals(createEntityHaving("def"), createEntityHaving("defe"));
			assertNotEquals(createEntityHaving("def"), createEntityHaving(null));
			assertEquals(
				createEntityHaving("def").hashCode(),
				createEntityHaving("def").hashCode()
			);
			assertNotEquals(
				createEntityHaving("def").hashCode(),
				createEntityHaving("defe").hashCode()
			);
			assertNotEquals(
				createEntityHaving("def").hashCode(),
				createEntityHaving(null).hashCode()
			);
		}
	}

	/**
	 * Verifies ordered conjunctions and optional children in the entity scope.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	@Nested
	@DisplayName("Several children")
	class SeveralChildrenTest {

		/**
		 * Every child survives construction in the supplied order, and the container stays applicable.
		 */
		@Test
		@DisplayName("Keeps all children in their order")
		void shouldKeepAllChildrenInOrderWhenSeveralAreProvided() {
			final FilterConstraint first = attributeEquals("first", true);
			final FilterConstraint second = attributeEquals("second", true);

			final EntityHaving constraint = entityHaving(first, second);

			assertNotNull(constraint);
			assertArrayEquals(new FilterConstraint[]{first, second}, constraint.getChildren());
			assertTrue(constraint.isApplicable());
			assertTrue(constraint.isNecessary());
		}

		/**
		 * The EvitaQL form lists every child, so no filter disappears from a printed query.
		 */
		@Test
		@DisplayName("Formats all children")
		void shouldFormatAllChildrenWhenSeveralAreProvided() {
			final EntityHaving constraint = entityHaving(
				attributeEquals("first", true), attributeEquals("second", true)
			);

			assertNotNull(constraint);
			assertEquals(
				"entityHaving(attributeEquals('first',true),attributeEquals('second',true))",
				constraint.toString()
			);
		}

		/**
		 * Two containers are equal only when they hold the same children in the same order.
		 */
		@Test
		@DisplayName("Equality depends on every child and their order")
		void shouldCompareAllChildrenInOrderWhenSeveralAreProvided() {
			final FilterConstraint first = attributeEquals("first", true);
			final FilterConstraint second = attributeEquals("second", true);

			final EntityHaving constraint = entityHaving(first, second);

			assertEquals(entityHaving(first, second), constraint);
			assertEquals(entityHaving(first, second).hashCode(), constraint.hashCode());
			assertNotEquals(entityHaving(first), constraint);
			assertNotEquals(entityHaving(second, first), constraint);
		}

		/**
		 * A copy takes every supplied child in the supplied order, not only the first one.
		 */
		@Test
		@DisplayName("Copies all supplied children in their order")
		void shouldCopyAllChildrenInOrderWhenSeveralAreSupplied() {
			final FilterConstraint first = attributeEquals("first", true);
			final FilterConstraint second = attributeEquals("second", true);
			final EntityHaving constraint = entityHaving(first, second);
			assertNotNull(constraint);

			final FilterConstraint copy = constraint.getCopyWithNewChildren(
				new FilterConstraint[]{second, first}, new Constraint<?>[0]
			);

			assertEquals(entityHaving(second, first), copy);
		}

		/**
		 * The deprecated accessor still returns the whole filter: several children come back as their conjunction.
		 */
		@Test
		@DisplayName("getChild() combines several children into one and")
		@SuppressWarnings("deprecation")
		void shouldReturnConjunctionWhenGetChildReadsSeveralChildren() {
			final FilterConstraint first = attributeEquals("first", true);
			final FilterConstraint second = attributeEquals("second", true);
			final EntityHaving constraint = entityHaving(first, second);
			assertNotNull(constraint);

			final FilterConstraint child = constraint.getChild();

			assertEquals(new And(first, second), child);
		}

		/**
		 * A single child is returned as it is, without a wrapping conjunction.
		 */
		@Test
		@DisplayName("getChild() returns the sole child itself")
		@SuppressWarnings("deprecation")
		void shouldReturnSoleChildWhenGetChildReadsOneChild() {
			final FilterConstraint first = attributeEquals("first", true);
			final EntityHaving constraint = entityHaving(first);
			assertNotNull(constraint);

			final FilterConstraint child = constraint.getChild();

			assertSame(first, child);
		}

		/**
		 * The varargs constructor called without children creates an empty, inapplicable container.
		 */
		@Test
		@DisplayName("Container without children is empty")
		@SuppressWarnings("deprecation")
		void shouldBeEmptyWhenConstructedWithoutChildren() {
			final EntityHaving empty = new EntityHaving();

			assertNull(empty.getChild());
			assertFalse(empty.isApplicable());
			assertFalse(empty.isNecessary());
		}

		/**
		 * Optional child expressions are omitted exactly as in the other filter containers.
		 */
		@Test
		@DisplayName("Omits null children while preserving the remaining order")
		void shouldOmitNullChildrenWhenMixedWithFilters() {
			final FilterConstraint first = attributeEquals("first", true);
			final FilterConstraint second = attributeEquals("second", true);
			assertEquals(entityHaving(first, second), new EntityHaving(null, first, null, second, null));
		}

		/**
		 * An absent filter list produces no scope constraint from the factory.
		 */
		@Test
		@DisplayName("Returns no constraint for null, empty or all-null input")
		void shouldReturnNullWhenNoFiltersProvided() {
			assertNull(entityHaving((FilterConstraint[]) null));
			assertNull(entityHaving());
			assertNull(entityHaving(null, null));
		}
	}

	/**
	 * Creates an {@link EntityHaving} constraint wrapping an {@link AttributeEquals} with the given value.
	 *
	 * @param value the attribute value to filter on, may be null
	 * @return a new EntityHaving constraint
	 */
	private static EntityHaving createEntityHaving(String value) {
		return entityHaving(new AttributeEquals("abc", value));
	}
}
