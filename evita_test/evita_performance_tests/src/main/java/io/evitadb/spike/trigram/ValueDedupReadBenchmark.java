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

package io.evitadb.spike.trigram;

import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.invertedIndex.InvertedIndex;
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
import io.evitadb.utils.ArrayUtils.InsertionPosition;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.infra.Blackhole;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * **P4 of the catalog-wide value-ids line - the read path.** The census (`ValueDedupCensus`) and the representation
 * spike ({@link ValueDedupRepresentationSpike}) settled the memory side: a reduced index's front-coded value tree can
 * be replaced by an exact-sized container and the ledger is byte-exact. Neither of them touched **latency**, and the
 * whole proposal turns on it - a container that saves 1.31 GB and costs a millisecond per equality probe is not a
 * saving, it is a regression with a nice balance sheet.
 *
 * This benchmark answers the R5 question: **does replacing a reduced index's value tree with a candidate
 * representation regress the read path?**
 *
 * # What is compared
 *
 * All structures are built in `@Setup` from generated values whose *shapes* reproduce what the census measured on the
 * production corpora - no catalog is booted inside the fork. Every variant is built from the same `K` values and
 * references the **same live record bitmaps**, exactly as the representation spike did, so the only difference between
 * two columns of a table is the key structure being searched.
 *
 * ## B1 - the equality probe
 *
 * | benchmark | what it measures |
 * |---|---|
 * | {@link #equalityBaselineTree} | today: the live {@link InvertedIndex#getRecordsEqualTo} on the reduced tree |
 * | {@link #equalityContainerStrings} | `FrontCodedStringColumn#findKeyPosition` over one exactly-sized column |
 * | {@link #equalityContainerPrimitive} | binary search over a `long[]` key column |
 * | {@link #equalityDictionary} | the owner's {@link InvertedIndex#getValueId}, then a binary search on `int[]` ids |
 *
 * The dictionary variant resolves its id against a **separate, realistically sized owner** (`V` =
 * {@link #OWNER_VALUE_COUNT}), because the id resolution is the cost that decides whether the dictionary lever is
 * affordable and measuring it against a toy owner would hide it.
 *
 * ## B2 / B3 - the range evaluation
 *
 * | benchmark | direction |
 * |---|---|
 * | {@link #rangeBaselineTree} | B3: today's `getRecords(moreThanEq, lessThanEq)` on the reduced tree |
 * | {@link #rangeCanonicalFirst} | B2a, **id space**: collect the canonical ids in range, test the reduced ids on it |
 * | {@link #rangeReducedFirst} | B2b, **value space**: test each reduced value against the bounds via the comparator |
 * | {@link #rangeContainerSpan} | the ordered container's answer: binary-search both bounds, take the span |
 *
 * The two B2 directions are the two ways a planner could evaluate a range against a dictionary container, and they
 * scale in opposite ways - (a) with the **canonical** range width, (b) with `K`. Finding the crossover selectivity is
 * the point of the `selectivity` parameter.
 *
 * # Three deliberate deviations, stated rather than hidden
 *
 * 1. **The canonical ordered cursor is modelled, not called.** The engine exposes no "value ids inside a value range"
 *    cursor: `InvertedIndex#forEachValueId` walks all `V` values regardless of the range, and
 *    `InvertedIndex#getValueById` refuses inside a transaction and is not what direction (a) needs anyway. Direction
 *    (a) therefore binary-searches the owner's ascending value array and reads one id per in-range value, with no
 *    B+ tree leaf hops and no bucket materialization. That is **optimistic for direction (a)**: a real cursor would be
 *    slower, so wherever (a) loses here it loses harder in production.
 * 2. **The normalizer is the identity on every variant.** Production would hand a `String` attribute
 *    `FilterIndex#getNormalizer`'s NFD normalization; it is a per-query constant paid identically by every variant
 *    (`getRecordsEqualTo` is *given* an already-normalized value, while `getValueId` normalizes internally), so
 *    leaving it in would have charged the dictionary path alone for a step its competitors take just outside their
 *    own API. The generated values are ASCII, where NFD is a no-op regardless.
 * 3. **`findKeyPosition` is reached through a `static final` {@link MethodHandle}.** `FrontCodedStringColumn` is
 *    package-private to `evita_engine` and this spike may not edit the engine, so the column is created through
 *    `ValueColumnFactory#forKey` (the representation spike's trick) and searched through an `invokeExact` on a
 *    constant handle, which C2 folds to a direct call. {@link #controlHandleCall} / {@link #controlDirectCall}
 *    measure what is left of that dispatch so the constant is quoted rather than assumed.
 *
 * # Running it
 *
 * The equality and range families take different parameter axes, so they are two invocations - the `selectivity` axis
 * lives on {@link RangeSelectivity}, which only the range benchmarks reference, and `k` is narrowed for the range
 * family on the command line:
 *
 * ```shell
 * java -jar evita_test/evita_performance_tests/target/benchmarks.jar 'ValueDedupReadBenchmark.equality' \
 *   -rf json -rff p4-b1.json
 * java -jar evita_test/evita_performance_tests/target/benchmarks.jar 'ValueDedupReadBenchmark.range' \
 *   -p k=256,4096 -rf json -rff p4-b2b3.json
 * java -jar evita_test/evita_performance_tests/target/benchmarks.jar 'ValueDedupReadBenchmark.control' \
 *   -p shape=IDENTIFIER -p k=1 -rf json -rff p4-control.json
 * ```
 *
 * A variant that does not apply to a shape (a front-coded column on `INTEGER`, a `long[]` column on a `String` shape)
 * consumes {@link #NOT_APPLICABLE} and finishes in a couple of nanoseconds. Those cells are **not results** and must
 * be reported as `n/a`; they exist only because JMH crosses every parameter with every benchmark.
 *
 * @author Claude (catalog-wide value ids spike), FG Forrest a.s. (c) 2026
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
// pinned rather than merely defaulted: the front-coded column's decode scratch is thread-local and therefore safe,
// but the probe cursor below is shared mutable state and a multi-threaded run would make the probe sequence - and so
// the work each variant does - differ between variants
@Threads(1)
@State(Scope.Benchmark)
public class ValueDedupReadBenchmark {

	/**
	 * Distinct values held by the canonical owner - the realistic catalog-wide cardinality the census measured for a
	 * `GLOBAL`-owned string domain on the production fan-out corpus (`urlInactive` 150,790, `name` 118,451,
	 * `catalogNumber` ~118,500). Rounded to 100,000 so the id-resolution cost is honest rather than flattering.
	 */
	private static final int OWNER_VALUE_COUNT = 100_000;

	/**
	 * How many distinct probes one trial cycles through. A single fixed probe would sit in L1 for the whole run and
	 * report a cache-resident best case; a power of two keeps the cursor advance to one mask.
	 */
	private static final int PROBE_COUNT = 64;

	/**
	 * Mask of {@link #PROBE_COUNT}.
	 */
	private static final int PROBE_MASK = PROBE_COUNT - 1;

	/**
	 * One bucket in ten holds more than one record - the census's dominant shape is the single-record bucket, and this
	 * is the minority that carries a real bitmap. `3` rather than `0` so a `K = 1` tree stays single-record, which is
	 * what the `K = 1` stratum overwhelmingly is.
	 */
	private static final int MULTI_RECORD_EVERY = 10;

	/**
	 * Bucket index inside a {@link #MULTI_RECORD_EVERY} window that carries the multi-record bitmap.
	 */
	private static final int MULTI_RECORD_OFFSET = 3;

	/**
	 * Record ids allotted per bucket, so a multi-record bucket's ids never collide with its neighbours'.
	 */
	private static final int RECORDS_PER_BUCKET = 8;

	/**
	 * Where the measured range starts inside the owner's ascending value space, as a fraction. A quarter in, so even
	 * the widest selectivity stays inside the corpus and no direction gets a free "runs off the end" early exit.
	 */
	private static final double RANGE_START_FRACTION = 0.25;

	/**
	 * Seed of the reduced-subset draw and the owner's insertion shuffle. Constant, so two runs measure the same
	 * structures.
	 */
	private static final long FIXTURE_SEED = 0x1454_0004_0001L;

	/**
	 * Consumed by a variant that does not apply to the current shape. Never a plausible position or record id, so a
	 * cell that reports it is unmistakably `n/a` rather than "fast".
	 */
	private static final int NOT_APPLICABLE = Integer.MIN_VALUE;

	/**
	 * Name this benchmark registers with the owner tree, so the tree mints value ids at all.
	 */
	private static final String VALUE_ID_CONSUMER_NAME = "value-dedup-read-benchmark";

	/**
	 * The selectivities {@link #verifyRangeAgreement} checks the four range directions against - the same three
	 * {@link RangeSelectivity} measures, so nothing is timed at a width that was never proven to select alike.
	 */
	private static final double[] VERIFIED_SELECTIVITIES = {0.001, 0.05, 0.5};

	/**
	 * `io.evitadb.index.bPlusTree.ValueColumnFactory`, reached by name because it is not public.
	 */
	private static final Class<?> VALUE_COLUMN_FACTORY_CLASS =
		openClass("io.evitadb.index.bPlusTree.ValueColumnFactory");

	/**
	 * `io.evitadb.index.bPlusTree.FrontCodedStringColumn`, likewise.
	 */
	private static final Class<?> FRONT_CODED_COLUMN_CLASS =
		openClass("io.evitadb.index.bPlusTree.FrontCodedStringColumn");

	/**
	 * The factory the engine itself selects for a `String` key under natural order - a front-coded column. Natural
	 * order is what the benchmark's shapes use, and it is what arms the column's BMP-safe byte-compare fast path, so
	 * `V3` measures the search the engine would really run.
	 */
	private static final Object STRING_COLUMN_FACTORY = stringColumnFactory();

	/**
	 * `ValueColumnFactory#create(int)`, opened once.
	 */
	private static final Method COLUMN_CREATE_METHOD = openMethod(VALUE_COLUMN_FACTORY_CLASS, "create", int.class);

	/**
	 * `FrontCodedStringColumn#bulkLoad(Object[], int)`, opened once.
	 */
	private static final Method COLUMN_BULK_LOAD_METHOD =
		openMethod(FRONT_CODED_COLUMN_CLASS, "bulkLoad", Object[].class, int.class);

	/**
	 * `FrontCodedStringColumn#findKeyPosition`, as a constant handle whose type has been widened to `Object` so it can
	 * be `invokeExact`-ed from a package that cannot name the receiver. A `static final` handle invoked exactly is a
	 * JIT constant and folds to a direct call; {@link #controlHandleCall} measures what remains.
	 */
	private static final MethodHandle FIND_KEY_POSITION = findKeyPositionHandle();

	/**
	 * The same widened handle shape bound to {@link #controlTarget}, so the pair of control benchmarks differs in
	 * nothing but the dispatch.
	 */
	private static final MethodHandle CONTROL_HANDLE = controlHandle();

	/**
	 * The value shape being measured.
	 */
	@Param({"IDENTIFIER", "URL", "INTEGER"})
	private ValueShape shape;

	/**
	 * `K` - how many distinct values the reduced index holds. The strata the census reported.
	 */
	@Param({"1", "4", "32", "256", "4096"})
	private int k;

	/**
	 * The owner's distinct values, ascending. Always built: the range benchmarks take their bounds from it whether or
	 * not the owner *index* was needed.
	 */
	@SuppressWarnings("rawtypes")
	private Comparable[] ownerValues;

	/**
	 * The owner's value id of {@link #ownerValues}`[i]`, or `null` when this trial does not need the owner. Ids are
	 * allocation-ordered and the owner is populated in shuffled order on purpose, so this array is **not** monotonic -
	 * a monotonic one would let direction (a) degenerate into an id-range comparison it could never make in
	 * production.
	 */
	@Nullable private int[] ownerIds;

	/**
	 * The canonical owner, id-carrying. `null` when this trial's benchmark does not resolve ids.
	 */
	@Nullable private InvertedIndex owner;

	/**
	 * The reduced index exactly as the catalog holds it today - the baseline of every comparison.
	 */
	private InvertedIndex reduced;

	/**
	 * The reduced index's `K` values, ascending. This is the parallel array that stands in for a strings-in-place
	 * container's key column in the value-space range direction.
	 */
	@SuppressWarnings("rawtypes")
	private Comparable[] reducedValues;

	/**
	 * The lone record id of each single-record bucket, in **value** order; don't-care where {@link #overflow} holds a
	 * bitmap.
	 */
	private int[] postings;

	/**
	 * The live record bitmaps of the multi-record buckets, in value order, `null` where the bucket holds one record
	 * and wholly `null` when the tree has no multi-record bucket at all.
	 */
	@Nullable private Bitmap[] overflow;

	/**
	 * The front-coded string column of the container-strings variant, `null` on a primitive shape. Held as `Object`
	 * because its type is package-private to the engine.
	 */
	@Nullable private Object frontCodedColumn;

	/**
	 * The `long[]` key column of the container-primitive variant, `null` on a `String` shape.
	 */
	@Nullable private long[] primitiveKeys;

	/**
	 * The dictionary container's key column: the owner's value ids of the same `K` values, sorted **numerically**. A
	 * container that has given up its values has no reason to keep them in value order, and a numerically sorted id
	 * column is what makes the equality probe a binary search; value order is recovered from the sort-slot column the
	 * census and the representation spike already price.
	 */
	@Nullable private int[] dictionaryIds;

	/**
	 * {@link #postings} permuted into {@link #dictionaryIds} order.
	 */
	@Nullable private int[] dictionaryPostings;

	/**
	 * {@link #overflow} permuted into {@link #dictionaryIds} order.
	 */
	@Nullable private Bitmap[] dictionaryOverflow;

	/**
	 * The probes every equality benchmark cycles through, drawn from {@link #reducedValues} so every probe is a hit.
	 */
	@SuppressWarnings("rawtypes")
	private Comparable[] probes;

	/**
	 * {@link #probes} in their `long` form, for the primitive variant.
	 */
	private long[] primitiveProbes;

	/**
	 * Rotating cursor into {@link #probes}; advanced identically by all four equality benchmarks.
	 */
	private int probeCursor;

	/**
	 * Builds every structure this trial needs, and self-checks that they answer alike before a single timing is taken.
	 *
	 * The owner index costs `V` inserts and only two of the seven benchmarks resolve an id, so it is built only when
	 * the trial's own benchmark name says it will be used - the same trick the uncommitted `TrigramQueryBenchmark`
	 * spike plays with its baseline index, and worth roughly a third of this matrix's wall clock.
	 *
	 * @param parameters JMH's description of the trial, consulted for the benchmark's name
	 */
	@Setup(Level.Trial)
	@SuppressWarnings({"rawtypes", "unchecked"})
	public void setUp(@Nonnull BenchmarkParams parameters) {
		final String benchmark = parameters.getBenchmark();
		final boolean needsOwnerIndex = benchmark.contains("Dictionary") || benchmark.contains("CanonicalFirst");
		final long startNanos = System.nanoTime();

		this.ownerValues = generateValues(this.shape, OWNER_VALUE_COUNT);
		this.reducedValues = drawSubset(this.ownerValues, this.k);

		final Class<?> plainType = this.shape == ValueShape.INTEGER ? Integer.class : String.class;
		this.reduced = new InvertedIndex(plainType, FilterIndex.NO_NORMALIZATION, Comparator.naturalOrder());
		for (int i = 0; i < this.k; i++) {
			final int records = recordCountOf(i);
			for (int r = 0; r < records; r++) {
				this.reduced.addRecord((Serializable) this.reducedValues[i], 1 + i * RECORDS_PER_BUCKET + r);
			}
		}

		// the container columns share the reduced tree's OWN bitmaps by reference, exactly as the representation
		// spike did - a record bitmap survives every candidate representation untouched
		final ValueToRecordBitmap[] buckets = this.reduced.getValueToRecordBitmap();
		this.postings = new int[this.k];
		Bitmap[] overflowColumn = null;
		for (int i = 0; i < this.k; i++) {
			if (buckets[i].size() > 1) {
				if (overflowColumn == null) {
					overflowColumn = new Bitmap[this.k];
				}
				overflowColumn[i] = buckets[i].getRecordIds();
			} else {
				this.postings[i] = buckets[i].getRecordIds().getFirst();
			}
		}
		this.overflow = overflowColumn;

		if (this.shape == ValueShape.INTEGER) {
			this.primitiveKeys = new long[this.k];
			for (int i = 0; i < this.k; i++) {
				this.primitiveKeys[i] = ((Integer) this.reducedValues[i]).longValue();
			}
		} else {
			this.frontCodedColumn = frontCodedColumnOf(this.reducedValues, this.k);
		}

		this.probes = new Comparable[PROBE_COUNT];
		this.primitiveProbes = new long[PROBE_COUNT];
		for (int i = 0; i < PROBE_COUNT; i++) {
			// spread the probes over the whole key space rather than clustering them, so a large K really pays for
			// the descent it would pay for in production
			final int slot = (int) ((long) i * this.k / PROBE_COUNT);
			this.probes[i] = this.reducedValues[slot];
			this.primitiveProbes[i] = this.shape == ValueShape.INTEGER ? ((Integer) this.probes[i]).longValue() : 0L;
		}

		if (needsOwnerIndex) {
			buildOwner(plainType);
		}

		verifyFixture(buckets);
		try {
			verifyRangeAgreement();
		} catch (final Throwable e) {
			throw new GenericEvitaInternalError(
				"The range directions could not be cross-checked on this fixture!",
				"The range directions could not be cross-checked!",
				e
			);
		}
		System.out.printf(
			"[P4 fixture] shape=%-10s K=%-5d owner=%-3s multiRecordBuckets=%d built in %d ms%n",
			this.shape, this.k, needsOwnerIndex ? "yes" : "no", countMultiRecordBuckets(buckets),
			(System.nanoTime() - startNanos) / 1_000_000L
		);
	}

	/* ===================================== B1 - the equality probe ===================================== */

	/**
	 * Today's execution: a descent of the reduced index's own B+ tree down to the leaf's front-coded (or primitive)
	 * column. This is the number every other cell in the B1 table is a ratio of.
	 *
	 * @param bh JMH's sink
	 */
	@Benchmark
	public void equalityBaselineTree(@Nonnull Blackhole bh) {
		bh.consume(this.reduced.getRecordsEqualTo((Serializable) this.probes[nextProbeIndex()]));
	}

	/**
	 * The container-strings candidate: one exactly-sized front-coded column holding all `K` values, searched by the
	 * engine's own `findKeyPosition`. No tree, no leaves, no internal nodes - and, at a large `K`, a binary search
	 * whose every hop pays a longer restart walk than a 256-entry leaf's would.
	 *
	 * @param bh JMH's sink
	 * @throws Throwable as {@link MethodHandle#invokeExact} declares
	 */
	@Benchmark
	public void equalityContainerStrings(@Nonnull Blackhole bh) throws Throwable {
		if (this.frontCodedColumn == null) {
			bh.consume(NOT_APPLICABLE);
			return;
		}
		final Object probe = this.probes[nextProbeIndex()];
		final InsertionPosition position = (InsertionPosition) FIND_KEY_POSITION.invokeExact(
			this.frontCodedColumn, probe, 0, this.k, (Object) null
		);
		consumeRecordSet(bh, this.postings, this.overflow, position.position());
	}

	/**
	 * The container-primitive candidate: a binary search over an exactly-sized `long[]` key column.
	 *
	 * @param bh JMH's sink
	 */
	@Benchmark
	public void equalityContainerPrimitive(@Nonnull Blackhole bh) {
		if (this.primitiveKeys == null) {
			bh.consume(NOT_APPLICABLE);
			return;
		}
		final int position = Arrays.binarySearch(this.primitiveKeys, this.primitiveProbes[nextProbeIndex()]);
		consumeRecordSet(bh, this.postings, this.overflow, position);
	}

	/**
	 * The dictionary candidate: the probe is resolved to a value id by the **canonical owner** - a real
	 * {@link InvertedIndex} of {@link #OWNER_VALUE_COUNT} distinct values, descended in full - and the id is then
	 * binary-searched in the reduced container's `int[]` id column.
	 *
	 * The owner descent is the whole point: the dictionary lever moves the key bytes out of every reduced index and
	 * into one canonical tree, so every reduced probe grows a hop through a tree that is two orders of magnitude
	 * larger than the one it replaced.
	 *
	 * @param bh JMH's sink
	 */
	@Benchmark
	public void equalityDictionary(@Nonnull Blackhole bh) {
		final int valueId = this.owner.getValueId((Serializable) this.probes[nextProbeIndex()]);
		final int position = Arrays.binarySearch(this.dictionaryIds, valueId);
		consumeRecordSet(bh, this.dictionaryPostings, this.dictionaryOverflow, position);
		bh.consume(valueId);
	}

	/* ================================== B2 / B3 - the range evaluation ================================== */

	/**
	 * B3, the baseline: today's {@link InvertedIndex#getRecords(Serializable, Serializable)} on the reduced tree. It
	 * anchors a cursor at the lower bound and walks forward until the upper bound, materializing one bucket per
	 * match - work the two B2 directions do not do, which is charged here because it is what this API returns.
	 *
	 * @param bh        JMH's sink
	 * @param selection the range width being evaluated
	 */
	@Benchmark
	public void rangeBaselineTree(@Nonnull Blackhole bh, @Nonnull RangeSelectivity selection) {
		final int lowerIndex = lowerBoundIndex();
		final int upperIndex = upperBoundIndex(selection.selectivity);
		bh.consume(
			this.reduced.getRecords(
				(Serializable) this.ownerValues[lowerIndex], (Serializable) this.ownerValues[upperIndex]
			)
		);
	}

	/**
	 * B2a - **canonical-first (id space)**. Binary-searches both bounds in the canonical owner's ascending value
	 * space, collects the value ids of every value inside the range into a set, and then tests each of the reduced
	 * container's ids against it. Scales with the *canonical* range width, not with `K`.
	 *
	 * See the class javadoc's deviation 1: the canonical walk is modelled on the owner's arrays rather than driven
	 * through a cursor the engine does not expose, which makes this direction's numbers a **lower bound**.
	 *
	 * @param bh        JMH's sink
	 * @param selection the range width being evaluated
	 */
	@Benchmark
	public void rangeCanonicalFirst(@Nonnull Blackhole bh, @Nonnull RangeSelectivity selection) {
		final int lowerIndex = lowerBoundIndex();
		final int upperIndex = upperBoundIndex(selection.selectivity);
		final int from = Arrays.binarySearch(this.ownerValues, this.ownerValues[lowerIndex]);
		final int to = Arrays.binarySearch(this.ownerValues, this.ownerValues[upperIndex]);
		final ValueIdSet inRange = new ValueIdSet(to - from + 1);
		for (int i = from; i <= to; i++) {
			inRange.add(this.ownerIds[i]);
		}
		int postingAccumulator = 0;
		int bitmapCount = 0;
		Bitmap lastBitmap = null;
		for (int i = 0; i < this.k; i++) {
			if (inRange.contains(this.dictionaryIds[i])) {
				final Bitmap bitmap = this.dictionaryOverflow == null ? null : this.dictionaryOverflow[i];
				if (bitmap == null) {
					postingAccumulator += this.dictionaryPostings[i];
				} else {
					bitmapCount++;
					lastBitmap = bitmap;
				}
			}
		}
		bh.consume(postingAccumulator);
		bh.consume(bitmapCount);
		bh.consume(lastBitmap);
	}

	/**
	 * B2b - **reduced-first (value space)**. Tests every one of the reduced container's `K` values against the two
	 * bounds through the comparator. Scales with `K` and is indifferent to how wide the canonical range is.
	 *
	 * Deliberately an unordered full scan: this is the direction available to a container that holds its keys in
	 * *id* order (the dictionary's own column) and reaches back to a parallel value column only to verify. The
	 * ordered container's cheaper answer is {@link #rangeContainerSpan}, measured separately rather than folded in
	 * here.
	 *
	 * @param bh        JMH's sink
	 * @param selection the range width being evaluated
	 */
	@Benchmark
	@SuppressWarnings("unchecked")
	public void rangeReducedFirst(@Nonnull Blackhole bh, @Nonnull RangeSelectivity selection) {
		final Comparable<Object> lowerBound = (Comparable<Object>) this.ownerValues[lowerBoundIndex()];
		final Comparable<Object> upperBound =
			(Comparable<Object>) this.ownerValues[upperBoundIndex(selection.selectivity)];
		int postingAccumulator = 0;
		int bitmapCount = 0;
		Bitmap lastBitmap = null;
		for (int i = 0; i < this.k; i++) {
			final Object value = this.reducedValues[i];
			if (lowerBound.compareTo(value) <= 0 && upperBound.compareTo(value) >= 0) {
				final Bitmap bitmap = this.overflow == null ? null : this.overflow[i];
				if (bitmap == null) {
					postingAccumulator += this.postings[i];
				} else {
					bitmapCount++;
					lastBitmap = bitmap;
				}
			}
		}
		bh.consume(postingAccumulator);
		bh.consume(bitmapCount);
		bh.consume(lastBitmap);
	}

	/**
	 * The ordered container's own range answer, and the reason B2's two directions are not the whole story: a
	 * container that keeps its keys in **value** order - a front-coded string column or a `long[]` - binary-searches
	 * both bounds and takes the contiguous span, in `O(log K + matched)`.
	 *
	 * @param bh        JMH's sink
	 * @param selection the range width being evaluated
	 * @throws Throwable as {@link MethodHandle#invokeExact} declares
	 */
	@Benchmark
	public void rangeContainerSpan(@Nonnull Blackhole bh, @Nonnull RangeSelectivity selection) throws Throwable {
		final int from = spanFrom(lowerBoundIndex());
		final int to = spanTo(upperBoundIndex(selection.selectivity));
		int postingAccumulator = 0;
		int bitmapCount = 0;
		Bitmap lastBitmap = null;
		for (int i = from; i <= to; i++) {
			final Bitmap bitmap = this.overflow == null ? null : this.overflow[i];
			if (bitmap == null) {
				postingAccumulator += this.postings[i];
			} else {
				bitmapCount++;
				lastBitmap = bitmap;
			}
		}
		bh.consume(postingAccumulator);
		bh.consume(bitmapCount);
		bh.consume(lastBitmap);
	}

	/* ======================================== the dispatch control ======================================== */

	/**
	 * Control A: one `invokeExact` on the same constant-handle shape {@link #equalityContainerStrings} uses, bound to
	 * a target that does nothing but allocate the result. Run at one parameter combination only.
	 *
	 * @param bh JMH's sink
	 * @throws Throwable as {@link MethodHandle#invokeExact} declares
	 */
	@Benchmark
	public void controlHandleCall(@Nonnull Blackhole bh) throws Throwable {
		bh.consume(
			(InsertionPosition) CONTROL_HANDLE.invokeExact(
				this.frontCodedColumn, (Object) this.probes[0], 0, this.k, (Object) null
			)
		);
	}

	/**
	 * Control B: the identical call made directly. `controlHandleCall - controlDirectCall` is the whole overhead the
	 * `MethodHandle` adds to every container-strings probe.
	 *
	 * @param bh JMH's sink
	 */
	@Benchmark
	public void controlDirectCall(@Nonnull Blackhole bh) {
		bh.consume(controlTarget(this.frontCodedColumn, this.probes[0], 0, this.k, null));
	}

	/* ============================================ the fixture ============================================ */

	/**
	 * Advances the shared probe cursor. Identical in every equality benchmark, so the comparison is not skewed by
	 * one variant seeing a warmer probe than another.
	 *
	 * @return the probe slot to read
	 */
	private int nextProbeIndex() {
		final int cursor = this.probeCursor + 1 & PROBE_MASK;
		this.probeCursor = cursor;
		return cursor;
	}

	/**
	 * Hands the record set of one container slot to the sink without allocating: a single-record bucket yields its
	 * `int`, a multi-record one the live bitmap every variant shares.
	 *
	 * @param bh       JMH's sink
	 * @param postings the posting column
	 * @param overflow the overflow column, `null` when the tree has no multi-record bucket
	 * @param position the slot the search landed on
	 */
	private static void consumeRecordSet(
		@Nonnull Blackhole bh,
		@Nonnull int[] postings,
		@Nullable Bitmap[] overflow,
		int position
	) {
		final Bitmap bitmap = overflow == null ? null : overflow[position];
		if (bitmap == null) {
			bh.consume(postings[position]);
		} else {
			bh.consume(bitmap);
		}
	}

	/**
	 * First container slot at or after the range's lower bound - one binary search over the ordered key column,
	 * front-coded or primitive depending on the shape.
	 *
	 * Shared with {@link #verifyRangeAgreement}, so the checked code and the timed code cannot drift apart.
	 *
	 * @param lowerIndex index of the lower bound inside {@link #ownerValues}
	 * @return the first slot inside the range
	 * @throws Throwable as {@link MethodHandle#invokeExact} declares
	 */
	private int spanFrom(int lowerIndex) throws Throwable {
		if (this.frontCodedColumn != null) {
			final Object probe = this.ownerValues[lowerIndex];
			final InsertionPosition position = (InsertionPosition) FIND_KEY_POSITION.invokeExact(
				this.frontCodedColumn, probe, 0, this.k, (Object) null
			);
			return position.position();
		}
		final long key = ((Integer) this.ownerValues[lowerIndex]).longValue();
		final int position = Arrays.binarySearch(this.primitiveKeys, key);
		return position >= 0 ? position : -position - 1;
	}

	/**
	 * Last container slot at or before the range's upper bound - the companion of {@link #spanFrom}.
	 *
	 * @param upperIndex index of the upper bound inside {@link #ownerValues}
	 * @return the last slot inside the range, or one less than {@link #spanFrom}'s answer when the range is empty
	 * @throws Throwable as {@link MethodHandle#invokeExact} declares
	 */
	private int spanTo(int upperIndex) throws Throwable {
		if (this.frontCodedColumn != null) {
			final Object probe = this.ownerValues[upperIndex];
			final InsertionPosition position = (InsertionPosition) FIND_KEY_POSITION.invokeExact(
				this.frontCodedColumn, probe, 0, this.k, (Object) null
			);
			return position.alreadyPresent() ? position.position() : position.position() - 1;
		}
		final long key = ((Integer) this.ownerValues[upperIndex]).longValue();
		final int position = Arrays.binarySearch(this.primitiveKeys, key);
		return position >= 0 ? position : -position - 2;
	}

	/**
	 * @return the index of the range's lower bound inside {@link #ownerValues}
	 */
	private static int lowerBoundIndex() {
		return (int) (OWNER_VALUE_COUNT * RANGE_START_FRACTION);
	}

	/**
	 * @param selectivity the fraction of the canonical value space the range spans
	 * @return the index of the range's upper bound inside {@link #ownerValues}
	 */
	private static int upperBoundIndex(double selectivity) {
		return lowerBoundIndex() + (int) (OWNER_VALUE_COUNT * selectivity) - 1;
	}

	/**
	 * @param bucket the bucket's ordinal inside the reduced tree
	 * @return how many records it holds
	 */
	private static int recordCountOf(int bucket) {
		return bucket % MULTI_RECORD_EVERY == MULTI_RECORD_OFFSET ? 2 + bucket % 4 : 1;
	}

	/**
	 * Builds the canonical owner and the id columns that hang off it.
	 *
	 * The owner is populated in **shuffled** order on purpose. Value ids are allocation-ordered in production - the
	 * writer mints them as values first appear - so an owner filled in ascending value order would hand out ids that
	 * are monotonic in the value, and B2's canonical-first direction would collapse into an integer range test it
	 * could never perform against a real catalog.
	 *
	 * @param plainType the attribute's plain type, which selects the tree's leaf column kind
	 */
	private void buildOwner(@Nonnull Class<?> plainType) {
		this.owner = new InvertedIndex(plainType, FilterIndex.NO_NORMALIZATION, Comparator.naturalOrder());
		// the consumer must attach while the tree is still empty - that is the production moment too (the first write
		// to an accelerator-declaring attribute), and `detachValueIdConsumer` refuses any other
		this.owner.attachValueIdConsumer(VALUE_ID_CONSUMER_NAME);
		final int[] insertionOrder = new int[OWNER_VALUE_COUNT];
		for (int i = 0; i < OWNER_VALUE_COUNT; i++) {
			insertionOrder[i] = i;
		}
		shuffle(insertionOrder, new Random(FIXTURE_SEED + 1));
		for (int i = 0; i < OWNER_VALUE_COUNT; i++) {
			final int index = insertionOrder[i];
			this.owner.addRecord((Serializable) this.ownerValues[index], 1 + index);
		}
		this.ownerIds = new int[OWNER_VALUE_COUNT];
		for (int i = 0; i < OWNER_VALUE_COUNT; i++) {
			this.ownerIds[i] = this.owner.getValueId((Serializable) this.ownerValues[i]);
			if (this.ownerIds[i] <= 0) {
				throw new GenericEvitaInternalError(
					"The canonical owner minted no id for value `" + this.ownerValues[i] + "` at index " + i + "!",
					"The canonical owner minted no value id!"
				);
			}
		}

		// the dictionary container's id column is sorted numerically, and the posting / overflow columns are permuted
		// to match; the value order the other variants use is not available to a container that holds no values
		final int[] ids = new int[this.k];
		for (int i = 0; i < this.k; i++) {
			ids[i] = this.owner.getValueId((Serializable) this.reducedValues[i]);
		}
		final int[] order = new int[this.k];
		for (int i = 0; i < this.k; i++) {
			order[i] = i;
		}
		sortByKey(order, ids);
		this.dictionaryIds = new int[this.k];
		this.dictionaryPostings = new int[this.k];
		this.dictionaryOverflow = this.overflow == null ? null : new Bitmap[this.k];
		for (int i = 0; i < this.k; i++) {
			final int source = order[i];
			this.dictionaryIds[i] = ids[source];
			this.dictionaryPostings[i] = this.postings[source];
			if (this.dictionaryOverflow != null) {
				this.dictionaryOverflow[i] = this.overflow[source];
			}
		}
	}

	/**
	 * Proves every variant answers the same question before any of them is timed. A variant that silently searches
	 * the wrong structure would report a beautiful number for work nobody asked for, and the whole R5 verdict rests
	 * on these being the same lookup.
	 *
	 * @param buckets the reduced tree's buckets, in value order
	 */
	@SuppressWarnings("unchecked")
	private void verifyFixture(@Nonnull ValueToRecordBitmap[] buckets) {
		if (buckets.length != this.k) {
			throw new GenericEvitaInternalError(
				"The reduced tree holds " + buckets.length + " buckets where " + this.k + " values were inserted - " +
					"the generated values are not distinct!",
				"The generated values are not distinct!"
			);
		}
		for (int i = 0; i < PROBE_COUNT; i++) {
			final Comparable<Object> probe = (Comparable<Object>) this.probes[i];
			final int expected = binarySearchValues(this.reducedValues, this.k, probe);
			if (expected < 0) {
				throw new GenericEvitaInternalError(
					"Probe `" + probe + "` is absent from the reduced values it was drawn from!",
					"A probe is absent from the values it was drawn from!"
				);
			}
			final Bitmap baseline = this.reduced.getRecordsEqualTo((Serializable) probe);
			if (baseline.size() != recordCountOf(expected)) {
				throw new GenericEvitaInternalError(
					"The reduced tree answers " + baseline.size() + " records for probe `" + probe + "` where the " +
						"fixture inserted " + recordCountOf(expected) + "!",
					"The reduced tree disagrees with the fixture!"
				);
			}
			if (this.frontCodedColumn != null) {
				final int position = findKeyPosition(this.frontCodedColumn, probe, this.k);
				if (position != expected) {
					throw new GenericEvitaInternalError(
						"The front-coded column places probe `" + probe + "` at " + position + " where the value " +
							"order places it at " + expected + "!",
						"The front-coded column disagrees with the value order!"
					);
				}
			}
			if (this.primitiveKeys != null) {
				final int position = Arrays.binarySearch(this.primitiveKeys, this.primitiveProbes[i]);
				if (position != expected) {
					throw new GenericEvitaInternalError(
						"The primitive column places probe `" + probe + "` at " + position + " where the value " +
							"order places it at " + expected + "!",
						"The primitive column disagrees with the value order!"
					);
				}
			}
			if (this.dictionaryIds != null) {
				final int valueId = this.owner.getValueId((Serializable) probe);
				final int position = Arrays.binarySearch(this.dictionaryIds, valueId);
				if (position < 0 || this.dictionaryPostings[position] != this.postings[expected]) {
					throw new GenericEvitaInternalError(
						"The dictionary container resolves probe `" + probe + "` to id " + valueId + " at position " +
							position + ", whose posting disagrees with the value-ordered column!",
						"The dictionary container disagrees with the value-ordered column!"
					);
				}
			}
		}
	}

	/**
	 * Proves the four range directions select the **same buckets** at every selectivity the range family measures.
	 * Each one reaches its answer through a different column - the tree's own cursor, an id set, a comparator scan
	 * and a binary-searched span - so a permutation applied to the wrong column, or an off-by-one at a bound, would
	 * otherwise show up only as a suspiciously good number.
	 *
	 * @throws Throwable as {@link MethodHandle#invokeExact} declares
	 */
	@SuppressWarnings("unchecked")
	private void verifyRangeAgreement() throws Throwable {
		for (final double selectivity : VERIFIED_SELECTIVITIES) {
			final int lowerIndex = lowerBoundIndex();
			final int upperIndex = upperBoundIndex(selectivity);
			final Comparable<Object> lowerBound = (Comparable<Object>) this.ownerValues[lowerIndex];
			final Comparable<Object> upperBound = (Comparable<Object>) this.ownerValues[upperIndex];

			// the reference: a plain scan of the value column, which is also B2b's own strategy
			int expected = 0;
			for (int i = 0; i < this.k; i++) {
				final Object value = this.reducedValues[i];
				if (lowerBound.compareTo(value) <= 0 && upperBound.compareTo(value) >= 0) {
					expected++;
				}
			}

			final int treeMatches = this.reduced
				.getRecords((Serializable) lowerBound, (Serializable) upperBound)
				.getBuckets().length;
			assertRangeAgreement("the reduced tree's cursor", selectivity, treeMatches, expected);

			final int spanMatches = spanTo(upperIndex) - spanFrom(lowerIndex) + 1;
			assertRangeAgreement("the ordered container's span", selectivity, spanMatches, expected);

			if (this.dictionaryIds != null) {
				final int from = Arrays.binarySearch(this.ownerValues, this.ownerValues[lowerIndex]);
				final int to = Arrays.binarySearch(this.ownerValues, this.ownerValues[upperIndex]);
				final ValueIdSet inRange = new ValueIdSet(to - from + 1);
				for (int i = from; i <= to; i++) {
					inRange.add(this.ownerIds[i]);
				}
				int idMatches = 0;
				for (int i = 0; i < this.k; i++) {
					if (inRange.contains(this.dictionaryIds[i])) {
						idMatches++;
					}
				}
				assertRangeAgreement("the canonical id set", selectivity, idMatches, expected);
			}
		}
	}

	/**
	 * Raises when one range direction disagrees with the value-column reference.
	 *
	 * @param direction   which direction disagreed
	 * @param selectivity the range width it disagreed at
	 * @param actual      what it selected
	 * @param expected    what the value column selects
	 */
	private static void assertRangeAgreement(
		@Nonnull String direction,
		double selectivity,
		int actual,
		int expected
	) {
		if (actual != expected) {
			throw new GenericEvitaInternalError(
				"At selectivity " + selectivity + ", " + direction + " selects " + actual + " buckets where the " +
					"value column selects " + expected + " - the four range directions are not evaluating the same " +
					"range and their timings would not be comparable!",
				"The range directions disagree about which buckets are in range!"
			);
		}
	}

	/**
	 * @param buckets the reduced tree's buckets
	 * @return how many of them hold more than one record
	 */
	private static int countMultiRecordBuckets(@Nonnull ValueToRecordBitmap[] buckets) {
		int count = 0;
		for (final ValueToRecordBitmap bucket : buckets) {
			if (bucket.size() > 1) {
				count++;
			}
		}
		return count;
	}

	/* ======================================== value generation ======================================== */

	/**
	 * The value shapes the census found on the production corpora, reproduced synthetically.
	 *
	 * Every shape generates strictly ascending, distinct values whose natural (`String#compareTo`) order matches
	 * their UTF-8 byte order - all-ASCII, so the front-coded column's BMP-safe byte-compare fast path is the one
	 * production would take on `code` / `ean` / `url`.
	 */
	public enum ValueShape {
		/** 12-16 character product-code-shaped identifiers, sharing 8-11 leading characters with their neighbour. */
		IDENTIFIER,
		/** 37-44 character URL paths, sharing a 26-character section prefix with their neighbour. */
		URL,
		/** Plain `Integer` keys, sparse rather than dense so the primitive column is not unrealistically cheap. */
		INTEGER
	}

	/**
	 * The range width axis, kept on its own state class so it applies to the range benchmarks alone - a `@Param` on
	 * the enclosing state would multiply the whole B1 matrix by three for nothing.
	 */
	@State(Scope.Benchmark)
	public static class RangeSelectivity {

		/**
		 * The fraction of the canonical value space the range spans: a point-ish lookup, a normal filter, and half
		 * the catalog.
		 */
		@Param({"0.001", "0.05", "0.5"})
		public double selectivity;
	}

	/**
	 * Generates `count` distinct values of the given shape in strictly ascending natural order.
	 *
	 * @param shape the shape to generate
	 * @param count how many values
	 * @return the values, ascending
	 */
	@Nonnull
	@SuppressWarnings("rawtypes")
	private static Comparable[] generateValues(@Nonnull ValueShape shape, int count) {
		final Comparable[] values = new Comparable[count];
		if (shape == ValueShape.INTEGER) {
			for (int i = 0; i < count; i++) {
				// a stride rather than a dense run: a real Integer attribute's distinct values are sparse
				values[i] = Integer.valueOf(13 + i * 7);
			}
			return values;
		}
		final StringBuilder builder = new StringBuilder(64);
		for (int i = 0; i < count; i++) {
			builder.setLength(0);
			if (shape == ValueShape.IDENTIFIER) {
				builder.append("AB").append(pad(i / 1000, 3)).append('-').append(pad(i, 6));
				// a variable-length tail spreads the lengths over the shape's stated 8-16 band; order is unaffected
				// because the fixed-width numeric field already separates any two distinct keys
				builder.append("-x9k", 0, i % 5);
			} else {
				builder.append("/catalog/section-").append(pad(i / 500, 4))
					.append("/product-").append(pad(i, 7))
					.append("-detail", 0, i % 8);
			}
			values[i] = builder.toString();
		}
		return values;
	}

	/**
	 * Draws the reduced index's `K` values from the owner's `V`: a seeded uniform subset, then sorted back into value
	 * order. Uniform rather than contiguous because a reduced index holds the values of one referenced entity's
	 * products, which are scattered through the catalog-wide value space rather than adjacent in it.
	 *
	 * @param ownerValues the owner's ascending values
	 * @param k           how many to draw
	 * @return the drawn values, ascending
	 */
	@Nonnull
	@SuppressWarnings("rawtypes")
	private static Comparable[] drawSubset(@Nonnull Comparable[] ownerValues, int k) {
		final int[] indexes = new int[ownerValues.length];
		for (int i = 0; i < indexes.length; i++) {
			indexes[i] = i;
		}
		shuffle(indexes, new Random(FIXTURE_SEED));
		final int[] drawn = Arrays.copyOf(indexes, k);
		Arrays.sort(drawn);
		final Comparable[] subset = new Comparable[k];
		for (int i = 0; i < k; i++) {
			subset[i] = ownerValues[drawn[i]];
		}
		return subset;
	}

	/**
	 * Zero-pads `value` to `width` digits.
	 *
	 * @param value the number
	 * @param width the target width
	 * @return the padded text
	 */
	@Nonnull
	private static String pad(int value, int width) {
		final String text = Integer.toString(value);
		if (text.length() >= width) {
			return text;
		}
		final StringBuilder builder = new StringBuilder(width);
		for (int i = text.length(); i < width; i++) {
			builder.append('0');
		}
		builder.append(text);
		return builder.toString();
	}

	/**
	 * Fisher-Yates, seeded.
	 *
	 * @param values the array to shuffle in place
	 * @param random the seeded source
	 */
	private static void shuffle(@Nonnull int[] values, @Nonnull Random random) {
		for (int i = values.length - 1; i > 0; i--) {
			final int j = random.nextInt(i + 1);
			final int swap = values[i];
			values[i] = values[j];
			values[j] = swap;
		}
	}

	/**
	 * Sorts `order` so that `keys[order[i]]` ascends - an insertion sort, because it runs once per trial over at most
	 * 4,096 elements and pulling in a boxed comparator sort would allocate more than it saves.
	 *
	 * @param order the permutation to sort
	 * @param keys  the keys it addresses
	 */
	private static void sortByKey(@Nonnull int[] order, @Nonnull int[] keys) {
		for (int i = 1; i < order.length; i++) {
			final int current = order[i];
			final int key = keys[current];
			int j = i - 1;
			while (j >= 0 && keys[order[j]] > key) {
				order[j + 1] = order[j];
				j--;
			}
			order[j + 1] = current;
		}
	}

	/**
	 * Binary-searches an ascending `Comparable[]` prefix.
	 *
	 * @param values the ascending values
	 * @param length how many of them are live
	 * @param probe  the value to find
	 * @return its index, or a negative insertion point
	 */
	@SuppressWarnings({"rawtypes", "unchecked"})
	private static int binarySearchValues(@Nonnull Comparable[] values, int length, @Nonnull Comparable probe) {
		int lo = 0;
		int hi = length - 1;
		while (lo <= hi) {
			final int mid = lo + hi >>> 1;
			final int comparison = values[mid].compareTo(probe);
			if (comparison < 0) {
				lo = mid + 1;
			} else if (comparison > 0) {
				hi = mid - 1;
			} else {
				return mid;
			}
		}
		return -lo - 1;
	}

	/* ====================================== the engine's own column ====================================== */

	/**
	 * Builds the container-strings key column: one exactly-sized front-coded column holding all `K` values, bulk
	 * loaded in ascending order so prefix sharing is what a sorted run gives.
	 *
	 * @param values the ascending values
	 * @param count  how many of them
	 * @return the column, as an opaque object - its type is package-private to the engine
	 */
	@Nonnull
	@SuppressWarnings("rawtypes")
	private static Object frontCodedColumnOf(@Nonnull Comparable[] values, int count) {
		final Object[] keys = new Object[count];
		System.arraycopy(values, 0, keys, 0, count);
		try {
			final Object column = COLUMN_CREATE_METHOD.invoke(STRING_COLUMN_FACTORY, count);
			COLUMN_BULK_LOAD_METHOD.invoke(column, keys, count);
			return column;
		} catch (final ReflectiveOperationException e) {
			throw new GenericEvitaInternalError(
				"Cannot bulk-load a front-coded column of " + count + " keys!",
				"Cannot bulk-load a front-coded column!",
				e
			);
		}
	}

	/**
	 * The cold-path form of the search the benchmark drives through {@link #FIND_KEY_POSITION} - used by the fixture
	 * self-check, where clarity matters and a nanosecond does not.
	 *
	 * @param column the front-coded column
	 * @param probe  the value to find
	 * @param count  how many live slots the column holds
	 * @return the slot the probe sits in, or a negative value when it is absent
	 */
	private static int findKeyPosition(@Nonnull Object column, @Nonnull Object probe, int count) {
		try {
			final InsertionPosition position = (InsertionPosition) FIND_KEY_POSITION.invokeExact(
				column, probe, 0, count, (Object) null
			);
			return position.alreadyPresent() ? position.position() : -position.position() - 1;
		} catch (final Throwable e) {
			throw new GenericEvitaInternalError(
				"Cannot search a front-coded column of " + count + " keys!",
				"Cannot search a front-coded column!",
				e
			);
		}
	}

	/**
	 * The dispatch control's target: the same signature the real search has, doing nothing but building the result
	 * object, so the two control benchmarks differ in the dispatch alone. The allocation is deliberate - a target
	 * that returned a constant would be folded away on both sides and measure nothing.
	 *
	 * @param column     ignored
	 * @param probe      ignored
	 * @param from       lower search bound
	 * @param to         upper search bound
	 * @param comparator ignored
	 * @return a fresh position
	 */
	@Nonnull
	private static InsertionPosition controlTarget(
		@Nullable Object column,
		@Nullable Object probe,
		int from,
		int to,
		@Nullable Object comparator
	) {
		return new InsertionPosition(from + to, false);
	}

	/**
	 * Builds the factory the engine selects for a `String` key under natural order.
	 *
	 * @return the factory instance
	 */
	@Nonnull
	private static Object stringColumnFactory() {
		try {
			final Method forKey = openMethod(VALUE_COLUMN_FACTORY_CLASS, "forKey", Class.class, Comparator.class);
			return forKey.invoke(null, String.class, null);
		} catch (final ReflectiveOperationException e) {
			throw new GenericEvitaInternalError(
				"Cannot obtain the engine's own value-column factory for a `String` key!",
				"Cannot obtain the value-column factory for a String key!",
				e
			);
		}
	}

	/**
	 * Unreflects `FrontCodedStringColumn#findKeyPosition` and widens every reference parameter to `Object`, so the
	 * handle can be `invokeExact`-ed from a package that cannot name the receiver type.
	 *
	 * @return the widened handle
	 */
	@Nonnull
	private static MethodHandle findKeyPositionHandle() {
		try {
			final Method method = openMethod(
				FRONT_CODED_COLUMN_CLASS, "findKeyPosition", Comparable.class, int.class, int.class, Comparator.class
			);
			return MethodHandles.lookup().unreflect(method).asType(
				MethodType.methodType(
					InsertionPosition.class, Object.class, Object.class, int.class, int.class, Object.class
				)
			);
		} catch (final IllegalAccessException e) {
			throw new GenericEvitaInternalError(
				"Cannot bind a handle to `FrontCodedStringColumn#findKeyPosition`!",
				"Cannot bind a handle to the front-coded column's search!",
				e
			);
		}
	}

	/**
	 * @return the same widened handle shape, bound to {@link #controlTarget}
	 */
	@Nonnull
	private static MethodHandle controlHandle() {
		try {
			final Method method = ValueDedupReadBenchmark.class.getDeclaredMethod(
				"controlTarget", Object.class, Object.class, int.class, int.class, Object.class
			);
			method.setAccessible(true);
			return MethodHandles.lookup().unreflect(method).asType(
				MethodType.methodType(
					InsertionPosition.class, Object.class, Object.class, int.class, int.class, Object.class
				)
			);
		} catch (final ReflectiveOperationException e) {
			throw new GenericEvitaInternalError(
				"Cannot bind the dispatch control's handle!",
				"Cannot bind the dispatch control's handle!",
				e
			);
		}
	}

	/**
	 * Loads an engine class by name, because its package-private visibility makes it unnameable from here.
	 *
	 * @param className fully qualified class name
	 * @return the loaded class
	 */
	@Nonnull
	private static Class<?> openClass(@Nonnull String className) {
		try {
			return Class.forName(className);
		} catch (final ClassNotFoundException e) {
			throw new GenericEvitaInternalError(
				"Cannot load `" + className + "` - the benchmark reaches it by name because it is not public.",
				"Cannot load a class the read benchmark needs!",
				e
			);
		}
	}

	/**
	 * Opens one declared method for invocation.
	 *
	 * @param owner          the declaring class
	 * @param methodName     the method to open
	 * @param parameterTypes its parameter types
	 * @return the opened method
	 */
	@Nonnull
	private static Method openMethod(
		@Nonnull Class<?> owner,
		@Nonnull String methodName,
		@Nonnull Class<?>... parameterTypes
	) {
		try {
			final Method method = owner.getDeclaredMethod(methodName, parameterTypes);
			method.setAccessible(true);
			return method;
		} catch (final NoSuchMethodException | SecurityException e) {
			throw new GenericEvitaInternalError(
				"Cannot open `" + owner.getSimpleName() + "#" + methodName + "` - the benchmark calls it because " +
					"the declaring type is not public.",
				"Cannot open a method the read benchmark needs!",
				e
			);
		}
	}

	/**
	 * An open-addressed set of value ids, allocated per range evaluation - which is what a planner taking the
	 * canonical-first direction would have to do, and a cost that grows with the canonical range rather than with
	 * `K`. `0` is free as the empty sentinel because {@code ValueIdAllocator.UNASSIGNED_VALUE_ID} is `0` and every
	 * minted id is strictly greater.
	 */
	private static final class ValueIdSet {

		/**
		 * The open-addressed table; `0` marks an empty slot.
		 */
		@Nonnull private final int[] table;

		/**
		 * `table.length - 1`, the probe mask.
		 */
		private final int mask;

		/**
		 * @param expected how many ids will be added
		 */
		ValueIdSet(int expected) {
			// a load factor of at most one half, rounded up to a power of two
			final int capacity = Integer.highestOneBit(Math.max(4, expected)) << 2;
			this.table = new int[capacity];
			this.mask = capacity - 1;
		}

		/**
		 * @param id the id to add
		 */
		void add(int id) {
			int slot = hash(id) & this.mask;
			while (this.table[slot] != 0) {
				if (this.table[slot] == id) {
					return;
				}
				slot = slot + 1 & this.mask;
			}
			this.table[slot] = id;
		}

		/**
		 * @param id the id to test
		 * @return whether it was added
		 */
		boolean contains(int id) {
			int slot = hash(id) & this.mask;
			while (true) {
				final int value = this.table[slot];
				if (value == 0) {
					return false;
				}
				if (value == id) {
					return true;
				}
				slot = slot + 1 & this.mask;
			}
		}

		/**
		 * Fibonacci hashing - allocation-ordered ids are dense and would collide catastrophically under identity.
		 *
		 * @param id the id to scatter
		 * @return its scattered form
		 */
		private static int hash(int id) {
			final int scattered = id * 0x9E3779B1;
			return scattered ^ scattered >>> 16;
		}
	}

	/**
	 * JMH entry point, so the class can be run straight from the shaded jar.
	 *
	 * @param args JMH's own arguments
	 * @throws Exception when JMH fails
	 */
	public static void main(@Nonnull String[] args) throws Exception {
		org.openjdk.jmh.Main.main(args);
	}
}
