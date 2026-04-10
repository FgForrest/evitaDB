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
import io.evitadb.exception.GenericEvitaInternalError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.api.query.order.OrderDirection.DESC;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link Segments} verifying construction, applicability, accessor methods,
 * copy/clone operations, visitor acceptance, and equality contract.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@DisplayName("Segments constraint")
class SegmentsTest {

	@Nested
	@DisplayName("Construction and factory method")
	class ConstructionTest {

		@Test
		@DisplayName("should create via factory method with multiple segments")
		void shouldCreateViaFactoryMethodWithMultipleSegments() {
			final Segments segments = segments(
				segment(orderBy(attributeNatural("orderedQuantity", DESC)), limit(3)),
				segment(
					entityHaving(attributeEquals("new", true)),
					orderBy(random()),
					limit(2)
				),
				segment(orderBy(attributeNatural("code"), attributeNatural("create")))
			);

			assertNotNull(segments);
			assertEquals(3, segments.getChildrenCount());
		}

		@Test
		@DisplayName("should create with single segment")
		void shouldCreateWithSingleSegment() {
			final Segments segments = segments(
				segment(orderBy(attributeNatural("code")))
			);

			assertNotNull(segments);
			assertEquals(1, segments.getChildrenCount());
		}

		@Test
		@DisplayName("should return null when factory method receives no segments")
		void shouldReturnNullWhenFactoryMethodReceivesNoSegments() {
			final Segments result = segments();

			assertNull(result);
		}
	}

	@Nested
	@DisplayName("Applicability and necessity")
	class ApplicabilityTest {

		@Test
		@DisplayName("should be applicable when it has children")
		void shouldBeApplicableWhenItHasChildren() {
			final Segments segments = new Segments(
				segment(orderBy(attributeNatural("orderedQuantity", DESC)), limit(3))
			);

			assertTrue(segments.isApplicable());
		}

		@Test
		@DisplayName("should not be applicable when it has no children")
		void shouldNotBeApplicableWhenItHasNoChildren() {
			final Segments emptySegments = new Segments();

			assertFalse(emptySegments.isApplicable());
		}

		@Test
		@DisplayName("should be necessary when applicable")
		void shouldBeNecessaryWhenApplicable() {
			final Segments segments = new Segments(
				segment(orderBy(attributeNatural("code")))
			);

			assertTrue(segments.isNecessary());
		}

		@Test
		@DisplayName("should not be necessary when not applicable")
		void shouldNotBeNecessaryWhenNotApplicable() {
			final Segments emptySegments = new Segments();

			assertFalse(emptySegments.isNecessary());
		}
	}

	@Nested
	@DisplayName("Accessor methods")
	class AccessorTest {

		@Test
		@DisplayName("should return segments array from getSegments")
		void shouldReturnSegmentsArrayFromGetSegments() {
			final Segments segments = segments(
				segment(orderBy(attributeNatural("orderedQuantity", DESC)), limit(3)),
				segment(orderBy(attributeNatural("code")))
			);

			final Segment[] segmentArray = segments.getSegments();

			assertEquals(2, segmentArray.length);
			assertInstanceOf(Segment.class, segmentArray[0]);
			assertInstanceOf(Segment.class, segmentArray[1]);
		}
	}

	@Nested
	@DisplayName("Type and visitor")
	class TypeAndVisitorTest {

		@Test
		@DisplayName("should return OrderConstraint class as type")
		void shouldReturnOrderConstraintClassAsType() {
			final Segments segments = new Segments(
				segment(orderBy(attributeNatural("code")))
			);

			assertEquals(OrderConstraint.class, segments.getType());
		}

		@Test
		@DisplayName("should accept visitor")
		void shouldAcceptVisitor() {
			final Segments segments = segments(
				segment(orderBy(attributeNatural("code")))
			);
			final AtomicReference<Constraint<?>> visited = new AtomicReference<>();
			segments.accept(new ConstraintVisitor() {
				@Override
				public void visit(@Nonnull Constraint<?> constraint) {
					visited.set(constraint);
				}
			});

			assertSame(segments, visited.get());
		}
	}

	@Nested
	@DisplayName("Copy and clone operations")
	class CopyAndCloneTest {

