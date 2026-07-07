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

package io.evitadb.index.invertedIndex;

import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.base.OrFormula;
import io.evitadb.index.bitmap.Bitmap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.INDEXING;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies contract of {@link InvertedIndexSubSet}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Tag(INDEXING)
@Tag(ATTRIBUTE)
class InvertedIndexSubTest {
	/**
	 * Two-bucket subset used by non-empty tests.
	 * Buckets: value=1 → records [1, 2], value=5 → records [8, 9].
	 */
	private final InvertedIndexSubSet tested = new InvertedIndexSubSet(
		new long[]{1L},
		new ValueToRecordBitmap[]{
			new ValueToRecordBitmap(1, 1, 2),
			new ValueToRecordBitmap(5, 8, 9),
		},
		(indexTransactionIds, histogramBuckets) -> new OrFormula(
			indexTransactionIds,
			Arrays.stream(histogramBuckets).map(ValueToRecord::getRecordIds).toArray(Bitmap[]::new)
		)
	);

	@Nested
	@DisplayName("Empty subset")
	class EmptySubSetTest {

		private final InvertedIndexSubSet empty = new InvertedIndexSubSet(
			new long[]{1L},
			new ValueToRecordBitmap[0],
			(indexTransactionIds, histogramBuckets) -> EmptyFormula.INSTANCE
		);

		@Test
		@DisplayName("isEmpty returns true for empty subset")
		void shouldBeEmpty() {
			assertTrue(this.empty.isEmpty());
		}

		@Test
		@DisplayName("getMinimalValue returns null for empty subset")
		void shouldReturnNullMinimalValue() {
			assertNull(this.empty.getMinimalValue());
		}

		@Test
		@DisplayName("getMaximalValue returns null for empty subset")
		void shouldReturnNullMaximalValue() {
			assertNull(this.empty.getMaximalValue());
		}

		@Test
		@DisplayName("getRecordIds returns empty bitmap for empty subset")
		void shouldReturnEmptyRecordIds() {
			assertEquals(0, this.empty.getRecordIds().size());
		}

		@Test
		@DisplayName("getFormula returns EmptyFormula for empty subset")
		void shouldReturnEmptyFormula() {
			assertSame(EmptyFormula.INSTANCE, this.empty.getFormula());
		}

		@Test
		@DisplayName("getBuckets returns empty array for empty subset")
		void shouldReturnEmptyBucketsArray() {
			assertEquals(0, this.empty.getBuckets().length);
		}
	}

	@Nested
	@DisplayName("Non-empty subset")
	class NonEmptySubSetTest {

		@Test
		@DisplayName("isEmpty returns false for non-empty subset")
		void shouldNotBeEmpty() {
			assertFalse(InvertedIndexSubTest.this.tested.isEmpty());
		}

		@Test
		@DisplayName("getMinimalValue returns value of the first bucket")
		void shouldReturnMin() {
			assertEquals(1, InvertedIndexSubTest.this.tested.getMinimalValue());
		}

		@Test
		@DisplayName("getMaximalValue returns value of the last bucket")
		void shouldReturnMax() {
			assertEquals(5, InvertedIndexSubTest.this.tested.getMaximalValue());
		}

		@Test
		@DisplayName("getRecordIds returns aggregated and sorted record ids from all buckets")
		void shouldReturnAggregatedRecordIds() {
			assertArrayEquals(new int[]{1, 2, 8, 9}, InvertedIndexSubTest.this.tested.getRecordIds().getArray());
		}

		@Test
		@DisplayName("getBuckets returns the underlying bucket array with correct length")
		void shouldReturnHistogramBuckets() {
			final ValueToRecord[] buckets = InvertedIndexSubTest.this.tested.getBuckets();

			assertEquals(2, buckets.length);
			assertEquals(1, buckets[0].getValue());
			assertEquals(5, buckets[1].getValue());
		}

		@Test
		@DisplayName("getFormula returns the same memoized instance on repeated calls")
		void shouldMemoizeFormula() {
			final Formula first = InvertedIndexSubTest.this.tested.getFormula();
			final Formula second = InvertedIndexSubTest.this.tested.getFormula();

			assertSame(first, second);
		}

		@Test
		@DisplayName("getRecordIds is consistent with repeated calls due to memoization")
		void shouldReturnConsistentRecordIdsOnRepeatedCalls() {
			final int[] first = InvertedIndexSubTest.this.tested.getRecordIds().getArray();
			final int[] second = InvertedIndexSubTest.this.tested.getRecordIds().getArray();

			assertArrayEquals(first, second);
		}
	}

	@Nested
	@DisplayName("Zero-copy bucket read-out")
	class ZeroCopyBucketReadOutTest {
		/**
		 * The compact single-record primitive bucket (value=1 → record 7), kept first in the mixed backing array so a
		 * test can assert it is handed back unchanged, not promoted to a bitmap-backed bucket.
		 */
		private final ValueToRecordPrimitive primitiveBucket = new ValueToRecordPrimitive(1, 7);
		/**
		 * Mixed backing array blending the compact single-record primitive with a multi-record bitmap bucket
		 * (value=5 → records [8, 9]). Held as a field so a test can assert the subset exposes this very array instance.
		 */
		private final ValueToRecord[] mixedBackingArray = new ValueToRecord[]{
			this.primitiveBucket,
			new ValueToRecordBitmap(5, 8, 9),
		};
		/**
		 * Subset over the mixed backing array. The aggregation lambda folds the polymorphic
		 * {@link ValueToRecord#getRecordIds()} views (typed as {@link Bitmap}) so it tolerates both bucket
		 * representations; these tests read only the buckets, so the lambda is never invoked.
		 */
		private final InvertedIndexSubSet subset = new InvertedIndexSubSet(
			new long[]{1L},
			this.mixedBackingArray,
			(indexTransactionIds, histogramBuckets) -> new OrFormula(
				indexTransactionIds,
				Arrays.stream(histogramBuckets).map(ValueToRecord::getRecordIds).toArray(Bitmap[]::new)
			)
		);

		@Test
		@DisplayName("getBuckets returns a single-record primitive bucket without materializing it to a bitmap")
		void shouldReturnPrimitiveBucketUnmaterializedWhenReadingBuckets() {
			final ValueToRecord[] buckets = this.subset.getBuckets();

			final ValueToRecordPrimitive firstBucket = assertInstanceOf(
				ValueToRecordPrimitive.class, buckets[0]
			);
			assertSame(this.primitiveBucket, firstBucket);
			assertEquals(1, firstBucket.getValue());
			assertArrayEquals(new int[]{7}, firstBucket.getRecordIds().getArray());
		}

		@Test
		@DisplayName("getBuckets returns the subset's internal backing array reference")
		void shouldReturnSameBackingArrayInstanceFromGetBuckets() {
			assertSame(this.mixedBackingArray, this.subset.getBuckets());
			assertSame(this.subset.getBuckets(), this.subset.getBuckets());
		}
	}
}
