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

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.dataType.ConsistencySensitiveDataStructure.ConsistencyReport;
import io.evitadb.dataType.ConsistencySensitiveDataStructure.ConsistencyState;
import io.evitadb.comparator.LocalizedStringComparator;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.store.index.serializer.InvertedIndexSerializer;
import io.evitadb.store.index.serializer.TransactionalIntegerBitmapSerializer;
import io.evitadb.store.index.serializer.ValueToRecordBitmapSerializer;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.text.Collator;
import java.util.*;
import java.util.function.Function;

import static io.evitadb.utils.AssertionUtils.assertIteratorContains;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static org.junit.jupiter.api.Assertions.*;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.ATTRIBUTE;

/**
 * This test verifies contract of {@link InvertedIndex} data structure.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2019
 */
@SuppressWarnings("SameParameterValue")
@Tag(INDEXING)
@Tag(ATTRIBUTE)
class InvertedIndexTest implements TimeBoundedTestSupport {
	private final InvertedIndex tested = new InvertedIndex(FilterIndex.NO_NORMALIZATION, Comparator.naturalOrder());

	@BeforeEach
	void setUp() {
		this.tested.addRecord(5, 1);
		this.tested.addRecord(5, 20);
		this.tested.addRecord(10, 3);
		this.tested.addRecord(15, 2);
		this.tested.addRecord(15, 4);
		this.tested.addRecord(20, 5);
	}

	@Nested
	@DisplayName("Construction and initialization")
	class ConstructionTest {

		@Test
		@DisplayName("Empty constructor yields empty index")
		void shouldCreateEmptyIndex() {
			final InvertedIndex empty = new InvertedIndex(
				FilterIndex.NO_NORMALIZATION, Comparator.naturalOrder()
			);

			assertEquals(0, empty.getBucketCount());
			assertEquals(0, empty.getLength());
			assertTrue(empty.isEmpty());
		}

		@Test
		@DisplayName("Pre-populated constructor with sorted buckets succeeds")
		void shouldCreatePrePopulatedIndexWithSortedBuckets() {
			final ValueToRecordBitmap[] buckets = new ValueToRecordBitmap[]{
				new ValueToRecordBitmap(1, 10, 20),
				new ValueToRecordBitmap(5, 30),
				new ValueToRecordBitmap(10, 40, 50)
			};
			final InvertedIndex index = new InvertedIndex(
				buckets, FilterIndex.NO_NORMALIZATION, Comparator.naturalOrder()
			);

			assertEquals(3, index.getBucketCount());
			assertEquals(5, index.getLength());
			assertFalse(index.isEmpty());
		}

		@Test
		@DisplayName("Custom normalizer transforms values during add and query")
		void shouldApplyCustomNormalizerToValues() {
			final Function<Object, Serializable> lowercaseNormalizer =
				obj -> ((String) obj).toLowerCase(Locale.ROOT);
			final InvertedIndex index = new InvertedIndex(
				lowercaseNormalizer, Comparator.naturalOrder()
			);

			index.addRecord("Hello", 1);
			index.addRecord("HELLO", 2);

			// both should land in the same bucket because normalizer lowercases
			assertEquals(1, index.getBucketCount());
			assertEquals(2, index.getLength());
			assertTrue(index.contains("HELLO"));
			assertTrue(index.contains("hello"));
		}

		@Test
		@DisplayName("Custom comparator (reverse order) affects bucket ordering")
		void shouldRespectCustomComparator() {
			final Comparator<Comparable<? super Comparable<?>>> reverseComparator =
				Comparator.reverseOrder();
			final InvertedIndex index = new InvertedIndex(
				FilterIndex.NO_NORMALIZATION, reverseComparator
			);

			index.addRecord(1, 10);
			index.addRecord(5, 20);
			index.addRecord(10, 30);

			// with reverse order, buckets should be [10, 5, 1]
			final ValueToRecordBitmap[] buckets = index.getValueToRecordBitmap();
			assertEquals(3, buckets.length);
			assertEquals(10, buckets[0].getValue());
			assertEquals(5, buckets[1].getValue());
			assertEquals(1, buckets[2].getValue());
		}

		@Test
		@DisplayName("Constructor stores comparator accessible via getComparator()")
		void shouldReturnComparatorPassedInConstructor() {
			final Comparator<?> comparator = Comparator.naturalOrder();
			final InvertedIndex index = new InvertedIndex(FilterIndex.NO_NORMALIZATION, comparator);

			assertSame(comparator, index.getComparator());
		}

		@Test
		@DisplayName("Localized Czech comparator orders buckets differently from natural String order")
		void shouldOrderByLocalizedCzechComparatorNotNaturalStringOrder() {
			// In Czech collation the digraph "ch" sorts as a single letter AFTER "h", so "chladný" sorts last;
			// in natural String order 'c' < 'h' so "chladný" sorts near the front. This proves the comparator
			// is actually driving bucket order (the Phase-1 payoff).
			final InvertedIndex index = new InvertedIndex(
				FilterIndex.NO_NORMALIZATION,
				new LocalizedStringComparator(Collator.getInstance(new Locale("cs", "CZ")))
			);
			index.addRecord("chladný", 1);
			index.addRecord("hora", 2);
			index.addRecord("cibule", 3);
			index.addRecord("auto", 4);

			final ValueToRecordBitmap[] buckets = index.getValueToRecordBitmap();
			assertArrayEquals(
				new Serializable[]{"auto", "cibule", "hora", "chladný"},
				new Serializable[]{
					buckets[0].getValue(), buckets[1].getValue(),
					buckets[2].getValue(), buckets[3].getValue()
				}
			);

			// sanity: natural String ordering would put "chladný" right after "auto"
			final String[] naturalOrder = {"chladný", "hora", "cibule", "auto"};
			Arrays.sort(naturalOrder);
			assertArrayEquals(new String[]{"auto", "chladný", "cibule", "hora"}, naturalOrder);
		}
	}

	@Nested
	@DisplayName("Non-transactional operations")
	class NonTransactionalOperationsTest {

		@Test
		@DisplayName("addRecord with multiple record IDs via vararg")
		void shouldAddRecordWithMultipleIds() {
			final InvertedIndex index = new InvertedIndex(
				FilterIndex.NO_NORMALIZATION, Comparator.naturalOrder()
			);
			index.addRecord(42, 1, 2, 3);

			assertEquals(1, index.getBucketCount());
			assertEquals(3, index.getLength());
			assertArrayEquals(
				new int[]{1, 2, 3},
				index.getSortedRecords().getRecordIds().getArray()
			);
		}

