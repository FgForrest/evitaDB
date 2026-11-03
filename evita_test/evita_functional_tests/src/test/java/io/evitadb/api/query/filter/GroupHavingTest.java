/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static io.evitadb.api.query.QueryConstraints.attributeEquals;
import static io.evitadb.api.query.QueryConstraints.groupHaving;
import static org.junit.jupiter.api.Assertions.*;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.FILTER;

/**
 * This tests verifies basic properties of {@link GroupHaving} query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("GroupHaving constraint")
@Tag(CONTRACT)
@Tag(FILTER)
class GroupHavingTest {

	@Test
	void shouldCreateViaFactoryClassWorkAsExpected() {
		final ConstraintContainer<FilterConstraint> groupHaving = groupHaving(
			attributeEquals("abc", "def")
		);
		assertNotNull(groupHaving);
		assertEquals(1, groupHaving.getChildrenCount());
		assertEquals("abc", ((AttributeEquals) groupHaving.getChildren()[0]).getAttributeName());
		assertEquals("def", ((AttributeEquals) groupHaving.getChildren()[0]).getAttributeValue());
	}

	@Test
	void shouldRecognizeApplicability() {
		assertTrue(new GroupHaving(attributeEquals("abc", "def")).isApplicable());
		final GroupHaving hackedCopyByInternalAPI = (GroupHaving) new GroupHaving(attributeEquals("abc", "def")).getCopyWithNewChildren(new FilterConstraint[0], new Constraint<?>[0]);
		assertFalse(hackedCopyByInternalAPI.isApplicable());
	}

	@Test
	void shouldRecognizeNecessity() {
		assertTrue(new GroupHaving(attributeEquals("abc", "def")).isNecessary());
		final GroupHaving hackedCopyByInternalAPI = (GroupHaving) new GroupHaving(attributeEquals("abc", "def")).getCopyWithNewChildren(new FilterConstraint[0], new Constraint<?>[0]);
		assertFalse(hackedCopyByInternalAPI.isNecessary());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		final ConstraintContainer<FilterConstraint> groupHaving = groupHaving(
			attributeEquals("abc", '\'')
		);
		assertNotNull(groupHaving);
		assertEquals("groupHaving(attributeEquals('abc','\\''))", groupHaving.toString());
	}

	@Test
	void shouldConformToEqualsOrHashContract() {
		assertNotSame(createGroupHavingConstraint("def"), createGroupHavingConstraint("def"));
		assertEquals(createGroupHavingConstraint("def"), createGroupHavingConstraint("def"));
		assertNotEquals(createGroupHavingConstraint("def"), createGroupHavingConstraint("defe"));
		assertNotEquals(createGroupHavingConstraint("def"), createGroupHavingConstraint(null));
		assertNotEquals(createGroupHavingConstraint("def"), createGroupHavingConstraint(null));
		assertEquals(createGroupHavingConstraint("def").hashCode(), createGroupHavingConstraint("def").hashCode());
		assertNotEquals(createGroupHavingConstraint("def").hashCode(), createGroupHavingConstraint("defe").hashCode());
		assertNotEquals(createGroupHavingConstraint("def").hashCode(), createGroupHavingConstraint(null).hashCode());
		assertNotEquals(createGroupHavingConstraint("def").hashCode(), createGroupHavingConstraint(null).hashCode());
	}

	/**
	 * Verifies ordered conjunctions and optional children in the group scope.
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

			final GroupHaving constraint = groupHaving(first, second);

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
			final GroupHaving constraint = groupHaving(
				attributeEquals("first", true), attributeEquals("second", true)
			);

			assertNotNull(constraint);
			assertEquals(
				"groupHaving(attributeEquals('first',true),attributeEquals('second',true))",
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

			final GroupHaving constraint = groupHaving(first, second);

			assertEquals(groupHaving(first, second), constraint);
			assertEquals(groupHaving(first, second).hashCode(), constraint.hashCode());
			assertNotEquals(groupHaving(first), constraint);
			assertNotEquals(groupHaving(second, first), constraint);
		}

		/**
		 * A copy takes every supplied child in the supplied order, not only the first one.
		 */
		@Test
		@DisplayName("Copies all supplied children in their order")
		void shouldCopyAllChildrenInOrderWhenSeveralAreSupplied() {
			final FilterConstraint first = attributeEquals("first", true);
			final FilterConstraint second = attributeEquals("second", true);
			final GroupHaving constraint = groupHaving(first, second);
			assertNotNull(constraint);

			final FilterConstraint copy = constraint.getCopyWithNewChildren(
				new FilterConstraint[]{second, first}, new Constraint<?>[0]
			);

			assertEquals(groupHaving(second, first), copy);
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
			final GroupHaving constraint = groupHaving(first, second);
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
			final GroupHaving constraint = groupHaving(first);
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
			final GroupHaving empty = new GroupHaving();

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
			assertEquals(groupHaving(first, second), new GroupHaving(null, first, null, second, null));
		}

		/**
		 * An absent filter list produces no scope constraint from the factory.
		 */
		@Test
		@DisplayName("Returns no constraint for null, empty or all-null input")
		void shouldReturnNullWhenNoFiltersProvided() {
			assertNull(groupHaving((FilterConstraint[]) null));
			assertNull(groupHaving());
			assertNull(groupHaving(null, null));
		}
	}

	private static GroupHaving createGroupHavingConstraint(String value) {
		return groupHaving(new AttributeEquals("abc", value));
	}

}
