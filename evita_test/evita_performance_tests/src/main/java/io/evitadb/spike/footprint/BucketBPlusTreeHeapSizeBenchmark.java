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

package io.evitadb.spike.footprint;

import io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree;
import io.evitadb.index.bPlusTree.ValueColumnFactory;
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

import java.util.Comparator;
import java.util.concurrent.TimeUnit;

/**
 * Answers one question: **what does `MEMORY_FOOTPRINT` cost on a realistically large index?**
 *
 * `TransactionalBucketBPlusTree#getHeapSizeInBytes` is the first figure in the statistics work that is not `O(1)`.
 * Every other component reads a counter; this one walks the tree, so its cost is `O(entries / blockSize)` — every
 * internal node and every leaf, plus both columns of each leaf. The complexity class alone does not say whether the
 * component is usable on a production catalog, so this benchmark reports the wall-clock number that does.
 *
 * # What is measured
 *
 * - `heapSize` — the full walk, which is what the statistics API would call.
 * - `sizeCounter` — the `O(1)` counter read, as the baseline every other component pays. The ratio between the two
 *   is the real cost of the decision.
 *
 * # Reading the result
 *
 * `distinctValues` spans two decimal orders up to **10 million buckets**, the scale Johnny asked about. Both block
 * sizes are production values (`InvertedIndex` / `OwnerUnique` use 256). `recordsPerValue = 16` forces the overflow
 * bitmaps, which is the expensive shape: each one is a `TransactionalBitmap` the walk must also price.
 *
 * The tree is built once per trial and never mutated, so the measured method is the only thing timed. Build time at
 * 10M buckets is substantial — expect a long setup before the first iteration reports.
 *
 * Run:
 * ```
 * java -jar target/evita-performance-tests.jar BucketBPlusTreeHeapSizeBenchmark -f 1
 * ```
 *
 * @author Claude (heap-size traversal cost), FG Forrest a.s. (c) 2026
 */
@BenchmarkMode({Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class BucketBPlusTreeHeapSizeBenchmark {

	/**
	 * The full tree walk — what an operator asking for `MEMORY_FOOTPRINT` would pay.
	 *
	 * @param state     the pre-built tree
	 * @param blackhole consumes the result so the walk cannot be optimized away
	 */
	@Benchmark
	public void heapSize(TreeState state, Blackhole blackhole) {
		blackhole.consume(state.tree.getHeapSizeInBytes());
	}

	/**
	 * The `O(1)` counter read every other statistics component pays, as the baseline to compare against.
	 *
	 * @param state     the pre-built tree
	 * @param blackhole consumes the result so the read cannot be optimized away
	 */
	@Benchmark
	public void sizeCounter(TreeState state, Blackhole blackhole) {
		blackhole.consume(state.tree.size());
	}

	/**
	 * Builds a bucket tree of `distinctValues` buckets, each holding `recordsPerValue` records. Mirrors
	 * `BucketBPlusTreePayloadBenchmark#buildTree` so the two benchmarks describe the same structure.
	 *
	 * @param blockSize       the leaf block size
	 * @param distinctValues  the number of distinct keys (buckets)
	 * @param recordsPerValue the records per bucket; more than one forces the overflow bitmap
	 * @return the populated tree
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	private static TransactionalBucketBPlusTree buildTree(int blockSize, int distinctValues, int recordsPerValue) {
		final int minBlock = blockSize / 2 - 1;
		final int minInternal = (int) (Math.ceil((float) minBlock / 2.0) - 1);
		final ValueColumnFactory factory = ValueColumnFactory.forKey(
			Integer.class, (Comparator) Comparator.naturalOrder()
		);
		final TransactionalBucketBPlusTree tree = new TransactionalBucketBPlusTree<>(
			blockSize, minBlock, minBlock, minInternal,
			Comparable.class, (Comparator) Comparator.naturalOrder(), factory
		);
		for (int i = 0; i < distinctValues; i++) {
			final Integer key = 2 * i;
			final int base = i * recordsPerValue;
			if (recordsPerValue == 1) {
				tree.addRecord((Comparable) key, base);
			} else {
				final int[] recordIds = new int[recordsPerValue];
				for (int r = 0; r < recordsPerValue; r++) {
					recordIds[r] = base + r;
				}
				tree.addRecord((Comparable) key, recordIds);
			}
		}
		return tree;
	}

	/**
	 * Holds the pre-built tree. Nothing here mutates it, so one build serves every iteration of a trial.
	 */
	@State(Scope.Benchmark)
	public static class TreeState {

		/**
		 * Leaf block size, pinned to the production value for `InvertedIndex` / `OwnerUnique`. Sweeping it as well
		 * would multiply the trial count, and every trial rebuilds its whole tree — at 10M buckets the build, not the
		 * measurement, is what costs.
		 */
		@Param({"256"})
		public int blockSize;

		/** Distinct keys (buckets). The top value is the 10M scale the traversal decision hangs on. */
		@Param({"100000", "1000000", "10000000"})
		public int distinctValues;

		/** Records per bucket — 1 stays on the bare record column, 16 forces an overflow bitmap per bucket. */
		@Param({"1", "16"})
		public int recordsPerValue;

		@SuppressWarnings("rawtypes")
		public TransactionalBucketBPlusTree tree;

		@Setup(Level.Trial)
		public void setUp() {
			this.tree = buildTree(this.blockSize, this.distinctValues, this.recordsPerValue);
		}
	}
}