		@Test
		@DisplayName("addRecord throws when record IDs array is empty")
		void shouldThrowWhenAddingEmptyRecordIds() {
			final InvertedIndex index = new InvertedIndex(
				FilterIndex.NO_NORMALIZATION, Comparator.naturalOrder()
			);

			assertThrows(
				EvitaInvalidUsageException.class,
				() -> index.addRecord(1, new int[0])
			);
		}

		@Test
		@DisplayName("addRecord inserts buckets in comparator order regardless of insertion sequence")
		void shouldInsertBucketsInComparatorOrder() {
			final InvertedIndex index = new InvertedIndex(
				FilterIndex.NO_NORMALIZATION, Comparator.naturalOrder()
			);

			index.addRecord(10, 1);
			index.addRecord(5, 2);
			index.addRecord(20, 3);
			// add to the already existing bucket for value 10
			index.addRecord(10, 4);

			final ValueToRecordBitmap[] buckets = index.getValueToRecordBitmap();
			assertEquals(3, buckets.length);
			assertEquals(5, buckets[0].getValue());
			assertEquals(10, buckets[1].getValue());
			assertEquals(20, buckets[2].getValue());
			assertArrayEquals(new int[]{1, 4}, buckets[1].getRecordIds().getArray());
		}

		@Test
		@DisplayName("removeRecord with multiple record IDs removes all at once")
		void shouldRemoveMultipleRecordIdsAtOnce() {
			// bucket 5 has [1, 20]; remove both in a single call
			InvertedIndexTest.this.tested.removeRecord(5, 1, 20);

			final ValueToRecordBitmap[] buckets = InvertedIndexTest.this.tested.getValueToRecordBitmap();
			// bucket 5 should be gone since all records were removed
			assertEquals(3, buckets.length);
			assertEquals(10, buckets[0].getValue());
		}

		@Test
		@DisplayName("removeRecord on missing value is a silent no-op")
		void shouldSilentlyIgnoreRemovalOfMissingValue() {
			final int bucketCountBefore = InvertedIndexTest.this.tested.getBucketCount();

			InvertedIndexTest.this.tested.removeRecord(999, 1);

			assertEquals(bucketCountBefore, InvertedIndexTest.this.tested.getBucketCount());
			assertFalse(InvertedIndexTest.this.tested.contains(999));
		}

		@Test
		@DisplayName("removeRecord on non-existent record ID in existing bucket is silently skipped")
		void shouldSilentlySkipRemovalOfNonExistentRecordId() {
			// bucket with value 5 has records [1, 20] — removing 99 should not change it
			InvertedIndexTest.this.tested.removeRecord(5, 99);

			final ValueToRecordBitmap[] buckets = InvertedIndexTest.this.tested.getValueToRecordBitmap();
			// bucket for value 5 should still have records 1 and 20
			assertEquals(5, buckets[0].getValue());
			assertArrayEquals(new int[]{1, 20}, buckets[0].getRecordIds().getArray());
		}

		@Test
		@DisplayName("isEmpty returns false when records exist and true after all records removed")
		void shouldReportEmptyState() {
			assertFalse(InvertedIndexTest.this.tested.isEmpty());

			InvertedIndexTest.this.tested.removeRecord(5, 1);
			InvertedIndexTest.this.tested.removeRecord(5, 20);
			InvertedIndexTest.this.tested.removeRecord(10, 3);
			InvertedIndexTest.this.tested.removeRecord(15, 2);
			InvertedIndexTest.this.tested.removeRecord(15, 4);

			assertFalse(InvertedIndexTest.this.tested.isEmpty());

			InvertedIndexTest.this.tested.removeRecord(20, 5);

			assertTrue(InvertedIndexTest.this.tested.isEmpty());
		}

		@Test
		@DisplayName("contains(null) returns false")
		void shouldReturnFalseForContainsNull() {
			assertFalse(InvertedIndexTest.this.tested.contains(null));
		}

		@Test
		@DisplayName("contains returns true for present value")
		void shouldReturnTrueForPresentValue() {
			assertTrue(InvertedIndexTest.this.tested.contains(5));
			assertTrue(InvertedIndexTest.this.tested.contains(10));
			assertTrue(InvertedIndexTest.this.tested.contains(15));
			assertTrue(InvertedIndexTest.this.tested.contains(20));
		}

		@Test
		@DisplayName("contains returns false for absent value")
		void shouldReturnFalseForAbsentValue() {
			assertFalse(InvertedIndexTest.this.tested.contains(7));
			assertFalse(InvertedIndexTest.this.tested.contains(100));
		}

		@Test
		@DisplayName("getValueIteratorFrom anchors at the first bucket >= the given value")
		void shouldIterateBucketsFromGivenValue() {
			// buckets are 5, 10, 15, 20; starting from 10 should yield 10, 15, 20
			final Iterator<ValueToRecord> it = InvertedIndexTest.this.tested.getValueIteratorFrom(10);
			final List<Serializable> values = new ArrayList<>();
			while (it.hasNext()) {
				values.add(it.next().getValue());
			}
			assertEquals(List.of(10, 15, 20), values);

			// a value between buckets (12) should anchor at the next greater bucket (15)
			final Iterator<ValueToRecord> it2 = InvertedIndexTest.this.tested.getValueIteratorFrom(12);
			assertEquals(15, it2.next().getValue());
		}

		@Test
		@DisplayName("getValueIteratorFrom respects localized Czech collation order")
		void shouldIterateBucketsFromGivenValueWithLocalizedComparator() {
			final InvertedIndex index = new InvertedIndex(
				FilterIndex.NO_NORMALIZATION,
				new LocalizedStringComparator(Collator.getInstance(new Locale("cs", "CZ")))
			);
			index.addRecord("chladný", 1);
			index.addRecord("hora", 2);
			index.addRecord("cibule", 3);
			index.addRecord("auto", 4);

			// under cs-CZ collation "ch" sorts after "h" so the run from "hora" is [hora, chladný]
			final Iterator<ValueToRecord> it = index.getValueIteratorFrom("hora");
			final List<Serializable> values = new ArrayList<>();
			while (it.hasNext()) {
				values.add(it.next().getValue());
			}
			assertEquals(List.of("hora", "chladný"), values);
		}

		@Test
		@DisplayName("getRecordsEqualTo with present value returns correct bitmap")
		void shouldReturnRecordsForPresentValue() {
			final Bitmap records = InvertedIndexTest.this.tested.getRecordsEqualTo(5);

			assertArrayEquals(new int[]{1, 20}, records.getArray());
		}

		@Test
		@DisplayName("getRecordsEqualTo with absent value returns EmptyBitmap")
		void shouldReturnEmptyBitmapForAbsentValue() {
			final Bitmap records = InvertedIndexTest.this.tested.getRecordsEqualTo(999);

			assertSame(EmptyBitmap.INSTANCE, records);
		}

