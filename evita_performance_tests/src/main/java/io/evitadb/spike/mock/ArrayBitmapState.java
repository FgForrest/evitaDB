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

import io.evitadb.dataType.array.CompositeIntArray;
import io.evitadb.index.bitmap.ArrayBitmap;
import io.evitadb.index.bitmap.Bitmap;
import lombok.Getter;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.util.Random;

/**
 * No extra information provided - see (selfexplanatory) method signatures.
 * I have the best intention to write more detailed documentation but if you see this, there was not enough time or will to do so.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@State(Scope.Benchmark)
public class ArrayBitmapState {
	private static final int VALUE_COUNT = 100_000;
	private static final Random random = new Random(42);

	@Getter private Bitmap bitmapA;
	@Getter private Bitmap bitmapB;

	/**
	 * This setup is called once for each `valueCount`.
	 */
	@Setup(Level.Trial)
	public void setUp() {
		this.bitmapA = generateBitmap(VALUE_COUNT);
		this.bitmapB = generateBitmap(VALUE_COUNT);
	}

	private Bitmap generateBitmap(int valueCount) {
		final CompositeIntArray intArray = new CompositeIntArray();
		final ArrayBitmap bitmap = new ArrayBitmap(intArray);
		for (int i = 0; i < valueCount; i++) {
			bitmap.add(getRandomNumber());
		}

		return bitmap;
	}

	private int getRandomNumber() {
		return random.nextInt(VALUE_COUNT * 2);
	}

}
