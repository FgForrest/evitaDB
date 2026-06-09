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

package io.evitadb.spike;

import io.evitadb.index.bPlusTree.TransactionalObjectBPlusTree;
import io.evitadb.index.bPlusTree.TransactionalObjectBPlusTree.EntryCursor;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.index.invertedIndex.ValueToRecord;
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
import io.evitadb.index.invertedIndex.ValueToRecordPrimitive;
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
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import org.roaringbitmap.RoaringBitmap;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Microbenchmark comparing the **read** behaviour of the two {@code SortIndex} value backings:
 *
 * - the **original** representation — a contiguous, naturally sorted {@code Serializable[]} of distinct values plus a
 *   sparse {@code HashMap<value, cardinality>} that only holds cardinalities greater than one (cardinality `1` is
 *   implied), and
 * - the **new** representation — a single comparator-ordered {@link TransactionalObjectBPlusTree} keyed by value with
 *   the cardinality stored inline as the value (always `>= 1`).
 *
 * The commit-side win of the tree (path-copying `O(Δ·log N)` instead of rebuilding the whole `Serializable[]`) is not
 * in question and is not measured here; what this benchmark guards is the **read hot path** — the documented primary
 * risk of the migration: a B+ tree chases leaf pointers while the array is a single cache-friendly contiguous scan.
 * If sorting (ORDER BY) regresses materially, the migration should not roll out.
 *
 * Three read patterns are measured, each in an `array*` (baseline) and `tree*` (candidate) variant:
 *
 * 1. {@code ascendingSweep*} / {@code descendingSweep*} — the ORDER BY traversal. A monotonic forward (resp. reverse)
 *    seeker is created and asked for the value at a strictly increasing (resp. decreasing) sequence of record
 *    positions, exactly as {@code MergedComparableSortedRecordsSupplierSorter} drives it during query sorting. The
 *    array seeker indexes into the contiguous array and reads cardinality from the sparse map; the tree seeker walks a
 *    `(value, cardinality)` entry cursor and reads cardinality inline.
 * 2. {@code pointLookup*} — the {@code getRecordsEqualTo} probe: locate a random value and read its cardinality.
 *    Array = {@link Arrays#binarySearch} over the contiguous array plus a map lookup; tree = a single
 *    {@link TransactionalObjectBPlusTree#search}.
 * 3. {@code valueIndexRebuild*} — the per-transaction {@code SortIndexChanges} prefix-sum cache build: a full ordered
 *    walk accumulating cardinalities into a start-offset array. Array = array iteration + per-value map lookup; tree =
 *    a single {@code entryIterator} walk with inline cardinality.
 *
 * A second gate — the **shared value→ValueToRecord tree** backing both the filter and sort indexes — adds two
 * candidate-vs-baseline pairs measuring the deltas that design introduces:
 *
 * - {@code ascendingSweepFatTree} / {@code descendingSweepFatTree} vs {@code *SweepTree} — the ORDER BY sweep when the
 *   tree value is a fat {@link ValueToRecord} bucket (cardinality read via {@link ValueToRecord#size()}, one indirection)
 *   instead of an inline `Integer`. The leaf VALUE slots are object references either way, so this isolates the
 *   cardinality-access cost, not a leaf-density change.
 * - {@code pointLookupHashMap} (today's {@code UniqueIndex} O(1) map) vs {@code pointLookupFatTree} (the unified O(log n)
 *   tree search) — the unique-check-on-write probe, the hot write path this gate must not regress materially.
 *
 * Each benchmark reports both latency ({@link Mode#AverageTime}) and throughput ({@link Mode#Throughput}) so the two
 * facets the migration is gated on can be read directly. The sweep ops cover a bounded, evenly distributed sample of
 * positions across the whole value range (so the cursor still advances through every distinct value) to keep the
 * per-op cost proportional to the distinct-value count rather than the full record count.
 *
 * The benchmarks jar uses a custom main class, so run through JMH's own runner:
 * {@code java -cp evita_test/evita_performance_tests/target/benchmarks.jar org.openjdk.jmh.Main
 * io\.evitadb\.spike\.SortIndexArrayVsBPlusTreeBenchmark}.
 *
 * **Recorded results and the decision this benchmark drove live in this benchmark's results folder**
 * `documentation/performance/individual/SortIndexArrayVsBPlusTreeBenchmark/` (its `README.md`).
 * That document is the human-readable counterpart of this class: it holds the measured numbers, the gate arc (an initial
 * ~10× ORDER BY regression, the async-profiler diagnosis that it was cache-miss-bound rather than the suspected
 * per-entry allocation or method dispatch), and the three fixes it motivated — leaf-array caching in
 * {@link TransactionalObjectBPlusTree}, the leaf block size of `256` now in {@code OwnerSortIndex#VALUE_BLOCK_SIZE}, and
 * software prefetch — which moved the read gate from FAIL to PASS and justified the consolidated-tree migration. Keep
 * the two in sync: when the tree's read path or block size changes, re-run this benchmark and update that document.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@BenchmarkMode({Mode.AverageTime, Mode.Throughput})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class SortIndexArrayVsBPlusTreeBenchmark {

	public static void main(String[] args) throws Exception {
		org.openjdk.jmh.Main.main(args);
	}

	/* =========================================================================================== */

	@Benchmark
	public void ascendingSweepArray(IndexState state, Blackhole bh) {
		final ArrayForwardSeeker seeker = new ArrayForwardSeeker(state.sortedValues, state.cardinalityMap, state.totalCount);
		final int[] positions = state.sweepPositions;
		for (int i = 0; i < positions.length; i++) {
			bh.consume(seeker.getValueToCompareOn(positions[i]));
		}
	}

	@Benchmark
	public void ascendingSweepTree(IndexState state, Blackhole bh) {
		final TreeForwardSeeker seeker = new TreeForwardSeeker(state.tree);
		final int[] positions = state.sweepPositions;
		for (int i = 0; i < positions.length; i++) {
			bh.consume(seeker.getValueToCompareOn(positions[i]));
		}
	}

	@Benchmark
	public void descendingSweepArray(IndexState state, Blackhole bh) {
		final ArrayReverseSeeker seeker = new ArrayReverseSeeker(state.sortedValues, state.cardinalityMap, state.totalCount);
		final int[] positions = state.sweepPositions;
		for (int i = 0; i < positions.length; i++) {
			bh.consume(seeker.getValueToCompareOn(positions[i]));
		}
	}

	@Benchmark
	public void descendingSweepTree(IndexState state, Blackhole bh) {
		final TreeReverseSeeker seeker = new TreeReverseSeeker(state.tree, state.totalCount);
		final int[] positions = state.sweepPositions;
		for (int i = 0; i < positions.length; i++) {
			bh.consume(seeker.getValueToCompareOn(positions[i]));
		}
	}

	/* =========================================================================================== */

	// ===== shared-tree gate =====================================================================
	// The unified design backs SortIndex by the FILTER tree, whose value is a fat ValueToRecord
	// (not an inline Integer). The leaf VALUE slots are object references either way, so leaf density
	// is unchanged; the only sweep difference is reading cardinality via
	// ValueToRecord.size() (one indirection through the bucket) instead of unboxing an inline Integer.
	// These variants measure exactly that delta vs ascending/descendingSweepTree above — a perf
	// NON-REGRESSION check, not a make-or-break gate (the memory win is independent).

	@Benchmark
	public void ascendingSweepFatTree(IndexState state, Blackhole bh) {
		final FatTreeForwardSeeker seeker = new FatTreeForwardSeeker(state.fatTree);
		final int[] positions = state.sweepPositions;
		for (int i = 0; i < positions.length; i++) {
			bh.consume(seeker.getValueToCompareOn(positions[i]));
		}
	}

	@Benchmark
	public void descendingSweepFatTree(IndexState state, Blackhole bh) {
		final FatTreeReverseSeeker seeker = new FatTreeReverseSeeker(state.fatTree, state.totalCount);
		final int[] positions = state.sweepPositions;
		for (int i = 0; i < positions.length; i++) {
			bh.consume(seeker.getValueToCompareOn(positions[i]));
		}
	}

	/* =========================================================================================== */

	// ===== ORDER BY: the ACTUAL design path vs today's sorter ===================================
	// The *SweepFatTree benchmarks above drive the LEGACY cardinality-accumulation seeker over the
	// fat tree — a worst-case proxy (it pays TransactionalBitmap.size() per bucket, which the real
	// design never calls). The two benchmarks below measure the REAL trade-off for "return the
	// query-result records in sorted order":
	//
	//  - orderByMergeJoinFatTree (candidate): walk the shared value→ValueToRecord tree in sort order,
	//    intersect each bucket's bitmap with the query-result bitmap (RoaringBitmap AND), emit matches
	//    in order. No flat arrays, no positions, no size(). Pageable in production (omitted here so we
	//    measure the full-sort case — the one UNFAVOURABLE to merge-join, since it walks every bucket).
	//  - orderByPositionMaskArray (baseline): today's MergedSortedRecordsSupplierSorter shape — map each
	//    query-result id to its sorted position via recordPositions[], collect a position bitmap, then
	//    resolve ids back via the flat sortedRecordIds[] in ascending position order.
	//
	// Both emit the identical sorted id sequence. Selectivity is the decisive axis: low selectivity
	// favours the baseline (it touches only matches); high selectivity / full sort favours merge-join.

	@Benchmark
	public void orderByMergeJoinFatTree(MergeJoinState state, Blackhole bh) {
		final RoaringBitmap queryResult = state.queryResult;
		@SuppressWarnings("rawtypes")
		final EntryCursor cursor = state.fatTree.entryCursor();
		while (cursor.hasNext()) {
			cursor.next();
			final ValueToRecord bucket = (ValueToRecord) cursor.value();
			final RoaringBitmap matched = RoaringBitmap.and(
				queryResult, RoaringBitmapBackedBitmap.getRoaringBitmap(bucket.getRecordIds())
			);
			final org.roaringbitmap.IntIterator it = matched.getIntIterator();
			while (it.hasNext()) {
				bh.consume(it.next());
			}
		}
	}

	@Benchmark
	public void orderByPositionMaskArray(MergeJoinState state, Blackhole bh) {
		final int[] recordPositions = state.recordPositions;
		final int[] sortedRecordIds = state.sortedRecordIds;
		// build the position mask for the query-result records (the getMask step)
		final RoaringBitmap positions = new RoaringBitmap();
		final org.roaringbitmap.IntIterator queryIt = state.queryResult.getIntIterator();
		while (queryIt.hasNext()) {
			positions.add(recordPositions[queryIt.next()]);
		}
		// resolve positions back to record ids in ascending sorted order (the fetchSlice step)
		final org.roaringbitmap.IntIterator posIt = positions.getIntIterator();
		while (posIt.hasNext()) {
			bh.consume(sortedRecordIds[posIt.next()]);
		}
	}

	/* =========================================================================================== */

	@Benchmark
	public int pointLookupArray(IndexState state, ValueCursor cursor) {
		final Integer value = state.lookupValues[cursor.next(state.lookupValues.length)];
		final int index = Arrays.binarySearch(state.sortedValues, value);
		if (index < 0) {
			return 0;
		}
		final Integer cardinality = state.cardinalityMap.get(value);
		return cardinality == null ? 1 : cardinality;
	}

	@Benchmark
	public int pointLookupTree(IndexState state, ValueCursor cursor) {
		final Integer value = state.lookupValues[cursor.next(state.lookupValues.length)];
		//noinspection unchecked
		final Integer cardinality = (Integer) state.tree.search((Comparable) value).orElse(null);
		return cardinality == null ? 0 : cardinality;
	}

	// ===== unique-check-on-write gate ===========================================================
	// Today UniqueIndex.uniqueValueToRecordId is a HashMap-backed map → O(1) point lookup. The
	// unified design replaces it with a search in the shared fat-value tree → O(log n). This pair
	// measures that O(1) → O(log n) delta directly on the write-time uniqueness probe.

	@Benchmark
	public int pointLookupHashMap(IndexState state, ValueCursor cursor) {
		final Integer value = state.lookupValues[cursor.next(state.lookupValues.length)];
		final ValueToRecord record = state.uniqueMap.get(value);
		return record == null ? 0 : record.size();
	}

	@Benchmark
	@SuppressWarnings("unchecked")
	public int pointLookupFatTree(IndexState state, ValueCursor cursor) {
		final Integer value = state.lookupValues[cursor.next(state.lookupValues.length)];
		final ValueToRecord record = (ValueToRecord) state.fatTree.search((Comparable) value).orElse(null);
		return record == null ? 0 : record.size();
	}

	/* =========================================================================================== */

	@Benchmark
	public long valueIndexRebuildArray(IndexState state) {
		// the SortIndexChanges prefix-sum build over the array form: iterate values, sum cardinalities from the map
		final Serializable[] values = state.sortedValues;
		final Map<Integer, Integer> cardinalities = state.cardinalityMap;
		long accumulator = 0;
		for (int i = 0; i < values.length; i++) {
			final Integer cardinality = cardinalities.get((Integer) values[i]);
			accumulator += cardinality == null ? 1 : cardinality;
		}
		return accumulator;
	}

	@Benchmark
	@SuppressWarnings("rawtypes")
	public long valueIndexRebuildTree(IndexState state) {
		// the same prefix-sum build over the tree form: a single allocation-free entry-cursor walk, value inline
		long accumulator = 0;
		final EntryCursor cursor = state.tree.entryCursor();
		while (cursor.hasNext()) {
			cursor.next();
			accumulator += (Integer) cursor.value();
		}
		return accumulator;
	}

	/* =========================================================================================== */

	/**
	 * Holds both value backings (sorted array + sparse cardinality map, and the comparator-ordered B+ tree), built
	 * from the same `distinctValues` distinct {@link Integer} keys each carrying the same uniform `cardinality`, plus
	 * the precomputed monotonic sweep positions and the random point-lookup values.
	 */
	@State(Scope.Benchmark)
	public static class IndexState {

		/** Number of bounded sweep samples - keeps the ORDER BY sweep op proportional to the distinct-value count. */
		private static final int MAX_SWEEP_SAMPLES = 200_000;

		@Param({"1000", "100000", "1000000"})
		public int distinctValues;

		/** Records sharing each value (cardinality). `1` exercises the single-record fast path (empty sparse map). */
		@Param({"1", "4"})
		public int cardinality;

		public int totalCount;
		public Serializable[] sortedValues;
		public Map<Integer, Integer> cardinalityMap;
		@SuppressWarnings("rawtypes")
		public TransactionalObjectBPlusTree tree;
		/** The shared-structure candidate: same keys, but value is a fat {@link ValueToRecord} bucket. */
		@SuppressWarnings("rawtypes")
		public TransactionalObjectBPlusTree fatTree;
		/** Today's {@code UniqueIndex} backing: a plain {@link HashMap} value→bucket (the O(1) point-lookup baseline). */
		public Map<Integer, ValueToRecord> uniqueMap;
		public int[] sweepPositions;
		public Integer[] lookupValues;

		@Setup(Level.Trial)
		@SuppressWarnings({"rawtypes", "unchecked"})
		public void setUp() {
			this.totalCount = this.distinctValues * this.cardinality;

			// naturally sorted distinct Integer values 0..N-1 (mirrors SortIndex single-attribute NULLS_LAST ASC order)
			this.sortedValues = new Serializable[this.distinctValues];
			this.cardinalityMap = new HashMap<>(this.cardinality > 1 ? this.distinctValues : 0);
			this.uniqueMap = new HashMap<>(this.distinctValues);

			final Comparator comparator = Comparator.naturalOrder();
			// leaf block size is configurable via -Dsortbench.blockSize=<N> so the read-locality effect of larger
			// (fewer, more sequential) leaves can be measured against the default; min/internal ratios mirror the
			// tree's own DEFAULT_* derivation
			final int blockSize = Integer.getInteger("sortbench.blockSize", 64);
			final int minBlock = blockSize / 2 - 1;
			final int minInternal = (int) (Math.ceil((float) minBlock / 2.0) - 1);
			final TransactionalObjectBPlusTree theTree = new TransactionalObjectBPlusTree<>(
				blockSize, minBlock, minBlock, minInternal, Comparable.class, Integer.class, comparator
			);
			// the fat-value candidate tree: same keys/order, value = ValueToRecord (Primitive for card==1, Bitmap else).
			// ValueToRecordBitmap implements TransactionalLayerProducer, so the tree needs the same identity value
			// wrapper InvertedIndex uses (InvertedIndex.VALUE_TO_RECORD_WRAPPER = ValueToRecord.class::cast).
			final Function<Object, ValueToRecord> valueWrapper = ValueToRecord.class::cast;
			final TransactionalObjectBPlusTree theFatTree = new TransactionalObjectBPlusTree<>(
				blockSize, minBlock, minBlock, minInternal, Comparable.class, ValueToRecord.class, valueWrapper, comparator
			);
			for (int i = 0; i < this.distinctValues; i++) {
				this.sortedValues[i] = i;
				if (this.cardinality > 1) {
					// sparse map only stores cardinalities greater than one (the original convention)
					this.cardinalityMap.put(i, this.cardinality);
				}
				theTree.insert((Comparable) Integer.valueOf(i), this.cardinality);
				final ValueToRecord bucket = buildBucket(i, this.cardinality);
				theFatTree.insert((Comparable) Integer.valueOf(i), bucket);
				this.uniqueMap.put(i, bucket);
			}
			this.tree = theTree;
			this.fatTree = theFatTree;

			// bounded, strictly increasing sample of record positions spread across the whole [0, totalCount) range
			final int sampleCount = Math.min(this.totalCount, MAX_SWEEP_SAMPLES);
			this.sweepPositions = new int[sampleCount];
			for (int i = 0; i < sampleCount; i++) {
				// floor(i * totalCount / sampleCount) is non-decreasing; clamp to keep within bounds
				final long position = (long) i * this.totalCount / sampleCount;
				this.sweepPositions[i] = (int) Math.min(position, this.totalCount - 1L);
			}

			// random distinct values to probe (all present, so both branches do the full lookup work)
			final Random random = new Random(42);
			this.lookupValues = new Integer[Math.min(this.distinctValues, MAX_SWEEP_SAMPLES)];
			for (int i = 0; i < this.lookupValues.length; i++) {
				this.lookupValues[i] = random.nextInt(this.distinctValues);
			}
		}

		/**
		 * Builds the production {@link ValueToRecord} bucket for a value: a lean {@link ValueToRecordPrimitive} when the
		 * value holds a single record (the unique / cardinality-1 fast path), otherwise a {@link ValueToRecordBitmap}
		 * over a contiguous block of `cardinality` record ids. Record ids are disjoint across values.
		 */
		@Nonnull
		private static ValueToRecord buildBucket(int value, int cardinality) {
			if (cardinality == 1) {
				return new ValueToRecordPrimitive(value, value);
			}
			final int[] recordIds = new int[cardinality];
			final int base = value * cardinality;
			for (int j = 0; j < cardinality; j++) {
				recordIds[j] = base + j;
			}
			return new ValueToRecordBitmap(value, recordIds);
		}
	}

	/**
	 * State for the ORDER BY merge-join vs position-mask comparison. Builds the shared `value → ValueToRecord` tree
	 * (multi-record `ValueToRecordBitmap` buckets), the flat `sortedRecordIds` / `recordPositions` arrays the legacy
	 * sorter needs, and a query-result bitmap of the records to sort. Record ids are **shuffled** across values (so the
	 * sorted order genuinely differs from id order — the realistic case the `recordPositions` machinery exists for),
	 * while remaining **ascending within each value** (the within-bucket projection), so both benchmarks emit the
	 * identical sequence.
	 */
	@State(Scope.Benchmark)
	public static class MergeJoinState {

		@Param({"100000", "1000000"})
		public int distinctValues;

		/** Records per value. Kept > 1 so buckets are `ValueToRecordBitmap` (stored bitmap, no per-call allocation). */
		@Param({"4"})
		public int cardinality;

		/** Percent of records present in the query-result (ORDER BY input). `100` = full sort (worst case for join). */
		@Param({"100", "10", "1"})
		public int selectivityPercent;

		public int totalCount;
		@SuppressWarnings("rawtypes")
		public TransactionalObjectBPlusTree fatTree;
		public int[] sortedRecordIds;
		public int[] recordPositions;
		public RoaringBitmap queryResult;

		@Setup(Level.Trial)
		@SuppressWarnings({"rawtypes", "unchecked"})
		public void setUp() {
			this.totalCount = this.distinctValues * this.cardinality;
			final int n = this.totalCount;

			// Fisher-Yates shuffle of [0..n) so a value's records are scattered ids (not contiguous).
			final int[] shuffled = new int[n];
			for (int i = 0; i < n; i++) {
				shuffled[i] = i;
			}
			final Random random = new Random(42);
			for (int i = n - 1; i > 0; i--) {
				final int j = random.nextInt(i + 1);
				final int t = shuffled[i];
				shuffled[i] = shuffled[j];
				shuffled[j] = t;
			}

			this.sortedRecordIds = new int[n];
			this.recordPositions = new int[n];

			final Comparator comparator = Comparator.naturalOrder();
			final int blockSize = Integer.getInteger("sortbench.blockSize", 64);
			final int minBlock = blockSize / 2 - 1;
			final int minInternal = (int) (Math.ceil((float) minBlock / 2.0) - 1);
			final Function<Object, ValueToRecord> valueWrapper = ValueToRecord.class::cast;
			final TransactionalObjectBPlusTree theFatTree = new TransactionalObjectBPlusTree<>(
				blockSize, minBlock, minBlock, minInternal, Comparable.class, ValueToRecord.class, valueWrapper, comparator
			);

			// each value owns a contiguous chunk of the shuffled array, sorted ascending within the value (projection)
			for (int value = 0; value < this.distinctValues; value++) {
				final int from = value * this.cardinality;
				final int[] ids = Arrays.copyOfRange(shuffled, from, from + this.cardinality);
				Arrays.sort(ids);
				for (int s = 0; s < ids.length; s++) {
					final int pos = from + s;
					this.sortedRecordIds[pos] = ids[s];
					this.recordPositions[ids[s]] = pos;
				}
				theFatTree.insert((Comparable) Integer.valueOf(value), new ValueToRecordBitmap(value, ids));
			}
			this.fatTree = theFatTree;

			// query-result bitmap: selectivityPercent% of records, deterministic
			this.queryResult = new RoaringBitmap();
			final Random sel = new Random(7);
			for (int id = 0; id < n; id++) {
				if (sel.nextInt(100) < this.selectivityPercent) {
					this.queryResult.add(id);
				}
			}
		}
	}

	/**
	 * Per-thread rotating cursor over the point-lookup value array, so successive invocations probe different keys
	 * without the cost of a fresh random draw inside the measured method.
	 */
	@State(Scope.Thread)
	public static class ValueCursor {
		private int position;

		public int next(int bound) {
			int next = this.position + 1;
			if (next >= bound) {
				next = 0;
			}
			this.position = next;
			return next;
		}
	}

	/* =========================================================================================== */

	/**
	 * Standalone replica of the original array-based forward seeker: maps an ascending record position to its value by
	 * indexing the contiguous sorted array and reading cardinality from the sparse map. Kept faithful to the
	 * pre-migration {@code SortIndex.SortedComparableForwardSeeker} so the comparison reflects the real baseline.
	 */
	private static final class ArrayForwardSeeker {
		private final Serializable[] sortedValues;
		private final Map<Integer, Integer> cardinalities;
		private final int totalCount;
		private int index = -1;
		private int indexPeak = 0;

		ArrayForwardSeeker(@Nonnull Serializable[] sortedValues, @Nonnull Map<Integer, Integer> cardinalities, int totalCount) {
			this.sortedValues = sortedValues;
			this.cardinalities = cardinalities;
			this.totalCount = totalCount;
		}

		Serializable getValueToCompareOn(int position) {
			if (this.indexPeak <= position) {
				int currentIndexCardinality = this.cardinalities.getOrDefault(this.sortedValues[this.index + 1], 1);
				while (this.indexPeak <= position && this.indexPeak < this.totalCount) {
					this.indexPeak += currentIndexCardinality;
					this.index++;
					if (this.index + 1 < this.sortedValues.length) {
						currentIndexCardinality = this.cardinalities.getOrDefault(this.sortedValues[this.index + 1], 1);
					} else {
						break;
					}
				}
			}
			return this.sortedValues[this.index];
		}
	}

	/**
	 * Standalone replica of the original array-based reverse seeker.
	 */
	private static final class ArrayReverseSeeker {
		private final Serializable[] sortedValues;
		private final Map<Integer, Integer> cardinalities;
		private final int totalCount;
		private int index;
		private int indexPeak;

		ArrayReverseSeeker(@Nonnull Serializable[] sortedValues, @Nonnull Map<Integer, Integer> cardinalities, int totalCount) {
			this.sortedValues = sortedValues;
			this.cardinalities = cardinalities;
			this.totalCount = totalCount;
			this.index = sortedValues.length;
			this.indexPeak = totalCount;
		}

		Serializable getValueToCompareOn(int invertedPosition) {
			final int position = this.totalCount - invertedPosition - 1;
			if (this.indexPeak > position) {
				int currentIndexCardinality = this.cardinalities.getOrDefault(this.sortedValues[this.index - 1], 1);
				while (this.indexPeak > position) {
					this.indexPeak -= currentIndexCardinality;
					this.index--;
					if (this.index > 0) {
						currentIndexCardinality = this.cardinalities.getOrDefault(this.sortedValues[this.index - 1], 1);
					} else {
						break;
					}
				}
			}
			return this.sortedValues[this.index];
		}
	}

	/**
	 * Standalone replica of the new tree-based forward seeker: walks a forward `(value, cardinality)` entry cursor and
	 * accumulates cardinalities, reading the cardinality inline from each entry. Mirrors the production
	 * {@code SortIndex.SortedComparableForwardSeeker}.
	 */
	private static final class TreeForwardSeeker {
		@SuppressWarnings("rawtypes")
		private final EntryCursor cursor;
		private Serializable currentValue;
		private int indexPeak = 0;

		@SuppressWarnings("rawtypes")
		TreeForwardSeeker(@Nonnull TransactionalObjectBPlusTree tree) {
			this.cursor = tree.entryCursor();
		}

		Serializable getValueToCompareOn(int position) {
			while (this.indexPeak <= position && this.cursor.hasNext()) {
				this.currentValue = (Serializable) this.cursor.next();
				this.indexPeak += (Integer) this.cursor.value();
			}
			return this.currentValue;
		}
	}

	/**
	 * Standalone replica of the new tree-based reverse seeker.
	 */
	private static final class TreeReverseSeeker {
		@SuppressWarnings("rawtypes")
		private final EntryCursor cursor;
		private final int totalCount;
		private Serializable currentValue;
		private int indexPeak;

		@SuppressWarnings("rawtypes")
		TreeReverseSeeker(@Nonnull TransactionalObjectBPlusTree tree, int totalCount) {
			this.cursor = tree.entryReverseCursor();
			this.totalCount = totalCount;
			this.indexPeak = totalCount;
		}

		Serializable getValueToCompareOn(int invertedPosition) {
			final int position = this.totalCount - invertedPosition - 1;
			while (this.indexPeak > position && this.cursor.hasNext()) {
				this.currentValue = (Serializable) this.cursor.next();
				this.indexPeak -= (Integer) this.cursor.value();
			}
			return this.currentValue;
		}
	}

	/**
	 * The shared-tree forward seeker: identical traversal to {@link TreeForwardSeeker}, but the tree value is a fat
	 * {@link ValueToRecord} and the cardinality is read via {@link ValueToRecord#size()} (one indirection through the
	 * bucket) rather than an inline `Integer`. This is the cardinality-access delta the non-regression gate measures.
	 */
	private static final class FatTreeForwardSeeker {
		@SuppressWarnings("rawtypes")
		private final EntryCursor cursor;
		private Serializable currentValue;
		private int indexPeak = 0;

		@SuppressWarnings("rawtypes")
		FatTreeForwardSeeker(@Nonnull TransactionalObjectBPlusTree tree) {
			this.cursor = tree.entryCursor();
		}

		Serializable getValueToCompareOn(int position) {
			while (this.indexPeak <= position && this.cursor.hasNext()) {
				this.currentValue = (Serializable) this.cursor.next();
				this.indexPeak += ((ValueToRecord) this.cursor.value()).size();
			}
			return this.currentValue;
		}
	}

	/**
	 * The shared-tree reverse seeker — counterpart to {@link FatTreeForwardSeeker}.
	 */
	private static final class FatTreeReverseSeeker {
		@SuppressWarnings("rawtypes")
		private final EntryCursor cursor;
		private final int totalCount;
		private Serializable currentValue;
		private int indexPeak;

		@SuppressWarnings("rawtypes")
		FatTreeReverseSeeker(@Nonnull TransactionalObjectBPlusTree tree, int totalCount) {
			this.cursor = tree.entryReverseCursor();
			this.totalCount = totalCount;
			this.indexPeak = totalCount;
		}

		Serializable getValueToCompareOn(int invertedPosition) {
			final int position = this.totalCount - invertedPosition - 1;
			while (this.indexPeak > position && this.cursor.hasNext()) {
				this.currentValue = (Serializable) this.cursor.next();
				this.indexPeak -= ((ValueToRecord) this.cursor.value()).size();
			}
			return this.currentValue;
		}
	}

}