		@Test
		@DisplayName("getBucketCount returns correct count")
		void shouldReturnCorrectBucketCount() {
			assertEquals(4, InvertedIndexTest.this.tested.getBucketCount());
		}

		@Test
		@DisplayName("getLength returns total record count across all buckets")
		void shouldReturnTotalRecordCount() {
			// buckets: [5: {1,20}], [10: {3}], [15: {2,4}], [20: {5}] => 6 records
			assertEquals(6, InvertedIndexTest.this.tested.getLength());
		}

		@Test
		@DisplayName("resetDirty clears dirty flag so committed copy is same instance")
		void shouldResetDirtyFlagSoCommittedCopyIsSameInstance() {
			final InvertedIndex index = new InvertedIndex(
				FilterIndex.NO_NORMALIZATION, Comparator.naturalOrder()
			);
			index.addRecord(1, 10);
			index.resetDirty();

			assertStateAfterCommit(
				index,
				original -> {
					// no modifications inside transaction
				},
				Assertions::assertSame
			);
		}
	}

	@Nested
	@DisplayName("Transactional operations")
	class TransactionalOperationsTest {

		@Test
		@DisplayName("Adding records in transaction and rolling back restores original state")
		void shouldAddTransactionalItemsAndRollback() {
			assertStateAfterRollback(
				InvertedIndexTest.this.tested,
				original -> {
					original.addRecord(5, 7);
					original.addRecord(12, 18);
					original.addRecord(1, 10);
					original.addRecord(20, 11);

					assertArrayEquals(
						new ValueToRecordBitmap[]{
							new ValueToRecordBitmap(1, 10),
							new ValueToRecordBitmap(5, 1, 7, 20),
							new ValueToRecordBitmap(10, 3),
							new ValueToRecordBitmap(12, 18),
							new ValueToRecordBitmap(15, 2, 4),
							new ValueToRecordBitmap(20, 5, 11)
						},
						original.getValueToRecordBitmap()
					);
				},
				(original, committed) -> {
					assertNull(committed);
					assertArrayEquals(
						new ValueToRecordBitmap[]{
							new ValueToRecordBitmap(5, 1, 20),
							new ValueToRecordBitmap(10, 3),
							new ValueToRecordBitmap(15, 2, 4),
							new ValueToRecordBitmap(20, 5)
						},
						original.getValueToRecordBitmap()
					);
				}
			);
		}

		@Test
		@DisplayName("Adding a single new record in transaction and committing creates correct state")
		void shouldAddSingleNewTransactionalItemAndCommit() {
			assertStateAfterCommit(
				InvertedIndexTest.this.tested,
				original -> {
					original.addRecord(55, 78);

					assertArrayEquals(
						new ValueToRecordBitmap[]{
							new ValueToRecordBitmap(5, 1, 20),
							new ValueToRecordBitmap(10, 3),
							new ValueToRecordBitmap(15, 2, 4),
							new ValueToRecordBitmap(20, 5),
							new ValueToRecordBitmap(55, 78)
						},
						original.getValueToRecordBitmap()
					);
				},
				(original, committed) -> {
					assertArrayEquals(
						new ValueToRecordBitmap[]{
							new ValueToRecordBitmap(5, 1, 20),
							new ValueToRecordBitmap(10, 3),
							new ValueToRecordBitmap(15, 2, 4),
							new ValueToRecordBitmap(20, 5)
						},
						original.getValueToRecordBitmap()
					);
					assertArrayEquals(
						new ValueToRecordBitmap[]{
							new ValueToRecordBitmap(5, 1, 20),
							new ValueToRecordBitmap(10, 3),
							new ValueToRecordBitmap(15, 2, 4),
							new ValueToRecordBitmap(20, 5),
							new ValueToRecordBitmap(55, 78)
						},
						committed.getValueToRecordBitmap()
					);
				}
			);
		}

		@Test
		@DisplayName("Removing a single record in transaction and committing creates correct state")
		void shouldRemoveSingleTransactionalItemAndCommit() {
			assertStateAfterCommit(
				InvertedIndexTest.this.tested,
				original -> {
					original.removeRecord(10, 3);

					assertArrayEquals(
						new ValueToRecordBitmap[]{
							new ValueToRecordBitmap(5, 1, 20),
							new ValueToRecordBitmap(15, 2, 4),
							new ValueToRecordBitmap(20, 5)
						},
						original.getValueToRecordBitmap()
					);
				},
				(original, committed) -> {
					assertArrayEquals(
						new ValueToRecordBitmap[]{
							new ValueToRecordBitmap(5, 1, 20),
							new ValueToRecordBitmap(10, 3),
							new ValueToRecordBitmap(15, 2, 4),
							new ValueToRecordBitmap(20, 5)
						},
						original.getValueToRecordBitmap()
					);
					assertArrayEquals(
						new ValueToRecordBitmap[]{
							new ValueToRecordBitmap(5, 1, 20),
							new ValueToRecordBitmap(15, 2, 4),
							new ValueToRecordBitmap(20, 5)
						},
						committed.getValueToRecordBitmap()
					);
				}
			);
		}

		@Test
		@DisplayName("Adding multiple records in transaction and committing creates correct state")
		void shouldAddTransactionalItemsAndCommit() {
			assertStateAfterCommit(
				InvertedIndexTest.this.tested,
				original -> {
					original.addRecord(5, 7);
					original.addRecord(12, 18);
					original.addRecord(1, 10);
					original.addRecord(20, 11);

					assertArrayEquals(
						new ValueToRecordBitmap[]{
							new ValueToRecordBitmap(1, 10),
							new ValueToRecordBitmap(5, 1, 7, 20),
							new ValueToRecordBitmap(10, 3),
							new ValueToRecordBitmap(12, 18),
							new ValueToRecordBitmap(15, 2, 4),
							new ValueToRecordBitmap(20, 5, 11)
						},
						original.getValueToRecordBitmap()
					);
				},
				(original, committed) -> {
					assertArrayEquals(
						new ValueToRecordBitmap[]{
							new ValueToRecordBitmap(5, 1, 20),
							new ValueToRecordBitmap(10, 3),
							new ValueToRecordBitmap(15, 2, 4),
							new ValueToRecordBitmap(20, 5)
						},
						original.getValueToRecordBitmap()
					);
					assertArrayEquals(
						new ValueToRecordBitmap[]{
							new ValueToRecordBitmap(1, 10),
							new ValueToRecordBitmap(5, 1, 7, 20),
							new ValueToRecordBitmap(10, 3),
							new ValueToRecordBitmap(12, 18),
							new ValueToRecordBitmap(15, 2, 4),
							new ValueToRecordBitmap(20, 5, 11)
						},
						committed.getValueToRecordBitmap()
					);
				}
			);
		}

