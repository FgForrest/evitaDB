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
import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.api.query.QueryConstraints.priceBetween;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link PriceBetween} verifying construction, applicability, property accessors,
 * cloning, visitor support, string representation, and equality contract.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("PriceBetween constraint")
class PriceBetweenTest {

	@Nested
	@DisplayName("Construction")
	class ConstructionTest {

		@Test
		@DisplayName("should create via factory method with both bounds")
		void shouldCreateViaFactoryWithBothBounds() {
			final PriceBetween between = priceBetween(new BigDecimal("1"), new BigDecimal("2"));

			assertNotNull(between);
			assertEquals(new BigDecimal("1"), between.getFrom());
			assertEquals(new BigDecimal("2"), between.getTo());
		}

		@Test
		@DisplayName("should create via factory method with from only")
		void shouldCreateViaFactoryWithFromOnly() {
			final PriceBetween between = priceBetween(new BigDecimal("10"), null);

			assertNotNull(between);
			assertEquals(new BigDecimal("10"), between.getFrom());
			assertNull(between.getTo());
		}

		@Test
		@DisplayName("should create via factory method with to only")
		void shouldCreateViaFactoryWithToOnly() {
			final PriceBetween between = priceBetween(null, new BigDecimal("20"));

			assertNotNull(between);
			assertNull(between.getFrom());
			assertEquals(new BigDecimal("20"), between.getTo());
		}

		@Test
		@DisplayName("should return null from factory when both args null")
		void shouldReturnNullFromFactoryWhenBothArgsNull() {
			assertNull(priceBetween(null, null));
		}
	}

	@Nested
	@DisplayName("Applicability")
	class ApplicabilityTest {

		@Test
		@DisplayName("should be non-applicable when both arguments are null")
		void shouldBeNonApplicableWhenBothNull() {
			assertFalse(new PriceBetween(null, null).isApplicable());
		}

		@Test
		@DisplayName("should be applicable when only to is provided")
		void shouldBeApplicableWhenOnlyToProvided() {
			assertTrue(priceBetween(null, new BigDecimal("1")).isApplicable());
		}

		@Test
		@DisplayName("should be applicable when only from is provided")
		void shouldBeApplicableWhenOnlyFromProvided() {
			assertTrue(priceBetween(new BigDecimal("1"), null).isApplicable());
		}

		@Test
		@DisplayName("should be applicable when both bounds are provided")
		void shouldBeApplicableWhenBothProvided() {
			assertTrue(priceBetween(new BigDecimal("1"), new BigDecimal("2")).isApplicable());
		}
	}

	@Nested
	@DisplayName("Property accessors")
	class PropertyAccessorsTest {

		@Test
		@DisplayName("should return from value")
		void shouldReturnFromValue() {
			final PriceBetween between = priceBetween(new BigDecimal("100.50"), new BigDecimal("200"));

			assertEquals(new BigDecimal("100.50"), between.getFrom());
		}

		@Test
		@DisplayName("should return to value")
		void shouldReturnToValue() {
			final PriceBetween between = priceBetween(new BigDecimal("100"), new BigDecimal("999.99"));

			assertEquals(new BigDecimal("999.99"), between.getTo());
		}

		@Test
		@DisplayName("should return null from when constructed with null from")
		void shouldReturnNullFrom() {
			final PriceBetween between = priceBetween(null, new BigDecimal("50"));

			assertNull(between.getFrom());
		}

		@Test
		@DisplayName("should return null to when constructed with null to")
		void shouldReturnNullTo() {
			final PriceBetween between = priceBetween(new BigDecimal("50"), null);

			assertNull(between.getTo());
		}
	}

	@Nested
	@DisplayName("Cloning")
	class CloningTest {

		@Test
		@DisplayName("should produce equal but not same instance via cloneWithArguments")
		void shouldCloneWithArguments() {
			final PriceBetween original = priceBetween(new BigDecimal("1"), new BigDecimal("2"));
			final FilterConstraint clone = original.cloneWithArguments(
				new Serializable[]{new BigDecimal("1"), new BigDecimal("2")}
			);

			assertEquals(original, clone);
			assertNotSame(original, clone);
			assertInstanceOf(PriceBetween.class, clone);
		}
	}

