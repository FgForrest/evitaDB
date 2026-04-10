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

import static io.evitadb.api.query.QueryConstraints.priceDiscount;
import static io.evitadb.api.query.order.OrderDirection.ASC;
import static io.evitadb.api.query.order.OrderDirection.DESC;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link PriceDiscount} verifying construction, applicability, property accessors,
 * default argument handling, cloning, visitor support, string representation, and equality contract.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("PriceDiscount constraint")
class PriceDiscountTest {

	@Nested
	@DisplayName("Construction")
	class ConstructionTest {

		@Test
		@DisplayName("should create with default DESC order when only price lists provided")
		void shouldCreateWithDefaultDescOrder() {
			final PriceDiscount constraint = priceDiscount("discount", "basic");

			assertEquals(DESC, constraint.getOrder());
			assertArrayEquals(new String[]{"discount", "basic"}, constraint.getInPriceLists());
		}

		@Test
		@DisplayName("should create with explicit ASC order")
		void shouldCreateWithExplicitAscOrder() {
			final PriceDiscount constraint = priceDiscount(ASC, "discount", "basic");

			assertEquals(ASC, constraint.getOrder());
			assertArrayEquals(new String[]{"discount", "basic"}, constraint.getInPriceLists());
		}

		@Test
		@DisplayName("should create with explicit DESC order")
		void shouldCreateWithExplicitDescOrder() {
			final PriceDiscount constraint = priceDiscount(DESC, "discount");

			assertEquals(DESC, constraint.getOrder());
			assertArrayEquals(new String[]{"discount"}, constraint.getInPriceLists());
		}

		@Test
		@DisplayName("should create with single price list")
		void shouldCreateWithSinglePriceList() {
			final PriceDiscount constraint = priceDiscount("vip");

			assertEquals(DESC, constraint.getOrder());
			assertArrayEquals(new String[]{"vip"}, constraint.getInPriceLists());
		}

		@Test
		@DisplayName("should create with multiple price lists")
		void shouldCreateWithMultiplePriceLists() {
			final PriceDiscount constraint = priceDiscount(ASC, "discount", "basic", "vip");

			assertEquals(ASC, constraint.getOrder());
			assertArrayEquals(new String[]{"discount", "basic", "vip"}, constraint.getInPriceLists());
		}
	}

	@Nested
	@DisplayName("Applicability")
	class ApplicabilityTest {

		@Test
		@DisplayName("should be applicable with price lists and default order")
		void shouldBeApplicableWithDefaultOrder() {
			assertTrue(priceDiscount("discount").isApplicable());
		}

		@Test
		@DisplayName("should be applicable with price lists and explicit order")
		void shouldBeApplicableWithExplicitOrder() {
			assertTrue(priceDiscount(ASC, "discount", "basic").isApplicable());
			assertTrue(priceDiscount(DESC, "discount").isApplicable());
		}
	}

	@Nested
	@DisplayName("Property accessors")
	class PropertyAccessorsTest {

		@Test
		@DisplayName("should return DESC when no order direction specified")
		void shouldReturnDescByDefault() {
			final PriceDiscount constraint = priceDiscount("discount");

			assertEquals(DESC, constraint.getOrder());
		}

		@Test
		@DisplayName("should return ASC when explicitly specified")
		void shouldReturnAscWhenSpecified() {
			final PriceDiscount constraint = priceDiscount(ASC, "discount");

			assertEquals(ASC, constraint.getOrder());
		}

		@Test
		@DisplayName("should return correct price lists")
		void shouldReturnCorrectPriceLists() {
			final PriceDiscount constraint = priceDiscount(ASC, "discount", "basic");

			assertArrayEquals(new String[]{"discount", "basic"}, constraint.getInPriceLists());
		}

		@Test
		@DisplayName("should return price lists excluding order direction from arguments")
		void shouldReturnPriceListsExcludingOrderDirection() {
			// DESC variant: no OrderDirection stored in args, only strings
			final PriceDiscount descConstraint = priceDiscount("discount", "basic");
			assertArrayEquals(new String[]{"discount", "basic"}, descConstraint.getInPriceLists());

			// ASC variant: OrderDirection.ASC stored in args along with strings
			final PriceDiscount ascConstraint = priceDiscount(ASC, "discount", "basic");
			assertArrayEquals(new String[]{"discount", "basic"}, ascConstraint.getInPriceLists());
		}
	}

	@Nested
	@DisplayName("ConstraintWithDefaults behavior")
	class ConstraintWithDefaultsTest {

		@Test
		@DisplayName("should exclude DESC from arguments (it is the default)")
		void shouldExcludeDescFromArguments() {
			final PriceDiscount constraint = priceDiscount(DESC, "discount", "basic");
			final Serializable[] argsExcludingDefaults = constraint.getArgumentsExcludingDefaults();

			// DESC is omitted because it is the default
			assertArrayEquals(new Serializable[]{"discount", "basic"}, argsExcludingDefaults);
		}

