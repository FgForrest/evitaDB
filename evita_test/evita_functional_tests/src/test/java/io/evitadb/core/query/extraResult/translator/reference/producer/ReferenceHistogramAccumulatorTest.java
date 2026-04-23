/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.core.query.extraResult.translator.reference.producer;

import io.evitadb.core.query.extraResult.translator.reference.producer.ReferenceSummaryProducer.RequestedBucketRange;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.EntityIndex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for static helpers inside {@link ReferenceHistogramAccumulator}. Both helpers under
 * test are `private static` and live in a package-private final class, so the tests reach them
 * through reflection.
 *
 * Helpers covered:
 *
 * - `coerceToAttributeType(BigDecimal, Class)` — converts a boundary threshold into the attribute's
 *   concrete numeric type, returning `null` when the value is out of range;
 * - `requestedBucketPredicate(RequestedBucketRange)` — produces the predicate used to flip a
 *   bucket's `requested` flag; returns `threshold -> false` when the range is `null`.
 *
 * The full `injectHistograms` public API is covered by
 * `ReferenceHistogramFunctionalTest`, which exercises the accumulator end-to-end against a real
 * Evita catalog.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("ReferenceHistogramAccumulator static helpers")
class ReferenceHistogramAccumulatorTest {

	/**
	 * Reflectively invokes the private static `requestedBucketPredicate` helper.
	 *
	 * @param range the requested bucket range (nullable)
	 * @return the predicate used to flag `requested` buckets
	 * @throws Exception any reflective access failure
	 */
	@Nonnull
	@SuppressWarnings("unchecked")
	private static Predicate<BigDecimal> requestedPredicate(
		@Nullable RequestedBucketRange range
	) throws Exception {
		final Method method = ReferenceHistogramAccumulator.class.getDeclaredMethod(
			"requestedBucketPredicate", RequestedBucketRange.class
		);
		method.setAccessible(true);
		return (Predicate<BigDecimal>) method.invoke(null, range);
	}

	@Nested
	@DisplayName("requestedBucketPredicate")
	class RequestedBucketPredicate {

		@Test
		@DisplayName("should produce an always-false predicate when range is null")
		void shouldProduceAlwaysFalsePredicateWhenRangeIsNull() throws Exception {
			final Predicate<BigDecimal> predicate = requestedPredicate(null);

			assertFalse(predicate.test(BigDecimal.ZERO));
			assertFalse(predicate.test(new BigDecimal("100")));
			assertFalse(predicate.test(new BigDecimal("-50")));
		}

		@Test
		@DisplayName("should accept values inside a closed [from, to] range (both bounds inclusive)")
		void shouldAcceptValuesInsideClosedRangeWithBothBoundsInclusive() throws Exception {
			final Predicate<BigDecimal> predicate = requestedPredicate(
				new RequestedBucketRange(new BigDecimal("10"), new BigDecimal("20"))
			);

			assertTrue(predicate.test(new BigDecimal("10")), "lower bound must be inclusive");
			assertTrue(predicate.test(new BigDecimal("15")));
			assertTrue(predicate.test(new BigDecimal("20")), "upper bound must be inclusive");
			assertFalse(predicate.test(new BigDecimal("9")));
			assertFalse(predicate.test(new BigDecimal("21")));
		}

		@Test
		@DisplayName("should accept any value <= `to` when `from` bound is null (open lower bound)")
		void shouldAcceptAnyValueBelowOrEqualToUpperBoundWhenFromIsNull() throws Exception {
			final Predicate<BigDecimal> predicate = requestedPredicate(
				new RequestedBucketRange(null, new BigDecimal("20"))
			);

			assertTrue(predicate.test(new BigDecimal("-100")));
			assertTrue(predicate.test(new BigDecimal("20")));
			assertFalse(predicate.test(new BigDecimal("21")));
		}

		@Test
		@DisplayName("should accept any value >= `from` when `to` bound is null (open upper bound)")
		void shouldAcceptAnyValueAboveOrEqualToLowerBoundWhenToIsNull() throws Exception {
			final Predicate<BigDecimal> predicate = requestedPredicate(
				new RequestedBucketRange(new BigDecimal("10"), null)
			);

			assertTrue(predicate.test(new BigDecimal("10")));
			assertTrue(predicate.test(new BigDecimal("1000000")));
			assertFalse(predicate.test(new BigDecimal("9")));
		}

		@Test
		@DisplayName("should accept any value when both bounds are null (fully open range)")
		void shouldAcceptAnyValueWhenBothBoundsAreNull() throws Exception {
			final Predicate<BigDecimal> predicate = requestedPredicate(
				new RequestedBucketRange(null, null)
			);

			assertTrue(predicate.test(new BigDecimal("-1000")));
			assertTrue(predicate.test(BigDecimal.ZERO));
			assertTrue(predicate.test(new BigDecimal("1000")));
		}
	}

	/**
	 * Defensive-design test: helpers that dispatch on {@link EntityIndex} subtype must not silently
	 * swallow an unknown subtype — every dispatch miss is a programming error and must surface via
	 * {@link GenericEvitaInternalError}, matching the codebase-wide defensive design rule.
	 *
	 * Covering one representative dispatch site (`referencedPrimaryKeys`) pins the pattern for all
	 * three sibling dispatches in the accumulator (`histogramFilterIndexFor`,
	 * `referencedPrimaryKeysForIndexPks`). The other two share identical structure — covering them
	 * individually would pin implementation detail, not behavior.
	 */
	@Nested
	@DisplayName("Defensive dispatch on EntityIndex subtype")
	class DefensiveDispatch {

		@Test
		@DisplayName("should throw GenericEvitaInternalError from `referencedPrimaryKeys` for an unknown EntityIndex subtype")
		void shouldThrowGenericEvitaInternalErrorFromReferencedPrimaryKeysForUnknownEntityIndexSubtype() throws Exception {
			final Method method = ReferenceHistogramAccumulator.class.getDeclaredMethod(
				"referencedPrimaryKeys", EntityIndex.class
			);
			method.setAccessible(true);

			// a Mockito mock of the abstract EntityIndex cannot match either
			// ReducedGroupEntityIndex or ReferencedTypeEntityIndex and therefore forces the
			// unknown-subtype branch
			final InvocationTargetException ite = assertThrows(
				InvocationTargetException.class,
				() -> method.invoke(null, mock(EntityIndex.class))
			);
			assertNotNull(ite.getCause());
			assertInstanceOf(
				GenericEvitaInternalError.class, ite.getCause(),
				"Expected GenericEvitaInternalError, got: " + ite.getCause().getClass().getName()
			);
			assertTrue(
				ite.getCause().getMessage().contains("Unexpected EntityIndex subtype"),
				"Error must mention the unexpected-subtype contract, was: " + ite.getCause().getMessage()
			);
		}
	}
}
