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
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.infra.Blackhole;
import org.roaringbitmap.RoaringBitmap;
import org.roaringbitmap.RoaringBitmapWriter;
import org.roaringbitmap.buffer.ImmutableRoaringBitmap;
import org.roaringbitmap.buffer.MutableRoaringBitmap;

import java.util.Random;

/**
 * This spike test tries to test how fast is inserting records one by one to immutable RoaringBitmap instance.
 *
 * Results:
 *
 * (MUTABLE VERSION)
 * Benchmark                                 Mode  Cnt         Score   Error  Units
 * RoaringBitmapInsert.roaringBitmapInsert  thrpt    2  91566769.049          ops/s
 *
 * (IMMUTABLE VERSION)
 * Benchmark                                 Mode  Cnt        Score   Error  Units
 * RoaringBitmapInsert.roaringBitmapInsert  thrpt    2  1604353.059          ops/s
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class RoaringBitmapInsert {

	@State(Scope.Benchmark)
	public static class DataState {
		private static final int VALUE_COUNT = 20_000;
		private static final Random random = new Random();

		@Getter
		private ImmutableRoaringBitmap bitmap;

		/**
		 * This setup is called once for each `valueCount`.
		 */
		@Setup(Level.Iteration)
		public void setUp() {
			final RoaringBitmapWriter<RoaringBitmap> set = RoaringBitmapWriter.writer().constantMemory().runCompress(false).get();
			for (int i = 0; i < VALUE_COUNT; i++) {
				set.add(getRandomNumber());
			}

			final RoaringBitmap roaringBitmap = set.get();
			roaringBitmap.runOptimize();
			this.bitmap = roaringBitmap.toMutableRoaringBitmap();
		}

		private int getRandomNumber() {
			return random.nextInt(VALUE_COUNT * 2);
		}

	}

	/**
	 * Internal sorted arrays based implementation.
	 * @param bitmapDataSet
	 */
	@Benchmark
	@Threads(1)
	@BenchmarkMode({Mode.Throughput})
	public void roaringBitmapInsert(DataState bitmapDataSet, Blackhole blackhole) {
		final ImmutableRoaringBitmap immutableBitmap = bitmapDataSet.getBitmap();
		final MutableRoaringBitmap mutableBitmap = immutableBitmap.toMutableRoaringBitmap();
		mutableBitmap.add(
			bitmapDataSet.getRandomNumber()
		);
		blackhole.consume(mutableBitmap.toImmutableRoaringBitmap());
	}

	public static void main(String[] args) throws Exception {
		org.openjdk.jmh.Main.main(args);
	}

}
