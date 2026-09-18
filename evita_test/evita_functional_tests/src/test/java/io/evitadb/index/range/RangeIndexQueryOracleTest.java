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

package io.evitadb.index.range;

import io.evitadb.core.query.algebra.Formula;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Predicate;

import static io.evitadb.test.TestTags.DATA_TYPE;
import static io.evitadb.test.TestTags.INDEXING;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * Pins the SEMANTICS of every {@link RangeIndex} query against a brute-force scan of the intervals that were inserted,
 * over randomly generated indexes.
 *
 * The oracle deliberately shares NO code with the index: it keeps the `(recordId, from, to)` triples the test handed
 * over and answers each query by testing the interval predicate directly. The index instead answers by a signed count
 * over a prefix of its threshold tree, never materializing an interval at all — so the two arrive at the same answer
 * by genuinely different routes, and a defect would have to appear identically in both to survive.
 *
 * This exists because the four queries were collapsed onto a single two-bound prefix count. That rewrite rests on an
 * arithmetic identity (a range whose END is already behind the queried point necessarily has its START behind it too,
 * so the subtraction cancels exactly the expired ranges), and an identity is precisely the kind of claim that a
 * hand-picked example can confirm while being false in general. The generator therefore aims at the cases the identity
 * could break on: several ranges per record, probes landing exactly ON a threshold as well as between thresholds,
 * ranges that touch the probe with only one endpoint, and the {@link Long#MIN_VALUE}/{@link Long#MAX_VALUE} borders.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(INDEXING)
@Tag(DATA_TYPE)
@DisplayName("RangeIndex: queries agree with a brute-force interval scan")
class RangeIndexQueryOracleTest {
	/**
	 * Number of independently generated indexes the assertions run over.
	 */
	private static final int SEEDS = 150;
	/**
	 * Highest record id the generator hands out — small enough that records routinely share thresholds.
	 */
	private static final int RECORD_COUNT = 12;
	/**
	 * Thresholds are drawn from this many distinct values so that probes land exactly on a threshold often.
	 */
	private static final int THRESHOLD_SPACE = 24;

	/**
	 * One inserted interval, exactly as it was handed to {@link RangeIndex#addRecord(long, long, int)}.
	 */
	private record Span(int recordId, long from, long to) {
	}

	@Test
	@DisplayName("getRecordsEnvelopingInclusive returns records with a range covering the point")
	void shouldMatchOracleForEnvelopingInclusive() {
		forEachGeneratedIndex((spans, index, probe) -> assertSameRecords(
			spans, index.getRecordsEnvelopingInclusive(probe),
			span -> span.from() <= probe && probe <= span.to(),
			"getRecordsEnvelopingInclusive(" + probe + ")"
		));
	}

	@Test
	@DisplayName("getRecordsTo returns records whose range started by the point and has not ended")
	void shouldMatchOracleForRecordsTo() {
		forEachGeneratedIndex((spans, index, probe) -> assertSameRecords(
			spans, index.getRecordsTo(probe),
			span -> span.from() <= probe && probe < span.to(),
			"getRecordsTo(" + probe + ")"
		));
	}

	@Test
	@DisplayName("getRecordsFrom returns records whose range started before the point and has not ended")
	void shouldMatchOracleForRecordsFrom() {
		forEachGeneratedIndex((spans, index, probe) -> assertSameRecords(
			spans, index.getRecordsFrom(probe),
			span -> span.from() < probe && probe <= span.to(),
			"getRecordsFrom(" + probe + ")"
		));
	}

	@Test
	@DisplayName("getRecordsWithRangesOverlapping returns records with a range meeting the window")
	void shouldMatchOracleForOverlapping() {
		for (int seed = 0; seed < SEEDS; seed++) {
			final Random random = new Random(seed);
			final List<Span> spans = generateSpans(random);
			final RangeIndex index = indexOf(spans);
			final long[] probes = probesFor(spans);
			for (final long from : probes) {
				// pair every probe with a randomly drawn upper bound at or above it, so both the degenerate
				// `from == to` window (which must behave exactly like the enveloping query) and wide windows are hit
				final long to = probes[random.nextInt(probes.length)];
				final long lower = Math.min(from, to);
				final long upper = Math.max(from, to);
				assertSameRecords(
					spans, index.getRecordsWithRangesOverlapping(lower, upper),
					span -> span.from() <= upper && lower <= span.to(),
					"getRecordsWithRangesOverlapping(" + lower + ", " + upper + ")"
				);
			}
		}
	}

	/**
	 * Callback shape for an assertion made against one generated index at one probe point.
	 */
	@FunctionalInterface
	private interface IndexAssertion {
		void assertAt(@Nonnull List<Span> spans, @Nonnull RangeIndex index, long probe);
	}

	/**
	 * Generates {@link #SEEDS} independent indexes and runs the passed assertion at every probe point of each.
	 */
	private static void forEachGeneratedIndex(@Nonnull IndexAssertion assertion) {
		for (int seed = 0; seed < SEEDS; seed++) {
			final List<Span> spans = generateSpans(new Random(seed));
			final RangeIndex index = indexOf(spans);
			for (final long probe : probesFor(spans)) {
				assertion.assertAt(spans, index, probe);
			}
		}
	}

	/**
	 * Generates the intervals of one index. Each record gets one to three ranges that neither overlap nor touch —
	 * {@link RangeIndex} requires a single record's ranges not to share a border, because removal would then take the
	 * shared point away from both.
	 */
	@Nonnull
	private static List<Span> generateSpans(@Nonnull Random random) {
		final List<Span> spans = new ArrayList<>();
		for (int recordId = 1; recordId <= RECORD_COUNT; recordId++) {
			long cursor = random.nextInt(4);
			final int rangeCount = 1 + random.nextInt(3);
			for (int range = 0; range < rangeCount && cursor < THRESHOLD_SPACE; range++) {
				final long from = cursor + random.nextInt(3);
				final long to = from + random.nextInt(5);
				if (to >= THRESHOLD_SPACE) {
					break;
				}
				spans.add(new Span(recordId, from, to));
				// leave a gap of at least two so the next range of THIS record cannot share a border with it
				cursor = to + 2;
			}
		}
		// a couple of records anchored at the index borders - the sentinels are the easiest thing for a bound to get
		// wrong, and the production `validity` shape is full of open-ended ranges
		spans.add(new Span(RECORD_COUNT + 1, Long.MIN_VALUE, THRESHOLD_SPACE / 2));
		spans.add(new Span(RECORD_COUNT + 2, THRESHOLD_SPACE / 2, Long.MAX_VALUE));
		spans.add(new Span(RECORD_COUNT + 3, Long.MIN_VALUE, Long.MAX_VALUE));
		return spans;
	}

	/**
	 * Builds the index under test from the generated intervals.
	 */
	@Nonnull
	private static RangeIndex indexOf(@Nonnull List<Span> spans) {
		final RangeIndex index = new RangeIndex();
		for (final Span span : spans) {
			index.addRecord(span.from(), span.to(), span.recordId());
		}
		return index;
	}

	/**
	 * Returns the points each query is asked about: every threshold present in the index, the value immediately
	 * before and after it, and both borders. Landing exactly on a threshold is the case the collapsed boundary
	 * handling has to get right, so it must not be left to chance.
	 */
	@Nonnull
	private static long[] probesFor(@Nonnull List<Span> spans) {
		final List<Long> probes = new ArrayList<>();
		probes.add(Long.MIN_VALUE);
		probes.add(Long.MAX_VALUE);
		for (final Span span : spans) {
			for (final long threshold : new long[]{span.from(), span.to()}) {
				probes.add(threshold);
				if (threshold > Long.MIN_VALUE) {
					probes.add(threshold - 1);
				}
				if (threshold < Long.MAX_VALUE) {
					probes.add(threshold + 1);
				}
			}
		}
		return probes.stream().mapToLong(Long::longValue).distinct().sorted().toArray();
	}

	/**
	 * Asserts the formula computes exactly the records the interval predicate selects over the raw spans.
	 */
	private static void assertSameRecords(
		@Nonnull List<Span> spans,
		@Nonnull Formula actual,
		@Nonnull Predicate<Span> oracle,
		@Nonnull String query
	) {
		final int[] expected = spans.stream()
			.filter(oracle)
			.mapToInt(Span::recordId)
			.distinct()
			.sorted()
			.toArray();
		assertArrayEquals(
			expected, actual.compute().getArray(),
			() -> query + " disagreed with the brute-force scan over " + spans.size() + " spans"
		);
	}

}
