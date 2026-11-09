/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.api.query.order;

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.visitor.QueryPurifierVisitor;
import io.evitadb.exception.GenericEvitaInternalError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.api.query.QueryConstraints.orderBy;
import static io.evitadb.api.query.QueryConstraints.random;
import static io.evitadb.api.query.QueryConstraints.relevanceText;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.ORDER;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link RelevanceText} verifying construction, applicability and necessity (which keep the childless
 * container alive through query purification), child management, cloning, visitor support, string representation
 * and the equality contract.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("RelevanceText constraint")
@Tag(CONTRACT)
@Tag(ORDER)
class RelevanceTextTest {

	@Nested
	@DisplayName("Construction")
	class ConstructionTest {

		@Test
		@DisplayName("should create via factory without children")
		void shouldCreateViaFactoryWithoutChildren() {
			final RelevanceText constraint = relevanceText();

			assertEquals(0, constraint.getChildrenCount());
			assertEquals(0, constraint.getArguments().length);
		}
	}

	@Nested
	@DisplayName("Applicability and necessity")
	class ApplicabilityTest {

		@Test
		@DisplayName("should be applicable and necessary without children")
		void shouldBeApplicableAndNecessaryWithoutChildren() {
			assertTrue(relevanceText().isApplicable());
			assertTrue(relevanceText().isNecessary());
		}

		@Test
		@DisplayName("should survive query purification")
		void shouldSurviveQueryPurification() {
			final OrderConstraint orderBy = orderBy(relevanceText());

			assertEquals(orderBy, QueryPurifierVisitor.purify(orderBy));
			assertEquals(relevanceText(), QueryPurifierVisitor.purify(relevanceText()));
		}
	}

	@Nested
	@DisplayName("Child management")
	class ChildManagementTest {

		@Test
		@DisplayName("should create copy without children")
		void shouldCreateCopyWithoutChildren() {
			final OrderConstraint copy = relevanceText().getCopyWithNewChildren(
				new OrderConstraint[0], new Constraint<?>[0]
			);

			assertEquals(relevanceText(), copy);
		}

		@Test
		@DisplayName("should throw when children provided")
		void shouldThrowWhenChildrenProvided() {
			assertThrows(
				GenericEvitaInternalError.class,
				() -> relevanceText().getCopyWithNewChildren(new OrderConstraint[]{random()}, new Constraint<?>[0])
			);
		}
	}

	@Nested
	@DisplayName("Cloning with arguments")
	class CloneWithArgumentsTest {

		@Test
		@DisplayName("should throw because the constraint has no arguments")
		void shouldThrowBecauseConstraintHasNoArguments() {
			assertThrows(
				UnsupportedOperationException.class,
				() -> relevanceText().cloneWithArguments(new Serializable[0])
			);
		}
	}

	@Nested
	@DisplayName("Type and visitor")
	class TypeAndVisitorTest {

		@Test
		@DisplayName("should return OrderConstraint type")
		void shouldReturnOrderConstraintType() {
			assertEquals(OrderConstraint.class, relevanceText().getType());
		}

		@Test
		@DisplayName("should accept visitor")
		void shouldAcceptVisitor() {
			final RelevanceText constraint = relevanceText();
			final AtomicReference<Constraint<?>> visited = new AtomicReference<>();

			constraint.accept(c -> visited.set((Constraint<?>) c));

			assertSame(constraint, visited.get());
		}
	}

	@Nested
	@DisplayName("String representation")
	class ToStringTest {

		@Test
		@DisplayName("should format without children")
		void shouldFormatWithoutChildren() {
			assertEquals("relevanceText()", relevanceText().toString());
			assertEquals("orderBy(relevanceText())", orderBy(relevanceText()).toString());
		}
	}

	@Nested
	@DisplayName("Equality and hash code")
	class EqualityTest {

		@Test
		@DisplayName("should equal another instance")
		void shouldEqualAnotherInstance() {
			assertEquals(relevanceText(), relevanceText());
			assertEquals(relevanceText().hashCode(), relevanceText().hashCode());
		}

		@Test
		@DisplayName("should not equal other ordering constraint")
		void shouldNotEqualOtherOrderingConstraint() {
			assertNotEquals(relevanceText(), random());
		}
	}
}
