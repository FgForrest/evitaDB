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

package io.evitadb.core.query.algebra.price.termination;

import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.core.cache.CacheSupervisor;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.index.price.model.PriceIndexKey;
import net.openhft.hashing.LongHashFunction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Currency;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.PRICE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link PriceEvaluationContext} as what it actually is — a **cache key**.
 *
 * The record collapses price-terminating formulas onto one memoized result: two formulas whose contexts are equal
 * share an answer. Its `validIn` component is therefore not a display value but the discriminator, and it has to
 * discriminate at exactly the granularity the validity range index answers at. Reduced too coarsely, two probes that
 * select different prices would share one memoized result and one of them would silently be served the other's
 * prices; there is no exception anywhere on that path.
 *
 * The record appears throughout the price-formula suites as a fixture, and its own contract has no coverage there,
 * which is what this class supplies.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("PriceEvaluationContext")
@Tag(ENGINE)
@Tag(PRICE)
class PriceEvaluationContextTest {

	/**
	 * The hash function the cache actually keys with — {@code CacheSupervisor.createHashFunction()} — so a hash
	 * assertion here is the one the cache would make.
	 */
	private static final LongHashFunction HASH_FUNCTION = CacheSupervisor.createHashFunction();

	/**
	 * The single target-index array every context in this class is built with. The record's generated {@code equals}
	 * compares its reference components with {@code Objects.equals}, which is array identity — so the array is shared
	 * deliberately, leaving `validIn` as the only thing that can make two contexts differ.
	 */
	private static final PriceIndexKey[] TARGET_INDEXES = {
		new PriceIndexKey("basic", Currency.getInstance("EUR"), PriceInnerRecordHandling.NONE)
	};

	/**
	 * Builds a context over {@link #TARGET_INDEXES} valid at the given moment.
	 *
	 * @param validIn the moment prices must be valid at, or `null` for no validity constraint
	 * @return the context
	 */
	@Nonnull
	private static PriceEvaluationContext contextAt(@Nullable OffsetDateTime validIn) {
		return new PriceEvaluationContext(validIn, TARGET_INDEXES);
	}

	@Nested
	@DisplayName("validIn as a cache-key discriminator")
	class DiscriminationTest {

		@Test
		@DisplayName("should tell two moments inside one second apart")
		void shouldTellTwoMomentsInsideOneSecondApart() {
			// the granularity the validity range index answers at is the millisecond, so two probes 500 ms apart can
			// select different prices. Reduced to a whole second they would collapse onto one memoized result
			final OffsetDateTime base = OffsetDateTime.parse("2026-05-20T12:19:26.100Z");
			final PriceEvaluationContext earlier = contextAt(base);
			final PriceEvaluationContext later = contextAt(base.plusNanos(500_000_000L));

			assertEquals(
				earlier.validIn() + 500L, later.validIn(),
				"the two keys must differ by exactly 500 milliseconds"
			);
			assertNotEquals(earlier, later, "two moments half a second apart must not share a memoized result");
			assertNotEquals(
				earlier.computeHash(HASH_FUNCTION), later.computeHash(HASH_FUNCTION),
				"and the cache hash must separate them too"
			);
		}

		@Test
		@DisplayName("should collapse two moments inside one millisecond")
		void shouldCollapseTwoMomentsInsideOneMillisecond() {
			// the other half of the contract: below the millisecond nothing can select differently, because every
			// temporal value entering the engine is truncated there - so collapsing is correct and is what makes the
			// memoization worth anything
			final OffsetDateTime base = OffsetDateTime.parse("2026-05-20T12:19:26.100Z");
			final PriceEvaluationContext plain = contextAt(base);
			final PriceEvaluationContext aNanosecondLater = contextAt(base.plusNanos(1L));

			assertEquals(plain.validIn(), aNanosecondLater.validIn());
			assertEquals(plain, aNanosecondLater, "two moments inside one millisecond are one cache key");
			assertEquals(plain.computeHash(HASH_FUNCTION), aNanosecondLater.computeHash(HASH_FUNCTION));
		}

		@Test
		@DisplayName("should reduce a moment exactly as the validity range index does")
		void shouldReduceAMomentExactlyAsTheValidityIndexDoes() {
			// the key must be the very long the range index is probed with, not merely something monotone in it
			final OffsetDateTime moment = OffsetDateTime.parse("2026-05-20T12:19:26.123Z");
			assertEquals(
				DateTimeRange.toComparableLong(moment).longValue(), contextAt(moment).validIn(),
				"the cache key and the range-index probe must be the same reduction"
			);
		}

		@Test
		@DisplayName("should treat an absent validity constraint as a value no moment can reach")
		void shouldTreatAnAbsentValidityConstraintAsUnreachable() {
			final PriceEvaluationContext unconstrained = contextAt(null);

			assertEquals(
				Long.MIN_VALUE, unconstrained.validIn(), "no validity constraint is the lowest possible key"
			);
			// a real moment saturates one step short of it, so nothing can collide with the sentinel
			assertNotEquals(
				Long.MIN_VALUE, DateTimeRange.toComparableLong(OffsetDateTime.MIN).longValue(),
				"even the lowest expressible moment must stay clear of the no-constraint sentinel"
			);
			assertNotEquals(
				unconstrained, contextAt(OffsetDateTime.MIN),
				"a query for the earliest expressible moment is not a query without a validity constraint"
			);
		}
	}

	@Nested
	@DisplayName("Rendering")
	class RenderingTest {

		@Test
		@DisplayName("should render no validIn clause when there is no validity constraint")
		void shouldRenderNoValidInClauseWithoutAValidityConstraint() {
			final String rendered = contextAt(null).toString();

			assertFalse(rendered.contains("validIn"), "an absent constraint must not be rendered at all: " + rendered);
			assertTrue(rendered.contains("basic"), "the target price indexes must still be rendered: " + rendered);
		}

		@Test
		@DisplayName("should name the right instant for a pre-epoch validIn")
		void shouldNameTheRightInstantForAPreEpochValidIn() {
			// the only place the key's decoding back into a moment is observable. A pre-epoch key has a negative
			// millisecond count, and splitting it into (second, nanosecond) with a truncating division yields a
			// NEGATIVE nanosecond-of-second, which is not a legal argument - so the failure is loud here and would be
			// a wrong instant in a log line if it were ever made silent
			final OffsetDateTime halfASecondBeforeEpoch = Instant.ofEpochMilli(-500L).atOffset(ZoneOffset.UTC);
			final PriceEvaluationContext context = contextAt(halfASecondBeforeEpoch);
			assertEquals(-500L, context.validIn(), "the fixture must carry a negative key");

			assertTrue(
				context.toString().endsWith(" validIn: " + LocalDateTime.of(1969, 12, 31, 23, 59, 59, 500_000_000)),
				"a pre-epoch key must name the moment it was built from: " + context
			);
		}

		@Test
		@DisplayName("should render a post-epoch validIn at UTC, to the millisecond")
		void shouldRenderAPostEpochValidInAtUtc() {
			final OffsetDateTime moment = OffsetDateTime.parse("2026-05-20T12:19:26.123Z");
			final PriceEvaluationContext context = contextAt(moment.withOffsetSameInstant(ZoneOffset.ofHours(2)));

			assertTrue(
				context.toString().endsWith(" validIn: " + LocalDateTime.of(2026, 5, 20, 12, 19, 26, 123_000_000)),
				"the moment is rendered at UTC whatever offset it arrived at: " + context
			);
		}
	}
}
