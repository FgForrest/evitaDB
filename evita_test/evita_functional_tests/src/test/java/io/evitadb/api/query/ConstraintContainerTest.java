/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.api.query;

import io.evitadb.api.query.filter.And;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.filter.Or;
import io.evitadb.exception.EvitaInvalidUsageException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static io.evitadb.api.query.QueryConstraints.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ConstraintContainer} - the abstract base class for constraint containers
 * that hold child constraints. Uses concrete implementations ({@link And}, {@link Or}, {@link FilterBy})
 * to test base container behavior.
 *
 * @author evitaDB
 */
@DisplayName("ConstraintContainer")
class ConstraintContainerTest {

	@Nested
	@DisplayName("Child management")
	class ChildManagement {

		@Test
		@DisplayName("should return children array")
		void shouldReturnChildren() {
			final FilterConstraint child1 = attributeEquals("code", "a");
			final FilterConstraint child2 = attributeEquals("code", "b");
			final And container = and(child1, child2);
			assertArrayEquals(new FilterConstraint[]{child1, child2}, container.getChildren());
		}

		@Test
		@DisplayName("should return correct children count")
		void shouldReturnChildrenCount() {
			final And container = and(
				attributeEquals("a", "1"),
				attributeEquals("b", "2"),
				attributeEquals("c", "3")
			);
			assertEquals(3, container.getChildrenCount());
		}

		@Test
		@DisplayName("should filter out null children")
		void shouldFilterOutNullChildren() {
			final FilterConstraint child1 = attributeEquals("code", "a");
			final And container = new And(child1, null);
			assertEquals(1, container.getChildrenCount());
			assertArrayEquals(new FilterConstraint[]{child1}, container.getChildren());
		}

		@Test
		@DisplayName("should handle empty children")
		void shouldHandleEmptyChildren() {
			final And container = new And();
			assertEquals(0, container.getChildrenCount());
			assertArrayEquals(new FilterConstraint[0], container.getChildren());
		}
	}

	@Nested
	@DisplayName("Explicit children")
	class ExplicitChildren {

		@Test
		@DisplayName("should return all children when no suffix")
		void shouldReturnAllChildrenWhenNoSuffix() {
			final FilterConstraint child1 = attributeEquals("a", "1");
			final FilterConstraint child2 = attributeEquals("b", "2");
			final And container = and(child1, child2);
			assertArrayEquals(new FilterConstraint[]{child1, child2}, container.getExplicitChildren());
		}
	}

	@Nested
	@DisplayName("Applicability and necessity")
	class ApplicabilityAndNecessity {

		@Test
		@DisplayName("should be applicable with children")
		void shouldBeApplicableWithChildren() {
			final And container = and(attributeEquals("a", "1"));
			assertTrue(container.isApplicable());
		}

		@Test
		@DisplayName("should not be applicable without children")
		void shouldNotBeApplicableWithoutChildren() {
			final And container = new And();
			assertFalse(container.isApplicable());
		}

		@Test
		@DisplayName("should be necessary with more than one child")
		void shouldBeNecessaryWithMultipleChildren() {
			final And container = and(
				attributeEquals("a", "1"),
				attributeEquals("b", "2")
			);
			assertTrue(container.isNecessary());
		}

		@Test
		@DisplayName("should not be necessary with zero children")
		void shouldNotBeNecessaryWithZeroChildren() {
			final And container = new And();
			assertFalse(container.isNecessary());
		}

		@Test
		@DisplayName("should not be necessary with one child")
		void shouldNotBeNecessaryWithOneChild() {
			final And container = and(attributeEquals("a", "1"));
			assertFalse(container.isNecessary());
		}
	}

	@Nested
	@DisplayName("Iteration")
	class Iteration {

		@Test
		@DisplayName("should iterate children in order")
		void shouldIterateChildrenInOrder() {
			final FilterConstraint child1 = attributeEquals("a", "1");
			final FilterConstraint child2 = attributeEquals("b", "2");
			final FilterConstraint child3 = attributeEquals("c", "3");
			final And container = and(child1, child2, child3);

			final List<FilterConstraint> result = new ArrayList<>(3);
			final Iterator<FilterConstraint> it = container.iterator();
			while (it.hasNext()) {
				result.add(it.next());
			}

			assertEquals(3, result.size());
			assertSame(child1, result.get(0));
			assertSame(child2, result.get(1));
			assertSame(child3, result.get(2));
		}

		@Test
		@DisplayName("should support for-each iteration")
		void shouldSupportForEachIteration() {
			final And container = and(
				attributeEquals("a", "1"),
				attributeEquals("b", "2")
			);

			int count = 0;
			for (final FilterConstraint ignored : container) {
				count++;
			}
			assertEquals(2, count);
		}
	}

	@Nested
	@DisplayName("String representation")
	class StringRepresentation {

		@Test
		@DisplayName("should produce correct toString with children")
		void shouldProduceCorrectToStringWithChildren() {
			final And container = and(
				attributeEquals("code", "a"),
				attributeEquals("code", "b")
			);
			assertEquals(
				"and(attributeEquals('code','a'),attributeEquals('code','b'))",
				container.toString()
			);
		}

		@Test
		@DisplayName("should produce correct toString for empty container")
		void shouldProduceCorrectToStringForEmptyContainer() {
			final And container = new And();
			assertEquals("and()", container.toString());
		}

		@Test
		@DisplayName("should produce correct toString for FilterBy with single child")
		void shouldProduceCorrectToStringForFilterBy() {
			final FilterBy filterBy = filterBy(attributeEquals("code", "a"));
			assertEquals(
				"filterBy(attributeEquals('code','a'))",
				filterBy.toString()
			);
		}
	}

	@Nested
	@DisplayName("Equals and hash code")
	class EqualsAndHashCode {

		@Test
		@DisplayName("should be equal with same children")
		void shouldBeEqualWithSameChildren() {
			final And a = and(attributeEquals("code", "a"));
			final And b = and(attributeEquals("code", "a"));
			assertEquals(a, b);
			assertEquals(a.hashCode(), b.hashCode());
		}

		@Test
		@DisplayName("should not be equal with different children")
		void shouldNotBeEqualWithDifferentChildren() {
			final And a = and(attributeEquals("code", "a"));
			final And b = and(attributeEquals("code", "b"));
			assertNotEquals(a, b);
		}

		@Test
		@DisplayName("should not be equal with different child count")
		void shouldNotBeEqualWithDifferentChildCount() {
			final And a = and(attributeEquals("code", "a"));
			final And b = and(attributeEquals("code", "a"), attributeEquals("code", "b"));
			assertNotEquals(a, b);
		}
	}
}
