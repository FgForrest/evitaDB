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

package io.evitadb.performance.index.range;

import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.AndFormula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.base.OrFormula;
import io.evitadb.core.query.algebra.base.RangeCountFormula;
import io.evitadb.core.query.algebra.utils.FormulaFactory;
import io.evitadb.dataType.array.CompositeIntArray;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.roaringbitmap.IntIterator;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;
import io.evitadb.roaringbitmap.RoaringBitmapWriter;
import io.evitadb.index.range.RangeIndex;
import io.evitadb.index.range.RangePoint;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import javax.annotation.Nonnull;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

/**
 * A/B of the range-index QUERY shape: the positional `RangeLookup` construction against the single two-bound prefix
 * count that replaced it.
 *
 * This measures one step further out than `RangeCountKernelBenchmark`, which takes operand families as given. Here
 * the families are BUILT, because that is what the change is about: the positional form materialized every threshold
 * point into an array, binary-searched it, collected a prefix AND a suffix family pair, and intersected two counting
 * formulas - where the prefix form walks the tree once, stops at the queried point, and counts once.
 *
 * ## The fixture names itself
 *
 * A shape either REPLAYS a span dump captured from a real catalog - the file path is the parameter value, so it
 * reaches the forked JVM and is written into the result JSON - or builds one of the generated shapes calibrated to
 * a census of a production e-commerce catalog's `validity` attributes. An unrecognised shape is an error, never a
 * fallback: the predecessor of this class took its dump path from a system property and quietly re-measured the
 * generated fixture when the property failed to reach the fork, which produced an entirely plausible number for
 * the wrong workload. {@link Fixture#setUp()} additionally prints the span and threshold counts it ended up with,
 * so a result can be checked against the fixture rather than trusted to it.
 *
 * The probe point is a parameter for the same reason. How much of the tree the prefix form walks depends on where
 * the query lands in the threshold ordering, so a single convenient probe point could flatter it; `p<NN>` sweeps
 * that deliberately.
 *
 * ## The baseline is frozen here, and cross-checked
 *
 * `materializeRanges`, `RangeLookup` and the boundary fix-up were deleted from the engine, so the "before" side is a
 * faithful local copy built on the index's public surface. A copy that has drifted would make the comparison
 * meaningless in a way no timing could reveal, so {@link Fixture#setUp()} asserts that both sides compute the
 * identical bitmap on the very inputs that are about to be measured, and fails the fixture if they do not.
 *
 * Run:
 * {@code java -jar evita_test/evita_performance_tests/target/benchmarks.jar
 * io\.evitadb\.performance\.index\.range\.RangeQueryBenchmark}
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@BenchmarkMode({Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class RangeQueryBenchmark {

	public static void main(String[] args) throws Exception {
		org.openjdk.jmh.Main.main(args);
	}

	/* ========================================================================================== enveloping */

	/**
	 * The shape that ships: one forward walk of the threshold tree that stops at the queried point.
	 */
	@Benchmark
	public Bitmap prefixCountEnveloping(Fixture fixture) {
		return fixture.index.getRecordsEnvelopingInclusive(fixture.now).compute();
	}

	/**
	 * The positional shape it replaced, frozen: materialize, binary-search, collect a prefix and a suffix family
	 * pair, intersect the two counts, then OR the exact-threshold point back in.
	 */
	@Benchmark
	public Bitmap positionalEnveloping(Fixture fixture) {
		return legacyEnveloping(fixture.index, fixture.now).compute();
	}

	/**
	 * Planning only - the part that runs for every matching index during query planning, whether or not the result
	 * is ever computed.
	 */
	@Benchmark
	public Formula prefixCountEnvelopingPlanOnly(Fixture fixture) {
		return fixture.index.getRecordsEnvelopingInclusive(fixture.now);
	}

	@Benchmark
	public Formula positionalEnvelopingPlanOnly(Fixture fixture) {
		return legacyEnveloping(fixture.index, fixture.now);
	}

	/* ========================================================================================= overlapping */

	@Benchmark
	public Bitmap prefixCountOverlapping(Fixture fixture) {
		return fixture.index.getRecordsWithRangesOverlapping(fixture.windowFrom, fixture.windowTo).compute();
	}

	@Benchmark
	public Bitmap positionalOverlapping(Fixture fixture) {
		return legacyOverlapping(fixture.index, fixture.windowFrom, fixture.windowTo).compute();
	}

	/* ================================================================= the algorithm still on `dev` */

	/**
	 * The shape `dev` carries: the positional construction AND the `JoinFormula` -> `DisentangleFormula` pair
	 * underneath it, rather than the counting kernel.
	 *
	 * This exists so all three variants can be read off ONE fixture in ONE session. The kernel A/B was measured on
	 * operand families taken as given, which is a different question from what a query costs end to end, and
	 * multiplying the two ratios together would assert a number nobody measured.
	 */
	@Benchmark
	public Bitmap pairEnveloping(Fixture fixture) {
		return legacyEnvelopingWithPair(fixture.index, fixture.now);
	}

	@Benchmark
	public Bitmap pairOverlapping(Fixture fixture) {
		return legacyOverlappingWithPair(fixture.index, fixture.windowFrom, fixture.windowTo);
	}

	/**
	 * The positional enveloping query with the counting done by the replaced pair.
	 */
	@Nonnull
	private static Bitmap legacyEnvelopingWithPair(@Nonnull RangeIndex index, long threshold) {
		final RangePoint<?>[] points = materializeRanges(index);
		final int found = binarySearchThreshold(points, threshold);
		final boolean thresholdFound = found >= 0;
		final int lookupIndex = thresholdFound ? found : -found - 1;

		final int startIndex = thresholdFound ? lookupIndex : lookupIndex - 1;
		final int endIndex = thresholdFound ? lookupIndex + 1 : lookupIndex;

		final StartsEnds before = startIndex >= 0 ? collect(0, startIndex, points) : new StartsEnds();
		final StartsEnds after = endIndex < points.length ?
			collect(endIndex, points.length - 1, points) : new StartsEnds();

		final Formula envelopeFormula = new AndFormula(
			new ConstantFormula(disentangle(before.starts(), before.ends())),
			new ConstantFormula(disentangle(after.ends(), after.starts()))
		);

		if (thresholdFound) {
			final Bitmap starts = points[lookupIndex].getStarts();
			final Bitmap ends = points[lookupIndex].getEnds();
			if (!starts.isEmpty() || !ends.isEmpty()) {
				return FormulaFactory.or(
					envelopeFormula,
					starts.isEmpty() ? EmptyFormula.INSTANCE : new ConstantFormula(starts),
					ends.isEmpty() ? EmptyFormula.INSTANCE : new ConstantFormula(ends)
				).compute();
			}
		}
		return envelopeFormula.compute();
	}

	/**
	 * The positional overlapping query with the counting done by the replaced pair.
	 */
	@Nonnull
	private static Bitmap legacyOverlappingWithPair(@Nonnull RangeIndex index, long from, long to) {
		final RangePoint<?>[] points = materializeRanges(index);
		final int foundFrom = binarySearchThreshold(points, from);
		final int startIndex = foundFrom >= 0 ? foundFrom : -foundFrom - 1;
		final int foundTo = binarySearchThreshold(points, to);
		final int endIndex = foundTo >= 0 ? foundTo : -foundTo - 2;

		final StartsEnds between = collect(startIndex, endIndex, points);
		final StartsEnds before = collect(0, Math.min(startIndex, endIndex), points);
		final StartsEnds after = collect(Math.max(startIndex, endIndex), points.length - 1, points);

		return new OrFormula(
			unionFormula(between.starts()),
			unionFormula(between.ends()),
			new AndFormula(
				new ConstantFormula(disentangle(before.starts(), before.ends())),
				new ConstantFormula(disentangle(after.ends(), after.starts()))
			)
		).compute();
	}

	/**
	 * `DisentangleFormula(JoinFormula(plus), JoinFormula(minus))`: merge each family keeping duplicates, then walk
	 * the two in lockstep so each control occurrence cancels exactly one main occurrence.
	 *
	 * Like the copy in `RangeCountKernelBenchmark` this sizes each batch buffer to its operand where the deleted
	 * code allocated a flat `int[256]`, which makes it measurably FASTER than what it stands in for - so a gain
	 * measured against it understates the gain over the code actually being replaced.
	 */
	@Nonnull
	private static Bitmap disentangle(@Nonnull List<Bitmap> plus, @Nonnull List<Bitmap> minus) {
		final int[] main = mergeKeepingDuplicates(withoutEmpty(plus));
		final int[] control = mergeKeepingDuplicates(withoutEmpty(minus));
		final RoaringBitmapWriter<PersistentRoaringBitmap> writer = RoaringBitmapBackedBitmap.buildWriter();
		int c = 0;
		for (final int candidate : main) {
			while (c < control.length && control[c] < candidate) {
				c++;
			}
			if (c < control.length && control[c] == candidate) {
				c++;
			} else {
				writer.add(candidate);
			}
		}
		return new BaseBitmap(writer.get());
	}

	/**
	 * The k-way merge `JoinFormula` performed: ascending order, duplicates preserved.
	 */
	@Nonnull
	private static int[] mergeKeepingDuplicates(@Nonnull Bitmap[] family) {
		final PriorityQueue<int[]> queue = new PriorityQueue<>(
			Math.max(1, family.length), (a, b) -> Integer.compare(a[0], b[0])
		);
		final IntIterator[] iterators = new IntIterator[family.length];
		for (int i = 0; i < family.length; i++) {
			iterators[i] = RoaringBitmapBackedBitmap.getRoaringBitmap(family[i])
				.getBatchIterator()
				.asIntIterator(new int[Math.min(256, Math.max(1, family[i].size()))]);
			if (iterators[i].hasNext()) {
				queue.offer(new int[]{iterators[i].next(), i});
			}
		}
		final CompositeIntArray result = new CompositeIntArray();
		while (!queue.isEmpty()) {
			final int[] head = queue.poll();
			result.add(head[0]);
			final IntIterator it = iterators[head[1]];
			if (it.hasNext()) {
				queue.offer(new int[]{it.next(), head[1]});
			}
		}
		return result.toArray();
	}

	/* ============================================================================== the frozen positional form */

	/**
	 * Faithful copy of the deleted `getRecordsEnvelopingInclusive`, expressed on the index's public surface.
	 *
	 * @param index     the index to query
	 * @param threshold the point whose enveloping ranges are wanted
	 * @return the formula the positional implementation would have produced
	 */
	@Nonnull
	private static Formula legacyEnveloping(@Nonnull RangeIndex index, long threshold) {
		final RangePoint<?>[] points = materializeRanges(index);
		final int found = binarySearchThreshold(points, threshold);
		final boolean thresholdFound = found >= 0;
		final int lookupIndex = thresholdFound ? found : -found - 1;

		final int startIndex = thresholdFound ? lookupIndex : lookupIndex - 1;
		final int endIndex = thresholdFound ? lookupIndex + 1 : lookupIndex;

		final StartsEnds before = startIndex >= 0 ? collect(0, startIndex, points) : new StartsEnds();
		final StartsEnds after = endIndex < points.length ?
			collect(endIndex, points.length - 1, points) : new StartsEnds();

		final AndFormula envelopeFormula = new AndFormula(
			countFormula(index.getId(), before.starts(), before.ends()),
			countFormula(index.getId(), after.ends(), after.starts())
		);

		if (thresholdFound) {
			final Bitmap starts = points[lookupIndex].getStarts();
			final Bitmap ends = points[lookupIndex].getEnds();
			if (starts.isEmpty() && ends.isEmpty()) {
				return envelopeFormula;
			}
			return FormulaFactory.or(
				envelopeFormula,
				starts.isEmpty() ? EmptyFormula.INSTANCE : new ConstantFormula(starts),
				ends.isEmpty() ? EmptyFormula.INSTANCE : new ConstantFormula(ends)
			);
		}
		return envelopeFormula;
	}

	/**
	 * Faithful copy of the deleted `getRecordsWithRangesOverlapping`, expressed on the index's public surface.
	 *
	 * @param index the index to query
	 * @param from  inclusive lower bound of the window
	 * @param to    inclusive upper bound of the window
	 * @return the formula the positional implementation would have produced
	 */
	@Nonnull
	private static Formula legacyOverlapping(@Nonnull RangeIndex index, long from, long to) {
		final RangePoint<?>[] points = materializeRanges(index);
		final int foundFrom = binarySearchThreshold(points, from);
		final int startIndex = foundFrom >= 0 ? foundFrom : -foundFrom - 1;
		final int foundTo = binarySearchThreshold(points, to);
		final int endIndex = foundTo >= 0 ? foundTo : -foundTo - 2;

		final StartsEnds between = collect(startIndex, endIndex, points);
		final StartsEnds before = collect(0, Math.min(startIndex, endIndex), points);
		final StartsEnds after = collect(Math.max(startIndex, endIndex), points.length - 1, points);

		return new OrFormula(
			unionFormula(between.starts()),
			unionFormula(between.ends()),
			new AndFormula(
				countFormula(index.getId(), before.starts(), before.ends()),
				countFormula(index.getId(), after.ends(), after.starts())
			)
		);
	}

	/**
	 * The two operand families collected over a positional slice of the materialized point array.
	 */
	private record StartsEnds(@Nonnull List<Bitmap> starts, @Nonnull List<Bitmap> ends) {
		StartsEnds() {
			this(List.of(), List.of());
		}
	}

	/**
	 * The `materializeRanges()` the positional queries opened with: every threshold point copied into a
	 * positionally addressable array, on every single call.
	 */
	@Nonnull
	private static RangePoint<?>[] materializeRanges(@Nonnull RangeIndex index) {
		final List<RangePoint<?>> result = new ArrayList<>(index.getRangePointCount());
		final Iterator<? extends RangePoint<?>> it = index.rangesIterator();
		while (it.hasNext()) {
			result.add(it.next());
		}
		return result.toArray(new RangePoint<?>[0]);
	}

	/**
	 * The `RangeLookup` binary search: the index of the matching threshold, or `-(insertionPoint) - 1` when the
	 * queried value sits between two points.
	 */
	private static int binarySearchThreshold(@Nonnull RangePoint<?>[] points, long threshold) {
		int low = 0;
		int high = points.length - 1;
		while (low <= high) {
			final int mid = (low + high) >>> 1;
			final long midThreshold = points[mid].getThreshold();
			if (midThreshold < threshold) {
				low = mid + 1;
			} else if (midThreshold > threshold) {
				high = mid - 1;
			} else {
				return mid;
			}
		}
		return -(low + 1);
	}

	/**
	 * The `collectsStartsAndEnds` helper: every start and every end between two positions, inclusive.
	 */
	@Nonnull
	private static StartsEnds collect(int fromIndex, int toIndex, @Nonnull RangePoint<?>[] points) {
		final List<Bitmap> starts = new ArrayList<>();
		final List<Bitmap> ends = new ArrayList<>();
		for (int i = fromIndex; i <= toIndex; i++) {
			starts.add(points[i].getStarts());
			ends.add(points[i].getEnds());
		}
		return new StartsEnds(starts, ends);
	}

	/**
	 * The `createRangeCountFormulaIfNecessary` short-circuiting, reproduced so the baseline builds the same formula
	 * shapes the engine used to build.
	 */
	@Nonnull
	private static Formula countFormula(long id, @Nonnull List<Bitmap> plus, @Nonnull List<Bitmap> minus) {
		final Bitmap[] filteredPlus = withoutEmpty(plus);
		if (filteredPlus.length == 0) {
			return EmptyFormula.INSTANCE;
		}
		final Bitmap[] filteredMinus = withoutEmpty(minus);
		if (filteredMinus.length == 0) {
			return filteredPlus.length == 1 ?
				new ConstantFormula(filteredPlus[0]) : new OrFormula(new long[]{id}, filteredPlus);
		}
		return new RangeCountFormula(id, filteredPlus, filteredMinus);
	}

	/**
	 * The `StartsEndsDTO#getRangeStarts()` / `#getRangeEnds()` union the overlapping query OR-ed its middle slice in
	 * with.
	 */
	@Nonnull
	private static Formula unionFormula(@Nonnull List<Bitmap> bitmaps) {
		final Bitmap[] filtered = withoutEmpty(bitmaps);
		if (filtered.length == 0) {
			return EmptyFormula.INSTANCE;
		}
		if (filtered.length == 1) {
			return new ConstantFormula(filtered[0]);
		}
		return new OrFormula(
			Arrays.stream(filtered).map(ConstantFormula::new).toArray(Formula[]::new)
		);
	}

	@Nonnull
	private static Bitmap[] withoutEmpty(@Nonnull List<Bitmap> bitmaps) {
		return bitmaps.stream().filter(it -> !it.isEmpty()).toArray(Bitmap[]::new);
	}

	/* ====================================================================================== the fixture */

	/**
	 * The index under test, together with the point it is queried at.
	 */
	@State(Scope.Benchmark)
	public static class Fixture {

		/**
		 * What to build the index from.
		 *
		 * A value naming a readable file is REPLAYED: a span dump of `int spanCount` followed by that many
		 * `(int primaryKey, long from, long to)` triples, where the two bounds are the very thresholds
		 * `FilterIndex` feeds to `RangeIndex#addRecord`. Anything else must be one of the generated shapes below,
		 * and an unknown value is an error rather than a silent substitution - a benchmark result has to name the
		 * fixture that produced it, and the surest way to guarantee that is to make an unusable fixture fail.
		 *
		 * The generated shapes carry record counts and threshold cardinalities taken from a census of a production
		 * e-commerce catalog's `validity` attributes; `wide` extends the same proportions to an index an order of
		 * magnitude larger than any this catalog holds, and `narrow` is the inexpensive control.
		 */
		@Param({"census-product", "census-pricelist", "wide", "narrow"})
		public String shape;

		/**
		 * Where in the index's own threshold ordering the query point sits.
		 *
		 * `p<NN>` places it exactly ON the threshold at that percentile; `p<NN>+` one millisecond after it, which is
		 * where a real `attributeInRangeNow` almost always lands. The distinction is not cosmetic: landing on a
		 * threshold is the branch the positional implementation handled with an extra OR of that point's starts and
		 * ends, so measuring only that case would flatter the replacement. A plain number is taken as an explicit
		 * epoch-milli query point.
		 */
		@Param({"p50+"})
		public String probe;

		RangeIndex index;
		long now;
		long windowFrom;
		long windowTo;

		@Setup
		public void setUp() {
			final int generatedIds;
			final Path dump = Path.of(this.shape);
			if (Files.isReadable(dump)) {
				final List<long[]> spans = loadSpans(dump);
				this.index = new RangeIndex();
				for (final long[] span : spans) {
					this.index.addRecord(span[1], span[2], (int) span[0]);
				}
				generatedIds = spans.size();
			} else {
				final Shape built = switch (this.shape) {
					case "census-product" -> new Shape(7267, 857, 126, 900, 0.10d);
					case "census-pricelist" -> new Shape(1791, 843, 790, 800, 0.45d);
					case "wide" -> new Shape(72670, 8570, 1260, 9000, 0.10d);
					case "narrow" -> new Shape(500, 60, 20, 60, 0.10d);
					default -> throw new IllegalArgumentException(
						"Shape `" + this.shape + "` is neither a readable span dump nor a known generated shape!"
					);
				};
				this.index = build(built);
				generatedIds = built.ids();
			}

			final long[] thresholds = thresholdsOf(this.index);
			this.now = probePoint(thresholds, this.probe);
			this.windowFrom = quantile(thresholds, 25);
			this.windowTo = quantile(thresholds, 75);

			int beforeNow = 0;
			for (final long threshold : thresholds) {
				if (threshold <= this.now) {
					beforeNow++;
				}
			}
			System.out.printf(
				"# fixture: shape=%s probe=%s spans=%d points=%d (<=probe %d, >probe %d) valid-at-probe=%d%n",
				this.shape, this.probe, generatedIds, thresholds.length,
				beforeNow, thresholds.length - beforeNow,
				this.index.getRecordsEnvelopingInclusive(this.now).compute().size()
			);

			// the baseline must agree with the shipped path on exactly the inputs about to be measured, or the
			// comparison is between two different questions
			assertSame(
				"enveloping",
				legacyEnveloping(this.index, this.now),
				this.index.getRecordsEnvelopingInclusive(this.now)
			);
			assertSame(
				"overlapping",
				legacyOverlapping(this.index, this.windowFrom, this.windowTo),
				this.index.getRecordsWithRangesOverlapping(this.windowFrom, this.windowTo)
			);
			// and the `dev` variant too - three arms are only comparable if all three answer the same question
			assertSameBitmap(
				"enveloping (dev pair)",
				legacyEnvelopingWithPair(this.index, this.now),
				this.index.getRecordsEnvelopingInclusive(this.now).compute()
			);
			assertSameBitmap(
				"overlapping (dev pair)",
				legacyOverlappingWithPair(this.index, this.windowFrom, this.windowTo),
				this.index.getRecordsWithRangesOverlapping(this.windowFrom, this.windowTo).compute()
			);
		}

		private static void assertSameBitmap(@Nonnull String query, @Nonnull Bitmap legacy, @Nonnull Bitmap current) {
			if (!Arrays.equals(legacy.getArray(), current.getArray())) {
				throw new IllegalStateException(
					"The frozen `dev` algorithm and the shipped prefix count disagree on `" + query + "`: "
						+ legacy.size() + " vs " + current.size() + " records - the A/B would be meaningless!"
				);
			}
		}

		private static void assertSame(@Nonnull String query, @Nonnull Formula legacy, @Nonnull Formula current) {
			final int[] expected = legacy.compute().getArray();
			final int[] actual = current.compute().getArray();
			if (!Arrays.equals(expected, actual)) {
				throw new IllegalStateException(
					"The frozen positional baseline and the shipped prefix count disagree on `" + query + "`: "
						+ expected.length + " vs " + actual.length + " records - the A/B would be meaningless!"
				);
			}
		}
	}

	/**
	 * Reads a span dump written by the catalog probe.
	 *
	 * @param dump the file to read
	 * @return one `{primaryKey, from, to}` triple per span, in the order they were dumped
	 */
	@Nonnull
	private static List<long[]> loadSpans(@Nonnull Path dump) {
		try (final DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(dump)))) {
			final int count = in.readInt();
			final List<long[]> spans = new ArrayList<>(count);
			for (int i = 0; i < count; i++) {
				spans.add(new long[]{in.readInt(), in.readLong(), in.readLong()});
			}
			return spans;
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot read the span dump " + dump, e);
		}
	}

	/**
	 * Every distinct threshold the index carries, ascending, sentinels included - the ordering the probe point is
	 * expressed in.
	 */
	@Nonnull
	private static long[] thresholdsOf(@Nonnull RangeIndex index) {
		final List<Long> thresholds = new ArrayList<>(index.getRangePointCount());
		final Iterator<? extends RangePoint<?>> it = index.rangesIterator();
		while (it.hasNext()) {
			thresholds.add(it.next().getThreshold());
		}
		return thresholds.stream().mapToLong(Long::longValue).toArray();
	}

	/**
	 * Resolves a probe specification against the index's own thresholds.
	 */
	private static long probePoint(@Nonnull long[] thresholds, @Nonnull String specification) {
		if (!specification.startsWith("p")) {
			return Long.parseLong(specification);
		}
		final boolean justAfter = specification.endsWith("+");
		final int percentile = Integer.parseInt(
			specification.substring(1, specification.length() - (justAfter ? 1 : 0))
		);
		final long threshold = quantile(thresholds, percentile);
		// one millisecond later still sits between the same two thresholds unless they are adjacent, which is where
		// a wall-clock `now` lands in practice
		return justAfter && threshold < Long.MAX_VALUE ? threshold + 1 : threshold;
	}

	/**
	 * The threshold at the requested percentile of the ascending threshold array.
	 */
	private static long quantile(@Nonnull long[] thresholds, int percentile) {
		final int position = Math.min(thresholds.length - 1, Math.max(0, (thresholds.length * percentile) / 100));
		return thresholds[position];
	}

	/**
	 * How far below zero generated start thresholds reach; zero is the generated timeline's present.
	 */
	private static final long PAST_SPAN = 1_000_000L;
	/**
	 * How far above zero generated end thresholds reach.
	 */
	private static final long FUTURE_SPAN = 1_000_000L;

	/**
	 * @param ids              records carrying exactly one validity span each
	 * @param startPoints      distinct start thresholds they share between them
	 * @param expiredEndPoints distinct end thresholds already behind the generated present
	 * @param futureEndPoints  distinct end thresholds still ahead of it
	 * @param expiredFraction  share of records whose span has already lapsed
	 */
	private record Shape(int ids, int startPoints, int expiredEndPoints, int futureEndPoints, double expiredFraction) {
	}

	/**
	 * Builds an index of one validity span per record. Thresholds are drawn from a small shared alphabet, which is
	 * what produces the census's several-records-per-threshold-point density rather than one point per record.
	 */
	@Nonnull
	private static RangeIndex build(@Nonnull Shape shape) {
		final Random random = new Random(20260918L);
		final long[] starts = distinctThresholds(random, shape.startPoints(), -PAST_SPAN, -1);
		final long[] expiredEnds = distinctThresholds(random, shape.expiredEndPoints(), -PAST_SPAN + 1, -1);
		final long[] futureEnds = distinctThresholds(random, shape.futureEndPoints(), 1, FUTURE_SPAN);

		final RangeIndex index = new RangeIndex();
		for (int recordId = 1; recordId <= shape.ids(); recordId++) {
			final long start = starts[random.nextInt(starts.length)];
			final long end;
			if (random.nextDouble() < shape.expiredFraction()) {
				// an expired span must still end after it started, or the index would hold a contradiction
				final int candidate = random.nextInt(expiredEnds.length);
				end = expiredEnds[candidate] > start ? expiredEnds[candidate] : start + 1;
			} else {
				end = futureEnds[random.nextInt(futureEnds.length)];
			}
			index.addRecord(start, end, recordId);
		}
		return index;
	}

	/**
	 * Draws the requested number of distinct thresholds from the closed interval.
	 */
	@Nonnull
	private static long[] distinctThresholds(@Nonnull Random random, int count, long lowest, long highest) {
		final TreeSet<Long> drawn = new TreeSet<>();
		final long span = highest - lowest + 1;
		while (drawn.size() < count) {
			drawn.add(lowest + Math.floorMod(random.nextLong(), span));
		}
		return drawn.stream().mapToLong(Long::longValue).toArray();
	}

}
