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

import static io.evitadb.api.query.QueryConstraints.priceHistogram;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link PriceHistogram} verifying construction, applicability, getters,
 * clone operations, visitor acceptance, default arguments, and equality contract.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("PriceHistogram constraint")
class PriceHistogramTest {

	@Nested
	@DisplayName("Construction and factory methods")
	class ConstructionTest {

		@Test
		@DisplayName("should create with bucket count (defaults to STANDARD)")
		void shouldCreateWithBucketCount() {
			final PriceHistogram histogram = priceHistogram(20);

			assertEquals(20, histogram.getRequestedBucketCount());
			assertEquals(HistogramBehavior.STANDARD, histogram.getBehavior());
		}

		@Test
		@DisplayName("should create with explicit behavior")
		void shouldCreateWithExplicitBehavior() {
			final PriceHistogram histogram = priceHistogram(20, HistogramBehavior.OPTIMIZED);

			assertEquals(20, histogram.getRequestedBucketCount());
			assertEquals(HistogramBehavior.OPTIMIZED, histogram.getBehavior());
		}

		@Test
		@DisplayName("should create with equalized behavior variants")
		void shouldCreateWithEqualizedBehavior() {
			final PriceHistogram equalized = priceHistogram(20, HistogramBehavior.EQUALIZED);

			assertEquals(HistogramBehavior.EQUALIZED, equalized.getBehavior());

			final PriceHistogram equalizedOptimized = priceHistogram(20, HistogramBehavior.EQUALIZED_OPTIMIZED);

			assertEquals(HistogramBehavior.EQUALIZED_OPTIMIZED, equalizedOptimized.getBehavior());
		}
	}

	@Nested
	@DisplayName("Applicability")
	class ApplicabilityTest {

		@Test
		@DisplayName("should always be applicable")
		void shouldAlwaysBeApplicable() {
			assertTrue(priceHistogram(20).isApplicable());
			assertTrue(priceHistogram(20, HistogramBehavior.OPTIMIZED).isApplicable());
			assertTrue(priceHistogram(20, HistogramBehavior.EQUALIZED).isApplicable());
			assertTrue(priceHistogram(20, HistogramBehavior.EQUALIZED_OPTIMIZED).isApplicable());
		}
	}

	@Nested
	@DisplayName("Default arguments")
	class DefaultArgumentsTest {

		@Test
		@DisplayName("should exclude STANDARD as implicit default argument")
		void shouldExcludeStandardAsDefault() {
			final PriceHistogram histogram = priceHistogram(20);

			final Serializable[] argsExcludingDefaults = histogram.getArgumentsExcludingDefaults();

			// Only bucket count should remain, STANDARD is excluded
			assertEquals(1, argsExcludingDefaults.length);
			assertEquals(20, argsExcludingDefaults[0]);
		}

		@Test
		@DisplayName("should keep OPTIMIZED as non-default argument")
		void shouldKeepOptimizedAsNonDefault() {
			final PriceHistogram histogram = priceHistogram(20, HistogramBehavior.OPTIMIZED);

			final Serializable[] argsExcludingDefaults = histogram.getArgumentsExcludingDefaults();

			assertEquals(2, argsExcludingDefaults.length);
		}

		@Test
		@DisplayName("should recognize STANDARD as implicit argument")
		void shouldRecognizeStandardAsImplicit() {
			final PriceHistogram histogram = priceHistogram(20);

			assertTrue(histogram.isArgumentImplicit(HistogramBehavior.STANDARD));
			assertFalse(histogram.isArgumentImplicit(HistogramBehavior.OPTIMIZED));
		}
	}

	@Nested
	@DisplayName("Type and visitor")
	class TypeAndVisitorTest {

		@Test
		@DisplayName("should return RequireConstraint class as type")
		void shouldReturnRequireConstraintClassAsType() {
			assertEquals(RequireConstraint.class, priceHistogram(20).getType());
		}

		@Test
		@DisplayName("should accept visitor")
		void shouldAcceptVisitor() {
			final PriceHistogram histogram = priceHistogram(20);
			final AtomicReference<Constraint<?>> visited = new AtomicReference<>();
			histogram.accept(new ConstraintVisitor() {
				@Override
				public void visit(@Nonnull Constraint<?> constraint) {
					visited.set(constraint);
				}
			});

			assertSame(histogram, visited.get());
		}
	}

	@Nested
	@DisplayName("Clone operations")
	class CloneTest {

		@Test
		@DisplayName("should clone with new arguments")
		void shouldCloneWithNewArguments() {
			final PriceHistogram original = priceHistogram(20);
			final RequireConstraint cloned = original.cloneWithArguments(
				new Serializable[]{30, HistogramBehavior.OPTIMIZED}
			);

			assertNotSame(original, cloned);
			assertInstanceOf(PriceHistogram.class, cloned);
			final PriceHistogram clonedHistogram = (PriceHistogram) cloned;
			assertEquals(30, clonedHistogram.getRequestedBucketCount());
			assertEquals(HistogramBehavior.OPTIMIZED, clonedHistogram.getBehavior());
		}
	}

	@Nested
	@DisplayName("String representation")
	class ToStringTest {

		@Test
		@DisplayName("should produce expected toString with default behavior")
		void shouldProduceToStringWithDefaultBehavior() {
			assertEquals("priceHistogram(20)", priceHistogram(20).toString());
		}

		@Test
		@DisplayName("should produce expected toString with OPTIMIZED behavior")
		void shouldProduceToStringWithOptimizedBehavior() {
			assertEquals(
				"priceHistogram(20,OPTIMIZED)",
				priceHistogram(20, HistogramBehavior.OPTIMIZED).toString()
			);
		}
	}

	@Nested
	@DisplayName("Equality and hashCode")
	class EqualityTest {

		@Test
		@DisplayName("should conform to equals and hashCode contract")
		void shouldConformToEqualsAndHashContract() {
			assertNotSame(priceHistogram(20), priceHistogram(20));
			assertEquals(priceHistogram(20), priceHistogram(20));
			assertEquals(
				priceHistogram(20, HistogramBehavior.OPTIMIZED),
				priceHistogram(20, HistogramBehavior.OPTIMIZED)
			);
			assertNotEquals(priceHistogram(20), priceHistogram(25));
			assertNotEquals(
				priceHistogram(20, HistogramBehavior.OPTIMIZED),
				priceHistogram(20, HistogramBehavior.STANDARD)
			);
			assertEquals(priceHistogram(20).hashCode(), priceHistogram(20).hashCode());
			assertNotEquals(priceHistogram(20).hashCode(), priceHistogram(22).hashCode());
			assertNotEquals(
				priceHistogram(20, HistogramBehavior.OPTIMIZED).hashCode(),
				priceHistogram(20, HistogramBehavior.STANDARD).hashCode()
			);
		}
	}
}
