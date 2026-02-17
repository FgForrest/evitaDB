/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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
import io.evitadb.api.query.ConstraintVisitor;
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.exception.EvitaInvalidUsageException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.api.query.QueryConstraints.limit;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SegmentLimit} verifying construction, applicability, accessor methods,
 * clone operations, visitor acceptance, and equality contract.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@DisplayName("SegmentLimit constraint")
class SegmentLimitTest {

	@Nested
	@DisplayName("Construction and factory method")
	class ConstructionTest {

		@Test
		@DisplayName("should create with positive limit value")
		void shouldCreateWithPositiveLimitValue() {
			final SegmentLimit segmentLimit = limit(5);

			assertNotNull(segmentLimit);
			assertEquals(5, segmentLimit.getLimit());
		}

		@Test
		@DisplayName("should return null when factory receives null")
		void shouldReturnNullWhenFactoryReceivesNull() {
			final SegmentLimit result = limit(null);

			assertNull(result);
		}

		@Test
		@DisplayName("should throw when negative limit provided")
		void shouldThrowWhenNegativeLimitProvided() {
			assertThrows(EvitaInvalidUsageException.class, () -> limit(-1));
		}

		@Test
		@DisplayName("should throw when zero limit provided")
		void shouldThrowWhenZeroLimitProvided() {
			assertThrows(EvitaInvalidUsageException.class, () -> limit(0));
		}

		@Test
		@DisplayName("should create with limit value of 1")
		void shouldCreateWithLimitValueOfOne() {
			final SegmentLimit segmentLimit = limit(1);

			assertNotNull(segmentLimit);
			assertEquals(1, segmentLimit.getLimit());
		}
	}

	@Nested
	@DisplayName("Applicability")
	class ApplicabilityTest {

		@Test
		@DisplayName("should be applicable when it has a valid limit")
		void shouldBeApplicableWhenItHasValidLimit() {
			final SegmentLimit segmentLimit = new SegmentLimit(5);

			assertTrue(segmentLimit.isApplicable());
		}
	}

	@Nested
	@DisplayName("Type and visitor")
	class TypeAndVisitorTest {

		@Test
		@DisplayName("should return OrderConstraint class as type")
		void shouldReturnOrderConstraintClassAsType() {
			final SegmentLimit segmentLimit = new SegmentLimit(5);

			assertEquals(OrderConstraint.class, segmentLimit.getType());
		}

		@Test
		@DisplayName("should accept visitor")
		void shouldAcceptVisitor() {
			final SegmentLimit segmentLimit = new SegmentLimit(5);
			final AtomicReference<Constraint<?>> visited = new AtomicReference<>();
			segmentLimit.accept(new ConstraintVisitor() {
				@Override
				public void visit(@Nonnull Constraint<?> constraint) {
					visited.set(constraint);
				}
			});

			assertSame(segmentLimit, visited.get());
		}
	}

	@Nested
	@DisplayName("Clone operations")
	class CloneTest {

		@Test
		@DisplayName("should create new instance when cloning with valid integer argument")
		void shouldCreateNewInstanceWhenCloningWithValidIntegerArgument() {
			final SegmentLimit original = new SegmentLimit(5);

			final OrderConstraint cloned = original.cloneWithArguments(new Serializable[]{10});

			assertNotSame(original, cloned);
			assertInstanceOf(SegmentLimit.class, cloned);
			assertEquals(10, ((SegmentLimit) cloned).getLimit());
		}

		@Test
		@DisplayName("should throw when cloning with wrong argument type")
		void shouldThrowWhenCloningWithWrongArgumentType() {
			final SegmentLimit original = new SegmentLimit(5);

			assertThrows(
				EvitaInvalidUsageException.class,
				() -> original.cloneWithArguments(new Serializable[]{"notAnInteger"})
			);
		}

		@Test
		@DisplayName("should throw when cloning with wrong argument count")
		void shouldThrowWhenCloningWithWrongArgumentCount() {
			final SegmentLimit original = new SegmentLimit(5);

			assertThrows(
				EvitaInvalidUsageException.class,
				() -> original.cloneWithArguments(new Serializable[]{5, 10})
			);
		}

		@Test
		@DisplayName("should throw when cloning with empty arguments")
		void shouldThrowWhenCloningWithEmptyArguments() {
			final SegmentLimit original = new SegmentLimit(5);

			assertThrows(
				EvitaInvalidUsageException.class,
				() -> original.cloneWithArguments(new Serializable[0])
			);
		}
	}

	@Nested
	@DisplayName("String representation")
	class ToStringTest {

		@Test
		@DisplayName("should produce expected toString format")
		void shouldProduceExpectedToStringFormat() {
			final SegmentLimit segmentLimit = limit(5);

			assertEquals("limit(5)", segmentLimit.toString());
		}

		@Test
		@DisplayName("should produce expected toString format for different values")
		void shouldProduceExpectedToStringForDifferentValues() {
			final SegmentLimit segmentLimit = limit(10);

			assertEquals("limit(10)", segmentLimit.toString());
		}
	}

	@Nested
	@DisplayName("Equality and hashCode")
	class EqualityTest {

		@Test
		@DisplayName("should conform to equals and hashCode contract")
		void shouldConformToEqualsAndHashContract() {
			assertNotSame(limit(5), limit(5));
			assertEquals(limit(5), limit(5));
			assertNotEquals(limit(10), limit(5));
			assertEquals(limit(5).hashCode(), limit(5).hashCode());
			assertNotEquals(limit(10).hashCode(), limit(5).hashCode());
		}
	}
}
