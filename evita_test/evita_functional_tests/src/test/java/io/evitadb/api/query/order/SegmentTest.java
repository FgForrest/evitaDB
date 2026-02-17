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
import io.evitadb.api.query.filter.EntityHaving;
import io.evitadb.exception.EvitaInvalidUsageException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.api.query.order.OrderDirection.DESC;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link Segment} verifying construction, applicability, accessor methods,
 * copy/clone operations, visitor acceptance, and equality contract.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@DisplayName("Segment constraint")
class SegmentTest {

	@Nested
	@DisplayName("Construction and factory methods")
	class ConstructionTest {

		@Test
		@DisplayName("should create with OrderBy only")
		void shouldCreateWithOrderByOnly() {
			final Segment segment = new Segment(orderBy(attributeNatural("code")));

			assertNotNull(segment);
			assertTrue(segment.isApplicable());
		}

		@Test
		@DisplayName("should create with OrderBy and limit")
		void shouldCreateWithOrderByAndLimit() {
			final Segment segment = new Segment(orderBy(attributeNatural("code")), limit(5));

			assertNotNull(segment);
			assertTrue(segment.isApplicable());
		}

		@Test
		@DisplayName("should create with EntityHaving and OrderBy")
		void shouldCreateWithEntityHavingAndOrderBy() {
			final Segment segment = new Segment(
				entityHaving(attributeEquals("code", "123")),
				orderBy(attributeNatural("code"))
			);

			assertNotNull(segment);
			assertTrue(segment.isApplicable());
		}

		@Test
		@DisplayName("should create with EntityHaving, OrderBy, and limit")
		void shouldCreateWithEntityHavingOrderByAndLimit() {
			final Segment segment = new Segment(
				entityHaving(attributeEquals("code", "123")),
				orderBy(attributeNatural("code")),
				limit(5)
			);

			assertNotNull(segment);
			assertTrue(segment.isApplicable());
		}

		@Test
		@DisplayName("should create via factory method with OrderBy only")
		void shouldCreateViaFactoryMethodWithOrderByOnly() {
			final Segment segment = segment(orderBy(attributeNatural("code")));

			assertNotNull(segment);
		}

		@Test
		@DisplayName("should create via factory method with OrderBy and limit")
		void shouldCreateViaFactoryMethodWithOrderByAndLimit() {
			final Segment segment = segment(orderBy(attributeNatural("code")), limit(5));

			assertNotNull(segment);
		}

		@Test
		@DisplayName("should create via factory method with EntityHaving and OrderBy")
		void shouldCreateViaFactoryMethodWithEntityHavingAndOrderBy() {
			final Segment segment = segment(
				entityHaving(attributeEquals("code", "123")),
				orderBy(attributeNatural("code"))
			);

			assertNotNull(segment);
		}

		@Test
		@DisplayName("should create via factory method with all parameters")
		void shouldCreateViaFactoryMethodWithAllParameters() {
			final Segment segment = segment(
				entityHaving(attributeEquals("code", "123")),
				orderBy(attributeNatural("code")),
				limit(5)
			);

			assertNotNull(segment);
		}

		@Test
		@DisplayName("should return null from factory when null OrderBy provided")
		void shouldReturnNullFromFactoryWhenNullOrderByProvided() {
			final Segment result = segment((OrderBy) null);

			assertNull(result);
		}

		@Test
		@DisplayName("should return null from two-arg factory when null OrderBy provided")
		void shouldReturnNullFromTwoArgFactoryWhenNullOrderByProvided() {
			final Segment result = segment((OrderBy) null, limit(5));

			assertNull(result);
		}

		@Test
		@DisplayName("should return null from EntityHaving+OrderBy factory when null OrderBy provided")
		void shouldReturnNullFromEntityHavingOrderByFactoryWhenNullOrderByProvided() {
			final Segment result = segment(
				entityHaving(attributeEquals("code", "123")),
				(OrderBy) null
			);

			assertNull(result);
		}

		@Test
		@DisplayName("should return null from three-arg factory when null OrderBy provided")
		void shouldReturnNullFromThreeArgFactoryWhenNullOrderByProvided() {
			final Segment result = segment(
				entityHaving(attributeEquals("code", "123")),
				(OrderBy) null,
				limit(5)
			);

			assertNull(result);
		}
	}

	@Nested
	@DisplayName("Applicability and necessity")
	class ApplicabilityTest {

		@Test
		@DisplayName("should be applicable when it has OrderBy child")
		void shouldBeApplicableWhenItHasOrderByChild() {
			final Segment segment = new Segment(orderBy(attributeNatural("code")));

			assertTrue(segment.isApplicable());
		}

