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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Predicate;

import static io.evitadb.test.TestTags.DATA_TYPE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
 * could break on: several ranges per record — disjoint and gap-separated, overlapping, and nested (see
 * {@link #generateSpans}), and in {@link WithSharedBorders} ranges of one record that share a border or repeat
 * outright — probes landing exactly ON a threshold as well as between thresholds, ranges that touch the probe with
 * only one endpoint, and the {@link Long#MIN_VALUE}/{@link Long#MAX_VALUE} borders.
 *
 * The overlapping and nested arrangements are not decoration. They are the shapes on which a signed COUNT and a
 * parity fold disagree: a record holding `(2,15)` and `(5,20)` has two starts and no ends at `t=10`, so counting
 * keeps it while an XOR of the same operands cancels it away. A suite that generated only disjoint ranges would pass
 * against an implementation that had quietly degraded into parity.
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

	@Test
	@DisplayName("getRecordsFrom at a point equals getRecordsTo at the point before it")
	void shouldAgreeBetweenTheSuffixAndPrefixFormsOfTheSameQuery() {
		// `getRecordsFrom` is the one query still answered over a SUFFIX of the threshold tree, and the javadoc on
		// it states the identity `E(>=t) - S(>=t) == S(<t) - E(<t)` as the reason that is safe. The identity is what
		// licenses leaving one query on a different walk from the other three, so it is asserted rather than trusted.
		for (int seed = 0; seed < SEEDS; seed++) {
			final List<Span> spans = generateSpans(new Random(seed));
			final RangeIndex index = indexOf(spans);
			for (final long probe : probesFor(spans)) {
				if (probe == Long.MIN_VALUE) {
					continue;
				}
				assertArrayEquals(
					index.getRecordsTo(probe - 1).compute().getArray(),
					index.getRecordsFrom(probe).compute().getArray(),
					() -> "getRecordsFrom(" + probe + ") disagreed with getRecordsTo(" + (probe - 1) + ")"
				);
			}
		}
	}

	@Test
	@DisplayName("getRecordsEnvelopingInclusive is constant across the open interval between two thresholds")
	void shouldReturnTheSameRecordsForEveryPointBetweenTwoAdjacentThresholds() {
		// `getRecordsValidNowFormula` memoizes the enveloping result for exactly this interval, so the cache is only
		// correct if the answer really is constant over it. That is a claim about the collapsed boundary handling,
		// not about caching: a threshold that stopped contributing where it should would show up here as an answer
		// that changes strictly between two thresholds.
		for (int seed = 0; seed < SEEDS; seed++) {
			final List<Span> spans = generateSpans(new Random(seed));
			final RangeIndex index = indexOf(spans);
			final long[] thresholds = thresholdsOf(spans);
			for (int i = 1; i < thresholds.length; i++) {
				final long lower = thresholds[i - 1];
				final long upper = thresholds[i];
				// written as an addition rather than a subtraction because `upper - lower` overflows for the border
				// sentinels; `lower + 1` cannot, since a sorted distinct pair always has lower < upper <= MAX
				if (lower + 1 >= upper) {
					// no point lies strictly between them
					continue;
				}
				// midpoint computed so it cannot overflow even for the MIN_VALUE / MAX_VALUE pair
				final long middle = lower + ((upper - lower) >>> 1);
				final int[] atLowerEnd = index.getRecordsEnvelopingInclusive(lower + 1).compute().getArray();
				for (final long point : new long[]{middle, upper - 1}) {
					assertArrayEquals(
						atLowerEnd, index.getRecordsEnvelopingInclusive(point).compute().getArray(),
						() -> "getRecordsEnvelopingInclusive changed between thresholds " + lower + " and " + upper +
							" - at " + (lower + 1) + " and at " + point
					);
				}
			}
		}
	}

	@Nested
	@DisplayName("Indexes a subset of whose records was removed again")
	class WithRemovals {
		/**
		 * Fewer seeds than the base suite - this arm builds and then tears down each index, and the breadth is
		 * already carried by the method-level assertions above.
		 */
		private static final int REMOVAL_SEEDS = 25;

		@Test
		@DisplayName("Every query still agrees with the brute-force scan over the surviving spans")
		void shouldMatchOracleAfterRemovingASubsetOfTheSpans() {
			// `removeRecord` produces shapes `addRecord` never does: a threshold point is swept away only when its
			// starts AND its ends are both empty, so a removal routinely leaves a one-sided point behind. The probes
			// are taken from the ORIGINAL spans on purpose, so the queries are still asked about points that the
			// removals may have emptied or swept.
			for (int seed = 0; seed < REMOVAL_SEEDS; seed++) {
				final Random random = new Random(seed * 104_729L + 3L);
				final List<Span> spans = generateSpans(random);
				final RangeIndex index = indexOf(spans);
				final long[] probes = probesFor(spans);

				final List<Span> surviving = new ArrayList<>(spans);
				for (int i = spans.size() - 1; i >= 0; i--) {
					if (random.nextInt(3) == 0) {
						final Span removed = spans.get(i);
						index.removeRecord(removed.from(), removed.to(), removed.recordId());
						surviving.remove(i);
					}
				}

				assertAllQueries(surviving, index, probes);
			}
		}
	}

	@Nested
	@DisplayName("Indexes read through an open transaction")
	class UnderAnOpenTransaction {
		/**
		 * Fewer seeds than the base suite - every assertion here runs inside a transactional overlay, which is the
		 * expensive part, and the breadth is already carried by the method-level assertions above.
		 */
		private static final int TRANSACTION_SEEDS = 25;

		@Test
		@DisplayName("Every query agrees with the brute-force scan over the committed spans plus the pending ones")
		void shouldMatchOracleForSpansAddedInsideATransaction() {
			// `createPrefixCountFormula` walks the TRANSACTIONAL entry iterator, so the operand families it collects
			// come from the overlay rather than from the committed tree. Nothing else proves the prefix walk agrees
			// with a brute-force scan once an overlay is in play.
			for (int seed = 0; seed < TRANSACTION_SEEDS; seed++) {
				final Random random = new Random(seed * 7919L + 17L);
				final List<Span> committed = generateSpans(random);
				final RangeIndex index = indexOf(committed);

				final List<Span> pending = new ArrayList<>();
				for (int i = 0; i < 4; i++) {
					// record ids beyond the generated range; shared borders inside a transaction are covered by
					// WithSharedBorders
					final int recordId = RECORD_COUNT + 10 + i;
					final long from = random.nextInt(THRESHOLD_SPACE);
					final long to = from + random.nextInt(5);
					pending.add(new Span(recordId, from, to));
				}

				final List<Span> expected = new ArrayList<>(committed);
				expected.addAll(pending);
				final long[] probes = probesFor(expected);

				assertStateAfterRollback(
					index,
					original -> {
						for (final Span span : pending) {
							original.addRecord(span.from(), span.to(), span.recordId());
						}
						assertAllQueries(expected, original, probes);
					},
					(original, committedVersion) -> assertNull(committedVersion)
				);
			}
		}
	}

	/**
	 * Ranges of one record that share a border — the shape a contribution-keyed index produces when several owners add
	 * their ranges to one record id. The index keeps every occurrence of a border, so each query must still agree with
	 * the brute-force scan, also after removals and across a transaction.
	 */
	@Nested
	@DisplayName("Indexes whose records hold ranges sharing a border")
	class WithSharedBorders {

		@Test
		@DisplayName("Every query agrees with the brute-force scan")
		void shouldMatchOracleWhenRangesOfOneRecordShareBorders() {
			for (int seed = 0; seed < SEEDS; seed++) {
				final List<Span> spans = generateSpansSharingBorders(new Random(seed * 31L + 11L));
				assertAllQueries(spans, indexOf(spans), probesFor(spans));
			}
		}

		@Test
		@DisplayName("Every query agrees with the brute-force scan after a subset of the ranges is removed")
		void shouldMatchOracleAfterRemovingRangesSharingBorders() {
			for (int seed = 0; seed < SEEDS; seed++) {
				final Random random = new Random(seed * 6_151L + 5L);
				final List<Span> spans = generateSpansSharingBorders(random);
				final RangeIndex index = indexOf(spans);
				final long[] probes = probesFor(spans);

				// removal order is random too: the occurrence a removal takes is the highest one, whichever range
				// added it, and the answer must not depend on which of two equal borders goes first
				final List<Span> surviving = new ArrayList<>(spans);
				final int removals = random.nextInt(spans.size() + 1);
				for (int i = 0; i < removals; i++) {
					final Span removed = surviving.remove(random.nextInt(surviving.size()));
					index.removeRecord(removed.from(), removed.to(), removed.recordId());
				}

				assertAllQueries(surviving, index, probes);
			}
		}

		@Test
		@DisplayName("Every query agrees with the brute-force scan inside a transaction, after commit and rollback")
		void shouldMatchOracleForRangesSharingBordersChangedInsideATransaction() {
			for (int seed = 0; seed < 40; seed++) {
				final Random random = new Random(seed * 2_003L + 1L);
				final List<Span> committed = generateSpansSharingBorders(random);
				final List<Span> expected = new ArrayList<>(committed);
				final List<Span> added = new ArrayList<>();
				final List<Span> removed = new ArrayList<>();
				for (int i = 0; i < 6; i++) {
					if (random.nextBoolean() && !expected.isEmpty()) {
						removed.add(expected.remove(random.nextInt(expected.size())));
					} else {
						// existing record ids on purpose: the new range shares borders with the ones they hold
						final Span span = randomSharingSpan(random, 1 + random.nextInt(RECORD_COUNT));
						added.add(span);
						expected.add(span);
					}
				}
				final List<Span> all = new ArrayList<>(committed);
				all.addAll(added);
				final long[] probes = probesFor(all);

				assertStateAfterCommit(
					indexOf(committed),
					original -> {
						applyInOrder(original, added, removed, random.nextLong());
						assertAllQueries(expected, original, probes);
					},
					(original, committedVersion) -> {
						assertAllQueries(committed, original, probes);
						assertAllQueries(expected, committedVersion, probes);
					}
				);
				assertStateAfterRollback(
					indexOf(committed),
					original -> applyInOrder(original, added, removed, 0L),
					(original, committedVersion) -> {
						assertNull(committedVersion);
						assertAllQueries(committed, original, probes);
					}
				);
			}
		}

		/**
		 * Applies the pending additions and removals to the index, interleaved in an order derived from `seed`. A
		 * removal is only ever of a range that was committed, so any interleaving leaves the same multiset of ranges.
		 *
		 * @param index   the index to mutate
		 * @param added   the ranges to add
		 * @param removed the committed ranges to remove
		 * @param seed    decides the interleaving
		 */
		private static void applyInOrder(
			@Nonnull RangeIndex index, @Nonnull List<Span> added, @Nonnull List<Span> removed, long seed
		) {
			final Random order = new Random(seed);
			int addIndex = 0;
			int removeIndex = 0;
			while (addIndex < added.size() || removeIndex < removed.size()) {
				if (removeIndex == removed.size() || (addIndex < added.size() && order.nextBoolean())) {
					final Span span = added.get(addIndex++);
					index.addRecord(span.from(), span.to(), span.recordId());
				} else {
					final Span span = removed.get(removeIndex++);
					index.removeRecord(span.from(), span.to(), span.recordId());
				}
			}
		}
	}

	/**
	 * Generates the intervals of one index in which the ranges of one record routinely share a border: every record
	 * gets one to four ranges whose borders come from a handful of values and the two sentinels, so shared starts,
	 * shared ends, a range repeated outright, and one range ending where another starts all occur in every seed.
	 *
	 * @param random the source of randomness
	 * @return the generated intervals
	 */
	@Nonnull
	private static List<Span> generateSpansSharingBorders(@Nonnull Random random) {
		final List<Span> spans = new ArrayList<>();
		for (int recordId = 1; recordId <= RECORD_COUNT; recordId++) {
			final int rangeCount = 1 + random.nextInt(4);
			for (int i = 0; i < rangeCount; i++) {
				spans.add(randomSharingSpan(random, recordId));
			}
		}
		return spans;
	}

	/**
	 * Draws one range of `recordId` whose borders come from six values and the two sentinels, so it is likely to share
	 * a border with another range of the same record.
	 *
	 * @param random   the source of randomness
	 * @param recordId the record the range belongs to
	 * @return the range
	 */
	@Nonnull
	private static Span randomSharingSpan(@Nonnull Random random, int recordId) {
		final long first = sharingBorder(random);
		final long second = sharingBorder(random);
		return new Span(recordId, Math.min(first, second), Math.max(first, second));
	}

	/**
	 * Draws a border from a deliberately tiny space: six values two apart, or one of the two sentinels.
	 *
	 * @param random the source of randomness
	 * @return the border
	 */
	private static long sharingBorder(@Nonnull Random random) {
		final int pick = random.nextInt(8);
		if (pick == 0) {
			return Long.MIN_VALUE;
		} else if (pick == 1) {
			return Long.MAX_VALUE;
		} else {
			return 2L * pick;
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
	 * Generates the intervals of one index. Each record gets one to three disjoint ranges, or an overlapping or a
	 * nested pair, none of which share a border — the shapes with shared borders are generated by
	 * {@link #generateSpansSharingBorders}.
	 */
	@Nonnull
	private static List<Span> generateSpans(@Nonnull Random random) {
		final List<Span> spans = new ArrayList<>();
		for (int recordId = 1; recordId <= RECORD_COUNT; recordId++) {
			// half the records get the disjoint chain, the rest an overlapping or a nested pair - every arrangement
			// the index permits, so the oracle cannot pass against an implementation that only handles the easy one
			final int shape = random.nextInt(4);
			if (shape < 2) {
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
			} else {
				// four STRICTLY increasing borders, so whichever pairing is taken below the record never repeats a
				// border value - repeated borders are what generateSpansSharingBorders is for
				final long b0 = random.nextInt(6);
				final long b1 = b0 + 1 + random.nextInt(3);
				final long b2 = b1 + 1 + random.nextInt(3);
				final long b3 = b2 + 1 + random.nextInt(3);
				if (shape == 2) {
					// overlapping: b0 .. b2 and b1 .. b3 share the stretch b1..b2 without sharing an endpoint
					spans.add(new Span(recordId, b0, b2));
					spans.add(new Span(recordId, b1, b3));
				} else {
					// nested: b1 .. b2 sits strictly inside b0 .. b3
					spans.add(new Span(recordId, b0, b3));
					spans.add(new Span(recordId, b1, b2));
				}
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
	 * Runs all four range queries at every probe point and checks each against the brute-force interval scan.
	 *
	 * @param spans  the intervals the index is expected to hold
	 * @param index  the index under test
	 * @param probes the points to ask about
	 */
	private static void assertAllQueries(
		@Nonnull List<Span> spans, @Nonnull RangeIndex index, @Nonnull long[] probes
	) {
		// a fixed seed, so a failure reproduces exactly - the second bound of the overlap window is the only thing
		// drawn here and it only has to vary across probes, not across runs
		final Random random = new Random(0);
		for (final long probe : probes) {
			assertSameRecords(
				spans, index.getRecordsEnvelopingInclusive(probe),
				span -> span.from() <= probe && probe <= span.to(),
				"getRecordsEnvelopingInclusive(" + probe + ")"
			);
			assertSameRecords(
				spans, index.getRecordsTo(probe),
				span -> span.from() <= probe && probe < span.to(),
				"getRecordsTo(" + probe + ")"
			);
			assertSameRecords(
				spans, index.getRecordsFrom(probe),
				span -> span.from() < probe && probe <= span.to(),
				"getRecordsFrom(" + probe + ")"
			);
			final long other = probes[random.nextInt(probes.length)];
			final long lower = Math.min(probe, other);
			final long upper = Math.max(probe, other);
			assertSameRecords(
				spans, index.getRecordsWithRangesOverlapping(lower, upper),
				span -> span.from() <= upper && lower <= span.to(),
				"getRecordsWithRangesOverlapping(" + lower + ", " + upper + ")"
			);
		}
	}

	/**
	 * Returns every threshold present in the index, ascending and deduplicated.
	 *
	 * @param spans the intervals the index was built from
	 * @return the thresholds
	 */
	@Nonnull
	private static long[] thresholdsOf(@Nonnull List<Span> spans) {
		final List<Long> thresholds = new ArrayList<>(spans.size() * 2);
		for (final Span span : spans) {
			thresholds.add(span.from());
			thresholds.add(span.to());
		}
		return thresholds.stream().mapToLong(Long::longValue).distinct().sorted().toArray();
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
