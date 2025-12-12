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

import lombok.Getter;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.infra.Blackhole;
import org.roaringbitmap.IntConsumer;
import org.roaringbitmap.IntIterator;
import org.roaringbitmap.RoaringBitmap;
import org.roaringbitmap.RoaringBitmapWriter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * This spike test tries to use RoaringBitmaps for grouping and sorting.
 *
 * Results:
 *
 * NON DISTINCT COMPUTATION
 *
 * Benchmark                              (filteredKeysShare)  (valueCount)   Mode  Cnt      Score   Error  Units
 * RoaringBitmapGroup.roaringBitmapGroup                   10         10000  thrpt       23206.684          ops/s
 * RoaringBitmapGroup.roaringBitmapGroup                   10         50000  thrpt        7041.065          ops/s
 * RoaringBitmapGroup.roaringBitmapGroup                   10        200000  thrpt        1756.990          ops/s
 * RoaringBitmapGroup.roaringBitmapGroup                    5         10000  thrpt       22797.625          ops/s
 * RoaringBitmapGroup.roaringBitmapGroup                    5         50000  thrpt        6923.398          ops/s
 * RoaringBitmapGroup.roaringBitmapGroup                    5        200000  thrpt        1579.983          ops/s
 * RoaringBitmapGroup.roaringBitmapGroup                    3         10000  thrpt       28253.861          ops/s
 * RoaringBitmapGroup.roaringBitmapGroup                    3         50000  thrpt        6128.491          ops/s
 * RoaringBitmapGroup.roaringBitmapGroup                    3        200000  thrpt        1570.371          ops/s
 * RoaringBitmapGroup.roaringBitmapGroup                  1.5         10000  thrpt       27530.599          ops/s
 * RoaringBitmapGroup.roaringBitmapGroup                  1.5         50000  thrpt        5384.490          ops/s
 * RoaringBitmapGroup.roaringBitmapGroup                  1.5        200000  thrpt        1432.586          ops/s
 *
 * USING ROARING BITMAP AS DISTINCTIZER
 *
 * Benchmark                              (filteredKeysShare)  (valueCount)   Mode  Cnt     Score   Error  Units
 * RoaringBitmapGroup.roaringBitmapGroup                   10         10000  thrpt       2667.550          ops/s
 * RoaringBitmapGroup.roaringBitmapGroup                   10         50000  thrpt        625.118          ops/s
 * RoaringBitmapGroup.roaringBitmapGroup                   10        200000  thrpt        168.226          ops/s
 * RoaringBitmapGroup.roaringBitmapGroup                    5         10000  thrpt       2663.898          ops/s
 * RoaringBitmapGroup.roaringBitmapGroup                    5         50000  thrpt        677.482          ops/s
 * RoaringBitmapGroup.roaringBitmapGroup                    5        200000  thrpt        161.487          ops/s
 * RoaringBitmapGroup.roaringBitmapGroup                    3         10000  thrpt       2618.019          ops/s
 * RoaringBitmapGroup.roaringBitmapGroup                    3         50000  thrpt        666.536          ops/s
 * RoaringBitmapGroup.roaringBitmapGroup                    3        200000  thrpt        158.869          ops/s
 * RoaringBitmapGroup.roaringBitmapGroup                  1.5         10000  thrpt       2588.396          ops/s
 * RoaringBitmapGroup.roaringBitmapGroup                  1.5         50000  thrpt        664.493          ops/s
 * RoaringBitmapGroup.roaringBitmapGroup                  1.5        200000  thrpt        164.929          ops/s
 *
 * USING HASHSET AS DISTINCTIZER
 *
 * Benchmark                              (filteredKeysShare)  (valueCount)   Mode  Cnt     Score   Error  Units
 * RoaringBitmapGroup.roaringBitmapGroup                   10         10000  thrpt       5789.156          ops/s
 * RoaringBitmapGroup.roaringBitmapGroup                   10         50000  thrpt        990.253          ops/s
 * RoaringBitmapGroup.roaringBitmapGroup                   10        200000  thrpt        209.213          ops/s
 * RoaringBitmapGroup.roaringBitmapGroup                    5         10000  thrpt       5499.659          ops/s
 * RoaringBitmapGroup.roaringBitmapGroup                    5         50000  thrpt        904.918          ops/s
 * RoaringBitmapGroup.roaringBitmapGroup                    5        200000  thrpt        208.138          ops/s
 * RoaringBitmapGroup.roaringBitmapGroup                    3         10000  thrpt       6037.117          ops/s
 * RoaringBitmapGroup.roaringBitmapGroup                    3         50000  thrpt        896.018          ops/s
 * RoaringBitmapGroup.roaringBitmapGroup                    3        200000  thrpt        172.981          ops/s
 * RoaringBitmapGroup.roaringBitmapGroup                  1.5         10000  thrpt       6357.472          ops/s
 * RoaringBitmapGroup.roaringBitmapGroup                  1.5         50000  thrpt       1178.306          ops/s
 * RoaringBitmapGroup.roaringBitmapGroup                  1.5        200000  thrpt        247.969          ops/s
 *
 * BLOOM WITH HASHSET COMBINED
 *
 * Benchmark                              (filteredKeysShare)  (valueCount)   Mode  Cnt     Score   Error  Units
 * RoaringBitmapGroup.roaringBitmapGroup                   10         10000  thrpt       1901.337          ops/s
 * RoaringBitmapGroup.roaringBitmapGroup                   10         50000  thrpt        372.658          ops/s
 * RoaringBitmapGroup.roaringBitmapGroup                   10        200000  thrpt         85.558          ops/s
 * RoaringBitmapGroup.roaringBitmapGroup                    5         10000  thrpt       1948.557          ops/s
 * RoaringBitmapGroup.roaringBitmapGroup                    5         50000  thrpt        371.185          ops/s
 * RoaringBitmapGroup.roaringBitmapGroup                    5        200000  thrpt         84.915          ops/s
 * RoaringBitmapGroup.roaringBitmapGroup                    3         10000  thrpt       1881.300          ops/s
 * RoaringBitmapGroup.roaringBitmapGroup                    3         50000  thrpt        371.241          ops/s
 * RoaringBitmapGroup.roaringBitmapGroup                    3        200000  thrpt         85.508          ops/s
 * RoaringBitmapGroup.roaringBitmapGroup                  1.5         10000  thrpt       1912.739          ops/s
 * RoaringBitmapGroup.roaringBitmapGroup                  1.5         50000  thrpt        381.452          ops/s
 * RoaringBitmapGroup.roaringBitmapGroup                  1.5        200000  thrpt         87.562          ops/s
 *
 * HASHSET + FILLING INDEX MAP ALL CPUS
 *
 * RoaringBitmapGroup.roaringBitmapGroup                   10         10000  thrpt        7667.423          ops/s
 * RoaringBitmapGroup.roaringBitmapGroup                   10         50000  thrpt        1363.851          ops/s
 * RoaringBitmapGroup.roaringBitmapGroup                   10        200000  thrpt         278.684          ops/s
 * RoaringBitmapGroup.roaringBitmapGroup                    5         10000  thrpt        6584.130          ops/s
 * RoaringBitmapGroup.roaringBitmapGroup                    5         50000  thrpt         974.755          ops/s
 * RoaringBitmapGroup.roaringBitmapGroup                    5        200000  thrpt         274.463          ops/s
 * RoaringBitmapGroup.roaringBitmapGroup                    3         10000  thrpt       10345.754          ops/s
 * RoaringBitmapGroup.roaringBitmapGroup                    3         50000  thrpt        1328.236          ops/s
 * RoaringBitmapGroup.roaringBitmapGroup                    3        200000  thrpt         272.819          ops/s
 * RoaringBitmapGroup.roaringBitmapGroup                  1.5         10000  thrpt        8406.718          ops/s
 * RoaringBitmapGroup.roaringBitmapGroup                  1.5         50000  thrpt        1500.331          ops/s
 * RoaringBitmapGroup.roaringBitmapGroup                  1.5        200000  thrpt         282.363          ops/s
 *
 * HASHSET + NOT FILLING INDEX MAP ALL CPUS
 *
 * RoaringBitmapGroup.roaringBitmapGroup                   10         10000  thrpt       50022.669          ops/s
 * RoaringBitmapGroup.roaringBitmapGroup                   10         50000  thrpt        6388.307          ops/s
 * RoaringBitmapGroup.roaringBitmapGroup                   10        200000  thrpt         792.710          ops/s
 * RoaringBitmapGroup.roaringBitmapGroup                    5         10000  thrpt       45827.106          ops/s
 * RoaringBitmapGroup.roaringBitmapGroup                    5         50000  thrpt        6242.946          ops/s
 * RoaringBitmapGroup.roaringBitmapGroup                    5        200000  thrpt         765.271          ops/s
 * RoaringBitmapGroup.roaringBitmapGroup                    3         10000  thrpt       46976.800          ops/s
 * RoaringBitmapGroup.roaringBitmapGroup                    3         50000  thrpt        6449.478          ops/s
 * RoaringBitmapGroup.roaringBitmapGroup                    3        200000  thrpt         745.044          ops/s
 * RoaringBitmapGroup.roaringBitmapGroup                  1.5         10000  thrpt       46062.219          ops/s
 * RoaringBitmapGroup.roaringBitmapGroup                  1.5         50000  thrpt        6241.271          ops/s
 * RoaringBitmapGroup.roaringBitmapGroup                  1.5        200000  thrpt         781.194          ops/s
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class RoaringBitmapGroup {

	@State(Scope.Benchmark)
	public static class DataState {
		private static final Random random = new Random();
		private static final float DEVIATION = 0.3f;
		private static final int PK_MAX_DISTANCE = 10;
		private static final float FUNNEL = 0.4f;

		/**
		 * Number of generated values in the value holder.
		 */
		@Param({ "10000", "50000", "200000" })
		@Getter private int valueCount;

		/**
		 * Number of generated values in the value holder.
		 */
		@Param({ "10", "5", "3", "1.5" })
		@Getter private float filteredKeysShare;

		/**
		 * Randomized collection of sets of integer.
		 */
		@Getter
		private RoaringBitmap allPrimaryKeys;

		/**
		 * Random values that keep repeating in unsorted order but with same cardinality as all primary keys.
		 */
		@Getter
		private int[] unsortedMappedKeys;

		/**
		 * Randomized collection of sets of integer.
		 */
		@Getter
		private RoaringBitmap filteredKeys;

		/**
		 * This setup is called once for each `valueCount`.
		 */
		@Setup(Level.Iteration)
		public void setUp() {
			final int valueCount = getValueCount();
			final int setSize = valueCount + (random.nextBoolean() ? 1 : -1) * random.nextInt((int) (valueCount * DEVIATION));
			final RoaringBitmapWriter<RoaringBitmap> set = RoaringBitmapWriter.writer().constantMemory().runCompress(false).get();
			int primaryKey = random.nextInt((int) (valueCount * random.nextFloat()));
			for (int i = 0; i < setSize; i++) {
				primaryKey += random.nextInt(PK_MAX_DISTANCE);
				set.add(primaryKey);
			}

			final RoaringBitmap roaringBitmap = set.get();
			roaringBitmap.runOptimize();
			this.allPrimaryKeys = roaringBitmap;

			this.unsortedMappedKeys = new int[setSize];
			for (int i = 0; i < setSize; i++) {
				final int position = random.nextInt((int) (FUNNEL * valueCount));
				final int id = this.allPrimaryKeys.select(position);
				this.unsortedMappedKeys[i] = id;
			}

			final RoaringBitmapWriter<RoaringBitmap> filteredSet = RoaringBitmapWriter.writer().constantMemory().runCompress(false).get();
			final int filteredCount = (int) (getFilteredKeysShare() * getAllPrimaryKeys().getCardinality());
			for (int i = 0; i < filteredCount; i++) {
				final int position = random.nextInt((int) (FUNNEL * valueCount));
				final int id = this.allPrimaryKeys.select(position);
				filteredSet.add(id);
			}
			this.filteredKeys = filteredSet.get();
		}

	}

	/**
	 * Internal sorted arrays based implementation.
	 * @param generatedSets
	 * @param blackhole
	 */
	@Benchmark
	@Threads(1)
	@BenchmarkMode({Mode.Throughput})
	public void roaringBitmapGroup(DataState generatedSets, Blackhole blackhole) {
		final RoaringBitmap allKeys = generatedSets.getAllPrimaryKeys();
		final RoaringBitmap filteredKeys = generatedSets.getFilteredKeys();
		final int[] unsortedMappedKeys = generatedSets.getUnsortedMappedKeys();
		final int[] unsortedDistinctResult = new int[filteredKeys.getCardinality()];
		final DistinctUnsortedIntConsumer consumer = new DistinctUnsortedIntConsumer(
			allKeys, unsortedMappedKeys, unsortedDistinctResult
		);
		filteredKeys.forEach(consumer);

		blackhole.consume(unsortedDistinctResult);
	}

	public static void main(String[] args) throws Exception {
		org.openjdk.jmh.Main.main(args);
	}

	private static class DistinctUnsortedIntConsumer implements IntConsumer {
		private final IntIterator allIt;
		private final RoaringBitmap allKeys;
		private final int[] unsortedMappedKeys;
		private final int[] unsortedDistinctResult;
		private int position;
		private int unsortedPosition;
		private final Set<Integer> distinctizer;
		private final Map<Integer, List<Integer>> swallowMap;

		public DistinctUnsortedIntConsumer(RoaringBitmap allKeys, int[] unsortedMappedKeys, int[] unsortedDistinctResult) {
			this.allKeys = allKeys;
			this.allIt = allKeys.getBatchIterator().asIntIterator(new int[8192]);
			this.unsortedMappedKeys = unsortedMappedKeys;
			this.unsortedDistinctResult = unsortedDistinctResult;
			this.position = -1;
			this.unsortedPosition = -1;
			this.distinctizer = new HashSet<>((int)(unsortedDistinctResult.length * 0.5f));
			this.swallowMap = new HashMap<>(50);
		}

		public int getPeek() {
			return this.unsortedPosition;
		}

		public Map<Integer, List<Integer>> getSwallowMap() {
			return this.swallowMap;
		}

		@Override
		public void accept(int filteredId) {
			while (this.allIt.hasNext()) {
				++this.position;
				final int allId = this.allIt.next();
				if (filteredId == allId) {
					final int unsortedMappedKey = this.unsortedMappedKeys[this.position];
					if (this.distinctizer.add(unsortedMappedKey)) {
						this.unsortedDistinctResult[++this.unsortedPosition] = unsortedMappedKey;
					}
					if (this.unsortedPosition <= 50) {
						this.swallowMap.computeIfAbsent(unsortedMappedKey, ArrayList::new)
							.add(this.allKeys.select(this.position));
					}
					break;
				}
			}
		}
	}
}
