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

package io.evitadb.index.bitmap.collection;

import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.stream.Collector.Characteristics;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies contract of {@link BitmapIntoBitmapCollector}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("BitmapIntoBitmapCollector")
class BitmapIntoBitmapCollectorTest {

	@Nested
	@DisplayName("Singleton instance")
	class SingletonInstanceTest {

		@Test
		@DisplayName("should not be null")
		void shouldNotBeNull() {
			assertNotNull(BitmapIntoBitmapCollector.INSTANCE);
		}
	}

	@Nested
	@DisplayName("Collector contract")
	class CollectorContractTest {

		@Test
		@DisplayName("should supply empty BaseBitmap")
		void shouldSupplyEmptyBaseBitmap() {
			final Bitmap bitmap = BitmapIntoBitmapCollector.INSTANCE.supplier().get();
			assertNotNull(bitmap);
			assertInstanceOf(BaseBitmap.class, bitmap);
			assertTrue(bitmap.isEmpty());
		}

		@Test
		@DisplayName("should accumulate bitmap into bitmap")
		void shouldAccumulateBitmapIntoBitmap() {
			final Bitmap target = new BaseBitmap();
			final Bitmap source = new BaseBitmap(1, 2, 3);
			BitmapIntoBitmapCollector.INSTANCE.accumulator().accept(target, source);
			assertEquals(3, target.size());
			assertArrayEquals(new int[]{1, 2, 3}, target.getArray());
		}

		@Test
		@DisplayName("should combine two bitmaps")
		void shouldCombineTwoBitmaps() {
			final Bitmap left = new BaseBitmap(1, 2, 3);
			final Bitmap right = new BaseBitmap(4, 5, 6);
			final Bitmap result = BitmapIntoBitmapCollector.INSTANCE.combiner().apply(left, right);
			assertSame(left, result);
			assertEquals(6, result.size());
			assertArrayEquals(new int[]{1, 2, 3, 4, 5, 6}, result.getArray());
		}

		@Test
		@DisplayName("should finish with identity")
		void shouldFinishWithIdentity() {
			final Bitmap bitmap = new BaseBitmap(1, 2, 3);
			final Bitmap finished = BitmapIntoBitmapCollector.INSTANCE.finisher().apply(bitmap);
			assertSame(bitmap, finished);
		}

		@Test
		@DisplayName("should have IDENTITY_FINISH characteristic")
		void shouldHaveIdentityFinishCharacteristic() {
			assertTrue(
				BitmapIntoBitmapCollector.INSTANCE.characteristics()
					.contains(Characteristics.IDENTITY_FINISH)
			);
			assertEquals(1, BitmapIntoBitmapCollector.INSTANCE.characteristics().size());
		}
	}

	@Nested
	@DisplayName("Stream collection")
	class StreamCollectionTest {

		@Test
		@DisplayName("should collect empty stream into empty bitmap")
		void shouldCollectEmptyStream() {
			final Bitmap bitmap = Stream.<Bitmap>empty()
				.collect(BitmapIntoBitmapCollector.INSTANCE);
			assertNotNull(bitmap);
			assertTrue(bitmap.isEmpty());
		}

		@Test
		@DisplayName("should collect single bitmap")
		void shouldCollectSingleBitmap() {
			final Bitmap bitmap = Stream.<Bitmap>of(new BaseBitmap(1, 2, 3))
				.collect(BitmapIntoBitmapCollector.INSTANCE);
			assertEquals(3, bitmap.size());
			assertArrayEquals(new int[]{1, 2, 3}, bitmap.getArray());
		}

		@Test
		@DisplayName("should collect multiple bitmaps into merged result")
		void shouldCollectMultipleBitmaps() {
			final Bitmap bitmap = Stream.<Bitmap>of(
				new BaseBitmap(1, 2),
				new BaseBitmap(5, 6),
				new BaseBitmap(10, 11)
			).collect(BitmapIntoBitmapCollector.INSTANCE);
			assertEquals(6, bitmap.size());
			assertArrayEquals(new int[]{1, 2, 5, 6, 10, 11}, bitmap.getArray());
		}

		@Test
		@DisplayName("should collect overlapping bitmaps with shared elements appearing once")
		void shouldCollectOverlappingBitmaps() {
			final Bitmap bitmap = Stream.<Bitmap>of(
				new BaseBitmap(1, 2, 3),
				new BaseBitmap(2, 3, 4),
				new BaseBitmap(3, 4, 5)
			).collect(BitmapIntoBitmapCollector.INSTANCE);
			assertEquals(5, bitmap.size());
			assertArrayEquals(new int[]{1, 2, 3, 4, 5}, bitmap.getArray());
		}
	}
}