		@Test
		@DisplayName("should not be applicable when null OrderBy provided")
		void shouldNotBeApplicableWhenNullOrderByProvided() {
			final Segment segment = new Segment((OrderBy) null);

			assertFalse(segment.isApplicable());
		}

		@Test
		@DisplayName("should be necessary when applicable")
		void shouldBeNecessaryWhenApplicable() {
			final Segment segment = new Segment(orderBy(attributeNatural("code")));

			assertTrue(segment.isNecessary());
		}
	}

	@Nested
	@DisplayName("Accessor methods")
	class AccessorTest {

		@Test
		@DisplayName("should return OrderBy from getOrderBy")
		void shouldReturnOrderByFromGetOrderBy() {
			final Segment segment = new Segment(orderBy(attributeNatural("code")));

			final OrderBy orderByResult = segment.getOrderBy();

			assertNotNull(orderByResult);
			assertEquals(1, orderByResult.getChildrenCount());
		}

		@Test
		@DisplayName("should throw when getOrderBy called on segment without OrderBy")
		void shouldThrowWhenGetOrderByCalledOnSegmentWithoutOrderBy() {
			final Segment segment = new Segment((OrderBy) null);

			assertThrows(EvitaInvalidUsageException.class, segment::getOrderBy);
		}

		@Test
		@DisplayName("should return limit from getLimit")
		void shouldReturnLimitFromGetLimit() {
			final Segment segment = new Segment(orderBy(attributeNatural("code")), limit(5));

			final OptionalInt limitResult = segment.getLimit();

			assertTrue(limitResult.isPresent());
			assertEquals(5, limitResult.getAsInt());
		}

		@Test
		@DisplayName("should return empty OptionalInt when no limit")
		void shouldReturnEmptyOptionalIntWhenNoLimit() {
			final Segment segment = new Segment(orderBy(attributeNatural("code")));

			final OptionalInt limitResult = segment.getLimit();

			assertFalse(limitResult.isPresent());
		}

		@Test
		@DisplayName("should return EntityHaving from getEntityHaving")
		void shouldReturnEntityHavingFromGetEntityHaving() {
			final Segment segment = new Segment(
				entityHaving(attributeEquals("code", "123")),
				orderBy(attributeNatural("code")),
				limit(5)
			);

			final Optional<EntityHaving> entityHavingResult = segment.getEntityHaving();

			assertTrue(entityHavingResult.isPresent());
		}

		@Test
		@DisplayName("should return empty Optional when no EntityHaving")
		void shouldReturnEmptyOptionalWhenNoEntityHaving() {
			final Segment segment = new Segment(orderBy(attributeNatural("code")));

			final Optional<EntityHaving> entityHavingResult = segment.getEntityHaving();

			assertFalse(entityHavingResult.isPresent());
		}
	}

	@Nested
	@DisplayName("Type and visitor")
	class TypeAndVisitorTest {

		@Test
		@DisplayName("should return OrderConstraint class as type")
		void shouldReturnOrderConstraintClassAsType() {
			final Segment segment = new Segment(orderBy(attributeNatural("code")));

			assertEquals(OrderConstraint.class, segment.getType());
		}

		@Test
		@DisplayName("should accept visitor")
		void shouldAcceptVisitor() {
			final Segment segment = new Segment(orderBy(attributeNatural("code")));
			final AtomicReference<Constraint<?>> visited = new AtomicReference<>();
			segment.accept(new ConstraintVisitor() {
				@Override
				public void visit(@Nonnull Constraint<?> constraint) {
					visited.set(constraint);
				}
			});

			assertSame(segment, visited.get());
		}
	}

	@Nested
	@DisplayName("Copy and clone operations")
	class CopyAndCloneTest {

		@Test
		@DisplayName("should create copy with new children via getCopyWithNewChildren")
		void shouldCreateCopyWithNewChildrenViaGetCopyWithNewChildren() {
			final Segment original = new Segment(orderBy(attributeNatural("code")), limit(5));
			final OrderConstraint copy = original.getCopyWithNewChildren(
				new OrderConstraint[]{orderBy(attributeNatural("name", DESC))},
				new Constraint<?>[0]
			);

			assertInstanceOf(Segment.class, copy);
			final Segment copiedSegment = (Segment) copy;
			assertEquals("name", ((AttributeNatural) copiedSegment.getOrderBy().getChildren()[0]).getAttributeName());
		}