		@Test
		@DisplayName("Adding and removing same items in transaction results in empty committed state")
		void shouldAddAndRemoveItemsInTransaction() {
			assertStateAfterCommit(
				new InvertedIndex(FilterIndex.NO_NORMALIZATION, Comparator.naturalOrder()),
				original -> {
					original.addRecord(5, 7);
					original.addRecord(12, 18);
					original.removeRecord(5, 7);
					original.removeRecord(12, 18);

					assertArrayEquals(
						new ValueToRecordBitmap[0],
						original.getValueToRecordBitmap()
					);
				},
				(original, committed) -> {
					assertArrayEquals(
						new ValueToRecordBitmap[0],
						original.getValueToRecordBitmap()
					);
					assertArrayEquals(
						new ValueToRecordBitmap[0],
						committed.getValueToRecordBitmap()
					);
				}
			);
		}

		@Test
		@DisplayName("Removing records in transaction shrinks committed histogram")
		void shouldShrinkHistogramOnRemovingItems() {
			assertStateAfterCommit(
				InvertedIndexTest.this.tested,
				original -> {
					original.removeRecord(5, 1);
					original.removeRecord(10, 3);
					original.removeRecord(20, 5);

					assertArrayEquals(
						new ValueToRecordBitmap[]{
							new ValueToRecordBitmap(5, 20),
							new ValueToRecordBitmap(15, 2, 4)
						},
						original.getValueToRecordBitmap()
					);
				},
				(original, committed) -> {
					assertArrayEquals(
						new ValueToRecordBitmap[]{
							new ValueToRecordBitmap(5, 1, 20),
							new ValueToRecordBitmap(10, 3),
							new ValueToRecordBitmap(15, 2, 4),
							new ValueToRecordBitmap(20, 5)
						},
						original.getValueToRecordBitmap()
					);
					assertArrayEquals(
						new ValueToRecordBitmap[]{
							new ValueToRecordBitmap(5, 20),
							new ValueToRecordBitmap(15, 2, 4)
						},
						committed.getValueToRecordBitmap()
					);
				}
			);
		}

		@Test
		@DisplayName("isEmpty reports correctly even inside a transaction")
		void shouldReportEmptyStateEvenInTransaction() {
			assertStateAfterCommit(
				InvertedIndexTest.this.tested,
				original -> {
					assertFalse(original.isEmpty());

					original.removeRecord(5, 1);
					original.removeRecord(5, 20);
					original.removeRecord(10, 3);
					original.removeRecord(15, 2);
					original.removeRecord(15, 4);

					assertFalse(original.isEmpty());

					original.removeRecord(20, 5);

					assertTrue(original.isEmpty());
				},
				(original, committed) -> {
					assertFalse(original.isEmpty());
					assertTrue(committed.isEmpty());
				}
			);
		}
	}

	@Nested
	@DisplayName("STM invariants")
	class StmInvariantsTest {

		@Test
		@DisplayName("INV-1: getId() returns constant 1L")
		void shouldReturnConstantIdOfOne() {
			assertEquals(
				1L,
				InvertedIndexTest.this.tested.getId()
			);
		}

		@Test
		@DisplayName("INV-5/INV-10: removeLayer cascades to nested structures")
		void shouldRemoveLayerCascadeToNestedStructures() {
			assertStateAfterRollback(
				InvertedIndexTest.this.tested,
				original -> {
					original.addRecord(99, 77);

					// verify the record is visible in the transaction
					assertTrue(original.contains(99));
				},
				(original, committed) -> {
					// after rollback, committed is null for VoidTransactionMemoryProducer
					assertNull(committed);
					// the transactional changes should have been removed
					assertFalse(original.contains(99));
				}
			);
		}

		@Test
		@DisplayName("INV-6: non-dirty index returns same instance on commit")
		void shouldReturnSameInstanceWhenNotDirty() {
			final InvertedIndex index = new InvertedIndex(
				FilterIndex.NO_NORMALIZATION, Comparator.naturalOrder()
			);
			index.addRecord(1, 10);
			index.resetDirty();

			assertStateAfterCommit(
				index,
				original -> {
					// do nothing - index stays non-dirty
				},
				Assertions::assertSame
			);
		}

		@Test
		@DisplayName("INV-7: dirty index returns new instance on commit")
		void shouldReturnNewInstanceWhenDirty() {
			assertStateAfterCommit(
				InvertedIndexTest.this.tested,
				original -> original.addRecord(99, 77),
				(original, committed) -> {
					assertNotSame(original, committed);
					// committed should have the new record
					assertTrue(committed.contains(99));
				}
			);
		}

		@Test
		@DisplayName("T6: removing a bucket to empty inside a transaction commits cleanly (layer fully swept)")
		void shouldRemoveBucketToEmptyInsideTransactionAndCommitCleanly() {
			assertStateAfterCommit(
				InvertedIndexTest.this.tested,
				original -> {
					// bucket for value 10 holds only record 3; removing it must delete the whole bucket
					original.removeRecord(10, 3);
					assertFalse(original.contains(10));
				},
				(original, committed) -> {
					// original is unchanged, committed has the bucket gone - and the value's transactional
					// layer must have been fully swept (assertStateAfterCommit runs verifyLayerWasFullySwept)
					assertTrue(original.contains(10));
					assertFalse(committed.contains(10));
					assertArrayEquals(
						new ValueToRecordBitmap[]{
							new ValueToRecordBitmap(5, 1, 20),
							new ValueToRecordBitmap(15, 2, 4),
							new ValueToRecordBitmap(20, 5)
						},
						committed.getValueToRecordBitmap()
					);
				}
			);
		}

		@Test
		@DisplayName("T6: modifying then removing a bucket to empty in one transaction sweeps cleanly")
		void shouldModifyThenRemoveBucketToEmptyInOneTransaction() {
			assertStateAfterCommit(
				InvertedIndexTest.this.tested,
				original -> {
					// modify the bucket (touches its transactional bitmap layer) then delete it entirely
					original.addRecord(10, 30);
					original.removeRecord(10, 3, 30);
					assertFalse(original.contains(10));
				},
				(original, committed) -> {
					assertTrue(original.contains(10));
					assertFalse(committed.contains(10));
				}
			);
		}

