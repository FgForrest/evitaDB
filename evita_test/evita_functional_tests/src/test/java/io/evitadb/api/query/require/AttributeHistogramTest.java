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

import static io.evitadb.api.query.QueryConstraints.attributeHistogram;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link AttributeHistogram} verifying construction, applicability, getters,
 * clone operations, visitor acceptance, default arguments, and equality contract.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("AttributeHistogram constraint")
class AttributeHistogramTest {

	@Nested
	@DisplayName("Construction and factory methods")
	class ConstructionTest {

		@Test
		@DisplayName("should create with bucket count and attribute names")
		void shouldCreateWithBucketCountAndAttributeNames() {
			final AttributeHistogram histogram = attributeHistogram(20, "a", "b");

			assertEquals(20, histogram.getRequestedBucketCount());
			assertEquals(HistogramBehavior.STANDARD, histogram.getBehavior());
			assertArrayEquals(new String[]{"a", "b"}, histogram.getAttributeNames());
		}

		@Test
		@DisplayName("should create with explicit behavior")
		void shouldCreateWithExplicitBehavior() {
			final AttributeHistogram histogram = attributeHistogram(20, HistogramBehavior.OPTIMIZED, "a", "b");

			assertEquals(20, histogram.getRequestedBucketCount());
			assertEquals(HistogramBehavior.OPTIMIZED, histogram.getBehavior());
			assertArrayEquals(new String[]{"a", "b"}, histogram.getAttributeNames());
		}

		@Test
		@DisplayName("should create with equalized behavior variants")
		void shouldCreateWithEqualizedBehavior() {
			final AttributeHistogram equalized = attributeHistogram(20, HistogramBehavior.EQUALIZED, "a", "b");

			assertEquals(HistogramBehavior.EQUALIZED, equalized.getBehavior());

			final AttributeHistogram equalizedOptimized = attributeHistogram(
				20, HistogramBehavior.EQUALIZED_OPTIMIZED, "a"
			);

			assertEquals(HistogramBehavior.EQUALIZED_OPTIMIZED, equalizedOptimized.getBehavior());
		}

		@Test
		@DisplayName("should return null from factory when no attribute names provided")
		void shouldReturnNullFromFactoryWhenNoAttributeNames() {
			assertNull(attributeHistogram(20));
		}
	}

	@Nested
	@DisplayName("Applicability")
	class ApplicabilityTest {

		@Test
		@DisplayName("should be applicable when attribute names are provided")
		void shouldBeApplicableWithAttributeNames() {
			assertTrue(attributeHistogram(20, "a").isApplicable());
			assertTrue(attributeHistogram(20, "a", "c").isApplicable());
			assertTrue(attributeHistogram(20, HistogramBehavior.OPTIMIZED, "a", "c").isApplicable());
			assertTrue(attributeHistogram(20, HistogramBehavior.EQUALIZED, "a", "c").isApplicable());
			assertTrue(attributeHistogram(20, HistogramBehavior.EQUALIZED_OPTIMIZED, "a", "c").isApplicable());
		}

		@Test
		@DisplayName("should not be applicable when no attribute names")
		void shouldNotBeApplicableWithoutAttributeNames() {
			assertFalse(new AttributeHistogram(20).isApplicable());
			assertFalse(new AttributeHistogram(20, HistogramBehavior.OPTIMIZED).isApplicable());
		}
	}

	@Nested
	@DisplayName("Default arguments")
	class DefaultArgumentsTest {

		@Test
		@DisplayName("should exclude STANDARD as implicit default argument")
		void shouldExcludeStandardAsDefault() {
			final AttributeHistogram histogram = attributeHistogram(20, "a", "b");

			final Serializable[] argsExcludingDefaults = histogram.getArgumentsExcludingDefaults();

			// Should contain: 20, "a", "b" (STANDARD excluded)
			assertEquals(3, argsExcludingDefaults.length);
			assertEquals(20, argsExcludingDefaults[0]);
			assertEquals("a", argsExcludingDefaults[1]);
		}

