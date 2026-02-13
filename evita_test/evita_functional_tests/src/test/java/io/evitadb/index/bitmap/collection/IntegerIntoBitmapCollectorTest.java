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
 * This test verifies contract of {@link IntegerIntoBitmapCollector}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("IntegerIntoBitmapCollector")
class IntegerIntoBitmapCollectorTest {

	@Nested
	@DisplayName("Singleton instance")
	class SingletonInstanceTest {

		@Test
		@DisplayName("should not be null")
		void shouldNotBeNull() {
			assertNotNull(IntegerIntoBitmapCollector.INSTANCE);
		}
	}

	@Nested
	@DisplayName("Collector contract")
	class CollectorContractTest {

		@Test
		@DisplayName("should supply empty BaseBitmap")
		void shouldSupplyEmptyBaseBitmap() {
			final Bitmap bitmap = IntegerIntoBitmapCollector.INSTANCE.supplier().get();
			assertNotNull(bitmap);
			assertInstanceOf(BaseBitmap.class, bitmap);
			assertTrue(bitmap.isEmpty());
		}

		@Test
		@DisplayName("should accumulate integer into bitmap")
		void shouldAccumulateIntegerIntoBitmap() {
			final Bitmap bitmap = new BaseBitmap();
			IntegerIntoBitmapCollector.INSTANCE.accumulator().accept(bitmap, 42);
			assertEquals(1, bitmap.size());
			assertTrue(bitmap.contains(42));
		}

		@Test
		@DisplayName("should combine two bitmaps")
		void shouldCombineTwoBitmaps() {
			final Bitmap left = new BaseBitmap(1, 2, 3);
			final Bitmap right = new BaseBitmap(4, 5, 6);
			final Bitmap result = IntegerIntoBitmapCollector.INSTANCE.combiner().apply(left, right);
			assertSame(left, result);
			assertEquals(6, result.size());
			assertArrayEquals(new int[]{1, 2, 3, 4, 5, 6}, result.getArray());
		}

		@Test
		@DisplayName("should finish with identity")
		void shouldFinishWithIdentity() {
			final Bitmap bitmap = new BaseBitmap(1, 2, 3);
			final Bitmap finished = IntegerIntoBitmapCollector.INSTANCE.finisher().apply(bitmap);
			assertSame(bitmap, finished);
		}

		@Test
		@DisplayName("should have IDENTITY_FINISH characteristic")
		void shouldHaveIdentityFinishCharacteristic() {
			assertTrue(
				IntegerIntoBitmapCollector.INSTANCE.characteristics()
					.contains(Characteristics.IDENTITY_FINISH)
			);
			assertEquals(1, IntegerIntoBitmapCollector.INSTANCE.characteristics().size());
		}
	}

	@Nested
	@DisplayName("Stream collection")
	class StreamCollectionTest {

		@Test
		@DisplayName("should collect empty stream into empty bitmap")
		void shouldCollectEmptyStream() {
			final Bitmap bitmap = Stream.<Integer>empty()
				.collect(IntegerIntoBitmapCollector.INSTANCE);
			assertNotNull(bitmap);
			assertTrue(bitmap.isEmpty());
		}

		@Test
		@DisplayName("should collect single element")
		void shouldCollectSingleElement() {
			final Bitmap bitmap = Stream.of(42)
				.collect(IntegerIntoBitmapCollector.INSTANCE);
			assertEquals(1, bitmap.size());
			assertArrayEquals(new int[]{42}, bitmap.getArray());
		}

		@Test
		@DisplayName("should collect multiple elements in sorted order")
		void shouldCollectMultipleElements() {
			final Bitmap bitmap = Stream.of(5, 3, 8, 1, 10)
				.collect(IntegerIntoBitmapCollector.INSTANCE);
			assertEquals(5, bitmap.size());
			assertArrayEquals(new int[]{1, 3, 5, 8, 10}, bitmap.getArray());
		}

		@Test
		@DisplayName("should collect duplicate elements without duplicating")
		void shouldCollectDuplicateElements() {
			final Bitmap bitmap = Stream.of(3, 5, 3, 8, 5, 8)
				.collect(IntegerIntoBitmapCollector.INSTANCE);
			assertEquals(3, bitmap.size());
			assertArrayEquals(new int[]{3, 5, 8}, bitmap.getArray());
		}
	}
}