		@Test
		@DisplayName("T9: value iterator reflects in-transaction additions and removals")
		void shouldReflectInTransactionChangesInValueIterator() {
			assertStateAfterCommit(
				InvertedIndexTest.this.tested,
				original -> {
					original.addRecord(7, 70);
					original.removeRecord(20, 5);

					// the bounded range iteration used by getSortedRecords must observe the in-txn state
					assertIteratorContains(
						original.getSortedRecords(5, 15).getRecordIds().iterator(),
						new int[]{1, 2, 3, 4, 20, 70}
					);
					assertTrue(original.contains(7));
					assertFalse(original.contains(20));
				},
				(original, committed) -> {
					assertFalse(original.contains(7));
					assertTrue(original.contains(20));
					assertTrue(committed.contains(7));
					assertFalse(committed.contains(20));
				}
			);
		}

		@Test
		@DisplayName("T2: original dirty flag stays false after commit")
		void shouldKeepOriginalDirtyFlagFalseAfterCommit() {
			final InvertedIndex index = new InvertedIndex(
				FilterIndex.NO_NORMALIZATION, Comparator.naturalOrder()
			);
			index.addRecord(1, 10);
			index.resetDirty();

			assertStateAfterCommit(
				index,
				original -> original.addRecord(2, 20),
				(original, committed) -> {
					// original should not be dirty after commit
					// verify by doing another commit without modifications
					assertStateAfterCommit(
						original,
						innerOriginal -> {
							// no modifications
						},
						Assertions::assertSame
					);
				}
			);
		}
	}

	@Nested
	@DisplayName("Query methods")
	class QueryMethodsTest {

		@Test
		@DisplayName("getSortedRecords returns all record ids sorted by record id value")
		void shouldReturnSortedAllValues() {
			assertIteratorContains(InvertedIndexTest.this.tested.getSortedRecords().getRecordIds().iterator(), new int[]{1, 2, 3, 4, 5, 20});
		}

		@Test
		@DisplayName("getSortedRecords with lower bound returns records with value >= bound")
		void shouldReturnSortedValuesFromLowerBoundUp() {
			assertIteratorContains(InvertedIndexTest.this.tested.getSortedRecords(10, null).getRecordIds().iterator(), new int[]{2, 3, 4, 5});
		}

		@Test
		@DisplayName("getSortedRecords with non-exact lower bound starts from next bucket")
		void shouldReturnSortedValuesFromLowerBoundUpNotExact() {
			assertIteratorContains(InvertedIndexTest.this.tested.getSortedRecords(11, null).getRecordIds().iterator(), new int[]{2, 4, 5});
		}

		@Test
		@DisplayName("getSortedRecords with upper bound returns records with value <= bound")
		void shouldReturnSortedValuesFromUpperBoundDown() {
			assertIteratorContains(InvertedIndexTest.this.tested.getSortedRecords(null, 15).getRecordIds().iterator(), new int[]{1, 2, 3, 4, 20});
		}

		@Test
		@DisplayName("getSortedRecords with non-exact upper bound stops at previous bucket")
		void shouldReturnSortedValuesFromUpperBoundDownNotExact() {
			assertIteratorContains(InvertedIndexTest.this.tested.getSortedRecords(null, 14).getRecordIds().iterator(), new int[]{1, 3, 20});
		}

		@Test
		@DisplayName("getSortedRecords with both bounds returns records in value range")
		void shouldReturnSortedValuesBetweenBounds() {
			assertIteratorContains(InvertedIndexTest.this.tested.getSortedRecords(10, 15).getRecordIds().iterator(), new int[]{2, 3, 4});
		}

		@Test
		@DisplayName("getSortedRecords with non-exact bounds returns only matching bucket records")
		void shouldReturnSortedValuesBetweenBoundsNotExact() {
			assertIteratorContains(InvertedIndexTest.this.tested.getSortedRecords(11, 14).getRecordIds().iterator(), new int[0]);
			assertIteratorContains(InvertedIndexTest.this.tested.getSortedRecords(14, 16).getRecordIds().iterator(), new int[]{2, 4});
			assertIteratorContains(InvertedIndexTest.this.tested.getSortedRecords(15, 15).getRecordIds().iterator(), new int[]{2, 4});
		}

		@Test
		@DisplayName("getRecords returns all record ids in bucket insertion order")
		void shouldReturnAllValues() {
			assertIteratorContains(InvertedIndexTest.this.tested.getRecords().getRecordIds().iterator(), new int[]{1, 20, 3, 2, 4, 5});
		}

		@Test
		@DisplayName("getRecords with lower bound returns records from matching bucket onward")
		void shouldReturnValuesFromLowerBoundUp() {
			assertIteratorContains(InvertedIndexTest.this.tested.getRecords(10, null).getRecordIds().iterator(), new int[]{3, 2, 4, 5});
		}

		@Test
		@DisplayName("getRecords with non-exact lower bound starts from next bucket")
		void shouldReturnValuesFromLowerBoundUpNotExact() {
			assertIteratorContains(InvertedIndexTest.this.tested.getRecords(11, null).getRecordIds().iterator(), new int[]{2, 4, 5});
		}

		@Test
		@DisplayName("getRecords with upper bound returns records up to matching bucket")
		void shouldReturnValuesFromUpperBoundDown() {
			assertIteratorContains(InvertedIndexTest.this.tested.getRecords(null, 15).getRecordIds().iterator(), new int[]{1, 20, 3, 2, 4});
		}

		@Test
		@DisplayName("getRecords with non-exact upper bound stops at previous bucket")
		void shouldReturnValuesFromUpperBoundDownNotExact() {
			assertIteratorContains(InvertedIndexTest.this.tested.getRecords(null, 14).getRecordIds().iterator(), new int[]{1, 20, 3});
		}

		@Test
		@DisplayName("getRecords with both bounds returns records in value range")
		void shouldReturnValuesBetweenBounds() {
			assertIteratorContains(InvertedIndexTest.this.tested.getRecords(10, 15).getRecordIds().iterator(), new int[]{3, 2, 4});
		}

		@Test
		@DisplayName("getRecords with non-exact bounds returns only matching bucket records")
		void shouldReturnValuesBetweenBoundsNotExact() {
			assertIteratorContains(InvertedIndexTest.this.tested.getRecords(11, 14).getRecordIds().iterator(), new int[0]);
			assertIteratorContains(InvertedIndexTest.this.tested.getRecords(14, 16).getRecordIds().iterator(), new int[]{2, 4});
			assertIteratorContains(InvertedIndexTest.this.tested.getRecords(15, 15).getRecordIds().iterator(), new int[]{2, 4});
		}