		@Test
		@DisplayName("should create copy with new Segment children")
		void shouldCreateCopyWithNewSegmentChildren() {
			final Segments original = segments(
				segment(orderBy(attributeNatural("code")))
			);
			final Segment newSegment = segment(orderBy(attributeNatural("name", DESC)), limit(5));
			final OrderConstraint copy = original.getCopyWithNewChildren(
				new OrderConstraint[]{newSegment},
				new Constraint<?>[0]
			);

			assertInstanceOf(Segments.class, copy);
			assertEquals(1, ((Segments) copy).getChildrenCount());
		}

		@Test
		@DisplayName("should reject non-Segment children in getCopyWithNewChildren")
		void shouldRejectNonSegmentChildrenInGetCopyWithNewChildren() {
			final Segments original = segments(
				segment(orderBy(attributeNatural("code")))
			);

			assertThrows(
				GenericEvitaInternalError.class,
				() -> original.getCopyWithNewChildren(
					new OrderConstraint[]{attributeNatural("notASegment")},
					new Constraint<?>[0]
				)
			);
		}

		@Test
		@DisplayName("should reject non-empty additional children in getCopyWithNewChildren")
		void shouldRejectNonEmptyAdditionalChildren() {
			final Segments original = segments(
				segment(orderBy(attributeNatural("code")))
			);

			assertThrows(
				GenericEvitaInternalError.class,
				() -> original.getCopyWithNewChildren(
					new OrderConstraint[]{segment(orderBy(attributeNatural("code")))},
					new Constraint<?>[]{attributeNatural("extra")}
				)
			);
		}

		@Test
		@DisplayName("should throw UnsupportedOperationException when cloning with arguments")
		void shouldThrowWhenCloningWithArguments() {
			final Segments segments = segments(
				segment(orderBy(attributeNatural("code")))
			);

			// argument-less containers throw UnsupportedOperationException by convention
			assertThrows(
				UnsupportedOperationException.class,
				() -> segments.cloneWithArguments(new Serializable[]{"arg"})
			);
		}
	}

	@Nested
	@DisplayName("String representation")
	class ToStringTest {

		@Test
		@DisplayName("should produce expected toString format")
		void shouldProduceExpectedToStringFormat() {
			final Segments segments = segments(
				segment(orderBy(attributeNatural("orderedQuantity", DESC)), limit(3)),
				segment(entityHaving(attributeEquals("new", true)), orderBy(random()), limit(2)),
				segment(orderBy(attributeNatural("code"), attributeNatural("create")))
			);

			assertEquals(
				"segments(segment(orderBy(attributeNatural('orderedQuantity',DESC)),limit(3))," +
					"segment(entityHaving(attributeEquals('new',true)),orderBy(random()),limit(2))," +
					"segment(orderBy(attributeNatural('code',ASC),attributeNatural('create',ASC))))",
				segments.toString()
			);
		}
	}

	@Nested
	@DisplayName("Equality and hashCode")
	class EqualityTest {

		@Test
		@DisplayName("should conform to equals and hashCode contract")
		void shouldConformToEqualsAndHashContract() {
			assertNotSame(createSegments(), createSegments());
			assertEquals(createSegments(), createSegments());
			assertNotEquals(createSegments(), createDifferentSegments());
			assertEquals(createSegments().hashCode(), createSegments().hashCode());
			assertNotEquals(createSegments().hashCode(), createDifferentSegments().hashCode());
		}
	}

	/**
	 * Creates a standard {@link Segments} constraint for equality testing.
	 */
	@Nonnull
	private static Segments createSegments() {
		return segments(
			segment(orderBy(attributeNatural("orderedQuantity", DESC)), limit(3)),
			segment(entityHaving(attributeEquals("new", true)), orderBy(random()), limit(2)),
			segment(orderBy(attributeNatural("code"), attributeNatural("create")))
		);
	}

	/**
	 * Creates a different {@link Segments} constraint (different limit) for inequality testing.
	 */
	@Nonnull
	private static Segments createDifferentSegments() {
		return segments(
			segment(orderBy(attributeNatural("orderedQuantity", DESC)), limit(3)),
			segment(entityHaving(attributeEquals("new", true)), orderBy(random()), limit(1)),
			segment(orderBy(attributeNatural("code"), attributeNatural("create")))
		);
	}
}
