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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.api.query.QueryConstraints.priceValidIn;
import static io.evitadb.api.query.QueryConstraints.priceValidInNow;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link PriceValidIn} verifying construction, applicability, property accessors,
 * cloning, visitor support, string representation, and equality contract.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("PriceValidIn constraint")
class PriceValidInTest {

	@Nested
	@DisplayName("Construction")
	class ConstructionTest {

		@Test
		@DisplayName("should create datetime variant via factory method")
		void shouldCreateDatetimeVariantViaFactory() {
			final OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
			final PriceValidIn constraint = priceValidIn(now);

			assertNotNull(constraint);
			assertEquals(now, constraint.getTheMoment(() -> null));
		}

		@Test
		@DisplayName("should create 'now' variant via factory method")
		void shouldCreateNowVariantViaFactory() {
			final PriceValidIn constraint = priceValidInNow();

			assertNotNull(constraint);
		}

		@Test
		@DisplayName("should return null from factory when null datetime is passed")
		void shouldReturnNullFromFactoryWhenNullDatetime() {
			assertNull(priceValidIn(null));
		}

		@Test
		@DisplayName("should create 'now' variant via no-arg constructor")
		void shouldCreateNowVariantViaConstructor() {
			final PriceValidIn constraint = new PriceValidIn();

			assertNotNull(constraint);
			assertNull(constraint.getTheMoment(() -> null));
		}

		@Test
		@DisplayName("should create non-applicable instance with null argument")
		void shouldCreateWithNullArgument() {
			// PriceValidIn(null) stores null as the argument -- still applicable because isApplicable() returns true
			final PriceValidIn constraint = new PriceValidIn(null);

			assertTrue(constraint.isApplicable());
		}
	}

	@Nested
	@DisplayName("Applicability")
	class ApplicabilityTest {

		@Test
		@DisplayName("should always be applicable for 'now' variant")
		void shouldAlwaysBeApplicableForNowVariant() {
			assertTrue(new PriceValidIn().isApplicable());
		}

		@Test
		@DisplayName("should always be applicable for datetime variant")
		void shouldAlwaysBeApplicableForDatetimeVariant() {
			assertTrue(priceValidIn(OffsetDateTime.now(ZoneOffset.UTC)).isApplicable());
		}

		@Test
		@DisplayName("should be applicable even with null argument constructor")
		void shouldBeApplicableEvenWithNullArgument() {
			assertTrue(new PriceValidIn(null).isApplicable());
		}
	}

	@Nested
	@DisplayName("Property accessors")
	class PropertyAccessorsTest {

		@Test
		@DisplayName("should return exact datetime from datetime variant")
		void shouldReturnExactDatetimeFromDatetimeVariant() {
			final OffsetDateTime moment = OffsetDateTime.of(2021, 6, 15, 12, 0, 0, 0, ZoneOffset.UTC);
			final PriceValidIn constraint = priceValidIn(moment);

			assertEquals(moment, constraint.getTheMoment(() -> null));
		}