	@Nested
	@DisplayName("Visitor support")
	class VisitorSupportTest {

		@Test
		@DisplayName("should accept visitor and call visit method")
		void shouldAcceptVisitor() {
			final PriceBetween between = priceBetween(new BigDecimal("1"), new BigDecimal("2"));
			final AtomicReference<Constraint<?>> visited = new AtomicReference<>();
			between.accept(new ConstraintVisitor() {
				@Override
				public void visit(@Nonnull Constraint<?> constraint) {
					visited.set(constraint);
				}
			});

			assertSame(between, visited.get());
		}

		@Test
		@DisplayName("should return FilterConstraint class as type")
		void shouldReturnCorrectType() {
			final PriceBetween between = priceBetween(new BigDecimal("1"), new BigDecimal("2"));

			assertEquals(FilterConstraint.class, between.getType());
		}
	}

	@Nested
	@DisplayName("String representation")
	class ToStringTest {

		@Test
		@DisplayName("should produce expected format with both bounds")
		void shouldToStringWithBothBounds() {
			assertEquals(
				"priceBetween(1,2)",
				priceBetween(new BigDecimal("1"), new BigDecimal("2")).toString()
			);
		}

		@Test
		@DisplayName("should produce expected format with null from")
		void shouldToStringWithNullFrom() {
			assertEquals(
				"priceBetween(<NULL>,2)",
				priceBetween(null, new BigDecimal("2")).toString()
			);
		}

		@Test
		@DisplayName("should produce expected format with null to")
		void shouldToStringWithNullTo() {
			assertEquals(
				"priceBetween(1,<NULL>)",
				priceBetween(new BigDecimal("1"), null).toString()
			);
		}
	}

	@Nested
	@DisplayName("Equality and hashCode")
	class EqualityTest {

		@Test
		@DisplayName("should conform to equals and hashCode contract")
		void shouldConformToEqualsAndHashContract() {
			assertNotSame(
				priceBetween(new BigDecimal("1"), new BigDecimal("2")),
				priceBetween(new BigDecimal("1"), new BigDecimal("2"))
			);
			assertEquals(
				priceBetween(new BigDecimal("1"), new BigDecimal("2")),
				priceBetween(new BigDecimal("1"), new BigDecimal("2"))
			);
			assertNotEquals(
				priceBetween(new BigDecimal("1"), new BigDecimal("2")),
				priceBetween(new BigDecimal("1"), new BigDecimal("3"))
			);
			assertNotEquals(
				priceBetween(new BigDecimal("1"), new BigDecimal("2")),
				priceBetween(null, null)
			);
			assertNotEquals(
				priceBetween(new BigDecimal("1"), new BigDecimal("2")),
				priceBetween(null, new BigDecimal("3"))
			);
			assertEquals(
				priceBetween(null, new BigDecimal("2")),
				priceBetween(null, new BigDecimal("2"))
			);
		}

		@Test
		@DisplayName("should produce consistent hashCodes for equal instances")
		void shouldProduceConsistentHashCodes() {
			assertEquals(
				priceBetween(new BigDecimal("1"), new BigDecimal("2")).hashCode(),
				priceBetween(new BigDecimal("1"), new BigDecimal("2")).hashCode()
			);
			assertNotEquals(
				priceBetween(new BigDecimal("1"), new BigDecimal("2")).hashCode(),
				priceBetween(new BigDecimal("1"), new BigDecimal("3")).hashCode()
			);
			assertNotEquals(
				priceBetween(new BigDecimal("1"), new BigDecimal("2")).hashCode(),
				new PriceBetween(null, null).hashCode()
			);
			assertNotEquals(
				priceBetween(new BigDecimal("1"), new BigDecimal("2")).hashCode(),
				priceBetween(null, new BigDecimal("3")).hashCode()
			);
			assertEquals(
				priceBetween(null, new BigDecimal("2")).hashCode(),
				priceBetween(null, new BigDecimal("2")).hashCode()
			);
		}
	}
}
