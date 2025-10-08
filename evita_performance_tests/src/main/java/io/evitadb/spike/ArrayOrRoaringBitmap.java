/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

import lombok.Data;
import lombok.Getter;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;
import org.roaringbitmap.FastAggregation;
import org.roaringbitmap.RoaringBitmap;
import org.roaringbitmap.RoaringBitmapWriter;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * This microbenchmark tests the raw performance of AND operation of multiple sorted arrays with custom implementation
 * and roaring bitmap implementation. Custom implementation was removed after the tests were executed.
 *
 * Results:
 *
 * internalSortedArrayAnd                 10         10000  thrpt       4615.139          ops/s
 * internalSortedArrayAnd                 10         50000  thrpt        774.444          ops/s
 * internalSortedArrayAnd                 10        200000  thrpt        194.023          ops/s
 * internalSortedArrayAnd                 50         10000  thrpt       4971.149          ops/s
 * internalSortedArrayAnd                 50         50000  thrpt        909.171          ops/s
 * internalSortedArrayAnd                 50        200000  thrpt        239.360          ops/s
 * internalSortedArrayAnd                200         10000  thrpt       4423.127          ops/s
 * internalSortedArrayAnd                200         50000  thrpt        934.304          ops/s
 * internalSortedArrayAnd                200        200000  thrpt        236.468          ops/s
 *
 * roaringBitmapAnd                       10         10000  thrpt     154878.724          ops/s
 * roaringBitmapAnd                       10         50000  thrpt      81716.343          ops/s
 * roaringBitmapAnd                       10        200000  thrpt      29258.711          ops/s
 * roaringBitmapAnd                       50         10000  thrpt      50609.126          ops/s
 * roaringBitmapAnd                       50         50000  thrpt      21478.869          ops/s
 * roaringBitmapAnd                       50        200000  thrpt       8951.145          ops/s
 * roaringBitmapAnd                      200         10000  thrpt      13764.836          ops/s
 * roaringBitmapAnd                      200         50000  thrpt       5547.304          ops/s
 * roaringBitmapAnd                      200        200000  thrpt       1371.117          ops/s
 *
 * internalSortedArrayDistinctOr          10         10000  thrpt        220.272          ops/s
 * internalSortedArrayDistinctOr          10         50000  thrpt         61.176          ops/s
 * internalSortedArrayDistinctOr          10        200000  thrpt          9.912          ops/s
 * internalSortedArrayDistinctOr          50         10000  thrpt         34.935          ops/s
 * internalSortedArrayDistinctOr          50         50000  thrpt          6.754          ops/s
 * internalSortedArrayDistinctOr          50        200000  thrpt          1.688          ops/s
 * internalSortedArrayDistinctOr         200         10000  thrpt          6.681          ops/s
 * internalSortedArrayDistinctOr         200         50000  thrpt          1.209          ops/s
 * internalSortedArrayDistinctOr         200        200000  thrpt          0.341          ops/s
 *
 * roaringBitmapDistinctOr               10          10000  thrpt      24799.479          ops/s
 * roaringBitmapDistinctOr               10          50000  thrpt       4308.594          ops/s
 * roaringBitmapDistinctOr               10         200000  thrpt       1176.012          ops/s
 * roaringBitmapDistinctOr               50          10000  thrpt      16599.457          ops/s
 * roaringBitmapDistinctOr               50          50000  thrpt       3141.445          ops/s
 * roaringBitmapDistinctOr               50         200000  thrpt        667.411          ops/s
 * roaringBitmapDistinctOr              200          10000  thrpt       9251.631          ops/s
 * roaringBitmapDistinctOr              200          50000  thrpt       1172.078          ops/s
 * roaringBitmapDistinctOr              200         200000  thrpt        265.732          ops/s
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class ArrayOrRoaringBitmap {

	@Data
	public static abstract class GeneratedSetsBase<T, S> {
		private static final Random random = new Random();
		private static final float DEVIATION = 0.3f;
		public static final int PK_MAX_DISTANCE = 10;

		/**
		 * Randomized collection of sets of integer.
		 */
		@Getter
		private List<S> sets;

		/**
		 * This setup is called once for each `valueCount`.
		 */
		@Setup(Level.Iteration)
		public void setUp() {
			final int setCount = getSetCount();
			this.sets = new ArrayList<>(setCount);
			for (int i = 0; i < setCount; i++) {
				final int valueCount = getValueCount();
				final int setSize = valueCount + (random.nextBoolean() ? 1 : -1) * random.nextInt((int) (valueCount * DEVIATION));
				final T set = createSetContainer(setSize);
				int primaryKey = random.nextInt((int) (valueCount * random.nextFloat()));
				for (int j = 0; j < setSize; j++) {
					primaryKey += random.nextInt(PK_MAX_DISTANCE);
					addPrimaryKey(set, j, primaryKey);
				}
				this.sets.add(convert(set));
			}
		}

		protected abstract int getValueCount();

		protected abstract int getSetCount();

		protected abstract T createSetContainer(int setSize);

		protected abstract void addPrimaryKey(T set, int index, int primaryKey);

		protected abstract S convert(T set);

	}

	@State(Scope.Benchmark)
	public static class GeneratedSetsAsIntegerArray extends GeneratedSetsBase<int[], int[]> {
		/**
		 * Number of generated values in the value holder.
		 */
		@Param({ "10000", "50000", "200000" })
		@Getter private int valueCount;

		/**
		 * Number of generated values in the value holder.
		 */
		@Param({ "10", "50", "200" })
		@Getter private int setCount;

		@Override
		protected int[] createSetContainer(int setSize) {
			return new int[setSize];
		}

		@Override
		protected void addPrimaryKey(int[] set, int j, int primaryKey) {
			set[j] = primaryKey;
		}

		@Override
		protected int[] convert(int[] set) {
			return set;
		}
	}

	@State(Scope.Benchmark)
	public static class GeneratedSetsAsRoaringMap extends GeneratedSetsBase<RoaringBitmapWriter<RoaringBitmap>, RoaringBitmap> {
		/**
		 * Number of generated values in the value holder.
		 */
		@Param({ "10000", "50000", "200000" })
		@Getter private int valueCount;

		/**
		 * Number of generated values in the value holder.
		 */
		@Param({ "10", "50", "200" })
		@Getter private int setCount;

		@Override
		protected RoaringBitmapWriter<RoaringBitmap> createSetContainer(int setSize) {
			return RoaringBitmapWriter.writer().constantMemory().runCompress(false).get();
		}

		@Override
		protected void addPrimaryKey(RoaringBitmapWriter<RoaringBitmap> set, int j, int primaryKey) {
			set.add(primaryKey);
		}

		@Override
		protected RoaringBitmap convert(RoaringBitmapWriter<RoaringBitmap> set) {
			final RoaringBitmap roaringBitmap = set.get();
			roaringBitmap.runOptimize();
			return roaringBitmap;
		}
	}

	/**
	 * Internal sorted arrays based implementation.
	 * @param generatedSets
	 * @param blackhole
	 */
	@Benchmark
	@BenchmarkMode({Mode.Throughput})
	public void internalSortedArrayAnd(GeneratedSetsAsIntegerArray generatedSets, Blackhole blackhole) {
		blackhole.consume(
			MatrixAnd.intMatrixConjunction(
				generatedSets.getSets()
			)
		);
	}

	/**
	 * Internal sorted arrays based implementation.
	 * @param generatedSets
	 * @param blackhole
	 */
	@Benchmark
	@BenchmarkMode({Mode.Throughput})
	public void roaringBitmapAnd(GeneratedSetsAsRoaringMap generatedSets, Blackhole blackhole) {
		blackhole.consume(
			FastAggregation.and(
				generatedSets.getSets().toArray(RoaringBitmap[]::new)
			)
		);
	}

	/**
	 * Internal sorted arrays based implementation.
	 * @param generatedSets
	 * @param blackhole
	 */
	@Benchmark
	@BenchmarkMode({Mode.Throughput})
	public void internalSortedArrayDistinctOr(GeneratedSetsAsIntegerArray generatedSets, Blackhole blackhole) {
		blackhole.consume(
			MatrixDistinctOr.intMatrixDisjunction(
				generatedSets.getSets()
			)
		);
	}

	/**
	 * Internal sorted arrays based implementation.
	 * @param generatedSets
	 * @param blackhole
	 */
	@Benchmark
	@BenchmarkMode({Mode.Throughput})
	public void roaringBitmapDistinctOr(GeneratedSetsAsRoaringMap generatedSets, Blackhole blackhole) {
		blackhole.consume(
			FastAggregation.priorityqueue_or(
				generatedSets.getSets().toArray(RoaringBitmap[]::new)
			).toArray()
		);
	}

	public static void main(String[] args) throws Exception {
		org.openjdk.jmh.Main.main(args);
	}

	private static class MatrixDistinctOr {
		public static byte intMatrixDisjunction(List<int[]> sets) {
			// implementation removed
			return 0;
		}
	}

	private static class MatrixAnd {
		public static byte intMatrixConjunction(List<int[]> sets) {
			// implementation removed
			return 0;
		}
	}
}