		@Test
		@DisplayName("getSortedRecords and getRecords return same records but in different orders")
		void shouldReturnDistinctOrderingForSortedVsUnsorted() {
			// unsorted follows bucket order: bucket 5 -> [1,20], bucket 10 -> [3], bucket 15 -> [2,4], bucket 20 -> [5]
			assertIteratorContains(
				InvertedIndexTest.this.tested.getRecords().getRecordIds().iterator(),
				new int[]{1, 20, 3, 2, 4, 5}
			);
			// sorted orders by record id ascending
			assertIteratorContains(
				InvertedIndexTest.this.tested.getSortedRecords().getRecordIds().iterator(),
				new int[]{1, 2, 3, 4, 5, 20}
			);
		}

		@Test
		@DisplayName("getSortedRecordsExclusive excludes exact match on both bounds")
		void shouldExcludeExactMatchOnBothBounds() {
			// index has buckets: 5, 10, 15, 20
			// exclusive(5, 20) should exclude 5 and 20, returning only 10 and 15
			final InvertedIndexSubSet subset =
				InvertedIndexTest.this.tested.getSortedRecordsExclusive(5, 20);

			assertArrayEquals(
				new int[]{2, 3, 4},
				subset.getRecordIds().getArray()
			);
		}

		@Test
		@DisplayName("getSortedRecordsExclusive with both bounds null returns all")
		void shouldReturnAllWhenBothBoundsNull() {
			final InvertedIndexSubSet subset =
				InvertedIndexTest.this.tested.getSortedRecordsExclusive(
					null, null
				);

			assertArrayEquals(
				new int[]{1, 2, 3, 4, 5, 20},
				subset.getRecordIds().getArray()
			);
		}

		@Test
		@DisplayName("getSortedRecordsExclusive with lower bound only")
		void shouldExcludeLowerBoundOnly() {
			// exclusive(10, null) excludes value 10, includes 15 and 20
			final InvertedIndexSubSet subset =
				InvertedIndexTest.this.tested.getSortedRecordsExclusive(
					10, null
				);

			assertArrayEquals(
				new int[]{2, 4, 5},
				subset.getRecordIds().getArray()
			);
		}

		@Test
		@DisplayName("getSortedRecordsExclusive with upper bound only")
		void shouldExcludeUpperBoundOnly() {
			// exclusive(null, 15) excludes value 15, includes 5 and 10
			final InvertedIndexSubSet subset =
				InvertedIndexTest.this.tested.getSortedRecordsExclusive(
					null, 15
				);

			assertArrayEquals(
				new int[]{1, 3, 20},
				subset.getRecordIds().getArray()
			);
		}

		@Test
		@DisplayName("getRecords on empty index returns empty subset")
		void shouldReturnEmptySubsetFromEmptyIndex() {
			final InvertedIndex empty = new InvertedIndex(
				FilterIndex.NO_NORMALIZATION, Comparator.naturalOrder()
			);

			final InvertedIndexSubSet subset = empty.getRecords();

			assertTrue(subset.isEmpty());
			assertEquals(0, subset.getRecordIds().size());
		}

		@Test
		@DisplayName("getSortedRecords on empty index returns empty subset")
		void shouldReturnEmptySortedSubsetFromEmptyIndex() {
			final InvertedIndex empty = new InvertedIndex(
				FilterIndex.NO_NORMALIZATION, Comparator.naturalOrder()
			);

			final InvertedIndexSubSet subset = empty.getSortedRecords();

			assertTrue(subset.isEmpty());
			assertEquals(0, subset.getRecordIds().size());
		}

		@Test
		@DisplayName("InvertedIndexSubSet.getMinimalValue returns correct value")
		void shouldReturnMinimalValueFromSubset() {
			final InvertedIndexSubSet subset =
				InvertedIndexTest.this.tested.getSortedRecords(10, 20);

			assertEquals(10, subset.getMinimalValue());
		}

		@Test
		@DisplayName("InvertedIndexSubSet.getMaximalValue returns correct value")
		void shouldReturnMaximalValueFromSubset() {
			final InvertedIndexSubSet subset =
				InvertedIndexTest.this.tested.getSortedRecords(10, 20);

			assertEquals(20, subset.getMaximalValue());
		}

		@Test
		@DisplayName("InvertedIndexSubSet.getMinimalValue returns null when empty")
		void shouldReturnNullMinimalValueWhenEmpty() {
			final InvertedIndex empty = new InvertedIndex(
				FilterIndex.NO_NORMALIZATION, Comparator.naturalOrder()
			);

			assertNull(empty.getSortedRecords().getMinimalValue());
		}

		@Test
		@DisplayName("InvertedIndexSubSet.getMaximalValue returns null when empty")
		void shouldReturnNullMaximalValueWhenEmpty() {
			final InvertedIndex empty = new InvertedIndex(
				FilterIndex.NO_NORMALIZATION, Comparator.naturalOrder()
			);

			assertNull(empty.getSortedRecords().getMaximalValue());
		}

		@Test
		@DisplayName("InvertedIndexSubSet.isEmpty returns true for empty")
		void shouldReturnTrueForEmptySubset() {
			final InvertedIndex empty = new InvertedIndex(
				FilterIndex.NO_NORMALIZATION, Comparator.naturalOrder()
			);

			assertTrue(empty.getSortedRecords().isEmpty());
		}

		@Test
		@DisplayName("InvertedIndexSubSet.isEmpty returns false for non-empty")
		void shouldReturnFalseForNonEmptySubset() {
			assertFalse(
				InvertedIndexTest.this.tested.getSortedRecords().isEmpty()
			);
		}

		@Test
		@DisplayName("InvertedIndexSubSet.getFormula returns memoized instance")
		void shouldReturnMemoizedFormula() {
			final InvertedIndexSubSet subset =
				InvertedIndexTest.this.tested.getSortedRecords();

			final Formula firstCall = subset.getFormula();
			final Formula secondCall = subset.getFormula();

			assertSame(firstCall, secondCall);
		}

