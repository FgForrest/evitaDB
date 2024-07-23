/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.index.bitmap;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.roaringbitmap.RoaringBitmap;
import org.roaringbitmap.RoaringBitmapWriter;

/**
 * Verifies methods in{@link RoaringBitmapBackedBitmap}
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
class RoaringBitmapBackedBitmapTest {

	@Test
	void shouldExecuteAndOnNegativeBitmaps() {
		final Bitmap result = RoaringBitmapBackedBitmap.and(
			new RoaringBitmap[]{
				creatRoaringBitmap(Integer.MIN_VALUE, -1000, 0, 15, 78),
				creatRoaringBitmap(-1000, 1, 2, 3),
				creatRoaringBitmap(-1000, 1000, Integer.MAX_VALUE)
			}
		);
		Assertions.assertArrayEquals(
			new int[] {-1000},
			result.getArray()
		);
	}

	@Test
	void shouldExecuteAndOnPositiveBitmaps() {
		final Bitmap result = RoaringBitmapBackedBitmap.and(
			new RoaringBitmap[]{
				creatRoaringBitmap(0, 1, 15, 78),
				creatRoaringBitmap(0, 1, 2, 3),
				creatRoaringBitmap(0, 1, 1000, Integer.MAX_VALUE)
			}
		);
		Assertions.assertArrayEquals(
			new int[] {0, 1},
			result.getArray()
		);
	}

	private static RoaringBitmap creatRoaringBitmap(int... ints) {
		final RoaringBitmapWriter<RoaringBitmap> writer = RoaringBitmapBackedBitmap.buildWriter();
		writer.addMany(ints);
		return writer.get();
	}
}
