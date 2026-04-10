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

package io.evitadb.api.query.order;

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.ConstraintVisitor;
import io.evitadb.api.query.OrderConstraint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.api.query.QueryConstraints.priceNatural;
import static io.evitadb.api.query.order.OrderDirection.ASC;
import static io.evitadb.api.query.order.OrderDirection.DESC;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link PriceNatural} verifying construction, applicability, property accessors,
 * cloning, visitor support, string representation, and equality contract.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("PriceNatural constraint")
class PriceNaturalTest {

	@Nested
	@DisplayName("Construction")
	class ConstructionTest {

		@Test
		@DisplayName("should default to ASC when no direction specified")
		void shouldDefaultToAsc() {
			final PriceNatural constraint = priceNatural();

			assertEquals(ASC, constraint.getOrderDirection());
		}

		@Test
		@DisplayName("should create with explicit DESC direction")
		void shouldCreateWithDescDirection() {
			final PriceNatural constraint = priceNatural(DESC);

			assertEquals(DESC, constraint.getOrderDirection());
		}

		@Test
		@DisplayName("should create with explicit ASC direction")
		void shouldCreateWithAscDirection() {
			final PriceNatural constraint = priceNatural(ASC);

			assertEquals(ASC, constraint.getOrderDirection());
		}
	}

	@Nested
	@DisplayName("Applicability")
	class ApplicabilityTest {

		@Test
		@DisplayName("should be applicable with default ASC direction")
		void shouldBeApplicableWithDefault() {
			assertTrue(priceNatural().isApplicable());
		}

		@Test
		@DisplayName("should be applicable with explicit DESC direction")
		void shouldBeApplicableWithDesc() {
			assertTrue(priceNatural(DESC).isApplicable());
		}

		@Test
		@DisplayName("should not be applicable with null direction")
		void shouldNotBeApplicableWithNull() {
			assertFalse(new PriceNatural(null).isApplicable());
		}
	}

	@Nested
	@DisplayName("Property accessors")
	class PropertyAccessorsTest {

		@Test
		@DisplayName("should return ASC order direction by default")
		void shouldReturnAscByDefault() {
			assertEquals(ASC, priceNatural().getOrderDirection());
		}

		@Test
		@DisplayName("should return DESC order direction when specified")
		void shouldReturnDescWhenSpecified() {
			assertEquals(DESC, priceNatural(DESC).getOrderDirection());
		}
	}

	@Nested
	@DisplayName("Cloning")
	class CloningTest {

		@Test
		@DisplayName("should produce equal but not same instance via cloneWithArguments")
		void shouldCloneWithArguments() {
			final PriceNatural original = priceNatural(DESC);
			final OrderConstraint clone = original.cloneWithArguments(new Serializable[]{DESC});

			assertEquals(original, clone);
			assertNotSame(original, clone);
			assertInstanceOf(PriceNatural.class, clone);
		}

		@Test
		@DisplayName("should clone with different arguments")
		void shouldCloneWithDifferentArguments() {
			final PriceNatural original = priceNatural(ASC);
			final OrderConstraint clone = original.cloneWithArguments(new Serializable[]{DESC});

			assertNotEquals(original, clone);
			assertInstanceOf(PriceNatural.class, clone);
		}
	}

	@Nested
	@DisplayName("Visitor support")
	class VisitorSupportTest {

		@Test
		@DisplayName("should accept visitor and call visit method")
		void shouldAcceptVisitor() {
			final PriceNatural constraint = priceNatural();
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
		@DisplayName("should return OrderConstraint class as type")
		void shouldReturnCorrectType() {
			assertEquals(OrderConstraint.class, priceNatural().getType());
		}
	}

	@Nested
	@DisplayName("String representation")
	class ToStringTest {

		@Test
		@DisplayName("should produce expected format with ASC")
		void shouldToStringWithAsc() {
			assertEquals("priceNatural(ASC)", priceNatural().toString());
		}

		@Test
		@DisplayName("should produce expected format with DESC")
		void shouldToStringWithDesc() {
			assertEquals("priceNatural(DESC)", priceNatural(DESC).toString());
		}
	}

	@Nested
	@DisplayName("Equality and hashCode")
	class EqualityTest {

		@Test
		@DisplayName("should be equal for same direction")
		void shouldBeEqualForSameDirection() {
			assertNotSame(priceNatural(), priceNatural());
			assertEquals(priceNatural(), priceNatural());
			assertEquals(priceNatural(ASC), priceNatural());
			assertEquals(priceNatural().hashCode(), priceNatural().hashCode());
			assertEquals(priceNatural(ASC).hashCode(), priceNatural().hashCode());
		}

		@Test
		@DisplayName("should not be equal for different directions")
		void shouldNotBeEqualForDifferentDirections() {
			assertNotEquals(priceNatural(ASC), priceNatural(DESC));
			assertNotEquals(priceNatural(ASC).hashCode(), priceNatural(DESC).hashCode());
		}
	}
}