		@Test
		@DisplayName("HistogramBounds guard: moreThanEq > lessThanEq throws")
		void shouldThrowWhenLowerBoundGreaterThanUpperBound() {
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> InvertedIndexTest.this.tested.getSortedRecords(20, 5)
			);
		}
	}

	@Nested
	@DisplayName("Serialization")
	class SerializationTest {

		@Test
		@DisplayName("Serialized and deserialized index equals original")
		void shouldSerializeAndDeserialize() {
			final Kryo kryo = new Kryo();

			kryo.register(InvertedIndex.class, new InvertedIndexSerializer());
			kryo.register(ValueToRecordBitmap.class, new ValueToRecordBitmapSerializer());
			kryo.register(TransactionalBitmap.class, new TransactionalIntegerBitmapSerializer());

			final Output output = new Output(1024, -1);
			kryo.writeObject(output, InvertedIndexTest.this.tested);
			output.flush();

			final byte[] bytes = output.getBuffer();

			final InvertedIndex deserializedTested = kryo.readObject(new Input(bytes), InvertedIndex.class);
			assertEquals(InvertedIndexTest.this.tested, deserializedTested);
		}
	}

	@Nested
	@DisplayName("Other operations")
	class OtherOperationsTest {

		@Test
		@DisplayName("getValuesForRecord returns all values associated with a record across buckets")
		void shouldReturnValuesForRecord() {
			InvertedIndexTest.this.tested.addRecord(50, 1);
			InvertedIndexTest.this.tested.addRecord(100, 3);

			assertArrayEquals(new Integer[]{5, 50}, InvertedIndexTest.this.tested.getValuesForRecord(1, Integer.class));
			assertArrayEquals(new Integer[]{10, 100}, InvertedIndexTest.this.tested.getValuesForRecord(3, Integer.class));
		}

		@Test
		@DisplayName("getValuesForRecord returns empty array for non-existent record")
		void shouldReturnEmptyArrayForNonExistentRecord() {
			final Integer[] values =
				InvertedIndexTest.this.tested.getValuesForRecord(
					999, Integer.class
				);

			assertEquals(0, values.length);
		}

		@Test
		@DisplayName("getValuesForRecord on empty index returns empty array")
		void shouldReturnEmptyArrayFromEmptyIndex() {
			final InvertedIndex empty = new InvertedIndex(
				FilterIndex.NO_NORMALIZATION, Comparator.naturalOrder()
			);

			final Integer[] values = empty.getValuesForRecord(
				1, Integer.class
			);

			assertEquals(0, values.length);
		}

		@Test
		@DisplayName("getConsistencyReport returns CONSISTENT for valid index")
		void shouldReturnConsistentReport() {
			final ConsistencyReport report =
				InvertedIndexTest.this.tested.getConsistencyReport();

			assertEquals(ConsistencyState.CONSISTENT, report.state());
		}

		@Test
		@DisplayName("addRecord sets dirty flag in transactional context")
		void shouldSetDirtyFlagOnAdd() {
			final InvertedIndex index = new InvertedIndex(
				FilterIndex.NO_NORMALIZATION, Comparator.naturalOrder()
			);
			index.resetDirty();

			// the committed copy should be a new instance because dirty was set to true
			assertStateAfterCommit(
				index,
				original -> original.addRecord(1, 10),
				Assertions::assertNotSame
			);
		}

		@Test
		@DisplayName("removeRecord sets dirty flag even for no-op removal")
		void shouldSetDirtyFlagOnNoOpRemoval() {
			final InvertedIndex index = new InvertedIndex(
				FilterIndex.NO_NORMALIZATION, Comparator.naturalOrder()
			);
			index.addRecord(1, 10);
			index.resetDirty();

			// dirty was set, so committed should be new instance
			assertStateAfterCommit(
				index,
				original -> {
					// remove a record ID that does not exist in any bucket
					original.removeRecord(999, 77);
				},
				Assertions::assertNotSame
			);
		}

		@Test
		@DisplayName("T5: deep-wise atomicity -- commit propagates nested changes")
		void shouldCommitNestedChangesAtomically() {
			assertStateAfterCommit(
				InvertedIndexTest.this.tested,
				original -> {
					original.addRecord(5, 99);
					original.addRecord(100, 50);
					original.removeRecord(10, 3);
				},
				(original, committed) -> {
					// original is unchanged
					assertArrayEquals(
						new ValueToRecordBitmap[]{
							new ValueToRecordBitmap(5, 1, 20),
							new ValueToRecordBitmap(10, 3),
							new ValueToRecordBitmap(15, 2, 4),
							new ValueToRecordBitmap(20, 5)
						},
						original.getValueToRecordBitmap()
					);

					// committed has all nested changes applied
					final ValueToRecordBitmap[] committedBuckets =
						committed.getValueToRecordBitmap();

					// bucket for value 5 should have record 99 added
					assertEquals(5, committedBuckets[0].getValue());
					assertArrayEquals(
						new int[]{1, 20, 99},
						committedBuckets[0].getRecordIds().getArray()
					);

					// bucket for value 10 should be gone (removed)
					assertFalse(committed.contains(10));

					// bucket for value 100 should be new
					assertTrue(committed.contains(100));
				}
			);
		}

		@Test
		@DisplayName("toString returns expected format")
		void shouldReturnExpectedToStringFormat() {
			final String result = InvertedIndexTest.this.tested.toString();

			assertTrue(
				result.startsWith("InvertedIndex{"),
				"toString should start with 'InvertedIndex{'"
			);
			assertTrue(
				!result.isEmpty() && result.charAt(result.length() - 1) == '}',
				"toString should end with '}'"
			);
			assertTrue(
				result.contains("points="),
				"toString should contain 'points='"
			);
		}

		@Test
		@DisplayName("equals: same buckets with different dirty state are equal")
		void shouldBeEqualRegardlessOfDirtyState() {
			final InvertedIndex index1 = new InvertedIndex(
				new ValueToRecordBitmap[]{
					new ValueToRecordBitmap(1, 10),
					new ValueToRecordBitmap(2, 20)
				},
				FilterIndex.NO_NORMALIZATION,
				Comparator.naturalOrder()
			);
			final InvertedIndex index2 = new InvertedIndex(
				new ValueToRecordBitmap[]{
					new ValueToRecordBitmap(1, 10),
					new ValueToRecordBitmap(2, 20)
				},
				FilterIndex.NO_NORMALIZATION,
				Comparator.naturalOrder()
			);

			// one dirty, one not — should still be equal (dirty excluded)
			index1.addRecord(99, 1);
			index1.removeRecord(99, 1);

			assertEquals(index1, index2);
			assertEquals(index1.hashCode(), index2.hashCode());
		}

		@Test
		@DisplayName("hashCode: structurally identical indexes have same hashCode")
		void shouldHaveSameHashCodeForIdenticalIndexes() {
			final InvertedIndex index1 = new InvertedIndex(
				new ValueToRecordBitmap[]{
					new ValueToRecordBitmap(5, 1, 20),
					new ValueToRecordBitmap(10, 3)
				},
				FilterIndex.NO_NORMALIZATION,
				Comparator.naturalOrder()
			);
			final InvertedIndex index2 = new InvertedIndex(
				new ValueToRecordBitmap[]{
					new ValueToRecordBitmap(5, 1, 20),
					new ValueToRecordBitmap(10, 3)
				},
				FilterIndex.NO_NORMALIZATION,
				Comparator.naturalOrder()
			);

			assertEquals(index1.hashCode(), index2.hashCode());
		}

	}

	@Nested
	@DisplayName("Generational proof tests")
	class GenerationalTest {

		@Test
		@DisplayName("Fixed generational scenario with known operations produces correct result")
		void shouldGenerationalTestPass() {
			final InvertedIndex histogram = new InvertedIndex(FilterIndex.NO_NORMALIZATION, Comparator.naturalOrder());
			histogram.addRecord(64L, 36, 47);
			histogram.addRecord(0L, 10);
			histogram.addRecord(65L, 90);
			histogram.addRecord(2L, 89);
			histogram.addRecord(67L, 9);
			histogram.addRecord(4L, 31, 22);
			histogram.addRecord(5L, 87);
			histogram.addRecord(6L, 5);
			histogram.addRecord(7L, 40);
			histogram.addRecord(74L, 7);
			histogram.addRecord(10L, 54);
			histogram.addRecord(12L, 16);
			histogram.addRecord(76L, 97);
			histogram.addRecord(77L, 56);
			histogram.addRecord(13L, 82);
			histogram.addRecord(15L, 67);
			histogram.addRecord(16L, 55);
			histogram.addRecord(82L, 32);
			histogram.addRecord(18L, 53, 76);
			histogram.addRecord(22L, 45, 37);
			histogram.addRecord(87L, 94, 83);
			histogram.addRecord(88L, 46, 44);
			histogram.addRecord(25L, 99);
			histogram.addRecord(26L, 98, 49);
			histogram.addRecord(92L, 0);
			histogram.addRecord(93L, 1);
			histogram.addRecord(31L, 57);
			histogram.addRecord(95L, 85);
			histogram.addRecord(97L, 66);
			histogram.addRecord(41L, 11);
			histogram.addRecord(44L, 51);
			histogram.addRecord(46L, 81, 3, 41);
			histogram.addRecord(49L, 26);
			histogram.addRecord(51L, 96);
			histogram.addRecord(54L, 8);
			histogram.addRecord(56L, 34);
			histogram.addRecord(57L, 62);
			histogram.addRecord(61L, 78);

			assertStateAfterCommit(
				histogram,
				original -> {
					histogram.removeRecord(65L, 90);
					histogram.removeRecord(51L, 96);
					histogram.removeRecord(22L, 37);
					histogram.addRecord(0L, 75);
					histogram.removeRecord(7L, 40);
					histogram.removeRecord(26L, 49);
					histogram.removeRecord(0L, 75);
					histogram.addRecord(92L, 71);
					histogram.addRecord(31L, 88);
					histogram.addRecord(16L, 59);
					histogram.addRecord(93L, 70);
					histogram.addRecord(74L, 84);
					histogram.removeRecord(64L, 47);
					histogram.addRecord(85L, 69);
					histogram.addRecord(78L, 28);
					histogram.addRecord(71L, 40);
					histogram.addRecord(37L, 43);
					histogram.removeRecord(97L, 66);
					histogram.addRecord(9L, 50);
					histogram.removeRecord(67L, 9);
					histogram.addRecord(45L, 73);
					histogram.removeRecord(13L, 82);
					histogram.removeRecord(92L, 0);
					histogram.removeRecord(93L, 1);
					histogram.addRecord(67L, 17);
					histogram.removeRecord(77L, 56);
					histogram.addRecord(66L, 23);
					histogram.addRecord(98L, 56);
					histogram.addRecord(29L, 48);
					histogram.removeRecord(88L, 44);
					histogram.addRecord(75L, 49);
					histogram.removeRecord(31L, 57);
					histogram.removeRecord(5L, 87);
					histogram.addRecord(65L, 64);
					histogram.removeRecord(71L, 40);
					histogram.removeRecord(4L, 22);
					histogram.removeRecord(61L, 78);
					histogram.addRecord(11L, 12);
					histogram.removeRecord(46L, 81);
					histogram.addRecord(0L, 2);
					histogram.addRecord(42L, 15);
					histogram.addRecord(37L, 25);
					histogram.removeRecord(75L, 49);
					histogram.removeRecord(54L, 8);
					histogram.addRecord(74L, 61);
					histogram.removeRecord(37L, 25);
					histogram.addRecord(16L, 30);
					histogram.addRecord(96L, 72);
					histogram.addRecord(65L, 39);
					histogram.removeRecord(18L, 53);
					histogram.removeRecord(56L, 34);
					histogram.removeRecord(45L, 73);
					histogram.removeRecord(0L, 2);
					histogram.removeRecord(95L, 85);
					histogram.addRecord(85L, 78);
					histogram.addRecord(80L, 18);
					histogram.addRecord(88L, 8);
					histogram.removeRecord(74L, 84);
					histogram.addRecord(96L, 1);
					histogram.addRecord(54L, 38);
					histogram.addRecord(33L, 93);
					histogram.removeRecord(16L, 59);
					histogram.removeRecord(57L, 62);
					histogram.addRecord(64L, 60);
					histogram.addRecord(94L, 75);
					histogram.removeRecord(25L, 99);
					histogram.removeRecord(37L, 43);
					histogram.removeRecord(42L, 15);
					histogram.removeRecord(10L, 54);
					histogram.removeRecord(85L, 78);
					histogram.addRecord(19L, 2);
					histogram.addRecord(81L, 90);
					histogram.addRecord(21L, 95);
					histogram.removeRecord(64L, 60);
					histogram.addRecord(87L, 42);
					histogram.removeRecord(46L, 41);
					histogram.removeRecord(82L, 32);
					histogram.removeRecord(74L, 61);
					histogram.addRecord(42L, 73);
					histogram.removeRecord(78L, 28);
					histogram.removeRecord(16L, 30);
					histogram.removeRecord(98L, 56);
					histogram.addRecord(64L, 47);
					histogram.removeRecord(87L, 83);
					histogram.removeRecord(42L, 73);
					histogram.removeRecord(22L, 45);
					histogram.addRecord(35L, 19);
					histogram.removeRecord(81L, 90);
					histogram.removeRecord(54L, 38);
					histogram.addRecord(64L, 60);
				},
				(original, committed) -> {
					final int[] expected = {1, 2, 3, 5, 7, 8, 10, 11, 12, 16, 17, 18, 19, 23, 26, 31, 36, 39, 42, 46, 47, 48, 50, 51, 55, 60, 64, 67, 69, 70, 71, 72, 75, 76, 88, 89, 93, 94, 95, 97, 98};
					assertArrayEquals(
						expected,
						committed.getSortedRecords().getRecordIds().getArray(),
						"\nExpected: " + Arrays.toString(expected) + "\n" +
							"Actual:   " + Arrays.toString(committed.getSortedRecords().getRecordIds().getArray()) + "\n"
					);
				}
			);
		}

	}

}