		@Test
		@DisplayName("should include ASC in arguments (it is not the default)")
		void shouldIncludeAscInArguments() {
			final PriceDiscount constraint = priceDiscount(ASC, "discount", "basic");
			final Serializable[] argsExcludingDefaults = constraint.getArgumentsExcludingDefaults();

			assertArrayEquals(new Serializable[]{ASC, "discount", "basic"}, argsExcludingDefaults);
		}

		@Test
		@DisplayName("should consider DESC as implicit argument")
		void shouldConsiderDescAsImplicit() {
			final PriceDiscount constraint = priceDiscount("discount");

			assertTrue(constraint.isArgumentImplicit(DESC));
		}

		@Test
		@DisplayName("should not consider ASC as implicit argument")
		void shouldNotConsiderAscAsImplicit() {
			final PriceDiscount constraint = priceDiscount(ASC, "discount");

			assertFalse(constraint.isArgumentImplicit(ASC));
		}

		@Test
		@DisplayName("should not consider string as implicit argument")
		void shouldNotConsiderStringAsImplicit() {
			final PriceDiscount constraint = priceDiscount("discount");

			assertFalse(constraint.isArgumentImplicit("discount"));
		}
	}

	@Nested
	@DisplayName("Cloning")
	class CloningTest {

		@Test
		@DisplayName("should produce equal but not same instance via cloneWithArguments")
		void shouldCloneWithArguments() {
			final PriceDiscount original = priceDiscount(ASC, "discount", "basic");
			final OrderConstraint clone = original.cloneWithArguments(
				new Serializable[]{ASC, "discount", "basic"}
			);

			assertEquals(original, clone);
			assertNotSame(original, clone);
			assertInstanceOf(PriceDiscount.class, clone);
		}

		@Test
		@DisplayName("should clone with different arguments")
		void shouldCloneWithDifferentArguments() {
			final PriceDiscount original = priceDiscount("discount");
			final OrderConstraint clone = original.cloneWithArguments(
				new Serializable[]{ASC, "vip"}
			);

			assertNotEquals(original, clone);
			assertInstanceOf(PriceDiscount.class, clone);
		}
	}

	@Nested
	@DisplayName("Visitor support")
	class VisitorSupportTest {

		@Test
		@DisplayName("should accept visitor and call visit method")
		void shouldAcceptVisitor() {
			final PriceDiscount constraint = priceDiscount("discount");
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
			final PriceDiscount constraint = priceDiscount("discount");

			assertEquals(OrderConstraint.class, constraint.getType());
		}
	}

	@Nested
	@DisplayName("String representation")
	class ToStringTest {

		@Test
		@DisplayName("should omit DESC direction in toString (it is the default)")
		void shouldOmitDescInToString() {
			final PriceDiscount constraint = priceDiscount("discount", "basic");

			assertEquals("priceDiscount('discount','basic')", constraint.toString());
		}

		@Test
		@DisplayName("should include ASC direction in toString")
		void shouldIncludeAscInToString() {
			final PriceDiscount constraint = priceDiscount(ASC, "discount", "basic");

			assertEquals("priceDiscount(ASC,'discount','basic')", constraint.toString());
		}

		@Test
		@DisplayName("should show single price list correctly")
		void shouldShowSinglePriceList() {
			final PriceDiscount constraint = priceDiscount("vip");

			assertEquals("priceDiscount('vip')", constraint.toString());
		}
	}

	@Nested
	@DisplayName("Equality and hashCode")
	class EqualityTest {

		@Test
		@DisplayName("should be equal for same price lists with default order")
		void shouldBeEqualForSamePriceLists() {
			assertNotSame(priceDiscount("discount"), priceDiscount("discount"));
			assertEquals(priceDiscount("discount"), priceDiscount("discount"));
			assertEquals(
				priceDiscount("discount").hashCode(),
				priceDiscount("discount").hashCode()
			);
		}

		@Test
		@DisplayName("should be equal for explicitly specified DESC and default DESC")
		void shouldBeEqualForExplicitAndDefaultDesc() {
			// Both store only strings when DESC (default) — so they are equal
			assertEquals(priceDiscount("discount"), priceDiscount(DESC, "discount"));
			assertEquals(
				priceDiscount("discount").hashCode(),
				priceDiscount(DESC, "discount").hashCode()
			);
		}

		@Test
		@DisplayName("should not be equal for different order directions")
		void shouldNotBeEqualForDifferentDirections() {
			assertNotEquals(priceDiscount(ASC, "discount"), priceDiscount(DESC, "discount"));
			assertNotEquals(priceDiscount(ASC, "discount"), priceDiscount("discount"));
		}

		@Test
		@DisplayName("should not be equal for different price lists")
		void shouldNotBeEqualForDifferentPriceLists() {
			assertNotEquals(priceDiscount("discount"), priceDiscount("vip"));
			assertNotEquals(
				priceDiscount("discount").hashCode(),
				priceDiscount("vip").hashCode()
			);
		}

		@Test
		@DisplayName("should not be equal for different number of price lists")
		void shouldNotBeEqualForDifferentNumberOfPriceLists() {
			assertNotEquals(
				priceDiscount("discount"),
				priceDiscount("discount", "basic")
			);
		}
	}
}