		@Test
		@DisplayName("should delegate to supplier for 'now' variant")
		void shouldDelegateToSupplierForNowVariant() {
			final OffsetDateTime supplied = OffsetDateTime.of(2025, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
			final PriceValidIn constraint = priceValidInNow();

			assertEquals(supplied, constraint.getTheMoment(() -> supplied));
		}

		@Test
		@DisplayName("should return null when 'now' variant supplier returns null")
		void shouldReturnNullWhenNowVariantSupplierReturnsNull() {
			final PriceValidIn constraint = priceValidInNow();

			assertNull(constraint.getTheMoment(() -> null));
		}

		@Test
		@DisplayName("should ignore supplier for datetime variant")
		void shouldIgnoreSupplierForDatetimeVariant() {
			final OffsetDateTime moment = OffsetDateTime.of(2021, 3, 1, 10, 30, 0, 0, ZoneOffset.UTC);
			final OffsetDateTime otherMoment = OffsetDateTime.of(2099, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
			final PriceValidIn constraint = priceValidIn(moment);

			// datetime variant always returns its own moment, ignoring the supplier
			assertEquals(moment, constraint.getTheMoment(() -> otherMoment));
		}
	}

	@Nested
	@DisplayName("Suffix support")
	class SuffixTest {

		@Test
		@DisplayName("should return 'now' suffix for no-arg variant")
		void shouldReturnNowSuffixForNoArgVariant() {
			final PriceValidIn constraint = priceValidInNow();

			assertEquals(Optional.of("now"), constraint.getSuffixIfApplied());
		}

		@Test
		@DisplayName("should return empty suffix for datetime variant")
		void shouldReturnEmptySuffixForDatetimeVariant() {
			final PriceValidIn constraint = priceValidIn(OffsetDateTime.now(ZoneOffset.UTC));

			assertEquals(Optional.empty(), constraint.getSuffixIfApplied());
		}
	}

	@Nested
	@DisplayName("Cloning")
	class CloningTest {

		@Test
		@DisplayName("should clone datetime variant producing equal but not same instance")
		void shouldCloneDatetimeVariant() {
			final OffsetDateTime moment = OffsetDateTime.of(2021, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
			final PriceValidIn original = priceValidIn(moment);
			final FilterConstraint clone = original.cloneWithArguments(new Serializable[]{moment});

			assertEquals(original, clone);
			assertNotSame(original, clone);
			assertInstanceOf(PriceValidIn.class, clone);
		}

		@Test
		@DisplayName("should clone 'now' variant producing equal but not same instance")
		void shouldCloneNowVariant() {
			final PriceValidIn original = priceValidInNow();
			final FilterConstraint clone = original.cloneWithArguments(new Serializable[0]);

			assertEquals(original, clone);
			assertNotSame(original, clone);
			assertInstanceOf(PriceValidIn.class, clone);
		}
	}

	@Nested
	@DisplayName("Visitor support")
	class VisitorSupportTest {

		@Test
		@DisplayName("should accept visitor and call visit method")
		void shouldAcceptVisitor() {
			final PriceValidIn constraint = priceValidIn(OffsetDateTime.now(ZoneOffset.UTC));
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
			final PriceValidIn constraint = priceValidInNow();

			assertEquals(FilterConstraint.class, constraint.getType());
		}
	}

	@Nested
	@DisplayName("String representation")
	class ToStringTest {

		@Test
		@DisplayName("should produce expected format for datetime variant")
		void shouldToStringForDatetimeVariant() {
			final PriceValidIn constraint = priceValidIn(
				OffsetDateTime.of(2021, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
			);

			assertEquals("priceValidIn(2021-01-01T00:00:00Z)", constraint.toString());
		}

		@Test
		@DisplayName("should produce expected format for 'now' variant")
		void shouldToStringForNowVariant() {
			final PriceValidIn constraint = priceValidInNow();

			assertEquals("priceValidInNow()", constraint.toString());
		}
	}

	@Nested
	@DisplayName("Equality and hashCode")
	class EqualityTest {

		@Test
		@DisplayName("should conform to equals contract for datetime variants")
		void shouldConformToEqualsForDatetimeVariants() {
			final OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

			assertNotSame(priceValidIn(now), priceValidIn(now));
			assertEquals(priceValidIn(now), priceValidIn(now));
			assertNotEquals(priceValidIn(now), priceValidIn(now.plusHours(1)));
		}

		@Test
		@DisplayName("should conform to hashCode contract for datetime variants")
		void shouldConformToHashCodeForDatetimeVariants() {
			final OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

			assertEquals(priceValidIn(now).hashCode(), priceValidIn(now).hashCode());
			assertNotEquals(priceValidIn(now).hashCode(), priceValidIn(now.plusHours(1)).hashCode());
		}

		@Test
		@DisplayName("should have equal 'now' variants")
		void shouldHaveEqualNowVariants() {
			final PriceValidIn first = priceValidInNow();
			final PriceValidIn second = priceValidInNow();

			assertEquals(first, second);
			assertEquals(first.hashCode(), second.hashCode());
		}

		@Test
		@DisplayName("should not be equal between 'now' and datetime variants")
		void shouldNotBeEqualBetweenNowAndDatetimeVariants() {
			final PriceValidIn nowVariant = priceValidInNow();
			final PriceValidIn datetimeVariant = priceValidIn(OffsetDateTime.now(ZoneOffset.UTC));

			assertNotEquals(nowVariant, datetimeVariant);
		}
	}
}
