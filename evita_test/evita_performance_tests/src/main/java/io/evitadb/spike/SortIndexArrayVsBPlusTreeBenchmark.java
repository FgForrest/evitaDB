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

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Microbenchmark comparing the **read** behaviour of the two {@code SortIndex} value backings introduced under
 * issue #760:
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
 * {@link TransactionalObjectBPlusTree}, the leaf block size of `256` now in {@code SortIndex#VALUE_BLOCK_SIZE}, and
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
		public int[] sweepPositions;
		public Integer[] lookupValues;

		@Setup(Level.Trial)
		@SuppressWarnings({"rawtypes", "unchecked"})
		public void setUp() {
			this.totalCount = this.distinctValues * this.cardinality;

			// naturally sorted distinct Integer values 0..N-1 (mirrors SortIndex single-attribute NULLS_LAST ASC order)
			this.sortedValues = new Serializable[this.distinctValues];
			this.cardinalityMap = new HashMap<>(this.cardinality > 1 ? this.distinctValues : 0);

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
			for (int i = 0; i < this.distinctValues; i++) {
				this.sortedValues[i] = i;
				if (this.cardinality > 1) {
					// sparse map only stores cardinalities greater than one (the original convention)
					this.cardinalityMap.put(i, this.cardinality);
				}
				theTree.insert((Comparable) Integer.valueOf(i), this.cardinality);
			}
			this.tree = theTree;

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

}
