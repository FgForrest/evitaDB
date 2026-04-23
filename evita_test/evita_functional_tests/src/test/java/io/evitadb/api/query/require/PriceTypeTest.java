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

package io.evitadb.api.query.require;

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.ConstraintVisitor;
import io.evitadb.api.query.RequireConstraint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.api.query.QueryConstraints.priceType;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link PriceType} verifying construction, applicability, getters,
 * clone operations, visitor acceptance, and equality contract.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("PriceType constraint")
class PriceTypeTest {

	@Nested
	@DisplayName("Construction and factory methods")
	class ConstructionTest {

		@Test
		@DisplayName("should create with WITH_TAX mode")
		void shouldCreateWithTaxMode() {
			final PriceType priceType = priceType(QueryPriceMode.WITH_TAX);

			assertEquals(QueryPriceMode.WITH_TAX, priceType.getQueryPriceMode());
		}

		@Test
		@DisplayName("should create with WITHOUT_TAX mode")
		void shouldCreateWithoutTaxMode() {
			final PriceType priceType = priceType(QueryPriceMode.WITHOUT_TAX);

			assertEquals(QueryPriceMode.WITHOUT_TAX, priceType.getQueryPriceMode());
		}

		@Test
		@DisplayName("should default null to WITH_TAX via factory")
		void shouldDefaultNullToWithTax() {
			final PriceType priceType = priceType(null);

			assertEquals(QueryPriceMode.WITH_TAX, priceType.getQueryPriceMode());
		}
	}

	@Nested
	@DisplayName("Applicability")
	class ApplicabilityTest {

		@Test
		@DisplayName("should be applicable when created with valid mode")
		void shouldBeApplicableWithValidMode() {
			assertTrue(priceType(QueryPriceMode.WITH_TAX).isApplicable());
			assertTrue(priceType(QueryPriceMode.WITHOUT_TAX).isApplicable());
			assertTrue(priceType(null).isApplicable());
		}
	}

	@Nested
	@DisplayName("Type and visitor")
	class TypeAndVisitorTest {

		@Test
		@DisplayName("should return RequireConstraint class as type")
		void shouldReturnRequireConstraintClassAsType() {
			assertEquals(RequireConstraint.class, priceType(QueryPriceMode.WITH_TAX).getType());
		}

		@Test
		@DisplayName("should accept visitor")
		void shouldAcceptVisitor() {
			final PriceType constraint = priceType(QueryPriceMode.WITH_TAX);
			final AtomicReference<Constraint<?>> visited = new AtomicReference<>();
			constraint.accept(new ConstraintVisitor() {
				@Override
				public void visit(@Nonnull Constraint<?> constraint) {
					visited.set(constraint);
				}
			});

			assertSame(constraint, visited.get());
		}
	}

	@Nested
	@DisplayName("Clone operations")
	class CloneTest {

		@Test
		@DisplayName("should clone with new arguments using provided mode")
		void shouldCloneWithNewArguments() {
			final PriceType original = priceType(QueryPriceMode.WITH_TAX);
			final RequireConstraint cloned = original.cloneWithArguments(
				new Serializable[]{QueryPriceMode.WITHOUT_TAX}
			);

			assertNotSame(original, cloned);
			assertInstanceOf(PriceType.class, cloned);
			final PriceType clonedPriceType = (PriceType) cloned;
			assertEquals(QueryPriceMode.WITHOUT_TAX, clonedPriceType.getQueryPriceMode());
		}
	}

	@Nested
	@DisplayName("String representation")
	class ToStringTest {

		@Test
		@DisplayName("should produce expected toString with WITH_TAX")
		void shouldProduceToStringWithTax() {
			assertEquals("priceType(WITH_TAX)", priceType(QueryPriceMode.WITH_TAX).toString());
		}

		@Test
		@DisplayName("should produce expected toString with WITHOUT_TAX")
		void shouldProduceToStringWithoutTax() {
			assertEquals("priceType(WITHOUT_TAX)", priceType(QueryPriceMode.WITHOUT_TAX).toString());
		}
	}

	@Nested
	@DisplayName("Equality and hashCode")
	class EqualityTest {

		@Test
		@DisplayName("should conform to equals and hashCode contract")
		void shouldConformToEqualsAndHashContract() {
			assertNotSame(
				priceType(QueryPriceMode.WITH_TAX),
				priceType(QueryPriceMode.WITH_TAX)
			);
			assertEquals(
				priceType(QueryPriceMode.WITH_TAX),
				priceType(QueryPriceMode.WITH_TAX)
			);
			assertEquals(
				priceType(QueryPriceMode.WITHOUT_TAX),
				priceType(QueryPriceMode.WITHOUT_TAX)
			);
			assertNotEquals(
				priceType(QueryPriceMode.WITH_TAX),
				priceType(QueryPriceMode.WITHOUT_TAX)
			);
			assertEquals(
				priceType(QueryPriceMode.WITH_TAX).hashCode(),
				priceType(QueryPriceMode.WITH_TAX).hashCode()
			);
			assertNotEquals(
				priceType(QueryPriceMode.WITH_TAX).hashCode(),
				priceType(QueryPriceMode.WITHOUT_TAX).hashCode()
			);
		}
	}
}