		@Test
		@DisplayName("should keep OPTIMIZED as non-default argument")
		void shouldKeepOptimizedAsNonDefault() {
			final AttributeHistogram histogram = attributeHistogram(20, HistogramBehavior.OPTIMIZED, "a");

			final Serializable[] argsExcludingDefaults = histogram.getArgumentsExcludingDefaults();

			// Should contain: 20, OPTIMIZED, "a"
			assertEquals(3, argsExcludingDefaults.length);
		}

		@Test
		@DisplayName("should recognize STANDARD as implicit argument")
		void shouldRecognizeStandardAsImplicit() {
			final AttributeHistogram histogram = attributeHistogram(20, "a");

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
			assertEquals(RequireConstraint.class, attributeHistogram(20, "a").getType());
		}

		@Test
		@DisplayName("should accept visitor")
		void shouldAcceptVisitor() {
			final AttributeHistogram histogram = attributeHistogram(20, "a");
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
			final AttributeHistogram original = attributeHistogram(20, "a", "b");
			final RequireConstraint cloned = original.cloneWithArguments(
				new Serializable[]{30, HistogramBehavior.OPTIMIZED, "x", "y"}
			);

			assertNotSame(original, cloned);
			assertInstanceOf(AttributeHistogram.class, cloned);
			final AttributeHistogram clonedHistogram = (AttributeHistogram) cloned;
			assertEquals(30, clonedHistogram.getRequestedBucketCount());
			assertEquals(HistogramBehavior.OPTIMIZED, clonedHistogram.getBehavior());
			assertArrayEquals(new String[]{"x", "y"}, clonedHistogram.getAttributeNames());
		}
	}

	@Nested
	@DisplayName("String representation")
	class ToStringTest {

		@Test
		@DisplayName("should produce expected toString with default behavior")
		void shouldProduceToStringWithDefaultBehavior() {
			assertEquals("attributeHistogram(20,'a','b')", attributeHistogram(20, "a", "b").toString());
		}

		@Test
		@DisplayName("should produce expected toString with OPTIMIZED behavior")
		void shouldProduceToStringWithOptimizedBehavior() {
			assertEquals(
				"attributeHistogram(20,OPTIMIZED,'a','b')",
				attributeHistogram(20, HistogramBehavior.OPTIMIZED, "a", "b").toString()
			);
		}
	}

	@Nested
	@DisplayName("Equality and hashCode")
	class EqualityTest {

		@Test
		@DisplayName("should conform to equals and hashCode contract")
		void shouldConformToEqualsAndHashContract() {
			assertNotSame(attributeHistogram(20, "a", "b"), attributeHistogram(20, "a", "b"));
			assertEquals(attributeHistogram(20, "a", "b"), attributeHistogram(20, "a", "b"));
			assertEquals(
				attributeHistogram(20, HistogramBehavior.OPTIMIZED, "a", "b"),
				attributeHistogram(20, HistogramBehavior.OPTIMIZED, "a", "b")
			);
			assertNotEquals(attributeHistogram(20, "a", "b"), attributeHistogram(20, "a", "e"));
			assertNotEquals(attributeHistogram(20, "a", "b"), attributeHistogram(21, "a", "b"));
			assertNotEquals(attributeHistogram(20, "a", "b"), attributeHistogram(20, "a"));
			assertNotEquals(
				attributeHistogram(20, HistogramBehavior.STANDARD, "a", "b"),
				attributeHistogram(20, HistogramBehavior.OPTIMIZED, "a", "b")
			);
			assertEquals(
				attributeHistogram(20, "a", "b").hashCode(),
				attributeHistogram(20, "a", "b").hashCode()
			);
			assertNotEquals(
				attributeHistogram(20, "a", "b").hashCode(),
				attributeHistogram(20, "a", "e").hashCode()
			);
			assertNotEquals(
				attributeHistogram(20, "a", "b").hashCode(),
				attributeHistogram(21, "a", "b").hashCode()
			);
		}
	}
}