		@Test
		@DisplayName("should preserve additional children in getCopyWithNewChildren")
		void shouldPreserveAdditionalChildrenInGetCopyWithNewChildren() {
			final Segment original = new Segment(
				entityHaving(attributeEquals("code", "123")),
				orderBy(attributeNatural("code")),
				limit(5)
			);
			final OrderConstraint copy = original.getCopyWithNewChildren(
				new OrderConstraint[]{orderBy(attributeNatural("name"))},
				new Constraint<?>[]{entityHaving(attributeEquals("status", "active"))}
			);

			assertInstanceOf(Segment.class, copy);
			final Segment copiedSegment = (Segment) copy;
			assertTrue(copiedSegment.getEntityHaving().isPresent());
		}

		@Test
		@DisplayName("should throw UnsupportedOperationException when cloning with arguments")
		void shouldThrowWhenCloningWithArguments() {
			final Segment segment = new Segment(orderBy(attributeNatural("code")));

			// argument-less containers throw UnsupportedOperationException by convention
			assertThrows(
				UnsupportedOperationException.class,
				() -> segment.cloneWithArguments(new Serializable[]{"arg"})
			);
		}
	}

	@Nested
	@DisplayName("String representation")
	class ToStringTest {

		@Test
		@DisplayName("should produce expected toString format for simple segment")
		void shouldProduceExpectedToStringForSimpleSegment() {
			final Segment segment = new Segment(orderBy(attributeNatural("code")));

			assertEquals("segment(orderBy(attributeNatural('code',ASC)))", segment.toString());
		}

		@Test
		@DisplayName("should produce expected toString format for segment with limit")
		void shouldProduceExpectedToStringForSegmentWithLimit() {
			final Segment segment = new Segment(orderBy(attributeNatural("code")), limit(5));

			assertEquals(
				"segment(orderBy(attributeNatural('code',ASC)),limit(5))",
				segment.toString()
			);
		}

		@Test
		@DisplayName("should produce expected toString format for segment with filter and limit")
		void shouldProduceExpectedToStringForSegmentWithFilterAndLimit() {
			final Segment segment = new Segment(
				entityHaving(attributeEquals("code", "123")),
				orderBy(attributeNatural("code")),
				limit(5)
			);

			assertEquals(
				"segment(entityHaving(attributeEquals('code','123'))," +
					"orderBy(attributeNatural('code',ASC)),limit(5))",
				segment.toString()
			);
		}
	}

	@Nested
	@DisplayName("Equality and hashCode")
	class EqualityTest {

		@Test
		@DisplayName("should conform to equals and hashCode contract")
		void shouldConformToEqualsAndHashContract() {
			final Segment segment1 = new Segment(orderBy(attributeNatural("code")));
			final Segment segment2 = new Segment(orderBy(attributeNatural("code")));
			final Segment segment3 = new Segment(orderBy(attributeNatural("name")));

			assertSame(segment1, segment1);
			assertNotSame(segment1, segment2);
			assertEquals(segment1, segment2);
			assertNotEquals(segment1, segment3);
			assertEquals(segment1.hashCode(), segment2.hashCode());
		}

		@Test
		@DisplayName("should treat segments with different limits as not equal")
		void shouldTreatSegmentsWithDifferentLimitsAsNotEqual() {
			final Segment segmentLimit5 = new Segment(orderBy(attributeNatural("code")), limit(5));
			final Segment segmentLimit10 = new Segment(orderBy(attributeNatural("code")), limit(10));
			final Segment segmentLimit5Copy = new Segment(orderBy(attributeNatural("code")), limit(5));

			assertEquals(segmentLimit5, segmentLimit5Copy);
			assertNotEquals(segmentLimit5, segmentLimit10);
			assertEquals(segmentLimit5.hashCode(), segmentLimit5Copy.hashCode());
			assertNotEquals(segmentLimit5.hashCode(), segmentLimit10.hashCode());
		}

		@Test
		@DisplayName("should treat segments with different filters as not equal")
		void shouldTreatSegmentsWithDifferentFiltersAsNotEqual() {
			final Segment segmentFilter1 = new Segment(
				entityHaving(attributeEquals("code", "123")),
				orderBy(attributeNatural("code")),
				limit(5)
			);
			final Segment segmentFilter2 = new Segment(
				entityHaving(attributeEquals("code", "456")),
				orderBy(attributeNatural("code")),
				limit(5)
			);
			final Segment segmentFilter1Copy = new Segment(
				entityHaving(attributeEquals("code", "123")),
				orderBy(attributeNatural("code")),
				limit(5)
			);

			assertEquals(segmentFilter1, segmentFilter1Copy);
			assertNotEquals(segmentFilter1, segmentFilter2);
			assertEquals(segmentFilter1.hashCode(), segmentFilter1Copy.hashCode());
			assertNotEquals(segmentFilter1.hashCode(), segmentFilter2.hashCode());
		}
	}
}
