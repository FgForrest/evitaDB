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
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.api.query.QueryConstraints.priceInPriceLists;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link PriceInPriceLists} verifying construction, applicability, property accessors,
 * cloning, visitor support, string representation, and equality contract.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("PriceInPriceLists constraint")
class PriceInPriceListsTest {

	@Nested
	@DisplayName("Construction")
	class ConstructionTest {

		@Test
		@DisplayName("should create via factory method with multiple price lists")
		void shouldCreateViaFactoryWithMultiplePriceLists() {
			final PriceInPriceLists constraint = priceInPriceLists("basic", "reference");

			assertArrayEquals(new String[]{"basic", "reference"}, constraint.getPriceLists());
		}

		@Test
		@DisplayName("should create via factory method with single price list")
		void shouldCreateViaFactoryWithSinglePriceList() {
			final PriceInPriceLists constraint = priceInPriceLists("basic");

			assertArrayEquals(new String[]{"basic"}, constraint.getPriceLists());
		}

		@Test
		@DisplayName("should filter out null values in array")
		void shouldFilterOutNullValuesInArray() {
			final PriceInPriceLists constraint = priceInPriceLists("basic", null, "reference");

			assertArrayEquals(new String[]{"basic", "reference"}, constraint.getPriceLists());
		}

		@Test
		@DisplayName("should filter out blank string values in array")
		void shouldFilterOutBlankStringValues() {
			final PriceInPriceLists constraint = priceInPriceLists("basic", "", "reference");

			assertArrayEquals(new String[]{"basic", "reference"}, constraint.getPriceLists());
		}

		@Test
		@DisplayName("should return null from factory for null variable")
		void shouldReturnNullFromFactoryForNullVariable() {
			final String nullString = null;

			assertNull(priceInPriceLists(nullString));
		}

		@Test
		@DisplayName("should create constraint with empty array")
		void shouldCreateWithEmptyArray() {
			final PriceInPriceLists constraint = priceInPriceLists(new String[0]);

			assertArrayEquals(new String[0], constraint.getPriceLists());
		}
	}

	@Nested
	@DisplayName("Applicability")
	class ApplicabilityTest {

		@Test
		@DisplayName("should always be applicable even with empty price lists")
		void shouldAlwaysBeApplicableWithEmptyPriceLists() {
			assertTrue(new PriceInPriceLists(new String[0]).isApplicable());
		}

		@Test
		@DisplayName("should be applicable with single price list")
		void shouldBeApplicableWithSinglePriceList() {
			assertTrue(priceInPriceLists("A").isApplicable());
		}

		@Test
		@DisplayName("should be applicable with multiple price lists")
		void shouldBeApplicableWithMultiplePriceLists() {
			assertTrue(priceInPriceLists("A", "B").isApplicable());
		}
	}

	@Nested
	@DisplayName("Property accessors")
	class PropertyAccessorsTest {

		@Test
		@DisplayName("should return price lists in insertion order")
		void shouldReturnPriceListsInInsertionOrder() {
			final PriceInPriceLists constraint = priceInPriceLists("vip", "basic", "reference");

			assertArrayEquals(new String[]{"vip", "basic", "reference"}, constraint.getPriceLists());
		}

		@Test
		@DisplayName("should return empty array when constructed with empty array")
		void shouldReturnEmptyArray() {
			final PriceInPriceLists constraint = priceInPriceLists(new String[0]);

			assertArrayEquals(new String[0], constraint.getPriceLists());
		}
	}

	@Nested
	@DisplayName("Cloning")
	class CloningTest {

		@Test
		@DisplayName("should produce equal but not same instance via cloneWithArguments")
		void shouldCloneWithArguments() {
			final PriceInPriceLists original = priceInPriceLists("basic", "reference");
			final FilterConstraint clone = original.cloneWithArguments(
				new Serializable[]{"basic", "reference"}
			);

			assertEquals(original, clone);
			assertNotSame(original, clone);
			assertInstanceOf(PriceInPriceLists.class, clone);
		}
	}

	@Nested
	@DisplayName("Visitor support")
	class VisitorSupportTest {

		@Test
		@DisplayName("should accept visitor and call visit method")
		void shouldAcceptVisitor() {
			final PriceInPriceLists constraint = priceInPriceLists("basic");
			final AtomicReference<Constraint<?>> visited = new AtomicReference<>();
			constraint.accept(new ConstraintVisitor() {
				@Override
				public void visit(@Nonnull Constraint<?> constraint) {
					visited.set(constraint);
				}
			});

			assertSame(constraint, visited.get());
		}

		@Test
		@DisplayName("should return FilterConstraint class as type")
		void shouldReturnCorrectType() {
			final PriceInPriceLists constraint = priceInPriceLists("basic");

			assertEquals(FilterConstraint.class, constraint.getType());
		}
	}

	@Nested
	@DisplayName("String representation")
	class ToStringTest {

		@Test
		@DisplayName("should produce expected format with multiple price lists")
		void shouldToStringWithMultiplePriceLists() {
			assertEquals(
				"priceInPriceLists('basic','reference')",
				priceInPriceLists("basic", "reference").toString()
			);
		}

		@Test
		@DisplayName("should produce expected format with single price list")
		void shouldToStringWithSinglePriceList() {
			assertEquals("priceInPriceLists('basic')", priceInPriceLists("basic").toString());
		}

		@Test
		@DisplayName("should produce expected format with empty price lists")
		void shouldToStringWithEmptyPriceLists() {
			assertEquals("priceInPriceLists()", priceInPriceLists(new String[0]).toString());
		}
	}

	@Nested
	@DisplayName("Equality and hashCode")
	class EqualityTest {

		@Test
		@DisplayName("should conform to equals and hashCode contract")
		void shouldConformToEqualsAndHashContract() {
			assertNotSame(
				priceInPriceLists("basic", "reference"),
				priceInPriceLists("basic", "reference")
			);
			assertEquals(
				priceInPriceLists("basic", "reference"),
				priceInPriceLists("basic", "reference")
			);
			assertNotEquals(
				priceInPriceLists("basic", "reference"),
				priceInPriceLists("basic", "action")
			);
			assertNotEquals(
				priceInPriceLists("basic", "reference"),
				priceInPriceLists("basic")
			);
		}

		@Test
		@DisplayName("should produce consistent hashCodes for equal instances")
		void shouldProduceConsistentHashCodes() {
			assertEquals(
				priceInPriceLists("basic", "reference").hashCode(),
				priceInPriceLists("basic", "reference").hashCode()
			);
			assertNotEquals(
				priceInPriceLists("basic", "reference").hashCode(),
				priceInPriceLists("basic", "action").hashCode()
			);
			assertNotEquals(
				priceInPriceLists("basic", "reference").hashCode(),
				priceInPriceLists("basic").hashCode()
			);
		}
	}
}
