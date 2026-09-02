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

import io.evitadb.index.bitmap.TransactionalBitmap;
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

import java.util.concurrent.TimeUnit;

/**
 * Isolates **why** sizing 10 million bitmaps costs what it does.
 *
 * `BucketBPlusTreeHeapSizeBenchmark` shows the tree spine over 10M buckets walking in ~2 ms while the same tree with
 * an overflow bitmap per bucket takes ~300 ms — roughly 30 ns per bitmap. That is either slow *code* or slow
 * *memory*, and the two call for completely different responses, so this benchmark discriminates between them.
 *
 * # The experiment
 *
 * The measured code is **identical** across every `@Param`; only the number of bitmaps changes, and with it the
 * working-set size. `getHeapSizeInBytes` on one bitmap is a fixed ~15 arithmetic operations over a chain of
 * dependent loads: `TransactionalBitmap` → `PersistentRoaringBitmap` → `RoaringArray` → its three backbone
 * arrays → the container → the container's own array.
 *
 * - **Per-bitmap cost flat across sizes** ⇒ the arithmetic dominates, and the sizing code is worth optimizing.
 * - **Per-bitmap cost rising with the array** ⇒ the dependent loads dominate. At 10M bitmaps the graph is far past
 *   any last-level cache, so each of those hops is a miss, and no amount of tightening the arithmetic helps. The
 *   lever would then be *fewer objects touched*, not faster code — a cached or incrementally maintained figure.
 *
 * `report` divides by the bitmap count so the numbers are directly comparable; JMH times the whole sweep, so read
 * `ns/op ÷ count` rather than the raw score.
 *
 * @author Claude (heap-size cost attribution), FG Forrest a.s. (c) 2026
 */
@BenchmarkMode({Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class BitmapHeapSizeCostBenchmark {

	/**
	 * Sums the reported footprint over the whole array. Divide the score by `count` for the per-bitmap figure.
	 *
	 * @param state the pre-built bitmaps
	 * @return the summed footprint, returned so it cannot be optimized away
	 */
	@Benchmark
	public long heapSize(BitmapState state) {
		long total = 0;
		final TransactionalBitmap[] bitmaps = state.bitmaps;
		for (int i = 0; i < bitmaps.length; i++) {
			total += bitmaps[i].getHeapSizeInBytes();
		}
		return total;
	}

	/**
	 * The floor: touches every bitmap object but does no sizing work. The gap between this and {@link #heapSize} is
	 * what the sizing arithmetic and the deeper hops actually cost, separated from the cost of reaching the objects
	 * at all.
	 *
	 * @param state the pre-built bitmaps
	 * @return the summed cardinality, returned so it cannot be optimized away
	 */
	@Benchmark
	public long touchOnly(BitmapState state) {
		long total = 0;
		final TransactionalBitmap[] bitmaps = state.bitmaps;
		for (int i = 0; i < bitmaps.length; i++) {
			total += bitmaps[i].size();
		}
		return total;
	}

	/**
	 * Holds a flat array of bitmaps, each carrying the same small record set the bucket tree's overflow bitmaps do.
	 */
	@State(Scope.Benchmark)
	public static class BitmapState {

		/**
		 * Bitmap count — spans from comfortably cache-resident to the 10M scale the tree benchmark reaches.
		 */
		@Param({"10000", "1000000", "10000000"})
		public int count;

		/** Records per bitmap, matching `recordsPerValue = 16` in the tree benchmark. */
		private static final int RECORDS_PER_BITMAP = 16;

		public TransactionalBitmap[] bitmaps;

		@Setup(Level.Trial)
		public void setUp() {
			this.bitmaps = new TransactionalBitmap[this.count];
			final int[] recordIds = new int[RECORDS_PER_BITMAP];
			for (int i = 0; i < this.count; i++) {
				final int base = i * RECORDS_PER_BITMAP;
				for (int r = 0; r < RECORDS_PER_BITMAP; r++) {
					recordIds[r] = base + r;
				}
				this.bitmaps[i] = new TransactionalBitmap(recordIds);
			}
		}
	}
}
