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
import org.roaringbitmap.RoaringBitmap;
import org.roaringbitmap.RoaringBitmapWriter;

import java.util.Random;

/**
 * No extra information provided - see (selfexplanatory) method signatures.
 * I have the best intention to write more detailed documentation but if you see this, there was not enough time or will to do so.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@State(Scope.Benchmark)
public class IntegerBitmapState {
	private static final int VALUE_COUNT = 100_000;
	private static final Random random = new Random(42);

	@Getter private RoaringBitmapBackedBitmap bitmapA;
	@Getter private RoaringBitmapBackedBitmap bitmapB;

	/**
	 * This setup is called once for each `valueCount`.
	 */
	@Setup(Level.Trial)
	public void setUp() {
		this.bitmapA = generateBitmap(VALUE_COUNT);
		this.bitmapB = generateBitmap(VALUE_COUNT);
	}

	private RoaringBitmapBackedBitmap generateBitmap(int valueCount) {
		final RoaringBitmapWriter<RoaringBitmap> set = RoaringBitmapWriter.writer().constantMemory().runCompress(false).get();
		for (int i = 0; i < valueCount; i++) {
			set.add(getRandomNumber());
		}

		final RoaringBitmap roaringBitmap = set.get();
		roaringBitmap.runOptimize();
		return new BaseBitmap(roaringBitmap);
	}

	private int getRandomNumber() {
		return random.nextInt(VALUE_COUNT * 2);
	}

}
