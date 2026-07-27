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

package io.evitadb.spike.mock;

import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import lombok.Getter;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;
import io.evitadb.roaringbitmap.RoaringBitmapWriter;

import java.util.Random;

/**
 * JMH benchmark state providing two pre-generated {@link RoaringBitmapBackedBitmap} instances
 * for bitmap set-operation benchmarks ({@link io.evitadb.spike.FormulaCostMeasurement}).
 *
 * Each bitmap contains {@link #VALUE_COUNT} random integers drawn from [0, 2×VALUE_COUNT),
 * yielding ~50% overlap between the two bitmaps on average. Both bitmaps are run-optimized
 * for realistic PersistentRoaringBitmap performance characteristics. A fixed seed (42) ensures
 * deterministic, reproducible datasets across runs.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@State(Scope.Benchmark)
public class IntegerBitmapState {
	/** Number of random values inserted into each bitmap. */
	private static final int VALUE_COUNT = 100_000;
	private static final Random random = new Random(42);

	@Getter private RoaringBitmapBackedBitmap bitmapA;
	@Getter private RoaringBitmapBackedBitmap bitmapB;

	/**
	 * Generates two independent bitmaps with {@link #VALUE_COUNT} random entries each.
	 */
	@Setup(Level.Trial)
	public void setUp() {
		this.bitmapA = generateBitmap(VALUE_COUNT);
		this.bitmapB = generateBitmap(VALUE_COUNT);
	}

	private RoaringBitmapBackedBitmap generateBitmap(int valueCount) {
		final RoaringBitmapWriter<PersistentRoaringBitmap> set = RoaringBitmapWriter.writer().constantMemory().runCompress(false).get();
		for (int i = 0; i < valueCount; i++) {
			set.add(getRandomNumber());
		}

		final PersistentRoaringBitmap roaringBitmap = set.get();
		roaringBitmap.runOptimize();
		return new BaseBitmap(roaringBitmap);
	}

	private int getRandomNumber() {
		return random.nextInt(VALUE_COUNT * 2);
	}

}
