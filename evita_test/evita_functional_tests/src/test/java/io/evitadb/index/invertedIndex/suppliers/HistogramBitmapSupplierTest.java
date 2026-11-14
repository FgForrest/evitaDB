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

package io.evitadb.index.invertedIndex.suppliers;

import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.invertedIndex.ValueToRecord;
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
import io.evitadb.index.invertedIndex.ValueToRecordPrimitive;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.CACHE;
import static io.evitadb.test.TestTags.INDEXING;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies exact bucket identity, lazy computation and execution costs of histogram suppliers.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Tag(INDEXING)
@Tag(ATTRIBUTE)
@Tag(CACHE)
@DisplayName("HistogramBitmapSupplier formula-cache identity")
class HistogramBitmapSupplierTest {

	/**
	 * Detached supplier keys include values and leaf provenance.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	@Nested
	@DisplayName("Detached supplier keys")
	class KeyTest {
		/** Ordered bucket values affect equality and the hash prefilter. */
		@Test
		@DisplayName("Distinguishes bucket values and order")
		void shouldDistinguishBucketValuesAndOrder() {
			final HistogramBitmapSupplier first = new HistogramBitmapSupplier(1L,
				new ValueToRecord[]{new ValueToRecordPrimitive(5, 1), new ValueToRecordPrimitive(10, 2)});
			final HistogramBitmapSupplier changed = new HistogramBitmapSupplier(1L,
				new ValueToRecord[]{new ValueToRecordPrimitive(7, 1), new ValueToRecordPrimitive(10, 2)});
			final HistogramBitmapSupplier reversed = new HistogramBitmapSupplier(1L,
				new ValueToRecord[]{new ValueToRecordPrimitive(10, 2), new ValueToRecordPrimitive(5, 1)});
			assertNotEquals(first.getKey(), changed.getKey());
			assertNotEquals(first.getKey().getHash64(), changed.getKey().getHash64());
			assertNotEquals(first.getKey(), reversed.getKey());
			assertNotEquals(first.getKey().getHash64(), reversed.getKey().getHash64());
		}

		/** String content remains distinct even when the ordinary string hashes collide. */
		@Test
		@DisplayName("Distinguishes bucket strings with colliding 32-bit hashes")
		void shouldDistinguishCollidingStringValues() {
			assertEquals("Aa".hashCode(), "BB".hashCode());
			final HistogramBitmapSupplier first = new HistogramBitmapSupplier(1L,
				new ValueToRecord[]{new ValueToRecordPrimitive("Aa", 1)});
			final HistogramBitmapSupplier second = new HistogramBitmapSupplier(1L,
				new ValueToRecord[]{new ValueToRecordPrimitive("BB", 1)});
			assertNotEquals(first.getKey(), second.getKey());
			assertNotEquals(first.getKey().getHash64(), second.getKey().getHash64());
		}

		/** A bucket value's type remains part of its detached identity. */
		@Test
		@DisplayName("Distinguishes numeric and string buckets with the same text")
		void shouldDistinguishValueTypesWithSameText() {
			final HistogramBitmapSupplier integral = new HistogramBitmapSupplier(1L,
				new ValueToRecord[]{new ValueToRecordPrimitive(5, 1)});
			final HistogramBitmapSupplier text = new HistogramBitmapSupplier(1L,
				new ValueToRecord[]{new ValueToRecordPrimitive("5", 1)});
			assertNotEquals(integral.getKey(), text.getKey());
			assertNotEquals(integral.getKey().getHash64(), text.getKey().getHash64());
		}

		/** Equal values under different leaf versions describe different computations. */
		@Test
		@DisplayName("Distinguishes leaf version sets")
		void shouldDistinguishLeafVersions() {
			final ValueToRecord[] buckets = {new ValueToRecordPrimitive(5, 1)};
			final HistogramBitmapSupplier first = new HistogramBitmapSupplier(new long[]{7, 9}, buckets);
			final HistogramBitmapSupplier changed = new HistogramBitmapSupplier(new long[]{7, 10}, buckets);
			final HistogramBitmapSupplier shorter = new HistogramBitmapSupplier(new long[]{7}, buckets);
			assertNotEquals(first.getKey(), changed.getKey());
			assertNotEquals(first.getKey().getHash64(), changed.getKey().getHash64());
			assertNotEquals(first.getKey(), shorter.getKey());
			assertEquals(first.getKey(), new HistogramBitmapSupplier(new long[]{7, 9}, buckets).getKey());
		}
	}

	/**
	 * Verifies deferred bucket access and the execution cost independent of the key.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	@Nested
	@DisplayName("Supplier execution and cost")
	class SupplierExecutionTest {

		@Test
		@DisplayName("The estimated cardinality is the sum of the bucket sizes")
		void shouldComputeEstimatedCardinalityAsSumOfBucketSizes() {
			final HistogramBitmapSupplier supplier = new HistogramBitmapSupplier(
				1L,
				new ValueToRecord[]{
					new ValueToRecordPrimitive(5, 1),       // size 1
					new ValueToRecordBitmap(10, 2, 3, 4)    // size 3
				}
			);

			assertEquals(4, supplier.getEstimatedCardinality());
		}

		@Test
		@DisplayName("Construction reads no bucket's record set, so the union really is deferred")
		void shouldNotUnionTheBucketsAtConstruction() {
			// the whole point of wrapping this supplier in a DeferredFormula is that the union is paid only if the
			// formula is actually computed. Reading a bucket's record set at construction defeats that outright - the
			// attribute-histogram path unions the WHOLE index while the plan is still being costed
			final ValueToRecord bucket = mock(ValueToRecord.class);
			when(bucket.getValue()).thenReturn(5);
			when(bucket.size()).thenReturn(3);
			when(bucket.getRecordIds()).thenReturn(new BaseBitmap(1, 2, 3));

			final HistogramBitmapSupplier supplier = new HistogramBitmapSupplier(
				1L, new ValueToRecord[]{bucket}
			);
			verify(bucket, never()).getRecordIds();
			// the cheap facts are still available without the union - they come off `size()`
			assertEquals(3, supplier.getEstimatedCardinality());
			verify(bucket, never()).getRecordIds();

			assertArrayEquals(new int[]{1, 2, 3}, supplier.get().getArray());
			verify(bucket, atLeastOnce()).getRecordIds();
		}

		@Test
		@DisplayName("An empty slice is priced at zero")
		void shouldPriceAnEmptySlice() {
			// A bucket that holds no record id is the reachable shape - the tree deletes drained buckets, but a
			// caller may hand this supplier a freshly built, still-empty one
			final HistogramBitmapSupplier supplier = assertDoesNotThrow(
				() -> new HistogramBitmapSupplier(1L, new ValueToRecord[]{new ValueToRecordBitmap(5)})
			);
			assertTrue(supplier.get().isEmpty());
			assertEquals(0, supplier.getEstimatedCardinality());
			assertEquals(0L, supplier.getEstimatedCost());
			assertEquals(0L, supplier.getCost());
		}
	}

}
